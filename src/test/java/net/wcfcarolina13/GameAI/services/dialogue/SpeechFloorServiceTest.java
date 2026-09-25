package net.wcfcarolina13.GameAI.services.dialogue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle tests for the live speech-floor map: the teardown hooks wired in {@code Frens}
 * (SERVER_STOPPING → {@code clearAll}, real-player DISCONNECT → {@code clear}). The service
 * imports only {@code java.util}, so it is exercised directly. Its map is static and the test JVM
 * is shared, so every test starts and ends from an empty map.
 */
class SpeechFloorServiceTest {

    private static final SpeechFloorPolicy.Source SCRIPTED = SpeechFloorPolicy.Source.SCRIPTED_AMBIENT;
    private static final SpeechFloorPolicy.Source SCENE_END = SpeechFloorPolicy.Source.SOUL_SCENE_END;
    private static final SpeechFloorPolicy.Source SCENE_LINE = SpeechFloorPolicy.Source.SOUL_SCENE_LINE;
    private static final SpeechFloorPolicy.Source PENDING = SpeechFloorPolicy.Source.SOUL_SCENE_PENDING;

    @BeforeEach
    @AfterEach
    void emptyTheMap() {
        SpeechFloorService.clearAll();
    }

    @Test
    void armedFloorIsClosedUntilCleared() {
        UUID audience = UUID.randomUUID();
        SpeechFloorService.noteSpeech(audience, SCENE_END);

        assertFalse(SpeechFloorService.isFloorOpen(audience, SCRIPTED));
        assertTrue(SpeechFloorService.remainingMs(audience) > 0L);

        SpeechFloorService.clear(audience);

        assertTrue(SpeechFloorService.isFloorOpen(audience, SCRIPTED));
        assertEquals(0L, SpeechFloorService.remainingMs(audience));
    }

    @Test
    void clearingOneAudienceLeavesTheOthersArmed() {
        UUID leaving = UUID.randomUUID();
        UUID staying = UUID.randomUUID();
        SpeechFloorService.noteSpeech(leaving, SCENE_END);
        SpeechFloorService.noteSpeech(staying, SCENE_END);

        SpeechFloorService.clear(leaving);

        assertTrue(SpeechFloorService.isFloorOpen(leaving, SCRIPTED));
        assertFalse(SpeechFloorService.isFloorOpen(staying, SCRIPTED));
    }

    @Test
    void clearAllOpensEveryFloor() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        SpeechFloorService.noteSpeech(first, SCENE_END);
        SpeechFloorService.noteSpeech(second, SCRIPTED);
        assertFalse(SpeechFloorService.isFloorOpen(first, SCRIPTED));
        assertFalse(SpeechFloorService.isFloorOpen(second, SCRIPTED));

        SpeechFloorService.clearAll();

