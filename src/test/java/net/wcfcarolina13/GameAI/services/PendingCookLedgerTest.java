package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingCookLedgerTest {

    private static final String OW = "minecraft:overworld";
    private static final String BEEF = "minecraft:beef";
    private static final String STEAK = "minecraft:cooked_beef";
    private final UUID bot = UUID.randomUUID();

    @BeforeEach
    @AfterEach
    void reset() {
        PendingCookLedger.clear();
    }

    @Test
    void loadingTheSameFurnaceAgainAddsUpAndRefreshesTheTick() {
        assertEquals(4, PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 4, 100L));
        assertEquals(6, PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 2, 300L));
        assertEquals(300L, PendingCookLedger.get(bot, OW, 7L).recordedTick());
        assertEquals(6, PendingCookLedger.owed(bot, OW, 7L, STEAK));
    }

    @Test
    void anotherFoodReplacesTheEntry() {
        PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 4, 100L);
        PendingCookLedger.record(bot, OW, 7L, "minecraft:chicken", "minecraft:cooked_chicken", 3, 200L);
        assertEquals(0, PendingCookLedger.owed(bot, OW, 7L, STEAK));
        assertEquals(3, PendingCookLedger.owed(bot, OW, 7L, "minecraft:cooked_chicken"));
    }

    @Test
    void furnacesAndDimensionsAreSeparate() {
        PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 4, 100L);
        PendingCookLedger.record(bot, OW, 8L, BEEF, STEAK, 2, 100L);
        PendingCookLedger.record(bot, "minecraft:the_nether", 7L, BEEF, STEAK, 1, 100L);
        assertEquals(3, PendingCookLedger.entriesFor(bot).size());
        assertEquals(4, PendingCookLedger.owed(bot, OW, 7L, STEAK));
        assertEquals(0, PendingCookLedger.owed(UUID.randomUUID(), OW, 7L, STEAK));
    }

    @Test
    void debitCountsDownAndRemovesAtZero() {
        PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 4, 100L);
        assertEquals(3, PendingCookLedger.debit(bot, OW, 7L, STEAK, 1));
        assertEquals(0, PendingCookLedger.debit(bot, OW, 7L, STEAK, 5));
        assertNull(PendingCookLedger.get(bot, OW, 7L));
        assertTrue(PendingCookLedger.entriesFor(bot).isEmpty());
    }

    @Test
    void debitOfAnotherItemLeavesTheEntryAlone() {
        PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 4, 100L);
        assertEquals(0, PendingCookLedger.debit(bot, OW, 7L, "minecraft:iron_ingot", 3));
        assertEquals(4, PendingCookLedger.owed(bot, OW, 7L, STEAK));
    }

    @Test
    void ignoresEmptyLoads() {
        assertEquals(0, PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 0, 100L));
        assertTrue(PendingCookLedger.entriesFor(bot).isEmpty());
    }

    @Test
    void oneTripAtATimeAndClearResetsEverything() {
        assertTrue(PendingCookLedger.beginTrip(bot));
        assertFalse(PendingCookLedger.beginTrip(bot));
        PendingCookLedger.endTrip(bot);
        assertTrue(PendingCookLedger.beginTrip(bot));
        PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 4, 100L);
        PendingCookLedger.clear();
        assertFalse(PendingCookLedger.tripInFlight(bot));
        assertTrue(PendingCookLedger.entriesFor(bot).isEmpty());
    }

    @Test
    void theBotsOwnTakeLowersTheBaselineSoItIsNotDebitedTwice() {
        PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 4, 100L);
        assertEquals(4, PendingCookLedger.observe(bot, OW, 7L, 3, 1));
        assertEquals(1, PendingCookLedger.debit(bot, OW, 7L, STEAK, 3));
        // Output went 3 -> 0 by the bot's own take: nothing more comes off.
        assertEquals(1, PendingCookLedger.observe(bot, OW, 7L, 0, 1));
    }

    @Test
    void someoneElseTakingTheOutputDebitsAndClosesAtZero() {
        PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 4, 100L);
        assertEquals(4, PendingCookLedger.observe(bot, OW, 7L, 4, 0));
        // Owner takes all 4 and loads 8 beef of their own.
        assertEquals(0, PendingCookLedger.observe(bot, OW, 7L, 0, 8));
        assertNull(PendingCookLedger.get(bot, OW, 7L));
    }

    @Test
    void aFreshLoadResetsTheBaselineAndTripCount() {
        PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 2, 100L);
        PendingCookLedger.observe(bot, OW, 7L, 2, 0);
        assertEquals(1, PendingCookLedger.noteTrip(bot, OW, 7L, 0));
        PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 2, 200L);
        assertEquals(-1, PendingCookLedger.get(bot, OW, 7L).lastSeenOutput());
        assertEquals(0, PendingCookLedger.get(bot, OW, 7L).failedTrips());
    }

    @Test
    void missedTripsCountUpAndATakeResetsThem() {
        PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 4, 100L);
        assertEquals(1, PendingCookLedger.noteTrip(bot, OW, 7L, -1));
        assertEquals(2, PendingCookLedger.noteTrip(bot, OW, 7L, 0));
        assertEquals(0, PendingCookLedger.noteTrip(bot, OW, 7L, 2));
        assertEquals(1, PendingCookLedger.noteTrip(bot, OW, 7L, 0));
    }

    @Test
    void aTripForAGoneEntryIsANoOp() {
        assertEquals(0, PendingCookLedger.noteTrip(bot, OW, 7L, 0));
        assertNull(PendingCookLedger.get(bot, OW, 7L));
    }

    @Test
    void clearBotForgetsOnlyThatBotAndLeavesItsTripFlag() {
        UUID other = UUID.randomUUID();
        PendingCookLedger.record(bot, OW, 7L, BEEF, STEAK, 4, 100L);
        PendingCookLedger.record(bot, OW, 9L, BEEF, STEAK, 2, 100L);
        PendingCookLedger.record(other, OW, 7L, BEEF, STEAK, 1, 100L);
        assertTrue(PendingCookLedger.beginTrip(bot));
        PendingCookLedger.clearBot(bot);
        assertFalse(PendingCookLedger.hasEntries(bot));
        assertTrue(PendingCookLedger.hasEntries(other));
        assertTrue(PendingCookLedger.tripInFlight(bot));
    }
}
