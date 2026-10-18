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
     * cannot jump straight onto the dirt at x=1. Priced on unmutated terrain, the cheapest plan mines the
     * dirt (move 0), walks past it (move 1), then jumps back onto it as a step (move 2): the verifier,
     * which applies move 0's break first, forbids that ASCEND (the field line: "move 3 ASCEND now
     * forbidden ... planned=5.6 live=1000000.0"). There is no lawful route to the goal.
     */
    private static SearchContext minesItsOwnStep() {
        VoxelGrid g = new VoxelGrid(0, 0, 0, 5, 6, 3, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 4, 5, 2, 'n');
        g.fill(0, 1, 1, 2, 4, 1, '.');
        g.set(0, 3, 1, 'n');
        g.set(1, 1, 1, 'd');
        return SearchContext.builder(g, 0, 1, 1, 1, 2, 1).allowBreak(true).build();
    }

    /**
     * The self-conflicting plan the pre-1.1.267 search returned on {@link #minesItsOwnStep}, built from
     * raw-priced moves (each evaluated on the unmutated terrain, as the search prices them) so the
     * {@link UnifiedPathFinder#truncateToExecutable} backstop stays covered now that the search refuses it.
     */
    private static PathPlan rawSelfConflict(SearchContext ctx) {
        TerrainView t = ctx.terrain();
        PlannedMove mine = MoveCosts.evaluate(ctx, t, 0, 1, 1, MoveKind.TRAVERSE, 1, 0, ctx.scaffoldCount(), 0);
        PlannedMove past = MoveCosts.evaluate(ctx, t, 1, 1, 1, MoveKind.TRAVERSE, 1, 0, mine.scaffoldLeftAfter(),
                mine.swimRunAfter());
        PlannedMove back = MoveCosts.evaluate(ctx, t, 2, 1, 1, MoveKind.ASCEND, -1, 0, past.scaffoldLeftAfter(),
                past.swimRunAfter());
        List<PlannedMove> moves = List.of(mine, past, back);
        double ticks = 0;
        for (PlannedMove m : moves) {
            ticks += m.ticks();
        }
        return new PathPlan(5, PlanStatus.FULL, NoPathReason.NONE, 0, moves, ticks, 3, 1);
    }

    @Test
    void aPlannedBreakUnderALaterAscendCancelsIt() {
        SearchContext ctx = minesItsOwnStep();
        PathPlan plan = rawSelfConflict(ctx);
        List<PlannedMove> m = plan.moves();
        assertEquals(CellPos.pack(1, 1, 1), m.get(0).toBreak()[0]);
        assertEquals(2, m.get(1).toX());
        assertEquals(0, m.get(2).toBreak().length);
        assertEquals(1, m.get(2).toX());
        assertEquals(2, m.get(2).toY());
        CostVerifier.Verdict v = CostVerifier.check(ctx, plan, 0, ctx.terrain());
        assertTrue(v.cancel());
        assertEquals(2, v.moveIndex());
        assertTrue(v.reason().contains("ASCEND now forbidden"), v.reason());
        assertEquals(ActionCosts.COST_INF, v.liveTicks());
    }

    @Test
    void theSearchNoLongerReturnsAPlanThatMinesItsOwnStep() {
        SearchContext ctx = minesItsOwnStep();
        PathPlan plan = UnifiedPathFinder.search(ctx, () -> 0L);
        // No lawful route: the goal stands on the dirt and the only way past it mines it.
        assertEquals(PlanStatus.NO_PATH, plan.status(), plan.moves().toString());
        assertFalse(UnifiedPathFinder.truncateToExecutable(ctx, plan).truncated());
    }

    @Test
    void aNoPathCausedByTheSearchsOwnRefusalsIsSelfConflict() {
        // minesItsOwnStep keeps its real cause: its walls and ceiling are unbreakable.
        PathPlan walled = UnifiedPathFinder.search(minesItsOwnStep(), () -> 0L);
        assertEquals(PlanStatus.NO_PATH, walled.status(), walled.moves().toString());
        assertEquals(NoPathReason.BLOCKED_UNBREAKABLE, walled.reason());
        // The same corridor with nothing unbreakable in reach: the box ends at the corridor and the cell
        // over the start is lava (never broken, never tallied), so only the search's own refusal is left.
        VoxelGrid g = new VoxelGrid(0, 0, 0, 5, 6, 3, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 4, 5, 2, 'n');
        g.fill(0, 1, 1, 2, 4, 1, '.');
        g.set(0, 3, 1, '!');
        g.set(1, 1, 1, 'd');
        SearchContext ctx = SearchContext.builder(g, 0, 1, 1, 1, 2, 1).allowBreak(true)
                .box(new SearchBox(0, 1, 1, 2, 4, 1)).build();
        PathPlan plan = UnifiedPathFinder.search(ctx, () -> 0L);
        assertEquals(PlanStatus.NO_PATH, plan.status(), plan.moves().toString());
        assertEquals(NoPathReason.SELF_CONFLICT, plan.reason());
        // Only the generic BOXED_IN gives way; a pit with one incidental self-refusal keeps its cause.
        assertEquals(NoPathReason.SELF_CONFLICT, UnifiedPathFinder.selfConflictOr(NoPathReason.BOXED_IN, 1));
        assertEquals(NoPathReason.BLOCKED_UNBREAKABLE,
                UnifiedPathFinder.selfConflictOr(NoPathReason.BLOCKED_UNBREAKABLE, 1));
        assertEquals(NoPathReason.NO_SCAFFOLD, UnifiedPathFinder.selfConflictOr(NoPathReason.NO_SCAFFOLD, 2));
        assertEquals(NoPathReason.BOXED_IN, UnifiedPathFinder.selfConflictOr(NoPathReason.BOXED_IN, 0));
        assertEquals(NoPathReason.BUDGET, UnifiedPathFinder.selfConflictOr(NoPathReason.BUDGET, 3));
        assertEquals(NoPathReason.BLOCKED_PROTECTED,
                UnifiedPathFinder.selfConflictOr(NoPathReason.BLOCKED_PROTECTED, 3));
    }

    /**
     * {@link #minesItsOwnStep} with a lawful detour: the corridor runs west to a step at x=0, and from
     * the step a raised lane (z=2, feet y=3) runs east to above the dirt, where a DESCEND lands on it.
     * Raw-priced, mining the dirt is cheaper than the detour; the search now refuses the ASCEND back onto
     * the mined cell and walks the detour, a FULL plan the verifier passes. The mining branch never
     * reaches the lane (its wall is two high and never broken), so no node it owns blocks the detour.
     */
    @Test
    void theSearchRoutesAroundAMoveItsOwnPathUndoes() {
        VoxelGrid g = new VoxelGrid(0, 0, 0, 8, 7, 4, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 7, 6, 3, 'n');
        g.fill(0, 1, 1, 5, 4, 1, '.');
        g.set(0, 1, 1, 'n');          // the step up to the lane
        g.set(3, 3, 1, 'n');          // over the start: no jump straight onto the dirt
        g.set(4, 1, 1, 'd');
        g.fill(0, 3, 2, 5, 4, 2, '.'); // the raised lane, floor at y=2
        SearchContext ctx = SearchContext.builder(g, 3, 1, 1, 4, 2, 1).allowBreak(true).build();

        PathPlan plan = UnifiedPathFinder.search(ctx, () -> 0L);
        assertEquals(PlanStatus.FULL, plan.status(), plan.toString());
        assertEquals(4, plan.endX());
        assertEquals(2, plan.endY());
        assertEquals(1, plan.endZ());
        for (PlannedMove m : plan.moves()) {
            assertEquals(0, m.toBreak().length, m.toString());
        }
        assertFalse(CostVerifier.check(ctx, plan, 0, ctx.terrain(), plan.moves().size(),
                Double.POSITIVE_INFINITY).cancel());
        assertFalse(UnifiedPathFinder.truncateToExecutable(ctx, plan).truncated());
        assertTrue(MoveSimulator.simulate(ctx, plan).executable());

        // The raw-priced mine-and-step-back plan is the cheaper one: only the refusal chose the detour.
        TerrainView t = ctx.terrain();
        PlannedMove mine = MoveCosts.evaluate(ctx, t, 3, 1, 1, MoveKind.TRAVERSE, 1, 0, 0, 0);
        PlannedMove past = MoveCosts.evaluate(ctx, t, 4, 1, 1, MoveKind.TRAVERSE, 1, 0, 0, 0);
        PlannedMove back = MoveCosts.evaluate(ctx, t, 5, 1, 1, MoveKind.ASCEND, -1, 0, 0, 0);
        assertEquals(CellPos.pack(4, 1, 1), mine.toBreak()[0]);
        assertTrue(mine.ticks() + past.ticks() + back.ticks() < plan.totalTicks(),
                (mine.ticks() + past.ticks() + back.ticks()) + " vs " + plan.totalTicks());
    }

    /**
     * The random terrain of {@link MoveCostsInvariantTest} (seed 140 there once lost a break-free route:
     * the cell before it was reached cheaper by a path whose own break the next move needed). A route
     * that breaks and places nothing is never refused, so whenever one exists the search is FULL; and a
     * FULL plan runs as planned, with nothing for the truncation backstop to cut.
     */
    @Test
    void onRandomTerrainABreakFreeRouteIsNeverLostAndFullPlansNeedNoCut() {
        int breakFree = 0;
        int full = 0;
        for (long seed = 1; seed <= 300; seed++) {
            java.util.Random rnd = new java.util.Random(seed * 7919);
            VoxelGrid g = MoveCostsInvariantTest.randomGrid(rnd);
            int[] s = MoveCostsInvariantTest.randomStanding(g, rnd);
            int[] t = MoveCostsInvariantTest.randomStanding(g, rnd);
            if (s == null || t == null) {
                continue;
            }
            SearchContext ctx = SearchContext.builder(g, s[0], s[1], s[2], t[0], t[1], t[2])
                    .canSprint(rnd.nextBoolean()).allowBreak(true).allowSwim(true).allowDownward(rnd.nextBoolean())
                    .box(new SearchBox(0, 0, 0, 9, 9, 9)).primaryMs(10_000).failureMs(10_000).nodeCap(1_000_000)
                    .build();
            PathPlan plan = UnifiedPathFinder.search(ctx, () -> 0L);
            if (plan.status() == PlanStatus.FULL) {
                full++;
                assertFalse(UnifiedPathFinder.truncateToExecutable(ctx, plan).truncated(), "seed " + seed);
            }
            PathPlan walk = UnifiedPathFinder.search(ctx.toBuilder().allowBreak(false).build(), () -> 0L);
            if (walk.status() == PlanStatus.FULL) {
                breakFree++;
                assertEquals(PlanStatus.FULL, plan.status(), "break-free route lost, seed " + seed);
            }
        }
        assertTrue(breakFree > 40 && full > breakFree, breakFree + " break-free, " + full + " full");
    }

    @Test
    void truncationKeepsTheMovesBeforeTheConflictAsAPartial() {
        SearchContext ctx = minesItsOwnStep();
        PathPlan plan = rawSelfConflict(ctx);
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
        PlannedMove mine = rawSelfConflict(ctx).moves().get(0);
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
