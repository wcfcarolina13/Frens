package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shows a random post-sleep dialogue line when a bot wakes up after sleeping
 * near a player who also slept.
 *
 * <p>To avoid spam, there is a ~40% chance that no line triggers at all on any
 * given wake-up event, and a per-bot cooldown prevents repeated firing.
 */
public final class BotWakeUpDialogueService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-wakeup-dialogue");

    /** Probability that a line actually fires on wake-up (0.0–1.0). */
    private static final double SPEAK_CHANCE = 0.60;

    /** Max horizontal distance (blocks) between bot bed and player bed to count as "near". */
    private static final double NEAR_BED_RADIUS_SQ = 16.0 * 16.0; // 16 blocks

    /** Minimum ticks between wake-up lines for the same bot (prevent double-fire). */
    private static final long COOLDOWN_TICKS = 20L * 60L * 10L; // 10 minutes (one sleep cycle)

    /** Delay after wake-up before showing the line (ticks). Lets the screen transition finish. */
    private static final int DISPLAY_DELAY_TICKS = 40; // 2 seconds

    private static final int DISPLAY_DURATION_MS = 3_500;

    private static final String[] WAKE_LINES = {
            "You know you snore like a piglin?",
            "I had the strangest dream that I was an NPC in a video game.",
            "A good night's rest.",
            "Seize the day!"
    };

    /** Tracks whether each bot was sleeping on the previous tick. */
    private static final ConcurrentHashMap<UUID, Boolean> WAS_SLEEPING = new ConcurrentHashMap<>();

    /** Tracks whether each real player was sleeping on the previous tick. */
    private static final ConcurrentHashMap<UUID, Boolean> PLAYER_WAS_SLEEPING = new ConcurrentHashMap<>();

    /** Last tick a wake-up line was shown for each bot. */
    private static final ConcurrentHashMap<UUID, Long> LAST_WAKE_LINE_TICK = new ConcurrentHashMap<>();

    /** Tracks which bots have already been queued for co-sleep this night cycle. */
    private static final ConcurrentHashMap<UUID, Long> CO_SLEEP_QUEUED_TICK = new ConcurrentHashMap<>();

    /** Co-sleep cooldown: don't re-queue for 5 minutes after a co-sleep attempt. */
    private static final long CO_SLEEP_COOLDOWN_TICKS = 20L * 60L * 5L;

    /** Co-sleep proximity: bot must be within 16 blocks of commander. */
    private static final double CO_SLEEP_RADIUS_SQ = 16.0 * 16.0;

    private BotWakeUpDialogueService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long nowTick = server.getTicks();

        // --- Phase 1: Track real player sleep edges and trigger co-sleep ---
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player == null || player.isRemoved()) continue;
            if (BotEventHandler.isRegisteredBot(player)) continue;
            UUID pid = player.getUuid();
            boolean sleeping = player.isSleeping();
            boolean was = PLAYER_WAS_SLEEPING.getOrDefault(pid, false);
            if (!was && sleeping) {
                // Player just went to sleep — try to co-sleep nearby bots.
                triggerCoSleep(server, player, nowTick);
            }
            if (was && !sleeping) {
                // Player just woke up — record the tick so bots can reference it.
                PLAYER_WAS_SLEEPING.put(pid, false);
            } else {
                PLAYER_WAS_SLEEPING.put(pid, sleeping);
            }
        }

        // --- Phase 2: Detect bot wake-up and maybe show a line ---
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) continue;
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) continue;

            UUID botId = bot.getUuid();
            boolean sleeping = bot.isSleeping();
            boolean wasSleeping = WAS_SLEEPING.getOrDefault(botId, false);
            WAS_SLEEPING.put(botId, sleeping);

            if (!wasSleeping || sleeping) {
                // Not a wake-up edge.
                continue;
            }

            // Bot just woke up. Check cooldown.
            long lastLine = LAST_WAKE_LINE_TICK.getOrDefault(botId, Long.MIN_VALUE);
            if (nowTick - lastLine < COOLDOWN_TICKS) {
                continue;
            }

            // Check if any real player nearby also just slept (within last few seconds).
            boolean playerSleptNearby = false;
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (player == null || player.isRemoved()) continue;
                if (BotEventHandler.isRegisteredBot(player)) continue;
                // Player must have been sleeping within the last ~5 seconds or still be in the
                // waking-up window (PLAYER_WAS_SLEEPING just flipped to false this tick or recently).
                // Since vanilla wakes everyone simultaneously, checking !player.isSleeping() after
                // the edge detection above is sufficient — the player's edge fires the same tick.
                // Accept if the player was sleeping last tick (edge fires same tick as bot).
                // The PLAYER_WAS_SLEEPING map is updated in phase 1 above, so if the player
                // woke up this tick, the map already has false. But we can also just check if
                // the player is close enough and was sleeping recently by looking at the raw
                // sleeping position.
                boolean playerJustWoke = !player.isSleeping();
                if (!playerJustWoke) continue;

                double distSq = player.squaredDistanceTo(bot);
                if (distSq <= NEAR_BED_RADIUS_SQ) {
                    playerSleptNearby = true;
                    break;
                }
            }

            if (!playerSleptNearby) {
                continue;
            }

            // Random chance to stay silent.
            if (ThreadLocalRandom.current().nextDouble() >= SPEAK_CHANCE) {
                LOGGER.debug("Wake-up dialogue suppressed (random silence) for bot {}", bot.getName().getString());
                LAST_WAKE_LINE_TICK.put(botId, nowTick);
                continue;
            }

            // Pick a random line and schedule it after a short delay.
            String line = WAKE_LINES[ThreadLocalRandom.current().nextInt(WAKE_LINES.length)];
            LAST_WAKE_LINE_TICK.put(botId, nowTick);

            // Schedule the display a couple seconds later so the player sees it after the
            // sleep screen fades out.
            long dueTick = nowTick + DISPLAY_DELAY_TICKS;
            server.execute(() -> {
                // Use server.send with a delayed task if available, otherwise just run on next tick.
                scheduleDelayed(server, bot, line, dueTick);
            });

            LOGGER.debug("Scheduled wake-up line for bot {} in {} ticks: \"{}\"", bot.getName().getString(), DISPLAY_DELAY_TICKS, line);
        }
    }

    private static void scheduleDelayed(MinecraftServer server, ServerPlayerEntity bot, String line, long dueTick) {
        server.send(new net.minecraft.server.ServerTask((int) dueTick, () -> {
            if (bot == null || bot.isRemoved()) return;
            CompanionOverheadDialogueService.showOverheadLine(
                    bot, line, DISPLAY_DURATION_MS, 48.0, "wake-up", null);
        }));
    }

    // ── Co-sleep: when commander goes to sleep, nearby idle bots sleep too ──

    private static final java.util.concurrent.atomic.AtomicInteger CO_SLEEP_THREAD_ID =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.ExecutorService CO_SLEEP_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "co-sleep-" + CO_SLEEP_THREAD_ID.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    /**
     * When a real player goes to sleep, queue sleep for any nearby idle bots
     * that this player commands.
     */
    /** Delay (ticks) before retrying co-sleep for bots skipped due to transient conditions. */
    private static final int CO_SLEEP_RETRY_DELAY_TICKS = 60; // 3 seconds

    private static void triggerCoSleep(MinecraftServer server, ServerPlayerEntity player, long nowTick) {
        List<ServerPlayerEntity> bots = BotEventHandler.getRegisteredBots(server);
        LOGGER.info("Co-sleep: commander {} went to sleep, checking {} bot(s)...",
                player.getName().getString(), bots.size());
        List<UUID> retryBots = new ArrayList<>();
        for (ServerPlayerEntity bot : bots) {
            if (bot == null || bot.isRemoved()) continue;
            String botName = bot.getName().getString();
            if (bot.isSleeping()) {
                LOGGER.info("Co-sleep: {} skipped — already sleeping", botName);
                continue;
            }
            if (bot.getEntityWorld() != player.getEntityWorld()) {
                LOGGER.info("Co-sleep: {} skipped — different world", botName);
                continue;
            }

            UUID botId = bot.getUuid();

            // Cooldown: don't re-queue co-sleep within 5 minutes.
            // Use -1 as sentinel (not Long.MIN_VALUE which overflows on subtraction).
            long lastQueued = CO_SLEEP_QUEUED_TICK.getOrDefault(botId, -1L);
            if (lastQueued >= 0 && lastQueued <= nowTick && (nowTick - lastQueued) < CO_SLEEP_COOLDOWN_TICKS) {
                LOGGER.info("Co-sleep: {} skipped — cooldown ({} ticks remaining)",
                        botName, CO_SLEEP_COOLDOWN_TICKS - (nowTick - lastQueued));
                continue;
            }

            // Bot must be within 16 blocks of the player.
            double distSq = bot.squaredDistanceTo(player);
            if (distSq > CO_SLEEP_RADIUS_SQ) {
                LOGGER.info("Co-sleep: {} skipped — too far ({} blocks)",
                        botName, String.format("%.1f", Math.sqrt(distSq)));
                continue;
            }

            // Player must be the bot's commander/owner.
            ServerPlayerEntity commander = CompanionCommunicationPolicy.resolveController(server, bot);
            if (commander == null || !commander.getUuid().equals(player.getUuid())) {
                LOGGER.info("Co-sleep: {} skipped — {} is not commander (resolved={})",
                        botName, player.getName().getString(),
                        commander != null ? commander.getName().getString() : "null");
                continue;
            }

            // Bot must be idle (no active task).
            if (TaskService.hasActiveTask(botId)) {
                LOGGER.info("Co-sleep: {} skipped — has active task ({}), will retry in {}t",
                        botName, TaskService.getActiveTaskName(botId).orElse("?"), CO_SLEEP_RETRY_DELAY_TICKS);
                retryBots.add(botId);
                continue;
            }

            // Must be valid sleep time.
            if (bot.getEntityWorld() instanceof ServerWorld sw) {
                if (sw.isDay() && !sw.isThundering()) {
                    LOGGER.info("Co-sleep: {} skipped — daytime", botName);
                    continue;
                }
            }

            CO_SLEEP_QUEUED_TICK.put(botId, nowTick);
            LOGGER.info("Co-sleep: {} going to sleep because commander {} slept nearby",
                    botName, player.getName().getString());
            scheduleCoSleep(server, bot);
        }

        // Schedule a delayed retry for bots skipped due to transient conditions (active task).
        if (!retryBots.isEmpty()) {
            UUID playerUuid = player.getUuid();
            server.send(new net.minecraft.server.ServerTask((int) (nowTick + CO_SLEEP_RETRY_DELAY_TICKS), () -> {
                ServerPlayerEntity retryPlayer = server.getPlayerManager().getPlayer(playerUuid);
                if (retryPlayer == null || retryPlayer.isRemoved() || !retryPlayer.isSleeping()) return;
                long retryTick = server.getTicks();
                LOGGER.info("Co-sleep retry: checking {} bot(s) that were busy earlier", retryBots.size());
                for (UUID botId : retryBots) {
                    ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botId);
                    if (bot == null || bot.isRemoved() || bot.isSleeping()) continue;
                    if (bot.getEntityWorld() != retryPlayer.getEntityWorld()) continue;
                    if (TaskService.hasActiveTask(botId)) {
                        LOGGER.info("Co-sleep retry: {} still has active task, giving up", bot.getName().getString());
                        continue;
                    }
                    long lastQueued = CO_SLEEP_QUEUED_TICK.getOrDefault(botId, Long.MIN_VALUE);
                    if (retryTick - lastQueued < CO_SLEEP_COOLDOWN_TICKS) continue;
                    double distSq = bot.squaredDistanceTo(retryPlayer);
                    if (distSq > CO_SLEEP_RADIUS_SQ) continue;
                    if (bot.getEntityWorld() instanceof ServerWorld sw) {
                        if (sw.isDay() && !sw.isThundering()) continue;
                    }
                    CO_SLEEP_QUEUED_TICK.put(botId, retryTick);
                    LOGGER.info("Co-sleep retry: {} going to sleep (task cleared)", bot.getName().getString());
                    scheduleCoSleep(server, bot);
                }
            }));
        }
    }

    private static void scheduleCoSleep(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null || bot.isRemoved()) return;
        UUID botUuid = bot.getUuid();

        // Avoid double-queueing.
        var active = TaskService.getActiveTaskInfo(botUuid);
        if (active.isPresent() && "skill:sleep".equalsIgnoreCase(active.get().name())) return;

        net.minecraft.server.command.ServerCommandSource source = bot.getCommandSource().withSilent();
        var ticketOpt = TaskService.beginSkill("sleep", source, botUuid);
        if (ticketOpt.isEmpty()) return;

        TaskService.TaskTicket ticket = ticketOpt.get();
        ticket.setOrigin(TaskService.Origin.SYSTEM);
        ticket.setOpenEnded(false);

        CO_SLEEP_EXECUTOR.submit(() -> {
            boolean success = false;
            try {
                TaskService.attachExecutingThread(ticket, Thread.currentThread());
                success = SleepService.sleep(source, bot) && !TaskService.isAbortRequested(botUuid);
            } catch (Exception e) {
                LOGGER.warn("Co-sleep failed for {}: {}", bot.getName().getString(), e.getMessage());
            } finally {
                TaskService.complete(ticket, success);
            }
        });
    }

    /** Call on server stop / world unload to prevent stale state. */
    public static void clear() {
        WAS_SLEEPING.clear();
        PLAYER_WAS_SLEEPING.clear();
        LAST_WAKE_LINE_TICK.clear();
        CO_SLEEP_QUEUED_TICK.clear();
    }
}
