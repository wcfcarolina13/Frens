package net.wcfcarolina13.PathFinding.unified;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The design's section 10 scenarios as runnable cases, shared by {@code UnifiedFinderBenchmarkTest}
 * (thresholds) and {@link BenchmarkMain} (the printed table).
 */
final class BenchmarkScenarios {
    /** Runs per scenario; the first is discarded for JIT. */
    static final int RUNS = 5;

    record Case(String name, VoxelGrid grid, SearchContext ctx) {}

    record Row(String name, PathPlan plan, double optimal, double[] millis, MoveSimulator.Result exec) {
        double ratio() {
            return plan.status() == PlanStatus.FULL && optimal > 0 && optimal < ActionCosts.COST_INF
                    ? plan.totalTicks() / optimal : Double.NaN;
        }

        double p50() {
            return percentile(millis, 0.50);
        }

        double p95() {
            return percentile(millis, 0.95);
        }

        Set<MoveKind> kinds() {
            Set<MoveKind> kinds = EnumSet.noneOf(MoveKind.class);
            for (PlannedMove m : plan.moves()) {
                kinds.add(m.kind());
            }
            return kinds;
        }
    }

    private BenchmarkScenarios() {
    }

    static Case load(String file) {
        GridLoader.GridScenario s = GridLoader.load(file);
        return new Case(file, s.grid(), s.builder().build());
    }

    /** gully variant 1: swimming allowed, the file as is. */
    static Case gullySwim() {
        return renamed(load("gully_ledge"), "gully_swim");
    }

    /** gully variant 2: no swimming, hand tool, no scaffold. */
    static Case gullyNoSwim() {
        Case c = load("gully_ledge");
        return new Case("gully_no_swim", c.grid(), c.ctx().toBuilder().allowSwim(false).scaffoldCount(0).build());
    }

    /**
     * gully variant 3: no swimming, 2 dirt, plus an ASSUMED unprotected stone causeway east of the
     * ledge (x23..31, z126, topped at y64), a 2-rise right next to the ledge. Stone by hand cannot be
     * mined, so the only way up is a 1-block dirt stair.
     */
    static Case gullyStair() {
        GridLoader.GridScenario s = GridLoader.load("gully_ledge");
        VoxelGrid g = s.grid().copy();
        g.fill(23, 56, 126, 31, 64, 126, '#');
        SearchContext ctx = s.builder(g, 31, 65, 126).allowSwim(false).scaffoldCount(2).build();
        return new Case("gully_stair", g, ctx);
    }

    private static Case renamed(Case c, String name) {
        return new Case(name, c.grid(), c.ctx());
    }

    static List<Case> all() {
        List<Case> out = new ArrayList<>();
        for (String f : new String[]{"open_field", "terraces", "pure_stair", "cliff_side_slope"}) {
            out.add(load(f));
        }
        out.add(gullySwim());
        out.add(gullyNoSwim());
        out.add(gullyStair());
        for (String f : new String[]{"sand_wall_break", "cave_pit", "trees_2x2", "leaf_canopy", "campfire", "lava",
                "protected_zone", "partial_budget", "boxed_in"}) {
            out.add(load(f));
        }
        return out;
    }

    static Row run(Case c) {
        return run(c, RUNS, System::currentTimeMillis);
    }

    static Row run(Case c, int runs, LongSupplier clock) {
        PathPlan plan = null;
        double[] millis = new double[Math.max(1, runs - 1)];
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            plan = UnifiedPathFinder.search(c.ctx(), clock);
            long t1 = System.nanoTime();
            if (i > 0) {
                millis[i - 1] = (t1 - t0) / 1e6;
            }
        }
        if (runs == 1) {
            millis[0] = plan.elapsedMs();
        }
        double optimal = c.ctx().failureMs() < 50 ? Double.NaN : DijkstraReference.optimal(c.ctx());
        MoveSimulator.Result exec = MoveSimulator.simulate(c.ctx(), plan.moves(), c.grid().copy());
        return new Row(c.name(), plan, optimal, millis, exec);
    }

    static double percentile(double[] values, double q) {
        double[] v = values.clone();
        Arrays.sort(v);
        int idx = (int) Math.ceil(q * v.length) - 1;
        return v[Math.max(0, Math.min(v.length - 1, idx))];
    }

    static String header() {
        return String.format(Locale.ROOT, "%-17s %-8s %-18s %5s %8s %8s %6s %6s %7s %7s %-5s %s",
                "scenario", "status", "reason", "moves", "ticks", "dijkstra", "ratio", "nodes", "p50ms", "p95ms",
                "exec", "kinds");
    }

    static String format(Row r) {
        PathPlan p = r.plan();
        String ticks = p.status() == PlanStatus.NO_PATH ? "-" : String.format(Locale.ROOT, "%.1f", p.totalTicks());
        String opt = Double.isNaN(r.optimal()) ? "-"
                : r.optimal() >= ActionCosts.COST_INF ? "inf" : String.format(Locale.ROOT, "%.1f", r.optimal());
        String ratio = Double.isNaN(r.ratio()) ? "-" : String.format(Locale.ROOT, "%.4f", r.ratio());
        StringBuilder kinds = new StringBuilder();
        for (MoveKind k : r.kinds()) {
            kinds.append(kinds.length() == 0 ? "" : ",").append(k.name().toLowerCase(Locale.ROOT));
        }
        return String.format(Locale.ROOT, "%-17s %-8s %-18s %5d %8s %8s %6s %6d %7.3f %7.3f %-5s %s",
                r.name(), p.status(), p.reason(), p.moves().size(), ticks, opt, ratio, p.nodesExpanded(),
                r.p50(), r.p95(), r.exec().executable() ? "ok" : "FAIL", kinds);
    }
}
