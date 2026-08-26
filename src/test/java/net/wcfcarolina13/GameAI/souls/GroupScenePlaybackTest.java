package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the pure pacing/staleness rules of the scene playback machine. The Minecraft-facing
 * fan-out itself (player list, sendMessage, payloads) is exercised in-game, like
 * {@link SoulMessageDelivery}'s server path.
 */
class GroupScenePlaybackTest {

    @Test
    void beatPacingIsClampedAndLengthScaled() {
        assertEquals(1500L, GroupScenePlayback.beatMsFor(0));
        assertEquals(1900L, GroupScenePlayback.beatMsFor(100));
        assertEquals(2500L, GroupScenePlayback.beatMsFor(300));
        assertEquals(2500L, GroupScenePlayback.beatMsFor(10_000));
    }

    @Test
    void lineDurationUsesAudioWhenPresentAndBeatOtherwise() {
        var audio = new SoulVoiceService.SynthesizedLine(22050, List.of(new byte[10]), 4_000L);
        assertEquals(4_000L + GroupScenePlayback.LINE_GAP_MS,
                GroupScenePlayback.lineDurationMs(Optional.of(audio), 50));
        assertEquals(GroupScenePlayback.beatMsFor(50),
                GroupScenePlayback.lineDurationMs(Optional.empty(), 50));
    }

    @Test
    void lineGroupIdsAreStableDistinctAndNeverTheRoutingIdOrSegmentIds() {
        UUID routingId = UUID.randomUUID();
        UUID line0 = GroupScenePlayback.lineGroupId(routingId, 0);
        UUID line1 = GroupScenePlayback.lineGroupId(routingId, 1);
        assertEquals(line0, GroupScenePlayback.lineGroupId(routingId, 0));
        assertNotEquals(line0, line1);
        assertNotEquals(routingId, line0);
        assertNotEquals(SoulVoiceService.segmentCorrelationId(routingId, 0), line0);
        assertNotEquals(SoulVoiceService.segmentCorrelationId(routingId, 1), line1);
    }

    @Test
    void decideStepOrdersCancelFinishPacingSynthOwnerBot() {
        // cancelled wins over everything
        assertEquals(GroupScenePlayback.Step.ABORT,
                GroupScenePlayback.decideStep(true, true, true, true, true, true));
        // no lines left
        assertEquals(GroupScenePlayback.Step.FINISH,
                GroupScenePlayback.decideStep(false, false, true, true, true, true));
        // pacing gate not yet open
        assertEquals(GroupScenePlayback.Step.WAIT,
                GroupScenePlayback.decideStep(false, true, true, true, false, true));
        // synthesis not settled yet
        assertEquals(GroupScenePlayback.Step.WAIT,
                GroupScenePlayback.decideStep(false, true, true, true, true, false));
        // owner gone aborts the whole scene
        assertEquals(GroupScenePlayback.Step.ABORT,
                GroupScenePlayback.decideStep(false, true, false, true, true, true));
        // speaker gone skips just this line
        assertEquals(GroupScenePlayback.Step.SKIP,
                GroupScenePlayback.decideStep(false, true, true, false, true, true));
        // all clear
        assertEquals(GroupScenePlayback.Step.DELIVER,
                GroupScenePlayback.decideStep(false, true, true, true, true, true));
    }

    @Test
    void lineSurfacesTruthTable() {
        // PLAYER scenes: always text; audio iff present; never skipped.
        assertEquals(new GroupScenePlayback.LineSurfaces(true, true, false),
                GroupScenePlayback.lineSurfaces(false, false, false, true));
        assertEquals(new GroupScenePlayback.LineSurfaces(true, false, false),
                GroupScenePlayback.lineSurfaces(false, false, false, false));
        // BANTER: both open.
        assertEquals(new GroupScenePlayback.LineSurfaces(true, true, false),
                GroupScenePlayback.lineSurfaces(true, true, true, true));
        // BANTER: text muted, voice open -> voice-only.
        assertEquals(new GroupScenePlayback.LineSurfaces(false, true, false),
                GroupScenePlayback.lineSurfaces(true, false, true, true));
        // BANTER: voice muted (or no audio), text open -> text-only.
        assertEquals(new GroupScenePlayback.LineSurfaces(true, false, false),
                GroupScenePlayback.lineSurfaces(true, true, false, true));
        assertEquals(new GroupScenePlayback.LineSurfaces(true, false, false),
                GroupScenePlayback.lineSurfaces(true, true, true, false));
        // BANTER: both closed -> skip without commit.
        assertTrue(GroupScenePlayback.lineSurfaces(true, false, false, true).skip());
        assertTrue(GroupScenePlayback.lineSurfaces(true, false, true, false).skip());
    }

    @Test
    void banterCombatAbortOnlyAppliesToBanterScenes() {
        assertTrue(GroupScenePlayback.banterCombatAbort(true, true, false));
        assertTrue(GroupScenePlayback.banterCombatAbort(true, false, true));
        assertEquals(false, GroupScenePlayback.banterCombatAbort(true, false, false));
        assertEquals(false, GroupScenePlayback.banterCombatAbort(false, true, true));
    }

    @Test
    void synthesisIsConsideredSettledOnCompletionOrGuardTimeout() {
        assertTrue(GroupScenePlayback.synthSettled(true, 0, 0, 1_000));
        assertTrue(GroupScenePlayback.synthSettled(false, 10_000, 21_000, 10_000));
        assertEquals(false, GroupScenePlayback.synthSettled(false, 10_000, 15_000, 10_000));
    }
}
