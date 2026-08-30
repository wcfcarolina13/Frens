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
    private static final Pattern PYTHON_VERSION = Pattern.compile("Python (\\d+)\\.(\\d+)");

    /** An interpreter that can build the venv: {@code kind} is {@code "uv"} or {@code "python"}. */
    public record Runtime(Path executable, String kind, String version) {
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
        Matcher m = PYTHON_VERSION.matcher(versionLine);
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
    static Optional<Runtime> findRuntime(List<Path> uvCandidates, List<Path> pythonCandidates,
                                         Predicate<Path> isExecutable,
                                         Function<List<String>, String> runForOutput) {
        for (Path uv : uvCandidates) {
            if (!isExecutable.test(uv)) {
                continue;
            }
            String out = runForOutput.apply(List.of(uv.toString(), "--version"));
            if (out != null && !out.isBlank()) {
                return Optional.of(new Runtime(uv, "uv", out.trim()));
            }
        }
        for (Path py : pythonCandidates) {
            if (!isExecutable.test(py)) {
                continue;
            }
            String out = runForOutput.apply(List.of(py.toString(), "--version"));
            if (pythonVersionOk(out)) {
                return Optional.of(new Runtime(py, "python", out.trim()));
            }
        }
        return Optional.empty();
    }

    static List<String> venvCommand(Runtime rt, Path venvDir) {
        if ("uv".equals(rt.kind())) {
            return List.of(rt.executable().toString(), "venv", "--python", UV_PYTHON, venvDir.toString());
        }
        return List.of(rt.executable().toString(), "-m", "venv", venvDir.toString());
    }

    static List<String> pipInstallCommand(Runtime rt, Path venvDir) {
        if ("uv".equals(rt.kind())) {
            return List.of(rt.executable().toString(), "pip", "install", "--python",
                    venvDir.resolve("bin/python").toString(), PACKAGE_SPEC);
        }
        return List.of(venvDir.resolve("bin/pip").toString(), "install", PACKAGE_SPEC);
    }

    private static List<Path> pathDirs() {
        List<Path> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path == null) {
            return dirs;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank()) {
                dirs.add(Path.of(dir));
            }
        }
        return dirs;
    }

    private static List<Path> uvCandidates() {
        String home = System.getProperty("user.home", "");
        List<Path> out = new ArrayList<>(List.of(
                Path.of("/opt/homebrew/bin/uv"),
                Path.of(home, ".local", "bin", "uv"),
                Path.of("/usr/local/bin/uv")));
        for (Path dir : pathDirs()) {
            out.add(dir.resolve("uv"));
        }
        return out;
    }

    private static List<Path> pythonCandidates() {
        List<Path> out = new ArrayList<>();
        for (String minor : List.of("3.13", "3.12", "3.11", "3.10")) {
            out.add(Path.of("/opt/homebrew/bin/python" + minor));
        }
        out.add(Path.of("/opt/homebrew/bin/python3"));
        for (String minor : List.of("3.13", "3.12", "3.11", "3.10")) {
            out.add(Path.of("/Library/Frameworks/Python.framework/Versions", minor, "bin", "python3"));
        }
        out.add(Path.of("/usr/local/bin/python3"));
        for (Path dir : pathDirs()) {
            out.add(dir.resolve("python3"));
        }
        return out;
    }

    /** Merged stdout+stderr of a short probe command; any failure or timeout reads as {@code ""}. */
    private static String runForOutput(List<String> cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
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

        Runtime runtime = findRuntime(uvCandidates(), pythonCandidates(),
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
        Runtime rt = plan.runtime();
        if (rt == null) {
            throw new IOException("No Python 3.10+ or uv found. Install uv (docs.astral.sh/uv) "
                    + "or python.org 3.12, then retry.");
        }
        Path installDir = plan.installDir();
        if (!plan.alreadyInstalled()) {
            Files.createDirectories(installDir);
            progress.update("Creating environment...", 0, 0);
            runLogged(venvCommand(rt, plan.venvDir()), progress);
            progress.update("Installing pocket-tts (about 850 MB, a few minutes)...", 0, 0);
            runLogged(pipInstallCommand(rt, plan.venvDir()), progress);
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
