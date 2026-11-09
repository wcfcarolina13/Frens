package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSpawnActivityPolicyTest {

    private static final long NEWEST = 1_000_000_000L;

    @Test
    void aPutAwayBotStaysAway() {
        assertFalse(AutoSpawnActivityPolicy.bringBack(true, NEWEST, NEWEST));
        assertFalse(AutoSpawnActivityPolicy.bringBack(true, 0L, 0L));
    }

    @Test
    void onlyTheLastSessionsBotsComeBack() {
        assertTrue(AutoSpawnActivityPolicy.bringBack(false, NEWEST, NEWEST));
        assertTrue(AutoSpawnActivityPolicy.bringBack(false, NEWEST - AutoSpawnActivityPolicy.SESSION_WINDOW_MS, NEWEST));
        assertFalse(AutoSpawnActivityPolicy.bringBack(false, NEWEST - AutoSpawnActivityPolicy.SESSION_WINDOW_MS - 1, NEWEST));
        assertFalse(AutoSpawnActivityPolicy.bringBack(null, 0L, NEWEST)); // an untimed old entry beside timed ones
    }

    @Test
    void anOldSaveWithNoTimesKeepsTheOldBehaviour() {
        assertTrue(AutoSpawnActivityPolicy.bringBack(null, 0L, 0L));
    }
}
