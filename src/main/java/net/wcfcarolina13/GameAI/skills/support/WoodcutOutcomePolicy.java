package net.wcfcarolina13.GameAI.skills.support;

/**
 * How a woodcut run that hit its failure streak ends. A scan that finds no tree pins the streak
 * at the maximum to force a relocation, so after the last relocation the counter reads like
 * repeated reach failures even when nothing failed (finding 41, 1.1.274: a bot that cut the only
 * tree ended "Repeated failures reaching remaining targets" with pathFailures=0). The caller
 * tracks whether the streak was last driven by an empty scan rather than a real failure.
 *
 * <p>No Minecraft types.
 */
public final class WoodcutOutcomePolicy {

    private WoodcutOutcomePolicy() {
    }

    /** True when the run ended on real failures to reach or cut the remaining targets. */
    public static boolean endedOnRepeatedFailures(int consecutiveFailures, int maxConsecutiveFailures,
                                                  boolean exhaustedNoTargets) {
        return consecutiveFailures >= maxConsecutiveFailures && !exhaustedNoTargets;
    }

    /** True when the run cut some trees and then ran out of trees to find. */
    public static boolean ranOutOfTrees(int felled, int consecutiveFailures, int maxConsecutiveFailures,
                                        boolean exhaustedNoTargets) {
        return felled > 0 && consecutiveFailures >= maxConsecutiveFailures && exhaustedNoTargets;
    }

    /** The success line for a run that ran out of trees (no semicolons in woodcut lines). */
    public static String ranOutOfTreesLine(int felled) {
        return "Cut " + Math.max(0, felled) + " tree(s). No more trees nearby.";
    }
}
