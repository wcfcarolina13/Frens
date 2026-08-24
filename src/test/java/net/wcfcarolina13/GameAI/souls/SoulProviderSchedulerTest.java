package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SoulProviderSchedulerTest {

    private static SoulTypes.ConversationKey randomKey() {
        return new SoulTypes.ConversationKey(UUID.randomUUID(), UUID.randomUUID(), SoulTypes.Channel.DIRECT);
    }

    private static SoulTypes.ProviderRequest requestFor(String content) {
        return new SoulTypes.ProviderRequest(UUID.randomUUID(), "test-model",
                List.of(new SoulTypes.Message(SoulTypes.Role.USER, content)),
                Duration.ofSeconds(60), 220);
    }

    // === Own coverage: cancelForPlayer — the player-disconnect cancellation surface ===

    @Test
    void cancelForPlayerCancelsOnlyThatPlayersActiveAndQueuedWork() {
        SoulGenerationScheduler scheduler = new SoulGenerationScheduler(2, 8);
        UUID disconnectingPlayer = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        SoulTypes.ConversationKey activeKey = new SoulTypes.ConversationKey(
                UUID.randomUUID(), disconnectingPlayer, SoulTypes.Channel.DIRECT);
        SoulTypes.ConversationKey otherKey = new SoulTypes.ConversationKey(
                UUID.randomUUID(), otherPlayer, SoulTypes.Channel.DIRECT);
        SoulTypes.ConversationKey queuedKey = new SoulTypes.ConversationKey(
                UUID.randomUUID(), disconnectingPlayer, SoulTypes.Channel.DIRECT);

        AtomicBoolean activeCancelled = new AtomicBoolean();
        CompletableFuture<SoulTypes.ProviderResult> activeResult = new CompletableFuture<>();
        CompletableFuture<SoulTypes.ProviderResult> otherResult = new CompletableFuture<>();

        scheduler.submit(activeKey, 0L,
                () -> new SoulModelProvider.Call(activeResult, () -> activeCancelled.set(true)));
        scheduler.submit(otherKey, 0L,
                () -> new SoulModelProvider.Call(otherResult, () -> otherResult.cancel(false)));
        // Both slots are now busy; this third job stays queued.
        CompletableFuture<SoulTypes.ProviderResult> queued = scheduler.submit(queuedKey, 0L,
                () -> new SoulModelProvider.Call(new CompletableFuture<>(), () -> {}));

        assertEquals(3, scheduler.inFlightCount());
        int cancelled = scheduler.cancelForPlayer(disconnectingPlayer);

        assertEquals(2, cancelled);
        assertTrue(activeCancelled.get(), "the player's active call must be cancelled");
        assertEquals(SoulTypes.FailureCode.CANCELLED, queued.join().failureCode(),
                "the player's queued job must complete CANCELLED without ever starting");
        assertFalse(otherResult.isDone(), "the other player's active call must be untouched");

        scheduler.close();
    }

    @Test
    void cancelForPlayerWithNothingInFlightReportsZero() {
        SoulGenerationScheduler scheduler = new SoulGenerationScheduler(1, 4);
        assertEquals(0, scheduler.cancelForPlayer(UUID.randomUUID()));
        scheduler.close();
    }

    // === Mandated tests (verbatim from the task brief) ===

    @Test
    void sameConversationNeverOverlaps() {
        SoulGenerationScheduler scheduler = new SoulGenerationScheduler(2, 8);
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                UUID.randomUUID(), UUID.randomUUID(), SoulTypes.Channel.DIRECT);
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<SoulTypes.ProviderResult> firstResult = new CompletableFuture<>();
        CompletableFuture<SoulTypes.ProviderResult> secondResult = new CompletableFuture<>();

        CompletableFuture<SoulTypes.ProviderResult> first = scheduler.submit(key, 0L,
                () -> {
                    started.incrementAndGet();
                    return new SoulModelProvider.Call(firstResult, () -> firstResult.cancel(false));
                });
        CompletableFuture<SoulTypes.ProviderResult> second = scheduler.submit(key, 0L,
                () -> {
                    started.incrementAndGet();
                    return new SoulModelProvider.Call(secondResult, () -> secondResult.cancel(false));
                });

        assertEquals(1, started.get());
        // The cross-mod load probe's signal: one active call + one queued job = 2 in flight.
        assertEquals(2, scheduler.inFlightCount());
        firstResult.complete(new SoulTypes.ProviderResult(
                true, "first", null, "test", "test-model", 1L, null, null, null));
        first.join();
        assertEquals(2, started.get());
        assertEquals(1, scheduler.inFlightCount());
        secondResult.complete(new SoulTypes.ProviderResult(
                true, "second", null, "test", "test-model", 1L, null, null, null));
        second.join();
        assertEquals(0, scheduler.inFlightCount());

        scheduler.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ollamaHttpFailureIsNotDialogueText() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(response.body()).thenReturn("upstream unavailable");
        OllamaSoulProvider provider = new OllamaSoulProvider(
                URI.create("http://127.0.0.1:11434"), "test-model",
                request -> CompletableFuture.completedFuture(response), new ObjectMapper());
        SoulTypes.ProviderRequest request = new SoulTypes.ProviderRequest(
                UUID.randomUUID(), "test-model",
                List.of(new SoulTypes.Message(SoulTypes.Role.USER, "hello")),
                Duration.ofSeconds(60), 220);

        SoulTypes.ProviderResult result = provider.generate(request).result().join();
        assertFalse(result.success());
        assertEquals(SoulTypes.FailureCode.UNAVAILABLE, result.failureCode());
        assertTrue(result.text().isBlank());

        provider.close();
    }

    // === Additional adapter coverage ===

    @Test
    void httpTimeoutMapsToTimeoutFailure() {
        OllamaSoulProvider provider = new OllamaSoulProvider(
                URI.create("http://127.0.0.1:11434"), "test-model",
                request -> CompletableFuture.failedFuture(new HttpTimeoutException("timed out")),
                new ObjectMapper());

        SoulTypes.ProviderResult result = provider.generate(requestFor("hello")).result().join();

        assertFalse(result.success());
        assertEquals(SoulTypes.FailureCode.TIMEOUT, result.failureCode());
        assertTrue(result.text().isBlank());

        provider.close();
    }

    @Test
    void cancelNowCancelsUnderlyingHttpFutureAndResolvesCancelled() {
        CompletableFuture<HttpResponse<String>> httpFuture = new CompletableFuture<>();
        OllamaSoulProvider provider = new OllamaSoulProvider(
                URI.create("http://127.0.0.1:11434"), "test-model",
                request -> httpFuture, new ObjectMapper());

        SoulModelProvider.Call call = provider.generate(requestFor("hello"));
        call.cancelNow();

        SoulTypes.ProviderResult result = call.result().join();
        assertFalse(result.success());
        assertEquals(SoulTypes.FailureCode.CANCELLED, result.failureCode());
        assertTrue(httpFuture.isCancelled());

        provider.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingMessageContentMapsToMalformed() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}");
        OllamaSoulProvider provider = new OllamaSoulProvider(
                URI.create("http://127.0.0.1:11434"), "test-model",
                request -> CompletableFuture.completedFuture(response), new ObjectMapper());

        SoulTypes.ProviderResult result = provider.generate(requestFor("hi")).result().join();

        assertFalse(result.success());
        assertEquals(SoulTypes.FailureCode.MALFORMED, result.failureCode());
        assertTrue(result.text().isBlank());

        provider.close();
    }

    @Test
    void requestBodyMatchesOllamaChatShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OllamaSoulProvider provider = new OllamaSoulProvider(
                URI.create("http://127.0.0.1:11434"), "test-model",
                request -> new CompletableFuture<>(), mapper);
        SoulTypes.ProviderRequest request = new SoulTypes.ProviderRequest(
                UUID.randomUUID(), "test-model",
                List.of(new SoulTypes.Message(SoulTypes.Role.SYSTEM, "You are Jake."),
                        new SoulTypes.Message(SoulTypes.Role.USER, "How are you holding up?")),
                Duration.ofSeconds(60), 220);

        String body = provider.buildRequestBody(request);
        JsonNode root = mapper.readTree(body);

        assertEquals("test-model", root.path("model").asText());
        assertFalse(root.path("stream").asBoolean(true));
        assertEquals("60m", root.path("keep_alive").asText());
        assertEquals(0.7, root.path("options").path("temperature").asDouble(), 0.0001);
        assertEquals(220, root.path("options").path("num_predict").asInt());
        assertEquals(8192, root.path("options").path("num_ctx").asInt());
        assertEquals("system", root.path("messages").get(0).path("role").asText());
        assertEquals("You are Jake.", root.path("messages").get(0).path("content").asText());
        assertEquals("user", root.path("messages").get(1).path("role").asText());
        assertEquals("How are you holding up?", root.path("messages").get(1).path("content").asText());

        provider.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthChecksTagsEndpointWithShortTimeout() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        OllamaSoulProvider provider = new OllamaSoulProvider(
                URI.create("http://127.0.0.1:11434"), "test-model",
                request -> {
                    captured.set(request);
                    return CompletableFuture.completedFuture(response);
                }, new ObjectMapper());

        boolean healthy = provider.health().join();

        assertTrue(healthy);
        HttpRequest sent = captured.get();
        assertEquals("GET", sent.method());
        assertTrue(sent.uri().toString().endsWith("/api/tags"));
        assertTrue(sent.timeout().isPresent());
        assertEquals(Duration.ofMillis(1500), sent.timeout().get());

        provider.close();
    }

    // === Scheduler coverage ===

    @Test
    void queueOverflowCompletesImmediatelyWithOverloaded() {
        SoulGenerationScheduler scheduler = new SoulGenerationScheduler(1, 1);
        AtomicInteger started = new AtomicInteger();

        CompletableFuture<SoulTypes.ProviderResult> r1 = new CompletableFuture<>();
        scheduler.submit(randomKey(), 0L, () -> {
            started.incrementAndGet();
            return new SoulModelProvider.Call(r1, () -> r1.cancel(false));
        });

        CompletableFuture<SoulTypes.ProviderResult> r2 = new CompletableFuture<>();
        scheduler.submit(randomKey(), 0L, () -> {
            started.incrementAndGet();
            return new SoulModelProvider.Call(r2, () -> r2.cancel(false));
        });
        assertEquals(1, scheduler.queueDepth());

        CompletableFuture<SoulTypes.ProviderResult> third = scheduler.submit(randomKey(), 0L, () -> {
            started.incrementAndGet();
            return new SoulModelProvider.Call(new CompletableFuture<>(), () -> { });
        });

        SoulTypes.ProviderResult result = third.join();
        assertEquals(SoulTypes.FailureCode.OVERLOADED, result.failureCode());
        assertEquals(1, started.get());
        assertEquals(1, scheduler.queueDepth());

        scheduler.close();
    }

    @Test
    void twoDifferentKeysRespectGlobalConcurrencyCap() {
        SoulGenerationScheduler scheduler = new SoulGenerationScheduler(1, 8);
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<SoulTypes.ProviderResult> r1 = new CompletableFuture<>();
        CompletableFuture<SoulTypes.ProviderResult> r2 = new CompletableFuture<>();

        CompletableFuture<SoulTypes.ProviderResult> first = scheduler.submit(randomKey(), 0L, () -> {
            started.incrementAndGet();
            return new SoulModelProvider.Call(r1, () -> r1.cancel(false));
        });
        CompletableFuture<SoulTypes.ProviderResult> second = scheduler.submit(randomKey(), 0L, () -> {
            started.incrementAndGet();
            return new SoulModelProvider.Call(r2, () -> r2.cancel(false));
        });

        assertEquals(1, started.get());
        r1.complete(new SoulTypes.ProviderResult(true, "first", null, "test", "test-model", 1L, null, null, null));
        first.join();
        assertEquals(2, started.get());
        r2.complete(new SoulTypes.ProviderResult(true, "second", null, "test", "test-model", 1L, null, null, null));
        second.join();

        scheduler.close();
    }

    @Test
    void invalidateOlderEpochCompletesQueuedJobAsStaleEpochAndCancelsActive() {
        SoulGenerationScheduler scheduler = new SoulGenerationScheduler(1, 8);
        SoulTypes.ConversationKey key = randomKey();
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<SoulTypes.ProviderResult> activeResult = new CompletableFuture<>();

        CompletableFuture<SoulTypes.ProviderResult> active = scheduler.submit(key, 0L, () -> {
            started.incrementAndGet();
            return new SoulModelProvider.Call(activeResult, () -> activeResult.complete(
                    new SoulTypes.ProviderResult(false, "", SoulTypes.FailureCode.CANCELLED,
                            "test", "test-model", 1L, null, null, null)));
        });
        CompletableFuture<SoulTypes.ProviderResult> queued = scheduler.submit(key, 0L, () -> {
            started.incrementAndGet();
            return new SoulModelProvider.Call(new CompletableFuture<>(), () -> { });
        });
        assertEquals(1, scheduler.queueDepth());

        scheduler.invalidate(key, 1L);

        assertEquals(SoulTypes.FailureCode.STALE_EPOCH, queued.join().failureCode());
        assertEquals(0, scheduler.queueDepth());
        assertEquals(1, started.get());
        assertEquals(SoulTypes.FailureCode.CANCELLED, active.join().failureCode());

        scheduler.close();
    }

    @Test
    void closeCancelsActiveAndCompletesQueuedAsCancelled() {
        SoulGenerationScheduler scheduler = new SoulGenerationScheduler(1, 8);
        AtomicInteger started = new AtomicInteger();
        AtomicBoolean activeCancelled = new AtomicBoolean();
        CompletableFuture<SoulTypes.ProviderResult> activeResult = new CompletableFuture<>();

        CompletableFuture<SoulTypes.ProviderResult> active = scheduler.submit(randomKey(), 0L, () -> {
            started.incrementAndGet();
            return new SoulModelProvider.Call(activeResult, () -> {
                activeCancelled.set(true);
                activeResult.complete(new SoulTypes.ProviderResult(false, "", SoulTypes.FailureCode.CANCELLED,
                        "test", "test-model", 1L, null, null, null));
            });
        });
        CompletableFuture<SoulTypes.ProviderResult> queued = scheduler.submit(randomKey(), 0L, () -> {
            started.incrementAndGet();
            return new SoulModelProvider.Call(new CompletableFuture<>(), () -> { });
        });
        assertEquals(1, scheduler.queueDepth());

        scheduler.close();

        assertTrue(activeCancelled.get());
        assertEquals(SoulTypes.FailureCode.CANCELLED, active.join().failureCode());
        assertEquals(SoulTypes.FailureCode.CANCELLED, queued.join().failureCode());
        assertEquals(0, scheduler.queueDepth());
        assertEquals(1, started.get());
    }
}
