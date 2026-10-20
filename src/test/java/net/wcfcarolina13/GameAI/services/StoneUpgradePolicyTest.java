package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoneUpgradePolicyTest {

    @Test
    void onlyWoodenIsUpgraded() {
        assertTrue(StoneUpgradePolicy.wants(true, false));
    }

    @Test
    void missingToolCountsAsUpgradeable() {
        // Field (finding 9): a bot with no axe never got a stone one, so needAxe never cleared.
        assertTrue(StoneUpgradePolicy.wants(false, true));
    }

    @Test
    void betterTierIsLeftAlone() {
        assertFalse(StoneUpgradePolicy.wants(false, false));
    }

    @Test
    void bothFlagsStillWants() {
        assertTrue(StoneUpgradePolicy.wants(true, true));
    }

    // ── batch reserve (finding 57, 1.1.279) ─────────────────────────────────────────────────

    @Test
    void batchReserveSumsStoneHeadsAndSticks() {
        assertEquals(new StoneUpgradePolicy.BatchReserve(9, 7), StoneUpgradePolicy.batchReserve(true, true, true, true));
        assertEquals(new StoneUpgradePolicy.BatchReserve(6, 5), StoneUpgradePolicy.batchReserve(false, true, true, true));
        assertEquals(new StoneUpgradePolicy.BatchReserve(4, 4), StoneUpgradePolicy.batchReserve(false, false, true, true));
        assertEquals(new StoneUpgradePolicy.BatchReserve(1, 2), StoneUpgradePolicy.batchReserve(false, false, false, true));
    }

    @Test
    void pickaxeOnlyReservesThreeStoneAndTwoSticks() {
        assertEquals(new StoneUpgradePolicy.BatchReserve(3, 2), StoneUpgradePolicy.batchReserve(true, false, false, false));
    }

    @Test
    void nothingWantedReservesNothing() {
        assertTrue(StoneUpgradePolicy.batchReserve(false, false, false, false).isEmpty());
        assertTrue(StoneUpgradePolicy.BatchReserve.NONE.isEmpty());
    }

    @Test
    void planksCoverOnlyTheStickShortfall() {
        assertEquals(0, StoneUpgradePolicy.planksForSticks(7, 7));
        assertEquals(0, StoneUpgradePolicy.planksForSticks(7, 20));
        assertEquals(2, StoneUpgradePolicy.planksForSticks(7, 3));
        assertEquals(4, StoneUpgradePolicy.planksForSticks(7, 0));
        assertEquals(2, StoneUpgradePolicy.planksForSticks(1, -5));
    }
}
