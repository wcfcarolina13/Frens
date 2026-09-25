package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.MutualAidChestFoodPolicy.Next;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MutualAidChestFoodPolicyTest {

    // ── withdrawCount ────────────────────────────────────────────────────────────────────────

    @Test
    void takesTwoPiecesWhenHungryAndThreeWhenStarving() {
        // Bread (nutrition 5): deficit 12 at food 8 needs 3, capped at 2; deficit 17 at food 3 needs 4, capped at 3.
        assertEquals(2, MutualAidChestFoodPolicy.withdrawCount(8, false, 5, 64));
        assertEquals(3, MutualAidChestFoodPolicy.withdrawCount(3, true, 5, 64));
    }

    @Test
    void neverTakesMoreThanTheDeficitNeeds() {
        // Steak (nutrition 8) at food 14: deficit 6 needs 1.
        assertEquals(1, MutualAidChestFoodPolicy.withdrawCount(14, false, 8, 64));
        // Starving with a big meal: deficit 16 at nutrition 8 needs 2, under the 3 cap.
        assertEquals(2, MutualAidChestFoodPolicy.withdrawCount(4, true, 8, 64));
    }

    @Test
    void neverTakesMoreThanTheChestCanGrant() {
        assertEquals(1, MutualAidChestFoodPolicy.withdrawCount(3, true, 2, 1));
        assertEquals(2, MutualAidChestFoodPolicy.withdrawCount(3, true, 2, 2));
    }

    @Test
    void unknownNutritionSkipsTheDeficitCap() {
        assertEquals(2, MutualAidChestFoodPolicy.withdrawCount(19, false, 0, 64));
        assertEquals(3, MutualAidChestFoodPolicy.withdrawCount(1, true, -1, 64));
    }

    @Test
    void alwaysAtLeastOnePiece() {
        // A full bar still counts a deficit of 1, and a zero grant still yields 1 (the caller skips it first).
        assertEquals(1, MutualAidChestFoodPolicy.withdrawCount(MutualAidChestFoodPolicy.MAX_FOOD_LEVEL, false, 5, 64));
        assertEquals(1, MutualAidChestFoodPolicy.withdrawCount(10, false, 5, 0));
    }

    // ── askedFirst ───────────────────────────────────────────────────────────────────────────

    @Test
    void theAskedChestMovesToTheFrontKeepingTheRestInOrder() {
        assertEquals(List.of("c", "a", "b", "d"), MutualAidChestFoodPolicy.askedFirst(List.of("a", "b", "c", "d"), "c"));
    }

    @Test
    void withNoAskedChestOrOneNotInReachTheOrderIsUnchanged() {
        List<String> byDistance = List.of("a", "b");
        assertSame(byDistance, MutualAidChestFoodPolicy.askedFirst(byDistance, null));
        assertSame(byDistance, MutualAidChestFoodPolicy.askedFirst(byDistance, "z"));
        assertEquals(List.of("a", "b"), MutualAidChestFoodPolicy.askedFirst(byDistance, "a"));
    }

    // ── afterWithdraw ────────────────────────────────────────────────────────────────────────

    @Test
    void movedIsTakenAndAnUnansweredPromptWaits() {
        assertEquals(Next.TAKEN, MutualAidChestFoodPolicy.afterWithdraw(Kind.MOVED, "MOVED", false));
        assertEquals(Next.WAIT, MutualAidChestFoodPolicy.afterWithdraw(Kind.WAITING, "ASKED", false));
        assertEquals(Next.WAIT, MutualAidChestFoodPolicy.afterWithdraw(Kind.WAITING, "PENDING", true));
    }

    @Test
    void readyStopsBecauseThisSiteNeverWalks() {
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.READY, "OUT_OF_REACH", false));
    }

    @Test
    void noRoomMakesRoomOnceThenStops() {
        assertEquals(Next.MAKE_ROOM_AND_RETRY, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, "NO_ROOM", false));
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, "NO_ROOM", true));
    }

    @Test
    void refusalsAboutThisFoodTryTheNextFood() {
        for (String reason : List.of("INELIGIBLE(NOT_ALLOWLISTED)", "INELIGIBLE(RESERVE_EXHAUSTED)", "INELIGIBLE",
                "REJECT_COOLDOWN", "NO_STOCK")) {
            assertEquals(Next.NEXT_ITEM, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, reason, false), reason);
        }
    }

    @Test
    void aBotWithNoOwnerStopsInsteadOfAskingAboutEveryFood() {
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, "INELIGIBLE(NO_OWNER)", false));
        assertEquals("INELIGIBLE(NO_OWNER)", MutualAidChestFoodPolicy.INELIGIBLE_NO_OWNER);
    }

    @Test
    void refusalsAboutThisChestTryTheNextChest() {
        for (String reason : List.of("DENIED(DENY_NOT_CHEST)", "DENIED(DENY_FOREIGN)", "DENIED", "OWNER_NOT_NEARBY",
                "PROMPT_COOLDOWN")) {
            assertEquals(Next.NEXT_CHEST, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, reason, false), reason);
        }
    }

    @Test
    void everyOtherRefusalStops() {
        for (String reason : List.of("NOT_PERMITTED", "OTHER_REQUEST_PENDING", "DUPLICATE_PENDING", "NOT_RUNNING",
                "BOT_GONE", "INVALID", "WRONG_THREAD", "SERVER_BUSY", "ABORTED", "TIMEOUT", "ERROR", "CHEST_MISMATCH",
                "OWNER_OR_BOT_MISMATCH", "MOVED_SHORT", "")) {
            assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, reason, false), reason);
        }
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, null, false));
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(null, "MOVED", false));
    }

    @Test
    void aReasonThatOnlySharesAPrefixIsNotConfused() {
        // "DENIED_X" is not DENIED; "NO_ROOMS" is not NO_ROOM.
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, "DENIED_X", false));
        assertEquals(Next.STOP, MutualAidChestFoodPolicy.afterWithdraw(Kind.REFUSED, "NO_ROOMS", false));
    }
}
