package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedstoneDoorPolicyTest {
    @Test
    void plateOpenedDoorIsNeverClaimedOrThrottled() {
        var door = new RedstoneDoorPolicy(false, true, true, false, false, true, true);
        assertFalse(door.shouldScheduleOwnClose());
        assertFalse(door.shouldSetAlreadyOpenCooldown());
        assertFalse(door.shouldMarkRecentlyClosed());
    }

    @Test
    void powerAtCloseTimePreventsClick() {
        assertFalse(new RedstoneDoorPolicy(false, true, true, false, true, false, false).mayCloseNow());
    }

    @Test
    void botOpenedOrdinaryDoorCanCloseAndMarkIt() {
        var open = new RedstoneDoorPolicy(false, true, false, true, true, false, false);
        var closed = new RedstoneDoorPolicy(false, false, false, true, true, false, false);
        assertTrue(open.shouldScheduleOwnClose());
        assertTrue(open.mayCloseNow());
        assertTrue(closed.shouldMarkRecentlyClosed());
    }

    @Test
    void lockedDoorCannotBeHandOpened() {
        assertFalse(new RedstoneDoorPolicy(true, false, false, true, false, false, false).mayHandOpen());
    }

    @Test
    void unpoweredControlledWoodenDoorCanBeHandOpened() {
        assertTrue(new RedstoneDoorPolicy(false, false, false, true, false, true, true).mayHandOpen());
    }
}
