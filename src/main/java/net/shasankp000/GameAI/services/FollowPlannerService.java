package net.shasankp000.GameAI.services;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shasankp000.GameAI.BotEventHandler;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static net.shasankp000.GameAI.services.FollowStateService.*;

/**
 * Stage-2 refactor: follow path planning extracted from BotEventHandler.
 *
 * <p>Owns async snapshot capture + waypoint planning and writes results into {@link FollowStateService}
 * only if the bot is still in follow mode and the target/goal is still current.</p>
 */
public final class FollowPlannerService {

    private FollowPlannerService() {}

    public static void requestPlanToTarget(Logger logger,
                                           ServerPlayerEntity bot,
                                           ServerPlayerEntity target,
                                           boolean force,
                                           String reason) {
        if (bot == null || target == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            FollowDebugService.maybeLogPlanSkip(logger, bot.getUuid(), "skip: no server (reason=" + reason + ")");
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld botWorld) || !(target.getEntityWorld() instanceof ServerWorld targetWorld)) {
            FollowDebugService.maybeLogPlanSkip(logger, bot.getUuid(), "skip: non-server world (reason=" + reason + ")");
            return;
        }
        if (botWorld != targetWorld) {
            FollowDebugService.maybeLogPlanSkip(logger, bot.getUuid(), "skip: world mismatch (reason=" + reason + ")");
            return;
        }

        UUID botId = bot.getUuid();
        UUID targetId = target.getUuid();
        long now = System.currentTimeMillis();
        long last = FOLLOW_LAST_PATH_PLAN_MS.getOrDefault(botId, -1L);
        long minInterval = force ? Math.min(650L, FollowPathService.PLAN_COOLDOWN_MS) : FollowPathService.PLAN_COOLDOWN_MS;
        if (last >= 0 && (now - last) < minInterval) {
            FollowDebugService.maybeLogPlanSkip(logger, botId, "skip: cooldown (" + (now - last) + "ms, reason=" + reason + ")");
            return;
        }
        if (FOLLOW_PATH_INFLIGHT.containsKey(botId)) {
            FollowDebugService.maybeLogPlanSkip(logger, botId, "skip: inflight (reason=" + reason + ")");
            return;
        }
        BlockPos targetPos = target.getBlockPos().toImmutable();
        BlockPos lastTarget = FOLLOW_LAST_PATH_TARGET.get(botId);
        if (!force && lastTarget != null && lastTarget.getSquaredDistance(targetPos) <= 4.0D
                && last >= 0 && (now - last) < FollowPathService.PLAN_COOLDOWN_MS * 2) {
            FollowDebugService.maybeLogPlanSkip(logger, botId, "skip: same target (reason=" + reason + ")");
            return;
        }
        FOLLOW_LAST_PATH_PLAN_MS.put(botId, now);
        FOLLOW_LAST_PATH_TARGET.put(botId, targetPos);

        RegistryKey<World> worldKey = botWorld.getRegistryKey();

