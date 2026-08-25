package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulVoiceServiceTest {

    private record Sent(UUID playerId, UUID correlationId, UUID botId,
                         SoulVoiceGate.Mode mode, int sampleRate, List<byte[]> chunks) {
    }

    private static byte[] tinyWav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(22050).putInt(44100).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }

    private static SoulTypes.AcceptedTurn turn(SoulTypes.Reachability reachability, UUID routingId) {
        UUID botId = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                botId, UUID.randomUUID(), SoulTypes.Channel.DIRECT);
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(botId, "Jake", "minecraft:overworld",
                "plains", 0, 64, 0, true, "day", "clear", 20.0f, 20.0f, 20, 0, "bare hands",
                0, 36, List.of(), "content", "idle", "", "", "", "", false, 0, false, Optional.empty());
        SoulTypes.GroundingSnapshot grounding = new SoulTypes.GroundingSnapshot(
                reachability, bot, Optional.empty(), Instant.now());
        return new SoulTypes.AcceptedTurn(key, "Jake", "Player", "hi", "frens:jake",
                grounding, Instant.now(), routingId);
    }

    private static SoulTypes.TurnToken tokenFor(SoulTypes.AcceptedTurn turn) {
        return new SoulTypes.TurnToken(turn.key(), turn.routingId(), 0L, 1L);
    }

    private static SoulVoiceSettings enabledSettings() {
        return new SoulVoiceSettings(true, true, "", SoulVoiceSettings.ENGINE_PIPER,
                "/bin/piper", "/voices/jake.onnx", "", "", "",
                400, 8000L, 0.6f);
    }

    @Test
    void deliveredLocalTurnIsSynthesizedChunkedAndSentPositional() throws Exception {
        CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        byte[] pcm = new byte[40_000];
        SoulVoiceEngine engine = new SoulVoiceEngine() {
            @Override public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
                return CompletableFuture.completedFuture(tinyWav(pcm));
            }
            @Override public boolean alive() { return true; }
            @Override public void close() { }
        };
        SoulVoiceService service = new SoulVoiceService(enabledSettings(), engine,
                (playerId, correlationId, botId, mode, sampleRate, chunks, groupId, segmentIndex) -> {
                    sent.add(new Sent(playerId, correlationId, botId, mode, sampleRate, chunks));
                    done.countDown();
                });

        UUID routingId = UUID.randomUUID();
        SoulTypes.AcceptedTurn accepted = turn(SoulTypes.Reachability.LOCAL, routingId);
        service.onSpoken(accepted, tokenFor(accepted), "Hello there.");

        assertTrue(done.await(2, TimeUnit.SECONDS));
        Sent one = sent.get(0);
        // Sentence streaming: correlationId is the derived per-segment id (segment 0 here);
        // the turn's routingId travels as the delivery groupId instead.
        assertEquals(SoulVoiceService.segmentCorrelationId(routingId, 0), one.correlationId());
        assertEquals(SoulVoiceGate.Mode.POSITIONAL, one.mode());
        assertEquals(22050, one.sampleRate());
        assertEquals(2, one.chunks().size()); // 40_000 bytes at 32_768/chunk
        service.close();
    }

    @Test
    void disabledServiceAndUnreachableTurnsSendNothing() throws Exception {
        CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();
        SoulVoiceService disabled = SoulVoiceService.disabled();
        SoulTypes.AcceptedTurn accepted = turn(SoulTypes.Reachability.LOCAL, UUID.randomUUID());
        disabled.onSpoken(accepted, tokenFor(accepted), "Hello.");

        SoulVoiceService enabled = new SoulVoiceService(enabledSettings(),
                new SoulVoiceEngine() {
                    @Override public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
                        return CompletableFuture.completedFuture(tinyWav(new byte[2]));
                    }
                    @Override public boolean alive() { return true; }
                    @Override public void close() { }
                },
                (playerId, correlationId, botId, mode, sampleRate, chunks, groupId, segmentIndex) ->
                        sent.add(new Sent(playerId, correlationId, botId, mode, sampleRate, chunks)));
        SoulTypes.AcceptedTurn unreachable = turn(SoulTypes.Reachability.UNREACHABLE, UUID.randomUUID());
        enabled.onSpoken(unreachable, tokenFor(unreachable), "Hello.");
        Thread.sleep(200);
        assertEquals(0, sent.size());
        disabled.close();
        enabled.close();
    }

    @Test
    void engineStaysReachableAfterOneFailureSoTheNextLineIsDelivered() throws Exception {
        // Regression for C1: an engine whose alive() always reports true (as PiperVoiceEngine
        // does post-fix — it is always retryable) must not be permanently gated out by
        // SoulVoiceService after a single failed synthesis. The second onSpoken must still
        // reach the engine and deliver.
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        byte[] pcm = new byte[10];
        CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        SoulVoiceEngine engine = new SoulVoiceEngine() {
            @Override public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
                if (attempts.getAndIncrement() == 0) {
                    return CompletableFuture.failedFuture(new RuntimeException("boom"));
                }
                return CompletableFuture.completedFuture(tinyWav(pcm));
            }
            @Override public boolean alive() { return true; }
            @Override public void close() { }
        };
        SoulVoiceService service = new SoulVoiceService(enabledSettings(), engine,
                (playerId, correlationId, botId, mode, sampleRate, chunks, groupId, segmentIndex) -> {
                    sent.add(new Sent(playerId, correlationId, botId, mode, sampleRate, chunks));
                    delivered.countDown();
                });

        SoulTypes.AcceptedTurn first = turn(SoulTypes.Reachability.LOCAL, UUID.randomUUID());
        service.onSpoken(first, tokenFor(first), "First line fails.");
        Thread.sleep(200); // let the failing attempt complete before the next one is queued

        SoulTypes.AcceptedTurn second = turn(SoulTypes.Reachability.LOCAL, UUID.randomUUID());
        service.onSpoken(second, tokenFor(second), "Second line should land.");

        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
        assertEquals(SoulVoiceService.segmentCorrelationId(second.routingId(), 0), sent.get(0).correlationId());
        service.close();
    }

    @Test
    void engineFailureDropsAudioWithoutThrowing() throws Exception {
        SoulVoiceService service = new SoulVoiceService(enabledSettings(),
                new SoulVoiceEngine() {
                    @Override public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
                        return CompletableFuture.failedFuture(new RuntimeException("boom"));
                    }
                    @Override public boolean alive() { return true; }
                    @Override public void close() { }
                },
                (playerId, correlationId, botId, mode, sampleRate, chunks, groupId, segmentIndex) -> {
                    throw new AssertionError("must not deliver on engine failure");
                });
        SoulTypes.AcceptedTurn accepted = turn(SoulTypes.Reachability.LOCAL, UUID.randomUUID());
        service.onSpoken(accepted, tokenFor(accepted), "Hello.");
        Thread.sleep(200);
        service.close();
    }
}
