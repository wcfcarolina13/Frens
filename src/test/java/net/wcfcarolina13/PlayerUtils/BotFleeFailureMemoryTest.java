package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the staleness window used by
 * {@code BotFleeService#getRecentSurfaceRecoveryFailureReason}: a remembered surface-recovery
 * failure is only reported while it is recent (1200 ticks / 60s), so the {@code [guard-escape]}
 * log can't name a failure from minutes earlier.
 */
class BotFleeFailureMemoryTest {

    private static final long WINDOW = 1200L;

    @Test
    void freshWithinWindow() {
        assertTrue(TickFreshness.isFresh(1000L, 1500L, WINDOW));
        assertTrue(TickFreshness.isFresh(1000L, 2200L, WINDOW), "exactly at the window edge is fresh");
    }

    @Test
    void staleBeyondWindow() {
        assertFalse(TickFreshness.isFresh(1000L, 2201L, WINDOW));
        assertFalse(TickFreshness.isFresh(1000L, 99999L, WINDOW));
    }

    @Test
    void unsetOrFutureTickIsNotFresh() {
        assertFalse(TickFreshness.isFresh(0L, 500L, WINDOW), "never recorded");
        assertFalse(TickFreshness.isFresh(-5L, 500L, WINDOW));
        assertFalse(TickFreshness.isFresh(2000L, 1000L, WINDOW), "recorded after now (world reload)");
    }
}
