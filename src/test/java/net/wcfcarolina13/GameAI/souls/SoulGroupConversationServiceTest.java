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
        assertEquals(null, player.enqueued.get(0).privateSeedOwner(), "no private memory, no playback gate");

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
    void deathDuringFailureAppendSuppressesLateFailureStatus() throws Exception {
        assertFailureAppendRevoked(() -> service.cancelForBot(JAKE_ID));
    }

    @Test
    void pipelineCloseDuringFailureAppendSuppressesLateFailureStatus() throws Exception {
        assertFailureAppendRevoked(() -> service.close());
    }

    private void assertFailureAppendRevoked(Runnable revoke) throws Exception {
        provider.clear();
        CompletableFuture<SoulTypes.ProviderResult> failure = new CompletableFuture<>();
        provider.enqueue(failure);
        CompletableFuture<SoulGroupConversationService.Submission> submitted = service.submit(turn);
        assertTrue(provider.started.await(2, SECONDS));

        CompletableFuture<Void> writerBlocked = new CompletableFuture<>();
        CompletableFuture<Void> releaseWriter = new CompletableFuture<>();
        storeExecutor.submit(() -> {
            writerBlocked.complete(null);
            releaseWriter.join();
        });
        writerBlocked.get(2, SECONDS);
        try {
            failure.complete(new SoulTypes.ProviderResult(false, "", SoulTypes.FailureCode.TIMEOUT,
                    "test", "test-model", 5L, null, null, null));
            revoke.run();
            assertEquals(SoulGroupConversationService.Submission.FAILED, submitted.get(2, SECONDS));
        } finally {
            releaseWriter.complete(null);
        }
        storeExecutor.submit(() -> { }).get(2, SECONDS);
        assertTrue(status.messages.isEmpty(), "revoked scenes cannot send an obsolete failure status");
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

        service.commitLine(token, 0, "Jake: Mining tonight.", null);
        service.commitLine(token, 1, "Sara: Fishing is safer.", null);
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
    void commitLineMarksAPrivatelySeededLinePrivateToItsOwner() throws Exception {
        service.submit(turn).get(2, SECONDS);
        SoulTypes.TurnToken token = player.enqueued.get(0).token();

        service.commitLine(token, 0, "Jake: still scared of the dark?", OWNER_ID);
        service.commitLine(token, 1, "Sara: Fishing is safer.", null);
        service.sceneFinished(token, 2, 2);
        partyStore.state(OWNER_ID).get(2, SECONDS); // barrier: drain the writer queue

        List<SoulTypes.ConversationRecord> records = partyRecords();
        assertEquals(null, records.get(0).privateTo(), "the owner's own HEARD line is never marked");
        assertEquals(OWNER_ID, records.get(1).privateTo());
        assertEquals(null, records.get(2).privateTo());
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
    void workSeedIsTaggedWithTheBanterMarkerNotThePlayerName() throws Exception {
        // 1.1.196 field bug: WORK-lane seeds were persisted as "<Player>: <seed>" and replayed as
        // something the player said, so bots credited the player with the bots' own events.
        SoulGroupTypes.GroupSceneTurn work = new SoulGroupTypes.GroupSceneTurn(
                SoulGroupTypes.SceneKind.WORK, OWNER_ID, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(JAKE_ID, "frens:jake", "Jake", grounding(JAKE_ID, "Jake")),
                        new SoulGroupTypes.SceneParticipant(SARA_ID, "frens:jake", "Sara", grounding(SARA_ID, "Sara"))),
                "one of you needles the other about the work", Instant.EPOCH, UUID.randomUUID());
        var submission = service.submit(work).get(2, SECONDS);
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED, submission);
        List<SoulTypes.ConversationRecord> records =
                partyStore.recent(work.key(), 20, 12_000).get(2, SECONDS);
        assertEquals(SoulGroupPromptAssembler.BANTER_HEARD_PREFIX + "one of you needles the other about the work",
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

    private SoulGroupConversationService noveltyService(String reply, int times) {
        provider.clear();
        for (int i = 0; i < times; i++) {
            provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
                    true, reply, null, "test", "test-model", 5L, null, null, null)));
        }
        SoulSettings settings = new SoulSettings(true, true, "", "ollama", "test-model",
                URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
        return new SoulGroupConversationService(partyStore, new SoulGroupPromptAssembler(), scheduler,
                provider, new SoulGroupResponseValidator(), settings, player, status, () -> true);
    }

    @Test
    void noveltyNeverEmptiesAPlayerScene() throws Exception {
        // Finding 19: the same reply twice would drop every line of the second scene. A PLAYER
        // scene keeps its first parsed line instead, so the owner is never answered by silence.
        SoulGroupConversationService novel = noveltyService("Jake: Mining tonight.\nSara: Fishing is safer.", 2);
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED, novel.submit(turn).get(2, SECONDS));
        assertEquals(2, player.enqueued.get(0).lines().size());

        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED,
                novel.submit(sceneTurn("and tomorrow?")).get(2, SECONDS));
        List<SoulGroupTypes.SceneLine> second = player.enqueued.get(1).lines();
        assertEquals(1, second.size(), "novelty floor keeps exactly one line");
        assertEquals(0, second.get(0).participantIndex());
        assertEquals("Mining tonight.", second.get(0).text());
    }

    @Test
    void noveltyMayStillEmptyABanterScene() throws Exception {
        SoulGroupConversationService novel = noveltyService("Jake: Mining tonight.\nSara: Fishing is safer.", 2);
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED,
                novel.submit(banterTurn("seed one")).get(2, SECONDS));
        assertEquals(2, player.enqueued.get(0).lines().size());
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED,
                novel.submit(banterTurn("seed two")).get(2, SECONDS));
        assertEquals(0, player.enqueued.get(1).lines().size(), "banter keeps the zero-delivery path");
    }

    @Test
    void noveltyDroppingTheMiddleLineMergesTheSpeakerAgain() throws Exception {
        // Finding 3: Jake, Sara, Jake with Sara's repeat dropped must play as one Jake turn.
        SoulGroupConversationService novel = noveltyService("Sara: Fishing is safer.", 1);
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED,
                novel.submit(banterTurn("seed one")).get(2, SECONDS));
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(true,
                "Jake: Mining tonight.\nSara: Fishing is safer.\nJake: Bring torches.",
                null, "test", "test-model", 5L, null, null, null)));
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED,
                novel.submit(banterTurn("seed two")).get(2, SECONDS));
        List<SoulGroupTypes.SceneLine> lines = player.enqueued.get(1).lines();
        assertEquals(1, lines.size());
        assertEquals(0, lines.get(0).participantIndex());
        assertEquals("Mining tonight. Bring torches.", lines.get(0).text());
    }

    // === Fakes ===

    @Test
    void sceneWhosePromptAdmittedAPrivateMemoryIsFlaggedForTheOwner() throws Exception {
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), List.of(
                new SoulTypes.PlayerMemory(OWNER_ID, 3, "Bradley is scared of the dark", 9, -1, List.of(),
                        SoulTypes.MemoryVisibility.PRIVATE)));
        SoulSettings settings = new SoulSettings(true, true, "", "ollama", "test-model",
                URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
        SoulGroupConversationService withMind = new SoulGroupConversationService(partyStore,
                new SoulGroupPromptAssembler(id -> Optional.of(mind)),
                scheduler, provider, new SoulGroupResponseValidator(), settings, player, status);
        SoulGroupTypes.GroupSceneTurn alone = new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.PLAYER,
                OWNER_ID, "Bradley", turn.roster(), "what should we do tonight", Instant.EPOCH,
                UUID.randomUUID(), false, java.util.Set.of(OWNER_ID));

        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED, withMind.submit(alone).get(2, SECONDS));
        assertEquals(1, player.enqueued.size());
        assertEquals(OWNER_ID, player.enqueued.get(0).privateSeedOwner());
        assertTrue(player.enqueued.get(0).privatelySeeded());
    }

    @Test
    void privateSeedStaysPrivateWhenMindMemoryIsGoneBeforeAssembly() throws Exception {
        SoulGroupTypes.GroupSceneTurn seeded = new SoulGroupTypes.GroupSceneTurn(
                SoulGroupTypes.SceneKind.BANTER, OWNER_ID, "Bradley", turn.roster(),
                "Bradley once said: scared of the dark", Instant.EPOCH, UUID.randomUUID(),
                false, java.util.Set.of(OWNER_ID), OWNER_ID);

        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED,
                service.submit(seeded).get(2, SECONDS));
        assertEquals(OWNER_ID, player.enqueued.get(0).privateSeedOwner());
    }

    @Test
    void conflictingPrivateOwnersRejectSceneBeforeGeneration() throws Exception {
        UUID other = UUID.randomUUID();
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), List.of(
                new SoulTypes.PlayerMemory(OWNER_ID, 3, "private fact", 9, -1, List.of(),
                        SoulTypes.MemoryVisibility.PRIVATE)));
        SoulSettings settings = new SoulSettings(true, true, "", "ollama", "test-model",
                URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
        SoulGroupConversationService withMind = new SoulGroupConversationService(partyStore,
                new SoulGroupPromptAssembler(id -> Optional.of(mind)), scheduler, provider,
                new SoulGroupResponseValidator(), settings, player, status);
        SoulGroupTypes.GroupSceneTurn conflicting = new SoulGroupTypes.GroupSceneTurn(
                SoulGroupTypes.SceneKind.PLAYER, OWNER_ID, "Bradley", turn.roster(), "hello",
                Instant.EPOCH, UUID.randomUUID(), false, java.util.Set.of(OWNER_ID), other);

        assertEquals(SoulGroupConversationService.Submission.FAILED,
                withMind.submit(conflicting).get(2, SECONDS));
        assertTrue(player.enqueued.isEmpty());
    }

    private static final String SIDE_CHANNEL_SCENE =
            "Jake: Mining tonight.\nSara: Fishing is safer.\n##FRENS {\"stance\":{\"Sara\":{\"warmth\":1}}}";

    private SoulGroupConversationService structuredService(SoulGroupPromptAssembler prompts,
                                                           List<SoulSideChannelOps.SideEffects> applied) {
        SoulSettings settings = new SoulSettings(true, true, "", "ollama", "test-model",
                URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
        provider.clear();
        provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
                true, SIDE_CHANNEL_SCENE, null, "test", "test-model", 5L, null, null, null)));
        return new SoulGroupConversationService(partyStore, prompts, scheduler, provider,
                new SoulGroupResponseValidator(), settings, player, status,
                () -> false, () -> true, (sceneTurn, effects) -> applied.add(effects));
    }

    @Test
    void privatelySeededSceneAppliesNoSideChannelEffects() throws Exception {
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), List.of(
                new SoulTypes.PlayerMemory(OWNER_ID, 3, "Bradley is scared of the dark", 9, -1, List.of(),
                        SoulTypes.MemoryVisibility.PRIVATE)));
        List<SoulSideChannelOps.SideEffects> applied = new CopyOnWriteArrayList<>();
        SoulGroupConversationService withMind = structuredService(
                new SoulGroupPromptAssembler(id -> Optional.of(mind)), applied);
        SoulGroupTypes.GroupSceneTurn alone = new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.PLAYER,
                OWNER_ID, "Bradley", turn.roster(), "what should we do tonight", Instant.EPOCH,
                UUID.randomUUID(), false, java.util.Set.of(OWNER_ID));

        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED, withMind.submit(alone).get(2, SECONDS));
        assertEquals(1, player.enqueued.size());
        assertEquals(OWNER_ID, player.enqueued.get(0).privateSeedOwner());
        assertEquals(2, player.enqueued.get(0).lines().size(), "the sentinel line is never spoken");
        assertTrue(applied.isEmpty(), "a privately seeded scene must not fold ##FRENS into shared minds");
    }

    @Test
    void sharedSceneStillAppliesSideChannelEffects() throws Exception {
        List<SoulSideChannelOps.SideEffects> applied = new CopyOnWriteArrayList<>();
        SoulGroupConversationService shared = structuredService(new SoulGroupPromptAssembler(), applied);

        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED, shared.submit(turn).get(2, SECONDS));
        assertEquals(null, player.enqueued.get(0).privateSeedOwner());
        assertEquals(1, applied.size(), "control: the same payload applies when the scene is not private");
        assertEquals(1, applied.get(0).stanceDeltas().size());
        assertEquals("Sara", applied.get(0).stanceDeltas().get(0).peerName());
    }

    private SoulGroupConversationService lifecycleService(SoulGroupPromptAssembler prompts,
                                                         SoulGroupConversationService.SideChannelSink sink) {
        SoulSettings settings = new SoulSettings(true, true, "", "ollama", "test-model",
                URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
        return new SoulGroupConversationService(partyStore, prompts, scheduler, provider,
                new SoulGroupResponseValidator(), settings, player, status, () -> false, () -> true, sink);
    }

    private static SoulTypes.ProviderResult sceneSuccess() {
        return new SoulTypes.ProviderResult(true, SIDE_CHANNEL_SCENE, null, "test", "test-model",
                5L, null, null, null);
    }

    @Test
    void deathBeforeHeardTokenPreventsProviderDispatch() throws Exception {
        CompletableFuture<Void> release = new CompletableFuture<>();
        storeExecutor.submit(() -> { release.join(); });
        CompletableFuture<SoulGroupConversationService.Submission> submitted = service.submit(turn);
        try {
            service.cancelForBot(JAKE_ID);
            assertEquals(SoulGroupConversationService.Submission.FAILED, submitted.get(2, SECONDS));
        } finally {
            release.complete(null);
        }
        storeExecutor.submit(() -> { }).get(2, SECONDS);
        assertTrue(provider.requests.isEmpty());
        assertTrue(player.enqueued.isEmpty());
    }

    @Test
    void deathDuringPromptAssemblyDoesNotRegisterAProviderCall() throws Exception {
        service = lifecycleService(new SoulGroupPromptAssembler(id -> {
            service.cancelForBot(JAKE_ID);
            return Optional.empty();
        }), (scene, effects) -> { });
        assertEquals(SoulGroupConversationService.Submission.FAILED, service.submit(turn).get(2, SECONDS));
        storeExecutor.submit(() -> { }).get(2, SECONDS);
        assertTrue(provider.requests.isEmpty());
        assertTrue(player.enqueued.isEmpty());
    }

    @Test
    void ignoredProviderCancellationCannotEnqueueOrApplyEffectsAndRespawnCanStartFresh() throws Exception {
        List<SoulSideChannelOps.SideEffects> applied = new CopyOnWriteArrayList<>();
        service = lifecycleService(new SoulGroupPromptAssembler(), (scene, effects) -> applied.add(effects));
        provider.clear();
        provider.ignoreCancellation = true;
        CompletableFuture<SoulTypes.ProviderResult> late = new CompletableFuture<>();
        provider.enqueue(late);
        provider.enqueue(CompletableFuture.completedFuture(sceneSuccess()));
        CompletableFuture<SoulGroupConversationService.Submission> submitted = service.submit(turn);
        assertTrue(provider.started.await(2, SECONDS));
        service.cancelForBot(JAKE_ID);
        scheduler.cancelForBot(JAKE_ID);
        assertEquals(SoulGroupConversationService.Submission.FAILED, submitted.get(2, SECONDS));
        late.complete(sceneSuccess());
        assertTrue(player.enqueued.isEmpty());
        assertTrue(applied.isEmpty());
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED,
                service.submit(sceneTurn("we returned")).get(2, SECONDS));
        assertEquals(1, player.enqueued.size());
        assertEquals(1, applied.size());
    }

    @Test
    void unrelatedBotDeathDoesNotRevokeTheRoster() throws Exception {
        provider.clear();
        CompletableFuture<SoulTypes.ProviderResult> reply = new CompletableFuture<>();
        provider.enqueue(reply);
        CompletableFuture<SoulGroupConversationService.Submission> submitted = service.submit(turn);
        assertTrue(provider.started.await(2, SECONDS));
        service.cancelForBot(UUID.randomUUID());
        reply.complete(sceneSuccess());
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED, submitted.get(2, SECONDS));
    }

    @Test
    void queuedServerSideEffectsRemainRevocableAfterPlaybackCleanup() throws Exception {
        List<Runnable> serverTasks = new CopyOnWriteArrayList<>();
        List<SoulSideChannelOps.SideEffects> applied = new CopyOnWriteArrayList<>();
        service = lifecycleService(new SoulGroupPromptAssembler(), new SoulGroupConversationService.SideChannelSink() {
            @Override public void apply(SoulGroupTypes.GroupSceneTurn scene, SoulSideChannelOps.SideEffects effects) {
                throw new AssertionError("production must preserve the validity gate across the hop");
            }
            @Override public void applyIfCurrent(SoulGroupTypes.GroupSceneTurn scene,
                                                SoulSideChannelOps.SideEffects effects,
                                                java.util.function.BooleanSupplier current) {
                serverTasks.add(() -> {
                    if (current.getAsBoolean()) applied.add(effects);
                });
            }
        });
        provider.clear();
        provider.enqueue(CompletableFuture.completedFuture(sceneSuccess()));
        assertEquals(SoulGroupConversationService.Submission.SCENE_STARTED, service.submit(turn).get(2, SECONDS));
        assertEquals(1, serverTasks.size());
        GroupScenePlayback.PlayableScene scene = player.enqueued.get(0);
        service.sceneFinished(scene.token(), 0, scene.lines().size());
        service.cancelForBot(JAKE_ID);
        serverTasks.forEach(Runnable::run);
        assertTrue(applied.isEmpty());
    }

    @Test
    void closingReplacedPipelineRejectsLateProviderResult() throws Exception {
        provider.clear();
        provider.ignoreCancellation = true;
        CompletableFuture<SoulTypes.ProviderResult> late = new CompletableFuture<>();
        provider.enqueue(late);
        CompletableFuture<SoulGroupConversationService.Submission> submitted = service.submit(turn);
        assertTrue(provider.started.await(2, SECONDS));
        service.close();
        late.complete(sceneSuccess());
        assertEquals(SoulGroupConversationService.Submission.FAILED, submitted.get(2, SECONDS));
        assertTrue(player.enqueued.isEmpty());
        assertEquals(SoulGroupConversationService.Submission.FAILED,
                service.submit(sceneTurn("old pipeline")).get(2, SECONDS));
    }

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
        private final List<SoulTypes.ProviderRequest> requests = new CopyOnWriteArrayList<>();
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        boolean ignoreCancellation;

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
            started.countDown();
            return new Call(result, () -> {
                if (!ignoreCancellation) result.cancel(false);
            });
        }

        @Override public CompletableFuture<Boolean> health() {
            return CompletableFuture.completedFuture(true);
        }

        @Override public void close() { }
    }
}
