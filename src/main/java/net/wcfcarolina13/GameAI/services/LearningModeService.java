package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.network.LearningInputSamplePayload;
import net.wcfcarolina13.network.LearningSessionStatusPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Operator-scoped demonstration recorder ("learning mode") for collecting high-signal traces
 * of human demonstrations to tune bot control logic offline.
 */
public final class LearningModeService {

    private static final Logger LOGGER = LoggerFactory.getLogger("learning-mode");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Gson GSON_COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private static final AtomicLong SESSION_COUNTER = new AtomicLong(0L);
    private static final Map<UUID, ArmedLearningPreset> ARMED_PRESETS = new ConcurrentHashMap<>();
    private static final Map<UUID, ActiveLearningSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, LearningClientInputState> LATEST_CLIENT_INPUT = new ConcurrentHashMap<>();

    private static volatile boolean RECEIVER_REGISTERED = false;
    private static volatile long SERVER_TICK_COUNTER = 0L;

    private static final Pattern OPTION_TOKEN_PATTERN = Pattern.compile("(?:[^\\s=]+=\"(?:\\\\.|[^\"\\\\])*\"|\\S+)");

    private static final int MAX_GOAL_LEN = 64;
    private static final int MAX_LABEL_LEN = 64;
    private static final int MAX_NOTE_LEN = 256;
    private static final int MAX_LIST_COUNT = 50;
    private static final int DEFAULT_LIST_COUNT = 10;

    private static final long MAX_SESSION_DURATION_MS = 30L * 60L * 1000L;
    private static final long MAX_EVENT_BYTES = 512L * 1024L * 1024L;
    private static final long PERIODIC_FLUSH_MS = 1000L;
    private static final long CLIENT_INPUT_WARN_DELAY_MS = 3000L;
    private static final int PLACE_RESULT_INFER_TIMEOUT_TICKS = 8;
    private static final int MAX_PENDING_PLACE_ATTEMPTS = 32;

    private static final int INPUT_FLAG_FORWARD = 1 << 0;
    private static final int INPUT_FLAG_BACK = 1 << 1;
    private static final int INPUT_FLAG_LEFT = 1 << 2;
    private static final int INPUT_FLAG_RIGHT = 1 << 3;
    private static final int INPUT_FLAG_JUMP = 1 << 4;
    private static final int INPUT_FLAG_SNEAK = 1 << 5;
    private static final int INPUT_FLAG_SPRINT = 1 << 6;
    private static final int INPUT_FLAG_USE = 1 << 7;
    private static final int INPUT_FLAG_ATTACK = 1 << 8;

    private LearningModeService() {
    }

    public enum LearningProfile {
        GENERAL,
        CONSTRUCTION,
        NAVIGATION,
        COMBAT;

