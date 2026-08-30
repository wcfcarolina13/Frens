package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Engine backed by Kyutai Pocket TTS (CPU, English preset voices). The engine owns one
 * {@code pocket-tts serve} subprocess from the installer-managed venv, bound to loopback on a
 * private port, and POSTs {@code /tts} ({@code text} + {@code voice_url} form fields) for a
 * 24 kHz mono 16-bit WAV body per line. Per-bot voices come from the resolver: a bot's
 * {@link SoulTypes.VoiceSpec#voice()} is the preset name; blank falls back to the configured
 * default preset.
 *
 * <p>Same lifecycle discipline as {@link DreamsleeveVoiceEngine}: all process/HTTP I/O on the
 * single engine thread, stdout/stderr drained on daemon threads, non-blocking idempotent
 * {@link #close()} with a daemon closer thread, {@code alive()} = not closed (restart on next
 * call; health policy lives in {@link SoulVoiceService}).
 *
 * <p>The first line after a cold server start pays the model load; a fire-and-forget warm-up
 * request is sent right after the server reports healthy so that cost lands before the first
 * real reply needs it.
 */
public final class PocketVoiceEngine implements SoulVoiceEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");
    private static final String LOOPBACK = "127.0.0.1";
    private static final long STARTUP_TIMEOUT_MS = 20_000L;
    private static final long STARTUP_POLL_MS = 250L;

    private final String installDir;
    private final String defaultVoice;
    private final long synthTimeoutMs;
    private final Function<SoulTypes.VoiceKey, SoulTypes.VoiceSpec> voiceResolver;
    private final int port;
    /** False in tests, which point the engine at a stub server instead of spawning one. */
    private final boolean spawnServer;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ExecutorService engineThread =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "frens-soul-voice-engine");
                t.setDaemon(true);
                return t;
            });

    private Process process;
    private volatile boolean closed;

    public PocketVoiceEngine(String installDir, String defaultVoice, long synthTimeoutMs,
                             Function<SoulTypes.VoiceKey, SoulTypes.VoiceSpec> voiceResolver)
            throws IOException {
        this(installDir, defaultVoice, synthTimeoutMs, voiceResolver, pickFreePort(), true);
    }

    PocketVoiceEngine(String installDir, String defaultVoice, long synthTimeoutMs,
                      Function<SoulTypes.VoiceKey, SoulTypes.VoiceSpec> voiceResolver,
                      int fixedPort, boolean spawnServer) {
        this.installDir = installDir;
        this.defaultVoice = defaultVoice == null || defaultVoice.isBlank()
                ? SoulVoiceSettings.DEFAULT_POCKET_VOICE : defaultVoice.trim();
        this.synthTimeoutMs = synthTimeoutMs;
        this.voiceResolver = voiceResolver == null ? key -> SoulTypes.VoiceSpec.EMPTY : voiceResolver;
        this.port = fixedPort;
        this.spawnServer = spawnServer;
    }

    static Path binaryPath(String installDir) {
        return Path.of(installDir, "venv", "bin", "pocket-tts");
    }

    static List<String> command(String installDir, int port) {
        return List.of(binaryPath(installDir).toString(),
                "serve", "--host", LOOPBACK, "--port", Integer.toString(port));
    }

    /** Pure form-encoding of one {@code /tts} request body. */
    static String formBody(String text, String voice) {
        return "text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + "&voice_url=" + URLEncoder.encode(voice, StandardCharsets.UTF_8);
    }

    static boolean looksLikeWav(byte[] bytes) {
        return bytes != null && bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
    }

    private static int pickFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private SoulTypes.VoiceSpec resolveQuietly(SoulTypes.VoiceKey key) {
        try {
            SoulTypes.VoiceSpec spec = voiceResolver.apply(key);
            return spec == null ? SoulTypes.VoiceSpec.EMPTY : spec;
        } catch (RuntimeException ignored) {
            return SoulTypes.VoiceSpec.EMPTY;
        }
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
        return synthesize(text, new SoulTypes.VoiceKey("", voiceId));
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, SoulTypes.VoiceKey key) {
        final SoulTypes.VoiceKey voiceKey = key == null ? new SoulTypes.VoiceKey("", "") : key;
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try {
            engineThread.submit(() -> {
                try {
                    ensureServer();
                    SoulTypes.VoiceSpec spec = resolveQuietly(voiceKey);
                    String voice = spec.voice().isEmpty() ? defaultVoice : spec.voice();
                    result.complete(postTts(text.replace('\n', ' '), voice, synthTimeoutMs));
                } catch (Exception ex) {
                    if (spawnServer) {
                        killProcess();
                    }
                    result.completeExceptionally(ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            result.completeExceptionally(new IllegalStateException("engine closed", ex));
        }
        return result;
    }

    private HttpRequest ttsRequest(String text, String voice, long timeoutMs) {
        return HttpRequest.newBuilder(URI.create("http://" + LOOPBACK + ":" + port + "/tts"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(formBody(text, voice), StandardCharsets.UTF_8))
                .build();
    }

    /** One synchronous {@code /tts} round trip; validates the WAV magic. Engine thread only. */
    private byte[] postTts(String text, String voice, long timeoutMs) throws Exception {
        HttpResponse<byte[]> response =
                http.send(ttsRequest(text, voice, timeoutMs), HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = response.body();
        if (response.statusCode() != 200 || !looksLikeWav(body)) {
            throw new IOException("pocket tts returned " + response.statusCode());
        }
        return body;
    }

    /** {@code GET /health} with a short deadline; any failure reads as unhealthy. */
    private boolean healthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://" + LOOPBACK + ":" + port + "/health"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Spawns the warm server on our private port if it is not already running and healthy. */
    private void ensureServer() throws Exception {
        if (closed) {
            throw new IOException("engine closed");
        }
        if (!spawnServer) {
            return;
        }
        if (process != null && process.isAlive() && healthy()) {
            return;
        }
        killProcess();
        ProcessBuilder builder = new ProcessBuilder(command(installDir, port));
        // Pocket TTS is CPU-bound; a single BLAS thread keeps synthesis from stealing the
        // game's cores (the render thread shares them).
        builder.environment().put("OMP_NUM_THREADS", "1");
        builder.redirectErrorStream(false);
        process = builder.start();
        drainStream(process.getInputStream(), "frens-soul-voice-stdout");
        drainStream(process.getErrorStream(), "frens-soul-voice-stderr");
        LOGGER.info("[souls] tts engine started pid={} (pocket-tts serve :{})", process.pid(), port);
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
        boolean up = false;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("pocket tts server exited during startup");
            }
            if (healthy()) {
                up = true;
                break;
            }
            Thread.sleep(STARTUP_POLL_MS);
        }
        if (!up) {
            throw new TimeoutException("pocket tts server never became healthy");
        }
        // Fire-and-forget warm-up so the model load lands before the first real line.
        http.sendAsync(ttsRequest("Ready.", defaultVoice, synthTimeoutMs),
                        HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> null);
    }

    /**
     * The server logs to stdout/stderr; neither is part of our protocol, but both must be
     * drained so a full pipe can never block the child. Content is discarded (never logged).
     */
    private void drainStream(InputStream stream, String threadName) {
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
     * {@link DreamsleeveVoiceEngine#close()}: flip the flag, enqueue the kill, stop the
     * executor, and let a daemon closer thread do the blocking wait.
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
        }, "frens-soul-voice-closer");
        closer.setDaemon(true);
        closer.start();
    }
}
