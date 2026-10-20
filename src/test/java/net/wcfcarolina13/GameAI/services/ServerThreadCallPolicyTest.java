package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServerThreadCallPolicyTest {

    @Test
    void serverThreadRunsInlineWithoutDispatchOrWaiting() {
        Thread caller = Thread.currentThread();
        assertSame(caller, ServerThreadCallPolicy.call(true, task -> fail("dispatched on server thread"),
                Thread::currentThread, 1L, null));
    }

    @Test
    void workerWaitsForTheDispatchedMutationAndItsResult() {
        Thread caller = Thread.currentThread();
        AtomicBoolean mutated = new AtomicBoolean();
        try (var server = Executors.newSingleThreadExecutor()) {
            Thread mutationThread = ServerThreadCallPolicy.call(false, server::execute, () -> {
                mutated.set(true);
                return Thread.currentThread();
            }, 2_000L, null);
            assertNotNull(mutationThread);
            assertNotSame(caller, mutationThread);
            assertTrue(mutated.get());
        }
    }

    @Test
    void timedOutQueuedMutationCannotRunLater() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicBoolean mutated = new AtomicBoolean();
        assertFalse(ServerThreadCallPolicy.call(false, queued::set, () -> {
            mutated.set(true);
            return true;
        }, 1L, false));
        assertNotNull(queued.get());
        queued.get().run();
        assertFalse(mutated.get());
    }

    @Test
    void interruptedWaitPreservesInterruptAndCancelsQueuedMutation() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicBoolean mutated = new AtomicBoolean();
        Thread.currentThread().interrupt();
        try {
            assertFalse(ServerThreadCallPolicy.call(false, queued::set, () -> {
                mutated.set(true);
                return true;
            }, 2_000L, false));
            assertTrue(Thread.currentThread().isInterrupted());
            assertNotNull(queued.get());
            queued.get().run();
            assertFalse(mutated.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void missingServerDoesNotRunMutationOnWorker() {
        AtomicBoolean mutated = new AtomicBoolean();
        assertFalse(ServerThreadCallPolicy.call(false, null, () -> {
            mutated.set(true);
            return true;
        }, 1L, false));
        assertFalse(mutated.get());
    }

    @Test
    void failedDispatchedMutationReturnsFallback() {
        assertFalse(ServerThreadCallPolicy.call(false, Runnable::run, () -> {
            throw new IllegalStateException("failed mutation");
        }, 1L, false));
    }

    @Test
    void rejectedDispatchReturnsFallbackWithoutMutation() {
        AtomicBoolean mutated = new AtomicBoolean();
        assertFalse(ServerThreadCallPolicy.call(false, task -> {
            throw new RejectedExecutionException("server stopped");
        }, () -> {
            mutated.set(true);
            return true;
        }, 1L, false));
        assertFalse(mutated.get());
    }
}
