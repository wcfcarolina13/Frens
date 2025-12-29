package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.services.BotRescueService;
import net.shasankp000.GameAI.services.BotStuckService;
import net.shasankp000.GameAI.services.SafePositionService;
import net.shasankp000.GameAI.services.SkillResumeService;
import net.shasankp000.GameAI.services.TaskService;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * Emergency recovery helpers used by the hovel builder.
 *
 * Key design constraints (per user request):
 * - Do NOT enter "come" / follow-fixed-goal behaviors from hovel logic.
 * - If the bot is suffocating/inside-wall while building, snap it to a nearby safe tile.
 * - If the bot falls well below the build site and there is no nearby sky light, PAUSE and ask for help.
 */
final class HovelEmergencyRegroupService {

    // "Deep" heuristic: more than 4 blocks below the build site's Y.
    private static final int DEEP_UNDERGROUND_BELOW_BUILD_DELTA_Y = 4;
    private static final int DEEP_UNDERGROUND_SURFACE_DELTA_Y = 10;
    private static final int DEEP_UNDERGROUND_NO_SKYLIGHT_RADIUS = 12;
    private static final long DEEP_UNDERGROUND_PAUSE_COOLDOWN_MS = 60_000L;
    private static final Map<UUID, Long> LAST_DEEP_UNDERGROUND_REGROUP_MS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long IN_WALL_PERSIST_MS = 900L;
    private static final long IN_WALL_REGROUP_COOLDOWN_MS = 45_000L;
    private static final Map<UUID, Long> IN_WALL_SINCE_MS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_IN_WALL_REGROUP_MS = new java.util.concurrent.ConcurrentHashMap<>();

    private HovelEmergencyRegroupService() {
    }

