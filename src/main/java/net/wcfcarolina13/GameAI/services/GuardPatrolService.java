package net.wcfcarolina13.GameAI.services;

import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage-2 refactor: centralized storage for guard/patrol mode state.
 *
 * <p>This keeps per-bot guard center/radius and patrol targets out of {@code BotEventHandler}'s
 * generic command state map.</p>
 */
public final class GuardPatrolService {

    private static final double DEFAULT_GUARD_RADIUS = 6.0D;

    private static final Map<UUID, Vec3d> GUARD_CENTER = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> GUARD_RADIUS = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> PATROL_RADIUS = new ConcurrentHashMap<>();
    private static final Map<UUID, Vec3d> PATROL_TARGET = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PATROL_NEXT_PICK_TICK = new ConcurrentHashMap<>();

    // Stuck detection for guard/patrol modes.
    private static final double STUCK_MOVE_THRESHOLD_SQ = 0.25D; // 0.5 blocks squared
    private static final int STUCK_TICK_THRESHOLD = 60; // 3 seconds
    private static final long ESCAPE_COOLDOWN_TICKS = 600L; // 30 seconds after a failed escape
    private static final Map<UUID, Vec3d> STUCK_LAST_POS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> STUCK_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> ESCAPE_IN_PROGRESS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ESCAPE_COOLDOWN_UNTIL = new ConcurrentHashMap<>();

    private GuardPatrolService() {}

    public static void setGuardState(UUID botId, Vec3d center, double radius) {
        if (botId == null) {
            return;
        }
        if (center == null) {
            GUARD_CENTER.remove(botId);
        } else {
            GUARD_CENTER.put(botId, center);
        }
        GUARD_RADIUS.put(botId, radius);
    }

    public static void setPatrolState(UUID botId, Vec3d center, double radius) {
        if (botId == null) {
            return;
        }
        if (center == null) {
            GUARD_CENTER.remove(botId);
        } else {
            GUARD_CENTER.put(botId, center);
        }
        PATROL_RADIUS.put(botId, radius);
    }

    public static Vec3d getGuardCenter(UUID botId) {
        return botId == null ? null : GUARD_CENTER.get(botId);
    }

    public static double getGuardRadius(UUID botId) {
        if (botId == null) {
            return DEFAULT_GUARD_RADIUS;
        }
        return GUARD_RADIUS.getOrDefault(botId, DEFAULT_GUARD_RADIUS);
    }

    public static double getPatrolRadius(UUID botId) {
        if (botId == null) {
            return DEFAULT_GUARD_RADIUS;
        }
        return PATROL_RADIUS.getOrDefault(botId, DEFAULT_GUARD_RADIUS);
    }

    public static void clearGuard(UUID botId) {
        if (botId == null) {
            return;
        }
        GUARD_CENTER.remove(botId);
        GUARD_RADIUS.remove(botId);
        PATROL_RADIUS.remove(botId);
        PATROL_TARGET.remove(botId);
        PATROL_NEXT_PICK_TICK.remove(botId);
        resetStuck(botId);
    }

    public static Vec3d getPatrolTarget(UUID botId) {
        return botId == null ? null : PATROL_TARGET.get(botId);
    }

    public static void setPatrolTarget(UUID botId, Vec3d target) {
        if (botId == null) {
            return;
        }
        if (target == null) {
            PATROL_TARGET.remove(botId);
        } else {
            PATROL_TARGET.put(botId, target);
        }
    }

    public static long getNextPatrolPickTick(UUID botId) {
        if (botId == null) {
            return 0L;
        }
        return PATROL_NEXT_PICK_TICK.getOrDefault(botId, 0L);
    }

    public static void setNextPatrolPickTick(UUID botId, long tick) {
        if (botId == null) {
            return;
        }
        PATROL_NEXT_PICK_TICK.put(botId, Math.max(0L, tick));
    }

    // ── Stuck detection ──────────────────────────────────────────────────

    public static void updateStuckTracker(UUID botId, Vec3d currentPos) {
        if (botId == null || currentPos == null) return;
        Vec3d lastPos = STUCK_LAST_POS.get(botId);
        STUCK_LAST_POS.put(botId, currentPos);
        if (lastPos != null && lastPos.squaredDistanceTo(currentPos) < STUCK_MOVE_THRESHOLD_SQ) {
            STUCK_TICKS.merge(botId, 1, Integer::sum);
        } else {
            STUCK_TICKS.remove(botId);
        }
    }

    public static boolean isStuck(UUID botId, long currentTick) {
        if (botId == null || STUCK_TICKS.getOrDefault(botId, 0) < STUCK_TICK_THRESHOLD) return false;
        Long cooldownUntil = ESCAPE_COOLDOWN_UNTIL.get(botId);
        return cooldownUntil == null || currentTick >= cooldownUntil;
    }

    public static boolean isEscapeInProgress(UUID botId) {
        return botId != null && Boolean.TRUE.equals(ESCAPE_IN_PROGRESS.get(botId));
    }

    public static void setEscapeInProgress(UUID botId, boolean value) {
        if (botId == null) return;
        if (value) {
            ESCAPE_IN_PROGRESS.put(botId, true);
        } else {
            ESCAPE_IN_PROGRESS.remove(botId);
        }
    }

    public static void startEscapeCooldown(UUID botId, long currentTick) {
        if (botId != null) {
            ESCAPE_COOLDOWN_UNTIL.put(botId, currentTick + ESCAPE_COOLDOWN_TICKS);
        }
    }

    public static void resetStuck(UUID botId) {
        if (botId == null) return;
        STUCK_LAST_POS.remove(botId);
        STUCK_TICKS.remove(botId);
        ESCAPE_IN_PROGRESS.remove(botId);
        ESCAPE_COOLDOWN_UNTIL.remove(botId);
    }

    public static void reset() {
        GUARD_CENTER.clear();
        GUARD_RADIUS.clear();
        PATROL_RADIUS.clear();
        PATROL_TARGET.clear();
        PATROL_NEXT_PICK_TICK.clear();
        STUCK_LAST_POS.clear();
        STUCK_TICKS.clear();
        ESCAPE_IN_PROGRESS.clear();
        ESCAPE_COOLDOWN_UNTIL.clear();
    }
}
