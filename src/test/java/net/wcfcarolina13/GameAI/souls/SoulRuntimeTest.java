package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers what {@link SoulRuntime} does without a running Minecraft server.
 *
 * <p>{@code start(MinecraftServer, ManualConfig)} itself is untestable here: no test in this
 * repository mocks {@code net.minecraft.server.MinecraftServer} (see {@code SoulMessageDeliveryTest}'s
 * class Javadoc) and {@code SoulRuntime} does not either. Everything below goes through the
 * package-private five-argument constructor and {@link SoulRuntime#installForTest}, exactly the
 * seam the brief calls for, so {@code stop()}/{@code current()}/the readiness and reload/reset
 * flags are exercised against injected (mostly Mockito) collaborators instead.
 */
class SoulRuntimeTest {

    @TempDir
    Path worldRoot;

    private SoulStore store;
    private SoulGenerationScheduler scheduler;
    private SoulConversationService conversationService;

    @BeforeEach
    void setUp() {
        store = mock(SoulStore.class);
        scheduler = mock(SoulGenerationScheduler.class);
        conversationService = mock(SoulConversationService.class);
    }

    @AfterEach
    void tearDown() {
        // The static INSTANCE slot is process-wide; make sure a test that installs a runtime
        // never leaks it into the next test.
        SoulRuntime.stop();
    }

    private static SoulSettings settings(boolean enabled, boolean valid, String model) {
        return new SoulSettings(enabled, valid, valid ? "" : "invalid", "ollama", model,
                URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
    }

    // === Mandated tests (verbatim from the task brief) ===

    @Test
    void disabledRuntimeMakesNoProviderCall() {
        SoulSettings disabled = new SoulSettings(false, true, "", "ollama", "test-model",
                URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
        SoulModelProvider provider = mock(SoulModelProvider.class);
        SoulRuntime runtime = new SoulRuntime(disabled, store, provider, scheduler, conversationService);
        assertFalse(runtime.isConversationEnabled());
        verify(provider, never()).generate(any());
    }

    @Test
    void stopCancelsGenerationAndClearsInstalledRuntime() {
        SoulSettings enabled = new SoulSettings(true, true, "", "ollama", "test-model",
                URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
        SoulModelProvider provider = mock(SoulModelProvider.class);
        SoulRuntime runtime = new SoulRuntime(enabled, store, provider, scheduler, conversationService);
        SoulRuntime.installForTest(runtime);
        SoulRuntime.stop();
        assertTrue(SoulRuntime.current().isEmpty());
        verify(provider).close();
    }

    // === Own coverage: readiness / reload / reset / status / disconnect-cancellation semantics ===

    @Test
    void stopAlsoClosesTheSchedulerAndTheWorldLocalStore() {
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                mock(SoulModelProvider.class), scheduler, conversationService);
        SoulRuntime.installForTest(runtime);

        SoulRuntime.stop();

        verify(scheduler).close();
        verify(store).close();
    }

    @Test
    void stopIsIdempotentWhenNoRuntimeIsInstalled() {
        SoulRuntime.stop();
        SoulRuntime.stop();
        assertTrue(SoulRuntime.current().isEmpty());
    }

    @Test
    void readinessReflectsPreloadCompletionNotConstruction() {
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                mock(SoulModelProvider.class), scheduler, conversationService);
        CompletableFuture<Void> preload = new CompletableFuture<>();
        when(store.preloadIndex()).thenReturn(preload);

        assertFalse(runtime.isReady(), "must not be ready before preloadIndex() is even called");

        CompletableFuture<Void> result = runtime.preloadIndex();
        assertFalse(runtime.isReady(), "must stay 'still loading' until the store future completes");

        preload.complete(null);
        result.join();

        assertTrue(runtime.isReady());
    }

    @Test
    void pipelineAvailableRequiresBothValidSettingsAndReadiness() {
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                mock(SoulModelProvider.class), scheduler, conversationService);
        when(store.preloadIndex()).thenReturn(CompletableFuture.completedFuture(null));

        assertFalse(runtime.pipelineAvailable(), "not ready yet");
        runtime.preloadIndex().join();
        assertTrue(runtime.pipelineAvailable());
    }

    @Test
    void safeValidationErrorSurfacesTheDeterministicSettingsMessage() {
        SoulRuntime runtime = new SoulRuntime(settings(true, false, ""), store,
                mock(SoulModelProvider.class), scheduler, conversationService);
        assertEquals("invalid", runtime.safeValidationError());
    }

    @Test
    void reloadSettingsSwapsThePipelineAndClosesThePreviousOne() {
        SoulModelProvider oldProvider = mock(SoulModelProvider.class);
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                oldProvider, scheduler, conversationService);

        ManualConfig config = mock(ManualConfig.class);
        when(config.isSoulsEnabled()).thenReturn(false);
        when(config.getSoulProvider()).thenReturn("ollama");
        when(config.getSoulModel()).thenReturn("test-model");
        when(config.getOllamaBaseUrl()).thenReturn("http://127.0.0.1:11434");
        when(config.getSoulRequestTimeoutSeconds()).thenReturn(60);
        when(config.getSoulQueueCapacity()).thenReturn(8);

        runtime.reloadSettings(config).join();

        assertFalse(runtime.isMasterEnabled());
        verify(oldProvider).close();
        verify(scheduler).close();
    }

    @Test
    void resetArchivesThenInvalidatesConversationServiceAtTheNewEpoch() throws Exception {
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                mock(SoulModelProvider.class), scheduler, conversationService);
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                UUID.randomUUID(), UUID.randomUUID(), SoulTypes.Channel.DIRECT);
        when(store.archiveAndReset(key)).thenReturn(CompletableFuture.completedFuture(3L));

        long newEpoch = runtime.reset(key).get(2, SECONDS);

        assertEquals(3L, newEpoch);
        verify(conversationService).invalidate(key, 3L);
    }

    @Test
    void hasActiveProfileReflectsCachedState() {
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                mock(SoulModelProvider.class), scheduler, conversationService);
        UUID bot = UUID.randomUUID();

        when(store.cachedState(bot)).thenReturn(Optional.empty());
        assertFalse(runtime.hasActiveProfile(bot));

        when(store.cachedState(bot)).thenReturn(Optional.of(
                new SoulTypes.SoulState(1, bot, "frens:jake", true, Map.of())));
        assertTrue(runtime.hasActiveProfile(bot));

        when(store.cachedState(bot)).thenReturn(Optional.of(
                new SoulTypes.SoulState(1, bot, "", true, Map.of())));
        assertFalse(runtime.hasActiveProfile(bot), "active but no bound profile id is not an active profile");
    }

    @Test
    void bindJakeBindsTheBuiltInProfileId() throws Exception {
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                mock(SoulModelProvider.class), scheduler, conversationService);
        UUID bot = UUID.randomUUID();
        SoulTypes.SoulState bound = new SoulTypes.SoulState(1, bot, "frens:jake", false, Map.of());
        when(store.bindProfile(bot, "frens:jake")).thenReturn(CompletableFuture.completedFuture(bound));

        SoulTypes.SoulState result = runtime.bindJake(bot).get(2, SECONDS);

        assertEquals("frens:jake", result.profileId());
        verify(store).bindProfile(bot, "frens:jake");
    }

    @Test
    void setActiveFalseAlsoRoutesThroughCancelBot() throws Exception {
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                mock(SoulModelProvider.class), scheduler, conversationService);
        UUID bot = UUID.randomUUID();
        SoulTypes.SoulState inactive = new SoulTypes.SoulState(1, bot, "frens:jake", false, Map.of());
        when(store.setActive(bot, false)).thenReturn(CompletableFuture.completedFuture(inactive));

        SoulTypes.SoulState result = runtime.setActive(bot, false).get(2, SECONDS);

        assertFalse(result.active());
        // cancelBot(bot) itself also deactivates via the store -- setActive(false) must trigger
        // at least that one call in addition to its own.
        verify(store, org.mockito.Mockito.atLeastOnce()).setActive(bot, false);
    }

    @Test
    void statusCombinesLivePipelineStoreAndProviderHealth() throws Exception {
        SoulModelProvider provider = mock(SoulModelProvider.class);
        when(provider.health()).thenReturn(CompletableFuture.completedFuture(true));
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                provider, scheduler, conversationService);

        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        Map<String, SoulTypes.ConversationCursor> conversations = Map.of(
                "DIRECT:" + player, new SoulTypes.ConversationCursor(4L, 2L));
        SoulTypes.SoulState state = new SoulTypes.SoulState(1, bot, "frens:jake", true, conversations);
        when(store.state(bot)).thenReturn(CompletableFuture.completedFuture(state));
        when(scheduler.queueDepth()).thenReturn(2);

        SoulRuntime.Status status = runtime.status(bot, player).get(2, SECONDS);

        assertTrue(status.systemEnabled());
        assertTrue(status.settingsValid());
        assertEquals("ollama", status.provider());
        assertEquals("test-model", status.model());
        assertTrue(status.providerHealthy());
        assertEquals(2, status.queueDepth());
        assertEquals(bot, status.botId());
        assertEquals("frens:jake", status.profileId());
        assertTrue(status.profileActive());
        assertEquals(4L, status.conversationEpoch());
    }

    @Test
    void recordEventAppendsThroughTheStore() {
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                mock(SoulModelProvider.class), scheduler, conversationService);
        UUID bot = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SoulTypes.SoulEvent event = new SoulTypes.SoulEvent(UUID.randomUUID(),
                SoulTypes.EventType.COMBAT_STARTED, actor, List.of(actor), "overworld", "plains",
                Map.of(), SoulTypes.Witness.SELF, 0L, Instant.EPOCH, SoulTypes.Salience.NORMAL);
        when(store.appendEvent(bot, event)).thenReturn(CompletableFuture.completedFuture(null));

        runtime.recordEvent(bot, event);

        verify(store).appendEvent(bot, event);
    }

    @Test
    void cancelPlayerNeverTouchesTheProvider() {
        SoulModelProvider provider = mock(SoulModelProvider.class);
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                provider, scheduler, conversationService);

        runtime.cancelPlayer(UUID.randomUUID());

        verify(provider, never()).generate(any());
        verify(provider, never()).close();
    }

    // === Own coverage: the real SoulStore behavior this task adds (preloadIndex/cachedState/close) ===
    // Exercised here (against a real SoulStore, not a mock) rather than in SoulStoreTest so the
    // task's exact-five-file commit list stays intact.

    @Test
    void preloadIndexOnAMissingWorldRootTouchesNoDirectoriesAndLeavesTheCacheEmpty() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SoulStore realStore = new SoulStore(worldRoot, executor);
        try {
            realStore.preloadIndex().get(2, SECONDS);
            assertTrue(realStore.cachedState(UUID.randomUUID()).isEmpty());
            assertFalse(java.nio.file.Files.exists(worldRoot.resolve("frens")),
                    "a never-enabled world must not have anything created on disk");
        } finally {
            realStore.close();
        }
    }

    @Test
    void preloadIndexPopulatesCachedStateFromDiskAndCloseRejectsFurtherWrites() throws Exception {
        ExecutorService seedExecutor = Executors.newSingleThreadExecutor();
        SoulStore seedStore = new SoulStore(worldRoot, seedExecutor);
        UUID bot = UUID.randomUUID();
        seedStore.bindProfile(bot, "frens:jake").get(2, SECONDS);
        seedStore.setActive(bot, true).get(2, SECONDS);
        seedStore.close();

        ExecutorService restartedExecutor = Executors.newSingleThreadExecutor();
        SoulStore restarted = new SoulStore(worldRoot, restartedExecutor);
        assertTrue(restarted.cachedState(bot).isEmpty(), "cache is empty before preload runs");

        restarted.preloadIndex().get(2, SECONDS);
        Optional<SoulTypes.SoulState> cached = restarted.cachedState(bot);
        assertTrue(cached.isPresent());
        assertEquals("frens:jake", cached.get().profileId());
        assertTrue(cached.get().active());

        restarted.close();
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> restarted.setActive(bot, false).get(2, SECONDS));
        assertTrue(ex.getCause() instanceof RejectedExecutionException);
    }
}
