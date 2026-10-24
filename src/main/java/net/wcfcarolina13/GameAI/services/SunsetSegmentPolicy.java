package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.PathFinding.unified.PlanStatus;

/**
 * Pure decisions for the sunset return's travel segments ({@link BotAutoReturnSunsetService}): which
 * node of a probe path becomes the next follow goal, and when the next probe may run. Ints only, so
 * it is testable without Minecraft types.
 */
final class SunsetSegmentPolicy {

    /** A segment ends once the walked length along the path reaches this. */
    static final double MAX_SEGMENT_PROGRESS = 24.0D;
    /** A FULL (or old-finder) path must move the goal at least this far, unless it ends on the anchor. */
    static final double MIN_SEGMENT_PROGRESS = 8.0D;
    /**
     * The bot counts as within reach of its segment goal (and reprobes) at or inside this squared
     * distance. Shared with {@link BotAutoReturnSunsetService} so the two cannot drift apart.
     */
    static final double SEGMENT_REFRESH_REACH_SQ = 6.0D * 6.0D;
    /**
     * A unified PARTIAL counts as progress once its end lies strictly outside the refresh radius
     * (squared distance above {@link #SEGMENT_REFRESH_REACH_SQ}). A looser floor (5 blocks, to match
     * UnifiedPathFinder.MIN_DIST_PATH) let a goal 5-6 blocks out count as within reach on the next
     * tick, so every tick ran another 200 ms probe on the server thread.
     */
    static final double MIN_PARTIAL_PROGRESS_SQ = SEGMENT_REFRESH_REACH_SQ;

    private SunsetSegmentPolicy() {
    }

    /**
     * Index of the path node to use as the next segment goal, or -1 for none.
     *
     * @param xs     path node X coordinates (same length as {@code ys} and {@code zs})
     * @param status the unified plan status, or null for the old finders
     */
    static int chooseSegmentGoal(int sx, int sy, int sz, int ax, int ay, int az,
                                 int[] xs, int[] ys, int[] zs, PlanStatus status) {
        if (xs == null || ys == null || zs == null) {
            return -1;
        }
        int n = Math.min(xs.length, Math.min(ys.length, zs.length));
        int best = -1;
        double accumulated = 0.0D;
        int px = sx;
        int py = sy;
        int pz = sz;
        for (int i = 0; i < n; i++) {
            if (xs[i] == sx && ys[i] == sy && zs[i] == sz) {
                continue;
            }
            accumulated += dist(px, py, pz, xs[i], ys[i], zs[i]);
            best = i;
            px = xs[i];
            py = ys[i];
            pz = zs[i];
            if (accumulated >= MAX_SEGMENT_PROGRESS) {
                break;
            }
        }
        if (best < 0) {
            return -1;
        }
        boolean atAnchor = xs[best] == ax && ys[best] == ay && zs[best] == az;
        if (atAnchor) {
            return best;
        }
        if (status == PlanStatus.PARTIAL) {
            return distSq(sx, sy, sz, xs[best], ys[best], zs[best]) > MIN_PARTIAL_PROGRESS_SQ ? best : -1;
        }
        double progress = dist(sx, sy, sz, xs[best], ys[best], zs[best]);
        return progress >= MIN_SEGMENT_PROGRESS ? best : -1;
    }

    /**
     * Whether this tick should run a segment refresh (a bounded path probe). Without a failed last
     * refresh: when there is no goal, the bot is within reach of it, or the replan tick is due. After a
     * failed refresh the goal is stale and being within reach of it stays true every tick, so only the
     * replan tick may trigger the next probe.
     */
    static boolean shouldReprobe(boolean hasGoal, boolean withinReachOfGoal, long serverTick,
                                 long nextRouteServerTick, boolean lastRefreshFailed) {
        boolean due = serverTick >= nextRouteServerTick;
        if (lastRefreshFailed) {
            return due;
        }
        return !hasGoal || withinReachOfGoal || due;
    }

    private static double dist(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Math.sqrt(distSq(x1, y1, z1, x2, y2, z2));
    }

    /** Same arithmetic as BlockPos.getSquaredDistance(BlockPos), which the reach check uses. */
    static double distSq(int x1, int y1, int z1, int x2, int y2, int z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }
}
