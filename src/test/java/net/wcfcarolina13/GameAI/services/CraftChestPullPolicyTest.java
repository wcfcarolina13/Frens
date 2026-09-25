package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.CraftChestPullPolicy.Next;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Timings;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for CraftingHelper's chest-pull decisions — no net.minecraft types, no world. */
class CraftChestPullPolicyTest {

    // ── first ask / after the walk ───────────────────────────────────────────────────────────

    @Test
    void offTheServerThreadReadyAndWaitingWalkAndRefusedNeverDoes() {
        assertEquals(Next.NEXT, CraftChestPullPolicy.afterAsk(Kind.MOVED, "MOVED", false));
        assertEquals(Next.WALK, CraftChestPullPolicy.afterAsk(Kind.READY, "OUT_OF_REACH", false));
        assertEquals(Next.WALK, CraftChestPullPolicy.afterAsk(Kind.WAITING, "ASKED", false));
        assertEquals(Next.WALK, CraftChestPullPolicy.afterAsk(Kind.WAITING, "PENDING", false));
        for (String reason : new String[]{"DENIED(DENY_LOCKED)", "INELIGIBLE(NOT_ALLOWLISTED)", "PROMPT_COOLDOWN",
                "NO_STOCK", "OTHER_REQUEST_PENDING", "OWNER_NOT_NEARBY"}) {
            assertTrue(CraftChestPullPolicy.afterAsk(Kind.REFUSED, reason, false) != Next.WALK, reason);
        }
    }

    @Test
    void onTheServerThreadNothingWalks() {
        for (Kind kind : Kind.values()) {
            assertTrue(CraftChestPullPolicy.afterAsk(kind, "X", true) != Next.WALK, kind.name());
        }
        assertEquals(Next.SKIP_CHEST, CraftChestPullPolicy.afterAsk(Kind.READY, "OUT_OF_REACH", true));
        assertEquals(Next.STOP_WAITING, CraftChestPullPolicy.afterAsk(Kind.WAITING, "ASKED", true));
        assertEquals(Next.NEXT, CraftChestPullPolicy.afterAsk(Kind.MOVED, "MOVED", true));
    }

    @Test
    void afterTheWalkWaitingStopsForALaterPullAndReadyMovesOn() {
        assertEquals(Next.NEXT, CraftChestPullPolicy.afterWalk(Kind.MOVED, "MOVED_SHORT"));
        assertEquals(Next.SKIP_CHEST, CraftChestPullPolicy.afterWalk(Kind.READY, "OUT_OF_REACH"));
        assertEquals(Next.STOP_WAITING, CraftChestPullPolicy.afterWalk(Kind.WAITING, "PENDING"));
        assertEquals(Next.STOP, CraftChestPullPolicy.afterWalk(Kind.REFUSED, "OTHER_REQUEST_PENDING"));
        assertEquals(Next.SKIP_CHEST, CraftChestPullPolicy.afterWalk(Kind.REFUSED, "NOT_PERMITTED"));
    }

    @Test
    void aMissingKindStops() {
        assertEquals(Next.STOP, CraftChestPullPolicy.afterAsk(null, "MOVED", false));
        assertEquals(Next.STOP, CraftChestPullPolicy.afterWalk(null, "MOVED"));
    }

    // ── refusals ─────────────────────────────────────────────────────────────────────────────

