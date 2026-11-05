package net.wcfcarolina13.GameAI.skills;

/**
 * Ends a collect job that keeps iterating without collecting anything. The failure counter alone is not
 * a reliable exit: a shovel "success" or an exploration move resets it, so a job left with only
 * unbreakable targets (blocks holding a painting) could loop until the game closed. Pure so it can be
 * tested without Minecraft types.
 */
public final class MiningStallPolicy {
    /**
     * Iterations in a row without a gain before the job ends. Six covers a full exploration cycle
     * (fail, move, retry) with room to spare; each stalled iteration takes about 3-6 s in the sandbox.
     */
    public static final int NO_GAIN_ITERATION_LIMIT = 6;

    private MiningStallPolicy() {
    }

    /** The no-gain streak after an iteration that started with {@code previous} and now has {@code collected}. */
    public static int nextStreak(int streak, int previous, int collected) {
        return collected > previous ? 0 : streak + 1;
    }

    public static boolean exhausted(int streak) {
        return streak >= NO_GAIN_ITERATION_LIMIT;
    }
}
