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
    void lastLiveWaypointIsReachedWithinItsStopDistance() {
        // Bot at 5.5/5.5 (centre of block 5,5); stop distance 3.0 (personal space).
        assertTrue(FollowWaypointReachPolicy.reachedLastLive(5.5, 64, 5.5, 6, 64, 5, 3.0));
        assertTrue(FollowWaypointReachPolicy.reachedLastLive(5.5, 64, 5.5, 5, 64, 5, 3.0));
        assertTrue(FollowWaypointReachPolicy.reachedLastLive(5.5, 64, 5.5, 6, 64, 6, 3.0));
        assertTrue(FollowWaypointReachPolicy.reachedLastLive(5.5, 64, 5.5, 6, 65, 6, 3.0));
        // Too far horizontally, or two levels off.
        assertFalse(FollowWaypointReachPolicy.reachedLastLive(5.5, 64, 5.5, 9, 64, 5, 3.0));
        assertFalse(FollowWaypointReachPolicy.reachedLastLive(5.5, 64, 5.5, 6, 66, 5, 3.0));
        assertFalse(FollowWaypointReachPolicy.reachedLastLive(5.5, 64, 5.5, 6, 62, 5, 3.0));
    }

    @Test
    void botHaltedThreeShortOfTheLastWaypointCountsAsReached() {
        // The freeze: moveToward halts at horizontal distance 3.0 from the waypoint centre
        // (8.5, 5.5), and the old 3D <= 2.25 reach never dropped it.
        assertTrue(FollowWaypointReachPolicy.reachedLastLive(5.5, 64, 5.5, 8, 64, 5, 3.0));
        // Off-axis halt at exactly 3.0 as well.
        double h = 3.0 / Math.sqrt(2.0);
        assertTrue(FollowWaypointReachPolicy.reachedLastLive(8.5 - h, 64, 5.5 - h, 8, 64, 5, 3.0));
        // A standoff of 5 is honoured too.
        assertTrue(FollowWaypointReachPolicy.reachedLastLive(5.5, 64, 5.5, 10, 64, 5, 5.0));
        assertFalse(FollowWaypointReachPolicy.reachedLastLive(5.5, 64, 5.5, 10, 64, 5, 3.0));
    }

    @Test
    void lastLiveWaypointStopsAtPersonalSpaceOthersInTheColumn() {
        assertEquals(3.0D, FollowWaypointReachPolicy.stopDistance(true, 3.0D, 0.3D), 1e-9);
        assertEquals(0.3D, FollowWaypointReachPolicy.stopDistance(false, 3.0D, 0.3D), 1e-9);
        assertEquals(0.3D, FollowWaypointReachPolicy.stopDistance(true, 0.0D, 0.3D), 1e-9);
    }
}
