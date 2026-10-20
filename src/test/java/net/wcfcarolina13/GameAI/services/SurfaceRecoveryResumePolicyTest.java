package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static net.wcfcarolina13.GameAI.services.SurfaceRecoveryResumePolicy.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SurfaceRecoveryResumePolicyTest {
    @Test
    void resumesImmediatelyWithoutRecovery() {
        assertEquals(RESUME, SurfaceRecoveryResumePolicy.action(true, true, false, 0L));
    }

    @Test
    void waitsWhileRecoveryIsActiveBeforeDeadline() {
        assertEquals(WAIT, SurfaceRecoveryResumePolicy.action(true, true, true, 0L));
        assertEquals(WAIT, SurfaceRecoveryResumePolicy.action(true, true, true, 29_999L));
    }

    @Test
    void resumesAsSoonAsRecoveryFinishes() {
        assertEquals(RESUME, SurfaceRecoveryResumePolicy.action(true, true, false, 1_000L));
    }

    @Test
    void resumesAnywayAtThirtySeconds() {
        assertEquals(RESUME_AFTER_TIMEOUT, SurfaceRecoveryResumePolicy.action(true, true, true, 30_000L));
        assertEquals(RESUME_AFTER_TIMEOUT, SurfaceRecoveryResumePolicy.action(true, true, true, 45_000L));
    }

    @Test
    void stoppedOrReplacedRequestNeverResumesEvenAfterTimeout() {
        assertEquals(CANCEL, SurfaceRecoveryResumePolicy.action(false, true, false, 1_000L));
        assertEquals(CANCEL, SurfaceRecoveryResumePolicy.action(false, true, true, 30_000L));
    }

    @Test
    void missingOrReplacedBotCancelsRatherThanResumingItsAlias() {
        assertEquals(CANCEL, SurfaceRecoveryResumePolicy.action(true, false, false, 1_000L));
        assertEquals(CANCEL, SurfaceRecoveryResumePolicy.action(true, false, true, 30_000L));
    }
}
