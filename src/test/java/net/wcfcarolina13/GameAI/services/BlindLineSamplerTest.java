package net.wcfcarolina13.GameAI.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.wcfcarolina13.GameAI.services.BlindLineSampler.Cell;
import net.wcfcarolina13.GameAI.services.BlindLineSampler.LocalPoint;
import net.wcfcarolina13.GameAI.services.BlindLineSampler.ShapeBox;
import org.junit.jupiter.api.Test;

class BlindLineSamplerTest {

    private static final int MAX = 16;

    private static Cell last(List<Cell> cells) {
        return cells.get(cells.size() - 1);
    }

    // --- step count and clamped sample distance ---

    @Test
    void stepCountIsCeilCappedAtMax() {
        assertEquals(0, BlindLineSampler.stepCount(0.0, MAX));
        assertEquals(0, BlindLineSampler.stepCount(-1.0, MAX));
        assertEquals(0, BlindLineSampler.stepCount(Double.NaN, MAX));
        assertEquals(0, BlindLineSampler.stepCount(5.0, 0));
        assertEquals(1, BlindLineSampler.stepCount(0.3, MAX));
        assertEquals(1, BlindLineSampler.stepCount(1.0, MAX));
        assertEquals(2, BlindLineSampler.stepCount(1.2, MAX));
        assertEquals(3, BlindLineSampler.stepCount(3.0, MAX));
        assertEquals(4, BlindLineSampler.stepCount(3.2, MAX));
        assertEquals(MAX, BlindLineSampler.stepCount(40.0, MAX));
    }

    @Test
    void sampleDistanceNeverPassesTheDestinationCentre() {
        assertEquals(1.0, BlindLineSampler.sampleDistance(1, 3.2));
        assertEquals(3.0, BlindLineSampler.sampleDistance(3, 3.2));
        assertEquals(3.2, BlindLineSampler.sampleDistance(4, 3.2));
        assertEquals(0.4, BlindLineSampler.sampleDistance(1, 0.4));
    }

    // --- destinations on a lip 1, 2 and 3 cells away: the lip is never sampled ---

    @Test
    void destinationOneCellAwayOnALipSamplesOnlyThatCell() {
        // Bot at x=10.3 (cell 10), goal cell 11; the abyss is cell 12+. horizontal = 1.2, ceil = 2.
        List<Cell> cells = BlindLineSampler.cells(10.3, 20.5, 11, 20, MAX);
        assertEquals(List.of(new Cell(11, 20)), cells);
    }

    @Test
    void destinationTwoCellsAwayOnALipEndsAtTheDestination() {
        // horizontal = 2.2, ceil = 3: the old sampler landed its third sample in cell 13.
        List<Cell> cells = BlindLineSampler.cells(10.3, 20.5, 12, 20, MAX);
        assertEquals(List.of(new Cell(11, 20), new Cell(12, 20)), cells);
    }

    @Test
    void destinationThreeCellsAwayOnALipEndsAtTheDestination() {
        // horizontal = 3.2, ceil = 4: the old sampler landed its fourth sample in cell 14.
        List<Cell> cells = BlindLineSampler.cells(10.3, 20.5, 13, 20, MAX);
        assertEquals(List.of(new Cell(11, 20), new Cell(12, 20), new Cell(13, 20)), cells);
    }

    @Test
    void lipBeyondTheDestinationIsNeverSampledForAnyStartOffset() {
        for (int dist = 1; dist <= 12; dist++) {
            for (double frac = 0.0; frac < 1.0; frac += 0.05) {
                double startX = 10.0 + frac;
                int destX = 10 + dist;
                List<Cell> cells = BlindLineSampler.cells(startX, 20.5, destX, 20, MAX);
                assertFalse(cells.isEmpty(), "start " + startX + " dest " + destX);
                assertEquals(new Cell(destX, 20), last(cells), "start " + startX + " dest " + destX);
                for (Cell c : cells) {
                    assertTrue(c.x() > 10 && c.x() <= destX, "cell " + c + " start " + startX + " dest " + destX);
                    assertEquals(20, c.z());
                }
                assertEquals(dist, cells.size(), "start " + startX + " dest " + destX);
            }
        }
    }

    @Test
    void negativeDirectionNeverPassesTheDestination() {
        List<Cell> cells = BlindLineSampler.cells(10.7, 20.5, 8, 20, MAX);
        assertEquals(List.of(new Cell(9, 20), new Cell(8, 20)), cells);
        List<Cell> z = BlindLineSampler.cells(10.5, 20.9, 10, 17, MAX);
        assertEquals(List.of(new Cell(10, 19), new Cell(10, 18), new Cell(10, 17)), z);
    }

    // --- trivial and near cases ---

    @Test
    void sameCellProducesNothing() {
        assertTrue(BlindLineSampler.cells(10.1, 20.9, 10, 20, MAX).isEmpty());
        assertTrue(BlindLineSampler.cells(10.5, 20.5, 10, 20, MAX).isEmpty());
    }

