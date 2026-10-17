package net.wcfcarolina13.PathFinding.unified;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Optimal reference for the benchmark: plain Dijkstra over the same graph as the finder (same
 * {@link UnifiedPathFinder#expand}, same {@link MoveCosts}, same state space of position plus swim
 * run, scaffold carried along the best path), no heuristic, no budget. Lazy-deletion
 * {@link PriorityQueue}, independent of {@link BinaryHeapOpenSet}.
 */
public final class DijkstraReference {
    private DijkstraReference() {
    }

    private record Entry(double g, long key, int x, int y, int z, int swimRun, int scaffold) {}

    /** Optimal ticks to the (snapped) goal, or {@link ActionCosts#COST_INF} when unreachable. */
    public static double optimal(SearchContext ctx) {
        PathPlan p = optimalPlan(ctx);
        return p == null ? ActionCosts.COST_INF : p.totalTicks();
    }

    /**
     * The optimal path itself as a FULL plan, or null when unreachable. Raw-priced like the graph it
     * searches, so it may be one {@link UnifiedPathFinder#truncateToExecutable} cuts (a move an earlier
     * move of the path undoes); the finder refuses those since 1.1.267.
     */
    public static PathPlan optimalPlan(SearchContext ctx) {
        int[] goal = UnifiedPathFinder.snapGoal(ctx);
        if (goal == null) {
            return null;
        }
        Long2ObjectOpenHashMap<PlannedMove> via = new Long2ObjectOpenHashMap<>();
        Long2LongOpenHashMap from = new Long2LongOpenHashMap();
        SearchBox box = ctx.box();
        TerrainView t = ctx.terrain();
        Long2DoubleOpenHashMap dist = new Long2DoubleOpenHashMap();
        dist.defaultReturnValue(Double.MAX_VALUE);
        Long2IntOpenHashMap done = new Long2IntOpenHashMap();
        PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.g, b.g));
        long startKey = box.key(ctx.startX(), ctx.startY(), ctx.startZ(), 0);
        dist.put(startKey, 0);
        open.add(new Entry(0, startKey, ctx.startX(), ctx.startY(), ctx.startZ(), 0, ctx.scaffoldCount()));
        MoveCosts.Refusals r = new MoveCosts.Refusals();
        List<PlannedMove> succ = new ArrayList<>();
        while (!open.isEmpty()) {
            Entry e = open.poll();
            if (done.containsKey(e.key) || e.g > dist.get(e.key)) {
                continue;
            }
            done.put(e.key, 1);
            if (e.x == goal[0] && e.y == goal[1] && e.z == goal[2]) {
                List<PlannedMove> moves = new ArrayList<>();
                for (long k = e.key; k != startKey; k = from.get(k)) {
                    moves.add(via.get(k));
                }
                java.util.Collections.reverse(moves);
                return new PathPlan(ctx.planId(), PlanStatus.FULL, NoPathReason.NONE, 0, moves, e.g, done.size(), 0);
            }
            succ.clear();
            UnifiedPathFinder.expand(ctx, t, e.x, e.y, e.z, e.scaffold, e.swimRun, r, succ);
            for (PlannedMove m : succ) {
                if (m.ticks() >= ActionCosts.COST_INF) {
                    continue;
                }
                long k = box.key(m.toX(), m.toY(), m.toZ(), m.swimRunAfter());
                double ng = e.g + m.ticks();
                if (ng < dist.get(k) - UnifiedPathFinder.MIN_IMPROVEMENT && !done.containsKey(k)) {
                    dist.put(k, ng);
                    via.put(k, m);
                    from.put(k, e.key);
                    open.add(new Entry(ng, k, m.toX(), m.toY(), m.toZ(), m.swimRunAfter(), m.scaffoldLeftAfter()));
                }
            }
        }
        return null;
    }
}
