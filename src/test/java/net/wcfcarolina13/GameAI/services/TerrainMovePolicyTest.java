package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.TerrainMovePolicy.Inputs;
import net.wcfcarolina13.GameAI.services.TerrainMovePolicy.Move;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainMovePolicyTest {

    private static final float HAND = 1.0f;
    private static final float SAND_HARDNESS = 0.5f;
    private static final float STONE_HARDNESS = 1.5f;

    private static int sandByHand() {
        return ObstructionMinePolicy.groundBreakTicks(HAND, SAND_HARDNESS, ObstructionMinePolicy.canHarvest(false, false));
    }

    private static int stoneByHand() {
        return ObstructionMinePolicy.groundBreakTicks(HAND, STONE_HARDNESS, ObstructionMinePolicy.canHarvest(true, false));
    }

    /** One blocking cell (2-rise), with the break verdict the caller would compute. */
    private static Inputs twoRise(int cellTicks, boolean territoryOk, int scaffold, boolean placeOk,
                                  boolean detour, int detourBlocks) {
        boolean breakAllowed = territoryOk && ObstructionMinePolicy.allow(cellTicks, ObstructionMinePolicy.MAX_GROUND_BREAK_TICKS);
        return new Inputs(2, 1, cellTicks, cellTicks, breakAllowed, scaffold, placeOk, detour, detourBlocks);
    }

    @Test
    void sandTwoRiseWithoutScaffoldBreaks() {
        int ticks = sandByHand();
        assertEquals(15, ticks, "sand by hand is about 15 ground ticks");
        TerrainMovePolicy.Decision d = TerrainMovePolicy.choose(twoRise(ticks, true, 0, true, false, 0));
        assertEquals(Move.BREAK, d.move());
        assertEquals(15 / 20.0 + 2.0, d.cost(), 1e-9);
    }

    @Test
    void stoneTwoRiseWithoutPickaxeButWithScaffoldPlaces() {
        int ticks = stoneByHand();
        assertTrue(ticks > ObstructionMinePolicy.MAX_GROUND_BREAK_TICKS, "stone by hand is refused");
        TerrainMovePolicy.Decision d = TerrainMovePolicy.choose(twoRise(ticks, true, 12, true, false, 0));
        assertEquals(Move.PLACE, d.move());
        assertEquals(TerrainMovePolicy.PLACE_COST, d.cost(), 1e-9);
    }

    @Test
    void stoneWithoutScaffoldWalksAroundOrGivesUp() {
        int ticks = stoneByHand();
        assertEquals(Move.WALK_AROUND, TerrainMovePolicy.choose(twoRise(ticks, true, 0, true, true, 6)).move());
        assertEquals(Move.NONE, TerrainMovePolicy.choose(twoRise(ticks, true, 0, true, false, 0)).move());
    }

    @Test
    void breakAllowedFlagCannotOverrideTheTickCap() {
        int ticks = stoneByHand();
        Inputs lying = new Inputs(2, 1, ticks, ticks, true, 0, false, false, 0);
        assertEquals(Move.NONE, TerrainMovePolicy.choose(lying).move());
        Inputs unbreakable = new Inputs(2, 1, 0, Integer.MAX_VALUE, true, 0, false, false, 0);
        assertEquals(Move.NONE, TerrainMovePolicy.choose(unbreakable).move());
    }

    @Test
    void protectedCellsNeverBreakOrPlace() {
        // Spawn base / protected zone: territory refuses both the break and the place.
        Inputs protectedSand = twoRise(sandByHand(), false, 64, false, false, 0);
        assertEquals(Move.NONE, TerrainMovePolicy.choose(protectedSand).move());
        Inputs protectedWithDetour = twoRise(sandByHand(), false, 64, false, true, 9);
        assertEquals(Move.WALK_AROUND, TerrainMovePolicy.choose(protectedWithDetour).move());
    }

    @Test
    void cheapDetourBeatsBreaking() {
        TerrainMovePolicy.Decision d = TerrainMovePolicy.choose(twoRise(sandByHand(), true, 0, false, true, 2));
        assertEquals(Move.WALK_AROUND, d.move());
        assertEquals(2.0, d.cost(), 1e-9);
    }

    @Test
    void tiesGoToWalking() {
        // Place costs 3; a 3-block detour ties it and walking wins.
        Inputs tie = new Inputs(2, 1, 0, 0, false, 5, true, true, 3);
        assertEquals(Move.WALK_AROUND, TerrainMovePolicy.choose(tie).move());
        // Break 20 ticks + 2 = 3.0 ties place: break wins (spends no inventory).
        Inputs breakPlaceTie = new Inputs(2, 1, 20, 20, true, 5, true, false, 0);
        assertEquals(Move.BREAK, TerrainMovePolicy.choose(breakPlaceTie).move());
    }

    @Test
    void threeRiseNeverPlaces() {
        Inputs tall = new Inputs(3, 2, 2L * stoneByHand(), stoneByHand(), false, 64, true, false, 0);
        assertEquals(Move.NONE, TerrainMovePolicy.choose(tall).move());
        Inputs tallSand = new Inputs(3, 2, 30, 15, true, 64, true, false, 0);
        TerrainMovePolicy.Decision d = TerrainMovePolicy.choose(tallSand);
        assertEquals(Move.BREAK, d.move());
        assertEquals(30 / 20.0 + 4.0, d.cost(), 1e-9);
        assertNotEquals(Move.PLACE, TerrainMovePolicy.choose(new Inputs(4, 2, 0, 0, false, 64, true, false, 0)).move());
    }

    @Test
    void lowRiseIsNotATerrainMove() {
        assertEquals(Move.NONE, TerrainMovePolicy.choose(new Inputs(1, 0, 0, 0, true, 64, true, false, 0)).move());
        assertEquals(Move.WALK_AROUND, TerrainMovePolicy.choose(new Inputs(1, 0, 0, 0, true, 64, true, true, 4)).move());
    }

    @Test
    void onlyRouteCandidatesThatLeaveTheTrapOrDirectivesAreDetours() {
        assertTrue(TerrainMovePolicy.detourCounts(true, true, false), "BFS route leaving the trap");
        assertTrue(TerrainMovePolicy.detourCounts(false, false, true), "directive");
        assertFalse(TerrainMovePolicy.detourCounts(false, true, false), "1-hop fallback, even when it leaves the trap");
        assertFalse(TerrainMovePolicy.detourCounts(false, false, false), "1-hop fallback");
        assertFalse(TerrainMovePolicy.detourCounts(true, false, false), "BFS route that stays in the trap");
    }

    @Test
    void jakesWallWithoutACountedDetourBreaksInsteadOfLoopingOnWalk() {
        // Field 1.1.240: a 1-hop flat fallback (cost 1) beat BREAK every second without moving him.
        // With the fallback dropped, a trap-like cell facing soft terrain breaks.
        Inputs trapNoDetour = new Inputs(2, 1, sandByHand(), sandByHand(), true, 0, false, false, 0, true, false);
        assertEquals(Move.BREAK, TerrainMovePolicy.choose(trapNoDetour).move());
    }

    @Test
    void openCellSidestepBeatsSoftBreak() {
        int sand = sandByHand();
        Inputs open = new Inputs(2, 1, sand, sand, true, 0, false, false, 0, false, true);
        TerrainMovePolicy.Decision d = TerrainMovePolicy.choose(open);
        assertEquals(Move.WALK_AROUND, d.move());
        assertEquals(TerrainMovePolicy.SIDESTEP_COST, d.cost(), 1e-9);
        // 3+ rise, two sand cells: still walks.
        Inputs tall = new Inputs(3, 2, 2L * sand, sand, true, 0, false, false, 0, false, true);
        assertEquals(Move.WALK_AROUND, TerrainMovePolicy.choose(tall).move());
        // Beats place too.
        Inputs withDirt = new Inputs(2, 1, sand, sand, true, 64, true, false, 0, false, true);
        assertEquals(Move.WALK_AROUND, TerrainMovePolicy.choose(withDirt).move());
    }

    @Test
    void instantBreakTiesTheSidestepAndWalkingWins() {
        Inputs zeroTick = new Inputs(2, 1, 0, 0, true, 0, false, false, 0, false, true);
        TerrainMovePolicy.Decision d = TerrainMovePolicy.choose(zeroTick);
        assertEquals(TerrainMovePolicy.SIDESTEP_COST, d.breakCost(), 1e-9);
        assertEquals(Move.WALK_AROUND, d.move());
    }

    @Test
    void openCellWithoutSidestepBreaks() {
        Inputs open = new Inputs(2, 1, sandByHand(), sandByHand(), true, 0, false, false, 0, false, false);
        assertEquals(Move.BREAK, TerrainMovePolicy.choose(open).move());
    }

    @Test
    void trapCellIgnoresTheSidestep() {
        Inputs trap = new Inputs(2, 1, sandByHand(), sandByHand(), true, 0, false, false, 0, true, true);
        assertEquals(Move.BREAK, TerrainMovePolicy.choose(trap).move());
        Inputs trapStone = new Inputs(2, 1, stoneByHand(), stoneByHand(), true, 0, false, false, 0, true, true);
        assertEquals(Move.NONE, TerrainMovePolicy.choose(trapStone).move());
    }

    @Test
    void aCountedDetourStillUsesItsOwnCostInAnOpenCell() {
        Inputs openWithRoute = new Inputs(2, 1, sandByHand(), sandByHand(), true, 0, false, true, 1, false, true);
        TerrainMovePolicy.Decision d = TerrainMovePolicy.choose(openWithRoute);
        assertEquals(Move.WALK_AROUND, d.move());
        assertEquals(1.0, d.cost(), 1e-9);
    }

    @Test
    void cellsToBreak() {
        assertEquals(0, TerrainMovePolicy.cellsToBreak(1));
        assertEquals(1, TerrainMovePolicy.cellsToBreak(2));
        assertEquals(2, TerrainMovePolicy.cellsToBreak(3));
        assertEquals(2, TerrainMovePolicy.cellsToBreak(4));
    }
}
