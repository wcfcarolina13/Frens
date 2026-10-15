package net.wcfcarolina13.PathFinding.unified;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure half of the plan adapter (design section 6): plan cells with jump flags and the
 * {@link ExecUnit} split. {@code PlanAdapter} only converts these to {@code PathNode}/{@code Segment}.
 */
public final class PlanUnits {

    /**
     * One cell the bot stands on (or swims in) along a plan.
     *
     * @param jump whether the step INTO this cell needs a jump (the PathNode / Segment jump flag)
     * @param via  the move that reached this cell, null for the start cell
     */
    public record PlanNode(int x, int y, int z, boolean jump, MoveKind via) {
        public long packed() {
            return CellPos.pack(x, y, z);
        }
    }

    private PlanUnits() {
    }

    /**
     * Whether stepping along {@code m} needs a jump. ASCEND jumps unless its step (the cell in front
     * at the origin's height) is a bottom slab ({@link CellProps#SLAB_TOP}): slice A prices a slab step
     * as a full ascend and leaves the jump decision here. ASCEND_PLACE always jumps (it pillars), and so
     * does a swim exit one block up. Everything else walks or drops.
     */
    public static boolean needsJump(PlannedMove m, TerrainView t) {
        return switch (m.kind()) {
            case ASCEND -> !CellProps.has(t.props(m.toX(), m.fromY(), m.toZ()), CellProps.SLAB_TOP);
            case ASCEND_PLACE -> true;
            case SWIM -> m.toY() > m.fromY();
            case TRAVERSE, DIAGONAL, DESCEND, FALL, DOWNWARD -> false;
        };
    }

    /**
     * The start cell and every move's landing, in order. An empty plan gives just the start (FULL
     * with start == goal, like the old finders' one-node path) or nothing (NO_PATH).
     */
    public static List<PlanNode> nodes(PathPlan plan, int startX, int startY, int startZ, TerrainView t) {
        if (plan.status() == PlanStatus.NO_PATH) {
            return List.of();
        }
        List<PlannedMove> moves = plan.moves();
        List<PlanNode> out = new ArrayList<>(moves.size() + 1);
        if (moves.isEmpty()) {
            out.add(new PlanNode(startX, startY, startZ, false, null));
            return out;
        }
        PlannedMove first = moves.get(0);
        out.add(new PlanNode(first.fromX(), first.fromY(), first.fromZ(), false, null));
        for (PlannedMove m : moves) {
            out.add(new PlanNode(m.toX(), m.toY(), m.toZ(), needsJump(m, t), m.kind()));
        }
        return out;
    }

    /** The run's start cell and its moves' landings. */
    static List<PlanNode> runNodes(List<PlannedMove> moves, int first, int last, TerrainView t) {
        List<PlanNode> out = new ArrayList<>(last - first + 2);
        PlannedMove m0 = moves.get(first);
        out.add(new PlanNode(m0.fromX(), m0.fromY(), m0.fromZ(), false, null));
        for (int i = first; i <= last; i++) {
            PlannedMove m = moves.get(i);
            out.add(new PlanNode(m.toX(), m.toY(), m.toZ(), needsJump(m, t), m.kind()));
        }
        return out;
    }

    /** Splits a plan into walk runs and action units in plan order. Empty for an empty plan. */
    public static List<ExecUnit> units(PathPlan plan, TerrainView t) {
        List<PlannedMove> moves = plan.moves();
        List<ExecUnit> out = new ArrayList<>();
        int runStart = -1;
        for (int i = 0; i < moves.size(); i++) {
            PlannedMove m = moves.get(i);
            if (m.isAction()) {
                if (runStart >= 0) {
                    out.add(new ExecUnit.WalkRun(runStart, i - 1, runNodes(moves, runStart, i - 1, t)));
                    runStart = -1;
                }
                out.add(new ExecUnit.ActionUnit(m, i));
            } else if (runStart < 0) {
                runStart = i;
            }
        }
        if (runStart >= 0) {
            int last = moves.size() - 1;
            out.add(new ExecUnit.WalkRun(runStart, last, runNodes(moves, runStart, last, t)));
        }
        return out;
    }

    /** Whether every move of the plan is a plain walk, so today's List&lt;PathNode&gt; executor can walk it. */
    public static boolean walkOnly(PathPlan plan) {
        for (PlannedMove m : plan.moves()) {
            if (m.isAction()) {
                return false;
            }
        }
        return true;
    }
}
