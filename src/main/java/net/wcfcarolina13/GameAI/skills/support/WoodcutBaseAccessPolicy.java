package net.wcfcarolina13.GameAI.skills.support;

import java.util.Collection;
import java.util.function.Function;

/**
 * Whether woodcut may touch trees inside a SERVER-owned base (the automatic fresh-world Spawn
 * base, or a base an admin handed to the server). Owner literally SERVER only: legacy bases with
 * a blank owner are not server bases here. Pure: no Minecraft types.
 *
 * <p>Default (toggle off): the Spawn base protects its trees like a player base. Detection
 * reports them as protected ("spawn-base") and the miner's claim check refuses them, so target
 * detection and mining agree. With the per-save admin toggle on, both exempt the Spawn base,
 * for log and leaves blocks only. Player bases, user-drawn protected areas, forts and villages
 * are never affected by the toggle.
 */
public final class WoodcutBaseAccessPolicy {

    /** Protection reason reported when only the server Spawn base blocks a tree. */
    public static final String SPAWN_BASE_REASON = "spawn-base";

    /** Mirrors BotHomeService.SERVER_OWNER_UUID (kept literal so this class loads under JUnit). */
    public static final String SERVER_OWNER_SENTINEL = "SERVER";

    /** Mirrors BotHomeService.SERVER_OWNER_NAME, the owner name stamped on the automatic Spawn base. */
    public static final String SERVER_OWNER_NAME = "Server";

    /** What kind of protected zone a containing zone is, for woodcut and the Spawn toggle. */
    public enum ZoneKind {
        /** Auto-zone of a base whose owner is literally SERVER (the Spawn base, or one an admin gave the server). */
        SERVER_BASE,
        /**
         * Auto-zone of a legacy base with a blank or missing owner (made before ownership was
         * tracked). Never "spawn-base" and never opened by the toggle. Detection skips the zone
         * itself (the base radius still blocks) and mining refuses it, exactly as in 1.1.237.
         */
        LEGACY_BASE,
        /** A player's base auto-zone or a user-drawn protected area. */
        OTHER
    }

    /** What the explicit protected zones containing a position mean for woodcut. */
    public enum ZoneVerdict {
        /** No containing zone blocks woodcut. */
        NONE,
        /** Only server-owned base zones contain the position and the toggle is off. */
        SPAWN_BASE,
        /** A player base zone, a user-drawn zone, or an unknown zone contains the position. */
        OTHER
    }

    private WoodcutBaseAccessPolicy() {
    }

    /** True when a saved base's owner field names the server (base-radius path). */
    public static boolean isServerOwnedBaseOwner(String ownerUuid) {
        return SERVER_OWNER_SENTINEL.equals(ownerUuid);
    }

    /**
     * Kind of a protected zone. {@code baseAutoZone}: the label is a {@code base:} auto-zone label.
     * {@code zoneHasOwner}: the zone carries a player UUID (player bases do, SERVER and legacy
     * blank owners both map to no owner). When the zone's base is registered, its stored owner
     * decides; otherwise (a stale zone) the zone's owner name must be exactly "Server".
     */
    public static ZoneKind zoneKind(boolean baseAutoZone, boolean zoneHasOwner,
                                    boolean baseFound, String baseOwnerUuid, String zoneOwnerName) {
        if (!baseAutoZone || zoneHasOwner) {
            return ZoneKind.OTHER;
        }
        boolean server = baseFound
                ? isServerOwnedBaseOwner(baseOwnerUuid)
                : SERVER_OWNER_NAME.equals(zoneOwnerName);
        return server ? ZoneKind.SERVER_BASE : ZoneKind.LEGACY_BASE;
    }

    /**
     * Detection: a base (its auto-zone or its radius) is ignored for woodcut target detection
     * only when it is server-owned and the admin toggle is on.
     */
    public static boolean detectionExempt(boolean serverOwnedBase, boolean spawnBaseWoodcutAllowed) {
        return serverOwnedBase && spawnBaseWoodcutAllowed;
    }

    /**
     * What a bot block mutation is, for the Spawn-base exemptions (1.1.268 ruling: a bot may place
     * and remove its own woodcut scaffold in the Spawn base when the toggle is on; every other
     * mutation there stays refused).
     */
    public enum MutationKind {
        /** Any mutation the toggle never opens (dirt, stone, supports, bridges, replant, ...). */
        OTHER,
        /** Breaking a log or leaves block (the 1.1.238 toggle exemption). */
        TREE_BLOCK,
        /** Placing a scaffold-material block on an explicitly flagged woodcut climb pillar. */
        OWN_SCAFFOLD_PLACE,
        /** Breaking a block this bot placed as own scaffold (registry) that is still scaffold material. */
        OWN_SCAFFOLD_BREAK
    }

