package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Shared shoreline escape helpers for tasks that require dry footing.
 */
public final class BotWaterEscapeService {

    private BotWaterEscapeService() {
    }

    public static boolean isInWater(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos feet = bot.getBlockPos();
        return bot.isTouchingWater()
                || bot.isSwimming()
                || bot.isSubmergedInWater()
                || world.getFluidState(feet).isIn(FluidTags.WATER)
                || world.getFluidState(feet.up()).isIn(FluidTags.WATER);
    }

    /**
     * Find nearby dry land while preferring shoreline-adjacent stands.
     */
    public static BlockPos findNearestShoreStand(ServerPlayerEntity bot, int radius) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            return null;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        BlockPos origin = bot.getBlockPos();
        int r = Math.max(3, radius);

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (BlockPos candidate : BlockPos.iterate(origin.add(-r, -3, -r), origin.add(r, 4, r))) {
            if (!world.isChunkLoaded(candidate)) {
                continue;
            }
            if (!isStandable(world, candidate)) {
                continue;
            }

            int dx = candidate.getX() - origin.getX();
            int dz = candidate.getZ() - origin.getZ();
            int dy = candidate.getY() - origin.getY();
            double horizontalDistSq = dx * dx + dz * dz;
            double verticalPenalty = Math.abs(dy) * 1.4D;
            boolean shoreline = isAdjacentToWater(world, candidate);
            double shorelinePenalty = shoreline ? 0.0D : 7.0D;
            double score = horizontalDistSq + verticalPenalty + shorelinePenalty;

            if (score < bestScore) {
                bestScore = score;
                best = candidate.toImmutable();
            }
        }
        return best;
    }

    private static boolean isStandable(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        if (world.getFluidState(feet).isIn(FluidTags.WATER) || world.getFluidState(feet.up()).isIn(FluidTags.WATER)) {
            return false;
        }
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());
        if (!feetState.getCollisionShape(world, feet).isEmpty()) {
            return false;
        }
        if (!headState.getCollisionShape(world, feet.up()).isEmpty()) {
            return false;
        }
        BlockPos floor = feet.down();
        BlockState floorState = world.getBlockState(floor);
        return !floorState.getCollisionShape(world, floor).isEmpty();
    }

    private static boolean isAdjacentToWater(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos side = feet.offset(dir);
            if (!world.isChunkLoaded(side)) {
                continue;
            }
            if (world.getFluidState(side).isIn(FluidTags.WATER) || world.getFluidState(side.down()).isIn(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }
}
