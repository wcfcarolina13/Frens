package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropReachPolicyTest {

    @Test
    void itemsUpToTwoAboveFeetAreReachable() {
        assertTrue(DropReachPolicy.isVerticallyReachable(0));
        assertTrue(DropReachPolicy.isVerticallyReachable(1));
        assertTrue(DropReachPolicy.isVerticallyReachable(2));
    }

    @Test
    void itemsThreeOrMoreAboveAreNotReachable() {
        assertFalse(DropReachPolicy.isVerticallyReachable(3));
        assertFalse(DropReachPolicy.isVerticallyReachable(20));
    }

    @Test
    void belowBoundKeepsSixButDropsDeepRavines() {
        assertTrue(DropReachPolicy.isVerticallyReachable(-1));
        assertTrue(DropReachPolicy.isVerticallyReachable(-6));
        assertFalse(DropReachPolicy.isVerticallyReachable(-7));
        assertFalse(DropReachPolicy.isVerticallyReachable(-20));
    }

    @Test
    void timeoutIsShortOnlyWhenOutOfReach() {
        assertEquals(200L, DropReachPolicy.noProgressTimeoutTicks(0));
        assertEquals(200L, DropReachPolicy.noProgressTimeoutTicks(2));
        assertEquals(200L, DropReachPolicy.noProgressTimeoutTicks(-6));
        assertEquals(60L, DropReachPolicy.noProgressTimeoutTicks(3));
        assertEquals(60L, DropReachPolicy.noProgressTimeoutTicks(-7));
    }

    @Test
    void retargetOnlyAfterMoreThanOneBlock() {
        assertFalse(DropReachPolicy.shouldRetarget(0.0D));
        assertFalse(DropReachPolicy.shouldRetarget(1.0D));
        assertTrue(DropReachPolicy.shouldRetarget(1.01D));
        assertTrue(DropReachPolicy.shouldRetarget(9.0D));
    }

    @Test
    void nudgeNeedsFeetAndHeadClear() {
        assertTrue(DropReachPolicy.isNudgeCellSafe(true, true));
        assertFalse(DropReachPolicy.isNudgeCellSafe(true, false));
        assertFalse(DropReachPolicy.isNudgeCellSafe(false, true));
        assertFalse(DropReachPolicy.isNudgeCellSafe(false, false));
    }
}
