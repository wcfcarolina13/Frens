package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.SupplyPullPolicy.Backoff;
import net.wcfcarolina13.GameAI.services.SupplyPullPolicy.Next;
import net.wcfcarolina13.GameAI.services.SupplyPullPolicy.Pull;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Scope;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.WaitMode;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
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
            Scope.TARGET, Next.SKIP_CHEST,
            Scope.BOT, Next.STOP,
            Scope.OWNER_ABSENT, Next.OWNER_AWAY,
            Scope.BUSY, Next.RETRY_LATER,
            Scope.NONE, Next.STOP));
    private static final Set<Scope> MISSES = EnumSet.of(Scope.CHEST, Scope.TARGET, Scope.BOT, Scope.NONE);

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
    void anAnswerThatIsNotARefusalMovesOnOrStopsWhateverScopeItCarries() {
        for (Scope scope : Scope.values()) {
            assertEquals(Next.NEXT, SupplyPullPolicy.next(Kind.MOVED, scope), scope.name());
            assertEquals(Next.STOP, SupplyPullPolicy.next(Kind.WAITING, scope), scope.name());
            assertEquals(Next.STOP, SupplyPullPolicy.next(Kind.READY, scope), scope.name());
            assertEquals(Next.STOP, SupplyPullPolicy.next(null, scope), scope.name());
        }
    }

    @Test
    void onlyABotRefusalAnOpenPromptOrTheOwnerAwayEndsThePass() {
        for (Next next : Next.values()) {
            assertEquals(next == Next.STOP || next == Next.OWNER_AWAY, next.stopsPass(), next.name());
        }
        for (Scope scope : Scope.values()) {
            boolean stops = scope == Scope.BOT || scope == Scope.OWNER_ABSENT || scope == Scope.NONE;
            assertEquals(stops, SupplyPullPolicy.next(Kind.REFUSED, scope).stopsPass(), scope.name());
        }
        // A transient refusal leaves its chest for later and never stops the rest of the run.
        assertFalse(SupplyPullPolicy.next(Kind.REFUSED, Scope.BUSY).stopsPass());
    }

    @Test
    void waitingIsAnOpenPromptOrAPendingGrantOnly() {
        for (Kind kind : Kind.values()) {
            assertEquals(kind == Kind.WAITING || kind == Kind.READY, SupplyPullPolicy.isWaiting(kind), kind.name());
        }
        assertFalse(SupplyPullPolicy.isWaiting(null));
    }

    @Test
    void aMissIsAChestOrBotRefusalNeverTheOwnerAwayOrATransientOne() {
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
    void ownerAwayIsItsOwnAnswer() {
        for (Scope scope : Scope.values()) {
            assertEquals(scope == Scope.OWNER_ABSENT, SupplyPullPolicy.isOwnerAway(Kind.REFUSED, scope), scope.name());
            for (Kind kind : Kind.values()) {
                if (kind != Kind.REFUSED) {
                    assertFalse(SupplyPullPolicy.isOwnerAway(kind, scope), kind + " " + scope);
                }
            }
        }
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
    void theNextStackInAChestIsTriedOnlyAfterANeverGrantedItem() {
        for (Scope scope : Scope.values()) {
            assertEquals(scope == Scope.ITEM, SupplyPullPolicy.tryNextStack(Kind.REFUSED, scope), scope.name());
            for (Kind kind : Kind.values()) {
                if (kind != Kind.REFUSED) {
                    assertFalse(SupplyPullPolicy.tryNextStack(kind, scope), kind + " " + scope);
                }
            }
        }
        assertFalse(SupplyPullPolicy.tryNextStack(Kind.REFUSED, null));
    }

    @Test
    void aPullTalliesMovesWaitsMissesOwnerAwayAndStops() {
        Pull p = SupplyPullPolicy.fold(Pull.NOTHING, Kind.REFUSED, 0, Scope.ITEM);
        assertEquals(Pull.NOTHING, p);
        p = SupplyPullPolicy.fold(p, Kind.REFUSED, 0, Scope.BUSY);
        assertEquals(Pull.NOTHING, p, "a transient refusal adds nothing");
        p = SupplyPullPolicy.fold(p, Kind.MOVED, 4, Scope.NONE);
        assertEquals(new Pull(4, false, false, false, false), p);
        p = SupplyPullPolicy.fold(p, Kind.REFUSED, 0, Scope.CHEST);
        assertEquals(new Pull(4, false, true, false, false), p);
        p = SupplyPullPolicy.fold(p, Kind.WAITING, 0, Scope.NONE);
        assertEquals(new Pull(4, true, true, false, true), p);
        assertEquals(new Pull(0, false, true, false, true), SupplyPullPolicy.fold(null, Kind.REFUSED, 0, Scope.BOT));
        assertEquals(new Pull(0, false, false, true, true),
                SupplyPullPolicy.fold(null, Kind.REFUSED, 0, Scope.OWNER_ABSENT));
        // A refused kind never adds to the moved count, whatever number it carries.
        assertEquals(0, SupplyPullPolicy.fold(Pull.NOTHING, Kind.REFUSED, 5, Scope.BUSY).moved());
        assertEquals(new Pull(5, true, true, true, true),
                new Pull(1, false, true, false, false).plus(new Pull(4, true, false, true, true)));
        assertEquals(new Pull(1, false, false, false, false), new Pull(1, false, false, false, false).plus(null));
    }

    @Test
    void anOpenPromptNeverBacksOffSoItsGrantCanBeRedeemed() {
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(new Pull(0, true, true, false, true)));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(new Pull(2, true, false, true, true)));
        assertEquals(Backoff.FAILURE, SupplyPullPolicy.idleBackoff(new Pull(0, false, true, false, true)));
        assertEquals(Backoff.FAILURE, SupplyPullPolicy.idleBackoff(new Pull(3, false, true, false, false)));
        assertEquals(Backoff.SUCCESS, SupplyPullPolicy.idleBackoff(new Pull(1, false, false, false, false)));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(Pull.NOTHING));
        assertEquals(Backoff.NONE, SupplyPullPolicy.idleBackoff(null));
    }

    @Test
    void anIdlePullThatFoundTheOwnerAwayRechecksFlatAndNeverCountsAFailure() {
        // Even after a chest miss earlier in the same pull: a bot left alone must not climb the ladder.
        assertEquals(Backoff.OWNER_AWAY, SupplyPullPolicy.idleBackoff(new Pull(0, false, false, true, true)));
        assertEquals(Backoff.OWNER_AWAY, SupplyPullPolicy.idleBackoff(new Pull(0, false, true, true, true)));
        assertEquals(Backoff.OWNER_AWAY, SupplyPullPolicy.idleBackoff(new Pull(2, false, false, true, true)));
        // The idle backoff is keyed in server ticks: 60 s is 1200 ticks.
        assertEquals(60_000L, SupplyPullPolicy.OWNER_AWAY_PAUSE_MS);
        assertEquals(1_200L, SupplyPullPolicy.OWNER_AWAY_PAUSE_TICKS);
        assertEquals(SupplyPullPolicy.OWNER_AWAY_PAUSE_MS, SupplyPullPolicy.OWNER_AWAY_PAUSE_TICKS * 50L);
    }

    @Test
    void theIdleFallbackHoldsOnlyForAToolItStillLacks() {
        assertTrue(SupplyPullPolicy.holdsIdleFallback(new Pull(0, true, false, false, true), true));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(new Pull(0, true, false, false, true), false));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(new Pull(0, false, true, false, true), true));
        assertFalse(SupplyPullPolicy.holdsIdleFallback(new Pull(0, false, false, true, true), true));
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
    void aRetrievalComesBackSoonForAPromptFlatForAnAbsentOwnerAndBacksOffAfterAMiss() {
        Pull waiting = new Pull(0, true, true, false, true);
        Pull away = new Pull(0, false, true, true, true);
        Pull missed = new Pull(0, false, true, false, true);
        assertEquals(SupplyPullPolicy.WAITING_RECHECK_MS, SupplyPullPolicy.retrievalPauseMs(waiting, 3));
        assertEquals(SupplyPullPolicy.OWNER_AWAY_PAUSE_MS, SupplyPullPolicy.retrievalPauseMs(away, 0));
        assertEquals(SupplyPullPolicy.OWNER_AWAY_PAUSE_MS, SupplyPullPolicy.retrievalPauseMs(away, 4),
                "the owner-away pause never climbs with earlier misses");
        assertEquals(60_000L, SupplyPullPolicy.retrievalPauseMs(missed, 0));
        assertEquals(240_000L, SupplyPullPolicy.retrievalPauseMs(missed, 2));
        assertEquals(0L, SupplyPullPolicy.retrievalPauseMs(Pull.NOTHING, 2));
        assertEquals(0L, SupplyPullPolicy.retrievalPauseMs(null, 2));
        assertEquals(0L, SupplyPullPolicy.retrievalPauseMs(new Pull(1, false, true, false, false), 2),
                "a search that took its tool never pauses");
        assertTrue(SupplyPullPolicy.WAITING_RECHECK_MS < 60_000L, "must recheck well inside a grant's 60 s life");
    }

    @Test
    void theMissCounterResetsOnAMoveAndClimbsOnlyOnARealMiss() {
        assertEquals(0, SupplyPullPolicy.nextMissCount(4, new Pull(1, false, true, false, false)));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, new Pull(0, true, true, false, true)));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, new Pull(0, false, true, true, true)));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, Pull.NOTHING));
        assertEquals(2, SupplyPullPolicy.nextMissCount(2, null));
        assertEquals(3, SupplyPullPolicy.nextMissCount(2, new Pull(0, false, true, false, true)));
        assertEquals(1, SupplyPullPolicy.nextMissCount(-1, new Pull(0, false, true, false, true)));
        assertEquals(HobbyBackoffPolicy.MAX_FAILURE_COUNT, SupplyPullPolicy.nextMissCount(
                HobbyBackoffPolicy.MAX_FAILURE_COUNT, new Pull(0, false, true, false, true)));
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
        // Transient trouble (a busy server, a full inventory) never climbs either, and pauses nothing.
        Pull busy = SupplyPullPolicy.fold(Pull.NOTHING, Kind.REFUSED, 0, Scope.BUSY);
        assertEquals(0L, SupplyPullPolicy.retrievalPauseMs(busy, 3));
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
}
