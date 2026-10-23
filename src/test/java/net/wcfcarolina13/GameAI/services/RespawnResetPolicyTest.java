package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RespawnResetPolicyTest {

    @Test
    void aliveMeansTheBotLeftTheEnd() {
        assertEquals(RespawnResetPolicy.Cause.DIMENSION_EXIT, RespawnResetPolicy.causeOf(true));
        assertEquals(RespawnResetPolicy.Cause.DEATH, RespawnResetPolicy.causeOf(false));
        assertSame(RespawnResetPolicy.plan(RespawnResetPolicy.Cause.DEATH), RespawnResetPolicy.plan(false));
        assertSame(RespawnResetPolicy.plan(RespawnResetPolicy.Cause.DIMENSION_EXIT), RespawnResetPolicy.plan(true));
    }

    @Test
    void deathRunsTheWholeResetWithTheOldMessages() {
        RespawnResetPolicy.Plan plan = RespawnResetPolicy.plan(false);
        assertTrue(plan.clearTargets());
        assertTrue(plan.resetMode());
        assertTrue(plan.relocate());
        assertTrue(plan.regroupChat());
        assertTrue(plan.clearLeash());
        assertTrue(plan.noteSoulRespawn());
        assertTrue(plan.sleepBeforeRestart());
        assertEquals("§cCurrent task aborted due to respawn.", plan.taskAbortMessage());
        assertEquals("§cTask aborted due to bot respawn.", plan.lateAbortMessage());
    }

    @Test
    void leavingTheEndAliveKeepsTheTripAndOnlyResetsWhatTheNewEntityNeeds() {
        RespawnResetPolicy.Plan plan = RespawnResetPolicy.plan(true);
        assertFalse(plan.clearTargets(), "follow and base targets survive a dimension change");
        assertFalse(plan.resetMode(), "the bot is not set idle");
        assertFalse(plan.relocate(), "no teleport to the respawn point");
        assertFalse(plan.regroupChat(), "no regrouped line for a bot that did not die");
        assertFalse(plan.noteSoulRespawn(), "the soul does not record a respawn");
        assertFalse(plan.sleepBeforeRestart(), "no server-thread sleep");
        assertTrue(plan.clearLeash(), "a dimension change drops the leads too");
        assertEquals("§eTask ended: changed dimension.", plan.taskAbortMessage());
        assertNull(plan.lateAbortMessage(), "one abort, with the dimension message");
    }

    @Test
    void dimensionAbortIsNotTheManualStopReason() {
        String reason = RespawnResetPolicy.plan(true).taskAbortMessage();
        assertFalse(StopFeedbackPolicy.isManualStop(reason));
        assertEquals(SurvivalLatchPolicy.Decision.CLEAR_AND_PROCEED,
                SurvivalLatchPolicy.decide(true, reason, false));
    }
}