        static LearningProfile parse(String raw) {
            if (raw == null || raw.isBlank()) return CONSTRUCTION;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "general" -> GENERAL;
                case "construction", "build", "building" -> CONSTRUCTION;
                case "navigation", "nav", "pathing" -> NAVIGATION;
                case "combat", "fight" -> COMBAT;
                default -> null;
            };
        }
    }

    public enum LearningDetailTier {
        CORE,
        BALANCED,
        HEAVY;

        static LearningDetailTier parse(String raw) {
            if (raw == null || raw.isBlank()) return BALANCED;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "core", "light" -> CORE;
                case "balanced", "default", "normal" -> BALANCED;
                case "heavy", "full" -> HEAVY;
                default -> null;
            };
        }
    }

    public enum LearningStopOutcome {
        SUCCESS,
        FAIL,
        ABORT,
        MANUAL;

        static LearningStopOutcome parse(String raw) {
            if (raw == null || raw.isBlank()) return MANUAL;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "success", "ok", "done" -> SUCCESS;
                case "fail", "failure", "failed" -> FAIL;
                case "abort", "aborted", "cancel", "canceled", "cancelled" -> ABORT;
                case "manual", "stop" -> MANUAL;
                default -> null;
            };
        }
    }

    private record SamplingConfig(int tickSampleEveryTicks,
                                  int voxelEveryTicks,
                                  int voxelRadiusXZ,
                                  int voxelDown,
                                  int voxelUp,
                                  int clientTickHz) {
    }

    private record ArmedLearningPreset(String goalTag,
                                       LearningProfile profile,
                                       LearningDetailTier detailTier,
                                       String pairedBotAlias,
                                       String note,
                                       Integer radiusOverride,
                                       Integer tickHzOverride,
                                       long updatedAtMs) {
    }

    private record PendingPlacementAttempt(BlockPos pos,
                                           String beforeBlockId,
                                           String heldBlockId,
                                           String hand,
                                           long createdTick,
                                           long expiresTick) {
    }

    private record LearningClientInputState(long receivedAtMs,
                                            long sessionToken,
                                            int sampleSeq,
                                            int flags,
                                            int selectedSlot,
                                            float yaw,
                                            float pitch) {
    }

    private record DerivedTickState(Vec3d pos,
                                    Vec3d vel,
                                    BlockPos blockPos,
                                    String dimensionId,
                                    boolean onGround,
                                    boolean sneaking,
                                    boolean sprinting,
                                    boolean swimming,
                                    boolean climbing,
                                    int selectedSlot) {
    }

    private static final class ActiveLearningSession {
        final UUID operatorUuid;
        final String operatorName;
        final long sessionToken;
        final String sessionId;
        final String worldKey;
        final String startDimensionId;
        final String goalTag;
        final LearningProfile profile;
        final LearningDetailTier detailTier;
        final SamplingConfig sampling;
        final String pairedBotAlias;
        final String note;
        UUID pairedBotUuid;
        final Path dir;
        final Path sessionJsonPath;
        final Path eventsJsonlPath;
        final Path summaryJsonPath;
        final BufferedWriter eventsWriter;
        final long startEpochMs;
        final Map<String, Integer> eventCounts = new LinkedHashMap<>();
        final Map<String, Integer> warningCounts = new LinkedHashMap<>();
        final Deque<PendingPlacementAttempt> pendingPlacementAttempts = new ArrayDeque<>();
        final List<String> notes = new ArrayList<>();
        long endEpochMs;
        long seq;
        long localTickCounter;
        long approxEventBytes;
        long lastFlushMs;
        long tickSamples;
        long clientInputSamples;
        long marks;
        long useBlockEvents;
        long useItemEvents;
        long attackBlockEvents;
        long blockBreakEvents;
        long blockPlaceAttempts;
        long blockPlaceResults;
        long botPairTickSamples;
        long warnings;
        long jumps;
        long falls;
        long lands;
        long damageEvents;
        long sneakTicks;
        long sprintTicks;
        long dimensionChanges;
        double totalDistance;
        boolean warnedMissingClientTelemetry;
        boolean clientTelemetrySupported = true;
        boolean closed;
        String outcome = "active";
        String stopReason = "";
        DerivedTickState lastDerived;
        long lastClientInputSeenMs;

        ActiveLearningSession(UUID operatorUuid,
                              String operatorName,
                              long sessionToken,
                              String sessionId,
                              String worldKey,
                              String startDimensionId,
                              String goalTag,
                              LearningProfile profile,
                              LearningDetailTier detailTier,
                              SamplingConfig sampling,
                              String pairedBotAlias,
                              String note,
                              UUID pairedBotUuid,
                              Path dir,
                              Path sessionJsonPath,
                              Path eventsJsonlPath,
                              Path summaryJsonPath,
                              BufferedWriter eventsWriter) {
            this.operatorUuid = operatorUuid;
            this.operatorName = operatorName;
            this.sessionToken = sessionToken;
            this.sessionId = sessionId;
            this.worldKey = worldKey;
            this.startDimensionId = startDimensionId;
            this.goalTag = goalTag;
            this.profile = profile;
            this.detailTier = detailTier;
            this.sampling = sampling;
            this.pairedBotAlias = pairedBotAlias;
            this.note = note;
            this.pairedBotUuid = pairedBotUuid;
            this.dir = dir;
            this.sessionJsonPath = sessionJsonPath;
            this.eventsJsonlPath = eventsJsonlPath;
            this.summaryJsonPath = summaryJsonPath;
            this.eventsWriter = eventsWriter;
            this.startEpochMs = System.currentTimeMillis();
            this.endEpochMs = 0L;
            this.seq = 0L;
            this.lastFlushMs = this.startEpochMs;
            this.lastClientInputSeenMs = 0L;
            if (note != null && !note.isBlank()) {
                this.notes.add(note);
            }
        }
    }

    private static final class ParsedLearningOptions {
        String goalTag;
        LearningProfile profile;
        LearningDetailTier detailTier;
        String pairedBotAlias;
        String note;
        Integer radiusOverride;
        Integer tickHzOverride;
        String label;
    }

    private record SessionListEntry(String sessionId,
                                    String goalTag,
                                    String outcome,
                                    long startEpochMs,
                                    long endEpochMs,
                                    String operatorName,
                                    Path dir) {
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Network registration / client telemetry
    // ─────────────────────────────────────────────────────────────────────────────

    public static void registerReceiversOnce() {
        if (RECEIVER_REGISTERED) return;
        RECEIVER_REGISTERED = true;
        ServerPlayNetworking.registerGlobalReceiver(LearningInputSamplePayload.ID, (payload, context) ->
                context.server().execute(() -> onClientInputSample(context.server(), context.player(), payload)));
    }

    private static void onClientInputSample(MinecraftServer server, ServerPlayerEntity player, LearningInputSamplePayload payload) {
        if (server == null || player == null || player.isRemoved() || payload == null) return;
        if (player instanceof createFakePlayer) return;
        ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
        if (session == null) return;
        if (payload.sessionToken() != session.sessionToken) return;

        LearningClientInputState state = new LearningClientInputState(
                System.currentTimeMillis(),
                payload.sessionToken(),
                payload.sampleSeq(),
                payload.flags(),
                payload.selectedSlot(),
                payload.yaw(),
                payload.pitch()
        );
        LATEST_CLIENT_INPUT.put(player.getUuid(), state);
        session.lastClientInputSeenMs = state.receivedAtMs();
        session.clientInputSamples++;

        JsonObject data = new JsonObject();
        data.addProperty("sampleSeq", payload.sampleSeq());
        data.addProperty("flags", payload.flags());
        data.addProperty("selectedSlot", payload.selectedSlot());
        data.addProperty("yaw", payload.yaw());
        data.addProperty("pitch", payload.pitch());
        JsonObject decoded = new JsonObject();
        decoded.addProperty("forward", (payload.flags() & INPUT_FLAG_FORWARD) != 0);
        decoded.addProperty("back", (payload.flags() & INPUT_FLAG_BACK) != 0);
        decoded.addProperty("left", (payload.flags() & INPUT_FLAG_LEFT) != 0);
        decoded.addProperty("right", (payload.flags() & INPUT_FLAG_RIGHT) != 0);
        decoded.addProperty("jump", (payload.flags() & INPUT_FLAG_JUMP) != 0);
        decoded.addProperty("sneak", (payload.flags() & INPUT_FLAG_SNEAK) != 0);
        decoded.addProperty("sprint", (payload.flags() & INPUT_FLAG_SPRINT) != 0);
        decoded.addProperty("use", (payload.flags() & INPUT_FLAG_USE) != 0);
        decoded.addProperty("attack", (payload.flags() & INPUT_FLAG_ATTACK) != 0);
        data.add("decoded", decoded);
        ServerWorld world = player.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        recordEvent(session, world, player, "CLIENT_INPUT_SAMPLE", data);
    }

    private static void notifyClientStreamState(ServerPlayerEntity player, boolean active, long sessionToken, int tickHz) {
        if (player == null || player.isRemoved()) return;
        if (!ServerPlayNetworking.canSend(player, LearningSessionStatusPayload.ID)) {
            ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
            if (session != null) {
                session.clientTelemetrySupported = false;
            }
            return;
        }
        try {
            ServerPlayNetworking.send(player, new LearningSessionStatusPayload(active, sessionToken, tickHz));
        } catch (Exception e) {
            LOGGER.debug("LearningMode: failed to send session status payload to {}: {}", player.getName().getString(), e.getMessage());
            ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
            if (session != null) {
                session.clientTelemetrySupported = false;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Command surface
    // ─────────────────────────────────────────────────────────────────────────────

    public static int executeStatus(ServerCommandSource source) {
        for (String line : statusLines(source)) {
            ChatUtils.sendSystemMessage(source, line);
        }
        return 1;
    }

    public static int executeArm(ServerCommandSource source, String optionsRaw) {
        ServerPlayerEntity player = requireOperatorPlayer(source, "arm");
        if (player == null) return 0;
        List<String> out = new ArrayList<>();
        boolean ok = armPreset(player, optionsRaw, out);
        for (String line : out) {
            ChatUtils.sendSystemMessage(source, line);
        }
        return ok ? 1 : 0;
    }

    public static int executeStart(ServerCommandSource source, String optionsRaw) {
        ServerPlayerEntity player = requireOperatorPlayer(source, "start");
        if (player == null) return 0;
        List<String> out = new ArrayList<>();
        boolean ok = startSession(player, optionsRaw, false, null, out);
        for (String line : out) {
            ChatUtils.sendSystemMessage(source, line);
        }
        return ok ? 1 : 0;
    }

    public static int executeStop(ServerCommandSource source, String outcomeToken) {
        ServerPlayerEntity player = requireOperatorPlayer(source, "stop");
        if (player == null) return 0;
        List<String> out = new ArrayList<>();
        LearningStopOutcome outcome = LearningStopOutcome.parse(outcomeToken);
        if (outcome == null) {
            out.add("Unknown learning stop outcome: " + outcomeToken + " (use success|fail|abort|manual)");
            for (String line : out) ChatUtils.sendSystemMessage(source, line);
            return 0;
        }
        boolean ok = stopSession(player, outcome, "manual-stop", out);
        for (String line : out) {
            ChatUtils.sendSystemMessage(source, line);
        }
        return ok ? 1 : 0;
    }

    public static int executeMark(ServerCommandSource source, String optionsRaw) {
        ServerPlayerEntity player = requireOperatorPlayer(source, "mark");
        if (player == null) return 0;
        List<String> out = new ArrayList<>();
        boolean ok = addMark(player, optionsRaw, out);
        for (String line : out) {
            ChatUtils.sendSystemMessage(source, line);
        }
        return ok ? 1 : 0;
    }

    public static int executeList(ServerCommandSource source, Integer countArg) {
        if (!Frens.isOperator(source)) {
            ChatUtils.sendSystemMessage(source, "You must be an operator to use /bot learn.");
            return 0;
        }
        int count = Math.max(1, Math.min(countArg != null ? countArg : DEFAULT_LIST_COUNT, MAX_LIST_COUNT));
        List<String> lines = listSessions(source, count);
        for (String line : lines) {
            ChatUtils.sendSystemMessage(source, line);
        }
        return 1;
    }

    public static int executeReport(ServerCommandSource source, String sessionRef) {
        if (!Frens.isOperator(source)) {
            ChatUtils.sendSystemMessage(source, "You must be an operator to use /bot learn.");
            return 0;
        }
        List<String> lines = reportSession(source, sessionRef);
        for (String line : lines) {
            ChatUtils.sendSystemMessage(source, line);
        }
        return 1;
    }

    public static List<String> handleAdminAction(MinecraftServer server, ServerPlayerEntity player, String action, String adminScreenBotAlias) {
        List<String> out = new ArrayList<>();
        if (server == null || player == null || player.isRemoved()) {
            out.add("Learning mode unavailable (player/server missing).");
            return out;
        }
        String k = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        switch (k) {
            case "learning_status" -> out.addAll(statusLinesForPlayer(player));
            case "learning_start" -> startSession(player, null, true, adminScreenBotAlias, out);
            case "learning_stop_success" -> stopSession(player, LearningStopOutcome.SUCCESS, "admin-stop-success", out);
            case "learning_stop_fail" -> stopSession(player, LearningStopOutcome.FAIL, "admin-stop-fail", out);
            case "learning_stop_abort" -> stopSession(player, LearningStopOutcome.ABORT, "admin-stop-abort", out);
            default -> out.add("Unknown learning admin action: " + k);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public event hooks (Frens.java)
    // ─────────────────────────────────────────────────────────────────────────────

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        SERVER_TICK_COUNTER++;
        if (ACTIVE_SESSIONS.isEmpty()) return;

        List<UUID> toAbort = new ArrayList<>();
        Map<UUID, String> abortReasons = new LinkedHashMap<>();
        for (ActiveLearningSession session : new ArrayList<>(ACTIVE_SESSIONS.values())) {
            if (session == null || session.closed) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.operatorUuid);
            if (player == null || player.isRemoved()) {
                toAbort.add(session.operatorUuid);
                abortReasons.put(session.operatorUuid, "disconnect");
                continue;
            }
            if (!player.isAlive()) {
                toAbort.add(session.operatorUuid);
                abortReasons.put(session.operatorUuid, "player-death");
                continue;
            }
            ServerWorld world = player.getEntityWorld() instanceof ServerWorld sw ? sw : null;
            if (world == null) {
                toAbort.add(session.operatorUuid);
                abortReasons.put(session.operatorUuid, "world-unavailable");
                continue;
            }
            String dim = dimensionId(world);
            if (!Objects.equals(dim, session.startDimensionId)) {
                JsonObject data = new JsonObject();
                data.addProperty("from", session.startDimensionId);
                data.addProperty("to", dim);
                recordEvent(session, world, player, "DIMENSION_CHANGE", data);
                session.dimensionChanges++;
                toAbort.add(session.operatorUuid);
                abortReasons.put(session.operatorUuid, "dimension-change");
                continue;
            }
            tickSession(server, session, player, world);
        }
        for (UUID operatorUuid : toAbort) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(operatorUuid);
            if (player != null) {
                List<String> out = new ArrayList<>();
                stopSession(player, LearningStopOutcome.ABORT, abortReasons.getOrDefault(operatorUuid, "auto-abort"), out);
            } else {
                ActiveLearningSession session = ACTIVE_SESSIONS.remove(operatorUuid);
                if (session != null) {
                    stopSessionInternal(server, session, null, null, LearningStopOutcome.ABORT,
                            abortReasons.getOrDefault(operatorUuid, "auto-abort"), null);
                }
            }
        }
    }

    public static void onUseBlock(ServerPlayerEntity player, ServerWorld world, Hand hand, BlockHitResult hitResult) {
        if (!isRecordableHuman(player) || world == null || hitResult == null) return;
        ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
        if (session == null) return;

        BlockPos hitPos = hitResult.getBlockPos();
        BlockState hitState = world.getBlockState(hitPos);
        ItemStack held = player.getStackInHand(hand);
        String heldItemId = itemId(held);

        JsonObject useData = new JsonObject();
        useData.addProperty("hand", hand == null ? "unknown" : hand.name().toLowerCase(Locale.ROOT));
        useData.add("hitPos", jsonPos(hitPos));
        useData.addProperty("hitBlock", blockId(hitState));
        useData.addProperty("side", hitResult.getSide() == null ? "unknown" : hitResult.getSide().asString());
        useData.addProperty("heldItem", heldItemId);
        recordEvent(session, world, player, "USE_BLOCK", useData);
        session.useBlockEvents++;

        if (hand != Hand.MAIN_HAND || held == null || !(held.getItem() instanceof BlockItem blockItem)) {
            return;
        }

        BlockPos candidate = hitPos.offset(hitResult.getSide());
        if (world.getBlockState(hitPos).isReplaceable()) {
            candidate = hitPos;
        }
        BlockState before = world.getBlockState(candidate);
        String heldBlockId = Registries.BLOCK.getId(blockItem.getBlock()).toString();

        JsonObject attempt = new JsonObject();
        attempt.addProperty("heldBlock", heldBlockId);
        attempt.addProperty("heldItem", heldItemId);
        attempt.add("candidatePos", jsonPos(candidate));
        attempt.addProperty("beforeBlock", blockId(before));
        attempt.addProperty("replaceableBefore", before.isReplaceable());
        attempt.addProperty("hand", "main_hand");
        recordEvent(session, world, player, "BLOCK_PLACE_ATTEMPT", attempt);
        session.blockPlaceAttempts++;

        while (session.pendingPlacementAttempts.size() >= MAX_PENDING_PLACE_ATTEMPTS) {
            session.pendingPlacementAttempts.removeFirst();
        }
        session.pendingPlacementAttempts.addLast(new PendingPlacementAttempt(
                candidate.toImmutable(),
                blockId(before),
                heldBlockId,
                "main_hand",
                SERVER_TICK_COUNTER,
                SERVER_TICK_COUNTER + PLACE_RESULT_INFER_TIMEOUT_TICKS
        ));
    }

    public static void onUseItem(ServerPlayerEntity player, ServerWorld world, Hand hand) {
        if (!isRecordableHuman(player) || world == null) return;
        ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
        if (session == null) return;
        JsonObject data = new JsonObject();
        data.addProperty("hand", hand == null ? "unknown" : hand.name().toLowerCase(Locale.ROOT));
        data.addProperty("item", itemId(player.getStackInHand(hand)));
        recordEvent(session, world, player, "USE_ITEM", data);
        session.useItemEvents++;
    }

    public static void onAttackBlock(ServerPlayerEntity player, ServerWorld world, Hand hand, BlockPos pos, Direction side) {
        if (!isRecordableHuman(player) || world == null || pos == null) return;
        ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
        if (session == null) return;
        BlockState state = world.getBlockState(pos);
        JsonObject data = new JsonObject();
        data.addProperty("hand", hand == null ? "unknown" : hand.name().toLowerCase(Locale.ROOT));
        data.add("pos", jsonPos(pos));
        data.addProperty("block", blockId(state));
        data.addProperty("side", side == null ? "unknown" : side.asString());
        recordEvent(session, world, player, "ATTACK_BLOCK_START", data);
        session.attackBlockEvents++;
    }

    public static void onBlockBreakAfter(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState brokenState) {
        if (!isRecordableHuman(player) || world == null || pos == null || brokenState == null) return;
        ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
        if (session == null) return;
        JsonObject data = new JsonObject();
        data.add("pos", jsonPos(pos));
        data.addProperty("block", blockId(brokenState));
        recordEvent(session, world, player, "BLOCK_BREAK_AFTER", data);
        session.blockBreakEvents++;
    }

    public static void onPlayerDamage(ServerPlayerEntity player, DamageSource source, float amount) {
        if (!isRecordableHuman(player)) return;
        ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
        if (session == null) return;
        ServerWorld world = player.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        if (world == null) return;
        JsonObject data = new JsonObject();
        data.addProperty("amount", amount);
        data.addProperty("source", source != null ? source.getName() : "unknown");
        data.addProperty("attackerType", source != null && source.getAttacker() != null
                ? source.getAttacker().getType().toString() : "");
        recordEvent(session, world, player, "DAMAGE_TAKEN", data);
        session.damageEvents++;
    }

    public static void onPlayerDisconnect(ServerPlayerEntity player) {
        if (player == null) return;
        ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
        if (session == null) return;
        MinecraftServer server = player.getEntityWorld() instanceof ServerWorld sw ? sw.getServer() : null;
        stopSessionInternal(server, session, null, null, LearningStopOutcome.ABORT, "disconnect", null);
    }

    public static void onServerStopping(MinecraftServer server) {
        if (server == null) return;
        for (ActiveLearningSession session : new ArrayList<>(ACTIVE_SESSIONS.values())) {
            stopSessionInternal(server, session, null, null, LearningStopOutcome.ABORT, "server-stopping", null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Session lifecycle
    // ─────────────────────────────────────────────────────────────────────────────

    private static boolean armPreset(ServerPlayerEntity player, String optionsRaw, List<String> out) {
        ParsedLearningOptions parsed = parseOptions(optionsRaw, Set.of("goal", "profile", "detail", "pair", "note", "radius", "tickhz"), out);
        if (parsed == null) return false;
        if (parsed.goalTag == null || parsed.goalTag.isBlank()) {
            out.add("Missing required option: goal=<tag>");
            out.add("Example: /bot learn arm goal=pillaring_to_roof profile=construction detail=balanced pair=Jake");
            return false;
        }
        LearningProfile profile = parsed.profile != null ? parsed.profile : LearningProfile.CONSTRUCTION;
        LearningDetailTier detail = parsed.detailTier != null ? parsed.detailTier : LearningDetailTier.BALANCED;
        ArmedLearningPreset preset = new ArmedLearningPreset(
                parsed.goalTag,
                profile,
                detail,
                blankToNull(parsed.pairedBotAlias),
                blankToNull(parsed.note),
                parsed.radiusOverride,
                parsed.tickHzOverride,
                System.currentTimeMillis()
        );
        ARMED_PRESETS.put(player.getUuid(), preset);
        out.add("Learning preset armed.");
        out.add("goal=" + preset.goalTag() + " profile=" + preset.profile().name().toLowerCase(Locale.ROOT)
                + " detail=" + preset.detailTier().name().toLowerCase(Locale.ROOT)
                + (preset.pairedBotAlias() != null ? " pair=" + preset.pairedBotAlias() : ""));
        if (preset.note() != null) {
            out.add("note=" + preset.note());
        }
        return true;
    }

    private static boolean startSession(ServerPlayerEntity player,
                                        String optionsRaw,
                                        boolean adminStart,
                                        String adminDefaultPairAlias,
                                        List<String> out) {
        if (player == null || player.isRemoved()) {
            out.add("Learning mode start failed: player unavailable.");
            return false;
        }
        if (ACTIVE_SESSIONS.containsKey(player.getUuid())) {
            out.addAll(statusLinesForPlayer(player));
            out.add("Stop the active learning session first.");
            return false;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            out.add("Learning mode start failed: server world unavailable.");
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            out.add("Learning mode start failed: server unavailable.");
            return false;
        }

        ArmedLearningPreset armed = ARMED_PRESETS.get(player.getUuid());
        ParsedLearningOptions parsed = null;
        if (optionsRaw != null && !optionsRaw.isBlank()) {
            parsed = parseOptions(optionsRaw, Set.of("goal", "profile", "detail", "pair", "note", "radius", "tickhz"), out);
            if (parsed == null) return false;
        }

        String goal = parsed != null && parsed.goalTag != null ? parsed.goalTag
                : (armed != null ? armed.goalTag() : null);
        if ((goal == null || goal.isBlank()) && adminStart) {
            goal = "manual_demo";
        }
        if (goal == null || goal.isBlank()) {
            out.add("No goal tag set. Use /bot learn arm goal=<tag> ... or /bot learn start goal=<tag> ...");
            return false;
        }

        LearningProfile profile = parsed != null && parsed.profile != null ? parsed.profile
                : (armed != null ? armed.profile() : LearningProfile.CONSTRUCTION);
        LearningDetailTier detail = parsed != null && parsed.detailTier != null ? parsed.detailTier
                : (armed != null ? armed.detailTier() : LearningDetailTier.BALANCED);

        String pairedBotAlias = parsed != null && parsed.pairedBotAlias != null ? parsed.pairedBotAlias
                : (armed != null && armed.pairedBotAlias() != null ? armed.pairedBotAlias()
                : (adminStart ? blankToNull(adminDefaultPairAlias) : null));
        String note = parsed != null && parsed.note != null ? parsed.note
                : (armed != null ? armed.note() : null);

        Integer radiusOverride = parsed != null && parsed.radiusOverride != null ? parsed.radiusOverride
                : (armed != null ? armed.radiusOverride() : null);
        Integer tickHzOverride = parsed != null && parsed.tickHzOverride != null ? parsed.tickHzOverride
                : (armed != null ? armed.tickHzOverride() : null);

        SamplingConfig sampling = computeSamplingConfig(profile, detail, radiusOverride, tickHzOverride);
        UUID pairedBotUuid = resolvePairedBotUuid(server, pairedBotAlias);
        if (pairedBotAlias != null && pairedBotUuid == null) {
            out.add("Paired bot alias not found ('" + pairedBotAlias + "'); recording player-only.");
        }

        long sessionToken = Math.max(1L, SESSION_COUNTER.incrementAndGet());
        String sessionId = System.currentTimeMillis() + "-" + sessionToken;
        String worldKey = worldKey(server);
        String dimensionId = dimensionId(world);
        Path dir = worldLearningDir(server, world).resolve(sessionId);
        Path sessionFile = dir.resolve("session.json");
        Path eventsFile = dir.resolve("events.jsonl");
        Path summaryFile = dir.resolve("summary.json");

        BufferedWriter writer;
        try {
            Files.createDirectories(dir);
            writer = Files.newBufferedWriter(eventsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOGGER.warn("Failed to open learning trace file: {}", e.getMessage());
            out.add("Failed to start learning session (trace file error): " + e.getMessage());
            return false;
        }

        ActiveLearningSession session = new ActiveLearningSession(
                player.getUuid(),
                player.getName().getString(),
                sessionToken,
                sessionId,
                worldKey,
                dimensionId,
                goal,
                profile,
                detail,
                sampling,
                blankToNull(pairedBotAlias),
                blankToNull(note),
                pairedBotUuid,
                dir,
                sessionFile,
                eventsFile,
                summaryFile,
                writer
        );
        ACTIVE_SESSIONS.put(player.getUuid(), session);
        LATEST_CLIENT_INPUT.remove(player.getUuid());

        writeSessionMetadata(session);
        JsonObject startData = new JsonObject();
        startData.addProperty("goalTag", goal);
        startData.addProperty("profile", profile.name().toLowerCase(Locale.ROOT));
        startData.addProperty("detailTier", detail.name().toLowerCase(Locale.ROOT));
        startData.addProperty("pairedBotAlias", session.pairedBotAlias != null ? session.pairedBotAlias : "");
        startData.addProperty("pairedBotResolved", session.pairedBotUuid != null);
        startData.add("sampling", jsonSampling(session.sampling));
        recordEvent(session, world, player, "SESSION_START", startData);

        notifyClientStreamState(player, true, session.sessionToken, session.sampling.clientTickHz());

        out.add("Learning session started.");
        out.add("session=" + session.sessionId + " goal=" + session.goalTag
                + " profile=" + session.profile.name().toLowerCase(Locale.ROOT)
                + " detail=" + session.detailTier.name().toLowerCase(Locale.ROOT));
        out.add("trace=" + relativizeOrAbsolute(session.eventsJsonlPath));
        return true;
    }

    private static boolean stopSession(ServerPlayerEntity player, LearningStopOutcome outcome, String reason, List<String> out) {
        if (player == null) {
            out.add("Learning mode stop failed: player unavailable.");
            return false;
        }
        ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
        if (session == null) {
            out.add("No active learning session.");
            return false;
        }
        MinecraftServer server = player.getEntityWorld() instanceof ServerWorld swSrv ? swSrv.getServer() : null;
        stopSessionInternal(server, session,
                player.getEntityWorld() instanceof ServerWorld sw ? sw : null,
                player, outcome, reason, out);
        return true;
    }

    private static void stopSessionInternal(MinecraftServer server,
                                            ActiveLearningSession session,
                                            ServerWorld world,
                                            ServerPlayerEntity player,
                                            LearningStopOutcome outcome,
                                            String reason,
                                            List<String> out) {
        if (session == null || session.closed) return;
        session.endEpochMs = System.currentTimeMillis();
        session.outcome = outcome != null ? outcome.name().toLowerCase(Locale.ROOT) : LearningStopOutcome.ABORT.name().toLowerCase(Locale.ROOT);
        session.stopReason = reason == null ? "" : reason;
        if (reason != null && !reason.isBlank()) {
            session.notes.add("stopReason:" + reason);
        }

        if (world == null && player != null && player.getEntityWorld() instanceof ServerWorld sw) {
            world = sw;
        }

        JsonObject stopData = new JsonObject();
        stopData.addProperty("outcome", session.outcome);
        stopData.addProperty("reason", session.stopReason);
        stopData.addProperty("durationMs", Math.max(0L, session.endEpochMs - session.startEpochMs));
        recordEvent(session, world, player, "SESSION_STOP", stopData);

        session.closed = true;
        flushEvents(session);
        closeQuietly(session.eventsWriter);
        writeSessionMetadata(session);
        writeSummary(session);

        ACTIVE_SESSIONS.remove(session.operatorUuid);
        LATEST_CLIENT_INPUT.remove(session.operatorUuid);
        if (player != null) {
            notifyClientStreamState(player, false, 0L, 0);
        } else if (server != null) {
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(session.operatorUuid);
            if (online != null) {
                notifyClientStreamState(online, false, 0L, 0);
            }
        }

        if (out != null) {
            out.add("Learning session stopped (" + session.outcome + ").");
            out.add("session=" + session.sessionId + " duration=" + formatDurationMs(session.endEpochMs - session.startEpochMs)
                    + " events=" + totalEventCount(session.eventCounts));
            out.add("summary=" + relativizeOrAbsolute(session.summaryJsonPath));
        }
    }

    private static boolean addMark(ServerPlayerEntity player, String optionsRaw, List<String> out) {
        if (player == null || player.isRemoved()) {
            out.add("Learning mark failed: player unavailable.");
            return false;
        }
        ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
        if (session == null) {
            out.add("No active learning session.");
            return false;
        }
        ParsedLearningOptions parsed = parseOptions(optionsRaw, Set.of("label", "note"), out);
        if (parsed == null) return false;
        String label = parsed.label != null ? parsed.label : "mark";
        String note = parsed.note != null ? parsed.note : "";
        ServerWorld world = player.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        JsonObject data = new JsonObject();
        data.addProperty("label", label);
        data.addProperty("note", note);
        recordEvent(session, world, player, "MARK", data);
        session.marks++;
        out.add("Learning mark recorded: " + label + (note.isBlank() ? "" : " (" + note + ")"));
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Tick sampling and inference
    // ─────────────────────────────────────────────────────────────────────────────

    private static void tickSession(MinecraftServer server, ActiveLearningSession session, ServerPlayerEntity player, ServerWorld world) {
        session.localTickCounter++;
        long now = System.currentTimeMillis();

        DerivedTickState current = deriveTickState(player, world);
        maybeInferStateTransitions(session, player, world, current);
        maybeInferPlacementResults(session, player, world);

        if (now - session.startEpochMs > MAX_SESSION_DURATION_MS) {
            recordWarning(session, world, player, "durationCap", "max-session-duration-ms reached");
            stopSessionInternal(server, session, world, player, LearningStopOutcome.ABORT, "duration-cap", null);
            return;
        }
        if (session.approxEventBytes > MAX_EVENT_BYTES) {
            recordWarning(session, world, player, "fileSizeCap", "max-event-bytes reached");
            stopSessionInternal(server, session, world, player, LearningStopOutcome.ABORT, "file-size-cap", null);
            return;
        }

        if (session.clientTelemetrySupported && !session.warnedMissingClientTelemetry) {
            if (session.lastClientInputSeenMs <= 0 && (now - session.startEpochMs) >= CLIENT_INPUT_WARN_DELAY_MS) {
                session.warnedMissingClientTelemetry = true;
                recordWarning(session, world, player, "clientInputMissing", "no client input telemetry received yet");
            }
        }

        if (session.lastDerived != null) {
            session.totalDistance += session.lastDerived.pos().distanceTo(current.pos());
        }
        if (current.sneaking()) session.sneakTicks++;
        if (current.sprinting()) session.sprintTicks++;

        boolean sampleTick = (session.localTickCounter % Math.max(1, session.sampling.tickSampleEveryTicks())) == 0;
        if (sampleTick) {
            JsonObject sample = buildTickSample(session, player, world, current, now);
            recordEvent(session, world, player, "TICK_SAMPLE", sample);
            session.tickSamples++;
            maybeRecordPairedBotTick(session, world);
        }

        session.lastDerived = current;
        maybePeriodicFlush(session, now);
    }

    private static DerivedTickState deriveTickState(ServerPlayerEntity player, ServerWorld world) {
        return new DerivedTickState(
                new Vec3d(player.getX(), player.getY(), player.getZ()),
                player.getVelocity(),
                player.getBlockPos().toImmutable(),
                dimensionId(world),
                player.isOnGround(),
                player.isSneaking(),
                player.isSprinting(),
                player.isSwimming(),
                player.isClimbing(),
                player.getInventory().getSelectedSlot()
        );
    }

    private static void maybeInferStateTransitions(ActiveLearningSession session,
                                                   ServerPlayerEntity player,
                                                   ServerWorld world,
                                                   DerivedTickState current) {
        DerivedTickState prev = session.lastDerived;
        if (prev == null) return;

        if (prev.selectedSlot() != current.selectedSlot()) {
            JsonObject data = new JsonObject();
            data.addProperty("from", prev.selectedSlot());
            data.addProperty("to", current.selectedSlot());
            data.addProperty("item", itemId(player.getMainHandStack()));
            recordEvent(session, world, player, "HOTBAR_CHANGE", data);
        }
        if (prev.sneaking() != current.sneaking()) {
            JsonObject data = new JsonObject();
            data.addProperty("active", current.sneaking());
            recordEvent(session, world, player, "SNEAK_STATE_CHANGE", data);
        }
        if (prev.sprinting() != current.sprinting()) {
            JsonObject data = new JsonObject();
            data.addProperty("active", current.sprinting());
            recordEvent(session, world, player, "SPRINT_STATE_CHANGE", data);
        }

        boolean jumpInferred = prev.onGround() && !current.onGround() && (current.pos().y - prev.pos().y) > 0.12D;
        if (jumpInferred) {
            JsonObject data = new JsonObject();
            data.addProperty("deltaY", current.pos().y - prev.pos().y);
            recordEvent(session, world, player, "JUMP_INFERRED", data);
            session.jumps++;
        }

        boolean startedFalling = !current.onGround() && current.vel().y < -0.25D && (prev.vel().y >= -0.25D || prev.onGround());
        if (startedFalling) {
            JsonObject data = new JsonObject();
            data.addProperty("velY", current.vel().y);
            recordEvent(session, world, player, "FALL_EVENT", data);
            session.falls++;
        }

        boolean landed = !prev.onGround() && current.onGround();
        if (landed) {
            JsonObject data = new JsonObject();
            data.addProperty("velYBefore", prev.vel().y);
            data.addProperty("deltaY", current.pos().y - prev.pos().y);
            recordEvent(session, world, player, "LAND_EVENT", data);
            session.lands++;
        }
    }

    private static void maybeInferPlacementResults(ActiveLearningSession session, ServerPlayerEntity player, ServerWorld world) {
        if (session.pendingPlacementAttempts.isEmpty()) return;
        long nowTick = SERVER_TICK_COUNTER;
        int processed = 0;
        for (var it = session.pendingPlacementAttempts.iterator(); it.hasNext(); ) {
            PendingPlacementAttempt attempt = it.next();
            if (attempt == null || attempt.pos() == null) {
                it.remove();
                continue;
            }
            if (processed > MAX_PENDING_PLACE_ATTEMPTS) break;
            processed++;
            BlockState current = world.getBlockState(attempt.pos());
            String currentId = blockId(current);
            if (!Objects.equals(currentId, attempt.beforeBlockId())) {
                JsonObject data = new JsonObject();
                data.add("pos", jsonPos(attempt.pos()));
                data.addProperty("result", "success_inferred");
                data.addProperty("beforeBlock", attempt.beforeBlockId());
                data.addProperty("afterBlock", currentId);
                data.addProperty("heldBlock", attempt.heldBlockId());
                recordEvent(session, world, player, "BLOCK_PLACE_RESULT", data);
                session.blockPlaceResults++;
                it.remove();
                continue;
            }
            if (nowTick >= attempt.expiresTick()) {
                JsonObject data = new JsonObject();
                data.add("pos", jsonPos(attempt.pos()));
                data.addProperty("result", "timeout_no_change");
                data.addProperty("beforeBlock", attempt.beforeBlockId());
                data.addProperty("afterBlock", currentId);
                data.addProperty("heldBlock", attempt.heldBlockId());
                recordEvent(session, world, player, "BLOCK_PLACE_RESULT", data);
                session.blockPlaceResults++;
                it.remove();
            }
        }
    }

    private static JsonObject buildTickSample(ActiveLearningSession session,
                                              ServerPlayerEntity player,
                                              ServerWorld world,
                                              DerivedTickState state,
                                              long nowMs) {
        JsonObject data = new JsonObject();

        JsonObject velocity = new JsonObject();
        velocity.addProperty("x", round3(state.vel().x));
        velocity.addProperty("y", round3(state.vel().y));
        velocity.addProperty("z", round3(state.vel().z));
        data.add("velocity", velocity);

        data.addProperty("onGround", state.onGround());
        data.addProperty("isSneaking", state.sneaking());
        data.addProperty("isSprinting", state.sprinting());
        data.addProperty("isSwimming", state.swimming());
        data.addProperty("isClimbing", state.climbing());
        data.addProperty("selectedSlot", state.selectedSlot());
        data.addProperty("selectedItem", itemId(player.getMainHandStack()));
        data.addProperty("health", round2(player.getHealth()));
        data.addProperty("hunger", player.getHungerManager().getFoodLevel());
        data.addProperty("saturation", round2(player.getHungerManager().getSaturationLevel()));
        data.addProperty("xpLevel", player.experienceLevel);
        data.addProperty("fallDistance", round2(player.fallDistance));

        HitResult ray = player.raycast(6.0D, 1.0F, false);
        if (ray != null) {
            JsonObject rayObj = new JsonObject();
            rayObj.addProperty("type", ray.getType().name().toLowerCase(Locale.ROOT));
            rayObj.addProperty("distance", round3(player.getEyePos().distanceTo(ray.getPos())));
            if (ray instanceof BlockHitResult bhr) {
                BlockPos hitPos = bhr.getBlockPos();
                rayObj.add("blockPos", jsonPos(hitPos));
                rayObj.addProperty("side", bhr.getSide().asString());
                rayObj.addProperty("block", blockId(world.getBlockState(hitPos)));
            }
            data.add("raycast", rayObj);
        }

        JsonObject collision = new JsonObject();
        collision.addProperty("feetBlocked", hasCollision(world, state.blockPos()));
        collision.addProperty("headBlocked", hasCollision(world, state.blockPos().up()));
        collision.addProperty("standable", canStandAt(world, state.blockPos()));
        collision.addProperty("edgeRisk", hasEdgeRisk(world, state.blockPos()));
        data.add("collision", collision);

        if (session.lastDerived != null) {
            JsonObject delta = new JsonObject();
            delta.addProperty("dx", round3(state.pos().x - session.lastDerived.pos().x));
            delta.addProperty("dy", round3(state.pos().y - session.lastDerived.pos().y));
            delta.addProperty("dz", round3(state.pos().z - session.lastDerived.pos().z));
            delta.addProperty("distance", round3(session.lastDerived.pos().distanceTo(state.pos())));
            data.add("delta", delta);
        }

        LearningClientInputState input = LATEST_CLIENT_INPUT.get(player.getUuid());
        if (input != null && input.sessionToken() == session.sessionToken && (nowMs - input.receivedAtMs()) <= 1000L) {
            JsonObject inputObj = new JsonObject();
            inputObj.addProperty("ageMs", nowMs - input.receivedAtMs());
            inputObj.addProperty("sampleSeq", input.sampleSeq());
            inputObj.addProperty("flags", input.flags());
            inputObj.addProperty("selectedSlot", input.selectedSlot());
            inputObj.addProperty("yaw", input.yaw());
            inputObj.addProperty("pitch", input.pitch());
            data.add("clientInputLatest", inputObj);
        }

        boolean includeVoxel = session.detailTier != LearningDetailTier.CORE
                && (session.localTickCounter % Math.max(1, session.sampling.voxelEveryTicks())) == 0;
        if (includeVoxel) {
            data.add("localVoxelSnapshot", buildLocalVoxelSnapshot(world, state.blockPos(), session.sampling));
        }

        return data;
    }

    private static void maybeRecordPairedBotTick(ActiveLearningSession session, ServerWorld world) {
        if (session == null || session.pairedBotUuid == null || world == null) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;
        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(session.pairedBotUuid);
        if (!(bot instanceof createFakePlayer) || bot.isRemoved()) {
            if (session.pairedBotAlias != null && session.pairedBotUuid != null) {
                recordWarning(session, world, null, "pairedBotMissing", "paired bot not currently online: " + session.pairedBotAlias);
            }
            session.pairedBotUuid = null;
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("botAlias", bot.getName().getString());
        data.add("botPos", jsonPos(bot.getBlockPos()));
        JsonObject vel = new JsonObject();
        vel.addProperty("x", round3(bot.getVelocity().x));
        vel.addProperty("y", round3(bot.getVelocity().y));
        vel.addProperty("z", round3(bot.getVelocity().z));
        data.add("velocity", vel);
        data.addProperty("onGround", bot.isOnGround());
        data.addProperty("isSneaking", bot.isSneaking());
        data.addProperty("selectedItem", itemId(bot.getMainHandStack()));
        TaskService.getActiveTaskInfo(bot.getUuid()).ifPresent(info -> {
            JsonObject task = new JsonObject();
            task.addProperty("name", info.name());
            task.addProperty("state", info.state().name().toLowerCase(Locale.ROOT));
            task.addProperty("origin", info.origin().name().toLowerCase(Locale.ROOT));
            task.addProperty("cancelRequested", info.cancelRequested());
            data.add("task", task);
        });
        recordEvent(session, world, null, "BOT_PAIR_TICK", data);
        session.botPairTickSamples++;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Event recording / persistence
    // ─────────────────────────────────────────────────────────────────────────────

    private static void recordWarning(ActiveLearningSession session,
                                      ServerWorld world,
                                      ServerPlayerEntity player,
                                      String warningKey,
                                      String message) {
        if (session == null || session.closed) return;
        session.warnings++;
        session.warningCounts.merge(warningKey, 1, Integer::sum);
        JsonObject data = new JsonObject();
        data.addProperty("warningKey", warningKey);
        data.addProperty("message", message);
        recordEvent(session, world, player, "SESSION_WARNING", data);
    }

    private static void recordEvent(ActiveLearningSession session,
                                    ServerWorld world,
                                    ServerPlayerEntity player,
                                    String eventType,
                                    JsonObject data) {
        if (session == null || session.closed || session.eventsWriter == null) return;
        JsonObject event = new JsonObject();
        long now = System.currentTimeMillis();
        event.addProperty("tsMs", now);
        event.addProperty("serverTick", SERVER_TICK_COUNTER);
        event.addProperty("sessionId", session.sessionId);
        event.addProperty("sessionToken", session.sessionToken);
        event.addProperty("seq", ++session.seq);
        event.addProperty("eventType", eventType);
        event.addProperty("goalTag", session.goalTag);

        String dim = world != null ? dimensionId(world)
                : (player != null && player.getEntityWorld() instanceof ServerWorld sw ? dimensionId(sw) : session.startDimensionId);
        event.addProperty("dim", dim);

        JsonObject playerObj = new JsonObject();
        if (player != null) {
            playerObj.addProperty("uuid", player.getUuidAsString());
            playerObj.addProperty("name", player.getName().getString());
            playerObj.add("pos", jsonVec3(new Vec3d(player.getX(), player.getY(), player.getZ())));
            playerObj.add("blockPos", jsonPos(player.getBlockPos()));
            playerObj.addProperty("yaw", round2(player.getYaw()));
            playerObj.addProperty("pitch", round2(player.getPitch()));
        } else {
            playerObj.addProperty("uuid", session.operatorUuid.toString());
            playerObj.addProperty("name", session.operatorName);
        }
        event.add("player", playerObj);
        if (data != null) {
            event.add("data", data);
        }

        String line = GSON_COMPACT.toJson(event);
        try {
            session.eventsWriter.write(line);
            session.eventsWriter.newLine();
            session.approxEventBytes += line.length() + 1L;
            session.eventCounts.merge(eventType, 1, Integer::sum);
        } catch (IOException e) {
            LOGGER.warn("LearningMode event write failed (session={}): {}", session.sessionId, e.getMessage());
            // Mark and stop safely; avoid recursive event writes.
            closeQuietly(session.eventsWriter);
            session.closed = true;
            ACTIVE_SESSIONS.remove(session.operatorUuid);
        }
    }

    private static void flushEvents(ActiveLearningSession session) {
        if (session == null || session.eventsWriter == null) return;
        try {
            session.eventsWriter.flush();
            session.lastFlushMs = System.currentTimeMillis();
        } catch (IOException e) {
            LOGGER.debug("LearningMode flush failed for {}: {}", session.sessionId, e.getMessage());
        }
    }

    private static void maybePeriodicFlush(ActiveLearningSession session, long nowMs) {
        if (session == null || session.eventsWriter == null) return;
        if (nowMs - session.lastFlushMs >= PERIODIC_FLUSH_MS) {
            flushEvents(session);
        }
    }

    private static void writeSessionMetadata(ActiveLearningSession session) {
        if (session == null) return;
        JsonObject root = new JsonObject();
        root.addProperty("sessionId", session.sessionId);
        root.addProperty("sessionToken", session.sessionToken);
        root.addProperty("worldKey", session.worldKey);
        root.addProperty("dimension", session.startDimensionId);
        root.addProperty("operatorUuid", session.operatorUuid.toString());
        root.addProperty("operatorName", session.operatorName);
        root.addProperty("goalTag", session.goalTag);
        root.addProperty("profile", session.profile.name().toLowerCase(Locale.ROOT));
        root.addProperty("detailTier", session.detailTier.name().toLowerCase(Locale.ROOT));
        root.addProperty("pairedBotAlias", session.pairedBotAlias != null ? session.pairedBotAlias : "");
        root.addProperty("pairedBotUuid", session.pairedBotUuid != null ? session.pairedBotUuid.toString() : "");
        root.addProperty("startEpochMs", session.startEpochMs);
        root.addProperty("endEpochMs", session.endEpochMs);
        root.addProperty("outcome", session.outcome);
        root.addProperty("stopReason", session.stopReason != null ? session.stopReason : "");
        root.addProperty("modVersion", modVersion());
        root.add("samplingConfig", jsonSampling(session.sampling));
        root.add("aggregateCounts", jsonAggregateCounts(session));
        JsonArray notes = new JsonArray();
        for (String note : session.notes) {
            if (note != null && !note.isBlank()) notes.add(note);
        }
        root.add("notes", notes);
        root.addProperty("eventsFile", relativizeOrAbsolute(session.eventsJsonlPath));
        root.addProperty("summaryFile", relativizeOrAbsolute(session.summaryJsonPath));
        try {
            Files.createDirectories(session.sessionJsonPath.getParent());
            try (Writer writer = Files.newBufferedWriter(session.sessionJsonPath)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to write learning session metadata {}: {}", session.sessionId, e.getMessage());
        }
    }

    private static void writeSummary(ActiveLearningSession session) {
        if (session == null) return;
        JsonObject summary = new JsonObject();
        summary.addProperty("sessionId", session.sessionId);
        summary.addProperty("goalTag", session.goalTag);
        summary.addProperty("profile", session.profile.name().toLowerCase(Locale.ROOT));
        summary.addProperty("detailTier", session.detailTier.name().toLowerCase(Locale.ROOT));
        summary.addProperty("operatorName", session.operatorName);
        summary.addProperty("outcome", session.outcome);
        summary.addProperty("durationMs", Math.max(0L, (session.endEpochMs > 0 ? session.endEpochMs : System.currentTimeMillis()) - session.startEpochMs));
        summary.addProperty("totalEvents", totalEventCount(session.eventCounts));
        summary.addProperty("tickSamples", session.tickSamples);
        summary.addProperty("clientInputSamples", session.clientInputSamples);
        summary.addProperty("useBlockEvents", session.useBlockEvents);
        summary.addProperty("useItemEvents", session.useItemEvents);
        summary.addProperty("attackBlockEvents", session.attackBlockEvents);
        summary.addProperty("blockBreakEvents", session.blockBreakEvents);
        summary.addProperty("blockPlaceAttempts", session.blockPlaceAttempts);
        summary.addProperty("blockPlaceResults", session.blockPlaceResults);
        summary.addProperty("botPairTickSamples", session.botPairTickSamples);
        summary.addProperty("warnings", session.warnings);
        summary.addProperty("jumps", session.jumps);
        summary.addProperty("falls", session.falls);
        summary.addProperty("lands", session.lands);
        summary.addProperty("damageEvents", session.damageEvents);
        summary.addProperty("sneakTicks", session.sneakTicks);
        summary.addProperty("sprintTicks", session.sprintTicks);
        summary.addProperty("dimensionChanges", session.dimensionChanges);
        summary.addProperty("distanceMoved", round3(session.totalDistance));
        summary.add("eventCounts", mapToJson(session.eventCounts));
        summary.add("warningCounts", mapToJson(session.warningCounts));
        JsonArray warningsHeur = new JsonArray();
        if (!session.clientTelemetrySupported) warningsHeur.add("client telemetry payload unsupported");
        if (session.clientInputSamples == 0) warningsHeur.add("no client input telemetry received");
        if (session.blockPlaceAttempts > 0 && session.blockPlaceResults == 0) warningsHeur.add("no inferred placement results detected");
        summary.add("warningsSummary", warningsHeur);
        JsonArray failureClusters = new JsonArray();
        if (session.falls > 0) failureClusters.add("falls:" + session.falls);
        if (session.warningCounts.getOrDefault("clientInputMissing", 0) > 0) failureClusters.add("missingClientInput");
        if (session.blockPlaceAttempts > session.blockPlaceResults) failureClusters.add("placeTimeoutOrNoChange");
        summary.add("topFailureClusters", failureClusters);
        summary.addProperty("eventsFile", relativizeOrAbsolute(session.eventsJsonlPath));
        summary.addProperty("sessionFile", relativizeOrAbsolute(session.sessionJsonPath));
        try {
            Files.createDirectories(session.summaryJsonPath.getParent());
            try (Writer writer = Files.newBufferedWriter(session.summaryJsonPath)) {
                GSON.toJson(summary, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to write learning summary {}: {}", session.sessionId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Status / list / report
    // ─────────────────────────────────────────────────────────────────────────────

    private static List<String> statusLines(ServerCommandSource source) {
        if (!Frens.isOperator(source)) {
            return List.of("You must be an operator to use /bot learn.");
        }
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return List.of("Learning mode: console has no active operator session context.",
                    "Use /bot learn list and /bot learn report <id|latest> from console.");
        }
        return statusLinesForPlayer(player);
    }

    private static List<String> statusLinesForPlayer(ServerPlayerEntity player) {
        List<String> out = new ArrayList<>();
        if (player == null) {
            out.add("Learning mode unavailable (no player).");
            return out;
        }
        ArmedLearningPreset preset = ARMED_PRESETS.get(player.getUuid());
        ActiveLearningSession session = ACTIVE_SESSIONS.get(player.getUuid());
        out.add("Learning mode status:");
        if (preset == null) {
            out.add("- Armed preset: none");
        } else {
            out.add("- Armed preset: goal=" + preset.goalTag()
                    + " profile=" + preset.profile().name().toLowerCase(Locale.ROOT)
                    + " detail=" + preset.detailTier().name().toLowerCase(Locale.ROOT)
                    + (preset.pairedBotAlias() != null ? " pair=" + preset.pairedBotAlias() : ""));
        }
        if (session == null) {
            out.add("- Active session: none");
            return out;
        }
        long elapsed = System.currentTimeMillis() - session.startEpochMs;
        out.add("- Active session: yes");
        out.add("- session=" + session.sessionId + " goal=" + session.goalTag + " elapsed=" + formatDurationMs(elapsed));
        out.add("- profile=" + session.profile.name().toLowerCase(Locale.ROOT)
                + " detail=" + session.detailTier.name().toLowerCase(Locale.ROOT)
                + (session.pairedBotAlias != null ? " pair=" + session.pairedBotAlias : ""));
        out.add("- counts: events=" + totalEventCount(session.eventCounts)
                + " ticks=" + session.tickSamples
                + " marks=" + session.marks
                + " breaks=" + session.blockBreakEvents
                + " placeAttempts=" + session.blockPlaceAttempts
                + " placeResults=" + session.blockPlaceResults);
        out.add("- client input: " + (session.clientInputSamples > 0 ? ("ok (" + session.clientInputSamples + ")") : "missing / waiting"));
        return out;
    }

    private static List<String> listSessions(ServerCommandSource source, int count) {
        List<String> out = new ArrayList<>();
        ServerWorld world = safeSourceWorld(source);
        if (world == null) {
            out.add("Learning list failed: world unavailable.");
            return out;
        }
        Path dir = worldLearningDir(source.getServer(), world);
        if (!Files.isDirectory(dir)) {
            out.add("No learning sessions recorded yet.");
            return out;
        }
        List<SessionListEntry> entries = loadSessionList(dir);
        entries.sort(Comparator.comparingLong(SessionListEntry::startEpochMs).reversed());
        if (entries.isEmpty()) {
            out.add("No learning sessions recorded yet.");
            return out;
        }
        out.add("Learning sessions (" + Math.min(entries.size(), count) + "/" + entries.size() + "):");
        int shown = 0;
        for (SessionListEntry entry : entries) {
            if (shown++ >= count) break;
            long durationMs = (entry.endEpochMs() > 0L ? entry.endEpochMs() : System.currentTimeMillis()) - entry.startEpochMs();
            out.add("- " + entry.sessionId()
                    + " goal=" + safe(entry.goalTag(), "unknown")
                    + " outcome=" + safe(entry.outcome(), "active")
                    + " by=" + safe(entry.operatorName(), "unknown")
                    + " duration=" + formatDurationMs(durationMs));
        }
        return out;
    }

    private static List<String> reportSession(ServerCommandSource source, String sessionRefRaw) {
        List<String> out = new ArrayList<>();
        ServerWorld world = safeSourceWorld(source);
        if (world == null) {
            out.add("Learning report failed: world unavailable.");
            return out;
        }
        Path worldDir = worldLearningDir(source.getServer(), world);
        if (!Files.isDirectory(worldDir)) {
            out.add("No learning sessions recorded yet.");
            return out;
        }
        String sessionRef = (sessionRefRaw == null || sessionRefRaw.isBlank()) ? "latest" : sessionRefRaw.trim();
        Path sessionDir = resolveSessionDir(worldDir, sessionRef);
        if (sessionDir == null || !Files.isDirectory(sessionDir)) {
            out.add("Could not find learning session: " + sessionRef);
            return out;
        }
        Path summaryPath = sessionDir.resolve("summary.json");
        Path sessionPath = sessionDir.resolve("session.json");
        if (!Files.exists(summaryPath) && Files.exists(sessionPath)) {
            out.add("summary.json missing; session may still be active or ended abruptly.");
        }
        JsonObject summary = readJson(summaryPath);
        JsonObject session = readJson(sessionPath);
        if (summary == null && session == null) {
            out.add("Could not read session metadata/summary.");
            return out;
        }
        String sessionId = str(summary, "sessionId", str(session, "sessionId", sessionDir.getFileName().toString()));
        out.add("Learning report: " + sessionId);
        out.add("- goal=" + str(summary, "goalTag", str(session, "goalTag", "unknown"))
                + " profile=" + str(summary, "profile", str(session, "profile", "unknown"))
                + " detail=" + str(summary, "detailTier", str(session, "detailTier", "unknown")));
        out.add("- outcome=" + str(summary, "outcome", str(session, "outcome", "unknown"))
                + " duration=" + formatDurationMs(longVal(summary, "durationMs",
                Math.max(0L, longVal(session, "endEpochMs", 0L) - longVal(session, "startEpochMs", 0L)))));
        out.add("- totals: events=" + longVal(summary, "totalEvents", -1L)
                + " tickSamples=" + longVal(summary, "tickSamples", -1L)
                + " clientInput=" + longVal(summary, "clientInputSamples", -1L)
                + " breaks=" + longVal(summary, "blockBreakEvents", -1L)
                + " placeAttempts=" + longVal(summary, "blockPlaceAttempts", -1L)
                + " placeResults=" + longVal(summary, "blockPlaceResults", -1L));
        out.add("- movement: distance=" + dblVal(summary, "distanceMoved", -1D)
                + " jumps=" + longVal(summary, "jumps", -1L)
                + " falls=" + longVal(summary, "falls", -1L)
                + " lands=" + longVal(summary, "lands", -1L)
                + " sneakTicks=" + longVal(summary, "sneakTicks", -1L));
        out.add("- warnings=" + longVal(summary, "warnings", -1L)
                + " pairedBotSamples=" + longVal(summary, "botPairTickSamples", -1L));
        out.add("- files: " + relativizeOrAbsolute(sessionDir.resolve("events.jsonl")));
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Option parsing / helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static ParsedLearningOptions parseOptions(String raw, Set<String> allowedKeys, List<String> out) {
        ParsedLearningOptions parsed = new ParsedLearningOptions();
        if (raw == null || raw.isBlank()) {
            return parsed;
        }
        List<String> tokens = tokenizeOptions(raw);
        for (String token : tokens) {
            if (token == null || token.isBlank()) continue;
            int eq = token.indexOf('=');
            if (eq <= 0 || eq == token.length() - 1) {
                out.add("Invalid option token: " + token + " (expected key=value)");
                return null;
            }
            String key = normalizeOptionKey(token.substring(0, eq));
            String value = unquote(token.substring(eq + 1)).trim();
            if (!allowedKeys.contains(key)) {
                out.add("Unknown learning option: " + key);
                out.add("Allowed: " + String.join(", ", allowedKeys));
                return null;
            }
            switch (key) {
                case "goal" -> {
                    String goal = sanitizeTag(value, MAX_GOAL_LEN);
                    if (goal == null || goal.isBlank()) {
                        out.add("Invalid goal tag: " + value);
                        return null;
                    }
                    parsed.goalTag = goal;
                }
                case "profile" -> {
                    LearningProfile p = LearningProfile.parse(value);
                    if (p == null) {
                        out.add("Invalid profile: " + value + " (general|construction|navigation|combat)");
                        return null;
                    }
                    parsed.profile = p;
                }
                case "detail" -> {
                    LearningDetailTier d = LearningDetailTier.parse(value);
                    if (d == null) {
                        out.add("Invalid detail tier: " + value + " (core|balanced|heavy)");
                        return null;
                    }
                    parsed.detailTier = d;
                }
                case "pair" -> parsed.pairedBotAlias = sanitizeFreeText(value, MAX_GOAL_LEN);
                case "note" -> parsed.note = sanitizeFreeText(value, MAX_NOTE_LEN);
                case "label" -> {
                    String label = sanitizeTag(value, MAX_LABEL_LEN);
                    if (label == null || label.isBlank()) {
                        out.add("Invalid mark label: " + value);
                        return null;
                    }
                    parsed.label = label;
                }
                case "radius" -> {
                    try {
                        int r = Integer.parseInt(value);
                        if (r < 1 || r > 4) {
                            out.add("radius must be between 1 and 4.");
                            return null;
                        }
                        parsed.radiusOverride = r;
                    } catch (NumberFormatException e) {
                        out.add("Invalid radius value: " + value);
                        return null;
                    }
                }
                case "tickhz" -> {
                    try {
                        int hz = Integer.parseInt(value);
                        if (hz < 2 || hz > 20) {
                            out.add("tickHz must be between 2 and 20.");
                            return null;
                        }
                        parsed.tickHzOverride = hz;
                    } catch (NumberFormatException e) {
                        out.add("Invalid tickHz value: " + value);
                        return null;
                    }
                }
                default -> {
                    out.add("Unhandled option: " + key);
                    return null;
                }
            }
        }
        return parsed;
    }

    private static List<String> tokenizeOptions(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        Matcher m = OPTION_TOKEN_PATTERN.matcher(raw);
        while (m.find()) {
            String token = m.group();
            if (token != null && !token.isBlank()) {
                out.add(token);
            }
        }
        return out;
    }

    private static String normalizeOptionKey(String key) {
        return (key == null ? "" : key.trim().toLowerCase(Locale.ROOT)).replace("_", "").replace("-", "");
    }

    private static String unquote(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).replace("\\\"", "\"");
        }
        return v;
    }

    private static String sanitizeTag(String raw, int maxLen) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() && out.length() < maxLen; i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String sanitizeFreeText(String raw, int maxLen) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.length() > maxLen) {
            s = s.substring(0, maxLen);
        }
        return s;
    }

    private static ServerPlayerEntity requireOperatorPlayer(ServerCommandSource source, String action) {
        if (!Frens.isOperator(source)) {
            ChatUtils.sendSystemMessage(source, "You must be an operator to use /bot learn.");
            return null;
        }
        ServerPlayerEntity player = source.getPlayer();
        if (player == null || player instanceof createFakePlayer) {
            ChatUtils.sendSystemMessage(source, "Learning " + action + " requires a real in-game player.");
            return null;
        }
        return player;
    }

    private static SamplingConfig computeSamplingConfig(LearningProfile profile,
                                                       LearningDetailTier detail,
                                                       Integer radiusOverride,
                                                       Integer tickHzOverride) {
        int tickEvery;
        int voxelEvery;
        int radius;
        int down;
        int up;
        switch (detail) {
            case CORE -> {
                tickEvery = 2;
                voxelEvery = 999_999;
                radius = 0;
                down = 0;
                up = 0;
            }
            case HEAVY -> {
                tickEvery = 1;
                voxelEvery = 1;
                radius = 3;
                down = 2;
                up = 3;
            }
            case BALANCED -> {
                tickEvery = 1;
                voxelEvery = 2;
                radius = 2;
                down = 1;
                up = 2;
            }
            default -> {
                tickEvery = 1;
                voxelEvery = 2;
                radius = 2;
                down = 1;
                up = 2;
            }
        }
        if (profile == LearningProfile.COMBAT && detail != LearningDetailTier.CORE) {
            radius = Math.min(radius, 2);
            up = Math.min(up, 2);
        }
        if (radiusOverride != null) {
            radius = Math.max(1, Math.min(4, radiusOverride));
            down = Math.min(Math.max(1, radius), 2);
            up = Math.max(2, Math.min(radius + 1, 3));
        }
        int clientHzDefault = 20;
        int clientHz = tickHzOverride != null ? Math.max(2, Math.min(20, tickHzOverride)) : clientHzDefault;
        return new SamplingConfig(tickEvery, voxelEvery, radius, down, up, clientHz);
    }

    private static UUID resolvePairedBotUuid(MinecraftServer server, String alias) {
        if (server == null || alias == null || alias.isBlank()) return null;
        String normalized = alias.trim().toLowerCase(Locale.ROOT);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!(p instanceof createFakePlayer)) continue;
            if (p.getName().getString().trim().toLowerCase(Locale.ROOT).equals(normalized)) {
                return p.getUuid();
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // World/session filesystem helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static Path learningRootDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve("learning");
    }

    private static Path worldLearningDir(MinecraftServer server, ServerWorld world) {
        return learningRootDir().resolve(sanitizeFileComponent(worldKey(server)));
    }

    private static String worldKey(MinecraftServer server) {
        if (server == null || server.getSaveProperties() == null) return "default";
        String name = server.getSaveProperties().getLevelName();
        if (name == null || name.isBlank()) return "default";
        return name.trim();
    }

    private static String sanitizeFileComponent(String raw) {
        if (raw == null || raw.isBlank()) return "default";
        return raw.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private static String dimensionId(ServerWorld world) {
        if (world == null) return "unknown";
        try {
            return world.getRegistryKey().getValue().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String modVersion() {
        try {
            return FabricLoader.getInstance().getModContainer(Frens.MOD_ID)
                    .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static List<SessionListEntry> loadSessionList(Path worldDir) {
        List<SessionListEntry> out = new ArrayList<>();
        try (var stream = Files.list(worldDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                JsonObject session = readJson(dir.resolve("session.json"));
                if (session == null) return;
                out.add(new SessionListEntry(
                        str(session, "sessionId", dir.getFileName().toString()),
                        str(session, "goalTag", ""),
                        str(session, "outcome", ""),
                        longVal(session, "startEpochMs", 0L),
                        longVal(session, "endEpochMs", 0L),
                        str(session, "operatorName", ""),
                        dir
                ));
            });
        } catch (IOException e) {
            LOGGER.debug("LearningMode listSessions failed: {}", e.getMessage());
        }
        return out;
    }

    private static Path resolveSessionDir(Path worldDir, String ref) {
        if (worldDir == null || !Files.isDirectory(worldDir)) return null;
        String token = (ref == null || ref.isBlank()) ? "latest" : ref.trim();
        if ("latest".equalsIgnoreCase(token)) {
            try (var stream = Files.list(worldDir)) {
                return stream.filter(Files::isDirectory)
                        .max(Comparator.comparing(p -> p.getFileName().toString()))
                        .orElse(null);
            } catch (IOException e) {
                return null;
            }
        }
        Path exact = worldDir.resolve(token);
        if (Files.isDirectory(exact)) return exact;
        // Fuzzy prefix fallback
        try (var stream = Files.list(worldDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(token))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static JsonObject readJson(Path file) {
        if (file == null || !Files.exists(file)) return null;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement el = GSON.fromJson(reader, JsonElement.class);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            LOGGER.debug("LearningMode readJson failed {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static ServerWorld safeSourceWorld(ServerCommandSource source) {
        if (source == null) return null;
        try {
            ServerWorld w = source.getWorld();
            if (w != null) return w;
        } catch (Exception ignored) {
        }
        try {
            MinecraftServer server = source.getServer();
            return server != null ? server.getOverworld() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JSON helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static JsonObject jsonPos(BlockPos pos) {
        JsonObject obj = new JsonObject();
        if (pos == null) {
            obj.addProperty("x", 0);
            obj.addProperty("y", 0);
            obj.addProperty("z", 0);
            return obj;
        }
        obj.addProperty("x", pos.getX());
        obj.addProperty("y", pos.getY());
        obj.addProperty("z", pos.getZ());
        return obj;
    }

    private static JsonObject jsonVec3(Vec3d v) {
        JsonObject obj = new JsonObject();
        if (v == null) {
            obj.addProperty("x", 0.0);
            obj.addProperty("y", 0.0);
            obj.addProperty("z", 0.0);
            return obj;
        }
        obj.addProperty("x", round3(v.x));
        obj.addProperty("y", round3(v.y));
        obj.addProperty("z", round3(v.z));
        return obj;
    }

    private static JsonObject jsonSampling(SamplingConfig cfg) {
        JsonObject obj = new JsonObject();
        if (cfg == null) return obj;
        obj.addProperty("tickSampleEveryTicks", cfg.tickSampleEveryTicks());
        obj.addProperty("voxelEveryTicks", cfg.voxelEveryTicks());
        obj.addProperty("voxelRadiusXZ", cfg.voxelRadiusXZ());
        obj.addProperty("voxelDown", cfg.voxelDown());
        obj.addProperty("voxelUp", cfg.voxelUp());
        obj.addProperty("clientTickHz", cfg.clientTickHz());
        return obj;
    }

    private static JsonObject jsonAggregateCounts(ActiveLearningSession session) {
        JsonObject obj = new JsonObject();
        if (session == null) return obj;
        obj.addProperty("totalEvents", totalEventCount(session.eventCounts));
        obj.addProperty("tickSamples", session.tickSamples);
        obj.addProperty("clientInputSamples", session.clientInputSamples);
        obj.addProperty("marks", session.marks);
        obj.addProperty("useBlockEvents", session.useBlockEvents);
        obj.addProperty("useItemEvents", session.useItemEvents);
        obj.addProperty("attackBlockEvents", session.attackBlockEvents);
        obj.addProperty("blockBreakEvents", session.blockBreakEvents);
        obj.addProperty("blockPlaceAttempts", session.blockPlaceAttempts);
        obj.addProperty("blockPlaceResults", session.blockPlaceResults);
        obj.addProperty("botPairTickSamples", session.botPairTickSamples);
        obj.addProperty("warnings", session.warnings);
        obj.addProperty("jumps", session.jumps);
        obj.addProperty("falls", session.falls);
        obj.addProperty("lands", session.lands);
        obj.addProperty("damageEvents", session.damageEvents);
        obj.addProperty("sneakTicks", session.sneakTicks);
        obj.addProperty("sprintTicks", session.sprintTicks);
        obj.addProperty("dimensionChanges", session.dimensionChanges);
        obj.addProperty("distanceMoved", round3(session.totalDistance));
        obj.add("eventCounts", mapToJson(session.eventCounts));
        obj.add("warningCounts", mapToJson(session.warningCounts));
        return obj;
    }

    private static JsonObject mapToJson(Map<String, Integer> map) {
        JsonObject obj = new JsonObject();
        if (map == null) return obj;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getKey() == null) continue;
            obj.addProperty(e.getKey(), e.getValue() != null ? e.getValue() : 0);
        }
        return obj;
    }

    private static JsonArray buildLocalVoxelSnapshot(ServerWorld world, BlockPos center, SamplingConfig sampling) {
        JsonArray cells = new JsonArray();
        if (world == null || center == null || sampling == null || sampling.voxelRadiusXZ() <= 0) return cells;
        int r = sampling.voxelRadiusXZ();
        for (int dy = -sampling.voxelDown(); dy <= sampling.voxelUp(); dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    JsonObject cell = new JsonObject();
                    cell.addProperty("dx", dx);
                    cell.addProperty("dy", dy);
                    cell.addProperty("dz", dz);
                    cell.addProperty("block", blockId(state));
                    cell.addProperty("air", state.isAir());
                    cell.addProperty("replaceable", state.isReplaceable());
                    boolean solidCollision = hasCollision(world, pos);
                    cell.addProperty("solidCollision", solidCollision);
                    cell.addProperty("shapeClass", shapeClass(world, pos, state));
                    JsonObject hazard = new JsonObject();
                    hazard.addProperty("lava", state.isOf(Blocks.LAVA));
                    hazard.addProperty("water", state.isOf(Blocks.WATER));
                    hazard.addProperty("fire", state.isOf(Blocks.FIRE));
                    hazard.addProperty("campfire", state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE));
                    hazard.addProperty("magma", state.isOf(Blocks.MAGMA_BLOCK));
                    cell.add("hazard", hazard);
                    cells.add(cell);
                }
            }
        }
        return cells;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Misc helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static boolean isRecordableHuman(ServerPlayerEntity player) {
        return player != null && !player.isRemoved() && !(player instanceof createFakePlayer);
    }

    private static boolean hasCollision(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean canStandAt(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        if (hasCollision(world, pos) || hasCollision(world, pos.up())) return false;
        return hasCollision(world, pos.down());
    }

    private static boolean hasEdgeRisk(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos side = pos.offset(dir);
            if (hasCollision(world, side) || hasCollision(world, side.up())) continue;
            if (!hasCollision(world, side.down()) && !hasCollision(world, side.down(2))) {
                return true;
            }
        }
        return false;
    }

    private static String shapeClass(ServerWorld world, BlockPos pos, BlockState state) {
        if (world == null || pos == null || state == null) return "unknown";
        var shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return "empty";
        try {
            if (state.isFullCube(world, pos)) return "full";
        } catch (Throwable ignored) {
        }
        return "partial";
    }

    private static String blockId(BlockState state) {
        if (state == null) return "minecraft:air";
        try {
            return Registries.BLOCK.getId(state.getBlock()).toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "minecraft:air";
        try {
            return Registries.ITEM.getId(stack.getItem()).toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0D) / 1000.0D;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String relativizeOrAbsolute(Path path) {
        if (path == null) return "";
        try {
            Path cwd = Path.of("").toAbsolutePath().normalize();
            Path abs = path.toAbsolutePath().normalize();
            if (abs.startsWith(cwd)) {
                return cwd.relativize(abs).toString();
            }
            return abs.toString();
        } catch (Exception e) {
            return path.toString();
        }
    }

    private static void closeQuietly(BufferedWriter writer) {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }

    private static int totalEventCount(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) return 0;
        int total = 0;
        for (Integer v : counts.values()) {
            total += v != null ? v : 0;
        }
        return total;
    }

    private static String formatDurationMs(long durationMs) {
        long ms = Math.max(0L, durationMs);
        long seconds = ms / 1000L;
        long minutes = seconds / 60L;
        long remSeconds = seconds % 60L;
        if (minutes > 0) {
            return minutes + "m" + remSeconds + "s";
        }
        return remSeconds + "s";
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String str(JsonObject obj, String key, String fallback) {
        if (obj == null || key == null || !obj.has(key)) return fallback;
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long longVal(JsonObject obj, String key, long fallback) {
        if (obj == null || key == null || !obj.has(key)) return fallback;
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double dblVal(JsonObject obj, String key, double fallback) {
        if (obj == null || key == null || !obj.has(key)) return fallback;
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return fallback;
        }
    }
}
