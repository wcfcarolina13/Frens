package net.wcfcarolina13.PathFinding;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/**
 * The one "can a body wade here" read both pathfinders share (1.1.240 slice E). The edge rule
 * itself lives in {@link PathMovePolicy}; this only classifies a cell.
 *
 * <p>Wadeable: water (source or flowing) whose block has no collision shape. Lava is never
 * water; a bubble column is refused (it pushes the bot up or drags it down); a waterlogged block
 * with collision (slab, stairs, fence, ...) is refused, the bot cannot walk through it.
 */
public final class WadeableWater {

    private WadeableWater() {
    }

    public static boolean isWadeable(BlockState state, BlockView world, BlockPos pos) {
        if (state == null || state.isAir() || state.isOf(Blocks.BUBBLE_COLUMN)) {
            return false;
        }
        if (!state.getFluidState().isIn(FluidTags.WATER)) {
            return false;
        }
        if (net.wcfcarolina13.GameAI.services.BotHazardService.isDeadlyBlock(state)) {
            return false;
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    /**
     * Extra head check over a wading body: the head cell must have no collision at all. A lily
     * pad is a walkable partial (fine to stand ON), but it sits at head height over a 1-deep pond
     * and blocks a wading bot.
     */
    public static boolean headFitsOverWater(BlockState headState, BlockView world, BlockPos headPos) {
        return headState != null && headState.getCollisionShape(world, headPos).isEmpty();
    }
}
