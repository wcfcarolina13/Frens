package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulVoiceSettingsTest {

    @Test
    void defaultsAreDisabledAndValidStructurally() {
        SoulVoiceSettings s = SoulVoiceSettings.of(false, "", "", 400, 8000L, 0.6f);
        assertFalse(s.enabled());
        assertEquals(400, s.maxChars());
        assertEquals(8000L, s.synthTimeoutMs());
        assertEquals(0.6f, s.radioGain(), 0.0001f);
    }

    @Test
    void enabledWithBlankPathsIsInvalidWithAConcreteError() {
        SoulVoiceSettings s = SoulVoiceSettings.of(true, "", "", 400, 8000L, 0.6f);
        assertTrue(s.enabled());
        assertFalse(s.valid());
        assertFalse(s.validationError().isBlank());
    }

    @Test
    void enabledWithBothPathsIsValid() {
        SoulVoiceSettings s = SoulVoiceSettings.of(true, "/opt/homebrew/bin/piper", "/Users/roti/voices/jake.onnx", 400, 8000L, 0.6f);
        assertTrue(s.valid());
    }

    @Test
    void nullConfigYieldsDisabledInvalid() {
        SoulVoiceSettings s = SoulVoiceSettings.from(null);
        assertFalse(s.enabled());
        assertFalse(s.valid());
    }

    @Test
    void outOfRangeNumbersClampToSaneBounds() {
        SoulVoiceSettings s = SoulVoiceSettings.of(false, "", "", 99999, 50, 9.0f);
        assertEquals(1000, s.maxChars());
        assertEquals(1000L, s.synthTimeoutMs());
        assertEquals(1.0f, s.radioGain(), 0.0001f);
    }
}
