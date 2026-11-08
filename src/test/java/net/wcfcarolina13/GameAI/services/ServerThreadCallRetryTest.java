package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServerThreadCallRetryTest {

    /** Drops the first {@code drops} dispatched tasks (a stalled server), then runs them inline. */
    private static Consumer<Runnable> stalling(int drops, AtomicInteger dispatched) {
        return task -> {
            if (dispatched.incrementAndGet() > drops) {
                task.run();
            }
        };
    }

    @Test
    void oneStallIsRetried() {
        AtomicInteger dispatched = new AtomicInteger();
        int slot = ServerThreadCallPolicy.callRetrying(false, stalling(1, dispatched), () -> 4, 5L, 2, -1);
        assertEquals(4, slot);
        assertEquals(2, dispatched.get());
    }

    @Test
    void twoStallsGiveTheFallback() {
        AtomicInteger dispatched = new AtomicInteger();
        int slot = ServerThreadCallPolicy.callRetrying(false, stalling(2, dispatched), () -> 4, 5L, 2, -1);
        assertEquals(-1, slot);
        assertEquals(2, dispatched.get());
    }

    @Test
    void aRealAnswerIsNeverRetriedEvenWhenItEqualsTheFallback() {
        AtomicInteger dispatched = new AtomicInteger();
        int slot = ServerThreadCallPolicy.callRetrying(false, stalling(0, dispatched), () -> -1, 5L, 2, -1);
        assertEquals(-1, slot);
        assertEquals(1, dispatched.get(), "an answered -1 is a real 'no', not a timeout");
        AtomicInteger again = new AtomicInteger();
        assertNull(ServerThreadCallPolicy.callRetrying(false, stalling(0, again), () -> null, 5L, 2, "fallback"));
        assertEquals(1, again.get());
    }

    @Test
    void onTheServerThreadItRunsInlineOnce() {
        AtomicInteger dispatched = new AtomicInteger();
        assertEquals(7, (int) ServerThreadCallPolicy.callRetrying(true, stalling(5, dispatched), () -> 7, 5L, 2, -1));
        assertEquals(0, dispatched.get());
    }
}
