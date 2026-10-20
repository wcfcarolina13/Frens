package net.wcfcarolina13.GameAI.services;

import java.util.function.IntPredicate;

/**
 * Pure "is this an operational surface" rule over the fields of
 * {@code SafePositionService.SurfaceCandidateAssessment}, with one extension: a tile where only
 * leaves and logs are overhead (forest floor under a canopy) counts like open sky, so surface recovery does
 * not pillar a bot up through the leaves.
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class SurfaceReadinessPolicy {

    public static final int MAX_CANOPY_HEIGHT = 16;

    private SurfaceReadinessPolicy() {}

    /**
     * True when nothing but leaves is above the feet: the highest motion-blocking non-leaf block's
     * top ({@code MOTION_BLOCKING_NO_LEAVES} heightmap) is at or below the feet. Logs, stone and
     * the like still count as cover.
     */
    public static boolean canopyOnly(int topYNoLeaves, int feetY) {
        return topYNoLeaves <= feetY;
    }

    /** The entire overhead column must be air, leaves or logs, not just its nearest block. */
    public static boolean canopyColumn(int topY, int feetY, IntPredicate isAirLeavesOrLogsAtY) {
        if (isAirLeavesOrLogsAtY == null || topY - feetY > MAX_CANOPY_HEIGHT) {
            return false;
        }
        for (int y = feetY + 2; y < topY; y++) {
            if (!isAirLeavesOrLogsAtY.test(y)) {
                return false;
            }
        }
        return true;
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

    /**
     * The low-roof fallback of {@code SafePositionService.isRecoveryReadySurface}: a standable tile
     * with a low roof (tree overhang, porch) still counts as ready, but only when it is at the real
     * surface by the wide sample ({@code nearSurface}). {@code columnGap} is measured from the
     * tile's own column heightmap, so a covered pit capped a few blocks overhead passes the gap
     * check on its own; without {@code nearSurface} a bot at the bottom of a capped trench three
     * below the surrounding ground read as "at the surface" and nothing ever climbed it out.
     */
    public static boolean lowRoofReady(boolean standable,
                                       boolean lowRoof,
                                       boolean nearSurface,
                                       int columnGap,
                                       int cardinalStandableNeighbors,
                                       int totalStandableNeighbors,
                                       int steepDropNeighbors,
                                       int blockedCardinals,
                                       int localHeightVariance) {
        return standable
                && lowRoof
                && nearSurface
                && columnGap <= 4
                && cardinalStandableNeighbors >= 2
                && totalStandableNeighbors >= 3
                && steepDropNeighbors <= 1
                && blockedCardinals <= 2
                && localHeightVariance <= 6;
    }
}
