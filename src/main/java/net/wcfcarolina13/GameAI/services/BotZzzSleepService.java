package net.wcfcarolina13.GameAI.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.createFakePlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Chat "zzz" sleep flow + failure-driven logoff/respawn cycle.
 *
 * <p>When a bot's commander types a "zzz" message (3+ z's, case-insensitive) in chat,
 * each of their Frens bots in the same dimension tries to sleep via
 * {@link SleepService#sleep}. If the bot fails to
 * sleep AND the sender is themselves sleeping {@link #FAILURE_DEADLINE_TICKS}
 * ticks later, the bot "logs off" — its fake-player entity is removed from
 * the world (state persisted) and the bot waits until either daytime or no
 * same-world non-bot player is sleeping, at which point it respawns at the
 * position where it logged off.</p>
 *
 * <p>Per-bot debouncing: once a bot enters any non-idle state in this
 * service's machine (ATTEMPTING / WAITING_LOGOFF / LOGGED_OFF), subsequent
 * "zzz" chat lines are ignored for that bot. Multiple players spamming
 * "zzzzz" produces exactly one sleep attempt per bot.</p>
 *
 * <p>Dimension philosophy matches the rest of the mod: bots in Nether/End
 * can't sleep at all (vanilla rule, enforced by
 * {@code SleepService.dimensionAllowsBeds}). The "logoff wait" check only
 * counts players in the bot's OWN dimension — a commander in the Nether
 * who can't sleep doesn't block the bot's wake conditions.</p>
 *
 * <p>Persistence: {@link #LOGGED_OFF} entries are written to a JSON sidecar
 * at {@code <configDir>/frens/sleep_logoff_state.json} on every state
 * change so the cycle survives server restart. Restored on first tick by
 * {@link #loadFromDisk}.</p>
 */
public final class BotZzzSleepService {

    private static final Logger LOGGER = LoggerFactory.getLogger("zzz-sleep");

    private static final AtomicInteger SLEEP_THREAD_ID = new AtomicInteger();
    private static volatile ExecutorService sleepExecutor = createSleepExecutor();

    /** How long after a failed sleep attempt before we decide whether to log off. */
    private static final int FAILURE_DEADLINE_TICKS = 400; // 20s

    /** Tick cadence for the state machine. 40 ticks = 2s — fast enough for the
     *  20s deadline to land within ±2s, slow enough to not be noisy. */
    private static final int TICK_INTERVAL = 40;

    /** Vanilla day-cycle constant. */
    private static final long DAY_TICKS = 24_000L;
    /** Daytime range: tod in [0, NIGHT_START) is daytime. */
    private static final long NIGHT_START = 12_000L;

    /** Chat trigger: 3 or more z's, nothing else. Case-insensitive. */
    private static final Pattern ZZZ_PATTERN = Pattern.compile("^z{3,}$", Pattern.CASE_INSENSITIVE);

    private static final String SIDECAR_NAME = "sleep_logoff_state.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private enum State { ATTEMPTING, WAITING_LOGOFF }

    private record StateEntry(State state, long deadlineTick, UUID senderUuid) {}

    /** Lightweight per-tick state for in-progress attempts and pending logoff. */
    private static final ConcurrentMap<UUID, StateEntry> ACTIVE = new ConcurrentHashMap<>();

    /** Persistent state for bots that have logged off and are waiting to respawn. */
    public static final class LoggedOff {
        public String botName;
        public double x, y, z;
        public float yaw, pitch;
        public String dimensionId;
        public String gameMode;
        public long loggedOffAtTickEpoch;

        public LoggedOff() {}

        LoggedOff(String name, Vec3d pos, float yaw, float pitch, String dim, String mode, long tick) {
            this.botName = name;
            this.x = pos.x; this.y = pos.y; this.z = pos.z;
            this.yaw = yaw; this.pitch = pitch;
            this.dimensionId = dim;
            this.gameMode = mode;
            this.loggedOffAtTickEpoch = tick;
        }
    }

    /** Bot-name → logoff entry. Name-keyed (not UUID) because after disconnect
     *  the ServerPlayerEntity is gone — we re-spawn by name via the same path
     *  as fast-travel respawn. */
    private static final ConcurrentMap<String, LoggedOff> LOGGED_OFF = new ConcurrentHashMap<>();

    /** Set true after first successful disk load — guards against repeating
     *  the load on every tick before the world is ready. */
    private static volatile boolean diskLoaded = false;

    private BotZzzSleepService() {}

    private static ExecutorService createSleepExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "zzz-sleep-" + SLEEP_THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    static Future<?> submitSleepAttempt(Runnable attempt) {
        return sleepExecutor.submit(attempt);
    }

    public static synchronized void restartExecutor() {
        if (sleepExecutor.isShutdown()) {
            sleepExecutor = createSleepExecutor();
        }
    }

    public static void shutdownExecutor() {
        sleepExecutor.shutdownNow();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public query — for other systems to defer.
    // ─────────────────────────────────────────────────────────────────────

    public static boolean isInSleepCycle(UUID botId) {
        return botId != null && ACTIVE.containsKey(botId);
    }

    public static boolean isLoggedOff(String botName) {
        return botName != null && LOGGED_OFF.containsKey(botName);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Chat trigger.
    // ─────────────────────────────────────────────────────────────────────

    /** Hook from {@code ServerMessageEvents.CHAT_MESSAGE}. Returns true if
     *  the message was recognized as a zzz trigger (caller may still want to
     *  let other handlers run, so this is informational only). */
    public static boolean handleChatTrigger(ServerPlayerEntity sender, String rawMessage) {
        if (sender == null || rawMessage == null) return false;
        String trimmed = rawMessage.trim();
        if (!ZZZ_PATTERN.matcher(trimmed).matches()) return false;
        MinecraftServer server = sender.getEntityWorld().getServer();
        if (server == null) return true;
        if (!(sender.getEntityWorld() instanceof ServerWorld senderWorld)) return true;

        // Chat is a direct command: route it to every bot this player controls in
        // the same dimension. Each bot searches for a bed around its own position.
        List<ServerPlayerEntity> controlledBots = new ArrayList<>();
        for (UUID botId : BotRegistry.ids()) {
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botId);
            if (bot == null || bot.isRemoved() || !bot.isAlive()) continue;
            ServerPlayerEntity commander = CompanionCommunicationPolicy.resolveController(server, bot);
            if (!isEligibleChatTarget(
                    bot.getEntityWorld() == senderWorld,
                    sender.getUuid(),
                    commander == null ? null : commander.getUuid())) continue;
            controlledBots.add(bot);
        }
        if (controlledBots.isEmpty()) return true;

        long nowTick = server.getTicks();
        // Pre-check world-level sleep eligibility ONCE — same for every bot in the world.
        // If it's daytime and not thundering, sleep is fundamentally impossible until
        // night. No point entering WAITING_LOGOFF — just tell the user and skip.
        boolean canSleepInWorld = !senderWorld.isDay() || senderWorld.isThundering();

        for (ServerPlayerEntity bot : controlledBots) {
            UUID botId = bot.getUuid();
            String botName = bot.getName().getString();
            ServerCommandSource source = sender.getCommandSource();

            // Verbose debounce feedback: tell the user WHY the second zzz is ignored
            // instead of silently dropping it. State-aware so they know if it's mid-attempt
            // or mid-logoff-wait.
            StateEntry existing = ACTIVE.get(botId);
            if (existing != null) {
                String msg = switch (existing.state()) {
                    case ATTEMPTING -> botName + " is already trying to sleep — give them a moment.";
                    case WAITING_LOGOFF -> botName + " already tried — waiting to see if you sleep.";
                };
                ChatUtils.sendSystemMessage(source, "§7" + msg + "§r");
                continue;
            }
            if (bot.isSleeping()) {
                ChatUtils.sendSystemMessage(source, "§7" + botName + " is already asleep.§r");
                continue;
            }
            // Vanilla refuses sleep while mounted. Skip the doomed 20s WAITING_LOGOFF wait.
            if (bot.hasVehicle()) {
                ChatUtils.sendSystemMessage(source,
                        "§7" + botName + " can't sleep while mounted — dismount them first.§r");
                continue;
            }
            // Not night / no thunder → vanilla refuses. Don't waste 20s; tell user clearly.
            if (!canSleepInWorld) {
                ChatUtils.sendSystemMessage(source,
                        "§7" + botName + " can't sleep right now — it's not night yet.§r");
                continue;
            }
            // Mark ATTEMPTING before invoking SleepService so a re-fire mid-attempt is debounced.
            ACTIVE.put(botId, new StateEntry(State.ATTEMPTING, nowTick, sender.getUuid()));
            tryAttemptSleep(server, bot, sender);
        }
        return true;
    }

    static boolean isEligibleChatTarget(boolean sameWorld,
                                        UUID senderUuid,
                                        UUID commanderUuid) {
        return sameWorld
                && senderUuid != null
                && senderUuid.equals(commanderUuid);
    }

    /** Run pathfinding on a worker; SleepService marshals mutations to the server thread. */
    private static void tryAttemptSleep(MinecraftServer server, ServerPlayerEntity bot,
                                        ServerPlayerEntity sender) {
        UUID botId = bot.getUuid();
        ServerCommandSource source = sender.getCommandSource();
        var ticketOpt = TaskService.beginSkill("sleep", source, botId);
        if (ticketOpt.isEmpty()) {
            ACTIVE.remove(botId);
            ChatUtils.sendSystemMessage(source, "§7" + bot.getName().getString() + " is busy.§r");
            return;
        }

        TaskService.TaskTicket ticket = ticketOpt.get();
        try {
            submitSleepAttempt(() -> runSleepAttempt(server, bot, sender, source, ticket));
        } catch (RejectedExecutionException e) {
            ACTIVE.remove(botId);
            TaskService.complete(ticket, false);
            LOGGER.info("[zzz] rejected sleep attempt for {} because the executor is stopped",
                    bot.getName().getString());
        }
    }

    private static void runSleepAttempt(MinecraftServer server,
                                        ServerPlayerEntity bot,
                                        ServerPlayerEntity sender,
                                        ServerCommandSource source,
                                        TaskService.TaskTicket ticket) {
        UUID botId = bot.getUuid();
        boolean success = false;
        boolean aborted = false;
        try {
            TaskService.attachExecutingThread(ticket, Thread.currentThread());
            try {
                success = SleepService.sleep(source, bot);
            } catch (Throwable t) {
                LOGGER.warn("[zzz] sleep attempt for {} threw: {}", bot.getName().getString(), t.toString());
            }
            aborted = TaskService.isAbortRequested(botId) || Thread.currentThread().isInterrupted();
        } finally {
            TaskService.complete(ticket, success && !aborted);
        }

        if (aborted || TaskService.isServerStopping() || bot.isRemoved()) {
            ACTIVE.remove(botId);
            return;
        }
        if (success || bot.isSleeping()) {
            ACTIVE.remove(botId);
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world) || isNoSleepDimension(world)) {
            ACTIVE.remove(botId);
            return;
        }

        // Anchor the promised 20-second window after navigation returns.
        long deadlineAnchor = server.getTicks();
        ACTIVE.put(botId, new StateEntry(
                State.WAITING_LOGOFF,
                deadlineAnchor + FAILURE_DEADLINE_TICKS, sender.getUuid()));
        LOGGER.info("[zzz] {} couldn't sleep; will check logoff conditions in {}s",
                bot.getName().getString(), FAILURE_DEADLINE_TICKS / 20);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Per-tick state machine + wake monitor.
    // ─────────────────────────────────────────────────────────────────────

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (!diskLoaded) {
            loadFromDisk();
            diskLoaded = true;
        }
        long tick = server.getTicks();
        if (tick % TICK_INTERVAL != 0) return;

        tickActiveStates(server, tick);
        tickWakeMonitor(server);
    }

    private static void tickActiveStates(MinecraftServer server, long nowTick) {
        if (ACTIVE.isEmpty()) return;
        for (Map.Entry<UUID, StateEntry> e : new ArrayList<>(ACTIVE.entrySet())) {
            UUID botId = e.getKey();
            StateEntry st = e.getValue();
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botId);
            if (bot == null || bot.isRemoved()) {
                ACTIVE.remove(botId);
                continue;
            }
            if (bot.isSleeping()) {
                // Either succeeded after retry or a nearby bed opened up.
                ACTIVE.remove(botId);
                continue;
            }
            if (st.state() != State.WAITING_LOGOFF) continue;
            if (nowTick < st.deadlineTick()) continue;

            // Deadline reached — decide whether to log off.
            ServerPlayerEntity sender = server.getPlayerManager().getPlayer(st.senderUuid());
            boolean senderSleeping = sender != null && !sender.isRemoved() && sender.isSleeping();
            ACTIVE.remove(botId);
            String botName = bot.getName().getString();
            if (!senderSleeping) {
                LOGGER.info("[zzz] {} deadline reached but sender isn't sleeping — staying online", botName);
                // Tell the user — otherwise the 20s wait just looks like nothing happened.
                if (sender != null && !sender.isRemoved()) {
                    ChatUtils.sendSystemMessage(sender.getCommandSource(),
                            "§7" + botName + " couldn't sleep, and you're not in bed either — staying online.§r");
                }
                continue;
            }
            beginLogoff(server, bot, sender);
        }
    }

    private static void beginLogoff(MinecraftServer server, ServerPlayerEntity bot,
                                    ServerPlayerEntity sender) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return;
        String name = bot.getName().getString();
        Vec3d pos = bot.getEntityPos();
        float yaw = bot.getYaw();
        float pitch = bot.getPitch();
        String dim = world.getRegistryKey().getValue().toString();
        String mode = bot.interactionManager.getGameMode().name();

        LOGGER.info("[zzz] {} logging off at {} ({}); waiting for daytime or sleep cycle.",
                name, pos, dim);

        // Mirror the proven disconnect path used by NavigationArtifactService.
        try {
            BotPersistenceService.onBotDisconnect(bot);
        } catch (Throwable t) {
            LOGGER.warn("[zzz] persist failed for {}: {}", name, t.toString());
        }
        try {
            BotPersistenceService.removeFromPlayerManager(server, bot);
        } catch (Throwable t) {
            LOGGER.warn("[zzz] removeFromPlayerManager failed for {}: {}", name, t.toString());
        }
        try {
            bot.discard();
        } catch (Throwable t) {
            LOGGER.warn("[zzz] discard failed for {}: {}", name, t.toString());
        }

        LoggedOff entry = new LoggedOff(name, pos, yaw, pitch, dim, mode, server.getOverworld().getTime());
        LOGGED_OFF.put(name, entry);
        saveToDisk();

        // Friendly chat to the sender who initiated. Send directly to the player —
        // routing through server.getCommandSource() loses the recipient and silently
        // drops the message (verified in 1.1.132 latest.log audit: "No recipient
        // resolved for system message; dropping: '... went to sleep...'").
        if (sender != null && !sender.isRemoved()) {
            sender.sendMessage(net.minecraft.text.Text.literal(
                    "§7" + name + " went to sleep — they'll be back when the cycle's done.§r"),
                    false);
        }
    }

    private static void tickWakeMonitor(MinecraftServer server) {
        if (LOGGED_OFF.isEmpty()) return;
        for (Map.Entry<String, LoggedOff> e : new ArrayList<>(LOGGED_OFF.entrySet())) {
            LoggedOff lo = e.getValue();
            ServerWorld targetWorld = resolveWorld(server, lo.dimensionId);
            if (targetWorld == null) continue;
            if (!shouldWakeUp(targetWorld, server)) continue;
            respawnBot(server, lo);
            LOGGED_OFF.remove(e.getKey());
            saveToDisk();
        }
    }

    /** A logged-off bot wakes when:
     *  - it's daytime in its dimension (tod in [0, 12000)), OR
     *  - no non-bot player in the SAME dimension is currently sleeping AND there
     *    is at least one non-bot player in some world (so we don't immediately
     *    respawn a bot when the lone player just logged off — wait for daytime). */
    private static boolean shouldWakeUp(ServerWorld botWorld, MinecraftServer server) {
        long tod = Math.floorMod(botWorld.getTimeOfDay(), DAY_TICKS);
        if (tod < NIGHT_START) return true;

        boolean anyHumanInWorld = false;
        boolean anyHumanSleepingInWorld = false;
        for (ServerPlayerEntity p : botWorld.getPlayers()) {
            if (p == null || !p.isAlive() || p.isRemoved()) continue;
            if (BotRegistry.isRegistered(p.getUuid())) continue;
            anyHumanInWorld = true;
            if (p.isSleeping()) anyHumanSleepingInWorld = true;
        }
        // It's nighttime in the bot's dim. If no human in this dim is asleep — nothing to wait for
        // here. But we should also wait for global state: if no humans are online at all, we'd loop
        // forever — accept that, the server time will tick to dawn eventually.
        return anyHumanInWorld && !anyHumanSleepingInWorld;
    }

    private static void respawnBot(MinecraftServer server, LoggedOff lo) {
        ServerWorld targetWorld = resolveWorld(server, lo.dimensionId);
        if (targetWorld == null) {
            LOGGER.warn("[zzz] can't respawn {} — world {} unresolved", lo.botName, lo.dimensionId);
            return;
        }
        GameMode mode = parseGameMode(lo.gameMode);
        LOGGER.info("[zzz] respawning {} at {} in {}", lo.botName,
                String.format("%.1f,%.1f,%.1f", lo.x, lo.y, lo.z), lo.dimensionId);
        try {
            createFakePlayer.createFake(lo.botName, server,
                    new Vec3d(lo.x, lo.y, lo.z),
                    lo.yaw, lo.pitch,
                    targetWorld.getRegistryKey(), mode, false);
        } catch (Throwable t) {
            LOGGER.error("[zzz] respawn failed for {}: {}", lo.botName, t.toString());
        }
    }

    private static ServerWorld resolveWorld(MinecraftServer server, String dimensionId) {
        try {
            Identifier id = Identifier.tryParse(dimensionId);
            if (id == null) return null;
            RegistryKey<World> key = RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, id);
            return server.getWorld(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static GameMode parseGameMode(String name) {
        if (name == null || name.isBlank()) return GameMode.SURVIVAL;
        try {
            return GameMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return GameMode.SURVIVAL;
        }
    }

    private static boolean isNoSleepDimension(ServerWorld world) {
        RegistryKey<World> key = world.getRegistryKey();
        return key == World.NETHER || key == World.END;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Persistence.
    // ─────────────────────────────────────────────────────────────────────

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(SIDECAR_NAME);
    }

    @SuppressWarnings("unchecked")
    private static void loadFromDisk() {
        Path file = stateFile();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, Object> raw = JSON.readValue(r, Map.class);
            LOGGED_OFF.clear();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                LoggedOff lo = JSON.convertValue(e.getValue(), LoggedOff.class);
                if (lo != null && lo.botName != null) LOGGED_OFF.put(lo.botName, lo);
            }
            if (!LOGGED_OFF.isEmpty()) {
                LOGGER.info("[zzz] restored {} logged-off bot(s) from disk", LOGGED_OFF.size());
            }
        } catch (IOException e) {
            LOGGER.warn("[zzz] failed to load logoff state: {}", e.getMessage());
        }
    }

    private static synchronized void saveToDisk() {
        try {
            Path file = stateFile();
            Files.createDirectories(file.getParent());
            Map<String, LoggedOff> snapshot = new HashMap<>(LOGGED_OFF);
            try (Writer w = Files.newBufferedWriter(file)) {
                JSON.writerWithDefaultPrettyPrinter().writeValue(w, snapshot);
            }
        } catch (IOException e) {
            LOGGER.warn("[zzz] failed to save logoff state: {}", e.getMessage());
        }
    }

    /** Called from server shutdown — flush any pending state. */
    public static void reset() {
        ACTIVE.clear();
        diskLoaded = false;
    }
}
