package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class PocketInstallerTest {

    private static final PocketInstaller.Platform POSIX =
            new PocketInstaller.Platform(false, "/Users/tester", null, null, null, List.of());

    private static List<PocketInstaller.Candidate> cands(Path... paths) {
        return java.util.Arrays.stream(paths).map(PocketInstaller.Candidate::new).toList();
    }

    @Test
    void pythonVersionGate() {
        assertTrue(PocketInstaller.pythonVersionOk("Python 3.12.0"));
        assertTrue(PocketInstaller.pythonVersionOk("Python 3.10.14"));
        assertFalse(PocketInstaller.pythonVersionOk("Python 3.9.6"));
        assertFalse(PocketInstaller.pythonVersionOk("Python 2.7.18"));
        assertFalse(PocketInstaller.pythonVersionOk(""));
    }

    @Test
    void uvWinsWhenPresentOtherwiseFirstModernPython() {
        Path uv = Path.of("/opt/homebrew/bin/uv");
        Path oldPy = Path.of("/usr/bin/python3");
        Path newPy = Path.of("/opt/homebrew/bin/python3.11");
        Optional<PocketInstaller.Runtime> withUv = PocketInstaller.findRuntime(
                cands(uv), cands(oldPy, newPy), p -> true,
                cmd -> cmd.get(0).endsWith("uv") ? "uv 0.8.0"
                        : cmd.get(0).equals(oldPy.toString()) ? "Python 3.9.6" : "Python 3.11.9");
        assertEquals("uv", withUv.orElseThrow().kind());
        Optional<PocketInstaller.Runtime> noUv = PocketInstaller.findRuntime(
                cands(uv), cands(oldPy, newPy), p -> !p.equals(uv),
                cmd -> cmd.get(0).equals(oldPy.toString()) ? "Python 3.9.6" : "Python 3.11.9");
        assertEquals("python", noUv.orElseThrow().kind());
        assertEquals(newPy, noUv.orElseThrow().executable());
        assertTrue(PocketInstaller.findRuntime(cands(uv), cands(oldPy), p -> !p.equals(uv),
                cmd -> "Python 3.9.6").isEmpty());
    }

    @Test
    void pythonVersionAnchoredToLineStart() {
        // A version-shaped string that isn't the first line must not leak through find()-style
        // matching anywhere in the text.
        assertFalse(PocketInstaller.pythonVersionOk("permission denied\nPython 3.12.0"));
        assertTrue(PocketInstaller.pythonVersionOk("  Python 3.12.0 \n"));
    }

    @Test
    void launcherNotFoundMessageYieldsNoRuntimeWhenProbeReturnsEmpty() {
        // Models the fixed runForOutput: the Windows `py -3.12` launcher prints
        // "Python 3.12 not found!" on stderr and exits non-zero, and runForOutput now maps any
        // non-zero exit to "" rather than the merged output — so the fake here returns "" for
        // that candidate exactly as the real probe now would.
        Path py = Path.of("/usr/bin/py.exe");
        assertTrue(PocketInstaller.findRuntime(List.of(), cands(py), p -> true,
                cmd -> "").isEmpty());
    }

    @Test
    void commandsPerRuntimeKind() {
        Path venv = Path.of("/cfg/pocket-tts/venv");
        PocketInstaller.Runtime uv = new PocketInstaller.Runtime(Path.of("/opt/homebrew/bin/uv"), "uv", "uv 0.8.0");
        assertEquals(List.of("/opt/homebrew/bin/uv", "venv", "--python", "3.12", venv.toString()),
                PocketInstaller.venvCommand(uv, venv, POSIX));
        assertEquals(List.of("/opt/homebrew/bin/uv", "pip", "install", "--python",
                        venv.resolve("bin/python").toString(), PocketInstaller.PACKAGE_SPEC),
                PocketInstaller.pipInstallCommand(uv, venv, POSIX));
        PocketInstaller.Runtime py = new PocketInstaller.Runtime(Path.of("/opt/homebrew/bin/python3.11"), "python", "Python 3.11.9");
        assertEquals(List.of("/opt/homebrew/bin/python3.11", "-m", "venv", venv.toString()),
                PocketInstaller.venvCommand(py, venv, POSIX));
        assertEquals(List.of(venv.resolve("bin/pip").toString(), "install", PocketInstaller.PACKAGE_SPEC),
                PocketInstaller.pipInstallCommand(py, venv, POSIX));
    }
}