    /**
     * Mining: a zone that would otherwise refuse the mutation lets it through only when the zone
     * is a server-owned base zone, the block is a log or leaves, and the toggle is on.
     * Same answer as {@link #mutationExempt} with {@link MutationKind#TREE_BLOCK} / {@link MutationKind#OTHER}.
     */
    public static boolean miningExempt(boolean serverOwnedBaseZone, boolean treeBlock, boolean spawnBaseWoodcutAllowed) {
        return mutationExempt(serverOwnedBaseZone, treeBlock ? MutationKind.TREE_BLOCK : MutationKind.OTHER,
                false, false, spawnBaseWoodcutAllowed);
    }

    /**
     * Whether a zone that would otherwise refuse a bot mutation lets it through. Only a
     * server-owned base zone (the Spawn base) can ever be exempted:
     * <ul>
     *   <li>{@link MutationKind#TREE_BLOCK}: the toggle is on.</li>
     *   <li>{@link MutationKind#OWN_SCAFFOLD_PLACE}: the toggle is on and the block placed is a
     *       scaffold material.</li>
     *   <li>{@link MutationKind#OWN_SCAFFOLD_BREAK}: the position is in this bot's own scaffold
     *       registry and the live block is still a scaffold material. The toggle is NOT needed, so a
     *       bot can always remove what it placed (the admin may turn the toggle off mid-fell).</li>
     *   <li>{@link MutationKind#OTHER}: never.</li>
     * </ul>
     */
    public static boolean mutationExempt(boolean serverOwnedBaseZone, MutationKind kind,
                                         boolean ownedByThisBot, boolean stillScaffoldMaterial,
                                         boolean spawnBaseWoodcutAllowed) {
        if (!serverOwnedBaseZone || kind == null) {
            return false;
        }
        return switch (kind) {
            case TREE_BLOCK -> spawnBaseWoodcutAllowed;
            case OWN_SCAFFOLD_PLACE -> spawnBaseWoodcutAllowed && stillScaffoldMaterial;
            case OWN_SCAFFOLD_BREAK -> ownedByThisBot && stillScaffoldMaterial;
            case OTHER -> false;
        };
    }

    /**
     * The pillar's {@code overhead-in-base} abort (ScaffoldService) is skipped only for an
     * own-scaffold woodcut pillar, when every base covering the overhead block is server-owned,
     * the toggle is on, and the overhead block is a log or leaves. Any other overhead block in a
     * base still aborts; the overhead mine itself still goes through the miner's normal gate.
     */
    public static boolean overheadBaseAbortExempt(boolean ownScaffold, boolean serverOwnedBaseOnly,
                                                  boolean treeBlockOverhead, boolean spawnBaseWoodcutAllowed) {
        return ownScaffold && serverOwnedBaseOnly && treeBlockOverhead && spawnBaseWoodcutAllowed;
    }

    /**
     * Classifies the explicit zones that contain a position (and refuse a bot mutation there).
     * Any {@link ZoneKind#OTHER} zone (or a null element or kind) wins as {@link ZoneVerdict#OTHER}.
     * Otherwise a SERVER base zone blocks as {@link ZoneVerdict#SPAWN_BASE} unless the toggle
     * exempts it. Legacy ownerless base zones are skipped here, as before 1.1.238.
     */
    public static <T> ZoneVerdict classifyZones(Collection<T> containingZones,
                                                Function<T, ZoneKind> kindOf,
                                                boolean spawnBaseWoodcutAllowed) {
        if (containingZones == null || containingZones.isEmpty()) {
            return ZoneVerdict.NONE;
        }
        boolean serverBase = false;
        for (T zone : containingZones) {
            ZoneKind kind = zone == null ? null : kindOf.apply(zone);
            if (kind == null || kind == ZoneKind.OTHER) {
                return ZoneVerdict.OTHER;
            }
            if (kind == ZoneKind.SERVER_BASE) {
                serverBase = true;
            }
        }
        if (serverBase && !detectionExempt(true, spawnBaseWoodcutAllowed)) {
            return ZoneVerdict.SPAWN_BASE;
        }
        return ZoneVerdict.NONE;
    }
}
