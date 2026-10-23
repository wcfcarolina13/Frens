package net.wcfcarolina13.GameAI.services;

import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Keeps profile lookup completion and server-side connection in one observed future. */
public final class DeferredSpawnPolicy {
    private static final Map<Object, Set<CompletableFuture<Void>>> PENDING = new IdentityHashMap<>();

    private DeferredSpawnPolicy() { }

    /** Call after closing this owner's admission predicate, before its server queue is discarded. */
    public static void cancelPending(Object owner) {
        Set<CompletableFuture<Void>> pending;
        synchronized (PENDING) {
            pending = PENDING.remove(owner);
        }
        if (pending != null) {
            for (CompletableFuture<Void> future : pending) future.cancel(false);
        }
    }

    public static <T> CompletableFuture<Void> connectAfter(CompletableFuture<T> lookup,
                                                          Consumer<Runnable> dispatch,
                                                          BooleanSupplier serverAcceptsWork,
                                                          Consumer<T> connect) {
        return connectAfter(null, lookup, dispatch, serverAcceptsWork, connect);
    }

    public static <T> CompletableFuture<Void> connectAfter(Object owner,
                                                          CompletableFuture<T> lookup,
                                                          Consumer<Runnable> dispatch,
                                                          BooleanSupplier serverAcceptsWork,
                                                          Consumer<T> connect) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(serverAcceptsWork, "serverAcceptsWork");
        Objects.requireNonNull(connect, "connect");
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (owner != null) {
            result.whenComplete((ignored, error) -> {
                synchronized (PENDING) {
                    Set<CompletableFuture<Void>> pending = PENDING.get(owner);
                    if (pending != null) {
                        pending.remove(result);
                        if (pending.isEmpty()) PENDING.remove(owner);
                    }
                }
            });
            try {
                synchronized (PENDING) {
                    if (!serverAcceptsWork.getAsBoolean()) {
                        result.cancel(false);
                        return result;
                    }
                    PENDING.computeIfAbsent(owner, ignored -> new HashSet<>()).add(result);
                }
            } catch (Throwable error) {
                result.completeExceptionally(error);
                return result;
            }
        }
        lookup.whenComplete((profile, lookupError) -> {
            if (result.isDone()) return;
            if (lookupError != null) {
                result.completeExceptionally(lookupError);
                return;
            }
            try {
                if (!serverAcceptsWork.getAsBoolean()) {
                    result.completeExceptionally(new CancellationException("Spawn server is no longer active"));
                    return;
                }
                dispatch.accept(() -> {
                    if (result.isDone()) return;
                    try {
                        if (!serverAcceptsWork.getAsBoolean()) {
                            result.completeExceptionally(new CancellationException("Spawn server is no longer active"));
                            return;
                        }
                        connect.accept(profile);
                        result.complete(null);
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                    }
                });
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }
}
