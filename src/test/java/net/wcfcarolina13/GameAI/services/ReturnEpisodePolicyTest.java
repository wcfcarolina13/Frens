package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReturnEpisodePolicyTest {

    @Test
    void sameBaseWhileReturningIsNotANewReturn() {
        assertFalse(ReturnEpisodePolicy.isNewReturn(true, 100.5, -20.5, 100.5, -20.5));
        assertFalse(ReturnEpisodePolicy.isNewReturn(true, 100.5, -20.5, 102.0, -20.5)); // 1.5 blocks
    }

    @Test
    void baseFourBlocksAwayIsANewReturn() {
        assertTrue(ReturnEpisodePolicy.isNewReturn(true, 100.5, -20.5, 104.5, -20.5));
    }

    @Test
    void exactlyThreeBlocksAwayIsANewReturn() {
        // Same threshold as the old guard: distSq < 9 is the same base.
        assertTrue(ReturnEpisodePolicy.isNewReturn(true, 0.0, 0.0, 3.0, 0.0));
    }

    @Test
    void notReturningIsAlwaysANewReturn() {
        assertTrue(ReturnEpisodePolicy.isNewReturn(false, 100.5, -20.5, 100.5, -20.5));
    }
}
