package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Bounded, FIFO scheduler for {@link SoulModelProvider} generation calls.
 *
 * <p>Enforces two invariants: no two in-flight calls ever share a {@link SoulTypes.ConversationKey}
 * (a bot never talks over itself in the same conversation), and no more than {@code maxConcurrent}
 * calls run at once across all conversations. Everything is driven off the provider futures'
 * {@code whenComplete} callbacks — there is no polling thread, no cached thread pool, and no
 * sleeping; {@link #pump()} is invoked synchronously wherever the active/queued state changes.
 *
 * <p>A single {@code lock} object guards all mutable state ({@code queue}, {@code activeKeys},
 * {@code activeCalls}, {@code closed}). Java's {@code synchronized} is reentrant per-thread, so a
 * completion callback that re-enters the scheduler (e.g. re-submitting on failure) cannot
 * self-deadlock. External side effects — completing a caller's future, invoking a provider's
 * {@code cancel} — are always performed after releasing the lock.
 */
public final class SoulGenerationScheduler implements AutoCloseable {

    private record QueuedJob(SoulTypes.ConversationKey key, long epoch,
                              Supplier<SoulModelProvider.Call> callSupplier,
                              CompletableFuture<SoulTypes.ProviderResult> future) {
    }

    private record ActiveJob(SoulTypes.ConversationKey key, long epoch,
                              SoulModelProvider.Call call,
                              CompletableFuture<SoulTypes.ProviderResult> future) {
    }

    private record StartedJob(QueuedJob job, ActiveJob activeJob) {
    }

    private final Object lock = new Object();
    private final int maxConcurrent;
    private final int queueCapacity;

    private final ArrayDeque<QueuedJob> queue = new ArrayDeque<>();
    private final Set<SoulTypes.ConversationKey> activeKeys = new HashSet<>();
    private final Map<SoulTypes.ConversationKey, ActiveJob> activeCalls = new HashMap<>();
    private boolean closed;

    public SoulGenerationScheduler(int maxConcurrent, int queueCapacity) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be >= 1");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("queueCapacity must be >= 0");
        }
        this.maxConcurrent = maxConcurrent;
        this.queueCapacity = queueCapacity;
    }

    /**
     * Enqueues a generation call for {@code key}. Starts immediately if a slot is free and no
     * other call for the same key is active; otherwise waits in the FIFO queue. If the queue is
     * already at capacity, the returned future completes immediately with {@code OVERLOADED} and
     * {@code callSupplier} is never invoked.
     */
    public CompletableFuture<SoulTypes.ProviderResult> submit(
            SoulTypes.ConversationKey key, long epoch, Supplier<SoulModelProvider.Call> callSupplier) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(callSupplier, "callSupplier");

        CompletableFuture<SoulTypes.ProviderResult> future = new CompletableFuture<>();
        boolean rejectClosed = false;
        boolean overloaded = false;
        synchronized (lock) {
            if (closed) {
                rejectClosed = true;
            } else if (queue.size() >= queueCapacity) {
                overloaded = true;
            } else {
                queue.addLast(new QueuedJob(key, epoch, callSupplier, future));
            }
        }

        if (rejectClosed) {
            future.complete(typedFailure(SoulTypes.FailureCode.CANCELLED));
            return future;
        }
        if (overloaded) {
            future.complete(typedFailure(SoulTypes.FailureCode.OVERLOADED));
            return future;
        }
        pump();
        return future;
    }

    /**
     * Invalidates work belonging to {@code key} that was scheduled before {@code newEpoch}: the
     * active call (if older) is cancelled — its own future resolves however the provider maps
     * that cancellation, normally {@code CANCELLED} — and any still-queued, never-started jobs
     * for that key older than {@code newEpoch} are completed immediately with {@code STALE_EPOCH}.
     */
    public void invalidate(SoulTypes.ConversationKey key, long newEpoch) {
        Objects.requireNonNull(key, "key");

        SoulModelProvider.Call callToCancel = null;
        List<CompletableFuture<SoulTypes.ProviderResult>> staleFutures = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            ActiveJob active = activeCalls.get(key);
            if (active != null && active.epoch() < newEpoch) {
                callToCancel = active.call();
            }
            Iterator<QueuedJob> it = queue.iterator();
            while (it.hasNext()) {
                QueuedJob job = it.next();
                if (job.key().equals(key) && job.epoch() < newEpoch) {
                    it.remove();
                    staleFutures.add(job.future());
                }
            }
        }

        if (callToCancel != null) {
            callToCancel.cancelNow();
        }
        for (CompletableFuture<SoulTypes.ProviderResult> staleFuture : staleFutures) {
            staleFuture.complete(typedFailure(SoulTypes.FailureCode.STALE_EPOCH));
        }
    }

    /** Number of jobs waiting in the queue (not yet dispatched to a provider). */
    public int queueDepth() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /**
     * Cancels every active call and completes every still-queued job with {@code CANCELLED}.
     * Does not block: cancellation is fire-and-forget, and queued jobs never had a provider call
     * to wait on. Idempotent.
     */
    @Override
    public void close() {
        List<SoulModelProvider.Call> callsToCancel = new ArrayList<>();
        List<CompletableFuture<SoulTypes.ProviderResult>> queuedFutures = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            for (ActiveJob active : activeCalls.values()) {
                callsToCancel.add(active.call());
            }
            for (QueuedJob job : queue) {
                queuedFutures.add(job.future());
            }
            queue.clear();
        }

        for (SoulModelProvider.Call call : callsToCancel) {
            call.cancelNow();
        }
        for (CompletableFuture<SoulTypes.ProviderResult> future : queuedFutures) {
            future.complete(typedFailure(SoulTypes.FailureCode.CANCELLED));
        }
    }

    // === Dispatch ===

    /**
     * Starts as many queued jobs as capacity and per-key exclusivity allow. Safe to call from
     * any thread at any time; serializes on {@code lock} so concurrent callers (e.g. two
     * provider futures completing on different HTTP client threads at once) can never together
     * exceed {@code maxConcurrent}.
     */
    private void pump() {
        List<StartedJob> started = new ArrayList<>();
        synchronized (lock) {
            while (!closed && activeCalls.size() < maxConcurrent) {
                QueuedJob job = pollNextEligibleLocked();
                if (job == null) {
                    break;
                }
                activeKeys.add(job.key());
                SoulModelProvider.Call call;
                try {
                    call = job.callSupplier().get();
                } catch (RuntimeException ex) {
                    activeKeys.remove(job.key());
                    started.add(new StartedJob(job, null));
                    continue;
                }
                ActiveJob activeJob = new ActiveJob(job.key(), job.epoch(), call, job.future());
                activeCalls.put(job.key(), activeJob);
                started.add(new StartedJob(job, activeJob));
            }
        }

        for (StartedJob startedJob : started) {
            if (startedJob.activeJob() == null) {
                // Supplier itself threw: an ordinary scheduling failure, not a dialogue result
                // or an exceptional future.
                startedJob.job().future().complete(typedFailure(SoulTypes.FailureCode.INTERNAL));
            } else {
                wireCompletion(startedJob.job(), startedJob.activeJob());
            }
        }
    }

    /** Must be called while holding {@code lock}. */
    private QueuedJob pollNextEligibleLocked() {
        Iterator<QueuedJob> it = queue.iterator();
        while (it.hasNext()) {
            QueuedJob job = it.next();
            if (!activeKeys.contains(job.key())) {
                it.remove();
                return job;
            }
        }
        return null;
    }

    private void wireCompletion(QueuedJob job, ActiveJob activeJob) {
        activeJob.call().result().whenComplete((result, throwable) -> {
            SoulTypes.ProviderResult outcome = throwable == null ? result : typedFailure(SoulTypes.FailureCode.INTERNAL);
            boolean shouldComplete;
            synchronized (lock) {
                // Only this job's own completion should retire it. invalidate()/close() may have
                // already force-completed job.future() (e.g. STALE_EPOCH for a queued job) — but
                // for an active job neither of those touches activeCalls directly, so this check
                // is a defensive invariant guard rather than a live race in the current design.
                shouldComplete = activeCalls.get(job.key()) == activeJob;
                if (shouldComplete) {
                    activeCalls.remove(job.key());
                    activeKeys.remove(job.key());
                }
            }
            if (shouldComplete) {
                job.future().complete(outcome);
            }
            pump();
        });
    }

    private static SoulTypes.ProviderResult typedFailure(SoulTypes.FailureCode code) {
        return new SoulTypes.ProviderResult(false, "", code, "", "", 0L, null, null, null);
    }
}
