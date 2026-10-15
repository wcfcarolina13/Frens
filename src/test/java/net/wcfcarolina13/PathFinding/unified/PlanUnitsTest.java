package net.wcfcarolina13.PathFinding.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** PlanUnits / ExecUnit / PlanRequest: the pure half of the slice B adapter, over VoxelGrid plans. */
class PlanUnitsTest {

    private static PathPlan plan(SearchContext ctx) {
        return UnifiedPathFinder.search(ctx, () -> 0L);
    }

    /** Every move index appears in exactly one unit, in order, and two walk runs are never adjacent. */
    private static void assertCoversPlanInOrder(PathPlan plan, List<ExecUnit> units) {
        int next = 0;
        ExecUnit prev = null;
        for (ExecUnit u : units) {
            assertEquals(next, u.firstMoveIndex(), "units must be contiguous");
            assertTrue(u.lastMoveIndex() >= u.firstMoveIndex());
            if (u instanceof ExecUnit.WalkRun run) {
                assertFalse(prev instanceof ExecUnit.WalkRun, "two walk runs in a row");
                for (int i = run.firstMoveIndex(); i <= run.lastMoveIndex(); i++) {
                    assertFalse(plan.moves().get(i).isAction(), "action move inside a walk run at " + i);
                }
                PlannedMove first = plan.moves().get(run.firstMoveIndex());
                assertEquals(first.fromPacked(), run.nodes().get(0).packed());
                for (int i = 0; i < run.moveCount(); i++) {
                    assertEquals(plan.moves().get(run.firstMoveIndex() + i).toPacked(), run.nodes().get(i + 1).packed());
                }
            } else {
                ExecUnit.ActionUnit a = assertInstanceOf(ExecUnit.ActionUnit.class, u);
                assertTrue(a.move().isAction());
                assertEquals(plan.moves().get(a.moveIndex()), a.move());
            }
            next = u.lastMoveIndex() + 1;
            prev = u;
        }
        assertEquals(plan.moves().size(), next, "units must cover every move");
    }

    @Test
    void openFieldIsOneWalkRun() {
        BenchmarkScenarios.Case c = BenchmarkScenarios.load("open_field");
        PathPlan p = plan(c.ctx());
        List<ExecUnit> units = PlanUnits.units(p, c.grid());
        assertEquals(1, units.size());
        ExecUnit.WalkRun run = assertInstanceOf(ExecUnit.WalkRun.class, units.get(0));
        assertEquals(0, run.firstMoveIndex());
        assertEquals(p.moves().size() - 1, run.lastMoveIndex());
        assertEquals(p.moves().size() + 1, run.nodes().size());
        assertCoversPlanInOrder(p, units);
        assertTrue(PlanUnits.walkOnly(p));
    }

    @Test
    void gullySwimSplitsIntoRunsAndSwimActions() {
        BenchmarkScenarios.Case c = BenchmarkScenarios.gullySwim();
        PathPlan p = plan(c.ctx());
        assertEquals(PlanStatus.FULL, p.status());
        List<ExecUnit> units = PlanUnits.units(p, c.grid());
        assertCoversPlanInOrder(p, units);
        long swims = units.stream().filter(u -> u instanceof ExecUnit.ActionUnit a && a.move().kind() == MoveKind.SWIM)
                .count();
        assertEquals(p.moves().stream().filter(m -> m.kind() == MoveKind.SWIM).count(), swims);
        assertTrue(swims > 0);
        assertFalse(PlanUnits.walkOnly(p));
    }

    @Test
    void gullyStairIsAPlaceActionThatJumps() {
        BenchmarkScenarios.Case c = BenchmarkScenarios.gullyStair();
        PathPlan p = plan(c.ctx());
        List<ExecUnit> units = PlanUnits.units(p, c.grid());
        assertCoversPlanInOrder(p, units);
        ExecUnit.ActionUnit place = units.stream()
                .filter(u -> u instanceof ExecUnit.ActionUnit a && a.move().kind() == MoveKind.ASCEND_PLACE)
                .map(u -> (ExecUnit.ActionUnit) u).findFirst().orElseThrow();
        assertEquals(1, place.move().toPlace().length);
        assertTrue(PlanUnits.needsJump(place.move(), c.grid()));
    }

