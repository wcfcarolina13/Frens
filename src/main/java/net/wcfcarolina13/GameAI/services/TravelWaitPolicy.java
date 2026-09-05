package net.wcfcarolina13.GameAI.services;

/**
 * Pure-logic policy: what a bot should do while a fast-travel cooldown is running.
 *
 * <p>No Minecraft imports. Decides between traveling immediately, running an idle hobby,
 * offloading inventory into an <em>existing</em> nearby chest, or simply waiting.
 *
 * <p>Note: {@link Action#OFFLOAD_EXISTING} only ever applies to a chest that already exists
 * nearby ({@code hasNearbyExistingChest}). This policy never asks the bot to build a new chest.
 */
public final class TravelWaitPolicy {

    public enum Action {
        TRAVEL_NOW,
        HOBBY,
        OFFLOAD_EXISTING,
        WAIT
    }

    public record Inputs(long cooldownRemainingTicks, boolean hobbiesEnabled, boolean hobbyAlreadyRunning,
                          boolean taskActive, boolean hasNearbyExistingChest, float inventoryFullness) {
    }

    /** Minimum remaining cooldown, in ticks, worth interrupting for a hobby (30s at 20 tps). */
    public static final long MIN_TICKS_WORTH_A_HOBBY = 600L;

    /** Inventory fullness fraction at/above which offload to an existing chest is worthwhile. */
    public static final float OFFLOAD_FULLNESS_THRESHOLD = 0.85f;

    /** Minimum ticks between two OFFLOAD_EXISTING dispatches for the same pending request (60s). */
    public static final long OFFLOAD_INTERVAL_TICKS = 1200L;

    /** Maximum times a queued travel may be retried before the request is dropped. */
    public static final int MAX_TRAVEL_RETRIES = 3;

    /**
     * Maximum recorded offload failures before a request stops dispatching further offload
     * attempts. A single false result from the offload attempt (chest full, or a transient walk
     * failure such as an abort) is not distinguishable from "this chest never accepts anything",
     * so a permanent latch on one failure is too aggressive; this caps the retries instead.
     */
    public static final int MAX_OFFLOAD_FAILURES = 2;

    private TravelWaitPolicy() {
    }

    /** True while a queued travel that has already been retried {@code retries} times may retry again. */
    public static boolean canRetry(int retries) {
        return retries < MAX_TRAVEL_RETRIES;
    }

    /** Occupied-slot fraction, clamped to [0,1]; a non-positive size reads as empty. */
    public static float fullness(int occupied, int size) {
        if (size <= 0) {
            return 0f;
        }
        return clampFullness((float) occupied / (float) size);
    }

    public static Action decide(Inputs in) {
        if (in == null) {
            return Action.WAIT;
        }

        if (in.cooldownRemainingTicks() <= 0) {
            return Action.TRAVEL_NOW;
        }

        if (in.hobbyAlreadyRunning()) {
            return Action.HOBBY;
        }

        float fullness = clampFullness(in.inventoryFullness());

        if (in.hasNearbyExistingChest() && fullness >= OFFLOAD_FULLNESS_THRESHOLD && !in.taskActive()) {
            return Action.OFFLOAD_EXISTING;
        }

        if (in.hobbiesEnabled() && !in.taskActive() && in.cooldownRemainingTicks() >= MIN_TICKS_WORTH_A_HOBBY) {
            return Action.HOBBY;
        }

        return Action.WAIT;
    }

    /**
     * True when a HOBBY decision should actually nudge the idle-hobby scheduler.
     * <p>
     * A HOBBY action can be reached while some other task already occupies the bot's single task
     * slot (including an ambient offload started by travel-wait itself); nudging then would be a
     * no-op at best and a re-dispatch attempt every tick at worst.
     */
    /**
     * Whether an {@link Action#OFFLOAD_EXISTING} decision may actually dispatch an offload run.
     *
     * <p>The action holds for as long as the inventory stays full and a chest is remembered nearby,
     * so without a backoff the service would walk the bot to the same chest every tick. Two gates:
     * a {@link #OFFLOAD_INTERVAL_TICKS} interval, and a failure counter — once
     * {@code offloadFailures} reaches {@code maxFailures} the request stops dispatching and falls
     * back to HOBBY/WAIT. A false result from a single offload attempt (chest full, or a transient
     * walk failure) is counted rather than latching permanently on the first one; an abort
     * (e.g. {@code /bot stop}) is not counted at all — the caller only increments the counter when
     * the offload ran to completion and moved nothing.
     *
     * @param nowTick         current world tick
     * @param lastOffloadTick tick of the last dispatch, or {@link Long#MIN_VALUE} when never
     * @param offloadFailures number of prior offload attempts that reported failure
     * @param maxFailures     failures at/above which no further dispatch is allowed
     */
    public static boolean shouldDispatchOffload(long nowTick, long lastOffloadTick, int offloadFailures,
                                                 int maxFailures) {
        if (offloadFailures >= maxFailures) {
            return false;
        }
        if (lastOffloadTick == Long.MIN_VALUE) {
            return true;
        }
        long elapsed = nowTick - lastOffloadTick;
        // A world-time rewind (negative elapsed) re-arms rather than locking the request out.
        return elapsed < 0 || elapsed >= OFFLOAD_INTERVAL_TICKS;
    }

    /**
     * Legacy 3-arg form kept for callers/tests still using the boolean latch. Delegates to the
     * counter-based rule: a latched failure reads as {@link #MAX_OFFLOAD_FAILURES} failures (blocks
     * immediately), no failure reads as zero.
     *
     * @deprecated prefer {@link #shouldDispatchOffload(long, long, int, int)}, which distinguishes
     *             a transient failure from a permanent one instead of latching on the first false.
     */
    @Deprecated
    public static boolean shouldDispatchOffload(long nowTick, long lastOffloadTick, boolean offloadFailed) {
        return shouldDispatchOffload(nowTick, lastOffloadTick,
                offloadFailed ? MAX_OFFLOAD_FAILURES : 0, MAX_OFFLOAD_FAILURES);
    }

    public static boolean shouldNudgeHobby(Inputs in) {
        return in != null && in.hobbiesEnabled() && !in.taskActive();
    }

    public static String describe(Inputs in, Action a) {
        if (in == null) {
            return "travel-wait remaining=? hobbies=? running=? task=? chest=? full=? -> " + a;
        }
        float fullness = clampFullness(in.inventoryFullness());
        return String.format(
                "travel-wait remaining=%dt hobbies=%s running=%s task=%s chest=%s full=%.2f -> %s",
                in.cooldownRemainingTicks(),
                in.hobbiesEnabled() ? "on" : "off",
                in.hobbyAlreadyRunning() ? "yes" : "no",
                in.taskActive() ? "yes" : "no",
                in.hasNearbyExistingChest() ? "yes" : "no",
                fullness,
                a
        );
    }

    private static float clampFullness(float fullness) {
        if (fullness < 0f) {
            return 0f;
        }
        if (fullness > 1f) {
            return 1f;
        }
        return fullness;
    }
}
