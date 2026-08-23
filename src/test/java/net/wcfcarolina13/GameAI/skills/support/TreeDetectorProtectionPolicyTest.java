package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeDetectorProtectionPolicyTest {

    @Test
    void woodcutAllowsWildernessWhenNoSavedProtectionApplies() {
        TreeDetector.WoodcutProtectionDecision decision =
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(false, false, false, false);

        assertFalse(decision.blocked());
        assertEquals("none", decision.reason());
    }

    @Test
    void woodcutBlocksMappedVillageHull() {
        TreeDetector.WoodcutProtectionDecision decision =
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(false, false, false, true);

        assertTrue(decision.blocked());
        assertEquals("mapped-village", decision.reason());
    }

    @Test
    void woodcutBlocksSavedBaseRadius() {
        TreeDetector.WoodcutProtectionDecision decision =
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(false, true, false, false);

        assertTrue(decision.blocked());
        assertEquals("base-radius", decision.reason());
    }

    @Test
    void explicitProtectedZonesWinFirst() {
        TreeDetector.WoodcutProtectionDecision decision =
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(true, true, true, true);

        assertTrue(decision.blocked());
        assertEquals("admin-zone", decision.reason());
    }
}
