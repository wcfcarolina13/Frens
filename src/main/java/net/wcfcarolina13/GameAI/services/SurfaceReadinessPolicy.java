package net.wcfcarolina13.GameAI.services;

/**
 * Pure "is this an operational surface" rule over the fields of
 * {@code SafePositionService.SurfaceCandidateAssessment}, with one extension: a tile where only
 * leaves are overhead (forest floor under a canopy) counts like open sky, so surface recovery does
 * not pillar a bot up through the leaves.
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class SurfaceReadinessPolicy {

    private SurfaceReadinessPolicy() {}

    /**
     * True when nothing but leaves is above the feet: the highest motion-blocking non-leaf block's
     * top ({@code MOTION_BLOCKING_NO_LEAVES} heightmap) is at or below the feet. Logs, stone and
     * the like still count as cover.
     */
    public static boolean canopyOnly(int topYNoLeaves, int feetY) {
        return topYNoLeaves <= feetY;
    }

    /**
     * Mirrors {@code SafePositionService.isOperationalSurfaceAssessment}, with {@code openSky}
     * widened to {@code openSky || canopyOnly}.
     */
    public static boolean isOperational(boolean openSky,
                                        boolean canopyOnly,
                                        boolean standable,
                                        boolean nearSurface,
                                        int cardinalStandableNeighbors,
                                        int totalStandableNeighbors,
                                        int steepDropNeighbors,
                                        int blockedCardinals) {
        return (openSky || canopyOnly)
                && standable
                && nearSurface
                && cardinalStandableNeighbors >= 2
                && totalStandableNeighbors >= 4
                && steepDropNeighbors <= 1
                && blockedCardinals <= 2;
    }
}
