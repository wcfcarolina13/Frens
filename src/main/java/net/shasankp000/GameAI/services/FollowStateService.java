package net.shasankp000.GameAI.services;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage-2 refactor: centralized follow/come state previously stored as many static maps in BotEventHandler.
 *
 * <p>This class intentionally stores only state; behavioral logic remains in BotEventHandler for now.</p>
 */
public final class FollowStateService {

    public record FollowDoorPlan(BlockPos doorBase,
                                 BlockPos approachPos,
                                 BlockPos stepThroughPos,
                                 long expiresAtMs,
                                 boolean stepping) {
    }

    public record FollowDoorRecovery(BlockPos goal, int remainingTicks) {
    }

    public static final Map<UUID, Long> LAST_FOLLOW_PLAN_MS = new ConcurrentHashMap<>();
    public static final Map<UUID, BlockPos> LAST_FOLLOW_TARGET_POS = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> FOLLOW_TOO_CLOSE_SINCE = new ConcurrentHashMap<>();
    public static final Map<UUID, Double> FOLLOW_LAST_DISTANCE_SQ = new ConcurrentHashMap<>();
    public static final Map<UUID, Integer> FOLLOW_STAGNANT_TICKS = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> FOLLOW_LAST_TELEPORT_TICK = new ConcurrentHashMap<>();
    public static final Map<UUID, FollowDoorPlan> FOLLOW_DOOR_PLAN = new ConcurrentHashMap<>();
    public static final Map<UUID, ArrayDeque<BlockPos>> FOLLOW_WAYPOINTS = new ConcurrentHashMap<>();
    public static final Map<UUID, CompletableFuture<?>> FOLLOW_PATH_INFLIGHT = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> FOLLOW_LAST_PATH_PLAN_MS = new ConcurrentHashMap<>();
    public static final Map<UUID, BlockPos> FOLLOW_LAST_PATH_TARGET = new ConcurrentHashMap<>();
    public static final Map<UUID, BlockPos> FOLLOW_LAST_DOOR_BASE = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> FOLLOW_LAST_DOOR_CROSS_MS = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> FOLLOW_LAST_PATH_LOG_MS = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> FOLLOW_LAST_PATH_FAIL_LOG_MS = new ConcurrentHashMap<>();
    public static final Map<UUID, Boolean> FOLLOW_SEALED_STATE = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> FOLLOW_SEALED_STATE_MS = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> FOLLOW_REPLAN_AFTER_DOOR_MS = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS = new ConcurrentHashMap<>();
    public static final Map<UUID, BlockPos> FOLLOW_DOOR_LAST_BLOCK = new ConcurrentHashMap<>();
    public static final Map<UUID, Integer> FOLLOW_DOOR_STUCK_TICKS = new ConcurrentHashMap<>();
    public static final Map<UUID, Integer> FOLLOW_DIRECT_BLOCKED_TICKS = new ConcurrentHashMap<>();
    public static final Map<UUID, FollowDoorRecovery> FOLLOW_DOOR_RECOVERY = new ConcurrentHashMap<>();
    public static final Map<UUID, BlockPos> FOLLOW_AVOID_DOOR_BASE = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> FOLLOW_AVOID_DOOR_UNTIL_MS = new ConcurrentHashMap<>();
    public static final Map<UUID, Long> FOLLOW_LAST_BLOCKED_PROBE_MS = new ConcurrentHashMap<>();
    public static final Map<UUID, BlockPos> FOLLOW_LAST_BLOCKED_PROBE_GOAL = new ConcurrentHashMap<>();
    public static final Map<UUID, BlockPos> FOLLOW_LAST_BLOCKED_PROBE_BOTPOS = new ConcurrentHashMap<>();
    public static final Map<UUID, Boolean> FOLLOW_LAST_BLOCKED_PROBE_RESULT = new ConcurrentHashMap<>();
    public static final Map<UUID, BlockPos> FOLLOW_LAST_BLOCK_POS = new ConcurrentHashMap<>();
    public static final Map<UUID, Integer> FOLLOW_POS_STAGNANT_TICKS = new ConcurrentHashMap<>();

    private FollowStateService() {}

    public static BlockPos currentAvoidDoor(UUID botId) {
        if (botId == null) {
            return null;
        }
        Long until = FOLLOW_AVOID_DOOR_UNTIL_MS.get(botId);
        if (until != null && until > 0 && System.currentTimeMillis() > until) {
            FOLLOW_AVOID_DOOR_UNTIL_MS.remove(botId);
            FOLLOW_AVOID_DOOR_BASE.remove(botId);
            return null;
        }
        return FOLLOW_AVOID_DOOR_BASE.get(botId);
    }

    public static void avoidDoorFor(UUID botId, BlockPos doorBase, long durationMs) {
        if (botId == null || doorBase == null) {
            return;
        }
        long until = System.currentTimeMillis() + Math.max(0L, durationMs);
        FOLLOW_AVOID_DOOR_BASE.put(botId, doorBase.toImmutable());
        FOLLOW_AVOID_DOOR_UNTIL_MS.put(botId, until);
    }

