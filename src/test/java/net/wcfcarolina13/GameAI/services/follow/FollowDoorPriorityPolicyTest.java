package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowDoorPriorityPolicyTest {

    @Test
    void stagnantBotAtTenBlocksProbesDirectRoute() {
        assertTrue(FollowDoorPriorityPolicy.shouldProbeDirectBlocked(100.0D, 4));
    }

    @Test
    void movingBotAtTenBlocksDoesNotProbe() {
        assertFalse(FollowDoorPriorityPolicy.shouldProbeDirectBlocked(100.0D, 0));
    }

    @Test
    void nearGoalAlwaysProbes() {
        assertTrue(FollowDoorPriorityPolicy.shouldProbeDirectBlocked(30.0D, 0));
        assertTrue(FollowDoorPriorityPolicy.shouldProbeDirectBlocked(36.0D, 0));
    }

    @Test
    void probeStartsAtThreeStagnantTicks() {
        assertFalse(FollowDoorPriorityPolicy.shouldProbeDirectBlocked(100.0D, 2));
        assertTrue(FollowDoorPriorityPolicy.shouldProbeDirectBlocked(100.0D, 3));
    }

    @Test
    void activeDoorPlanKeepsProbingWhileBotWalksToTheDoor() {
        assertTrue(FollowDoorPriorityPolicy.shouldProbeDirectBlocked(100.0D, 0, true));
        assertFalse(FollowDoorPriorityPolicy.shouldProbeDirectBlocked(100.0D, 0, false));
    }

    @Test
    void stagnantBotBehindFenceKeepsDoorHandling() {
        // 21:26:00 — Jake 10.02 blocks away, commander visible through the fence, pinned for 1+ s.
        assertFalse(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(true, false, 100.4D, false, false, 6));
    }

    @Test
    void movingBotWithVisibleCommanderSkipsDoorMagnet() {
        assertTrue(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(true, false, 100.4D, false, false, 0));
    }

    @Test
    void skipStopsAtFourStagnantTicks() {
        assertTrue(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(true, false, 100.4D, false, false, 3));
        assertFalse(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(true, false, 100.4D, false, false, 4));
    }

    @Test
    void directlyBlockedNeverSkips() {
        assertFalse(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(true, true, 100.4D, false, false, 0));
    }

    @Test
    void sealedSpaceNeverSkips() {
        assertFalse(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(true, false, 100.4D, true, false, 0));
        assertFalse(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(true, false, 100.4D, false, true, 0));
    }

    @Test
    void unseenCommanderSkipsOnlyAtLongRange() {
        assertFalse(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(false, false, 400.0D, false, false, 0));
        assertTrue(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(false, false, 900.0D, false, false, 0));
        assertFalse(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(false, false, 900.0D, false, false, 5));
    }
}
