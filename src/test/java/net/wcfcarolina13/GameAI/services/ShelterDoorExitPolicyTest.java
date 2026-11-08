package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShelterDoorExitPolicyTest {

    @Test
    void genericAndJoinEnclosedTryTheDoorFirst() {
        assertTrue(ShelterDoorExitPolicy.tryDoorFirst(null));
        assertTrue(ShelterDoorExitPolicy.tryDoorFirst("JOIN_ENCLOSED"));
    }

    @Test
    void sheltersTheBotBuiltKeepTheirOwnExit() {
        // Names must match BotFleeService.ShelterType.
        assertFalse(ShelterDoorExitPolicy.tryDoorFirst("DIG_DOWN"));
        assertFalse(ShelterDoorExitPolicy.tryDoorFirst("CLIFF"));
        assertFalse(ShelterDoorExitPolicy.tryDoorFirst("WALL_OFF"));
        assertFalse(ShelterDoorExitPolicy.tryDoorFirst("VILLAGE_HOUSE"));
    }

    @Test
    void crossingMeansEndingOnTheOtherSide() {
        assertTrue(ShelterDoorExitPolicy.crossedDoorway(10, 8, 11));
        assertTrue(ShelterDoorExitPolicy.crossedDoorway(10, 12, 9));
        assertTrue(ShelterDoorExitPolicy.crossedDoorway(-3, -1, -5));
    }

    @Test
    void stoppingShortOrInTheDoorIsNotCrossing() {
        assertFalse(ShelterDoorExitPolicy.crossedDoorway(10, 8, 9));   // still inside
        assertFalse(ShelterDoorExitPolicy.crossedDoorway(10, 8, 10));  // in the doorway
        assertFalse(ShelterDoorExitPolicy.crossedDoorway(10, 8, 7));   // walked away from it
        assertFalse(ShelterDoorExitPolicy.crossedDoorway(10, 10, 11)); // started in the doorway
    }

    @Test
    void nearestDoorWins() {
        assertTrue(ShelterDoorExitPolicy.nearer(9, -1));
        assertTrue(ShelterDoorExitPolicy.nearer(4, 9));
        assertFalse(ShelterDoorExitPolicy.nearer(9, 9));
        assertFalse(ShelterDoorExitPolicy.nearer(16, 9));
    }

    @Test
    void farSideIsAwayFromTheBot() {
        assertEquals(1, ShelterDoorExitPolicy.farSign(10, 7));
        assertEquals(-1, ShelterDoorExitPolicy.farSign(10, 13));
        assertEquals(0, ShelterDoorExitPolicy.farSign(10, 10));
    }
}