    /**
     * Clears follow planning state but keeps durable history (like lastDoor) unless removed elsewhere.
     * Intended for starting a new follow/come action.
     */
    public static void clearPlanning(UUID botId) {
        if (botId == null) {
            return;
        }
        FOLLOW_DOOR_PLAN.remove(botId);
        FOLLOW_WAYPOINTS.remove(botId);
        FOLLOW_LAST_DISTANCE_SQ.remove(botId);
        FOLLOW_STAGNANT_TICKS.remove(botId);
        FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS.remove(botId);
        FOLLOW_PATH_INFLIGHT.remove(botId);
        FOLLOW_LAST_PATH_PLAN_MS.remove(botId);
        FOLLOW_LAST_PATH_TARGET.remove(botId);
        FOLLOW_LAST_PATH_LOG_MS.remove(botId);
        FOLLOW_LAST_PATH_FAIL_LOG_MS.remove(botId);
        FOLLOW_REPLAN_AFTER_DOOR_MS.remove(botId);
        FOLLOW_LAST_BLOCK_POS.remove(botId);
        FOLLOW_POS_STAGNANT_TICKS.remove(botId);
        FOLLOW_DIRECT_BLOCKED_TICKS.remove(botId);
        FOLLOW_DOOR_RECOVERY.remove(botId);
        FOLLOW_AVOID_DOOR_BASE.remove(botId);
        FOLLOW_AVOID_DOOR_UNTIL_MS.remove(botId);
        FOLLOW_DOOR_LAST_BLOCK.remove(botId);
        FOLLOW_DOOR_STUCK_TICKS.remove(botId);
    }

    /**
     * Clears short-lived stuck/blocked tracking that should not linger once the bot has re-entered close range.
     */
    public static void clearTransientCloseRange(UUID botId) {
        if (botId == null) {
            return;
        }
        FOLLOW_LAST_DISTANCE_SQ.remove(botId);
        FOLLOW_STAGNANT_TICKS.remove(botId);
        FOLLOW_LAST_BLOCK_POS.remove(botId);
        FOLLOW_POS_STAGNANT_TICKS.remove(botId);
        FOLLOW_DIRECT_BLOCKED_TICKS.remove(botId);
        FOLLOW_DOOR_RECOVERY.remove(botId);
        FOLLOW_AVOID_DOOR_BASE.remove(botId);
        FOLLOW_AVOID_DOOR_UNTIL_MS.remove(botId);
    }

    /**
     * Clears all follow state including durable history.
     * Intended for stop/reset/disconnect scenarios.
     */
    public static void clearAll(UUID botId) {
        if (botId == null) {
            return;
        }
        clearPlanning(botId);
        LAST_FOLLOW_PLAN_MS.remove(botId);
        LAST_FOLLOW_TARGET_POS.remove(botId);
        FOLLOW_TOO_CLOSE_SINCE.remove(botId);
        FOLLOW_LAST_TELEPORT_TICK.remove(botId);
        FOLLOW_LAST_DOOR_BASE.remove(botId);
        FOLLOW_LAST_DOOR_CROSS_MS.remove(botId);
        FOLLOW_SEALED_STATE.remove(botId);
        FOLLOW_SEALED_STATE_MS.remove(botId);
        FOLLOW_LAST_BLOCKED_PROBE_MS.remove(botId);
        FOLLOW_LAST_BLOCKED_PROBE_GOAL.remove(botId);
        FOLLOW_LAST_BLOCKED_PROBE_BOTPOS.remove(botId);
        FOLLOW_LAST_BLOCKED_PROBE_RESULT.remove(botId);
    }

    public static void reset() {
        LAST_FOLLOW_PLAN_MS.clear();
        LAST_FOLLOW_TARGET_POS.clear();
        FOLLOW_TOO_CLOSE_SINCE.clear();
        FOLLOW_LAST_DISTANCE_SQ.clear();
        FOLLOW_STAGNANT_TICKS.clear();
        FOLLOW_LAST_TELEPORT_TICK.clear();
        FOLLOW_DOOR_PLAN.clear();
        FOLLOW_WAYPOINTS.clear();
        FOLLOW_PATH_INFLIGHT.clear();
        FOLLOW_LAST_PATH_PLAN_MS.clear();
        FOLLOW_LAST_PATH_TARGET.clear();
        FOLLOW_LAST_DOOR_BASE.clear();
        FOLLOW_LAST_DOOR_CROSS_MS.clear();
        FOLLOW_LAST_PATH_LOG_MS.clear();
        FOLLOW_LAST_PATH_FAIL_LOG_MS.clear();
        FOLLOW_SEALED_STATE.clear();
        FOLLOW_SEALED_STATE_MS.clear();
        FOLLOW_REPLAN_AFTER_DOOR_MS.clear();
        FOLLOW_LAST_ESCAPE_DOOR_PLAN_MS.clear();
        FOLLOW_DOOR_LAST_BLOCK.clear();
        FOLLOW_DOOR_STUCK_TICKS.clear();
        FOLLOW_DIRECT_BLOCKED_TICKS.clear();
        FOLLOW_DOOR_RECOVERY.clear();
        FOLLOW_AVOID_DOOR_BASE.clear();
        FOLLOW_AVOID_DOOR_UNTIL_MS.clear();
        FOLLOW_LAST_BLOCKED_PROBE_MS.clear();
        FOLLOW_LAST_BLOCKED_PROBE_GOAL.clear();
        FOLLOW_LAST_BLOCKED_PROBE_BOTPOS.clear();
        FOLLOW_LAST_BLOCKED_PROBE_RESULT.clear();
        FOLLOW_LAST_BLOCK_POS.clear();
        FOLLOW_POS_STAGNANT_TICKS.clear();
    }
}
