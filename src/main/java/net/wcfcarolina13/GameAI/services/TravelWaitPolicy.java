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

    /** Maximum times a queued travel may be retried before the request is dropped. */
    public static final int MAX_TRAVEL_RETRIES = 3;

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
