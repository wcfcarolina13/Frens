package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static net.wcfcarolina13.GameAI.services.LegacyWorldStateRecoveryPolicy.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyWorldStateRecoveryPolicyTest {
    private static final String LEGACY = "New World";
    private static final String FIRST = "New World#aaaa";
    private static final String SECOND = "New World#bbbb";

    @Test
    void ordinaryReadsNeverAdoptLegacyEvenAfterRepeatedReadsAndReload() {
        var states = new HashMap<>(Map.of(LEGACY, "position"));
        for (int i = 0; i < 100; i++) {
            assertTrue(LegacyWorldStateRecoveryPolicy.current(states, FIRST).isEmpty());
            assertTrue(LegacyWorldStateRecoveryPolicy.current(states, SECOND).isEmpty());
        }
        var reloaded = new HashMap<>(states);
        assertTrue(LegacyWorldStateRecoveryPolicy.current(reloaded, FIRST).isEmpty());
        assertEquals(Map.of(LEGACY, "position"), reloaded);
    }

    @Test
    void exactConfirmationCopiesImmutableStateWithoutConsumingLegacy() {
        var states = new HashMap<>(Map.of(LEGACY, "position"));
        assertEquals(IMPORTED, LegacyWorldStateRecoveryPolicy.copyLegacy(states, LEGACY, FIRST, FIRST));
        assertEquals(Map.of(LEGACY, "position", FIRST, "position"), states);
        assertTrue(LegacyWorldStateRecoveryPolicy.current(states, SECOND).isEmpty());
    }

    @Test
    void anotherSavesConfirmationCannotAuthorizeThisSave() {
        var states = new HashMap<>(Map.of(LEGACY, "position"));
        assertEquals(WRONG_CONFIRMATION, LegacyWorldStateRecoveryPolicy.copyLegacy(states, LEGACY, FIRST, SECOND));
        assertEquals(WRONG_CONFIRMATION, LegacyWorldStateRecoveryPolicy.copyLegacy(states, LEGACY, FIRST, null));
        assertEquals(Map.of(LEGACY, "position"), states);
    }

    @Test
    void ownStateWinsAndRepeatedImportCannotOverwriteIt() {
        var states = new HashMap<>(Map.of(LEGACY, "old", FIRST, "current", SECOND, "other"));
        assertEquals("current", LegacyWorldStateRecoveryPolicy.current(states, FIRST).orElseThrow());
        assertEquals(TARGET_EXISTS, LegacyWorldStateRecoveryPolicy.copyLegacy(states, LEGACY, FIRST, FIRST));
        assertEquals(Map.of(LEGACY, "old", FIRST, "current", SECOND, "other"), states);
    }

    @Test
    void clearRemovesOnlyCurrentFingerprintAndNeverRevivesLegacy() {
        var states = new HashMap<>(Map.of(LEGACY, "old", FIRST, "current", SECOND, "other"));
        assertTrue(LegacyWorldStateRecoveryPolicy.clearCurrent(states, LEGACY, FIRST));
        assertEquals(Map.of(LEGACY, "old", SECOND, "other"), states);
        assertTrue(LegacyWorldStateRecoveryPolicy.current(states, FIRST).isEmpty());
        assertFalse(LegacyWorldStateRecoveryPolicy.clearCurrent(states, LEGACY, FIRST));
        assertFalse(LegacyWorldStateRecoveryPolicy.clearCurrent(states, LEGACY, LEGACY));
    }

    @Test
    void missingLegacyAndUnknownSaveRootRefuseWithoutMutation() {
        var states = new HashMap<>(Map.of(SECOND, "other"));
        assertEquals(NO_LEGACY, LegacyWorldStateRecoveryPolicy.copyLegacy(states, LEGACY, FIRST, FIRST));
        assertEquals(NO_LEGACY, LegacyWorldStateRecoveryPolicy.copyLegacy(null, LEGACY, FIRST, FIRST));
        for (String target : new String[]{LEGACY, "New World#unknown", "New World#", ""}) {
            assertEquals(INVALID_TARGET, LegacyWorldStateRecoveryPolicy.copyLegacy(states, LEGACY, target, target));
        }
        assertEquals(Map.of(SECOND, "other"), states);
    }

    @Test
    void failedImportRollbackKeepsAReplacementWrittenAtRemoval() {
        Map<String, String> states = new HashMap<>() {
            @Override public boolean remove(Object key, Object expected) {
                put(FIRST, "newer position");
                return super.remove(key, expected);
            }
        };
        states.put(LEGACY, "legacy position");
        assertEquals(IMPORTED, LegacyWorldStateRecoveryPolicy.copyLegacy(states, LEGACY, FIRST, FIRST));
        String imported = states.get(FIRST);
        assertFalse(LegacyWorldStateRecoveryPolicy.rollback(states, FIRST, imported));
        assertEquals("newer position", states.get(FIRST));
        assertEquals("legacy position", states.get(LEGACY));
    }

    @Test
    void failedImportRollbackRemovesOnlyItsCopyAndKeepsOtherSaves() {
        var states = new HashMap<>(Map.of(LEGACY, "legacy position", SECOND, "other save"));
        assertEquals(IMPORTED, LegacyWorldStateRecoveryPolicy.copyLegacy(states, LEGACY, FIRST, FIRST));
        assertTrue(LegacyWorldStateRecoveryPolicy.rollback(states, FIRST, states.get(FIRST)));
        assertEquals(Map.of(LEGACY, "legacy position", SECOND, "other save"), states);
        assertFalse(LegacyWorldStateRecoveryPolicy.rollback(states, FIRST, null));
        assertFalse(LegacyWorldStateRecoveryPolicy.rollback(null, FIRST, "legacy position"));
    }

    @Test
    void explicitImportsToTwoSavesAreIndependentAndKeepOriginal() {
        var states = new HashMap<>(Map.of(LEGACY, "position"));
        assertEquals(IMPORTED, LegacyWorldStateRecoveryPolicy.copyLegacy(states, LEGACY, FIRST, FIRST));
        assertEquals(IMPORTED, LegacyWorldStateRecoveryPolicy.copyLegacy(states, LEGACY, SECOND, SECOND));
        assertEquals(TARGET_EXISTS, LegacyWorldStateRecoveryPolicy.copyLegacy(states, LEGACY, FIRST, FIRST));
        assertEquals(3, states.size());
        assertEquals("position", states.get(LEGACY));
    }
}
