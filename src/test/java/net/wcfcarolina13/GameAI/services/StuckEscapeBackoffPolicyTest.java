package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.StuckEscapeBackoffPolicy.Outcome;
import net.wcfcarolina13.GameAI.services.StuckEscapeBackoffPolicy.Result;
import net.wcfcarolina13.GameAI.services.StuckEscapeBackoffPolicy.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StuckEscapeBackoffPolicyTest {

    private static final int Y = 62;

    /** A failed attempt in the finding-#17 pocket: on the ground at Y=62, 0 blocks, daytime. */
    private static Result fail(State prior, long now) {
        return StuckEscapeBackoffPolicy.recordAttempt(prior, false, true, Y, 0, true, now);
    }

    private static State failTimes(int n) {
        State s = StuckEscapeBackoffPolicy.NONE_STATE;
        for (int i = 0; i < n; i++) {
            s = fail(s, 1000L * i).state();
        }
        return s;
    }

    @Test
    void cooldownProgressionDoublesFromTheSecondFailure() {
        assertEquals(0L, StuckEscapeBackoffPolicy.cooldownTicks(0));
        assertEquals(0L, StuckEscapeBackoffPolicy.cooldownTicks(1));
        assertEquals(600L, StuckEscapeBackoffPolicy.cooldownTicks(2));
        assertEquals(1200L, StuckEscapeBackoffPolicy.cooldownTicks(3));
        assertEquals(2400L, StuckEscapeBackoffPolicy.cooldownTicks(4));
        assertEquals(4800L, StuckEscapeBackoffPolicy.cooldownTicks(5));
        assertEquals(9600L, StuckEscapeBackoffPolicy.cooldownTicks(6));
    }

    @Test
    void cooldownCapsAtTenMinutes() {
        assertEquals(12_000L, StuckEscapeBackoffPolicy.cooldownTicks(7));
        assertEquals(12_000L, StuckEscapeBackoffPolicy.cooldownTicks(40));
        assertEquals(12_000L, StuckEscapeBackoffPolicy.cooldownTicks(Integer.MAX_VALUE));
    }

    @Test
    void firstFailureIsFreeSecondStartsACooldown() {
        Result first = fail(StuckEscapeBackoffPolicy.NONE_STATE, 100L);
        assertEquals(Outcome.NONE, first.outcome());
        assertFalse(StuckEscapeBackoffPolicy.inCooldown(first.state(), 101L));

        Result second = fail(first.state(), 1100L);
        assertEquals(600L, second.cooldownTicks());
        assertTrue(StuckEscapeBackoffPolicy.inCooldown(second.state(), 1100L + 599L));
        assertFalse(StuckEscapeBackoffPolicy.inCooldown(second.state(), 1100L + 600L));
    }

    @Test
    void ownerIsNotifiedOncePerEpisode() {
        Result r1 = fail(StuckEscapeBackoffPolicy.NONE_STATE, 0L);
        Result r2 = fail(r1.state(), 1000L);
        Result r3 = fail(r2.state(), 5000L);
        Result r4 = fail(r3.state(), 9000L);
        assertEquals(Outcome.NONE, r1.outcome());
        assertEquals(Outcome.NOTIFY_ONCE, r2.outcome());
        assertEquals(Outcome.COOLDOWN_STARTED, r3.outcome());
        assertEquals(Outcome.COOLDOWN_STARTED, r4.outcome());
        assertEquals(2400L, r4.cooldownTicks());

        // A new episode after a reset notifies again.
        State reset = StuckEscapeBackoffPolicy.observe(r4.state(), true, Y, 0, true, true);
        Result again = fail(fail(reset, 20_000L).state(), 21_000L);
        assertEquals(Outcome.NOTIFY_ONCE, again.outcome());
    }

    @Test
    void successEndsTheEpisode() {
        State s = failTimes(3);
        Result ok = StuckEscapeBackoffPolicy.recordAttempt(s, true, true, Y, 0, true, 9000L);
        assertFalse(ok.state().active());
        assertEquals(Outcome.NONE, ok.outcome());
    }

    @Test
    void failureWithScaffoldInHandDoesNotCount() {
        Result r = StuckEscapeBackoffPolicy.recordAttempt(failTimes(1), false, true, Y, 4, true, 2000L);
        assertFalse(r.state().active());
        assertEquals(Outcome.NONE, r.outcome());
    }

    @Test
    void failureAtANewGroundYStartsANewEpisode() {
        State s = failTimes(3);
        Result r = StuckEscapeBackoffPolicy.recordAttempt(s, false, true, Y + 1, 0, true, 9000L);
        assertEquals(1, r.state().failures());
        assertEquals(Y + 1, r.state().groundY());
        assertEquals(Outcome.NONE, r.outcome());
    }

    @Test
    void airborneYAtFailureIsIgnored() {
        State s = failTimes(1);
        Result r = StuckEscapeBackoffPolicy.recordAttempt(s, false, false, Y + 1, 0, true, 2000L);
        assertEquals(2, r.state().failures());
        assertEquals(Y, r.state().groundY());
    }

    @Test
    void resetOnGroundYChange() {
        State s = failTimes(2);
        assertFalse(StuckEscapeBackoffPolicy.observe(s, true, Y + 1, 0, true, false).active());
        assertFalse(StuckEscapeBackoffPolicy.observe(s, true, Y - 3, 0, true, false).active());
        // An airborne reading (jumping in place) is not a change.
        assertSame(s, StuckEscapeBackoffPolicy.observe(s, false, Y + 1, 0, true, false));
    }

    @Test
    void resetWhenScaffoldArrives() {
        State s = failTimes(2);
        assertFalse(StuckEscapeBackoffPolicy.observe(s, true, Y, 1, true, false).active());
    }

    @Test
    void resetOnNightToDayTransition() {
        State s = failTimes(2);
        State night = StuckEscapeBackoffPolicy.observe(s, true, Y, 0, false, false);
        assertTrue(night.active());
        assertTrue(StuckEscapeBackoffPolicy.inCooldown(night, 1000L));
        assertFalse(StuckEscapeBackoffPolicy.observe(night, true, Y, 0, true, false).active());
    }

    @Test
    void dayToNightDoesNotReset() {
        State s = failTimes(2);
        assertTrue(StuckEscapeBackoffPolicy.observe(s, true, Y, 0, false, false).active());
    }

    @Test
    void resetOnOwnerCommand() {
        State s = failTimes(4);
        State r = StuckEscapeBackoffPolicy.observe(s, true, Y, 0, true, true);
        assertFalse(r.active());
        assertFalse(StuckEscapeBackoffPolicy.inCooldown(r, 3000L));
    }

    @Test
    void quietObservationKeepsTheCooldown() {
        State s = failTimes(2);
        assertSame(s, StuckEscapeBackoffPolicy.observe(s, true, Y, 0, true, false));
    }

    @Test
    void noEpisodeObservesAsNone() {
        assertFalse(StuckEscapeBackoffPolicy.observe(null, true, Y, 0, true, false).active());
        assertFalse(StuckEscapeBackoffPolicy.inCooldown(null, 0L));
        assertFalse(StuckEscapeBackoffPolicy.inCooldown(StuckEscapeBackoffPolicy.NONE_STATE, 0L));
    }
}
