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
    /** Per-bot clone anchors (2026-08-29): profile id → spec; blank refAudio = the global anchor. */
    private final java.util.function.Function<net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceKey, net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec> voiceResolver;
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

    /** Written on the engine thread, read by the closer thread for the direct kill in {@link #close()}. */
    private volatile Process process;
    private volatile boolean closed;

    /**
     * The warm-up ack wait. Long on purpose: if the server acks only after it has loaded its model
     * and rendered, a short wait would send the first real line into the load, time it out, kill the
     * server and loop. This runs on the engine thread, never the server thread; the old read had no
     * bound at all. Failure is still ignored.
     */
    static final long WARMUP_ACK_TIMEOUT_MS = 120_000L;

    public DreamsleeveVoiceEngine(String dreamsleeveDir, String refAudio, String refText,
                                   long synthTimeoutMs) throws IOException {
        this(dreamsleeveDir, refAudio, refText, synthTimeoutMs,
                key -> net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec.EMPTY);
    }

    public DreamsleeveVoiceEngine(String dreamsleeveDir, String refAudio, String refText,
                                   long synthTimeoutMs,
                                   java.util.function.Function<net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceKey, net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec> voiceResolver)
            throws IOException {
        this.dreamsleeveDir = dreamsleeveDir;
        this.refAudio = refAudio;
        this.refText = refText;
        this.voiceResolver = voiceResolver == null
                ? key -> net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec.EMPTY : voiceResolver;
        this.synthTimeoutMs = synthTimeoutMs;
        this.workDir = Files.createTempDirectory("frens-soul-voice");
        // AF_UNIX socket paths are limited to ~104 bytes on macOS; java.io.tmpdir
        // (/var/folders/…) plus the temp-dir name runs right up against that (and the server
        // exits with "bind failed (AF_UNIX path too long)" when it overflows — observed live).
        // Bind the socket at a short /tmp path instead; only WAV drops use workDir.
        this.sockPath = Path.of("/tmp", "frens-voice-" + Long.toHexString(
                System.nanoTime() & 0xFFFFFFFFL) + ".sock");
    }

    private net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec resolveQuietly(net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceKey key) {
        try {
            net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec spec = voiceResolver.apply(key);
            return spec == null ? net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec.EMPTY : spec;
        } catch (RuntimeException ignored) {
            return net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec.EMPTY;
        }
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
        return synthesize(text, new net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceKey("", voiceId));
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceKey key) {
        final net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceKey voiceKey =
                key == null ? new net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceKey("", "") : key;
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try {
            engineThread.submit(() -> {
                Path outFile = workDir.resolve("frens-" + requestSeq.incrementAndGet() + ".wav");
                try {
                    ensureServer();
                    net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec spec = resolveQuietly(voiceKey);
                    String ref = spec.refAudio().isEmpty() ? refAudio : spec.refAudio();
                    String txt = spec.refAudio().isEmpty() ? refText : spec.refText();
                    // One deadline covers the ack and the render, so a line still costs at most
                    // synthTimeoutMs even when the server accepts and then never answers.
                    long deadline = System.currentTimeMillis() + synthTimeoutMs;
                    sendRequest(speakRequestJson(text.replace('\n', ' '), ref, txt,
                            outFile.toString()), deadline);
                    byte[] bytes = awaitRenderedFile(outFile, deadline);
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
    private void sendRequest(String requestLine, long deadlineMs)
            throws IOException, InterruptedException, TimeoutException {
        sendRequestLine(sockPath, requestLine, deadlineMs);
    }

    /**
     * Connects, writes the request and waits for the server's one-line ack ({@code {"ok":true}},
     * read but never parsed) on a non-blocking channel, every step bounded by {@code deadlineMs}.
     * The ack is still awaited rather than skipped: the server writes it after taking the request,
     * and closing before it lands would hand the Python side a broken pipe on every line. A server
     * that accepts and never acks used to park the engine thread in a blocking read for good
     * (voice went silently dead); now it is a {@link TimeoutException} and the caller kills it.
     */
    static void sendRequestLine(Path sockPath, String requestLine, long deadlineMs)
            throws IOException, InterruptedException, TimeoutException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.configureBlocking(false);
            if (!channel.connect(UnixDomainSocketAddress.of(sockPath))) {
                DeadlineReads.awaitProgress(() -> channel.finishConnect() ? 1 : 0, deadlineMs);
            }
            ByteBuffer out = ByteBuffer.wrap(requestLine.getBytes(StandardCharsets.UTF_8));
            DeadlineReads.awaitProgress(() -> {
                channel.write(out);
                return out.hasRemaining() ? 0 : 1;
            }, deadlineMs);
            ByteBuffer ack = ByteBuffer.allocate(256);
            // Any non-zero result ends the wait: bytes of ack, or EOF because the server closed.
            DeadlineReads.awaitProgress(() -> channel.read(ack), deadlineMs);
        }
    }

    /**
     * Waits for the server's atomic rename of {@code outFile} (a visible file is complete by
     * construction) up to the shared synth deadline. Engine thread only.
     */
    private byte[] awaitRenderedFile(Path outFile, long deadline) throws Exception {
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
                    workDir.resolve("warmup.wav").toString()),
                    System.currentTimeMillis() + WARMUP_ACK_TIMEOUT_MS);
        } catch (IOException | TimeoutException ignored) {
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

    /** Safe from any thread: {@code destroyForcibly} is thread-safe and idempotent. */
    private void killProcess() {
        Process current = process;
        if (current != null) {
            current.destroyForcibly();
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
     * and let a daemon closer thread do the blocking wait and temp-dir cleanup. The closer also
     * kills the server directly: with a synth in flight the queued kill never runs
     * ({@code shutdownNow} drops it), which used to leave {@code tts_server.py} alive past a
     * world reload and even past JVM exit.
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
            killProcess();
            deleteWorkDirQuietly();
        }, "frens-soul-voice-closer");
        closer.setDaemon(true);
        closer.start();
    }

    private void deleteWorkDirQuietly() {
        try {
            Files.deleteIfExists(sockPath);
        } catch (IOException ignored) {
            // best-effort; a stale socket is also reclaimed by the next server start
        }
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
