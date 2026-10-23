package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

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
    void clearDuringRemainingTimeReadDoesNotThrowOrResurrectTheBot() throws Exception {
        FailedRoutePolicy memory = new FailedRoutePolicy();
        memory.record(BOB, GULLY, 1_000L);
        interleaveNextAccess(memory, "failed", () -> memory.clear(BOB));
        assertEquals(TTL_MS, memory.remainingMs(BOB, GULLY, 1_000L));
        assertEquals(0L, memory.remainingMs(BOB, GULLY, 1_000L));
    }

    @Test
    void expiredReadCannotDeleteARefreshedRoute() throws Exception {
        FailedRoutePolicy memory = new FailedRoutePolicy();
        memory.record(BOB, GULLY, 0L);
        interleaveNextAccess(memory, "failed", () -> memory.record(BOB, GULLY, TTL_MS));
        assertFalse(memory.isFailed(BOB, GULLY, TTL_MS));
        assertTrue(memory.isFailed(BOB, GULLY, TTL_MS + 1));
    }

    @Test
    void expiredReadCannotDeleteARefreshedSidestep() throws Exception {
        FailedRoutePolicy memory = new FailedRoutePolicy();
        var goal = new FailedRoutePolicy.Goal(10, 64, 0);
        memory.recordSidestep(BOB, goal, 0L);
        interleaveNextAccess(memory, "sidesteps", () -> memory.recordSidestep(BOB, goal, TTL_MS));
        assertEquals(0, memory.sidestepsWithinTtl(BOB, goal, TTL_MS));
        assertEquals(1, memory.sidestepsWithinTtl(BOB, goal, TTL_MS + 1));
    }

    @Test
    void loggingAnOldReadCannotUndoAConcurrentRouteRefresh() throws Exception {
        FailedRoutePolicy memory = new FailedRoutePolicy();
        memory.record(BOB, GULLY, 0L);
        interleaveNextAccess(memory, "failed", () -> memory.record(BOB, GULLY, 10_000L));
        assertTrue(memory.claimSkipLog(BOB, GULLY, 10_000L));
        assertEquals(5_000L, memory.remainingMs(BOB, GULLY, 35_000L));
    }

    @Test
    void refreshingAnOldReadCannotUndoAConcurrentLogClaim() throws Exception {
        FailedRoutePolicy memory = new FailedRoutePolicy();
        memory.record(BOB, GULLY, 0L);
        interleaveNextAccess(memory, "failed", () -> memory.claimSkipLog(BOB, GULLY, 1_000L));
        memory.record(BOB, GULLY, 2_000L);
        assertFalse(memory.claimSkipLog(BOB, GULLY, 2_100L));
    }

    // Complete one competing operation at a map-access boundary without timing or sleeps.
    @SuppressWarnings("unchecked")
    private static void interleaveNextAccess(FailedRoutePolicy memory, String fieldName,
                                             Runnable competingOperation) throws Exception {
        var field = FailedRoutePolicy.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        var bots = (Map<UUID, Map<Object, Object>>) field.get(memory);
        var replacement = new InterleavingMap(bots.get(BOB), competingOperation);
        bots.put(BOB, replacement);
    }

    private static final class InterleavingMap extends ConcurrentHashMap<Object, Object> {
        private final AtomicReference<Runnable> pending;

        InterleavingMap(Map<Object, Object> initial, Runnable competingOperation) {
            super(initial);
            pending = new AtomicReference<>(competingOperation);
        }

        private void interleave() {
            Runnable action = pending.getAndSet(null);
            if (action != null) action.run();
        }

        @Override public Object get(Object key) {
            Object observed = super.get(key);
            interleave();
            return observed;
        }

        @Override public Object compute(Object key,
                BiFunction<? super Object, ? super Object, ? extends Object> update) {
            interleave();
            return super.compute(key, update);
        }

        @Override public Object computeIfPresent(Object key,
                BiFunction<? super Object, ? super Object, ? extends Object> update) {
            interleave();
            return super.computeIfPresent(key, update);
        }
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
