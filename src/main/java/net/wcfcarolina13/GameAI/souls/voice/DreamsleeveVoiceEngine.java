package net.wcfcarolina13.GameAI.souls.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Voice-clone engine backed by the Dreamsleeve warm TTS server (Qwen3-TTS on MLX/Metal — the
 * same stack that voices Casca in the OpenMW Dreamsleeve project). The engine owns one server
 * subprocess started from the {@code qwen-tts} venv on a PRIVATE Unix socket (never
 * Dreamsleeve's own daemon socket, so an OpenMW session and Frens can coexist), and speaks the
 * server's newline-terminated JSON protocol: an {@code op:"speak"} request carrying the text,
 * the voice-anchor reference clip + transcript that Qwen clones per line, and an
 * {@code out_file} the server renders a dry, loudness-normalized 16-bit mono WAV into via an
 * atomic rename — the engine polls for that file, reads it, deletes it.
 *
 * <p>Same lifecycle discipline as {@link PiperVoiceEngine}: all process/socket I/O on the
 * single engine thread, stdout/stderr drained on daemon threads, non-blocking idempotent
 * {@link #close()} with a daemon closer thread, {@code alive()} = not closed (restart on next
 * call; health policy lives in {@link SoulVoiceService}).
 *
 * <p>The first line after a cold server start pays the model load (tens of seconds on this
 * hardware); that line may time out and be dropped — the backoff policy retries the next line
 * against the by-then-warm server. A fire-and-forget warm-up request is sent right after spawn
 * to start that load before Jake's first real reply needs it.
 */
public final class DreamsleeveVoiceEngine implements SoulVoiceEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");
    static final String DEFAULT_MODEL_ID = "mlx-community/Qwen3-TTS-12Hz-1.7B-Base-8bit";
    /** Clone-identity stability: the vanilla banks pin temperature low (Dreamsleeve drift fix). */
    static final double CLONE_TEMPERATURE = 0.3;
    private static final String VENV_PYTHON =
            System.getProperty("user.home") + "/.venvs/qwen-tts/bin/python";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String dreamsleeveDir;
    private final String refAudio;
    private final String refText;
    private final long synthTimeoutMs;
    private final Path workDir;
    private final Path sockPath;
    private final AtomicLong requestSeq = new AtomicLong();
    private final ExecutorService engineThread =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "frens-soul-voice-engine");
                t.setDaemon(true);
                return t;
            });

    private Process process;
    private volatile boolean closed;

    public DreamsleeveVoiceEngine(String dreamsleeveDir, String refAudio, String refText,
                                   long synthTimeoutMs) throws IOException {
        this.dreamsleeveDir = dreamsleeveDir;
        this.refAudio = refAudio;
        this.refText = refText;
        this.synthTimeoutMs = synthTimeoutMs;
        this.workDir = Files.createTempDirectory("frens-soul-voice");
        this.sockPath = workDir.resolve("tts.sock");
    }

    public static List<String> command(String dreamsleeveDir, String sockPath, String outDir) {
        return List.of(VENV_PYTHON,
                dreamsleeveDir + "/scripts/tts_server.py",
                "--sock", sockPath,
                "--out", outDir);
    }

    /**
     * Pure builder for one {@code op:"speak"} request line (newline-terminated JSON). The
     * {@code key} keeps barge-in per-conversation server-side; {@code play:false} plus
     * {@code out_file} selects the durable render-to-file path (atomic rename, no afplay).
     */
    static String speakRequestJson(String text, String refAudio, String refText, String outFile) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", "speak");
        req.put("key", "frens-soul");
        req.put("model", DEFAULT_MODEL_ID);
        req.put("text", text);
        req.put("ref_audio", refAudio);
        if (refText != null && !refText.isBlank()) {
            req.put("ref_text", refText);
        }
        req.put("temperature", CLONE_TEMPERATURE);
        req.put("play", false);
        req.put("out_file", outFile);
        try {
            return JSON.writeValueAsString(req) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException("speak request serialization failed", e);
        }
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try {
            engineThread.submit(() -> {
                Path outFile = workDir.resolve("frens-" + requestSeq.incrementAndGet() + ".wav");
                try {
                    ensureServer();
                    sendRequest(speakRequestJson(text.replace('\n', ' '), refAudio, refText,
                            outFile.toString()));
                    byte[] bytes = awaitRenderedFile(outFile);
                    result.complete(bytes);
                } catch (Exception ex) {
                    killProcess();
                    result.completeExceptionally(ex);
                } finally {
                    try {
                        Files.deleteIfExists(outFile);
                    } catch (IOException ignored) {
                        // best-effort
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            result.completeExceptionally(new IllegalStateException("engine closed", ex));
        }
        return result;
    }

    /** Sends one newline-terminated JSON request and reads the one-line ack. Engine thread only. */
    private void sendRequest(String requestLine) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(sockPath));
            channel.write(ByteBuffer.wrap(requestLine.getBytes(StandardCharsets.UTF_8)));
            // One short ack line ({"ok":true}); read whatever arrives, don't parse strictly.
            ByteBuffer ack = ByteBuffer.allocate(256);
            channel.read(ack);
        }
    }

    /**
     * Waits for the server's atomic rename of {@code outFile} (a visible file is complete by
     * construction) up to the synth deadline. Engine thread only.
     */
    private byte[] awaitRenderedFile(Path outFile) throws Exception {
        long deadline = System.currentTimeMillis() + synthTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(outFile)) {
                return Files.readAllBytes(outFile);
            }
            Thread.sleep(50);
        }
        throw new TimeoutException("dreamsleeve synthesis exceeded " + synthTimeoutMs + "ms");
    }

    /** Spawns the warm server on our private socket if it is not already running. */
    private void ensureServer() throws Exception {
        if (closed) {
            throw new IOException("engine closed");
        }
        if (process != null && process.isAlive() && Files.exists(sockPath)) {
            return;
        }
        Files.deleteIfExists(sockPath);
        ProcessBuilder builder = new ProcessBuilder(
                command(dreamsleeveDir, sockPath.toString(), workDir.toString()));
        builder.redirectErrorStream(false);
        process = builder.start();
        drainStream(process, process.getInputStream(), "frens-soul-voice-stdout");
        drainStream(process, process.getErrorStream(), "frens-soul-voice-stderr");
        LOGGER.info("[souls] tts engine started pid={} (dreamsleeve warm server)", process.pid());
        // Wait briefly for the socket to bind so the first request has somewhere to land.
        long bindDeadline = System.currentTimeMillis() + 15_000L;
        while (!Files.exists(sockPath) && System.currentTimeMillis() < bindDeadline) {
            if (!process.isAlive()) {
                throw new IOException("dreamsleeve tts server exited during startup");
            }
            Thread.sleep(100);
        }
        if (!Files.exists(sockPath)) {
            throw new TimeoutException("dreamsleeve tts server socket never appeared");
        }
        // Fire-and-forget warm-up so the model load starts before the first real line.
        try {
            sendRequest(speakRequestJson("Ready.", refAudio, refText,
                    workDir.resolve("warmup.wav").toString()));
        } catch (IOException ignored) {
            // warm-up is opportunistic; the first real line will trigger the load regardless
        }
    }

    /**
     * The server logs to stdout (timeline lines) and stderr; neither is part of our
     * file-render protocol, but both must be drained so a full pipe can never block the
     * child. Content is discarded (never logged — it can echo config paths, not chat text,
     * but content-free is the house rule).
     */
    private void drainStream(Process proc, java.io.InputStream stream, String threadName) {
        Thread drain = new Thread(() -> {
            try (stream) {
                byte[] buf = new byte[1024];
                while (stream.read(buf) != -1) {
                    // discard
                }
            } catch (IOException ignored) {
                // process exited or stream closed
            }
        }, threadName);
        drain.setDaemon(true);
        drain.start();
    }

    private void killProcess() {
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
    }

    /** Retryability signal only — see {@link PiperVoiceEngine#alive()} for the contract. */
    @Override
    public boolean alive() {
        return !closed;
    }

    /**
     * Idempotent, non-blocking teardown — identical discipline to
     * {@link PiperVoiceEngine#close()}: flip the flag, enqueue the kill, stop the executor,
     * and let a daemon closer thread do the blocking wait and temp-dir cleanup.
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
            // engine thread already shutting down
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
            deleteWorkDirQuietly();
        }, "frens-soul-voice-closer");
        closer.setDaemon(true);
        closer.start();
    }

    private void deleteWorkDirQuietly() {
        try {
            if (Files.isDirectory(workDir)) {
                try (var entries = Files.list(workDir)) {
                    entries.forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
                }
            }
            Files.deleteIfExists(workDir);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
