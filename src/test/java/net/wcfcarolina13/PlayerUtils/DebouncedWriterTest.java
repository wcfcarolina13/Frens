package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic tests for {@link DebouncedWriter}: a manually driven fake scheduler plus a
 * manually advanced clock, so nothing here sleeps.
 */
class DebouncedWriterTest {

    /** A scheduled task the test fires by hand. */
    private static final class FakeTask implements ScheduledFuture<Object> {
        final Runnable body;
        final long dueAtMs;
        boolean cancelled;
        boolean ran;

        FakeTask(Runnable body, long dueAtMs) {
            this.body = body;
            this.dueAtMs = dueAtMs;
        }

        @Override public long getDelay(TimeUnit unit) { return 0L; }
        @Override public int compareTo(Delayed o) { return 0; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return ran || cancelled; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
    }

    /** Minimal fake: only {@code schedule(Runnable, long, TimeUnit)} is supported. */
    private static final class FakeScheduler implements ScheduledExecutorService {
        final List<FakeTask> tasks = new ArrayList<>();
        final AtomicLong now;

        FakeScheduler(AtomicLong now) { this.now = now; }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            FakeTask t = new FakeTask(command, now.get() + unit.toMillis(delay));
            tasks.add(t);
            return t;
        }

        /** Advances the clock and runs every non-cancelled task now due. */
        void advance(long ms) {
            now.addAndGet(ms);
            List<FakeTask> due = new ArrayList<>();
            for (FakeTask t : tasks) {
                if (!t.cancelled && !t.ran && t.dueAtMs <= now.get()) due.add(t);
            }
            for (FakeTask t : due) {
                t.ran = true;
                t.body.run();
            }
        }

        /** Delay (ms from "now") that the most recent live schedule call asked for. */
        long lastScheduledDelay() {
            FakeTask last = tasks.get(tasks.size() - 1);
            return last.dueAtMs - now.get();
        }

