package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSpawnPolicyTest {

    @Test
    void integratedServerDefersUntilHostJoins() {
        assertTrue(AutoSpawnPolicy.deferUntilHostJoins(false));
    }

    @Test
    void dedicatedServerSpawnsAtStartup() {
        assertFalse(AutoSpawnPolicy.deferUntilHostJoins(true));
    }

    @Test
    void singleplayerRoomKeepsLastSlotFree() {
        assertTrue(AutoSpawnPolicy.hasRoomForAutoSpawn(0, 8));
        assertTrue(AutoSpawnPolicy.hasRoomForAutoSpawn(6, 8));
        assertFalse(AutoSpawnPolicy.hasRoomForAutoSpawn(7, 8));
        assertFalse(AutoSpawnPolicy.hasRoomForAutoSpawn(8, 8));
        assertFalse(AutoSpawnPolicy.hasRoomForAutoSpawn(9, 8));
    }

    @Test
    void tinyOrInvalidMaxHasNoRoom() {
        assertFalse(AutoSpawnPolicy.hasRoomForAutoSpawn(0, 1));
        assertFalse(AutoSpawnPolicy.hasRoomForAutoSpawn(0, 0));
        assertFalse(AutoSpawnPolicy.hasRoomForAutoSpawn(0, -5));
        assertTrue(AutoSpawnPolicy.hasRoomForAutoSpawn(0, 2));
        assertFalse(AutoSpawnPolicy.hasRoomForAutoSpawn(1, 2));
    }

    @Test
    void dedicatedTwentySlots() {
        assertTrue(AutoSpawnPolicy.hasRoomForAutoSpawn(18, 20));
        assertFalse(AutoSpawnPolicy.hasRoomForAutoSpawn(19, 20));
    }

    @Test
    void negativeCurrentTreatedAsZero() {
        assertTrue(AutoSpawnPolicy.hasRoomForAutoSpawn(-3, 8));
    }

    @Test
    void effectiveCountCoversInFlightSpawns() {
        assertEquals(3, AutoSpawnPolicy.effectivePlayerCount(1, 1, 2));
        assertEquals(4, AutoSpawnPolicy.effectivePlayerCount(4, 1, 2));
        assertEquals(1, AutoSpawnPolicy.effectivePlayerCount(1, 1, 0));
        assertEquals(1, AutoSpawnPolicy.effectivePlayerCount(1, 1, -2));
    }

    @Test
    void loggedOffBotIsLeftToTheWakeMonitor() {
        assertEquals(AutoSpawnPolicy.Skip.LOGGED_OFF_FOR_NIGHT, AutoSpawnPolicy.skipReason(false, true, false));
        assertEquals(AutoSpawnPolicy.Skip.LOGGED_OFF_FOR_NIGHT, AutoSpawnPolicy.skipReason(false, true, true));
    }

    @Test
    void onlineBotIsNeverSpawnedTwice() {
        // The wake monitor may have brought it back between queueing and running.
        assertEquals(AutoSpawnPolicy.Skip.ALREADY_ONLINE, AutoSpawnPolicy.skipReason(true, true, false));
        assertEquals(AutoSpawnPolicy.Skip.ALREADY_ONLINE, AutoSpawnPolicy.skipReason(true, false, true));
    }

    @Test
    void inTransitAndPlainCases() {
        assertEquals(AutoSpawnPolicy.Skip.IN_TRANSIT, AutoSpawnPolicy.skipReason(false, false, true));
        assertEquals(AutoSpawnPolicy.Skip.NONE, AutoSpawnPolicy.skipReason(false, false, false));
    }
}
