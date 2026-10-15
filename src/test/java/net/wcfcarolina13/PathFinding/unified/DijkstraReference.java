package net.wcfcarolina13.PathFinding.unified;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

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
        int[] goal = UnifiedPathFinder.snapGoal(ctx);
        if (goal == null) {
            return ActionCosts.COST_INF;
        }
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
                return e.g;
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
                    open.add(new Entry(ng, k, m.toX(), m.toY(), m.toZ(), m.swimRunAfter(), m.scaffoldLeftAfter()));
                }
            }
        }
        return ActionCosts.COST_INF;
    }
}
