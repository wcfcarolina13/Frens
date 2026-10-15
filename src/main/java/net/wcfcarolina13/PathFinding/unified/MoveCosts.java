package net.wcfcarolina13.PathFinding.unified;

import static net.wcfcarolina13.PathFinding.unified.ActionCosts.*;
import static net.wcfcarolina13.PathFinding.unified.CellProps.*;

import net.wcfcarolina13.GameAI.services.TerrainMovePolicy;

/**
 * The one pure cost function (design section 3). The search, {@link MoveSimulator}, the Dijkstra
 * reference and {@link CostVerifier} all call it, so they cannot disagree.
 *
 * <p>Conventions: (x,y,z) is the origin feet cell, F = origin + (dx,0,dz). A "standing cell" has a
 * SOLID_FLOOR below, a dry passable body or a wadeable one, and a dry passable head; over a wading
 * body the head must also have no collision (approximated as "not SOLID_FLOOR", which is what
 * {@code headFitsOverWater} rules out). Cells read for preconditions must be LOADED; the landing
 * feet cell must be inside the search box.</p>
 *
 * <p>The returned {@link PlannedMove#ticks()} is the whole edge cost: base table cost, break ticks,
 * wade penalty and hazard penalty, minus the anchor bonus clamped to the penalty part (hazard plus
 * wade), so a move never costs less than its base table cost and the heuristic stays admissible.</p>
 */
public final class MoveCosts {

    /** Why candidates were refused, tallied per search for {@link NoPathReason}. */
    public static final class Refusals {
        public int protectedCells;
        public int unbreakable;
        public int noScaffold;
        public int unloaded;

        public void reset() {
            protectedCells = 0;
            unbreakable = 0;
            noScaffold = 0;
            unloaded = 0;
        }

        @Override
        public String toString() {
            return "protected=" + protectedCells + " unbreakable=" + unbreakable + " noScaffold=" + noScaffold
                    + " unloaded=" + unloaded;
        }
    }

    /** Throwaway sink so the four-argument paths need no null checks. */
    private static final ThreadLocal<Refusals> SCRATCH = ThreadLocal.withInitial(Refusals::new);

    private static final double SQRT2 = Math.sqrt(2);

    private MoveCosts() {
    }

    // ---------------------------------------------------------------- unit costs

    /** The cheapest horizontal step in this context: SPRINT when {@code canSprint}, else WALK. */
    public static double minHorizontal(SearchContext ctx) {
        return ctx.canSprint() ? SPRINT_ONE_BLOCK : WALK_ONE_BLOCK;
    }

    /** {@code max(JUMP_ONE_BLOCK, minHorizontal) + JUMP_PENALTY}. */
    public static double ascendBase(SearchContext ctx) {
        return Math.max(JUMP_ONE_BLOCK, minHorizontal(ctx)) + JUMP_PENALTY;
    }

    /**
     * Cheapest extra cost per block of drop over a horizontal step:
     * {@code max(0, min over n in 1..maxFall of (WALK_OFF + FALL_N[n] + CENTER - C_xz) / n)},
     * and with {@code allowDownward} also {@code BREAK_ADDITIONAL + FALL_N[1]}.
     */
    public static double downExtra(SearchContext ctx) {
        double cxz = minHorizontal(ctx);
        double best = Double.MAX_VALUE;
        for (int n = 1; n <= ctx.maxFall(); n++) {
            best = Math.min(best, (WALK_OFF_BLOCK + fallN(n) + CENTER_AFTER_FALL - cxz) / n);
        }
        if (ctx.allowDownward()) {
            best = Math.min(best, BREAK_ADDITIONAL + fallN(1));
        }
        return Math.max(0, best);
    }

    /** Design section 4 heuristic, with the 1/1000 tie-break nudge on the horizontal term. */
    public static double heuristic(SearchContext ctx, int x, int y, int z, int gx, int gy, int gz) {
        double cxz = minHorizontal(ctx);
        int cheb = Math.max(Math.abs(gx - x), Math.abs(gz - z));
        int up = Math.max(0, gy - y);
        int down = Math.max(0, y - gy);
        return cxz * cheb * (1 + 1 / 1000.0) + up * (ascendBase(ctx) - cxz) + down * downExtra(ctx);
    }

