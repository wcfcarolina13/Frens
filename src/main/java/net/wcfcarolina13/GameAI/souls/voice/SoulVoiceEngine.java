package net.wcfcarolina13.GameAI.souls.voice;

import java.util.concurrent.CompletableFuture;

/** One synthesis backend. Implementations own their process/resource lifecycle. */
public interface SoulVoiceEngine extends AutoCloseable {
    /**
     * Synthesizes one utterance to WAV bytes. {@code voiceId} is the per-profile voice seam —
     * v1 implementations may ignore it (single configured voice).
     */
    CompletableFuture<byte[]> synthesize(String text, String voiceId);

    /** Per-bot voice selection; engines that only know profile ids fall back to that. */
    default CompletableFuture<byte[]> synthesize(String text, net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceKey key) {
        return synthesize(text, key == null ? "" : key.profileId());
    }

    /**
     * Pre-warm hook: start whatever backend resource would serve {@code key} so the first real
     * line does not pay a cold start. Must never block the caller and never throw; returns
     * {@code true} only when a warm-up was actually scheduled. Engines without a warm path keep
     * this default no-op.
     */
    default boolean warm(net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceKey key) {
        return false;
    }

    boolean alive();

    @Override
    void close();
}
