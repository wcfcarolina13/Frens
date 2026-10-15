package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.PathFinding.unified.CostVerifier;
import net.wcfcarolina13.PathFinding.unified.MoveKind;
import net.wcfcarolina13.PathFinding.unified.PlannedMove;

/**
 * Pure decisions for {@code MovementService.executePlannedMove} (1.1.242 slice C, design section 7):
 * per-kind budgets, the origin check, the mutation gate, arrival rules for FALL and SWIM, the outcome
 * of a mined-but-not-stepped move, and the mapping from a {@link CostVerifier.Verdict} to a leg
 * cancel. No {@code net.minecraft} here, so all of it runs under JUnit.
 */
public final class PlannedMoveExecPolicy {

    /** Server tick length; the executor's worker loops sleep one tick per input. */
    public static final long TICK_MS = 50L;

    /** Step-up nudge after ASCEND mining or an ASCEND_PLACE stair (tryTerrainMoveToward's BREAK/PLACE numbers). */
    public static final double STEP_UP_REACH_SQ = 2.25D;
    public static final long STEP_UP_TIMEOUT_MS = 1400L;
    public static final double STEP_UP_IMPULSE = 0.18D;

    /** FALL/DESCEND: walking toward the edge until airborne. */
    public static final int WALK_OFF_TICKS = 30;
    /** FALL: wait for ground after leaving the edge (design: 60 ticks). */
    public static final int FALL_LAND_TICKS = 60;

    /** SWIM: budget per swim cell (design: 100 ticks) and the wall rule (no block change in 30 ticks). */
    public static final int SWIM_CELL_TICKS = 100;
    public static final int SWIM_STALL_TICKS = 30;

    public static final String REASON_OK = "ok";
    public static final String REASON_CANCEL_PREFIX = "cost verification ";

    private PlannedMoveExecPolicy() {
    }

    /** Moves that mine or place need the obstruction-mine gates on top of the per-cell territory checks. */
    public static boolean needsMutation(PlannedMove move) {
        return move != null && (move.toBreak().length > 0 || move.toPlace().length > 0
                || move.kind() == MoveKind.ASCEND_PLACE || move.kind() == MoveKind.DOWNWARD);
    }

    /**
     * Same gate as {@code tryTerrainMoveToward}'s {@code mutationOk}: obstruction mining not opted out by
     * the caller, a skill task running, and no construction protection active.
     */
    public static boolean mutationAllowed(boolean obstructionMineDisabled, boolean executingTask,
                                          boolean constructionProtectionActive) {
        return !obstructionMineDisabled && executingTask && !constructionProtectionActive;
    }

    /**
     * The bot must start from the move's origin: every toBreak/toPlace cell is addressed relative to it.
     * On land the cell must match exactly (the caller already lifts carpet/slab floors to the feet cell).
     * In water the bot floats, so the column must match and the y may be off by one.
     */
    public static boolean atOrigin(int dx, int dy, int dz, boolean inWater) {
        if (dx != 0 || dz != 0) {
            return false;
        }
        return inWater ? Math.abs(dy) <= 1 : dy == 0;
    }

    /** Wall-clock budget of a whole move, for logging and for callers that wrap it. */
    public static long budgetMs(MoveKind kind, int breakCells) {
        long mine = breakCells * 6_200L;   // tryMinePlannedStepTrim: 6 s future cap + settle
        return switch (kind) {
            case TRAVERSE, DIAGONAL, DESCEND -> mine + 1_600L;
            case ASCEND -> mine + STEP_UP_TIMEOUT_MS + 400L;
            case ASCEND_PLACE -> 4_000L + STEP_UP_TIMEOUT_MS;
            case FALL -> (WALK_OFF_TICKS + FALL_LAND_TICKS) * TICK_MS;
            case SWIM -> SWIM_CELL_TICKS * TICK_MS;
            case DOWNWARD -> 0L;
        };
    }

    /**
     * Outcome of a step move after its mining: ok only when the bot arrived (the next unit plans from
     * {@code to}). Mined but not stepped is a failure the leg loop replans from; the rise is open now.
     */
    public static String stepOutcome(boolean minedAll, boolean stepped) {
        if (!minedAll) {
            return "break-failed";
        }
        return stepped ? REASON_OK : "mined-not-stepped";
    }

    /**
     * FALL/DESCEND landing: on the planned cell, or beside it at the same y (momentum can carry the
     * bot one cell past the edge). Anything else (a deeper or shallower landing) is off plan.
     */
    public static boolean landedOk(int dx, int dy, int dz) {
        return dy == 0 && Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
    }

    /**
     * SWIM arrival. An exit move (to above from) needs the bot standing on {@code to}. A swim cell needs
     * the column and the bot's block y within one of the cell (it bobs at the surface).
     */
    public static boolean swimArrived(int dx, int dy, int dz, boolean exitMove, boolean onGround) {
        if (dx != 0 || dz != 0) {
            return false;
        }
        if (exitMove) {
            return dy == 0 && onGround;
        }
        return Math.abs(dy) <= 1;
    }

    /** Jump input on a swim tick: always on an exit (vanilla climbs out with a jump), else when the eyes are under. */
    public static boolean swimJump(boolean exitMove, int air, int maxAir, boolean submerged) {
        return exitMove || submerged || air < maxAir;
    }

    /** True when the swim loop must give up: the cell budget is spent, or the block has not changed for the stall window. */
    public static boolean swimGiveUp(int ticksElapsed, int ticksSinceBlockChange) {
        return ticksElapsed >= SWIM_CELL_TICKS || ticksSinceBlockChange >= SWIM_STALL_TICKS;
    }

    /** Leg cancel from the verifier: cancel on any forbidden, relanded or over-budget move in the lookahead. */
    public static boolean cancelLeg(CostVerifier.Verdict verdict) {
        return verdict != null && verdict.cancel();
    }

    /** Log/detail reason for a cancelled leg; {@link #REASON_OK} when the verdict proceeds. */
    public static String cancelReason(CostVerifier.Verdict verdict) {
        if (!cancelLeg(verdict)) {
            return REASON_OK;
        }
        return REASON_CANCEL_PREFIX + verdict.reason();
    }
}
