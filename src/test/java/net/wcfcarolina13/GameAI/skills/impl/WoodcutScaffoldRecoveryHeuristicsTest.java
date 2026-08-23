package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WoodcutScaffoldRecoveryHeuristicsTest {

    @Test
    void undergroundPositionTriggersSurfaceRecovery() {
        assertTrue(WoodcutScaffoldRecoveryHeuristics.shouldRecoverSurface(58, 69, 64, false));
    }

    @Test
    void surfaceTreeCanopyDoesNotForceRecovery() {
        assertFalse(WoodcutScaffoldRecoveryHeuristics.shouldRecoverSurface(63, 69, 64, false));
    }

    @Test
    void pillarRecoveryStepsAreBoundedByAvailableStock() {
        assertEquals(3, WoodcutScaffoldRecoveryHeuristics.desiredPillarRecoverySteps(60, 64, 3));
        assertEquals(0, WoodcutScaffoldRecoveryHeuristics.desiredPillarRecoverySteps(64, 64, 5));
    }

    @Test
    void cleanupBelowBaseRequestsReposition() {
        assertTrue(WoodcutScaffoldRecoveryHeuristics.shouldRepositionForCleanup(60, 64, 64, false));
    }
}
