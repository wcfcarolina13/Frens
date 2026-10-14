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
}
