package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WoodcutCanopyHeuristicsTest {

    @Test
    void unsafeCanopyAssessmentRejectsExposedLeafPerch() {
        assertTrue(WoodcutCanopyHeuristics.isUnsafeCanopyAssessment(1, 2, 2, 3, true, false));
        assertTrue(WoodcutCanopyHeuristics.isUnsafeCanopyAssessment(1, 2, 2, 3, false, true));
    }

    @Test
    void unsafeCanopyAssessmentAllowsStableSupportedCanopy() {
        assertFalse(WoodcutCanopyHeuristics.isUnsafeCanopyAssessment(2, 5, 1, 1, true, false));
        assertFalse(WoodcutCanopyHeuristics.isUnsafeCanopyAssessment(2, 5, 1, 1, false, false));
    }
}
