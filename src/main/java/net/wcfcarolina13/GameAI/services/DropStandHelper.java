package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * World-reading stand checks shared by the woodcut {@code DropSweeper} and the idle drop
 * sweep in {@code BotEventHandler}. At most 5 cells x 3 block reads per call, so it is cheap
 * enough for a per-candidate filter on the server thread. Rules live in {@link DropReachPolicy}.
 */
public final class DropStandHelper {

    private DropStandHelper() {
    }

    /** Feet cell is enterable (empty collision or a walkable partial such as a carpet). */
    public static boolean isCellPassable(ServerWorld world, BlockPos pos) {
        return WalkablePartialBlocks.isPathable(world.getBlockState(pos), world, pos);
    }

    /** True when a bot fits with its feet in {@code feet}: feet + head passable and solid footing. */
    public static boolean isTwoHighStand(ServerWorld world, BlockPos feet) {
        BlockPos below = feet.down();
        boolean footing = !world.getBlockState(below).getCollisionShape(world, below).isEmpty()
                || !world.getFluidState(below).isEmpty();
        return footing && DropReachPolicy.isNudgeCellSafe(isCellPassable(world, feet), isCellPassable(world, feet.up()));
    }

    /**
     * An item is collectable when the bot can stand in its cell or in a horizontal
     * neighbour at the same height (pickup reach is 1 block sideways, 0.5 vertically).
     */
    public static boolean hasTwoHighStandAtOrBeside(ServerWorld world, BlockPos itemCell) {
        if (world == null || itemCell == null) {
            return false;
        }
        if (isTwoHighStand(world, itemCell)) {
            return true;
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (isTwoHighStand(world, itemCell.offset(direction))) {
                return true;
            }
        }
        return false;
    }
}
