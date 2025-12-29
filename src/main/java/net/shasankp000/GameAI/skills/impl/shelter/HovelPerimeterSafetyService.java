package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.shasankp000.GameAI.services.BlockInteractionService;

import java.util.function.BiConsumer;

/**
 * Small, hovel-specific safety helpers for the exterior movement ring.
 */
final class HovelPerimeterSafetyService {

    private HovelPerimeterSafetyService() {
    }

    /**
     * Try to clear a small 1x1 column (plus immediate shoulder/head neighbors) at the given stand cell.
     *
     * <p>Intended for the exterior perimeter ring where the bot tends to corner-hump and take in-wall damage.
     * This is deliberately conservative: it will not mine doors or block-entities.</p>
     *
     * @return true if any block was mined.
     */
    static boolean tryClearStandColumn(ServerWorld world,
                                      ServerPlayerEntity bot,
                                      BlockPos stand,
                                      double reachSq,
                                      BiConsumer<ServerPlayerEntity, BlockPos> mine) {
        if (world == null || bot == null || stand == null || mine == null) {
            return false;
        }

        boolean minedAny = false;

        // Clear the 1x1 stand column up to 3 blocks above the stand cell.
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos p = stand.up(dy);
            minedAny |= tryMineIfObstructing(world, bot, p, reachSq, mine);
        }

        // Clear "shoulder" blocks around the stand cell that commonly clip the player hitbox at corners.
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos p1 = stand.offset(d).up();
            BlockPos p2 = stand.offset(d).up(2);
            minedAny |= tryMineIfObstructing(world, bot, p1, reachSq, mine);
            minedAny |= tryMineIfObstructing(world, bot, p2, reachSq, mine);
        }

        return minedAny;
    }

    private static boolean tryMineIfObstructing(ServerWorld world,
                                               ServerPlayerEntity bot,
                                               BlockPos pos,
                                               double reachSq,
                                               BiConsumer<ServerPlayerEntity, BlockPos> mine) {
        if (pos == null) {
            return false;
        }
        if (!BlockInteractionService.canInteract(bot, pos, reachSq)) {
            return false;
        }
        BlockState s = world.getBlockState(pos);
        if (s.isAir()) {
            return false;
        }
        if (s.getBlock() instanceof DoorBlock) {
            return false;
        }
        if (s.hasBlockEntity()) {
            return false;
        }
        boolean obstructing = !s.getCollisionShape(world, pos).isEmpty() || (s.isReplaceable() && !s.isAir());
        if (!obstructing) {
            return false;
        }
        mine.accept(bot, pos);
        return true;
    }
}
