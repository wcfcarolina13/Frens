package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the deterministic local-chat eligibility rules: veto ordering (roster before
 * salience, because scoring is per-bot), the cooldown band, and the danger veto.
 */
class SoulLocalDirectorTest {

    @Test
    void firstVetoReportsGatesInSpecOrder() {
        assertNull(SoulLocalDirector.firstVeto(true, true, true, true, true, true, 1, true));

        assertEquals("disabled", SoulLocalDirector.firstVeto(false, true, true, true, true, true, 1, true));
        assertEquals("pipeline", SoulLocalDirector.firstVeto(true, false, true, true, true, true, 1, true));
        assertEquals("cooldown", SoulLocalDirector.firstVeto(true, true, false, true, true, true, 1, true));
        assertEquals("busy", SoulLocalDirector.firstVeto(true, true, true, false, true, true, 1, true));
        assertEquals("muted", SoulLocalDirector.firstVeto(true, true, true, true, false, true, 1, true));
        assertEquals("player-not-at-ease", SoulLocalDirector.firstVeto(true, true, true, true, true, false, 1, true));
        assertEquals("roster", SoulLocalDirector.firstVeto(true, true, true, true, true, true, 0, true));
        assertEquals("salience", SoulLocalDirector.firstVeto(true, true, true, true, true, true, 1, false));

        // Earlier gate wins when several fail.
        assertEquals("cooldown", SoulLocalDirector.firstVeto(true, true, false, false, false, false, 0, false));
    }

    @Test
    void rosterIsCheckedBeforeSalience() {
        // Scoring is per-bot: with no roster there is nothing to score against, so "roster"
        // must be the reported reason even when the line is also unsalient.
        assertEquals("roster", SoulLocalDirector.firstVeto(true, true, true, true, true, true, 0, false));
    }

    @Test
    void cooldownBandsHoldOverManySamples() {
        Random random = new Random(11);
        for (int i = 0; i < 1000; i++) {
            long next = SoulLocalDirector.nextDelayMs(random);
            assertTrue(next >= 6 * 60_000L && next <= 12 * 60_000L, "next=" + next);
            long initial = SoulLocalDirector.initialDelayMs(random);
            assertTrue(initial >= 0L && initial <= 2 * 60_000L, "initial=" + initial);
        }
    }

    @Test
    void groundingDangerVetoMatchesBanter() {
        assertFalse(SoulLocalDirector.groundingDangerous(SoulTypes.SituationSnapshot.empty()));
        assertTrue(SoulLocalDirector.groundingDangerous(withHostiles()));
    }

    @Test
    void replyWindowConstantsAreTheSpecValues() {
        assertEquals(30_000L, SoulLocalDirector.REPLY_WINDOW_MS);
        assertEquals(16.0, SoulLocalDirector.EARSHOT_BLOCKS);
    }

    // === ContinuationTracker (amendment C): the reply window opens on delivery, not submission,
    // and a continuation's OWN delivery must never re-open a fresh window -- otherwise the
    // reaction-plus-continuation exchange could run forever. ===

    @Test
    void firstReactionsDeliveryOpensAWindow() {
        SoulLocalDirector.ContinuationTracker tracker = new SoulLocalDirector.ContinuationTracker();
        UUID player = UUID.randomUUID();

        tracker.noteFired(player, false); // the original reaction, not a continuation
        boolean shouldOpen = tracker.consumeShouldOpenWindow(player);

        assertTrue(shouldOpen, "a plain reaction's delivery must open the reply window");
    }

    @Test
    void continuationsDeliveryDoesNotOpenAnotherWindow() {
        SoulLocalDirector.ContinuationTracker tracker = new SoulLocalDirector.ContinuationTracker();
        UUID player = UUID.randomUUID();

        tracker.noteFired(player, true); // this fire consumed an already-open window
        boolean shouldOpen = tracker.consumeShouldOpenWindow(player);

        assertFalse(shouldOpen,
                "a continuation's own delivery must not re-open a window, or the exchange never ends");
    }

