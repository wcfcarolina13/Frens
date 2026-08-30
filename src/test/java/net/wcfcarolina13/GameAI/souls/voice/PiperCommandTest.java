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
    @Test
    void speakerFlagOnlyWhenRequested() {
        List<String> cmd = PiperVoiceEngine.command("/p/piper", "/v/a.onnx", 3, "/out");
        assertEquals(List.of("/p/piper", "--model", "/v/a.onnx", "--speaker", "3", "--output_dir", "/out"), cmd);
        assertEquals(PiperVoiceEngine.command("/p/piper", "/v/a.onnx", "/out"),
                PiperVoiceEngine.command("/p/piper", "/v/a.onnx", -1, "/out"));
    }

    @Test
    void modelPathResolvesNamesInsideTheVoicesDirAndFallsBackWhenMissing() {
        String dflt = "/cfg/frens/piper/voices/en_US-lessac-medium.onnx";
        java.util.function.Predicate<java.nio.file.Path> exists =
                p -> p.toString().endsWith("en_US-ryan-medium.onnx") || p.toString().equals("/abs/custom.onnx");
        assertEquals(dflt, PiperVoiceEngine.resolveModelPath(dflt, "", exists), "blank = default");
        assertEquals("/cfg/frens/piper/voices/en_US-ryan-medium.onnx",
                PiperVoiceEngine.resolveModelPath(dflt, "en_US-ryan-medium", exists), "bare name → voices dir");
        assertEquals("/abs/custom.onnx", PiperVoiceEngine.resolveModelPath(dflt, "/abs/custom.onnx", exists));
        assertEquals(dflt, PiperVoiceEngine.resolveModelPath(dflt, "en_US-amy-medium", exists), "missing → default");
    }
}
