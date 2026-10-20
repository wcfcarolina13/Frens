package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the shared recent-tree-soil ledger (finding 52). No net.minecraft types. */
class RecentTreeSoilLedgerTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final long TTL = RecentTreeSoilLedger.DEFAULT_TTL_MS;

    @Test
    void recordsTheWholeRingUnderTheBase() {
        RecentTreeSoilLedger ledger = new RecentTreeSoilLedger();
        ledger.recordSoilRing(OVERWORLD, 246, 120, 415, 0L);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                assertTrue(ledger.contains(OVERWORLD, 246 + dx, 119, 415 + dz, 1L));
            }
        }
        assertEquals(9, ledger.size());
        // Not the base level, not two below, not two blocks out.
        assertFalse(ledger.contains(OVERWORLD, 246, 120, 415, 1L));
        assertFalse(ledger.contains(OVERWORLD, 246, 118, 415, 1L));
        assertFalse(ledger.contains(OVERWORLD, 248, 119, 415, 1L));
        assertFalse(ledger.contains(OVERWORLD, 246, 119, 413, 1L));
    }

    @Test
    void entriesExpireAfterTheTtl() {
        RecentTreeSoilLedger ledger = new RecentTreeSoilLedger();
        ledger.recordSoilRing(OVERWORLD, 0, 64, 0, 1_000L);
        assertTrue(ledger.contains(OVERWORLD, 0, 63, 0, 1_000L + TTL - 1));
        assertFalse(ledger.contains(OVERWORLD, 0, 63, 0, 1_000L + TTL));
        ledger.purgeExpired(1_000L + TTL);
        assertEquals(0, ledger.size());
    }

    @Test
    void aLaterFellingExtendsOverlappingCells() {
        RecentTreeSoilLedger ledger = new RecentTreeSoilLedger();
        ledger.recordSoilRing(OVERWORLD, 0, 64, 0, 0L);
        ledger.recordSoilRing(OVERWORLD, 1, 64, 0, 5_000L);
        // (0,63,0) is in both rings: the later expiry wins.
        assertTrue(ledger.contains(OVERWORLD, 0, 63, 0, TTL + 1));
        // (-1,63,0) is only in the first ring.
        assertFalse(ledger.contains(OVERWORLD, -1, 63, 0, TTL + 1));
        // An earlier record never shortens a later one.
        ledger.recordSoilRing(OVERWORLD, 1, 64, 0, 0L);
        assertTrue(ledger.contains(OVERWORLD, 1, 63, 0, TTL + 1));
    }

    @Test
    void otherDimensionIsADifferentCell() {
        RecentTreeSoilLedger ledger = new RecentTreeSoilLedger();
        ledger.recordSoilRing(OVERWORLD, 10, 64, 10, 0L);
        assertFalse(ledger.contains(NETHER, 10, 63, 10, 1L));
        assertFalse(ledger.contains(null, 10, 63, 10, 1L));
        ledger.recordSoilRing(null, 10, 64, 10, 0L);
        assertEquals(9, ledger.size());
    }

    @Test
    void ringGeometryIsTheThreeByThreeOneBelow() {
        assertTrue(RecentTreeSoilLedger.soilRingContains(5, 70, 5, 5, 69, 5));
        assertTrue(RecentTreeSoilLedger.soilRingContains(5, 70, 5, 4, 69, 6));
        assertFalse(RecentTreeSoilLedger.soilRingContains(5, 70, 5, 5, 70, 5));
        assertFalse(RecentTreeSoilLedger.soilRingContains(5, 70, 5, 7, 69, 5));
        assertFalse(RecentTreeSoilLedger.soilRingContains(5, 70, 5, 5, 68, 5));
    }
}
