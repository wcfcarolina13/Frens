package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Safety: bots should never stand on campfires.
 *
 * <p>This is a tiny, deterministic escape nudge. It does not attempt pathfinding;
 * it just pushes the bot off the hazard immediately.
 */
public final class BotCampfireAvoidanceService {

    private static final Logger LOGGER = LoggerFactory.getLogger("campfire-avoid");

    // If possible, step at least 2 blocks away from the campfire tile.
    private static final int MIN_ESCAPE_RADIUS = 2;
    private static final int MAX_ESCAPE_RADIUS = 4;

    private BotCampfireAvoidanceService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                continue;
            }
            tryEscapeCampfire(bot);
        }
    }

    /**
     * If the bot is currently standing on a campfire, immediately nudges it off.
     * Returns true if an escape nudge was applied.
     */
    public static boolean tryEscapeCampfire(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }

        BlockPos feet = bot.getBlockPos();
        if (!world.isChunkLoaded(feet)) {
            return false;
        }

        // Entity#getBlockPos is typically the air block the entity occupies.
        // When standing on short blocks (like campfires), the campfire may be at feet OR at feet.down().
        BlockPos campfirePos = null;
        if (isCampfire(world.getBlockState(feet))) {
            campfirePos = feet;
        } else {
            BlockPos below = feet.down();
            if (world.isChunkLoaded(below) && isCampfire(world.getBlockState(below))) {
                campfirePos = below;
            }
        }
        if (campfirePos == null) {
            return false;
        }

        // Emergency: stop current movement inputs and nudge away.
        BotActions.stop(bot);

        BlockPos escape = findEscapeSpot(world, campfirePos);
        if (escape == null) {
            // Fall back to a small velocity kick in a random-ish horizontal direction.
            // (Still better than sitting on a campfire.)
            Vec3d kick = new Vec3d(0.4D, 0.15D, 0.0D);
            bot.setVelocity(kick);
            bot.velocityDirty = true;
            return true;
        }

        Vec3d target = Vec3d.ofCenter(escape);
        Vec3d current = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d dir = target.subtract(current);
        if (dir.lengthSquared() > 1.0E-6D) {
            dir = dir.normalize();
        }

        bot.setVelocity(dir.multiply(0.45D).add(0.0D, 0.12D, 0.0D));
        bot.velocityDirty = true;

        LOGGER.info("Bot {} escaped campfire at {} -> {}",
            bot.getName().getString(), campfirePos.toShortString(), escape.toShortString());
        return true;
    }

    private static boolean isCampfire(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.isOf(net.minecraft.block.Blocks.CAMPFIRE)
                || state.isOf(net.minecraft.block.Blocks.SOUL_CAMPFIRE);
    }

    private static BlockPos findEscapeSpot(ServerWorld world, BlockPos campfireFeet) {
        if (world == null || campfireFeet == null) {
            return null;
        }

        // Search rings outward; prefer the closest safe tile >= MIN_ESCAPE_RADIUS.
        for (int r = MIN_ESCAPE_RADIUS; r <= MAX_ESCAPE_RADIUS; r++) {
            BlockPos best = null;
            double bestDist = Double.POSITIVE_INFINITY;

            for (BlockPos pos : BlockPos.iterate(campfireFeet.add(-r, -1, -r), campfireFeet.add(r, 1, r))) {
                if (!world.isChunkLoaded(pos)) {
                    continue;
                }
                // Keep it mostly horizontal.
                if (Math.abs(pos.getY() - campfireFeet.getY()) > 1) {
                    continue;
                }
                if (pos.getSquaredDistance(campfireFeet) < (double) (MIN_ESCAPE_RADIUS * MIN_ESCAPE_RADIUS)) {
                    continue;
                }
                if (!isStandable(world, pos)) {
                    continue;
                }
                if (isTooCloseToAnyCampfire(world, pos, 1)) {
                    continue;
                }

                double d = pos.getSquaredDistance(campfireFeet);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.toImmutable();
                }
            }

            if (best != null) {
                return best;
            }
        }

        // As a last resort, allow immediate adjacency (still not on campfire).
        for (BlockPos pos : BlockPos.iterate(campfireFeet.add(-1, -1, -1), campfireFeet.add(1, 1, 1))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (pos.equals(campfireFeet)) {
                continue;
            }
            if (!isStandable(world, pos)) {
                continue;
            }
            if (isCampfire(world.getBlockState(pos))) {
                continue;
            }
            return pos.toImmutable();
        }

        return null;
    }

    private static boolean isStandable(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        // Need 2-block headroom and solid-ish floor.
        BlockPos head = feet.up();
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(head).isEmpty()) {
            return false;
        }
        if (!feetState.getCollisionShape(world, feet).isEmpty()) {
            return false;
        }
        if (!headState.getCollisionShape(world, head).isEmpty()) {
            return false;
        }
        BlockPos below = feet.down();
        BlockState belowState = world.getBlockState(below);
        return !belowState.getCollisionShape(world, below).isEmpty();
    }

    private static boolean isTooCloseToAnyCampfire(ServerWorld world, BlockPos pos, int radius) {
        int r = Math.max(0, radius);
        for (BlockPos p : BlockPos.iterate(pos.add(-r, -1, -r), pos.add(r, 1, r))) {
            if (!world.isChunkLoaded(p)) {
                continue;
            }
            if (isCampfire(world.getBlockState(p))) {
                return true;
            }
        }
        return false;
    }
}