    @Test
    void everyRequestStatusAsARefusalHasADeliberateScope() {
        Map<RequestStatus, Next> expected = new EnumMap<>(RequestStatus.class);
        expected.put(RequestStatus.OPENED, Next.STOP);             // a prompt without a fingerprint: fail closed
        expected.put(RequestStatus.DUPLICATE_PENDING, Next.STOP);
        expected.put(RequestStatus.PROMPT_COOLDOWN, Next.STOP);
        expected.put(RequestStatus.REJECT_COOLDOWN, Next.STOP);
        expected.put(RequestStatus.COVERED_BY_ALWAYS, Next.STOP);  // covered without a fingerprint: fail closed
        expected.put(RequestStatus.INELIGIBLE, Next.STOP);         // no verdict attached
        expected.put(RequestStatus.OWNER_NOT_NEARBY, Next.SKIP_CHEST);
        expected.put(RequestStatus.DENIED, Next.SKIP_CHEST);
        expected.put(RequestStatus.INVALID, Next.STOP);
        expected.put(RequestStatus.NOT_RUNNING, Next.STOP);
        expected.put(RequestStatus.WRONG_THREAD, Next.STOP);
        assertEquals(EnumSet.allOf(RequestStatus.class), expected.keySet(), "a new RequestStatus needs a decision here");
        expected.forEach((status, next) ->
                assertEquals(next, CraftChestPullPolicy.onRefusal(status.name()), status.name()));
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal("OPENED id=1234-abcd"));
        assertEquals(Next.SKIP_CHEST, CraftChestPullPolicy.onRefusal("DENIED(DENY_LOCKED)"));
        assertEquals(Next.SKIP_CHEST, CraftChestPullPolicy.onRefusal("DENIED(DENY_NOT_CHEST)"));
    }

    @Test
    void everyTransferStatusAsARefusalHasADeliberateScope() {
        Map<TransferStatus, Next> expected = new EnumMap<>(TransferStatus.class);
        expected.put(TransferStatus.MOVED, Next.NEXT);          // reported as a refusal only when 0 moved
        expected.put(TransferStatus.MOVED_SHORT, Next.NEXT);
        expected.put(TransferStatus.WRONG_THREAD, Next.STOP);
        expected.put(TransferStatus.NOT_RUNNING, Next.STOP);
        expected.put(TransferStatus.INVALID, Next.STOP);
        expected.put(TransferStatus.OWNER_OR_BOT_MISMATCH, Next.STOP);
        expected.put(TransferStatus.DENIED, Next.SKIP_CHEST);
        expected.put(TransferStatus.CHEST_MISMATCH, Next.SKIP_CHEST);
        expected.put(TransferStatus.OUT_OF_REACH, Next.STOP);   // arrives as READY, never as a refusal
        expected.put(TransferStatus.NO_STOCK, Next.NEXT);
        expected.put(TransferStatus.NO_ROOM, Next.STOP);
        expected.put(TransferStatus.NOT_PERMITTED, Next.SKIP_CHEST);
        assertEquals(EnumSet.allOf(TransferStatus.class), expected.keySet(), "a new TransferStatus needs a decision here");
        expected.forEach((status, next) ->
                assertEquals(next, CraftChestPullPolicy.onRefusal(status.name()), status.name()));
    }

    @Test
    void anIneligibleVerdictReachesTheItemTheChestsStockOrEverything() {
        Map<Verdict, Next> expected = new EnumMap<>(Verdict.class);
        expected.put(Verdict.ELIGIBLE, Next.STOP);              // nonsense as a refusal: fail closed
        expected.put(Verdict.NOT_ALLOWLISTED, Next.SKIP_ITEM);
        expected.put(Verdict.PROTECTED_COMPONENTS, Next.SKIP_ITEM);
        expected.put(Verdict.TIER_NOT_ALLOWED, Next.SKIP_ITEM);
        expected.put(Verdict.RESERVE_EXHAUSTED, Next.NEXT);
        expected.put(Verdict.NO_NEED, Next.NEXT);
        expected.put(Verdict.NO_OWNER, Next.STOP);
        assertEquals(EnumSet.allOf(Verdict.class), expected.keySet(), "a new Verdict needs a decision here");
        expected.forEach((verdict, next) ->
                assertEquals(next, CraftChestPullPolicy.onRefusal("INELIGIBLE(" + verdict + ")"), verdict.name()));
    }

    @Test
    void theFacadesOwnRefusalsStopExceptAClosedTicketWhichSkipsTheChest() {
        for (String reason : new String[]{"NOT_RUNNING", "BOT_GONE", "INVALID", "OTHER_REQUEST_PENDING",
                "TIMEOUT", "ABORTED", "SERVER_BUSY", "ERROR"}) {
            assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal(reason), reason);
        }
        assertEquals(Next.SKIP_CHEST, CraftChestPullPolicy.onRefusal("NOT_PERMITTED"));
    }

    @Test
    void anUnknownEmptyOrMissingReasonStops() {
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal("SOMETHING_NEW"));
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal(""));
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal(null));
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal("(NOT_ALLOWLISTED)"));
    }

    @Test
    void theReasonSplitsIntoCodeAndBracketedDetail() {
        assertEquals("DENIED", CraftChestPullPolicy.reasonCode("DENIED(DENY_LOCKED)"));
        assertEquals("DENY_LOCKED", CraftChestPullPolicy.reasonDetail("DENIED(DENY_LOCKED)"));
        assertEquals("OPENED", CraftChestPullPolicy.reasonCode("OPENED id=1234"));
        assertNull(CraftChestPullPolicy.reasonDetail("OPENED id=1234"));
        assertEquals("NO_STOCK", CraftChestPullPolicy.reasonCode("NO_STOCK"));
        assertNull(CraftChestPullPolicy.reasonDetail("NO_STOCK"));
        assertEquals("", CraftChestPullPolicy.reasonCode(null));
        assertNull(CraftChestPullPolicy.reasonDetail(null));
        assertNull(CraftChestPullPolicy.reasonDetail("BROKEN)("));
    }

    // ── loudness and the pause ───────────────────────────────────────────────────────────────

    @Test
    void onlyAnswersTheFacadeLogsAtInfoAreLoud() {
        assertFalse(CraftChestPullPolicy.isLoud(Kind.WAITING, "PENDING"), "a repeat wait is DEBUG");
        assertFalse(CraftChestPullPolicy.isLoud(Kind.REFUSED, "OTHER_REQUEST_PENDING"));
        assertFalse(CraftChestPullPolicy.isLoud(Kind.REFUSED, "INELIGIBLE(NOT_ALLOWLISTED)"), "the pre-filter is DEBUG");
        assertFalse(CraftChestPullPolicy.isLoud(Kind.REFUSED, "INELIGIBLE(PROTECTED_COMPONENTS)"));
        assertFalse(CraftChestPullPolicy.isLoud(Kind.REFUSED, "INELIGIBLE(TIER_NOT_ALLOWED)"));

        assertTrue(CraftChestPullPolicy.isLoud(Kind.WAITING, "ASKED"), "a new prompt");
        assertTrue(CraftChestPullPolicy.isLoud(Kind.REFUSED, "INELIGIBLE(RESERVE_EXHAUSTED)"), "a request was made");
        assertTrue(CraftChestPullPolicy.isLoud(Kind.REFUSED, "PROMPT_COOLDOWN"));
        assertTrue(CraftChestPullPolicy.isLoud(Kind.REFUSED, "OWNER_NOT_NEARBY"));
        assertTrue(CraftChestPullPolicy.isLoud(Kind.REFUSED, "NOT_PERMITTED"));
        assertTrue(CraftChestPullPolicy.isLoud(Kind.READY, "OUT_OF_REACH"));
        assertTrue(CraftChestPullPolicy.isLoud(Kind.MOVED, "MOVED"));
    }

    @Test
    void aPullPausesOnlyWhenItAskedAndGotNothingAndIsNotWaiting() {
        assertTrue(CraftChestPullPolicy.shouldPause(0, false, true));
        assertFalse(CraftChestPullPolicy.shouldPause(3, false, true), "something moved");
        assertFalse(CraftChestPullPolicy.shouldPause(0, true, true), "the next pull must be free to redeem the prompt");
        assertFalse(CraftChestPullPolicy.shouldPause(0, false, false), "nothing was asked");
        assertFalse(CraftChestPullPolicy.shouldPause(2, true, false));
    }

    @Test
    void aPauseHoldsUntilItsDeadline() {
        long until = 1_000_000L;
        assertTrue(CraftChestPullPolicy.isPaused(until, until - 1));
        assertFalse(CraftChestPullPolicy.isPaused(until, until), "the deadline itself has passed");
        assertFalse(CraftChestPullPolicy.isPaused(until, until + 1));
        assertFalse(CraftChestPullPolicy.isPaused(null, 0L));
    }

    @Test
    void thePauseOutlastsAnUnansweredPromptAndThePromptCooldown() {
        Timings t = Timings.defaults();
        assertTrue(CraftChestPullPolicy.PAUSE_MS > t.requestLifetimeMs() + t.promptCooldownMs());
    }
}
