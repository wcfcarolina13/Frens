package net.shasankp000.GameAI.services;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple per-bot "sneak lock" so skills can keep a bot crouched even if movement/RL code tries to unsneak.
 *
 * <p>Used by shelter hovel2 tower builds where falling is catastrophic.</p>
 */
public final class SneakLockService {

    private static final ConcurrentHashMap<UUID, AtomicInteger> LOCKS = new ConcurrentHashMap<>();

    private SneakLockService() {}

    public static void acquire(UUID botId) {
        if (botId == null) {
            return;
        }
        LOCKS.compute(botId, (_k, v) -> {
            if (v == null) {
                return new AtomicInteger(1);
            }
            v.incrementAndGet();
            return v;
        });
    }

    public static void release(UUID botId) {
        if (botId == null) {
            return;
        }
        LOCKS.computeIfPresent(botId, (_k, v) -> v.decrementAndGet() <= 0 ? null : v);
    }

    public static boolean isLocked(UUID botId) {
        if (botId == null) {
            return false;
        }
        AtomicInteger v = LOCKS.get(botId);
        return v != null && v.get() > 0;
    }
}

