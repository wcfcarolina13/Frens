package net.wcfcarolina13.PlayerUtils;

/**
 * Pure survival block-break timing, mirroring vanilla {@code AbstractBlock.calcBlockBreakingDelta}
 * (1.21.11 bytecode: hardness -1 is unbreakable, otherwise {@code speed / hardness / (canHarvest ? 30 : 100)}
 * in float) and the tick math {@link MiningTool} uses on top of it.
 *
 * <p>MiningTool keeps the vanilla delta call (it accounts for efficiency, haste, water and air) and
 * only uses {@link #requiredTicks}, {@link #isInstant}, {@link #timeoutMs} and {@link #extendDeadline} from here.</p>
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class BreakTimePolicy {

    /** Milliseconds per mining tick (one server tick). */
    public static final long TICK_MS = 50L;
    /** Floor for the mining failsafe timeout. */
    public static final long MIN_TIMEOUT_MS = 12_000L;
    /** Slack added on top of the expected mining time. */
    public static final long TIMEOUT_SLACK_MS = 5_000L;

    private BreakTimePolicy() {}

    /**
     * Per-tick break progress. Hardness -1 (bedrock and friends) gives 0, hardness 0 gives +Infinity.
     */
    public static float delta(float breakingSpeed, float hardness, boolean canHarvest) {
        if (hardness == -1.0f) {
            return 0.0f;
        }
        int divisor = canHarvest ? 30 : 100;
        return breakingSpeed / hardness / (float) divisor;
    }

    /** Ticks of continuous mining needed to break a block with this delta (at least 1). */
    public static int requiredTicks(float delta) {
        return Math.max(1, (int) Math.ceil(1.0f / delta));
    }

    /** Vanilla breaks the block on the first hit when one tick's progress reaches 1. */
    public static boolean isInstant(float delta) {
        return delta >= 1.0f;
    }

    /** Failsafe timeout for a mining attempt that needs {@code ticks} ticks. */
    public static long timeoutMs(int ticks) {
        return Math.max(MIN_TIMEOUT_MS, ticks * TICK_MS + TIMEOUT_SLACK_MS);
    }

    /**
     * Mining deadline after a re-poll switches to a tool needing {@code newRequiredTicks}: the later
     * of the current deadline and {@code nowMs + timeoutMs(newRequiredTicks)}. It never moves
     * earlier, so a faster tool keeps the slack it had and a slower one gets time to finish.
     */
    public static long extendDeadline(long currentDeadlineMs, long nowMs, int newRequiredTicks) {
        return Math.max(currentDeadlineMs, nowMs + timeoutMs(newRequiredTicks));
    }
}
