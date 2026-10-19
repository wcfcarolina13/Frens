package net.wcfcarolina13.GameAI.services;

/**
 * Decides whether a lodestone compass target is still valid for fast travel.
 * Pure: no Minecraft types, so it is unit-testable.
 *
 * Loaded chunk: the block check decides. Unloaded chunk on the server thread:
 * the POI storage check decides (what vanilla LodestoneTrackerComponent.forWorld
 * does; it reads POI region data and never loads the chunk). Unloaded off the
 * server thread: nothing safe to ask, so UNKNOWN, which still allows travel.
 */
public final class LodestoneValidityPolicy {

    public enum Validity { PRESENT, ABSENT, UNKNOWN }

    private LodestoneValidityPolicy() {}

    /**
     * @param chunkBlockIsLodestone block result when the chunk is loaded, null when it is not
     * @param poiHasLodestone POI result when it was asked, null when it was not
     * @param onServerThread whether the caller is on the server thread
     */
    public static Validity decide(Boolean chunkBlockIsLodestone, Boolean poiHasLodestone,
                                  boolean onServerThread) {
        if (chunkBlockIsLodestone != null) {
            return chunkBlockIsLodestone ? Validity.PRESENT : Validity.ABSENT;
        }
        if (onServerThread && poiHasLodestone != null) {
            return poiHasLodestone ? Validity.PRESENT : Validity.ABSENT;
        }
        return Validity.UNKNOWN;
    }

    public static boolean allowsTravel(Validity v) {
        return v != Validity.ABSENT;
    }
}
