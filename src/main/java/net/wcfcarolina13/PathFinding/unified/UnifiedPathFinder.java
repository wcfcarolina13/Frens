package net.wcfcarolina13.PathFinding.unified;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The 1.1.242 unified finder (design section 5): A* over {@link MoveCosts} in the Baritone
 * {@code calculate0} shape. Binary heap with decrease-key, nodes keyed relative to the
 * {@link SearchBox}, best-so-far partials per coefficient, NO_PATH with a reason.
 *
 * <p>Pure: reads only the {@link TerrainView} in the context. Runs on the calling thread with that
 * thread's {@link SearchBuffers}.</p>
 */
public final class UnifiedPathFinder {
    public static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};
    public static final double MIN_IMPROVEMENT = 0.01;
    /** A partial must end more than this many blocks (Euclidean) from the start. */
    public static final int MIN_DIST_PATH = 5;
    public static final int TIME_CHECK_INTERVAL = 64;
    /** Partials longer than this lose their last 10% (Baritone pathCutoffMinimumLength / pathCutoffFactor). */
    public static final int PARTIAL_CUTOFF_MIN_MOVES = 30;
    public static final double PARTIAL_CUTOFF_FACTOR = 0.9;
    /** An unstandable goal snaps to the nearest standable cell within this Chebyshev radius. */
    public static final int GOAL_SNAP_RADIUS = 2;

    /** Cardinal order: north, east, south, west. Fixed so results are deterministic. */
    private static final int[][] CARDINALS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final int[][] DIAGONALS = {{1, -1}, {1, 1}, {-1, 1}, {-1, -1}};

    /**
     * Key bit for a node whose path breaks or places anything ({@link SearchBox#key} leaves bit 63 free).
     * A cell reached both by a pure walk and by a path that changed the terrain is two nodes, so a pure
     * walk (never refused: it has no mutator chain) is never closed by a cheaper mutated path whose later
     * moves its own breaks undo. At most twice the states, none extra on terrain nothing is mined in.
     */
    static final long MUTATED_LAYER = Long.MIN_VALUE;

    private UnifiedPathFinder() {
    }

    public static PathPlan search(SearchContext ctx) {
        return search(ctx, System::currentTimeMillis);
    }

    /** As {@link #search(SearchContext)} with an injectable clock (tests freeze it for determinism). */
    public static PathPlan search(SearchContext ctx, LongSupplier clock) {
        SearchBuffers shared = SearchBuffers.get();
        SearchBuffers buffers = shared.inUse ? new SearchBuffers() : shared;
        buffers.inUse = true;
        try {
            buffers.clear();
            return run(ctx, clock, buffers);
        } finally {
            buffers.clear();
            buffers.inUse = false;
        }
    }

    /**
     * Whether a cell can be a goal: standable (a floor, wading allowed). Never a swim cell, even when
     * swimming is allowed: a FULL plan ends where the bot can stand, as a PARTIAL does
     * ({@link #trimPartial}); a goal in deep water snaps to the shore (design section 5.1).
     */
    public static boolean isGoalCell(SearchContext ctx, int x, int y, int z) {
        return ctx.box().contains(x, y, z) && MoveCosts.isStanding(ctx.terrain(), x, y, z);
    }

    /**
     * The goal the search aims at: the goal itself when standable, else the nearest standable cell
     * within {@link #GOAL_SNAP_RADIUS} (squared distance, then scan order), else null. An unloaded goal
     * is returned as is: the search heads toward it for a partial. On null, {@link #search} still heads
     * toward the raw goal for a partial, as the Baritone-style finder does (it has no goal check).
     */
    public static int[] snapGoal(SearchContext ctx) {
        int gx = ctx.goalX();
        int gy = ctx.goalY();
        int gz = ctx.goalZ();
        if (!CellProps.loaded(ctx.terrain().props(gx, gy, gz)) || isGoalCell(ctx, gx, gy, gz)) {
            return new int[]{gx, gy, gz};
        }
        int[] best = null;
        int bestD2 = Integer.MAX_VALUE;
        int r = GOAL_SNAP_RADIUS;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 >= bestD2) {
                        continue;
                    }
                    if (isGoalCell(ctx, gx + dx, gy + dy, gz + dz)) {
                        best = new int[]{gx + dx, gy + dy, gz + dz};
                        bestD2 = d2;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Every candidate move out of (x,y,z): per cardinal TRAVERSE, ASCEND, ASCEND_PLACE, DESCEND, FALL,
     * SWIM; per diagonal DIAGONAL; then DOWNWARD. A swim cell only offers SWIM. Shared with the
     * Dijkstra reference so both search the same graph.
     */
    public static void expand(SearchContext ctx, TerrainView t, int x, int y, int z, int scaffoldLeft, int swimRun,
                              MoveCosts.Refusals r, List<PlannedMove> out) {
        boolean swimming = MoveCosts.isSwimCell(t, x, y, z);
        for (int[] d : CARDINALS) {
            if (!swimming) {
                add(out, MoveCosts.evaluate(ctx, t, x, y, z, MoveKind.TRAVERSE, d[0], d[1], scaffoldLeft, swimRun, r));
                add(out, MoveCosts.evaluate(ctx, t, x, y, z, MoveKind.ASCEND, d[0], d[1], scaffoldLeft, swimRun, r));
                add(out, MoveCosts.evaluate(ctx, t, x, y, z, MoveKind.ASCEND_PLACE, d[0], d[1], scaffoldLeft, swimRun, r));
                add(out, MoveCosts.evaluate(ctx, t, x, y, z, MoveKind.DESCEND, d[0], d[1], scaffoldLeft, swimRun, r));
                add(out, MoveCosts.evaluate(ctx, t, x, y, z, MoveKind.FALL, d[0], d[1], scaffoldLeft, swimRun, r));
            }
            add(out, MoveCosts.evaluate(ctx, t, x, y, z, MoveKind.SWIM, d[0], d[1], scaffoldLeft, swimRun, r));
        }
        if (!swimming) {
            for (int[] d : DIAGONALS) {
                add(out, MoveCosts.evaluate(ctx, t, x, y, z, MoveKind.DIAGONAL, d[0], d[1], scaffoldLeft, swimRun, r));
            }
            add(out, MoveCosts.evaluate(ctx, t, x, y, z, MoveKind.DOWNWARD, 0, 0, scaffoldLeft, swimRun, r));
        }
    }

    private static void add(List<PlannedMove> out, PlannedMove move) {
        if (move != null) {
            out.add(move);
        }
    }

    private static PathPlan run(SearchContext ctx, LongSupplier clock, SearchBuffers buf) {
        long t0 = clock.getAsLong();
        TerrainView t = ctx.terrain();
        SearchBox box = ctx.box();
        int sx = ctx.startX();
        int sy = ctx.startY();
        int sz = ctx.startZ();
        if (!box.contains(sx, sy, sz) || !CellProps.loaded(t.props(sx, sy, sz))) {
            return PathPlan.noPath(ctx.planId(), NoPathReason.UNLOADED, 0, clock.getAsLong() - t0);
        }
        boolean goalLoaded = CellProps.loaded(t.props(ctx.goalX(), ctx.goalY(), ctx.goalZ()));
        int[] goal = snapGoal(ctx);
        // An unstandable goal with nothing standable within 2 can never be popped (no move lands on
        // it), so the search runs out and returns the best-so-far partial toward it, reason
        // GOAL_NOT_STANDABLE (1.1.242 field: Baritone-style returns a partial there, unified refused).
        boolean goalStandable = goal != null;
        if (goal == null) {
            goal = new int[]{ctx.goalX(), ctx.goalY(), ctx.goalZ()};
        }
        int gx = goal[0];
        int gy = goal[1];
        int gz = goal[2];
        boolean goalInBox = box.contains(gx, gy, gz);

        SearchNode start = new SearchNode(sx, sy, sz, 0, box.key(sx, sy, sz, 0));
        start.scaffoldLeft = ctx.scaffoldCount();
        start.g = 0;
        start.h = MoveCosts.heuristic(ctx, sx, sy, sz, gx, gy, gz);
        start.f = start.h;
        if (sx == gx && sy == gy && sz == gz) {
            return new PathPlan(ctx.planId(), PlanStatus.FULL, NoPathReason.NONE, 0, List.of(), 0, 0,
                    clock.getAsLong() - t0);
        }
        buf.nodes.put(start.packedKey, start);
        buf.open.insert(start);

        SearchNode[] best = new SearchNode[COEFFICIENTS.length];
        double[] bestScore = new double[COEFFICIENTS.length];
        java.util.Arrays.fill(bestScore, Double.MAX_VALUE);
        boolean failing = true;
        boolean budgetHit = false;
        int expanded = 0;
        // Successors refused because an earlier move of their own path undoes them: feeds SELF_CONFLICT.
        int selfRefused = 0;
        MoveCosts.Refusals refusals = buf.refusals;
        List<PlannedMove> succ = buf.successors;
        // The terrain as the popped node's own path left it, rebuilt per expansion from its mutator chain.
        MoveSimulator.Overlay changed = new MoveSimulator.Overlay(t);
        List<PlannedMove> chain = new ArrayList<>();

        while (!buf.open.isEmpty()) {
            if (expanded > 0 && (expanded & (TIME_CHECK_INTERVAL - 1)) == 0) {
                long elapsed = clock.getAsLong() - t0;
                boolean stop = ctx.failureMs() > 0
                        ? elapsed >= ctx.failureMs() || (!failing && elapsed >= ctx.primaryMs())
                        : elapsed >= ctx.primaryMs();
                if (stop) {
                    budgetHit = true;
                    break;
                }
            }
            if (expanded >= ctx.nodeCap()) {
                budgetHit = true;
                break;
            }
            SearchNode cur = buf.open.removeLowest();
            expanded++;
            if (cur.x == gx && cur.y == gy && cur.z == gz) {
                List<PlannedMove> moves = reconstruct(cur);
                int over = firstUnaffordablePlacement(moves, ctx.scaffoldCount());
                if (over >= 0) {
                    return scaffoldTruncated(ctx, moves, over, expanded, clock.getAsLong() - t0);
                }
                return new PathPlan(ctx.planId(), PlanStatus.FULL, NoPathReason.NONE, 0, moves, cur.g, expanded,
                        clock.getAsLong() - t0);
            }
            succ.clear();
            expand(ctx, t, cur.x, cur.y, cur.z, cur.scaffoldLeft, cur.swimRun, refusals, succ);
            boolean mutated = cur.mutator != null;
            if (mutated) {
                fillFromChain(changed, cur.mutator, chain);
            }
            for (int i = 0; i < succ.size(); i++) {
                PlannedMove mv = succ.get(i);
                double c = mv.ticks();
                if (c >= ActionCosts.COST_INF) {
                    continue;
                }
                if (mutated && undoneByOwnPath(ctx, changed, cur, mv)) {
                    selfRefused++;
                    continue;
                }
                if (!(c > 0)) {
                    c = MoveCosts.minHorizontal(ctx);
                }
                int swim = mv.swimRunAfter();
                boolean mutates = mutates(mv);
                long key = box.key(mv.toX(), mv.toY(), mv.toZ(), swim);
                if (mutated || mutates) {
                    key |= MUTATED_LAYER;
                }
                double ng = cur.g + c;
                SearchNode n = buf.nodes.get(key);
                if (n == null) {
                    n = new SearchNode(mv.toX(), mv.toY(), mv.toZ(), swim, key);
                    n.h = MoveCosts.heuristic(ctx, n.x, n.y, n.z, gx, gy, gz);
                    n.g = ng;
                    n.f = ng + n.h;
                    n.parent = cur;
                    n.move = mv;
                    n.mutator = mutates ? n : cur.mutator;
                    n.scaffoldLeft = mv.scaffoldLeftAfter();
                    buf.nodes.put(key, n);
                    buf.open.insert(n);
                } else if (ng < n.g - MIN_IMPROVEMENT) {
                    n.g = ng;
                    n.f = ng + n.h;
                    n.parent = cur;
                    n.move = mv;
                    n.mutator = mutates ? n : cur.mutator;
                    n.scaffoldLeft = mv.scaffoldLeftAfter();
                    if (n.isOpen()) {
                        buf.open.decreaseKey(n);
                    } else {
                        buf.open.insert(n);
                    }
                } else {
                    continue;
                }
                for (int k = 0; k < COEFFICIENTS.length; k++) {
                    double score = n.h + n.g / COEFFICIENTS[k];
                    if (score < bestScore[k] - MIN_IMPROVEMENT) {
                        bestScore[k] = score;
                        best[k] = n;
                        if (failing && distSq(n, sx, sy, sz) > MIN_DIST_PATH * MIN_DIST_PATH) {
                            failing = false;
                        }
                    }
                }
            }
        }

        NoPathReason reason = goalStandable
                ? reason(ctx, refusals, expanded, budgetHit, goalLoaded, goalInBox)
                : NoPathReason.GOAL_NOT_STANDABLE;
        for (int k = 0; k < COEFFICIENTS.length; k++) {
            SearchNode b = best[k];
            if (b == null || distSq(b, sx, sy, sz) <= MIN_DIST_PATH * MIN_DIST_PATH) {
                continue;
            }
            List<PlannedMove> moves = trimPartial(reconstruct(b));
            if (moves.isEmpty()) {
                break;
            }
            int over = firstUnaffordablePlacement(moves, ctx.scaffoldCount());
            if (over >= 0) {
                return scaffoldTruncated(ctx, moves, over, expanded, clock.getAsLong() - t0);
            }
            double ticks = 0;
            for (PlannedMove m : moves) {
                ticks += m.ticks();
            }
            return new PathPlan(ctx.planId(), PlanStatus.PARTIAL, reason, COEFFICIENTS[k], moves, ticks, expanded,
                    clock.getAsLong() - t0);
        }
        return PathPlan.noPath(ctx.planId(), selfConflictOr(reason, selfRefused), expanded,
                clock.getAsLong() - t0);
    }

    /**
     * The NO_PATH reason once the search's own refusals count: SELF_CONFLICT when it refused at least
     * one successor that an earlier move of its path undoes and {@code reason} is only the generic
     * exhausted-region family (BOXED_IN, BLOCKED_UNBREAKABLE, NO_SCAFFOLD). BUDGET, UNLOADED,
     * BLOCKED_PROTECTED and GOAL_NOT_STANDABLE are more specific and stay.
     */
    static NoPathReason selfConflictOr(NoPathReason reason, int selfRefused) {
        if (selfRefused <= 0) {
            return reason;
        }
        return switch (reason) {
            case BOXED_IN, BLOCKED_UNBREAKABLE, NO_SCAFFOLD -> NoPathReason.SELF_CONFLICT;
            default -> reason;
        };
    }

    private static boolean mutates(PlannedMove mv) {
        return mv.toBreak().length > 0 || mv.toPlace().length > 0;
    }

    /**
     * Resets {@code overlay} and applies, oldest first, the breaks and places of every move on the
     * mutator chain from {@code newest}: the terrain as the path to the popped node left it. Walks the
     * mutating ancestors only, so a pure walk costs nothing.
     */
    private static void fillFromChain(MoveSimulator.Overlay overlay, SearchNode newest, List<PlannedMove> chain) {
        overlay.clear();
        chain.clear();
        for (SearchNode m = newest; m != null; m = m.parent == null ? null : m.parent.mutator) {
            chain.add(m.move);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            PlannedMove mv = chain.get(i);
            for (long cell : mv.toBreak()) {
                overlay.removeBlock(CellPos.x(cell), CellPos.y(cell), CellPos.z(cell));
            }
            for (long cell : mv.toPlace()) {
                overlay.placeBlock(CellPos.x(cell), CellPos.y(cell), CellPos.z(cell));
            }
        }
    }

    /**
     * Whether an earlier move on the popped node's path undoes {@code mv} (6ad-6al field bug F): on the
     * terrain those moves leave it is refused, or it lands elsewhere, the {@link CostVerifier} criteria
     * {@link #truncateToExecutable} cuts on. Refuse only: a move that passes keeps its raw price, so the
     * graph stays the one {@link #expand} prices (and the Dijkstra reference searches).
     */
    private static boolean undoneByOwnPath(SearchContext ctx, MoveSimulator.Overlay changed, SearchNode cur,
                                           PlannedMove mv) {
        PlannedMove now = MoveCosts.evaluate(ctx, changed, cur.x, cur.y, cur.z, mv.kind(), mv.dx(), mv.dz(),
                cur.scaffoldLeft, cur.swimRun);
        return now == null || now.ticks() >= ActionCosts.COST_INF
                || now.toX() != mv.toX() || now.toY() != mv.toY() || now.toZ() != mv.toZ();
    }

    /**
     * NO_PATH / PARTIAL reason. BUDGET when the search was cut short; UNLOADED when the goal is in an
     * unloaded cell and an unloaded read was refused; BLOCKED_PROTECTED when protection was the most
     * frequent refusal (even if only the start expanded, since that is the actionable cause);
     * BOXED_IN when only the start expanded; then BLOCKED_UNBREAKABLE, NO_SCAFFOLD; and BOXED_IN for a
     * closed region nothing else explains.
     */
    static NoPathReason reason(SearchContext ctx, MoveCosts.Refusals r, int expanded, boolean budgetHit,
                               boolean goalLoaded, boolean goalInBox) {
        if (budgetHit) {
            return NoPathReason.BUDGET;
        }
        if (!goalLoaded && r.unloaded > 0) {
            return NoPathReason.UNLOADED;
        }
        if (r.protectedCells > 0 && r.protectedCells >= r.unbreakable && r.protectedCells >= r.noScaffold && goalInBox) {
            return NoPathReason.BLOCKED_PROTECTED;
        }
        if (expanded <= 1) {
            return NoPathReason.BOXED_IN;
        }
        if (r.unbreakable > 0 && r.unbreakable >= r.noScaffold) {
            return NoPathReason.BLOCKED_UNBREAKABLE;
        }
        if (r.noScaffold > 0 && ctx.scaffoldCount() == 0) {
            return NoPathReason.NO_SCAFFOLD;
        }
        return NoPathReason.BOXED_IN;
    }

    /**
     * Index of the first move whose placements the bot cannot afford, counting placements from
     * {@code scaffoldCount} along the plan, or -1. The search's {@code scaffoldLeftAfter} labels are not
     * trusted here: the scaffold count is not in the node key (review I4), so a relaxed parent can leave a
     * descendant priced under a larger count than the chain really has.
     */
    static int firstUnaffordablePlacement(List<PlannedMove> moves, int scaffoldCount) {
        int left = scaffoldCount;
        for (int i = 0; i < moves.size(); i++) {
            int n = moves.get(i).toPlace().length;
            if (n == 0) {
                continue;
            }
            if (n > left) {
                return i;
            }
            left -= n;
        }
        return -1;
    }

    /**
     * A plan cut before move {@code over} (the first unaffordable placement): PARTIAL with reason
     * NO_SCAFFOLD when the cut, after {@link #trimPartial}, still ends past {@link #MIN_DIST_PATH},
     * else NO_PATH(NO_SCAFFOLD). The cut keeps whole moves and never ends on a swim cell or an action.
     */
    static PathPlan scaffoldTruncated(SearchContext ctx, List<PlannedMove> moves, int over, int expanded,
                                      long elapsedMs) {
        List<PlannedMove> cut = trimPartial(moves.subList(0, over));
        if (!cut.isEmpty()) {
            PlannedMove last = cut.get(cut.size() - 1);
            long dx = last.toX() - ctx.startX();
            long dy = last.toY() - ctx.startY();
            long dz = last.toZ() - ctx.startZ();
            if (dx * dx + dy * dy + dz * dz > (long) MIN_DIST_PATH * MIN_DIST_PATH) {
                double ticks = 0;
                for (PlannedMove m : cut) {
                    ticks += m.ticks();
                }
                return new PathPlan(ctx.planId(), PlanStatus.PARTIAL, NoPathReason.NO_SCAFFOLD, 0, cut, ticks,
                        expanded, elapsedMs);
            }
        }
        return PathPlan.noPath(ctx.planId(), NoPathReason.NO_SCAFFOLD, expanded, elapsedMs);
    }

    /**
     * What {@link #truncateToExecutable} did: the plan to run, and the move it was cut before with the
     * verifier's reason ({@code cutAt} -1 and {@code reason} null when the plan was left alone).
     */
    public record Truncation(PathPlan plan, int cutAt, String reason) {
        public boolean truncated() {
            return cutAt >= 0;
        }
    }

    /**
     * Cuts a plan before the first move an earlier move of the same plan undoes (6ad-6al field bug F).
     * The search prices every move on the unmutated terrain, so it could mine a cell with one move and
     * step onto it as solid with a later one (TRAVERSE mines C, TRAVERSE on, ASCEND back onto C);
     * {@link CostVerifier} applies the earlier moves' breaks and places to an overlay and cancels that
     * move as forbidden. Since 1.1.267 the search itself refuses such a move against the popped node's
     * mutator chain and routes around it; this stays as the backstop for what that check cannot see
     * (a node re-parented after its descendants were expanded keeps their stale chains).
     *
     * <p>This is the verifier's own verdict at plan time: {@link CostVerifier#check} from move 0 over the
     * whole plan on an overlay of the planning terrain, with no cost-rise limit, so only a move that is
     * now refused or lands elsewhere cuts. Not {@link MoveSimulator#simulate}: its exact re-evaluation
     * also fails a move whose planned break an earlier move already made, which the executor skips
     * ({@code minePlannedCells}), and cutting those executable plans short would stop walks that work.</p>
     *
     * <p>A cut at move k &gt; 0 keeps moves [0, k) through {@link #trimPartial} as a PARTIAL with reason
     * {@link NoPathReason#SELF_CONFLICT}, so the bot walks up to the conflict and the next leg plans on
     * the real terrain. No minimum distance: any real progress beats a leg the verifier cancels. A cut
     * at move 0, or a prefix that trims to nothing, is NO_PATH(SELF_CONFLICT). NO_PATH and empty plans
     * come back unchanged. Pure: reads only {@code ctx.terrain()} through the overlay.</p>
     */
    public static Truncation truncateToExecutable(SearchContext ctx, PathPlan plan) {
        if (plan.status() == PlanStatus.NO_PATH || plan.isEmpty()) {
            return new Truncation(plan, -1, null);
        }
        List<PlannedMove> moves = plan.moves();
        CostVerifier.Verdict v = CostVerifier.check(ctx, plan, 0, ctx.terrain(), moves.size(),
                Double.POSITIVE_INFINITY);
        if (!v.cancel()) {
            return new Truncation(plan, -1, null);
        }
        int k = v.moveIndex();
        List<PlannedMove> cut = k > 0 ? trimPartial(moves.subList(0, k)) : List.of();
        if (cut.isEmpty()) {
            return new Truncation(PathPlan.noPath(plan.planId(), NoPathReason.SELF_CONFLICT, plan.nodesExpanded(),
                    plan.elapsedMs()), k, v.reason());
        }
        double ticks = 0;
        for (PlannedMove m : cut) {
            ticks += m.ticks();
        }
        return new Truncation(new PathPlan(plan.planId(), PlanStatus.PARTIAL, NoPathReason.SELF_CONFLICT, 0, cut,
                ticks, plan.nodesExpanded(), plan.elapsedMs()), k, v.reason());
    }

    /** 10% tail cut beyond 30 moves; never end on a swim cell or right after a break/place action. */
    static List<PlannedMove> trimPartial(List<PlannedMove> moves) {
        List<PlannedMove> out = new ArrayList<>(moves);
        if (out.size() > PARTIAL_CUTOFF_MIN_MOVES) {
            int keep = (int) (out.size() * PARTIAL_CUTOFF_FACTOR);
            out.subList(keep, out.size()).clear();
        }
        while (!out.isEmpty()) {
            PlannedMove last = out.get(out.size() - 1);
            if (last.swimRunAfter() > 0 || last.toBreak().length > 0 || last.toPlace().length > 0) {
                out.remove(out.size() - 1);
            } else {
                break;
            }
        }
        return out;
    }

    private static List<PlannedMove> reconstruct(SearchNode end) {
        List<PlannedMove> moves = new ArrayList<>();
        for (SearchNode n = end; n.move != null; n = n.parent) {
            moves.add(n.move);
        }
        Collections.reverse(moves);
        return moves;
    }

    private static long distSq(SearchNode n, int x, int y, int z) {
        long dx = n.x - x;
        long dy = n.y - y;
        long dz = n.z - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
