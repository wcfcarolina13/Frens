package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.CraftChestPullPolicy.Next;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Timings;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Refusal;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Scope;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for CraftingHelper's chest-pull decisions — no net.minecraft types, no world, no reason strings. */
class CraftChestPullPolicyTest {

    // ── refusals: the one per-scope rule ─────────────────────────────────────────────────────

    @Test
    void everyScopeHasADeliberateNext() {
        Map<Scope, Next> expected = new EnumMap<>(Scope.class);
        expected.put(Scope.ITEM, Next.SKIP_ITEM);
        expected.put(Scope.CHEST, Next.SKIP_CHEST);
        expected.put(Scope.BOT, Next.STOP);
        expected.put(Scope.OWNER_ABSENT, Next.STOP);
        expected.put(Scope.TRANSIENT, Next.NEXT);          // this stack only; the pass goes on
        expected.put(Scope.NONE, Next.STOP);               // never a refusal's scope: fail closed
        assertEquals(EnumSet.allOf(Scope.class), expected.keySet(), "a new Scope needs a decision here");
        expected.forEach((scope, next) -> {
            assertEquals(next, CraftChestPullPolicy.onRefusal(scope), scope.name());
            assertEquals(next, CraftChestPullPolicy.afterAsk(Kind.REFUSED, scope, true), "on thread " + scope);
            assertEquals(next, CraftChestPullPolicy.afterAsk(Kind.REFUSED, scope, false), "off thread " + scope);
            assertEquals(next, CraftChestPullPolicy.afterWalk(Kind.REFUSED, scope), "after walk " + scope);
        });
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal(null));
    }

    @Test
    void theOwnersNoOrAnIgnoredPromptStopsThePassInsteadOfAskingTheNextChest() {
        // R3 I1: NOT_PERMITTED used to skip to the next chest, which prompted again right after a No.
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal(Refusal.NOT_PERMITTED.scope()));
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal(
                SupplyWithdrawalPolicy.transferScope(TransferStatus.NOT_PERMITTED)));
        assertTrue(CraftChestPullPolicy.countsTowardPause(Kind.REFUSED, Refusal.NOT_PERMITTED.scope()));
        assertTrue(CraftChestPullPolicy.shouldPause(0, false, true));
        // Aligned with the other supply sites: a duplicate prompt is the bot's, not the chest's.
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal(
                SupplyWithdrawalPolicy.requestScope(RequestStatus.DUPLICATE_PENDING, null, null)));
    }

    @Test
    void everyFacadeRefusalHasADeliberateNext() {
        Map<Refusal, Next> expected = new EnumMap<>(Refusal.class);
        expected.put(Refusal.NOT_RUNNING, Next.NEXT);
        expected.put(Refusal.BOT_GONE, Next.NEXT);
        expected.put(Refusal.INVALID, Next.NEXT);
        expected.put(Refusal.NO_OWNER, Next.STOP);
        expected.put(Refusal.CHEST_UNREADABLE, Next.NEXT);
        expected.put(Refusal.OWNER_NOT_NEARBY, Next.STOP);
        expected.put(Refusal.OTHER_REQUEST_PENDING, Next.STOP);
        expected.put(Refusal.NOT_PERMITTED, Next.STOP);
        expected.put(Refusal.TIMEOUT, Next.NEXT);
        expected.put(Refusal.ABORTED, Next.NEXT);
        expected.put(Refusal.SERVER_BUSY, Next.NEXT);
        expected.put(Refusal.ERROR, Next.NEXT);
        assertEquals(EnumSet.allOf(Refusal.class), expected.keySet(), "a new Refusal needs a decision here");
        expected.forEach((refusal, next) ->
                assertEquals(next, CraftChestPullPolicy.onRefusal(refusal.scope()), refusal.name()));
    }

    @Test
    void everyRequestStatusAsARefusalHasADeliberateNext() {
        Map<RequestStatus, Next> expected = new EnumMap<>(RequestStatus.class);
        expected.put(RequestStatus.OPENED, Next.STOP);             // a prompt without a fingerprint: fail closed
        expected.put(RequestStatus.DUPLICATE_PENDING, Next.STOP);
        expected.put(RequestStatus.PROMPT_COOLDOWN, Next.STOP);
        expected.put(RequestStatus.REJECT_COOLDOWN, Next.STOP);
        expected.put(RequestStatus.COVERED_BY_ALWAYS, Next.STOP);  // covered without a fingerprint: fail closed
        expected.put(RequestStatus.INELIGIBLE, Next.STOP);         // no verdict attached: fail closed
        expected.put(RequestStatus.OWNER_NOT_NEARBY, Next.STOP);
        expected.put(RequestStatus.DENIED, Next.SKIP_CHEST);
        expected.put(RequestStatus.INVALID, Next.NEXT);
        expected.put(RequestStatus.NOT_RUNNING, Next.NEXT);
        expected.put(RequestStatus.WRONG_THREAD, Next.NEXT);
        assertEquals(EnumSet.allOf(RequestStatus.class), expected.keySet(), "a new RequestStatus needs a decision here");
        expected.forEach((status, next) -> assertEquals(next,
                CraftChestPullPolicy.onRefusal(SupplyWithdrawalPolicy.requestScope(status, null, null)), status.name()));
    }

    @Test
    void everyTransferStatusAsARefusalHasADeliberateNext() {
        Map<TransferStatus, Next> expected = new EnumMap<>(TransferStatus.class);
        expected.put(TransferStatus.MOVED, Next.NEXT);          // a refusal only when nothing moved
        expected.put(TransferStatus.MOVED_SHORT, Next.NEXT);
        expected.put(TransferStatus.WRONG_THREAD, Next.NEXT);
        expected.put(TransferStatus.NOT_RUNNING, Next.NEXT);
        expected.put(TransferStatus.INVALID, Next.NEXT);
        expected.put(TransferStatus.OWNER_OR_BOT_MISMATCH, Next.STOP);
        expected.put(TransferStatus.DENIED, Next.SKIP_CHEST);
        expected.put(TransferStatus.CHEST_MISMATCH, Next.SKIP_CHEST);
        expected.put(TransferStatus.OUT_OF_REACH, Next.NEXT);   // arrives as READY, never as a refusal
        expected.put(TransferStatus.NO_STOCK, Next.SKIP_CHEST);
        expected.put(TransferStatus.NO_ROOM, Next.NEXT);
        expected.put(TransferStatus.NOT_PERMITTED, Next.STOP);
        expected.put(TransferStatus.INELIGIBLE_NOW, Next.SKIP_CHEST);
        assertEquals(EnumSet.allOf(TransferStatus.class), expected.keySet(), "a new TransferStatus needs a decision here");
        expected.forEach((status, next) -> assertEquals(next,
                CraftChestPullPolicy.onRefusal(SupplyWithdrawalPolicy.transferScope(status)), status.name()));
    }

    @Test
    void anIneligibleVerdictReachesTheItemTheChestOrTheBot() {
        Map<Verdict, Next> expected = new EnumMap<>(Verdict.class);
        expected.put(Verdict.ELIGIBLE, Next.STOP);              // nonsense as a refusal: fail closed
        expected.put(Verdict.NOT_ALLOWLISTED, Next.SKIP_ITEM);
        expected.put(Verdict.PROTECTED_COMPONENTS, Next.SKIP_ITEM);
        expected.put(Verdict.TIER_NOT_ALLOWED, Next.SKIP_ITEM);
        expected.put(Verdict.NO_NEED, Next.SKIP_ITEM);
        expected.put(Verdict.RESERVE_EXHAUSTED, Next.SKIP_CHEST);
        expected.put(Verdict.NO_OWNER, Next.STOP);
        assertEquals(EnumSet.allOf(Verdict.class), expected.keySet(), "a new Verdict needs a decision here");
        expected.forEach((verdict, next) -> assertEquals(next,
                CraftChestPullPolicy.onRefusal(SupplyWithdrawalPolicy.requestScope(RequestStatus.INELIGIBLE, null,
                        verdict)), verdict.name()));
        // The pre-filter's refusal, made before any ask, reaches the item.
        assertEquals(Next.SKIP_ITEM, CraftChestPullPolicy.onRefusal(
                SupplyWithdrawalPolicy.preFilterScope(Verdict.NOT_ALLOWLISTED)));
    }

    // ── walking ──────────────────────────────────────────────────────────────────────────────

    @Test
    void onTheServerThreadNothingWalks() {
        for (Kind kind : Kind.values()) {
            for (Scope scope : Scope.values()) {
                assertNotEquals(Next.WALK, CraftChestPullPolicy.afterAsk(kind, scope, true), kind + "/" + scope);
            }
        }
        // Permitted but out of reach from the tick: hold the grant for a pull that can walk.
        assertEquals(Next.HOLD, CraftChestPullPolicy.afterAsk(Kind.READY, Scope.NONE, true));
        assertEquals(Next.HOLD, CraftChestPullPolicy.afterAsk(Kind.WAITING, Scope.NONE, true));
        assertEquals(Next.NEXT, CraftChestPullPolicy.afterAsk(Kind.MOVED, Scope.NONE, true));
    }

    @Test
    void offTheServerThreadOnlyAPermittedTicketWalks() {
        assertEquals(Next.WALK, CraftChestPullPolicy.afterAsk(Kind.READY, Scope.NONE, false));
        // R3 M2: an open prompt may yet be refused, so the bot does not walk to it.
        assertEquals(Next.HOLD, CraftChestPullPolicy.afterAsk(Kind.WAITING, Scope.NONE, false));
        assertEquals(Next.NEXT, CraftChestPullPolicy.afterAsk(Kind.MOVED, Scope.NONE, false));
        for (Scope scope : Scope.values()) {
            assertNotEquals(Next.WALK, CraftChestPullPolicy.afterAsk(Kind.REFUSED, scope, false), scope.name());
        }
    }

    @Test
    void afterTheWalkAnOpenTicketHoldsAndNothingWalksAgain() {
        assertEquals(Next.NEXT, CraftChestPullPolicy.afterWalk(Kind.MOVED, Scope.NONE));
        // The walk did not reach the chest: the yes is still live, so neither the next chest nor a pause.
        assertEquals(Next.HOLD, CraftChestPullPolicy.afterWalk(Kind.READY, Scope.NONE));
        assertEquals(Next.HOLD, CraftChestPullPolicy.afterWalk(Kind.WAITING, Scope.NONE));
        for (Kind kind : Kind.values()) {
            for (Scope scope : Scope.values()) {
                assertNotEquals(Next.WALK, CraftChestPullPolicy.afterWalk(kind, scope), kind + "/" + scope);
            }
        }
        assertFalse(CraftChestPullPolicy.shouldPause(0, true, true), "a held ticket never pauses");
    }

    @Test
    void aMissingKindStops() {
        for (Scope scope : Scope.values()) {
            assertEquals(Next.STOP, CraftChestPullPolicy.afterAsk(null, scope, false), scope.name());
            assertEquals(Next.STOP, CraftChestPullPolicy.afterAsk(null, scope, true), scope.name());
            assertEquals(Next.STOP, CraftChestPullPolicy.afterWalk(null, scope), scope.name());
        }
    }

    // ── the held ticket ──────────────────────────────────────────────────────────────────────

    @Test
    void aHeldTicketIsSettledByAMoveOrARefusalThatIsNotTransient() {
        for (Scope scope : Scope.values()) {
            assertTrue(CraftChestPullPolicy.settlesHeldTicket(Kind.MOVED, scope), "moved " + scope);
            assertFalse(CraftChestPullPolicy.settlesHeldTicket(Kind.READY, scope), "ready " + scope);
            assertFalse(CraftChestPullPolicy.settlesHeldTicket(Kind.WAITING, scope), "waiting " + scope);
            assertEquals(scope != Scope.TRANSIENT, CraftChestPullPolicy.settlesHeldTicket(Kind.REFUSED, scope),
                    "refused " + scope);
            assertTrue(CraftChestPullPolicy.settlesHeldTicket(null, scope), "no kind " + scope);
        }
        // A busy hop or a full inventory says nothing about the owner's answer: keep asking it first.
        assertFalse(CraftChestPullPolicy.settlesHeldTicket(Kind.REFUSED, Refusal.SERVER_BUSY.scope()));
        assertFalse(CraftChestPullPolicy.settlesHeldTicket(Kind.REFUSED,
                SupplyWithdrawalPolicy.transferScope(TransferStatus.NO_ROOM)));
        assertTrue(CraftChestPullPolicy.settlesHeldTicket(Kind.REFUSED, Refusal.NOT_PERMITTED.scope()));
    }

    // ── the pause ────────────────────────────────────────────────────────────────────────────

    @Test
    void onlyARefusalFromTheOwnersSideCountsTowardThePause() {
        EnumSet<Scope> counting = EnumSet.of(Scope.CHEST, Scope.BOT, Scope.OWNER_ABSENT);
        for (Kind kind : Kind.values()) {
            for (Scope scope : Scope.values()) {
                boolean expected = kind == Kind.REFUSED && counting.contains(scope);
                assertEquals(expected, CraftChestPullPolicy.countsTowardPause(kind, scope), kind + "/" + scope);
            }
        }
        assertFalse(CraftChestPullPolicy.countsTowardPause(Kind.REFUSED, null));
        assertFalse(CraftChestPullPolicy.countsTowardPause(null, Scope.BOT));
    }

    @Test
    void aPullPausesOnlyWhenItMetARefusalMovedNothingAndHoldsNoTicket() {
        assertTrue(CraftChestPullPolicy.shouldPause(0, false, true));
        assertFalse(CraftChestPullPolicy.shouldPause(3, false, true), "something moved");
        assertFalse(CraftChestPullPolicy.shouldPause(0, true, true), "the next pull must be free to redeem the ticket");
        assertFalse(CraftChestPullPolicy.shouldPause(0, false, false), "only the item, or transient failures");
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

    @Test
    void theOwnerAwayDeferIsAFlatMinute() {
        assertEquals(60_000L, CraftChestPullPolicy.PAUSE_MS);
        assertTrue(CraftChestPullPolicy.PAUSE_MS >= SupplyWithdrawalPolicy.OWNER_AWAY_MEMO_MS,
                "the pause outlasts the facade's quiet owner-away memo");
    }
}
