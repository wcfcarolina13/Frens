package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.souls.voice.DreamsleeveVoiceEngine;
import net.wcfcarolina13.GameAI.souls.voice.PiperVoiceEngine;
import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceEngine;
import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceGate;
import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceService;
import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceSettings;
import net.wcfcarolina13.network.SoulVoicePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
                             SoulConversationService conversationService,
                             SoulVoiceService voice,
                             SoulGroupConversationService groupService) {
    }

    /** Public API contract consumed by later tasks' chat/command routing. */
    public record Status(boolean systemEnabled, boolean settingsValid, boolean ready,
                          String provider, String model, String ollamaHost, boolean providerHealthy,
                          int queueDepth, UUID botId, String profileId,
                          boolean profileActive, long conversationEpoch) {
    }

    private final SoulStore store;
    /** Party-channel transcripts — a second SoulStore rooted at {@code <world>/frens/party/v1}.
     *  The test seam aliases it to {@code store}; only {@link #start} opens the real party root. */
    private final SoulStore partyStore;
    private final SoulConversationService.Delivery delivery;
    private final SoulVoiceService.VoiceDelivery voiceDelivery;
    private final SoulPromptAssembler promptAssembler = new SoulPromptAssembler();
    private final SoulResponseValidator validator = new SoulResponseValidator();
    private final SoulGroupPromptAssembler groupPromptAssembler = new SoulGroupPromptAssembler();
    private final SoulGroupResponseValidator groupValidator = new SoulGroupResponseValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<Pipeline> pipelineRef;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile SoulVoiceSettings voiceSettings = SoulVoiceSettings.from(null);
    /** Scene playback machine; production-only (created by {@link #start}), null in the test seam. */
    private volatile GroupScenePlayback scenePlayback;
    /** Autonomous banter scheduler; production-only, null in the test seam. */
    private volatile SoulBanterDirector banterDirector;
    /** Ambient/local-chat scheduler; production-only, null in the test seam. */
    private volatile SoulLocalDirector localDirector;

    // Serializes every pipeline-lifecycle transition (reload, reset's invalidation read, and
    // shutdown) against this one instance's own `stopped` flag. Without it, `stop()` reading and
    // closing "the current pipeline" and `reloadSettings()` swapping in a brand-new one are two
    // unsynchronized read-modify-write sequences on the same `pipelineRef` -- either can win the
    // race to actually be "current" after the other's close, leaking whichever pipeline object
    // never gets closed. Every method that touches `pipelineRef` for anything but a plain,
    // point-in-time read (`reloadSettings`, `reset`'s invalidation, `shutdown`) takes this lock;
    // cheap, non-blocking, no I/O ever happens while holding it.
    private final Object lifecycleLock = new Object();
    private volatile boolean stopped;

    /**
     * Package-private test seam: wires a fully-formed pipeline directly instead of going through
     * {@link #buildPipeline}, so a test can inject mocks for {@code provider}/{@code scheduler}/
     * {@code conversationService}. Delivery defaults to an inert no-op since production delivery
     * is only ever built by {@link #start} against a real {@link MinecraftServer}.
     */
    SoulRuntime(SoulSettings settings, SoulStore store, SoulModelProvider provider,
                SoulGenerationScheduler scheduler, SoulConversationService conversationService) {
        this.store = Objects.requireNonNull(store, "store");
        // Test seam only: no world root exists here, so the party store aliases the DM store.
        // Group turns still fail closed in this configuration (see submitGroupTurn).
        this.partyStore = store;
        this.delivery = NO_OP_DELIVERY;
        this.voiceDelivery = (playerId, correlationId, botId, mode, sampleRate, chunks, groupId, segmentIndex) -> { };
        this.pipelineRef = new AtomicReference<>(new Pipeline(
                Objects.requireNonNull(settings, "settings"),
                Objects.requireNonNull(provider, "provider"),
                Objects.requireNonNull(scheduler, "scheduler"),
                Objects.requireNonNull(conversationService, "conversationService"),
                SoulVoiceService.disabled(),
                null));
    }

    private SoulRuntime(SoulSettings initialSettings, SoulVoiceSettings initialVoiceSettings, SoulStore store,
                         SoulStore partyStore, SoulConversationService.Delivery delivery,
                         SoulVoiceService.VoiceDelivery voiceDelivery) {
        this.store = Objects.requireNonNull(store, "store");
        this.partyStore = Objects.requireNonNull(partyStore, "partyStore");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.voiceDelivery = Objects.requireNonNull(voiceDelivery, "voiceDelivery");
        this.voiceSettings = Objects.requireNonNull(initialVoiceSettings, "voiceSettings");
        this.pipelineRef = new AtomicReference<>(buildPipeline(initialSettings, initialVoiceSettings));
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
            SoulVoiceSettings voiceSettings = SoulVoiceSettings.from(config);
            Path worldRoot = server.getSavePath(WorldSavePath.ROOT);
            SoulStore store = new SoulStore(worldRoot);
            SoulStore partyStore = SoulStore.openAt(
                    worldRoot.resolve("frens").resolve("party").resolve("v1"));
            // Soul replies deliberately ignore the Text Chat master (Bradley's ruling
            // 2026-08-25): a direct conversation should stay visible even when generic
            // bot dialogue text is off. The textEnabled seam in SoulMessageDelivery
            // stays available for a future per-category text menu.
            SoulConversationService.Delivery delivery = new SoulMessageDelivery(server,
                    new SoulMessageDelivery.ProductionDeliveryGuard(server, store,
                            () -> current().map(SoulRuntime::isMasterEnabled).orElse(false)));

            SoulVoiceService.VoiceDelivery voiceDelivery = (playerId, correlationId, botId, mode, sampleRate, chunks, groupId, segmentIndex) ->
                    server.execute(() -> {
                        net.minecraft.server.network.ServerPlayerEntity player =
                                server.getPlayerManager().getPlayer(playerId);
                        if (player == null) {
                            return;
                        }
                        byte modeByte = mode == SoulVoiceGate.Mode.POSITIONAL
                                ? SoulVoicePayload.MODE_POSITIONAL : SoulVoicePayload.MODE_RADIO;
                        for (int i = 0; i < chunks.size(); i++) {
                            ServerPlayNetworking.send(player, new SoulVoicePayload(
                                    correlationId, botId, modeByte, sampleRate, i, chunks.size(), chunks.get(i),
                                    groupId, segmentIndex));
                        }
                    });

            SoulRuntime runtime = new SoulRuntime(settings, voiceSettings, store, partyStore,
                    delivery, voiceDelivery);
            // Ambient-category gates for BANTER scenes (banter spec D6): lazy lambdas so this
            // class never class-loads Frens/ChatUtils outside a live game. PLAYER scenes ignore
            // these (soul exemption).
            java.util.function.BooleanSupplier ambientTextOpen = () ->
                    net.wcfcarolina13.ChatUtils.TextLineVisibilityService.isTextAllowed(
                            net.wcfcarolina13.ChatUtils.VoiceLineCategory.AMBIENT_CHATTER);
            java.util.function.BooleanSupplier ambientVoiceOpen = () -> {
                ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                return cfg == null || (cfg.isVoicedDialogueEnabled() && !cfg.isVoiceCategoryMuted(
                        net.wcfcarolina13.ChatUtils.VoiceLineCategory.AMBIENT_CHATTER.id()));
            };

            // The playback machine reads the CURRENT pipeline's voice/group service through the
            // runtime, so a settings reload swapping the pipeline never leaves it holding a
            // closed voice engine or a displaced group service.
            runtime.scenePlayback = new GroupScenePlayback(server,
                    () -> runtime.pipelineRef.get().voice(), voiceDelivery,
                    new GroupScenePlayback.LineCommitter() {
                        @Override
                        public void commitLine(SoulTypes.TurnToken token, int participantIndex,
                                                String taggedLine) {
                            SoulGroupConversationService groupService =
                                    runtime.pipelineRef.get().groupService();
                            if (groupService != null) {
                                groupService.commitLine(token, participantIndex, taggedLine);
                            }
                        }

                        @Override
                        public void sceneFinished(SoulTypes.TurnToken token, int deliveredLines,
                                                   int totalLines) {
                            SoulGroupConversationService groupService =
                                    runtime.pipelineRef.get().groupService();
                            if (groupService != null) {
                                groupService.sceneFinished(token, deliveredLines, totalLines);
                            }
                        }

                        @Override
                        public void sceneDelivered(SoulGroupTypes.GroupSceneTurn turn, int deliveredLines) {
                            // Always notify the director for a LOCAL scene, even with zero
                            // deliveries (fix round 1 FIX 2) -- noteSceneDelivered itself decides
                            // whether to open a window; deliveredLines == 0 (generation failed, or
                            // muted on every surface) still consumes the pending-continuation flag
                            // so it never outlives its own scene, but never opens a window.
                            if (turn.kind() == SoulGroupTypes.SceneKind.LOCAL) {
                                SoulLocalDirector local = runtime.localDirector;
                                if (local != null && !turn.roster().isEmpty()) {
                                    local.noteSceneDelivered(turn.ownerId(), turn.roster().get(0).botId(),
                                            deliveredLines);
                                }
                            }
                        }
                    }, ambientTextOpen, ambientVoiceOpen);
            runtime.banterDirector = new SoulBanterDirector(runtime, server,
                    () -> {
                        ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                        return cfg != null && cfg.isSoulBanterEnabled();
                    },
                    ambientTextOpen, ambientVoiceOpen,
                    srv -> net.wcfcarolina13.GameAI.BotEventHandler.getRegisteredBots(srv),
                    System::currentTimeMillis, new java.util.Random());
            runtime.localDirector = new SoulLocalDirector(runtime, server,
                    () -> {
                        ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                        return cfg != null && cfg.isSoulLocalChatEnabled();
                    },
                    ambientTextOpen, ambientVoiceOpen,
                    srv -> net.wcfcarolina13.GameAI.BotEventHandler.getRegisteredBots(srv),
                    System::currentTimeMillis, new java.util.Random());
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
    /**
     * Player-activity notes for Option C awareness. Static facades so Frens' event hooks stay
     * one-liners; no-ops cost a map put even when souls are disabled (negligible, and keeps the
     * hooks free of runtime-presence branching).
     */
    public static void notePlayerBlockBreak(net.minecraft.server.network.ServerPlayerEntity player,
                                            net.minecraft.block.BlockState state) {
        try {
            SoulPlayerActivity.noteBlockBreak(player.getUuid(),
                    state.getBlock().getName().getString(), System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    public static void notePlayerAttack(net.minecraft.server.network.ServerPlayerEntity player,
                                        net.minecraft.entity.Entity target) {
        try {
            SoulPlayerActivity.noteAttack(player.getUuid(),
                    target.getName().getString(), System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    /** Capture-side peek at cached knowledge memory; empty when souls are off or not yet loaded. */
    static Optional<SoulTypes.KnowledgeMemory> peekKnowledgeMemory(UUID botId) {
        SoulRuntime runtime = INSTANCE.get();
        return runtime == null ? Optional.empty() : runtime.store.cachedKnowledgeMemory(botId);
    }

    /** Fire-and-forget removal of disproven remembered places. */
    static void disprovePlaces(UUID botId, java.util.Set<String> positionKeys) {
        SoulRuntime runtime = INSTANCE.get();
        if (runtime != null && !positionKeys.isEmpty()) {
            runtime.store.removePlaces(botId, positionKeys).exceptionally(ex -> null);
        }
    }

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

    /**
     * Stable cross-mod probe: soul generation calls currently queued or active, 0 when the
     * runtime is not running. LoadGoverner reflects this exact static signature
     * ({@code com.stoneba.loadgoverner.integrations.FrensSoulProbe}) to hold a mitigation-stage
     * floor while an LLM generation contends with the game for the GPU — do not rename or change
     * the signature without updating that probe. Same plain-read discipline as
     * {@link #isMasterEnabled()}: no lock, cheap enough for a once-per-second poll.
     */
    public static int activeGenerations() {
        SoulRuntime runtime = INSTANCE.get();
        if (runtime == null) {
            return 0;
        }
        Pipeline pipeline = runtime.pipelineRef.get();
        // Include active TTS renders: the synthesis window (8-10s on Metal) starts AFTER the
        // LLM scheduler frees its slot, and it is the heaviest GPU contention of the whole
        // turn — without this the LoadGoverner floor dropped exactly when it mattered most.
        // Playing group scenes count too: their per-line renders arrive in bursts across the
        // whole playback window, and the floor must hold between lines as well.
        GroupScenePlayback playback = runtime.scenePlayback;
        int activeScenes = playback == null ? 0 : playback.activeSceneCount();
        return pipeline.scheduler().inFlightCount() + pipeline.voice().activeSyntheses() + activeScenes;
    }

    /** Package-private test seam: installs {@code runtime} without going through {@link #start}. */
    static void installForTest(SoulRuntime runtime) {
        INSTANCE.set(runtime);
    }

    private void shutdown() {
        synchronized (lifecycleLock) {
            if (stopped) {
                // Only the static stop() calls this, and it already dedupes via
                // INSTANCE.getAndSet(null) -- this guard is a defensive invariant, not a live
                // race, but keeps shutdown() itself idempotent if that ever changes.
                return;
            }
            stopped = true;
            closePipeline(pipelineRef.get());
        }
        GroupScenePlayback playback = scenePlayback;
        if (playback != null) {
            playback.cancelAll();
        }
        // SoulLocalMemory is a process-wide static ring (unlike localDirector's per-player maps,
        // which are instance state on this runtime and are simply discarded with it), so it needs
        // an explicit sweep here — mirrors SoulPlayerActivity.clear(), which has no production
        // call site of its own (grepped for one; only a test resets it directly).
        SoulLocalMemory.clear();
        store.close();
        if (partyStore != store) {
            partyStore.close();
        }
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

    /**
     * Submits {@code turn} to the currently installed pipeline's {@link SoulConversationService}.
     *
     * <p>Reads {@link #pipelineRef} at call time, the same plain-read discipline
     * {@link #isMasterEnabled()}/{@link #safeValidationError()} already use -- this is
     * deliberately <em>not</em> taken under {@link #lifecycleLock}, since holding that lock across
     * an actual submit (which schedules async work) would serialize unrelated turns against every
     * lifecycle transition for no benefit. A submit racing a concurrent {@code stop()}/
     * {@code reloadSettings()} lands on whichever pipeline was current at the moment of this read;
     * the queued/active call itself is still safely torn down by that pipeline's own
     * {@code close()} (see {@link SoulGenerationScheduler#close()}) if it loses the race.
     *
     * <p>Fails closed with {@link SoulConversationService.Submission#FAILED} -- without touching
     * the pipeline at all -- when the runtime is stopped or the current settings are not enabled
     * and valid; a caller in either state has nothing live to submit to.
     */
    public CompletableFuture<SoulConversationService.Submission> submitTurn(SoulTypes.AcceptedTurn turn) {
        Objects.requireNonNull(turn, "turn");
        if (stopped || !isConversationEnabled()) {
            return CompletableFuture.completedFuture(SoulConversationService.Submission.FAILED);
        }
        return pipelineRef.get().conversationService().submit(turn);
    }

    /**
     * Submits a group-scene turn to the currently installed pipeline's group service. Same
     * plain-read discipline and fail-closed shape as {@link #submitTurn}; additionally fails
     * closed when no group service exists (the test-seam pipeline, or no playback machine).
     */
    public CompletableFuture<SoulGroupConversationService.Submission> submitGroupTurn(
            SoulGroupTypes.GroupSceneTurn turn) {
        Objects.requireNonNull(turn, "turn");
        if (stopped || !isConversationEnabled()) {
            return CompletableFuture.completedFuture(SoulGroupConversationService.Submission.FAILED);
        }
        SoulGroupConversationService groupService = pipelineRef.get().groupService();
        if (groupService == null || scenePlayback == null) {
            return CompletableFuture.completedFuture(SoulGroupConversationService.Submission.FAILED);
        }
        if (turn.kind() == SoulGroupTypes.SceneKind.PLAYER) {
            // A real conversation re-arms both ambient cooldowns — they yield to the player.
            SoulBanterDirector banter = banterDirector;
            if (banter != null) {
                banter.notePlayerScene(turn.ownerId());
            }
            SoulLocalDirector local = localDirector;
            if (local != null) {
                local.notePlayerScene(turn.ownerId());
            }
        } else if (turn.kind() == SoulGroupTypes.SceneKind.LOCAL) {
            SoulBanterDirector banter = banterDirector;
            if (banter != null) {
                banter.notePlayerScene(turn.ownerId());
            }
        } else if (turn.kind() == SoulGroupTypes.SceneKind.BANTER) {
            SoulLocalDirector local = localDirector;
            if (local != null) {
                local.notePlayerScene(turn.ownerId());
            }
        }
        return groupService.submit(turn);
    }

    /**
     * Banter-director budget probe: true only when no soul generation or TTS render is active
     * anywhere AND no scene is playing for {@code ownerId} — an autonomous scene never queues
     * behind anything (banter spec §3 item 3).
     */
    boolean isSceneBudgetFree(UUID ownerId) {
        if (activeGenerations() > 0) {
            return false;
        }
        GroupScenePlayback playback = scenePlayback;
        return playback == null || !playback.hasActiveScene(ownerId);
    }

    /** Banter seed input: a bot's recent witnessed events from the DM store's per-bot journal. */
    CompletableFuture<List<SoulTypes.SoulEvent>> recentEventsForBanter(UUID botId, int maxRecords) {
        return store.recentEvents(botId, maxRecords);
    }

    /**
     * Archives and bumps the actor's party-channel epoch, cancels any playing scene, and
     * invalidates queued/in-flight scene generations — the party-channel analogue of
     * {@link #reset}, with the same current-pipeline-under-lock invalidation rule.
     */
    public CompletableFuture<Long> resetParty(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        SoulTypes.ConversationKey key = SoulGroupTypes.partyKey(ownerId);
        GroupScenePlayback playback = scenePlayback;
        if (playback != null) {
            playback.cancelOwner(ownerId);
        }
        return partyStore.archiveAndReset(key).whenComplete((newEpoch, err) -> {
            if (err == null) {
                synchronized (lifecycleLock) {
                    SoulGroupConversationService groupService = pipelineRef.get().groupService();
                    if (groupService != null) {
                        groupService.invalidate(key, newEpoch);
                    }
                }
            }
        });
    }

    /**
     * Server-tick facade for scene playback, registered on END_SERVER_TICK by {@code Frens}.
     * Cheap no-op when no runtime is installed or no scene is active.
     */
    public static void tickScenes(MinecraftServer server) {
        SoulRuntime runtime = INSTANCE.get();
        if (runtime == null) {
            return;
        }
        GroupScenePlayback playback = runtime.scenePlayback;
        if (playback != null) {
            playback.tick();
        }
        SoulBanterDirector director = runtime.banterDirector;
        if (director != null) {
            director.tick();
        }
        SoulLocalDirector local = runtime.localDirector;
        if (local != null) {
            local.tick();
        }
    }

    /**
     * Quiet-window signal for the banter director: notes a player's public chat line. Static
     * facade so the Frens chat callback stays a one-liner; safe no-op on any failure. Every
     * typed line — plain chat, DM turns, group-scene triggers — arrives through that callback,
     * so one timestamp covers all "the player is actively conversing" cases.
     */
    public static void notePlayerChat(net.minecraft.server.network.ServerPlayerEntity player) {
        try {
            if (player != null) {
                SoulPlayerActivity.noteChat(player.getUuid(), System.currentTimeMillis());
            }
        } catch (Throwable ignored) {
        }
    }

    /** For {@code /bot soul banter status}. */
    public String banterStatus(UUID playerId) {
        SoulBanterDirector director = banterDirector;
        return director == null ? "Banter director not running." : director.statusFor(playerId);
    }

    /**
     * Records one unaddressed chat line into the overhear ring and offers it to the local
     * director. Observational only — this never consumes the chat line (local-chat spec §3).
     */
    public static void noteUnaddressedChat(net.minecraft.server.network.ServerPlayerEntity player,
                                            String line) {
        try {
            SoulRuntime runtime = INSTANCE.get();
            if (runtime == null || player == null || line == null || line.isBlank()) {
                return;
            }
            ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
            if (cfg == null || !cfg.isSoulLocalChatEnabled()) {
                return; // gated at the write: nothing recorded, nothing to read
            }
            SoulLocalDirector director = runtime.localDirector;
            if (director != null) {
                director.noteUnaddressedChat(player, line);
            }
        } catch (Throwable ignored) {
        }
    }

    /** An explicit address closes any open reply window (local-chat spec §7). */
    public static void noteAddressedChat(net.minecraft.server.network.ServerPlayerEntity player) {
        try {
            SoulRuntime runtime = INSTANCE.get();
            if (runtime != null && player != null) {
                SoulLocalDirector director = runtime.localDirector;
                if (director != null) {
                    director.noteAddressedChat(player.getUuid());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Disconnect-time cleanup for a real player's local-chat state: drops their overheard ring
     * ({@link SoulLocalMemory#forget}) AND the director's own per-player state — cooldown,
     * verdict/score history, reply window, and pending-continuation flag — via
     * {@link SoulLocalDirector#forget}. Both are needed: the director holds the player's last
     * chat line and open reply window, which {@code SoulLocalMemory} does not.
     */
    public static void forgetPlayerLocalMemory(UUID playerId) {
        try {
            SoulLocalMemory.forget(playerId);
            SoulRuntime runtime = INSTANCE.get();
            if (runtime != null) {
                SoulLocalDirector director = runtime.localDirector;
                if (director != null) {
                    director.forget(playerId);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** For {@code /bot soul local status}. */
    public String localStatus(UUID playerId) {
        SoulLocalDirector director = localDirector;
        return director == null ? "Local chat director not running." : director.statusFor(playerId);
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
     *
     * <p>Building the new pipeline happens outside {@link #lifecycleLock} (no shared state
     * touched, no reason to hold the lock across it); installing it and closing whatever it
     * displaces happens under the lock together with a {@link #stopped} check, so a
     * {@link #stop()} racing this call can never be left with a freshly built pipeline that
     * nothing ever closes -- either this call sees {@code stopped} already true and closes the
     * pipeline it just built without installing it, or it wins the lock first and installs it,
     * in which case {@code stop()} (blocked on the same lock) closes exactly that one once it
     * proceeds.
     */
    public CompletableFuture<Void> reloadSettings(ManualConfig config) {
        SoulSettings newSettings = SoulSettings.from(config);
        SoulVoiceSettings newVoiceSettings = SoulVoiceSettings.from(config);
        Pipeline built = buildPipeline(newSettings, newVoiceSettings);
        synchronized (lifecycleLock) {
            if (stopped) {
                closePipeline(built);
                return CompletableFuture.completedFuture(null);
            }
            voiceSettings = newVoiceSettings;
            closePipeline(pipelineRef.getAndSet(built));
        }
        return CompletableFuture.completedFuture(null);
    }

    private Pipeline buildPipeline(SoulSettings settings, SoulVoiceSettings voiceSettings) {
        warnIfNonLocalOllamaHost(settings);
        SoulModelProvider provider =
                new OllamaSoulProvider(settings.ollamaBaseUri(), settings.model(), objectMapper);
        SoulGenerationScheduler scheduler = new SoulGenerationScheduler(1, settings.queueCapacity());
        SoulVoiceService voice = buildVoiceService(voiceSettings);
        SoulConversationService conversationService = new SoulConversationService(
                store, promptAssembler, scheduler, provider, validator, delivery, settings, voice);
        SoulGroupConversationService groupService = new SoulGroupConversationService(
                partyStore, groupPromptAssembler, scheduler, provider, groupValidator, settings,
                new SoulGroupConversationService.ScenePlayer() {
                    @Override
                    public void enqueue(GroupScenePlayback.PlayableScene scene) {
                        GroupScenePlayback playback = scenePlayback;
                        if (playback != null) {
                            playback.enqueue(scene);
                        }
                    }

                    @Override
                    public boolean hasActiveScene(UUID ownerId) {
                        GroupScenePlayback playback = scenePlayback;
                        return playback != null && playback.hasActiveScene(ownerId);
                    }
                },
                delivery::deliverStatus);
        return new Pipeline(settings, provider, scheduler, conversationService, voice, groupService);
    }

    private SoulVoiceService buildVoiceService(SoulVoiceSettings voiceSettings) {
        if (!voiceSettings.enabled() || !voiceSettings.valid()) {
            return SoulVoiceService.disabled();
        }
        try {
            SoulVoiceEngine engine = switch (voiceSettings.engine()) {
                case SoulVoiceSettings.ENGINE_DREAMSLEEVE -> new DreamsleeveVoiceEngine(
                        voiceSettings.dreamsleeveDir(), voiceSettings.refAudio(),
                        voiceSettings.refText(), voiceSettings.synthTimeoutMs());
                default -> new PiperVoiceEngine(voiceSettings.piperBinary(),
                        voiceSettings.voiceModel(), voiceSettings.synthTimeoutMs());
            };
            // Global "Voice" toggle is the master over soul TTS as well as baked lines.
            return new SoulVoiceService(voiceSettings, engine, voiceDelivery, () -> {
                ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                return cfg == null || cfg.isVoicedDialogueEnabled();
            });
        } catch (Exception ex) {
            LOGGER.warn("[souls] tts engine unavailable, voice disabled: {}", ex.toString());
            return SoulVoiceService.disabled();
        }
    }

    /**
     * Logs one warning when {@code settings} are enabled and valid but the configured Ollama host
     * is not local -- soul turns (private player chat plus game-context facts) would then leave
     * this machine over the network. Silent for disabled/invalid settings, since nothing is ever
     * dispatched in that case regardless of host.
     */
    private static void warnIfNonLocalOllamaHost(SoulSettings settings) {
        if (!settings.enabled() || !settings.valid()) {
            return;
        }
        String hostPort = formatHostPort(settings.ollamaBaseUri());
        if (!isLoopbackHost(settings.ollamaBaseUri().getHost())) {
            LOGGER.warn("[souls] Soul conversations will be sent to non-local Ollama host {} -- "
                    + "private chat and game context leave this machine.", hostPort);
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("127.0.0.1") || normalized.equals("localhost")
                || normalized.equals("::1") || normalized.equals("[::1]");
    }

    /** {@code host[:port]} only -- never the scheme, path, or any credentials. */
    private static String formatHostPort(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return "";
        }
        return uri.getPort() >= 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
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
        try {
            pipeline.voice().close();
        } catch (RuntimeException ex) {
            LOGGER.warn("[souls] voice close failed: {}", ex.toString());
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

    /**
     * Archives and bumps {@code key}'s epoch, then invalidates whatever conversation service is
     * <em>currently</em> installed at that moment -- not whichever one happened to be installed
     * when {@code reset} was first called. The archive itself can take a while (filesystem I/O on
     * the store's writer thread) and a live {@code reloadSettings} can swap the pipeline out from
     * under an in-flight reset; reading {@code pipelineRef} inside {@code whenComplete}, under the
     * same {@link #lifecycleLock} {@code reloadSettings}/{@code stop} use, guarantees the
     * invalidation always lands on whichever service is actually live for {@code key} right now,
     * never a stale one a concurrent reload already displaced (and closed).
     */
    public CompletableFuture<Long> reset(SoulTypes.ConversationKey key) {
        Objects.requireNonNull(key, "key");
        return store.archiveAndReset(key).whenComplete((newEpoch, err) -> {
            if (err == null) {
                synchronized (lifecycleLock) {
                    pipelineRef.get().conversationService().invalidate(key, newEpoch);
                }
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
            String cursorKey = SoulStore.cursorKey(
                    new SoulTypes.ConversationKey(botId, playerId, SoulTypes.Channel.DIRECT));
            long epoch = state.conversations()
                    .getOrDefault(cursorKey, new SoulTypes.ConversationCursor(0L, 0L)).epoch();
            return new Status(pipeline.settings().enabled(), pipeline.settings().valid(), isReady(),
                    pipeline.settings().provider(), pipeline.settings().model(),
                    formatHostPort(pipeline.settings().ollamaBaseUri()), healthy,
                    pipeline.scheduler().queueDepth(), botId, state.profileId(), state.active(), epoch);
        });
    }

    /** Whether the currently installed pipeline's TTS engine is alive and usable right now. */
    public boolean voiceEngineAlive() {
        return pipelineRef.get().voice().engineAlive();
    }

    /** Current soul-voice settings snapshot, refreshed by {@link #reloadSettings}. */
    public SoulVoiceSettings voiceSettings() {
        return voiceSettings;
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
     *
     * <p>Skips the store write entirely when the master switch is off AND nothing is cached for
     * {@code botId}: a souls-disabled install (or a bot the souls system never touched) has no
     * live pipeline work to cancel and no persisted state to deactivate, so this must not touch
     * the store at all -- {@link SoulStore#setActive} itself refuses to synthesize a fresh
     * soul.json on disk for exactly this case, but skipping the call here avoids even queuing the
     * no-op write. When a profile IS cached (or the master switch is on), the store write still
     * runs unconditionally, same as before -- this never skips deactivating a bot that actually
     * has state to deactivate.
     */
    public void cancelBot(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        if (!isMasterEnabled() && cachedState(botId).isEmpty()) {
            return;
        }
        store.setActive(botId, false).exceptionally(ex -> {
            LOGGER.warn("[souls] cancelBot failed to deactivate {}: {}", botId, ex.toString());
            return null;
        });
    }

    /**
     * Disconnect-time hook for a real player: cancels the player's queued and active soul
     * generations so no GPU time (or LoadGoverner stage floor, via
     * {@link #activeGenerations()}) is spent finishing a reply that can no longer be delivered.
     * {@link SoulMessageDelivery.ProductionDeliveryGuard} already fails closed once the player
     * no longer resolves via the player manager — this stops the wasted generation itself, not
     * just its delivery. No separate per-player registry is needed: the scheduler's own
     * queue/active state is keyed by {@link SoulTypes.ConversationKey}, which carries the player
     * id ({@link SoulGenerationScheduler#cancelForPlayer}). Reads {@code pipelineRef} with the
     * same plain-read discipline as {@link #submitTurn}: a cancel racing a concurrent reload
     * lands on whichever scheduler is current, and the displaced pipeline's own {@code close()}
     * tears down anything it still held.
     */
    public void cancelPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        int cancelled = pipelineRef.get().scheduler().cancelForPlayer(playerId);
        if (cancelled > 0) {
            LOGGER.info("[souls] cancelPlayer player={} cancelledGenerations={}", playerId, cancelled);
        }
        // The party key's playerId IS the owner, so the scheduler sweep above already covers
        // queued/active scene generations; this stops a scene that is mid-playback.
        GroupScenePlayback playback = scenePlayback;
        if (playback != null) {
            playback.cancelOwner(playerId);
        }
    }
}
