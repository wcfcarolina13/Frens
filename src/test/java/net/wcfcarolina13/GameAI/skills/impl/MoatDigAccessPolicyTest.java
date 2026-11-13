package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoatDigAccessPolicyTest {
    @Test void profile119Keeps118When119IsAlsoPlanned() {
        assertFalse(MoatDigAccessPolicy.dropCoveredClear(true, true, true));
    }
    @Test void staleProfileClearUnderRetainedGroundIsDropped() {
        assertTrue(MoatDigAccessPolicy.dropCoveredClear(true, true, false));
    }
    @Test void exposedClearAndMoatDigAreNeverDropped() {
        assertFalse(MoatDigAccessPolicy.dropCoveredClear(true, false, false));
        assertFalse(MoatDigAccessPolicy.dropCoveredClear(false, true, false));
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
