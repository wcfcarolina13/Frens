package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform seam, exercised for Windows from a POSIX host. Fake Windows directories use
 * forward slashes so {@link Path#of} keeps them as single segments on macOS/Linux CI; every
 * assertion compares {@code Path} values built the same way rather than string literals, so
 * nothing here depends on the host's file separator.
 */
class PocketInstallerPlatformTest {

    private static final String USER = "C:/Users/bob";
    private static final String LOCAL = USER + "/AppData/Local";
    private static final String WINDIR = "C:/Windows";
    private static final Path PATH_BIN = Path.of("C:/tools/bin");
    private static final Path PATH_STORE =
            Path.of(LOCAL + "/Microsoft/WindowsApps");

    private static final PocketInstaller.Platform WIN = new PocketInstaller.Platform(
            true, USER, LOCAL, USER, WINDIR, List.of(PATH_BIN, PATH_STORE));

    private static final PocketInstaller.Platform POSIX = new PocketInstaller.Platform(
            false, "/Users/bob", null, null, null, List.of(Path.of("/usr/bin")));

    private static List<Path> exes(List<PocketInstaller.Candidate> cs) {
        return cs.stream().map(PocketInstaller.Candidate::executable).toList();
    }

    @Test
    void windowsUvCandidatesCoverPathThenUserProfileThenLocalAppData() {
        List<PocketInstaller.Candidate> uv = PocketInstaller.uvCandidates(WIN);
        assertEquals(List.of(
                        PATH_BIN.resolve("uv.exe"),
                        Path.of(USER, ".local", "bin", "uv.exe"),
                        Path.of(LOCAL, "Programs", "uv", "uv.exe")),
                exes(uv));
        assertTrue(uv.stream().allMatch(c -> c.args().isEmpty()));
    }

    @Test
    void windowsUvSkipsNullEnvVars() {
        PocketInstaller.Platform bare =
                new PocketInstaller.Platform(true, null, null, null, null, List.of(PATH_BIN));
        assertEquals(List.of(PATH_BIN.resolve("uv.exe")), exes(PocketInstaller.uvCandidates(bare)));
        // No %WINDIR%/%LOCALAPPDATA% entries, but PATH is still probed for py/python.
        assertEquals(List.of(
                        PATH_BIN.resolve("py.exe"), PATH_BIN.resolve("py.exe"),
                        PATH_BIN.resolve("py.exe"), PATH_BIN.resolve("py.exe"),
                        PATH_BIN.resolve("python.exe"), PATH_BIN.resolve("python3.exe")),
                exes(PocketInstaller.pythonCandidates(bare)));
    }

    @Test
    void windowsPythonCandidatesLeadWithPyLauncherAndCarryVersionArgs() {
        List<PocketInstaller.Candidate> py = PocketInstaller.pythonCandidates(WIN);
        List<String> minors = List.of("3.13", "3.12", "3.11", "3.10");

        // py.exe from each PATH dir (WindowsApps excluded), then %WINDIR%\py.exe, four versions each.
        for (int i = 0; i < 4; i++) {
            assertEquals(PATH_BIN.resolve("py.exe"), py.get(i).executable());
            assertEquals(List.of("-" + minors.get(i)), py.get(i).args());
        }
        for (int i = 0; i < 4; i++) {
            assertEquals(Path.of(WINDIR, "py.exe"), py.get(4 + i).executable());
            assertEquals(List.of("-" + minors.get(i)), py.get(4 + i).args());
        }
        // then the per-version LocalAppData installs, then python.exe / python3.exe on PATH.
        assertEquals(List.of(
                        Path.of(LOCAL, "Programs", "Python", "Python313", "python.exe"),
                        Path.of(LOCAL, "Programs", "Python", "Python312", "python.exe"),
                        Path.of(LOCAL, "Programs", "Python", "Python311", "python.exe"),
                        Path.of(LOCAL, "Programs", "Python", "Python310", "python.exe"),
                        PATH_BIN.resolve("python.exe"),
                        PATH_BIN.resolve("python3.exe")),
                exes(py).subList(8, py.size()));
    }

    @Test
    void windowsAppsStubIsNeverACandidate() {
        for (PocketInstaller.Candidate c : PocketInstaller.pythonCandidates(WIN)) {
            assertFalse(c.executable().toString().contains("WindowsApps"), c.toString());
        }
        for (PocketInstaller.Candidate c : PocketInstaller.uvCandidates(WIN)) {
            assertFalse(c.executable().toString().contains("WindowsApps"), c.toString());
        }
    }

    @Test
    void pyLauncherProbeRunsWithItsVersionArg() {
        Path launcher = Path.of(WINDIR, "py.exe");
        List<PocketInstaller.Candidate> pys = List.of(
                new PocketInstaller.Candidate(launcher, List.of("-3.13")),
                new PocketInstaller.Candidate(launcher, List.of("-3.12")));
        PocketInstaller.Runtime rt = PocketInstaller.findRuntime(List.of(), pys, p -> true,
                cmd -> {
                    assertEquals(List.of(launcher.toString(), cmd.get(1), "--version"), cmd);
                    return cmd.get(1).equals("-3.12") ? "Python 3.12.7" : "";
                }).orElseThrow();
        assertEquals("python", rt.kind());
        assertEquals(List.of("-3.12"), rt.args());
        assertEquals(List.of(launcher.toString(), "-3.12", "-m", "venv", "V"),
                PocketInstaller.venvCommand(rt, Path.of("V"), WIN));
    }

    @Test
    void venvLayoutIsScriptsOnWindowsAndBinOnPosix() {
        Path venv = Path.of("C:/game/config/frens/pocket-tts/venv");
        assertEquals(venv.resolve("Scripts").resolve("python.exe"),
                PocketInstaller.venvPython(venv, WIN));
        assertEquals(venv.resolve("Scripts").resolve("pocket-tts.exe"),
                PocketInstaller.venvScript(venv, "pocket-tts", WIN));
        assertEquals(venv.resolve("Scripts").resolve("pip.exe"),
                PocketInstaller.venvScript(venv, "pip", WIN));

        Path posixVenv = Path.of("/game/venv");
        assertEquals(posixVenv.resolve("bin").resolve("python"),
                PocketInstaller.venvPython(posixVenv, POSIX));
        assertEquals(posixVenv.resolve("bin").resolve("pocket-tts"),
                PocketInstaller.venvScript(posixVenv, "pocket-tts", POSIX));
    }

    @Test
    void engineBinaryPathFollowsTheVenvLayout() {
        assertEquals(Path.of("C:/game/pocket-tts", "venv", "Scripts", "pocket-tts.exe"),
                PocketVoiceEngine.binaryPath("C:/game/pocket-tts", WIN));
        assertEquals(Path.of("/game/pocket-tts", "venv", "bin", "pocket-tts"),
                PocketVoiceEngine.binaryPath("/game/pocket-tts", POSIX));
    }

    @Test
    void windowsInstallCommandsUseScriptsPaths() {
        Path venv = Path.of("C:/game/venv");
        PocketInstaller.Runtime uv = new PocketInstaller.Runtime(
                Path.of(LOCAL, "Programs", "uv", "uv.exe"), "uv", "uv 0.8.0");
        assertEquals(List.of(uv.executable().toString(), "venv", "--python", "3.12", venv.toString()),
                PocketInstaller.venvCommand(uv, venv, WIN));
        assertEquals(List.of(uv.executable().toString(), "pip", "install", "--python",
                        venv.resolve("Scripts").resolve("python.exe").toString(),
                        PocketInstaller.PACKAGE_SPEC),
                PocketInstaller.pipInstallCommand(uv, venv, WIN));

        PocketInstaller.Runtime py = new PocketInstaller.Runtime(
                Path.of(WINDIR, "py.exe"), "python", "Python 3.12.7", List.of("-3.12"));
        assertEquals(List.of(venv.resolve("Scripts").resolve("pip.exe").toString(),
                        "install", PocketInstaller.PACKAGE_SPEC),
                PocketInstaller.pipInstallCommand(py, venv, WIN));
    }

    @Test
    void posixCandidateListsAreUnchanged() {
        List<Path> uv = exes(PocketInstaller.uvCandidates(POSIX));
        assertEquals(List.of(
                        Path.of("/opt/homebrew/bin/uv"),
                        Path.of("/Users/bob", ".local", "bin", "uv"),
                        Path.of("/usr/local/bin/uv"),
                        Path.of("/usr/bin/uv")),
                uv);

        List<Path> py = exes(PocketInstaller.pythonCandidates(POSIX));
        assertEquals(List.of(
                        Path.of("/opt/homebrew/bin/python3.13"),
                        Path.of("/opt/homebrew/bin/python3.12"),
                        Path.of("/opt/homebrew/bin/python3.11"),
                        Path.of("/opt/homebrew/bin/python3.10"),
                        Path.of("/opt/homebrew/bin/python3"),
                        Path.of("/Library/Frameworks/Python.framework/Versions/3.13/bin/python3"),
                        Path.of("/Library/Frameworks/Python.framework/Versions/3.12/bin/python3"),
                        Path.of("/Library/Frameworks/Python.framework/Versions/3.11/bin/python3"),
                        Path.of("/Library/Frameworks/Python.framework/Versions/3.10/bin/python3"),
                        Path.of("/usr/local/bin/python3"),
                        Path.of("/usr/bin/python3")),
                py);
        assertTrue(py.stream().allMatch(p -> !p.toString().endsWith(".exe")));
        assertTrue(PocketInstaller.pythonCandidates(POSIX).stream()
                .allMatch(c -> c.args().isEmpty()));
    }

    @Test
    void missingRuntimeMessageIsPlatformAware() {
        assertTrue(PocketInstaller.missingRuntimeMessage(WIN).contains("Add to PATH"));
        assertTrue(PocketInstaller.missingRuntimeMessage(WIN).contains("docs.astral.sh/uv"));
        assertFalse(PocketInstaller.missingRuntimeMessage(POSIX).contains("Add to PATH"));
    }
}
