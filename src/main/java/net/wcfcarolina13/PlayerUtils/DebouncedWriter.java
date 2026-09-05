package net.wcfcarolina13.PlayerUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Coalesces many "something changed" signals into few actual writes.
 *
 * <p>Pure utility — no Minecraft types. A caller marks the state dirty as often as it likes;
 * the wrapped {@code write} runnable fires once {@code quietMs} have passed with no further
 * marks, but never later than {@code maxLatencyMs} after the first unflushed mark (so a
 * continuously-mutating state still reaches disk at a bounded cadence).
 *
 * <p>Threading: {@code markDirty()} is cheap and safe from any thread (including the server
 * thread). {@code write} runs on the scheduler thread, or on the caller's thread for
 * {@link #flushNow()} / {@link #shutdown()}. Writes never overlap — they are serialized on an
 * internal write lock. An exception thrown by {@code write} is logged and leaves the writer
 * dirty, so the next mark or flush retries it.
 */
public final class DebouncedWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger("debounced-writer");

    private final Runnable write;
    private final long quietMs;
    private final long maxLatencyMs;
    private final ScheduledExecutorService exec;
    private final LongSupplier clock;

    /** Guards the dirty flag / pending task bookkeeping. Never held while {@code write} runs. */
    private final Object stateLock = new Object();
    /** Serializes actual writes. */
    private final Object writeLock = new Object();

    private boolean dirty;
    private long firstMarkMs;
    private ScheduledFuture<?> pending;
    private boolean stopped;
    /** True while a post-failure retry is scheduled, so one failure schedules at most one retry. */
    private boolean retrying;

    public DebouncedWriter(Runnable write, long quietMs, long maxLatencyMs, ScheduledExecutorService exec) {
        this(write, quietMs, maxLatencyMs, exec, System::currentTimeMillis);
    }

    /** Test seam: injectable clock so max-latency behaviour can be driven deterministically. */
    DebouncedWriter(Runnable write, long quietMs, long maxLatencyMs, ScheduledExecutorService exec,
                    LongSupplier clock) {
        if (write == null) throw new IllegalArgumentException("write must not be null");
        if (exec == null) throw new IllegalArgumentException("exec must not be null");
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        this.write = write;
        this.quietMs = Math.max(0L, quietMs);
        this.maxLatencyMs = Math.max(this.quietMs, maxLatencyMs);
        this.exec = exec;
        this.clock = clock;
    }

    /**
     * Records that the underlying state changed. Schedules a write after {@code quietMs} of
     * quiet, capped so the write happens no later than {@code maxLatencyMs} after the first
     * mark of the current dirty window.
     *
     * <p>After {@link #shutdown()} the scheduler is gone, so a late mark would otherwise be
     * dropped. Instead it degrades to the old pre-debounce behaviour: a synchronous
     * write-through on the caller's thread, under the write lock, so it can never overlap
     * another write.
     */
    public void markDirty() {
        boolean writeThrough;
        synchronized (stateLock) {
            writeThrough = stopped;
            if (!writeThrough) {
                long now = clock.getAsLong();
                if (!dirty) {
                    dirty = true;
                    firstMarkMs = now;
                }
                long remainingToCap = Math.max(0L, (firstMarkMs + maxLatencyMs) - now);
                long delay = Math.min(quietMs, remainingToCap);
                if (pending != null) {
                    pending.cancel(false);
                    pending = null;
                }
                retrying = false;
                try {
                    pending = exec.schedule(this::onTimer, delay, TimeUnit.MILLISECONDS);
                } catch (Throwable t) {
                    // Executor rejected (shutting down). Stay dirty — the shutdown path's
                    // flushNow() still writes, and any later mark reschedules.
                    pending = null;
                    LOGGER.warn("Debounced write could not be scheduled: {}", t.toString());
                }
            }
        }
        if (writeThrough) {
            // Deliberately outside stateLock: runWrite() takes writeLock and its failure path
            // re-acquires stateLock, so holding stateLock here would invert the lock order.
            runWrite();
        }
    }

    private void onRetryTimer() {
        synchronized (stateLock) {
            retrying = false;
        }
        onTimer();
    }

    private void onTimer() {
        synchronized (stateLock) {
            pending = null;
            if (!dirty) {
                return;
            }
            dirty = false;
        }
        runWrite();
    }

    /**
     * Runs the write synchronously on the calling thread if anything is dirty, cancelling any
     * pending scheduled write. No-op when nothing is dirty.
     */
    public void flushNow() {
        synchronized (stateLock) {
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
            if (!dirty) {
                return;
            }
            dirty = false;
        }
        runWrite();
    }

    public boolean isDirty() {
        synchronized (stateLock) {
            return dirty;
        }
    }

    /** Flushes any pending write, then stops accepting further marks. Idempotent. */
    public void shutdown() {
        flushNow();
        synchronized (stateLock) {
            stopped = true;
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
        }
    }

    private void runWrite() {
        synchronized (writeLock) {
            try {
                write.run();
            } catch (Throwable t) {
                LOGGER.warn("Debounced write failed; staying dirty for retry: {}", t.toString());
                synchronized (stateLock) {
                    if (!dirty) {
                        dirty = true;
                        firstMarkMs = clock.getAsLong();
                    }
                    // Bounded: exactly one rescheduled retry per failure, never a retry loop —
                    // the retry itself runs via onTimer(), whose own failure schedules at most
                    // one more, and only while the writer is still running.
                    if (!stopped && !retrying && pending == null) {
                        retrying = true;
                        try {
                            pending = exec.schedule(this::onRetryTimer, quietMs, TimeUnit.MILLISECONDS);
                        } catch (Throwable rejected) {
                            retrying = false;
                            pending = null;
                            LOGGER.warn("Debounced write retry could not be scheduled: {}", rejected.toString());
                        }
                    }
                }
            }
        }
    }
}
