package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulConversationService;
import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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

    /** Minecraft-free delivery boundary; production impl sends SoulVoicePayload chunks. */
    public interface VoiceDelivery {
        void send(UUID playerId, UUID correlationId, UUID botId, SoulVoiceGate.Mode mode,
                  int sampleRate, List<byte[]> pcmChunks);
    }

    private static final SoulVoiceService DISABLED =
            new SoulVoiceService(new SoulVoiceSettings(false, false, "disabled", "", "", 400, 8000L, 0.6f),
                    null, (playerId, correlationId, botId, mode, sampleRate, chunks) -> { });

    private final SoulVoiceSettings settings;
    private final SoulVoiceEngine engine; // null only for the disabled instance
    private final VoiceDelivery delivery;
    private final ExecutorService worker;
    private final VoiceBackoffPolicy backoff = new VoiceBackoffPolicy();
    private volatile boolean selfDisabled;

    public SoulVoiceService(SoulVoiceSettings settings, SoulVoiceEngine engine, VoiceDelivery delivery) {
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
        Optional<SoulVoiceGate.Mode> mode = SoulVoiceGate.decide(settings.enabled(),
                settings.valid(), engineAlive(), turn.grounding().reachability());
        if (mode.isEmpty()) {
            return;
        }
        Optional<String> sanitized = SoulVoiceSanitizer.sanitize(text, settings.maxChars());
        if (sanitized.isEmpty()) {
            logTts(turn.routingId(), "skipped-empty", 0L, 0, 0);
            return;
        }
        worker.execute(() -> runSynthesis(turn, mode.get(), sanitized.get()));
    }

    private void runSynthesis(SoulTypes.AcceptedTurn turn, SoulVoiceGate.Mode mode, String text) {
        long startNanos = System.nanoTime();
        try {
            byte[] wav = engine.synthesize(text, turn.profileId())
                    .get(settings.synthTimeoutMs() + 500L, TimeUnit.MILLISECONDS);
            long synthMs = (System.nanoTime() - startNanos) / 1_000_000L;
            Optional<SoulVoicePcm.PcmAudio> pcm = SoulVoicePcm.parseWav(wav);
            if (pcm.isEmpty()) {
                noteFailure();
                logTts(turn.routingId(), "failed-badwav", synthMs, wav == null ? 0 : wav.length, 0);
                return;
            }
            List<byte[]> chunks = SoulVoicePcm.chunk(pcm.get().data(), CHUNK_BYTES);
            delivery.send(turn.key().playerId(), turn.routingId(), turn.key().botId(),
                    mode, pcm.get().sampleRate(), chunks);
            backoff.onSuccess();
            logTts(turn.routingId(), "spoken", synthMs, pcm.get().data().length, chunks.size());
        } catch (Exception ex) {
            long synthMs = (System.nanoTime() - startNanos) / 1_000_000L;
            noteFailure();
            logTts(turn.routingId(), "failed-" + ex.getClass().getSimpleName(), synthMs, 0, 0);
        }
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
