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
}
