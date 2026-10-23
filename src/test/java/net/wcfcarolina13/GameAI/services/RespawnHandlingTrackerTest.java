package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RespawnHandlingTrackerTest {
    @Test
    void oneBotsRespawnCannotSuppressAnotherBotsFallback() {
        RespawnHandlingTracker tracker = new RespawnHandlingTracker();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Object secondLife = tracker.captureLifecycle(second);
        tracker.recordHandled(first, 100);

        assertTrue(tracker.handledWithin(first, 105, 5));
        assertFalse(tracker.handledWithin(second, 105, 5));
        assertEquals(-1, tracker.lastHandledTick(second));
        assertTrue(tracker.isCurrent(second, secondLife));
    }

    @Test
    void handlingRespawnKeepsItsPendingFallbackIdentityAndWindowBoundary() {
        RespawnHandlingTracker tracker = new RespawnHandlingTracker();
        UUID bot = UUID.randomUUID();
        Object life = tracker.captureLifecycle(bot);
        tracker.recordHandled(bot, 0);

        assertTrue(tracker.isCurrent(bot, life));
        assertSame(life, tracker.captureLifecycle(bot));
        assertEquals(0, tracker.lastHandledTick(bot));
        assertTrue(tracker.handledWithin(bot, 5, 5));
        assertFalse(tracker.handledWithin(bot, 6, 5));
        tracker.recordHandled(bot, 10);
        assertTrue(tracker.handledWithin(bot, 5, 5),
                "a later handling still suppresses an older check, as before");
    }

    @Test
    void unregisterAndReregisterRejectTheOldQueuedFallback() {
        RespawnHandlingTracker tracker = new RespawnHandlingTracker();
        UUID bot = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Object oldLife = tracker.captureLifecycle(bot);
        Object otherLife = tracker.captureLifecycle(other);
        tracker.recordHandled(bot, 50);
        tracker.forget(bot);

        assertFalse(tracker.isCurrent(bot, oldLife));
        assertEquals(-1, tracker.lastHandledTick(bot));
        Object newLife = tracker.captureLifecycle(bot);
        assertNotSame(oldLife, newLife);
        assertFalse(tracker.isCurrent(bot, oldLife));
        assertTrue(tracker.isCurrent(bot, newLife));
        assertTrue(tracker.isCurrent(other, otherLife));
        assertFalse(tracker.isCurrent(other, newLife));
    }

    @Test
    void sessionResetInvalidatesEveryPendingFallbackAndHandledTick() {
        RespawnHandlingTracker tracker = new RespawnHandlingTracker();
        UUID bot = UUID.randomUUID();
        Object oldLife = tracker.captureLifecycle(bot);
        tracker.recordHandled(bot, 80);
        tracker.reset();

        assertFalse(tracker.isCurrent(bot, oldLife));
        assertEquals(-1, tracker.lastHandledTick(bot));
        assertFalse(tracker.handledWithin(bot, 85, 5));
        assertNotSame(oldLife, tracker.captureLifecycle(bot));
    }

    @Test
    void nullIdentityNeverCreatesAValidLifecycle() {
        RespawnHandlingTracker tracker = new RespawnHandlingTracker();
        assertNull(tracker.captureLifecycle(null));
        tracker.recordHandled(null, 10);
        tracker.forget(null);
        assertFalse(tracker.isCurrent(null, null));
        assertEquals(-1, tracker.lastHandledTick(null));
    }
}
