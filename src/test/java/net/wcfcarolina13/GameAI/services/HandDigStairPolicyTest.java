package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.HandDigStairPolicy.Cell;
import net.wcfcarolina13.GameAI.services.HandDigStairPolicy.CellView;
import net.wcfcarolina13.GameAI.services.HandDigStairPolicy.Decision;
import net.wcfcarolina13.GameAI.services.HandDigStairPolicy.Dir;
import net.wcfcarolina13.GameAI.services.HandDigStairPolicy.Offset;
import net.wcfcarolina13.GameAI.services.HandDigStairPolicy.Refusal;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandDigStairPolicyTest {

    private static final int STONE_BY_HAND = ObstructionMinePolicy.groundBreakTicks(1.0f, 1.5f,
            ObstructionMinePolicy.canHarvest(true, false));
    private static final int DEEPSLATE_BY_HAND = ObstructionMinePolicy.groundBreakTicks(1.0f, 3.0f,
            ObstructionMinePolicy.canHarvest(true, false));
    private static final int DIRT_BY_HAND = ObstructionMinePolicy.groundBreakTicks(1.0f, 0.5f,
            ObstructionMinePolicy.canHarvest(false, false));
    private static final int STONE_WOOD_PICK = ObstructionMinePolicy.groundBreakTicks(2.0f, 1.5f,
            ObstructionMinePolicy.canHarvest(true, true));

    /** A pit: everything solid with {@code fill} ticks except the bot's two body cells. */
    private static final class Grid implements CellView {
        final Map<Offset, Cell> cells = new HashMap<>();
        final Cell fill;

        Grid(Cell fill) {
            this.fill = fill;
            set(0, 0, 0, Cell.air());
            set(0, 1, 0, Cell.air());
        }

        Grid set(int dx, int dy, int dz, Cell c) {
            cells.put(new Offset(dx, dy, dz), c);
            return this;
        }

        @Override
        public Cell at(int dx, int dy, int dz) {
            return cells.getOrDefault(new Offset(dx, dy, dz), fill);
        }
    }

    private static Grid stonePit(int ticks) {
        return new Grid(Cell.solid(ticks));
    }

    @Test
    void handTimesMatchWiki() {
        // minecraft.wiki/w/Breaking: hardness x5 seconds without the right tool, x1.5 with it.
        assertEquals(150, STONE_BY_HAND);
        assertEquals(300, DEEPSLATE_BY_HAND);
        assertEquals(15, DIRT_BY_HAND);
    }

    @Test
    void stonePitByHandPlansThreeCellsInPreferredDirection() {
        Decision d = HandDigStairPolicy.decide(stonePit(STONE_BY_HAND), List.of(Dir.EAST), 0);
        assertTrue(d.ok());
        assertEquals(Dir.EAST, d.plan().dir());
        assertEquals(List.of(new Offset(0, 2, 0), new Offset(1, 2, 0), new Offset(1, 1, 0)),
                d.plan().cellsToMine());
        assertEquals(3L * STONE_BY_HAND, d.plan().breakTicks());
        assertEquals(new Offset(1, 1, 0), d.plan().standAt());
    }

    @Test
    void miningOrderIsTopDownPerColumn() {
        List<Offset> cut = HandDigStairPolicy.cutCells(Dir.NORTH);
        assertEquals(new Offset(0, 2, 0), cut.get(0));
        assertEquals(new Offset(0, 2, -1), cut.get(1));
        assertEquals(new Offset(0, 1, -1), cut.get(2));
    }

    @Test
    void naturalStepNeedsNoMining() {
        Grid g = stonePit(STONE_BY_HAND)
                .set(0, 2, 0, Cell.air()).set(1, 1, 0, Cell.air()).set(1, 2, 0, Cell.air());
        Decision d = HandDigStairPolicy.decide(g, List.of(Dir.EAST), 0);
        assertTrue(d.ok());
        assertTrue(d.plan().cellsToMine().isEmpty());
        assertEquals(0L, d.plan().breakTicks());
    }

    @Test
    void airCutCellsAreSkipped() {
        Grid g = stonePit(DIRT_BY_HAND).set(0, 2, 0, Cell.air());
        Decision d = HandDigStairPolicy.decide(g, List.of(Dir.SOUTH), 0);
        assertEquals(List.of(new Offset(0, 2, 1), new Offset(0, 1, 1)), d.plan().cellsToMine());
    }

    @Test
    void lavaBehindTheFaceRefusesThatDirectionOnly() {
        Grid g = stonePit(STONE_BY_HAND).set(2, 1, 0, Cell.lava());
        Decision d = HandDigStairPolicy.decide(g, List.of(Dir.EAST), 0);
        assertTrue(d.ok());
        assertTrue(d.plan().dir() != Dir.EAST);
        assertEquals(Refusal.LAVA_EXPOSED, d.perDirection().get(Dir.EAST));
    }

    @Test
    void lavaAboveOwnOverheadRefusesEverything() {
        Grid g = stonePit(STONE_BY_HAND).set(0, 3, 0, Cell.lava());
        Decision d = HandDigStairPolicy.decide(g, List.of(Dir.EAST), 0);
        assertFalse(d.ok());
        assertEquals(Refusal.LAVA_EXPOSED, d.refusal());
        assertEquals(4, d.perDirection().size());
    }

    @Test
    void lavaPitOnAllSidesRefusesAsLava() {
        Grid g = stonePit(STONE_BY_HAND);
        for (Dir dir : Dir.values()) {
            g.set(dir.dx * 2, 2, dir.dz * 2, Cell.lava());
        }
        Decision d = HandDigStairPolicy.decide(g, List.of(), 0);
        assertFalse(d.ok());
        assertEquals(Refusal.LAVA_EXPOSED, d.refusal());
    }

    @Test
    void lavaTreadRefusesAsLava() {
        Grid g = stonePit(STONE_BY_HAND).set(1, 0, 0, Cell.lava());
        Refusal[] out = new Refusal[1];
        assertNull(HandDigStairPolicy.planFor(g, Dir.EAST, out));
        assertEquals(Refusal.LAVA_EXPOSED, out[0]);
    }

    @Test
    void waterExposedOrInCutRefuses() {
        Grid side = stonePit(STONE_BY_HAND).set(1, 2, 1, Cell.water());
        Refusal[] out = new Refusal[1];
        assertNull(HandDigStairPolicy.planFor(side, Dir.EAST, out));
        assertEquals(Refusal.WATER_EXPOSED, out[0]);

        Grid inCut = stonePit(STONE_BY_HAND).set(1, 1, 0, Cell.water());
        assertNull(HandDigStairPolicy.planFor(inCut, Dir.EAST, out));
        assertEquals(Refusal.WATER_EXPOSED, out[0]);
    }

    @Test
    void lavaOutranksWaterWhenEveryDirectionRefuses() {
        Grid g = stonePit(STONE_BY_HAND).set(0, 3, 0, Cell.water()).set(1, 3, 0, Cell.lava());
        Decision d = HandDigStairPolicy.decide(g, List.of(Dir.EAST), 0);
        assertFalse(d.ok());
        assertEquals(Refusal.LAVA_EXPOSED, d.refusal());
    }

    @Test
    void fallingBlockAboveTheFrontCutRefuses() {
        Grid g = stonePit(STONE_BY_HAND).set(1, 3, 0, Cell.falling(DIRT_BY_HAND));
        Refusal[] out = new Refusal[1];
        assertNull(HandDigStairPolicy.planFor(g, Dir.EAST, out));
        assertEquals(Refusal.FALLING_ABOVE, out[0]);
        Decision d = HandDigStairPolicy.decide(g, List.of(Dir.EAST), 0);
        assertTrue(d.ok());
        assertTrue(d.plan().dir() != Dir.EAST);
    }

    @Test
    void gravelOverheadRefusesAllDirections() {
        Grid g = stonePit(STONE_BY_HAND).set(0, 3, 0, Cell.falling(DIRT_BY_HAND));
        Decision d = HandDigStairPolicy.decide(g, List.of(), 0);
        assertFalse(d.ok());
        assertEquals(Refusal.FALLING_ABOVE, d.refusal());
    }

    @Test
    void fallingCutCellUnderAnotherCutCellIsFine() {
        // Gravel at the front head cell: the cell above it is cut first, so nothing falls in.
        Grid g = stonePit(STONE_BY_HAND).set(1, 1, 0, Cell.falling(DIRT_BY_HAND));
        Decision d = HandDigStairPolicy.decide(g, List.of(Dir.EAST), 0);
        assertTrue(d.ok());
        assertEquals(Dir.EAST, d.plan().dir());
    }

    @Test
    void fallingTreadIsAStep() {
        Grid g = stonePit(STONE_BY_HAND).set(1, 0, 0, Cell.falling(DIRT_BY_HAND));
        assertTrue(HandDigStairPolicy.decide(g, List.of(Dir.EAST), 0).ok());
    }

    @Test
    void bedrockOrObsidianInTheCutRefuses() {
        Grid bedrock = new Grid(Cell.unbreakable());
        Decision d = HandDigStairPolicy.decide(bedrock, List.of(), 0);
        assertFalse(d.ok());
        assertEquals(Refusal.UNBREAKABLE, d.refusal());

        int obsidianByHand = ObstructionMinePolicy.groundBreakTicks(1.0f, 50.0f,
                ObstructionMinePolicy.canHarvest(true, false));
        Decision o = HandDigStairPolicy.decide(stonePit(obsidianByHand), List.of(), 0);
        assertFalse(o.ok());
        assertEquals(Refusal.TOO_SLOW, o.refusal());
    }

    @Test
    void deepslateByHandIsTooSlowButAWoodPickPasses() {
        assertEquals(Refusal.TOO_SLOW, HandDigStairPolicy.decide(stonePit(DEEPSLATE_BY_HAND), List.of(), 0).refusal());
        assertTrue(HandDigStairPolicy.decide(stonePit(STONE_WOOD_PICK), List.of(), 0).ok());
    }

    @Test
    void unbreakableOutranksTooSlow() {
        Grid g = stonePit(DEEPSLATE_BY_HAND).set(0, 2, 0, Cell.unbreakable());
        assertEquals(Refusal.UNBREAKABLE, HandDigStairPolicy.decide(g, List.of(), 0).refusal());
    }

    @Test
    void openFloorHasNoTread() {
        Grid g = new Grid(Cell.air());
        Decision d = HandDigStairPolicy.decide(g, List.of(), 0);
        assertFalse(d.ok());
        assertEquals(Refusal.NO_TREAD, d.refusal());
    }

    @Test
    void cannotStepBackDownTheStairWayItCame() {
        // After a step east, the west cell at feet level is the old head cell: air, no tread.
        Grid g = stonePit(STONE_BY_HAND).set(-1, 0, 0, Cell.air()).set(-1, 1, 0, Cell.air());
        Refusal[] out = new Refusal[1];
        assertNull(HandDigStairPolicy.planFor(g, Dir.WEST, out));
        assertEquals(Refusal.NO_TREAD, out[0]);
    }

    @Test
    void cheapestValidDirectionWinsWhenPreferredRefuses() {
        Grid g = stonePit(STONE_BY_HAND)
                .set(1, 3, 0, Cell.falling(DIRT_BY_HAND))      // EAST refused
                .set(0, 2, 1, Cell.solid(DIRT_BY_HAND))         // SOUTH cheaper
                .set(0, 1, 1, Cell.solid(DIRT_BY_HAND));
        Decision d = HandDigStairPolicy.decide(g, List.of(Dir.EAST), 0);
        assertEquals(Dir.SOUTH, d.plan().dir());
    }

    @Test
    void preferredValidDirectionWinsEvenIfAnotherIsCheaper() {
        Grid g = stonePit(STONE_BY_HAND).set(0, 2, 1, Cell.air()).set(0, 1, 1, Cell.air());
        Decision d = HandDigStairPolicy.decide(g, List.of(Dir.EAST), 0);
        assertEquals(Dir.EAST, d.plan().dir());
    }

    @Test
    void tieWithoutPreferenceGoesInDirOrder() {
        Decision d = HandDigStairPolicy.decide(stonePit(STONE_BY_HAND), null, 0);
        assertEquals(Dir.NORTH, d.plan().dir());
    }

    @Test
    void stepLimitRefuses() {
        Decision d = HandDigStairPolicy.decide(stonePit(DIRT_BY_HAND), List.of(Dir.EAST), HandDigStairPolicy.MAX_STEPS);
        assertFalse(d.ok());
        assertEquals(Refusal.STEP_LIMIT, d.refusal());
        assertTrue(HandDigStairPolicy.decide(stonePit(DIRT_BY_HAND), List.of(Dir.EAST),
                HandDigStairPolicy.MAX_STEPS - 1).ok());
    }

    @Test
    void appliesOnlyWithNoBlocksAndStepsLeft() {
        assertTrue(HandDigStairPolicy.applies(0, 0));
        assertFalse(HandDigStairPolicy.applies(1, 0));
        assertFalse(HandDigStairPolicy.applies(0, HandDigStairPolicy.MAX_STEPS));
    }

    @Test
    void threeDeepStonePitClimbsOutInThreeSimulatedSteps() {
        // Simulate: a 3-deep shaft at x=0 in stone, surface at dy=3. Each step mines its cells and
        // moves the feet; the preferred direction stays EAST (a straight staircase).
        Map<Offset, Cell> world = new HashMap<>();
        for (int y = 0; y < 3; y++) {
            world.put(new Offset(0, y, 0), Cell.air());
        }
        int feetX = 0;
        int feetY = 0;
        for (int step = 0; step < 3; step++) {
            final int fx = feetX;
            final int fy = feetY;
            CellView view = (dx, dy, dz) -> {
                int y = fy + dy;
                if (y >= 3) {
                    return Cell.air();
                }
                return world.getOrDefault(new Offset(fx + dx, y, dz), Cell.solid(STONE_BY_HAND));
            };
            Decision d = HandDigStairPolicy.decide(view, List.of(Dir.EAST), step);
            assertTrue(d.ok(), "step " + step + " refused: " + d.refusal());
            assertEquals(Dir.EAST, d.plan().dir());
            for (Offset c : d.plan().cellsToMine()) {
                world.put(new Offset(fx + c.dx(), fy + c.dy(), c.dz()), Cell.air());
            }
            feetX += d.plan().standAt().dx();
            feetY += d.plan().standAt().dy();
        }
        assertEquals(3, feetY);
        assertEquals(3, feetX);
    }
}
