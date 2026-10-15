package net.wcfcarolina13.PathFinding.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The 1.1.242 release gate (design section 10): every scenario with its thresholds. Each scenario
 * runs {@link BenchmarkScenarios#RUNS} times (first discarded for JIT); the table is printed once.
 */
class UnifiedFinderBenchmarkTest {
    private static final double RATIO = 1.01;
    private static final Map<String, BenchmarkScenarios.Row> ROWS = new ConcurrentHashMap<>();

    private static BenchmarkScenarios.Row row(BenchmarkScenarios.Case c) {
        return ROWS.computeIfAbsent(c.name(), k -> BenchmarkScenarios.run(c));
    }

    private static BenchmarkScenarios.Row row(String file) {
        return row(BenchmarkScenarios.load(file));
    }

    @AfterAll
    static void printTable() {
        System.out.println(BenchmarkScenarios.header());
        ROWS.values().stream().sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(r -> System.out.println(BenchmarkScenarios.format(r)));
    }

    private static void assertFullOptimalExecutable(BenchmarkScenarios.Row r) {
        assertEquals(PlanStatus.FULL, r.plan().status(), r.name());
        assertTrue(r.exec().executable(), r.name() + ": " + r.exec());
        assertTrue(r.ratio() < RATIO, r.name() + " ratio " + r.ratio());
    }

    private static boolean anyBreak(PathPlan p) {
        return p.moves().stream().anyMatch(m -> m.toBreak().length > 0);
    }

    @Test
    void openField() {
        BenchmarkScenarios.Row r = row("open_field");
        assertFullOptimalExecutable(r);
        assertTrue(EnumSet.of(MoveKind.TRAVERSE, MoveKind.DIAGONAL).containsAll(r.kinds()), r.kinds().toString());
        assertTrue(r.p50() < 5, "p50 " + r.p50());
        assertTrue(r.p95() < 20, "p95 " + r.p95());
        assertTrue(r.plan().nodesExpanded() < 3000);
        // nodes with sprint off too (design section 4: the exact C_xz in either setting)
        BenchmarkScenarios.Case walk = BenchmarkScenarios.load("open_field");
        PathPlan walking = UnifiedPathFinder.search(walk.ctx().toBuilder().canSprint(false).build());
        assertEquals(PlanStatus.FULL, walking.status());
        assertTrue(walking.nodesExpanded() < 3000, "walking nodes " + walking.nodesExpanded());
    }

    @Test
    void terraces() {
        BenchmarkScenarios.Row r = row("terraces");
        assertFullOptimalExecutable(r);
        assertTrue(r.kinds().containsAll(Set.of(MoveKind.ASCEND, MoveKind.DESCEND)));
        assertFalse(anyBreak(r.plan()));
        // up half: from the foot of the steps to the plateau, h(start) is the optimal cost
        GridLoader.GridScenario s = GridLoader.load("terraces");
        SearchContext up = SearchContext.builder(s.grid(), 4, 1, 3, 9, 6, 3).build();
        double optimal = DijkstraReference.optimal(up);
        double h = MoveCosts.heuristic(up, 4, 1, 3, 9, 6, 3);
        assertEquals(optimal, h, optimal * 0.001);
    }

    @Test
    void pureStair() {
        BenchmarkScenarios.Row r = row("pure_stair");
        assertFullOptimalExecutable(r);
        SearchContext ctx = BenchmarkScenarios.load("pure_stair").ctx();
        double h = MoveCosts.heuristic(ctx, ctx.startX(), ctx.startY(), ctx.startZ(), ctx.goalX(), ctx.goalY(), ctx.goalZ());
        assertEquals(r.optimal(), h, r.optimal() * 0.001, "admissibility bench: h(start) vs Dijkstra");
    }

    @Test
    void cliffSideSlope() {
        BenchmarkScenarios.Row r = row("cliff_side_slope");
        assertFullOptimalExecutable(r);
        assertFalse(anyBreak(r.plan()));
        assertTrue(r.plan().moves().stream().anyMatch(m -> m.kind() == MoveKind.ASCEND && m.toX() >= 14),
                "climbs the side slope");
    }

    @Test
    void gullyEscapesBySwimming() {
        BenchmarkScenarios.Row r = row(BenchmarkScenarios.gullySwim());
        assertFullOptimalExecutable(r);
        List<PlannedMove> swims = r.plan().moves().stream().filter(m -> m.kind() == MoveKind.SWIM).toList();
        assertTrue(swims.size() >= 3);
        assertTrue(swims.get(0).toY() < swims.get(0).fromY() || swims.get(0).toY() == swims.get(0).fromY(), "entry");
        assertEquals(1, swims.get(0).swimRunAfter(), "entry");
        assertTrue(swims.stream().anyMatch(m -> m.swimRunAfter() > 1), "lateral");
        PlannedMove exit = swims.get(swims.size() - 1);
        assertEquals(0, exit.swimRunAfter(), "exit");
        assertEquals(1, exit.toY() - exit.fromY(), "climbs out one block");
        int maxRun = swims.stream().mapToInt(PlannedMove::swimRunAfter).max().orElse(0);
        assertTrue(maxRun <= SearchContext.DEFAULT_MAX_SWIM_RUN);
    }

    @Test
    void gullyWithoutSwimmingIsBlockedByProtection() {
        BenchmarkScenarios.Row r = row(BenchmarkScenarios.gullyNoSwim());
        assertEquals(PlanStatus.NO_PATH, r.plan().status());
        assertEquals(NoPathReason.BLOCKED_PROTECTED, r.plan().reason());
    }

    @Test
    void gullyWithTwoDirtTakesOneStair() {
        BenchmarkScenarios.Row r = row(BenchmarkScenarios.gullyStair());
        assertFullOptimalExecutable(r);
        assertEquals(1, r.plan().moves().stream().filter(m -> m.kind() == MoveKind.ASCEND_PLACE).count());
    }

    @Test
    void gullyRealCellsMatchTheDump() {
        GridLoader.GridScenario s = GridLoader.load("gully_ledge");
        String dump = GridLoader.resource("gully/bob_ledge_22_63_126.txt");
        Map<Integer, List<String[]>> levels = BlocksDumpConverter.parse(dump);
        assertEquals(Set.of(60, 61, 62, 63, 64), levels.keySet());
        for (Map.Entry<Integer, List<String[]>> e : levels.entrySet()) {
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 5; col++) {
                    char expected = BlocksDumpConverter.protect(BlocksDumpConverter.glyph(e.getValue().get(row)[col]));
                    assertEquals(expected, s.grid().get(20 + col, e.getKey(), 124 + row), "y" + e.getKey() + " r" + row + " c" + col);
                }
            }
        }
        // the ledge: air over sand, 2-high protected sand west and south, deep water east
        assertTrue(MoveCosts.isStanding(s.grid(), 22, 63, 126));
        assertTrue(s.grid().isProtected(21, 64, 126) && s.grid().isProtected(22, 64, 127));
        assertTrue(MoveCosts.isSwimCell(s.grid(), 23, 62, 126));
        assertFalse(s.grid().isProtected(22, 63, 126));
    }

    @Test
    void sandWallBreakBeatsTheDetour() {
        BenchmarkScenarios.Row r = row("sand_wall_break");
        assertFullOptimalExecutable(r);
        List<PlannedMove> mines = r.plan().moves().stream().filter(m -> m.toBreak().length > 0).toList();
        assertEquals(1, mines.size());
        PlannedMove m = mines.get(0);
        assertEquals(MoveKind.ASCEND, m.kind());
        assertEquals(1, m.toBreak().length);
        assertEquals(CellPos.pack(m.toX(), m.fromY() + 1, m.toZ()), m.toBreak()[0], "breaks F.y+1");
        BenchmarkScenarios.Case c = BenchmarkScenarios.load("sand_wall_break");
        PathPlan detour = UnifiedPathFinder.search(c.ctx().toBuilder().allowBreak(false).build());
        assertEquals(PlanStatus.FULL, detour.status());
        assertTrue(r.plan().totalTicks() < detour.totalTicks(),
                r.plan().totalTicks() + " vs detour " + detour.totalTicks());
    }

    @Test
    void cavePitRespectsTheDepthFloor() {
        BenchmarkScenarios.Row r = row("cave_pit");
        assertFullOptimalExecutable(r);
        int floor = Math.min(6, 6) - 2;
        assertTrue(r.plan().moves().stream().allMatch(m -> m.toY() >= floor));
        assertEquals(floor, BenchmarkScenarios.load("cave_pit").ctx().box().minY());
    }

    @Test
    void treesBreakOnlyLeaves() {
        BenchmarkScenarios.Row r = row("trees_2x2");
        assertFullOptimalExecutable(r);
        VoxelGrid g = BenchmarkScenarios.load("trees_2x2").grid();
        for (PlannedMove m : r.plan().moves()) {
            for (long cell : m.toBreak()) {
                assertEquals('l', g.get(CellPos.x(cell), CellPos.y(cell), CellPos.z(cell)));
            }
        }
    }

    @Test
    void leafCanopyIsMinedAtHeadHeight() {
        BenchmarkScenarios.Row r = row("leaf_canopy");
        assertFullOptimalExecutable(r);
        List<PlannedMove> mines = r.plan().moves().stream().filter(m -> m.toBreak().length > 0).toList();
        assertEquals(2, mines.size());
        for (PlannedMove m : mines) {
            assertEquals(MoveKind.TRAVERSE, m.kind());
            assertEquals(CellPos.pack(m.toX(), m.toY() + 1, m.toZ()), m.toBreak()[0]);
            assertEquals(MoveCosts.minHorizontal(BenchmarkScenarios.load("leaf_canopy").ctx()) + 6 + 2, m.ticks(), 1e-9);
        }
    }

    @Test
    void campfireBerthIsKept() {
        BenchmarkScenarios.Row r = row("campfire");
        assertFullOptimalExecutable(r);
        VoxelGrid g = BenchmarkScenarios.load("campfire").grid();
        assertTrue(r.plan().moves().stream().noneMatch(m -> g.inCampfireBerth(m.toX(), m.toY(), m.toZ())));
    }

    @Test
    void lavaIsNeverStoodOnOrFallenToward() {
        BenchmarkScenarios.Row r = row("lava");
        assertFullOptimalExecutable(r);
        VoxelGrid g = BenchmarkScenarios.load("lava").grid();
        for (PlannedMove m : r.plan().moves()) {
            assertFalse(m.kind() == MoveKind.FALL);
            assertFalse(CellProps.has(g.props(m.toX(), m.toY(), m.toZ()), CellProps.LAVA));
            assertFalse(CellProps.has(g.props(m.toX(), m.toY() - 1, m.toZ()), CellProps.LAVA));
            assertFalse(CellProps.has(g.props(m.toX(), m.toY() + 1, m.toZ()), CellProps.LAVA));
        }
    }

    @Test
    void protectedZoneIsWalkedNotChanged() {
        BenchmarkScenarios.Row r = row("protected_zone");
        assertFullOptimalExecutable(r);
        VoxelGrid g = BenchmarkScenarios.load("protected_zone").grid();
        for (PlannedMove m : r.plan().moves()) {
            for (long c : m.toBreak()) {
                assertFalse(g.isProtected(CellPos.x(c), CellPos.y(c), CellPos.z(c)), "break in zone " + m);
            }
            for (long c : m.toPlace()) {
                assertFalse(g.isProtected(CellPos.x(c), CellPos.y(c), CellPos.z(c)), "place in zone " + m);
            }
        }
        assertTrue(r.plan().moves().stream().anyMatch(m -> g.isProtected(m.toX(), m.toY(), m.toZ())),
                "the detour walks through protected flat ground");
    }

    @Test
    void partialBudgetEndsPastFiveBlocks() {
        BenchmarkScenarios.Row r = row("partial_budget");
        PathPlan p = r.plan();
        assertEquals(PlanStatus.PARTIAL, p.status());
        SearchContext ctx = BenchmarkScenarios.load("partial_budget").ctx();
        double dx = p.endX() - ctx.startX();
        double dz = p.endZ() - ctx.startZ();
        assertTrue(Math.sqrt(dx * dx + dz * dz) >= UnifiedPathFinder.MIN_DIST_PATH);
        assertTrue(r.exec().executable());
        // over 30 moves the tail is cut by 10%: the end is short of the best node the cap allowed
        assertTrue(p.moves().size() > 30 && p.moves().size() <= p.nodesExpanded() * 0.9 + 1);
    }

    @Test
    void boxedInIsReportedFast() {
        BenchmarkScenarios.Row r = row("boxed_in");
        assertEquals(PlanStatus.NO_PATH, r.plan().status());
        assertEquals(NoPathReason.BOXED_IN, r.plan().reason());
        assertTrue(r.p50() < 1, "p50 " + r.p50());
    }

    @Test
    void everyScenarioIsDeterministic() {
        for (BenchmarkScenarios.Case c : BenchmarkScenarios.all()) {
            PathPlan a = UnifiedPathFinder.search(c.ctx(), () -> 0L);
            PathPlan b = UnifiedPathFinder.search(c.ctx(), () -> 0L);
            assertEquals(a.status(), b.status(), c.name());
            assertEquals(a.reason(), b.reason(), c.name());
            assertEquals(a.moves(), b.moves(), c.name());
            assertEquals(a.nodesExpanded(), b.nodesExpanded(), c.name());
        }
    }
}
