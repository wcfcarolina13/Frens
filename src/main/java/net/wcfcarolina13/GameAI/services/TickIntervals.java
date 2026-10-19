package net.wcfcarolina13.GameAI.services;

/**
 * Overflow-safe interval checks over tick (or millisecond) timestamps.
 *
 * <p>Many services start a "last time this happened" field at {@link Long#MIN_VALUE} to mean
 * "never". A plain {@code now - last >= interval} then overflows: {@code now - Long.MIN_VALUE} wraps
 * to a negative number, so the gate never opens and, because the timestamp is only written inside
 * the gate, the guarded action never runs at all (finding 32). The mirror case,
 * {@code now - last <= window}, reads a never-happened event as "just happened". Use these helpers
 * instead of open-coding the subtraction.
 *
 * <p>Pure logic, no Minecraft types, safe from any thread.
 */
public final class TickIntervals {

    private TickIntervals() {
    }

    /**
     * True when at least {@code interval} has passed since {@code last}.
     *
     * <p>A never-set {@code last} ({@link Long#MIN_VALUE}) counts as elapsed. A clock that went
     * backwards ({@code now < last}, e.g. a world-time rewind) also counts as elapsed so the caller
     * re-arms instead of locking itself out until the clock catches up.
     */
    public static boolean elapsed(long now, long last, long interval) {
        if (last == Long.MIN_VALUE || now < last) {
            return true;
        }
        return now - last >= interval;
    }

    /**
     * True when {@code last} happened no more than {@code window} before {@code now}.
     *
     * <p>A never-set {@code last} ({@link Long#MIN_VALUE}) is never recent. A {@code last} in the
     * future ({@code now < last}, a clock rewind) is not treated as recent either.
     */
    public static boolean within(long now, long last, long window) {
        if (last == Long.MIN_VALUE || now < last) {
            return false;
        }
        return now - last <= window;
    }
}
