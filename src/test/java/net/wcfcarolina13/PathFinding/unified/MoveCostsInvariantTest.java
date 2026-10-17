package net.wcfcarolina13.PathFinding.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Design section 3 heuristic invariant, checked mechanically for every kind:
 * {@code ticks >= C_xz * xzSteps + (C_asc - C_xz) * max(0, rise) + C_down_extra * max(0, drop)},
 * over random terrain (every kind must show up) with sprint on and off, plus admissibility of the
 * heuristic against the Dijkstra reference on random start/goal pairs.
 */
class MoveCostsInvariantTest {
    private static final char[] GLYPHS = {'.', '.', '.', '.', '.', '.', '#', '#', 'd', 'd', 's', 'l', '~', '~', '~',
            '_', 'm', 'P', 'w'};

    @Test
    void everyKindSatisfiesTheInvariantOnRandomTerrain() {
        Set<MoveKind> seen = EnumSet.noneOf(MoveKind.class);
        for (long seed = 1; seed <= 300; seed++) {
            Random rnd = new Random(seed);
            VoxelGrid g = randomGrid(rnd);
            for (boolean sprint : new boolean[]{true, false}) {
                SearchContext ctx = SearchContext.builder(g, 4, 4, 4, 8, 4, 8)
                        .canSprint(sprint).allowBreak(true).allowPlace(true).allowSwim(true).allowDownward(true)
                        .scaffoldCount(1 + rnd.nextInt(3)).box(new SearchBox(0, 0, 0, 9, 9, 9))
                        .hazardPenaltyTicks(cell -> (cell & 3) == 0 ? 7.5 : 0)
                        .anchorBonusTicks(cell -> (cell & 5) == 1 ? 50 : 0)
                        .build();
                List<PlannedMove> out = new ArrayList<>();
                MoveCosts.Refusals r = new MoveCosts.Refusals();
                for (int x = 1; x < 9; x++) {
                    for (int y = 1; y < 9; y++) {
                        for (int z = 1; z < 9; z++) {
                            boolean origin = MoveCosts.isStanding(g, x, y, z) || MoveCosts.isSwimCell(g, x, y, z);
                            if (!origin) {
                                continue;
                            }
                            out.clear();
                            UnifiedPathFinder.expand(ctx, g, x, y, z, ctx.scaffoldCount(), rnd.nextInt(3), r, out);
                            for (PlannedMove m : out) {
                                seen.add(m.kind());
                                assertInvariant(ctx, m);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(EnumSet.allOf(MoveKind.class), seen, "every kind must be exercised");
    }

    private static void assertInvariant(SearchContext ctx, PlannedMove m) {
        int xz = Math.max(Math.abs(m.toX() - m.fromX()), Math.abs(m.toZ() - m.fromZ()));
        int dy = m.toY() - m.fromY();
        double bound = MoveCosts.lowerBound(ctx, xz, dy);
        assertTrue(m.ticks() + 1e-9 >= bound, m + " below bound " + bound + " sprint=" + ctx.canSprint());
        assertTrue(m.ticks() > 0, "positive edge " + m);
    }

    @Test
    void heuristicNeverExceedsOptimalBeyondTheTieBreak() {
        int checked = 0;
        for (long seed = 1; seed <= 150; seed++) {
            Random rnd = new Random(seed * 7919);
            VoxelGrid g = randomGrid(rnd);
            int[] s = randomStanding(g, rnd);
            int[] t = randomStanding(g, rnd);
            if (s == null || t == null) {
                continue;
            }
            // Place off: scaffold is path state outside the node key, so A* and Dijkstra may explore it differently.
            SearchContext ctx = SearchContext.builder(g, s[0], s[1], s[2], t[0], t[1], t[2])
                    .canSprint(rnd.nextBoolean()).allowBreak(true).allowSwim(true).allowDownward(rnd.nextBoolean())
                    .box(new SearchBox(0, 0, 0, 9, 9, 9)).primaryMs(10_000).failureMs(10_000).nodeCap(1_000_000)
                    .build();
            PathPlan reference = DijkstraReference.optimalPlan(ctx);
            PathPlan plan = UnifiedPathFinder.search(ctx, () -> 0L);
            if (reference == null) {
                assertTrue(plan.status() != PlanStatus.FULL, "finder found a path Dijkstra did not, seed " + seed);
                continue;
            }
            double optimal = reference.totalTicks();
            checked++;
            double h = MoveCosts.heuristic(ctx, s[0], s[1], s[2], t[0], t[1], t[2]);
            assertTrue(h <= optimal * 1.001 + 1e-6, "h " + h + " > optimal " + optimal + " seed " + seed);
            if (UnifiedPathFinder.truncateToExecutable(ctx, reference).truncated()) {
                // The raw optimum mines a cell a later move needs (field bug F): the finder refuses it
                // since 1.1.267, so it may route dearer or find nothing. It is never cheaper.
                assertTrue(plan.status() != PlanStatus.FULL || plan.totalTicks() + 1e-6 >= optimal,
                        "cheaper than optimal, seed " + seed);
                continue;
            }
            assertEquals(PlanStatus.FULL, plan.status(), "seed " + seed);
            assertTrue(plan.totalTicks() <= optimal * 1.01 + 1e-6, "ratio over 1.01, seed " + seed);
            assertTrue(plan.totalTicks() + 1e-6 >= optimal, "cheaper than optimal, seed " + seed);
        }
        assertTrue(checked > 40, "enough reachable pairs: " + checked);
    }

    @Test
    void specificKindsAndCosts() {
        // A flat strip with one dirt step and a 2-rise of stone.
        VoxelGrid g = new VoxelGrid(0, 0, 0, 10, 8, 3, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 9, 0, 2, 'd');
        g.set(3, 1, 1, 'd');
        g.fill(6, 1, 0, 6, 2, 2, '#');
        SearchContext ctx = SearchContext.builder(g, 2, 1, 1, 9, 1, 1).allowPlace(true).scaffoldCount(1).build();
        double cxz = MoveCosts.minHorizontal(ctx);
        PlannedMove trav = MoveCosts.evaluate(ctx, g, 1, 1, 1, MoveKind.TRAVERSE, 1, 0, 1, 0);
        assertNotNull(trav);
        assertEquals(cxz, trav.ticks(), 1e-9);
        PlannedMove asc = MoveCosts.evaluate(ctx, g, 2, 1, 1, MoveKind.ASCEND, 1, 0, 1, 0);
        assertNotNull(asc);
        assertEquals(MoveCosts.ascendBase(ctx), asc.ticks(), 1e-9);
        assertEquals(2, asc.toY());
        PlannedMove desc = MoveCosts.evaluate(ctx, g, 3, 2, 1, MoveKind.DESCEND, 1, 0, 1, 0);
        assertNotNull(desc);
        assertEquals(ActionCosts.WALK_OFF_BLOCK + ActionCosts.fallN(1) + ActionCosts.CENTER_AFTER_FALL, desc.ticks(), 1e-9);
        PlannedMove place = MoveCosts.evaluate(ctx, g, 5, 1, 1, MoveKind.ASCEND_PLACE, 1, 0, 1, 0);
        assertNotNull(place);
        assertEquals(3, place.toY());
        assertEquals(0, place.scaffoldLeftAfter());
        assertEquals(CellPos.pack(5, 1, 1), place.toPlace()[0]);
        // scarcity: PLACE_COST * 20 / scaffoldLeft = 60 ticks with one dirt
        assertEquals(20 + ActionCosts.JUMP_ONE_BLOCK + MoveCosts.ascendBase(ctx) + 60, place.ticks(), 1e-9);
        assertNull(MoveCosts.evaluate(ctx, g, 5, 1, 1, MoveKind.ASCEND_PLACE, 1, 0, 0, 0), "no scaffold");
        assertNull(MoveCosts.evaluate(ctx, g, 2, 1, 1, MoveKind.DOWNWARD, 0, 0, 1, 0), "downward off by default");
    }

    @Test
    void slabStepIsFlooredAtTheAscendCost() {
        VoxelGrid g = new VoxelGrid(0, 0, 0, 6, 5, 3, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 5, 0, 2, 'd');
        g.set(2, 1, 1, '_');
        SearchContext ctx = SearchContext.builder(g, 1, 1, 1, 4, 1, 1).build();
        PlannedMove slab = MoveCosts.evaluate(ctx, g, 1, 1, 1, MoveKind.ASCEND, 1, 0, 0, 0);
        assertNotNull(slab);
        assertEquals(MoveCosts.ascendBase(ctx), slab.ticks(), 1e-9);
    }

    @Test
    void anchorBonusNeverEatsTheBaseCost() {
        VoxelGrid g = new VoxelGrid(0, 0, 0, 6, 4, 3, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 5, 0, 2, 'd');
        g.set(3, 1, 1, '~');
        SearchContext ctx = SearchContext.builder(g, 1, 1, 1, 5, 1, 1)
                .hazardPenaltyTicks(cell -> 3.0).anchorBonusTicks(cell -> 1000.0).build();
        PlannedMove dry = MoveCosts.evaluate(ctx, g, 1, 1, 1, MoveKind.TRAVERSE, 1, 0, 0, 0);
        assertEquals(MoveCosts.minHorizontal(ctx), dry.ticks(), 1e-9, "bonus only cancels the hazard part");
        PlannedMove wade = MoveCosts.evaluate(ctx, g, 2, 1, 1, MoveKind.TRAVERSE, 1, 0, 0, 0);
        assertEquals(MoveCosts.minHorizontal(ctx), wade.ticks(), 1e-9, "bonus may cancel the wade penalty too");
        SearchContext noBonus = ctx.toBuilder().anchorBonusTicks(null).build();
        PlannedMove wadePlain = MoveCosts.evaluate(noBonus, g, 2, 1, 1, MoveKind.TRAVERSE, 1, 0, 0, 0);
        assertEquals(MoveCosts.minHorizontal(ctx) + ActionCosts.WADE_PENALTY + 3.0, wadePlain.ticks(), 1e-9);
    }

    static VoxelGrid randomGrid(Random rnd) {
        VoxelGrid g = new VoxelGrid(0, 0, 0, 10, 10, 10,
                VoxelGrid.ToolTable.values()[rnd.nextInt(VoxelGrid.ToolTable.values().length)]);
        g.fill(0, 0, 0, 9, 0, 9, '#');
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                int h = 1 + rnd.nextInt(4);
                for (int y = 1; y < 10; y++) {
                    char c;
                    if (y < h) {
                        c = GLYPHS[6 + rnd.nextInt(GLYPHS.length - 6)];
                    } else if (y == h && rnd.nextInt(6) == 0) {
                        c = '~';
                    } else {
                        c = rnd.nextInt(14) == 0 ? GLYPHS[rnd.nextInt(GLYPHS.length)] : '.';
                    }
                    g.set(x, y, z, c);
                }
            }
        }
        if (rnd.nextInt(4) == 0) {
            g.set(1 + rnd.nextInt(8), 2, 1 + rnd.nextInt(8), 'c');
        }
        if (rnd.nextInt(3) == 0) {
            g.protect(rnd.nextInt(5), 0, rnd.nextInt(5), 5 + rnd.nextInt(5), 9, 5 + rnd.nextInt(5));
        }
        return g;
    }

    static int[] randomStanding(VoxelGrid g, Random rnd) {
        for (int tries = 0; tries < 200; tries++) {
            int x = 1 + rnd.nextInt(8);
            int y = 1 + rnd.nextInt(7);
            int z = 1 + rnd.nextInt(8);
            if (MoveCosts.isStanding(g, x, y, z)) {
                return new int[]{x, y, z};
            }
        }
        return null;
    }
}
