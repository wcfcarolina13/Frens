package net.wcfcarolina13.GameAI.skills.support;

/**
 * Pure update of the woodcut run's consecutive-failure streak (relocation at
 * {@code MAX_CONSECUTIVE_FAILURES}).
 *
 * <p>1.1.268 field (finding 3): a tree the bot cut logs from but could not finish
 * ("cluster-logs-remained") counted as a failure, so six partly felled trees in a row relocated
 * the bot out of a forest it was harvesting. A partial harvest that cut at least one log now
 * resets the streak like a success. The base is still marked failed so it is not picked again.
 *
 * <p>No Minecraft types.
 */
public final class WoodcutFailureStreakPolicy {

    public static final String CLUSTER_LOGS_REMAINED = "cluster-logs-remained";

    private WoodcutFailureStreakPolicy() {
    }

    /** The streak after one harvest attempt. */
    public static int next(int current, boolean success, String detail, int logsCut) {
        if (success || isProductivePartial(detail, logsCut)) {
            return 0;
        }
        return Math.max(0, current) + 1;
    }

    /** A partial harvest that cut logs: resets the streak and is not a path failure. */
    public static boolean isProductivePartial(String detail, int logsCut) {
        return logsCut > 0 && CLUSTER_LOGS_REMAINED.equals(detail);
    }
}
