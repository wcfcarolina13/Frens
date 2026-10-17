package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.ShelterRestorePolicy.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShelterRestorePolicyTest {

    private static final String OW = "minecraft:overworld";
    private static final int[] CELL = {10, 64, -3};

    @Test
    void persistedTypesExcludeJoinEnclosed() {
        assertTrue(ShelterRestorePolicy.persisted("WALL_OFF"));
        assertTrue(ShelterRestorePolicy.persisted("DIG_DOWN"));
        assertTrue(ShelterRestorePolicy.persisted("CLIFF"));
        assertTrue(ShelterRestorePolicy.persisted("VILLAGE_HOUSE"));
        assertFalse(ShelterRestorePolicy.persisted("JOIN_ENCLOSED"));
        assertFalse(ShelterRestorePolicy.persisted(null));
        assertFalse(ShelterRestorePolicy.persisted("wall_off"));
    }

    @Test
    void precheckPassesOnTheExactCell() {
        assertNull(ShelterRestorePolicy.precheck(true, "DIG_DOWN", OW, OW, CELL, 10, 64, -3));
    }

    @Test
    void precheckWithoutARecord() {
        assertEquals(Verdict.NO_RECORD, ShelterRestorePolicy.precheck(false, null, null, OW, null, 0, 0, 0));
    }

    @Test
    void precheckRefusesUnknownType() {
        assertEquals(Verdict.UNKNOWN_TYPE, ShelterRestorePolicy.precheck(true, "JOIN_ENCLOSED", OW, OW, CELL, 10, 64, -3));
        assertEquals(Verdict.UNKNOWN_TYPE, ShelterRestorePolicy.precheck(true, null, OW, OW, CELL, 10, 64, -3));
    }

    @Test
    void precheckRefusesAnotherDimension() {
        assertEquals(Verdict.WRONG_DIMENSION,
                ShelterRestorePolicy.precheck(true, "CLIFF", "minecraft:the_nether", OW, CELL, 10, 64, -3));
        assertEquals(Verdict.WRONG_DIMENSION, ShelterRestorePolicy.precheck(true, "CLIFF", null, OW, CELL, 10, 64, -3));
    }

    @Test
    void precheckRefusesAnyOtherCell() {
        assertEquals(Verdict.MOVED, ShelterRestorePolicy.precheck(true, "WALL_OFF", OW, OW, CELL, 11, 64, -3));
        assertEquals(Verdict.MOVED, ShelterRestorePolicy.precheck(true, "WALL_OFF", OW, OW, CELL, 10, 65, -3));
        assertEquals(Verdict.MOVED, ShelterRestorePolicy.precheck(true, "WALL_OFF", OW, OW, CELL, 10, 64, -2));
        assertEquals(Verdict.MOVED, ShelterRestorePolicy.precheck(true, "WALL_OFF", OW, OW, null, 10, 64, -3));
        assertEquals(Verdict.MOVED, ShelterRestorePolicy.precheck(true, "WALL_OFF", OW, OW, new int[]{10, 64}, 10, 64, -3));
    }

    @Test
    void afterAssessRestoresAValidShelterOfAnyType() {
        assertEquals(Verdict.RESTORE, ShelterRestorePolicy.afterAssess(true, false, "valid", false));
        assertEquals(Verdict.RESTORE, ShelterRestorePolicy.afterAssess(true, true, "valid", true));
    }

    @Test
    void afterAssessKeepsASealedWallOffWithAHostileOutside() {
        assertEquals(Verdict.RESTORE, ShelterRestorePolicy.afterAssess(false, true, "hostile_exposed", true));
        assertEquals(Verdict.INVALID, ShelterRestorePolicy.afterAssess(false, true, "hostile_exposed", false));
        assertEquals(Verdict.INVALID, ShelterRestorePolicy.afterAssess(false, true, "entrance_unsealed", false));
        assertEquals(Verdict.INVALID, ShelterRestorePolicy.afterAssess(false, true, "open_sky", true));
    }

    @Test
    void afterAssessRefusesOtherInvalidShelters() {
        assertEquals(Verdict.INVALID, ShelterRestorePolicy.afterAssess(false, false, "hostile_exposed", true));
        assertEquals(Verdict.INVALID, ShelterRestorePolicy.afterAssess(false, false, "entrance_unsealed", false));
    }
}
