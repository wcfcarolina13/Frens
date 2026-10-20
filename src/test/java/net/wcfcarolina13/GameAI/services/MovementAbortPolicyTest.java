package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MovementAbortPolicyTest {
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