        @Override public <V> ScheduledFuture<V> schedule(Callable<V> c, long d, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable c, long i, long p, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable c, long i, long d, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
        @Override public <T> java.util.concurrent.Future<T> submit(Callable<T> task) { throw new UnsupportedOperationException(); }
        @Override public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) { throw new UnsupportedOperationException(); }
        @Override public java.util.concurrent.Future<?> submit(Runnable task) { throw new UnsupportedOperationException(); }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> c) { throw new UnsupportedOperationException(); }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> c, long t, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> c) throws ExecutionException { throw new UnsupportedOperationException(); }
        @Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> c, long t, TimeUnit u) throws ExecutionException { throw new UnsupportedOperationException(); }
        @Override public void execute(Runnable command) { command.run(); }
    }

    @Test
    void coalescesManyMarksIntoOneWrite() {
        AtomicLong now = new AtomicLong(1_000L);
        FakeScheduler exec = new FakeScheduler(now);
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter w = new DebouncedWriter(writes::incrementAndGet, 500L, 5_000L, exec, now::get);

        for (int i = 0; i < 20; i++) {
            w.markDirty();
            exec.advance(50L); // still inside the quiet window each time
        }
        assertEquals(0, writes.get(), "no write while marks keep arriving inside the quiet window");
        assertTrue(w.isDirty());

        exec.advance(500L);
        assertEquals(1, writes.get(), "20 marks coalesce into a single write");
        assertFalse(w.isDirty());
    }

    @Test
    void maxLatencyCapFiresUnderContinuousMarks() {
        AtomicLong now = new AtomicLong(0L);
        FakeScheduler exec = new FakeScheduler(now);
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter w = new DebouncedWriter(writes::incrementAndGet, 500L, 5_000L, exec, now::get);

        // Mark every 100 ms forever: the quiet window never elapses on its own.
        for (int i = 0; i < 60 && writes.get() == 0; i++) {
            w.markDirty();
            exec.advance(100L);
        }

        assertEquals(1, writes.get(), "max-latency cap forces a write despite continuous marks");
        assertTrue(now.get() <= 5_000L + 100L, "write happened within the 5 s cap, was at " + now.get());
    }

    @Test
    void scheduledDelayIsClampedToRemainingLatencyBudget() {
        AtomicLong now = new AtomicLong(0L);
        FakeScheduler exec = new FakeScheduler(now);
        DebouncedWriter w = new DebouncedWriter(() -> { }, 500L, 1_000L, exec, now::get);

        w.markDirty();
        assertEquals(500L, exec.lastScheduledDelay());
        exec.advance(700L); // no task due yet? the 500 ms task fires here, so re-dirty below
        w.markDirty();
        // firstMark is now 700; a fresh window, full quiet delay again
        assertEquals(500L, exec.lastScheduledDelay());
        now.addAndGet(800L); // 800 ms into a 1000 ms cap, without firing the task
        w.markDirty();
        assertEquals(200L, exec.lastScheduledDelay(), "delay clamped to the remaining cap budget");
    }

    @Test
    void flushNowWithNothingDirtyIsANoOp() {
        AtomicLong now = new AtomicLong(0L);
        FakeScheduler exec = new FakeScheduler(now);
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter w = new DebouncedWriter(writes::incrementAndGet, 500L, 5_000L, exec, now::get);

        w.flushNow();
        assertEquals(0, writes.get());

        w.markDirty();
        w.flushNow();
        assertEquals(1, writes.get());
        assertFalse(w.isDirty());

        w.flushNow();
        assertEquals(1, writes.get(), "second flush with nothing dirty writes nothing");
    }

    @Test
    void flushNowCancelsThePendingScheduledWrite() {
        AtomicLong now = new AtomicLong(0L);
        FakeScheduler exec = new FakeScheduler(now);
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter w = new DebouncedWriter(writes::incrementAndGet, 500L, 5_000L, exec, now::get);

        w.markDirty();
        w.flushNow();
        exec.advance(1_000L);
        assertEquals(1, writes.get(), "the cancelled timer must not produce a second write");
    }

    @Test
    void shutdownFlushesAndStopsAcceptingMarks() {
        AtomicLong now = new AtomicLong(0L);
        FakeScheduler exec = new FakeScheduler(now);
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter w = new DebouncedWriter(writes::incrementAndGet, 500L, 5_000L, exec, now::get);

        w.markDirty();
        w.shutdown();
        assertEquals(1, writes.get(), "shutdown flushes the pending write");

        w.markDirty();
        assertEquals(2, writes.get(), "a mark after shutdown writes through synchronously");
        exec.advance(10_000L);
        assertEquals(2, writes.get(), "no timer is scheduled after shutdown");
        assertFalse(w.isDirty());
    }

    @Test
    void marksAfterShutdownWriteThroughSynchronously() {
        AtomicLong now = new AtomicLong(0L);
        FakeScheduler exec = new FakeScheduler(now);
        AtomicInteger writes = new AtomicInteger();
        DebouncedWriter w = new DebouncedWriter(writes::incrementAndGet, 500L, 5_000L, exec, now::get);

        w.shutdown();
        assertEquals(0, writes.get(), "nothing dirty, nothing written");

        w.markDirty();
        assertEquals(1, writes.get(), "late mark writes on the caller's thread, immediately");
        w.markDirty();
        assertEquals(2, writes.get(), "every late mark writes through");
        assertFalse(w.isDirty(), "write-through leaves nothing pending");
        assertTrue(exec.tasks.isEmpty(), "no work is handed to the stopped scheduler");
    }

    @Test
    void failedWriteReschedulesExactlyOneRetry() {
        AtomicLong now = new AtomicLong(0L);
        FakeScheduler exec = new FakeScheduler(now);
        AtomicInteger attempts = new AtomicInteger();
        Runnable alwaysFails = () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("disk on fire");
        };
        DebouncedWriter w = new DebouncedWriter(alwaysFails, 500L, 5_000L, exec, now::get);

        w.markDirty();
        exec.advance(500L);
        assertEquals(1, attempts.get());
        assertTrue(w.isDirty(), "a failed write stays dirty");

        // The failure scheduled one retry, quietMs later.
        exec.advance(500L);
        assertEquals(2, attempts.get(), "exactly one automatic retry per failure");

        // That retry also failed and scheduled one more, but never a tight loop:
        // advancing far past quietMs yields one attempt per quiet window, not many.
        exec.advance(10_000L);
        assertEquals(3, attempts.get(), "retries do not loop within a single advance");
        assertTrue(w.isDirty());
    }

    @Test
    void writeExceptionLeavesWriterDirtyAndRetryable() {
        AtomicLong now = new AtomicLong(0L);
        FakeScheduler exec = new FakeScheduler(now);
        AtomicInteger attempts = new AtomicInteger();
        Runnable failsOnce = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("disk on fire");
            }
        };
        DebouncedWriter w = new DebouncedWriter(failsOnce, 500L, 5_000L, exec, now::get);

        w.markDirty();
        exec.advance(500L);
        assertEquals(1, attempts.get());
        assertTrue(w.isDirty(), "a failed write stays dirty");

        w.flushNow();
        assertEquals(2, attempts.get(), "retry happens on the next flush");
        assertFalse(w.isDirty());
    }
}
