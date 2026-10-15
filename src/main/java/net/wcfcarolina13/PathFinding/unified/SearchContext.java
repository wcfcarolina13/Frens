package net.wcfcarolina13.PathFinding.unified;

import java.util.Set;
import java.util.function.LongToDoubleFunction;

import net.wcfcarolina13.GameAI.services.ObstructionMinePolicy;

/**
 * Everything one search needs, snapshotted before it starts (tool speeds, scaffold count, sprint and
 * the break/place gates are never read live during a search).
 *
 * @param canSprint          food > 6 (Baritone)
 * @param allowBreak         isBotExecutingTask && !constructionProtection && !OBSTRUCTION_MINE_DISABLED
 * @param allowPlace         same gates; the owner's territory check is in the cell's PLACEABLE bit
 * @param allowSwim          default true for walkTo, false for probes
 * @param allowDownward      default false (ruling): DOWNWARD costs COST_INF unless set
 * @param scaffoldCount      plain DIRT in the hotbar (countTerrainStairBlocks)
 * @param maxFall            3
 * @param maxBreakTicks      ObstructionMinePolicy.MAX_GROUND_BREAK_TICKS = 100
 * @param maxSwimRun         10 cells, at most 15 (4 bits of the node key)
 * @param failureMs          0 or less: none, {@code primaryMs} is the whole budget (server thread)
 * @param hazardPenaltyTicks NavHazardCache.penaltyFor * STEP_TICKS by packed cell, snapshotted reads
 * @param anchorBonusTicks   PassageAnchorService.bonusFor * STEP_TICKS by packed cell
 * @param forbiddenFirstHops packed cells a move out of the start may not land on (FailedRoutePolicy first hops)
 */
