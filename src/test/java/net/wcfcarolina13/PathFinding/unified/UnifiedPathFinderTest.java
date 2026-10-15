package net.wcfcarolina13.PathFinding.unified;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class UnifiedPathFinderTest {

    private static VoxelGrid flat(int sx, int sz) {
        VoxelGrid g = new VoxelGrid(0, 0, 0, sx, 6, sz, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, sx - 1, 0, sz - 1, 'd');
        return g;
    }

    @Test
    void startEqualsGoalIsAnEmptyFullPlan() {
        VoxelGrid g = flat(5, 5);
        PathPlan plan = UnifiedPathFinder.search(SearchContext.builder(g, 2, 1, 2, 2, 1, 2).build());
        assertEquals(PlanStatus.FULL, plan.status());
        assertTrue(plan.isEmpty());
        assertThrows(IllegalStateException.class, plan::endX);
    }

    @Test
    void unstandableGoalSnapsWithinTwo() {
        VoxelGrid g = flat(12, 5);
        g.fill(9, 1, 0, 9, 3, 4, '#');
        // goal inside the stone column: nearest standable cell is beside it
        SearchContext ctx = SearchContext.builder(g, 1, 1, 2, 9, 1, 2).build();
        int[] snapped = UnifiedPathFinder.snapGoal(ctx);
        assertEquals(1, Math.abs(snapped[0] - 9) + Math.abs(snapped[2] - 2) + Math.abs(snapped[1] - 1));
        PathPlan plan = UnifiedPathFinder.search(ctx);
        assertEquals(PlanStatus.FULL, plan.status());
        assertEquals(8, plan.endX());
    }

    @Test
    void goalWithNothingStandableNearGivesAPartialTowardIt() {
        // Like the Baritone-style finder (no goal check, best-so-far partial): head toward the raw goal.
        VoxelGrid g = flat(14, 5);
        g.fill(8, 0, 0, 13, 0, 4, '.');
        PathPlan plan = UnifiedPathFinder.search(SearchContext.builder(g, 1, 1, 2, 12, 1, 2).build(), () -> 0L);
        assertEquals(PlanStatus.PARTIAL, plan.status());
        assertEquals(NoPathReason.GOAL_NOT_STANDABLE, plan.reason());
        assertEquals(7, plan.endX());
        // Nothing past 5 blocks from the start: NO_PATH with the same reason.
        PathPlan near = UnifiedPathFinder.search(SearchContext.builder(g, 5, 1, 2, 12, 1, 2).build(), () -> 0L);
        assertEquals(PlanStatus.NO_PATH, near.status());
        assertEquals(NoPathReason.GOAL_NOT_STANDABLE, near.reason());
    }

    /** A 24-long bank with a deep pond at x 12..20 (surface y2, bank top y2, stand y3). */
    private static VoxelGrid pond() {
        VoxelGrid g = new VoxelGrid(0, 0, 0, 24, 6, 3, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 23, 2, 2, 'd');
        g.fill(12, 0, 0, 20, 2, 2, '~');
        return g;
    }

    @Test
    void goalOverDeepWaterNeverEndsInTheWater() {
        VoxelGrid g = pond();
        // Mid-pond (4 from either shore): no standable cell within 2, so a partial that ends on the shore.
        SearchContext mid = SearchContext.builder(g, 1, 3, 1, 16, 3, 1).allowSwim(true).maxSwimRun(10).build();
        assertNull(UnifiedPathFinder.snapGoal(mid));
        PathPlan p = UnifiedPathFinder.search(mid, () -> 0L);
        assertEquals(PlanStatus.PARTIAL, p.status());
        assertEquals(NoPathReason.GOAL_NOT_STANDABLE, p.reason());
        assertTrue(MoveCosts.isStanding(g, p.endX(), p.endY(), p.endZ()));
        assertFalse(MoveCosts.isSwimCell(g, p.endX(), p.endY(), p.endZ()));
        // Near the far shore: the goal snaps to the shore and the FULL plan swims across and stands there.
        SearchContext edge = SearchContext.builder(g, 1, 3, 1, 19, 2, 1).allowSwim(true).maxSwimRun(10).build();
        PathPlan full = UnifiedPathFinder.search(edge);
        assertEquals(PlanStatus.FULL, full.status());
        assertTrue(full.moves().stream().anyMatch(m -> m.kind() == MoveKind.SWIM));
        assertTrue(MoveCosts.isStanding(g, full.endX(), full.endY(), full.endZ()));
        assertEquals(21, full.endX());
    }

    @Test
    void plankWallIsWalkedAroundWhileStoneIsStillMined() {
        // A 2-high wall across x10 with a gap at z15: the detour is 28 extra blocks, so mining the two
        // wall cells is cheaper whenever the wall is breakable at all (planks with a stone axe more so).
        VoxelGrid planks = new VoxelGrid(0, 0, 0, 20, 6, 16, VoxelGrid.ToolTable.STONE_AXE);
        planks.fill(0, 0, 0, 19, 0, 15, 'd');
        planks.fill(10, 1, 0, 10, 2, 14, 'n');
        PathPlan around = UnifiedPathFinder.search(
                SearchContext.builder(planks, 2, 1, 1, 18, 1, 1).allowBreak(true).build());
        assertEquals(PlanStatus.FULL, around.status());
        assertTrue(around.moves().stream().allMatch(m -> m.toBreak().length == 0), "planks never broken");
        assertTrue(around.moves().stream().anyMatch(m -> m.toZ() == 15), "walks through the gap");

        // The same wall in stone with a wooden pickaxe: mining through is still cheaper, as before.
        VoxelGrid stone = planks.copy();
        stone.fill(10, 1, 0, 10, 2, 14, '#');
        stone.setTool(VoxelGrid.ToolTable.WOOD_PICK);
        PathPlan through = UnifiedPathFinder.search(
                SearchContext.builder(stone, 2, 1, 1, 18, 1, 1).allowBreak(true).build());
        assertEquals(PlanStatus.FULL, through.status());
        assertTrue(through.moves().stream().anyMatch(m -> m.toBreak().length > 0), "stone still mined");
    }

    @Test
    void placementsBeyondTheScaffoldCountAreCutToANoScaffoldPartial() {
        // Hand-built: 7 walks then 2 placements; the bot holds 1 dirt.
        List<PlannedMove> moves = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            moves.add(new PlannedMove(MoveKind.TRAVERSE, i, 1, 0, i + 1, 1, 0, null, null, 4.6, 5, 0));
        }
        moves.add(new PlannedMove(MoveKind.ASCEND_PLACE, 7, 1, 0, 8, 2, 0, null,
                new long[]{CellPos.pack(7, 1, 0)}, 30, 4, 0));
        moves.add(new PlannedMove(MoveKind.ASCEND_PLACE, 8, 2, 0, 9, 3, 0, null,
                new long[]{CellPos.pack(8, 2, 0)}, 30, 3, 0));
        assertEquals(-1, UnifiedPathFinder.firstUnaffordablePlacement(moves, 2));
        assertEquals(8, UnifiedPathFinder.firstUnaffordablePlacement(moves, 1));
        assertEquals(7, UnifiedPathFinder.firstUnaffordablePlacement(moves, 0));

        VoxelGrid g = flat(12, 3);
        SearchContext ctx = SearchContext.builder(g, 0, 1, 0, 9, 3, 0).scaffoldCount(1).build();
        // Cut before move 8, then trimPartial drops the trailing ASCEND_PLACE: 7 walks, past 5 blocks.
        PathPlan cut = UnifiedPathFinder.scaffoldTruncated(ctx, moves, 8, 9, 0);
        assertEquals(PlanStatus.PARTIAL, cut.status());
        assertEquals(NoPathReason.NO_SCAFFOLD, cut.reason());
        assertEquals(7, cut.moves().size());
        assertEquals(7 * 4.6, cut.totalTicks(), 1e-9);
        // A cut that ends within 5 blocks of the start is NO_PATH(NO_SCAFFOLD).
        PathPlan none = UnifiedPathFinder.scaffoldTruncated(ctx, moves, 3, 9, 0);
        assertEquals(PlanStatus.NO_PATH, none.status());
        assertEquals(NoPathReason.NO_SCAFFOLD, none.reason());
    }

    @Test
    void unloadedStartAndUnloadedGoal() {
        VoxelGrid g = flat(10, 5);
        PathPlan start = UnifiedPathFinder.search(SearchContext.builder(g, -3, 1, 2, 5, 1, 2).build());
        assertEquals(NoPathReason.UNLOADED, start.reason());
        // Goal past the loaded edge: head toward it, end on a partial with reason UNLOADED.
        PathPlan goal = UnifiedPathFinder.search(SearchContext.builder(g, 1, 1, 2, 30, 1, 2).build(), () -> 0L);
        assertEquals(PlanStatus.PARTIAL, goal.status());
        assertEquals(NoPathReason.UNLOADED, goal.reason());
        assertEquals(9, goal.endX());
    }

    @Test
    void forbiddenFirstHopIsAvoided() {
        VoxelGrid g = flat(10, 5);
        SearchContext ctx = SearchContext.builder(g, 1, 1, 2, 8, 1, 2)
                .forbiddenFirstHops(Set.of(CellPos.pack(2, 1, 2))).build();
        PathPlan plan = UnifiedPathFinder.search(ctx);
        assertEquals(PlanStatus.FULL, plan.status());
        PlannedMove first = plan.moves().get(0);
        assertNotEquals(CellPos.pack(2, 1, 2), first.toPacked());
        // only the first hop is forbidden: later moves may cross that cell
        SearchContext later = SearchContext.builder(g, 0, 1, 2, 8, 1, 2)
                .forbiddenFirstHops(Set.of(CellPos.pack(2, 1, 2))).build();
        assertTrue(UnifiedPathFinder.search(later).moves().stream().anyMatch(m -> m.toPacked() == CellPos.pack(2, 1, 2)));
    }

    @Test
    void hazardPenaltySteersAroundAndAnchorCannotGoBelowBase() {
        VoxelGrid g = flat(12, 7);
        long hot = CellPos.pack(6, 1, 3);
        SearchContext ctx = SearchContext.builder(g, 1, 1, 3, 10, 1, 3)
                .hazardPenaltyTicks(c -> c == hot ? 12 * ActionCosts.STEP_TICKS : 0).build();
        PathPlan plan = UnifiedPathFinder.search(ctx);
        assertTrue(plan.moves().stream().noneMatch(m -> m.toPacked() == hot));
        SearchContext anchored = SearchContext.builder(g, 1, 1, 3, 10, 1, 3).anchorBonusTicks(c -> 100.0).build();
        PathPlan a = UnifiedPathFinder.search(anchored);
        for (PlannedMove m : a.moves()) {
            assertTrue(m.ticks() >= MoveCosts.minHorizontal(anchored) - 1e-9);
        }
    }

    @Test
    void scaffoldMakesTheDifferenceBetweenNoScaffoldAndAStair() {
        // A 3-long trench walled 2 high, nothing breakable (break off), goal up on the rim.
        VoxelGrid g = new VoxelGrid(0, 0, 0, 7, 8, 5, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 6, 2, 4, 'd');
        g.fill(2, 1, 2, 4, 2, 2, '.');
        SearchContext.Builder b = SearchContext.builder(g, 2, 1, 2, 5, 3, 2).allowPlace(true);
        PathPlan none = UnifiedPathFinder.search(b.scaffoldCount(0).build());
        assertEquals(PlanStatus.NO_PATH, none.status());
        assertEquals(NoPathReason.NO_SCAFFOLD, none.reason());
        PathPlan one = UnifiedPathFinder.search(b.scaffoldCount(1).build());
        assertEquals(PlanStatus.FULL, one.status());
        assertEquals(1, one.moves().stream().filter(m -> m.kind() == MoveKind.ASCEND_PLACE).count());
    }

    @Test
    void unbreakableWallsGiveBlockedUnbreakable() {
        VoxelGrid g = new VoxelGrid(0, 0, 0, 7, 8, 5, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 6, 2, 4, '#');
        g.fill(2, 1, 2, 4, 2, 2, '.');
        PathPlan plan = UnifiedPathFinder.search(SearchContext.builder(g, 2, 1, 2, 5, 3, 2).allowBreak(true).build());
        assertEquals(NoPathReason.BLOCKED_UNBREAKABLE, plan.reason());
    }

    @Test
    void downwardOnlyWhenAllowed() {
        // A sealed 2-high pocket at (2,5..6,1) under a dirt cap at y7; the start stands on top at (1,8,1).
        VoxelGrid g = new VoxelGrid(0, 0, 0, 4, 10, 3, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 3, 7, 2, 'd');
        g.fill(2, 5, 1, 2, 6, 1, '.');
        SearchContext.Builder b = SearchContext.builder(g, 1, 8, 1, 2, 5, 1).allowBreak(true)
                .box(new SearchBox(0, 0, 0, 3, 9, 2));
        assertNotEquals(PlanStatus.FULL, UnifiedPathFinder.search(b.build(), () -> 0L).status());
        PathPlan dig = UnifiedPathFinder.search(b.allowDownward(true).build());
        assertEquals(PlanStatus.FULL, dig.status());
        assertEquals(3, dig.moves().stream().filter(m -> m.kind() == MoveKind.DOWNWARD).count());
        assertEquals(MoveKind.TRAVERSE, dig.moves().get(dig.moves().size() - 1).kind());
    }

    @Test
    void swimRunIsCappedByMaxSwimRun() {
        // 6-wide deep pond between two banks.
        VoxelGrid g = new VoxelGrid(0, 0, 0, 12, 6, 3, VoxelGrid.ToolTable.HAND);
        g.fill(0, 0, 0, 11, 2, 2, 'd');
        g.fill(3, 1, 0, 8, 2, 2, '~');
        g.fill(3, 0, 0, 8, 0, 2, '~');
        SearchContext.Builder b = SearchContext.builder(g, 1, 3, 1, 10, 3, 1).allowSwim(true);
        PathPlan ok = UnifiedPathFinder.search(b.maxSwimRun(10).build());
        assertEquals(PlanStatus.FULL, ok.status());
        assertTrue(ok.moves().stream().anyMatch(m -> m.kind() == MoveKind.SWIM));
        PathPlan capped = UnifiedPathFinder.search(b.maxSwimRun(4).build(), () -> 0L);
        assertNotEquals(PlanStatus.FULL, capped.status());
        PathPlan noSwim = UnifiedPathFinder.search(b.maxSwimRun(10).allowSwim(false).build(), () -> 0L);
        assertNotEquals(PlanStatus.FULL, noSwim.status());
    }

    @Test
    void partialTrimCutsTailAndNeverEndsInWaterOrOnAnAction() {
        List<PlannedMove> moves = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            moves.add(new PlannedMove(MoveKind.TRAVERSE, i, 1, 0, i + 1, 1, 0, null, null, 3.5, 0, 0));
        }
        assertEquals(36, UnifiedPathFinder.trimPartial(moves).size());
        List<PlannedMove> shortOne = new ArrayList<>(moves.subList(0, 5));
        shortOne.add(new PlannedMove(MoveKind.SWIM, 5, 1, 0, 6, 0, 0, null, null, 19, 0, 1));
        shortOne.add(new PlannedMove(MoveKind.TRAVERSE, 6, 0, 0, 7, 0, 0, new long[]{CellPos.pack(7, 1, 0)}, null, 20, 0, 0));
        assertEquals(5, UnifiedPathFinder.trimPartial(shortOne).size());
    }

    @Test
    void reasonPrecedence() {
        SearchContext ctx = SearchContext.builder(flat(3, 3), 1, 1, 1, 1, 1, 1).build();
        MoveCosts.Refusals r = new MoveCosts.Refusals();
        assertEquals(NoPathReason.BOXED_IN, UnifiedPathFinder.reason(ctx, r, 1, false, true, true));
        assertEquals(NoPathReason.BOXED_IN, UnifiedPathFinder.reason(ctx, r, 9, false, true, true));
        assertEquals(NoPathReason.BUDGET, UnifiedPathFinder.reason(ctx, r, 9, true, true, true));
        r.unbreakable = 3;
        assertEquals(NoPathReason.BOXED_IN, UnifiedPathFinder.reason(ctx, r, 1, false, true, true));
        assertEquals(NoPathReason.BLOCKED_UNBREAKABLE, UnifiedPathFinder.reason(ctx, r, 9, false, true, true));
        r.protectedCells = 3;
        assertEquals(NoPathReason.BLOCKED_PROTECTED, UnifiedPathFinder.reason(ctx, r, 1, false, true, true));
        assertEquals(NoPathReason.BOXED_IN, UnifiedPathFinder.reason(ctx, r, 1, false, true, false), "goal outside box");
        r.unloaded = 1;
        assertEquals(NoPathReason.UNLOADED, UnifiedPathFinder.reason(ctx, r, 9, false, false, true));
        MoveCosts.Refusals s = new MoveCosts.Refusals();
        s.noScaffold = 2;
        assertEquals(NoPathReason.NO_SCAFFOLD, UnifiedPathFinder.reason(ctx, s, 9, false, true, true));
    }

    @Test
    void searchBoxKeysAreRelativeAndDistinctAcrossTallWorlds() {
        SearchBox box = new SearchBox(-100, -60, -100, 100, 196, 100);
        Set<Long> keys = new HashSet<>();
        for (int y = -60; y <= 196; y += 4) {
            for (int sw = 0; sw < 16; sw += 5) {
                assertTrue(keys.add(box.key(-100, y, 100, sw)));
                assertTrue(keys.add(box.key(100, y, -100, sw)));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new SearchBox(0, -64, 0, 1, 448, 1));
        assertThrows(IllegalArgumentException.class, () -> new SearchBox(0, 0, 0, 1 << 25, 1, 1));
        SearchBox around = SearchBox.around(0, 70, 0, 10, 64, 0, -64, 319);
        assertEquals(62, around.minY());
        assertEquals(86, around.maxY());
        assertEquals(-24, around.minX());
        assertEquals(48, SearchBox.around(0, 70, 0, 61, 70, 0, -64, 319).minX() * -1);
    }

    @Test
    void defaultNodeCapAndBuilderDefaults() {
        assertEquals(1000, SearchContext.defaultNodeCap(0, 0, 0, 3, 0, 0));
        assertEquals(1600, SearchContext.defaultNodeCap(0, 0, 0, 20, 0, 0));
        assertEquals(8000, SearchContext.defaultNodeCap(0, 0, 0, 200, 0, 0));
        SearchContext ctx = SearchContext.builder(flat(3, 3), 1, 1, 1, 2, 1, 1).build();
        assertTrue(ctx.allowSwim());
        assertFalse(ctx.allowBreak() || ctx.allowPlace() || ctx.allowDownward());
        assertEquals(100, ctx.maxBreakTicks());
        assertEquals(3, ctx.maxFall());
        assertEquals(10, ctx.maxSwimRun());
        assertThrows(IllegalArgumentException.class, () -> SearchContext.builder(flat(3, 3), 1, 1, 1, 2, 1, 1).maxSwimRun(16).build());
    }

    @Test
    void planIdsAreTokens() {
        PathPlanIds.reset();
        long a = PathPlanIds.next();
        long b = PathPlanIds.next();
        assertEquals(a + 1, b);
        assertEquals("p" + b, PathPlanIds.format(b));
        assertFalse(PathPlanIds.format(b).contains(" "));
    }

    @Test
    void reentrantSearchOnOneThreadUsesFreshBuffers() {
        VoxelGrid g = flat(10, 5);
        SearchContext outer = SearchContext.builder(g, 1, 1, 2, 8, 1, 2).build();
        PathPlan[] inner = new PathPlan[1];
        TerrainView reentrant = new TerrainView() {
            @Override
            public int props(int x, int y, int z) {
                if (inner[0] == null) {
                    inner[0] = UnifiedPathFinder.search(SearchContext.builder(g, 1, 1, 1, 8, 1, 1).build());
                }
                return g.props(x, y, z);
            }

            @Override
            public int breakTicks(int x, int y, int z) {
                return g.breakTicks(x, y, z);
            }

            @Override
            public int minY() {
                return g.minY();
            }

            @Override
            public int maxY() {
                return g.maxY();
            }
        };
        PathPlan plan = UnifiedPathFinder.search(outer.toBuilder().terrain(reentrant).build());
        assertEquals(PlanStatus.FULL, plan.status());
        assertEquals(PlanStatus.FULL, inner[0].status());
    }

    @Test
    void noNetMinecraftImportsInThePureCore() throws IOException {
        Path pkg = Path.of("src/main/java/net/wcfcarolina13/PathFinding/unified");
        assertTrue(Files.isDirectory(pkg), "run from the project root");
        Set<String> exempt = Set.of("WorldTerrainView.java", "PlanAdapter.java");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(pkg)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        files.add(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/NavLegPolicy.java"));
        assertTrue(files.size() > 15);
        for (Path p : files) {
            if (exempt.contains(p.getFileName().toString())) {
                continue;
            }
            String src = Files.readString(p);
            assertFalse(src.contains("import net.minecraft"), p + " imports net.minecraft");
            assertFalse(java.util.regex.Pattern.compile("net\\.minecraft\\.[a-z]").matcher(src).find(), p + " names a net.minecraft type");
        }
    }

    @Test
    void cellPropsHelpers() {
        assertTrue(CellProps.passable(CellProps.LOADED | CellProps.PASSABLE_BODY));
        assertFalse(CellProps.passable(CellProps.PASSABLE_BODY));
        assertFalse(CellProps.passable(CellProps.LOADED | CellProps.PASSABLE_BODY | CellProps.LOCKED_DOOR));
        assertEquals("UNLOADED", CellProps.describe(0));
        assertEquals("SOLID_FLOOR|LOADED", CellProps.describe(CellProps.SOLID_FLOOR | CellProps.LOADED));
        assertNull(UnifiedPathFinder.snapGoal(SearchContext.builder(flat(3, 3), 1, 1, 1, 1, 4, 1)
                .box(new SearchBox(0, 0, 0, 2, 5, 2)).build()));
        assertArrayEquals(new int[]{1, 1, 1}, UnifiedPathFinder.snapGoal(SearchContext.builder(flat(3, 3), 1, 1, 1, 1, 1, 1).build()));
    }
}
