package net.wcfcarolina13.GameAI.souls.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure coverage for the Dreamsleeve engine's argv builder and speak-request protocol line. */
class DreamsleeveVoiceEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void commandRunsTheServerScriptOnThePrivateSocket() {
        List<String> cmd = DreamsleeveVoiceEngine.command(
                "/Users/roti/pontus/openmw-forge/dreamsleeve", "/tmp/x/tts.sock", "/tmp/x");
        assertEquals(6, cmd.size());
        assertTrue(cmd.get(0).endsWith("/.venvs/qwen-tts/bin/python"));
        assertEquals("/Users/roti/pontus/openmw-forge/dreamsleeve/scripts/tts_server.py", cmd.get(1));
        assertEquals(List.of("--sock", "/tmp/x/tts.sock", "--out", "/tmp/x"), cmd.subList(2, 6));
    }

    @Test
    void speakRequestIsOneDurableRenderToFileLine() throws Exception {
        String line = DreamsleeveVoiceEngine.speakRequestJson(
                "Hello there.", "/refs/calm.wav", "Yes? What can I do for you?", "/out/frens-1.wav");
        assertTrue(line.endsWith("\n"), "protocol is newline-terminated JSON");
        JsonNode req = JSON.readTree(line);
        assertEquals("speak", req.get("op").asText());
        assertEquals("Hello there.", req.get("text").asText());
        assertEquals("/refs/calm.wav", req.get("ref_audio").asText());
        assertEquals("Yes? What can I do for you?", req.get("ref_text").asText());
        assertEquals("/out/frens-1.wav", req.get("out_file").asText());
        assertFalse(req.get("play").asBoolean(), "out_file renders must never afplay");
        assertEquals(DreamsleeveVoiceEngine.DEFAULT_MODEL_ID, req.get("model").asText());
        assertEquals(DreamsleeveVoiceEngine.CLONE_TEMPERATURE,
                req.get("temperature").asDouble(), 0.0001);
    }

    @Test
    void blankRefTextIsOmittedSoTheServerNeverRunsWhisperPerLine() throws Exception {
        JsonNode req = JSON.readTree(DreamsleeveVoiceEngine.speakRequestJson(
                "Hi.", "/refs/calm.wav", "", "/out/frens-2.wav"));
        // A present-but-empty ref_text would make the server transcribe the ref clip per render
        // (Dreamsleeve's own round-4 finding); omit the field entirely when blank.
        assertFalse(req.has("ref_text"));
    }
}
