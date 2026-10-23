package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 51: an external teleport with no task running latched an abort that then failed every
 * follow-time leaf clear. The teleport handler records hadTask before forceAbort and, with no task,
 * releases the latch only while it still holds the teleport's reason. Mirrors that sequence over
 * (hadTask, latch reason at release time).
 */
class TaskServiceTeleportLatchTest {

    private static final String TP = "External teleport detected.";

    @AfterEach
    void tearDown() {
        TaskService.resetAll("test-cleanup");
    }

    /** The BotEventHandler teleport sequence, with an optional abort landing between the two calls. */
    private static void teleport(UUID id, String interveningAbort) {
        boolean hadTask = TaskService.hasActiveTask(id);
        TaskService.forceAbort(id, TP);
        if (interveningAbort != null) {
            TaskService.forceAbort(id, interveningAbort, false);
        }
        if (!hadTask) {
            TaskService.clearAbortLatchIf(id, TP);
        }
    }

    @Test
    void noTaskAndOwnLatchIsReleased() {
        UUID id = UUID.randomUUID();
        teleport(id, null);
        assertFalse(TaskService.isAbortRequested(id));
    }

    @Test
    void noTaskButAStopLandedInBetweenIsKept() {
        UUID id = UUID.randomUUID();
        teleport(id, "Stopped.");
        assertTrue(TaskService.isAbortRequested(id));
    }

    @Test
    void runningTaskKeepsTheLatch() {
        UUID id = UUID.randomUUID();
        assertTrue(TaskService.beginSkill("woodcut", null, id).isPresent());
        teleport(id, null);
        assertTrue(TaskService.isAbortRequested(id));
    }

    @Test
    void clearIfWithADifferentReasonLeavesTheLatch() {
        UUID id = UUID.randomUUID();
        TaskService.forceAbort(id, TP, false);
        assertFalse(TaskService.clearAbortLatchIf(id, "Stopped."));
        assertTrue(TaskService.isAbortRequested(id));
        assertTrue(TaskService.clearAbortLatchIf(id, TP));
        assertFalse(TaskService.isAbortRequested(id));
    }

    @Test
    void clearIfWithNoLatchIsANoOp() {
        assertFalse(TaskService.clearAbortLatchIf(UUID.randomUUID(), TP));
        assertFalse(TaskService.clearAbortLatchIf(null, TP));
    }

    /** A skill worker that attached itself, then the bot died: SkillResumeService.handleDeath's abort. */
    private static TaskService.TaskTicket workerThatDied(UUID id) {
        TaskService.TaskTicket ticket = TaskService.beginSkill("mining", null, id).orElseThrow();
        TaskService.attachExecutingThread(ticket, Thread.currentThread());
        TaskService.forceAbort(id, RespawnResetPolicy.DEATH_PAUSE_ABORT);
        // The abort interrupts the attached thread, here the test thread; clear it so join() works.
        Thread.interrupted();
        return ticket;
    }

    @Test
    void deathAbortSurvivesTheTeleportClearOnTheWorkerThread() {
        UUID id = UUID.randomUUID();
        workerThatDied(id);
        // The death cancelled the ticket and dropped it from ACTIVE, so the detector sees no task
        // and clears its own latch, which had overwritten the death latch.
        assertFalse(TaskService.hasActiveTask(id));
        teleport(id, null);
        assertTrue(TaskService.isAbortRequested(id));
        assertEquals(Optional.of(RespawnResetPolicy.DEATH_PAUSE_ABORT), TaskService.getCancelReason(id));
    }

    @Test
    void anotherThreadDoesNotSeeTheWorkersTicket() throws InterruptedException {
        UUID id = UUID.randomUUID();
        workerThatDied(id);
        teleport(id, null);
        AtomicBoolean abort = new AtomicBoolean(true);
        AtomicReference<Optional<String>> reason = new AtomicReference<>();
        Thread other = new Thread(() -> {
            abort.set(TaskService.isAbortRequested(id));
            reason.set(TaskService.getCancelReason(id));
        });
        other.start();
        other.join();
        assertFalse(abort.get());
        assertEquals(Optional.empty(), reason.get());
    }

    @Test
    void workerTicketIsOnlyForItsOwnBot() {
        UUID id = UUID.randomUUID();
        workerThatDied(id);
        teleport(id, null);
        UUID otherBot = UUID.randomUUID();
        assertFalse(TaskService.isAbortRequested(otherBot));
        assertEquals(Optional.empty(), TaskService.getCancelReason(otherBot));
        assertFalse(TaskService.isAbortRequested(null));
    }

    @Test
    void completedTicketIsNotSeenByALaterTaskOnTheSameThread() {
        UUID id = UUID.randomUUID();
        TaskService.TaskTicket ticket = workerThatDied(id);
        teleport(id, null);
        TaskService.complete(ticket, false);
        assertFalse(TaskService.isAbortRequested(id));
        assertEquals(Optional.empty(), TaskService.getCancelReason(id));

        // The next task on this (reused) thread starts clean.
        TaskService.TaskTicket next = TaskService.beginSkill("woodcut", null, id).orElseThrow();
        TaskService.attachExecutingThread(next, Thread.currentThread());
        assertFalse(TaskService.isAbortRequested(id));
        TaskService.complete(next, true);
        assertFalse(TaskService.isAbortRequested(id));
    }

    @Test
    void cancelledWorkerKeepsItsReasonAfterANewTicketStarts() {
        UUID id = UUID.randomUUID();
        workerThatDied(id);
        // A new task for the same bot starts while the old worker is still unwinding.
        TaskService.TaskTicket next = TaskService.beginSkill("woodcut", null, id).orElseThrow();
        assertTrue(TaskService.isAbortRequested(id));
        assertEquals(Optional.of(RespawnResetPolicy.DEATH_PAUSE_ABORT), TaskService.getCancelReason(id));
        assertFalse(next.isCancelRequested());
    }

    @Test
    void attachingAnotherThreadDoesNotSetTheCallersTicket() {
        UUID id = UUID.randomUUID();
        TaskService.TaskTicket ticket = TaskService.beginSkill("mining", null, id).orElseThrow();
        TaskService.attachExecutingThread(ticket, new Thread(() -> { }));
        TaskService.forceAbort(id, RespawnResetPolicy.DEATH_PAUSE_ABORT);
        teleport(id, null);
        assertFalse(TaskService.isAbortRequested(id));
    }
}
