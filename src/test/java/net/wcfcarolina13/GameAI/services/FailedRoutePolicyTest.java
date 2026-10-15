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
}
