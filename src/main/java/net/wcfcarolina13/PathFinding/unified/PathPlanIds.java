package net.wcfcarolina13.PathFinding.unified;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Plan IDs for the {@code [PathFinder]} / PathCompare lines: {@link #next()} hands out 1, 2, 3...
 * and {@link #format(long)} renders {@code p<n>} (one token, no spaces). Reset at SERVER_STOPPING.
 */
public final class PathPlanIds {
    private static final AtomicLong COUNTER = new AtomicLong();

    private PathPlanIds() {
    }

    public static long next() {
        return COUNTER.incrementAndGet();
    }

    public static String format(long planId) {
        return "p" + planId;
    }

    public static void reset() {
        COUNTER.set(0);
    }
}
