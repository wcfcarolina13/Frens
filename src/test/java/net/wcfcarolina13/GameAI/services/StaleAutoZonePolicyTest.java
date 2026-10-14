package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaleAutoZonePolicyTest {

    @Test
    void autoZoneLabelNormalizesLikeBaseKeys() {
        assertEquals("base:spawn", StaleAutoZonePolicy.autoZoneLabel("  Spawn "));
        assertEquals("base:", StaleAutoZonePolicy.autoZoneLabel(null));
    }

    @Test
    void removesBaseZoneWhoseBaseIsGone() {
        assertEquals(Set.of("base:old farm"),
                StaleAutoZonePolicy.toRemove(List.of("base:spawn", "base:old farm"), List.of("Spawn")));
    }

    @Test
    void neverRemovesUserZones() {
        Set<String> out = StaleAutoZonePolicy.toRemove(
                List.of("zone-1", "my house", "Base:caps", "basement"), List.of());
        assertTrue(out.isEmpty(), out.toString());
    }

    @Test
    void noBasesRemovesEveryBaseZoneOnly() {
        assertEquals(Set.of("base:nirn keep", "base:spawn"),
                StaleAutoZonePolicy.toRemove(List.of("base:spawn", "zone-1", "base:nirn keep"), null));
    }

    @Test
    void currentBasesMatchCaseAndWhitespaceInsensitively() {
        assertTrue(StaleAutoZonePolicy.toRemove(List.of("base:my base"), List.of("  My Base ")).isEmpty());
    }

    @Test
    void emptyOrNullInputsAreSafe() {
        assertTrue(StaleAutoZonePolicy.toRemove(null, List.of("a")).isEmpty());
        assertTrue(StaleAutoZonePolicy.toRemove(List.of(), null).isEmpty());
        assertFalse(StaleAutoZonePolicy.isAutoZoneLabel(null));
        assertTrue(StaleAutoZonePolicy.toRemove(java.util.Arrays.asList(null, "zone-2"), List.of()).isEmpty());
    }
}
