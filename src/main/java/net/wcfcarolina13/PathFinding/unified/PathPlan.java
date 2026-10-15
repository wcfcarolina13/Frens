package net.wcfcarolina13.PathFinding.unified;

import java.util.List;

/**
 * The finder's answer. {@code moves} is empty on NO_PATH, and also on a FULL plan whose start is the
 * goal: callers check {@link #isEmpty()} before {@link #endX()}.
 *
 * @param partialCoefficient the best-so-far coefficient a PARTIAL plan came from, 0 otherwise
 * @param reason             NONE on FULL; why the search stopped short on PARTIAL and NO_PATH
 */
public record PathPlan(long planId, PlanStatus status, NoPathReason reason, double partialCoefficient,
                       List<PlannedMove> moves, double totalTicks, int nodesExpanded, long elapsedMs) {

    public PathPlan {
        moves = moves == null ? List.of() : List.copyOf(moves);
    }

    public static PathPlan noPath(long planId, NoPathReason reason, int nodes, long ms) {
        return new PathPlan(planId, PlanStatus.NO_PATH, reason, 0, List.of(), ActionCosts.COST_INF, nodes, ms);
    }

    public boolean isEmpty() {
        return moves.isEmpty();
    }

    private PlannedMove last() {
        if (moves.isEmpty()) {
            throw new IllegalStateException("empty plan has no end cell; use the search start");
        }
        return moves.get(moves.size() - 1);
    }

    public int endX() {
        return last().toX();
    }

    public int endY() {
        return last().toY();
    }

    public int endZ() {
        return last().toZ();
    }
}