    /** Heuristic invariant every move must satisfy: {@code ticks >= lowerBound(ctx, xzSteps, dy)}. */
    public static double lowerBound(SearchContext ctx, int xzSteps, int dy) {
        double cxz = minHorizontal(ctx);
        return cxz * xzSteps + (ascendBase(ctx) - cxz) * Math.max(0, dy) + downExtra(ctx) * Math.max(0, -dy);
    }

    // ---------------------------------------------------------------- cell predicates

    private static boolean wadeBody(int p) {
        return (p & (LOADED | WATER_WADE)) == (LOADED | WATER_WADE) && (p & LOCKED_DOOR) == 0;
    }

    private static boolean bodyOk(int p) {
        return passable(p) || wadeBody(p);
    }

    private static boolean headOk(int head, int body) {
        return passable(head) && !(has(body, WATER_WADE) && has(head, SOLID_FLOOR));
    }

    /** Standing cell, wading allowed. */
    public static boolean isStanding(TerrainView t, int x, int y, int z) {
        int floor = t.props(x, y - 1, z);
        if (!floor(floor)) {
            return false;
        }
        int body = t.props(x, y, z);
        return bodyOk(body) && headOk(t.props(x, y + 1, z), body);
    }

    /** Standing cell with a dry body (no wading). */
    public static boolean isDryStanding(TerrainView t, int x, int y, int z) {
        return floor(t.props(x, y - 1, z)) && passable(t.props(x, y, z)) && passable(t.props(x, y + 1, z));
    }

    /** Swim cell: deep water body, dry head with no collision. */
    public static boolean isSwimCell(TerrainView t, int x, int y, int z) {
        int body = t.props(x, y, z);
        if ((body & (LOADED | WATER_DEEP)) != (LOADED | WATER_DEEP) || has(body, LAVA)) {
            return false;
        }
        int head = t.props(x, y + 1, z);
        return passable(head) && !has(head, SOLID_FLOOR);
    }

    /** Landing body, head, above-head and floor carry no hazard or lava. */
    private static boolean landingSafe(TerrainView t, int x, int y, int z) {
        if (has(t.props(x, y, z), HAZARD | LAVA)) {
            return false;
        }
        if (has(t.props(x, y + 1, z), LAVA) || has(t.props(x, y + 2, z), LAVA)) {
            return false;
        }
        return !has(t.props(x, y - 1, z), HAZARD | LAVA);
    }

    private static boolean anyUnloaded(Refusals r, int... props) {
        for (int p : props) {
            if (!loaded(p)) {
                r.unloaded++;
                return true;
            }
        }
        return false;
    }

    /**
     * Ticks to break one cell including BREAK_ADDITIONAL, or COST_INF. Counts the refusal reason.
     * Liquids are never broken, nor a cell with liquid above or any liquid beside it (it would flow in).
     */
    static double breakCost(SearchContext ctx, TerrainView t, int x, int y, int z, Refusals r) {
        int p = t.props(x, y, z);
        if (!loaded(p)) {
            r.unloaded++;
            return COST_INF;
        }
        if (liquid(p) || !ctx.allowBreak()) {
            return COST_INF;
        }
        if (has(p, PROTECTED)) {
            r.protectedCells++;
            return COST_INF;
        }
        if (has(p, NEVER_BREAK | LOCKED_DOOR)) {
            r.unbreakable++;
            return COST_INF;
        }
        int bt = t.breakTicks(x, y, z);
        if (bt < 0 || bt >= COST_INF || bt > ctx.maxBreakTicks()) {
            r.unbreakable++;
            return COST_INF;
        }
        if (liquid(t.props(x, y + 1, z)) || liquid(t.props(x + 1, y, z)) || liquid(t.props(x - 1, y, z))
                || liquid(t.props(x, y, z + 1)) || liquid(t.props(x, y, z - 1))) {
            return COST_INF;
        }
        return bt + BREAK_ADDITIONAL;
    }

    /** Small fixed-capacity break list (at most 3 cells per move). */
    private static final class Breaks {
        final long[] cells = new long[3];
        int n;
        double ticks;

        void add(int x, int y, int z, double cost) {
            cells[n++] = CellPos.pack(x, y, z);
            ticks += cost;
        }

        long[] toArray() {
            if (n == 0) {
                return PlannedMove.NONE;
            }
            long[] out = new long[n];
            System.arraycopy(cells, 0, out, 0, n);
            return out;
        }

