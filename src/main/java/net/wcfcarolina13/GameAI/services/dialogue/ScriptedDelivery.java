package net.wcfcarolina13.GameAI.services.dialogue;

import net.wcfcarolina13.ChatUtils.BotDialoguePlayer;

/**
 * Pure decision for "did a scripted line actually reach the player?" — the input to arming the
 * cross-lane speech floor after a scripted reaction (pet, context).
 *
 * <p>A scripted line can surface three ways: the overhead hologram, the voice clip, and the chat
 * fallback. {@code CompanionOverheadDialogueService.showOverheadLine} already voices any line that
 * has a {@code DialogueTextMapper} entry, so a caller that then plays the same line again hits the
 * 2.5 s per-bot voice throttle and gets THROTTLED back although the player just heard it (the
 * 1.1.217 bug: voice on, text off, floor never armed). The rules here:
 * <ul>
 *   <li>PLAYED from either voice attempt is a delivery;</li>
 *   <li>THROTTLED on its own is not (the mutex swallowed it and no chat fallback runs), but it
 *       never cancels a PLAYED from the other attempt;</li>
 *   <li>shown overhead text or sent chat text is a delivery.</li>
 * </ul>
 *
 * <p>No Minecraft types: {@link VoiceOutcome} mirrors {@link BotDialoguePlayer.PlayResult} plus a
 * "not attempted" value, so tests never load the voice player.
 */
public final class ScriptedDelivery {

    private ScriptedDelivery() {
    }

    /** Outcome of one voice attempt, as far as delivery is concerned. */
    public enum VoiceOutcome {
        /** The clip played. */
        PLAYED,
        /** The per-bot voice mutex swallowed it: nothing audible, and no chat fallback either. */
        THROTTLED,
        /** Voice off, category muted, underwater, or the play failed: the caller may fall back to chat. */
        SUPPRESSED,
        /** No attempt was made (no voice mapping for the line, or the surface bailed early). */
        NONE
    }

    /**
     * What one surfacing call showed: whether the overhead text went up and what its voice attempt did.
     */
    public record Surface(boolean textShown, VoiceOutcome voice) {
        public static final Surface EMPTY = new Surface(false, VoiceOutcome.NONE);

        public Surface {
            voice = voice == null ? VoiceOutcome.NONE : voice;
        }
    }

    /** Maps the voice player's result; {@code null} means no attempt was made. */
    public static VoiceOutcome from(BotDialoguePlayer.PlayResult result) {
        if (result == null) {
            return VoiceOutcome.NONE;
        }
        return switch (result) {
            case PLAYED -> VoiceOutcome.PLAYED;
            case THROTTLED -> VoiceOutcome.THROTTLED;
            case DISABLED, FAILED -> VoiceOutcome.SUPPRESSED;
        };
    }

    /**
     * The caller's own voice play is needed only when the overhead surface made no attempt. When it
     * did, a second play of the same line is at best a no-op and at worst a THROTTLED that hides the
     * delivery; every gate it would hit (global/per-bot voice, category mask, underwater) is the
     * same one the overhead attempt already consulted.
     */
    public static boolean needsExplicitVoice(VoiceOutcome overheadVoice) {
        return overheadVoice == null || overheadVoice == VoiceOutcome.NONE;
    }

    /** The voice outcome that decides the chat fallback: the overhead's if it tried, else the caller's. */
    public static VoiceOutcome effectiveVoice(VoiceOutcome overheadVoice, VoiceOutcome explicitVoice) {
        if (!needsExplicitVoice(overheadVoice)) {
            return overheadVoice;
        }
        return explicitVoice == null ? VoiceOutcome.NONE : explicitVoice;
    }

    /**
     * True when the voice lane handled the line, so no chat fallback should run: it played, or the
     * mutex swallowed it (the historical rule — a throttled line is dropped, not re-routed to chat).
     */
    public static boolean voiceHandled(VoiceOutcome overheadVoice, VoiceOutcome explicitVoice) {
        VoiceOutcome effective = effectiveVoice(overheadVoice, explicitVoice);
        return effective == VoiceOutcome.PLAYED || effective == VoiceOutcome.THROTTLED;
    }

    /**
     * Whether the player actually got the line on at least one surface.
     *
     * @param textShown     the overhead hologram went up
     * @param overheadVoice the overhead surface's voice attempt
     * @param explicitVoice the caller's own voice attempt ({@link VoiceOutcome#NONE} when skipped)
     * @param chatTextSent  the chat fallback ran with its text gate open
     */
    public static boolean delivered(boolean textShown,
                                    VoiceOutcome overheadVoice,
                                    VoiceOutcome explicitVoice,
                                    boolean chatTextSent) {
        return textShown
                || chatTextSent
                || overheadVoice == VoiceOutcome.PLAYED
                || explicitVoice == VoiceOutcome.PLAYED;
    }
}
