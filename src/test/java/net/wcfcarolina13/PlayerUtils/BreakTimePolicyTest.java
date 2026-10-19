package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakTimePolicyTest {

    private static final float HAND = 1.0f;

    @Test
    void planksByHandTakeSixtyTicks() {
        float delta = BreakTimePolicy.delta(HAND, 2.0f, true);
        assertEquals(60, BreakTimePolicy.requiredTicks(delta));
        assertFalse(BreakTimePolicy.isInstant(delta));
    }

    @Test
    void planksWithWoodenAxeTakeThirtyTicks() {
        assertEquals(30, BreakTimePolicy.requiredTicks(BreakTimePolicy.delta(2.0f, 2.0f, true)));
    }

    @Test
    void leavesByHandTakeSixTicks() {
        assertEquals(6, BreakTimePolicy.requiredTicks(BreakTimePolicy.delta(HAND, 0.2f, true)));
    }

    @Test
    void stoneByHandTakesOneHundredFiftyTicks() {
        // Stone needs a pickaxe to drop, so bare hands get the /100 penalty.
        float delta = BreakTimePolicy.delta(HAND, 1.5f, false);
        assertEquals(150, BreakTimePolicy.requiredTicks(delta));
    }

    @Test
    void dirtByHandTakesFifteenTicks() {
        assertEquals(15, BreakTimePolicy.requiredTicks(BreakTimePolicy.delta(HAND, 0.5f, true)));
    }

    @Test
    void hardnessZeroIsInstant() {
        float delta = BreakTimePolicy.delta(HAND, 0.0f, true);
        assertTrue(BreakTimePolicy.isInstant(delta));
        assertEquals(1, BreakTimePolicy.requiredTicks(delta));
    }

    @Test
    void shearsOnLeavesAreInstant() {
        // Shears mine leaves at speed 15: 15 / 0.2 / 30 = 2.5.
        float delta = BreakTimePolicy.delta(15.0f, 0.2f, true);
        assertTrue(BreakTimePolicy.isInstant(delta));
        assertEquals(1, BreakTimePolicy.requiredTicks(delta));
    }

    @Test
    void deltaOfOneOrMoreIsInstant() {
        assertTrue(BreakTimePolicy.isInstant(1.0f));
        assertTrue(BreakTimePolicy.isInstant(4.0f));
        assertFalse(BreakTimePolicy.isInstant(0.99f));
        assertEquals(1, BreakTimePolicy.requiredTicks(1.0f));
    }

    @Test
    void unbreakableHasZeroDelta() {
        assertEquals(0.0f, BreakTimePolicy.delta(HAND, -1.0f, true));
        assertFalse(BreakTimePolicy.isInstant(0.0f));
    }

    @Test
    void timeoutHasATwelveSecondFloorPlusFiveSecondsSlack() {
        assertEquals(12_000L, BreakTimePolicy.timeoutMs(1));
        assertEquals(12_000L, BreakTimePolicy.timeoutMs(60));
        assertEquals(15_000L, BreakTimePolicy.timeoutMs(200));
    }

    @Test
    void slowerToolExtendsDeadlineFromNow() {
        // Started with 40 ticks (12 s floor) at t=0; at t=10 s a swap needs 300 ticks (20 s).
        long deadline = BreakTimePolicy.timeoutMs(40);
        assertEquals(30_000L, BreakTimePolicy.extendDeadline(deadline, 10_000L, 300));
    }

    @Test
    void fasterToolNeverShortensDeadline() {
        long deadline = 20_000L;
        assertEquals(20_000L, BreakTimePolicy.extendDeadline(deadline, 1_000L, 10));
    }

    @Test
    void extendDeadlineUsesTheTimeoutFloor() {
        // 1 tick still gets the 12 s floor from now.
        assertEquals(17_000L, BreakTimePolicy.extendDeadline(0L, 5_000L, 1));
    }
}
