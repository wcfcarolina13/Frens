package net.wcfcarolina13.GameAI.services;

import java.util.Map;

/**
 * Pure helpers that keep per-tick log and chat lines from flooding (1.1.273).
 * Callers own the map (usually a {@code ConcurrentHashMap}) and clear it on reset.
 */
public final class LogThrottle {

    private LogThrottle() {
    }

    /**
     * True when {@code state} differs from the one stored for {@code key}, and records it.
     * A null state is stored as {@code "<null>"} so concurrent maps accept it.
     */
    public static <K> boolean changed(Map<K, String> m, K key, String state) {
        String value = state == null ? "<null>" : state;
        String prev = m.put(key, value);
        return !value.equals(prev);
    }

    /**
     * True when at least {@code interval} has passed since the last recorded time for
     * {@code key} (or there is none), and records {@code now}. A negative gap (the clock
     * went back, e.g. after a server restart) counts as due.
     * <p>
     * Worker threads and the server tick both call this, so the read and the write are one
     * {@link Map#compute} step: on a {@code ConcurrentHashMap} exactly one of two racing callers
     * sees the slot as due.
     */
    public static <K> boolean due(Map<K, Long> m, K key, long now, long interval) {
        boolean[] due = {false};
        m.compute(key, (k, last) -> {
            if (last != null) {
                long gap = now - last;
                if (gap >= 0 && gap < interval) {
                    return last;
                }
            }
            due[0] = true;
            return now;
        });
        return due[0];
    }
}
