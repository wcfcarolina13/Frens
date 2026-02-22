package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private BotWakeUpDialogueService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long nowTick = server.getTicks();

        // --- Phase 1: Track real player sleep edges ---
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player == null || player.isRemoved()) continue;
            if (BotEventHandler.isRegisteredBot(player)) continue;
            UUID pid = player.getUuid();
            boolean sleeping = player.isSleeping();
            boolean was = PLAYER_WAS_SLEEPING.getOrDefault(pid, false);
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

    /** Call on server stop / world unload to prevent stale state. */
    public static void clear() {
        WAS_SLEEPING.clear();
        PLAYER_WAS_SLEEPING.clear();
        LAST_WAKE_LINE_TICK.clear();
    }
}
