package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class MovementAbortPolicyTest {
    @Test
    void queuedMutationRetainsTicketCancellationAndReturnsItsFallback() throws InterruptedException {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean mutated = new AtomicBoolean();
        AtomicReference<Boolean> result = new AtomicReference<>();
        Supplier<Boolean> mutation = MovementAbortPolicy.withAbort(cancelled::get,
                () -> MovementAbortPolicy.guard(() -> {
                    mutated.set(true);
                    return true;
                }, false));
        cancelled.set(true);
        Thread server = new Thread(() -> result.set(mutation.get()));
        server.start();
        server.join();
        assertEquals(Boolean.FALSE, result.get());
        assertFalse(mutated.get());
    }

    @Test
    void activeQueuedMutationReturnsItsResultAfterTheWorkerScopeEnds() {
        Supplier<String> mutation = MovementAbortPolicy.withAbort(() -> false,
                () -> MovementAbortPolicy.guard(() -> "moved", "cancelled"));
        assertEquals("moved", mutation.get());
    }

    @Test
    void unscopedMutationRunsNormally() {
        assertEquals("moved", MovementAbortPolicy.guard(() -> "moved", "cancelled").get());
    }

    @Test
    void replacedTicketStillAbortsAfterBotLatchClears() {
        AtomicBoolean ticketReplaced = new AtomicBoolean();
        MovementAbortPolicy.withAbort(ticketReplaced::get, () -> {
            assertFalse(MovementAbortPolicy.abortRequested(false));
            ticketReplaced.set(true);
            assertTrue(MovementAbortPolicy.abortRequested(false));
            return null;
        });
        assertFalse(MovementAbortPolicy.abortRequested(false));
        assertTrue(MovementAbortPolicy.abortRequested(true));
    }

    @Test
    void nestedScopeRetainsOuterCancellationAndRestoresItOnFailure() {
        AtomicBoolean outer = new AtomicBoolean();
        MovementAbortPolicy.withAbort(outer::get, () -> {
            assertThrows(IllegalStateException.class, () ->
                    MovementAbortPolicy.withAbort(() -> false, () -> {
                        outer.set(true);
                        assertTrue(MovementAbortPolicy.abortRequested(false));
                        throw new IllegalStateException();
                    }));
            assertTrue(MovementAbortPolicy.abortRequested(false));
            return null;
        });
        assertFalse(MovementAbortPolicy.abortRequested(false));
    }

    @Test
    void queuedStepRetainsTicketCheckAfterWorkerScopeEnds() throws InterruptedException {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean steered = new AtomicBoolean();
        Runnable step = MovementAbortPolicy.withAbort(cancelled::get,
                () -> MovementAbortPolicy.guard(() -> steered.set(true)));
        cancelled.set(true);
        Thread server = new Thread(step);
        server.start();
        server.join();
        assertFalse(steered.get());
    }

    @Test
    void activeQueuedStepRuns() {
        AtomicBoolean steered = new AtomicBoolean();
        Runnable step = MovementAbortPolicy.withAbort(null,
                () -> MovementAbortPolicy.guard(() -> steered.set(true)));
        step.run();
        assertTrue(steered.get());
    }
}
