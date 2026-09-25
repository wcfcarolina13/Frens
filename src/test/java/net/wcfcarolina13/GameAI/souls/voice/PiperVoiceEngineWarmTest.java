package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Pre-warm on the real engine class, with a stand-in "piper" shell script that records each
 * start and then idles on stdin like the real binary does between lines.
 */
class PiperVoiceEngineWarmTest {

    @TempDir
    Path dir;

    private static final SoulTypes.VoiceKey JAKE = new SoulTypes.VoiceKey("Jake", "frens:jake");

    private Path fakePiper() throws IOException {
        Path script = dir.resolve("fake-piper.sh");
        Files.writeString(script, """
                #!/bin/sh
                here="$(dirname "$0")"
                echo $$ >> "$here/starts.log"
                exec cat > /dev/null
                """, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private List<String> starts() throws IOException {
        Path log = dir.resolve("starts.log");
        return Files.exists(log) ? Files.readAllLines(log).stream().filter(s -> !s.isBlank()).toList() : List.of();
    }

    private void awaitStarts(int n) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (starts().size() < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void warmSpawnsOnceAndNeverRespawnsAKilledProcess() throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        PiperVoiceEngine engine = new PiperVoiceEngine(fakePiper().toString(),
                dir.resolve("model.onnx").toString(), 2_000L);
        try {
            assertTrue(engine.warm(JAKE));
            assertTrue(engine.warm(JAKE));
            // A different key resolving to the same model/speaker shares that one process.
            assertTrue(engine.warm(new SoulTypes.VoiceKey("Bob", "frens:bob")));
            awaitStarts(1);
            Thread.sleep(300);
            List<String> first = starts();
            assertEquals(1, first.size(), "one process per model+speaker, started once");

            ProcessHandle proc = ProcessHandle.of(Long.parseLong(first.get(0).trim())).orElseThrow();
            proc.destroyForcibly();
            proc.onExit().get(3, TimeUnit.SECONDS);

            // Restarts belong to the synthesis path (and its backoff), never to warm.
            assertTrue(engine.warm(JAKE));
            Thread.sleep(400);
            assertEquals(1, starts().size(), "warm must not respawn a process it already started");
        } finally {
            engine.close();
        }
    }

    @Test
    void warmSwallowsASpawnFailureAndSynthesisStillOwnsTheRetry() throws Exception {
        PiperVoiceEngine engine = new PiperVoiceEngine(dir.resolve("no-such-piper").toString(),
                dir.resolve("model.onnx").toString(), 1_000L);
        try {
            assertTrue(engine.warm(JAKE));
            Thread.sleep(200);
            assertTrue(engine.alive(), "a failed warm leaves the engine retryable");
            // The synth path still attempts its own start (and reports the failure to the caller).
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> engine.synthesize("Hello there.", JAKE).get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException, failure.getCause().toString());
        } finally {
            engine.close();
        }
    }

    @Test
    void warmAfterCloseSchedulesNothing() throws Exception {
        PiperVoiceEngine engine = new PiperVoiceEngine(dir.resolve("no-such-piper").toString(),
                dir.resolve("model.onnx").toString(), 1_000L);
        engine.close();
        assertFalse(engine.warm(JAKE));
    }
}
