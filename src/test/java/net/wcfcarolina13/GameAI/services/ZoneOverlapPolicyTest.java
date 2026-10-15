package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.ZoneOverlapPolicy.ZoneVerdict;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoneOverlapPolicyTest {

    private static final ZoneVerdict SPAWN_REFUSES = new ZoneVerdict(true, true, false);
    private static final ZoneVerdict HOST_BASE_GRANTS = new ZoneVerdict(false, false, true);
    private static final ZoneVerdict OTHER_PLAYER_BASE_REFUSES = new ZoneVerdict(true, false, false);
    private static final ZoneVerdict PROTECTED_AREA_REFUSES = new ZoneVerdict(true, false, false);
    private static final ZoneVerdict PUBLIC_AREA = new ZoneVerdict(false, false, false);

    @Test
    void hostBaseOverSpawnAllowsHostsBot() {
        assertEquals(-1, ZoneOverlapPolicy.firstRefusingIndex(List.of(SPAWN_REFUSES, HOST_BASE_GRANTS)));
        assertEquals(-1, ZoneOverlapPolicy.firstRefusingIndex(List.of(HOST_BASE_GRANTS, SPAWN_REFUSES)));
    }

    @Test
    void spawnOnlyRefuses() {
        assertEquals(0, ZoneOverlapPolicy.firstRefusingIndex(List.of(SPAWN_REFUSES)));
    }

    @Test
    void playerBaseRefusingPlusSpawnRefuses() {
        assertEquals(0, ZoneOverlapPolicy.firstRefusingIndex(List.of(SPAWN_REFUSES, OTHER_PLAYER_BASE_REFUSES)));
        assertEquals(0, ZoneOverlapPolicy.firstRefusingIndex(List.of(OTHER_PLAYER_BASE_REFUSES, SPAWN_REFUSES)));
    }

    @Test
    void protectedAreaRefusingOverHostBaseStillRefuses() {
        // A user-drawn protected area is not a server base, so a granting host base cannot hide it.
        assertEquals(1, ZoneOverlapPolicy.firstRefusingIndex(List.of(HOST_BASE_GRANTS, PROTECTED_AREA_REFUSES)));
        assertEquals(2, ZoneOverlapPolicy.firstRefusingIndex(
                List.of(SPAWN_REFUSES, HOST_BASE_GRANTS, PROTECTED_AREA_REFUSES)));
    }

    @Test
    void publicAreaIsNotAnExplicitGrantForSpawn() {
        assertEquals(1, ZoneOverlapPolicy.firstRefusingIndex(List.of(PUBLIC_AREA, SPAWN_REFUSES)));
    }

    @Test
    void serverZoneCannotGrantAnotherServerZone() {
        ZoneVerdict serverGrant = new ZoneVerdict(false, true, true);
        assertEquals(1, ZoneOverlapPolicy.firstRefusingIndex(List.of(serverGrant, SPAWN_REFUSES)));
    }

    @Test
    void alliedPlayersOverlappingBasesStillRefuse() {
        // Alliances grant nothing on zones: the ally's base refuses even where my base grants.
        assertEquals(1, ZoneOverlapPolicy.firstRefusingIndex(List.of(HOST_BASE_GRANTS, OTHER_PLAYER_BASE_REFUSES)));
    }

    @Test
    void emptyOrNullAllows() {
        assertEquals(-1, ZoneOverlapPolicy.firstRefusingIndex(List.of()));
        assertEquals(-1, ZoneOverlapPolicy.firstRefusingIndex(null));
        assertEquals(-1, ZoneOverlapPolicy.firstRefusingIndex(Arrays.asList(null, PUBLIC_AREA)));
    }
}
