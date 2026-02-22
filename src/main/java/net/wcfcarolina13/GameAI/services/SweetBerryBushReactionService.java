package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.wcfcarolina13.GameAI.BotEventHandler;

/**
 * Emits short overhead (non-chat) companion reactions when bots walk through or linger near
 * Sweet Berry Bushes.
 */
public final class SweetBerryBushReactionService {

    // "These are edible...I think." should be notably rarer than the pain reactions.
    private static final double EDIBLE_ADJACENT_PROBABILITY = 0.10; // 10%

    private static final java.util.Random RNG = new java.util.Random();

    private SweetBerryBushReactionService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }

            // If the bot is *in* a berry bush, show a quick pain line (rate-limited per bot).
            if (isInSweetBerryBush(world, bot)) {
                CompanionOverheadDialogueService.tryShowSweetBerryBushSting(bot, "in_bush");
                continue;
            }

            // Otherwise, if adjacent to berry bushes, occasionally show a curiosity line.
            if (isAdjacentToSweetBerryBush(world, bot.getBlockPos())) {
                if (RNG.nextDouble() < EDIBLE_ADJACENT_PROBABILITY) {
                    CompanionOverheadDialogueService.tryShowSweetBerryBushEdible(bot, "adjacent");
                }
            }
        }
    }

    private static boolean isInSweetBerryBush(ServerWorld world, ServerPlayerEntity bot) {
        if (world == null || bot == null || bot.isRemoved()) {
            return false;
        }

        // Scan the blocks overlapping the bot's bounding box (plus a tiny buffer) and also check
        // one block below the box to catch bushes that sit just under the bot's "feet" blockpos.
        Box box = bot.getBoundingBox().expand(0.001);
        BlockPos min = BlockPos.ofFloored(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ);

        // Include one block below in case the bush is at feet.down().
        BlockPos min2 = min.down();
        BlockPos max2 = max;

        for (BlockPos pos : BlockPos.iterate(min2, max2)) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (isSweetBerryBush(world.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdjacentToSweetBerryBush(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }

        // Only scan a small local cube: 3x3 around the bot, +/-1 in Y.
        BlockPos min = feet.add(-1, -1, -1);
        BlockPos max = feet.add(1, 1, 1);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (pos == null) {
                continue;
            }
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (isSweetBerryBush(world.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSweetBerryBush(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.isOf(net.minecraft.block.Blocks.SWEET_BERRY_BUSH);
    }
}
