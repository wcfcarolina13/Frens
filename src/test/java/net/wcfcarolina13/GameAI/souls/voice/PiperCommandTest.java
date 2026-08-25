package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiperCommandTest {

    @Test
    void commandCarriesBinaryModelAndOutputDirInOrder() {
        List<String> cmd = PiperVoiceEngine.command("/opt/homebrew/bin/piper",
                "/voices/jake.onnx", "/tmp/frens-voice");
        assertEquals(List.of("/opt/homebrew/bin/piper",
                "--model", "/voices/jake.onnx",
                "--output_dir", "/tmp/frens-voice"), cmd);
    }
}
