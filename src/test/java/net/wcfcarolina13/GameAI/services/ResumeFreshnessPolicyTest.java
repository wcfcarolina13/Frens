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
}
