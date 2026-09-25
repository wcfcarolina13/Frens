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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for CraftingHelper's chest-pull decisions — no net.minecraft types, no world, no reason strings. */
class CraftChestPullPolicyTest {

    /** The common per-scope rule, as this site spells it. */
    private static final Map<Scope, Next> REFUSAL_NEXT = new EnumMap<>(Map.of(
            Scope.ITEM, Next.SKIP_ITEM,
            Scope.CHEST, Next.SKIP_CHEST,
            Scope.TARGET, Next.SKIP_TARGET,
            Scope.OWNER_ABSENT, Next.OWNER_AWAY,
            Scope.BOT, Next.STOP,
            Scope.BUSY, Next.BUSY,
            Scope.INVENTORY_FULL, Next.FULL,
            Scope.NONE, Next.STOP));             // never a refusal's scope: fail closed

    // ── refusals: the one per-scope rule ─────────────────────────────────────────────────────

    @Test
    void everyScopeHasADeliberateNext() {
        assertEquals(EnumSet.allOf(Scope.class), REFUSAL_NEXT.keySet(), "a new Scope needs a decision here");
        REFUSAL_NEXT.forEach((scope, next) -> {
            assertEquals(next, CraftChestPullPolicy.onRefusal(scope), scope.name());
            assertEquals(next, CraftChestPullPolicy.afterAsk(Kind.REFUSED, scope, true), "on thread " + scope);
            assertEquals(next, CraftChestPullPolicy.afterAsk(Kind.REFUSED, scope, false), "off thread " + scope);
            assertEquals(next, CraftChestPullPolicy.afterWalk(Kind.REFUSED, scope), "after walk " + scope);
        });
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal(null));
    }

    @Test
    void everyKindAndScopeIsDecidedOnceOnAndOffTheServerThread() {
        for (Kind kind : Kind.values()) {
            for (Scope scope : scopesAndNull()) {
                String at = kind + "/" + scope;
                Next expected = switch (kind) {
                    case MOVED -> Next.NEXT;
                    case WAITING -> Next.HOLD;
                    case READY -> null; // depends on the thread, below
                    case REFUSED -> scope == null ? Next.STOP : REFUSAL_NEXT.get(scope);
                };
                if (kind == Kind.READY) {
                    assertEquals(Next.HOLD, CraftChestPullPolicy.afterAsk(kind, scope, true), at);
                    assertEquals(Next.WALK, CraftChestPullPolicy.afterAsk(kind, scope, false), at);
                    assertEquals(Next.HOLD, CraftChestPullPolicy.afterWalk(kind, scope), at);
                } else {
                    assertEquals(expected, CraftChestPullPolicy.afterAsk(kind, scope, true), at);
                    assertEquals(expected, CraftChestPullPolicy.afterAsk(kind, scope, false), at);
                    assertEquals(expected, CraftChestPullPolicy.afterWalk(kind, scope), at);
                }
                boolean refused = kind == Kind.REFUSED;
                Scope read = scope == null || scope == Scope.NONE ? Scope.BOT : scope;
                assertEquals(refused && read == Scope.BOT, CraftChestPullPolicy.countsTowardPause(kind, scope), at);
                assertEquals(refused && scope == Scope.OWNER_ABSENT, CraftChestPullPolicy.isOwnerAway(kind, scope), at);
                boolean settles = kind == Kind.MOVED
                        || (refused && scope != Scope.BUSY && scope != Scope.INVENTORY_FULL);
                assertEquals(settles, CraftChestPullPolicy.settlesHeldTicket(kind, scope), at);
            }
        }
    }

    @Test
    void theOwnersNoOrAnIgnoredPromptStopsThePassAndPauses() {
        // R3 I1: NOT_PERMITTED used to skip to the next chest, which prompted again right after a No.
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal(Refusal.NOT_PERMITTED.scope()));
        assertEquals(Next.STOP, CraftChestPullPolicy.onRefusal(
                SupplyWithdrawalPolicy.transferScope(TransferStatus.NOT_PERMITTED)));
        assertTrue(CraftChestPullPolicy.countsTowardPause(Kind.REFUSED, Refusal.NOT_PERMITTED.scope()));
        assertTrue(CraftChestPullPolicy.shouldPause(0, false, false, true, false));
    }

    @Test
    void anotherPromptOpenStopsThePassWithoutAPause() {
        // FC concern 4: contention for the owner is not their answer. A logs pull made while a planks
        // prompt is open stops, and does not pause logs for a minute.
        for (Scope contention : List.of(Refusal.OTHER_REQUEST_PENDING.scope(),
                SupplyWithdrawalPolicy.requestScope(RequestStatus.DUPLICATE_PENDING, null, null))) {
            assertEquals(Next.BUSY, CraftChestPullPolicy.onRefusal(contention));
            assertFalse(CraftChestPullPolicy.countsTowardPause(Kind.REFUSED, contention));
        }
        assertFalse(CraftChestPullPolicy.shouldPause(0, false, true, false, false));
        assertFalse(CraftChestPullPolicy.shouldPause(0, false, true, false, true), "busy holds: no owner-away minute");
    }

    @Test
    void noRoomAndABusyServerStopThePassInsteadOfAskingTheNextChest() {
        // FC concern 2: no room used to go on (prompting for items the bot cannot hold), a lagging
        // server cost a hop per candidate.
        for (Scope busy : List.of(Refusal.SERVER_BUSY.scope(), Refusal.TIMEOUT.scope(), Refusal.ABORTED.scope())) {
            assertEquals(Next.BUSY, CraftChestPullPolicy.onRefusal(busy), busy.name());
        }
        // Re-review B N1: no room is its own scope, and it stops the pull the same way: no pause.
        Scope full = SupplyWithdrawalPolicy.transferScope(TransferStatus.NO_ROOM);
        assertEquals(Scope.INVENTORY_FULL, full);
        assertEquals(Next.FULL, CraftChestPullPolicy.onRefusal(full));
        assertFalse(CraftChestPullPolicy.countsTowardPause(Kind.REFUSED, full));
        assertFalse(CraftChestPullPolicy.shouldPause(0, false, true, false, true),
                "a pull stopped on a full inventory never pauses, owner away or not");
    }

    @Test
    void theOwnerAwaySkipsOnlyThatChestSoAFartherAlwaysChestIsStillAsked() {
        // FC concern 1: the owner away used to stop the pull.
        assertEquals(Next.OWNER_AWAY, CraftChestPullPolicy.onRefusal(Refusal.OWNER_NOT_NEARBY.scope()));
        assertEquals(Next.OWNER_AWAY, CraftChestPullPolicy.onRefusal(
                SupplyWithdrawalPolicy.requestScope(RequestStatus.OWNER_NOT_NEARBY, null, null)));
        assertFalse(CraftChestPullPolicy.countsTowardPause(Kind.REFUSED, Scope.OWNER_ABSENT));
        // The pull then took something from the Always chest: no pause.
        assertFalse(CraftChestPullPolicy.shouldPause(4, false, false, false, true));
        // It found only the owner away: the flat minute.
        assertTrue(CraftChestPullPolicy.shouldPause(0, false, false, false, true));
    }

    @Test
    void aSpareAtItsReserveSkipsThatItemInThatChestOnly() {
        // FC concern 3: a chest whose oak planks hit the reserve still offers its spruce planks.
        assertEquals(Next.SKIP_TARGET, CraftChestPullPolicy.onRefusal(
                SupplyWithdrawalPolicy.verdictScope(Verdict.RESERVE_EXHAUSTED)));
        for (TransferStatus status : EnumSet.of(TransferStatus.NO_STOCK, TransferStatus.INELIGIBLE_NOW)) {
            assertEquals(Next.SKIP_TARGET, CraftChestPullPolicy.onRefusal(SupplyWithdrawalPolicy.transferScope(status)),
                    status.name());
        }
        assertFalse(CraftChestPullPolicy.countsTowardPause(Kind.REFUSED, Scope.TARGET));
    }

    @Test
    void everyFacadeRefusalHasADeliberateNext() {
        Map<Refusal, Next> expected = new EnumMap<>(Refusal.class);
        expected.put(Refusal.NOT_RUNNING, Next.BUSY);
        expected.put(Refusal.BOT_GONE, Next.BUSY);
        expected.put(Refusal.INVALID, Next.BUSY);
        expected.put(Refusal.NO_OWNER, Next.STOP);
        expected.put(Refusal.CHEST_UNREADABLE, Next.SKIP_CHEST);
        expected.put(Refusal.OWNER_NOT_NEARBY, Next.OWNER_AWAY);
        expected.put(Refusal.OTHER_REQUEST_PENDING, Next.BUSY);
        expected.put(Refusal.NOT_PERMITTED, Next.STOP);
        expected.put(Refusal.TIMEOUT, Next.BUSY);
        expected.put(Refusal.ABORTED, Next.BUSY);
        expected.put(Refusal.SERVER_BUSY, Next.BUSY);
        expected.put(Refusal.ERROR, Next.BUSY);
        assertEquals(EnumSet.allOf(Refusal.class), expected.keySet(), "a new Refusal needs a decision here");
        expected.forEach((refusal, next) ->
                assertEquals(next, CraftChestPullPolicy.onRefusal(refusal.scope()), refusal.name()));
    }

    @Test
    void everyRequestStatusAsARefusalHasADeliberateNext() {
        Map<RequestStatus, Next> expected = new EnumMap<>(RequestStatus.class);
        expected.put(RequestStatus.OPENED, Next.STOP);             // a prompt without a fingerprint: fail closed
        expected.put(RequestStatus.DUPLICATE_PENDING, Next.BUSY);
        expected.put(RequestStatus.PROMPT_COOLDOWN, Next.STOP);
        expected.put(RequestStatus.REJECT_COOLDOWN, Next.STOP);
        expected.put(RequestStatus.COVERED_BY_ALWAYS, Next.STOP);  // covered without a fingerprint: fail closed
        expected.put(RequestStatus.COVERED_BY_GRANT, Next.STOP);   // covered without a fingerprint: fail closed
        expected.put(RequestStatus.INELIGIBLE, Next.STOP);         // no verdict attached: fail closed
        expected.put(RequestStatus.OWNER_NOT_NEARBY, Next.OWNER_AWAY);
        expected.put(RequestStatus.DENIED, Next.SKIP_CHEST);
        expected.put(RequestStatus.INVALID, Next.BUSY);
        expected.put(RequestStatus.NOT_RUNNING, Next.BUSY);
        expected.put(RequestStatus.WRONG_THREAD, Next.BUSY);
        assertEquals(EnumSet.allOf(RequestStatus.class), expected.keySet(), "a new RequestStatus needs a decision here");
        expected.forEach((status, next) -> assertEquals(next,
                CraftChestPullPolicy.onRefusal(SupplyWithdrawalPolicy.requestScope(status, null, null)), status.name()));
    }

    @Test
    void everyTransferStatusAsARefusalHasADeliberateNext() {
        Map<TransferStatus, Next> expected = new EnumMap<>(TransferStatus.class);
        expected.put(TransferStatus.MOVED, Next.SKIP_TARGET);        // a refusal only when nothing moved
        expected.put(TransferStatus.MOVED_SHORT, Next.SKIP_TARGET);
        expected.put(TransferStatus.WRONG_THREAD, Next.BUSY);
        expected.put(TransferStatus.NOT_RUNNING, Next.BUSY);
        expected.put(TransferStatus.INVALID, Next.BUSY);
        expected.put(TransferStatus.OWNER_OR_BOT_MISMATCH, Next.STOP);
        expected.put(TransferStatus.DENIED, Next.SKIP_CHEST);
        expected.put(TransferStatus.CHEST_MISMATCH, Next.SKIP_CHEST);
        expected.put(TransferStatus.OUT_OF_REACH, Next.SKIP_CHEST);   // arrives as READY, never as a refusal
        expected.put(TransferStatus.NO_STOCK, Next.SKIP_TARGET);
        expected.put(TransferStatus.NO_ROOM, Next.FULL);
        expected.put(TransferStatus.NOT_PERMITTED, Next.STOP);
        expected.put(TransferStatus.INELIGIBLE_NOW, Next.SKIP_TARGET);
        assertEquals(EnumSet.allOf(TransferStatus.class), expected.keySet(), "a new TransferStatus needs a decision here");
        expected.forEach((status, next) -> assertEquals(next,
                CraftChestPullPolicy.onRefusal(SupplyWithdrawalPolicy.transferScope(status)), status.name()));
    }

    @Test
    void anIneligibleVerdictReachesTheItemTheItemInThisChestOrTheBot() {
        Map<Verdict, Next> expected = new EnumMap<>(Verdict.class);
        expected.put(Verdict.ELIGIBLE, Next.STOP);              // nonsense as a refusal: fail closed
        expected.put(Verdict.NOT_ALLOWLISTED, Next.SKIP_ITEM);
        expected.put(Verdict.PROTECTED_COMPONENTS, Next.SKIP_ITEM);
        expected.put(Verdict.TIER_NOT_ALLOWED, Next.SKIP_ITEM);
        expected.put(Verdict.NO_NEED, Next.SKIP_ITEM);
        expected.put(Verdict.RESERVE_EXHAUSTED, Next.SKIP_TARGET);
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
        assertFalse(CraftChestPullPolicy.shouldPause(0, true, false, true, true), "a held ticket never pauses");
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
    void aHeldTicketIsSettledByAMoveOrARefusalThatIsNotBusyOrFull() {
        for (Scope scope : Scope.values()) {
            assertTrue(CraftChestPullPolicy.settlesHeldTicket(Kind.MOVED, scope), "moved " + scope);
            assertFalse(CraftChestPullPolicy.settlesHeldTicket(Kind.READY, scope), "ready " + scope);
            assertFalse(CraftChestPullPolicy.settlesHeldTicket(Kind.WAITING, scope), "waiting " + scope);
            assertEquals(scope != Scope.BUSY && scope != Scope.INVENTORY_FULL,
                    CraftChestPullPolicy.settlesHeldTicket(Kind.REFUSED, scope), "refused " + scope);
            assertTrue(CraftChestPullPolicy.settlesHeldTicket(null, scope), "no kind " + scope);
        }
        // A busy hop or a full inventory says nothing about the owner's answer: keep asking it first.
        assertFalse(CraftChestPullPolicy.settlesHeldTicket(Kind.REFUSED, Refusal.SERVER_BUSY.scope()));
        assertFalse(CraftChestPullPolicy.settlesHeldTicket(Kind.REFUSED,
                SupplyWithdrawalPolicy.transferScope(TransferStatus.NO_ROOM)));
        assertTrue(CraftChestPullPolicy.settlesHeldTicket(Kind.REFUSED, Refusal.NOT_PERMITTED.scope()));
        // The facade drops the ticket on a policy refusal at the chest: the hint goes with it.
        assertTrue(CraftChestPullPolicy.settlesHeldTicket(Kind.REFUSED,
                SupplyWithdrawalPolicy.transferScope(TransferStatus.INELIGIBLE_NOW)));
    }

    // ── the pause ────────────────────────────────────────────────────────────────────────────

    @Test
    void onlyTheOwnersDecisionCountsTowardThePause() {
        for (Kind kind : Kind.values()) {
            for (Scope scope : Scope.values()) {
                boolean expected = kind == Kind.REFUSED && (scope == Scope.BOT || scope == Scope.NONE);
                assertEquals(expected, CraftChestPullPolicy.countsTowardPause(kind, scope), kind + "/" + scope);
            }
        }
        assertTrue(CraftChestPullPolicy.countsTowardPause(Kind.REFUSED, null), "no scope reads BOT");
        assertFalse(CraftChestPullPolicy.countsTowardPause(null, Scope.BOT));
    }

    @Test
    void aPullPausesOnlyOnTheOwnersDecisionOrAbsenceWhenItMovedNothingHeldNothingAndWasNotBusyOrFull() {
        boolean[] both = {false, true};
        for (int moved : new int[] {0, 3}) {
            for (boolean holding : both) {
                for (boolean busyOrFull : both) {
                    for (boolean decided : both) {
                        for (boolean away : both) {
                            boolean expected = moved == 0 && !holding && !busyOrFull && (decided || away);
                            assertEquals(expected,
                                    CraftChestPullPolicy.shouldPause(moved, holding, busyOrFull, decided, away),
                                    "moved=" + moved + " holding=" + holding + " busyOrFull=" + busyOrFull
                                            + " decided=" + decided + " away=" + away);
                        }
                    }
                }
            }
        }
        // Only item, chest and target refusals: nothing found, no pause (the pre-1.1.220 behaviour).
        assertFalse(CraftChestPullPolicy.shouldPause(0, false, false, false, false));
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

    private static List<Scope> scopesAndNull() {
        List<Scope> all = new ArrayList<>(Arrays.asList(Scope.values()));
        all.add(null);
        return all;
    }
}
