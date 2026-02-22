package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.CampfireBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
 * Reacts with overhead dialogue when:
 * <ol>
 *   <li><b>Ambient cooking:</b> A bot is near a lit furnace/smoker/blast furnace that is
 *       actively smelting food, or a lit campfire with food items on it.</li>
 *   <li><b>Food collected:</b> A player/bot picks up cooked food while near a cooking block.</li>
 * </ol>
 *
 * <p>Long per-bot cooldowns prevent spam. Both detection paths share the same cooldown.
 */
public final class CookingReactionService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-cooking-reaction");

    /** Poll interval in ticks (~1 second). */
    private static final int POLL_INTERVAL_TICKS = 20;

    /** Ambient cooking scan runs less frequently to save perf. */
    private static final int AMBIENT_POLL_INTERVAL_TICKS = 100; // every 5 seconds

    /** Max distance from player/bot to cooking block. */
    private static final int COOKING_BLOCK_SCAN_RADIUS = 6;

    /** Max distance from bot to the cooking player to react (for inventory-change path). */
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

        // Only run on poll intervals.
        if (nowTick % POLL_INTERVAL_TICKS != 0) {
            return;
        }

        List<ServerPlayerEntity> bots = BotEventHandler.getRegisteredBots(server);
        if (bots.isEmpty()) {
            return;
        }

        // --- Path 1: Inventory-change detection (every 1 second) ---
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player == null || player.isRemoved() || player.isSleeping()) continue;
            if (!(player.getEntityWorld() instanceof ServerWorld world)) continue;

            UUID pid = player.getUuid();
            int cookedCount = countCookedFoods(player);
            Integer prev = LAST_COOKED_COUNT.put(pid, cookedCount);

            if (prev == null) continue; // first tick baseline
            if (cookedCount <= prev) continue;

            // Cooked food count increased. Check if player is near a cooking block.
            if (!isNearCookingBlock(world, player.getBlockPos())) continue;

            ServerPlayerEntity reactor = findClosestReactingBot(player, bots, nowTick);
            if (reactor == null) continue;

            showCookingLine(reactor, player.getName().getString(), "inventory-change", nowTick);
        }

        // --- Path 2: Ambient cooking proximity (every 5 seconds) ---
        if (nowTick % AMBIENT_POLL_INTERVAL_TICKS == 0) {
            for (ServerPlayerEntity bot : bots) {
                if (bot == null || bot.isRemoved() || bot.isSleeping()) continue;
                if (!(bot.getEntityWorld() instanceof ServerWorld world)) continue;

                UUID botId = bot.getUuid();
                long lastReact = LAST_REACTION_TICK.getOrDefault(botId, Long.MIN_VALUE);
                if (nowTick - lastReact < COOLDOWN_TICKS) continue;
                if (CompanionOverheadDialogueService.isRecentlyShown(botId)) continue;

                if (isNearActivelyCookingFood(world, bot.getBlockPos())) {
                    showCookingLine(bot, bot.getName().getString(), "ambient-cooking", nowTick);
                }
            }
        }
    }

    private static void showCookingLine(ServerPlayerEntity bot, String triggerSource, String path, long nowTick) {
        String line = COOKING_LINES[ThreadLocalRandom.current().nextInt(COOKING_LINES.length)];
        LAST_REACTION_TICK.put(bot.getUuid(), nowTick);

        CompanionOverheadDialogueService.showOverheadLine(
                bot, line, DISPLAY_DURATION_MS, 48.0, "cooking", null);

        LOGGER.info("Cooking reaction [{}] bot={} trigger={} line=\"{}\"",
                path, bot.getName().getString(), triggerSource, line);
    }

    // ---- Inventory counting ----

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

    // ---- Block scanning ----

    /**
     * Returns true if there is a furnace/smoker/blast furnace (any state) or a lit campfire
     * within scan radius of the position.
     */
    private static boolean isNearCookingBlock(ServerWorld world, BlockPos center) {
        int r = COOKING_BLOCK_SCAN_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (isFurnaceType(state)) {
                        return true;
                    }
                    if (isCampfireType(state) && isLit(state)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns true if there is a lit furnace/smoker/blast furnace that currently has a food
     * item in its input slot, or a lit campfire with food items on it, within scan radius.
     */
    private static boolean isNearActivelyCookingFood(ServerWorld world, BlockPos center) {
        int r = COOKING_BLOCK_SCAN_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);

                    // Furnace/smoker/blast furnace: must be lit AND have a food item in the input slot.
                    if (isFurnaceType(state) && isLit(state)) {
                        var be = world.getBlockEntity(pos);
                        if (be instanceof AbstractFurnaceBlockEntity furnace) {
                            ItemStack input = furnace.getStack(0);
                            if (!input.isEmpty() && isFoodRelated(input)) {
                                return true;
                            }
                            // Also check output slot — food just finished cooking.
                            ItemStack output = furnace.getStack(2);
                            if (!output.isEmpty() && isCookedFood(output)) {
                                return true;
                            }
                        }
                    }

                    // Campfire: must be lit AND have at least one food item cooking.
                    if (isCampfireType(state) && isLit(state)) {
                        var be = world.getBlockEntity(pos);
                        if (be instanceof CampfireBlockEntity campfire) {
                            for (int slot = 0; slot < 4; slot++) {
                                ItemStack cooking = campfire.getItemsBeingCooked().get(slot);
                                if (cooking != null && !cooking.isEmpty()) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean isFurnaceType(BlockState state) {
        return state.isOf(Blocks.FURNACE)
                || state.isOf(Blocks.SMOKER)
                || state.isOf(Blocks.BLAST_FURNACE);
    }

    private static boolean isCampfireType(BlockState state) {
        return state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE);
    }

    private static boolean isLit(BlockState state) {
        try {
            return state.contains(Properties.LIT) && Boolean.TRUE.equals(state.get(Properties.LIT));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Checks if the item is a raw food that would cook into a cooked food. */
    private static boolean isFoodRelated(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        // Raw inputs that produce cooked food.
        return item == Items.BEEF || item == Items.PORKCHOP || item == Items.CHICKEN
                || item == Items.MUTTON || item == Items.RABBIT
                || item == Items.COD || item == Items.SALMON
                || item == Items.POTATO || item == Items.KELP;
    }

    /** Checks if the item is a cooked food product. */
    private static boolean isCookedFood(ItemStack stack) {
        return stack != null && !stack.isEmpty() && COOKED_FOODS.contains(stack.getItem());
    }

    // ---- Bot selection ----

    private static ServerPlayerEntity findClosestReactingBot(
            ServerPlayerEntity cook,
            List<ServerPlayerEntity> bots,
            long nowTick
    ) {
        ServerPlayerEntity best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (ServerPlayerEntity bot : bots) {
            if (bot == null || bot.isRemoved() || bot.isSleeping()) continue;
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
