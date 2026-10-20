package net.wcfcarolina13.GameAI.services;

import java.util.List;

/**
 * Which side a bot floating in a flooded pocket with no shore should carve a ledge on (finding 48:
 * Bob floated at 234, 62, 299 for hours, no sky, walls on all four sides, and every surface
 * recovery failed because a floating bot cannot pillar).
 *
 * <p>Geometry, with {@code W} the topmost water cell of the bot's column and {@code N} its
 * horizontal neighbour on one side: {@code N} must be solid (it becomes the ledge floor), and the
 * bot will stand in {@code N.up()} with its head in {@code N.up(2)}. Those two cells must end up
 * empty; each that is not already empty costs one mined block, and it may only be mined when it is
 * natural stone, dirt or ore that no protection covers. Ruling (2026-10-19, made on Bradley's
 * behalf): a trapped bot may mine up to {@link #MAX_MINED_BLOCKS} such blocks.</p>
 *
 * <p>Among the usable sides the shallowest column to the sky wins (the bot pillars up from the
 * ledge next), then the fewest blocks to mine, then the lowest side index.</p>
 *
 * <p>Pure: no {@code net.minecraft.*} types.</p>
 */
public final class ShoreStepPolicy {

    /** Most blocks a trapped bot may mine to make its ledge. */
    public static final int MAX_MINED_BLOCKS = 2;

    /** Returned by {@link #choose} when no side qualifies. */
    public static final int NO_SIDE = -1;

    /** What one of the stand cells ({@code N.up()} or {@code N.up(2)}) holds. */
    public enum Cell {
        /** Already empty (air or a replaceable plant), nothing to mine. */
        OPEN,
        /** Natural, unprotected, not a falling block: may be mined. */
        MINEABLE,
        /** Fluid, protected, player-built, unbreakable or a falling block: this side is out. */
        BLOCKED
    }

    /**
     * One side of the pocket.
     *
     * @param side       caller's index for the side (e.g. the horizontal direction's ordinal)
     * @param floorSolid {@code N} is a solid, non-fluid block
     * @param feet       the cell the bot will stand in, {@code N.up()}
     * @param head       the cell above it, {@code N.up(2)}
     * @param hazardNear opening the cells would let fluid in or bring a falling block down
     * @param depthToSky blocks between the stand and open sky (negative = unknown)
     */
    public record Side(int side, boolean floorSolid, Cell feet, Cell head, boolean hazardNear, int depthToSky) {
    }

    private ShoreStepPolicy() {
    }

    /** Blocks this side needs mined, or {@link Integer#MAX_VALUE} when it cannot be used. */
    public static int blocksToMine(Side s) {
        if (s == null || !s.floorSolid() || s.hazardNear()
                || s.feet() == null || s.head() == null
                || s.feet() == Cell.BLOCKED || s.head() == Cell.BLOCKED) {
            return Integer.MAX_VALUE;
        }
        int n = (s.feet() == Cell.MINEABLE ? 1 : 0) + (s.head() == Cell.MINEABLE ? 1 : 0);
        return n <= MAX_MINED_BLOCKS ? n : Integer.MAX_VALUE;
    }

    /** The chosen side's index, or {@link #NO_SIDE}. */
    public static int choose(List<Side> sides) {
        if (sides == null) {
            return NO_SIDE;
        }
        Side best = null;
        for (Side s : sides) {
            if (blocksToMine(s) == Integer.MAX_VALUE) {
                continue;
            }
            if (best == null || better(s, best)) {
                best = s;
            }
        }
        return best == null ? NO_SIDE : best.side();
    }

    private static boolean better(Side a, Side b) {
        int da = depthKey(a.depthToSky());
        int db = depthKey(b.depthToSky());
        if (da != db) {
            return da < db;
        }
        int ma = blocksToMine(a);
        int mb = blocksToMine(b);
        if (ma != mb) {
            return ma < mb;
        }
        return a.side() < b.side();
    }

    private static int depthKey(int depth) {
        return depth < 0 ? Integer.MAX_VALUE : depth;
    }
}
