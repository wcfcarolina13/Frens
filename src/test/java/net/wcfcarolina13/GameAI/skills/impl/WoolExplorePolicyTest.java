package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WoolExplorePolicyTest {

    @Test
    void keepsTryingBelowThreeStuckCycles() {
        assertFalse(WoolExplorePolicy.shouldGiveUp(0));
        assertFalse(WoolExplorePolicy.shouldGiveUp(1));
        assertFalse(WoolExplorePolicy.shouldGiveUp(2));
    }

    @Test
    void givesUpAtThreeStuckCycles() {
        assertEquals(3, WoolExplorePolicy.MAX_STUCK_CYCLES);
        assertTrue(WoolExplorePolicy.shouldGiveUp(3));
        assertTrue(WoolExplorePolicy.shouldGiveUp(10));
    }

    @Test
    void pausesBetweenStuckCycles() {
        assertTrue(WoolExplorePolicy.STUCK_PAUSE_MS >= 1000L);
    }
}
