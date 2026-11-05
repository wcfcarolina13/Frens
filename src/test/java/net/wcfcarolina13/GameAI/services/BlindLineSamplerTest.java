package net.wcfcarolina13.GameAI.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.wcfcarolina13.GameAI.services.BlindLineSampler.Cell;
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
}
