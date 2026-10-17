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

    /** walkTo's deadline floor for a fastReplan caller (the old fixed budget). */
    public static final long FAST_REPLAN_BASE_DEADLINE_MS = 5_000L;
    /** walkTo's deadline floor for every other caller (the old fixed budget). */
    public static final long BASE_DEADLINE_MS = 20_000L;
    /** A long trip earns this much time per straight-line block to its destination. */
    public static final long DEADLINE_MS_PER_BLOCK = 300L;
    /** No single walkTo call gets more than this, however far the destination. */
    public static final long MAX_DEADLINE_MS = 40_000L;

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

    /**
     * walkTo's time budget, set before planning (6ad-6al field bug D: a 96-block relocation died after
     * ~28 blocks on the fixed 5 s fastReplan deadline). The fixed base for short moves; a long trip
     * gets {@link #DEADLINE_MS_PER_BLOCK} per straight-line block up to {@link #MAX_DEADLINE_MS}, so
     * it grows past the base from ~17 blocks (fastReplan) or ~67 blocks (other callers). A negative
     * or NaN distance gets the base.
     */
    public static long walkDeadlineMs(boolean fastReplan, double distBlocks) {
        long base = fastReplan ? FAST_REPLAN_BASE_DEADLINE_MS : BASE_DEADLINE_MS;
        if (!(distBlocks > 0.0D)) {
            return base;
        }
        long scaled = Math.min(MAX_DEADLINE_MS, Math.round(Math.min(distBlocks, 1.0e9D) * DEADLINE_MS_PER_BLOCK));
        return Math.max(base, scaled);
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
     * Skip-forward (review C1): the unit to run next when the bot stands at {@code botCell}. The
     * current unit when the bot is on its origin; else the first later unit whose origin is the bot's
     * cell (a walk run that overshot, or an earlier unit already carried it there); else the current
     * unit, whose own origin check settles or fails.
     *
     * @param unitOrigins packed origin cell of every unit, in plan order
     */
    public static int skipForwardIndex(List<Long> unitOrigins, int current, long botCell) {
        if (unitOrigins == null || current < 0 || current >= unitOrigins.size()) {
            return current;
        }
        if (unitOrigins.get(current) == botCell) {
            return current;
        }
        for (int j = current + 1; j < unitOrigins.size(); j++) {
            if (unitOrigins.get(j) == botCell) {
                return j;
            }
        }
        return current;
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
