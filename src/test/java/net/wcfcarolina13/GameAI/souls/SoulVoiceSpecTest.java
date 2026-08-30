package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-bot voice selection (2026-08-29): the spec record, its parser, and the profile default. */
class SoulVoiceSpecTest {

    @Test
    void parsePiperSplitsModelAndSpeaker() {
        SoulTypes.VoiceSpec spec = SoulTypes.VoiceSpec.parsePiper("en_US-libritts_r-medium#42");
        assertEquals("en_US-libritts_r-medium", spec.piperModel());
        assertEquals(42, spec.piperSpeaker());
        SoulTypes.VoiceSpec plain = SoulTypes.VoiceSpec.parsePiper("  en_US-ryan-medium ");
        assertEquals("en_US-ryan-medium", plain.piperModel());
        assertEquals(-1, plain.piperSpeaker(), "no #speaker means the model default");
        assertEquals(SoulTypes.VoiceSpec.EMPTY, SoulTypes.VoiceSpec.parsePiper("   "));
        // A trailing '#' with junk is not a speaker id — the whole thing is the name.
        assertEquals("weird#name", SoulTypes.VoiceSpec.parsePiper("weird#name").piperModel());
    }

    @Test
    void emptinessAndNormalisation() {
        assertTrue(SoulTypes.VoiceSpec.EMPTY.isEmpty());
        assertTrue(new SoulTypes.VoiceSpec(null, -7, null, null).isEmpty());
        assertEquals(-1, new SoulTypes.VoiceSpec("", -7, "", "").piperSpeaker(), "below -1 clamps to default");
        assertTrue(!new SoulTypes.VoiceSpec("", -1, "/tmp/ref.wav", "hello").isEmpty(), "a clone anchor is a voice");
    }

    @Test
    void profileWithoutAVoiceBlockUsesTheGlobalVoice() {
        SoulTypes.SoulProfile profile = new SoulTypes.SoulProfile(
                "frens:x", "X", List.of(), List.of(), List.of(), List.of());
        assertEquals(SoulTypes.VoiceSpec.EMPTY, profile.voice());
        assertEquals(SoulTypes.VoiceSpec.EMPTY, SoulProfileRegistry.voiceFor("frens:not-registered"));
    }
}
