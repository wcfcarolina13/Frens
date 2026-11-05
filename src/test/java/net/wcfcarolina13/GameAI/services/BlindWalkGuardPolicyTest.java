package net.wcfcarolina13.GameAI.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.wcfcarolina13.GameAI.services.BlindWalkGuardPolicy.CellFact;
import net.wcfcarolina13.GameAI.services.BlindWalkGuardPolicy.Decision;
import net.wcfcarolina13.GameAI.services.BlindWalkGuardPolicy.Verdict;
import org.junit.jupiter.api.Test;

class BlindWalkGuardPolicyTest {

    private static List<CellFact> flat(int n) {
        List<CellFact> line = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            line.add(CellFact.flat());
        }
        return line;
    }

    private static List<CellFact> flatThen(int flatCells, CellFact... tail) {
        List<CellFact> line = flat(flatCells);
        line.addAll(Arrays.asList(tail));
        return line;
    }

    // --- constants pinned to the wiki / design ---

    @Test
    void vanillaSafeFallIsThreeBlocks() {
        assertEquals(3, BlindWalkGuardPolicy.MAX_SAFE_DROP);
        assertEquals(3, BlindWalkGuardPolicy.DEFAULT_REFUSE_HORIZON);
        assertEquals(1, BlindWalkGuardPolicy.CAP_MARGIN);
    }

    // --- empty / trivial lines ---

    @Test
    void emptyOrNullLineAllows() {
        assertEquals(Verdict.ALLOW, BlindWalkGuardPolicy.decide(null).verdict());
        assertEquals(Verdict.ALLOW, BlindWalkGuardPolicy.decide(Collections.emptyList()).verdict());
        assertEquals(0, BlindWalkGuardPolicy.decide(Collections.emptyList()).safeCells());
        assertNull(BlindWalkGuardPolicy.decide(Collections.emptyList()).reason());
    }

    @Test
    void flatLineAllowsWholeLength() {
        Decision d = BlindWalkGuardPolicy.decide(flat(12));
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(12, d.safeCells());
        assertTrue(d.allowed());
        assertFalse(d.capped());
        assertNull(d.reason());
    }

    @Test
    void nullCellsAreSkipped() {
        List<CellFact> line = new ArrayList<>();
        line.add(null);
        line.add(CellFact.flat());
        line.add(null);
        Decision d = BlindWalkGuardPolicy.decide(line);
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(3, d.safeCells());
    }

    // --- drops ---

    @Test
    void dropOfThreeIsStillSafe() {
        Decision d = BlindWalkGuardPolicy.decide(flatThen(0, CellFact.drop(3), CellFact.drop(1), CellFact.drop(2)));
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(3, d.safeCells());
    }

    @Test
    void dropOfFourWithinHorizonRefuses() {
        for (int at = 0; at < BlindWalkGuardPolicy.DEFAULT_REFUSE_HORIZON; at++) {
            Decision d = BlindWalkGuardPolicy.decide(flatThen(at, CellFact.drop(4), CellFact.flat()));
            assertEquals(Verdict.REFUSE, d.verdict(), "drop at index " + at);
            assertEquals(0, d.safeCells());
            assertFalse(d.allowed());
            assertEquals("drop of 4+ " + (at + 1) + (at == 0 ? " block" : " blocks") + " ahead", d.reason());
        }
    }

    @Test
    void dropBeyondHorizonCapsOneCellShort() {
        // hazard at index 7 -> cells 0..5 walkable (6 cells), cell 6 is the margin
        Decision d = BlindWalkGuardPolicy.decide(flatThen(7, CellFact.drop(5), CellFact.flat(), CellFact.flat()));
        assertEquals(Verdict.ALLOW_UP_TO, d.verdict());
        assertEquals(6, d.safeCells());
        assertTrue(d.allowed());
        assertTrue(d.capped());
        assertEquals("drop of 5+ 8 blocks ahead", d.reason());
    }

    @Test
    void dropExactlyAtHorizonCaps() {
        // index 3 == horizon -> not refused, capped to 3 - 1 = 2 cells
        Decision d = BlindWalkGuardPolicy.decide(flatThen(3, CellFact.drop(4)));
        assertEquals(Verdict.ALLOW_UP_TO, d.verdict());
        assertEquals(2, d.safeCells());
    }

    @Test
    void cappedSampledDropCountsAsHazard() {
        // The sampler caps the scan; a capped value above MAX_SAFE_DROP must still refuse.
        Decision d = BlindWalkGuardPolicy.decide(flatThen(1, CellFact.drop(99)));
        assertEquals(Verdict.REFUSE, d.verdict());
    }

    @Test
    void customSafeDropIsHonoured() {
        List<CellFact> line = flatThen(0, CellFact.drop(2));
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(line, 1, 3).verdict());
        assertEquals(Verdict.ALLOW, BlindWalkGuardPolicy.decide(line, 2, 3).verdict());
    }

    // --- lava / fire / water ---

    @Test
    void lavaInFirstCellRefuses() {
        Decision d = BlindWalkGuardPolicy.decide(flatThen(0, CellFact.lavaCell()));
        assertEquals(Verdict.REFUSE, d.verdict());
        assertEquals("lava 1 block ahead", d.reason());
    }

    @Test
    void fireWithinHorizonRefuses() {
        Decision d = BlindWalkGuardPolicy.decide(flatThen(2, CellFact.fireCell()));
        assertEquals(Verdict.REFUSE, d.verdict());
        assertEquals("fire/deadly block 3 blocks ahead", d.reason());
    }

    @Test
    void deepWaterWithinHorizonRefuses() {
        Decision d = BlindWalkGuardPolicy.decide(flatThen(1, CellFact.deepWaterCell()));
        assertEquals(Verdict.REFUSE, d.verdict());
        assertEquals("deep water 2 blocks ahead", d.reason());
    }

    @Test
    void lavaBeyondHorizonCaps() {
        Decision d = BlindWalkGuardPolicy.decide(flatThen(10, CellFact.lavaCell()));
        assertEquals(Verdict.ALLOW_UP_TO, d.verdict());
        assertEquals(9, d.safeCells());
        assertEquals("lava 11 blocks ahead", d.reason());
    }

    @Test
    void firstHazardDecidesEvenIfWorseOnesFollow() {
        Decision d = BlindWalkGuardPolicy.decide(flatThen(5, CellFact.drop(4), CellFact.lavaCell()));
        assertEquals(Verdict.ALLOW_UP_TO, d.verdict());
        assertEquals(4, d.safeCells());
        assertEquals("drop of 4+ 6 blocks ahead", d.reason());
    }

    @Test
    void hazardPriorityNamesTheDeadliest() {
        assertEquals("lava", BlindWalkGuardPolicy.hazardOf(new CellFact(true, 9, true, true, true), 3));
        assertEquals("fire/deadly block", BlindWalkGuardPolicy.hazardOf(new CellFact(true, 9, false, true, true), 3));
        assertEquals("deep water", BlindWalkGuardPolicy.hazardOf(new CellFact(true, 9, false, false, true), 3));
        assertEquals("drop of 9+", BlindWalkGuardPolicy.hazardOf(new CellFact(true, 9, false, false, false), 3));
        assertNull(BlindWalkGuardPolicy.hazardOf(CellFact.flat(), 3));
        assertNull(BlindWalkGuardPolicy.hazardOf(CellFact.drop(3), 3));
    }

    // --- walls end the inspection without refusing ---

    @Test
    void wallEndsInspectionAndAllows() {
        // Lava behind a wall is not reachable blindly; today's step-up/door/mine assists keep running.
        Decision d = BlindWalkGuardPolicy.decide(flatThen(2, CellFact.wall(), CellFact.lavaCell()));
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(2, d.safeCells());
        assertFalse(d.capped());
    }

    @Test
    void wallAsFirstCellAllowsZeroInspected() {
        Decision d = BlindWalkGuardPolicy.decide(flatThen(0, CellFact.wall(), CellFact.flat()));
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(0, d.safeCells());
    }

    @Test
    void hazardousWallWithinHorizonRefuses() {
        // A rise whose foot cell has lava in it (lava pooled against a wall).
        CellFact lavaWall = new CellFact(false, 0, true, false, false);
        Decision d = BlindWalkGuardPolicy.decide(flatThen(1, lavaWall));
        assertEquals(Verdict.REFUSE, d.verdict());
        assertEquals("lava 2 blocks ahead", d.reason());
    }

    @Test
    void hazardousWallBeyondHorizonJustEndsInspection() {
        CellFact lavaWall = new CellFact(false, 0, true, false, false);
        Decision d = BlindWalkGuardPolicy.decide(flatThen(6, lavaWall));
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(6, d.safeCells());
    }

    // --- horizon edge cases ---

    @Test
    void horizonBelowOneIsClampedToOne() {
        List<CellFact> line = flatThen(0, CellFact.drop(4));
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(line, 3, 0).verdict());
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(line, 3, -5).verdict());
    }

    @Test
    void capThatWouldLeaveNoCellsRefuses() {
        // horizon 1: hazard at index 1 is "beyond" the horizon but 1 - CAP_MARGIN = 0 cells -> refuse.
        Decision d = BlindWalkGuardPolicy.decide(flatThen(1, CellFact.drop(4)), 3, 1);
        assertEquals(Verdict.REFUSE, d.verdict());
        Decision ok = BlindWalkGuardPolicy.decide(flatThen(2, CellFact.drop(4)), 3, 1);
        assertEquals(Verdict.ALLOW_UP_TO, ok.verdict());
        assertEquals(1, ok.safeCells());
    }

    @Test
    void hugeHorizonRefusesAnywhereOnTheLine() {
        Decision d = BlindWalkGuardPolicy.decide(flatThen(15, CellFact.drop(4)), 3, 100);
        assertEquals(Verdict.REFUSE, d.verdict());
    }

    // --- the two sandbox scenarios from the backlog ---

    @Test
    void ravineEdgeRightAheadRefuses() {
        // Bot one step from a ravine lip: flat cell, then an abyss.
        Decision d = BlindWalkGuardPolicy.decide(flatThen(1, CellFact.drop(6), CellFact.drop(6), CellFact.drop(6)));
        assertEquals(Verdict.REFUSE, d.verdict());
    }

    @Test
    void shortFlatShortcutStillAllowed() {
        // 12-block flat shortcut with a single 1-block step down and a step up (drop 0).
        List<CellFact> line = flat(5);
        line.add(CellFact.drop(1));
        line.addAll(flat(6));
        Decision d = BlindWalkGuardPolicy.decide(line);
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(12, d.safeCells());
    }

    @Test
    void decisionFactoriesClampAndFlag() {
        assertEquals(0, Decision.allow(-3).safeCells());
        assertTrue(Decision.allowUpTo(2, "x").capped());
        assertTrue(Decision.allowUpTo(2, "x").allowed());
        assertFalse(Decision.refuse("x").allowed());
        assertFalse(Decision.refuse("x").capped());
        assertEquals("x", Decision.refuse("x").reason());
    }
}
