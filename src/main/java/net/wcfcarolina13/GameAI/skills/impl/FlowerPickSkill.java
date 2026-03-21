package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.ProtectedZoneService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class FlowerPickSkill implements Skill {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-flower-pick");
    private static final int DEFAULT_COUNT = 6;
    private static final int DEFAULT_RADIUS = 18;
    private static final int BASE_AVOID_RADIUS = 16;

    @Override
    public String name() {
        return "flowers";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No bot available.");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("World unavailable.");
        }

        int count = getIntParameter(context, "count", DEFAULT_COUNT);
        int radius = getIntParameter(context, "radius", DEFAULT_RADIUS);
        if (count <= 0) {
            return SkillExecutionResult.failure("Flower count must be positive.");
        }

        SafePositionService.SurfaceStagingCandidate staging =
                SafePositionService.findBestSurfaceStaging(world, bot.getBlockPos(), 8, true);
        BlockPos startAnchor = staging != null ? staging.pos() : bot.getBlockPos();
        BlockPos searchCenter = chooseSearchCenter(bot, world, startAnchor, radius);
        if (searchCenter != null && !bot.getBlockPos().equals(searchCenter)) {
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    searchCenter,
                    searchCenter,
                    null,
                    null,
                    null
            );
            MovementService.MovementResult move = MovementService.execute(
                    source,
                    bot,
                    plan,
                    SkillPreferences.teleportDuringSkills(bot),
                    true
            );
            if (!move.success()) {
                ChatUtils.sendSystemMessage(source, "Couldn't walk far enough from base to pick flowers.");
            }
        }

        List<BlockPos> flowers = findFlowers(world, searchCenter != null ? searchCenter : bot.getBlockPos(), radius);
        if (flowers.isEmpty()) {
            return SkillExecutionResult.failure("No flowers found away from bases.");
        }

        int picked = 0;
        for (BlockPos flower : flowers) {
            if (picked >= count) {
                break;
            }
            if (ProtectedZoneService.isProtected(flower, world, null)) {
                continue;
            }
            if (isNearHome(world, bot, flower)) {
                continue;
            }
            if (TreeDetector.isNearHumanBlocks(world, flower, 3)) {
                continue;
            }

            if (!withinReach(bot, flower)) {
                MovementService.MovementPlan plan = new MovementService.MovementPlan(
                        MovementService.Mode.DIRECT,
                        flower,
                        flower,
                        null,
                        null,
                        null
                );
                MovementService.MovementResult move = MovementService.execute(
                        source,
                        bot,
                        plan,
                        SkillPreferences.teleportDuringSkills(bot),
                        true
                );
                if (!move.success() && !withinReach(bot, flower)) {
                    continue;
                }
            }

            LookController.faceBlock(bot, flower);
            try {
                String result = MiningTool.mineBlock(bot, flower).get();
                if (result != null && result.toLowerCase().contains("complete")) {
                    picked++;
                    sleep(180L);
                }
            } catch (Exception e) {
                LOGGER.warn("Flower pick failed at {}: {}", flower.toShortString(), e.getMessage());
            }
        }

        if (picked == 0) {
            return SkillExecutionResult.failure("No flowers picked.");
        }
        return SkillExecutionResult.success("Picked " + picked + " flower" + (picked == 1 ? "" : "s") + ".");
    }

    private static List<BlockPos> findFlowers(ServerWorld world, BlockPos center, int radius) {
        List<BlockPos> out = new ArrayList<>();
        int r = Math.max(4, radius);
        for (BlockPos pos : BlockPos.iterate(center.add(-r, -2, -r), center.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.FLOWERS)) {
                continue;
            }
            out.add(pos.toImmutable());
        }
        out.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        return out;
    }

    private static BlockPos chooseSearchCenter(ServerPlayerEntity bot, ServerWorld world, BlockPos startAnchor, int radius) {
        BlockPos botPos = startAnchor != null ? startAnchor : bot.getBlockPos();
        Optional<BlockPos> base = findNearbyHome(world, bot, BASE_AVOID_RADIUS);
        if (base.isEmpty()) {
            SafePositionService.SurfaceStagingCandidate fallback =
                    SafePositionService.findBestSurfaceStaging(world, botPos, 6, true);
            return fallback != null ? fallback.pos() : botPos;
        }
        Vec3d away = new Vec3d(botPos.getX() - base.get().getX(), 0.0D, botPos.getZ() - base.get().getZ());
        if (away.lengthSquared() < 0.01D) {
            away = new Vec3d(1.0D, 0.0D, 0.0D);
        }
        Vec3d normalized = away.normalize().multiply(Math.max(8.0D, radius * 0.5));
        BlockPos rough = botPos.add((int) Math.round(normalized.x), 0, (int) Math.round(normalized.z));
        SafePositionService.SurfaceStagingCandidate staging =
                SafePositionService.findBestSurfaceStaging(world, rough, 6, true);
        return staging != null ? staging.pos() : botPos.toImmutable();
    }

    private static Optional<BlockPos> findNearbyHome(ServerWorld world, ServerPlayerEntity bot, int radius) {
        BlockPos botPos = bot.getBlockPos();
        Optional<BlockPos> bedOpt = BotHomeService.getLastSleep(bot);
        if (bedOpt.isPresent()) {
            BlockPos bed = bedOpt.get();
            if (botPos.getSquaredDistance(bed) <= (double) radius * radius) {
                return Optional.of(bed);
            }
        }
        if (world.getServer() != null) {
            for (BotHomeService.BaseEntry base : BotHomeService.listBases(world.getServer(), world)) {
                if (base.pos() != null && botPos.getSquaredDistance(base.pos()) <= (double) radius * radius) {
                    return Optional.of(base.pos());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isNearHome(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        Optional<BlockPos> bed = BotHomeService.getLastSleep(bot);
        if (bed.isPresent() && bed.get().getSquaredDistance(pos) <= (double) BASE_AVOID_RADIUS * BASE_AVOID_RADIUS) {
            return true;
        }
        if (world.getServer() != null) {
            for (BotHomeService.BaseEntry base : BotHomeService.listBases(world.getServer(), world)) {
                if (base.pos() != null && base.pos().getSquaredDistance(pos) <= (double) BASE_AVOID_RADIUS * BASE_AVOID_RADIUS) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean withinReach(ServerPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null) {
            return false;
        }
        return bot.getBlockPos().getSquaredDistance(pos) <= 20.0D;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int getIntParameter(SkillContext context, String key, int def) {
        if (context == null || key == null) {
            return def;
        }
        Object raw = context.parameters().get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
