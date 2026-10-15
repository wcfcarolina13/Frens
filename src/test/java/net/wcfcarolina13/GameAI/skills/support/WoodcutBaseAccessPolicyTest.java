package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WoodcutBaseAccessPolicyTest {

    private record Zone(String label, UUID owner) {}

    /** Mirrors BotHomeService.isServerOwnedBaseZone without loading it under JUnit. */
    private static final Predicate<Zone> SERVER_BASE_ZONE =
            z -> z.label() != null && z.label().startsWith("base:") && z.owner() == null;

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Zone spawn() { return new Zone("base:spawn", null); }
    private static Zone playerBase() { return new Zone("base:home", PLAYER); }
    private static Zone userZone() { return new Zone("orchard", PLAYER); }

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
}
