package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Long-lived Piper subprocesses, one per distinct voice. Framing per process: write one
 * sanitized line to stdin, Piper writes a WAV file into that process's output directory and
 * prints its path to stdout; we read that line, load the bytes, and delete the file. A
 * synthesis timeout kills and restarts the affected process (a hung engine is not trusted).
 * All process I/O happens on the single engine thread.
 *
 * <p>Per-bot voices (2026-08-29): {@code voiceId} is the bot's soul profile id. The injected
 * {@code voiceResolver} maps it to a {@link SoulTypes.VoiceSpec}; a Piper voice there selects
 * a model (a bare name resolves inside the voices directory next to the configured default
 * model, e.g. {@code en_US-ryan-medium} → {@code …/voices/en_US-ryan-medium.onnx}) and an
 * optional {@code --speaker} id for multi-speaker models. Missing voice files fall back to
 * the default model with one warning per name. Processes stay warm for the engine's life.
 */
public final class PiperVoiceEngine implements SoulVoiceEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    private final String binary;
    private final String modelPath;
    private final long synthTimeoutMs;
    private final Path outputDir;
    private final Function<String, SoulTypes.VoiceSpec> voiceResolver;
    private final ExecutorService engineThread =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "frens-soul-voice-engine");
                t.setDaemon(true);
                return t;
            });

    /** Engine-thread only: one warm Piper per (model path, speaker). */
    private final Map<String, PiperProcess> processes = new LinkedHashMap<>();
    private final Set<String> warnedMissingVoices = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** One Piper subprocess and its stdio; owned and touched only by the engine thread. */
    private static final class PiperProcess {
        final String model;
        final int speaker;
        final Path outDir;
        Process process;
        Writer stdin;
        BufferedReader stdout;

        PiperProcess(String model, int speaker, Path outDir) {
            this.model = model;
            this.speaker = speaker;
            this.outDir = outDir;
        }
    }

    public PiperVoiceEngine(String binary, String modelPath, long synthTimeoutMs) throws IOException {
        this(binary, modelPath, synthTimeoutMs, id -> SoulTypes.VoiceSpec.EMPTY);
    }

    public PiperVoiceEngine(String binary, String modelPath, long synthTimeoutMs,
                            Function<String, SoulTypes.VoiceSpec> voiceResolver) throws IOException {
        this.binary = binary;
        this.modelPath = modelPath;
        this.synthTimeoutMs = synthTimeoutMs;
        this.voiceResolver = voiceResolver == null ? id -> SoulTypes.VoiceSpec.EMPTY : voiceResolver;
        this.outputDir = Files.createTempDirectory("frens-soul-voice");
    }

    public static List<String> command(String binary, String modelPath, String outputDir) {
        return command(binary, modelPath, -1, outputDir);
    }

    /** {@code speaker < 0} means the model's default speaker (no {@code --speaker} flag). */
    public static List<String> command(String binary, String modelPath, int speaker, String outputDir) {
        List<String> cmd = new ArrayList<>(List.of(binary, "--model", modelPath));
        if (speaker >= 0) {
            cmd.add("--speaker");
            cmd.add(Integer.toString(speaker));
        }
        cmd.add("--output_dir");
        cmd.add(outputDir);
        return List.copyOf(cmd);
    }

    /** The directory voice files live in: the parent of the configured default model. */
    public static Path voicesDir(String defaultModelPath) {
        Path parent = Path.of(defaultModelPath).toAbsolutePath().getParent();
        return parent == null ? Path.of(".").toAbsolutePath() : parent;
    }

    /**
     * Pure model-path resolution: blank → the default model; a value containing a path
     * separator or ending in {@code .onnx} is taken as a path; a bare name resolves to
     * {@code <voicesDir>/<name>.onnx}. Anything that does not {@code exist} falls back to the
     * default model (the caller logs that once per name).
     */
    public static String resolveModelPath(String defaultModelPath, String requested, Predicate<Path> exists) {
        String want = requested == null ? "" : requested.trim();
        if (want.isEmpty()) {
            return defaultModelPath;
        }
        Path candidate;
        if (want.contains("/") || want.contains("\\") || want.toLowerCase(Locale.ROOT).endsWith(".onnx")) {
            candidate = Path.of(want);
            if (!candidate.isAbsolute()) {
                candidate = voicesDir(defaultModelPath).resolve(want);
            }
        } else {
            candidate = voicesDir(defaultModelPath).resolve(want + ".onnx");
        }
        return exists.test(candidate) ? candidate.toString() : defaultModelPath;
    }

    /**
     * The prebuilt macOS piper binary carries no LC_RPATH, so its @rpath dylibs (placed
     * beside it by {@link PiperInstaller}) only resolve via DYLD_LIBRARY_PATH; Linux gets
     * the same treatment with LD_LIBRARY_PATH as harmless insurance (its archive ships
     * .so files beside the binary too). No-op on Windows (DLLs load from the exe's dir).
     */
    public static void applyLibraryPathEnv(ProcessBuilder builder, String binary) {
        try {
            java.nio.file.Path parent = java.nio.file.Path.of(binary).toAbsolutePath().getParent();
            if (parent == null) {
                return;
            }
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("mac")) {
                builder.environment().put("DYLD_LIBRARY_PATH", parent.toString());
            } else if (os.contains("linux")) {
                builder.environment().put("LD_LIBRARY_PATH", parent.toString());
            }
        } catch (RuntimeException ignored) {
            // unparseable binary path — spawn without the env override
        }
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try {
            engineThread.submit(() -> {
                PiperProcess proc = null;
                try {
                    proc = processFor(voiceId);
                    ensureProcess(proc);
                    proc.stdin.write(text.replace('\n', ' '));
                    proc.stdin.write('\n');
                    proc.stdin.flush();
                    String wavPath = readLineWithDeadline(proc);
                    Path file = Path.of(wavPath.trim());
                    byte[] bytes = Files.readAllBytes(file);
                    Files.deleteIfExists(file);
                    result.complete(bytes);
                } catch (Exception ex) {
                    if (proc != null) {
                        killProcess(proc);
                    }
                    result.completeExceptionally(ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            result.completeExceptionally(new IllegalStateException("engine closed", ex));
        }
        return result;
    }

    /** Engine thread: pick (or create the bookkeeping for) the process serving this voice. */
    private PiperProcess processFor(String voiceId) throws IOException {
        SoulTypes.VoiceSpec spec;
        try {
            spec = voiceResolver.apply(voiceId == null ? "" : voiceId);
        } catch (RuntimeException resolveFailure) {
            spec = SoulTypes.VoiceSpec.EMPTY;
        }
        if (spec == null) {
            spec = SoulTypes.VoiceSpec.EMPTY;
        }
        String model = resolveModelPath(modelPath, spec.piperModel(), Files::isRegularFile);
        if (!spec.piperModel().isEmpty() && model.equals(modelPath)
                && !resolveModelPath(modelPath, spec.piperModel(), p -> true).equals(modelPath)
                && warnedMissingVoices.add(spec.piperModel())) {
            LOGGER.warn("[souls] tts voice '{}' for profile {} not found under {} — using the default voice",
                    spec.piperModel(), voiceId, voicesDir(modelPath));
        }
        int speaker = spec.piperSpeaker();
        String key = model + "#" + speaker;
        PiperProcess proc = processes.get(key);
        if (proc == null) {
            Path outDir = processes.isEmpty() ? outputDir
                    : Files.createDirectories(outputDir.resolve("v" + processes.size()));
            proc = new PiperProcess(model, speaker, outDir);
            processes.put(key, proc);
        }
        return proc;
    }

    /** Blocking stdout read bounded by the synth deadline, running ON the engine thread. */
    private String readLineWithDeadline(PiperProcess proc) throws Exception {
        long deadline = System.currentTimeMillis() + synthTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (proc.stdout.ready()) {
                String line = proc.stdout.readLine();
                if (line == null) {
                    throw new IOException("piper stdout closed");
                }
                if (!line.isBlank()) {
                    return line;
                }
            } else {
                Thread.sleep(20);
            }
        }
        throw new TimeoutException("piper synthesis exceeded " + synthTimeoutMs + "ms");
    }

    private void ensureProcess(PiperProcess proc) throws IOException {
        if (closed) {
            throw new IOException("engine closed");
        }
        if (proc.process != null && proc.process.isAlive()) {
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(command(binary, proc.model, proc.speaker, proc.outDir.toString()));
        builder.redirectErrorStream(false);
        applyLibraryPathEnv(builder, binary);
        proc.process = builder.start();
        proc.stdin = new java.io.OutputStreamWriter(proc.process.getOutputStream(), StandardCharsets.UTF_8);
        proc.stdout = new BufferedReader(new InputStreamReader(proc.process.getInputStream(), StandardCharsets.UTF_8));
        startStderrDrain(proc.process);
        LOGGER.info("[souls] tts engine started pid={} model={}{}", proc.process.pid(),
                Path.of(proc.model).getFileName(), proc.speaker >= 0 ? " speaker=" + proc.speaker : "");
    }

    /**
     * Piper's stderr is unused by our stdout-path protocol but must still be drained — an
     * un-read, full stderr pipe can block the child process and cause spurious synth timeouts.
     * Runs on its own daemon thread (never the engine thread) for the lifetime of one process;
     * discards content, logs only a content-free char count at debug level.
     */
    private void startStderrDrain(Process proc) {
        Thread drain = new Thread(() -> {
            long charsRead = 0;
            try (InputStreamReader err = new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[512];
                int n;
                while ((n = err.read(buf)) != -1) {
                    charsRead += n;
                }
            } catch (IOException ignored) {
                // process exited or stream closed; nothing left to drain
            }
            if (charsRead > 0 && LOGGER.isDebugEnabled()) {
                LOGGER.debug("[souls] tts stderr drained pid={} chars={}", proc.pid(), charsRead);
            }
        }, "frens-soul-voice-stderr");
        drain.setDaemon(true);
        drain.start();
    }

    private static void killProcess(PiperProcess proc) {
        if (proc.process != null) {
            proc.process.destroyForcibly();
            proc.process = null;
        }
    }

    /** Engine thread: tear down every warm process. */
    private void killAll() {
        for (PiperProcess proc : processes.values()) {
            killProcess(proc);
        }
    }

    /**
     * True whenever the engine has not been closed. This is a retryability signal, not a
     * live process-health probe: {@link #ensureProcess} restarts a dead or never-started
     * process on the next {@link #synthesize} call, so a transient synthesis failure must
     * never permanently gate callers out — health/backoff policy (restart with capped
     * backoff, self-disable after repeated failures) lives in {@link SoulVoiceService}, not
     * here.
     */
    @Override
    public boolean alive() {
        return !closed;
    }

    /**
     * Idempotent, non-blocking teardown: flips {@link #closed}, submits {@link #killAll()} to
     * the engine thread, and calls {@code engineThread.shutdown()} (all three are fire-and-forget
     * — submit only enqueues, shutdown only stops accepting new work) — then returns immediately.
     * The parts that actually block ({@code awaitTermination}, the {@code shutdownNow} escalation,
     * and the output-directory filesystem cleanup) run on a short-lived daemon closer thread
     * instead of the caller's thread. This must never block: {@link SoulVoiceService#close()}
     * calls this from {@code SoulRuntime.closePipeline}, which runs under
     * {@code SoulRuntime}'s {@code lifecycleLock} on the server thread (both from
     * {@code SERVER_STOPPING} via {@code SoulRuntime.stop()} and from {@code /bot soul} commands
     * via {@code reloadSettings}) — a blocking wait there would stall the server thread.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            engineThread.submit(this::killAll);
        } catch (RejectedExecutionException ignored) {
            // engine thread is already shutting down; nothing left to submit to
        }
        engineThread.shutdown();
        Thread closer = new Thread(() -> {
            try {
                if (!engineThread.awaitTermination(1, TimeUnit.SECONDS)) {
                    engineThread.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                engineThread.shutdownNow();
            }
            deleteOutputDirQuietly();
        }, "frens-soul-voice-closer");
        closer.setDaemon(true);
        closer.start();
    }

    /** Best-effort cleanup of the temp WAV-drop directory tree; failures are ignored on close. */
    private void deleteOutputDirQuietly() {
        try {
            if (Files.isDirectory(outputDir)) {
                try (var walk = Files.walk(outputDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
