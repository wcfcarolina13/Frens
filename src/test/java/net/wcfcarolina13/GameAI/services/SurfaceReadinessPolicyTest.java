package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceReadinessPolicyTest {

    @Test
    void openSkyFlatGroundIsOperational() {
        assertTrue(SurfaceReadinessPolicy.isOperational(true, false, true, true, 4, 8, 0, 0));
    }

    @Test
    void canopyOnlyFlatGroundIsOperational() {
        assertTrue(SurfaceReadinessPolicy.isOperational(false, true, true, true, 4, 8, 0, 0));
    }

    @Test
    void coveredGroundIsNotOperational() {
        assertFalse(SurfaceReadinessPolicy.isOperational(false, false, true, true, 4, 8, 0, 0));
    }

    @Test
    void canopyStillNeedsStandableNearSurfaceAndNeighbours() {
        assertFalse(SurfaceReadinessPolicy.isOperational(false, true, false, true, 4, 8, 0, 0));
        assertFalse(SurfaceReadinessPolicy.isOperational(false, true, true, false, 4, 8, 0, 0));
        assertFalse(SurfaceReadinessPolicy.isOperational(false, true, true, true, 1, 8, 0, 0));
        assertFalse(SurfaceReadinessPolicy.isOperational(false, true, true, true, 4, 3, 0, 0));
        assertFalse(SurfaceReadinessPolicy.isOperational(false, true, true, true, 4, 8, 2, 0));
        assertFalse(SurfaceReadinessPolicy.isOperational(false, true, true, true, 4, 8, 0, 3));
    }

    @Test
    void neighbourThresholdsMatchTheOperationalRule() {
        assertTrue(SurfaceReadinessPolicy.isOperational(true, false, true, true, 2, 4, 1, 2));
    }

    @Test
    void canopyOnlyWhenNoNonLeafBlockIsAboveTheFeet() {
        // Standing on the ground: the heightmap top equals the feet Y.
        assertTrue(SurfaceReadinessPolicy.canopyOnly(64, 64));
        // Standing on a pillar above the ground.
        assertTrue(SurfaceReadinessPolicy.canopyOnly(64, 67));
        // A log, stone ceiling or cave roof above.
        assertFalse(SurfaceReadinessPolicy.canopyOnly(65, 64));
        assertFalse(SurfaceReadinessPolicy.canopyOnly(80, 40));
    }

    @Test
    void coveredPitBelowTheWideSurfaceIsNotLowRoofReady() {
        // Jake, 1.1.236 sandbox: feet 62 in a capped trench, wide surface 65, cap 3 overhead.
        assertFalse(SurfaceReadinessPolicy.lowRoofReady(true, true, false, 3, 2, 3, 0, 1, 3));
    }

    @Test
    void lowRoofAtTheRealSurfaceIsReady() {
        // Tree overhang at ground level: same shape, but nearSurface by the wide sample.
        assertTrue(SurfaceReadinessPolicy.lowRoofReady(true, true, true, 3, 2, 3, 0, 1, 3));
    }

    @Test
    void lowRoofKeepsItsOtherThresholds() {
        assertTrue(SurfaceReadinessPolicy.lowRoofReady(true, true, true, 4, 2, 3, 1, 2, 6));
        assertFalse(SurfaceReadinessPolicy.lowRoofReady(false, true, true, 3, 2, 3, 0, 1, 3));
        assertFalse(SurfaceReadinessPolicy.lowRoofReady(true, false, true, 3, 2, 3, 0, 1, 3));
        assertFalse(SurfaceReadinessPolicy.lowRoofReady(true, true, true, 5, 2, 3, 0, 1, 3));
        assertFalse(SurfaceReadinessPolicy.lowRoofReady(true, true, true, 3, 1, 3, 0, 1, 3));
        assertFalse(SurfaceReadinessPolicy.lowRoofReady(true, true, true, 3, 2, 2, 0, 1, 3));
        assertFalse(SurfaceReadinessPolicy.lowRoofReady(true, true, true, 3, 2, 3, 2, 1, 3));
        assertFalse(SurfaceReadinessPolicy.lowRoofReady(true, true, true, 3, 2, 3, 0, 3, 3));
        assertFalse(SurfaceReadinessPolicy.lowRoofReady(true, true, true, 3, 2, 3, 0, 1, 7));
    }
}
