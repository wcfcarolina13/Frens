package net.wcfcarolina13.GameAI.souls.voice;

import net.fabricmc.loader.api.FabricLoader;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.souls.InstallJob;
import net.wcfcarolina13.GameAI.souls.SoulRuntime;
import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detection + venv provisioning + pinned pip install + smoke-test service behind the
 * Pocket TTS installer screen — the {@link PiperInstaller} shape applied to a Python
 * package instead of a prebuilt binary.
 *
 * <p>Runtime preference: {@code uv} (fast, downloads its own CPython so the machine's
 * python does not matter) over the first {@code python3} at 3.10 or newer. The package is
 * pinned ({@value #PACKAGE_SPEC}); the venv lives under the install dir and is what
 * {@link PocketVoiceEngine#binaryPath(String)} points at. Nothing is inferred at runtime
 * beyond which interpreter exists — every command is a pure function of the runtime kind.
 *
 * <p>Single-player oriented: the venv lands under the game directory and the config is
 * updated in-process, exactly like Piper.
 */
public final class PocketInstaller {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    public static final String PACKAGE_SPEC = "pocket-tts==3.0.2";
    public static final long NEED_FREE_BYTES = 2L * 1024 * 1024 * 1024;
    /** Interpreter uv provisions for the venv (pocket-tts wheels are published for it). */
    private static final String UV_PYTHON = "3.12";
    private static final long PROBE_TIMEOUT_SECONDS = 10;
    private static final long STEP_TIMEOUT_MINUTES = 30;
    private static final long SMOKE_TIMEOUT_SECONDS = 150;
    private static final Pattern PYTHON_VERSION = Pattern.compile("^Python (\\d+)\\.(\\d+)");
    /** Interpreter minor versions we look for, newest first. */
    private static final List<String> MINORS = List.of("3.13", "3.12", "3.11", "3.10");

    /**
     * The only place OS differences enter this class. Built from system properties and the
     * environment by {@link #current()}, or hand-built in tests so every candidate list and
     * venv path below is exercisable on any host.
     *
     * <p>{@code localAppData}, {@code userProfile} and {@code windir} are the Windows env vars
     * and may be null/blank anywhere (including on Windows, if the environment is odd).
     */
    public record Platform(boolean windows, String home, String localAppData, String userProfile,
                           String windir, List<Path> pathDirs) {

        public static Platform current() {
            boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            List<Path> dirs = new ArrayList<>();
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(java.io.File.pathSeparator)) {
                    if (!dir.isBlank()) {
                        dirs.add(Path.of(dir));
                    }
                }
            }
            return new Platform(win, System.getProperty("user.home", ""),
                    System.getenv("LOCALAPPDATA"), System.getenv("USERPROFILE"),
                    System.getenv("WINDIR"), List.copyOf(dirs));
        }

        /** Appends {@code .exe} on Windows so one call site covers both layouts. */
        String exe(String name) {
            return windows ? name + ".exe" : name;
        }
    }

    /**
     * One thing that might be an interpreter: an executable plus the fixed arguments that
     * select a version. Empty for a plain {@code python3}; {@code ["-3.12"]} for the Windows
     * {@code py} launcher, which is a single shim for every installed CPython.
     */
    public record Candidate(Path executable, List<String> args) {
        public Candidate(Path executable) {
            this(executable, List.of());
        }

        /** The candidate as a command prefix, e.g. {@code [py.exe, -3.12]}. */
        List<String> command() {
            List<String> out = new ArrayList<>();
            out.add(executable.toString());
            out.addAll(args);
            return out;
        }
    }

    /**
     * An interpreter that can build the venv: {@code kind} is {@code "uv"} or {@code "python"}.
     * {@code args} carries any version-selecting prefix (the Windows {@code py -3.12} case).
     */
    public record Runtime(Path executable, String kind, String version, List<String> args) {
        public Runtime(Path executable, String kind, String version) {
            this(executable, kind, version, List.of());
        }

        List<String> command() {
            return new Candidate(executable, args).command();
        }
    }

    /** Everything the installer screen shows the user before they click anything. {@code runtime} may be null. */
    public record Plan(Path installDir, Path venvDir, Path binary, boolean alreadyInstalled,
                       Runtime runtime, long freeDiskBytes, boolean hfTokenPresent) {
    }

    private PocketInstaller() {
    }

    // ── Background job (screen-independent, survives menu close/reopen) ──────

    private static final AtomicReference<InstallJob> ACTIVE_JOB = new AtomicReference<>();

    /** The running (or finished-but-unconsumed) install job, or null. */
    public static InstallJob activeJob() {
        return ACTIVE_JOB.get();
    }

    /** Clears a finished job so a new one can start; no-op while one is still running. */
    public static void clearFinishedJob() {
        InstallJob job = ACTIVE_JOB.get();
        if (job != null && job.finished()) {
            ACTIVE_JOB.compareAndSet(job, null);
        }
    }

    /**
     * Starts {@link #install} on a service-owned daemon thread. Returns false if a job is
     * already active — the caller should attach to {@link #activeJob()} instead, which is
     * what makes a double-start (two pip installs racing on the same venv) impossible even
     * if the screen is closed and reopened mid-install.
     */
    public static boolean installAsync(Plan plan) {
        InstallJob job = new InstallJob("Pocket TTS install");
        if (!ACTIVE_JOB.compareAndSet(null, job)) {
            return false;
        }
        Thread t = new Thread(() -> {
            try {
                install(plan, job::progress);
                job.finishOk();
            } catch (Throwable ex) {
                job.finishFailed(String.valueOf(ex.getMessage()));
            }
        }, "frens-pocket-install");
        t.setDaemon(true);
        t.start();
        return true;
    }

    // ── Detection ────────────────────────────────────────────────────────────

    public static Path defaultInstallDir() {
        return FabricLoader.getInstance().getGameDir().resolve("config").resolve("frens")
                .resolve("pocket-tts");
    }

    static boolean pythonVersionOk(String versionLine) {
        if (versionLine == null) {
            return false;
        }
        String firstLine = versionLine.strip();
        int nl = firstLine.indexOf('\n');
        if (nl >= 0) {
            firstLine = firstLine.substring(0, nl).strip();
        }
        Matcher m = PYTHON_VERSION.matcher(firstLine);
        if (!m.find()) {
            return false;
        }
        return Integer.parseInt(m.group(1)) == 3 && Integer.parseInt(m.group(2)) >= 10;
    }

    /**
     * Picks the runtime: the first executable uv candidate that answers {@code --version},
     * else the first executable python candidate whose {@code --version} passes the 3.10 gate.
     * Pure over the injected probes so the preference order is unit-testable.
     */
    static Optional<Runtime> findRuntime(List<Candidate> uvCandidates, List<Candidate> pythonCandidates,
                                         Predicate<Path> isExecutable,
                                         Function<List<String>, String> runForOutput) {
        for (Candidate uv : uvCandidates) {
            if (!isExecutable.test(uv.executable())) {
                continue;
            }
            List<String> probe = uv.command();
            probe.add("--version");
            String out = runForOutput.apply(List.copyOf(probe));
            if (out != null && !out.isBlank()) {
                return Optional.of(new Runtime(uv.executable(), "uv", out.trim(), uv.args()));
            }
        }
        for (Candidate py : pythonCandidates) {
            if (!isExecutable.test(py.executable())) {
                continue;
            }
            List<String> probe = py.command();
            probe.add("--version");
            String out = runForOutput.apply(List.copyOf(probe));
            if (pythonVersionOk(out)) {
                return Optional.of(new Runtime(py.executable(), "python", out.trim(), py.args()));
            }
        }
        return Optional.empty();
    }

    // ── venv layout (the one place bin/ vs Scripts\ is decided) ──────────────

    /** {@code <venv>/bin} on POSIX, {@code <venv>\Scripts} on Windows. */
    static Path venvBinDir(Path venvDir, Platform platform) {
        return venvDir.resolve(platform.windows() ? "Scripts" : "bin");
    }

    /** The venv's own interpreter. */
    static Path venvPython(Path venvDir, Platform platform) {
        return venvBinDir(venvDir, platform).resolve(platform.exe("python"));
    }

    /** A console script installed into the venv ({@code pip}, {@code pocket-tts}, ...). */
    static Path venvScript(Path venvDir, String name, Platform platform) {
        return venvBinDir(venvDir, platform).resolve(platform.exe(name));
    }

    static List<String> venvCommand(Runtime rt, Path venvDir, Platform platform) {
        if ("uv".equals(rt.kind())) {
            List<String> cmd = rt.command();
            cmd.addAll(List.of("venv", "--python", UV_PYTHON, venvDir.toString()));
            return List.copyOf(cmd);
        }
        List<String> cmd = rt.command();
        cmd.addAll(List.of("-m", "venv", venvDir.toString()));
        return List.copyOf(cmd);
    }

    static List<String> pipInstallCommand(Runtime rt, Path venvDir, Platform platform) {
        if ("uv".equals(rt.kind())) {
            List<String> cmd = rt.command();
            cmd.addAll(List.of("pip", "install", "--python",
                    venvPython(venvDir, platform).toString(), PACKAGE_SPEC));
            return List.copyOf(cmd);
        }
        return List.of(venvScript(venvDir, "pip", platform).toString(), "install", PACKAGE_SPEC);
    }

    // ── Candidate lists ──────────────────────────────────────────────────────

    /**
     * The Microsoft Store ships {@code python.exe}/{@code python3.exe} alias stubs under
     * {@code WindowsApps}; running one opens the Store instead of a Python, so probing it
     * would pop a shop window at the user mid-detection. Never a candidate.
     */
    private static boolean storeAliasStub(Path p) {
        return p.toString().toLowerCase(Locale.ROOT).contains("windowsapps");
    }

    private static void addCandidate(List<Candidate> out, Path exe, List<String> args) {
        if (!storeAliasStub(exe)) {
            out.add(new Candidate(exe, args));
        }
    }

    static List<Candidate> uvCandidates(Platform platform) {
        List<Candidate> out = new ArrayList<>();
        if (platform.windows()) {
            for (Path dir : platform.pathDirs()) {
                addCandidate(out, dir.resolve("uv.exe"), List.of());
            }
            if (platform.userProfile() != null && !platform.userProfile().isBlank()) {
                addCandidate(out, Path.of(platform.userProfile(), ".local", "bin", "uv.exe"), List.of());
            }
            if (platform.localAppData() != null && !platform.localAppData().isBlank()) {
                addCandidate(out, Path.of(platform.localAppData(), "Programs", "uv", "uv.exe"), List.of());
            }
            return List.copyOf(out);
        }
        String home = platform.home() == null ? "" : platform.home();
        out.add(new Candidate(Path.of("/opt/homebrew/bin/uv")));
        out.add(new Candidate(Path.of(home, ".local", "bin", "uv")));
        out.add(new Candidate(Path.of("/usr/local/bin/uv")));
        for (Path dir : platform.pathDirs()) {
            out.add(new Candidate(dir.resolve("uv")));
        }
        return List.copyOf(out);
    }

    static List<Candidate> pythonCandidates(Platform platform) {
        List<Candidate> out = new ArrayList<>();
        if (platform.windows()) {
            List<Path> launchers = new ArrayList<>();
            for (Path dir : platform.pathDirs()) {
                launchers.add(dir.resolve("py.exe"));
            }
            if (platform.windir() != null && !platform.windir().isBlank()) {
                launchers.add(Path.of(platform.windir(), "py.exe"));
            }
            for (Path launcher : launchers) {
                for (String minor : MINORS) {
                    addCandidate(out, launcher, List.of("-" + minor));
                }
            }
            if (platform.localAppData() != null && !platform.localAppData().isBlank()) {
                for (String minor : MINORS) {
                    addCandidate(out, Path.of(platform.localAppData(), "Programs", "Python",
                            "Python" + minor.replace(".", ""), "python.exe"), List.of());
                }
            }
            for (Path dir : platform.pathDirs()) {
                addCandidate(out, dir.resolve("python.exe"), List.of());
            }
            for (Path dir : platform.pathDirs()) {
                addCandidate(out, dir.resolve("python3.exe"), List.of());
            }
            return List.copyOf(out);
        }
        for (String minor : MINORS) {
            out.add(new Candidate(Path.of("/opt/homebrew/bin/python" + minor)));
        }
        out.add(new Candidate(Path.of("/opt/homebrew/bin/python3")));
        for (String minor : MINORS) {
            out.add(new Candidate(
                    Path.of("/Library/Frameworks/Python.framework/Versions", minor, "bin", "python3")));
        }
        out.add(new Candidate(Path.of("/usr/local/bin/python3")));
        for (Path dir : platform.pathDirs()) {
            out.add(new Candidate(dir.resolve("python3")));
        }
        return List.copyOf(out);
    }

    /** How to tell the user to get a runtime when none was found. */
    static String missingRuntimeMessage(Platform platform) {
        return platform.windows()
                ? "No Python 3.10+ or uv found. Install uv (docs.astral.sh/uv) or Python 3.12 "
                + "from python.org (tick 'Add to PATH'), then retry."
                : "No Python 3.10+ or uv found. Install uv (docs.astral.sh/uv) "
                + "or python.org 3.12, then retry.";
    }

    /** One-line variant of {@link #missingRuntimeMessage} for the installer screen row. */
    public static String missingRuntimeHint() {
        return Platform.current().windows()
                ? "No Python 3.10+/uv found. Install uv or python.org 3.12, Add PATH."
                : "No Python 3.10+ or uv found — install uv from docs.astral.sh/uv";
    }

    /**
     * Merged stdout+stderr of a short probe command; any failure, timeout, or non-zero exit
     * reads as {@code ""}. A non-zero exit matters here: the Windows {@code py -3.12} launcher
     * prints a line shaped like a version string (e.g. {@code "Python 3.12 not found!"}) on
     * stderr while exiting non-zero when that interpreter isn't installed, and that text must
     * not be mistaken for a passing version probe.
     */
    private static String runForOutput(List<String> cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            if (p.exitValue() != 0) {
                return "";
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
            return "";
        } catch (Exception ex) {
            return "";
        }
    }

    public static Plan detect() {
        ManualConfig cfg = Frens.CONFIG;
        String configured = cfg == null ? "" : cfg.getSoulVoicePocketDir();
        Path installDir = configured == null || configured.isBlank()
                ? defaultInstallDir() : Path.of(configured);
        Path venvDir = installDir.resolve("venv");
        Path binary = PocketVoiceEngine.binaryPath(installDir.toString());
        boolean alreadyInstalled = Files.isExecutable(binary);

        Platform platform = Platform.current();
        // Files::isExecutable is POSIX-permission-based on POSIX and ACL-based on Windows;
        // the Windows path has not been verified against real installs (uv/python.org/Store
        // layouts) — if candidates are wrongly skipped there, check this predicate first.
        Runtime runtime = findRuntime(uvCandidates(platform), pythonCandidates(platform),
                Files::isExecutable, PocketInstaller::runForOutput).orElse(null);

        long free;
        try {
            Files.createDirectories(installDir);
            free = Files.getFileStore(installDir).getUsableSpace();
        } catch (IOException e) {
            free = -1L;
        }

        boolean hfToken = Files.isRegularFile(
                Path.of(System.getProperty("user.home", ""), ".cache", "huggingface", "token"));

        return new Plan(installDir, venvDir, binary, alreadyInstalled, runtime, free, hfToken);
    }

    // ── Install ──────────────────────────────────────────────────────────────

    /**
     * Provisions the venv + package when not already installed, then smoke-tests a real
     * {@link PocketVoiceEngine} (first run downloads the model) and writes the config.
     * Worker thread only.
     */
    static void install(Plan plan, PiperInstaller.Progress progress) throws Exception {
        Platform platform = Platform.current();
        Runtime rt = plan.runtime();
        if (rt == null) {
            throw new IOException(missingRuntimeMessage(platform));
        }
        Path installDir = plan.installDir();
        if (!plan.alreadyInstalled()) {
            Files.createDirectories(installDir);
            progress.update("Creating environment...", 0, 0);
            runLogged(venvCommand(rt, plan.venvDir(), platform), progress);
            progress.update("Installing pocket-tts (about 850 MB, a few minutes)...", 0, 0);
            runLogged(pipInstallCommand(rt, plan.venvDir(), platform), progress);
        }
        if (!Files.isExecutable(plan.binary())) {
            throw new IOException("pocket-tts binary missing after install: " + plan.binary());
        }

        progress.update("Testing synthesis (first run downloads the 228 MB model)...", 0, 0);
        PocketVoiceEngine eng = new PocketVoiceEngine(installDir.toString(),
                SoulVoiceSettings.DEFAULT_POCKET_VOICE, 120_000L, k -> SoulTypes.VoiceSpec.EMPTY);
        try {
            byte[] wav = eng.synthesize("Ready.", new SoulTypes.VoiceKey("", ""))
                    .get(SMOKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!PocketVoiceEngine.looksLikeWav(wav)) {
                throw new IOException("smoke test produced no WAV");
            }
        } finally {
            eng.close();
        }

        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            throw new IllegalStateException("Config not loaded");
        }
        cfg.setSoulVoiceEngine(SoulVoiceSettings.ENGINE_POCKET);
        cfg.setSoulVoicePocketDir(installDir.toAbsolutePath().toString());
        cfg.setSoulVoiceEnabled(true);
        cfg.save();
        SoulRuntime.current().ifPresent(r -> r.reloadSettings(cfg).exceptionally(ex -> {
            LOGGER.warn("[souls] reloadSettings failed after pocket install: {}", ex.toString());
            return null;
        }));
        progress.update("Done", 0, 0);
        LOGGER.info("[souls] pocket-tts installed: {}", installDir);
    }

    /**
     * Runs one install step, streaming merged stdout to the progress line (last non-blank
     * line, trimmed to 70 chars) so the user sees pip working rather than a frozen bar.
     */
    private static void runLogged(List<String> cmd, PiperInstaller.Progress progress) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String lastLine = "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                lastLine = trimmed.length() > 70 ? trimmed.substring(0, 70) : trimmed;
                progress.update("pip: " + lastLine, 0, 0);
            }
        }
        if (!p.waitFor(STEP_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException(cmd.get(0) + " timed out after " + STEP_TIMEOUT_MINUTES + " minutes");
        }
        int code = p.exitValue();
        if (code != 0) {
            throw new IOException(cmd.get(0) + " failed (exit " + code + "): " + lastLine);
        }
    }
}
