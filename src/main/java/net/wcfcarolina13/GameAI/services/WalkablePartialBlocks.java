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
import net.minecraft.registry.tag.BlockTags;
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
        if (isSinkableSurface(state, world, pos)) return true;
        return false;
    }

    /**
     * "Sinkable" full-cell surface: a block where vanilla physics seats the entity
     * slightly below the cell top, so the bot's floored {@code BlockPos.Y} lands on
     * the cell rather than above it. Soul Sand (collision maxY=0.875), Mud (0.9),
     * Muddy Mangrove Roots, Honey Block (0.9375), Farmland (0.9375), Dirt Path
     * (0.9375), plus third-party mod variants like Wet Sand's "Soaked Sand".
     *
     * <p>The bug this fixes: {@link BotRescueService#rescueFromBurial} and the
     * movement-impulse gate both classify the bot as "stuck in blocks" whenever
     * its feet blockpos coincides with a non-empty-collision block that isn't on
     * a hardcoded allowlist. Hardcoding every mod's sinkable variant doesn't
     * scale. This method classifies by property, with three layers:</p>
     *
     * <ol>
     *   <li><b>Vanilla explicit allowlist</b> (fast path for the cases we know).</li>
     *   <li><b>Block-tag inclusion</b> ({@code BlockTags.SAND}, {@code BlockTags.DIRT})
     *       — auto-covers any mod block whose author tagged it correctly. Wet
     *       Sand's "Soaked Sand" likely lives here.</li>
     *   <li><b>Collision-shape heuristic</b> — non-empty shape with top face in
     *       {@code (0.5, 1.0)} that isn't a slab/stair/fence/wall. Catches mods
     *       that ship blocks without proper tags. Excludes fence/wall/gate tags
     *       defensively so a real burial in a fence cell still triggers rescue.</li>
     * </ol>
     *
     * <p>Note: vanilla {@code SAND}/{@code RED_SAND} themselves are full-cell
     * (maxY=1.0); they don't seat the entity inside the cell, so they aren't and
     * shouldn't be sinkable. Soaked Sand from the Wet Sand mod IS sinkable
     * (entity logged at Y=62.92 on a block at Y=62 — partial collision).</p>
     */
    public static boolean isSinkableSurface(BlockState state, BlockView world, BlockPos pos) {
        if (state == null || world == null || pos == null) {
            return false;
        }

        // Vanilla sinkable surfaces.
        if (state.isOf(Blocks.SOUL_SAND)) return true;
        if (state.isOf(Blocks.SOUL_SOIL)) return true;
        if (state.isOf(Blocks.MUD)) return true;
        if (state.isOf(Blocks.MUDDY_MANGROVE_ROOTS)) return true;
        if (state.isOf(Blocks.HONEY_BLOCK)) return true;
        if (state.isOf(Blocks.FARMLAND)) return true;
        if (state.isOf(Blocks.DIRT_PATH)) return true;

        // Defensive: never call a fence/wall/gate "standable", even if its shape
        // happens to fall in our heuristic window. A real burial in those cells
        // should keep triggering the rescue path.
        if (state.isIn(BlockTags.FENCES)) return false;
        if (state.isIn(BlockTags.FENCE_GATES)) return false;
        if (state.isIn(BlockTags.WALLS)) return false;

        // Heuristic: non-empty collision with top face in (0.5, 1.0) means vanilla
        // seats the entity inside the cell rather than on top of it. The < 1.0 cap
        // rules out full-cell sand/dirt (vanilla SAND/RED_SAND themselves are
        // BlockTags.SAND but full-cell, so they sit BELOW the bot's feet blockpos
        // and never trip the rescue). The > 0.5 floor rules out slabs and thinner
        // partials already covered by isPathable's 0.125 fallback or the explicit
        // SlabBlock class check above. Catches Soul Sand-equivalent mod variants
        // (Wet Sand's "Soaked Sand", custom mud, etc.) without per-block allowlists.
        var shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            return false;
        }
        double maxY = shape.getMax(Direction.Axis.Y);
        return maxY > 0.5D && maxY < 1.0D;
    }
}
