package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

/**
 * Shared classification of "walkable partial blocks" — blocks that have non-empty
 * collision shapes but should count as passable/standable for the bot's
 * path-planning, stuck-detection, and passability logic.
 *
 * <p>Two distinct questions are answered here:
 *
 * <ul>
 *   <li>{@link #isPathable(BlockState, BlockView, BlockPos)} — "can the bot walk
 *       INTO this cell from the side without colliding?" Used by path planners
 *       that evaluate future cells. Narrower set: carpets, pressure plates, rails,
 *       tripwire, lily pad, pale-moss/moss carpet, plus a ≤ 0.125 max-Y fallback for
 *       any other truly thin walkable partial. Does NOT include slabs, stairs, or
 *       layered snow — those can block horizontal motion when the partial is on the
 *       "wrong" side (e.g., top slab at foot level) and are handled separately by
 *       the path planners' step-up logic.</li>
 *   <li>{@link #isStandable(BlockState, BlockView, BlockPos)} — "is the bot's feet
 *       blockpos coinciding with this block a normal standing position, or is the
 *       bot stuck?" Used by the rescue service's stuck-detection path. Broader set:
 *       everything from {@code isPathable} plus slabs, stairs, and snow layers,
 *       because the bot standing on top of any of those has feet blockpos equal to
 *       the partial block cell (via {@code Math.floor} on the entity Y).</li>
 * </ul>
 *
 * <p><b>Why this matters:</b> Many walkable partial blocks (carpet 1/16, pressure
 * plate 1/16, rail 1/8, tripwire ~1.5/16) have non-empty collision shapes. A naive
 * {@code !getCollisionShape().isEmpty()} passability check rejects them, causing
 * the pathfinder to route around every carpeted doorway and the rescue service to
 * scream "stuck in blocks" every time the bot stands on a carpet. Both have
 * happened in production (see the 2026-04-10 doorway-stall autopsy and the
 * follow-up pathfinder regression after the rescue-service whitelist landed).</p>
 *
 * <p><b>Why 0.125 as the max-Y fallback:</b> covers 1–2 pixel blocks (carpets,
 * pressure plates, tripwire, rails, layered snow layer 1–2, sculk vein, floor
 * candles, heads, turtle eggs, pink petals, amethyst small buds, etc.) without
 * accidentally matching slab height (0.5) or cake/hopper/composter-style partial
 * collisions that obstruct horizontal movement. Future thin walkable blocks are
 * covered automatically without editing the whitelist.</p>
 */
public final class WalkablePartialBlocks {

    private WalkablePartialBlocks() {}

    /**
     * Narrow check: can the bot walk INTO this cell from the side without colliding?
     * Returns true for empty collision AND for thin walkable partials that never
     * obstruct horizontal motion regardless of direction.
     *
     * <p>Excludes slabs, stairs, and layered snow — those are direction-dependent
     * (top slab at foot level blocks lateral motion) and are handled by the path
     * planner's step-up logic, not by passability.</p>
     */
    public static boolean isPathable(BlockState state, BlockView world, BlockPos pos) {
        if (state == null || world == null || pos == null) {
            return false;
        }

        Block block = state.getBlock();

        // Direction-independent walkable partials.
        if (block instanceof CarpetBlock) return true;
        if (block instanceof AbstractPressurePlateBlock) return true;
        if (block instanceof AbstractRailBlock) return true;

        // Explicit blocks not caught by class hierarchy in 1.21.
        if (state.isOf(Blocks.PALE_MOSS_CARPET)) return true;
        if (state.isOf(Blocks.MOSS_CARPET)) return true;
        if (state.isOf(Blocks.TRIPWIRE)) return true;
        if (state.isOf(Blocks.LILY_PAD)) return true;

        // Fallback: any block with collision shape max Y ≤ 0.125 is trivially
        // walkable regardless of direction. Catches floor candles, skulls, sculk
        // vein, turtle eggs, pink petals, amethyst small buds, frogspawn, etc.
        var shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            return true;
        }
        return shape.getMax(Direction.Axis.Y) <= 0.125D;
    }

    /**
     * Broad check: is the bot's feet blockpos coinciding with this block a normal
     * standing position? Returns true for everything {@link #isPathable} returns true
     * for, PLUS slabs, stairs, and layered snow — because the bot standing on top of
     * any of those has its feet blockpos equal to the partial block cell (since
     * {@code Entity.getBlockPos()} floors the entity Y and these partials all have
     * top surfaces < 1.0 blocks above the cell floor).
     *
     * <p>Used by stuck detection to avoid firing rescue on a bot that is simply
     * standing on a slab/stair/carpet — all normal walking situations.</p>
     */
    public static boolean isStandable(BlockState state, BlockView world, BlockPos pos) {
        if (isPathable(state, world, pos)) {
            return true;
        }
        if (state == null) {
            return false;
        }
        Block block = state.getBlock();
        if (block instanceof SlabBlock) return true;
        if (block instanceof StairsBlock) return true;
        if (block instanceof SnowBlock) return true;
        return false;
    }
}
