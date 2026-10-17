package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.DigDownPolicy.Cell;
import net.wcfcarolina13.GameAI.services.DigDownPolicy.Mark;
import net.wcfcarolina13.GameAI.services.DigDownPolicy.Outcome;
import net.wcfcarolina13.GameAI.services.DigDownPolicy.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigDownPolicyTest {

    private static final List<Cell> SOLID3 = List.of(Cell.DIGGABLE, Cell.DIGGABLE, Cell.DIGGABLE);
    private static final String OW = "minecraft:overworld";

    @Test
    void preflightPassesWithSealBlocksAndASolidColumn() {
        assertNull(DigDownPolicy.preflight(false, SOLID3, true, 1, "minecraft:stone"));
    }

    @Test
    void preflightPassesWithNoSealBlockWhenTheTopCellDropsOneByHand() {
        assertNull(DigDownPolicy.preflight(false, SOLID3, true, 0, "minecraft:grass_block"));
        assertNull(DigDownPolicy.preflight(false, SOLID3, true, 0, "minecraft:dirt"));
        assertNull(DigDownPolicy.preflight(false, SOLID3, true, 0, "minecraft:sand"));
        assertNull(DigDownPolicy.preflight(false, SOLID3, true, 0, "minecraft:gravel"));
    }

    @Test
    void preflightSkipsWithNoSealBlockOnStone() {
        assertEquals("no-seal-block", DigDownPolicy.preflight(false, SOLID3, true, 0, "minecraft:stone"));
        assertEquals("no-seal-block", DigDownPolicy.preflight(false, SOLID3, true, 0, null));
    }

    @Test
    void preflightNamesTheFirstBadCell() {
        assertEquals("cell1-air", DigDownPolicy.preflight(false,
                List.of(Cell.AIR, Cell.DIGGABLE, Cell.DIGGABLE), true, 5, "minecraft:dirt"));
        assertEquals("cell2-fluid", DigDownPolicy.preflight(false,
                List.of(Cell.DIGGABLE, Cell.FLUID, Cell.PROTECTED), true, 5, "minecraft:dirt"));
        assertEquals("cell3-protected", DigDownPolicy.preflight(false,
                List.of(Cell.DIGGABLE, Cell.DIGGABLE, Cell.PROTECTED), true, 5, "minecraft:dirt"));
        assertEquals("cell3-unbreakable", DigDownPolicy.preflight(false,
                List.of(Cell.DIGGABLE, Cell.DIGGABLE, Cell.UNBREAKABLE), true, 5, "minecraft:dirt"));
    }

    @Test
    void preflightNeedsAFloorUnderTheThirdCell() {
        assertEquals("no-floor", DigDownPolicy.preflight(false, SOLID3, false, 5, "minecraft:dirt"));
    }

    @Test
    void preflightMarkWinsAndBadSnapshotIsRefused() {
        assertEquals("column-marked", DigDownPolicy.preflight(true, SOLID3, true, 5, "minecraft:dirt"));
        assertEquals("no-snapshot", DigDownPolicy.preflight(false, List.of(Cell.DIGGABLE), true, 5, "minecraft:dirt"));
        assertEquals("no-snapshot", DigDownPolicy.preflight(false, null, true, 5, "minecraft:dirt"));
    }

    @Test
    void afterMineStopsOnTheFirstUnopenedCell() {
        assertEquals(Step.STOP_NOTHING_DUG, DigDownPolicy.afterMine(0, false));
        assertEquals(Step.CONTINUE, DigDownPolicy.afterMine(0, true));
        assertEquals(Step.STOP_PARTIAL, DigDownPolicy.afterMine(1, false));
        assertEquals(Step.CONTINUE, DigDownPolicy.afterMine(1, true));
        assertEquals(Step.DONE, DigDownPolicy.afterMine(2, true));
        assertEquals(Step.STOP_PARTIAL, DigDownPolicy.afterMine(2, false));
    }

    @Test
    void capOnlyFromTwoOrThreeDeepInTheColumn() {
        assertTrue(DigDownPolicy.capAllowed(5, 61, 5, 5, 64, 5));   // 3 deep: cap flush with the ground
        assertTrue(DigDownPolicy.capAllowed(5, 62, 5, 5, 64, 5));   // 2 deep: head walled by the ground
        assertFalse(DigDownPolicy.capAllowed(5, 63, 5, 5, 64, 5));  // 1 deep: head open at ground level
        assertFalse(DigDownPolicy.capAllowed(5, 64, 5, 5, 64, 5));  // never fell in
        assertFalse(DigDownPolicy.capAllowed(6, 61, 5, 5, 64, 5));  // beside the column
    }

    @Test
    void afterCapClimbsOutOnlyAfterARealDig() {
        assertEquals(Outcome.SHELTER, DigDownPolicy.afterCap(3, true));
        assertEquals(Outcome.CLIMB_OUT, DigDownPolicy.afterCap(3, false));
        assertEquals(Outcome.CLIMB_OUT, DigDownPolicy.afterCap(1, false));
        assertEquals(Outcome.NOTHING, DigDownPolicy.afterCap(0, false));
        assertEquals(Outcome.NOTHING, DigDownPolicy.afterCap(0, true));
    }

    @Test
    void climbStepsRefillTheDepthWhenScaffoldAllows() {
        assertEquals(3, DigDownPolicy.climbSteps(3, 10));
        assertEquals(2, DigDownPolicy.climbSteps(3, 2));
        assertEquals(0, DigDownPolicy.climbSteps(3, 0));
        assertEquals(0, DigDownPolicy.climbSteps(0, 5));
    }

    @Test
    void climbedOutAtOrAboveGround() {
        assertTrue(DigDownPolicy.climbedOut(64, 64));
        assertTrue(DigDownPolicy.climbedOut(65, 64));
        assertFalse(DigDownPolicy.climbedOut(63, 64));
    }

    @Test
    void gameDayTurnsAtDawn() {
        assertEquals(0, DigDownPolicy.gameDay(13_000));
        assertEquals(0, DigDownPolicy.gameDay(23_999));
        assertEquals(1, DigDownPolicy.gameDay(24_000));
        assertEquals(5, DigDownPolicy.gameDay(5 * 24_000 + 18_000));
    }

    @Test
    void markCoversTheColumnAndTwoAround() {
        Mark mark = new Mark(OW, 10, -4, 3);
        assertTrue(DigDownPolicy.covers(mark, OW, 10, -4));
        assertTrue(DigDownPolicy.covers(mark, OW, 12, -2));
        assertTrue(DigDownPolicy.covers(mark, OW, 8, -6));
        assertFalse(DigDownPolicy.covers(mark, OW, 13, -4));
        assertFalse(DigDownPolicy.covers(mark, OW, 10, -7));
        assertFalse(DigDownPolicy.covers(mark, "minecraft:the_nether", 10, -4));
    }

    @Test
    void markLastsUntilTheNextDawn() {
        Mark mark = new Mark(OW, 0, 0, 3);
        assertFalse(DigDownPolicy.expired(mark, 3));
        assertTrue(DigDownPolicy.expired(mark, 4));
        assertTrue(DigDownPolicy.isMarked(List.of(mark), OW, 1, 1, 3));
        assertFalse(DigDownPolicy.isMarked(List.of(mark), OW, 1, 1, 4));
        assertFalse(DigDownPolicy.isMarked(null, OW, 0, 0, 3));
    }

    @Test
    void withMarkDropsExpiredMarks() {
        Mark old = new Mark(OW, 0, 0, 1);
        Mark kept = new Mark(OW, 50, 50, 3);
        Mark added = new Mark(OW, 9, 9, 3);
        assertEquals(List.of(kept, added), DigDownPolicy.withMark(List.of(old, kept), added, 3));
        assertEquals(List.of(added), DigDownPolicy.withMark(null, added, 3));
    }
}
