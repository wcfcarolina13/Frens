package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static net.wcfcarolina13.GameAI.services.PendingCookCollectPolicy.Decision.DROP;
import static net.wcfcarolina13.GameAI.services.PendingCookCollectPolicy.Decision.TAKE;
import static net.wcfcarolina13.GameAI.services.PendingCookCollectPolicy.Decision.WAIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingCookCollectPolicyTest {

    @Test
    void waitsWhileRawFoodIsStillCookingAndTheBatchIsShort() {
        // 4 beef loaded, 1 cooked, 3 raw still in, furnace burning: one trip later brings all 4.
        assertEquals(WAIT, PendingCookCollectPolicy.decide(4, true, 1, 64, true, true));
    }

    @Test
    void takesOnceTheInputHoldsNoneOfTheRawFood() {
        assertEquals(TAKE, PendingCookCollectPolicy.decide(4, true, 4, 64, false, true));
        assertEquals(TAKE, PendingCookCollectPolicy.decide(4, true, 3, 64, false, false));
    }

    @Test
    void takesWhenTheOutputHasReachedWhatIsOwed() {
        // The owner's same raw food may still be in the input: the bot's share is already out.
        assertEquals(TAKE, PendingCookCollectPolicy.decide(4, true, 4, 64, true, true));
        assertEquals(TAKE, PendingCookCollectPolicy.decide(4, true, 9, 64, true, true));
    }

    @Test
    void takesAFullOutputStackOrAFurnaceThatWentOut() {
        assertEquals(TAKE, PendingCookCollectPolicy.decide(100, true, 64, 64, true, true));
        assertEquals(TAKE, PendingCookCollectPolicy.decide(4, true, 2, 64, true, false));
    }

    @Test
    void dropsWhenNothingOfTheBotsIsLeft() {
        assertEquals(DROP, PendingCookCollectPolicy.decide(4, false, 0, 64, false, false));
        // Output holds something else and the raw food is gone (someone emptied it).
        assertEquals(DROP, PendingCookCollectPolicy.decide(4, false, 5, 64, false, true));
        assertEquals(DROP, PendingCookCollectPolicy.decide(0, true, 4, 64, false, true));
    }

    @Test
    void waitsWhenTheCookedFoodIsGoneButRawFoodStillCooks() {
        assertEquals(WAIT, PendingCookCollectPolicy.decide(3, false, 0, 64, true, true));
        // Unlit with raw food and no output yet: still waits (until expiry); nothing to take.
        assertEquals(WAIT, PendingCookCollectPolicy.decide(3, false, 0, 64, true, false));
    }

    @Test
    void neverTakesMoreThanOwed() {
        assertEquals(4, PendingCookCollectPolicy.take(4, 9));
        assertEquals(2, PendingCookCollectPolicy.take(4, 2));
        assertEquals(0, PendingCookCollectPolicy.take(0, 9));
        assertEquals(0, PendingCookCollectPolicy.take(-1, 9));
    }

    @Test
    void closesTheEntryWhenNothingMoreOfTheBotsWillCook() {
        assertTrue(PendingCookCollectPolicy.closeAfterTake(0, true, false));
        assertTrue(PendingCookCollectPolicy.closeAfterTake(2, false, false));
        assertFalse(PendingCookCollectPolicy.closeAfterTake(2, true, false));
    }

    @Test
    void aTakeCutShortByAFullPackKeepsTheEntry() {
        assertFalse(PendingCookCollectPolicy.closeAfterTake(2, false, true));
        assertTrue(PendingCookCollectPolicy.closeAfterTake(0, false, true));
    }

    @Test
    void expiresAfterTwentyMinutesOrWhenTheTickCounterWentBack() {
        assertFalse(PendingCookCollectPolicy.isExpired(1000L, 1000L));
        assertFalse(PendingCookCollectPolicy.isExpired(1000L + PendingCookCollectPolicy.EXPIRY_TICKS, 1000L));
        assertTrue(PendingCookCollectPolicy.isExpired(1001L + PendingCookCollectPolicy.EXPIRY_TICKS, 1000L));
        assertTrue(PendingCookCollectPolicy.isExpired(10L, 1000L));
        assertEquals(24000L, PendingCookCollectPolicy.EXPIRY_TICKS);
    }

    @Test
    void unknownBaselineOnlyCapsToWhatIsInTheFurnace() {
        assertEquals(4, PendingCookCollectPolicy.reconcileOwed(4, -1, 1, 3));
        assertEquals(2, PendingCookCollectPolicy.reconcileOwed(4, -1, 2, 0));
    }

    @Test
    void outputThatDroppedWithoutTheBotComesOffWhatIsOwed() {
        // 3 steak seen, owner took 2: owed 4 -> 2 (cap 1 + 1 raw = 2 too).
        assertEquals(2, PendingCookCollectPolicy.reconcileOwed(4, 3, 1, 1));
        // Owner emptied the output and loaded 8 of their own beef: the cap no longer saves the bot,
        // the drop does.
        assertEquals(0, PendingCookCollectPolicy.reconcileOwed(4, 4, 0, 8));
    }

    @Test
    void outputThatRoseIsCookingNotATake() {
        assertEquals(4, PendingCookCollectPolicy.reconcileOwed(4, 1, 3, 1));
    }

    @Test
    void owedNeverExceedsOutputPlusRawInput() {
        assertEquals(3, PendingCookCollectPolicy.reconcileOwed(6, 2, 2, 1));
        assertEquals(0, PendingCookCollectPolicy.reconcileOwed(6, -1, 0, 0));
    }

    @Test
    void threeMissedTripsGiveTheEntryUp() {
        assertFalse(PendingCookCollectPolicy.tooManyFailedTrips(2));
        assertTrue(PendingCookCollectPolicy.tooManyFailedTrips(3));
        assertEquals(3, PendingCookCollectPolicy.MAX_FAILED_TRIPS);
    }
}
