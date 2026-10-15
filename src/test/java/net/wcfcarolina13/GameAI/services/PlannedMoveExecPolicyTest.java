package net.wcfcarolina13.GameAI.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.wcfcarolina13.PathFinding.unified.CellPos;
import net.wcfcarolina13.PathFinding.unified.CostVerifier;
import net.wcfcarolina13.PathFinding.unified.MoveKind;
import net.wcfcarolina13.PathFinding.unified.PlannedMove;
import org.junit.jupiter.api.Test;

class PlannedMoveExecPolicyTest {

    private static PlannedMove move(MoveKind kind, long[] toBreak, long[] toPlace) {
        return new PlannedMove(kind, 0, 64, 0, 1, 65, 0, toBreak, toPlace, 10.0, 0, 0);
    }

    @Test
    void mutationNeededOnlyForBreaksPlacesAndDig() {
        assertFalse(PlannedMoveExecPolicy.needsMutation(move(MoveKind.TRAVERSE, null, null)));
        assertFalse(PlannedMoveExecPolicy.needsMutation(move(MoveKind.SWIM, null, null)));
        assertFalse(PlannedMoveExecPolicy.needsMutation(move(MoveKind.FALL, null, null)));
        assertTrue(PlannedMoveExecPolicy.needsMutation(move(MoveKind.ASCEND, new long[] {CellPos.pack(0, 66, 0)}, null)));
        assertTrue(PlannedMoveExecPolicy.needsMutation(move(MoveKind.ASCEND_PLACE, null, new long[] {CellPos.pack(0, 64, 0)})));
        assertTrue(PlannedMoveExecPolicy.needsMutation(move(MoveKind.ASCEND_PLACE, null, null)));
        assertTrue(PlannedMoveExecPolicy.needsMutation(move(MoveKind.DOWNWARD, null, null)));
        assertFalse(PlannedMoveExecPolicy.needsMutation(null));
    }

    @Test
    void mutationGateMirrorsTerrainMove() {
        assertTrue(PlannedMoveExecPolicy.mutationAllowed(false, true, false));
        assertFalse(PlannedMoveExecPolicy.mutationAllowed(true, true, false));
        assertFalse(PlannedMoveExecPolicy.mutationAllowed(false, false, false));
        assertFalse(PlannedMoveExecPolicy.mutationAllowed(false, true, true));
    }

    @Test
    void originMustMatchOnLandAndColumnInWater() {
        assertTrue(PlannedMoveExecPolicy.atOrigin(0, 0, 0, false));
        assertFalse(PlannedMoveExecPolicy.atOrigin(0, 1, 0, false));
        assertFalse(PlannedMoveExecPolicy.atOrigin(1, 0, 0, false));
        assertTrue(PlannedMoveExecPolicy.atOrigin(0, -1, 0, true));
        assertTrue(PlannedMoveExecPolicy.atOrigin(0, 1, 0, true));
        assertFalse(PlannedMoveExecPolicy.atOrigin(0, 2, 0, true));
        assertFalse(PlannedMoveExecPolicy.atOrigin(0, 0, 1, true));
    }

    @Test
    void stepOutcomeRequiresArrival() {
        assertEquals(PlannedMoveExecPolicy.REASON_OK, PlannedMoveExecPolicy.stepOutcome(true, true));
        assertEquals("mined-not-stepped", PlannedMoveExecPolicy.stepOutcome(true, false));
        assertEquals("break-failed", PlannedMoveExecPolicy.stepOutcome(false, false));
        assertEquals("break-failed", PlannedMoveExecPolicy.stepOutcome(false, true));
    }

    @Test
    void fallLandsOnPlanOrOneCellPastAtSameY() {
        assertTrue(PlannedMoveExecPolicy.landedOk(0, 0, 0));
        assertTrue(PlannedMoveExecPolicy.landedOk(1, 0, -1));
        assertFalse(PlannedMoveExecPolicy.landedOk(2, 0, 0));
        assertFalse(PlannedMoveExecPolicy.landedOk(0, -1, 0));
        assertFalse(PlannedMoveExecPolicy.landedOk(0, 1, 0));
    }

