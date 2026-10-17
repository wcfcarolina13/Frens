package net.wcfcarolina13.PathFinding.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CostVerifierTest {

    private static final BenchmarkScenarios.Case FIELD = BenchmarkScenarios.load("open_field");

    @Test
    void unchangedWorldPasses() {
        PathPlan plan = UnifiedPathFinder.search(FIELD.ctx());
        for (int i = 0; i < plan.moves().size(); i += 7) {
            assertFalse(CostVerifier.check(FIELD.ctx(), plan, i, FIELD.grid()).cancel());
        }
        assertFalse(CostVerifier.check(FIELD.ctx(), plan, plan.moves().size(), FIELD.grid()).cancel());
    }

    @Test
    void aWallInsideTheLookaheadCancelsAtThatMove() {
        PathPlan plan = UnifiedPathFinder.search(FIELD.ctx());
        VoxelGrid live = FIELD.grid().copy();
        live.fill(12, 1, 0, 12, 3, 59, '#');
        // moves 0..: 5->6 ... move 6 lands on x=12
        CostVerifier.Verdict v = CostVerifier.check(FIELD.ctx(), plan, 3, live);
        assertTrue(v.cancel());
        assertEquals(6, v.moveIndex());
        assertTrue(v.reason().contains("forbidden"), v.reason());
        // outside the 5-move lookahead it is not looked at yet
        assertFalse(CostVerifier.check(FIELD.ctx(), plan, 0, live).cancel());
    }

    @Test
    void costRiseOverTenTicksCancelsButSmallRiseDoesNot() {
        PathPlan plan = UnifiedPathFinder.search(FIELD.ctx());
        long cell = CellPos.pack(9, 1, 30);
        SearchContext big = FIELD.ctx().toBuilder().hazardPenaltyTicks(c -> c == cell ? 12.0 : 0).build();
        CostVerifier.Verdict v = CostVerifier.check(big, plan, 0, FIELD.grid());
        assertTrue(v.cancel());
        assertEquals(3, v.moveIndex());
        assertEquals(12.0, v.liveTicks() - v.plannedTicks(), 1e-9);
        SearchContext small = FIELD.ctx().toBuilder().hazardPenaltyTicks(c -> c == cell ? 9.0 : 0).build();
        assertFalse(CostVerifier.check(small, plan, 0, FIELD.grid()).cancel());
    }

    @Test
    void plannedBreaksAreAppliedBeforeLaterMovesAreChecked() {
        BenchmarkScenarios.Case canopy = BenchmarkScenarios.load("leaf_canopy");
        PathPlan plan = UnifiedPathFinder.search(canopy.ctx());
        for (int i = 0; i < plan.moves().size(); i++) {
            assertFalse(CostVerifier.check(canopy.ctx(), plan, i, canopy.grid()).cancel(), "from " + i);
        }
    }

    /**
     * 6ad-6al field bug F. A 1-wide corridor (z=1) with an unbreakable block over the start, so the bot
     * cannot jump straight onto the dirt at x=1. The search, pricing every move on unmutated terrain,
     * mines the dirt (move 0), walks past it (move 1), then jumps back onto it as a step (move 2): the
     * verifier, which applies move 0's break first, forbids that ASCEND (the field line: "move 3 ASCEND
     * now forbidden ... planned=5.6 live=1000000.0"), and a replan returns the same plan.
     */
    private static SearchContext minesItsOwnStep() {
        VoxelGrid g = new VoxelGrid(0, 0, 0, 5, 6, 3, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 4, 5, 2, 'n');
        g.fill(0, 1, 1, 2, 4, 1, '.');
        g.set(0, 3, 1, 'n');
        g.set(1, 1, 1, 'd');
        return SearchContext.builder(g, 0, 1, 1, 1, 2, 1).allowBreak(true).build();
    }

    @Test
    void aPlannedBreakUnderALaterAscendCancelsIt() {
        SearchContext ctx = minesItsOwnStep();
        PathPlan plan = UnifiedPathFinder.search(ctx, () -> 0L);
        assertEquals(PlanStatus.FULL, plan.status());
        List<PlannedMove> m = plan.moves();
        assertEquals(3, m.size(), m.toString());
        assertEquals(MoveKind.TRAVERSE, m.get(0).kind());
        assertEquals(CellPos.pack(1, 1, 1), m.get(0).toBreak()[0]);
        assertEquals(MoveKind.ASCEND, m.get(2).kind());
        assertEquals(0, m.get(2).toBreak().length);
        CostVerifier.Verdict v = CostVerifier.check(ctx, plan, 0, ctx.terrain());
        assertTrue(v.cancel());
        assertEquals(2, v.moveIndex());
        assertTrue(v.reason().contains("ASCEND now forbidden"), v.reason());
        assertEquals(ActionCosts.COST_INF, v.liveTicks());
        // Same terrain, same search: the replan is the same plan.
        assertEquals(m, UnifiedPathFinder.search(ctx, () -> 0L).moves());
    }

    @Test
    void truncationKeepsTheMovesBeforeTheConflictAsAPartial() {
        SearchContext ctx = minesItsOwnStep();
        PathPlan plan = UnifiedPathFinder.search(ctx, () -> 0L);
        UnifiedPathFinder.Truncation cut = UnifiedPathFinder.truncateToExecutable(ctx, plan);
        assertTrue(cut.truncated());
        assertEquals(2, cut.cutAt());
        assertTrue(cut.reason().contains("ASCEND now forbidden"), cut.reason());
        PathPlan p = cut.plan();
        assertEquals(PlanStatus.PARTIAL, p.status());
        assertEquals(NoPathReason.SELF_CONFLICT, p.reason());
        assertEquals(plan.moves().subList(0, 2), p.moves());
        assertEquals(2, p.endX());
        assertEquals(plan.moves().get(0).ticks() + plan.moves().get(1).ticks(), p.totalTicks(), 1e-9);
        assertEquals(plan.planId(), p.planId());
        // What is kept runs: the verifier passes it and the simulator proves it.
        assertFalse(CostVerifier.check(ctx, p, 0, ctx.terrain(), p.moves().size(), Double.POSITIVE_INFINITY).cancel());
        assertTrue(MoveSimulator.simulate(ctx, p).executable());
        assertFalse(UnifiedPathFinder.truncateToExecutable(ctx, p).truncated());
    }

    @Test
    void aConflictAtTheFirstMoveIsNoPath() {
        SearchContext ctx = minesItsOwnStep();
        // An ASCEND from the start under the unbreakable block: refused before anything is mined.
        PlannedMove forged = new PlannedMove(MoveKind.ASCEND, 0, 1, 1, 1, 2, 1, null, null, 5.6, 0, 0);
        PathPlan plan = new PathPlan(7, PlanStatus.FULL, NoPathReason.NONE, 0, List.of(forged), 5.6, 3, 1);
        UnifiedPathFinder.Truncation cut = UnifiedPathFinder.truncateToExecutable(ctx, plan);
        assertEquals(0, cut.cutAt());
        assertEquals(PlanStatus.NO_PATH, cut.plan().status());
        assertEquals(NoPathReason.SELF_CONFLICT, cut.plan().reason());
        assertTrue(cut.plan().isEmpty());
        assertEquals(7, cut.plan().planId());
    }

    @Test
    void aPrefixThatTrimsToNothingIsNoPath() {
        SearchContext ctx = minesItsOwnStep();
        PlannedMove mine = UnifiedPathFinder.search(ctx, () -> 0L).moves().get(0);
        // Move 1 jumps east from inside the mined cell onto open air: refused, and the only move left
        // ends right after a break, which a partial never does.
        PlannedMove forged = new PlannedMove(MoveKind.ASCEND, 1, 1, 1, 2, 2, 1, null, null, 5.6, 0, 0);
        PathPlan plan = new PathPlan(8, PlanStatus.FULL, NoPathReason.NONE, 0, List.of(mine, forged), 26, 3, 1);
        UnifiedPathFinder.Truncation cut = UnifiedPathFinder.truncateToExecutable(ctx, plan);
        assertEquals(1, cut.cutAt());
        assertEquals(PlanStatus.NO_PATH, cut.plan().status());
        assertEquals(NoPathReason.SELF_CONFLICT, cut.plan().reason());
    }

    @Test
    void executablePlansAndNoPathAreLeftAlone() {
        PathPlan field = UnifiedPathFinder.search(FIELD.ctx());
        UnifiedPathFinder.Truncation same = UnifiedPathFinder.truncateToExecutable(FIELD.ctx(), field);
        assertFalse(same.truncated());
        assertSame(field, same.plan());
        assertNull(same.reason());
        // A plan whose breaks the next moves rely on being gone (the leaf canopy) is not cut either.
        BenchmarkScenarios.Case canopy = BenchmarkScenarios.load("leaf_canopy");
        PathPlan leaves = UnifiedPathFinder.search(canopy.ctx());
        assertSame(leaves, UnifiedPathFinder.truncateToExecutable(canopy.ctx(), leaves).plan());
        PathPlan none = PathPlan.noPath(9, NoPathReason.BOXED_IN, 1, 0);
        assertSame(none, UnifiedPathFinder.truncateToExecutable(FIELD.ctx(), none).plan());
    }

    @Test
    void aLandingThatMovedCancels() {
        // A planned 2-block fall whose landing floor is removed now falls 3: a different landing.
        VoxelGrid g = new VoxelGrid(0, 0, 0, 6, 8, 3, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 5, 0, 2, 'd');
        g.fill(0, 1, 0, 1, 3, 2, 'd');
        g.fill(2, 1, 0, 5, 1, 2, 'd');
        SearchContext ctx = SearchContext.builder(g, 1, 4, 1, 4, 2, 1).build();
        PathPlan plan = UnifiedPathFinder.search(ctx);
        assertEquals(MoveKind.FALL, plan.moves().get(0).kind());
        VoxelGrid live = g.copy();
        live.set(2, 1, 1, '.');
        CostVerifier.Verdict v = CostVerifier.check(ctx, plan, 0, live);
        assertTrue(v.cancel());
        assertEquals(0, v.moveIndex());
    }
}
