package net.wcfcarolina13.GameAI.services.supply;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The one bounded, abandon-safe hop onto the server thread for supply work: every worker that asks
 * for, or takes, supplies from a chest reaches the server thread through {@link #call}.
 *
 * <p><b>Abandon-safe.</b> Whichever side claims the task first owns it. The server task claims it
 * when it starts; a caller whose wait ran out, or was interrupted, claims it to abandon it, so a
 * task that starts after the caller gave up does nothing at all — it cannot open a prompt or move
 * an item nobody will hear about. A task that already started is waited for, up to one more
 * timeout, because its effects may already be real; past that its result is lost to the caller
 * (one WARN).
 *
 * <p><b>Stopped server.</b> Once the server has stopped, {@code MinecraftServer.execute} runs a task
 * inline on the calling thread. The task checks it is on the server thread before running, so it
 * never touches the world from a worker; the caller gets its fallback.
 *
 * <p>On the server thread itself, {@link #call} runs the task inline. Either way a task that throws
 * is logged (WARN) and the caller gets its fallback.
 */
public final class SupplyServerHop {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens-supply");

    private SupplyServerHop() {
    }

    /** How one hop ended. Only {@link #DONE} carries the task's value. */
    enum Status {
        /** The task ran to completion (inline, or on the server thread in time). */
        DONE,
        /** No server, the executor refused the task, or it ran off the server thread (a stopped server). */
        NOT_RUNNING,
        /** The task had not started when the wait ran out; it is abandoned and will do nothing. */
        TIMED_OUT,
        /** The caller was interrupted before the task started; abandoned, the interrupt is kept. */
        INTERRUPTED,
        /** The task threw. */
        FAILED,
        /** The task started but was still running one more timeout later; its result is lost. */
        UNFINISHED
    }

    /** A hop's end and, for {@link Status#DONE}, the task's value (which may itself be {@code null}). */
    record Hop<T>(Status status, T value) {
        static <T> Hop<T> of(Status status) {
            return new Hop<>(status, null);
        }

        T valueOr(T fallback) {
            return status == Status.DONE ? value : fallback;
        }
    }

    /**
     * Runs {@code task} on {@code server}'s thread and returns its value, or {@code fallback} when
     * it did not run to completion: no server, a stopped server, the task not started within
     * {@code timeoutMs} (it is abandoned and never runs), the caller interrupted (the interrupt is
     * kept), the task throwing, or a started task still running {@code timeoutMs} later. Inline
     * when already on the server thread. Blocks the caller for at most about twice
     * {@code timeoutMs}.
     */
    public static <T> T call(MinecraftServer server, Supplier<T> task, long timeoutMs, T fallback) {
        return hop(server, task, timeoutMs).valueOr(fallback);
    }

    /** {@link #call} with the way it ended, for callers that report why. */
    static <T> Hop<T> hop(MinecraftServer server, Supplier<T> task, long timeoutMs) {
        if (server == null) {
            return Hop.of(Status.NOT_RUNNING);
        }
        return hop(server::execute, server::isOnThread, task, timeoutMs);
    }

    /**
     * The hop over any executor: {@code onThread} says whether the current thread is the one
     * {@code executor} runs tasks on. Package-private so tests can drive it without a server.
     */
    static <T> Hop<T> hop(Executor executor, BooleanSupplier onThread, Supplier<T> task, long timeoutMs) {
        if (executor == null || onThread == null || task == null) {
            return Hop.of(Status.NOT_RUNNING);
        }
        if (onThread.getAsBoolean()) {
            return runHere(task);
        }
        long waitMs = Math.max(0L, timeoutMs);
        CompletableFuture<Hop<T>> done = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean();
        try {
            executor.execute(() -> {
                if (!claimed.compareAndSet(false, true)) {
                    return; // abandoned by the caller before it started
                }
                if (!onThread.getAsBoolean()) {
                    // A stopped server runs execute() inline on the caller: never run the task off-thread.
                    done.complete(Hop.of(Status.NOT_RUNNING));
                    return;
                }
                try {
                    done.complete(runHere(task));
                } finally {
                    done.complete(Hop.of(Status.FAILED)); // an Error escaped: no-op once completed
                }
            });
        } catch (RuntimeException rejected) {
            if (claimed.compareAndSet(false, true)) {
                return Hop.of(Status.NOT_RUNNING);
            }
            // The executor ran the task inline and only then threw: its end is already recorded.
        }
        try {
            return done.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            boolean interrupted = e instanceof InterruptedException;
            if (claimed.compareAndSet(false, true)) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Hop.of(interrupted ? Status.INTERRUPTED : Status.TIMED_OUT);
            }
            return awaitStarted(done, interrupted, waitMs);
        } catch (ExecutionException e) {
            return Hop.of(Status.FAILED);
        }
    }

    private static <T> Hop<T> runHere(Supplier<T> task) {
        try {
            return new Hop<>(Status.DONE, task.get());
        } catch (RuntimeException e) {
            LOGGER.warn("[supply] server-thread task failed", e);
            return Hop.of(Status.FAILED);
        }
    }

    /** Waits up to {@code waitMs} more for a task that already started; keeps any interrupt for later. */
    private static <T> Hop<T> awaitStarted(CompletableFuture<Hop<T>> done, boolean interrupted, long waitMs) {
        boolean restore = interrupted;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0L) {
                    // A last look: it may have finished at the deadline.
                    Hop<T> late = done.getNow(null);
                    if (late != null) {
                        return late;
                    }
                    LOGGER.warn("[supply] server-thread task still running after {} ms more; its result is lost"
                            + " to this caller", waitMs);
                    return Hop.of(Status.UNFINISHED);
                }
                try {
                    return done.get(left, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    restore = true;
                } catch (TimeoutException e) {
                    // loop: the deadline check reports it
                } catch (ExecutionException e) {
                    return Hop.of(Status.FAILED);
                }
            }
        } finally {
            if (restore) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