    @Test
    void adjacentCellLessThanOneBlockAwayIsStillSampled() {
        // Bot at 10.9 with the goal centre at 11.5: horizontal = 0.6. The old sampler skipped it.
        assertEquals(List.of(new Cell(11, 20)), BlindLineSampler.cells(10.9, 20.5, 11, 20, MAX));
    }

    @Test
    void maxCellsCapsALongLineShortOfTheDestination() {
        List<Cell> cells = BlindLineSampler.cells(10.5, 20.5, 50, 20, MAX);
        assertEquals(MAX, cells.size());
        assertEquals(new Cell(11, 20), cells.get(0));
        assertEquals(new Cell(26, 20), last(cells));
        assertEquals(List.of(), BlindLineSampler.cells(10.5, 20.5, 50, 20, 0));
    }

    // --- diagonals ---

    @Test
    void diagonalEndsAtTheDestinationWithoutDuplicates() {
        List<Cell> cells = BlindLineSampler.cells(10.5, 20.5, 15, 23, MAX);
        assertEquals(new Cell(15, 23), last(cells));
        for (int i = 1; i < cells.size(); i++) {
            assertFalse(cells.get(i).equals(cells.get(i - 1)), "duplicate at " + i);
        }
        for (Cell c : cells) {
            assertTrue(c.x() >= 10 && c.x() <= 15 && c.z() >= 20 && c.z() <= 23, "cell " + c);
        }
        assertFalse(cells.contains(new Cell(10, 20)));
    }