        long lastLog = FOLLOW_LAST_PATH_LOG_MS.getOrDefault(botId, -1L);
        if (logger != null && (lastLog < 0 || (now - lastLog) >= 5_000L)) {
            FOLLOW_LAST_PATH_LOG_MS.put(botId, now);
            logger.info("Follow path planning start: bot={} botPos={} target={} targetPos={} canSee={} force={} reason={}",
                    bot.getName().getString(),
                    bot.getBlockPos().toShortString(),
                    target.getName().getString(),
                    target.getBlockPos().toShortString(),
                    bot.canSee(target),
                    force,
                    reason == null ? "" : reason);
        }

        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            try {
                CompletableFuture<FollowPathService.FollowSnapshot> snapFuture = new CompletableFuture<>();
                server.execute(() -> {
                    try {
                        ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                        ServerPlayerEntity liveTarget = server.getPlayerManager().getPlayer(targetId);
                        ServerWorld world = server.getWorld(worldKey);
                        if (liveBot == null || liveTarget == null || world == null) {
                            snapFuture.complete(null);
                            return;
                        }
                        if (liveBot.getEntityWorld() != world || liveTarget.getEntityWorld() != world) {
                            snapFuture.complete(null);
                            return;
                        }
                        snapFuture.complete(FollowPathService.capture(world, liveBot.getBlockPos(), liveTarget.getBlockPos(), false));
                    } catch (Throwable t) {
                        snapFuture.complete(null);
                    }
                });

                FollowPathService.FollowSnapshot snapshot;
                try {
                    snapshot = snapFuture.get(900, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    snapshot = null;
                }
                if (snapshot == null) {
                    FollowDebugService.maybeLogPlanSkip(logger, botId, "skip: snapshot null (reason=" + (reason == null ? "" : reason) + ")");
                    return;
                }

                BlockPos avoidDoor = null;
                long lastDoorMs = FOLLOW_LAST_DOOR_CROSS_MS.getOrDefault(botId, -1L);
                if (lastDoorMs >= 0 && (System.currentTimeMillis() - lastDoorMs) < 5_000L) {
                    avoidDoor = FOLLOW_LAST_DOOR_BASE.get(botId);
                }

                List<BlockPos> waypoints = FollowPathService.planWaypoints(snapshot, avoidDoor);
                if (waypoints.isEmpty()) {
                    CompletableFuture<FollowPathService.FollowSnapshot> escapeSnapFuture = new CompletableFuture<>();
                    server.execute(() -> {
                        try {
                            ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                            ServerPlayerEntity liveTarget = server.getPlayerManager().getPlayer(targetId);
                            ServerWorld world = server.getWorld(worldKey);
                            if (liveBot == null || world == null || liveBot.getEntityWorld() != world) {
                                escapeSnapFuture.complete(null);
                                return;
                            }
                            BlockPos b = liveBot.getBlockPos();
                            BlockPos g = liveTarget != null ? liveTarget.getBlockPos() : b;
                            FollowPathService.FollowSnapshot attempt = FollowPathService.capture(world, b, g, true);
                            if (attempt == null) {
                                attempt = FollowPathService.capture(world, b, b, true);
                            }
                            escapeSnapFuture.complete(attempt);
                        } catch (Throwable t) {
                            escapeSnapFuture.complete(null);
                        }
                    });

                    FollowPathService.FollowSnapshot escapeSnapshot;
                    try {
                        escapeSnapshot = escapeSnapFuture.get(900, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        escapeSnapshot = null;
                    }
                    if (escapeSnapshot != null) {
                        waypoints = FollowPathService.planEscapeWaypoints(escapeSnapshot, avoidDoor);
                    }
                }

                if (waypoints.isEmpty()) {
                    long lastFail = FOLLOW_LAST_PATH_FAIL_LOG_MS.getOrDefault(botId, -1L);
                    long nowFail = System.currentTimeMillis();
                    if (logger != null && (lastFail < 0 || (nowFail - lastFail) >= 4_500L)) {
                        FOLLOW_LAST_PATH_FAIL_LOG_MS.put(botId, nowFail);
                        logger.info("Follow path planning: no path found (bot={} target={})", botId, targetId);
                    }
                    return;
                }

                final List<BlockPos> plannedWaypoints = List.copyOf(waypoints);
                server.execute(() -> {
                    ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                    ServerPlayerEntity liveTarget = server.getPlayerManager().getPlayer(targetId);
                    if (liveBot == null) {
                        return;
                    }
                    BotCommandStateService.State st = BotCommandStateService.stateFor(liveBot);
                    if (st == null || st.mode != BotEventHandler.Mode.FOLLOW || st.followTargetUuid == null || !st.followTargetUuid.equals(targetId)) {
                        return;
                    }
                    FOLLOW_WAYPOINTS.put(botId, new ArrayDeque<>(plannedWaypoints));
                    FOLLOW_DOOR_PLAN.remove(botId);
                    FOLLOW_LAST_DISTANCE_SQ.remove(botId);
                    FOLLOW_STAGNANT_TICKS.remove(botId);
                    if (logger != null && plannedWaypoints.size() > 0) {
                        logger.info("Follow planned {} waypoint(s): bot={} target={} first={}",
                                plannedWaypoints.size(),
                                liveBot.getName().getString(),
                                liveTarget != null ? liveTarget.getName().getString() : targetId.toString(),
                                plannedWaypoints.get(0).toShortString());
                    }
                });
            } catch (Throwable t) {
                if (logger != null) {
                    logger.debug("Follow path plan failed: {}", t.getMessage());
                }
            }
        });

