package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Dreamsleeve engine only runs on macOS; the chooser greys it out elsewhere. */
class SoulVoiceSettingsPlatformTest {

    @Test
    void macOsIsSupported() {
        assertTrue(SoulVoiceSettings.dreamsleeveSupportedOn("Mac OS X"));
        assertTrue(SoulVoiceSettings.dreamsleeveSupportedOn("Darwin"));
    }

    @Test
    void otherPlatformsAreNot() {
        assertFalse(SoulVoiceSettings.dreamsleeveSupportedOn("Windows 11"));
        assertFalse(SoulVoiceSettings.dreamsleeveSupportedOn("Linux"));
        assertFalse(SoulVoiceSettings.dreamsleeveSupportedOn(null));
        assertFalse(SoulVoiceSettings.dreamsleeveSupportedOn(""));
    }
}
