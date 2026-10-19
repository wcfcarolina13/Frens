package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WoodcutOutcomePolicyTest {

    private static final int MAX = 3;

    @Test
    void cuttingTheOnlyTreeThenFindingNoneIsNotRepeatedFailures() {
        // Finding 41: one tree cut, then empty scans pinned the streak at max.
        assertFalse(WoodcutOutcomePolicy.endedOnRepeatedFailures(MAX, MAX, true));
        assertTrue(WoodcutOutcomePolicy.ranOutOfTrees(1, MAX, MAX, true));
        assertEquals("Cut 1 tree(s). No more trees nearby.", WoodcutOutcomePolicy.ranOutOfTreesLine(1));
    }

    @Test
    void realReachFailuresStillEndOnRepeatedFailures() {
        assertTrue(WoodcutOutcomePolicy.endedOnRepeatedFailures(MAX, MAX, false));
        assertTrue(WoodcutOutcomePolicy.endedOnRepeatedFailures(MAX + 2, MAX, false));
        assertFalse(WoodcutOutcomePolicy.ranOutOfTrees(2, MAX, MAX, false));
    }

    @Test
    void aStreakBelowTheMaximumIsNeither() {
        assertFalse(WoodcutOutcomePolicy.endedOnRepeatedFailures(MAX - 1, MAX, false));
        assertFalse(WoodcutOutcomePolicy.ranOutOfTrees(2, MAX - 1, MAX, true));
    }

    @Test
    void runningOutWithNothingCutIsNotASuccess() {
        assertFalse(WoodcutOutcomePolicy.ranOutOfTrees(0, MAX, MAX, true));
    }
}
