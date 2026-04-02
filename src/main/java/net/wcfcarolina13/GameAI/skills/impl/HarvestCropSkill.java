package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Scans a radius around the bot for fully mature crops on farmland
 * and carefully harvests them, picking up drops without trampling the soil.
 */
public class HarvestCropSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("harvest-crop-skill");

    private static final int SCAN_RADIUS = 16;
    private static final int SCAN_VERTICAL = 4;
    private static final int ACTION_DELAY_MS = 150;

    /** Crop blocks that grow on farmland and can be checked for maturity via CropBlock. */
    private static final Set<Block> FARMLAND_CROPS = Set.of(
            Blocks.WHEAT,
            Blocks.BEETROOTS,
            Blocks.CARROTS,
            Blocks.POTATOES
    );

    @Override
    public String name() {
        return "harvest";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No bot player available.");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("Not in a server world.");
        }

        List<BlockPos> matureCrops = findMatureCrops(world, bot.getBlockPos());
        if (matureCrops.isEmpty()) {
            return SkillExecutionResult.failure("No mature crops nearby.");
        }

        // Sort nearest first to minimize walking
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        matureCrops.sort(Comparator.comparingDouble(p -> botPos.squaredDistanceTo(Vec3d.ofCenter(p))));

        int harvested = 0;
        try {
            for (BlockPos cropPos : matureCrops) {
                abortIfRequested(bot);

                // Re-verify crop is still mature (may have been harvested by another entity)
                BlockState state = world.getBlockState(cropPos);
                if (!isMatureFarmlandCrop(state)) continue;

                // Territory check on the crop block
                if (!isMutationAuthorized(bot, world, cropPos)) continue;

                // The farmland is one block below the crop
                BlockPos farmlandPos = cropPos.down();

                // Find a non-farmland standing spot; if none, sneak on the farmland
                BlockPos stand = findStandingSpot(world, farmlandPos);
                boolean sneak = false;
                if (stand == null) {
                    stand = farmlandPos;
                    sneak = true;
                }

                BotActions.sneak(bot, sneak);
                moveTo(source, bot, stand.up());
                abortIfRequested(bot);

                LookController.faceBlock(bot, cropPos);
                sleep(ACTION_DELAY_MS);

                // Break the crop block using MiningTool (preserves held item)
                try {
                    String result = MiningTool.mineBlock(bot, cropPos, true).get(8, TimeUnit.SECONDS);
                    if (result != null && result.toLowerCase(Locale.ROOT).contains("complete")) {
                        harvested++;
                        sleep(120);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to harvest crop at {}: {}", cropPos.toShortString(), e.getMessage());
                }
            }
        } catch (SkillAbortException e) {
            BotActions.sneak(bot, false);
            return SkillExecutionResult.failure("Harvesting interrupted after " + harvested + " crop"
                    + (harvested != 1 ? "s" : "") + ".");
        }

        BotActions.sneak(bot, false);

        // Brief pause then walk over drops to collect them
        if (harvested > 0) {
            sleep(300);
        }

        if (harvested == 0) {
            return SkillExecutionResult.failure("Could not harvest any crops.");
        }
        return SkillExecutionResult.success("Harvested " + harvested + " crop"
                + (harvested != 1 ? "s" : "") + ".");
    }

    // ── Crop detection ───────────────────────────────────────────────────

    private static List<BlockPos> findMatureCrops(ServerWorld world, BlockPos center) {
        List<BlockPos> crops = new ArrayList<>();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -SCAN_VERTICAL; dy <= SCAN_VERTICAL; dy++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (isMatureFarmlandCrop(state)) {
                        // Verify it's actually on farmland
                        if (world.getBlockState(pos.down()).isOf(Blocks.FARMLAND)) {
                            crops.add(pos);
                        }
                    }
                }
            }
        }
        return crops;
    }

    private static boolean isMatureFarmlandCrop(BlockState state) {
        if (state == null) return false;
        Block block = state.getBlock();
        if (!FARMLAND_CROPS.contains(block)) return false;
        // CropBlock.isMature() checks age == maxAge for all vanilla crop types
        if (block instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        return false;
    }

    // ── Movement helpers (same as PlantSeedsSkill) ───────────────────────

    private static void moveTo(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        abortIfRequested(bot);
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT, target, target, null, null, null);
        MovementService.execute(source, bot, plan);
    }

    private static BlockPos findStandingSpot(ServerWorld world, BlockPos target) {
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos ground = target.offset(dir);
            if (isSafeStandingGround(world, ground)) {
                return ground;
            }
        }
        return null;
    }

    private static boolean isSafeStandingGround(ServerWorld world, BlockPos ground) {
        BlockState base = world.getBlockState(ground);
        return !base.getCollisionShape(world, ground).isEmpty()
                && base.getFluidState().isEmpty()
                && !base.isOf(Blocks.FARMLAND)
                && isPassable(world, ground.up());
    }

    private static boolean isPassable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        if (state.isAir() || state.isReplaceable()) return true;
        return state.getCollisionShape(world, pos).isEmpty();
    }

    // ── Utility ──────────────────────────────────────────────────────────

    private static boolean isMutationAuthorized(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (bot == null || world == null || pos == null) return false;
        var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, pos);
        return auth.allowed();
    }

    private static void sleep(int ms) {
        if (Thread.currentThread().isInterrupted()) throw new SkillAbortException();
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkillAbortException();
        }
    }

    private static void abortIfRequested(ServerPlayerEntity bot) {
        if (bot != null && (SkillManager.shouldAbortSkill(bot) || !TaskService.hasActiveTask(bot.getUuid()))) {
            throw new SkillAbortException();
        }
    }

    private static final class SkillAbortException extends RuntimeException {
        private SkillAbortException() {
            super("harvest-crop-skill-abort");
        }
    }
}