        FOLLOW_PATH_INFLIGHT.put(botId, task);
        task.whenComplete((ignored, err) -> server.execute(() -> FOLLOW_PATH_INFLIGHT.remove(botId)));
    }

    public static void requestPlanToGoal(Logger logger,
                                         ServerPlayerEntity bot,
                                         BlockPos goal,
                                         boolean force,
                                         String reason) {
        if (bot == null || goal == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            FollowDebugService.maybeLogPlanSkip(logger, bot.getUuid(), "skip: no server (reason=" + reason + ")");
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld botWorld)) {
            FollowDebugService.maybeLogPlanSkip(logger, bot.getUuid(), "skip: non-server world (reason=" + reason + ")");
            return;
        }

        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = FOLLOW_LAST_PATH_PLAN_MS.getOrDefault(botId, -1L);
        long minInterval = force ? Math.min(650L, FollowPathService.PLAN_COOLDOWN_MS) : FollowPathService.PLAN_COOLDOWN_MS;
        if (last >= 0 && (now - last) < minInterval) {
            FollowDebugService.maybeLogPlanSkip(logger, botId, "skip: cooldown (" + (now - last) + "ms, reason=" + reason + ")");
            return;
        }
        if (FOLLOW_PATH_INFLIGHT.containsKey(botId)) {
            FollowDebugService.maybeLogPlanSkip(logger, botId, "skip: inflight (reason=" + reason + ")");
            return;
        }

        BlockPos goalPos = goal.toImmutable();
        BlockPos lastTarget = FOLLOW_LAST_PATH_TARGET.get(botId);
        if (!force && lastTarget != null && lastTarget.getSquaredDistance(goalPos) <= 4.0D
                && last >= 0 && (now - last) < FollowPathService.PLAN_COOLDOWN_MS * 2) {
            FollowDebugService.maybeLogPlanSkip(logger, botId, "skip: same goal (reason=" + reason + ")");
            return;
        }
        FOLLOW_LAST_PATH_PLAN_MS.put(botId, now);
        FOLLOW_LAST_PATH_TARGET.put(botId, goalPos);

        RegistryKey<World> worldKey = botWorld.getRegistryKey();

        long lastLog = FOLLOW_LAST_PATH_LOG_MS.getOrDefault(botId, -1L);
        if (logger != null && (lastLog < 0 || (now - lastLog) >= 5_000L)) {
            FOLLOW_LAST_PATH_LOG_MS.put(botId, now);
            logger.info("Follow path planning start (goal): bot={} botPos={} goal={} force={} reason={}",
                    bot.getName().getString(),
                    bot.getBlockPos().toShortString(),
                    goalPos.toShortString(),
                    force,
                    reason == null ? "" : reason);
        }

        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            try {
                CompletableFuture<FollowPathService.FollowSnapshot> snapFuture = new CompletableFuture<>();
                server.execute(() -> {
                    try {
                        ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                        ServerWorld world = server.getWorld(worldKey);
                        if (liveBot == null || world == null || liveBot.getEntityWorld() != world) {
                            snapFuture.complete(null);
                            return;
                        }
                        snapFuture.complete(FollowPathService.capture(world, liveBot.getBlockPos(), goalPos, false));
                    } catch (Throwable t) {
                        snapFuture.complete(null);
                    }
                });

                FollowPathService.FollowSnapshot snapshot;
                try {
                    snapshot = snapFuture.get(900, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    snapshot = null;
                }
                if (snapshot == null) {
                    FollowDebugService.maybeLogPlanSkip(logger, botId, "skip: snapshot null (reason=" + (reason == null ? "" : reason) + ")");
                    return;
                }

                BlockPos avoidDoor = null;
                long lastDoorMs = FOLLOW_LAST_DOOR_CROSS_MS.getOrDefault(botId, -1L);
                if (lastDoorMs >= 0 && (System.currentTimeMillis() - lastDoorMs) < 5_000L) {
                    avoidDoor = FOLLOW_LAST_DOOR_BASE.get(botId);
                }

                List<BlockPos> waypoints = FollowPathService.planWaypoints(snapshot, avoidDoor);
                if (waypoints.isEmpty()) {
                    CompletableFuture<FollowPathService.FollowSnapshot> escapeSnapFuture = new CompletableFuture<>();
                    server.execute(() -> {
                        try {
                            ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                            ServerWorld world = server.getWorld(worldKey);
                            if (liveBot == null || world == null || liveBot.getEntityWorld() != world) {
                                escapeSnapFuture.complete(null);
                                return;
                            }
                            BlockPos b = liveBot.getBlockPos();
                            FollowPathService.FollowSnapshot attempt = FollowPathService.capture(world, b, goalPos, true);
                            if (attempt == null) {
                                attempt = FollowPathService.capture(world, b, b, true);
                            }
                            escapeSnapFuture.complete(attempt);
                        } catch (Throwable t) {
                            escapeSnapFuture.complete(null);
                        }
                    });

                    FollowPathService.FollowSnapshot escapeSnapshot;
                    try {
                        escapeSnapshot = escapeSnapFuture.get(900, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        escapeSnapshot = null;
                    }
                    if (escapeSnapshot != null) {
                        waypoints = FollowPathService.planEscapeWaypoints(escapeSnapshot, avoidDoor);
                    }
                }

                if (waypoints.isEmpty()) {
                    long lastFail = FOLLOW_LAST_PATH_FAIL_LOG_MS.getOrDefault(botId, -1L);
                    long nowFail = System.currentTimeMillis();
                    if (logger != null && (lastFail < 0 || (nowFail - lastFail) >= 4_500L)) {
                        FOLLOW_LAST_PATH_FAIL_LOG_MS.put(botId, nowFail);
                        logger.info("Follow path planning: no path found (bot={} goal={})", botId, goalPos.toShortString());
                    }
                    return;
                }

                final List<BlockPos> plannedWaypoints = List.copyOf(waypoints);
                server.execute(() -> {
                    ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                    if (liveBot == null) {
                        return;
                    }
                    BotCommandStateService.State st = BotCommandStateService.stateFor(liveBot);
                    if (st == null || st.mode != BotEventHandler.Mode.FOLLOW || st.followFixedGoal == null || !st.followFixedGoal.equals(goalPos)) {
                        return;
                    }
                    FOLLOW_WAYPOINTS.put(botId, new ArrayDeque<>(plannedWaypoints));
                    FOLLOW_DOOR_PLAN.remove(botId);
                    FOLLOW_LAST_DISTANCE_SQ.remove(botId);
                    FOLLOW_STAGNANT_TICKS.remove(botId);
                    if (logger != null && plannedWaypoints.size() > 0) {
                        logger.info("Follow planned {} waypoint(s): bot={} goal={} first={}",
                                plannedWaypoints.size(),
                                liveBot.getName().getString(),
                                goalPos.toShortString(),
                                plannedWaypoints.get(0).toShortString());
                    }
                });
            } catch (Throwable t) {
                if (logger != null) {
                    logger.debug("Follow path plan (goal) failed: {}", t.getMessage());
                }
            }
        });

        FOLLOW_PATH_INFLIGHT.put(botId, task);
        task.whenComplete((ignored, err) -> server.execute(() -> FOLLOW_PATH_INFLIGHT.remove(botId)));
    }
}