    @Test
    void breakingAscendIsAnActionUnit() {
        BenchmarkScenarios.Case c = BenchmarkScenarios.load("sand_wall_break");
        PathPlan p = plan(c.ctx());
        List<ExecUnit> units = PlanUnits.units(p, c.grid());
        assertCoversPlanInOrder(p, units);
        assertTrue(units.stream().anyMatch(u -> u instanceof ExecUnit.ActionUnit a && a.move().toBreak().length > 0));
    }

    @Test
    void everyScenarioSplitsCleanly() {
        for (BenchmarkScenarios.Case c : BenchmarkScenarios.all()) {
            PathPlan p = plan(c.ctx());
            List<ExecUnit> units = PlanUnits.units(p, c.grid());
            assertCoversPlanInOrder(p, units);
            List<PlanUnits.PlanNode> nodes = PlanUnits.nodes(p, c.ctx().startX(), c.ctx().startY(), c.ctx().startZ(),
                    c.grid());
            if (p.status() == PlanStatus.NO_PATH) {
                assertTrue(nodes.isEmpty(), c.name());
            } else {
                assertEquals(p.moves().size() + 1, nodes.size(), c.name());
                assertNull(nodes.get(0).via(), c.name());
                assertFalse(nodes.get(0).jump(), c.name());
            }
        }
    }

    @Test
    void slabStepNeedsNoJumpButAFullBlockDoes() {
        // y0 dirt floor; x3 a bottom slab at y1; x4..7 dirt at y1 (a raised terrace).
        VoxelGrid g = new VoxelGrid(0, 0, 0, 9, 6, 5, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 8, 0, 4, 'd');
        g.fill(3, 1, 0, 3, 1, 4, '_');
        g.fill(4, 1, 0, 7, 1, 4, 'd');
        PathPlan slab = plan(SearchContext.builder(g, 1, 1, 2, 6, 2, 2).build());
        assertEquals(PlanStatus.FULL, slab.status());
        PlannedMove up = slab.moves().stream().filter(m -> m.kind() == MoveKind.ASCEND).findFirst().orElseThrow();
        assertEquals(3, up.toX());
        assertFalse(PlanUnits.needsJump(up, g));

        VoxelGrid full = g.copy();
        full.fill(3, 1, 0, 3, 1, 4, 'd');
        PathPlan block = plan(SearchContext.builder(full, 1, 1, 2, 6, 2, 2).build());
        PlannedMove up2 = block.moves().stream().filter(m -> m.kind() == MoveKind.ASCEND).findFirst().orElseThrow();
        assertTrue(PlanUnits.needsJump(up2, full));
        // The jump lands on the node that the ascend reached.
        List<PlanUnits.PlanNode> nodes = PlanUnits.nodes(block, 1, 1, 2, full);
        assertTrue(nodes.stream().anyMatch(n -> n.jump() && n.via() == MoveKind.ASCEND));
        assertTrue(nodes.stream().filter(n -> n.via() == MoveKind.TRAVERSE).noneMatch(PlanUnits.PlanNode::jump));
    }

    @Test
    void emptyPlans() {
        VoxelGrid g = new VoxelGrid(0, 0, 0, 5, 6, 5, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 4, 0, 4, 'd');
        PathPlan same = plan(SearchContext.builder(g, 2, 1, 2, 2, 1, 2).build());
        List<PlanUnits.PlanNode> one = PlanUnits.nodes(same, 2, 1, 2, g);
        assertEquals(List.of(new PlanUnits.PlanNode(2, 1, 2, false, null)), one);
        assertTrue(PlanUnits.units(same, g).isEmpty());
        PathPlan none = PathPlan.noPath(1, NoPathReason.BOXED_IN, 1, 0);
        assertTrue(PlanUnits.nodes(none, 2, 1, 2, g).isEmpty());
        assertTrue(PlanUnits.units(none, g).isEmpty());
    }

