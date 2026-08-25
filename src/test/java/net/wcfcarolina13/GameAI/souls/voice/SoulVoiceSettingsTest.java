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
    void dreamsleeveEngineValidatesDirAndRefClipInsteadOfPiperPaths() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulVoiceEnabled(true);
        config.setSoulVoiceEngine("dreamsleeve");
        config.setSoulVoiceDreamsleeveDir("/Users/roti/pontus/openmw-forge/dreamsleeve");
        SoulVoiceSettings missingRef = SoulVoiceSettings.from(config);
        assertFalse(missingRef.valid());
        assertEquals("Configure a voice reference clip (soulVoiceRefAudio) first.",
                missingRef.validationError());

        config.setSoulVoiceRefAudio("/voices/vanilla/imperial-m/calm.wav");
        config.setSoulVoiceRefText("Yes? What can I do for you?");
        SoulVoiceSettings s = SoulVoiceSettings.from(config);
        assertTrue(s.valid());
        assertEquals(SoulVoiceSettings.ENGINE_DREAMSLEEVE, s.engine());
        assertEquals("Yes? What can I do for you?", s.refText());
        // Piper paths are irrelevant for this engine and stay blank without invalidating.
        assertEquals("", s.piperBinary());
    }

    @Test
    void unknownEngineIsInvalid() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulVoiceEngine("espeak");
        SoulVoiceSettings s = SoulVoiceSettings.from(config);
        assertFalse(s.valid());
        assertEquals("Unknown soul voice engine: espeak", s.validationError());
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