    @Test
    void diagonalSweepAlwaysEndsAtTheDestinationCell() {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (double frac = 0.05; frac < 1.0; frac += 0.3) {
                    List<Cell> cells = BlindLineSampler.cells(100 + frac, 200 + 1 - frac, 100 + dx, 200 + dz, MAX);
                    assertEquals(new Cell(100 + dx, 200 + dz), last(cells), "dx " + dx + " dz " + dz + " frac " + frac);
                    for (Cell c : cells) {
                        assertTrue(Math.abs(c.x() - 100) <= Math.abs(dx) && Math.abs(c.z() - 200) <= Math.abs(dz),
                                "cell " + c + " past dx " + dx + " dz " + dz);
                    }
                }
            }
        }
    }

    // --- entry / exit points on a cell ---

    private static final double INSET = 0.05;
    private static final double EPS = 1e-9;

    @Test
    void edgePointForACardinalNeighbourSitsJustInsideTheSharedFace() {
        // Coming from the west: the shared face is local x = 0, pulled in by the inset.
        LocalPoint fromWest = BlindLineSampler.edgePoint(10.5, 20.5, 11, 20, INSET);
        assertEquals(INSET, fromWest.x(), EPS);
        assertEquals(0.5, fromWest.z(), EPS);
        // Coming from the east: local x = 1, pulled in.
        LocalPoint fromEast = BlindLineSampler.edgePoint(12.5, 20.5, 11, 20, INSET);
        assertEquals(1.0 - INSET, fromEast.x(), EPS);
        assertEquals(0.5, fromEast.z(), EPS);
        // North / south.
        LocalPoint fromNorth = BlindLineSampler.edgePoint(11.5, 19.5, 11, 20, INSET);
        assertEquals(0.5, fromNorth.x(), EPS);
        assertEquals(INSET, fromNorth.z(), EPS);
        LocalPoint fromSouth = BlindLineSampler.edgePoint(11.5, 21.5, 11, 20, INSET);
        assertEquals(1.0 - INSET, fromSouth.z(), EPS);
    }

    @Test
    void edgePointForADiagonalNeighbourIsTheSharedCorner() {
        LocalPoint p = BlindLineSampler.edgePoint(10.5, 19.5, 11, 20, INSET);
        assertEquals(INSET, p.x(), EPS);
        assertEquals(INSET, p.z(), EPS);
    }

    @Test
    void edgePointFromTheBotsExactPositionIsTheMidpointWhenAlreadyInside() {
        // Bot at 10.9/20.3 heading to cell 11/20: the midpoint (11.2, 20.4) is inside the cell.
        LocalPoint p = BlindLineSampler.edgePoint(10.9, 20.3, 11, 20, INSET);
        assertEquals(0.2, p.x(), EPS);
        assertEquals(0.4, p.z(), EPS);
    }

    @Test
    void edgePointClampsInsetAndNaN() {
        LocalPoint centre = BlindLineSampler.edgePoint(10.5, 20.5, 11, 20, 0.7);
        assertEquals(0.5, centre.x(), EPS);
        assertEquals(0.5, centre.z(), EPS);
        LocalPoint far = BlindLineSampler.edgePoint(-50.0, 90.0, 11, 20, INSET);
        assertEquals(INSET, far.x(), EPS);
        assertEquals(1.0 - INSET, far.z(), EPS);
        LocalPoint nan = BlindLineSampler.edgePoint(Double.NaN, 20.5, 11, 20, INSET);
        assertEquals(INSET, nan.x(), EPS);
        LocalPoint noInset = BlindLineSampler.edgePoint(10.5, 20.5, 11, 20, -1.0);
        assertEquals(0.0, noInset.x(), EPS);
    }

    // --- collision height at a point ---

    private static final ShapeBox SLAB = new ShapeBox(0, 0, 0, 1, 0.5, 1);
    private static final ShapeBox FULL = new ShapeBox(0, 0, 0, 1, 1, 1);
    /** Bottom stair facing west: 0.5 over x in [0, 1], 1.0 over its east (back) half. */
    private static final List<ShapeBox> STAIR_UP_EAST = List.of(SLAB, new ShapeBox(0.5, 0, 0, 1, 1, 1));
    private static final ShapeBox FENCE_POST = new ShapeBox(0.375, 0, 0.375, 0.625, 1.5, 0.625);

    @Test
    void heightAtOfASlabIsHalfEverywhere() {
        assertEquals(0.5, BlindLineSampler.heightAt(List.of(SLAB), INSET, 0.5), EPS);
        assertEquals(0.5, BlindLineSampler.heightAt(List.of(SLAB), 1.0 - INSET, 0.5), EPS);
        assertEquals(0.5, BlindLineSampler.heightAt(List.of(SLAB), 0.5, 0.5), EPS);
        assertEquals(1.0, BlindLineSampler.heightAt(List.of(FULL), INSET, INSET), EPS);
    }

    @Test
    void heightAtOfAStairIsHalfAtTheFrontAndFullAtTheBack() {
        assertEquals(0.5, BlindLineSampler.heightAt(STAIR_UP_EAST, INSET, 0.5), EPS);
        assertEquals(1.0, BlindLineSampler.heightAt(STAIR_UP_EAST, 1.0 - INSET, 0.5), EPS);
        assertEquals(0.5, BlindLineSampler.heightAt(STAIR_UP_EAST, 0.49, INSET), EPS);
        assertEquals(1.0, BlindLineSampler.heightAt(STAIR_UP_EAST, 0.51, 1.0 - INSET), EPS);
    }

    @Test
    void heightAtIsZeroWhereNoBoxCoversThePoint() {
        assertEquals(0.0, BlindLineSampler.heightAt(List.of(FENCE_POST), INSET, 0.5), EPS);
        assertEquals(1.5, BlindLineSampler.heightAt(List.of(FENCE_POST), 0.5, 0.5), EPS);
        assertEquals(0.0, BlindLineSampler.heightAt(List.of(), 0.5, 0.5), EPS);
        assertEquals(0.0, BlindLineSampler.heightAt(null, 0.5, 0.5), EPS);
        List<ShapeBox> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        withNull.add(SLAB);
        assertEquals(0.5, BlindLineSampler.heightAt(withNull, 0.5, 0.5), EPS);
    }

    @Test
    void stairWalkedFrontToBackMeasuresTheDropFromItsBack() {
        // Bot walks east from cell 10 onto a stair at cell 11 (front 0.5, back 1.0), then on to cell 12.
        LocalPoint entry = BlindLineSampler.edgePoint(10.5, 20.5, 11, 20, INSET);
        LocalPoint exit = BlindLineSampler.edgePoint(12.5, 20.5, 11, 20, INSET);
        double entryHeight = BlindLineSampler.heightAt(STAIR_UP_EAST, entry.x(), entry.z());
        double exitHeight = BlindLineSampler.heightAt(STAIR_UP_EAST, exit.x(), exit.z());
        assertEquals(BlindWalkGuardPolicy.StepKind.HALF_STEP, BlindWalkGuardPolicy.stepKind(entryHeight));
        assertEquals(1.0, exitHeight, EPS);
        // Three air cells past the stair's back is a 4-block fall; past a slab it is 3.5 and safe.
        assertEquals(4, BlindWalkGuardPolicy.effectiveDrop(3, exitHeight));
        double slabExit = BlindLineSampler.heightAt(List.of(SLAB), exit.x(), exit.z());
        assertEquals(3, BlindWalkGuardPolicy.effectiveDrop(3, slabExit));
    }

    @Test
    void stairWalkedBackToFrontIsAFullStepUp() {
        // Coming from the east the bot meets the stair's 1.0 back first: a plain one-block rise.
        LocalPoint entry = BlindLineSampler.edgePoint(12.5, 20.5, 11, 20, INSET);
        double entryHeight = BlindLineSampler.heightAt(STAIR_UP_EAST, entry.x(), entry.z());
        assertEquals(BlindWalkGuardPolicy.StepKind.STEP_UP, BlindWalkGuardPolicy.stepKind(entryHeight));
    }
}
