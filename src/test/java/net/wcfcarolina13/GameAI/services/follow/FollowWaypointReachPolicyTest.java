package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowWaypointReachPolicyTest {

    @Test
    void inColumnIsReached() {
        assertTrue(FollowWaypointReachPolicy.reached(5, 64, 5, 5, 64, 5, null));
        assertTrue(FollowWaypointReachPolicy.reached(5, 65, 5, 5, 64, 5, null));
        assertTrue(FollowWaypointReachPolicy.reached(5, 63, 5, 5, 64, 5, null));
    }

    @Test
    void inColumnButTwoAwayVerticallyIsNot() {
        assertFalse(FollowWaypointReachPolicy.reached(5, 66, 5, 5, 64, 5, null));
    }

    @Test
    void diagonalNeighbourIsNotReached() {
        assertFalse(FollowWaypointReachPolicy.reached(5, 64, 5, 6, 64, 6, new int[] {7, 64, 7}));
    }

    @Test
    void oneDownAndOverIsNotReached() {
        assertFalse(FollowWaypointReachPolicy.reached(5, 64, 5, 6, 63, 5, new int[] {7, 63, 5}));
    }

    @Test
    void levelNeighbourWithoutNextIsNotReached() {
        assertFalse(FollowWaypointReachPolicy.reached(5, 64, 5, 6, 64, 5, null));
    }

    @Test
    void levelNeighbourStraightAheadContinuingSameAxisIsReached() {
        assertTrue(FollowWaypointReachPolicy.reached(5, 64, 5, 6, 64, 5, new int[] {7, 64, 5}));
        assertTrue(FollowWaypointReachPolicy.reached(5, 64, 5, 5, 64, 4, new int[] {5, 64, 3}));
    }

    @Test
    void levelNeighbourWhoseNextTurnsIsNotReached() {
        assertFalse(FollowWaypointReachPolicy.reached(5, 64, 5, 6, 64, 5, new int[] {6, 64, 6}));
    }

    @Test
    void levelNeighbourWhoseNextStepsDownIsNotReached() {
        assertFalse(FollowWaypointReachPolicy.reached(5, 64, 5, 6, 64, 5, new int[] {7, 63, 5}));
    }

    @Test
    void levelNeighbourWhoseNextDoublesBackIsNotReached() {
        assertFalse(FollowWaypointReachPolicy.reached(5, 64, 5, 6, 64, 5, new int[] {5, 64, 5}));
    }

    @Test
    void stoppedOneBlockShortOfACornerIsNotReached() {
        // The 1.1.277 deadlock: bot one block before the waypoint, next waypoint turns.
        assertFalse(FollowWaypointReachPolicy.reached(359, 121, 661, 360, 121, 661, new int[] {360, 121, 664}));
    }

    @Test
    void lastLiveWaypointUsesTheOldReach() {
        // Player's block one level step away: reached without entering the player's column.
        assertTrue(FollowWaypointReachPolicy.reachedLastLive(5, 64, 5, 6, 64, 5));
        assertTrue(FollowWaypointReachPolicy.reachedLastLive(5, 64, 5, 5, 64, 5));
        // Diagonal (distSq 2) and two away (distSq 4).
        assertTrue(FollowWaypointReachPolicy.reachedLastLive(5, 64, 5, 6, 64, 6));
        assertFalse(FollowWaypointReachPolicy.reachedLastLive(5, 64, 5, 7, 64, 5));
        assertFalse(FollowWaypointReachPolicy.reachedLastLive(5, 64, 5, 6, 65, 6));
    }

    @Test
    void lastLiveWaypointStopsAtPersonalSpaceOthersInTheColumn() {
        assertEquals(3.0D, FollowWaypointReachPolicy.stopDistance(true, 3.0D, 0.3D), 1e-9);
        assertEquals(0.3D, FollowWaypointReachPolicy.stopDistance(false, 3.0D, 0.3D), 1e-9);
        assertEquals(0.3D, FollowWaypointReachPolicy.stopDistance(true, 0.0D, 0.3D), 1e-9);
    }
}