public record SearchContext(
        TerrainView terrain,
        int startX, int startY, int startZ,
        int goalX, int goalY, int goalZ,
        boolean canSprint,
        boolean allowBreak,
        boolean allowPlace,
        boolean allowSwim,
        boolean allowDownward,
        int scaffoldCount,
        int maxFall,
        int maxBreakTicks,
        int maxSwimRun,
        long primaryMs, long failureMs, int nodeCap,
        SearchBox box,
        LongToDoubleFunction hazardPenaltyTicks,
        LongToDoubleFunction anchorBonusTicks,
        Set<Long> forbiddenFirstHops,
        long planId) {

    public static final LongToDoubleFunction ZERO = cell -> 0.0;
    public static final int DEFAULT_MAX_FALL = 3;
    public static final int DEFAULT_MAX_SWIM_RUN = 10;
    public static final long DEFAULT_PRIMARY_MS = 500;
    public static final long DEFAULT_FAILURE_MS = 2000;

    public SearchContext {
        if (terrain == null) {
            throw new IllegalArgumentException("terrain");
        }
        if (maxSwimRun < 0 || maxSwimRun > 15) {
            throw new IllegalArgumentException("maxSwimRun must fit 4 bits: " + maxSwimRun);
        }
        if (maxFall < 1 || maxFall >= ActionCosts.FALL_TABLE_SIZE) {
            throw new IllegalArgumentException("maxFall " + maxFall);
        }
        if (box == null) {
            box = SearchBox.around(startX, startY, startZ, goalX, goalY, goalZ, terrain.minY(), terrain.maxY());
        }
        hazardPenaltyTicks = hazardPenaltyTicks == null ? ZERO : hazardPenaltyTicks;
        anchorBonusTicks = anchorBonusTicks == null ? ZERO : anchorBonusTicks;
        forbiddenFirstHops = forbiddenFirstHops == null ? Set.of() : Set.copyOf(forbiddenFirstHops);
        scaffoldCount = Math.max(0, scaffoldCount);
    }

    /** walkTo node cap: {@code min(8000, max(1000, 4 * dist^2))}, dist the 3D Euclidean start-goal distance. */
    public static int defaultNodeCap(int sx, int sy, int sz, int gx, int gy, int gz) {
        double dx = gx - sx;
        double dy = gy - sy;
        double dz = gz - sz;
        double d2 = dx * dx + dy * dy + dz * dz;
        return (int) Math.min(8000, Math.max(1000, 4 * d2));
    }

    public static Builder builder(TerrainView terrain, int sx, int sy, int sz, int gx, int gy, int gz) {
        return new Builder(terrain, sx, sy, sz, gx, gy, gz);
    }

    public Builder toBuilder() {
        Builder b = new Builder(terrain, startX, startY, startZ, goalX, goalY, goalZ);
        b.canSprint = canSprint;
        b.allowBreak = allowBreak;
        b.allowPlace = allowPlace;
        b.allowSwim = allowSwim;
        b.allowDownward = allowDownward;
        b.scaffoldCount = scaffoldCount;
        b.maxFall = maxFall;
        b.maxBreakTicks = maxBreakTicks;
        b.maxSwimRun = maxSwimRun;
        b.primaryMs = primaryMs;
        b.failureMs = failureMs;
        b.nodeCap = nodeCap;
        b.box = box;
        b.hazardPenaltyTicks = hazardPenaltyTicks;
        b.anchorBonusTicks = anchorBonusTicks;
        b.forbiddenFirstHops = forbiddenFirstHops;
        b.planId = planId;
        return b;
    }

    /** Defaults: the walkTo row of the design's budget table, no break, no place, swim on, sprint on. */
    public static final class Builder {
        private TerrainView terrain;
        private final int sx;
        private final int sy;
        private final int sz;
        private final int gx;
        private final int gy;
        private final int gz;
        private boolean canSprint = true;
        private boolean allowBreak;
        private boolean allowPlace;
        private boolean allowSwim = true;
        private boolean allowDownward;
        private int scaffoldCount;
        private int maxFall = DEFAULT_MAX_FALL;
        private int maxBreakTicks = ObstructionMinePolicy.MAX_GROUND_BREAK_TICKS;
        private int maxSwimRun = DEFAULT_MAX_SWIM_RUN;
        private long primaryMs = DEFAULT_PRIMARY_MS;
        private long failureMs = DEFAULT_FAILURE_MS;
        private int nodeCap = -1;
        private SearchBox box;
        private LongToDoubleFunction hazardPenaltyTicks = ZERO;
        private LongToDoubleFunction anchorBonusTicks = ZERO;
        private Set<Long> forbiddenFirstHops = Set.of();
        private long planId;

        private Builder(TerrainView terrain, int sx, int sy, int sz, int gx, int gy, int gz) {
            this.terrain = terrain;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
        }

        public Builder terrain(TerrainView v) { this.terrain = v; return this; }
        public Builder canSprint(boolean v) { this.canSprint = v; return this; }
        public Builder allowBreak(boolean v) { this.allowBreak = v; return this; }
        public Builder allowPlace(boolean v) { this.allowPlace = v; return this; }
        public Builder allowSwim(boolean v) { this.allowSwim = v; return this; }
        public Builder allowDownward(boolean v) { this.allowDownward = v; return this; }
        public Builder scaffoldCount(int v) { this.scaffoldCount = v; return this; }
        public Builder maxFall(int v) { this.maxFall = v; return this; }
        public Builder maxBreakTicks(int v) { this.maxBreakTicks = v; return this; }
        public Builder maxSwimRun(int v) { this.maxSwimRun = v; return this; }
        public Builder primaryMs(long v) { this.primaryMs = v; return this; }
        public Builder failureMs(long v) { this.failureMs = v; return this; }
        public Builder nodeCap(int v) { this.nodeCap = v; return this; }
        public Builder box(SearchBox v) { this.box = v; return this; }
        public Builder hazardPenaltyTicks(LongToDoubleFunction v) { this.hazardPenaltyTicks = v; return this; }
        public Builder anchorBonusTicks(LongToDoubleFunction v) { this.anchorBonusTicks = v; return this; }
        public Builder forbiddenFirstHops(Set<Long> v) { this.forbiddenFirstHops = v; return this; }
        public Builder planId(long v) { this.planId = v; return this; }

        public SearchContext build() {
            int cap = nodeCap > 0 ? nodeCap : defaultNodeCap(sx, sy, sz, gx, gy, gz);
            return new SearchContext(terrain, sx, sy, sz, gx, gy, gz, canSprint, allowBreak, allowPlace, allowSwim,
                    allowDownward, scaffoldCount, maxFall, maxBreakTicks, maxSwimRun, primaryMs, failureMs, cap, box,
                    hazardPenaltyTicks, anchorBonusTicks, forbiddenFirstHops, planId);
        }
    }
}
