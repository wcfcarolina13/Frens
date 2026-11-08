package net.wcfcarolina13;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a guarded server-thread handler throws: the first failure of each handler is logged with its
 * stack trace, later ones at most once per {@link #INTERVAL_MS} with the number held back since,
 * so a handler failing every tick neither floods the log nor goes quiet. Pure.
 */
public final class TickGuardLogPolicy {
    public static final long INTERVAL_MS = 60_000L;

    private final Map<String, long[]> state = new ConcurrentHashMap<>(); // {lastLogMs, suppressed}

    /** -1 when this failure is held back; otherwise how many were held back before it (0 the first time). */
    public long onFailure(String handler, long nowMs) {
        long[] s = state.computeIfAbsent(handler, k -> new long[]{Long.MIN_VALUE, 0});
        synchronized (s) {
            if (s[0] != Long.MIN_VALUE && nowMs - s[0] < INTERVAL_MS) {
                s[1]++;
                return -1;
            }
            long held = s[1];
            s[0] = nowMs;
            s[1] = 0;
            return held;
        }
    }
}
