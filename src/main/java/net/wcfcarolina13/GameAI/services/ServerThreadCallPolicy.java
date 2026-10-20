package net.wcfcarolina13.GameAI.services;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ServerThreadCallPolicy {
    private ServerThreadCallPolicy() {
    }

    public static <T> T call(boolean onServerThread, Consumer<Runnable> dispatch,
                             Supplier<T> action, long timeoutMs, T fallback) {
        if (action == null || dispatch == null) {
            return fallback;
        }
        if (onServerThread) {
            return action.get();
        }
        FutureTask<T> task = new FutureTask<>(action::get);
        try {
            dispatch.accept(task);
            return task.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            return fallback;
        } finally {
            // Cancel a queued mutation after its caller gives up, without interrupting the server.
            task.cancel(false);
        }
    }
}
