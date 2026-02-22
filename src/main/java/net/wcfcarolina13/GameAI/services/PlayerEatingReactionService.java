package net.wcfcarolina13.GameAI.services;

import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reacts with overhead dialogue when a nearby real player eats certain "gross" or risky foods.
 *
 * <p>Currently tracked:
 * <ul>
 *   <li><b>Rotten Flesh</b> — disgusted/amused reactions</li>
 *   <li><b>Suspicious Stew</b> — wary/skeptical reactions</li>
 * </ul>
 *
 * <p>Detection uses the player's {@code Stats.USED} counter for the item, polled each tick.
 * When the counter increments and a bot is within range, a random line is shown overhead.
 * A per-player-per-category cooldown prevents spam.
 */
public final class PlayerEatingReactionService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-eat-reaction");

    /** Max distance (blocks) for a bot to react. */
    private static final double REACT_RADIUS_SQ = 12.0 * 12.0;

    /** Per-player cooldown between reactions of the same category. */
    private static final long COOLDOWN_TICKS = 20L * 45L; // 45 seconds

    private static final int DISPLAY_DURATION_MS = 3_200;

    // --- Rotten Flesh lines ---
    private static final String[] ROTTEN_FLESH_LINES = {
            "Did you really just eat that?",
            "That's... rotten. You know that, right?",
            "I can smell it from here.",
            "Your stomach is braver than mine."
    };

    // --- Suspicious Stew lines ---
    private static final String[] SUSPICIOUS_STEW_LINES = {
            "That stew looks... suspicious.",
            "You sure about that stew?",
            "I wouldn't trust that if I were you.",
            "Bold choice with the mystery stew."
    };

    // Stat tracking maps: player UUID -> last known USED count
    private static final ConcurrentHashMap<UUID, Integer> LAST_ROTTEN_FLESH_COUNT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> LAST_SUSPICIOUS_STEW_COUNT = new ConcurrentHashMap<>();

    // Cooldown maps: player UUID -> last reaction tick
    private static final ConcurrentHashMap<UUID, Long> LAST_ROTTEN_REACTION_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_STEW_REACTION_TICK = new ConcurrentHashMap<>();

    private PlayerEatingReactionService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long nowTick = server.getTicks();
        // Only check every 4 ticks (~200ms) to reduce overhead.
        if (nowTick % 4 != 0) {
            return;
        }

        List<ServerPlayerEntity> bots = BotEventHandler.getRegisteredBots(server);
        if (bots.isEmpty()) {
            return;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player == null || player.isRemoved()) continue;
            if (BotEventHandler.isRegisteredBot(player)) continue;
            if (player.isSleeping()) continue;

            UUID pid = player.getUuid();

            // --- Rotten Flesh ---
            int rottenCount = safeGetUsedCount(player, Items.ROTTEN_FLESH);
            Integer prevRotten = LAST_ROTTEN_FLESH_COUNT.put(pid, rottenCount);
            if (prevRotten != null && rottenCount > prevRotten) {
                long lastReact = LAST_ROTTEN_REACTION_TICK.getOrDefault(pid, Long.MIN_VALUE);
                if (nowTick - lastReact >= COOLDOWN_TICKS) {
                    tryReactFromNearbyBot(server, player, bots, ROTTEN_FLESH_LINES, "rotten-flesh", nowTick);
                    LAST_ROTTEN_REACTION_TICK.put(pid, nowTick);
                }
            }

            // --- Suspicious Stew ---
            int stewCount = safeGetUsedCount(player, Items.SUSPICIOUS_STEW);
            Integer prevStew = LAST_SUSPICIOUS_STEW_COUNT.put(pid, stewCount);
            if (prevStew != null && stewCount > prevStew) {
                long lastReact = LAST_STEW_REACTION_TICK.getOrDefault(pid, Long.MIN_VALUE);
                if (nowTick - lastReact >= COOLDOWN_TICKS) {
                    tryReactFromNearbyBot(server, player, bots, SUSPICIOUS_STEW_LINES, "suspicious-stew", nowTick);
                    LAST_STEW_REACTION_TICK.put(pid, nowTick);
                }
            }
        }
    }

    private static void tryReactFromNearbyBot(
            MinecraftServer server,
            ServerPlayerEntity player,
            List<ServerPlayerEntity> bots,
            String[] lines,
            String tag,
            long nowTick
    ) {
        // Find the closest bot within range.
        ServerPlayerEntity closestBot = null;
        double closestDistSq = Double.MAX_VALUE;

        for (ServerPlayerEntity bot : bots) {
            if (bot == null || bot.isRemoved()) continue;
            if (bot.isSleeping()) continue;
            if (!(bot.getEntityWorld() instanceof ServerWorld)) continue;
            // Must be in the same world.
            if (bot.getEntityWorld() != player.getEntityWorld()) continue;

            double distSq = bot.squaredDistanceTo(player);
            if (distSq <= REACT_RADIUS_SQ && distSq < closestDistSq) {
                closestBot = bot;
                closestDistSq = distSq;
            }
        }

        if (closestBot == null) {
            return;
        }

        // Suppress if the bot recently showed any overhead line (avoid stacking).
        if (CompanionOverheadDialogueService.isRecentlyShown(closestBot.getUuid())) {
            return;
        }

        String line = lines[ThreadLocalRandom.current().nextInt(lines.length)];

        CompanionOverheadDialogueService.showOverheadLine(
                closestBot, line, DISPLAY_DURATION_MS, 48.0, tag, null);

        LOGGER.debug("Eating reaction ({}) bot={} player={} line=\"{}\"",
                tag, closestBot.getName().getString(), player.getName().getString(), line);
    }

    private static int safeGetUsedCount(ServerPlayerEntity player, net.minecraft.item.Item item) {
        try {
            return player.getStatHandler().getStat(Stats.USED.getOrCreateStat(item));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Call on server stop to prevent stale state. */
    public static void clear() {
        LAST_ROTTEN_FLESH_COUNT.clear();
        LAST_SUSPICIOUS_STEW_COUNT.clear();
        LAST_ROTTEN_REACTION_TICK.clear();
        LAST_STEW_REACTION_TICK.clear();
    }
}
