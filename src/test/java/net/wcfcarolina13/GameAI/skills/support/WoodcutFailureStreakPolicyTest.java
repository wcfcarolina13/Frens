package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the woodcut consecutive-failure streak. No net.minecraft types. */
class WoodcutFailureStreakPolicyTest {

    private static final String PARTIAL = WoodcutFailureStreakPolicy.CLUSTER_LOGS_REMAINED;

    @Test
    void successResets() {
        assertEquals(0, WoodcutFailureStreakPolicy.next(4, true, "success", 7));
        assertEquals(0, WoodcutFailureStreakPolicy.next(4, true, "all-logs-cleared", 0));
    }

    @Test
    void partialWithLogsResets() {
        assertEquals(0, WoodcutFailureStreakPolicy.next(5, false, PARTIAL, 4));
        assertTrue(WoodcutFailureStreakPolicy.isProductivePartial(PARTIAL, 1));
    }

    @Test
    void partialWithNoLogsCounts() {
        assertEquals(3, WoodcutFailureStreakPolicy.next(2, false, PARTIAL, 0));
        assertFalse(WoodcutFailureStreakPolicy.isProductivePartial(PARTIAL, 0));
    }

    @Test
    void otherDetailsCount() {
        assertEquals(1, WoodcutFailureStreakPolicy.next(0, false, "no-reachable-stand", 3));
        assertEquals(6, WoodcutFailureStreakPolicy.next(5, false, "trunk-never-started", 0));
        assertEquals(1, WoodcutFailureStreakPolicy.next(0, false, null, 2));
        assertFalse(WoodcutFailureStreakPolicy.isProductivePartial("no-reachable-stand", 3));
    }

    @Test
    void sixPartialsNeverReachSix() {
        int streak = 0;
        for (int i = 0; i < 6; i++) {
            streak = WoodcutFailureStreakPolicy.next(streak, false, PARTIAL, 4 + i);
            assertTrue(streak < 6);
        }
        assertEquals(0, streak);
    }
}
