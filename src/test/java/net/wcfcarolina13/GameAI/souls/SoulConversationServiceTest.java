package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulConversationServiceTest {

    private static final UUID BOT_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();

    @TempDir
    Path worldRoot;

    private ExecutorService storeExecutor;
    private SoulStore store;
    private FakeProvider provider;
    private FakeDelivery delivery;
    private SoulGenerationScheduler scheduler;
    private SoulConversationService service;
    private SoulTypes.ConversationKey key;
    private SoulTypes.AcceptedTurn turn;

    // === Fixture (per the task brief; SoulProfileRegistry is a static registry with a private
    // constructor -- not an injectable instance -- so it is loaded here as its own statement
    // rather than passed as a SoulConversationService constructor argument). ===

    @BeforeEach
    void setUp() throws Exception {
        storeExecutor = Executors.newSingleThreadExecutor();
        store = new SoulStore(worldRoot, storeExecutor);
        store.bindProfile(BOT_ID, "frens:jake").join();
        provider = new FakeProvider();
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
                true, "We're steady.", null, "test", "test-model", 5L, null, null, null)));
        delivery = new FakeDelivery();
        scheduler = new SoulGenerationScheduler(1, 8);
        SoulSettings settings = new SoulSettings(true, true, "", "ollama", "test-model",
                URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);

        SoulProfileRegistry.loadBuiltIns();
        service = new SoulConversationService(store, new SoulPromptAssembler(), scheduler, provider,
                new SoulResponseValidator(), delivery, settings);

        key = new SoulTypes.ConversationKey(BOT_ID, PLAYER_ID, SoulTypes.Channel.DIRECT);
        turn = new SoulTypes.AcceptedTurn(key, "Jake", "Player", "How are we doing?",
                "frens:jake", localGrounding(), Instant.EPOCH);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        store.close();
    }

    private SoulTypes.GroundingSnapshot localGrounding() {
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(
                BOT_ID, "Jake", "minecraft:overworld", "plains", 0, 64, 0, true,
                "day", "clear", 20.0F, 20.0F, 18, 4, "iron_pickaxe",
                8, 36, List.of("oak_log x32"), "content", "idle", "", "IDLE",
                "Workshop", "Player", true, 2, false, Optional.empty());
        SoulTypes.PlayerSnapshot player = new SoulTypes.PlayerSnapshot(
                PLAYER_ID, "Player", 6, "north", 20.0F, 20.0F, 20, "map", false);
        return new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, bot, Optional.of(player), Instant.EPOCH);
    }

    // === Mandated tests (verbatim from the task brief) ===

    @Test
    void recordsSpokenOnlyAfterSuccessfulDelivery() throws Exception {
        delivery.completeNext(true);
        SoulConversationService.Submission submission = service.submit(turn).get(2, SECONDS);

        assertEquals(SoulConversationService.Submission.DELIVERED, submission);
        List<SoulTypes.ConversationRecord> records = store.recent(key, 20, 12_000).get(2, SECONDS);
        assertEquals(List.of(SoulTypes.TurnKind.HEARD, SoulTypes.TurnKind.SPOKEN),
                records.stream().map(SoulTypes.ConversationRecord::kind).toList());
    }

    @Test
    void failedDeliveryNeverBecomesSpokenMemory() throws Exception {
        delivery.completeNext(false);
        service.submit(turn).get(2, SECONDS);
        assertEquals(List.of(SoulTypes.TurnKind.HEARD, SoulTypes.TurnKind.FAILURE),
                store.recent(key, 20, 12_000).get(2, SECONDS).stream()
                        .map(SoulTypes.ConversationRecord::kind).toList());
    }

    // === Additional coverage: prompt ordering ===

    @Test
    void currentMessageAppearsExactlyOnceAsFinalUserMessageExcludedFromPriorHistory() throws Exception {
        delivery.completeNext(true);
        service.submit(turn).get(2, SECONDS);

        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
                true, "Still steady.", null, "test", "test-model", 5L, null, null, null)));
        delivery.completeNext(true);
        SoulTypes.AcceptedTurn secondTurn = new SoulTypes.AcceptedTurn(key, "Jake", "Player",
                "What about now?", "frens:jake", localGrounding(), Instant.EPOCH.plusSeconds(10));
        service.submit(secondTurn).get(2, SECONDS);

        assertEquals(2, provider.requests().size());
        List<SoulTypes.Message> messages = provider.requests().get(1).messages();

        SoulTypes.Message last = messages.get(messages.size() - 1);
        assertEquals(SoulTypes.Role.USER, last.role());
        assertEquals("What about now?", last.content());

        long occurrences = messages.stream().filter(m -> "What about now?".equals(m.content())).count();
        assertEquals(1, occurrences, "current message must appear exactly once");

        boolean firstTurnCarriedAsHistory = messages.stream()
                .anyMatch(m -> m.role() == SoulTypes.Role.USER && "How are we doing?".equals(m.content()));
        assertTrue(firstTurnCarriedAsHistory, "prior turn's message must be present as USER history");

        boolean firstReplyCarriedAsHistory = messages.stream()
                .anyMatch(m -> m.role() == SoulTypes.Role.ASSISTANT && "We're steady.".equals(m.content()));
        assertTrue(firstReplyCarriedAsHistory, "prior reply must be present as ASSISTANT history");
    }

    // === Additional coverage: provider failure ===

    @Test
    void providerFailureAppendsFailureRecordAndNeverSpoken() throws Exception {
        provider.clear();
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
                false, "", SoulTypes.FailureCode.TIMEOUT, "test", "test-model", 5L, null, null, null)));

        SoulConversationService.Submission submission = service.submit(turn).get(2, SECONDS);

        assertEquals(SoulConversationService.Submission.FAILED, submission);
        List<SoulTypes.ConversationRecord> records = store.recent(key, 20, 12_000).get(2, SECONDS);
        assertEquals(List.of(SoulTypes.TurnKind.HEARD, SoulTypes.TurnKind.FAILURE),
                records.stream().map(SoulTypes.ConversationRecord::kind).toList());
        assertEquals(SoulTypes.FailureCode.TIMEOUT, records.get(1).failureCode());
    }

    // === Additional coverage: invalid (rejected) response ===

    @Test
    void invalidProviderResponseAppendsMalformedFailureRecord() throws Exception {
        provider.clear();
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
                true, "", null, "test", "test-model", 5L, null, null, null)));

        SoulConversationService.Submission submission = service.submit(turn).get(2, SECONDS);

        assertEquals(SoulConversationService.Submission.FAILED, submission);
        List<SoulTypes.ConversationRecord> records = store.recent(key, 20, 12_000).get(2, SECONDS);
        assertEquals(List.of(SoulTypes.TurnKind.HEARD, SoulTypes.TurnKind.FAILURE),
                records.stream().map(SoulTypes.ConversationRecord::kind).toList());
        assertEquals(SoulTypes.FailureCode.MALFORMED, records.get(1).failureCode());
    }

    // === Additional coverage: conversation reset while generation is in flight ===

    @Test
    void resetDuringGenerationSkipsFailureRecordButStillFails() throws Exception {
        // Occupy the scheduler's sole concurrency slot with an unrelated conversation so our
        // target turn's job sits queued (never dispatched to the provider) until we invalidate it.
        SoulTypes.ConversationKey blockerKey = new SoulTypes.ConversationKey(
                UUID.randomUUID(), UUID.randomUUID(), SoulTypes.Channel.DIRECT);
        CompletableFuture<SoulTypes.ProviderResult> blockerResult = new CompletableFuture<>();
        scheduler.submit(blockerKey, 0L,
                () -> new SoulModelProvider.Call(blockerResult, () -> blockerResult.cancel(false)));

        CompletableFuture<SoulConversationService.Submission> submission = service.submit(turn);

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (scheduler.queueDepth() < 1 && System.nanoTime() < deadlineNanos) {
            Thread.sleep(5);
        }
        assertEquals(1, scheduler.queueDepth(), "target turn's job must be queued before invalidating it");

        service.invalidate(key, 1L);

        assertEquals(SoulConversationService.Submission.FAILED, submission.get(2, SECONDS));
        List<SoulTypes.ConversationRecord> records = store.recent(key, 20, 12_000).get(2, SECONDS);
        assertEquals(List.of(SoulTypes.TurnKind.HEARD),
                records.stream().map(SoulTypes.ConversationRecord::kind).toList(),
                "a stale token must never attempt a FAILURE append");

        blockerResult.complete(new SoulTypes.ProviderResult(
                true, "irrelevant", null, "test", "test-model", 1L, null, null, null));
    }

    // === Additional coverage: routing-id adoption ===

    @Test
    void turnRoutingIdBecomesTheCorrelationIdEndToEnd() throws Exception {
        // The router's routingId must survive as the correlation id of the provider request and
        // the delivery token — one id joining every [souls] log line for the turn.
        UUID routingId = UUID.randomUUID();
        SoulTypes.AcceptedTurn routedTurn = new SoulTypes.AcceptedTurn(key, "Jake", "Player",
                "How are we doing?", "frens:jake", localGrounding(), Instant.EPOCH, routingId);
        delivery.completeNext(true);

        service.submit(routedTurn).get(2, SECONDS);

        assertEquals(routingId, provider.requests().get(0).correlationId());
        assertEquals(routingId, delivery.lastToken.correlationId());
    }

    // === Additional coverage: SpokenListener seam (voice subscription point) ===

    @Test
    void spokenListenerFiresExactlyOncePerDeliveredTurnWithTheValidatedText() throws Exception {
        List<String> spoken = new ArrayList<>();
        SoulConversationService listening = new SoulConversationService(store,
                new SoulPromptAssembler(), scheduler, provider, new SoulResponseValidator(),
                delivery, new SoulSettings(true, true, "", "ollama", "test-model",
                        URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8),
                (t, token, text) -> spoken.add(text));
        delivery.completeNext(true);

        listening.submit(turn).get(2, SECONDS);

        assertEquals(List.of("We're steady."), spoken);
    }

    @Test
    void spokenListenerNeverFiresOnFailedDelivery() throws Exception {
        List<String> spoken = new ArrayList<>();
        SoulConversationService listening = new SoulConversationService(store,
                new SoulPromptAssembler(), scheduler, provider, new SoulResponseValidator(),
                delivery, new SoulSettings(true, true, "", "ollama", "test-model",
                        URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8),
                (t, token, text) -> spoken.add(text));
        delivery.completeNext(false);

        listening.submit(turn).get(2, SECONDS);

        assertEquals(List.of(), spoken);
    }

    // === Fixtures (verbatim from the task brief, with additive, non-modifying test-only helpers) ===

    private static final class FakeProvider implements SoulModelProvider {
        private final Deque<CompletableFuture<SoulTypes.ProviderResult>> results = new ArrayDeque<>();
        private final List<SoulTypes.ProviderRequest> requests = new ArrayList<>();

        void enqueue(CompletableFuture<SoulTypes.ProviderResult> result) {
            results.addLast(result);
        }

        /** Test-only: drops the default fixture result so a test can substitute its own. */
        void clear() {
            results.clear();
        }

        /** Test-only accessor for asserting prompt assembly/ordering. */
        List<SoulTypes.ProviderRequest> requests() {
            return requests;
        }

        @Override public String id() { return "test"; }

        @Override
        public Call generate(SoulTypes.ProviderRequest request) {
            requests.add(request);
            CompletableFuture<SoulTypes.ProviderResult> result = results.removeFirst();
            return new Call(result, () -> result.cancel(false));
        }

        @Override public CompletableFuture<Boolean> health() {
            return CompletableFuture.completedFuture(true);
        }

        @Override public void close() {
        }
    }

    private static final class FakeDelivery implements SoulConversationService.Delivery {
        private final Deque<Boolean> results = new ArrayDeque<>();
        /** Test-only: the token of the most recent deliverReply, for correlation-id assertions. */
        private SoulTypes.TurnToken lastToken;

        void completeNext(boolean result) {
            results.addLast(result);
        }

        @Override
        public CompletableFuture<Boolean> deliverReply(SoulTypes.AcceptedTurn turn,
                                                        SoulTypes.TurnToken token,
                                                        String text) {
            lastToken = token;
            return CompletableFuture.completedFuture(results.removeFirst());
        }

        @Override
        public void deliverStatus(UUID playerId, String text) {
        }
    }
}