    @Test
    void swimArrivalAndExit() {
        assertTrue(PlannedMoveExecPolicy.swimArrived(0, 0, 0, false, false));
        assertTrue(PlannedMoveExecPolicy.swimArrived(0, -1, 0, false, false));
        assertFalse(PlannedMoveExecPolicy.swimArrived(1, 0, 0, false, false));
        assertFalse(PlannedMoveExecPolicy.swimArrived(0, 2, 0, false, false));
        assertTrue(PlannedMoveExecPolicy.swimArrived(0, 0, 0, true, true));
        assertFalse(PlannedMoveExecPolicy.swimArrived(0, 0, 0, true, false));
        assertFalse(PlannedMoveExecPolicy.swimArrived(0, -1, 0, true, true));
    }

    @Test
    void swimJumpsOnExitOrWhenUnder() {
        assertTrue(PlannedMoveExecPolicy.swimJump(true, 300, 300, false));
        assertTrue(PlannedMoveExecPolicy.swimJump(false, 299, 300, false));
        assertTrue(PlannedMoveExecPolicy.swimJump(false, 300, 300, true));
        assertFalse(PlannedMoveExecPolicy.swimJump(false, 300, 300, false));
    }

    @Test
    void swimGivesUpOnBudgetOrStall() {
        assertFalse(PlannedMoveExecPolicy.swimGiveUp(0, 0));
        assertFalse(PlannedMoveExecPolicy.swimGiveUp(PlannedMoveExecPolicy.SWIM_CELL_TICKS - 1,
                PlannedMoveExecPolicy.SWIM_STALL_TICKS - 1));
        assertTrue(PlannedMoveExecPolicy.swimGiveUp(PlannedMoveExecPolicy.SWIM_CELL_TICKS, 0));
        assertTrue(PlannedMoveExecPolicy.swimGiveUp(10, PlannedMoveExecPolicy.SWIM_STALL_TICKS));
        assertEquals(100, PlannedMoveExecPolicy.SWIM_CELL_TICKS);
        assertEquals(30, PlannedMoveExecPolicy.SWIM_STALL_TICKS);
        assertEquals(60, PlannedMoveExecPolicy.FALL_LAND_TICKS);
    }

    @Test
    void budgetsPerKind() {
        for (MoveKind kind : MoveKind.values()) {
            assertTrue(PlannedMoveExecPolicy.budgetMs(kind, 0) >= 0L, kind.name());
        }
        assertEquals(0L, PlannedMoveExecPolicy.budgetMs(MoveKind.DOWNWARD, 0));
        assertEquals(5_000L, PlannedMoveExecPolicy.budgetMs(MoveKind.SWIM, 0));
        assertTrue(PlannedMoveExecPolicy.budgetMs(MoveKind.ASCEND, 3) > PlannedMoveExecPolicy.budgetMs(MoveKind.ASCEND, 0));
    }

    @Test
    void verifierVerdictMapsToLegCancel() {
        assertFalse(PlannedMoveExecPolicy.cancelLeg(CostVerifier.Verdict.OK));
        assertFalse(PlannedMoveExecPolicy.cancelLeg(null));
        assertEquals(PlannedMoveExecPolicy.REASON_OK, PlannedMoveExecPolicy.cancelReason(CostVerifier.Verdict.OK));
        CostVerifier.Verdict rise = new CostVerifier.Verdict(true, 2, "move 2 ASCEND cost +12.0 ticks", 10, 22);
        assertTrue(PlannedMoveExecPolicy.cancelLeg(rise));
        assertEquals("cost verification move 2 ASCEND cost +12.0 ticks", PlannedMoveExecPolicy.cancelReason(rise));
        CostVerifier.Verdict forbidden = new CostVerifier.Verdict(true, 0, "move 0 SWIM now forbidden", 9, 1_000_000);
        assertTrue(PlannedMoveExecPolicy.cancelLeg(forbidden));
    }

