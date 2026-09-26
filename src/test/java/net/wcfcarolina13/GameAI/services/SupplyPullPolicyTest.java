package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.SupplyPullPolicy.Backoff;
import net.wcfcarolina13.GameAI.services.SupplyPullPolicy.Next;
import net.wcfcarolina13.GameAI.services.SupplyPullPolicy.Pull;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Scope;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.WaitMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyPullPolicyTest {

    /** The common per-scope rule for a refusal; NONE on a refusal fails closed as BOT. */
    private static final Map<Scope, Next> REFUSAL_NEXT = new EnumMap<>(Map.of(
            Scope.ITEM, Next.SKIP_ITEM,
            Scope.CHEST, Next.SKIP_CHEST,
            Scope.TARGET, Next.SKIP_TARGET,
            Scope.OWNER_ABSENT, Next.OWNER_AWAY,
            Scope.BOT, Next.STOP,
            Scope.BUSY, Next.BUSY,
            Scope.INVENTORY_FULL, Next.FULL,
            Scope.NONE, Next.STOP));
    /** Only the owner's decision is a miss (NONE on a refusal reads BOT). */
    private static final Set<Scope> MISSES = EnumSet.of(Scope.BOT, Scope.NONE);
    /** Answers that end the pass: the owner's decision, a busy answer and a full inventory. */
    private static final Set<Scope> STOPS = EnumSet.of(Scope.BOT, Scope.BUSY, Scope.INVENTORY_FULL, Scope.NONE);

    // ── one answer: Scope × Kind ─────────────────────────────────────────────────────────────

    @Test
    void everyRefusalScopeReachesAsFarAsTheCommonRuleSays() {
        assertEquals(EnumSet.allOf(Scope.class), REFUSAL_NEXT.keySet(), "every scope has an expected answer");
        for (Scope scope : Scope.values()) {
            assertEquals(REFUSAL_NEXT.get(scope), SupplyPullPolicy.next(Kind.REFUSED, scope), scope.name());
        }
        // A refusal that somehow carries no scope reads as the facade reads it: BOT.
        assertEquals(Next.STOP, SupplyPullPolicy.next(Kind.REFUSED, null));
    }

    @Test
    void anAnswerThatIsNotARefusalMovesOnOrHoldsWhateverScopeItCarries() {
        for (Scope scope : scopesAndNull()) {
            assertEquals(Next.NEXT, SupplyPullPolicy.next(Kind.MOVED, scope), "" + scope);
            assertEquals(Next.HOLD, SupplyPullPolicy.next(Kind.WAITING, scope), "" + scope);
            assertEquals(Next.HOLD, SupplyPullPolicy.next(Kind.READY, scope), "" + scope);
            assertEquals(Next.STOP, SupplyPullPolicy.next(null, scope), "" + scope);
        }
    }

    @Test
    void everyKindAndScopeFoldsIntoExactlyTheFlagsTheRuleSays() {
        for (Kind kind : Kind.values()) {
            for (Scope scope : scopesAndNull()) {
                String at = kind + "/" + scope;
                boolean refused = kind == Kind.REFUSED;
                Scope read = scope == null || scope == Scope.NONE ? Scope.BOT : scope;
                Pull p = SupplyPullPolicy.fold(Pull.NOTHING, kind, 3, scope);
                assertEquals(kind == Kind.MOVED ? 3 : 0, p.moved(), at);
                assertEquals(kind == Kind.WAITING || kind == Kind.READY, p.waiting(), at);
                assertEquals(refused && read == Scope.BUSY, p.busy(), at);
                assertEquals(refused && read == Scope.INVENTORY_FULL, p.full(), at);
                assertEquals(refused && read == Scope.BOT, p.missed(), at);
                assertEquals(refused && read == Scope.OWNER_ABSENT, p.ownerAway(), at);
                assertEquals(SupplyPullPolicy.next(kind, scope).stopsPass(), p.halted(), at);
                assertEquals(p.waiting() || p.busy(), p.held(), at);
                assertEquals(refused && (read == Scope.ITEM || read == Scope.TARGET),
                        SupplyPullPolicy.tryNextStack(kind, scope), at);
            }
        }
    }

    @Test
    void onlyTheOwnersDecisionAHoldABusyAnswerOrAFullInventoryEndsThePass() {
        for (Next next : Next.values()) {
            assertEquals(next == Next.STOP || next == Next.HOLD || next == Next.BUSY || next == Next.FULL,
                    next.stopsPass(), next.name());
        }
        for (Scope scope : Scope.values()) {
            assertEquals(STOPS.contains(scope), SupplyPullPolicy.next(Kind.REFUSED, scope).stopsPass(), scope.name());
        }
        // The owner away skips that chest only: a farther chest the owner granted "always" is still asked.
        assertFalse(SupplyPullPolicy.next(Kind.REFUSED, Scope.OWNER_ABSENT).stopsPass());
        // An item this chest cannot grant leaves the chest's other items, and other chests, to ask.
        assertFalse(SupplyPullPolicy.next(Kind.REFUSED, Scope.TARGET).stopsPass());
    }

    @Test
    void waitingIsAnOpenPromptOrAPendingGrantOnly() {
        for (Kind kind : Kind.values()) {
            assertEquals(kind == Kind.WAITING || kind == Kind.READY, SupplyPullPolicy.isWaiting(kind), kind.name());
        }
        assertFalse(SupplyPullPolicy.isWaiting(null));
    }

    @Test
    void aMissIsOnlyTheOwnersDecision() {
        for (Scope scope : Scope.values()) {
            assertEquals(MISSES.contains(scope), SupplyPullPolicy.isMiss(Kind.REFUSED, scope), scope.name());
            for (Kind kind : Kind.values()) {
                if (kind != Kind.REFUSED) {
                    assertFalse(SupplyPullPolicy.isMiss(kind, scope), kind + " " + scope);
                }
            }
        }
        assertTrue(SupplyPullPolicy.isMiss(Kind.REFUSED, null), "no scope reads BOT");
        assertFalse(SupplyPullPolicy.isMiss(null, Scope.BOT));
    }

    @Test
    void busyFullAndOwnerAwayAreTheirOwnAnswers() {
        for (Scope scope : Scope.values()) {
            assertEquals(scope == Scope.BUSY, SupplyPullPolicy.isBusy(Kind.REFUSED, scope), scope.name());
            assertEquals(scope == Scope.INVENTORY_FULL, SupplyPullPolicy.isFull(Kind.REFUSED, scope), scope.name());
            assertEquals(scope == Scope.OWNER_ABSENT, SupplyPullPolicy.isOwnerAway(Kind.REFUSED, scope), scope.name());
            for (Kind kind : Kind.values()) {
                if (kind != Kind.REFUSED) {
                    assertFalse(SupplyPullPolicy.isBusy(kind, scope), kind + " " + scope);
                    assertFalse(SupplyPullPolicy.isFull(kind, scope), kind + " " + scope);
                    assertFalse(SupplyPullPolicy.isOwnerAway(kind, scope), kind + " " + scope);
                }
            }
        }
        assertFalse(SupplyPullPolicy.isBusy(Kind.REFUSED, null));
        assertFalse(SupplyPullPolicy.isFull(Kind.REFUSED, null));
        assertFalse(SupplyPullPolicy.isOwnerAway(Kind.REFUSED, null));
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
    void theNextStackInAChestIsTriedAfterANeverGrantedItemOrOneThisChestCannotGrant() {
        for (Scope scope : Scope.values()) {
            assertEquals(scope == Scope.ITEM || scope == Scope.TARGET, SupplyPullPolicy.tryNextStack(Kind.REFUSED, scope),
                    scope.name());
        }
        assertFalse(SupplyPullPolicy.tryNextStack(Kind.REFUSED, null));
        // FB concern 1: a lone stone axe at its reserve no longer hides the wooden axes beside it.
        assertTrue(SupplyPullPolicy.tryNextStack(Kind.REFUSED, Scope.TARGET));
    }

    // ── stopping a chest tool search ─────────────────────────────────────────────────────────

    /**
     * Re-review B N2: /bot stop lands while the bot walks to the first chest. forceAbort removes the
     * task at once, so a task read per chest is already false while the abort latch stays set. Read
     * once at the start, the search stops before the next chest. The durability fallback's search,
     * begun outside any task, still ignores a latch a /bot come left behind.
     */
    @Test
    void rereviewBN2ATaskStopEndsTheToolSearchThoughTheTaskIsAlreadyGone() {
        assertTrue(SupplyPullPolicy.stopsToolSearch(true, true), "/bot stop mid-search: the task began it");
        assertFalse(SupplyPullPolicy.stopsToolSearch(true, false));
        assertFalse(SupplyPullPolicy.stopsToolSearch(false, true), "no task at the start: a stale latch is ignored");
        assertFalse(SupplyPullPolicy.stopsToolSearch(false, false));
    }

    // ── a pass ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aPullTalliesMovesHoldsMissesOwnerAwayAndStops() {
        Pull p = SupplyPullPolicy.fold(Pull.NOTHING, Kind.REFUSED, 0, Scope.ITEM);
        assertEquals(Pull.NOTHING, p);
        p = SupplyPullPolicy.fold(p, Kind.REFUSED, 0, Scope.CHEST);
        p = SupplyPullPolicy.fold(p, Kind.REFUSED, 0, Scope.TARGET);
        assertEquals(Pull.NOTHING, p, "item, chest and target refusals add nothing: nothing found");
        p = SupplyPullPolicy.fold(p, Kind.MOVED, 4, Scope.NONE);
        assertEquals(new Pull(4, false, false, false, false, false, false), p);
        p = SupplyPullPolicy.fold(p, Kind.REFUSED, 0, Scope.OWNER_ABSENT);
        assertEquals(new Pull(4, false, false, false, false, true, false), p, "the owner away does not halt");
        p = SupplyPullPolicy.fold(p, Kind.WAITING, 0, Scope.NONE);
        assertEquals(new Pull(4, true, false, false, false, true, true), p);
        assertEquals(new Pull(0, false, false, false, true, false, true), SupplyPullPolicy.fold(null, Kind.REFUSED, 0, Scope.BOT));
        assertEquals(new Pull(0, false, true, false, false, false, true), SupplyPullPolicy.fold(null, Kind.REFUSED, 0, Scope.BUSY));
        assertEquals(new Pull(0, false, false, true, false, false, true),
                SupplyPullPolicy.fold(null, Kind.REFUSED, 0, Scope.INVENTORY_FULL));
        // A refused kind never adds to the moved count, whatever number it carries.
        assertEquals(0, SupplyPullPolicy.fold(Pull.NOTHING, Kind.REFUSED, 5, Scope.TARGET).moved());
        assertEquals(new Pull(5, true, true, true, true, true, true),
                new Pull(1, false, true, false, true, false, false).plus(new Pull(4, true, false, true, false, true, true)));
        assertEquals(new Pull(1, false, false, false, false, false, false),
                new Pull(1, false, false, false, false, false, false).plus(null));
    }

    /** One pass over answers in order, as the chest loops run it: stop at the first answer that ends the pass. */
    private static Pull pass(Object... kindsAndScopes) {
        Pull p = Pull.NOTHING;
        for (int i = 0; i < kindsAndScopes.length; i += 2) {
            Kind kind = (Kind) kindsAndScopes[i];
            Scope scope = (Scope) kindsAndScopes[i + 1];
            p = SupplyPullPolicy.fold(p, kind, kind == Kind.MOVED ? 1 : 0, scope);
            if (SupplyPullPolicy.next(kind, scope).stopsPass()) {
                break;
            }
        }
        return p;
    }

    @Test
    void aFartherAlwaysChestIsStillReachedWhileTheOwnerIsAway() {
        // Nearest chest: owner away. Farther chest: the owner granted "always", so it serves.
        Pull p = pass(Kind.REFUSED, Scope.OWNER_ABSENT, Kind.MOVED, Scope.NONE);
        assertEquals(1, p.moved());
        assertEquals(Backoff.SUCCESS, SupplyPullPolicy.idleBackoff(p));
        assertEquals(0L, SupplyPullPolicy.retrievalPauseMs(p, 2));
        assertFalse(SupplyPullPolicy.ownerAwayDefers(p));
    }

    @Test
    void aPassThatOnlyFoundTheOwnerAwayWaitsFlatAndNeverCountsAMiss() {
        Pull p = pass(Kind.REFUSED, Scope.OWNER_ABSENT, Kind.REFUSED, Scope.TARGET, Kind.REFUSED, Scope.OWNER_ABSENT);
        assertTrue(SupplyPullPolicy.ownerAwayDefers(p));
        assertEquals(Backoff.OWNER_AWAY, SupplyPullPolicy.idleBackoff(p));
        assertEquals(SupplyPullPolicy.OWNER_AWAY_PAUSE_MS, SupplyPullPolicy.retrievalPauseMs(p, 0));
        assertEquals(SupplyPullPolicy.OWNER_AWAY_PAUSE_MS, SupplyPullPolicy.retrievalPauseMs(p, 4),
                "the owner-away pause never climbs with earlier misses");
        assertEquals(3, SupplyPullPolicy.nextMissCount(3, p));
        // The idle backoff is keyed in server ticks: 60 s is 1200 ticks.
        assertEquals(60_000L, SupplyPullPolicy.OWNER_AWAY_PAUSE_MS);
        assertEquals(1_200L, SupplyPullPolicy.OWNER_AWAY_PAUSE_TICKS);
        assertEquals(SupplyPullPolicy.OWNER_AWAY_PAUSE_MS, SupplyPullPolicy.OWNER_AWAY_PAUSE_TICKS * 50L);
    }

    @Test
    void theOwnerAwayDeferIsOnlyForAPassNothingElseEnded() {
        boolean[] both = {false, true};
        for (int moved : new int[] {0, 2}) {
            for (boolean waiting : both) {
                for (boolean busy : both) {
                    for (boolean full : both) {
                        for (boolean missed : both) {
                            for (boolean away : both) {
                                Pull p = new Pull(moved, waiting, busy, full, missed, away,
                                        waiting || busy || full || missed);
                                boolean expected = away && moved == 0 && !waiting && !busy && !full && !missed;
                                assertEquals(expected, SupplyPullPolicy.ownerAwayDefers(p), p.toString());
                            }
                        }
                    }
                }
            }
        }
        assertFalse(SupplyPullPolicy.ownerAwayDefers(null));
    }

    @Test
    void theOwnersDecisionAfterAnOwnerAwayChestIsStillAMiss() {
        // Precedence pinned: the No (or ignored prompt, or cooldown) climbs the ladder; the absence does not hide it.
        Pull p = pass(Kind.REFUSED, Scope.OWNER_ABSENT, Kind.REFUSED, Scope.BOT);
        assertTrue(p.missed() && p.ownerAway() && p.halted());
        assertEquals(Backoff.FAILURE, SupplyPullPolicy.idleBackoff(p));
        assertEquals(SupplyPullPolicy.missPauseMs(1), SupplyPullPolicy.retrievalPauseMs(p, 1));
        assertEquals(2, SupplyPullPolicy.nextMissCount(1, p));
    }

    @Test
    void aBusyAnswerStopsThePassAndHoldsLikeAnOpenPrompt() {
        // Another prompt of this bot open (OTHER_REQUEST_PENDING), a busy server: never a miss.
        Pull p = pass(Kind.REFUSED, Scope.OWNER_ABSENT, Kind.REFUSED, Scope.BUSY, Kind.MOVED, Scope.NONE);
        assertEquals(0, p.moved(), "nothing after the busy answer is asked");
        assertTrue(p.busy() && p.held() && p.halted());
        assertFalse(p.missed());
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(p));
        assertEquals(SupplyPullPolicy.WAITING_RECHECK_MS, SupplyPullPolicy.retrievalPauseMs(p, 3));
        assertEquals(3, SupplyPullPolicy.nextMissCount(3, p));
        assertFalse(SupplyPullPolicy.ownerAwayDefers(p), "held, so no flat minute either");
        assertTrue(SupplyPullPolicy.holdsIdleFallback(p, true));
    }

    /**
     * Re-review B N1: a toolless bot with 36 full slots and an in-reach "always" chest holding an
     * axe. Every ask is covered, finds no room (NO_ROOM, the facade keeps the ticket) and nothing
     * ever frees a slot. The pass stops, but it must not hold the idle wooden fallback, or the bot
     * would never craft or cut wood again: it falls through as if nothing were found, pass after
     * pass, with no miss and no owner-away defer. (A chest tool search still waits the flat
     * minute: review FF I1.)
     */
    @Test
    void rereviewBN1AFullBotWithAnAlwaysChestAxeDoesNotHoldTheIdleFallback() {
        SupplyWithdrawalPolicy.TransferAction noRoom = SupplyWithdrawalPolicy.onTransfer(TransferStatus.NO_ROOM, 0);
        assertEquals(Kind.REFUSED, noRoom.kind());
        assertEquals(Scope.INVENTORY_FULL, noRoom.scope(), "not BUSY: nothing a short wait fixes");
        assertTrue(noRoom.keepTicket(), "a caller that makes room may still take it");
        int misses = 0;
        for (int probe = 0; probe < 5; probe++) {
            // idle-weapon finds nothing (TARGET), idle-axe gets NO_ROOM, and the rest is not asked.
            Pull p = pass(Kind.REFUSED, Scope.TARGET, noRoom.kind(), noRoom.scope(), Kind.MOVED, Scope.NONE);
            assertEquals(0, p.moved(), "nothing after the full inventory is asked");
            assertTrue(p.full() && p.halted());
            assertFalse(p.held(), "a full inventory is not a hold");
            assertFalse(p.missed());
            assertFalse(SupplyPullPolicy.holdsIdleFallback(p, true), "the fallback crafts or cuts wood");
            assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(p));
            assertEquals(SupplyPullPolicy.OWNER_AWAY_PAUSE_MS, SupplyPullPolicy.retrievalPauseMs(p, misses),
                    "a flat minute, never the ladder");
            misses = SupplyPullPolicy.nextMissCount(misses, p);
        }
        assertEquals(0, misses, "never a miss, however often it repeats");
        // An owner-away chest before it does not turn the full stop into an owner-away defer either.
        Pull awayThenFull = pass(Kind.REFUSED, Scope.OWNER_ABSENT, Kind.REFUSED, Scope.INVENTORY_FULL);
        assertFalse(SupplyPullPolicy.ownerAwayDefers(awayThenFull));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(awayThenFull));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(awayThenFull, true));
        // What moved before the inventory filled still counts as a success.
        Pull movedThenFull = pass(Kind.MOVED, Scope.NONE, Kind.REFUSED, Scope.INVENTORY_FULL);
        assertEquals(Backoff.SUCCESS, SupplyPullPolicy.idleBackoff(movedThenFull));
    }

    /**
     * Review FF I1: the axe breaks mid-tree, all 36 slots are full and an Always chest holds an
     * axe. The facade answers READY before it checks room, so each search walks to the chest for
     * NO_ROOM, and Woodcut searches before every log. The search now waits a flat 60 s: never a
     * miss, never climbing, never a hold, and the idle fallback (N1) still does not hold.
     */
    @Test
    void reviewFFI1AFullToolSearchPausesSixtySecondsWithoutAMiss() {
        for (int misses : new int[] {0, 3, 7}) {
            Pull p = pass(Kind.REFUSED, Scope.TARGET, Kind.REFUSED, Scope.INVENTORY_FULL, Kind.MOVED, Scope.NONE);
            assertTrue(p.full() && p.halted());
            assertFalse(p.held(), "not a hold: the next search refreshes the snapshots");
            assertFalse(p.missed());
            assertEquals(60_000L, SupplyPullPolicy.retrievalPauseMs(p, misses), "flat, whatever the misses");
            assertEquals(SupplyPullPolicy.OWNER_AWAY_PAUSE_MS, SupplyPullPolicy.retrievalPauseMs(p, misses));
            assertEquals(misses, SupplyPullPolicy.nextMissCount(misses, p), "the miss ladder is untouched");
            assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(p));
            assertFalse(SupplyPullPolicy.holdsIdleFallback(p, true), "N1: full never holds the idle fallback");
        }
        Pull awayThenFull = pass(Kind.REFUSED, Scope.OWNER_ABSENT, Kind.REFUSED, Scope.INVENTORY_FULL);
        assertEquals(60_000L, SupplyPullPolicy.retrievalPauseMs(awayThenFull, 2));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, awayThenFull));
        Pull movedThenFull = pass(Kind.MOVED, Scope.NONE, Kind.REFUSED, Scope.INVENTORY_FULL);
        assertEquals(0L, SupplyPullPolicy.retrievalPauseMs(movedThenFull, 2), "it took its tool");
    }

    @Test
    void aPassOfOnlyItemChestAndTargetRefusalsFoundNothing() {
        // FB concern 3: NO_MATCH / NOT_CHEST / UNREACHABLE (CHEST) and a spare at its reserve (TARGET)
        // are "nothing found": no miss, no pause, no hold.
        Pull p = pass(Kind.REFUSED, Scope.ITEM, Kind.REFUSED, Scope.CHEST, Kind.REFUSED, Scope.TARGET);
        assertEquals(Pull.NOTHING, p);
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(p));
        assertEquals(0L, SupplyPullPolicy.retrievalPauseMs(p, 2));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, p));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(p, true));
    }

    @Test
    void aHeldPassNeverBacksOffSoItsGrantCanBeRedeemed() {
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(new Pull(0, true, false, false, true, false, true)));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(new Pull(2, true, false, false, false, true, true)));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(new Pull(0, false, true, false, false, true, true)));
        assertEquals(Backoff.FAILURE, SupplyPullPolicy.idleBackoff(new Pull(0, false, false, false, true, false, true)));
        assertEquals(Backoff.FAILURE, SupplyPullPolicy.idleBackoff(new Pull(3, false, false, false, true, false, true)));
        assertEquals(Backoff.SUCCESS, SupplyPullPolicy.idleBackoff(new Pull(1, false, false, false, false, false, false)));
        assertEquals(Backoff.SUCCESS, SupplyPullPolicy.idleBackoff(new Pull(1, false, false, false, false, true, false)));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(new Pull(0, false, false, true, false, true, true)));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(Pull.NOTHING));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(null));
    }

    @Test
    void theIdleFallbackHoldsOnlyWhileTheIdlePullHeldForAToolItStillLacks() {
        assertTrue(SupplyPullPolicy.holdsIdleFallback(new Pull(0, true, false, false, false, false, true), true));
        assertTrue(SupplyPullPolicy.holdsIdleFallback(new Pull(0, false, true, false, false, false, true), true));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(new Pull(0, true, false, false, false, false, true), false));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(new Pull(0, false, false, false, true, false, true), true));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(new Pull(0, false, false, false, false, true, false), true));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(new Pull(0, false, false, true, false, false, true), true),
                "a full inventory never holds it");
        assertFalse(SupplyPullPolicy.holdsIdleFallback(null, true));
    }

    // ── how long to leave the owner alone ────────────────────────────────────────────────────

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
    void aRetrievalComesBackSoonAfterAHoldBacksOffAfterAMissAndWaitsFlatForAnAbsentOwner() {
        Pull waiting = new Pull(0, true, false, false, false, false, true);
        Pull busy = new Pull(0, false, true, false, false, true, true);
        Pull away = new Pull(0, false, false, false, false, true, false);
        Pull missed = new Pull(0, false, false, false, true, false, true);
        assertEquals(SupplyPullPolicy.WAITING_RECHECK_MS, SupplyPullPolicy.retrievalPauseMs(waiting, 3));
        assertEquals(SupplyPullPolicy.WAITING_RECHECK_MS, SupplyPullPolicy.retrievalPauseMs(busy, 3));
        assertEquals(SupplyPullPolicy.OWNER_AWAY_PAUSE_MS, SupplyPullPolicy.retrievalPauseMs(away, 0));
        assertEquals(60_000L, SupplyPullPolicy.retrievalPauseMs(missed, 0));
        assertEquals(240_000L, SupplyPullPolicy.retrievalPauseMs(missed, 2));
        assertEquals(0L, SupplyPullPolicy.retrievalPauseMs(Pull.NOTHING, 2));
        assertEquals(SupplyPullPolicy.OWNER_AWAY_PAUSE_MS,
                SupplyPullPolicy.retrievalPauseMs(new Pull(0, false, false, true, false, true, true), 2),
                "a full inventory pauses the search a flat minute (review FF I1)");
        assertEquals(0L, SupplyPullPolicy.retrievalPauseMs(null, 2));
        assertEquals(0L, SupplyPullPolicy.retrievalPauseMs(new Pull(1, false, false, false, true, false, true), 2),
                "a search that took its tool never pauses");
        assertTrue(SupplyPullPolicy.WAITING_RECHECK_MS < 60_000L, "must recheck well inside a grant's 60 s life");
    }

    @Test
    void theMissCounterResetsOnAMoveAndClimbsOnlyOnTheOwnersDecision() {
        assertEquals(0, SupplyPullPolicy.nextMissCount(4, new Pull(1, false, false, false, true, false, true)));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, new Pull(0, true, false, false, true, false, true)));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, new Pull(0, false, true, false, false, false, true)));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, new Pull(0, false, false, false, false, true, false)));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, Pull.NOTHING));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, null));
        assertEquals(3, SupplyPullPolicy.nextMissCount(2, new Pull(0, false, false, false, true, false, true)));
        assertEquals(3, SupplyPullPolicy.nextMissCount(2, new Pull(0, false, false, false, true, true, true)));
        assertEquals(1, SupplyPullPolicy.nextMissCount(-1, new Pull(0, false, false, false, true, false, true)));
        assertEquals(HobbyBackoffPolicy.MAX_FAILURE_COUNT, SupplyPullPolicy.nextMissCount(
                HobbyBackoffPolicy.MAX_FAILURE_COUNT, new Pull(0, false, false, false, true, false, true)));
    }

    @Test
    void anOwnerAwayForHoursStillGetsAskedWithinAMinuteOfComingBack() {
        // R2 I2: each search while the owner is away used to add a miss, reaching the 10 min
        // ceiling; now it stays at the flat pause and the counter never moves.
        int misses = 0;
        for (int search = 0; search < 20; search++) {
            Pull away = SupplyPullPolicy.fold(Pull.NOTHING, Kind.REFUSED, 0, Scope.OWNER_ABSENT);
            assertEquals(SupplyPullPolicy.OWNER_AWAY_PAUSE_MS, SupplyPullPolicy.retrievalPauseMs(away, misses));
            misses = SupplyPullPolicy.nextMissCount(misses, away);
        }
        assertEquals(0, misses);
        // Contention for the owner (another prompt open) never climbs either: it comes back soon.
        Pull busy = SupplyPullPolicy.fold(Pull.NOTHING, Kind.REFUSED, 0, Scope.BUSY);
        assertEquals(SupplyPullPolicy.WAITING_RECHECK_MS, SupplyPullPolicy.retrievalPauseMs(busy, 3));
        assertEquals(3, SupplyPullPolicy.nextMissCount(3, busy));
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

    private static List<Scope> scopesAndNull() {
        List<Scope> all = new ArrayList<>(Arrays.asList(Scope.values()));
        all.add(null);
        return all;
    }
}
