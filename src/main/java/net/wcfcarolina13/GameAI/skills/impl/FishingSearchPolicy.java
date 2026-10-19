package net.wcfcarolina13.GameAI.skills.impl;

/**
 * Pure budget rules for {@link FishingSkill}'s fishing spot search (finding 14).
 *
 * <p>A search beside a 7x7 pond took 18.7 s before the first cast: every stand option of
 * every water column ran its own A* reachability check, and neighbouring columns share
 * most of their stands. The skill now caches reachability per stand for one search, checks
 * a column's stands in score order and stops after the first reachable one plus a couple of
 * alternates, and stops evaluating columns once a usable spot exists and the time budget is
 * spent. These are the numbers and decisions behind that, kept free of {@code net.minecraft.*}
 * types so they can be unit-tested.</p>
 */
public final class FishingSearchPolicy {

    /** Hard cap on water columns evaluated once a usable spot exists. */
    public static final int MAX_WATER_EVALUATIONS = 80;
    /** Wall-clock budget for one search once a usable spot exists. */
    public static final long TIME_BUDGET_MS = 1_500L;
    /** Reachable stands kept per spot besides the primary, for the approach fallback. */
    public static final int MAX_STAND_ALTERNATES = 2;

    private FishingSearchPolicy() {
    }

    /**
     * Whether the search should stop evaluating further water columns. Without a usable
     * spot it never stops early, so a slow search still finds something when it can.
     *
     * @param evaluated  water columns evaluated so far
     * @param elapsedMs  milliseconds since the search started
     * @param haveBest   whether a reachable spot has been found
     */
    public static boolean stopEvaluating(int evaluated, long elapsedMs, boolean haveBest) {
        if (!haveBest) {
            return false;
        }
        return evaluated >= MAX_WATER_EVALUATIONS || elapsedMs >= TIME_BUDGET_MS;
    }

    /**
     * Whether a column's stand option still needs its reachability checked. Options come in
     * score order (lower is better). Before the column has a reachable primary, an option
     * that cannot beat the current best cannot make this column win, so neither can any
     * later option. After a primary is found, options are only wanted as alternates.
     *
     * @param primaryFound     whether this column already has a reachable primary stand
     * @param reachableKept    reachable stands already kept for this column (primary included)
     * @param optionScore      this option's score
     * @param bestScore        best score found so far in the search ({@link Double#MAX_VALUE} if none)
     */
    public static boolean worthChecking(boolean primaryFound, int reachableKept, double optionScore, double bestScore) {
        if (primaryFound) {
            return reachableKept < 1 + MAX_STAND_ALTERNATES;
        }
        return optionScore < bestScore;
    }
}