    @Test
    void pendingFlagIsConsumedExactlyOnce() {
        SoulLocalDirector.ContinuationTracker tracker = new SoulLocalDirector.ContinuationTracker();
        UUID player = UUID.randomUUID();
        tracker.noteFired(player, false);

        assertTrue(tracker.consumeShouldOpenWindow(player));
        // A second delivery notification for the same player, with nothing newly fired in
        // between, must not spuriously open another window either.
        assertFalse(tracker.consumeShouldOpenWindow(player));
    }

    @Test
    void forgetClearsThePendingFlag() {
        SoulLocalDirector.ContinuationTracker tracker = new SoulLocalDirector.ContinuationTracker();
        UUID player = UUID.randomUUID();
        tracker.noteFired(player, false);

        tracker.forget(player);

        // Nothing pending after forget: no window should open off a stale/forgotten fire.
        assertFalse(tracker.consumeShouldOpenWindow(player));
    }

    @Test
    void explicitAddressAfterFireMeansTheInFlightScenesDeliveryOpensNoWindow() {
        // Regression for fix round 1, FIX 1: noteAddressedChat (and notePlayerScene) must clear
        // the tracker's pending entry, not just replyWindows -- otherwise a LOCAL scene already
        // in flight when the player explicitly addresses a bot (or a real PLAYER/BANTER scene
        // re-arms the cooldown) still opens a fresh window once it finally delivers, resurrecting
        // a window the explicit address was supposed to close for good.
        SoulLocalDirector.ContinuationTracker tracker = new SoulLocalDirector.ContinuationTracker();
        UUID player = UUID.randomUUID();
        tracker.noteFired(player, false); // scene submitted, not yet delivered

        tracker.forget(player); // the one-liner noteAddressedChat/notePlayerScene now perform

        assertFalse(tracker.consumeShouldOpenWindow(player),
                "the in-flight scene's later delivery must not open a window after an explicit address");
    }

    // === Candidate selection (fix wave FIX 1 + FIX 2): one capture per evaluation, and an open
    // reply window's bot is the candidate outright rather than competing on score. Extracted as a
    // pure helper for the same reason ContinuationTracker was -- MinecraftServer cannot be
    // constructed or mocked in this harness, so noteUnaddressedChat itself is not unit-reachable. ===

    @Test
    void highestScoringBotIsTheCandidateWithNoWindowOpen() {
        UUID nearest = UUID.randomUUID();
        UUID farther = UUID.randomUUID();
        List<SoulLocalDirector.ScoredBot> scored = List.of(
                new SoulLocalDirector.ScoredBot(nearest, 1),
                new SoulLocalDirector.ScoredBot(farther, 5));

        assertEquals(1, SoulLocalDirector.chooseCandidate(scored, null));
    }

    @Test
    void tiesAreBrokenByProximity() {
        // The list arrives nearest-first, so an equal score must not displace the nearer bot.
        List<SoulLocalDirector.ScoredBot> scored = List.of(
                new SoulLocalDirector.ScoredBot(UUID.randomUUID(), 4),
                new SoulLocalDirector.ScoredBot(UUID.randomUUID(), 4));

        assertEquals(0, SoulLocalDirector.chooseCandidate(scored, null));
    }

    @Test
    void emptyRosterHasNoCandidate() {
        assertEquals(-1, SoulLocalDirector.chooseCandidate(List.of(), UUID.randomUUID()));
    }

    @Test
    void openWindowsBotWinsEvenWhenASiblingOutscoresIt() {
        // Regression for the fix wave, FIX 1: a continuation bypasses the salience threshold and
        // therefore scores 0. On score alone a sibling at or above the threshold would displace
        // it, bestIsContinuation would go false, the cooldown gate would be re-applied, and the
        // player's follow-up would die as vetoed:cooldown -- defeating the reply window (spec §7).
        UUID windowBot = UUID.randomUUID();
        UUID loudSibling = UUID.randomUUID();
        List<SoulLocalDirector.ScoredBot> scored = List.of(
                new SoulLocalDirector.ScoredBot(loudSibling, 6),
                new SoulLocalDirector.ScoredBot(windowBot, 0));

        assertEquals(1, SoulLocalDirector.chooseCandidate(scored, windowBot),
                "the window's bot must be chosen unconditionally, not on score");
    }

