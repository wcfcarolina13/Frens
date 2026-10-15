package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeDetectorProtectionPolicyTest {

    /** Stand-in for ProtectedZoneService.ProtectedZone (label + owner UUID). */
    private record Zone(String label, UUID owner) {}

    /**
     * Mirrors BotHomeService.isServerOwnedBaseZone (base: label AND null zone owner, which is
     * what parseZoneOwner yields for SERVER_OWNER_UUID) without loading that class under JUnit.
     */
    private static final Predicate<Zone> SERVER_BASE_ZONE =
            z -> z.label() != null && z.label().startsWith("base:") && z.owner() == null;

    /** Kind for classifyZones: a base: zone without an owner here is the SERVER-owned Spawn base. */
    private static final java.util.function.Function<Zone, WoodcutBaseAccessPolicy.ZoneKind> SERVER_BASE_KIND =
            z -> SERVER_BASE_ZONE.test(z) ? WoodcutBaseAccessPolicy.ZoneKind.SERVER_BASE : WoodcutBaseAccessPolicy.ZoneKind.OTHER;

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Zone server(String label) { return new Zone(label, null); }
    private static Zone player(String label) { return new Zone(label, PLAYER); }

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
    void woodcutBlocksFortificationBuffer() {
        TreeDetector.WoodcutProtectionDecision decision =
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(false, false, true, false);

        assertTrue(decision.blocked());
        assertEquals("fort-buffer", decision.reason());
    }

    @Test
    void explicitProtectedZonesWinFirst() {
        TreeDetector.WoodcutProtectionDecision decision =
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(true, true, true, true);

        assertTrue(decision.blocked());
        assertEquals("admin-zone", decision.reason());
    }

    @Test
    void freshWorldServerSpawnBaseBlocksWoodcutWhenToggleOff() {
        // 2026-10-14 ruling: the automatic Spawn base protects its trees like a player base.
        WoodcutBaseAccessPolicy.ZoneVerdict verdict = WoodcutBaseAccessPolicy.classifyZones(
                List.of(server("base:spawn")), SERVER_BASE_KIND, false);
        TreeDetector.WoodcutProtectionDecision decision = VillageRuntimeProtectionPolicy.decideWoodcutProtection(
                verdict == WoodcutBaseAccessPolicy.ZoneVerdict.OTHER, false,
                verdict == WoodcutBaseAccessPolicy.ZoneVerdict.SPAWN_BASE, false, false);

        assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.SPAWN_BASE, verdict);
        assertTrue(decision.blocked());
        assertEquals("spawn-base", decision.reason());
    }

    @Test
    void freshWorldServerSpawnBaseAllowsWoodcutWhenToggleOn() {
        WoodcutBaseAccessPolicy.ZoneVerdict verdict = WoodcutBaseAccessPolicy.classifyZones(
                List.of(server("base:spawn")), SERVER_BASE_KIND, true);
        TreeDetector.WoodcutProtectionDecision decision = VillageRuntimeProtectionPolicy.decideWoodcutProtection(
                verdict == WoodcutBaseAccessPolicy.ZoneVerdict.OTHER, false,
                verdict == WoodcutBaseAccessPolicy.ZoneVerdict.SPAWN_BASE, false, false);

        assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.NONE, verdict);
        assertFalse(decision.blocked());
        assertEquals("none", decision.reason());
    }

    @Test
    void playerClaimsOutrankSpawnBaseReason() {
        assertEquals("admin-zone",
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(true, false, true, false, false).reason());
        assertEquals("base-radius",
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(false, true, true, false, false).reason());
        assertEquals("spawn-base",
                VillageRuntimeProtectionPolicy.decideWoodcutProtection(false, false, true, true, true).reason());
    }

    @Test
    void playerBaseAutoZoneStillBlocksWoodcut() {
        assertTrue(VillageRuntimeProtectionPolicy.anyNonExemptZone(List.of(player("base:home")), SERVER_BASE_ZONE));
    }

    @Test
    void userDrawnZoneStillBlocksWoodcut() {
        assertTrue(VillageRuntimeProtectionPolicy.anyNonExemptZone(List.of(player("my-garden")), SERVER_BASE_ZONE));
        // An owner-less user zone is not a base zone, so it blocks too.
        assertTrue(VillageRuntimeProtectionPolicy.anyNonExemptZone(List.of(server("my-garden")), SERVER_BASE_ZONE));
    }

    @Test
    void anyNonExemptZoneOverlappingServerBaseStillBlocks() {
        assertTrue(VillageRuntimeProtectionPolicy.anyNonExemptZone(
                List.of(server("base:spawn"), player("orchard")), SERVER_BASE_ZONE));
        assertTrue(VillageRuntimeProtectionPolicy.anyNonExemptZone(
                List.of(player("base:home"), server("base:spawn")), SERVER_BASE_ZONE));
    }

    @Test
    void onlyServerBaseZonesOverlappingDoNotBlock() {
        assertFalse(VillageRuntimeProtectionPolicy.anyNonExemptZone(
                List.of(server("base:spawn"), server("base:admin")), SERVER_BASE_ZONE));
    }

    @Test
    void noContainingZonesDoNotBlock() {
        assertFalse(VillageRuntimeProtectionPolicy.anyNonExemptZone(List.<Zone>of(), SERVER_BASE_ZONE));
        assertFalse(VillageRuntimeProtectionPolicy.anyNonExemptZone(null, SERVER_BASE_ZONE));
    }

    @Test
    void nullOrBlankLabelIsTreatedAsNonExempt() {
        assertTrue(VillageRuntimeProtectionPolicy.anyNonExemptZone(Arrays.asList(server("base:spawn"), null), SERVER_BASE_ZONE));
        assertTrue(VillageRuntimeProtectionPolicy.anyNonExemptZone(List.of(server(" ")), SERVER_BASE_ZONE));
        assertTrue(VillageRuntimeProtectionPolicy.anyNonExemptZone(List.of(server(null)), SERVER_BASE_ZONE));
    }

    @Test
    void prefixMustLeadTheLabel() {
        assertTrue(VillageRuntimeProtectionPolicy.anyNonExemptZone(List.of(server("my base:spawn")), SERVER_BASE_ZONE));
    }
}
