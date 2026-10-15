package net.wcfcarolina13.PathFinding.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
