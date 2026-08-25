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
                killProcess();
                result.completeExceptionally(ex);
            }
        });
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
        LOGGER.info("[souls] tts engine started pid={}", process.pid());
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

    @Override
    public void close() {
        closed = true;
        engineThread.submit(this::killProcess);
        engineThread.shutdown();
        try {
            if (!engineThread.awaitTermination(1, TimeUnit.SECONDS)) {
                engineThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            engineThread.shutdownNow();
        }
    }
}
