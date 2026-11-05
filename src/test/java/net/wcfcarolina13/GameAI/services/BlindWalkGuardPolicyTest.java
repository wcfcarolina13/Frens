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

    // --- a bot already in water may swim through deep water ---

    @Test
    void swimmingBotIgnoresDeepWaterOnItsOwnFirstCell() {
        // Pursuit / no-path walk toward shore from a pond 2+ deep: the first cell is deep water.
        List<CellFact> line = flatThen(0, CellFact.deepWaterCell(), CellFact.deepWaterCell(), CellFact.flat());
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(line).verdict());
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(line, false).verdict());
        Decision swimming = BlindWalkGuardPolicy.decide(line, true);
        assertEquals(Verdict.ALLOW, swimming.verdict());
        assertEquals(3, swimming.safeCells());
        assertNull(swimming.reason());
    }

    @Test
    void swimmingBotIsNotCappedByDeepWaterFurtherOut() {
        Decision dry = BlindWalkGuardPolicy.decide(flatThen(8, CellFact.deepWaterCell(), CellFact.flat()), false);
        assertEquals(Verdict.ALLOW_UP_TO, dry.verdict());
        Decision wet = BlindWalkGuardPolicy.decide(flatThen(8, CellFact.deepWaterCell(), CellFact.flat()), true);
        assertEquals(Verdict.ALLOW, wet.verdict());
        assertEquals(10, wet.safeCells());
    }

    @Test
    void swimmingBotStillRefusesLavaFireAndDrops() {
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(flatThen(0, CellFact.deepWaterCell(), CellFact.lavaCell()), true).verdict());
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(flatThen(0, CellFact.deepWaterCell(), CellFact.fireCell()), true).verdict());
        Decision drop = BlindWalkGuardPolicy.decide(flatThen(0, CellFact.deepWaterCell(), CellFact.drop(4)), true);
        assertEquals(Verdict.REFUSE, drop.verdict());
        assertEquals("drop of 4+ 2 blocks ahead", drop.reason());
        Decision far = BlindWalkGuardPolicy.decide(flatThen(6, CellFact.deepWaterCell(), CellFact.lavaCell()), true);
        assertEquals(Verdict.ALLOW_UP_TO, far.verdict());
        assertEquals(6, far.safeCells());
        assertEquals("lava 8 blocks ahead", far.reason());
    }

    @Test
    void startInWaterFlagThreadsThroughHazardOfAndCustomThresholds() {
        CellFact water = CellFact.deepWaterCell();
        assertEquals("deep water", BlindWalkGuardPolicy.hazardOf(water, 3));
        assertEquals("deep water", BlindWalkGuardPolicy.hazardOf(water, 3, false));
        assertNull(BlindWalkGuardPolicy.hazardOf(water, 3, true));
        // Deadlier hazards still win when swimming.
        assertEquals("lava", BlindWalkGuardPolicy.hazardOf(new CellFact(true, 0, true, false, true), 3, true));
        assertEquals("drop of 5+", BlindWalkGuardPolicy.hazardOf(new CellFact(true, 5, false, false, true), 3, true));
        // 4-arg decide honours the flag with custom thresholds; 3-arg decide is the dry default.
        List<CellFact> line = flatThen(0, water);
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(line, 1, 1).verdict());
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(line, 1, 1, false).verdict());
        assertEquals(Verdict.ALLOW, BlindWalkGuardPolicy.decide(line, 1, 1, true).verdict());
    }

    @Test
    void swimmingBotWithDeepWaterAgainstAWallAllows() {
        // Deep water at the foot of a rise is fine when already swimming; lava there is not.
        CellFact waterWall = new CellFact(false, 0, false, false, true);
        Decision d = BlindWalkGuardPolicy.decide(flatThen(1, waterWall), true);
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(1, d.safeCells());
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(flatThen(1, waterWall), false).verdict());
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

    // --- a drop into water is no fall (minecraft.wiki Damage: water of any depth negates fall damage) ---

    @Test
    void dropOfFourOrMoreIntoShallowWaterIsNotAHazard() {
        // A 1-deep landing pool: any depth of water cancels the fall, so the drop is allowed.
        assertNull(BlindWalkGuardPolicy.hazardOf(CellFact.dropIntoWater(4, false), 3));
        assertNull(BlindWalkGuardPolicy.hazardOf(CellFact.dropIntoWater(9, false), 3));
        Decision d = BlindWalkGuardPolicy.decide(flatThen(0, CellFact.dropIntoWater(6, false), CellFact.flat()));
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(2, d.safeCells());
        assertNull(d.reason());
    }

    @Test
    void sameDropOntoGroundStillRefuses() {
        // The 5-arg constructor means a solid landing: the control for the test above.
        CellFact ground = new CellFact(true, 6, false, false, false);
        assertFalse(ground.waterLanding());
        assertEquals("drop of 6+", BlindWalkGuardPolicy.hazardOf(ground, 3));
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(flatThen(0, ground, CellFact.flat())).verdict());
    }

    @Test
    void waterLandingStaysSubjectToTheDeepWaterRule() {
        // Not deep-water-safe: a 2+ deep landing pool is still deep water for a dry bot ...
        CellFact deepPool = CellFact.dropIntoWater(6, true);
        assertTrue(deepPool.deepWater());
        assertTrue(deepPool.waterLanding());
        Decision dry = BlindWalkGuardPolicy.decide(flatThen(0, deepPool));
        assertEquals(Verdict.REFUSE, dry.verdict());
        assertEquals("deep water 1 block ahead", dry.reason());
        // ... and plain ground for a bot already swimming, with no drop hazard left over.
        Decision wet = BlindWalkGuardPolicy.decide(flatThen(0, deepPool), true);
        assertEquals(Verdict.ALLOW, wet.verdict());
        assertNull(wet.reason());
    }

    @Test
    void waterLandingBeyondHorizonDoesNotCap() {
        Decision d = BlindWalkGuardPolicy.decide(flatThen(8, CellFact.dropIntoWater(5, false), CellFact.flat()));
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(10, d.safeCells());
    }

    @Test
    void lavaAndFireStillWinOverAWaterLanding() {
        assertEquals("lava", BlindWalkGuardPolicy.hazardOf(new CellFact(true, 5, true, false, false, true), 3));
        assertEquals("fire/deadly block", BlindWalkGuardPolicy.hazardOf(new CellFact(true, 5, false, true, false, true), 3));
    }

    // --- walls: only side-contact damage makes a wall hazardous ---

    @Test
    void magmaClassWallIsAPlainWall() {
        // Magma hurts only when stood on (minecraft.wiki Magma Block), so a magma wall ends the
        // inspection like stone does. wallFact(false) == wall().
        assertEquals(CellFact.wall(), CellFact.wallFact(false));
        Decision d = BlindWalkGuardPolicy.decide(flatThen(1, CellFact.wallFact(false), CellFact.lavaCell()));
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(1, d.safeCells());
        assertNull(d.reason());
    }

    @Test
    void cactusClassWallWithinHorizonRefuses() {
        CellFact cactus = CellFact.hazardousWall();
        assertFalse(cactus.passable());
        assertFalse(cactus.lava());
        assertTrue(cactus.fire());
        Decision d = BlindWalkGuardPolicy.decide(flatThen(2, cactus));
        assertEquals(Verdict.REFUSE, d.verdict());
        assertEquals("fire/deadly block 3 blocks ahead", d.reason());
    }

    @Test
    void cactusClassWallBeyondHorizonJustEndsInspection() {
        Decision d = BlindWalkGuardPolicy.decide(flatThen(3, CellFact.hazardousWall()));
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(3, d.safeCells());
    }

    @Test
    void wallFactNeverCarriesLavaOrWater() {
        // Lava and fire have empty collision shapes, so no wall cell can hold them.
        for (boolean hurts : new boolean[] {false, true}) {
            CellFact wall = CellFact.wallFact(hurts);
            assertFalse(wall.lava());
            assertFalse(wall.deepWater());
            assertFalse(wall.waterLanding());
            assertEquals(0, wall.floorDrop());
            assertEquals(hurts, wall.fire());
        }
    }

    // --- step rule: slabs and stairs are half steps, not a block of rise ---

    @Test
    void stepKindSplitsOpenHalfAndFull() {
        assertEquals(BlindWalkGuardPolicy.StepKind.OPEN, BlindWalkGuardPolicy.stepKind(0.0));
        assertEquals(BlindWalkGuardPolicy.StepKind.OPEN, BlindWalkGuardPolicy.stepKind(-1.0));
        assertEquals(BlindWalkGuardPolicy.StepKind.OPEN, BlindWalkGuardPolicy.stepKind(Double.NaN));
        assertEquals(BlindWalkGuardPolicy.StepKind.OPEN, BlindWalkGuardPolicy.stepKind(Double.NEGATIVE_INFINITY));
        assertEquals(BlindWalkGuardPolicy.StepKind.HALF_STEP, BlindWalkGuardPolicy.stepKind(0.5)); // bottom slab, stair front
        assertEquals(BlindWalkGuardPolicy.StepKind.HALF_STEP, BlindWalkGuardPolicy.stepKind(0.4375)); // campfire
        assertEquals(BlindWalkGuardPolicy.StepKind.HALF_STEP, BlindWalkGuardPolicy.stepKind(0.125)); // snow layer
        // The player's step height is 0.6 (minecraft.wiki Slab): anything taller is a jump.
        assertEquals(0.6, BlindWalkGuardPolicy.MAX_STEP_HEIGHT);
        assertEquals(BlindWalkGuardPolicy.StepKind.HALF_STEP, BlindWalkGuardPolicy.stepKind(BlindWalkGuardPolicy.MAX_STEP_HEIGHT));
        assertEquals(BlindWalkGuardPolicy.StepKind.STEP_UP, BlindWalkGuardPolicy.stepKind(0.61));
        assertEquals(BlindWalkGuardPolicy.StepKind.STEP_UP, BlindWalkGuardPolicy.stepKind(0.9375)); // cactus (15/16): jumped onto, then a deadly floor
        assertEquals(BlindWalkGuardPolicy.StepKind.STEP_UP, BlindWalkGuardPolicy.stepKind(1.0)); // full block, top slab, stair back
        assertEquals(BlindWalkGuardPolicy.StepKind.STEP_UP, BlindWalkGuardPolicy.stepKind(1.0 - BlindWalkGuardPolicy.FULL_HEIGHT_TOLERANCE / 2));
        assertEquals(BlindWalkGuardPolicy.StepKind.STEP_UP, BlindWalkGuardPolicy.stepKind(1.5)); // fence
    }

    @Test
    void entryHeightFallsBackToTheShapeTopWhenTheEntryColumnIsOpen() {
        assertEquals(0.5, BlindWalkGuardPolicy.entryHeight(0.5, 1.0));
        assertEquals(1.0, BlindWalkGuardPolicy.entryHeight(1.0, 1.0));
        assertEquals(1.5, BlindWalkGuardPolicy.entryHeight(0.0, 1.5)); // fence post off the line
        assertEquals(0.5, BlindWalkGuardPolicy.entryHeight(Double.NEGATIVE_INFINITY, 0.5));
        assertEquals(0.0, BlindWalkGuardPolicy.entryHeight(0.0, 0.0));
        assertEquals(0.0, BlindWalkGuardPolicy.entryHeight(-1.0, -1.0));
    }

    @Test
    void effectiveDropFollowsVanillaFallDamageThreshold() {
        // damage = floor(fall - 3) > 0 iff floor(fall) >= 4
        assertEquals(3, BlindWalkGuardPolicy.effectiveDrop(3, 0.0)); // 3.0: safe
        assertEquals(3, BlindWalkGuardPolicy.effectiveDrop(3, 0.5)); // 3.5 from a slab: safe
        assertEquals(4, BlindWalkGuardPolicy.effectiveDrop(3, 1.0)); // 4.0 from a stair's back: hazard
        assertEquals(4, BlindWalkGuardPolicy.effectiveDrop(4, 0.0));
        assertEquals(4, BlindWalkGuardPolicy.effectiveDrop(4, 0.5));
        assertEquals(0, BlindWalkGuardPolicy.effectiveDrop(0, 0.5));
        assertEquals(1, BlindWalkGuardPolicy.effectiveDrop(0, 1.0));
        assertEquals(0, BlindWalkGuardPolicy.effectiveDrop(-2, Double.NaN));
        assertEquals(4, BlindWalkGuardPolicy.effectiveDrop(3, 7.0)); // standing height clamped to 1
        assertEquals(2, BlindWalkGuardPolicy.effectiveDrop(2, -0.5));
    }

    @Test
    void slabBeforeALegalDropNoLongerInflatesIt() {
        // Old sampler: slab -> feetY+1 -> the 3-block drop read as 4 and refused.
        int inflated = BlindWalkGuardPolicy.effectiveDrop(3, 0.0) + 1;
        assertEquals(Verdict.REFUSE, BlindWalkGuardPolicy.decide(flatThen(0, CellFact.flat(), CellFact.drop(inflated))).verdict());
        int measured = BlindWalkGuardPolicy.effectiveDrop(3, 0.5);
        Decision d = BlindWalkGuardPolicy.decide(flatThen(0, CellFact.flat(), CellFact.drop(measured)));
        assertEquals(Verdict.ALLOW, d.verdict());
        assertEquals(2, d.safeCells());
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
