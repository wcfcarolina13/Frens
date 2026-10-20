package net.wcfcarolina13.GameAI.services;

import java.util.Collection;
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
        /**
         * Another save already adopted the legacy store: start empty. The chest registry adopts
         * instead when a checked legacy chest is really here ({@link #decideChestStore}).
         */
        START_EMPTY_CLAIMED,
        /**
         * The legacy zone and lock store does not look like this save's own (see
         * {@link #legacyZonesBelongToThisSave}): start empty and leave it unclaimed for its real
         * owner (no claim marker). This save's own empty store is written so zones and locks stick
         * the same way (zones would stick anyway: syncZonesFromBases saves this world's own base
         * zones right after the load). The chest registry uses it too, when none of the legacy
         * entry's checked chest positions holds a chest in this save ({@link #decideChestStore}).
         */
        START_EMPTY_NOT_THIS_SAVE
    }

    /** A base from this save's legacy (level-name keyed) bot-home entry. */
    public record LegacyBase(String label, int x, int y, int z) {}

    /** A zone from the legacy dimension-only zone file. */
    public record LegacyZone(String label, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(int x, int y, int z) {
            return x >= Math.min(minX, maxX) && x <= Math.max(minX, maxX)
                    && y >= Math.min(minY, maxY) && y <= Math.max(minY, maxY)
                    && z >= Math.min(minZ, maxZ) && z <= Math.max(minZ, maxZ);
        }
    }

    /**
     * Whether the legacy dimension-only zone and lock store (shared by every save before 1.1.234)
     * is this save's own. Game time alone let the first old world opened take it (review #5:
     * "test 1" would claim Nirn's zones and locked doors). It is this save's only when the save also
     * has a legacy bot-home entry, and, if the zone file holds any {@code base:} auto-zones, more
     * than half of them mirror a base of that entry: same label and the zone box holds the base.
     * One match is not enough: every world gets an auto-created "spawn" base, and the legacy
     * base:spawn zone was last re-synced from whichever save ran most recently (on Bradley's disk
     * an old "test 1" matches 1 of 4 base zones, Nirn 3 of 4).
     *
     * @param hasLegacyHomeEntry this save's level name has a legacy bot-home entry
     * @param homeBases          the bases in that entry (any dimension of this save)
     * @param legacyZones        every zone in the legacy zone file of the dimension
     */
    public static boolean legacyZonesBelongToThisSave(boolean hasLegacyHomeEntry,
                                                      Collection<LegacyBase> homeBases,
                                                      Collection<LegacyZone> legacyZones) {
        if (!hasLegacyHomeEntry) {
            return false;
        }
        int baseZones = 0;
        int matched = 0;
        if (legacyZones != null) {
            for (LegacyZone zone : legacyZones) {
                if (zone == null || !StaleAutoZonePolicy.isAutoZoneLabel(zone.label())) {
                    continue;
                }
                baseZones++;
                if (homeBases == null) {
                    continue;
                }
                for (LegacyBase base : homeBases) {
                    if (base != null
                            && StaleAutoZonePolicy.autoZoneLabel(base.label()).equals(zone.label())
                            && zone.contains(base.x(), base.y(), base.z())) {
                        matched++;
                        break;
                    }
                }
            }
        }
        return baseZones == 0 || matched * 2 > baseZones;
    }

    /**
     * {@link #decide} for the legacy zone and lock store, which every save shared: an adoption
     * also needs {@link #legacyZonesBelongToThisSave}. The bot-home store keeps plain
     * {@link #decide}, because its legacy key already carries the level name.
     */
    public static Adoption decideZoneStore(boolean hasOwnStore, boolean hasLegacyStore, long overworldGameTimeAtLoad,
                                           String legacyClaimant, String fingerprint,
                                           boolean legacyBelongsToThisSave) {
        Adoption adoption = decide(hasOwnStore, hasLegacyStore, overworldGameTimeAtLoad, legacyClaimant, fingerprint);
        if (adoption == Adoption.ADOPT_LEGACY && !legacyBelongsToThisSave) {
            return Adoption.START_EMPTY_NOT_THIS_SAVE;
        }
        return adoption;
    }

    /**
     * {@link #decide} for the bot chest registry. Its legacy key ({@code levelName/dim}) is shared
     * by every save with that level name, so the first such save to load would adopt another
     * save's chests (finding 40). The caller checks up to {@link #MAX_CHEST_POSITIONS_CHECKED} of the legacy entry's recorded chest
     * positions in this save, stopping at the first chest: when it checked at least one ({@code checked > 0}) and none holds a
     * chest-like block ({@code chestLike == 0}), an adoption becomes
     * {@link Adoption#START_EMPTY_NOT_THIS_SAVE} (no claim, legacy left for its real owner).
     * Nothing checked (no live records, or none readable) adopts as {@link #decide} does.
     *
     * <p>A verified chest ({@code chestLike > 0}) overrides another save's claim: 1.1.274 let a
     * same-named save claim the legacy entry by level name alone, which left the save that really
     * has the chests with an empty store for good. {@link Adoption#START_EMPTY_CLAIMED} becomes
     * {@link Adoption#ADOPT_LEGACY}, and the caller records the claim for this save (the claim
     * moves). A brand-new world still starts empty.
     */
    public static Adoption decideChestStore(boolean hasOwnStore, boolean hasLegacyStore, long overworldGameTimeAtLoad,
                                            String legacyClaimant, String fingerprint,
                                            int checked, int chestLike) {
        Adoption adoption = decide(hasOwnStore, hasLegacyStore, overworldGameTimeAtLoad, legacyClaimant, fingerprint);
        if (adoption == Adoption.ADOPT_LEGACY && checked > 0 && chestLike <= 0) {
            return Adoption.START_EMPTY_NOT_THIS_SAVE;
        }
        if (adoption == Adoption.START_EMPTY_CLAIMED && chestLike > 0) {
            return Adoption.ADOPT_LEGACY;
        }
        return adoption;
    }

    /**
     * Whether a save that already has its own chest store should replace it with the legacy
     * entry: only when its own store holds no records at all and a checked legacy position holds
     * a chest-like block here. This repairs the save that 1.1.274 left with an empty store after
     * a same-named save took the claim. An own store with any record is never overwritten.
     *
     * @param ownRecordCount every record in this save's own store (destroyed ones included)
     */
    public static boolean shouldAdoptOverEmptyOwnChestStore(int ownRecordCount, boolean hasLegacyStore, int chestLike) {
        return ownRecordCount == 0 && hasLegacyStore && chestLike > 0;
    }

    /** Most recorded chest positions tried per legacy chest entry (finding 40). */
    public static final int MAX_CHEST_POSITIONS_CHECKED = 16;

    /**
     * Result of probing a legacy chest entry's recorded positions in this save.
     *
     * @param candidates positions tried (read or not)
     * @param checked    positions actually read
     * @param chestLike  1 when a read position holds a chest-like block, else 0
     */
    public record ChestCheck(int candidates, int checked, int chestLike) {
        public static final ChestCheck NONE = new ChestCheck(0, 0, 0);
    }

    /**
     * Probes recorded chest positions in order: up to {@link #MAX_CHEST_POSITIONS_CHECKED} of
     * them, stopping at the first chest-like one. Misses do not end the check: a save's first
     * records can all be stale (moved chests stay live until verifyChests marks them destroyed),
     * and stopping after four of them marked the real owner "not this save" for good.
     *
     * @param probe {@code TRUE} chest-like, {@code FALSE} read and not chest-like, {@code null}
     *              not readable (chunk not loaded on this thread, or the read failed)
     */
    public static <P> ChestCheck checkChestPositions(Iterable<P> positions, java.util.function.Function<P, Boolean> probe) {
        if (positions == null || probe == null) {
            return ChestCheck.NONE;
        }
        int candidates = 0;
        int checked = 0;
        for (P pos : positions) {
            if (pos == null) continue;
            if (candidates >= MAX_CHEST_POSITIONS_CHECKED) {
                break;
            }
            candidates++;
            Boolean chest = probe.apply(pos);
            if (chest == null) continue;
            checked++;
            if (chest) {
                return new ChestCheck(candidates, checked, 1);
            }
        }
        return new ChestCheck(candidates, checked, 0);
    }

    /**
     * Whether a chest-registry claim that this save recorded (1.1.274 adopted by level name
     * alone) should be released: the claim is this save's, and of the legacy chest positions
     * checked here at least one was checked and none holds a chest. Releasing lets the save that
     * really owns the legacy chests adopt them if it has not decided yet; this save keeps its own
     * store.
     */
    public static boolean shouldReleaseChestClaim(String legacyClaimant, String fingerprint, int checked, int chestLike) {
        return legacyClaimant != null && legacyClaimant.equals(fingerprint) && checked > 0 && chestLike <= 0;
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
                || adoption == Adoption.START_EMPTY_NOT_THIS_SAVE
                || adoption == Adoption.ADOPT_LEGACY;
    }

    private static String dimensionOrUnknown(String dimensionId) {
        return dimensionId != null ? dimensionId : "unknown";
    }
}
