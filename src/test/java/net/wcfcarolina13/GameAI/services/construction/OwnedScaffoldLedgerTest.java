package net.wcfcarolina13.GameAI.services.construction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedScaffoldLedgerTest {

    private static final UUID JAKE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void registerIsOwnedForget() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        assertFalse(ledger.isOwned(JAKE, OVERWORLD, 42L));
        assertTrue(ledger.register(JAKE, OVERWORLD, 42L));
        assertFalse(ledger.register(JAKE, OVERWORLD, 42L));
        assertTrue(ledger.isOwned(JAKE, OVERWORLD, 42L));
        assertTrue(ledger.forget(JAKE, OVERWORLD, 42L));
        assertFalse(ledger.forget(JAKE, OVERWORLD, 42L));
        assertFalse(ledger.isOwned(JAKE, OVERWORLD, 42L));
    }

    @Test
    void ownershipIsPerBotAndPerDimension() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        ledger.register(JAKE, OVERWORLD, 7L);
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
        assertFalse(ledger.register(null, OVERWORLD, 1L));
        assertFalse(ledger.register(JAKE, null, 1L));
        assertFalse(ledger.isOwned(null, OVERWORLD, 1L));
        assertTrue(ledger.positions(null, OVERWORLD).isEmpty());
    }

    @Test
    void positionsIsASnapshot() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        ledger.register(JAKE, OVERWORLD, 1L);
        List<Long> snap = ledger.positions(JAKE, OVERWORLD);
        ledger.register(JAKE, OVERWORLD, 2L);
        assertEquals(List.of(1L), snap);
        assertEquals(2, ledger.positions(JAKE, OVERWORLD).size());
    }

    @Test
    void replaceAllDropsThePreviousSave() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        ledger.register(JAKE, OVERWORLD, 1L);
        ledger.register(BOB, NETHER, 9L);
        ledger.replaceAll(OVERWORLD, Map.of(BOB, List.of(5L, 6L)));
        assertFalse(ledger.isOwned(JAKE, OVERWORLD, 1L));
        assertFalse(ledger.isOwned(BOB, NETHER, 9L));
        assertTrue(ledger.isOwned(BOB, OVERWORLD, 5L));
        assertTrue(ledger.isOwned(BOB, OVERWORLD, 6L));
    }

    @Test
    void replaceAllWithNothingClears() {
        OwnedScaffoldLedger ledger = new OwnedScaffoldLedger();
        ledger.register(JAKE, OVERWORLD, 1L);
        ledger.replaceAll(OVERWORLD, null);
        assertFalse(ledger.isOwned(JAKE, OVERWORLD, 1L));
    }
}