    @Test
    void openWindowsBotWinsRegardlessOfItsPositionInTheRoster() {
        // Same rule with the window's bot listed first: a later, higher-scoring bot must not
        // take the candidacy back.
        UUID windowBot = UUID.randomUUID();
        List<SoulLocalDirector.ScoredBot> scored = List.of(
                new SoulLocalDirector.ScoredBot(windowBot, 0),
                new SoulLocalDirector.ScoredBot(UUID.randomUUID(), 9));

        assertEquals(0, SoulLocalDirector.chooseCandidate(scored, windowBot));
    }

    @Test
    void aWindowBotNoLongerInTheRosterDoesNotSuppressOrdinaryScoring() {
        // The window's bot walked off / lost eligibility: the remaining bots are picked on score
        // as usual (and the cooldown gate still applies to them, as it does today).
        UUID absentWindowBot = UUID.randomUUID();
        List<SoulLocalDirector.ScoredBot> scored = List.of(
                new SoulLocalDirector.ScoredBot(UUID.randomUUID(), 2),
                new SoulLocalDirector.ScoredBot(UUID.randomUUID(), 7));

        assertEquals(1, SoulLocalDirector.chooseCandidate(scored, absentWindowBot));
    }

    private static SoulTypes.SituationSnapshot withHostiles() {
        return new SoulTypes.SituationSnapshot(-1,
                List.of(new SoulTypes.HostileSighting("creeper", "north", 8)),
                List.of(), "", List.of(), false, false, false, "", false,
                false, false, 0, false, false, false, false,
                -1, -1, Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    @Test
    void captureIsSkippedWhenNoActiveTaskOverlapCouldReachTheThreshold() {
        // The only signal the capture adds is activeTask overlap, worth exactly
        // WEIGHT_TOPIC_OVERLAP. So a phase-1 score that far below THRESHOLD can never reach it,
        // and the (expensive) capture is provably pointless.
        int reachable = SoulLocalSalience.THRESHOLD - SoulLocalSalience.WEIGHT_TOPIC_OVERLAP;
        assertFalse(SoulLocalDirector.captureCouldReachThreshold(reachable - 1),
                "below the reachable floor, capture cannot change the outcome");
        assertTrue(SoulLocalDirector.captureCouldReachThreshold(reachable),
                "exactly at the floor, overlap alone would tie the threshold");
        assertTrue(SoulLocalDirector.captureCouldReachThreshold(SoulLocalSalience.THRESHOLD),
                "already over the threshold without any overlap");
        assertFalse(SoulLocalDirector.captureCouldReachThreshold(0),
                "a zero-scoring line is the common case and must not pay for a capture");
    }

    @Test
    void theSkipFloorTracksTheConstantsRatherThanAHardcodedNumber() {
        // Guards against someone retuning THRESHOLD or WEIGHT_TOPIC_OVERLAP in the salience
        // class and silently making this optimisation unsound.
        for (int phase1 = 0; phase1 <= SoulLocalSalience.THRESHOLD + 2; phase1++) {
            boolean couldReach = phase1 + SoulLocalSalience.WEIGHT_TOPIC_OVERLAP
                    >= SoulLocalSalience.THRESHOLD;
            assertEquals(couldReach, SoulLocalDirector.captureCouldReachThreshold(phase1),
                    "phase1=" + phase1);
        }
    }

    @Test
    void engagementWindowRuleOpensOnlyForDeliveredAddressPlayerBanter() {
        assertTrue(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.BANTER, true, 1, 0));
        assertFalse(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.BANTER, false, 3, 1), "plain banter never opens");
        assertFalse(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.BANTER, true, 0, -1), "zero deliveries never opens");
        assertFalse(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.BANTER, true, 2, -1), "no identified speaker, no window");
        assertFalse(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.LOCAL, true, 1, 0),
                "LOCAL scenes keep their own tracker path");
        assertFalse(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.PLAYER, true, 1, 0));
    }
    @Test
    void scaledCooldownBandFollowsTheMultiplier() {
        Random random = new Random(5);
        for (int i = 0; i < 500; i++) {
            long slow = SoulLocalDirector.nextDelayMs(random, 4.0);
            assertTrue(slow >= 24 * 60_000L && slow <= 48 * 60_000L, "slow=" + slow);
        }
    }
}
