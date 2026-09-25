package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.SupplyServerHop.Hop;
import net.wcfcarolina13.GameAI.services.supply.SupplyServerHop.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared hop over a plain executor standing in for the server: the server thread is one
 * dedicated thread, and {@code onThread} says whether the caller is on it. No Minecraft types.
 */
class SupplyServerHopTest {

    private final AtomicReference<Thread> serverThread = new AtomicReference<>();
    private final BooleanSupplier onServerThread = () -> Thread.currentThread() == serverThread.get();
    private final ExecutorService server = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "test-server-thread");
        t.setDaemon(true);
        serverThread.set(t);
        return t;
    });

    @AfterEach
    void stopServer() {
        server.shutdownNow();
        Thread.interrupted(); // never leak an interrupt into the next test
    }

    @Test
    void onTheServerThreadItRunsInline() {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        Hop<String> hop = SupplyServerHop.hop(r -> {
            throw new AssertionError("inline: nothing is queued");
        }, () -> true, () -> {
            ranOn.set(Thread.currentThread());
            return "v";
        }, 50L);
        assertEquals(new Hop<>(Status.DONE, "v"), hop);
        assertSame(caller, ranOn.get());
    }

    @Test
    void offTheThreadItRunsOnTheServerAndReturnsTheValue() {
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        Hop<Integer> hop = SupplyServerHop.hop(server, onServerThread, () -> {
            ranOn.set(Thread.currentThread());
            return 42;
        }, 2_000L);
        assertEquals(new Hop<>(Status.DONE, 42), hop);
        assertSame(serverThread.get(), ranOn.get());
    }

    @Test
    void aTaskThatStartsAfterTheCallerGaveUpDoesNothing() {
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        Hop<String> hop = SupplyServerHop.hop(queued::add, onServerThread, () -> {
            runs.incrementAndGet();
            return "late";
        }, 30L);
        assertEquals(Status.TIMED_OUT, hop.status());
        assertEquals(1, queued.size());
        // The server finally gets to it: the caller already claimed it, so it neither runs nor opens anything.
        serverThread.set(Thread.currentThread());
        queued.get(0).run();
        assertEquals(0, runs.get(), "an abandoned task never runs");
    }

    @Test
    void aStartedTaskIsAwaitedUpToOneMoreTimeout() {
        CountDownLatch started = new CountDownLatch(1);
        Hop<String> hop = SupplyServerHop.hop(server, onServerThread, () -> {
            started.countDown();
            sleep(750L); // past the first timeout, well inside the second (250 ms margin either side)
            return "done";
        }, 500L);
        assertEquals(0, started.getCount());
        assertEquals(new Hop<>(Status.DONE, "done"), hop, "its items may already have moved: the caller hears of it");
    }

    @Test
    void aStartedTaskStillRunningAfterTheSecondTimeoutIsLost() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        long began = System.nanoTime();
        Hop<String> hop = SupplyServerHop.hop(server, onServerThread, () -> {
            await(release);
            finished.incrementAndGet();
            return "too late";
        }, 100L);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
        assertEquals(Status.UNFINISHED, hop.status());
        assertNull(hop.value());
        assertTrue(waitedMs >= 190L, "waited the timeout twice: " + waitedMs);
        release.countDown();
        server.shutdown();
        assertTrue(server.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(1, finished.get(), "a started task runs to its end; only its result is lost");
    }

    @Test
    void aStoppedServerRunsTheTaskInlineAndItRefuses() {
        AtomicInteger runs = new AtomicInteger();
        // After stop, execute() runs the task on the caller: never the server thread.
        Hop<String> hop = SupplyServerHop.hop(Runnable::run, () -> false, () -> {
            runs.incrementAndGet();
            return "touched the world off-thread";
        }, 1_000L);
        assertEquals(Status.NOT_RUNNING, hop.status());
        assertEquals(0, runs.get());
    }

    @Test
    void aRefusedTaskIsNotRunning() {
        Hop<String> hop = SupplyServerHop.hop(r -> {
            throw new RejectedExecutionException("stopped");
        }, () -> false, () -> "never", 1_000L);
        assertEquals(Status.NOT_RUNNING, hop.status());
    }

    @Test
    void aTaskThatThrowsFailsInlineAndOnTheServer() {
        Hop<String> inline = SupplyServerHop.hop(Runnable::run, () -> true, () -> {
            throw new IllegalStateException("boom");
        }, 1_000L);
        assertEquals(Status.FAILED, inline.status());
        Hop<String> hopped = SupplyServerHop.hop(server, onServerThread, () -> {
            throw new IllegalStateException("boom");
        }, 2_000L);
        assertEquals(Status.FAILED, hopped.status());
    }

    @Test
    void anInterruptedCallerAbandonsTheTaskAndKeepsTheInterrupt() {
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        Thread.currentThread().interrupt();
        Hop<String> hop = SupplyServerHop.hop(queued::add, onServerThread, () -> {
            runs.incrementAndGet();
            return "late";
        }, 1_000L);
        assertEquals(Status.INTERRUPTED, hop.status());
        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
        serverThread.set(Thread.currentThread());
        queued.get(0).run();
        assertEquals(0, runs.get(), "an abandoned task never runs");
    }

    @Test
    void missingPiecesAreNotRunning() {
        assertEquals(Status.NOT_RUNNING, SupplyServerHop.hop(null, () -> true, () -> "x", 10L).status());
        assertEquals(Status.NOT_RUNNING, SupplyServerHop.hop(Runnable::run, null, () -> "x", 10L).status());
        assertEquals(Status.NOT_RUNNING, SupplyServerHop.<String>hop(Runnable::run, () -> true, null, 10L).status());
    }

    @Test
    void onlyADoneHopGivesTheTasksValueEvenANullOne() {
        assertEquals("v", new Hop<>(Status.DONE, "v").valueOr("fallback"));
        assertNull(new Hop<String>(Status.DONE, null).valueOr("fallback"), "the task's own null is its answer");
        for (Status s : Status.values()) {
            if (s != Status.DONE) {
                assertEquals("fallback", Hop.<String>of(s).valueOr("fallback"), s.name());
            }
        }
        assertFalse(Hop.of(Status.TIMED_OUT).status() == Status.DONE);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
