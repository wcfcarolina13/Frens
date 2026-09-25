package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.SupplyPullPolicy.Backoff;
import net.wcfcarolina13.GameAI.services.SupplyPullPolicy.ChestLoop;
import net.wcfcarolina13.GameAI.services.SupplyPullPolicy.Next;
import net.wcfcarolina13.GameAI.services.SupplyPullPolicy.Pull;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.WaitMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyPullPolicyTest {

    private static final List<String> NEVER_GRANTED = List.of(
            "INELIGIBLE(NOT_ALLOWLISTED)", "INELIGIBLE(PROTECTED_COMPONENTS)", "INELIGIBLE(TIER_NOT_ALLOWED)");
    private static final List<String> STOCK = List.of(
            "INELIGIBLE(RESERVE_EXHAUSTED)", "INELIGIBLE(NO_NEED)", "NO_STOCK", "MOVED", "MOVED_SHORT",
            SupplyPullPolicy.NO_MATCH);
    private static final List<String> CHEST = List.of(
            "DENIED(DENY_LOCKED)", "DENIED(DENY_FOREIGN)", "CHEST_MISMATCH", "OWNER_NOT_NEARBY",
            SupplyPullPolicy.NOT_CHEST, SupplyPullPolicy.UNREACHABLE);
    private static final List<String> STOPPING = List.of(
            "OTHER_REQUEST_PENDING", "DUPLICATE_PENDING", "NOT_PERMITTED", "PROMPT_COOLDOWN", "REJECT_COOLDOWN",
            "NO_ROOM", "INELIGIBLE(NO_OWNER)", "INELIGIBLE", "TIMEOUT", "ABORTED", "NOT_RUNNING", "BOT_GONE",
            "INVALID", "SERVER_BUSY", "ERROR", "WRONG_THREAD", "OWNER_OR_BOT_MISMATCH", "SOMETHING_NEW", "");

    @Test
    void everyKnownRefusalReachesAsFarAsItShould() {
        NEVER_GRANTED.forEach(r -> assertEquals(Next.SKIP_ITEM, SupplyPullPolicy.next(Kind.REFUSED, r), r));
        STOCK.forEach(r -> assertEquals(Next.NEXT, SupplyPullPolicy.next(Kind.REFUSED, r), r));
        CHEST.forEach(r -> assertEquals(Next.SKIP_CHEST, SupplyPullPolicy.next(Kind.REFUSED, r), r));
        STOPPING.forEach(r -> assertEquals(Next.STOP, SupplyPullPolicy.next(Kind.REFUSED, r), r));
        // A refusal with no reason at all is treated like an unknown one.
        assertEquals(Next.STOP, SupplyPullPolicy.next(Kind.REFUSED, null));
        assertTrue(SupplyPullPolicy.isMiss(Kind.REFUSED, null));
    }

    @Test
    void movedGoesOnWhileAnOpenPromptOrAPendingGrantStopsThePass() {
        assertEquals(Next.NEXT, SupplyPullPolicy.next(Kind.MOVED, "MOVED"));
        assertEquals(Next.STOP, SupplyPullPolicy.next(Kind.WAITING, "ASKED"));
        assertEquals(Next.STOP, SupplyPullPolicy.next(Kind.WAITING, "PENDING"));
        assertEquals(Next.STOP, SupplyPullPolicy.next(Kind.READY, "OUT_OF_REACH"));
        assertEquals(Next.STOP, SupplyPullPolicy.next(null, "MOVED"));
    }

    @Test
    void notPermittedStopsSoAnIgnoredPromptIsNotFollowedByAnotherAtOnce() {
        assertEquals(Next.STOP, SupplyPullPolicy.next(Kind.REFUSED, "NOT_PERMITTED"));
        assertEquals(ChestLoop.STOP, SupplyPullPolicy.afterChest(Kind.REFUSED, 0, "NOT_PERMITTED"));
        assertTrue(SupplyPullPolicy.isMiss(Kind.REFUSED, "NOT_PERMITTED"));
    }

    @Test
    void theReasonCodeIgnoresBracketsAndTrailingDetail() {
        assertEquals("DENIED", SupplyPullPolicy.reasonCode("DENIED(DENY_LOCKED)"));
        assertEquals("DENY_LOCKED", SupplyPullPolicy.reasonDetail("DENIED(DENY_LOCKED)"));
        assertEquals("OPENED", SupplyPullPolicy.reasonCode("OPENED id=1234"));
        assertEquals("", SupplyPullPolicy.reasonCode(null));
        assertEquals(null, SupplyPullPolicy.reasonDetail("NO_STOCK"));
        assertEquals(null, SupplyPullPolicy.reasonDetail(null));
    }

    @Test
    void waitingCoversAnOpenPromptAPendingGrantAndAnotherPromptOfThisBot() {
        assertTrue(SupplyPullPolicy.isWaiting(Kind.WAITING, "ASKED"));
        assertTrue(SupplyPullPolicy.isWaiting(Kind.WAITING, "PENDING"));
        assertTrue(SupplyPullPolicy.isWaiting(Kind.READY, "OUT_OF_REACH"));
        assertTrue(SupplyPullPolicy.isWaiting(Kind.REFUSED, "OTHER_REQUEST_PENDING"));
        assertTrue(SupplyPullPolicy.isWaiting(Kind.REFUSED, "DUPLICATE_PENDING"));
        assertFalse(SupplyPullPolicy.isWaiting(Kind.REFUSED, "PROMPT_COOLDOWN"));
        assertFalse(SupplyPullPolicy.isWaiting(Kind.MOVED, "MOVED"));
        assertFalse(SupplyPullPolicy.isWaiting(null, null));
    }

    @Test
    void aMissIsTheOwnersLedgerGivingNothingNotAQuietOrTransportRefusal() {
        for (String r : List.of("PROMPT_COOLDOWN", "REJECT_COOLDOWN", "OWNER_NOT_NEARBY", "NOT_PERMITTED", "TIMEOUT",
                "DENIED(DENY_LOCKED)", "INELIGIBLE(RESERVE_EXHAUSTED)", "INELIGIBLE(NO_OWNER)", "NO_STOCK", "NO_ROOM",
                "CHEST_MISMATCH", "MOVED_SHORT", SupplyPullPolicy.UNREACHABLE)) {
            assertTrue(SupplyPullPolicy.isMiss(Kind.REFUSED, r), r);
        }
        for (String r : List.of("INELIGIBLE(NOT_ALLOWLISTED)", "INELIGIBLE(TIER_NOT_ALLOWED)",
                "INELIGIBLE(PROTECTED_COMPONENTS)", SupplyPullPolicy.NOT_CHEST, SupplyPullPolicy.NO_MATCH,
                "OTHER_REQUEST_PENDING", "DUPLICATE_PENDING", "NOT_RUNNING", "BOT_GONE", "INVALID", "SERVER_BUSY",
                "ERROR", "ABORTED", "WRONG_THREAD")) {
            assertFalse(SupplyPullPolicy.isMiss(Kind.REFUSED, r), r);
        }
        assertFalse(SupplyPullPolicy.isMiss(Kind.MOVED, "MOVED"));
        assertFalse(SupplyPullPolicy.isMiss(Kind.WAITING, "ASKED"));
        assertFalse(SupplyPullPolicy.isMiss(Kind.READY, "OUT_OF_REACH"));
    }

    @Test
    void onlyAPermittedOrAWaitingCallerWalks() {
        for (WaitMode mode : WaitMode.values()) {
            assertTrue(SupplyPullPolicy.walkAfterAsk(Kind.READY, mode), mode.name());
            assertFalse(SupplyPullPolicy.walkAfterAsk(Kind.MOVED, mode), mode.name());
            assertFalse(SupplyPullPolicy.walkAfterAsk(Kind.REFUSED, mode), mode.name());
        }
        assertTrue(SupplyPullPolicy.walkAfterAsk(Kind.WAITING, WaitMode.UNTIL_ANSWERED));
        // A caller that never waits must not walk there and back on every call while the owner reads.
        assertFalse(SupplyPullPolicy.walkAfterAsk(Kind.WAITING, WaitMode.NONE));
        assertFalse(SupplyPullPolicy.walkAfterAsk(null, WaitMode.UNTIL_ANSWERED));
    }

    @Test
    void theNextStackInAChestIsTriedOnlyAfterARefusalOfThatStack() {
        NEVER_GRANTED.forEach(r -> assertTrue(SupplyPullPolicy.tryNextStack(Kind.REFUSED, r), r));
        STOCK.forEach(r -> assertTrue(SupplyPullPolicy.tryNextStack(Kind.REFUSED, r), r));
        CHEST.forEach(r -> assertFalse(SupplyPullPolicy.tryNextStack(Kind.REFUSED, r), r));
        STOPPING.forEach(r -> assertFalse(SupplyPullPolicy.tryNextStack(Kind.REFUSED, r), r));
        assertFalse(SupplyPullPolicy.tryNextStack(Kind.WAITING, "ASKED"));
        assertFalse(SupplyPullPolicy.tryNextStack(Kind.MOVED, "MOVED"));
    }

    @Test
    void aChestLoopIsDoneOnlyWhenItemsMoved() {
        assertEquals(ChestLoop.DONE, SupplyPullPolicy.afterChest(Kind.MOVED, 3, "MOVED"));
        assertEquals(ChestLoop.NEXT_CHEST, SupplyPullPolicy.afterChest(Kind.MOVED, 0, "MOVED"));
        assertEquals(ChestLoop.STOP, SupplyPullPolicy.afterChest(Kind.WAITING, 0, "ASKED"));
        assertEquals(ChestLoop.STOP, SupplyPullPolicy.afterChest(Kind.READY, 0, "OUT_OF_REACH"));
        NEVER_GRANTED.forEach(r -> assertEquals(ChestLoop.NEXT_CHEST, SupplyPullPolicy.afterChest(Kind.REFUSED, 0, r)));
        STOCK.forEach(r -> assertEquals(ChestLoop.NEXT_CHEST, SupplyPullPolicy.afterChest(Kind.REFUSED, 0, r)));
        CHEST.forEach(r -> assertEquals(ChestLoop.NEXT_CHEST, SupplyPullPolicy.afterChest(Kind.REFUSED, 0, r)));
        STOPPING.forEach(r -> assertEquals(ChestLoop.STOP, SupplyPullPolicy.afterChest(Kind.REFUSED, 0, r)));
    }

    @Test
    void aPullTalliesMovesWaitsMissesAndStops() {
        Pull p = SupplyPullPolicy.fold(Pull.NOTHING, Kind.REFUSED, 0, "INELIGIBLE(NOT_ALLOWLISTED)");
        assertEquals(Pull.NOTHING, p);
        p = SupplyPullPolicy.fold(p, Kind.MOVED, 4, "MOVED");
        assertEquals(new Pull(4, false, false, false), p);
        p = SupplyPullPolicy.fold(p, Kind.REFUSED, 0, "INELIGIBLE(RESERVE_EXHAUSTED)");
        assertEquals(new Pull(4, false, true, false), p);
        p = SupplyPullPolicy.fold(p, Kind.WAITING, 0, "ASKED");
        assertEquals(new Pull(4, true, true, true), p);
        assertEquals(new Pull(0, false, true, true), SupplyPullPolicy.fold(null, Kind.REFUSED, 0, "PROMPT_COOLDOWN"));
        // A refused kind never adds to the moved count, whatever number it carries.
        assertEquals(0, SupplyPullPolicy.fold(Pull.NOTHING, Kind.REFUSED, 5, "NO_ROOM").moved());
        assertEquals(new Pull(5, true, true, true), new Pull(1, false, true, false).plus(new Pull(4, true, false, true)));
        assertEquals(new Pull(1, false, false, false), new Pull(1, false, false, false).plus(null));
    }

    @Test
    void anOpenPromptNeverBacksOffSoItsGrantCanBeRedeemed() {
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(new Pull(0, true, true, true)));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(new Pull(2, true, false, true)));
        assertEquals(Backoff.FAILURE, SupplyPullPolicy.idleBackoff(new Pull(0, false, true, true)));
        assertEquals(Backoff.FAILURE, SupplyPullPolicy.idleBackoff(new Pull(3, false, true, false)));
        assertEquals(Backoff.SUCCESS, SupplyPullPolicy.idleBackoff(new Pull(1, false, false, false)));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(Pull.NOTHING));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(null));
    }

    @Test
    void theIdleFallbackHoldsOnlyForAToolItStillLacks() {
        assertTrue(SupplyPullPolicy.holdsIdleFallback(new Pull(0, true, false, true), true));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(new Pull(0, true, false, true), false));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(new Pull(0, false, true, true), true));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(null, true));
    }

    @Test
    void missPausesDoubleToTheTenMinuteCeiling() {
        assertEquals(60_000L, SupplyPullPolicy.missPauseMs(0));
        assertEquals(60_000L, SupplyPullPolicy.missPauseMs(-3));
        assertEquals(120_000L, SupplyPullPolicy.missPauseMs(1));
        assertEquals(240_000L, SupplyPullPolicy.missPauseMs(2));
        assertEquals(480_000L, SupplyPullPolicy.missPauseMs(3));
        assertEquals(600_000L, SupplyPullPolicy.missPauseMs(4));
        assertEquals(600_000L, SupplyPullPolicy.missPauseMs(Integer.MAX_VALUE));
    }

    @Test
    void aRetrievalComesBackSoonForAnOpenPromptAndBacksOffAfterAMiss() {
        assertEquals(SupplyPullPolicy.WAITING_RECHECK_MS, SupplyPullPolicy.retrievalPauseMs(true, true, 3));
        assertEquals(SupplyPullPolicy.WAITING_RECHECK_MS, SupplyPullPolicy.retrievalPauseMs(true, false, 0));
        assertEquals(60_000L, SupplyPullPolicy.retrievalPauseMs(false, true, 0));
        assertEquals(240_000L, SupplyPullPolicy.retrievalPauseMs(false, true, 2));
        assertEquals(0L, SupplyPullPolicy.retrievalPauseMs(false, false, 2));
        assertTrue(SupplyPullPolicy.WAITING_RECHECK_MS < 60_000L, "must recheck well inside a grant's 60 s life");

        assertEquals(0, SupplyPullPolicy.nextMissCount(4, true, false, true));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, false, true, true));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, false, false, false));
        assertEquals(3, SupplyPullPolicy.nextMissCount(2, false, false, true));
        assertEquals(1, SupplyPullPolicy.nextMissCount(-1, false, false, true));
        assertEquals(HobbyBackoffPolicy.MAX_FAILURE_COUNT,
                SupplyPullPolicy.nextMissCount(HobbyBackoffPolicy.MAX_FAILURE_COUNT, false, false, true));
    }

    @Test
    void aFailedIdleCraftKeepsItsFlatWaitFirstThenClimbsTheLadder() {
        // Wooden fallback craft: 20 s flat.
        assertEquals(400L, SupplyPullPolicy.craftRetryTicks(400L, 0));
        assertEquals(1200L, SupplyPullPolicy.craftRetryTicks(400L, 1));
        assertEquals(2400L, SupplyPullPolicy.craftRetryTicks(400L, 2));
        assertEquals(4800L, SupplyPullPolicy.craftRetryTicks(400L, 3));
        assertEquals(9600L, SupplyPullPolicy.craftRetryTicks(400L, 4));
        assertEquals(HobbyBackoffPolicy.MAX_BACKOFF_TICKS, SupplyPullPolicy.craftRetryTicks(400L, 5));
        assertEquals(HobbyBackoffPolicy.MAX_BACKOFF_TICKS, SupplyPullPolicy.craftRetryTicks(400L, 64));
        // Tool crafts: 2 min flat never shrinks.
        assertEquals(2400L, SupplyPullPolicy.craftRetryTicks(2400L, 0));
        assertEquals(2400L, SupplyPullPolicy.craftRetryTicks(2400L, 1));
        assertEquals(2400L, SupplyPullPolicy.craftRetryTicks(2400L, 2));
        assertEquals(4800L, SupplyPullPolicy.craftRetryTicks(2400L, 3));
        assertEquals(0L, SupplyPullPolicy.craftRetryTicks(-5L, 0));
    }
}
