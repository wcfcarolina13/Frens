package net.wcfcarolina13.GameAI.services;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Keeps a walk bound to its caller even when a replacement task clears the bot's abort latch. */
public final class MovementAbortPolicy {
    private static final ThreadLocal<BooleanSupplier> CURRENT = new ThreadLocal<>();

    private MovementAbortPolicy() {}

    public static boolean abortRequested(boolean taskAbort) {
        BooleanSupplier abort = CURRENT.get();
        return taskAbort || (abort != null && abort.getAsBoolean());
    }

    public static <T> T withAbort(BooleanSupplier abort, Supplier<T> body) {
        BooleanSupplier previous = CURRENT.get();
        CURRENT.set(() -> (previous != null && previous.getAsBoolean())
                || (abort != null && abort.getAsBoolean()));
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static <T> Supplier<T> guard(Supplier<T> action, T fallback) {
        BooleanSupplier abort = CURRENT.get();
        return () -> abort == null || !abort.getAsBoolean() ? action.get() : fallback;
    }

    /** Capture the worker's check before dispatching a steering step to the server thread. */
    public static Runnable guard(Runnable action) {
        BooleanSupplier abort = CURRENT.get();
        return () -> {
            if (abort == null || !abort.getAsBoolean()) {
                action.run();
            }
        };
    }
}
