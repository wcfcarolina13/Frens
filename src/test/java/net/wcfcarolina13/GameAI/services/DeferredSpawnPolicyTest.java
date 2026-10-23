package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeferredSpawnPolicyTest {
    @Test
    void lookupAndConnectionCompleteTheSameFutureInOrder() {
        var lookup = new CompletableFuture<String>();
        List<Runnable> queued = new ArrayList<>();
        List<String> connected = new ArrayList<>();
        var spawned = DeferredSpawnPolicy.connectAfter(lookup, queued::add, () -> true, connected::add);
        assertFalse(spawned.isDone());
        lookup.complete("profile");
        assertFalse(spawned.isDone());
        assertTrue(connected.isEmpty());
        queued.get(0).run();
        assertNull(spawned.join());
        assertEquals(List.of("profile"), connected);
    }

    @Test
    void stoppedOrReplacedServerDoesNotQueueTheLateLookup() {
        var lookup = new CompletableFuture<String>();
        AtomicBoolean active = new AtomicBoolean(true);
        List<Runnable> queued = new ArrayList<>();
        var spawned = DeferredSpawnPolicy.connectAfter(lookup, queued::add, active::get,
                profile -> fail("old server must not connect"));
        active.set(false);
        lookup.complete("profile");
        assertTrue(spawned.isCancelled());
        assertTrue(queued.isEmpty());
    }

    @Test
    void serverStoppingAfterDispatchDoesNotConnect() {
        AtomicBoolean active = new AtomicBoolean(true);
        List<Runnable> queued = new ArrayList<>();
        var spawned = DeferredSpawnPolicy.connectAfter(CompletableFuture.completedFuture("profile"),
                queued::add, active::get, profile -> fail("queued stale spawn must not connect"));
        active.set(false);
        queued.get(0).run();
        assertTrue(spawned.isCancelled());
    }

    @Test
    void rejectedDispatchCompletesExceptionallyInsteadOfLeavingSpawnPending() {
        var spawned = DeferredSpawnPolicy.connectAfter(CompletableFuture.completedFuture("profile"),
                task -> { throw new RejectedExecutionException("stopped"); }, () -> true,
                profile -> fail("dispatch was rejected"));
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(CompletionException.class, spawned::join).getCause());
    }

    @Test
    void lookupAndConnectionFailuresBothReachTheReturnedFuture() {
        var lookupFailure = new IllegalArgumentException("lookup");
        var first = DeferredSpawnPolicy.connectAfter(CompletableFuture.<String>failedFuture(lookupFailure),
                task -> fail("failed lookup must not dispatch"), () -> true, profile -> fail("no profile"));
        assertSame(lookupFailure, assertThrows(CompletionException.class, first::join).getCause());
        var connectFailure = new IllegalStateException("connect");
        var second = DeferredSpawnPolicy.connectAfter(CompletableFuture.completedFuture("profile"),
                Runnable::run, () -> true, profile -> { throw connectFailure; });
        assertSame(connectFailure, assertThrows(CompletionException.class, second::join).getCause());
    }

    @Test
    void cancellationBeforeLookupOrWhileQueuedPreventsConnection() {
        var lookup = new CompletableFuture<String>();
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger connected = new AtomicInteger();
        var first = DeferredSpawnPolicy.connectAfter(lookup, queued::add, () -> true,
                profile -> connected.incrementAndGet());
        first.cancel(false);
        lookup.complete("profile");
        assertTrue(queued.isEmpty());
        var second = DeferredSpawnPolicy.connectAfter(CompletableFuture.completedFuture("profile"),
                queued::add, () -> true, profile -> connected.incrementAndGet());
        second.cancel(false);
        queued.get(0).run();
        assertEquals(0, connected.get());
    }

    @Test
    void inlineDispatcherCompletesWithoutASecondThreadOrWait() {
        AtomicInteger connected = new AtomicInteger();
        var spawned = DeferredSpawnPolicy.connectAfter(CompletableFuture.completedFuture("profile"),
                Runnable::run, () -> true, profile -> connected.incrementAndGet());
        assertNull(spawned.join());
        assertEquals(1, connected.get());
    }

    @Test
    void lifecyclePredicateFailuresAreObservedBeforeAndInsideTheHop() {
        var first = DeferredSpawnPolicy.connectAfter(CompletableFuture.completedFuture("profile"),
                task -> fail("must not dispatch"), () -> { throw new IllegalStateException("lifecycle"); },
                profile -> fail("must not connect"));
        assertTrue(first.isCompletedExceptionally());
        AtomicInteger probes = new AtomicInteger();
        List<Runnable> queued = new ArrayList<>();
        var second = DeferredSpawnPolicy.connectAfter(CompletableFuture.completedFuture("profile"),
                queued::add, () -> { if (probes.incrementAndGet() > 1) throw new IllegalStateException("late"); return true; },
                profile -> fail("must not connect"));
        queued.get(0).run();
        assertTrue(second.isCompletedExceptionally());
    }

    @Test
    void shutdownCompletesAcceptedButDiscardedSpawnWithoutRunningTheQueue() {
        Object owner = new Object();
        AtomicBoolean active = new AtomicBoolean(true);
        List<Runnable> queued = new ArrayList<>();
        var spawned = DeferredSpawnPolicy.connectAfter(owner, CompletableFuture.completedFuture("profile"),
                queued::add, active::get, profile -> fail("discarded task must not connect"));
        assertEquals(1, queued.size());
        active.set(false);
        DeferredSpawnPolicy.cancelPending(owner);
        assertTrue(spawned.isCancelled());
        queued.get(0).run();
    }

    @Test
    void shutdownAlsoCompletesLookupThatNeverReturns() {
        Object owner = new Object();
        var lookup = new CompletableFuture<String>();
        var spawned = DeferredSpawnPolicy.connectAfter(owner, lookup,
                task -> fail("stopped lookup must not dispatch"), () -> true,
                profile -> fail("must not connect"));
        DeferredSpawnPolicy.cancelPending(owner);
        assertTrue(spawned.isCancelled());
        lookup.complete("late profile");
    }

    @Test
    void stoppedAdmissionCannotRegisterAfterShutdownDrain() {
        Object owner = new Object();
        DeferredSpawnPolicy.cancelPending(owner);
        var spawned = DeferredSpawnPolicy.connectAfter(owner, new CompletableFuture<String>(),
                task -> fail("must not dispatch"), () -> false, profile -> fail("must not connect"));
        assertTrue(spawned.isCancelled());
    }

    @Test
    void stopInsideDispatchCancelsTheAlreadyRegisteredFuture() {
        Object owner = new Object();
        AtomicBoolean active = new AtomicBoolean(true);
        var spawned = DeferredSpawnPolicy.connectAfter(owner, CompletableFuture.completedFuture("profile"),
                task -> {
                    active.set(false);
                    DeferredSpawnPolicy.cancelPending(owner);
                }, active::get, profile -> fail("dropped task must not connect"));
        assertTrue(spawned.isCancelled());
    }

    @Test
    void shutdownUsesOwnerIdentityAndCannotCancelAnotherServer() {
        Object oldServer = new String("same value");
        Object newServer = new String("same value");
        var oldLookup = new CompletableFuture<String>();
        var newLookup = new CompletableFuture<String>();
        var oldSpawn = DeferredSpawnPolicy.connectAfter(oldServer, oldLookup, Runnable::run,
                () -> true, profile -> fail("old lookup was cancelled"));
        var newSpawn = DeferredSpawnPolicy.connectAfter(newServer, newLookup, Runnable::run,
                () -> true, profile -> { });
        DeferredSpawnPolicy.cancelPending(oldServer);
        assertTrue(oldSpawn.isCancelled());
        assertFalse(newSpawn.isDone());
        newLookup.complete("profile");
        assertNull(newSpawn.join());
    }

    @Test
    void terminalSpawnsDoNotRetainTheirServerInTheRegistry() throws Exception {
        Object owner = new Object();
        DeferredSpawnPolicy.connectAfter(owner, CompletableFuture.completedFuture("profile"),
                Runnable::run, () -> true, profile -> { }).join();
        var cancelled = DeferredSpawnPolicy.connectAfter(owner, new CompletableFuture<String>(),
                Runnable::run, () -> true, profile -> { });
        cancelled.cancel(false);
        var failed = DeferredSpawnPolicy.connectAfter(owner, CompletableFuture.completedFuture("profile"),
                Runnable::run, () -> true, profile -> { throw new IllegalStateException("failed"); });
        assertTrue(failed.isCompletedExceptionally());
        var field = DeferredSpawnPolicy.class.getDeclaredField("PENDING");
        field.setAccessible(true);
        var pending = (java.util.Map<?, ?>) field.get(null);
        synchronized (pending) {
            assertFalse(pending.containsKey(owner));
        }
    }

    @Test
    void shutdownWiringClosesAdmissionBeforeDrainingAndSpawnCapturesServer() throws Exception {
        String lifecycle = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Frens.java"));
        int stop = lifecycle.indexOf("TaskService.markServerStopping();");
        int drain = lifecycle.indexOf("DeferredSpawnPolicy.cancelPending(server);", stop);
        int executors = lifecycle.indexOf("TaskService.shutdownExecutors();", stop);
        assertTrue(stop >= 0 && drain > stop && drain < executors);
        String spawn = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java"));
        assertTrue(spawn.contains("DeferredSpawnPolicy.connectAfter(\n                    server,"));
    }

    @Test
    void onlineSpawnUsesTheObservedLifecycleGuardedHandoffAndKeepsItsFallbackProfile() throws Exception {
        String source = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java"));
        int start = source.indexOf("if (useMojangAuth) {");
        int end = source.indexOf("return spawned;", start);
        assertTrue(start >= 0 && end > start);
        String deferred = source.substring(start, end);
        assertTrue(deferred.contains("DeferredSpawnPolicy.connectAfter("));
        assertTrue(deferred.contains("server::execute"));
        assertTrue(deferred.contains("!TaskService.isServerStopping()"));
        assertTrue(deferred.contains("!server.isStopped()"));
        assertTrue(deferred.contains("Frens.serverInstance == server"));
        assertTrue(deferred.contains("p.orElse(finalGP)"));
        assertTrue(deferred.contains(".exceptionally(e -> Optional.empty())"));
        assertFalse(deferred.contains("spawned.complete(null)"));
    }
}
