package net.wcfcarolina13.GameAI.services;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Failed-route memory for movement's local escape and step-up assists (1.1.240).
 *
 * <p>Sandbox case (1.1.239, 22,63,126): local escape picked the same impossible first hop ten
 * times in a row ({@code finalDist=2.62} every time) because nothing remembered that the hop had
 * already failed from that cell. A route is keyed by (bot, origin cell, first hop) and is skipped
 * for {@link #TTL_MS} after it fails. Origins are grounded cells, so a mid-jump read does not make
 * a fresh key for the same stuck spot.</p>
 *
 * <p>Pure: ints, longs and {@link UUID} only, no {@code net.minecraft.*} types. Callers pass the
 * clock in, so tests drive the TTL directly.</p>
 */
public final class FailedRoutePolicy {

    /** How long a failed (origin, first hop) route is skipped. */
    public static final long TTL_MS = 30_000L;
    /** A skip is logged at most once per route per this window. */
    public static final long SKIP_LOG_INTERVAL_MS = 5_000L;

    /** One route: the grounded origin cell and the first hop cell. */
    public record Key(int originX, int originY, int originZ, int hopX, int hopY, int hopZ) {}

    private record Entry(long recordedMs, long lastSkipLogMs) {}

    private final Map<UUID, Map<Key, Entry>> failed = new ConcurrentHashMap<>();

    public static Key key(int originX, int originY, int originZ, int hopX, int hopY, int hopZ) {
        return new Key(originX, originY, originZ, hopX, hopY, hopZ);
    }

    /** True while a failure recorded at {@code recordedMs} is still remembered at {@code nowMs}. */
    public static boolean live(long recordedMs, long nowMs) {
        return nowMs - recordedMs < TTL_MS;
    }

    /** Remembers a failed route. Re-recording refreshes the TTL. Expired entries are pruned. */
    public void record(UUID botId, Key key, long nowMs) {
        if (botId == null || key == null) {
            return;
        }
        Map<Key, Entry> perBot = failed.computeIfAbsent(botId, id -> new ConcurrentHashMap<>());
        perBot.values().removeIf(e -> !live(e.recordedMs(), nowMs));
        Entry previous = perBot.get(key);
        perBot.put(key, new Entry(nowMs, previous == null ? Long.MIN_VALUE : previous.lastSkipLogMs()));
    }

    /** True when this route failed less than {@link #TTL_MS} ago. */
    public boolean isFailed(UUID botId, Key key, long nowMs) {
        if (botId == null || key == null) {
            return false;
        }
        Map<Key, Entry> perBot = failed.get(botId);
        if (perBot == null) {
            return false;
        }
        Entry entry = perBot.get(key);
        if (entry == null) {
            return false;
        }
        if (!live(entry.recordedMs(), nowMs)) {
            perBot.remove(key);
            return false;
        }
        return true;
    }

    /** Milliseconds this route stays remembered, 0 when it is not (or no longer) remembered. */
    public long remainingMs(UUID botId, Key key, long nowMs) {
        if (!isFailed(botId, key, nowMs)) {
            return 0L;
        }
        Entry entry = failed.get(botId).get(key);
        return entry == null ? 0L : Math.max(0L, TTL_MS - (nowMs - entry.recordedMs()));
    }

    /**
     * True (and marks it) when a skip of this remembered route should be logged now: at most once
     * per {@link #SKIP_LOG_INTERVAL_MS} per route, so a search that meets the route many times logs once.
     */
    public boolean claimSkipLog(UUID botId, Key key, long nowMs) {
        if (botId == null || key == null) {
            return false;
        }
        Map<Key, Entry> perBot = failed.get(botId);
        if (perBot == null) {
            return false;
        }
        Entry entry = perBot.get(key);
        if (entry == null || !live(entry.recordedMs(), nowMs)) {
            return false;
        }
        if (entry.lastSkipLogMs() != Long.MIN_VALUE && nowMs - entry.lastSkipLogMs() < SKIP_LOG_INTERVAL_MS) {
            return false;
        }
        perBot.put(key, new Entry(entry.recordedMs(), nowMs));
        return true;
    }

    public void clear(UUID botId) {
        if (botId != null) {
            failed.remove(botId);
        }
    }

    public void clearAll() {
        failed.clear();
    }
}
