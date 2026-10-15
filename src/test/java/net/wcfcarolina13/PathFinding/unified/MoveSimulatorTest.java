package net.wcfcarolina13.PathFinding.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MoveSimulatorTest {

    @Test
    void benchmarkPlansAreExecutableAndTheOverlayLeavesTerrainAlone() {
        for (BenchmarkScenarios.Case c : List.of(BenchmarkScenarios.load("sand_wall_break"),
                BenchmarkScenarios.gullySwim(), BenchmarkScenarios.gullyStair(), BenchmarkScenarios.load("leaf_canopy"))) {
            PathPlan plan = UnifiedPathFinder.search(c.ctx());
            assertTrue(MoveSimulator.simulate(c.ctx(), plan.moves(), c.grid().copy()).executable(), c.name());
            String before = snapshot(c.grid());
            MoveSimulator.Result overlay = MoveSimulator.simulate(c.ctx(), plan);
            assertTrue(overlay.executable(), c.name() + " overlay: " + overlay);
            assertEquals(before, snapshot(c.grid()), "overlay must not touch the terrain");
        }
    }

    @Test
    void appliesBreaksAndPlacesToTheCopy() {
        BenchmarkScenarios.Case c = BenchmarkScenarios.load("sand_wall_break");
        PathPlan plan = UnifiedPathFinder.search(c.ctx());
        VoxelGrid copy = c.grid().copy();
        assertTrue(MoveSimulator.simulate(c.ctx(), plan.moves(), copy).executable());
        PlannedMove mine = plan.moves().stream().filter(m -> m.toBreak().length > 0).findFirst().orElseThrow();
        long cell = mine.toBreak()[0];
        assertEquals('.', copy.get(CellPos.x(cell), CellPos.y(cell), CellPos.z(cell)));
        assertEquals('s', c.grid().get(CellPos.x(cell), CellPos.y(cell), CellPos.z(cell)));

        BenchmarkScenarios.Case stair = BenchmarkScenarios.gullyStair();
        VoxelGrid s = stair.grid().copy();
        assertTrue(MoveSimulator.simulate(stair.ctx(), UnifiedPathFinder.search(stair.ctx()).moves(), s).executable());
        assertEquals('d', s.get(22, 63, 126), "the dirt stair is left in the world");
    }

    @Test
    void protectingAPlannedBreakFailsAtThatMove() {
        BenchmarkScenarios.Case c = BenchmarkScenarios.load("sand_wall_break");
        PathPlan plan = UnifiedPathFinder.search(c.ctx());
        int idx = indexOfFirstAction(plan.moves());
        long cell = plan.moves().get(idx).toBreak()[0];
        VoxelGrid changed = c.grid().copy();
        changed.protect(CellPos.x(cell), CellPos.y(cell), CellPos.z(cell), CellPos.x(cell), CellPos.y(cell), CellPos.z(cell));
        MoveSimulator.Result r = MoveSimulator.simulate(c.ctx(), plan.moves(), changed);
        assertFalse(r.executable());
        assertEquals(idx, r.failedIndex());
    }

    @Test
    void aWallAppearingOnTheRouteFails() {
        BenchmarkScenarios.Case c = BenchmarkScenarios.load("open_field");
        PathPlan plan = UnifiedPathFinder.search(c.ctx());
        VoxelGrid changed = c.grid().copy();
        changed.fill(30, 1, 0, 30, 3, 59, '#');
        MoveSimulator.Result r = MoveSimulator.simulate(c.ctx(), plan.moves(), changed);
        assertFalse(r.executable());
        assertEquals(30 - 5 - 1, r.failedIndex());
    }

    @Test
    void discontinuousOrForgedMovesFail() {
        BenchmarkScenarios.Case c = BenchmarkScenarios.load("open_field");
        List<PlannedMove> moves = new ArrayList<>(UnifiedPathFinder.search(c.ctx()).moves());
        moves.remove(3);
        MoveSimulator.Result gap = MoveSimulator.simulate(c.ctx(), moves, c.grid().copy());
        assertFalse(gap.executable());
        assertEquals(3, gap.failedIndex());

        // A traverse into a 2-high wall that pretends no mining is needed.
        BenchmarkScenarios.Case w = BenchmarkScenarios.load("sand_wall_break");
        PlannedMove forged = new PlannedMove(MoveKind.TRAVERSE, 5, 1, 2, 6, 1, 2, null, null, 3.5, 0, 0);
        VoxelGrid g = w.grid().copy();
        g.fill(6, 1, 2, 6, 2, 2, 's');
        assertFalse(MoveSimulator.simulate(w.ctx(), List.of(forged), g).executable());
    }

    @Test
    void swimRunOverTheCapFails() {
        BenchmarkScenarios.Case c = BenchmarkScenarios.gullySwim();
        PathPlan plan = UnifiedPathFinder.search(c.ctx());
        SearchContext tight = c.ctx().toBuilder().maxSwimRun(3).build();
        assertFalse(MoveSimulator.simulate(tight, plan.moves(), c.grid().copy()).executable());
    }

    private static int indexOfFirstAction(List<PlannedMove> moves) {
        for (int i = 0; i < moves.size(); i++) {
            if (moves.get(i).toBreak().length > 0) {
                return i;
            }
        }
        throw new AssertionError("no action");
    }

    private static String snapshot(VoxelGrid g) {
        StringBuilder sb = new StringBuilder();
        for (int y = g.originY; y < g.originY + g.sizeY; y++) {
            sb.append(g.layer(y));
        }
        return sb.toString();
    }
}
