package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WoodcutBaseAccessPolicyTest {

    /** Stand-in zone: label, zone owner UUID, and the stored owner of its base (null = no base found). */
    private record Zone(String label, UUID owner, String baseOwner) {
        Zone(String label, UUID owner) { this(label, owner, owner == null ? "SERVER" : owner.toString()); }
    }

    /** Mirrors BotHomeService.baseZoneKind without loading it under JUnit. */
    private static final Function<Zone, WoodcutBaseAccessPolicy.ZoneKind> SERVER_BASE_ZONE =
            z -> WoodcutBaseAccessPolicy.zoneKind(z.label() != null && z.label().startsWith("base:"),
                    z.owner() != null, z.baseOwner() != null, z.baseOwner(), null);

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Zone spawn() { return new Zone("base:spawn", null); }
    private static Zone playerBase() { return new Zone("base:home", PLAYER); }
    private static Zone userZone() { return new Zone("orchard", PLAYER); }
    /** Legacy base (Nirn's home, deep dark 1): registered, blank owner, so its zone has no owner. */
    private static Zone legacyBase() { return new Zone("base:home", null, ""); }

    @Test
    void detectionExemptsServerBaseOnlyWhenToggleOn() {
        assertFalse(WoodcutBaseAccessPolicy.detectionExempt(true, false));
        assertTrue(WoodcutBaseAccessPolicy.detectionExempt(true, true));
    }

    @Test
    void detectionNeverExemptsPlayerBase() {
        assertFalse(WoodcutBaseAccessPolicy.detectionExempt(false, false));
        assertFalse(WoodcutBaseAccessPolicy.detectionExempt(false, true));
    }

    @Test
    void miningInServerBaseNeedsToggleAndTreeBlock() {
        assertFalse(WoodcutBaseAccessPolicy.miningExempt(true, true, false));
        assertTrue(WoodcutBaseAccessPolicy.miningExempt(true, true, true));
    }

    @Test
    void nonTreeBlockInServerBaseNeverAllowedByToggle() {
        assertFalse(WoodcutBaseAccessPolicy.miningExempt(true, false, true));
        assertFalse(WoodcutBaseAccessPolicy.miningExempt(true, false, false));
    }

    @Test
    void playerBaseZoneAlwaysRefusesMining() {
        assertFalse(WoodcutBaseAccessPolicy.miningExempt(false, true, true));
        assertFalse(WoodcutBaseAccessPolicy.miningExempt(false, true, false));
        assertFalse(WoodcutBaseAccessPolicy.miningExempt(false, false, true));
    }

    @Test
    void classifyZonesSpawnOnly() {
        assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.SPAWN_BASE,
                WoodcutBaseAccessPolicy.classifyZones(List.of(spawn()), SERVER_BASE_ZONE, false));
        assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.NONE,
                WoodcutBaseAccessPolicy.classifyZones(List.of(spawn()), SERVER_BASE_ZONE, true));
    }

    @Test
    void classifyZonesPlayerOrUserZoneAlwaysOther() {
        for (boolean toggle : new boolean[]{false, true}) {
            assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.OTHER,
                    WoodcutBaseAccessPolicy.classifyZones(List.of(playerBase()), SERVER_BASE_ZONE, toggle));
            assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.OTHER,
                    WoodcutBaseAccessPolicy.classifyZones(List.of(spawn(), userZone()), SERVER_BASE_ZONE, toggle));
            assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.OTHER,
                    WoodcutBaseAccessPolicy.classifyZones(Arrays.asList(spawn(), null), SERVER_BASE_ZONE, toggle));
        }
    }

    @Test
    void classifyZonesEmptyIsNone() {
        assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.NONE,
                WoodcutBaseAccessPolicy.classifyZones(List.of(), SERVER_BASE_ZONE, false));
        assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.NONE,
                WoodcutBaseAccessPolicy.classifyZones(null, SERVER_BASE_ZONE, false));
    }

    @Test
    void serverOwnerSentinelMatchesOnlyServer() {
        assertTrue(WoodcutBaseAccessPolicy.isServerOwnedBaseOwner("SERVER"));
        assertFalse(WoodcutBaseAccessPolicy.isServerOwnedBaseOwner(PLAYER.toString()));
        assertFalse(WoodcutBaseAccessPolicy.isServerOwnedBaseOwner(null));
        assertFalse(WoodcutBaseAccessPolicy.isServerOwnedBaseOwner(""));
    }

    @Test
    void zoneKindServerOnlyWhenBaseOwnerIsLiterallyServer() {
        assertEquals(WoodcutBaseAccessPolicy.ZoneKind.SERVER_BASE,
                WoodcutBaseAccessPolicy.zoneKind(true, false, true, "SERVER", "Server"));
        // Legacy bases with a blank or missing owner are not server bases.
        assertEquals(WoodcutBaseAccessPolicy.ZoneKind.LEGACY_BASE,
                WoodcutBaseAccessPolicy.zoneKind(true, false, true, null, null));
        assertEquals(WoodcutBaseAccessPolicy.ZoneKind.LEGACY_BASE,
                WoodcutBaseAccessPolicy.zoneKind(true, false, true, " ", null));
        // The registered base's owner decides, even when the zone name says Server.
        assertEquals(WoodcutBaseAccessPolicy.ZoneKind.LEGACY_BASE,
                WoodcutBaseAccessPolicy.zoneKind(true, false, true, null, "Server"));
    }

    @Test
    void zoneKindStaleZoneFallsBackToOwnerName() {
        assertEquals(WoodcutBaseAccessPolicy.ZoneKind.SERVER_BASE,
                WoodcutBaseAccessPolicy.zoneKind(true, false, false, null, "Server"));
        assertEquals(WoodcutBaseAccessPolicy.ZoneKind.LEGACY_BASE,
                WoodcutBaseAccessPolicy.zoneKind(true, false, false, null, null));
        assertEquals(WoodcutBaseAccessPolicy.ZoneKind.LEGACY_BASE,
                WoodcutBaseAccessPolicy.zoneKind(true, false, false, null, "server"));
    }

    @Test
    void zoneKindPlayerOrUserZoneIsOther() {
        // A player named "Server" has a real UUID, so the zone has an owner.
        assertEquals(WoodcutBaseAccessPolicy.ZoneKind.OTHER,
                WoodcutBaseAccessPolicy.zoneKind(true, true, true, PLAYER.toString(), "Server"));
        assertEquals(WoodcutBaseAccessPolicy.ZoneKind.OTHER,
                WoodcutBaseAccessPolicy.zoneKind(false, false, false, null, "Server"));
    }

    @Test
    void legacyBaseZoneIsNeverSpawnBaseAndSkippedByDetectionAsBefore() {
        for (boolean toggle : new boolean[]{false, true}) {
            assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.NONE,
                    WoodcutBaseAccessPolicy.classifyZones(List.of(legacyBase()), SERVER_BASE_ZONE, toggle));
        }
        assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.SPAWN_BASE,
                WoodcutBaseAccessPolicy.classifyZones(List.of(legacyBase(), spawn()), SERVER_BASE_ZONE, false));
        assertEquals(WoodcutBaseAccessPolicy.ZoneVerdict.OTHER,
                WoodcutBaseAccessPolicy.classifyZones(List.of(legacyBase(), userZone()), SERVER_BASE_ZONE, true));
    }

    @Test
    void legacyBaseZoneNeverOpenedForMiningByToggle() {
        boolean serverOwned = SERVER_BASE_ZONE.apply(legacyBase()) == WoodcutBaseAccessPolicy.ZoneKind.SERVER_BASE;
        assertFalse(WoodcutBaseAccessPolicy.miningExempt(serverOwned, true, true));
    }

    // ── 1.1.268: own scaffold in the Spawn base ─────────────────────────────

    private static final WoodcutBaseAccessPolicy.MutationKind[] ALL_KINDS = WoodcutBaseAccessPolicy.MutationKind.values();

    @Test
    void mutationExemptTreeBlockMatchesMiningExempt() {
        boolean[] b = {false, true};
        for (boolean zone : b) {
            for (boolean toggle : b) {
                for (boolean owned : b) {
                    for (boolean material : b) {
                        assertEquals(WoodcutBaseAccessPolicy.miningExempt(zone, true, toggle),
                                WoodcutBaseAccessPolicy.mutationExempt(zone,
                                        WoodcutBaseAccessPolicy.MutationKind.TREE_BLOCK, owned, material, toggle));
                        assertFalse(WoodcutBaseAccessPolicy.mutationExempt(zone,
                                WoodcutBaseAccessPolicy.MutationKind.OTHER, owned, material, toggle));
                    }
                }
            }
        }
    }

    @Test
    void ownScaffoldPlaceNeedsServerZoneToggleAndMaterial() {
        WoodcutBaseAccessPolicy.MutationKind place = WoodcutBaseAccessPolicy.MutationKind.OWN_SCAFFOLD_PLACE;
        assertTrue(WoodcutBaseAccessPolicy.mutationExempt(true, place, false, true, true));
        assertFalse(WoodcutBaseAccessPolicy.mutationExempt(true, place, false, true, false));
        assertFalse(WoodcutBaseAccessPolicy.mutationExempt(true, place, false, false, true));
        assertFalse(WoodcutBaseAccessPolicy.mutationExempt(false, place, true, true, true));
    }

    @Test
    void ownScaffoldBreakNeedsOwnershipAndMaterialButNotToggle() {
        WoodcutBaseAccessPolicy.MutationKind brk = WoodcutBaseAccessPolicy.MutationKind.OWN_SCAFFOLD_BREAK;
        assertTrue(WoodcutBaseAccessPolicy.mutationExempt(true, brk, true, true, true));
        // Admin turned the toggle off mid-fell: the bot can still remove what it placed.
        assertTrue(WoodcutBaseAccessPolicy.mutationExempt(true, brk, true, true, false));
        assertFalse(WoodcutBaseAccessPolicy.mutationExempt(true, brk, false, true, true));
        assertFalse(WoodcutBaseAccessPolicy.mutationExempt(true, brk, true, false, true));
        assertFalse(WoodcutBaseAccessPolicy.mutationExempt(false, brk, true, true, true));
    }

    @Test
    void nothingIsExemptOutsideAServerBaseZoneOrWithNullKind() {
        for (WoodcutBaseAccessPolicy.MutationKind kind : ALL_KINDS) {
            assertFalse(WoodcutBaseAccessPolicy.mutationExempt(false, kind, true, true, true));
        }
        assertFalse(WoodcutBaseAccessPolicy.mutationExempt(true, null, true, true, true));
    }

    @Test
    void overheadAbortExemptOnlyForOwnScaffoldTreeOverheadInServerOnlyBaseWithToggle() {
        assertTrue(WoodcutBaseAccessPolicy.overheadBaseAbortExempt(true, true, true, true));
        assertFalse(WoodcutBaseAccessPolicy.overheadBaseAbortExempt(false, true, true, true));
        assertFalse(WoodcutBaseAccessPolicy.overheadBaseAbortExempt(true, false, true, true));
        assertFalse(WoodcutBaseAccessPolicy.overheadBaseAbortExempt(true, true, false, true));
        assertFalse(WoodcutBaseAccessPolicy.overheadBaseAbortExempt(true, true, true, false));
    }
}
