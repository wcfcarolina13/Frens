package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickIntervalsTest {

    @Test
    void naiveSubtractionFromMinValueOverflowsNegative() {
        // The bug this helper exists for (finding 32): never-set timestamps never pass the gate.
        long now = 12_000L;
        assertTrue(now - Long.MIN_VALUE < 0);
        assertFalse(now - Long.MIN_VALUE >= 200);
        assertTrue(now - Long.MIN_VALUE <= 200);
    }

    @Test
    void elapsedIsTrueWhenNeverSet() {
        assertTrue(TickIntervals.elapsed(0L, Long.MIN_VALUE, 200));
        assertTrue(TickIntervals.elapsed(12_000L, Long.MIN_VALUE, 200));
        assertTrue(TickIntervals.elapsed(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    void elapsedHonoursTheInterval() {
        assertFalse(TickIntervals.elapsed(1000L, 1000L, 200));
        assertFalse(TickIntervals.elapsed(1199L, 1000L, 200));
        assertTrue(TickIntervals.elapsed(1200L, 1000L, 200));
        assertTrue(TickIntervals.elapsed(5000L, 1000L, 200));
    }

    @Test
    void elapsedRearmsWhenTheClockWentBackwards() {
        assertTrue(TickIntervals.elapsed(50L, 5000L, 200));
    }

    @Test
    void elapsedWithZeroIntervalAlwaysPasses() {
        assertTrue(TickIntervals.elapsed(1000L, 1000L, 0));
    }

    @Test
    void withinIsFalseWhenNeverSet() {
        assertFalse(TickIntervals.within(0L, Long.MIN_VALUE, 100));
        assertFalse(TickIntervals.within(12_000L, Long.MIN_VALUE, 100));
        assertFalse(TickIntervals.within(12_000L, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    void withinHonoursTheWindow() {
        assertTrue(TickIntervals.within(1000L, 1000L, 100));
        assertTrue(TickIntervals.within(1100L, 1000L, 100));
        assertFalse(TickIntervals.within(1101L, 1000L, 100));
    }

    @Test
    void withinIsFalseForAFutureTimestamp() {
        assertFalse(TickIntervals.within(50L, 5000L, 100));
    }
}
