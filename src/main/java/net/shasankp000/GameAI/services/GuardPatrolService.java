package net.shasankp000.GameAI.services;

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
    private static final Map<UUID, Vec3d> PATROL_TARGET = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PATROL_NEXT_PICK_TICK = new ConcurrentHashMap<>();

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

    public static Vec3d getGuardCenter(UUID botId) {
        return botId == null ? null : GUARD_CENTER.get(botId);
    }

    public static double getGuardRadius(UUID botId) {
        if (botId == null) {
            return DEFAULT_GUARD_RADIUS;
        }
        return GUARD_RADIUS.getOrDefault(botId, DEFAULT_GUARD_RADIUS);
    }

    public static void clearGuard(UUID botId) {
        if (botId == null) {
            return;
        }
        GUARD_CENTER.remove(botId);
        GUARD_RADIUS.remove(botId);
        PATROL_TARGET.remove(botId);
        PATROL_NEXT_PICK_TICK.remove(botId);
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

    public static void reset() {
        GUARD_CENTER.clear();
        GUARD_RADIUS.clear();
        PATROL_TARGET.clear();
        PATROL_NEXT_PICK_TICK.clear();
    }
}
