package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static net.wcfcarolina13.GameAI.services.FailedRoutePolicy.TTL_MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedRoutePolicyTest {

    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    // The 1.1.239 sandbox case: origin 22,63,126, first hop up onto the sand.
    private static final FailedRoutePolicy.Key GULLY = FailedRoutePolicy.key(22, 63, 126, 21, 64, 126);

    @Test
    void keysAreValueEqual() {
        assertEquals(GULLY, FailedRoutePolicy.key(22, 63, 126, 21, 64, 126));
        assertFalse(GULLY.equals(FailedRoutePolicy.key(22, 64, 126, 21, 64, 126)), "a different origin is a different route");
        assertFalse(GULLY.equals(FailedRoutePolicy.key(22, 63, 126, 21, 65, 126)), "a different first hop is a different route");
    }

    @Test
    void rememberedForTheTtlThenForgotten() {
        FailedRoutePolicy memory = new FailedRoutePolicy();
        assertFalse(memory.isFailed(BOB, GULLY, 1_000L));
        memory.record(BOB, GULLY, 1_000L);
        assertTrue(memory.isFailed(BOB, GULLY, 1_000L));
        assertTrue(memory.isFailed(BOB, GULLY, 1_000L + TTL_MS - 1));
        assertFalse(memory.isFailed(BOB, GULLY, 1_000L + TTL_MS));
    }

    @Test
    void liveBoundary() {
        assertTrue(FailedRoutePolicy.live(0L, TTL_MS - 1));
        assertFalse(FailedRoutePolicy.live(0L, TTL_MS));
    }

    @Test
    void reRecordingRefreshesTheTtl() {
        FailedRoutePolicy memory = new FailedRoutePolicy();
        memory.record(BOB, GULLY, 0L);
        memory.record(BOB, GULLY, 20_000L);
        assertTrue(memory.isFailed(BOB, GULLY, 40_000L));
        assertEquals(10_000L, memory.remainingMs(BOB, GULLY, 40_000L));
    }

    @Test
    void perBot() {
        FailedRoutePolicy memory = new FailedRoutePolicy();
        memory.record(BOB, GULLY, 0L);
        assertFalse(memory.isFailed(ALICE, GULLY, 0L));
        memory.clear(BOB);
        assertFalse(memory.isFailed(BOB, GULLY, 0L));
    }

    @Test
    void skipLoggedOncePerInterval() {
        FailedRoutePolicy memory = new FailedRoutePolicy();
        assertFalse(memory.claimSkipLog(BOB, GULLY, 0L), "nothing remembered, nothing to log");
        memory.record(BOB, GULLY, 0L);
        assertTrue(memory.claimSkipLog(BOB, GULLY, 100L));
        assertFalse(memory.claimSkipLog(BOB, GULLY, 200L));
        assertTrue(memory.claimSkipLog(BOB, GULLY, 100L + FailedRoutePolicy.SKIP_LOG_INTERVAL_MS));
        memory.record(BOB, GULLY, 6_000L);
        assertFalse(memory.claimSkipLog(BOB, GULLY, 6_100L), "re-recording keeps the log throttle");
    }

    @Test
    void nullsAreIgnored() {
        FailedRoutePolicy memory = new FailedRoutePolicy();
        memory.record(null, GULLY, 0L);
        memory.record(BOB, null, 0L);
        assertFalse(memory.isFailed(null, GULLY, 0L));
        assertFalse(memory.isFailed(BOB, null, 0L));
        assertEquals(0L, memory.remainingMs(BOB, GULLY, 0L));
    }

    @Test
    void oneSidestepPerGoalIsRememberedForTheTtl() {
        FailedRoutePolicy memory = new FailedRoutePolicy();
        FailedRoutePolicy.Goal goal = new FailedRoutePolicy.Goal(10, 64, 0);
        FailedRoutePolicy.Goal other = new FailedRoutePolicy.Goal(-10, 64, 0);
        assertEquals(0, memory.sidestepsWithinTtl(BOB, goal, 1_000L));
        memory.recordSidestep(BOB, goal, 1_000L);
        assertEquals(1, memory.sidestepsWithinTtl(BOB, goal, 1_000L + TTL_MS - 1));
        assertEquals(0, memory.sidestepsWithinTtl(BOB, other, 1_000L), "per goal");
        assertEquals(0, memory.sidestepsWithinTtl(ALICE, goal, 1_000L), "per bot");
        assertEquals(0, memory.sidestepsWithinTtl(BOB, goal, 1_000L + TTL_MS), "forgotten after the TTL");
        memory.recordSidestep(BOB, goal, 5_000L);
        memory.clear(BOB);
        assertEquals(0, memory.sidestepsWithinTtl(BOB, goal, 5_000L), "cleared with the bot's routes");
        assertEquals(0, memory.sidestepsWithinTtl(null, goal, 0L));
    }

    @Test
    void aSidestepsBackStepIsARememberedRoute() {
        // Goal (10,0), long wall at x=1: A(0,0) -> B(0,1); the reverse route B -> A is recorded.
        FailedRoutePolicy memory = new FailedRoutePolicy();
        FailedRoutePolicy.Key back = FailedRoutePolicy.key(0, 64, 1, 0, 64, 0);
        memory.record(BOB, back, 2_000L);
        assertTrue(memory.isFailed(BOB, back, 2_000L));
        assertFalse(memory.isFailed(BOB, FailedRoutePolicy.key(0, 64, 0, 0, 64, 1), 2_000L), "only the reverse");
    }
}
