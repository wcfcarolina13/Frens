package net.wcfcarolina13.PathFinding;

/**
 * The one edge rule both pathfinders share (1.1.240): a planned move is only allowed when a
 * walking, jumping bot can actually execute it. No pillaring, no 2-high hops, no floating cells.
 *
 * <p>Every landing cell must be a standing cell: solid floor below, body and head passable.
 * On top of that:
 * <ul>
 *   <li>{@code dy == 0}: a flat (or diagonal) step. Diagonal corner clearance is checked by the
 *       finder separately.</li>
 *   <li>{@code dy == +1}: a one-block step-up jump. The bot needs headroom above its origin
 *       (origin.y + 2) to jump.</li>
 *   <li>{@code dy == -1}: a one-block step-down. The bot's head passes through the cell above the
 *       landing head (landing.y + 2, level with the origin head) before it drops.</li>
 *   <li>Anything steeper ({@code |dy| >= 2}) is refused. A 2-high rise needs a placed block or a
 *       broken one, which neither finder models yet.</li>
 * </ul>
 *
 * <p>The rule is symmetric when both cells are standing cells: step-up A→B and step-down B→A
 * read the same cells, which the bidirectional finder relies on (its backward frontier uses the
 * same neighbour function).
 */
public final class PathMovePolicy {

    /** Highest rise a planned move may have (a single jump). */
    public static final int MAX_RISE = 1;
    /** Deepest drop a planned move may have (what both executors walk today). */
    public static final int MAX_DROP = 1;

    private PathMovePolicy() {
    }

    /**
     * @param dy                    landing.y - origin.y
     * @param landingHasFloor       solid block under the landing cell
     * @param landingBodyClear      landing cell itself passable
     * @param landingHeadClear      cell above the landing passable
     * @param originHeadroomClear   origin.y + 2 passable (only read for a rise)
     * @param landingAboveHeadClear landing.y + 2 passable (only read for a drop)
     */
    public static boolean isExecutable(int dy,
                                       boolean landingHasFloor,
                                       boolean landingBodyClear,
                                       boolean landingHeadClear,
                                       boolean originHeadroomClear,
                                       boolean landingAboveHeadClear) {
        if (dy > MAX_RISE || dy < -MAX_DROP) {
            return false;
        }
        if (!landingHasFloor || !landingBodyClear || !landingHeadClear) {
            return false;
        }
        if (dy > 0) {
            return originHeadroomClear;
        }
        if (dy < 0) {
            return landingAboveHeadClear;
        }
        return true;
    }
}
