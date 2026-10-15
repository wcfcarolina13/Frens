package net.wcfcarolina13.PathFinding.unified;

import java.util.List;
import java.util.Locale;

/**
 * Baritone-style cost verification before each execution unit (design section 7, ruling "Leg
 * cancel"): re-evaluates the next {@code lookahead} moves against a fresh terrain view and cancels the
 * leg when one is now forbidden, lands somewhere else, or costs more than {@code maxCostIncrease}
 * ticks over plan. Moves after the first are evaluated on an overlay carrying the earlier moves'
 * breaks and places, so a planned mine does not read as a wall for the step after it. Pure.
 */
public final class CostVerifier {
    public static final int DEFAULT_LOOKAHEAD = 5;
    public static final double DEFAULT_MAX_COST_INCREASE = 10;

    public record Verdict(boolean cancel, int moveIndex, String reason, double plannedTicks, double liveTicks) {
        public static final Verdict OK = new Verdict(false, -1, "ok", 0, 0);
    }

    private CostVerifier() {
    }

    /**
     * @param ctx       the plan's context (flags, budgets, start for the first-hop rule); its terrain is not read
     * @param live      a fresh view of the world now
     * @param fromIndex the next move to execute
     */
    public static Verdict check(SearchContext ctx, PathPlan plan, int fromIndex, TerrainView live, int lookahead,
                                double maxCostIncrease) {
        List<PlannedMove> moves = plan.moves();
        if (fromIndex < 0 || fromIndex >= moves.size()) {
            return Verdict.OK;
        }
        MoveSimulator.Overlay view = new MoveSimulator.Overlay(live);
        int end = Math.min(moves.size(), fromIndex + Math.max(1, lookahead));
        for (int i = fromIndex; i < end; i++) {
            PlannedMove m = moves.get(i);
            int scaffold = i == 0 ? ctx.scaffoldCount() : moves.get(i - 1).scaffoldLeftAfter();
            int swimRun = i == 0 ? 0 : moves.get(i - 1).swimRunAfter();
            PlannedMove now = MoveCosts.evaluate(ctx, view, m.fromX(), m.fromY(), m.fromZ(), m.kind(), m.dx(), m.dz(),
                    scaffold, swimRun);
            if (now == null || now.ticks() >= ActionCosts.COST_INF) {
                return new Verdict(true, i, "move " + i + " " + m.kind() + " now forbidden", m.ticks(), ActionCosts.COST_INF);
            }
            if (now.toX() != m.toX() || now.toY() != m.toY() || now.toZ() != m.toZ()) {
                return new Verdict(true, i, "move " + i + " " + m.kind() + " now lands at "
                        + now.toX() + "," + now.toY() + "," + now.toZ(), m.ticks(), now.ticks());
            }
            double increase = now.ticks() - m.ticks();
            if (increase > maxCostIncrease) {
                return new Verdict(true, i, String.format(Locale.ROOT, "move %d %s cost +%.1f ticks", i, m.kind(), increase),
                        m.ticks(), now.ticks());
            }
            for (long cell : now.toBreak()) {
                view.removeBlock(CellPos.x(cell), CellPos.y(cell), CellPos.z(cell));
            }
            for (long cell : now.toPlace()) {
                view.placeBlock(CellPos.x(cell), CellPos.y(cell), CellPos.z(cell));
            }
        }
        return Verdict.OK;
    }

    public static Verdict check(SearchContext ctx, PathPlan plan, int fromIndex, TerrainView live) {
        return check(ctx, plan, fromIndex, live, DEFAULT_LOOKAHEAD, DEFAULT_MAX_COST_INCREASE);
    }
}
