package net.wcfcarolina13.GameAI.services.construction;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedScaffoldLedgerTest {

    private static final UUID JAKE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static final String PLANKS = "minecraft:oak_planks";
    private static final String DIRT = "minecraft:dirt";
    private static final long T0 = 1_760_000_000_000L;
    private static final long DAY = ScaffoldOwnershipPolicy.OWNERSHIP_TTL_MS;

    private static OwnedScaffoldLedger.Owned owned(String id, long at) {
        return new OwnedScaffoldLedger.Owned(id, at);
    }

    @Test
    void registerIsOwnedForget() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        assertFalse(ledger.isOwned(JAKE, OVERWORLD, 42L));
        assertTrue(ledger.register(JAKE, OVERWORLD, 42L, PLANKS, T0));
        assertFalse(ledger.register(JAKE, OVERWORLD, 42L, PLANKS, T0));
        assertTrue(ledger.isOwned(JAKE, OVERWORLD, 42L));
        assertTrue(ledger.forget(JAKE, OVERWORLD, 42L));
        assertFalse(ledger.forget(JAKE, OVERWORLD, 42L));
        assertFalse(ledger.isOwned(JAKE, OVERWORLD, 42L));
    }

    @Test
    void entryKeepsTheExactBlockIdAndTime() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        ledger.register(JAKE, OVERWORLD, 42L, PLANKS, T0);
        assertEquals(owned(PLANKS, T0), ledger.entry(JAKE, OVERWORLD, 42L));
        // A re-placement in the same cell refreshes the id and the clock.
        ledger.register(JAKE, OVERWORLD, 42L, DIRT, T0 + 5);
        assertEquals(owned(DIRT, T0 + 5), ledger.entry(JAKE, OVERWORLD, 42L));
        assertNull(ledger.entry(BOB, OVERWORLD, 42L));
    }

    @Test
    void blankBlockIdIsNeverOwnership() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        assertFalse(ledger.register(JAKE, OVERWORLD, 1L, null, T0));
        assertFalse(ledger.register(JAKE, OVERWORLD, 1L, " ", T0));
        assertFalse(ledger.isOwned(JAKE, OVERWORLD, 1L));
    }

    @Test
    void ownershipIsPerBotAndPerDimension() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        ledger.register(JAKE, OVERWORLD, 7L, PLANKS, T0);
        assertFalse(ledger.isOwned(BOB, OVERWORLD, 7L));
        assertFalse(ledger.isOwned(JAKE, NETHER, 7L));
        assertFalse(ledger.forget(BOB, OVERWORLD, 7L));
        assertTrue(ledger.isOwned(JAKE, OVERWORLD, 7L));
        assertEquals(List.of(JAKE), ledger.botsWithPositions(OVERWORLD));
        assertTrue(ledger.botsWithPositions(NETHER).isEmpty());
    }

    @Test
    void nullsNeverOwnOrRegister() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        assertFalse(ledger.register(null, OVERWORLD, 1L, PLANKS, T0));
        assertFalse(ledger.register(JAKE, null, 1L, PLANKS, T0));
        assertFalse(ledger.isOwned(null, OVERWORLD, 1L));
        assertTrue(ledger.positions(null, OVERWORLD).isEmpty());
        assertTrue(ledger.entries(null, OVERWORLD).isEmpty());
    }

    @Test
    void positionsAndEntriesAreSnapshots() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        ledger.register(JAKE, OVERWORLD, 1L, PLANKS, T0);
        List<Long> snap = ledger.positions(JAKE, OVERWORLD);
        Map<Long, OwnedScaffoldLedger.Owned> entries = ledger.entries(JAKE, OVERWORLD);
        ledger.register(JAKE, OVERWORLD, 2L, PLANKS, T0);
        assertEquals(List.of(1L), snap);
        assertEquals(Map.of(1L, owned(PLANKS, T0)), entries);
        assertEquals(2, ledger.positions(JAKE, OVERWORLD).size());
    }

    @Test
    void replaceAllDropsThePreviousSave() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        ledger.register(JAKE, OVERWORLD, 1L, PLANKS, T0);
        ledger.register(BOB, NETHER, 9L, PLANKS, T0);
        int dropped = ledger.replaceAll(OVERWORLD,
                Map.of(BOB, Map.of(5L, owned(PLANKS, T0), 6L, owned(DIRT, T0))), T0 + 1);
        assertEquals(0, dropped);
        assertFalse(ledger.isOwned(JAKE, OVERWORLD, 1L));
        assertFalse(ledger.isOwned(BOB, NETHER, 9L));
        assertEquals(owned(PLANKS, T0), ledger.entry(BOB, OVERWORLD, 5L));
        assertEquals(owned(DIRT, T0), ledger.entry(BOB, OVERWORLD, 6L));
    }

    @Test
    void replaceAllDropsExpiredAndIdlessEntries() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        Map<Long, OwnedScaffoldLedger.Owned> saved = new HashMap<>();
        saved.put(1L, owned(PLANKS, T0));             // fresh: kept
        saved.put(2L, owned(PLANKS, T0 - DAY));       // exactly 24 h old at T0: dropped
        saved.put(3L, owned(null, T0));               // old format, no block id: dropped
        saved.put(4L, owned("", T0));                 // blank id: dropped
        saved.put(5L, owned(DIRT, 0L));               // no timestamp: dropped
        saved.put(6L, owned(DIRT, T0 - DAY + 1));     // 1 ms short of 24 h: kept
        int dropped = ledger.replaceAll(OVERWORLD, Map.of(JAKE, saved), T0);
        assertEquals(4, dropped);
        assertTrue(ledger.isOwned(JAKE, OVERWORLD, 1L));
        assertTrue(ledger.isOwned(JAKE, OVERWORLD, 6L));
        assertEquals(2, ledger.positions(JAKE, OVERWORLD).size());
    }

    @Test
    void replaceAllWithNothingClears() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        ledger.register(JAKE, OVERWORLD, 1L, PLANKS, T0);
        assertEquals(0, ledger.replaceAll(OVERWORLD, null, T0));
        assertFalse(ledger.isOwned(JAKE, OVERWORLD, 1L));
    }
}
