package net.wcfcarolina13.GameAI.services;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-bot hotbar lock state behind {@link HotbarLockService}, free of game types.
 *
 * <p>Thread-safe: the server thread locks (ride sync) while skill workers read and expire. One
 * immutable record per bot makes a lock appear all at once (slot, expiry and reason together), and
 * expiry removes only the record it saw, so a fresh lock written in between survives.
 */
final class HotbarLockTable {

    record Lock(int slot, long untilTick, String reason) {
    }

    private final Map<UUID, Lock> locks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastLogTick = new ConcurrentHashMap<>();

    void lock(UUID id, int slot, long nowTick, long ttlTicks, String reason) {
        if (id == null) {
            return;
        }
        locks.put(id, new Lock(Math.max(0, Math.min(8, slot)), nowTick + Math.max(1L, ttlTicks),
                reason != null ? reason : "unknown"));
    }

    void clear(UUID id) {
        if (id == null) {
            return;
        }
        locks.remove(id);
        lastLogTick.remove(id);
    }

    /** Drops every lock and log cooldown (a world change: the next server's ticks restart at 0). */
    void clearAll() {
        locks.clear();
        lastLogTick.clear();
    }

    /** The live lock at {@code nowTick}, or null; an expired lock is dropped on the way. */
    Lock active(UUID id, long nowTick) {
        if (id == null) {
            return null;
        }
        Lock lock = locks.get(id);
        if (lock == null) {
            return null;
        }
        if (nowTick > lock.untilTick()) {
            // Conditional: never removes a newer lock put after the read above.
            if (locks.remove(id, lock)) {
                lastLogTick.remove(id);
            }
            return null;
        }
        return lock;
    }

    /** True at most once per {@code cooldownTicks} per bot; records the tick when true. */
    boolean logDue(UUID id, long nowTick, long cooldownTicks) {
        if (id == null) {
            return false;
        }
        boolean[] due = {false};
        lastLogTick.compute(id, (k, last) -> {
            if (last != null && nowTick - last < cooldownTicks) {
                return last;
            }
            due[0] = true;
            return nowTick;
        });
        return due[0];
    }
}
