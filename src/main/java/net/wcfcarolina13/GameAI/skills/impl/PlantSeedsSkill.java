package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scans a radius around the bot for empty farmland (tilled soil with air above)
 * and plants any seeds the bot has in inventory, nearest-first.
 */
public class PlantSeedsSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("plant-seeds-skill");

    private static final int SCAN_RADIUS = 16;
    private static final int SCAN_VERTICAL = 4;
    private static final int ACTION_DELAY_MS = 150;

    private static final List<Item> PLANTABLE_SEEDS = List.of(
            Items.WHEAT_SEEDS,
            Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.POTATO,
            Items.CARROT
    );

    @Override
    public String name() {
        return "plant";
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

        if (countSeeds(bot.getInventory()) <= 0) {
            return SkillExecutionResult.failure("No seeds in inventory.");
        }

        List<BlockPos> emptyPlots = findEmptyFarmland(world, bot.getBlockPos());
        if (emptyPlots.isEmpty()) {
            return SkillExecutionResult.failure("No empty farmland nearby.");
        }

        // Sort by distance, nearest first
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        emptyPlots.sort(Comparator.comparingDouble(p -> botPos.squaredDistanceTo(Vec3d.ofCenter(p))));

        int planted = 0;
        try {
            for (BlockPos plot : emptyPlots) {
                abortIfRequested(bot);

                if (countSeeds(bot.getInventory()) <= 0) {
                    LOGGER.info("Out of seeds after planting {}", planted);
                    break;
                }

                // Re-verify plot is still empty farmland
                if (!world.getBlockState(plot).isOf(Blocks.FARMLAND)) continue;
                if (!world.isAir(plot.up())) continue;

                // Territory check
                if (!isMutationAuthorized(bot, world, plot.up())) continue;

                // Find a non-farmland standing spot; if none, sneak on the farmland
                BlockPos stand = findStandingSpot(world, plot);
                boolean sneak = false;
                if (stand == null) {
                    stand = plot;
                    sneak = true;
                }

                BotActions.sneak(bot, sneak);
                moveTo(source, bot, stand.up());
                abortIfRequested(bot);

                int seedSlot = ensureAnySeedHotbar(bot);
                if (seedSlot < 0) {
                    LOGGER.warn("Seeds disappeared while planting.");
                    break;
                }
                selectHotbarSlot(bot, seedSlot);

                LookController.faceBlock(bot, plot);
                sleep(ACTION_DELAY_MS);

                BlockHitResult hit = new BlockHitResult(
                        Vec3d.ofCenter(plot).add(0, 0.5, 0),
                        Direction.UP,
                        plot,
                        false
                );

                ActionResult result = bot.interactionManager.interactBlock(
                        bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);

                if (result.isAccepted()) {
                    bot.swingHand(Hand.MAIN_HAND, true);
                    planted++;
                    sleep(120);
                }
            }
        } catch (SkillAbortException e) {
            BotActions.sneak(bot, false);
            return SkillExecutionResult.failure("Planting interrupted after " + planted + " seeds.");
        }

        BotActions.sneak(bot, false);

        if (planted == 0) {
            return SkillExecutionResult.failure("Could not plant any seeds.");
        }
        return SkillExecutionResult.success("Planted " + planted + " seed" + (planted != 1 ? "s" : "") + ".");
    }

    // ── Farmland scanning ────────────────────────────────────────────────

    private static List<BlockPos> findEmptyFarmland(ServerWorld world, BlockPos center) {
        List<BlockPos> plots = new ArrayList<>();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -SCAN_VERTICAL; dy <= SCAN_VERTICAL; dy++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (world.getBlockState(pos).isOf(Blocks.FARMLAND) && world.isAir(pos.up())) {
                        plots.add(pos);
                    }
                }
            }
        }
        return plots;
    }

    // ── Movement helpers ─────────────────────────────────────────────────

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

    // ── Inventory helpers ────────────────────────────────────────────────

    private static int countSeeds(PlayerInventory inventory) {
        int count = 0;
        for (Item seed : PLANTABLE_SEEDS) {
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isOf(seed)) count += stack.getCount();
            }
        }
        return count;
    }

    private static int ensureAnySeedHotbar(ServerPlayerEntity bot) {
        PlayerInventory inv = bot.getInventory();
        // Check hotbar first
        for (Item seed : PLANTABLE_SEEDS) {
            for (int i = 0; i < 9; i++) {
                if (!inv.getStack(i).isEmpty() && inv.getStack(i).isOf(seed)) return i;
            }
        }
        // Move from main inventory to hotbar
        for (Item seed : PLANTABLE_SEEDS) {
            for (int i = 9; i < inv.size(); i++) {
                if (!inv.getStack(i).isEmpty() && inv.getStack(i).isOf(seed)) {
                    int target = findEmptyHotbarSlot(inv);
                    if (target == -1) target = inv.getSelectedSlot();
                    swapStacks(inv, i, target);
                    return target;
                }
            }
        }
        return -1;
    }

    private static int findEmptyHotbarSlot(PlayerInventory inventory) {
        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private static void swapStacks(PlayerInventory inventory, int a, int b) {
        if (a == b) return;
        ItemStack first = inventory.getStack(a);
        ItemStack second = inventory.getStack(b);
        inventory.setStack(a, second);
        inventory.setStack(b, first);
        inventory.markDirty();
    }

    private static void selectHotbarSlot(ServerPlayerEntity bot, int slot) {
        if (slot < 0 || slot >= 9) return;
        PlayerInventory inventory = bot.getInventory();
        inventory.setSelectedSlot(slot);
        bot.setStackInHand(Hand.MAIN_HAND, inventory.getStack(slot));
        inventory.markDirty();
        bot.playerScreenHandler.syncState();
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
            super("plant-seeds-skill-abort");
        }
    }
}
