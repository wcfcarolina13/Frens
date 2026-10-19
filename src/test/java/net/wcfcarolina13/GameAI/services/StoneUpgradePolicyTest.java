package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

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
}