    @Test
    void walkRunEndingShortSettlesOntoOriginInsteadOfFailing() {
        // On the origin: run.
        assertEquals(PlannedMoveExecPolicy.OriginStep.AT_ORIGIN, PlannedMoveExecPolicy.originStep(true, 3.0, 3.0, 2));
        // walkSegment's close-enough: 1.5 blocks short on flat ground, and a diagonal neighbour cell.
        assertEquals(PlannedMoveExecPolicy.OriginStep.SETTLE, PlannedMoveExecPolicy.originStep(false, 1.5, 0.0, 0));
        assertEquals(PlannedMoveExecPolicy.OriginStep.SETTLE, PlannedMoveExecPolicy.originStep(false, -1.0, 1.0, 0));
        // One block up or down (a step before the action).
        assertEquals(PlannedMoveExecPolicy.OriginStep.SETTLE, PlannedMoveExecPolicy.originStep(false, 0.9, 0.2, -1));
        assertEquals(PlannedMoveExecPolicy.OriginStep.SETTLE, PlannedMoveExecPolicy.originStep(false, 0.0, 1.6, 1));
        // Too far or too far off in y: fail.
        assertEquals(PlannedMoveExecPolicy.OriginStep.FAIL, PlannedMoveExecPolicy.originStep(false, 1.7, 0.0, 0));
        assertEquals(PlannedMoveExecPolicy.OriginStep.FAIL, PlannedMoveExecPolicy.originStep(false, 1.2, 1.2, 0));
        assertEquals(PlannedMoveExecPolicy.OriginStep.FAIL, PlannedMoveExecPolicy.originStep(false, 0.3, 0.0, 2));
    }

    @Test
    void mutationTaskGateIsThisBotsRunningTicket() {
        assertTrue(PlannedMoveExecPolicy.botTaskAllowsMutation(true, "skill:woodcut", "RUNNING", false));
        assertTrue(PlannedMoveExecPolicy.botTaskAllowsMutation(true, "system:surface_recovery", "RUNNING", false));
        assertFalse(PlannedMoveExecPolicy.botTaskAllowsMutation(false, null, null, false));
        assertFalse(PlannedMoveExecPolicy.botTaskAllowsMutation(true, "skill:woodcut", "PAUSED", false));
        assertFalse(PlannedMoveExecPolicy.botTaskAllowsMutation(true, "skill:woodcut", "RUNNING", true));
        assertFalse(PlannedMoveExecPolicy.botTaskAllowsMutation(true, "skill:drop_sweep", "RUNNING", false));
        assertFalse(PlannedMoveExecPolicy.botTaskAllowsMutation(true, null, "RUNNING", false));
        assertFalse(PlannedMoveExecPolicy.botTaskAllowsMutation(true, "skill:sleep", "RUNNING", false));
        assertFalse(PlannedMoveExecPolicy.botTaskAllowsMutation(true, "skill:quick-store", "RUNNING", false));
        assertFalse(PlannedMoveExecPolicy.botTaskAllowsMutation(true, "skill:quick-fetch", "RUNNING", false));
        assertFalse(PlannedMoveExecPolicy.botTaskAllowsMutation(true, "skill:craft", "RUNNING", false));
        assertFalse(PlannedMoveExecPolicy.botTaskAllowsMutation(true, "SKILL:Sleep", "RUNNING", false));
        assertTrue(PlannedMoveExecPolicy.botTaskAllowsMutation(true, "skill:mining", "RUNNING", false));
    }

    @Test
    void consecutiveSwimCellsKeepMomentum() {
        assertFalse(PlannedMoveExecPolicy.stopAfterMove(MoveKind.SWIM, MoveKind.SWIM));
        assertTrue(PlannedMoveExecPolicy.stopAfterMove(MoveKind.SWIM, null));
        assertTrue(PlannedMoveExecPolicy.stopAfterMove(MoveKind.SWIM, MoveKind.FALL));
        assertTrue(PlannedMoveExecPolicy.stopAfterMove(MoveKind.FALL, MoveKind.SWIM));
        assertTrue(PlannedMoveExecPolicy.stopAfterMove(MoveKind.ASCEND, null));
    }

    @Test
    void deadlinePassedIsStrict() {
        assertFalse(PlannedMoveExecPolicy.deadlinePassed(1000L, 1000L));
        assertTrue(PlannedMoveExecPolicy.deadlinePassed(1001L, 1000L));
        assertFalse(PlannedMoveExecPolicy.deadlinePassed(5L, Long.MAX_VALUE));
    }
}
