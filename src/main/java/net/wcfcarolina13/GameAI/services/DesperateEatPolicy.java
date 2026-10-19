package net.wcfcarolina13.GameAI.services;

import java.util.Map;

/**
 * Pacing for last-resort eating (rotten flesh, golden food) in {@link HealingService#autoEat}
 * (finding 42). autoEat runs every tick; when the eat never starts (a skill reselects the hotbar
 * slot each tick), retrying every tick flooded the log 20 times a second and made callers skip
 * their own work. Tries are spaced {@link #ATTEMPT_COOLDOWN_MS} apart per bot, and the
 * "Desperate food" log lines at most once per {@link #LOG_INTERVAL_MS}.
 */
public final class DesperateEatPolicy {

    public static final long ATTEMPT_COOLDOWN_MS = 2_000L;
    public static final long LOG_INTERVAL_MS = 10_000L;

    private DesperateEatPolicy() {
    }

    /** True when a desperate eat may be tried now for {@code key}, and records the try. */
    public static <K> boolean attemptDue(Map<K, Long> lastAttempt, K key, long nowMs) {
        return LogThrottle.due(lastAttempt, key, nowMs, ATTEMPT_COOLDOWN_MS);
    }

    /** True when the "Desperate food" line may be logged now for {@code key}, and records it. */
    public static <K> boolean logDue(Map<K, Long> lastLog, K key, long nowMs) {
        return LogThrottle.due(lastLog, key, nowMs, LOG_INTERVAL_MS);
    }
}