    static boolean triggerDeepUndergroundRegroupIfNeeded(Logger logger,
                                                        ServerCommandSource source,
                                                        ServerPlayerEntity bot,
                                                        String where,
                                                        BlockPos activeBuildCenter,
                                                        int activeRadius,
                                                        ServerCommandSource stageMessageSource) {
        if (source == null || bot == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (SkillManager.shouldAbortSkill(bot)) {
            return false;
        }

        // Only applies when we have a known surface build site.
        if (activeBuildCenter == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        UUID id = bot.getUuid();
        Long last = LAST_DEEP_UNDERGROUND_REGROUP_MS.get(id);
        if (last != null && now - last < DEEP_UNDERGROUND_PAUSE_COOLDOWN_MS) {
            return false;
        }

        BlockPos pos = bot.getBlockPos();
        int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        boolean deepByBuild = bot.getBlockY() < activeBuildCenter.getY() - DEEP_UNDERGROUND_BELOW_BUILD_DELTA_Y;
        boolean deepBySurface = bot.getBlockY() < surfaceY - DEEP_UNDERGROUND_SURFACE_DELTA_Y;
        if (!deepByBuild && !deepBySurface) {
            return false;
        }

        // Avoid false positives when legitimately inside the hovel volume (under a just-built roof).
        if (activeBuildCenter != null && activeRadius > 0) {
            int dx = Math.abs(pos.getX() - activeBuildCenter.getX());
            int dz = Math.abs(pos.getZ() - activeBuildCenter.getZ());
            boolean inFootprint = dx <= activeRadius && dz <= activeRadius;
            if (inFootprint && bot.getBlockY() >= activeBuildCenter.getY() - 2) {
                return false;
            }
        }

        // If there's nearby sky light, this is likely an open pit/ravine; let normal movement recover.
        // Only pause when there is NO sky light within a reasonable radius (cave/ravine fall).
        if (hasAnySkyLightNearby(world, pos, DEEP_UNDERGROUND_NO_SKYLIGHT_RADIUS)) {
            return false;
        }

        LAST_DEEP_UNDERGROUND_REGROUP_MS.put(id, now);

        // Preserve resumability and pause instead of aborting.
        SkillResumeService.flagManualResume(bot);

        String reason = "§cHovel paused: I fell into a cave/ravine and can't see the surface.";
        TaskService.requestPause(id, reason);
        if (stageMessageSource != null) {
            ChatUtils.sendSystemMessage(stageMessageSource,
                    "Hovel: I fell deep underground and can't find daylight (" + where + "). I'm pausing — please help me out, then run /bot resume.");
        }

        if (logger != null) {
            logger.warn("Hovel: deep-underground pause triggered at={} botPos={} surfaceY={} buildCenter={}",
                    where,
                    pos.toShortString(),
                    surfaceY,
                    activeBuildCenter.toShortString());
        }
        return true;
    }

    /**
    * Emergency recovery: if the bot is repeatedly clipping/taking IN_WALL-type damage during a surface hovel build,
    * snap to a nearby safe tile (do NOT enter come/follow).
     */
    static boolean triggerInWallRegroupIfNeeded(Logger logger,
                                                ServerCommandSource source,
                                                ServerPlayerEntity bot,
                                                String where,
                                                BlockPos activeBuildCenter,
                                                int activeRadius,
                                                ServerCommandSource stageMessageSource) {
        if (source == null || bot == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return false;
        }
        UUID id = bot.getUuid();
        if (id == null) {
            return false;
        }
        if (SkillManager.shouldAbortSkill(bot)) {
            IN_WALL_SINCE_MS.remove(id);
            return false;
        }
        if (TaskService.isInAscentMode(id)) {
            // Ascent intentionally tolerates some in-wall ticks while digging upward.
            IN_WALL_SINCE_MS.remove(id);
            return false;
        }

        long now = System.currentTimeMillis();
        Long last = LAST_IN_WALL_REGROUP_MS.get(id);
        if (last != null && now - last < IN_WALL_REGROUP_COOLDOWN_MS) {
            return false;
        }

        boolean insideWall = bot.isInsideWall();
        boolean recentObstruct = BotRescueService.tookRecentObstructDamageWindow(bot);
        DamageSource recent = bot.getRecentDamageSource();
        boolean inWallDamage = recent != null
                && recentObstruct
                && (recent.isOf(DamageTypes.IN_WALL)
                || recent.isOf(DamageTypes.FLY_INTO_WALL)
                || recent.isOf(DamageTypes.CRAMMING));

        boolean condition = insideWall || inWallDamage;
        if (!condition) {
            IN_WALL_SINCE_MS.remove(id);
            return false;
        }

        long since = IN_WALL_SINCE_MS.computeIfAbsent(id, __ -> now);
        if (now - since < IN_WALL_PERSIST_MS) {
            return false;
        }

        // Avoid false positives if the bot is legitimately in a tight, safe spot inside the footprint and
        // not actually making contact with wall blocks anymore.
        if (!insideWall && recent != null && recent.isOf(DamageTypes.IN_WALL) && !recentObstruct) {
            IN_WALL_SINCE_MS.remove(id);
            return false;
        }

        LAST_IN_WALL_REGROUP_MS.put(id, now);
        IN_WALL_SINCE_MS.remove(id);

        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld sw ? sw : null;

        BlockPos goal = null;
        if (world != null) {
            // Prefer the nearest safe tile around the bot first.
            goal = SafePositionService.findSafeNear(world, bot.getBlockPos(), 6);
        }
        if (goal == null) {
            Vec3d safe = BotStuckService.getLastSafePosition(id);
            if (safe != null && world != null) {
                goal = SafePositionService.findSafeNear(world, BlockPos.ofFloored(safe), 6);
            }
        }
        if (goal == null && activeBuildCenter != null && world != null) {
            goal = SafePositionService.findSafeNear(world, activeBuildCenter.up(), 8);
        }

        if (goal == null) {
            // If we can't find a safe place to snap to, pause and ask for help.
            SkillResumeService.flagManualResume(bot);
            TaskService.requestPause(id, "§cHovel paused: I'm stuck inside blocks and couldn't find a safe spot.");
            if (stageMessageSource != null) {
                ChatUtils.sendSystemMessage(stageMessageSource,
                        "Hovel: I'm stuck inside blocks and couldn't find a safe spot (" + where + "). I'm pausing — please help me out, then run /bot resume.");
            }
            return true;
        }

        // Snap reposition to break suffocation loops without entering follow/come.
        snapTo(bot, goal);

        if (logger != null) {
            logger.warn("Hovel: in-wall emergency snap triggered at={} botPos={} insideWall={} recentObstruct={} goal={}",
                    where,
                    bot.getBlockPos().toShortString(),
                    insideWall,
                    recentObstruct,
                    goal.toShortString());
        }
        return true;
    }

    private static boolean hasAnySkyLightNearby(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return true;
        }
        int r = Math.max(0, radius);
        int step = 2;
        int best = 0;
        for (int dx = -r; dx <= r; dx += step) {
            for (int dz = -r; dz <= r; dz += step) {
                BlockPos p = origin.add(dx, 0, dz);
                if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
                    continue;
                }
                // Probe at head height-ish; sky light at feet can be misleading near slabs/partial blocks.
                BlockPos probe = p.up(1);
                int skylight = world.getLightLevel(LightType.SKY, probe);
                if (skylight > best) {
                    best = skylight;
                    if (best > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void snapTo(ServerPlayerEntity bot, BlockPos targetFeet) {
        if (bot == null || targetFeet == null) {
            return;
        }
        Vec3d center = new Vec3d(targetFeet.getX() + 0.5, targetFeet.getY(), targetFeet.getZ() + 0.5);
        bot.refreshPositionAndAngles(center.x, center.y, center.z, bot.getYaw(), bot.getPitch());
        bot.setVelocity(Vec3d.ZERO);
        bot.velocityDirty = true;
        bot.fallDistance = 0.0F;
        BotStuckService.setLastSafePosition(bot.getUuid(), center);
    }
}
