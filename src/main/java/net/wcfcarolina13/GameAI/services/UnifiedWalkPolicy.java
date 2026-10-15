package net.wcfcarolina13.GameAI.services;

import java.util.List;
import net.wcfcarolina13.PathFinding.unified.NoPathReason;
import net.wcfcarolina13.PathFinding.unified.PlanStatus;
import net.wcfcarolina13.PathFinding.unified.PlanUnits;

/**
 * Pure decisions for {@code MovementService.walkTo} when the plan came from the unified finder
 * (1.1.242 slice C, design sections 5.4 and 7): what a NO_PATH reason means, how many failure replans a
 * walk gets, when a PARTIAL plan earns another leg, and where a failed walk run's first hop is.
 */
public final class UnifiedWalkPolicy {

    /** walkTo's legacy loop has two attempts, so one replan after a failed or cancelled unit. */
    public static final int MAX_FAILURE_REPLANS = 1;

    public enum NoPathStep { TERRAIN_MOVE, FAIL }

    public enum AfterPlan { ARRIVED, NEXT_LEG, STOP }

    private UnifiedWalkPolicy() {
    }

    /**
     * NO_PATH: the boxed-in terrain move survives only for a request without breaks (swim and falls
     * were already searched, so only the terrain move's walk-around or BFS escape is left; its own
     * mutation gate keeps BREAK/PLACE off, the same gate that kept them out of the request), once per
     * walk. Every other reason is final for this walk and goes back to the caller in the detail.
     */
    public static NoPathStep onNoPath(NoPathReason reason, boolean requestAllowsBreak, boolean boxedInTried) {
        if (reason == NoPathReason.BOXED_IN && !requestAllowsBreak && !boxedInTried) {
            return NoPathStep.TERRAIN_MOVE;
        }
        return NoPathStep.FAIL;
    }

    public static boolean replanAfterFailure(int failureReplansUsed) {
        return failureReplansUsed < MAX_FAILURE_REPLANS;
    }

    /**
     * The legacy step-up pre-trim runs only when the plan could not include breaks itself; a mutating
     * plan already lists every cell it mines, and an extra trim would mine off-plan.
     */
    public static boolean preclearStepUps(boolean requestAllowsBreak) {
        return !requestAllowsBreak;
    }

    /**
     * After every unit of a plan ran: arrived ends the walk; a FULL plan that did not arrive stops; a
     * PARTIAL plan plans another leg while {@link NavLegPolicy} sees progress and the deadline holds.
     */
    public static AfterPlan afterPlan(PlanStatus status, boolean arrived, int legsDone, double progressBlocks,
                                      boolean aborted, boolean deadlinePassed) {
        if (arrived) {
            return AfterPlan.ARRIVED;
        }
        if (status != PlanStatus.PARTIAL || deadlinePassed) {
            return AfterPlan.STOP;
        }
        return NavLegPolicy.continueLegs(legsDone, progressBlocks, false, aborted) ? AfterPlan.NEXT_LEG : AfterPlan.STOP;
    }

    /**
     * Index of the node the bot stands on in a walk run, so a failed run's next node is the first hop
     * to forbid on the replan; -1 when the bot is on none of them or on the last.
     */
    public static int failedHopOrigin(List<PlanUnits.PlanNode> nodes, int x, int y, int z) {
        if (nodes == null) {
            return -1;
        }
        for (int i = nodes.size() - 2; i >= 0; i--) {
            PlanUnits.PlanNode n = nodes.get(i);
            if (n.x() == x && n.y() == y && n.z() == z) {
                return i;
            }
        }
        return -1;
    }
}
