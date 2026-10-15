package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.LeashTargetPolicy.Event;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeashTargetPolicyTest {

    @Test
    void tyingToAFenceForgetsTheTarget() {
        assertTrue(LeashTargetPolicy.shouldClear(Event.TIED_TO_FENCE, false));
    }

    @Test
    void tyingToAFenceKeepsATargetTheBotStillHolds() {
        assertFalse(LeashTargetPolicy.shouldClear(Event.TIED_TO_FENCE, true));
    }

    @Test
    void holdingTheMountOnALeadKeepsTheTarget() {
        assertFalse(LeashTargetPolicy.shouldClear(Event.HELD_BY_BOT, false));
        assertFalse(LeashTargetPolicy.shouldClear(Event.HELD_BY_BOT, true));
    }

    @Test
    void deathForgetsTheTarget() {
        assertTrue(LeashTargetPolicy.shouldClear(Event.DIED, false));
        assertTrue(LeashTargetPolicy.shouldClear(Event.DIED, true));
    }

    @Test
    void disconnectKeepsTheTargetForRejoin() {
        assertFalse(LeashTargetPolicy.shouldClear(Event.DISCONNECT, false));
    }

    @Test
    void nullEventKeepsTheTarget() {
        assertFalse(LeashTargetPolicy.shouldClear(null, false));
    }
}
