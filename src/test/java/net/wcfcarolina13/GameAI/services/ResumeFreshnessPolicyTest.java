package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static net.wcfcarolina13.GameAI.services.ResumeFreshnessPolicy.AUTO_RESUME_WINDOW_MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeFreshnessPolicyTest {

    private static final long PAUSED = 1_000_000L;

    @Test
    void windowIsTenMinutes() {
        assertEquals(600_000L, AUTO_RESUME_WINDOW_MS);
    }

    @Test
    void resumesWithinTheWindow() {
        assertTrue(ResumeFreshnessPolicy.shouldAutoResume(PAUSED, PAUSED, AUTO_RESUME_WINDOW_MS));
        assertTrue(ResumeFreshnessPolicy.shouldAutoResume(PAUSED, PAUSED + 30_000L, AUTO_RESUME_WINDOW_MS));
        assertTrue(ResumeFreshnessPolicy.shouldAutoResume(PAUSED, PAUSED + AUTO_RESUME_WINDOW_MS, AUTO_RESUME_WINDOW_MS));
    }

    @Test
    void doesNotResumeAPauseOlderThanTheWindow() {
        assertFalse(ResumeFreshnessPolicy.shouldAutoResume(PAUSED, PAUSED + AUTO_RESUME_WINDOW_MS + 1L, AUTO_RESUME_WINDOW_MS));
        // Finding 23: a woodcut that finished 45 minutes before the threat.
        assertFalse(ResumeFreshnessPolicy.shouldAutoResume(PAUSED, PAUSED + 45L * 60_000L, AUTO_RESUME_WINDOW_MS));
    }

    @Test
    void missingPauseTimeIsNeverFresh() {
        assertFalse(ResumeFreshnessPolicy.shouldAutoResume(0L, 5_000L, AUTO_RESUME_WINDOW_MS));
        assertFalse(ResumeFreshnessPolicy.shouldAutoResume(-1L, 5_000L, AUTO_RESUME_WINDOW_MS));
    }

    @Test
    void clockBehindThePauseCountsAsFresh() {
        assertTrue(ResumeFreshnessPolicy.shouldAutoResume(PAUSED, PAUSED - 2_000L, AUTO_RESUME_WINDOW_MS));
    }

    @Test
    void nonPositiveWindowNeverResumes() {
        assertFalse(ResumeFreshnessPolicy.shouldAutoResume(PAUSED, PAUSED, 0L));
    }

    @Test
    void finishedRunClearsItsRecordWhenNothingWantsIt() {
        assertTrue(ResumeFreshnessPolicy.shouldClearOnCompletion(true, false, false, false));
    }

    @Test
    void threatPausedRunKeepsItsRecord() {
        assertFalse(ResumeFreshnessPolicy.shouldClearOnCompletion(true, true, false, false));
    }

    @Test
    void continuePromptKeepsTheRecord() {
        assertFalse(ResumeFreshnessPolicy.shouldClearOnCompletion(true, false, true, false));
    }

    @Test
    void sunsetHoldKeepsTheRecord() {
        assertFalse(ResumeFreshnessPolicy.shouldClearOnCompletion(true, false, false, true));
    }

    @Test
    void anOlderRunNeverClearsANewerRecord() {
        assertFalse(ResumeFreshnessPolicy.shouldClearOnCompletion(false, false, false, false));
    }

    @Test
    void freshThreatClearResumes() {
        assertEquals(ResumeFreshnessPolicy.ThreatClearedAction.RESUME,
                ResumeFreshnessPolicy.onThreatCleared(PAUSED, PAUSED + 60_000L, AUTO_RESUME_WINDOW_MS, false, false));
        assertEquals(ResumeFreshnessPolicy.ThreatClearedAction.RESUME,
                ResumeFreshnessPolicy.onThreatCleared(PAUSED, PAUSED + 60_000L, AUTO_RESUME_WINDOW_MS, true, true));
    }

    @Test
    void staleThreatClearDropsTheRecordSoALaterThreatCannotReviveIt() {
        // Finding 23 again: a 12-minute-old woodcut pause must not leave a record to re-arm.
        assertEquals(ResumeFreshnessPolicy.ThreatClearedAction.DROP_REQUEST_AND_RECORD,
                ResumeFreshnessPolicy.onThreatCleared(PAUSED, PAUSED + 12L * 60_000L, AUTO_RESUME_WINDOW_MS, false, false));
    }

    @Test
    void staleThreatClearLeavesASunriseHoldAlone() {
        assertEquals(ResumeFreshnessPolicy.ThreatClearedAction.LEAVE_FOR_SUNRISE,
                ResumeFreshnessPolicy.onThreatCleared(PAUSED, PAUSED + 12L * 60_000L, AUTO_RESUME_WINDOW_MS, true, false));
        assertEquals(ResumeFreshnessPolicy.ThreatClearedAction.LEAVE_FOR_SUNRISE,
                ResumeFreshnessPolicy.onThreatCleared(PAUSED, PAUSED + 12L * 60_000L, AUTO_RESUME_WINDOW_MS, true, true));
    }

    @Test
    void staleThreatClearKeepsTheRecordWhileThePlayerIsAsked() {
        assertEquals(ResumeFreshnessPolicy.ThreatClearedAction.DROP_REQUEST_KEEP_RECORD,
                ResumeFreshnessPolicy.onThreatCleared(PAUSED, PAUSED + 12L * 60_000L, AUTO_RESUME_WINDOW_MS, false, true));
    }

    @Test
    void pauseArmsOnlyTheRecordedSkill() {
        assertTrue(ResumeFreshnessPolicy.pausedTaskOwnsRecord("skill:woodcut", false, "woodcut"));
        assertFalse(ResumeFreshnessPolicy.pausedTaskOwnsRecord("skill:hangout", false, "woodcut"),
                "a threat pausing an unrelated task must not re-arm an old woodcut");
        assertFalse(ResumeFreshnessPolicy.pausedTaskOwnsRecord("skill:woodcut", true, "woodcut"),
                "a woodcut hobby is not the recorded /bot skill woodcut");
        assertFalse(ResumeFreshnessPolicy.pausedTaskOwnsRecord("woodcut", false, "woodcut"));
        assertFalse(ResumeFreshnessPolicy.pausedTaskOwnsRecord("post_task_return", false, "woodcut"));
    }

    @Test
    void pauseArmsNothingWithoutATaskOrARecord() {
        assertFalse(ResumeFreshnessPolicy.pausedTaskOwnsRecord(null, false, "woodcut"));
        assertFalse(ResumeFreshnessPolicy.pausedTaskOwnsRecord("skill:woodcut", false, null));
        assertFalse(ResumeFreshnessPolicy.pausedTaskOwnsRecord("skill:", false, ""));
    }
}
