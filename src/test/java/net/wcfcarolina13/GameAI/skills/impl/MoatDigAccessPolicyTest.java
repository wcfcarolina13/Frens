package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoatDigAccessPolicyTest {
    @Test void profile119Keeps118When119IsAlsoPlanned() {
        assertFalse(MoatDigAccessPolicy.dropCoveredClear(true, true, true, false));
    }
    @Test void staleProfileClearUnderRetainedGroundIsDropped() {
        assertTrue(MoatDigAccessPolicy.dropCoveredClear(true, true, false, false));
    }
    @Test void exposedClearAndMoatDigAreNeverDropped() {
        assertFalse(MoatDigAccessPolicy.dropCoveredClear(true, false, false, false));
        assertFalse(MoatDigAccessPolicy.dropCoveredClear(false, true, false, false));
    }
    @Test void trackedScaffoldCoverMustBeRemovedBeforeClearCanComplete() {
        assertFalse(MoatDigAccessPolicy.dropCoveredClear(true, true, false, true));
    }
    @Test void initiallyAirPlannedCoverRemainsDiggableAfterRefill() {
        var fullPlan = java.util.Set.of(118, 119);
        var initialSolidTargets = java.util.Set.of(118);
        // Y119 started air, then acquired a pillar or fallen gravel. Y118 is still solid.
        assertFalse(initialSolidTargets.contains(119));
        assertFalse(MoatDigAccessPolicy.dropCoveredClear(true, true, fullPlan.contains(119), false));
        assertTrue(MoatDigAccessPolicy.dropCoveredClear(true, true, initialSolidTargets.contains(119), false),
                "using only the initial solid targets would falsely discard Y118");
    }
    @Test void visibleLipOutranksInvisibleBankEvenWithMoreExits() {
        assertTrue(MoatDigAccessPolicy.visibilityScore(true, true, true)
                > MoatDigAccessPolicy.visibilityScore(true, false, false) + 4 * 120);
    }
    @Test void plannedCoverIsDugBeforeTheCellBeneathIt() {
        assertTrue(MoatDigAccessPolicy.compareHeight(119, 118) < 0);
        assertTrue(MoatDigAccessPolicy.compareHeight(117, 118) > 0);
        assertEquals(0, MoatDigAccessPolicy.compareHeight(118, 118));
    }
    @Test void orthogonalLipWinsWhenBothAreVisible() {
        assertTrue(MoatDigAccessPolicy.visibilityScore(true, true, true)
                > MoatDigAccessPolicy.visibilityScore(true, true, false));
    }
}
