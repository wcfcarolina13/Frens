package net.wcfcarolina13.GameAI.skills.support;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each bot remembers the scaffold blocks its woodcut placed, so its cleanup can take them
 * down. The memory lives in the shared skill-state map, one entry per bot (finding 52: it used to
 * be one global set, so one bot's cleanup mined another bot's pillar while that bot stood on it).
 * A bot only ever reads and clears its own set. Pure: positions are packed longs, a dimension is
 * its id string.
 */
public final class WoodcutScaffoldMemoryStore {

    public static final String KEY_PREFIX = "woodcut.scaffoldMemory.";
    /** Positions kept per bot; the oldest-ish (arbitrary) entries go first. */
    public static final int MAX_POSITIONS = 2048;

    /** The pre-finding-52 global keys, dropped on first per-bot access. */
    static final String LEGACY_POSITIONS_KEY = "woodcut.scaffoldMemory.positions";
    static final String LEGACY_DIMENSION_KEY = "woodcut.scaffoldMemory.dimension";
    static final String LEGACY_UPDATED_AT_KEY = "woodcut.scaffoldMemory.updatedAt";

    private WoodcutScaffoldMemoryStore() {
    }

    static String positionsKey(UUID bot) {
        return KEY_PREFIX + bot + ".positions";
    }

    static String dimensionKey(UUID bot) {
        return KEY_PREFIX + bot + ".dimension";
    }

    static String updatedAtKey(UUID bot) {
        return KEY_PREFIX + bot + ".updatedAt";
    }

    /**
     * This bot's remembered scaffold in {@code dimension}. A change of dimension drops the old set.
     * Returns null when an argument is missing, or when nothing is stored and {@code create} is false.
     */
    public static Set<Long> get(Map<String, Object> sharedState, UUID bot, String dimension, boolean create) {
        if (sharedState == null || bot == null || dimension == null) {
            return null;
        }
        dropLegacyGlobal(sharedState);
        String positionsKey = positionsKey(bot);
        Object storedDim = sharedState.get(dimensionKey(bot));
        if (storedDim instanceof String stored && !stored.equals(dimension)) {
            sharedState.remove(positionsKey);
        }
        sharedState.put(dimensionKey(bot), dimension);

        Object raw = sharedState.get(positionsKey);
        if (raw instanceof Set<?> existing) {
            @SuppressWarnings("unchecked")
            Set<Long> typed = (Set<Long>) existing;
            trim(typed);
            return typed;
        }
        if (raw != null) {
            sharedState.remove(positionsKey);
        }
        if (!create) {
            return null;
        }
        Set<Long> created = ConcurrentHashMap.newKeySet();
        sharedState.put(positionsKey, created);
        return created;
    }

    /** Remembers a scaffold block this bot placed. */
    public static void record(Map<String, Object> sharedState, UUID bot, String dimension, long packedPos, long nowMs) {
        Set<Long> set = get(sharedState, bot, dimension, true);
        if (set == null) {
            return;
        }
        sharedState.put(updatedAtKey(bot), nowMs);
        set.add(packedPos);
        trim(set);
    }

    /** Forgets a position from this bot's memory (it was mined or never stood). */
    public static void forget(Map<String, Object> sharedState, UUID bot, String dimension, long packedPos) {
        Set<Long> set = get(sharedState, bot, dimension, false);
        if (set != null) {
            set.remove(packedPos);
        }
    }

    /** Whether this bot remembers placing scaffold at the position. */
    public static boolean contains(Map<String, Object> sharedState, UUID bot, String dimension, long packedPos) {
        Set<Long> set = get(sharedState, bot, dimension, false);
        return set != null && set.contains(packedPos);
    }

    /** Clears this bot's memory only (its skill switched away from woodcut). */
    public static void clearForBot(Map<String, Object> sharedState, UUID bot) {
        if (sharedState == null || bot == null) {
            return;
        }
        sharedState.remove(positionsKey(bot));
        sharedState.remove(dimensionKey(bot));
        sharedState.remove(updatedAtKey(bot));
        dropLegacyGlobal(sharedState);
    }

    /** The old global set belonged to no bot in particular; nothing may tear it down as its own. */
    private static void dropLegacyGlobal(Map<String, Object> sharedState) {
        if (sharedState.containsKey(LEGACY_POSITIONS_KEY)) {
            sharedState.remove(LEGACY_POSITIONS_KEY);
            sharedState.remove(LEGACY_DIMENSION_KEY);
            sharedState.remove(LEGACY_UPDATED_AT_KEY);
        }
    }

    private static void trim(Set<Long> set) {
        int overflow = set.size() - MAX_POSITIONS;
        if (overflow <= 0) {
            return;
        }
        Iterator<Long> it = set.iterator();
        while (overflow > 0 && it.hasNext()) {
            it.next();
            it.remove();
            overflow--;
        }
    }
}
