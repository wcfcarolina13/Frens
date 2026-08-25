package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulVoiceSettingsTest {

    /**
     * Constructs a real ManualConfig via reflection on its private no-arg constructor.
     * ManualConfig() only assigns selectedLanguageModel from a system property — no file I/O.
     */
    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test
    void defaultsAreDisabledAndValidStructurally() throws Exception {
        ManualConfig config = newRealConfig();
        SoulVoiceSettings s = SoulVoiceSettings.from(config);
        assertFalse(s.enabled());
        assertEquals(400, s.maxChars());
        assertEquals(8000L, s.synthTimeoutMs());
        assertEquals(0.6f, s.radioGain(), 0.0001f);
    }

    @Test
    void enabledWithBlankPathsIsInvalidWithAConcreteError() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulVoiceEnabled(true);
        SoulVoiceSettings s = SoulVoiceSettings.from(config);
        assertTrue(s.enabled());
        assertFalse(s.valid());
        assertFalse(s.validationError().isBlank());
    }

    @Test
    void enabledWithBothPathsIsValid() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulVoiceEnabled(true);
        config.setSoulVoicePiperBinary("/opt/homebrew/bin/piper");
        config.setSoulVoiceModel("/Users/roti/voices/jake.onnx");
        SoulVoiceSettings s = SoulVoiceSettings.from(config);
        assertTrue(s.valid());
    }

    @Test
    void nullConfigYieldsDisabledInvalid() {
        SoulVoiceSettings s = SoulVoiceSettings.from(null);
        assertFalse(s.enabled());
        assertFalse(s.valid());
    }

    @Test
    void outOfRangeNumbersClampToSaneBounds() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulVoiceMaxChars(99999);
        config.setSoulVoiceSynthTimeoutMs(50);
        config.setSoulVoiceRadioGain(9.0f);
        SoulVoiceSettings s = SoulVoiceSettings.from(config);
        assertEquals(1000, s.maxChars());
        assertEquals(1000L, s.synthTimeoutMs());
        assertEquals(1.0f, s.radioGain(), 0.0001f);
    }

    @Test
    void accessorsClampValuesCorrectly() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulVoiceMaxChars(20);           // below min 40
        config.setSoulVoiceSynthTimeoutMs(500);    // below min 1000
        config.setSoulVoiceRadioGain(-0.5f);       // below min 0.0

        // Accessors should clamp to bounds
        assertEquals(40, config.getSoulVoiceMaxChars());
        assertEquals(1000L, config.getSoulVoiceSynthTimeoutMs());
        assertEquals(0.0f, config.getSoulVoiceRadioGain(), 0.0001f);
    }
}
