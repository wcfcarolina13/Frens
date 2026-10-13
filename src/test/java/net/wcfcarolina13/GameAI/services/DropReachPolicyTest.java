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
    void idleSweepTakesLowItemsWithoutReadingTheWorld() {
        java.util.function.BooleanSupplier mustNotRun = () -> {
            throw new AssertionError("stand check must not run for dy <= 2");
        };
        assertTrue(DropReachPolicy.isIdleSweepReachable(0, mustNotRun));
        assertTrue(DropReachPolicy.isIdleSweepReachable(2, mustNotRun));
        assertTrue(DropReachPolicy.isIdleSweepReachable(-6, mustNotRun));
    }

    @Test
    void idleSweepTakesHighItemsOnlyWithAStand() {
        assertTrue(DropReachPolicy.isIdleSweepReachable(3, () -> true));
        assertTrue(DropReachPolicy.isIdleSweepReachable(10, () -> true));
        assertFalse(DropReachPolicy.isIdleSweepReachable(3, () -> false));
        assertFalse(DropReachPolicy.isIdleSweepReachable(10, () -> false));
    }

    @Test
    void idleSweepStillDropsDeepRavinesEvenWithAStand() {
        assertFalse(DropReachPolicy.isIdleSweepReachable(-7, () -> true));
        assertFalse(DropReachPolicy.isIdleSweepReachable(-20, () -> true));
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
