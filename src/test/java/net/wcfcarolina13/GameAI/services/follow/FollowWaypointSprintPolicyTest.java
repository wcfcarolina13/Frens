package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowWaypointSprintPolicyTest {

    @Test
    void straightRunKeepsSprint() {
        assertFalse(FollowWaypointSprintPolicy.turnsAt(0, 0, 3, 64, 0, new int[] {4, 64, 0}));
        assertTrue(FollowWaypointSprintPolicy.allowSprint(true, false));
    }

    @Test
    void aCornerAtTheWaypointDropsSprint() {
        assertTrue(FollowWaypointSprintPolicy.turnsAt(0, 0, 3, 64, 0, new int[] {3, 64, 2}));
        assertFalse(FollowWaypointSprintPolicy.allowSprint(true, true));
    }

    @Test
    void aHeightChangeAfterTheWaypointDropsSprint() {
        assertTrue(FollowWaypointSprintPolicy.turnsAt(0, 0, 3, 64, 0, new int[] {4, 63, 0}));
        assertTrue(FollowWaypointSprintPolicy.turnsAt(0, 0, 3, 64, 0, new int[] {4, 65, 0}));
    }

    @Test
    void aDiagonalApproachIntoAnAxisTurnDropsSprint() {
        assertTrue(FollowWaypointSprintPolicy.turnsAt(0, 0, 3, 64, 3, new int[] {4, 64, 3}));
    }

    @Test
    void theLastWaypointHasNoTurn() {
        assertFalse(FollowWaypointSprintPolicy.turnsAt(0, 0, 3, 64, 0, null));
    }

    @Test
    void inTheWaypointColumnAnyHeadingCountsAsATurn() {
        assertTrue(FollowWaypointSprintPolicy.turnsAt(3, 0, 3, 64, 0, new int[] {4, 64, 0}));
    }

    @Test
    void tooCloseNeverSprints() {
        assertFalse(FollowWaypointSprintPolicy.allowSprint(false, false));
    }
}
