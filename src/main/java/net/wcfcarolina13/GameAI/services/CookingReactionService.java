package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reacts with overhead dialogue when cooked food is taken from a furnace, smoker,
 * blast furnace, or campfire.
 *
 * <p>Detection: polls player inventories every second for increases in cooked food
 * item counts. When an increase is detected and the player (real or bot) is near a
 * lit cooking block, the closest bot shows a food comment overhead.
 *
 * <p>Fires for both player-initiated and bot-initiated cooking. Long cooldowns
 * prevent spam.
 */
public final class CookingReactionService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-cooking-reaction");

    /** Poll interval in ticks (~1 second). */
    private static final int POLL_INTERVAL_TICKS = 20;

    /** Max distance from player to cooking block to count as "at the cooking station". */
    private static final int COOKING_BLOCK_SCAN_RADIUS = 5;

    /** Max distance from bot to the cooking player to react. */
    private static final double BOT_REACT_RADIUS_SQ = 16.0 * 16.0;

    /** Per-bot cooldown between cooking reactions. */
    private static final long COOLDOWN_TICKS = 20L * 60L * 3L; // 3 minutes

    private static final int DISPLAY_DURATION_MS = 3_200;

    // All food items that come out of furnaces / smokers / campfires.
    private static final Set<Item> COOKED_FOODS = Set.of(
            Items.COOKED_BEEF,
            Items.COOKED_PORKCHOP,
            Items.COOKED_CHICKEN,
            Items.COOKED_MUTTON,
            Items.COOKED_RABBIT,
            Items.COOKED_COD,
            Items.COOKED_SALMON,
            Items.BAKED_POTATO,
            Items.DRIED_KELP
    );

    private static final String[] COOKING_LINES = {
            "Something smells good.",
            "Is that dinner?",
            "Now I'm getting hungry.",
            "Smells like home.",
            "Nothing beats a hot meal.",
            "Save me some, will you?",
            "That smells amazing.",
            "I could eat."
    };

    /** Last total cooked food count per player UUID. */
    private static final ConcurrentHashMap<UUID, Integer> LAST_COOKED_COUNT = new ConcurrentHashMap<>();

    /** Last tick a cooking reaction was shown per bot UUID. */
    private static final ConcurrentHashMap<UUID, Long> LAST_REACTION_TICK = new ConcurrentHashMap<>();

    private CookingReactionService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long nowTick = server.getTicks();
        if (nowTick % POLL_INTERVAL_TICKS != 0) {
            return;
        }

        List<ServerPlayerEntity> bots = BotEventHandler.getRegisteredBots(server);

        // Check ALL players (real + bots) for cooked food inventory changes.
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player == null || player.isRemoved()) continue;
            if (player.isSleeping()) continue;
            if (!(player.getEntityWorld() instanceof ServerWorld world)) continue;

            UUID pid = player.getUuid();
            int cookedCount = countCookedFoods(player);
            Integer prev = LAST_COOKED_COUNT.put(pid, cookedCount);

            // First tick for this player — just record baseline.
            if (prev == null) continue;

            // No increase in cooked food items — skip.
            if (cookedCount <= prev) continue;

            // Cooked food count increased. Check if player is near a lit cooking block.
            if (!isNearLitCookingBlock(world, player.getBlockPos())) continue;

            // Find the closest bot to react. If the player IS a bot, it can react to its own cooking.
            ServerPlayerEntity reactor = findClosestReactingBot(player, bots, nowTick);
            if (reactor == null) continue;

            String line = COOKING_LINES[ThreadLocalRandom.current().nextInt(COOKING_LINES.length)];
            LAST_REACTION_TICK.put(reactor.getUuid(), nowTick);

            CompanionOverheadDialogueService.showOverheadLine(
                    reactor, line, DISPLAY_DURATION_MS, 48.0, "cooking", null);

            LOGGER.debug("Cooking reaction bot={} cook={} line=\"{}\"",
                    reactor.getName().getString(), player.getName().getString(), line);
        }
    }

    /**
     * Counts the total number of cooked food items across all inventory slots.
     */
    private static int countCookedFoods(ServerPlayerEntity player) {
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (!stack.isEmpty() && COOKED_FOODS.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Scans a small area around the position for a lit furnace, smoker, blast furnace,
     * or campfire.
     */
    private static boolean isNearLitCookingBlock(ServerWorld world, BlockPos center) {
        int r = COOKING_BLOCK_SCAN_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (isCookingBlock(state) && isLit(state)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isCookingBlock(BlockState state) {
        return state.isOf(Blocks.FURNACE)
                || state.isOf(Blocks.SMOKER)
                || state.isOf(Blocks.BLAST_FURNACE)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE);
    }

    private static boolean isLit(BlockState state) {
        try {
            return state.contains(Properties.LIT) && Boolean.TRUE.equals(state.get(Properties.LIT));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Finds the closest bot that can react — within range, not on cooldown, and not
     * already showing overhead text.
     */
    private static ServerPlayerEntity findClosestReactingBot(
            ServerPlayerEntity cook,
            List<ServerPlayerEntity> bots,
            long nowTick
    ) {
        ServerPlayerEntity best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (ServerPlayerEntity bot : bots) {
            if (bot == null || bot.isRemoved()) continue;
            if (bot.isSleeping()) continue;
            if (bot.getEntityWorld() != cook.getEntityWorld()) continue;

            UUID botId = bot.getUuid();
            long lastReact = LAST_REACTION_TICK.getOrDefault(botId, Long.MIN_VALUE);
            if (nowTick - lastReact < COOLDOWN_TICKS) continue;

            if (CompanionOverheadDialogueService.isRecentlyShown(botId)) continue;

            double distSq = bot.squaredDistanceTo(cook);
            if (distSq <= BOT_REACT_RADIUS_SQ && distSq < bestDistSq) {
                best = bot;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    /** Call on server stop to prevent stale state. */
    public static void clear() {
        LAST_COOKED_COUNT.clear();
        LAST_REACTION_TICK.clear();
    }
}
