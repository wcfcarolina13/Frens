package net.wcfcarolina13.GameAI.services.dialogue;

import net.wcfcarolina13.GameAI.services.dialogue.ScriptedDelivery.Surface;
import net.wcfcarolina13.GameAI.services.dialogue.ScriptedDelivery.VoiceOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for scripted-line delivery (the speech-floor arming input). Never references
 * BotDialoguePlayer, so the voice player's sound-registry static init never runs.
 */
class ScriptedDeliveryTest {

    @Test
    void overheadPlayedThenExplicitThrottledIsDelivered() {
        // The 1.1.217 bug shape: the overhead voiced the line, a second play of it got THROTTLED.
        assertTrue(ScriptedDelivery.delivered(false, VoiceOutcome.PLAYED, VoiceOutcome.THROTTLED, false));
    }

    @Test
    void playedFromEitherAttemptIsDelivered() {
        assertTrue(ScriptedDelivery.delivered(false, VoiceOutcome.PLAYED, VoiceOutcome.NONE, false));
        assertTrue(ScriptedDelivery.delivered(false, VoiceOutcome.NONE, VoiceOutcome.PLAYED, false));
    }

    @Test
    void textOffAndVoiceOffIsNotDelivered() {
        assertFalse(ScriptedDelivery.delivered(false, VoiceOutcome.SUPPRESSED, VoiceOutcome.NONE, false));
        assertFalse(ScriptedDelivery.delivered(false, VoiceOutcome.NONE, VoiceOutcome.SUPPRESSED, false));
        assertFalse(ScriptedDelivery.delivered(false, VoiceOutcome.NONE, VoiceOutcome.NONE, false));
    }

    @Test
    void throttledAloneIsNotDelivered() {
        // Unmapped line, explicit play swallowed by another line's mutex, text off.
        assertFalse(ScriptedDelivery.delivered(false, VoiceOutcome.NONE, VoiceOutcome.THROTTLED, false));
        // Mapped line whose overhead play was swallowed by another line's mutex.
        assertFalse(ScriptedDelivery.delivered(false, VoiceOutcome.THROTTLED, VoiceOutcome.NONE, false));
    }

    @Test
    void anyTextSurfaceIsDelivered() {
        assertTrue(ScriptedDelivery.delivered(true, VoiceOutcome.SUPPRESSED, VoiceOutcome.NONE, false));
        assertTrue(ScriptedDelivery.delivered(true, VoiceOutcome.THROTTLED, VoiceOutcome.NONE, false));
        assertTrue(ScriptedDelivery.delivered(false, VoiceOutcome.SUPPRESSED, VoiceOutcome.NONE, true));
    }

    @Test
    void explicitVoiceOnlyWhenOverheadMadeNoAttempt() {
        assertTrue(ScriptedDelivery.needsExplicitVoice(VoiceOutcome.NONE));
        assertTrue(ScriptedDelivery.needsExplicitVoice(null));
        assertFalse(ScriptedDelivery.needsExplicitVoice(VoiceOutcome.PLAYED));
        assertFalse(ScriptedDelivery.needsExplicitVoice(VoiceOutcome.THROTTLED));
        assertFalse(ScriptedDelivery.needsExplicitVoice(VoiceOutcome.SUPPRESSED));
    }

    @Test
    void effectiveVoiceIsTheOverheadsWhenItTried() {
        assertEquals(VoiceOutcome.PLAYED, ScriptedDelivery.effectiveVoice(VoiceOutcome.PLAYED, VoiceOutcome.THROTTLED));
        assertEquals(VoiceOutcome.SUPPRESSED, ScriptedDelivery.effectiveVoice(VoiceOutcome.SUPPRESSED, VoiceOutcome.NONE));
        assertEquals(VoiceOutcome.THROTTLED, ScriptedDelivery.effectiveVoice(VoiceOutcome.NONE, VoiceOutcome.THROTTLED));
        assertEquals(VoiceOutcome.NONE, ScriptedDelivery.effectiveVoice(VoiceOutcome.NONE, null));
    }

    @Test
    void voiceHandledSkipsChatFallbackOnPlayedOrThrottled() {
        assertTrue(ScriptedDelivery.voiceHandled(VoiceOutcome.PLAYED, VoiceOutcome.NONE));
        assertTrue(ScriptedDelivery.voiceHandled(VoiceOutcome.THROTTLED, VoiceOutcome.NONE));
        assertTrue(ScriptedDelivery.voiceHandled(VoiceOutcome.NONE, VoiceOutcome.PLAYED));
        assertTrue(ScriptedDelivery.voiceHandled(VoiceOutcome.NONE, VoiceOutcome.THROTTLED));
        // Voice off / muted / failed: the caller falls back to chat.
        assertFalse(ScriptedDelivery.voiceHandled(VoiceOutcome.SUPPRESSED, VoiceOutcome.NONE));
        assertFalse(ScriptedDelivery.voiceHandled(VoiceOutcome.NONE, VoiceOutcome.SUPPRESSED));
        assertFalse(ScriptedDelivery.voiceHandled(VoiceOutcome.NONE, VoiceOutcome.NONE));
    }

    @Test
    void surfaceNormalisesMissingVoiceAndEmptyShowsNothing() {
        assertEquals(VoiceOutcome.NONE, new Surface(true, null).voice());
        assertFalse(Surface.EMPTY.textShown());
        assertEquals(VoiceOutcome.NONE, Surface.EMPTY.voice());
    }
}
