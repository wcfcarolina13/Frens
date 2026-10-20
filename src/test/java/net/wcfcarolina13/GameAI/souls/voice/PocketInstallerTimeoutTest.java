package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** POSIX-only: a child that keeps its output open must not hang the probe or the install step. */
class PocketInstallerTimeoutTest {

    private static final List<String> HANGS = List.of("/bin/sh", "-c", "echo started; sleep 30");

    private static void assumeShell() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs /bin/sh");
    }

    @Test
    void probeReturnsEmptyPromptlyWhenChildKeepsOutputOpen() {
        assumeShell();
        long t0 = System.nanoTime();
        String out = PocketInstaller.runForOutput(HANGS, 300);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals("", out);
        assertTrue(ms < 5_000, "probe took " + ms + " ms");
    }

    @Test
    void probeStillReturnsOutputOfAFastCommand() {
        assumeShell();
        assertEquals("Python 3.12.1", PocketInstaller.runForOutput(
                List.of("/bin/sh", "-c", "echo 'Python 3.12.1'"), 5_000).trim());
        assertEquals("", PocketInstaller.runForOutput(List.of("/bin/sh", "-c", "echo x; exit 3"), 5_000));
    }

    @Test
    void installStepTimesOutPromptlyWithAFailure() {
        assumeShell();
        long t0 = System.nanoTime();
        IOException ex = assertThrows(IOException.class,
                () -> PocketInstaller.runLogged(HANGS, (stage, done, total) -> { }, 300));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());
        assertTrue(ms < 5_000, "install step took " + ms + " ms");
    }

    @Test
    void installStepReportsNonZeroExitWithLastLine() {
        assumeShell();
        IOException ex = assertThrows(IOException.class, () -> PocketInstaller.runLogged(
                List.of("/bin/sh", "-c", "echo building; echo boom; exit 2"), (s, d, t) -> { }, 5_000));
        assertTrue(ex.getMessage().contains("exit 2") && ex.getMessage().contains("boom"), ex.getMessage());
    }

    @Test
    void outputReaderGetsAFixedFloorAfterTheProbeExits() throws IOException {
        assertTrue(PocketInstaller.DRAIN_JOIN_FLOOR_MS >= 500, "a probe exiting near the deadline keeps its output");
        String source = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/souls/voice/PocketInstaller.java"));
        assertTrue(source.contains("drain.join(Math.max(DRAIN_JOIN_FLOOR_MS, deadline - System.currentTimeMillis()));"));
        assumeShell();
        // Exits inside its 300 ms budget; the reader's wait is the 500 ms floor, so the output arrives.
        assertEquals("late", PocketInstaller.runForOutput(List.of("/bin/sh", "-c", "sleep 0.1; echo late"), 300).trim());
    }
}
