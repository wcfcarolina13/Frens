package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class MovementAbortPolicyTest {
    @Test
    void steeringDispatchUsesTheCancellableServerHop() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java"));
        int start = source.indexOf("static boolean runOnServerThread(");
        int end = source.indexOf("private static void sleep(", start);
        String dispatch = source.substring(start, end);
        assertTrue(dispatch.contains("BotActions.callOnServerThread("),
                "steering must use the shared server hop that cancels abandoned queued actions");
    }

    @Test
    void lowLevelMovementDispatchAlsoUsesTheCancellableServerHop() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/BotActions.java"));
        int start = source.indexOf("private static boolean runOnServerThread(");
        int end = source.indexOf("public static <T> T callOnServerThread(", start);
        String dispatch = source.substring(start, end);
        assertTrue(dispatch.contains("return callOnServerThread("));
        assertFalse(dispatch.contains("MovementAbortPolicy.guard("),
                "the low-level helper also dispatches stop, which must run after a walk is revoked");
    }

    @Test
    void revokedWalkStillAllowsLowLevelStopCleanup() {
        AtomicBoolean moving = new AtomicBoolean(true);
        boolean result = MovementAbortPolicy.withAbort(() -> true, () ->
                ServerThreadCallPolicy.call(false, Runnable::run, () -> {
                    moving.set(false);
                    return true;
                }, 100L, false));
        assertTrue(result);
        assertFalse(moving.get());
    }

    @Test
    void timedOutGuardedSteeringCannotRunAfterItsCallerGivesUp() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicBoolean moved = new AtomicBoolean();
        boolean result = MovementAbortPolicy.withAbort(() -> false, () ->
                ServerThreadCallPolicy.call(false, queued::set, MovementAbortPolicy.guard(() -> {
                    moved.set(true);
                    return true;
                }, false), 1L, false));
        assertFalse(result);
        assertNotNull(queued.get());
        queued.get().run();
        assertFalse(moved.get());
    }

    @Test
    void revokedWalkReportsFailureWhenTheServerExecutesItsQueuedStep() {
        AtomicBoolean revoked = new AtomicBoolean();
        AtomicBoolean moved = new AtomicBoolean();
        boolean result = MovementAbortPolicy.withAbort(revoked::get, () ->
                ServerThreadCallPolicy.call(false, task -> {
                    revoked.set(true);
                    task.run();
                }, MovementAbortPolicy.guard(() -> {
                    moved.set(true);
                    return true;
                }, false), 100L, false));
        assertFalse(result);
        assertFalse(moved.get());
    }

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
