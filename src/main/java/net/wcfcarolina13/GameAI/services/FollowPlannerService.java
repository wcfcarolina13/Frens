package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static net.wcfcarolina13.GameAI.services.FollowStateService.*;

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
        boolean stuckDriven = isStuckDrivenReason(reason);
        boolean waterStuckReason = isWaterStuckReason(reason);
        boolean unchangedPlanningOrigin = isPlanningOriginUnchanged(botId, bot.getBlockPos());
        int repeatedFirstWpStreak = repeatedFirstWaypointStreak(botId, bot.getBlockPos());
        int verticalTrapStreak = verticalTrapFirstWaypointStreak(botId, bot.getBlockPos());
        long minInterval = FollowPathService.PLAN_COOLDOWN_MS;
        if (force) {
            minInterval = Math.min(650L, minInterval);
        }
        // When we're stuck, spamming replans is expensive and usually unhelpful.
        // Let micro-unstuck nudges move the bot a few blocks, then replan.
        if (stuckDriven) {
            minInterval = Math.max(minInterval, 2_500L);
        }
        if (waterStuckReason && unchangedPlanningOrigin) {
            minInterval = Math.max(minInterval, 6_000L);
        }
        if (repeatedFirstWpStreak >= 2) {
            minInterval = Math.max(minInterval, 4_000L);
        }
        if (verticalTrapStreak >= 2) {
            minInterval = Math.max(minInterval, 6_000L);
        }
        // If we recently failed to find any path, back off even harder.
        long lastNoPath = FOLLOW_LAST_PATH_FAIL_LOG_MS.getOrDefault(botId, -1L);
        if (lastNoPath >= 0 && (now - lastNoPath) < 7_000L) {
            minInterval = Math.max(minInterval, 7_000L);
        }
        if (last >= 0 && (now - last) < minInterval) {
            maybeLogPlannerBackoff(logger, botId, now - last, minInterval, reason);
            if (waterStuckReason) {
                maybeLogWaterStuckPlanner(logger, botId, "cooldown-skip: elapsed=" + (now - last)
                        + "ms minInterval=" + minInterval
                        + " unchangedOrigin=" + unchangedPlanningOrigin
                        + " reason=" + (reason == null ? "" : reason));
            }
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
        if (waterStuckReason && unchangedPlanningOrigin) {
            maybeLogWaterStuckPlanner(logger, botId, "unchanged-origin replan: botPos=" + bot.getBlockPos().toShortString()
                    + " reason=" + (reason == null ? "" : reason));
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
                        snapFuture.complete(FollowPathService.capture(world, liveBot.getBlockPos(), liveTarget.getBlockPos(), waterStuckReason));
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

                boolean stuckMode = stuckDriven || repeatedFirstWpStreak >= 2 || verticalTrapStreak >= 2;
                int transitionRejects = 0;
                int swimTransitionRejects = 0;
                List<BlockPos> waypoints = FollowPathService.planWaypoints(snapshot, avoidDoor, stuckMode, waterStuckReason);
                transitionRejects += FollowPathService.consumeLastTransitionRejectCount();
                swimTransitionRejects += FollowPathService.consumeLastSwimTransitionRejectCount();
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
                        waypoints = FollowPathService.planEscapeWaypoints(escapeSnapshot, avoidDoor, stuckMode, waterStuckReason);
                        transitionRejects += FollowPathService.consumeLastTransitionRejectCount();
                        swimTransitionRejects += FollowPathService.consumeLastSwimTransitionRejectCount();
                    }
                }
                if (waypoints.isEmpty()) {
                    int[] fallbackTransitionRejects = new int[] { 0 };
                    int[] fallbackSwimTransitionRejects = new int[] { 0 };
                    waypoints = tryVerticalSeparationFallbackPlan(
                            logger,
                            server,
                            worldKey,
                            botId,
                            targetId,
                            avoidDoor,
                            waterStuckReason,
                            fallbackTransitionRejects,
                            fallbackSwimTransitionRejects
                    );
                    transitionRejects += fallbackTransitionRejects[0];
                    swimTransitionRejects += fallbackSwimTransitionRejects[0];
                }

                maybeLogTransitionRejects(logger, botId, transitionRejects, swimTransitionRejects, reason);

                if (waypoints.isEmpty()) {
                    long lastFail = FOLLOW_LAST_PATH_FAIL_LOG_MS.getOrDefault(botId, -1L);
                    long nowFail = System.currentTimeMillis();
                    if (logger != null && (lastFail < 0 || (nowFail - lastFail) >= 4_500L)) {
                        FOLLOW_LAST_PATH_FAIL_LOG_MS.put(botId, nowFail);
                        logger.info("[FollowAssert] planner-no-path bot={} reason={} target={}",
                                botId,
                                reason == null ? "" : reason,
                                targetId);
                        logger.info("Follow path planning: no path found (bot={} target={})", botId, targetId);
                    }
                    noteFollowNoPathAndMaybePrompt(logger, server, worldKey, botId, targetId, reason);
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
                    clearFollowNoPathState(botId);
                    updateRepeatedPlanState(logger, botId, liveBot.getBlockPos(), plannedWaypoints);
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
        boolean stuckDriven = isStuckDrivenReason(reason);
        boolean waterStuckReason = isWaterStuckReason(reason);
        boolean unchangedPlanningOrigin = isPlanningOriginUnchanged(botId, bot.getBlockPos());
        int repeatedFirstWpStreak = repeatedFirstWaypointStreak(botId, bot.getBlockPos());
        int verticalTrapStreak = verticalTrapFirstWaypointStreak(botId, bot.getBlockPos());
        long minInterval = FollowPathService.PLAN_COOLDOWN_MS;
        if (force) {
            minInterval = Math.min(650L, minInterval);
        }
        if (stuckDriven) {
            minInterval = Math.max(minInterval, 2_500L);
        }
        if (waterStuckReason && unchangedPlanningOrigin) {
            minInterval = Math.max(minInterval, 6_000L);
        }
        if (repeatedFirstWpStreak >= 2) {
            minInterval = Math.max(minInterval, 4_000L);
        }
        if (verticalTrapStreak >= 2) {
            minInterval = Math.max(minInterval, 6_000L);
        }
        long lastNoPath = FOLLOW_LAST_PATH_FAIL_LOG_MS.getOrDefault(botId, -1L);
        if (lastNoPath >= 0 && (now - lastNoPath) < 7_000L) {
            minInterval = Math.max(minInterval, 7_000L);
        }
        if (last >= 0 && (now - last) < minInterval) {
            maybeLogPlannerBackoff(logger, botId, now - last, minInterval, reason);
            if (waterStuckReason) {
                maybeLogWaterStuckPlanner(logger, botId, "cooldown-skip: elapsed=" + (now - last)
                        + "ms minInterval=" + minInterval
                        + " unchangedOrigin=" + unchangedPlanningOrigin
                        + " reason=" + (reason == null ? "" : reason));
            }
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
        if (waterStuckReason && unchangedPlanningOrigin) {
            maybeLogWaterStuckPlanner(logger, botId, "unchanged-origin replan: botPos=" + bot.getBlockPos().toShortString()
                    + " reason=" + (reason == null ? "" : reason));
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
                        snapFuture.complete(FollowPathService.capture(world, liveBot.getBlockPos(), goalPos, waterStuckReason));
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

                boolean stuckMode = stuckDriven || repeatedFirstWpStreak >= 2 || verticalTrapStreak >= 2;
                int transitionRejects = 0;
                int swimTransitionRejects = 0;
                List<BlockPos> waypoints = FollowPathService.planWaypoints(snapshot, avoidDoor, stuckMode, waterStuckReason);
                transitionRejects += FollowPathService.consumeLastTransitionRejectCount();
                swimTransitionRejects += FollowPathService.consumeLastSwimTransitionRejectCount();
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
                        waypoints = FollowPathService.planEscapeWaypoints(escapeSnapshot, avoidDoor, stuckMode, waterStuckReason);
                        transitionRejects += FollowPathService.consumeLastTransitionRejectCount();
                        swimTransitionRejects += FollowPathService.consumeLastSwimTransitionRejectCount();
                    }
                }

                maybeLogTransitionRejects(logger, botId, transitionRejects, swimTransitionRejects, reason);

                if (waypoints.isEmpty()) {
                    long lastFail = FOLLOW_LAST_PATH_FAIL_LOG_MS.getOrDefault(botId, -1L);
                    long nowFail = System.currentTimeMillis();
                    if (logger != null && (lastFail < 0 || (nowFail - lastFail) >= 4_500L)) {
                        FOLLOW_LAST_PATH_FAIL_LOG_MS.put(botId, nowFail);
                        logger.info("[FollowAssert] planner-no-path bot={} reason={} goal={}",
                                botId,
                                reason == null ? "" : reason,
                                goalPos.toShortString());
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
                    clearFollowNoPathState(botId);
                    updateRepeatedPlanState(logger, botId, liveBot.getBlockPos(), plannedWaypoints);
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

    private static void clearFollowNoPathState(UUID botId) {
        if (botId == null) {
            return;
        }
        FOLLOW_NO_PATH_STREAK.remove(botId);
        FOLLOW_LAST_NO_PATH_TICK.remove(botId);
        FOLLOW_NEXT_REGROUP_PROMPT_TICK.remove(botId);
    }

    private static List<BlockPos> tryVerticalSeparationFallbackPlan(Logger logger,
                                                                    MinecraftServer server,
                                                                    RegistryKey<World> worldKey,
                                                                    UUID botId,
                                                                    UUID targetId,
                                                                    BlockPos avoidDoor,
                                                                    boolean waterStuckReason,
                                                                    int[] transitionRejectsOut,
                                                                    int[] swimTransitionRejectsOut) {
        FollowPositions pos = captureFollowPositions(server, worldKey, botId, targetId);
        if (pos == null) {
            return List.of();
        }
        int absDeltaY = Math.abs(pos.botPos().getY() - pos.targetPos().getY());
        if (absDeltaY < 6) {
            return List.of();
        }
        for (BlockPos fallbackGoal : buildVerticalFallbackGoals(pos.botPos(), pos.targetPos())) {
            FollowPathService.FollowSnapshot snapshot = captureSnapshotForGoal(server, worldKey, botId, fallbackGoal, false);
            if (snapshot == null) {
                continue;
            }
            List<BlockPos> waypoints = FollowPathService.planWaypoints(snapshot, avoidDoor, true, waterStuckReason);
            transitionRejectsOut[0] += FollowPathService.consumeLastTransitionRejectCount();
            swimTransitionRejectsOut[0] += FollowPathService.consumeLastSwimTransitionRejectCount();
            if (waypoints.isEmpty()) {
                snapshot = captureSnapshotForGoal(server, worldKey, botId, fallbackGoal, true);
                if (snapshot == null) {
                    continue;
                }
                waypoints = FollowPathService.planWaypoints(snapshot, avoidDoor, true, waterStuckReason);
                transitionRejectsOut[0] += FollowPathService.consumeLastTransitionRejectCount();
                swimTransitionRejectsOut[0] += FollowPathService.consumeLastSwimTransitionRejectCount();
            }
            if (!waypoints.isEmpty()) {
                if (logger != null) {
                    logger.info("[FollowAssert] planner-fallback bot={} target={} fallbackGoal={} dy={}",
                            botId,
                            targetId,
                            fallbackGoal.toShortString(),
                            absDeltaY);
                }
                return waypoints;
            }
        }
        return List.of();
    }

    private static FollowPositions captureFollowPositions(MinecraftServer server,
                                                          RegistryKey<World> worldKey,
                                                          UUID botId,
                                                          UUID targetId) {
        if (server == null || worldKey == null || botId == null || targetId == null) {
            return null;
        }
        CompletableFuture<FollowPositions> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                ServerPlayerEntity liveTarget = server.getPlayerManager().getPlayer(targetId);
                ServerWorld world = server.getWorld(worldKey);
                if (liveBot == null || liveTarget == null || world == null
                        || liveBot.getEntityWorld() != world || liveTarget.getEntityWorld() != world) {
                    future.complete(null);
                    return;
                }
                future.complete(new FollowPositions(liveBot.getBlockPos().toImmutable(), liveTarget.getBlockPos().toImmutable()));
            } catch (Throwable t) {
                future.complete(null);
            }
        });
        try {
            return future.get(750, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static FollowPathService.FollowSnapshot captureSnapshotForGoal(MinecraftServer server,
                                                                           RegistryKey<World> worldKey,
                                                                           UUID botId,
                                                                           BlockPos goal,
                                                                           boolean centerOnStart) {
        if (server == null || worldKey == null || botId == null || goal == null) {
            return null;
        }
        CompletableFuture<FollowPathService.FollowSnapshot> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
                ServerWorld world = server.getWorld(worldKey);
                if (liveBot == null || world == null || liveBot.getEntityWorld() != world) {
                    future.complete(null);
                    return;
                }
                future.complete(FollowPathService.capture(world, liveBot.getBlockPos(), goal, centerOnStart));
            } catch (Throwable t) {
                future.complete(null);
            }
        });
        try {
            return future.get(750, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<BlockPos> buildVerticalFallbackGoals(BlockPos botPos, BlockPos targetPos) {
        if (botPos == null || targetPos == null) {
            return List.of();
        }
        int botY = botPos.getY();
        int goalY = targetPos.getY();
        int midY = (botY + goalY) / 2;
        // Generate fallback goals at multiple Y levels: bot level, midpoint, and goal level.
        // This lets the planner find stepping-stone paths when the bot is in a hole.
        int[] yLevels = (botY == goalY) ? new int[] { botY }
                : (midY == botY || midY == goalY) ? new int[] { botY, goalY }
                : new int[] { botY, midY, goalY };
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        for (int y : yLevels) {
            out.add(new BlockPos(targetPos.getX(), y, targetPos.getZ()));
            int[] rings = new int[] { 1, 2, 3 };
            for (int ring : rings) {
                out.add(new BlockPos(targetPos.getX() + ring, y, targetPos.getZ()));
                out.add(new BlockPos(targetPos.getX() - ring, y, targetPos.getZ()));
                out.add(new BlockPos(targetPos.getX(), y, targetPos.getZ() + ring));
                out.add(new BlockPos(targetPos.getX(), y, targetPos.getZ() - ring));
                if (ring == 2) {
                    out.add(new BlockPos(targetPos.getX() + ring, y, targetPos.getZ() + ring));
                    out.add(new BlockPos(targetPos.getX() + ring, y, targetPos.getZ() - ring));
                    out.add(new BlockPos(targetPos.getX() - ring, y, targetPos.getZ() + ring));
                    out.add(new BlockPos(targetPos.getX() - ring, y, targetPos.getZ() - ring));
                }
            }
        }
        int towardX = Integer.compare(targetPos.getX(), botPos.getX());
        int towardZ = Integer.compare(targetPos.getZ(), botPos.getZ());
        for (int y : yLevels) {
            out.add(new BlockPos(botPos.getX() + towardX * 6, y, botPos.getZ() + towardZ * 6));
            out.add(new BlockPos(botPos.getX() + towardX * 10, y, botPos.getZ() + towardZ * 10));
        }
        return new ArrayList<>(out);
    }

    private static void noteFollowNoPathAndMaybePrompt(Logger logger,
                                                       MinecraftServer server,
                                                       RegistryKey<World> worldKey,
                                                       UUID botId,
                                                       UUID targetId,
                                                       String reason) {
        if (server == null || worldKey == null || botId == null || targetId == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayerEntity liveBot = server.getPlayerManager().getPlayer(botId);
            ServerPlayerEntity liveTarget = server.getPlayerManager().getPlayer(targetId);
            ServerWorld world = server.getWorld(worldKey);
            if (liveBot == null || liveTarget == null || world == null
                    || liveBot.getEntityWorld() != world || liveTarget.getEntityWorld() != world) {
                return;
            }
            BotCommandStateService.State st = BotCommandStateService.stateFor(liveBot);
            if (st == null || st.mode != BotEventHandler.Mode.FOLLOW || st.followFixedGoal != null
                    || st.followTargetUuid == null || !st.followTargetUuid.equals(targetId)) {
                return;
            }
            long nowTick = server.getTicks();
            long lastTick = FOLLOW_LAST_NO_PATH_TICK.getOrDefault(botId, 0L);
            // Window must exceed the planning backoff interval (7s / ~140 ticks) so that
            // consecutive no-path events from the planner can actually build a streak.
            int streak = (lastTick > 0L && (nowTick - lastTick) <= 200L)
                    ? FOLLOW_NO_PATH_STREAK.getOrDefault(botId, 0) + 1
                    : 1;
            FOLLOW_LAST_NO_PATH_TICK.put(botId, nowTick);
            FOLLOW_NO_PATH_STREAK.put(botId, streak);

            boolean verticalGap = Math.abs(liveBot.getBlockY() - liveTarget.getBlockY()) >= 3;
            if (!verticalGap) {
                clearFollowNoPathState(botId);
                return;
            }
            long nextPromptTick = FOLLOW_NEXT_REGROUP_PROMPT_TICK.getOrDefault(botId, 0L);
            if (streak < 3 || nowTick < nextPromptTick) {
                return;
            }

            // Auto-regroup at streak >= 5 with cooldown and attempt limit.
            // Only auto-regroup when the bot is BELOW the player (safe to pillar/stair up).
            // When the bot is ABOVE the player (player went underground), auto-descending
            // could be fatal — fall into a shaft, lava, etc. Wait for manual /bot regroup.
            boolean botBelowTarget = liveBot.getBlockY() < liveTarget.getBlockY();
            if (streak >= 5 && botBelowTarget) {
                long lastAutoRegroup = FOLLOW_LAST_AUTO_REGROUP_TICK.getOrDefault(botId, 0L);
                int autoAttempts = FOLLOW_AUTO_REGROUP_ATTEMPTS.getOrDefault(botId, 0);
                if (autoAttempts < 3 && (nowTick - lastAutoRegroup) >= 600L) {
                    FOLLOW_LAST_AUTO_REGROUP_TICK.put(botId, nowTick);
                    FOLLOW_AUTO_REGROUP_ATTEMPTS.put(botId, autoAttempts + 1);
                    clearFollowNoPathState(botId);
                    String botName = liveBot.getName().getString();
                    ChatUtils.sendSystemMessage(liveTarget.getCommandSource(),
                            botName + " is auto-regrouping to you (attempt " + (autoAttempts + 1) + "/3).");
                    BotEventHandler.setComeModeWalk(liveBot, liveTarget,
                            liveTarget.getBlockPos().toImmutable(), 3.2D, true);
                    if (logger != null) {
                        logger.info("[FollowAssert] auto-regroup bot={} target={} reason={} streak={} attempt={} dy={}",
                                botId, targetId, reason == null ? "" : reason, streak,
                                autoAttempts + 1, Math.abs(liveBot.getBlockY() - liveTarget.getBlockY()));
                    }
                    return;
                }
            }

            FOLLOW_NEXT_REGROUP_PROMPT_TICK.put(botId, nowTick + 300L);
            // When the bot is below the player and auto-regroup is still within its attempt limit,
            // tell the player the bot is working on it instead of suggesting manual /bot regroup.
            int autoAttempts2 = FOLLOW_AUTO_REGROUP_ATTEMPTS.getOrDefault(botId, 0);
            if (botBelowTarget && autoAttempts2 < 3) {
                ChatUtils.sendSystemMessage(liveTarget.getCommandSource(),
                        liveBot.getName().getString()
                                + " is having trouble reaching you. Auto-regrouping shortly...");
            } else {
                ChatUtils.sendSystemMessage(liveTarget.getCommandSource(),
                        liveBot.getName().getString()
                                + " can't find a safe path to you here. Use /bot regroup to trigger recovery.");
            }
            if (logger != null) {
                logger.info("[FollowAssert] regroup-prompt bot={} target={} reason={} streak={} dy={}",
                        botId,
                        targetId,
                        reason == null ? "" : reason,
                        streak,
                        Math.abs(liveBot.getBlockY() - liveTarget.getBlockY()));
            }
        });
    }

    private record FollowPositions(BlockPos botPos, BlockPos targetPos) {}

    private static boolean isStuckDrivenReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return reason.startsWith("stagnant-")
                || reason.equals("direct-blocked-stuck")
                || reason.equals("water-stuck")
                || reason.equals("water-ledge-stuck")
                || reason.startsWith("direct-blocked");
    }

    private static boolean isWaterStuckReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return reason.equals("water-stuck") || reason.equals("water-ledge-stuck");
    }

    private static boolean isPlanningOriginUnchanged(UUID botId, BlockPos currentBotPos) {
        if (botId == null || currentBotPos == null) {
            return false;
        }
        BlockPos lastPlannedBotPos = FOLLOW_LAST_PLANNED_BOT_BLOCK.get(botId);
        return lastPlannedBotPos != null && lastPlannedBotPos.equals(currentBotPos);
    }

    private static int repeatedFirstWaypointStreak(UUID botId, BlockPos currentBotPos) {
        if (botId == null || currentBotPos == null) {
            return 0;
        }
        BlockPos lastPlannedBotPos = FOLLOW_LAST_PLANNED_BOT_BLOCK.get(botId);
        if (lastPlannedBotPos == null || !lastPlannedBotPos.equals(currentBotPos)) {
            return 0;
        }
        return FOLLOW_REPEAT_FIRST_WAYPOINT_STREAK.getOrDefault(botId, 0);
    }

    private static int verticalTrapFirstWaypointStreak(UUID botId, BlockPos currentBotPos) {
        if (botId == null || currentBotPos == null) {
            return 0;
        }
        BlockPos lastPlannedBotPos = FOLLOW_LAST_PLANNED_BOT_BLOCK.get(botId);
        if (lastPlannedBotPos == null || !lastPlannedBotPos.equals(currentBotPos)) {
            return 0;
        }
        return FOLLOW_REPEAT_VERTICAL_TRAP_STREAK.getOrDefault(botId, 0);
    }

    private static void updateRepeatedPlanState(Logger logger, UUID botId, BlockPos botPos, List<BlockPos> plannedWaypoints) {
        if (botId == null || botPos == null || plannedWaypoints == null || plannedWaypoints.isEmpty()) {
            return;
        }
        BlockPos first = plannedWaypoints.get(0).toImmutable();
        BlockPos prevFirst = FOLLOW_LAST_PLANNED_FIRST_WAYPOINT.get(botId);
        BlockPos prevBotPos = FOLLOW_LAST_PLANNED_BOT_BLOCK.get(botId);

        int streak;
        if (prevFirst != null && prevBotPos != null && prevFirst.equals(first) && prevBotPos.equals(botPos)) {
            streak = FOLLOW_REPEAT_FIRST_WAYPOINT_STREAK.getOrDefault(botId, 0) + 1;
        } else {
            streak = 0;
        }

        int verticalTrapStreak;
        if (prevBotPos != null
                && prevBotPos.equals(botPos)
                && prevFirst != null
                && isVerticalTrapFirstWaypoint(botPos, prevFirst)
                && isVerticalTrapFirstWaypoint(botPos, first)) {
            verticalTrapStreak = FOLLOW_REPEAT_VERTICAL_TRAP_STREAK.getOrDefault(botId, 0) + 1;
        } else {
            verticalTrapStreak = 0;
        }

        FOLLOW_REPEAT_FIRST_WAYPOINT_STREAK.put(botId, streak);
        FOLLOW_REPEAT_VERTICAL_TRAP_STREAK.put(botId, verticalTrapStreak);
        FOLLOW_LAST_PLANNED_FIRST_WAYPOINT.put(botId, first);
        FOLLOW_LAST_PLANNED_BOT_BLOCK.put(botId, botPos.toImmutable());

        if (logger != null && streak >= 2) {
            long now = System.currentTimeMillis();
            long last = FOLLOW_LAST_REPEAT_WAYPOINT_LOG_MS.getOrDefault(botId, -1L);
            if (last < 0 || (now - last) >= 2_500L) {
                FOLLOW_LAST_REPEAT_WAYPOINT_LOG_MS.put(botId, now);
                logger.info("Follow path planning: repeated first waypoint streak={} bot={} botPos={} first={}",
                        streak, botId, botPos.toShortString(), first.toShortString());
            }
        }
        if (logger != null && verticalTrapStreak >= 2) {
            long now = System.currentTimeMillis();
            long last = FOLLOW_LAST_VERTICAL_TRAP_LOG_MS.getOrDefault(botId, -1L);
            if (last < 0 || (now - last) >= 2_500L) {
                FOLLOW_LAST_VERTICAL_TRAP_LOG_MS.put(botId, now);
                logger.info("Follow path planning: repeated vertical-trap first waypoint streak={} bot={} botPos={} first={}",
                        verticalTrapStreak, botId, botPos.toShortString(), first.toShortString());
            }
        }
    }

    private static boolean isVerticalTrapFirstWaypoint(BlockPos botPos, BlockPos first) {
        if (botPos == null || first == null) {
            return false;
        }
        if ((first.getY() - botPos.getY()) < 1) {
            return false;
        }
        return Math.abs(first.getX() - botPos.getX()) <= 1
                && Math.abs(first.getZ() - botPos.getZ()) <= 1;
    }

    private static void maybeLogTransitionRejects(Logger logger, UUID botId, int transitionRejects, int swimTransitionRejects, String reason) {
        if (logger == null || botId == null || (transitionRejects <= 0 && swimTransitionRejects <= 0)) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = FOLLOW_LAST_TRANSITION_REJECT_LOG_MS.getOrDefault(botId, -1L);
        if (last >= 0 && (now - last) < 2_500L) {
            return;
        }
        FOLLOW_LAST_TRANSITION_REJECT_LOG_MS.put(botId, now);
        logger.info("[FollowAssert] transition-rejects bot={} rejects={} swimRejects={} reason={}",
                botId,
                transitionRejects,
                swimTransitionRejects,
                reason == null ? "" : reason);
        logger.info("Follow path planning: transition rejects={} swimRejects={} bot={} reason={}",
                transitionRejects,
                swimTransitionRejects,
                botId,
                reason == null ? "" : reason);
    }

    private static void maybeLogWaterStuckPlanner(Logger logger, UUID botId, String detail) {
        if (logger == null || botId == null || detail == null || detail.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = FOLLOW_LAST_WATER_ESCAPE_LOG_MS.getOrDefault(botId, -1L);
        if (last >= 0 && (now - last) < 2_500L) {
            return;
        }
        FOLLOW_LAST_WATER_ESCAPE_LOG_MS.put(botId, now);
        logger.info("Follow path planning: water-stuck {}", detail);
    }

    private static void maybeLogPlannerBackoff(Logger logger, UUID botId, long elapsedMs, long minIntervalMs, String reason) {
        if (logger == null || botId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = FOLLOW_LAST_PATH_LOG_MS.getOrDefault(botId, -1L);
        if (last >= 0 && (now - last) < 2_500L) {
            return;
        }
        FOLLOW_LAST_PATH_LOG_MS.put(botId, now);
        logger.info("[FollowAssert] planner-backoff bot={} elapsedMs={} minIntervalMs={} reason={}",
                botId,
                elapsedMs,
                minIntervalMs,
                reason == null ? "" : reason);
    }
}
