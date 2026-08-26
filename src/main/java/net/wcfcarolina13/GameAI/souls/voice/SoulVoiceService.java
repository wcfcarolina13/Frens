package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulConversationService;
import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The voice subscriber: sanitizes a committed reply, synthesizes it, and hands PCM chunks to a
 * {@link VoiceDelivery}. Every failure drops audio only — nothing here can affect the turn
 * that already delivered its text.
 */
public final class SoulVoiceService implements SoulConversationService.SpokenListener, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");
    private static final int CHUNK_BYTES = 32_768;
    private static final int QUEUE_CAP = 4;
    /** Sentence fragments shorter than this merge into the next sentence before synthesis. */
    static final int MIN_SEGMENT_CHARS = 24;
    /** Hard cap on streaming segments per reply; overflow folds into the last segment. */
    static final int MAX_SEGMENTS = 6;

    /**
     * Minecraft-free delivery boundary; production impl sends SoulVoicePayload chunks.
     * Sentence streaming: a reply is delivered as ordered segments sharing one
     * {@code groupId} (the turn's routingId); {@code correlationId} is unique per segment
     * (it keys client-side chunk reassembly). The client queues same-group segments on one
     * audio source so playback starts after segment 0 while later segments still render.
     */
    public interface VoiceDelivery {
        void send(UUID playerId, UUID correlationId, UUID botId, SoulVoiceGate.Mode mode,
                  int sampleRate, List<byte[]> pcmChunks, UUID groupId, int segmentIndex);
    }

    private static final SoulVoiceService DISABLED =
            new SoulVoiceService(new SoulVoiceSettings(false, false, "disabled",
                    SoulVoiceSettings.ENGINE_PIPER, "", "", "", "", "", 400, 8000L, 0.6f),
                    null, (playerId, correlationId, botId, mode, sampleRate, chunks, groupId, segmentIndex) -> { });

    private final SoulVoiceSettings settings;
    private final SoulVoiceEngine engine; // null only for the disabled instance
    private final VoiceDelivery delivery;
    /**
     * Live probe for the global "Voice" master toggle (BotControlScreen). Injected as a supplier
     * so this class never references Frens (whose static init breaks plain-JUnit loading);
     * production passes a ManualConfig read, tests get the always-true default.
     */
    private final java.util.function.BooleanSupplier masterVoiceEnabled;
    private final ExecutorService worker;
    private final VoiceBackoffPolicy backoff = new VoiceBackoffPolicy();
    /** Renders currently running on the worker — feeds SoulRuntime.activeGenerations() so the
     *  LoadGoverner stage floor stays held through the (heavy, Metal-bound) synthesis window,
     *  not just the LLM generation that precedes it. */
    private final java.util.concurrent.atomic.AtomicInteger activeSyntheses =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean selfDisabled;

    public SoulVoiceService(SoulVoiceSettings settings, SoulVoiceEngine engine, VoiceDelivery delivery) {
        this(settings, engine, delivery, () -> true);
    }

    public SoulVoiceService(SoulVoiceSettings settings, SoulVoiceEngine engine, VoiceDelivery delivery,
                             java.util.function.BooleanSupplier masterVoiceEnabled) {
        this.masterVoiceEnabled = Objects.requireNonNull(masterVoiceEnabled, "masterVoiceEnabled");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.engine = engine;
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAP),
                r -> {
                    Thread t = new Thread(r, "frens-soul-voice");
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> LOGGER.info("[souls] tts queue full; dropping a line"));
    }

    public static SoulVoiceService disabled() {
        return DISABLED;
    }

    public boolean engineAlive() {
        return engine != null && engine.alive() && !selfDisabled;
    }

    @Override
    public void onSpoken(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token, String text) {
        // The global "Voice" master (BotControlScreen) silences soul TTS along with baked lines.
        Optional<SoulVoiceGate.Mode> mode = SoulVoiceGate.decide(
                settings.enabled() && masterVoiceEnabled.getAsBoolean(),
                settings.valid(), engineAlive(), turn.grounding().reachability());
        if (mode.isEmpty()) {
            return;
        }
        Optional<String> sanitized = SoulVoiceSanitizer.sanitize(text, settings.maxChars());
        if (sanitized.isEmpty()) {
            logTts(turn.routingId(), "skipped-empty", 0L, 0, 0);
            return;
        }
        worker.execute(() -> {
            // Counted inside the task (not at submit) so a queue-full rejection can never
            // leak an increment — the rejection handler discards the task without running it.
            activeSyntheses.incrementAndGet();
            try {
                runSynthesis(turn, mode.get(), sanitized.get());
            } finally {
                activeSyntheses.decrementAndGet();
            }
        });
    }

    /** Number of synthesis renders currently running (0 or 1; the worker is single-threaded). */
    public int activeSyntheses() {
        return activeSyntheses.get();
    }

    /** One synthesized scene line: chunked PCM plus its playback duration (16-bit mono). */
    public record SynthesizedLine(int sampleRate, List<byte[]> chunks, long durationMs) {
        public SynthesizedLine {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    /**
     * Synthesizes one group-scene line with {@code profileId}'s voice on the shared voice worker
     * (counted in {@link #activeSyntheses()} so the LoadGoverner floor holds through the render).
     * Completes with empty on ANY gate or synthesis failure — scene playback then falls back to
     * beat pacing for that line. Never throws and never blocks the calling thread. Delivery is
     * the caller's job: unlike {@link #onSpoken}, nothing is sent from here.
     */
    public CompletableFuture<java.util.Optional<SynthesizedLine>> synthesizeLine(
            String profileId, String text) {
        Optional<SoulVoiceGate.Mode> mode = SoulVoiceGate.decide(
                settings.enabled() && masterVoiceEnabled.getAsBoolean(),
                settings.valid(), engineAlive(), SoulTypes.Reachability.LOCAL);
        Optional<String> sanitized = SoulVoiceSanitizer.sanitize(text, settings.maxChars());
        if (mode.isEmpty() || sanitized.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Optional<SynthesizedLine>> out = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                activeSyntheses.incrementAndGet();
                try {
                    byte[] wav = engine.synthesize(sanitized.get(), profileId)
                            .get(settings.synthTimeoutMs() + 500L, TimeUnit.MILLISECONDS);
                    Optional<SoulVoicePcm.PcmAudio> pcm = SoulVoicePcm.parseWav(wav);
                    if (pcm.isEmpty()) {
                        noteFailure();
                        out.complete(Optional.empty());
                        return;
                    }
                    long durationMs = pcm.get().data().length * 1000L / (pcm.get().sampleRate() * 2L);
                    out.complete(Optional.of(new SynthesizedLine(pcm.get().sampleRate(),
                            SoulVoicePcm.chunk(pcm.get().data(), CHUNK_BYTES), durationMs)));
                    backoff.onSuccess();
                } catch (Exception ex) {
                    noteFailure();
                    out.complete(Optional.empty());
                } finally {
                    activeSyntheses.decrementAndGet();
                }
            });
        } catch (RuntimeException rejected) {
            out.complete(Optional.empty());
        }
        // The worker's queue-full handler discards silently without running the task; the scene
        // playback machine additionally guards with a wall-clock timeout (synthGuardMs) so a
        // dropped render degrades to beat pacing instead of hanging the scene.
        return out;
    }

    /** Wall-clock guard the playback machine uses to declare a synthesis future abandoned. */
    public long synthGuardMs() {
        return settings.synthTimeoutMs() + 2_000L;
    }

    /**
     * Sentence-streaming synthesis: the reply is split into sentences and each is rendered
     * and delivered as its own segment (shared groupId = routingId), so the client starts
     * playing sentence one while the rest still render. A mid-reply failure aborts the
     * remaining segments — better to stop cleanly than skip a sentence and garble the reply.
     */
    private void runSynthesis(SoulTypes.AcceptedTurn turn, SoulVoiceGate.Mode mode, String text) {
        List<String> segments = splitSentences(text);
        long turnStartNanos = System.nanoTime();
        for (int i = 0; i < segments.size(); i++) {
            long startNanos = System.nanoTime();
            try {
                byte[] wav = engine.synthesize(segments.get(i), turn.profileId())
                        .get(settings.synthTimeoutMs() + 500L, TimeUnit.MILLISECONDS);
                long synthMs = (System.nanoTime() - startNanos) / 1_000_000L;
                Optional<SoulVoicePcm.PcmAudio> pcm = SoulVoicePcm.parseWav(wav);
                if (pcm.isEmpty()) {
                    noteFailure();
                    logTts(turn.routingId(), "failed-badwav-seg" + i, synthMs, wav == null ? 0 : wav.length, 0);
                    return;
                }
                List<byte[]> chunks = SoulVoicePcm.chunk(pcm.get().data(), CHUNK_BYTES);
                delivery.send(turn.key().playerId(), segmentCorrelationId(turn.routingId(), i),
                        turn.key().botId(), mode, pcm.get().sampleRate(), chunks,
                        turn.routingId(), i);
                backoff.onSuccess();
                logTts(turn.routingId(), "spoken-seg" + i + "/" + segments.size(), synthMs,
                        pcm.get().data().length, chunks.size());
            } catch (Exception ex) {
                long synthMs = (System.nanoTime() - startNanos) / 1_000_000L;
                noteFailure();
                Throwable causeToReport = (ex instanceof ExecutionException && ex.getCause() != null)
                        ? ex.getCause() : ex;
                logTts(turn.routingId(), "failed-seg" + i + "-" + causeToReport.getClass().getSimpleName(),
                        synthMs, 0, 0);
                return;
            }
        }
        logTts(turn.routingId(), "spoken-all-" + segments.size() + "seg",
                (System.nanoTime() - turnStartNanos) / 1_000_000L, 0, 0);
    }

    /**
     * Deterministic per-segment correlation id derived from the turn's routingId — unique per
     * segment (keys client chunk reassembly) yet traceable back to the turn in logs.
     */
    public static UUID segmentCorrelationId(UUID routingId, int segmentIndex) {
        return new UUID(routingId.getMostSignificantBits(),
                routingId.getLeastSignificantBits() ^ (0x9E3779B97F4A7C15L * (segmentIndex + 1)));
    }

    /**
     * Splits a sanitized reply into sentence segments for streaming synthesis. Fragments
     * shorter than {@value #MIN_SEGMENT_CHARS} chars are merged with the following sentence
     * (tiny synth calls sound choppy and waste round-trips); at most {@value #MAX_SEGMENTS}
     * segments — anything beyond is folded into the last one.
     */
    static List<String> splitSentences(String text) {
        List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] raw = text.trim().split("(?<=[.!?…])\\s+");
        StringBuilder pending = new StringBuilder();
        for (String part : raw) {
            if (pending.length() > 0) {
                pending.append(' ');
            }
            pending.append(part.trim());
            if (pending.length() >= MIN_SEGMENT_CHARS) {
                if (out.size() == MAX_SEGMENTS - 1) {
                    continue; // keep accumulating into the final segment
                }
                out.add(pending.toString());
                pending.setLength(0);
            }
        }
        if (pending.length() > 0) {
            out.add(pending.toString());
        }
        return out;
    }

    private void noteFailure() {
        long delayMs = backoff.onFailure(System.currentTimeMillis());
        if (delayMs == -1L) {
            selfDisabled = true;
            LOGGER.warn("[souls] tts self-disabled after repeated synthesis failures");
        }
    }

    private static void logTts(UUID correlationId, String outcome, long synthMs, int bytes, int chunks) {
        LOGGER.info("[souls] tts correlationId={} outcome={} synthMs={} bytes={} chunks={}",
                correlationId, outcome, synthMs, bytes, chunks);
    }

    @Override
    public void close() {
        if (this == DISABLED) {
            return;
        }
        worker.shutdownNow();
        if (engine != null) {
            engine.close();
        }
    }
}
