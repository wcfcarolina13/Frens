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

    /** How far a stand candidate may sit from its water block, per axis (FishingSkill.findStandCandidates). */
    public static final int STAND_REACH = 3;
    /** Blocks added to the scanned area when looking for chest anchors (finding 39). */
    public static final int CHEST_SCAN_MARGIN = 8;
    /**
     * Half height of the chest-anchor scan, centred on the bot (or the remembered water). 16, as
     * the old HOME_CHEST_Y_SPAN: the chest bonus counts within 16 blocks in 3D, so chests well
     * above or below the bot used to count and still should. Reads stay chunk-local.
     */
    public static final int CHEST_SCAN_Y_SPAN = 16;

    private FishingSearchPolicy() {
    }

    /**
     * Horizontal half width of the chest-anchor scan for an area of the given radius
     * (finding 39). The scan used to cover 48 blocks around the bot and again around home,
     * ~620k block reads per search; anchors only give stands a small score bonus, so the
     * scan now covers the searched area plus {@link #CHEST_SCAN_MARGIN}.
     *
     * @param areaRadius radius of the searched area (the water search radius). The
     *                   remembered-spot path does not use this: it scans
     *                   {@link #STAND_REACH} + FishingSkill.CHEST_PROXIMITY_RADIUS (19) around the
     *                   remembered water directly.
     */
    public static int chestScanHalfWidth(int areaRadius) {
        return Math.max(0, areaRadius) + CHEST_SCAN_MARGIN;
    }

    /** Whether a chest at this offset from the scan centre is inside the chest-anchor scan. */
    public static boolean chestScanCovers(int dx, int dy, int dz, int halfWidth, int yHalfSpan) {
        return Math.abs(dx) <= halfWidth && Math.abs(dz) <= halfWidth && Math.abs(dy) <= yHalfSpan;
    }

    /**
     * Whether a stand at this offset from the bot needs an A* reachability check, which is
     * what makes the search build its path snapshot (finding 39: the snapshot alone was ~60%
     * of an 11.7 s search that then ran no A* at all). Mirrors the reach-check condition in
     * FishingSkill: the stand lies inside the snapshot box (|dx|,|dz| &lt;= regionRadius,
     * |dy| &lt;= yHalfSpan for a snapshot centred on the bot) and the bot is not already on it.
     *
     * @param alreadyThereSq squared distance at or under which the bot counts as standing on the stand
     */
    public static boolean snapshotNeeded(int dx, int dy, int dz, int regionRadius, int yHalfSpan, double alreadyThereSq) {
        if (Math.abs(dx) > regionRadius || Math.abs(dz) > regionRadius || Math.abs(dy) > yHalfSpan) {
            return false;
        }
        double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
        return distSq > alreadyThereSq;
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