        assertTrue(SpeechFloorService.isFloorOpen(first, SCRIPTED));
        assertTrue(SpeechFloorService.isFloorOpen(second, SCRIPTED));
        assertEquals(0L, SpeechFloorService.remainingMs(first));
        assertEquals(0L, SpeechFloorService.remainingMs(second));
    }

    @Test
    void clearingNullOrUnknownAudienceIsANoOp() {
        UUID audience = UUID.randomUUID();
        SpeechFloorService.noteSpeech(audience, SCENE_END);

        assertDoesNotThrow(() -> SpeechFloorService.clear(null));
        assertDoesNotThrow(() -> SpeechFloorService.clear(UUID.randomUUID()));

        assertFalse(SpeechFloorService.isFloorOpen(audience, SCRIPTED));
    }

    @Test
    void floorCanBeReArmedAfterClear() {
        UUID audience = UUID.randomUUID();
        SpeechFloorService.noteSpeech(audience, SCENE_END);
        SpeechFloorService.clear(audience);

        SpeechFloorService.noteSpeech(audience, SCRIPTED);

        assertFalse(SpeechFloorService.isFloorOpen(audience, SCRIPTED));
    }

    // --- pending-scene reservation + hold-aware line floor (1.1.217) ---

    @Test
    void aPendingReservationVetoesScriptedButLetsScenesThrough() {
        UUID audience = UUID.randomUUID();
        SpeechFloorService.noteSpeech(audience, PENDING);

        assertFalse(SpeechFloorService.isFloorOpen(audience, SCRIPTED));
        assertTrue(SpeechFloorService.isFloorOpen(audience, SCENE_LINE));
        assertEquals("scene-pending", SpeechFloorService.armedByLabel(audience, SCRIPTED));
        assertEquals("none", SpeechFloorService.armedByLabel(audience, SCENE_LINE));
        // remainingMs reports what a vetoed scripted line is waiting on: the ~30 s reservation.
        long remaining = SpeechFloorService.remainingMs(audience);
        assertTrue(remaining > 25_000L && remaining <= 30_000L, "remaining=" + remaining);
    }

    @Test
    void releasePendingReopensTheFloorForScripted() {
        UUID audience = UUID.randomUUID();
        SpeechFloorService.noteSpeech(audience, PENDING);

        SpeechFloorService.releasePending(audience);

        assertTrue(SpeechFloorService.isFloorOpen(audience, SCRIPTED));
        assertEquals("none", SpeechFloorService.armedByLabel(audience, SCRIPTED));
        assertEquals(0L, SpeechFloorService.remainingMs(audience));
    }

    @Test
    void releasePendingLeavesTheSpeechFloorUnderItIntact() {
        UUID audience = UUID.randomUUID();
        SpeechFloorService.noteSpeech(audience, SCENE_END);
        SpeechFloorService.noteSpeech(audience, PENDING);

        SpeechFloorService.releasePending(audience);

        // The post-scene quiet still holds every lane, scenes included.
        assertFalse(SpeechFloorService.isFloorOpen(audience, SCRIPTED));
        assertFalse(SpeechFloorService.isFloorOpen(audience, SCENE_LINE));
        assertEquals("scene-end", SpeechFloorService.armedByLabel(audience, SCRIPTED));
        assertTrue(SpeechFloorService.remainingMs(audience) <= 20_000L);
    }

    @Test
    void releasePendingWithoutAReservationChangesNothing() {
        UUID audience = UUID.randomUUID();
        SpeechFloorService.noteSpeech(audience, SCRIPTED);

        assertDoesNotThrow(() -> SpeechFloorService.releasePending(null));
        assertDoesNotThrow(() -> SpeechFloorService.releasePending(UUID.randomUUID()));
        SpeechFloorService.releasePending(audience);

        assertFalse(SpeechFloorService.isFloorOpen(audience, SCRIPTED));
        assertEquals("scripted", SpeechFloorService.armedByLabel(audience, SCRIPTED));
    }

    @Test
    void theFirstSceneLineSupersedesTheReservation() {
        UUID audience = UUID.randomUUID();
        SpeechFloorService.noteSpeech(audience, PENDING);

        SpeechFloorService.noteSpeech(audience, SCENE_LINE, 0L);

        // Now held by the line's own 6 s floor, not by the 30 s reservation.
        assertFalse(SpeechFloorService.isFloorOpen(audience, SCRIPTED));
        assertEquals("scene-line", SpeechFloorService.armedByLabel(audience, SCRIPTED));
        assertTrue(SpeechFloorService.remainingMs(audience) <= 6_000L);
    }

    @Test
    void aVoicedSceneLineHoldsTheFloorForItsWholeHold() {
        UUID audience = UUID.randomUUID();
        SpeechFloorService.noteSpeech(audience, SCENE_LINE, 12_000L);

        long remaining = SpeechFloorService.remainingMs(audience);
        long expected = 12_000L + SpeechFloorPolicy.SCENE_LINE_HOLD_MARGIN_MS;
        assertTrue(remaining > expected - 1_000L && remaining <= expected, "remaining=" + remaining);
        assertFalse(SpeechFloorService.isFloorOpen(audience, SCRIPTED));
    }

    @Test
    void clearDropsTheReservationToo() {
        UUID audience = UUID.randomUUID();
        SpeechFloorService.noteSpeech(audience, PENDING);

        SpeechFloorService.clear(audience);

        assertTrue(SpeechFloorService.isFloorOpen(audience, SCRIPTED));
    }

    @Test
    void armedByLabelForAnUnknownOrNullAudienceIsNone() {
        assertEquals("none", SpeechFloorService.armedByLabel(null, SCRIPTED));
        assertEquals("none", SpeechFloorService.armedByLabel(UUID.randomUUID(), SCRIPTED));
    }
}
