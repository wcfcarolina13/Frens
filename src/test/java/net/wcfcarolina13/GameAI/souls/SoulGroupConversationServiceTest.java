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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulGroupConversationServiceTest {

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID JAKE_ID = UUID.randomUUID();
    private static final UUID SARA_ID = UUID.randomUUID();

    @TempDir
    Path partyRoot;

    private ExecutorService storeExecutor;
    private SoulStore partyStore;
    private FakeProvider provider;
    private FakeScenePlayer player;
    private FakeStatus status;
    private SoulGenerationScheduler scheduler;
    private SoulGroupConversationService service;
    private SoulGroupTypes.GroupSceneTurn turn;

    @BeforeEach
    void setUp() {
        storeExecutor = Executors.newSingleThreadExecutor();
        partyStore = SoulStore.openAt(partyRoot, storeExecutor);
        provider = new FakeProvider();
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
                true, "Jake: Mining tonight.\nSara: Fishing is safer.", null, "test", "test-model",
                5L, null, null, null)));
        player = new FakeScenePlayer();
        status = new FakeStatus();
        scheduler = new SoulGenerationScheduler(1, 8);
        SoulSettings settings = new SoulSettings(true, true, "", "ollama", "test-model",
                URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
        SoulProfileRegistry.loadBuiltIns();
        service = new SoulGroupConversationService(partyStore, new SoulGroupPromptAssembler(),
                scheduler, provider, new SoulGroupResponseValidator(), settings, player, status);
        turn = sceneTurn("what should we do tonight");
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        partyStore.close();
    }

    private static SoulTypes.GroundingSnapshot grounding(UUID botId, String name) {
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(botId, name, "overworld", "plains",
                0, 64, 0, true, "dusk", "clear", 20f, 20f, 18, 4, "", 4, 36,
                List.of(), "content", "FOLLOW", "", "", "", "Bradley", true, 0, true, Optional.empty());
        return new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                Optional.empty(), Instant.EPOCH);
    }

    private static SoulGroupTypes.GroupSceneTurn sceneTurn(String message) {
        return new SoulGroupTypes.GroupSceneTurn(OWNER_ID, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(JAKE_ID, "frens:jake", "Jake", grounding(JAKE_ID, "Jake")),
                        new SoulGroupTypes.SceneParticipant(SARA_ID, "frens:jake", "Sara", grounding(SARA_ID, "Sara"))),
                message, Instant.EPOCH, UUID.randomUUID());
    }

    private List<SoulTypes.ConversationRecord> partyRecords() throws Exception {
        return partyStore.recent(turn.key(), 20, 12_000).get(2, SECONDS);
    }

    @Test
    void happyPathTagsHeardStartsSceneAndHandsLinesToThePlayer() throws Exception {
        var submission = service.submit(turn).get(2, SECONDS);
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED, submission);

        assertEquals(1, player.enqueued.size());
        assertEquals(2, player.enqueued.get(0).lines().size());
        assertEquals(0, player.enqueued.get(0).lines().get(0).participantIndex());

        List<SoulTypes.ConversationRecord> records = partyRecords();
        assertEquals(1, records.size());
        assertEquals(SoulTypes.TurnKind.HEARD, records.get(0).kind());
        assertEquals("Bradley: what should we do tonight", records.get(0).content());
    }

    @Test
    void activeSceneGuardRefusesWithoutTouchingTheStore() throws Exception {
        player.active = true;
        var submission = service.submit(turn).get(2, SECONDS);
        assertEquals(SoulGroupConversationService.Submission.FAILED, submission);
        assertEquals(1, status.messages.size());
        assertTrue(status.messages.get(0).contains("still talking"));
        assertTrue(partyRecords().isEmpty());
    }

    @Test
    void providerFailureAppendsFailureAndSendsPluralStatus() throws Exception {
        provider.clear();
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
                false, "", SoulTypes.FailureCode.TIMEOUT, "test", "test-model", 5L, null, null, null)));
        var submission = service.submit(turn).get(2, SECONDS);
        assertEquals(SoulGroupConversationService.Submission.FAILED, submission);
        assertEquals(List.of(SoulTypes.TurnKind.HEARD, SoulTypes.TurnKind.FAILURE),
                partyRecords().stream().map(SoulTypes.ConversationRecord::kind).toList());
        assertTrue(status.messages.get(0).contains("companions"));
        assertTrue(player.enqueued.isEmpty());
    }

    @Test
    void unparseableResponseFailsMalformed() throws Exception {
        provider.clear();
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
                true, "no tagged lines at all", null, "test", "test-model", 5L, null, null, null)));
        var submission = service.submit(turn).get(2, SECONDS);
        assertEquals(SoulGroupConversationService.Submission.FAILED, submission);
        assertEquals(List.of(SoulTypes.TurnKind.HEARD, SoulTypes.TurnKind.FAILURE),
                partyRecords().stream().map(SoulTypes.ConversationRecord::kind).toList());
        assertTrue(player.enqueued.isEmpty());
    }

    @Test
    void commitLineAppendsTaggedSpokenRecordsInOrder() throws Exception {
        service.submit(turn).get(2, SECONDS);
        SoulTypes.TurnToken token = player.enqueued.get(0).token();

        service.commitLine(token, 0, "Jake: Mining tonight.");
        service.commitLine(token, 1, "Sara: Fishing is safer.");
        service.sceneFinished(token, 2, 2);
        partyStore.state(OWNER_ID).get(2, SECONDS); // barrier: drain the writer queue

        List<SoulTypes.ConversationRecord> records = partyRecords();
        assertEquals(List.of(SoulTypes.TurnKind.HEARD, SoulTypes.TurnKind.SPOKEN, SoulTypes.TurnKind.SPOKEN),
                records.stream().map(SoulTypes.ConversationRecord::kind).toList());
        assertEquals("Jake: Mining tonight.", records.get(1).content());
        assertEquals("Sara: Fishing is safer.", records.get(2).content());
        assertTrue(records.get(2).sequence() > records.get(1).sequence());
    }

    @Test
    void unknownProfileFailsClosedWithInternalStatus() throws Exception {
        SoulGroupTypes.GroupSceneTurn badTurn = new SoulGroupTypes.GroupSceneTurn(OWNER_ID, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(JAKE_ID, "frens:missing", "Jake", grounding(JAKE_ID, "Jake")),
                        new SoulGroupTypes.SceneParticipant(SARA_ID, "frens:jake", "Sara", grounding(SARA_ID, "Sara"))),
                "hi", Instant.EPOCH, UUID.randomUUID());
        var submission = service.submit(badTurn).get(2, SECONDS);
        assertEquals(SoulGroupConversationService.Submission.FAILED, submission);
        assertEquals(1, status.messages.size());
        assertTrue(player.enqueued.isEmpty());
    }

    // === Banter turns ===

    private static SoulGroupTypes.GroupSceneTurn banterTurn(String seed) {
        return new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.BANTER, OWNER_ID, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(JAKE_ID, "frens:jake", "Jake", grounding(JAKE_ID, "Jake")),
                        new SoulGroupTypes.SceneParticipant(SARA_ID, "frens:jake", "Sara", grounding(SARA_ID, "Sara"))),
                seed, Instant.EPOCH, UUID.randomUUID());
    }

    @Test
    void banterHeardIsTaggedWithTheBanterMarkerNotThePlayerName() throws Exception {
        SoulGroupTypes.GroupSceneTurn banter = banterTurn("dusk chatter seed");
        var submission = service.submit(banter).get(2, SECONDS);
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED, submission);
        List<SoulTypes.ConversationRecord> records =
                partyStore.recent(banter.key(), 20, 12_000).get(2, SECONDS);
        assertEquals(SoulGroupPromptAssembler.BANTER_HEARD_PREFIX + "dusk chatter seed",
                records.get(0).content());
    }

    @Test
    void banterFailuresAreSilentToThePlayer() throws Exception {
        provider.clear();
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
                false, "", SoulTypes.FailureCode.TIMEOUT, "test", "test-model", 5L, null, null, null)));
        var submission = service.submit(banterTurn("seed")).get(2, SECONDS);
        assertEquals(SoulGroupConversationService.Submission.FAILED, submission);
        assertTrue(status.messages.isEmpty(), "banter failure must not nag the player");
        assertEquals(List.of(SoulTypes.TurnKind.HEARD, SoulTypes.TurnKind.FAILURE),
                partyStore.recent(SoulGroupTypes.partyKey(OWNER_ID), 20, 12_000).get(2, SECONDS)
                        .stream().map(SoulTypes.ConversationRecord::kind).toList());
    }

    @Test
    void banterBusyGuardIsAlsoSilent() throws Exception {
        player.active = true;
        var submission = service.submit(banterTurn("seed")).get(2, SECONDS);
        assertEquals(SoulGroupConversationService.Submission.FAILED, submission);
        assertTrue(status.messages.isEmpty());
    }

    @Test
    void banterScenesAreCappedAtFourLines() throws Exception {
        // Three bots × two lines each = six lines surviving the per-bot cap; only the banter
        // scene cap (4) explains a smaller result.
        UUID thirdId = UUID.randomUUID();
        SoulGroupTypes.GroupSceneTurn banter = new SoulGroupTypes.GroupSceneTurn(
                SoulGroupTypes.SceneKind.BANTER, OWNER_ID, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(JAKE_ID, "frens:jake", "Jake", grounding(JAKE_ID, "Jake")),
                        new SoulGroupTypes.SceneParticipant(SARA_ID, "frens:jake", "Sara", grounding(SARA_ID, "Sara")),
                        new SoulGroupTypes.SceneParticipant(thirdId, "frens:jake", "Milo", grounding(thirdId, "Milo"))),
                "seed", Instant.EPOCH, UUID.randomUUID());
        provider.clear();
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
                true, "Jake: a\nSara: b\nMilo: c\nJake: d\nSara: e\nMilo: f", null, "test",
                "test-model", 5L, null, null, null)));
        var submission = service.submit(banter).get(2, SECONDS);
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED, submission);
        assertEquals(SoulGroupTypes.BANTER_MAX_SCENE_LINES, player.enqueued.get(0).lines().size());
    }

    // === Fakes ===

    private static final class FakeScenePlayer implements SoulGroupConversationService.ScenePlayer {
        final List<GroupScenePlayback.PlayableScene> enqueued = new CopyOnWriteArrayList<>();
        volatile boolean active;

        @Override public void enqueue(GroupScenePlayback.PlayableScene scene) {
            enqueued.add(scene);
        }

        @Override public boolean hasActiveScene(UUID ownerId) {
            return active;
        }
    }

    private static final class FakeStatus implements SoulGroupConversationService.StatusSink {
        final List<String> messages = new CopyOnWriteArrayList<>();

        @Override public void deliverStatus(UUID playerId, String text) {
            messages.add(text);
        }
    }

    private static final class FakeProvider implements SoulModelProvider {
        private final Deque<CompletableFuture<SoulTypes.ProviderResult>> results = new ArrayDeque<>();
        private final List<SoulTypes.ProviderRequest> requests = new ArrayList<>();

        void enqueue(CompletableFuture<SoulTypes.ProviderResult> result) {
            results.addLast(result);
        }

        void clear() {
            results.clear();
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

        @Override public void close() { }
    }
}
