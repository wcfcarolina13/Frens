package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.JoinEnclosurePolicy.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinEnclosurePolicyTest {

    private static final int ENOUGH = JoinEnclosurePolicy.MIN_BUILT_BLOCKS;

    @Test
    void houseWithDoorOnJoinDoesNotBreakFreeByDay() {
        assertEquals(Decision.SKIP_PLAYER_BUILT,
                JoinEnclosurePolicy.decide(false, false, false, false, true, true));
    }

    @Test
    void houseWithDoorOnJoinDoesNotShelterAtNight() {
        assertEquals(Decision.SKIP_PLAYER_BUILT,
                JoinEnclosurePolicy.decide(false, false, false, false, true, false));
    }

    @Test
    void enclosedOutsideAnyHouseStillBreaksFreeByDay() {
        assertEquals(Decision.BREAK_FREE,
                JoinEnclosurePolicy.decide(false, false, false, false, false, true));
    }

    @Test
    void enclosedOutsideAnyHouseSheltersAtNight() {
        assertEquals(Decision.SHELTER,
                JoinEnclosurePolicy.decide(false, false, false, false, false, false));
    }

    @Test
    void earlierGatesWinInCallSiteOrder() {
        assertEquals(Decision.SKIP_NEAR_BASE,
                JoinEnclosurePolicy.decide(true, true, true, true, true, true));
        assertEquals(Decision.SKIP_AT_SURFACE,
                JoinEnclosurePolicy.decide(false, true, true, true, true, true));
        assertEquals(Decision.SKIP_COMMANDER_NEAR,
                JoinEnclosurePolicy.decide(false, false, true, true, true, true));
        assertEquals(Decision.SKIP_MOVED,
                JoinEnclosurePolicy.decide(false, false, false, true, true, true));
    }

    @Test
    void onlyShelterAndBreakFreeAct() {
        for (Decision d : Decision.values()) {
            assertEquals(d != Decision.SHELTER && d != Decision.BREAK_FREE, d.skips(), d.name());
        }
    }

    @Test
    void playerBuiltNeedsADoor() {
        assertFalse(JoinEnclosurePolicy.isInsidePlayerBuilt(50, false));
    }

    @Test
    void playerBuiltNeedsEnoughBuiltBlocks() {
        assertFalse(JoinEnclosurePolicy.isInsidePlayerBuilt(0, true));
        assertFalse(JoinEnclosurePolicy.isInsidePlayerBuilt(ENOUGH - 1, true));
        assertTrue(JoinEnclosurePolicy.isInsidePlayerBuilt(ENOUGH, true));
        assertTrue(JoinEnclosurePolicy.isInsidePlayerBuilt(40, true));
    }

    @Test
    void houseRoomsAndCellarsAreShallowEnough() {
        assertTrue(JoinEnclosurePolicy.withinSurfaceDepth(0));
        assertTrue(JoinEnclosurePolicy.withinSurfaceDepth(4));
        assertTrue(JoinEnclosurePolicy.withinSurfaceDepth(JoinEnclosurePolicy.MAX_DEPTH_BELOW_SURFACE));
        // Standing on a roof or a tower: the heightmap is at or below the feet.
        assertTrue(JoinEnclosurePolicy.withinSurfaceDepth(-3));
    }

    @Test
    void deepStructuresDoNotCountAsPlayerBuilt() {
        // A stronghold corridor or a trial chamber sits tens of blocks below the surface.
        assertFalse(JoinEnclosurePolicy.withinSurfaceDepth(JoinEnclosurePolicy.MAX_DEPTH_BELOW_SURFACE + 1));
        assertFalse(JoinEnclosurePolicy.withinSurfaceDepth(40));
    }

    @Test
    void lingerHoldsForCommanderBaseOrHouse() {
        assertTrue(JoinEnclosurePolicy.lingerStaysAtNonFunctionalPosition(true, false, false));
        assertTrue(JoinEnclosurePolicy.lingerStaysAtNonFunctionalPosition(false, true, false));
        assertTrue(JoinEnclosurePolicy.lingerStaysAtNonFunctionalPosition(false, false, true));
        assertFalse(JoinEnclosurePolicy.lingerStaysAtNonFunctionalPosition(false, false, false));
    }
    @Test
    void hillsideHouseCountsWhenItsDoorOpensToSky() {
        // Deep below the heightmap (stone over the roof), but the front door faces the open slope.
        assertTrue(JoinEnclosurePolicy.isInsidePlayerBuilt(ENOUGH, true, false, true));
        assertTrue(JoinEnclosurePolicy.isInsidePlayerBuilt(ENOUGH, true, true, false));
        assertTrue(JoinEnclosurePolicy.isInsidePlayerBuilt(ENOUGH, true, true, true));
    }

    @Test
    void deepRoomWhoseDoorOpensToACorridorStaysOut() {
        assertFalse(JoinEnclosurePolicy.isInsidePlayerBuilt(40, true, false, false));
    }

    @Test
    void doorToSkyStillNeedsADoorAndEnoughBuiltBlocks() {
        assertFalse(JoinEnclosurePolicy.isInsidePlayerBuilt(ENOUGH - 1, true, false, true));
        assertFalse(JoinEnclosurePolicy.isInsidePlayerBuilt(40, false, true, true));
    }

    @Test
    void doorTopIsTheUpperHalf() {
        assertEquals(65, JoinEnclosurePolicy.doorTopY(64, false));
        assertEquals(65, JoinEnclosurePolicy.doorTopY(65, true));
    }

    @Test
    void doorCellIsSkyWhenTheHeightmapIsAtOrBelowTheDoorTop() {
        // Ground in front of the door: heightmap at the door's bottom half.
        assertTrue(JoinEnclosurePolicy.doorCellOpensToSky(64, 65));
        assertTrue(JoinEnclosurePolicy.doorCellOpensToSky(65, 65));
        // A slope dropping away below the door.
        assertTrue(JoinEnclosurePolicy.doorCellOpensToSky(58, 65));
        // An awning or stone over the cell.
        assertFalse(JoinEnclosurePolicy.doorCellOpensToSky(66, 65));
        assertFalse(JoinEnclosurePolicy.doorCellOpensToSky(90, 65));
    }

    @Test
    void unloadedColumnIsNeverSky() {
        assertFalse(JoinEnclosurePolicy.doorCellOpensToSky(Integer.MIN_VALUE, 65));
        assertFalse(JoinEnclosurePolicy.doorCellOpensToSky(Integer.MIN_VALUE, Integer.MIN_VALUE + 1));
    }

    @Test
    void doorSkyCellsAreOneAndTwoOutOnBothAxes() {
        int[][] offsets = JoinEnclosurePolicy.doorSkyOffsets();
        assertEquals(8, offsets.length);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int[] o : offsets) {
            assertTrue(o[0] == 0 || o[1] == 0, "one axis at a time");
            int d = Math.abs(o[0]) + Math.abs(o[1]);
            assertTrue(d == 1 || d == 2, "1 or 2 blocks out");
            seen.add(o[0] + "," + o[1]);
        }
        assertEquals(java.util.Set.of("1,0", "-1,0", "0,1", "0,-1", "2,0", "-2,0", "0,2", "0,-2"), seen);
    }
}
