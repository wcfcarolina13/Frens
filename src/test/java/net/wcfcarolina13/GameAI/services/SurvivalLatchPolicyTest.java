package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurvivalLatchPolicyTest {

    @Test
    void noLatchProceeds() {
        assertEquals(SurvivalLatchPolicy.Decision.PROCEED, SurvivalLatchPolicy.decide(false, null, false));
        assertEquals(SurvivalLatchPolicy.Decision.PROCEED,
                SurvivalLatchPolicy.decide(false, StopFeedbackPolicy.MANUAL_STOP_REASON, false));
    }

    @Test
    void manualStopHolds() {
        assertEquals(SurvivalLatchPolicy.Decision.HOLD,
                SurvivalLatchPolicy.decide(true, StopFeedbackPolicy.MANUAL_STOP_REASON, false));
        assertEquals(SurvivalLatchPolicy.Decision.HOLD,
                SurvivalLatchPolicy.decide(true, "§c" + StopFeedbackPolicy.MANUAL_STOP_REASON, false));
    }

    @Test
    void deathOrThreatLatchWithNoTaskIsClearedSoEscapeRuns() {
        assertEquals(SurvivalLatchPolicy.Decision.CLEAR_AND_PROCEED,
                SurvivalLatchPolicy.decide(true, "§cCurrent task aborted due to respawn.", false));
        assertEquals(SurvivalLatchPolicy.Decision.CLEAR_AND_PROCEED,
                SurvivalLatchPolicy.decide(true, "§cTerminating all current tasks due to threat detections", false));
        assertEquals(SurvivalLatchPolicy.Decision.CLEAR_AND_PROCEED, SurvivalLatchPolicy.decide(true, null, false));
        assertEquals(SurvivalLatchPolicy.Decision.CLEAR_AND_PROCEED, SurvivalLatchPolicy.decide(true, "", false));
    }

    @Test
    void nonStopLatchWithRunningTaskIsLeftToTheTask() {
        assertEquals(SurvivalLatchPolicy.Decision.PROCEED,
                SurvivalLatchPolicy.decide(true, "§cTerminating all current tasks due to threat detections", true));
    }
}
