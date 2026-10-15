package net.wcfcarolina13.PathFinding.unified;

import java.util.List;

/**
 * The executor's unit list for one {@link PathPlan} (design section 6), built by {@link PlanUnits}.
 * Consecutive non-action moves (TRAVERSE, DIAGONAL, ASCEND and DESCEND without break or place)
 * form one {@link WalkRun}; every action move ({@link PlannedMove#isAction()}) is its own
 * {@link ActionUnit}. Units alternate in plan order and cover every move exactly once.
 *
 * <p>Pure (no {@code net.minecraft} types). A WalkRun carries its cells as {@link PlanUnits.PlanNode}s
 * with jump flags; {@code PlanAdapter.toSegments(WalkRun, sprint)} turns it into today's
 * {@code Queue<Segment>} so {@code walkSegment} walks it unchanged.</p>
 */
public sealed interface ExecUnit permits ExecUnit.WalkRun, ExecUnit.ActionUnit {

    /** Index in {@link PathPlan#moves()} of the first move this unit executes. */
    int firstMoveIndex();

    /** Index in {@link PathPlan#moves()} of the last move this unit executes (inclusive). */
    int lastMoveIndex();

    /**
     * Moves {@code firstMoveIndex..lastMoveIndex}, walked with {@code walkSegment}.
     *
     * @param nodes the run's start cell (the first move's origin) and each move's landing, in order
     */
    record WalkRun(int firstMoveIndex, int lastMoveIndex, List<PlanUnits.PlanNode> nodes) implements ExecUnit {
        public WalkRun {
            if (firstMoveIndex < 0 || lastMoveIndex < firstMoveIndex) {
                throw new IllegalArgumentException("bad move range " + firstMoveIndex + ".." + lastMoveIndex);
            }
            nodes = List.copyOf(nodes);
            if (nodes.size() != lastMoveIndex - firstMoveIndex + 2) {
                throw new IllegalArgumentException("a run of " + (lastMoveIndex - firstMoveIndex + 1)
                        + " moves needs " + (lastMoveIndex - firstMoveIndex + 2) + " nodes, got " + nodes.size());
            }
        }

        public int moveCount() {
            return lastMoveIndex - firstMoveIndex + 1;
        }
    }

    /** One action move (break, place, swim, fall, downward) with its index in the plan. */
    record ActionUnit(PlannedMove move, int moveIndex) implements ExecUnit {
        public ActionUnit {
            if (move == null || moveIndex < 0) {
                throw new IllegalArgumentException("action unit needs a move and an index");
            }
        }

        @Override
        public int firstMoveIndex() {
            return moveIndex;
        }

        @Override
        public int lastMoveIndex() {
            return moveIndex;
        }
    }
}
