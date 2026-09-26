package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    // --- 1.1.223 review: stagnation that drives the probe and the skip is movement only ---

    @Test
    void movementStagnationIgnoresBlockedRayTicks() {
        // Blocked-ray ticks are not an input: only the distance and block counters are.
        assertEquals(0, FollowDoorPriorityPolicy.movementStagnant(0, 0));
        assertEquals(5, FollowDoorPriorityPolicy.movementStagnant(5, 0));
        assertEquals(4, FollowDoorPriorityPolicy.movementStagnant(1, 4));
    }

    @Test
    void movingAlongRouteWithDirectRayBlockedAllowsSkipAndNoForcedDoorMode() {
        // A bot walking a planned route 10 blocks out: it closes on each waypoint (distance counter 0)
        // and changes block every tick or two, while the ray to the commander stays blocked for 20 ticks.
        int prior = 0;
        for (int tick = 0; tick < 20; tick++) {
            int blockedRayTicks = tick + 1; // would have reached 20; must not matter
            int movement = FollowDoorPriorityPolicy.movementStagnant(0, tick % 2);
            boolean rayBlocked = blockedRayTicks > 0;
            boolean directBlocked = FollowDoorPriorityPolicy.shouldProbeDirectBlocked(100.0D, prior, false) && rayBlocked;
            assertFalse(directBlocked, "no forced door mode at tick " + tick);
            assertTrue(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(true, directBlocked, 100.4D, false, false, movement),
                    "magnet skip allowed at tick " + tick);
            prior = movement;
        }
    }

    @Test
    void botPinnedAgainstFenceGetsDoorHandlingWithinPointEightSeconds() {
        // 21:26:00 — Jake pinned at the fence ~10 blocks out, not moving; follow ticks are 200 ms.
        int prior = 0;
        int firstDoorTick = -1;
        for (int tick = 0; tick < 10 && firstDoorTick < 0; tick++) {
            int movement = FollowDoorPriorityPolicy.movementStagnant(tick, tick);
            boolean directBlocked = FollowDoorPriorityPolicy.shouldProbeDirectBlocked(100.0D, prior, false);
            boolean skip = FollowDoorPriorityPolicy.shouldSkipDoorMagnet(true, directBlocked, 100.4D, false, false, movement);
            if (!skip) {
                firstDoorTick = tick;
            }
            prior = movement;
        }
        assertTrue(firstDoorTick >= 0 && firstDoorTick <= 4, "door handling at tick " + firstDoorTick);
    }

    @Test
    void blockFlickerOnABoundaryDoesNotResetMovementStagnation() {
        // Jake flickered between z 1284.95 and 1285.07: the block counter resets, the distance counter does not.
        assertEquals(6, FollowDoorPriorityPolicy.movementStagnant(6, 0));
        assertFalse(FollowDoorPriorityPolicy.shouldSkipDoorMagnet(true, false, 100.4D, false, false,
                FollowDoorPriorityPolicy.movementStagnant(6, 0)));
    }
}
