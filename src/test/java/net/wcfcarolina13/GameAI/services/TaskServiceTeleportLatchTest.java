package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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
}
