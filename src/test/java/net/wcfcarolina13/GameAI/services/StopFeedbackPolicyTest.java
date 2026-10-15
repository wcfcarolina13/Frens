package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopFeedbackPolicyTest {

    @Test
    void reasonStaysInTheManualStopSoulBucket() {
        // Mirrors SoulEventObserver.sanitizeReasonCategory: these substrings are checked before "stop".
        String lower = StopFeedbackPolicy.MANUAL_STOP_REASON.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("stop"));
        for (String earlier : List.of("respawn", "stopping", "preempt", "interrupted by new command", "threat", "danger")) {
            assertFalse(lower.contains(earlier), "reason must not contain '" + earlier + "'");
        }
    }

    @Test
    void reasonNamesNoCommandAndNoDashOrSemicolon() {
        String reason = StopFeedbackPolicy.MANUAL_STOP_REASON;
        assertFalse(reason.contains("/bot"));
        assertFalse(reason.contains("—"));
        assertFalse(reason.contains(";"));
    }

    @Test
    void manualStopIsRecognisedWithLegacyFormatting() {
        assertTrue(StopFeedbackPolicy.isManualStop(StopFeedbackPolicy.MANUAL_STOP_REASON));
        assertTrue(StopFeedbackPolicy.isManualStop("§c" + StopFeedbackPolicy.MANUAL_STOP_REASON + "§r "));
        assertFalse(StopFeedbackPolicy.isManualStop("§cCurrent task aborted due to respawn."));
        assertFalse(StopFeedbackPolicy.isManualStop(null));
    }

    @Test
    void skillEchoIsSuppressedOnlyForManualStop() {
        assertFalse(StopFeedbackPolicy.shouldEchoSkillResult(StopFeedbackPolicy.MANUAL_STOP_REASON));
        assertTrue(StopFeedbackPolicy.shouldEchoSkillResult("Skill 'woodcut' paused due to nearby threat."));
        assertTrue(StopFeedbackPolicy.shouldEchoSkillResult("Collected 12 logs."));
        assertTrue(StopFeedbackPolicy.shouldEchoSkillResult(null));
    }

    @Test
    void creeperAbortReasonIsAPlainChatLineNotAKey() {
        String reason = CreeperEvasionPolicy.ABORT_REASON;
        assertFalse(reason.isBlank());
        assertFalse(reason.contains("-"));
        assertFalse(reason.contains("—"));
        assertFalse(reason.contains(";"));
        assertFalse(reason.matches("^[a-z]+(-[a-z]+)+$"));
        assertFalse(StopFeedbackPolicy.isManualStop(reason));
    }

    @Test
    void creeperAbortReasonStaysOutOfTheSoulBuckets() {
        // Mirrors SoulEventObserver.sanitizeReasonCategory: the old "creeper-fuse" key bucketed as "other".
        String lower = CreeperEvasionPolicy.ABORT_REASON.toLowerCase(Locale.ROOT);
        for (String word : List.of("respawn", "stopping", "preempt", "interrupted by new command",
                "threat", "danger", "halt", "stop")) {
            assertFalse(lower.contains(word), "reason must not contain '" + word + "'");
        }
    }

    @Test
    void skillEchoIsSuppressedForTheCreeperAbort() {
        assertFalse(StopFeedbackPolicy.shouldEchoSkillResult(CreeperEvasionPolicy.ABORT_REASON));
        assertFalse(StopFeedbackPolicy.shouldEchoSkillResult("§c" + CreeperEvasionPolicy.ABORT_REASON + " "));
        assertTrue(StopFeedbackPolicy.isCreeperBackoff(CreeperEvasionPolicy.ABORT_REASON));
        assertFalse(StopFeedbackPolicy.isCreeperBackoff("creeper-fuse"));
        assertFalse(StopFeedbackPolicy.isCreeperBackoff(null));
    }
}
