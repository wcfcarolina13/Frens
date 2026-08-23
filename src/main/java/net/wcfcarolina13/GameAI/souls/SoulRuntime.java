package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One per-{@link MinecraftServer} soul-communication runtime.
 *
 * <p>Owns the world-local {@link SoulStore}, the async index preload that gates readiness, and
 * the current generation pipeline — a {@link SoulSettings} snapshot bundled with the
 * {@link SoulModelProvider}, {@link SoulGenerationScheduler}, and {@link SoulConversationService}
 * built from it. The pipeline is replaced as one atomic unit by {@link #reloadSettings}, which
 * always rebuilds it (even when the new settings are disabled or invalid) so storage/status stay
 * backed by a consistent object graph at all times; only {@link #isConversationEnabled()} gates
 * whether it is ever actually used to generate a reply. {@link #isReady()} gates a second,
 * independent concern: whether {@link #preloadIndex()} has finished warming
 * {@link SoulStore#cachedState(UUID)}, so a router built on top of this class always has a
 * deterministic "still loading" answer instead of racing a legacy fallback.
 *
 * <p>Exactly one instance is installed at a time, tracked in a static {@link AtomicReference} —
 * mirroring the rest of the mod's per-server singleton services ({@code BotRegistry},
 * {@code TaskService}, ...) rather than being threaded explicitly through every call site.
 * {@link #start} and {@link #stop} are the only production callers that touch that static slot;
 * {@link #installForTest} exists purely so a unit test can exercise {@link #stop} without a real
 * {@link MinecraftServer}.
 *
 * <p>This class takes {@link MinecraftServer} and {@link ManualConfig} only as method parameters,
 * never as static state, and never references {@code Frens} — so neither it nor its test ever
 * trips {@code Frens}'s static initializer, which fails outside a running game (see
 * {@code SoulConversationService}'s Javadoc for the same rule).
 */
public final class SoulRuntime {

    // A dedicated logger (never Frens.LOGGER) -- see the class Javadoc above.
    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    private static final AtomicReference<SoulRuntime> INSTANCE = new AtomicReference<>();

    /** Inert placeholder used only by the test-seam constructor, which never has a real server. */
    private static final SoulConversationService.Delivery NO_OP_DELIVERY = new SoulConversationService.Delivery() {
        @Override
        public CompletableFuture<Boolean> deliverReply(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token,
                                                         String text) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public void deliverStatus(UUID playerId, String text) {
            // No real server to deliver to outside production -- intentionally inert.
        }
    };

    private record Pipeline(SoulSettings settings, SoulModelProvider provider,
                             SoulGenerationScheduler scheduler,
                             SoulConversationService conversationService) {
    }

    /** Public API contract consumed by later tasks' chat/command routing. */
    public record Status(boolean systemEnabled, boolean settingsValid, boolean ready,
                          String provider, String model, boolean providerHealthy,
                          int queueDepth, UUID botId, String profileId,
                          boolean profileActive, long conversationEpoch) {
    }

    private final SoulStore store;
    private final SoulConversationService.Delivery delivery;
    private final SoulPromptAssembler promptAssembler = new SoulPromptAssembler();
    private final SoulResponseValidator validator = new SoulResponseValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<Pipeline> pipelineRef;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    /**
     * Package-private test seam: wires a fully-formed pipeline directly instead of going through
     * {@link #buildPipeline}, so a test can inject mocks for {@code provider}/{@code scheduler}/
     * {@code conversationService}. Delivery defaults to an inert no-op since production delivery
     * is only ever built by {@link #start} against a real {@link MinecraftServer}.
     */
    SoulRuntime(SoulSettings settings, SoulStore store, SoulModelProvider provider,
                SoulGenerationScheduler scheduler, SoulConversationService conversationService) {
        this.store = Objects.requireNonNull(store, "store");
        this.delivery = NO_OP_DELIVERY;
        this.pipelineRef = new AtomicReference<>(new Pipeline(
                Objects.requireNonNull(settings, "settings"),
                Objects.requireNonNull(provider, "provider"),
                Objects.requireNonNull(scheduler, "scheduler"),
                Objects.requireNonNull(conversationService, "conversationService")));
    }

    private SoulRuntime(SoulSettings initialSettings, SoulStore store,
                         SoulConversationService.Delivery delivery) {
        this.store = Objects.requireNonNull(store, "store");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.pipelineRef = new AtomicReference<>(buildPipeline(initialSettings));
    }

    // === Static lifecycle ===

    /**
     * Installs one runtime for {@code server}: derives {@link SoulSettings} from {@code config},
     * opens the world-local {@link SoulStore} (creating no directories yet), and kicks off an
     * async {@link #preloadIndex()}. Any previously installed runtime is stopped first, so a
     * repeated {@code start} (e.g. an integrated-server world reload re-firing
     * {@code SERVER_STARTED}) never leaks the prior session's executors.
     */
    public static void start(MinecraftServer server, ManualConfig config) {
        Objects.requireNonNull(server, "server");
        stop();
        try {
            SoulProfileRegistry.loadBuiltIns();
            SoulSettings settings = SoulSettings.from(config);
            Path worldRoot = server.getSavePath(WorldSavePath.ROOT);
            SoulStore store = new SoulStore(worldRoot);
            SoulConversationService.Delivery delivery = new SoulMessageDelivery(server,
                    new SoulMessageDelivery.ProductionDeliveryGuard(server, store,
                            () -> current().map(SoulRuntime::isMasterEnabled).orElse(false)));

            SoulRuntime runtime = new SoulRuntime(settings, store, delivery);
            INSTANCE.set(runtime);
            runtime.preloadIndex().exceptionally(ex -> {
                LOGGER.warn("[souls] index preload failed: {}", ex.toString());
                return null;
            });
            LOGGER.info("[souls] runtime started; masterEnabled={} settingsValid={}",
                    settings.enabled(), settings.valid());
        } catch (RuntimeException ex) {
            LOGGER.error("[souls] failed to start soul runtime", ex);
        }
    }

    /**
     * Cancels the current pipeline's in-flight/queued generation, closes its provider, and closes
     * the world-local store's writer -- then clears the installed runtime. Every step is
     * fire-and-forget (no {@code awaitTermination}, no blocking I/O wait), so this is always safe
     * to call from the server thread during {@code SERVER_STOPPING}. Idempotent: a second call
     * with nothing installed is a no-op.
     */
    public static void stop() {
        SoulRuntime runtime = INSTANCE.getAndSet(null);
        if (runtime == null) {
            return;
        }
        runtime.shutdown();
    }

    public static Optional<SoulRuntime> current() {
        return Optional.ofNullable(INSTANCE.get());
    }

    /** Package-private test seam: installs {@code runtime} without going through {@link #start}. */
    static void installForTest(SoulRuntime runtime) {
        INSTANCE.set(runtime);
    }

    private void shutdown() {
        closePipeline(pipelineRef.get());
        store.close();
    }

    // === Readiness / status flags ===

    public boolean isMasterEnabled() {
        return pipelineRef.get().settings().enabled();
    }

    public boolean isConversationEnabled() {
        SoulSettings settings = pipelineRef.get().settings();
        return settings.enabled() && settings.valid();
    }

    /** Whether {@link #preloadIndex()} has completed at least once. */
    public boolean isReady() {
        return ready.get();
    }

    /** Whether it is currently safe to actually dispatch a generation call. */
    public boolean pipelineAvailable() {
        return isConversationEnabled() && isReady();
    }

    public String safeValidationError() {
        return pipelineRef.get().settings().validationError();
    }

    // === Store passthroughs ===

    public Optional<SoulTypes.SoulState> cachedState(UUID botId) {
        return store.cachedState(botId);
    }

    public boolean hasActiveProfile(UUID botId) {
        return store.cachedState(botId)
                .map(state -> state.active() && !state.profileId().isBlank())
                .orElse(false);
    }

    public CompletableFuture<Void> preloadIndex() {
        return store.preloadIndex().whenComplete((v, err) -> {
            if (err != null) {
                LOGGER.warn("[souls] preloadIndex failed: {}", err.toString());
            }
            ready.set(true);
        });
    }

    // === Settings reload ===

    /**
     * Rebuilds the pipeline from {@code config} and atomically swaps it in, then cancels/closes
     * the previous scheduler and provider. Runs the same way regardless of whether the new
     * settings are enabled/valid -- only {@link #isConversationEnabled()} gates whether the new
     * pipeline is ever actually used to generate a reply.
     */
    public CompletableFuture<Void> reloadSettings(ManualConfig config) {
        SoulSettings newSettings = SoulSettings.from(config);
        Pipeline previous = pipelineRef.getAndSet(buildPipeline(newSettings));
        closePipeline(previous);
        return CompletableFuture.completedFuture(null);
    }

    private Pipeline buildPipeline(SoulSettings settings) {
        SoulModelProvider provider =
                new OllamaSoulProvider(settings.ollamaBaseUri(), settings.model(), objectMapper);
        SoulGenerationScheduler scheduler = new SoulGenerationScheduler(1, settings.queueCapacity());
        SoulConversationService conversationService = new SoulConversationService(
                store, promptAssembler, scheduler, provider, validator, delivery, settings);
        return new Pipeline(settings, provider, scheduler, conversationService);
    }

    private void closePipeline(Pipeline pipeline) {
        if (pipeline == null) {
            return;
        }
        try {
            pipeline.scheduler().close();
        } catch (RuntimeException ex) {
            LOGGER.warn("[souls] scheduler close failed: {}", ex.toString());
        }
        try {
            pipeline.provider().close();
        } catch (RuntimeException ex) {
            LOGGER.warn("[souls] provider close failed: {}", ex.toString());
        }
    }

    // === Profile / activation / reset ===

    public CompletableFuture<SoulTypes.SoulState> bindJake(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        return store.bindProfile(botId, "frens:jake");
    }

    public CompletableFuture<SoulTypes.SoulState> setActive(UUID botId, boolean active) {
        Objects.requireNonNull(botId, "botId");
        if (!active) {
            cancelBot(botId);
        }
        return store.setActive(botId, active);
    }

    public CompletableFuture<Long> reset(SoulTypes.ConversationKey key) {
        Objects.requireNonNull(key, "key");
        SoulConversationService conversationService = pipelineRef.get().conversationService();
        return store.archiveAndReset(key).whenComplete((newEpoch, err) -> {
            if (err == null) {
                conversationService.invalidate(key, newEpoch);
            }
        });
    }

    public CompletableFuture<Status> status(UUID botId, UUID playerId) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(playerId, "playerId");
        Pipeline pipeline = pipelineRef.get();
        CompletableFuture<Boolean> healthFuture = pipeline.provider().health();
        CompletableFuture<SoulTypes.SoulState> stateFuture = store.state(botId);
        return healthFuture.thenCombine(stateFuture, (healthy, state) -> {
            String cursorKey = SoulTypes.Channel.DIRECT.name() + ":" + playerId;
            long epoch = state.conversations()
                    .getOrDefault(cursorKey, new SoulTypes.ConversationCursor(0L, 0L)).epoch();
            return new Status(pipeline.settings().enabled(), pipeline.settings().valid(), isReady(),
                    pipeline.settings().provider(), pipeline.settings().model(), healthy,
                    pipeline.scheduler().queueDepth(), botId, state.profileId(), state.active(), epoch);
        });
    }

    // === Events / disconnect cancellation ===

    public void recordEvent(UUID botId, SoulTypes.SoulEvent event) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(event, "event");
        store.appendEvent(botId, event).exceptionally(ex -> {
            LOGGER.warn("[souls] recordEvent failed for bot {}: {}", botId, ex.toString());
            return null;
        });
    }

    /**
     * Deactivates {@code botId} for soul communication. Called both directly (on disconnect of a
     * registered fake player) and from {@link #setActive(UUID, boolean)} when deactivating: a
     * fake player going offline makes any reply to or from it undeliverable regardless, since
     * {@link SoulMessageDelivery.ProductionDeliveryGuard} already fails closed the moment the bot
     * no longer resolves via the player manager -- this call's job is to stop any *future* turn
     * from being dispatched to it at all.
     */
    public void cancelBot(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        store.setActive(botId, false).exceptionally(ex -> {
            LOGGER.warn("[souls] cancelBot failed to deactivate {}: {}", botId, ex.toString());
            return null;
        });
    }

    /**
     * Disconnect-time hook for a real player. No per-player in-flight-generation registry exists
     * yet -- turn submission itself is wired by a later task -- and delivery already fails closed
     * once the player no longer resolves via the player manager, so there is nothing to cancel
     * today beyond leaving one explicit call site for that future turn-tracking to plug into.
     */
    public void cancelPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
    }
}