    @Test
    void walkRunAndActionUnitValidate() {
        assertThrows(IllegalArgumentException.class, () -> new ExecUnit.WalkRun(2, 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecUnit.WalkRun(0, 1, List.of(new PlanUnits.PlanNode(0, 0, 0, false, null))));
        assertThrows(IllegalArgumentException.class, () -> new ExecUnit.ActionUnit(null, 0));
    }

    // ---------------------------------------------------------------- PlanRequest

    @Test
    void legacyWalkPlansOnlyWhatWalkSegmentCanWalk() {
        for (BenchmarkScenarios.Case c : BenchmarkScenarios.all()) {
            SearchContext ctx = PlanRequest.legacyWalk().applyTo(c.ctx().toBuilder()).build();
            assertFalse(ctx.allowBreak() || ctx.allowPlace() || ctx.allowSwim() || ctx.allowDownward(), c.name());
            assertEquals(1, ctx.maxFall(), c.name());
            PathPlan p = plan(ctx);
            assertTrue(PlanUnits.walkOnly(p), c.name() + " planned an action under legacyWalk: " + p.moves());
            for (PlannedMove m : p.moves()) {
                assertTrue(Math.abs(m.toY() - m.fromY()) <= 1, c.name() + " " + m);
            }
        }
    }

    @Test
    void requestFactories() {
        PlanRequest walk = PlanRequest.walkTo(true, false);
        assertTrue(walk.allowBreak());
        assertFalse(walk.allowPlace());
        assertTrue(walk.allowSwim());
        assertEquals(SearchContext.DEFAULT_MAX_FALL, walk.maxFall());
        assertEquals(500, walk.primaryMs());
        assertEquals(2000, walk.failureMs());

        PlanRequest replan = walk.asReplan();
        assertEquals(300, replan.primaryMs());
        assertEquals(1200, replan.failureMs());
        assertTrue(replan.allowBreak());

        PlanRequest probe = PlanRequest.probe(150);
        assertFalse(probe.allowSwim());
        assertEquals(150, probe.primaryMs());
        assertEquals(0, probe.failureMs());
        assertEquals(PlanRequest.PROBE_NODE_CAP, probe.nodeCap());

        PlanRequest server = walk.onServerThread();
        assertEquals(0, server.failureMs());
        assertEquals(PlanRequest.PROBE_NODE_CAP, server.nodeCap());
        assertEquals(7, new PlanRequest(false, false, false, false, 3, 500, 2000, 7, null).onServerThread().nodeCap());

        assertEquals(1, new PlanRequest(false, false, false, false, 0, 0, 0, 0, null).maxFall());
        assertEquals(SearchContext.DEFAULT_MAX_FALL,
                new PlanRequest(false, false, false, false, 9, 1, 0, 0, null).maxFall());
        assertEquals(Set.of(5L), walk.withForbiddenFirstHops(Set.of(5L)).forbiddenFirstHops());
    }

    @Test
    void applyToCopiesGatesAndBudget() {
        VoxelGrid g = new VoxelGrid(0, 0, 0, 5, 6, 5, VoxelGrid.ToolTable.HAND);
        SearchContext ctx = new PlanRequest(true, true, false, false, 2, 123, 456, 789, Set.of(9L))
                .applyTo(SearchContext.builder(g, 1, 1, 1, 3, 1, 3)).build();
        assertTrue(ctx.allowBreak());
        assertTrue(ctx.allowPlace());
        assertFalse(ctx.allowSwim());
        assertEquals(2, ctx.maxFall());
        assertEquals(123, ctx.primaryMs());
        assertEquals(456, ctx.failureMs());
        assertEquals(789, ctx.nodeCap());
        assertEquals(Set.of(9L), ctx.forbiddenFirstHops());
        SearchContext defCap = PlanRequest.legacyWalk().applyTo(SearchContext.builder(g, 1, 1, 1, 3, 1, 3)).build();
        assertEquals(SearchContext.defaultNodeCap(1, 1, 1, 3, 1, 3), defCap.nodeCap());
    }
}
