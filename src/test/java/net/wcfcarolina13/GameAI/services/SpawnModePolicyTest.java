package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpawnModePolicyTest {

    @Test
    void removedTrainingModeLoadsAsAdmin() {
        assertEquals("admin", SpawnModePolicy.normalizeOrNull("training"));
        assertEquals("admin", SpawnModePolicy.normalizeOrNull("train"));
        assertEquals("admin", SpawnModePolicy.normalizeOrNull(" Training "));
    }

    @Test
    void liveModesAndLegacyAliasesKeepTheirMeaning() {
        assertEquals("admin", SpawnModePolicy.normalizeOrNull("admin"));
        assertEquals("admin", SpawnModePolicy.normalizeOrNull("play"));
        assertEquals("questing", SpawnModePolicy.normalizeOrNull("questing"));
        assertEquals("questing", SpawnModePolicy.normalizeOrNull("QUEST"));
    }

    @Test
    void unknownOrBlankIsNull() {
        assertNull(SpawnModePolicy.normalizeOrNull("garbage"));
        assertNull(SpawnModePolicy.normalizeOrNull(""));
        assertNull(SpawnModePolicy.normalizeOrNull(null));
    }
}
