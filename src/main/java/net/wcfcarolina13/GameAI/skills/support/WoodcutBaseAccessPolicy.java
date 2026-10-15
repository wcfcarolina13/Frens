package net.wcfcarolina13.GameAI.skills.support;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * Whether woodcut may touch trees inside a SERVER-owned base (the automatic fresh-world Spawn
 * base, or a base an admin handed to the server). Pure: no Minecraft types.
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
     * Detection: a base (its auto-zone or its radius) is ignored for woodcut target detection
     * only when it is server-owned and the admin toggle is on.
     */
    public static boolean detectionExempt(boolean serverOwnedBase, boolean spawnBaseWoodcutAllowed) {
        return serverOwnedBase && spawnBaseWoodcutAllowed;
    }

    /**
     * Mining: a zone that would otherwise refuse the mutation lets it through only when the zone
     * is a server-owned base zone, the block is a log or leaves, and the toggle is on.
     */
    public static boolean miningExempt(boolean serverOwnedBaseZone, boolean treeBlock, boolean spawnBaseWoodcutAllowed) {
        return serverOwnedBaseZone && treeBlock && spawnBaseWoodcutAllowed;
    }

    /**
     * Classifies the explicit zones that contain a position (and refuse a bot mutation there).
     * Any zone that is not a server-owned base zone (or a null element) wins as {@link ZoneVerdict#OTHER}.
     * Otherwise a server-owned base zone blocks as {@link ZoneVerdict#SPAWN_BASE} unless the toggle
     * exempts it.
     */
    public static <T> ZoneVerdict classifyZones(Collection<T> containingZones,
                                                Predicate<T> serverOwnedBaseZone,
                                                boolean spawnBaseWoodcutAllowed) {
        if (containingZones == null || containingZones.isEmpty()) {
            return ZoneVerdict.NONE;
        }
        boolean serverBase = false;
        for (T zone : containingZones) {
            if (zone == null || !serverOwnedBaseZone.test(zone)) {
                return ZoneVerdict.OTHER;
            }
            serverBase = true;
        }
        if (serverBase && !detectionExempt(true, spawnBaseWoodcutAllowed)) {
            return ZoneVerdict.SPAWN_BASE;
        }
        return ZoneVerdict.NONE;
    }
}
