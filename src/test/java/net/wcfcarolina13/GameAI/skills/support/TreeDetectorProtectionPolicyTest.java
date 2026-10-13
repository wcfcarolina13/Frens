package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeDetectorProtectionPolicyTest {

    /** Mirrors BotHomeService.isBaseAutoZoneLabel without loading that class under JUnit. */
    private static final Predicate<String> BASE_AUTO_ZONE = label -> label.startsWith("base:");

    @Test
    void woodcutAllowsWildernessWhenNoSavedProtectionApplies() {
        TreeDetector.WoodcutProtectionDecision decision =
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(false, false, false);

        assertFalse(decision.blocked());
        assertEquals("none", decision.reason());
    }

    @Test
    void woodcutBlocksMappedVillageHull() {
        TreeDetector.WoodcutProtectionDecision decision =
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(false, false, true);

        assertTrue(decision.blocked());
        assertEquals("mapped-village", decision.reason());
    }

    @Test
    void woodcutBlocksFortificationBuffer() {
        TreeDetector.WoodcutProtectionDecision decision =
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(false, true, false);

        assertTrue(decision.blocked());
        assertEquals("fort-buffer", decision.reason());
    }

    @Test
    void explicitProtectedZonesWinFirst() {
        TreeDetector.WoodcutProtectionDecision decision =
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(true, true, true);

        assertTrue(decision.blocked());
        assertEquals("admin-zone", decision.reason());
    }

    @Test
    void freshWorldSpawnBaseAutoZoneDoesNotBlockWoodcut() {
        boolean explicit = VillageRuntimeProtectionPolicy.anyNonBaseZone(List.of("base:spawn"), BASE_AUTO_ZONE);
        TreeDetector.WoodcutProtectionDecision decision =
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(explicit, false, false);

        assertFalse(explicit);
        assertFalse(decision.blocked());
        assertEquals("none", decision.reason());
    }

    @Test
    void userDrawnZoneStillBlocksWoodcut() {
        assertTrue(VillageRuntimeProtectionPolicy.anyNonBaseZone(List.of("my-garden"), BASE_AUTO_ZONE));
    }

    @Test
    void userDrawnZoneOverlappingBaseZoneStillBlocks() {
        assertTrue(VillageRuntimeProtectionPolicy.anyNonBaseZone(List.of("base:spawn", "orchard"), BASE_AUTO_ZONE));
        assertTrue(VillageRuntimeProtectionPolicy.anyNonBaseZone(List.of("orchard", "base:home"), BASE_AUTO_ZONE));
    }

    @Test
    void onlyBaseZonesOverlappingDoNotBlock() {
        assertFalse(VillageRuntimeProtectionPolicy.anyNonBaseZone(List.of("base:spawn", "base:home"), BASE_AUTO_ZONE));
    }

    @Test
    void noContainingZonesDoNotBlock() {
        assertFalse(VillageRuntimeProtectionPolicy.anyNonBaseZone(List.of(), BASE_AUTO_ZONE));
        assertFalse(VillageRuntimeProtectionPolicy.anyNonBaseZone(null, BASE_AUTO_ZONE));
    }

    @Test
    void blankOrNullLabelIsTreatedAsUserDrawn() {
        assertTrue(VillageRuntimeProtectionPolicy.anyNonBaseZone(Arrays.asList("base:spawn", null), BASE_AUTO_ZONE));
        assertTrue(VillageRuntimeProtectionPolicy.anyNonBaseZone(List.of(" "), BASE_AUTO_ZONE));
    }

    @Test
    void prefixMustLeadTheLabel() {
        assertTrue(VillageRuntimeProtectionPolicy.anyNonBaseZone(List.of("my base:spawn"), BASE_AUTO_ZONE));
    }
}
