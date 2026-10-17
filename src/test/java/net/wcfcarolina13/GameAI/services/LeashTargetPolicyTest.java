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

    @Test
    void fenceTieForgetsTheSavedHeldRecordForThatMount() {
        assertTrue(LeashTargetPolicy.shouldForgetHeldRecord(Event.TIED_TO_FENCE, true, true));
    }

    @Test
    void fenceTieLeavesARecordForAnotherMount() {
        assertFalse(LeashTargetPolicy.shouldForgetHeldRecord(Event.TIED_TO_FENCE, true, false));
    }

    @Test
    void deathForgetsAnyHeldRecord() {
        assertTrue(LeashTargetPolicy.shouldForgetHeldRecord(Event.DIED, true, false));
    }

    @Test
    void recordsNotHeldAreNeverRewritten() {
        assertFalse(LeashTargetPolicy.shouldForgetHeldRecord(Event.TIED_TO_FENCE, false, true));
        assertFalse(LeashTargetPolicy.shouldForgetHeldRecord(Event.DIED, false, false));
    }

    @Test
    void holdingOrLeavingKeepsTheHeldRecord() {
        assertFalse(LeashTargetPolicy.shouldForgetHeldRecord(Event.HELD_BY_BOT, true, true));
        assertFalse(LeashTargetPolicy.shouldForgetHeldRecord(Event.DISCONNECT, true, true));
        assertFalse(LeashTargetPolicy.shouldForgetHeldRecord(null, true, true));
    }

    @Test
    void releaseForgetsTheTarget() {
        assertTrue(LeashTargetPolicy.shouldClear(Event.RELEASED, false));
        assertTrue(LeashTargetPolicy.shouldClear(Event.RELEASED, true));
    }

    @Test
    void releaseForgetsOnlyTheHeldRecordOfAReleasedMount() {
        assertTrue(LeashTargetPolicy.shouldForgetHeldRecord(Event.RELEASED, true, true));
        assertFalse(LeashTargetPolicy.shouldForgetHeldRecord(Event.RELEASED, true, false));
        assertFalse(LeashTargetPolicy.shouldForgetHeldRecord(Event.RELEASED, false, true));
    }

    @Test
    void aLostLeadForgetsTheTarget() {
        assertTrue(LeashTargetPolicy.shouldClear(Event.LEAD_LOST, false));
        assertTrue(LeashTargetPolicy.shouldClear(Event.LEAD_LOST, true));
    }

    @Test
    void aLostLeadForgetsTheHeldRecordOnlyForThatMount() {
        assertTrue(LeashTargetPolicy.shouldForgetHeldRecord(Event.LEAD_LOST, true, true));
        assertFalse(LeashTargetPolicy.shouldForgetHeldRecord(Event.LEAD_LOST, true, false));
        assertFalse(LeashTargetPolicy.shouldForgetHeldRecord(Event.LEAD_LOST, false, true));
    }
}
