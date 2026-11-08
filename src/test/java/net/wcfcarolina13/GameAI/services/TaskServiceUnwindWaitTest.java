package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceUnwindWaitTest {

    @AfterEach
    void tearDown() {
        TaskService.setUnwindWaitMsForTest(UnwindWaitPolicy.WAIT_MS);
        TaskService.resetAll("test-cleanup");
    }

    @Test
    void policyWaitsOnlyForAnotherLiveWorkerOffTheServerThread() {
        assertTrue(UnwindWaitPolicy.shouldWait(false, true, false));
        assertFalse(UnwindWaitPolicy.shouldWait(true, true, false));
        assertFalse(UnwindWaitPolicy.shouldWait(false, false, false));
        assertFalse(UnwindWaitPolicy.shouldWait(false, true, true));
    }

    /** Old worker that ignores its interrupt and finishes on its own after {@code unwindMs}. */
    private static Thread startStubbornWorker(UUID bot, long unwindMs, CountDownLatch attached, AtomicBoolean oldDone) {
        Thread t = new Thread(() -> {
            TaskService.TaskTicket ticket = TaskService.beginSkill("old", null, bot).orElseThrow();
            TaskService.attachExecutingThread(ticket, Thread.currentThread());
            attached.countDown();
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(unwindMs);
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
            oldDone.set(true);
            TaskService.complete(ticket, false);
        }, "old-worker");
        t.start();
        return t;
    }

    @Test
    void newWorkerStartsOnlyAfterTheStoppedWorkerExits() throws Exception {
        UUID bot = UUID.randomUUID();
        CountDownLatch attached = new CountDownLatch(1);
        AtomicBoolean oldDone = new AtomicBoolean(false);
        Thread old = startStubbornWorker(bot, 300, attached, oldDone);
        assertTrue(attached.await(2, TimeUnit.SECONDS));

        TaskService.forceAbort(bot, "stop", false);
        AtomicBoolean oldDoneWhenNewWentOn = new AtomicBoolean(false);
        Thread fresh = new Thread(() -> {
            TaskService.TaskTicket ticket = TaskService.beginSkill("new", null, bot).orElseThrow();
            TaskService.attachExecutingThread(ticket, Thread.currentThread());
            oldDoneWhenNewWentOn.set(oldDone.get());
            TaskService.complete(ticket, true);
        }, "new-worker");
        fresh.start();
        fresh.join(5000);
        old.join(5000);
        assertTrue(oldDoneWhenNewWentOn.get(), "the new worker went on before the old one exited");
    }

    @Test
    void newWorkerGivesUpWaitingAtTheBudget() throws Exception {
        TaskService.setUnwindWaitMsForTest(150);
        UUID bot = UUID.randomUUID();
        CountDownLatch attached = new CountDownLatch(1);
        AtomicBoolean oldDone = new AtomicBoolean(false);
        Thread old = startStubbornWorker(bot, 1500, attached, oldDone);
        assertTrue(attached.await(2, TimeUnit.SECONDS));

        TaskService.forceAbort(bot, "stop", false);
        AtomicLong waitedMs = new AtomicLong(-1);
        AtomicBoolean oldDoneWhenNewWentOn = new AtomicBoolean(true);
        Thread fresh = new Thread(() -> {
            TaskService.TaskTicket ticket = TaskService.beginSkill("new", null, bot).orElseThrow();
            long t0 = System.nanoTime();
            TaskService.attachExecutingThread(ticket, Thread.currentThread());
            waitedMs.set((System.nanoTime() - t0) / 1_000_000L);
            oldDoneWhenNewWentOn.set(oldDone.get());
            TaskService.complete(ticket, true);
        }, "new-worker");
        fresh.start();
        fresh.join(5000);
        old.join(5000);
        assertFalse(oldDoneWhenNewWentOn.get());
        assertTrue(waitedMs.get() >= 100 && waitedMs.get() < 1200, "waited " + waitedMs.get() + " ms");
    }

    /**
     * Skill workers are pooled: the old worker's thread stays alive after its task completes. The
     * new task must go on once the old ticket completes, not sit out the whole budget (sandbox
     * failure on f457350e: "still unwinding after 2000 ms" every time).
     */
    @Test
    void pooledWorkerThatStaysAliveDoesNotCostTheWholeBudget() throws Exception {
        UUID bot = UUID.randomUUID();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            CountDownLatch attached = new CountDownLatch(1);
            AtomicBoolean oldDone = new AtomicBoolean(false);
            pool.submit(() -> {
                TaskService.TaskTicket ticket = TaskService.beginSkill("old", null, bot).orElseThrow();
                TaskService.attachExecutingThread(ticket, Thread.currentThread());
                attached.countDown();
                long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
                while (System.nanoTime() < end) {
                    Thread.onSpinWait();
                }
                oldDone.set(true);
                TaskService.complete(ticket, false);
            });
            assertTrue(attached.await(2, TimeUnit.SECONDS));
            TaskService.forceAbort(bot, "stop", false);

            AtomicLong waitedMs = new AtomicLong(-1);
            AtomicBoolean oldDoneWhenNewWentOn = new AtomicBoolean(false);
            Thread fresh = new Thread(() -> {
                TaskService.TaskTicket ticket = TaskService.beginSkill("new", null, bot).orElseThrow();
                long t0 = System.nanoTime();
                TaskService.attachExecutingThread(ticket, Thread.currentThread());
                waitedMs.set((System.nanoTime() - t0) / 1_000_000L);
                oldDoneWhenNewWentOn.set(oldDone.get());
                TaskService.complete(ticket, true);
            }, "new-worker");
            fresh.start();
            fresh.join(5000);
            assertTrue(oldDoneWhenNewWentOn.get(), "the new worker went on before the old ticket completed");
            assertTrue(waitedMs.get() < 1500, "waited " + waitedMs.get() + " ms for a finished pooled worker");
        } finally {
            pool.shutdownNow();
        }
    }

    /** A worker that aborts its own ticket and keeps unwinding still holds off a new command. */
    @Test
    void selfAbortedWorkerStillHoldsOffTheNextTask() throws Exception {
        UUID bot = UUID.randomUUID();
        CountDownLatch aborted = new CountDownLatch(1);
        AtomicBoolean oldDone = new AtomicBoolean(false);
        Thread old = new Thread(() -> {
            TaskService.TaskTicket ticket = TaskService.beginSkill("old", null, bot).orElseThrow();
            TaskService.attachExecutingThread(ticket, Thread.currentThread());
            TaskService.forceAbort(bot, "self", false);
            aborted.countDown();
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
            oldDone.set(true);
            TaskService.complete(ticket, false);
        }, "old-worker");
        old.start();
        assertTrue(aborted.await(2, TimeUnit.SECONDS));
        AtomicBoolean oldDoneWhenNewWentOn = new AtomicBoolean(false);
        Thread fresh = new Thread(() -> {
            TaskService.TaskTicket ticket = TaskService.beginSkill("new", null, bot).orElseThrow();
            TaskService.attachExecutingThread(ticket, Thread.currentThread());
            oldDoneWhenNewWentOn.set(oldDone.get());
            TaskService.complete(ticket, true);
        }, "new-worker");
        fresh.start();
        fresh.join(5000);
        old.join(5000);
        assertTrue(oldDoneWhenNewWentOn.get(), "the new worker went on while the self-aborted one still ran");
    }

    @Test
    void noWaitWhenNothingWasStopped() throws Exception {
        UUID bot = UUID.randomUUID();
        AtomicLong waitedMs = new AtomicLong(-1);
        Thread fresh = new Thread(() -> {
            TaskService.TaskTicket ticket = TaskService.beginSkill("new", null, bot).orElseThrow();
            long t0 = System.nanoTime();
            TaskService.attachExecutingThread(ticket, Thread.currentThread());
            waitedMs.set((System.nanoTime() - t0) / 1_000_000L);
            TaskService.complete(ticket, true);
        });
        fresh.start();
        fresh.join(5000);
        assertTrue(waitedMs.get() >= 0 && waitedMs.get() < 50);
    }
}
