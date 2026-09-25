package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the DM follow-up window (addressee rule R1): opens only on a delivered reply to the
 * player's newest DM, refreshes on each later delivery, closes at its exact deadline, on
 * {@code close} and when a DM to a different bot is submitted, and never lets a slow reply to an
 * older DM steal the window.
 */
class SoulDmFollowUpWindowTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final SoulDmFollowUpWindow window = new SoulDmFollowUpWindow(now::get);
    private final UUID player = UUID.randomUUID();
    private final UUID jake = UUID.randomUUID();
    private final UUID wren = UUID.randomUUID();

    private UUID submit(UUID bot) {
        UUID routingId = UUID.randomUUID();
        window.noteSubmitted(player, bot, routingId);
        return routingId;
    }

    @Test
    void noWindowBeforeAnyDelivery() {
        assertTrue(window.current(player).isEmpty());
        submit(jake);
        assertTrue(window.current(player).isEmpty(), "a submitted DM alone opens nothing");
    }

    @Test
    void deliveredReplyToTheNewestDmOpensTheWindow() {
        UUID dm = submit(jake);
        assertTrue(window.noteDelivered(player, jake, dm));
        Optional<SoulDmFollowUpWindow.Open> open = window.current(player);
        assertTrue(open.isPresent());
        assertEquals(jake, open.get().botId());
        assertEquals(0L, open.get().ageMs());
    }

    @Test
    void deliveryWithoutAnySubmissionOpensNothing() {
        assertFalse(window.noteDelivered(player, jake, UUID.randomUUID()));
        assertTrue(window.current(player).isEmpty());
    }

    @Test
    void windowReportsItsAge() {
        window.noteDelivered(player, jake, submit(jake));
        now.addAndGet(12_345L);
        assertEquals(12_345L, window.current(player).orElseThrow().ageMs());
    }

    @Test
    void windowClosesAtTheExactDeadline() {
        window.noteDelivered(player, jake, submit(jake));
        now.addAndGet(SoulDmFollowUpWindow.WINDOW_MS - 1);
        assertTrue(window.current(player).isPresent(), "still open one millisecond before the deadline");
        now.addAndGet(1);
        assertTrue(window.current(player).isEmpty(), "closed at the deadline itself");
        now.addAndGet(-1);
        assertTrue(window.current(player).isEmpty(), "an expired window is dropped, not resurrected");
    }

    @Test
    void laterDeliveryRefreshesTheDeadline() {
        window.noteDelivered(player, jake, submit(jake));
        now.addAndGet(20_000L);
        UUID followUp = submit(jake);
        assertTrue(window.current(player).isPresent(), "a new submission keeps the open window");
        now.addAndGet(5_000L);
        assertTrue(window.noteDelivered(player, jake, followUp));
        now.addAndGet(SoulDmFollowUpWindow.WINDOW_MS - 1);
        assertTrue(window.current(player).isPresent(), "deadline counts from the latest delivery");
    }

    @Test
    void pendingReplyReopensAWindowThatExpiredMeanwhile() {
        window.noteDelivered(player, jake, submit(jake));
        now.addAndGet(25_000L);
        UUID followUp = submit(jake);
        now.addAndGet(10_000L);
        assertTrue(window.current(player).isEmpty());
        assertTrue(window.noteDelivered(player, jake, followUp));
        assertTrue(window.current(player).isPresent());
    }

    @Test
    void slowReplyToAnOlderDmNeverStealsTheWindow() {
        UUID toJake = submit(jake);
        UUID toWren = submit(wren);
        assertFalse(window.noteDelivered(player, jake, toJake), "Jake answers late: stale routing id");
        assertTrue(window.current(player).isEmpty());
        assertTrue(window.noteDelivered(player, wren, toWren));
        assertEquals(wren, window.current(player).orElseThrow().botId());
        assertFalse(window.noteDelivered(player, jake, toJake));
        assertEquals(wren, window.current(player).orElseThrow().botId());
    }

    @Test
    void newerDmToAnotherBotClosesTheWindowAtSubmitTime() {
        UUID toJake = submit(jake);
        window.noteDelivered(player, jake, toJake);
        UUID toWren = submit(wren);
        assertTrue(window.current(player).isEmpty(), "Jake's window closes when the DM to Wren is submitted");
        assertFalse(window.noteDelivered(player, jake, toJake), "Jake's late refresh cannot reopen it");
        assertTrue(window.current(player).isEmpty());
        assertTrue(window.noteDelivered(player, wren, toWren));
        assertEquals(wren, window.current(player).orElseThrow().botId());
    }

    @Test
    void newerDmToTheSameBotKeepsTheWindowOpenUntilItsReply() {
        window.noteDelivered(player, jake, submit(jake));
        now.addAndGet(3_000L);
        submit(jake);
        SoulDmFollowUpWindow.Open open = window.current(player).orElseThrow();
        assertEquals(jake, open.botId());
        assertEquals(3_000L, open.ageMs(), "the pending follow-up does not reset the age");
    }

    @Test
    void closeDropsTheWindowAndThePendingDm() {
        UUID dm = submit(jake);
        window.noteDelivered(player, jake, dm);
        window.close(player);
        assertTrue(window.current(player).isEmpty());
        UUID inFlight = submit(jake);
        window.close(player);
        assertFalse(window.noteDelivered(player, jake, inFlight),
                "a reply still in flight at close time cannot reopen the window");
        assertTrue(window.current(player).isEmpty());
    }

    @Test
    void playersAreIndependent() {
        UUID other = UUID.randomUUID();
        window.noteDelivered(player, jake, submit(jake));
        assertTrue(window.current(other).isEmpty());
        window.close(other);
        assertTrue(window.current(player).isPresent());
    }

    @Test
    void nullPlayerReadsAndClosesAreSafe() {
        assertTrue(window.current(null).isEmpty());
        window.close(null);
    }
}
