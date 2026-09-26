package net.wcfcarolina13.FilingSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ManualConfig.ModelFetchCoalescer}: one fetch at a time, one rerun after a key change. */
class ModelFetchCoalescerTest {

    /** Fetches that finish only when the test completes them. */
    private static final class Harness {
        final AtomicReference<String> snapshot = new AtomicReference<>("openai\u0000old-key\u0000");
        final List<String> started = new ArrayList<>();
        final List<CompletableFuture<Void>> pending = new ArrayList<>();
        final ManualConfig.ModelFetchCoalescer coalescer = new ManualConfig.ModelFetchCoalescer(snapshot::get, s -> {
            started.add(s);
            CompletableFuture<Void> f = new CompletableFuture<>();
            pending.add(f);
            return f;
        });

        void finish(int index) {
            pending.get(index).complete(null);
        }
    }

    @Test
    void sameSnapshotSharesTheInFlightFetch() {
        Harness h = new Harness();
        CompletableFuture<Void> first = h.coalescer.request();
        CompletableFuture<Void> second = h.coalescer.request();
        assertSame(first, second);
        assertEquals(1, h.started.size());
        assertTrue(h.coalescer.inFlight());
        h.finish(0);
        assertTrue(first.isDone());
        assertFalse(h.coalescer.inFlight());
        assertEquals(1, h.started.size(), "no rerun without a change");
    }

    @Test
    void keyChangeWhileInFlightRunsExactlyOneFollowUpWithTheNewKey() {
        Harness h = new Harness();
        CompletableFuture<Void> first = h.coalescer.request();
        h.snapshot.set("openai\u0000new-key\u0000");
        CompletableFuture<Void> second = h.coalescer.request();
        CompletableFuture<Void> third = h.coalescer.request(); // coalesces into the same rerun
        assertSame(first, second);
        assertSame(first, third);

        h.finish(0);
        assertFalse(first.isDone(), "callers get the future of the FINAL fetch, not the stale one");
        assertEquals(List.of("openai\u0000old-key\u0000", "openai\u0000new-key\u0000"), h.started);

        h.finish(1);
        assertTrue(first.isDone());
        assertEquals(2, h.started.size(), "exactly one follow-up");
        assertFalse(h.coalescer.inFlight());
    }

    @Test
    void aFailedFetchStillCompletesAndReleases() {
        Harness h = new Harness();
        CompletableFuture<Void> first = h.coalescer.request();
        h.pending.get(0).completeExceptionally(new RuntimeException("offline"));
        assertTrue(first.isDone());
        assertFalse(first.isCompletedExceptionally(), "callers only wait for completion");
        CompletableFuture<Void> next = h.coalescer.request();
        assertFalse(next.isDone());
        assertEquals(2, h.started.size());
    }

    @Test
    void aFetchThatThrowsOnStartDoesNotWedgeTheCoalescer() {
        ManualConfig.ModelFetchCoalescer c = new ManualConfig.ModelFetchCoalescer(() -> "x", s -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(c.request().isDone());
        assertFalse(c.inFlight());
    }
}
