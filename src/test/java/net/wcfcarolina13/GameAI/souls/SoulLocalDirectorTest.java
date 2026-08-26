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

    private static SoulTypes.SituationSnapshot withHostiles() {
        return new SoulTypes.SituationSnapshot(-1,
                List.of(new SoulTypes.HostileSighting("creeper", "north", 8)),
                List.of(), "", List.of(), false, false, false, "", false,
                false, false, 0, false, false, false, false,
                -1, -1, Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }
}
