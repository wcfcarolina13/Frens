package net.wcfcarolina13.network;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.services.BotAccessPolicy;
import net.wcfcarolina13.GameAI.services.BotRegistry;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import net.wcfcarolina13.GameAI.souls.OllamaModelInstaller;
import net.wcfcarolina13.GameAI.souls.SoulProfileRegistry;
import net.wcfcarolina13.GameAI.souls.SoulRuntime;
import net.wcfcarolina13.GameAI.souls.SoulTypes;
import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Network surface of the "Set up AI companions" checklist: a status request/response pair and
 * one action packet, all server-authoritative.
 *
 * <p>Threads: receivers hop to the server thread, which reads live state (bots, runtime flags,
 * permissions) into a plain {@link AiSetupStatus}. The Ollama probe (blocking HTTP, up to ~10 s)
 * and {@link SoulRuntime#reloadSettings} run on this class's worker threads; their results hop
 * back with {@code server.execute} and re-resolve the player by UUID before anything is sent.
 *
 * <p>Abuse limits: one Ollama probe result is reused server-wide for
 * {@link AiSetupActionPolicy#OLLAMA_PROBE_TTL_MS}, with at most one probe in flight; each player
 * may request status once per {@link AiSetupActionPolicy#STATUS_REQUEST_INTERVAL_MS} and act once
 * per {@link AiSetupActionPolicy#ACTION_INTERVAL_MS} (excess dropped at DEBUG). A non-operator
 * gets {@link AiSetupStatus#redactedForPlayer()} and never triggers a probe.
 */
public final class AiSetupNetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.ai-setup");

    private AiSetupNetworkManager() {
    }

    // ── Payloads ────────────────────────────────────────────────────────────

    /** Client → server: send me the current setup status. {@code reason} is a short tag for logs. */
    public record AiSetupStatusRequestPayload(String reason) implements CustomPayload {
        public static final Id<AiSetupStatusRequestPayload> ID =
                new Id<>(Identifier.of("frens", "ai_setup_status_request"));
        private static final PacketCodec<PacketByteBuf, String> REASON = new StringCodec(32);
        public static final PacketCodec<PacketByteBuf, AiSetupStatusRequestPayload> CODEC =
                PacketCodec.tuple(REASON, AiSetupStatusRequestPayload::reason, AiSetupStatusRequestPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server → client: {@link AiSetupStatus#toJson()}. */
    public record AiSetupStatusPayload(String json) implements CustomPayload {
        public static final Id<AiSetupStatusPayload> ID = new Id<>(Identifier.of("frens", "ai_setup_status"));
        private static final PacketCodec<PacketByteBuf, String> JSON = new StringCodec(32767);
        public static final PacketCodec<PacketByteBuf, AiSetupStatusPayload> CODEC =
                PacketCodec.tuple(JSON, AiSetupStatusPayload::json, AiSetupStatusPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Client → server: one setup action. {@code action} is an {@link AiSetupActionPolicy.Action}
     * name; {@code arg} is the bot UUID (ENABLE_BOT), model tag (SELECT_MODEL), engine id
     * (SELECT_VOICE_ENGINE) or "".
     */
    public record AiSetupActionPayload(String action, String arg) implements CustomPayload {
        public static final Id<AiSetupActionPayload> ID = new Id<>(Identifier.of("frens", "ai_setup_action"));
        private static final PacketCodec<PacketByteBuf, String> ACTION = new StringCodec(32);
        private static final PacketCodec<PacketByteBuf, String> ARG = new StringCodec(256);
        public static final PacketCodec<PacketByteBuf, AiSetupActionPayload> CODEC = PacketCodec.tuple(
                ACTION, AiSetupActionPayload::action, ARG, AiSetupActionPayload::arg, AiSetupActionPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ── Registration (common entrypoint, once per JVM) ──────────────────────

    private static volatile boolean registered;

    /** Registers the three payload types and the server receivers. Called from {@code Frens.onInitialize}. */
    public static void registerOnce() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.playC2S().register(AiSetupStatusRequestPayload.ID, AiSetupStatusRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AiSetupStatusPayload.ID, AiSetupStatusPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AiSetupActionPayload.ID, AiSetupActionPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(AiSetupStatusRequestPayload.ID, (payload, context) ->
                context.server().execute(() -> handleStatusRequest(context.server(), context.player())));
        ServerPlayNetworking.registerGlobalReceiver(AiSetupActionPayload.ID, (payload, context) ->
                context.server().execute(() -> handleAction(context.server(), context.player(), payload)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.player.getUuid();
            server.execute(() -> {
                LAST_STATUS_MS.remove(playerId);
                LAST_ACTION_MS.remove(playerId);
            });
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LAST_STATUS_MS.clear();
            LAST_ACTION_MS.clear();
            synchronized (PROBE_LOCK) {
                cachedProbe = null;
                cachedProbeAtMs = 0L;
            }
        });
    }

    // ── Server state (maps: server thread only) ─────────────────────────────

    private static final Map<UUID, Long> LAST_STATUS_MS = new HashMap<>();
    private static final Map<UUID, Long> LAST_ACTION_MS = new HashMap<>();

    private static final ExecutorService PROBE_EXECUTOR = daemonExecutor("frens-ai-setup-probe");
    private static final ExecutorService RELOAD_EXECUTOR = daemonExecutor("frens-ai-setup-reload");

    private static final Object PROBE_LOCK = new Object();
    private static AiSetupStatus.Ollama cachedProbe;
    private static long cachedProbeAtMs;
    private static CompletableFuture<AiSetupStatus.Ollama> probeInFlight;

    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private static boolean isRealPlayer(ServerPlayerEntity player) {
        return player != null && !player.isRemoved() && !(player instanceof createFakePlayer);
    }

    /** Operator, or the host of an integrated server (no ops.json entry with cheats off). */
    private static boolean canEditServer(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null) {
            return false;
        }
        return Frens.isOperator(player) || isIntegratedHost(server, player);
    }

    private static boolean isIntegratedHost(MinecraftServer server, ServerPlayerEntity player) {
        return server.isHost(new PlayerConfigEntry(player.getGameProfile()));
    }

    // ── Ollama probe: cached, single in flight ──────────────────────────────

    /**
     * The server machine's Ollama state, reused for {@link AiSetupActionPolicy#OLLAMA_PROBE_TTL_MS};
     * concurrent callers share the one in-flight probe. Never blocks the caller.
     */
    static CompletableFuture<AiSetupStatus.Ollama> probeOllama() {
        synchronized (PROBE_LOCK) {
            long now = System.currentTimeMillis();
            if (cachedProbe != null && AiSetupActionPolicy.cacheFresh(cachedProbeAtMs, now,
                    AiSetupActionPolicy.OLLAMA_PROBE_TTL_MS)) {
                return CompletableFuture.completedFuture(cachedProbe);
            }
            if (probeInFlight != null) {
                return probeInFlight;
            }
            CompletableFuture<AiSetupStatus.Ollama> probe =
                    CompletableFuture.supplyAsync(AiSetupNetworkManager::runProbe, PROBE_EXECUTOR);
            probeInFlight = probe;
            probe.whenComplete((result, err) -> {
                synchronized (PROBE_LOCK) {
                    if (err == null && result != null) {
                        cachedProbe = result;
                        cachedProbeAtMs = System.currentTimeMillis();
                    }
                    if (probeInFlight == probe) {
                        probeInFlight = null;
                    }
                }
            });
            return probe;
        }
    }

    private static AiSetupStatus.Ollama runProbe() {
        OllamaModelInstaller.Status detected = OllamaModelInstaller.detect();
        List<String> tags = new ArrayList<>();
        for (OllamaModelInstaller.InstalledModel model : detected.installed()) {
            if (tags.size() >= AiSetupStatus.MAX_TAGS) break;
            tags.add(model.tag());
        }
        double ramGb = detected.totalRamBytes() > 0 ? detected.totalRamBytes() / 1073741824.0 : -1;
        return new AiSetupStatus.Ollama(detected.reachable(), detected.version(), tags, ramGb);
    }

    private static List<String> catalogTags() {
        List<String> tags = new ArrayList<>();
        for (OllamaModelInstaller.KnownModel model : OllamaModelInstaller.KNOWN_MODELS) {
            tags.add(model.tag());
        }
        return tags;
    }

    // ── Status ──────────────────────────────────────────────────────────────

    private static void handleStatusRequest(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || !isRealPlayer(player)) {
            return;
        }
        UUID playerId = player.getUuid();
        long now = System.currentTimeMillis();
        if (!AiSetupActionPolicy.throttleAllows(LAST_STATUS_MS.get(playerId), now,
                AiSetupActionPolicy.STATUS_REQUEST_INTERVAL_MS)) {
            LOGGER.debug("[ai-setup] status request from {} dropped (throttled)", player.getName().getString());
            return;
        }
        LAST_STATUS_MS.put(playerId, now);
        pushStatus(server, player);
    }

    /**
     * Server thread: snapshots live state for {@code viewer} and sends it — immediately for a
     * non-operator, after the (cached) Ollama probe and a provider health check for an operator.
     */
    static void pushStatus(MinecraftServer server, ServerPlayerEntity viewer) {
        if (server == null || !isRealPlayer(viewer)) {
            return;
        }
        boolean canEdit = canEditServer(server, viewer);
        AiSetupStatus snapshot = snapshot(server, viewer, canEdit);
        UUID viewerId = viewer.getUuid();
        if (!canEdit) {
            send(server, viewerId, snapshot.redactedForPlayer());
            return;
        }
        SoulRuntime runtime = SoulRuntime.current().orElse(null);
        CompletableFuture<Boolean> health;
        try {
            health = runtime == null ? CompletableFuture.completedFuture(null)
                    : runtime.providerHealth().exceptionally(ex -> false);
        } catch (RuntimeException ex) {
            health = CompletableFuture.completedFuture(false);
        }
        probeOllama().thenCombine(health, (ollama, healthy) -> withProbe(snapshot, ollama, healthy))
                .whenComplete((status, err) -> server.execute(() -> {
                    if (err != null) {
                        LOGGER.warn("[ai-setup] status probe failed: {}", err.toString());
                        send(server, viewerId, withProbe(snapshot,
                                new AiSetupStatus.Ollama(false, "", List.of(), -1), false));
                    } else {
                        send(server, viewerId, status);
                    }
                }));
    }

    private static AiSetupStatus withProbe(AiSetupStatus base, AiSetupStatus.Ollama ollama, Boolean healthy) {
        AiSetupStatus.Runtime r = base.runtime();
        return new AiSetupStatus(true, ollama,
                new AiSetupStatus.Runtime(r.running(), r.soulsEnabled(), r.ready(), r.model(),
                        r.validationError(), healthy),
                base.bots(), base.voice(), base.viewer());
    }

    /** Server thread only: live runtime flags, registered bots and viewer permissions. */
    private static AiSetupStatus snapshot(MinecraftServer server, ServerPlayerEntity viewer, boolean canEdit) {
        ManualConfig config = Frens.CONFIG;
        SoulRuntime runtime = SoulRuntime.current().orElse(null);
        UUID viewerId = viewer.getUuid();

        boolean soulsEnabled = runtime != null ? runtime.isMasterEnabled()
                : config != null && config.isSoulsEnabled();
        String model = config != null && config.getSoulModel() != null ? config.getSoulModel().trim() : "";
        AiSetupStatus.Runtime runtimeState = new AiSetupStatus.Runtime(runtime != null, soulsEnabled,
                runtime != null && runtime.pipelineAvailable(), model,
                runtime != null ? runtime.safeValidationError() : "", null);

        UUID repliedBot = SoulRuntime.dmFollowUp(viewerId).map(open -> open.botId()).orElse(null);
        List<AiSetupStatus.Bot> bots = new ArrayList<>();
        for (ServerPlayerEntity bot : BotRegistry.getPlayers(server)) {
            if (bots.size() >= AiSetupStatus.MAX_BOTS) break;
            if (!(bot instanceof createFakePlayer) || bot.isRemoved()) continue;
            UUID botId = bot.getUuid();
            boolean owned = viewerId.equals(CompanionCommunicationPolicy.resolveOwnerUuid(bot));
            if (!owned && !canEdit) continue;
            Optional<SoulTypes.SoulState> state = runtime != null ? runtime.cachedState(botId) : Optional.empty();
            boolean inReach = CompanionCommunicationPolicy.classifySoulReachability(bot, viewer)
                    != SoulTypes.Reachability.UNREACHABLE;
            bots.add(new AiSetupStatus.Bot(bot.getName().getString(), botId.toString(),
                    state.map(SoulTypes.SoulState::profileId).orElse(""),
                    state.map(SoulTypes.SoulState::active).orElse(false),
                    owned, inReach, botId.equals(repliedBot)));
        }

        AiSetupStatus.Voice voice;
        if (runtime != null) {
            SoulVoiceSettings vs = runtime.voiceSettings();
            voice = new AiSetupStatus.Voice(vs.enabled(), true, vs.engine(), vs.valid(), runtime.voiceEngineAlive());
        } else {
            boolean on = config != null && config.isSoulVoiceEnabled();
            voice = new AiSetupStatus.Voice(on, config != null,
                    config != null ? config.getSoulVoiceEngine() : "", false, false);
        }
        AiSetupStatus.Viewer viewerState = new AiSetupStatus.Viewer(canEdit, isIntegratedHost(server, viewer));
        return new AiSetupStatus(canEdit, null, runtimeState, bots, voice, viewerState);
    }

    /** Server thread: re-resolves the viewer (they may have left) and sends. */
    private static void send(MinecraftServer server, UUID viewerId, AiSetupStatus status) {
        ServerPlayerEntity viewer = server.getPlayerManager().getPlayer(viewerId);
        if (!isRealPlayer(viewer) || status == null) {
            return;
        }
        // Deliberately not gated on canSend: the client registers its receiver lazily when the
        // checklist opens, and that channel may not have reached the server's sendable set yet.
        ServerPlayNetworking.send(viewer, new AiSetupStatusPayload(status.toJson()));
    }

    private static void reply(MinecraftServer server, UUID playerId, String message) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (!isRealPlayer(player) || message == null || message.isBlank()) {
            return;
        }
        player.sendMessage(Text.literal(message).formatted(Formatting.GRAY), false);
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private static void handleAction(MinecraftServer server, ServerPlayerEntity player, AiSetupActionPayload payload) {
        if (server == null || !isRealPlayer(player) || payload == null) {
            return;
        }
        UUID playerId = player.getUuid();
        String name = player.getName().getString();
        long now = System.currentTimeMillis();
        if (!AiSetupActionPolicy.throttleAllows(LAST_ACTION_MS.get(playerId), now,
                AiSetupActionPolicy.ACTION_INTERVAL_MS)) {
            LOGGER.debug("[ai-setup] action from {} dropped (throttled)", name);
            return;
        }
        LAST_ACTION_MS.put(playerId, now);

        Optional<AiSetupActionPolicy.Action> parsed = AiSetupActionPolicy.Action.parse(payload.action());
        if (parsed.isEmpty()) {
            LOGGER.debug("[ai-setup] unknown action '{}' from {}", BotAccessPolicy.logSafe(payload.action()), name);
            return;
        }
        AiSetupActionPolicy.Action action = parsed.get();
        String arg = payload.arg() == null ? "" : payload.arg();

        ServerPlayerEntity bot = null;
        boolean botRegistered = false;
        boolean botAccess = false;
        if (action == AiSetupActionPolicy.Action.ENABLE_BOT) {
            UUID botId = parseUuid(arg);
            bot = botId == null ? null : server.getPlayerManager().getPlayer(botId);
            BotAccessPolicy.Decision decision = BotAccessGate.decide(player, bot);
            botRegistered = decision != BotAccessPolicy.Decision.DENY_NOT_BOT;
            botAccess = BotAccessPolicy.allowed(decision);
        }
        AiSetupActionPolicy.Verdict verdict = AiSetupActionPolicy.gate(action, canEditServer(server, player),
                botRegistered, botAccess);
        if (verdict != AiSetupActionPolicy.Verdict.ALLOW) {
            LOGGER.debug("[ai-setup] {} from {} denied: {}", action, name, verdict);
            reply(server, playerId, AiSetupActionPolicy.denialMessage(verdict));
            return;
        }

        ManualConfig config = Frens.CONFIG;
        if (config == null) {
            reply(server, playerId, "Frens configuration is unavailable.");
            return;
        }
        switch (action) {
            case SOULS_ON, SOULS_OFF -> {
                boolean on = action == AiSetupActionPolicy.Action.SOULS_ON;
                config.setSoulsEnabled(on);
                config.save();
                LOGGER.info("[ai-setup] {} set Soul Chat {}", name, on ? "on" : "off");
                reloadThenReply(server, playerId, () -> on
                        ? "Soul Chat is now ON for this world."
                        : "Soul Chat is now OFF for this world.");
            }
            case SELECT_MODEL -> selectModel(server, playerId, arg);
            case SELECT_VOICE_ENGINE -> {
                if (!AiSetupActionPolicy.isKnownEngine(arg)) {
                    LOGGER.debug("[ai-setup] unknown voice engine '{}' from {}", BotAccessPolicy.logSafe(arg), name);
                    reply(server, playerId, "Unknown voice engine.");
                    return;
                }
                config.setSoulVoiceEngine(arg);
                config.save();
                LOGGER.info("[ai-setup] {} set soul voice engine {}", name, arg);
                reloadThenReply(server, playerId, () -> {
                    String msg = "Voice engine set to " + arg + ".";
                    SoulRuntime rt = SoulRuntime.current().orElse(null);
                    SoulVoiceSettings vs = rt != null ? rt.voiceSettings() : null;
                    if (vs != null && !vs.valid() && !vs.validationError().isBlank()) {
                        msg += " It isn't set up on the server machine yet: " + vs.validationError();
                    }
                    return msg;
                });
            }
            case ENABLE_BOT -> enableBot(server, playerId, bot);
        }
    }

    /**
     * SELECT_MODEL: a catalog tag applies at once; any other well-formed tag first waits for the
     * (cached) Ollama probe on a worker, then validates against the installed tags back on the
     * server thread.
     */
    private static void selectModel(MinecraftServer server, UUID playerId, String tag) {
        List<String> catalog = catalogTags();
        AiSetupActionPolicy.TagCheck syntax = AiSetupActionPolicy.checkTagSyntax(tag);
        if (syntax != AiSetupActionPolicy.TagCheck.OK) {
            LOGGER.debug("[ai-setup] model tag '{}' rejected: {}", BotAccessPolicy.logSafe(tag), syntax);
            reply(server, playerId, AiSetupActionPolicy.tagProblem(syntax));
            return;
        }
        if (!AiSetupActionPolicy.needsInstalledTags(tag, catalog)) {
            applyModel(server, playerId, tag);
            return;
        }
        probeOllama().whenComplete((ollama, err) -> server.execute(() -> {
            List<String> installed = err == null && ollama != null && ollama.reachable()
                    ? ollama.installedTags() : null;
            AiSetupActionPolicy.TagCheck check = AiSetupActionPolicy.checkModelTag(tag, catalog, installed);
            if (check != AiSetupActionPolicy.TagCheck.OK) {
                LOGGER.debug("[ai-setup] model tag '{}' rejected: {}", BotAccessPolicy.logSafe(tag), check);
                reply(server, playerId, AiSetupActionPolicy.tagProblem(check));
                return;
            }
            applyModel(server, playerId, tag);
        }));
    }

    private static void applyModel(MinecraftServer server, UUID playerId, String tag) {
        ManualConfig config = Frens.CONFIG;
        if (config == null) {
            return;
        }
        config.setSoulModel(tag);
        config.save();
        LOGGER.info("[ai-setup] soul model set to {}", tag);
        reloadThenReply(server, playerId, () -> "Soul model set to '" + tag + "'.");
    }

    /**
     * Server thread, after the config was saved: pushes the shared config to every client (a
     * dedicated-server client keeps a mirror of it, and BotControlScreen saves that mirror back),
     * reloads the runtime on a worker, then replies and pushes a fresh status on the server thread.
     */
    private static void reloadThenReply(MinecraftServer server, UUID playerId,
                                        java.util.function.Supplier<String> message) {
        configNetworkManager.broadcastConfigSync(server);
        SoulRuntime runtime = SoulRuntime.current().orElse(null);
        if (runtime == null) {
            reply(server, playerId, message.get() + " (soul runtime not currently running.)");
            pushStatusTo(server, playerId);
            return;
        }
        ManualConfig config = Frens.CONFIG;
        CompletableFuture.supplyAsync(() -> runtime.reloadSettings(config), RELOAD_EXECUTOR)
                .thenCompose(f -> f)
                .whenComplete((v, err) -> server.execute(() -> {
                    if (err != null) {
                        LOGGER.warn("[ai-setup] reloadSettings failed after setup change: {}", err.toString());
                        reply(server, playerId, message.get() + " Reload reported an error; check the server log.");
                    } else {
                        reply(server, playerId, message.get());
                    }
                    pushStatusTo(server, playerId);
                }));
    }

    private static void pushStatusTo(MinecraftServer server, UUID playerId) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (isRealPlayer(player)) {
            pushStatus(server, player);
        }
    }

    /** ENABLE_BOT: same binding as {@code /bot soul enable} (name-matched persona, else Jake). */
    private static void enableBot(MinecraftServer server, UUID playerId, ServerPlayerEntity bot) {
        SoulRuntime runtime = SoulRuntime.current().orElse(null);
        if (runtime == null || bot == null) {
            reply(server, playerId, "The soul runtime is not running right now.");
            return;
        }
        UUID botId = bot.getUuid();
        String botName = bot.getName().getString();
        String jake = SoulProfileRegistry.profileIdForBotName("jake").orElse("frens:jake");
        String profileId = SoulProfileRegistry.profileIdForBotName(botName).orElse(jake);
        boolean fallback = AiSetupActionPolicy.isFallbackPersona(botName, profileId, jake);
        String persona = profileId.equals(jake) ? "Jake" : botName;
        runtime.bindProfile(botId, profileId)
                .thenCompose(bound -> runtime.setActive(botId, true))
                .whenComplete((state, err) -> {
                    if (err != null) {
                        server.execute(() -> {
                            LOGGER.warn("[ai-setup] enable failed for bot {}: {}", botId, err.toString());
                            reply(server, playerId, "Failed to enable AI chat for " + botName + ".");
                            pushStatusTo(server, playerId);
                        });
                        return;
                    }
                    boolean soulsOn = runtime.isMasterEnabled();
                    String validation = runtime.safeValidationError();
                    CompletableFuture<Boolean> health = soulsOn && (validation == null || validation.isBlank())
                            ? runtime.providerHealth().exceptionally(ex -> false)
                            : CompletableFuture.completedFuture(null);
                    health.whenComplete((healthy, healthErr) -> server.execute(() -> {
                        LOGGER.info("[ai-setup] enabled bot {} as {}", botName, profileId);
                        reply(server, playerId, AiSetupActionPolicy.enableMessage(botName, persona, fallback,
                                soulsOn, validation, healthErr != null ? Boolean.FALSE : healthy));
                        pushStatusTo(server, playerId);
                    }));
                });
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 36) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ── Client API ──────────────────────────────────────────────────────────

    /**
     * Client side of the setup surface. Loaded only from client screens, so a dedicated server
     * never touches the client classes it references. Every method runs on the render thread.
     */
    @Environment(EnvType.CLIENT)
    public static final class Client {
        private static boolean receiverRegistered;
        private static AiSetupStatus latest;
        private static long latestAtMs;
        private static Consumer<AiSetupStatus> listener;

        private Client() {
        }

        /** Registers the status receiver (idempotent). Screens call this in their constructor. */
        public static void registerOnce() {
            if (receiverRegistered) {
                return;
            }
            receiverRegistered = true;
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                    AiSetupStatusPayload.ID, (payload, context) -> context.client().execute(() -> accept(payload.json())));
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                    (handler, client) -> client.execute(() -> {
                        latest = null;
                        latestAtMs = 0L;
                    }));
        }

        private static void accept(String json) {
            AiSetupStatus status = AiSetupStatus.fromJson(json);
            if (status == null) {
                return;
            }
            latest = status;
            latestAtMs = net.minecraft.util.Util.getMeasuringTimeMs();
            Consumer<AiSetupStatus> l = listener;
            if (l != null) {
                l.accept(status);
            }
        }

        /** Whether the connected server has this surface (false on servers without Frens ≥ 1.1.225). */
        public static boolean isAvailable() {
            return net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                    AiSetupStatusRequestPayload.ID);
        }

        /** Asks the server for a fresh status (throttled server-side to one per 2 s). */
        public static boolean requestStatus() {
            registerOnce();
            if (!isAvailable()) {
                return false;
            }
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new AiSetupStatusRequestPayload("request"));
            return true;
        }

        /**
         * Sends one action; the server replies in chat and pushes a fresh status afterwards (no
         * need to call {@link #requestStatus()}). False when the server lacks this surface.
         */
        public static boolean sendAction(AiSetupActionPolicy.Action action, String arg) {
            registerOnce();
            if (action == null || !isAvailable()) {
                return false;
            }
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new AiSetupActionPayload(action.name(), arg == null ? "" : arg));
            return true;
        }

        /** SELECT_MODEL: op/host only; tag must be a Frens catalog tag or installed on the server. */
        public static boolean selectModel(String tag) {
            return sendAction(AiSetupActionPolicy.Action.SELECT_MODEL, tag);
        }

        /** SELECT_VOICE_ENGINE: op/host only; one of {@link AiSetupActionPolicy#VOICE_ENGINES}. */
        public static boolean selectVoiceEngine(String engineId) {
            return sendAction(AiSetupActionPolicy.Action.SELECT_VOICE_ENGINE, engineId);
        }

        public static boolean setSoulsEnabled(boolean on) {
            return sendAction(on ? AiSetupActionPolicy.Action.SOULS_ON : AiSetupActionPolicy.Action.SOULS_OFF, "");
        }

        public static boolean enableBot(String botUuid) {
            return sendAction(AiSetupActionPolicy.Action.ENABLE_BOT, botUuid);
        }

        /** Last status received on this connection, or null. */
        public static AiSetupStatus latestStatus() {
            return latest;
        }

        /** {@code Util.getMeasuringTimeMs()} when {@link #latestStatus()} arrived; 0 if never. */
        public static long latestStatusAtMs() {
            return latestAtMs;
        }

        /** One listener at a time (the open screen); called on the render thread per status. */
        public static void setListener(Consumer<AiSetupStatus> newListener) {
            listener = newListener;
        }

        /** Clears the listener if it is still {@code owner}. */
        public static void clearListener(Consumer<AiSetupStatus> owner) {
            if (listener == owner) {
                listener = null;
            }
        }
    }
}
