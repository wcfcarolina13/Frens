package net.wcfcarolina13.GameAI.services;

import java.util.Locale;

/**
 * Pure keying rules for per-world stores (no Minecraft types).
 *
 * <p>A save is identified by its <b>fingerprint</b>: the trimmed level name plus a hash of the
 * absolute save folder path ({@code "test 1#b5d170e5"}). The level name alone is not unique:
 * Minecraft names a second world created with the same name "test 1 (1)" on disk but keeps
 * the level name "test 1", so stores keyed by level name leak between the two saves.
 * {@link BotWorldStateService#currentWorldKey} is the canonical producer of the fingerprint
 * and delegates here, so the format is shared byte-for-byte.
 *
 * <p>Legacy stores (keyed by level name, or by dimension only) are never deleted or rewritten.
 * A world with no fingerprinted store copies ("adopts") the legacy one only when
 * {@link #decide} says so.
 */
public final class WorldStoreKeyPolicy {

    /** A world whose overworld game time at load is below this is treated as brand-new (one minute). */
    public static final long BRAND_NEW_WORLD_MAX_TICKS = 1200L;

    /** Hash used when the save path cannot be resolved. */
    public static final String UNKNOWN_PATH_HASH = "unknown";

    private WorldStoreKeyPolicy() {}

    /** Trimmed level name, or {@code "default"} when null/blank. Same as the legacy world-state key. */
    public static String levelNameOrDefault(String levelName) {
        if (levelName == null || levelName.isBlank()) {
            return "default";
        }
        return levelName.trim();
    }

    /**
     * Hash of an absolute, normalized save path: lower-cased (ROOT locale) then
     * {@code String.hashCode()} as unsigned hex. {@link #UNKNOWN_PATH_HASH} when null.
     */
    public static String savePathHash(String absoluteNormalizedPath) {
        if (absoluteNormalizedPath == null) {
            return UNKNOWN_PATH_HASH;
        }
        String lowered = absoluteNormalizedPath.toLowerCase(Locale.ROOT);
        return Integer.toUnsignedString(lowered.hashCode(), 16);
    }

    /** {@code levelName#savePathHash}. */
    public static String fingerprint(String levelName, String savePathHash) {
        String hash = (savePathHash == null || savePathHash.isBlank()) ? UNKNOWN_PATH_HASH : savePathHash;
        return levelNameOrDefault(levelName) + "#" + hash;
    }

    /** Store key for one dimension of one save: {@code levelName#hash:dimensionId}. */
    public static String key(String levelName, String savePathHash, String dimensionId) {
        return fingerprintKey(fingerprint(levelName, savePathHash), dimensionId);
    }

    /** Store key for one dimension when the fingerprint is already known. */
    public static String fingerprintKey(String fingerprint, String dimensionId) {
        return fingerprint + ":" + dimensionOrUnknown(dimensionId);
    }

    /**
     * Pre-1.1.234 bases key ({@code BotHomeService}): raw level name + ":" + dimension, with
     * {@code "unknown"} for a missing level name or dimension. Not trimmed: it must match what
     * older builds wrote.
     */
    public static String legacyLevelDimensionKey(String rawLevelName, String dimensionId) {
        String level = rawLevelName != null ? rawLevelName : "unknown";
        return level + ":" + dimensionOrUnknown(dimensionId);
    }

    /**
     * Filesystem-safe directory name for an arbitrary id: unsafe characters become {@code _},
     * then {@code __<hex hashCode>} keeps distinct ids distinct. This is the scheme the
     * dimension-only {@code bot_zones/<dim>} directories already use.
     */
    public static String safeDirName(String id) {
        if (id == null || id.isBlank()) {
            return "unknown_world__0";
        }
        String sanitized = id
                .replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (sanitized.isBlank()) {
            sanitized = "world";
        }
        return sanitized + "__" + Integer.toHexString(id.hashCode());
    }

    /** True when the world is brand-new at load (game time under one minute). */
    public static boolean isBrandNewWorld(long overworldGameTimeAtLoad) {
        return overworldGameTimeAtLoad < BRAND_NEW_WORLD_MAX_TICKS;
    }

    public enum Adoption {
        /** The world already has its own fingerprinted store: use it. */
        USE_OWN,
        /** Copy the legacy store into this world's fingerprinted store and record the claim. */
        ADOPT_LEGACY,
        /** No legacy store exists: start empty. */
        START_EMPTY_NO_LEGACY,
        /** Brand-new world: start empty and leave the legacy store unclaimed for its real owner. */
        START_EMPTY_NEW_WORLD,
        /** Another save already adopted the legacy store: start empty. */
        START_EMPTY_CLAIMED
    }

    /**
     * Decide what a world with the given fingerprint does at load.
     *
     * @param hasOwnStore             the fingerprinted store already exists
     * @param hasLegacyStore          a legacy store exists
     * @param overworldGameTimeAtLoad overworld game time (not time of day) when the world loaded
     * @param legacyClaimant          fingerprint that already adopted the legacy store, or null
     * @param fingerprint             this world's fingerprint
     */
    public static Adoption decide(boolean hasOwnStore, boolean hasLegacyStore, long overworldGameTimeAtLoad,
                                  String legacyClaimant, String fingerprint) {
        if (hasOwnStore) {
            return Adoption.USE_OWN;
        }
        if (!hasLegacyStore) {
            return Adoption.START_EMPTY_NO_LEGACY;
        }
        if (isBrandNewWorld(overworldGameTimeAtLoad)) {
            return Adoption.START_EMPTY_NEW_WORLD;
        }
        if (legacyClaimant != null && !legacyClaimant.isBlank() && !legacyClaimant.equals(fingerprint)) {
            return Adoption.START_EMPTY_CLAIMED;
        }
        return Adoption.ADOPT_LEGACY;
    }

    /**
     * Whether the caller must create an (empty) fingerprinted store now so the decision sticks.
     * Without it a brand-new world would find "no own store" on its next load, when its game
     * time is past the brand-new threshold, and adopt another save's legacy data.
     */
    public static boolean mustCreateOwnStore(Adoption adoption) {
        return adoption == Adoption.START_EMPTY_NEW_WORLD
                || adoption == Adoption.START_EMPTY_CLAIMED
                || adoption == Adoption.ADOPT_LEGACY;
    }

    private static String dimensionOrUnknown(String dimensionId) {
        return dimensionId != null ? dimensionId : "unknown";
    }
}
