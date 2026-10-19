package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogThrottleTest {

    @Test
    void changedFirstSameDifferent() {
        Map<String, String> m = new ConcurrentHashMap<>();
        assertTrue(LogThrottle.changed(m, "bot", "minecraft:horse"));
        assertFalse(LogThrottle.changed(m, "bot", "minecraft:horse"));
        assertFalse(LogThrottle.changed(m, "bot", "minecraft:horse"));
        assertTrue(LogThrottle.changed(m, "bot", "minecraft:boat"));
        assertEquals("minecraft:boat", m.get("bot"));
        assertTrue(LogThrottle.changed(m, "other", "minecraft:boat"), "keys are independent");
    }

    @Test
    void changedAfterRemoveLogsAgain() {
        Map<String, String> m = new ConcurrentHashMap<>();
        assertTrue(LogThrottle.changed(m, "bot", "a"));
        m.remove("bot");
        assertTrue(LogThrottle.changed(m, "bot", "a"));
    }

    @Test
    void changedAcceptsNullStateInConcurrentMap() {
        Map<String, String> m = new ConcurrentHashMap<>();
        assertTrue(LogThrottle.changed(m, "bot", null));
        assertFalse(LogThrottle.changed(m, "bot", null));
        assertTrue(LogThrottle.changed(m, "bot", "x"));
    }

    @Test
    void dueMissingKeyRecords() {
        Map<String, Long> m = new ConcurrentHashMap<>();
        assertTrue(LogThrottle.due(m, "k", 100L, 200L));
        assertEquals(100L, m.get("k"));
    }

    @Test
    void dueInsideIntervalIsFalseAndKeepsStamp() {
        Map<String, Long> m = new ConcurrentHashMap<>();
        assertTrue(LogThrottle.due(m, "k", 100L, 200L));
        assertFalse(LogThrottle.due(m, "k", 101L, 200L));
        assertFalse(LogThrottle.due(m, "k", 299L, 200L));
        assertEquals(100L, m.get("k"), "a suppressed call does not push the window");
        assertTrue(LogThrottle.due(m, "k", 300L, 200L));
        assertEquals(300L, m.get("k"));
    }

    @Test
    void dueNegativeGapCountsAsDue() {
        Map<String, Long> m = new ConcurrentHashMap<>();
        assertTrue(LogThrottle.due(m, "k", 5_000L, 200L));
        assertTrue(LogThrottle.due(m, "k", 10L, 200L));
        assertEquals(10L, m.get("k"));
    }

    @Test
    void dueKeysAreIndependent() {
        Map<String, Long> m = new ConcurrentHashMap<>();
        assertTrue(LogThrottle.due(m, "bot|Stone|1", 0L, 200L));
        assertTrue(LogThrottle.due(m, "bot|Dirt|2", 1L, 200L), "a new block still speaks");
        assertFalse(LogThrottle.due(m, "bot|Stone|1", 2L, 200L));
    }
    @Test
    void dueRacingCallersGetExactlyOneSlot() throws Exception {
        Map<String, Long> m = new ConcurrentHashMap<>();
        int threads = 16;
        for (int round = 0; round < 200; round++) {
            m.clear();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger dueCount = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        if (LogThrottle.due(m, "k", 1_000L, 200L)) {
                            dueCount.incrementAndGet();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (java.util.concurrent.Future<?> f : futures) {
                    f.get();
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, dueCount.get(), "round " + round);
        }
    }
}
