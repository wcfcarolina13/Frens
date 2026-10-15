package net.wcfcarolina13.PathFinding.unified;

import static net.wcfcarolina13.PathFinding.unified.CellProps.*;

import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Proves a plan executable on a mutable copy of the terrain (design section 2.4): each move must
 * start where the previous one ended, re-evaluate to the same step under {@link MoveCosts} before its
 * mutations, mine only breakable unprotected cells and place only on placeable unprotected cells, and
 * after its mutations pass an independent physical check of its kind (landing standing, lanes and
 * corridors clear, swim limits). Reports the first move that fails.
 *
 * <p>Production uses {@link #simulate(SearchContext, PathPlan)} for the PathCompare {@code exec}
 * status: it wraps the context's terrain in an {@link Overlay}, so nothing in the world changes.</p>
 */
public final class MoveSimulator {

    public record Result(boolean executable, int failedIndex, String reason) {
        public static final Result OK = new Result(true, -1, "ok");

        static Result fail(int index, String reason) {
            return new Result(false, index, reason);
        }
    }

    private MoveSimulator() {
    }

    /** Simulates on a read-only overlay of {@code ctx.terrain()}. */
    public static Result simulate(SearchContext ctx, PathPlan plan) {
        return simulate(ctx, plan.moves(), new Overlay(ctx.terrain()));
    }

    /** Simulates on {@code world}, which is mutated (pass a copy). {@code ctx.terrain()} is not read. */
    public static Result simulate(SearchContext ctx, List<PlannedMove> moves, MutableTerrainView world) {
        int x = ctx.startX();
        int y = ctx.startY();
        int z = ctx.startZ();
        int scaffold = ctx.scaffoldCount();
        int swimRun = 0;
        for (int i = 0; i < moves.size(); i++) {
            PlannedMove m = moves.get(i);
            if (m.fromX() != x || m.fromY() != y || m.fromZ() != z) {
                return Result.fail(i, "discontinuous: at " + x + "," + y + "," + z + " but move starts at "
                        + m.fromX() + "," + m.fromY() + "," + m.fromZ());
            }
            PlannedMove again = MoveCosts.evaluate(ctx, world, x, y, z, m.kind(), m.dx(), m.dz(), scaffold, swimRun);
            if (again == null || !again.sameStep(m)) {
                return Result.fail(i, "re-evaluation differs: planned " + m + " now " + again);
            }
            for (long cell : m.toBreak()) {
                int cx = CellPos.x(cell);
                int cy = CellPos.y(cell);
                int cz = CellPos.z(cell);
                int p = world.props(cx, cy, cz);
                if (!loaded(p) || passable(p) || liquid(p) || has(p, PROTECTED | NEVER_BREAK | LOCKED_DOOR)) {
                    return Result.fail(i, "cannot break " + CellPos.format(cell) + " (" + describe(p) + ")");
                }
                if (world.breakTicks(cx, cy, cz) > ctx.maxBreakTicks()) {
                    return Result.fail(i, "too slow to break " + CellPos.format(cell));
                }
            }
            for (long cell : m.toPlace()) {
                int p = world.props(CellPos.x(cell), CellPos.y(cell), CellPos.z(cell));
                if (scaffold <= 0 || !has(p, PLACEABLE) || has(p, PROTECTED)) {
                    return Result.fail(i, "cannot place at " + CellPos.format(cell) + " (" + describe(p) + ")");
                }
            }
            for (long cell : m.toBreak()) {
                world.removeBlock(CellPos.x(cell), CellPos.y(cell), CellPos.z(cell));
            }
            for (long cell : m.toPlace()) {
                world.placeBlock(CellPos.x(cell), CellPos.y(cell), CellPos.z(cell));
                scaffold--;
            }
            String physical = physicalCheck(ctx, world, m, swimRun);
            if (physical != null) {
                return Result.fail(i, physical + ": " + m);
            }
            x = m.toX();
            y = m.toY();
            z = m.toZ();
            swimRun = m.swimRunAfter();
            if (m.scaffoldLeftAfter() != scaffold) {
                return Result.fail(i, "scaffold count " + m.scaffoldLeftAfter() + " but simulated " + scaffold);
            }
        }
        return Result.OK;
    }

    // Independent of MoveCosts on purpose: plain cell reads after the move's own mutations.

    private static boolean standing(TerrainView w, int x, int y, int z) {
        int body = w.props(x, y, z);
        boolean bodyOk = passable(body) || (loaded(body) && has(body, WATER_WADE));
        return floor(w.props(x, y - 1, z)) && bodyOk && passable(w.props(x, y + 1, z));
    }

    private static boolean swimCell(TerrainView w, int x, int y, int z) {
        return has(w.props(x, y, z), WATER_DEEP) && passable(w.props(x, y + 1, z));
    }

    private static String physicalCheck(SearchContext ctx, TerrainView w, PlannedMove m, int swimRunBefore) {
        int fx = m.fromX();
        int fy = m.fromY();
        int fz = m.fromZ();
        int tx = m.toX();
        int ty = m.toY();
        int tz = m.toZ();
        int adx = Math.abs(tx - fx);
        int adz = Math.abs(tz - fz);
        int dy = ty - fy;
        int xz = adx + adz;
        int landingBody = w.props(tx, ty, tz);
        if (has(landingBody, HAZARD | LAVA) || has(w.props(tx, ty - 1, tz), HAZARD | LAVA)) {
            return "landing on hazard or lava";
        }
        boolean landed = standing(w, tx, ty, tz);
        switch (m.kind()) {
            case TRAVERSE -> {
                if (xz != 1 || dy != 0) return "bad traverse geometry";
                if (!landed) return "traverse landing not standing";
            }
            case DIAGONAL -> {
                if (adx != 1 || adz != 1 || dy != 0) return "bad diagonal geometry";
                if (!passable(w.props(tx, fy, fz)) || !passable(w.props(tx, fy + 1, fz))
                        || !passable(w.props(fx, fy, tz)) || !passable(w.props(fx, fy + 1, tz))) {
                    return "diagonal corridor blocked";
                }
                if (!landed || !passable(landingBody)) return "diagonal landing not dry standing";
            }
            case ASCEND -> {
                if (xz != 1 || dy != 1) return "bad ascend geometry";
                if (!passable(w.props(fx, fy + 2, fz))) return "no headroom to jump";
                if (!landed) return "ascend landing not standing";
            }
            case DESCEND -> {
                if (xz != 1 || dy != -1) return "bad descend geometry";
                if (!passable(w.props(tx, fy, tz)) || !passable(w.props(tx, fy + 1, tz))) return "descend lane blocked";
                if (!landed) return "descend landing not standing";
            }
            case FALL -> {
                if (xz != 1 || dy > -2 || dy < -ctx.maxFall()) return "bad fall geometry";
                for (int yy = fy + 1; yy > ty; yy--) {
                    if (!passable(w.props(tx, yy, tz))) return "fall column blocked at y=" + yy;
                }
                if (!landed) return "fall landing not standing";
            }
            case ASCEND_PLACE -> {
                if (xz != 1 || dy != 2) return "bad ascend-place geometry";
                if (!floor(w.props(fx, fy, fz))) return "placed block missing";
                if (!passable(w.props(fx, fy + 1, fz)) || !passable(w.props(fx, fy + 2, fz))
                        || !passable(w.props(fx, fy + 3, fz))) {
                    return "no room to pillar";
                }
                if (!landed || !passable(landingBody)) return "ascend-place landing not dry standing";
            }
            case SWIM -> {
                if (xz != 1 || dy < -1 || dy > 1) return "bad swim geometry";
                if (!ctx.allowSwim()) return "swim not allowed";
                if (m.swimRunAfter() > ctx.maxSwimRun()) return "swim run over " + ctx.maxSwimRun();
                boolean inWater = swimCell(w, tx, ty, tz);
                if (!inWater && !landed) return "swim landing neither water nor standing";
                if (inWater && m.swimRunAfter() != swimRunBefore + 1 && !(swimRunBefore == 0 && m.swimRunAfter() == 1)) {
                    return "swim run not counted";
                }
                if (dy == 1 && !passable(w.props(fx, fy + 2, fz))) return "no headroom to climb out";
            }
            case DOWNWARD -> {
                if (xz != 0 || dy != -1) return "bad downward geometry";
                if (!landed) return "downward landing not standing";
            }
        }
        return null;
    }

    /**
     * A mutable overlay on any {@link TerrainView}: removed cells read as air, placed cells as a solid
     * floor. Derived bits of neighbours (WATER_WADE below, PLACEABLE above) are not recomputed, which
     * only makes the simulation stricter.
     */
    public static final class Overlay implements MutableTerrainView {
        static final int AIR = LOADED | PASSABLE_BODY;
        static final int SCAFFOLD = LOADED | SOLID_FLOOR;
        static final int SCAFFOLD_BREAK_TICKS = 15;

        private final TerrainView base;
        private final Long2IntOpenHashMap overrides = new Long2IntOpenHashMap();

        public Overlay(TerrainView base) {
            this.base = base;
            overrides.defaultReturnValue(Integer.MIN_VALUE);
        }

        @Override
        public int props(int x, int y, int z) {
            int o = overrides.get(CellPos.pack(x, y, z));
            return o == Integer.MIN_VALUE ? base.props(x, y, z) : o;
        }

        @Override
        public int breakTicks(int x, int y, int z) {
            int o = overrides.get(CellPos.pack(x, y, z));
            if (o == Integer.MIN_VALUE) {
                return base.breakTicks(x, y, z);
            }
            return o == SCAFFOLD ? SCAFFOLD_BREAK_TICKS : (int) ActionCosts.COST_INF;
        }

        @Override
        public int minY() {
            return base.minY();
        }

        @Override
        public int maxY() {
            return base.maxY();
        }

        @Override
        public void removeBlock(int x, int y, int z) {
            overrides.put(CellPos.pack(x, y, z), AIR);
        }

        @Override
        public void placeBlock(int x, int y, int z) {
            overrides.put(CellPos.pack(x, y, z), SCAFFOLD);
        }
    }
}
