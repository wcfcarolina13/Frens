package net.wcfcarolina13.FilingSystem;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Admission + save coalescing for client-sent per-player preference toggles (preserve gear,
 * auto-accept precious food). A request that matches the effective value is ignored; accepted
 * changes to one (player, preference) are at least {@link #MIN_CHANGE_INTERVAL_MS} apart; and an
 * accepted change only marks the config dirty -- the server tick flushes it at most once per
 * {@link #SAVE_COALESCE_MS}, and SERVER_STOPPING flushes whatever is left. Pure and
 * thread-safe; callers use it from the server thread.
 */
public final class PlayerPreferenceGate {

    public static final long MIN_CHANGE_INTERVAL_MS = 500L;
    public static final long SAVE_COALESCE_MS = 2_000L;

    public enum Decision { UNCHANGED, RATE_LIMITED, ACCEPTED }

    /** {@code risingEdge} is true only for an accepted false-to-true change. */
    public record Admission(Decision decision, boolean risingEdge) {
        public boolean accepted() {
            return decision == Decision.ACCEPTED;
        }
    }

    private final long minChangeIntervalMs;
    private final long saveCoalesceMs;
    private final Map<String, Long> lastAcceptedMs = new HashMap<>();
    private boolean dirty;
    private boolean flushedBefore;
    private long lastFlushMs;

    public PlayerPreferenceGate() {
        this(MIN_CHANGE_INTERVAL_MS, SAVE_COALESCE_MS);
    }

    public PlayerPreferenceGate(long minChangeIntervalMs, long saveCoalesceMs) {
        this.minChangeIntervalMs = minChangeIntervalMs;
        this.saveCoalesceMs = saveCoalesceMs;
    }

    /**
     * Decides a request to set {@code preference} for {@code player} from {@code current} (the
     * effective value) to {@code requested}. An accepted change marks the config dirty.
     */
    public synchronized Admission admit(UUID player, String preference, boolean current, boolean requested, long nowMs) {
        if (current == requested) {
            return new Admission(Decision.UNCHANGED, false);
        }
        String key = player + "|" + preference;
        Long last = lastAcceptedMs.get(key);
        if (last != null && nowMs - last < minChangeIntervalMs) {
            return new Admission(Decision.RATE_LIMITED, false);
        }
        lastAcceptedMs.put(key, nowMs);
        dirty = true;
        return new Admission(Decision.ACCEPTED, !current && requested);
    }

    /** Server tick: true when a save should run now (clears the dirty mark). */
    public synchronized boolean flushDue(long nowMs) {
        if (!dirty) {
            return false;
        }
        if (flushedBefore && nowMs - lastFlushMs < saveCoalesceMs) {
            return false;
        }
        dirty = false;
        flushedBefore = true;
        lastFlushMs = nowMs;
        return true;
    }

    /**
     * SERVER_STOPPING: true when unsaved changes remain (the caller saves); resets the session so
     * a reloaded world starts with no rate-limit history.
     */
    public synchronized boolean drainForShutdown() {
        boolean pending = dirty;
        dirty = false;
        flushedBefore = false;
        lastAcceptedMs.clear();
        return pending;
    }
}
