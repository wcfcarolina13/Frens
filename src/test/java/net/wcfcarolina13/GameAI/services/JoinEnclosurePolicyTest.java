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
    void lingerHoldsForCommanderBaseOrHouse() {
        assertTrue(JoinEnclosurePolicy.lingerStaysAtNonFunctionalPosition(true, false, false));
        assertTrue(JoinEnclosurePolicy.lingerStaysAtNonFunctionalPosition(false, true, false));
        assertTrue(JoinEnclosurePolicy.lingerStaysAtNonFunctionalPosition(false, false, true));
        assertFalse(JoinEnclosurePolicy.lingerStaysAtNonFunctionalPosition(false, false, false));
    }
}
