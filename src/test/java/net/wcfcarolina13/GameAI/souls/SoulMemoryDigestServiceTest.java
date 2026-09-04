package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulMemoryDigestServiceTest {

    private static final UUID BOT_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();

    @TempDir
    Path worldRoot;

    private ExecutorService storeExecutor;
    private SoulStore store;
    private FakeProvider provider;
    private SoulGenerationScheduler scheduler;
    private AtomicBoolean enabled;
    private SoulMemoryDigestService service;

    @BeforeEach
    void setUp() {
        storeExecutor = Executors.newSingleThreadExecutor();
        store = new SoulStore(worldRoot, storeExecutor);
        provider = new FakeProvider();
        scheduler = new SoulGenerationScheduler(1, 8);
        enabled = new AtomicBoolean(true);
        // The runtime hands the digest two stores (DM + party); reusing one instance here is the
        // same seam the runtime's own tests use -- botDirectories() then also lists DM bots, which
        // the service must tolerate by only digesting party owners that have a known name.
        service = new SoulMemoryDigestService(store, store, scheduler, provider, "test-model",
                Duration.ofSeconds(5), enabled::get);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        storeExecutor.shutdownNow();
    }

    @Test
    void digestWritesMemoriesAndAdvancesCursor() throws Exception {
        SoulTypes.ConversationKey key =
                new SoulTypes.ConversationKey(BOT_ID, PLAYER_ID, SoulTypes.Channel.DIRECT);
        heard(key, "I hate the Nether", "I want to build a farm", "call the base Home",
                "creepers scare me");
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(true,
                "- Roti hates the Nether\n- Roti wants to build a farm\n- none of this\n",
                null, "test", "test-model", 5L, null, null, null)));

        service.digest(BOT_ID, "Jake", 3, Map.of(PLAYER_ID, "Roti")).get(5, SECONDS);

        SoulTypes.SoulMind mind = store.mind(BOT_ID).get(5, SECONDS);
        assertEquals(2, mind.playerMemories().size());
        assertEquals("Roti hates the Nether", mind.playerMemories().get(0).fact());
        assertEquals("Roti wants to build a farm", mind.playerMemories().get(1).fact());
        assertEquals(3, mind.playerMemories().get(0).day());
        assertEquals(PLAYER_ID, mind.playerMemories().get(0).playerId());
        assertFalse(mind.playerMemories().get(0).sourceCorrelationIds().isEmpty());
        assertEquals(new SoulTypes.ConversationCursor(0L, 4L),
                mind.digestCursors().get("DIRECT:" + PLAYER_ID));
        assertEquals(1, provider.requests().size());
        SoulTypes.ProviderRequest request = provider.requests().get(0);
        assertEquals(2, request.messages().size());
        assertEquals(SoulTypes.Role.SYSTEM, request.messages().get(0).role());
        assertTrue(request.messages().get(0).content().contains("memory clerk for Jake"));
        assertEquals(200, request.maxOutputTokens());
        assertTrue(request.messages().get(1).content().contains("Roti: I hate the Nether"));
        assertTrue(request.messages().get(1).content().contains("Roti: creepers scare me"));
    }

    @Test
    void providerFailureAdvancesCursorWithoutMemories() throws Exception {
        SoulTypes.ConversationKey key =
                new SoulTypes.ConversationKey(BOT_ID, PLAYER_ID, SoulTypes.Channel.DIRECT);
        heard(key, "I hate the Nether", "I want to build a farm", "call the base Home",
                "creepers scare me");
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(false,
                "", SoulTypes.FailureCode.TIMEOUT, "test", "test-model", 5L, null, null, null)));

        service.digest(BOT_ID, "Jake", 3, Map.of(PLAYER_ID, "Roti")).get(5, SECONDS);

        SoulTypes.SoulMind mind = store.mind(BOT_ID).get(5, SECONDS);
        assertEquals(0, mind.playerMemories().size());
        assertEquals(new SoulTypes.ConversationCursor(0L, 4L),
                mind.digestCursors().get("DIRECT:" + PLAYER_ID));
        assertEquals(1, provider.requests().size());
    }

    @Test
    void tooFewLinesDoesNotCallProviderOrMoveCursor() throws Exception {
        SoulTypes.ConversationKey key =
                new SoulTypes.ConversationKey(BOT_ID, PLAYER_ID, SoulTypes.Channel.DIRECT);
        heard(key, "I hate the Nether", "I want to build a farm", "call the base Home");

        service.digest(BOT_ID, "Jake", 3, Map.of(PLAYER_ID, "Roti")).get(5, SECONDS);

        SoulTypes.SoulMind mind = store.mind(BOT_ID).get(5, SECONDS);
        assertEquals(List.of(), provider.requests());
        assertEquals(0, mind.playerMemories().size());
        assertNull(mind.digestCursors().get("DIRECT:" + PLAYER_ID));
    }

    @Test
    void disabledIsNoOp() throws Exception {
        enabled.set(false);
        SoulTypes.ConversationKey key =
                new SoulTypes.ConversationKey(BOT_ID, PLAYER_ID, SoulTypes.Channel.DIRECT);
        heard(key, "I hate the Nether", "I want to build a farm", "call the base Home",
                "creepers scare me");

        service.digest(BOT_ID, "Jake", 3, Map.of(PLAYER_ID, "Roti")).get(5, SECONDS);

        SoulTypes.SoulMind mind = store.mind(BOT_ID).get(5, SECONDS);
        assertEquals(List.of(), provider.requests());
        assertEquals(0, mind.playerMemories().size());
        assertNull(mind.digestCursors().get("DIRECT:" + PLAYER_ID));
    }

    @Test
    void partyTranscriptDigestsUnderThePartyCursor() throws Exception {
        SoulTypes.ConversationKey partyKey = SoulGroupTypes.partyKey(PLAYER_ID);
        for (String line : List.of("Roti: I hate the Nether", "Roti: I want to build a farm",
                "Roti: call the base Home", "Roti: creepers scare me")) {
            store.beginHeardTurn(partyKey, UUID.randomUUID(), line, Instant.EPOCH, List.of(BOT_ID))
                    .get(5, SECONDS);
        }
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(true,
                "- Roti hates the Nether\n", null, "test", "test-model", 5L, null, null, null)));

        // BOT_ID has no name in playerNames, so its own (shared-store) directory is not digested
        // as a party; only PLAYER_ID's party transcript is.
        service.digest(BOT_ID, "Jake", 7, Map.of(PLAYER_ID, "Roti")).get(5, SECONDS);

        SoulTypes.SoulMind mind = store.mind(BOT_ID).get(5, SECONDS);
        assertEquals(1, mind.playerMemories().size());
        assertEquals("Roti hates the Nether", mind.playerMemories().get(0).fact());
        assertEquals(new SoulTypes.ConversationCursor(0L, 4L),
                mind.digestCursors().get("PARTY:" + PLAYER_ID));
        assertEquals(1, provider.requests().size());
        assertTrue(provider.requests().get(0).messages().get(1).content()
                .contains("Roti: creepers scare me"));
    }

    private void heard(SoulTypes.ConversationKey key, String... lines) throws Exception {
        for (String line : lines) {
            store.beginHeardTurn(key, UUID.randomUUID(), line, Instant.EPOCH).get(5, SECONDS);
        }
    }

    // === Fixtures (FakeProvider copied from SoulConversationServiceTest) ===

    private static final class FakeProvider implements SoulModelProvider {
        private final Deque<CompletableFuture<SoulTypes.ProviderResult>> results = new ArrayDeque<>();
        private final List<SoulTypes.ProviderRequest> requests = new ArrayList<>();

        void enqueue(CompletableFuture<SoulTypes.ProviderResult> result) {
            results.addLast(result);
        }

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
}
