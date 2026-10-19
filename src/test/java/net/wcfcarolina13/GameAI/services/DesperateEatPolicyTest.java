package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesperateEatPolicyTest {

    @Test
    void firstAttemptIsDueThenWaitsTwoSeconds() {
        Map<String, Long> m = new ConcurrentHashMap<>();
        assertTrue(DesperateEatPolicy.attemptDue(m, "bot", 10_000L));
        assertFalse(DesperateEatPolicy.attemptDue(m, "bot", 10_050L)); // next tick
        assertFalse(DesperateEatPolicy.attemptDue(m, "bot", 11_999L));
        assertTrue(DesperateEatPolicy.attemptDue(m, "bot", 12_000L));
    }

    @Test
    void twentyTicksASecondForSevenSecondsGivesFourAttempts() {
        Map<String, Long> m = new ConcurrentHashMap<>();
        int attempts = 0;
        for (long t = 0; t < 7_000L; t += 50L) {
            if (DesperateEatPolicy.attemptDue(m, "bot", t)) attempts++;
        }
        assertEquals(4, attempts); // 0, 2000, 4000, 6000
    }

    @Test
    void botsAreIndependent() {
        Map<String, Long> m = new ConcurrentHashMap<>();
        assertTrue(DesperateEatPolicy.attemptDue(m, "a", 0L));
        assertTrue(DesperateEatPolicy.attemptDue(m, "b", 100L));
        assertFalse(DesperateEatPolicy.attemptDue(m, "a", 100L));
    }

    @Test
    void logLineAtMostOncePerTenSeconds() {
        Map<String, Long> m = new ConcurrentHashMap<>();
        assertTrue(DesperateEatPolicy.logDue(m, "bot", 0L));
        assertFalse(DesperateEatPolicy.logDue(m, "bot", 2_000L));
        assertFalse(DesperateEatPolicy.logDue(m, "bot", 9_999L));
        assertTrue(DesperateEatPolicy.logDue(m, "bot", 10_000L));
    }

    @Test
    void clockGoingBackCountsAsDue() {
        Map<String, Long> m = new ConcurrentHashMap<>();
        assertTrue(DesperateEatPolicy.attemptDue(m, "bot", 50_000L));
        assertTrue(DesperateEatPolicy.attemptDue(m, "bot", 1_000L));
    }
}
