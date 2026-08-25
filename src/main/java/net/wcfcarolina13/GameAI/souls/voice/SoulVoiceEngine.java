package net.wcfcarolina13.GameAI.souls.voice;

import java.util.concurrent.CompletableFuture;

/** One synthesis backend. Implementations own their process/resource lifecycle. */
public interface SoulVoiceEngine extends AutoCloseable {
    /**
     * Synthesizes one utterance to WAV bytes. {@code voiceId} is the per-profile voice seam —
     * v1 implementations may ignore it (single configured voice).
     */
    CompletableFuture<byte[]> synthesize(String text, String voiceId);

    boolean alive();

    @Override
    void close();
}
