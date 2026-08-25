package net.wcfcarolina13.GameAI.souls.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One long-lived Piper subprocess. Framing: write one sanitized line to stdin, Piper writes a
 * WAV file into {@code outputDir} and prints its path to stdout; we read that line, load the
 * bytes, and delete the file. A synthesis timeout kills and restarts the process (a hung
 * engine is not trusted). All process I/O happens on the single engine thread.
 */
public final class PiperVoiceEngine implements SoulVoiceEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    private final String binary;
    private final String modelPath;
    private final long synthTimeoutMs;
    private final Path outputDir;
    private final ExecutorService engineThread =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "frens-soul-voice-engine");
                t.setDaemon(true);
                return t;
            });

    private Process process;
    private Writer stdin;
    private BufferedReader stdout;
    private volatile boolean closed;
    /**
     * True when the last synthesis attempt failed (process wouldn't start, crashed mid-request,
     * or timed out). Cleared back to false on the next successful synthesis. {@link #alive()}
     * reports whether the last interaction with the process was healthy or untried — it is not
     * a live process-health probe.
     */
    private volatile boolean lastStartFailed;

    public PiperVoiceEngine(String binary, String modelPath, long synthTimeoutMs) throws IOException {
        this.binary = binary;
        this.modelPath = modelPath;
        this.synthTimeoutMs = synthTimeoutMs;
        this.outputDir = Files.createTempDirectory("frens-soul-voice");
    }

    public static List<String> command(String binary, String modelPath, String outputDir) {
        return List.of(binary, "--model", modelPath, "--output_dir", outputDir);
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try {
            engineThread.submit(() -> {
                try {
                    ensureProcess();
                    stdin.write(text.replace('\n', ' '));
                    stdin.write('\n');
                    stdin.flush();
                    String wavPath = readLineWithDeadline();
                    Path file = Path.of(wavPath.trim());
                    byte[] bytes = Files.readAllBytes(file);
                    Files.deleteIfExists(file);
                    lastStartFailed = false;
                    result.complete(bytes);
                } catch (Exception ex) {
                    lastStartFailed = true;
                    killProcess();
                    result.completeExceptionally(ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            result.completeExceptionally(new IllegalStateException("engine closed", ex));
        }
        return result;
    }

    /** Blocking stdout read bounded by the synth deadline, running ON the engine thread. */
    private String readLineWithDeadline() throws Exception {
        long deadline = System.currentTimeMillis() + synthTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (stdout.ready()) {
                String line = stdout.readLine();
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

    private void ensureProcess() throws IOException {
        if (closed) {
            throw new IOException("engine closed");
        }
        if (process != null && process.isAlive()) {
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(command(binary, modelPath, outputDir.toString()));
        builder.redirectErrorStream(false);
        process = builder.start();
        stdin = new java.io.OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        startStderrDrain(process);
        LOGGER.info("[souls] tts engine started pid={}", process.pid());
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

    private void killProcess() {
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
    }

    @Override
    public boolean alive() {
        return !closed && !lastStartFailed;
    }

    /**
     * Idempotent, non-blocking teardown: flips {@link #closed}, submits {@link #killProcess()} to
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
            engineThread.submit(this::killProcess);
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

    /** Best-effort cleanup of the temp WAV-drop directory; failures are ignored on close. */
    private void deleteOutputDirQuietly() {
        try {
            if (Files.isDirectory(outputDir)) {
                try (var entries = Files.list(outputDir)) {
                    entries.forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
                }
            }
            Files.deleteIfExists(outputDir);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
