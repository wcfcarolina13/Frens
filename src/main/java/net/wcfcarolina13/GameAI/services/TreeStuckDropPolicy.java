package net.wcfcarolina13.GameAI.services;

/**
 * Tree-canopy escape rules ({@link TreeStuckEscapeService}) that need no Minecraft types.
 */
public final class TreeStuckDropPolicy {

    /** Vanilla fall damage is fall distance minus 3: a 3-block drop is the deepest that costs nothing. */
    public static final int SAFE_DROP_HEIGHT = 3;

    private TreeStuckDropPolicy() {}

    /** Whether {@code drop} open blocks under an edge cell make it a safe walk-off. */
    public static boolean isSafeDrop(int drop) {
        return drop >= 0 && drop <= SAFE_DROP_HEIGHT;
    }

    /** How far the bot's centre may be from the column centre with its whole hitbox (half-width 0.3) inside the column. */
    public static final double CLEAR_OF_LIP = 0.2D;

    /**
     * In the air on the way to the safe-drop column ({@code tx,tz} its block coordinates): once the
     * whole hitbox is inside that column and still moving sideways, the sideways momentum is
     * dropped so the bot comes down there and not over the next, deeper column. Holding as soon
     * as the centre crossed in would leave part of the hitbox over the leaf lip, and the bot would
     * land back on it.
     */
    public static boolean holdOverTarget(double x, double z, int tx, int tz, double vx, double vz) {
        return Math.abs(x - (tx + 0.5D)) <= CLEAR_OF_LIP && Math.abs(z - (tz + 0.5D)) <= CLEAR_OF_LIP
                && (vx * vx + vz * vz) > 1.0E-6D;
    }

    /** Ticks the bot may spend edging onto one hole before it gives up on that hole. */
    public static final int HOLE_NUDGE_MAX_TICKS = 40;

    /** Fastest sideways speed, in blocks per tick, used to edge onto the hole. */
    public static final double HOLE_NUDGE_MAX_SPEED = 0.1D;

    /**
     * Standing on the ground with the cell under its block position open: the hitbox is resting on
     * the lip of a neighbouring leaf, usually over the hole the bot just broke to get down. The leaf
     * break reads "below is air" and stops, and the bot stays there (sandbox 2026-11-09, 3 of 4 runs).
     * It edges onto the hole when the support is a leaf, the drop into the hole costs no health, the
     * commander is lower down (or unknown), and this hole has not already had its time.
     *
     * @param holeDrop open cells below the feet before the first block it would land on
     * @param commanderFeetY the commander's block Y, or {@code null} when there is none
     * @param maxDrop {@link #SAFE_DROP_HEIGHT}, or deeper for a hole broken as the last resort
     */
    public static boolean shouldEdgeIntoHole(boolean onGround, boolean belowOpen, boolean supportIsLeaf,
                                             int holeDrop, int botFeetY, Integer commanderFeetY,
                                             long ticksOnThisHole, int maxDrop) {
        if (!onGround || !belowOpen || !supportIsLeaf) {
            return false;
        }
        if (holeDrop < 1 || holeDrop > maxDrop) {
            return false;
        }
        if (commanderFeetY != null && commanderFeetY >= botFeetY) {
            return false;
        }
        return ticksOnThisHole <= HOLE_NUDGE_MAX_TICKS;
    }

    /** Deepest fall the 10 s last resort accepts (it costs up to 5 HP, never more). */
    public static final int LAST_RESORT_MAX_FALL = 8;

    /**
     * A cell beside the bot where it could stand on a leaf: offset from the bot's block position
     * ({@code dy} -1, 0 or 1), the worst single fall digging down that column leaf by leaf, and how many leaves (0-2) it
     * has to break first to get its feet and head into that cell.
     */
    public record SideStep(int dx, int dy, int dz, int fall, int breaks) {
        boolean diagonal() {
            return dx != 0 && dz != 0;
        }
    }

    /**
     * Where to step before breaking a leaf, when the one under the bot is not a safe fall. At a
     * tree on a terrace edge the bot ends up on the leaves over the low side, and the leaf under it
     * opens a 6-block fall, while the leaf a block toward the trunk sits over the high side
     * (sandbox 2026-11-09: 3 HP in 4 of 6 runs). Picks the shallowest fall, then the fewest leaves
     * to break, straight before diagonal, level before a step. A step that is no safe fall is taken only as the last resort,
     * and only when it falls less than breaking straight down.
     *
     * @param belowFall the worst fall digging straight down from where the bot stands ({@link Integer#MAX_VALUE} when unsafe)
     * @param health the bot's health: a costly step leaves at least a heart
     * @return the step to take, or {@code null} to stay
     */
    public static SideStep chooseSideStep(java.util.List<SideStep> steps, int belowFall, boolean lastResort, float health) {
        if (steps == null || isSafeDrop(belowFall)) {
            return null;
        }
        SideStep best = null;
        for (SideStep s : steps) {
            if (s == null || s.fall() < 1 || s.fall() > LAST_RESORT_MAX_FALL) {
                continue;
            }
            if (best == null || s.fall() < best.fall()
                    || (s.fall() == best.fall() && rank(s) < rank(best))) {
                best = s;
            }
        }
        if (best == null) {
            return null;
        }
        if (isSafeDrop(best.fall())) {
            return best;
        }
        // A costly step must leave at least a heart: a below column that never broke (too deep or a
        // bad landing) used to keep a hurt bot on the canopy (review 2026-11-09).
        boolean survivable = health - (best.fall() - SAFE_DROP_HEIGHT) >= 2.0F;
        return lastResort && best.fall() < belowFall && survivable ? best : null;
    }

    private static int rank(SideStep s) {
        return s.breaks() * 4 + (s.diagonal() ? 2 : 0) + Math.abs(s.dy());
    }

    /** Ticks the bot may take to step onto the chosen cell before it gives that cell up. */
    public static final int SIDE_STEP_MAX_TICKS = 60;

    /** Sideways speed on one axis that moves the centre {@code pos} toward the column centre {@code columnCentre}. */
    public static double edgeSpeed(double pos, double columnCentre) {
        double v = (columnCentre - pos) * 0.5D;
        return Math.max(-HOLE_NUDGE_MAX_SPEED, Math.min(HOLE_NUDGE_MAX_SPEED, v));
    }
}