        boolean contains(long packed) {
            for (int i = 0; i < n; i++) {
                if (cells[i] == packed) {
                    return true;
                }
            }
            return false;
        }

        /** A gravity block directly above a broken cell (and not broken itself) refills the hole. */
        boolean gravityRefill(TerrainView t) {
            for (int i = 0; i < n; i++) {
                int x = CellPos.x(cells[i]);
                int y = CellPos.y(cells[i]);
                int z = CellPos.z(cells[i]);
                if (has(t.props(x, y + 1, z), GRAVITY) && !contains(CellPos.pack(x, y + 1, z))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Clears a body or head cell: 0 when already passable, the break cost when it can be mined, or
     * COST_INF. The cell is appended to {@code breaks} when mined.
     */
    private static double clear(SearchContext ctx, TerrainView t, int x, int y, int z, Breaks breaks, Refusals r) {
        int p = t.props(x, y, z);
        if (passable(p)) {
            return 0;
        }
        double c = breakCost(ctx, t, x, y, z, r);
        if (c >= COST_INF) {
            return COST_INF;
        }
        breaks.add(x, y, z, c);
        return c;
    }

    // ---------------------------------------------------------------- evaluate

    /** Returns the move for (from, kind, dir) or null when a precondition fails. */
    public static PlannedMove evaluate(SearchContext ctx, TerrainView t, int x, int y, int z, MoveKind kind,
                                       int dx, int dz, int scaffoldLeft, int swimRun) {
        Refusals scratch = SCRATCH.get();
        scratch.reset();
        return evaluate(ctx, t, x, y, z, kind, dx, dz, scaffoldLeft, swimRun, scratch);
    }

    /** As {@link #evaluate(SearchContext, TerrainView, int, int, int, MoveKind, int, int, int, int)}, tallying refusals into {@code r}. */
    public static PlannedMove evaluate(SearchContext ctx, TerrainView t, int x, int y, int z, MoveKind kind,
                                       int dx, int dz, int scaffoldLeft, int swimRun, Refusals r) {
        return switch (kind) {
            case TRAVERSE -> traverse(ctx, t, x, y, z, dx, dz, scaffoldLeft, r);
            case DIAGONAL -> diagonal(ctx, t, x, y, z, dx, dz, scaffoldLeft, r);
            case ASCEND -> ascend(ctx, t, x, y, z, dx, dz, scaffoldLeft, r);
            case DESCEND -> descend(ctx, t, x, y, z, dx, dz, scaffoldLeft, r);
            case FALL -> fall(ctx, t, x, y, z, dx, dz, scaffoldLeft, r);
            case ASCEND_PLACE -> ascendPlace(ctx, t, x, y, z, dx, dz, scaffoldLeft, r);
            case SWIM -> swim(ctx, t, x, y, z, dx, dz, scaffoldLeft, swimRun, r);
            case DOWNWARD -> downward(ctx, t, x, y, z, scaffoldLeft, r);
        };
    }

    private static PlannedMove traverse(SearchContext ctx, TerrainView t, int x, int y, int z, int dx, int dz,
                                        int scaffoldLeft, Refusals r) {
        int fx = x + dx;
        int fz = z + dz;
        if (!ctx.box().contains(fx, y, fz)) {
            return null;
        }
        int floor = t.props(fx, y - 1, fz);
        int body = t.props(fx, y, fz);
        int head = t.props(fx, y + 1, fz);
        if (anyUnloaded(r, floor, body, head)) {
            return null;
        }
        if (!floor(floor) || has(floor, HAZARD | LAVA) || has(body, HAZARD | LAVA | WATER_DEEP)) {
            return null;
        }
        boolean wade = wadeBody(body);
        Breaks breaks = new Breaks();
        if (!passable(head)) {
            if (clear(ctx, t, fx, y + 1, fz, breaks, r) >= COST_INF) {
                return null;
            }
        } else if (wade && has(head, SOLID_FLOOR)) {
            return null;
        }
        if (!wade && clear(ctx, t, fx, y, fz, breaks, r) >= COST_INF) {
            return null;
        }
        if (breaks.gravityRefill(t) || !landingSafe(t, fx, y, fz)) {
            return null;
        }
        double wadeTicks = wade ? WADE_PENALTY : 0;
        return finish(ctx, MoveKind.TRAVERSE, x, y, z, fx, y, fz, breaks, PlannedMove.NONE,
                minHorizontal(ctx), wadeTicks, scaffoldLeft, 0);
    }

    private static PlannedMove diagonal(SearchContext ctx, TerrainView t, int x, int y, int z, int dx, int dz,
                                        int scaffoldLeft, Refusals r) {
        if (dx == 0 || dz == 0) {
            return null;
        }
        int fx = x + dx;
        int fz = z + dz;
        if (!ctx.box().contains(fx, y, fz)) {
            return null;
        }
        // Both side corridors, body and head (mineflayer #393), then a dry landing.
        if (!passable(t.props(fx, y, z)) || !passable(t.props(fx, y + 1, z))
                || !passable(t.props(x, y, fz)) || !passable(t.props(x, y + 1, fz))) {
            return null;
        }
        if (!isDryStanding(t, fx, y, fz) || !landingSafe(t, fx, y, fz)) {
            return null;
        }
        return finish(ctx, MoveKind.DIAGONAL, x, y, z, fx, y, fz, null, PlannedMove.NONE,
                minHorizontal(ctx) * SQRT2, 0, scaffoldLeft, 0);
    }

    private static PlannedMove ascend(SearchContext ctx, TerrainView t, int x, int y, int z, int dx, int dz,
                                      int scaffoldLeft, Refusals r) {
        int fx = x + dx;
        int fz = z + dz;
        int ty = y + 1;
        if (!ctx.box().contains(fx, ty, fz)) {
            return null;
        }
        int step = t.props(fx, y, fz);
        if (!loaded(step)) {
            r.unloaded++;
            return null;
        }
        if (!floor(step) || passable(step) || has(step, HAZARD | LAVA)) {
            return null;
        }
        int body = t.props(fx, ty, fz);
        int head = t.props(fx, ty + 1, fz);
        int originHead2 = t.props(x, y + 2, z);
        if (anyUnloaded(r, body, head, originHead2)) {
            return null;
        }
        if (has(body, HAZARD | LAVA | WATER_DEEP)) {
            return null;
        }
        boolean wade = wadeBody(body);
        Breaks breaks = new Breaks();
        // Top cells first, so nothing falls into a cell already mined.
        if (clear(ctx, t, x, y + 2, z, breaks, r) >= COST_INF) {
            return null;
        }
        if (!passable(head)) {
            if (clear(ctx, t, fx, ty + 1, fz, breaks, r) >= COST_INF) {
                return null;
            }
        } else if (wade && has(head, SOLID_FLOOR)) {
            return null;
        }
        if (!wade && clear(ctx, t, fx, ty, fz, breaks, r) >= COST_INF) {
            return null;
        }
        if (breaks.gravityRefill(t) || !landingSafe(t, fx, ty, fz)) {
            return null;
        }
        // A slab-top step needs no jump, but it is floored at the ascend cost to keep the heuristic
        // admissible (design section 3 invariant); the executor reads the jump from the floor cell.
        double wadeTicks = wade ? WADE_PENALTY : 0;
        return finish(ctx, MoveKind.ASCEND, x, y, z, fx, ty, fz, breaks, PlannedMove.NONE,
                ascendBase(ctx), wadeTicks, scaffoldLeft, 0);
    }

    private static PlannedMove descend(SearchContext ctx, TerrainView t, int x, int y, int z, int dx, int dz,
                                       int scaffoldLeft, Refusals r) {
        int fx = x + dx;
        int fz = z + dz;
        int ty = y - 1;
        if (!ctx.box().contains(fx, ty, fz)) {
            return null;
        }
        int laneBody = t.props(fx, y, fz);
        int laneHead = t.props(fx, y + 1, fz);
        int body = t.props(fx, ty, fz);
        int floor = t.props(fx, ty - 1, fz);
        if (anyUnloaded(r, laneBody, laneHead, body, floor)) {
            return null;
        }
        if (!passable(laneBody) || !passable(laneHead)) {
            return null;
        }
        if (!floor(floor) || !bodyOk(body) || !headOk(laneBody, body) || !landingSafe(t, fx, ty, fz)) {
            return null;
        }
        double wadeTicks = wadeBody(body) ? WADE_PENALTY : 0;
        return finish(ctx, MoveKind.DESCEND, x, y, z, fx, ty, fz, null, PlannedMove.NONE,
                WALK_OFF_BLOCK + fallN(1) + CENTER_AFTER_FALL, wadeTicks, scaffoldLeft, 0);
    }

    private static PlannedMove fall(SearchContext ctx, TerrainView t, int x, int y, int z, int dx, int dz,
                                    int scaffoldLeft, Refusals r) {
        int fx = x + dx;
        int fz = z + dz;
        int laneBody = t.props(fx, y, fz);
        int laneHead = t.props(fx, y + 1, fz);
        if (anyUnloaded(r, laneBody, laneHead)) {
            return null;
        }
        if (!passable(laneBody) || !passable(laneHead)) {
            return null;
        }
        int landing = Integer.MIN_VALUE;
        boolean wade = false;
        for (int k = 1; k <= ctx.maxFall() + 1; k++) {
            int c = t.props(fx, y - k, fz);
            if (!loaded(c)) {
                r.unloaded++;
                return null;
            }
            if (passable(c)) {
                continue;
            }
            if (wadeBody(c)) {
                landing = y - k;
                wade = true;
            } else if (floor(c) && !liquid(c)) {
                landing = y - k + 1;
            }
            break;
        }
        if (landing == Integer.MIN_VALUE) {
            return null;
        }
        int n = y - landing;
        if (n < 2 || n > ctx.maxFall() || !ctx.box().contains(fx, landing, fz)) {
            return null;
        }
        if (!isStanding(t, fx, landing, fz) || !landingSafe(t, fx, landing, fz)) {
            return null;
        }
        double wadeTicks = wade ? WADE_PENALTY : 0;
        return finish(ctx, MoveKind.FALL, x, y, z, fx, landing, fz, null, PlannedMove.NONE,
                WALK_OFF_BLOCK + fallN(n) + CENTER_AFTER_FALL, wadeTicks, scaffoldLeft, 0);
    }

    private static PlannedMove ascendPlace(SearchContext ctx, TerrainView t, int x, int y, int z, int dx, int dz,
                                           int scaffoldLeft, Refusals r) {
        int fx = x + dx;
        int fz = z + dz;
        int ty = y + 2;
        if (!ctx.box().contains(fx, ty, fz)) {
            return null;
        }
        int step1 = t.props(fx, y, fz);
        int step2 = t.props(fx, y + 1, fz);
        if (anyUnloaded(r, step1, step2)) {
            return null;
        }
        // Rise exactly 2: two solid cells in front.
        if (!floor(step1) || passable(step1) || !floor(step2) || passable(step2) || has(step2, HAZARD | LAVA)) {
            return null;
        }
        int o2 = t.props(x, y + 2, z);
        int o3 = t.props(x, y + 3, z);
        if (anyUnloaded(r, o2, o3)) {
            return null;
        }
        if (!passable(o2) || !passable(o3) || !isDryStanding(t, fx, ty, fz) || !landingSafe(t, fx, ty, fz)) {
            return null;
        }
        if (!ctx.allowPlace()) {
            return null;
        }
        if (scaffoldLeft <= 0) {
            r.noScaffold++;
            return null;
        }
        int origin = t.props(x, y, z);
        if (has(origin, PROTECTED)) {
            r.protectedCells++;
            return null;
        }
        if (!has(origin, PLACEABLE)) {
            return null;
        }
        double scarcity = TerrainMovePolicy.PLACE_COST * TerrainMovePolicy.TICKS_PER_COST_UNIT / scaffoldLeft;
        double base = BLOCK_PLACEMENT_PENALTY + JUMP_ONE_BLOCK + ascendBase(ctx) + scarcity;
        return finish(ctx, MoveKind.ASCEND_PLACE, x, y, z, fx, ty, fz, null, new long[]{CellPos.pack(x, y, z)},
                base, 0, scaffoldLeft - 1, 0);
    }

    private static PlannedMove swim(SearchContext ctx, TerrainView t, int x, int y, int z, int dx, int dz,
                                    int scaffoldLeft, int swimRun, Refusals r) {
        if (!ctx.allowSwim()) {
            return null;
        }
        int fx = x + dx;
        int fz = z + dz;
        int ty;
        double base;
        int swimAfter;
        if (!isSwimCell(t, x, y, z)) {
            // Entry from a standing cell, level or one down.
            if (ctx.maxSwimRun() < 1) {
                return null;
            }
            if (isSwimCell(t, fx, y, fz)) {
                ty = y;
            } else if (passable(t.props(fx, y, fz)) && passable(t.props(fx, y + 1, fz)) && isSwimCell(t, fx, y - 1, fz)) {
                ty = y - 1;
            } else {
                return null;
            }
            base = WALK_ONE_IN_WATER + SWIM_ENTRY_PENALTY;
            swimAfter = 1;
        } else if (isSwimCell(t, fx, y, fz)) {
            if (swimRun >= ctx.maxSwimRun()) {
                return null;
            }
            ty = y;
            base = WALK_ONE_IN_WATER;
            swimAfter = swimRun + 1;
        } else if (isStanding(t, fx, y, fz)) {
            ty = y;
            base = WALK_ONE_IN_WATER;
            swimAfter = 0;
        } else if (isStanding(t, fx, y + 1, fz) && passable(t.props(x, y + 2, z))) {
            // Vanilla jumps one block out of water; floored at the ascend cost (heuristic invariant).
            ty = y + 1;
            base = Math.max(WALK_ONE_IN_WATER, ascendBase(ctx));
            swimAfter = 0;
        } else {
            return null;
        }
        if (!ctx.box().contains(fx, ty, fz) || !landingSafe(t, fx, ty, fz)) {
            return null;
        }
        return finish(ctx, MoveKind.SWIM, x, y, z, fx, ty, fz, null, PlannedMove.NONE, base, 0, scaffoldLeft, swimAfter);
    }

    private static PlannedMove downward(SearchContext ctx, TerrainView t, int x, int y, int z, int scaffoldLeft,
                                        Refusals r) {
        if (!ctx.allowDownward()) {
            return null;
        }
        int ty = y - 1;
        if (!ctx.box().contains(x, ty, z)) {
            return null;
        }
        int below = t.props(x, y - 2, z);
        if (anyUnloaded(r, below)) {
            return null;
        }
        if (!floor(below) || liquid(below) || has(below, HAZARD)) {
            return null;
        }
        // O.y+1 stays passable. In a shaft this search dug itself (origin body solid in the unmutated
        // terrain, so an earlier DOWNWARD broke it) the head cell was broken by that move as well.
        if (!passable(t.props(x, y + 1, z)) && passable(t.props(x, y, z))) {
            return null;
        }
        if (!floor(t.props(x, ty, z))) {
            return null;
        }
        Breaks breaks = new Breaks();
        double c = breakCost(ctx, t, x, ty, z, r);
        if (c >= COST_INF) {
            return null;
        }
        breaks.add(x, ty, z, c);
        if (breaks.gravityRefill(t)) {
            return null;
        }
        return finish(ctx, MoveKind.DOWNWARD, x, y, z, x, ty, z, breaks, PlannedMove.NONE, fallN(1), 0,
                scaffoldLeft, 0);
    }

    private static PlannedMove finish(SearchContext ctx, MoveKind kind, int x, int y, int z, int tx, int ty, int tz,
                                      Breaks breaks, long[] toPlace, double base, double wadeTicks,
                                      int scaffoldAfter, int swimAfter) {
        long toKey = CellPos.pack(tx, ty, tz);
        if (x == ctx.startX() && y == ctx.startY() && z == ctx.startZ() && !ctx.forbiddenFirstHops().isEmpty()
                && ctx.forbiddenFirstHops().contains(toKey)) {
            return null;
        }
        double hazard = Math.max(0, ctx.hazardPenaltyTicks().applyAsDouble(toKey));
        double bonus = Math.max(0, ctx.anchorBonusTicks().applyAsDouble(toKey));
        bonus = Math.min(bonus, hazard + wadeTicks);
        double breakTicks = breaks == null ? 0 : breaks.ticks;
        double ticks = base + breakTicks + wadeTicks + hazard - bonus;
        if (!(ticks < COST_INF)) {
            return null;
        }
        return new PlannedMove(kind, x, y, z, tx, ty, tz, breaks == null ? PlannedMove.NONE : breaks.toArray(),
                toPlace, ticks, scaffoldAfter, swimAfter);
    }
}
