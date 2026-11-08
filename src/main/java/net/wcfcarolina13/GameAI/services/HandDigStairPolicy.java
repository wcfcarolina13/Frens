package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hand-dig stair escape for a bot in a pit with no placeable blocks (RALPH backlog P2, 1.1.236 /
 * 1.1.239 handoffs). {@link TerrainMovePolicy} and the surface-recovery pillar both need scaffold
 * blocks; with none, the ascent used to mine overhead and bare-jump in place, which never gains a
 * block. This policy cuts a staircase into the wall instead: per step it opens the bot's own
 * overhead cell (jump headroom) and the two cells in front above the tread (body and head at the
 * new stand), then the bot steps up onto the tread.
 *
 * <p>Coordinates are offsets from the bot's feet cell: {@code (0,0,0)} feet, {@code (0,1,0)} head.
 * For direction {@code d}: tread {@code d+(0,0,0)} must be solid, cut cells are {@code (0,2,0)},
 * {@code d+(0,2,0)}, {@code d+(0,1,0)}, mined in that order (top-down per column, so a gravel cell
 * under another cut cell never falls into an opened gap). The bot then stands at {@code d+(0,1,0)}.</p>
 *
 * <p>Refusals per direction: a cut cell that is lava/water, or a mined cell with a lava/water face
 * neighbour outside the open space (bot body + cut cells), exposes liquid; a falling block
 * (sand, gravel, concrete powder, ...) directly above a mined cell that is not itself cut would fall
 * into the bot's head space; a cut cell that is unbreakable (bedrock, protected) or slower than
 * {@link #MAX_CELL_BREAK_TICKS} with the bot's best tool; a tread that is not a usable step. A tread
 * is usable only when it collides and its collision top is a full cube face at y=1.0
 * ({@link Cell#fullTop()}): a fence or wall (1.5 high), a bottom slab or an open trapdoor collide
 * but cannot be stepped onto, so they refuse as {@link Refusal#NO_TREAD}. Lava in the tread cell
 * refuses as lava. The step count is capped at {@link #MAX_STEPS} per ascent.</p>
 *
 * <p>Direction choice: the first preferred direction that is valid keeps the staircase straight;
 * if it is not valid, the cheapest valid direction by total break ticks wins, ties in preference
 * order. When none is valid the most severe reason across directions is reported
 * (lava, water, falling block, unbreakable, too slow, no tread).</p>
 *
 * <p>Wide floors: when every direction refuses only for {@link Refusal#NO_TREAD} (the bot stands
 * on a floor wider than one cell, such as a 3x3 pocket or a ravine bottom, with nothing in front to
 * cut a step into), the decision is to <em>walk</em> one cell toward the first direction in
 * preference order whose feet and head cells are air and whose floor cell collides
 * ({@link Decision#walkToward()}), so the next step finds a wall. With no such direction (fenced in,
 * water or a drop on every side) it refuses with NO_TREAD as before.</p>
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class HandDigStairPolicy {

    /** Steps one ascent may hand-dig before giving up (a 12-deep pit is already a long dig). */
    public static final int MAX_STEPS = 12;

    /**
     * Per-cell on-ground break cap (10 s). Stone by hand (hardness 1.5 x5 = 7.5 s, 150 ticks) passes:
     * stuck in a pit is worse than a slow climb. Deepslate by hand (3.0 x5 = 15 s) and obsidian do
     * not. It stays under MiningTool's 12 s failsafe so a cell never times out mid-break.
     */
    public static final int MAX_CELL_BREAK_TICKS = 200;

    public enum Kind {
        /** No collision and no fluid (air, torch, grass): nothing to mine. */
        AIR,
        /** Solid, breakable. */
        SOLID,
        /** Solid gravity block (sand, gravel, concrete powder, ...). */
        FALLING,
        WATER,
        LAVA,
        /** Bedrock-class (hardness -1) or protected: cannot be broken. */
        UNBREAKABLE
    }

    /**
     * One cell: its kind, for SOLID/FALLING/UNBREAKABLE the on-ground break ticks with the bot's best
     * tool, and whether its collision shape's top is a full cube face at y=1.0 ({@code fullTop}: a
     * full block, a top slab, an upside-down stair, a closed top trapdoor; not a fence, wall, bottom
     * slab or open trapdoor). Only a {@code fullTop} collider is a usable tread. Non-colliding kinds
     * always carry {@code fullTop == false}.
     */
    public record Cell(Kind kind, int breakTicks, boolean fullTop) {
        public static Cell air() { return new Cell(Kind.AIR, 0, false); }
        /** A full-cube breakable block (stone, dirt, planks). */
        public static Cell solid(int ticks) { return new Cell(Kind.SOLID, ticks, true); }
        public static Cell solid(int ticks, boolean fullTop) { return new Cell(Kind.SOLID, ticks, fullTop); }
        /** A breakable collider without a full top face (fence, wall, bottom slab, open trapdoor). */
        public static Cell partial(int ticks) { return new Cell(Kind.SOLID, ticks, false); }
        public static Cell falling(int ticks) { return new Cell(Kind.FALLING, ticks, true); }
        public static Cell falling(int ticks, boolean fullTop) { return new Cell(Kind.FALLING, ticks, fullTop); }
        public static Cell water() { return new Cell(Kind.WATER, 0, false); }
        public static Cell lava() { return new Cell(Kind.LAVA, 0, false); }
        public static Cell unbreakable() { return new Cell(Kind.UNBREAKABLE, Integer.MAX_VALUE, true); }
        public static Cell unbreakable(boolean fullTop) { return new Cell(Kind.UNBREAKABLE, Integer.MAX_VALUE, fullTop); }

        boolean collides() {
            return kind == Kind.SOLID || kind == Kind.FALLING || kind == Kind.UNBREAKABLE;
        }

        /** A step the bot can stand on: collides and presents a full cube top face at y=1.0. */
        boolean usableTread() {
            return collides() && fullTop;
        }

        boolean liquid() {
            return kind == Kind.WATER || kind == Kind.LAVA;
        }
    }

    /** Read access to cells by offset from the bot's feet. */
    @FunctionalInterface
    public interface CellView {
        Cell at(int dx, int dy, int dz);
    }

    public enum Dir {
        NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);

        public final int dx;
        public final int dz;

        Dir(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    /** Ordered by severity: a lower ordinal wins when every direction refuses. */
    public enum Refusal {
        STEP_LIMIT, LAVA_EXPOSED, WATER_EXPOSED, FALLING_ABOVE, UNBREAKABLE, TOO_SLOW, NO_TREAD
    }

    public record Offset(int dx, int dy, int dz) {
        Offset plus(int ox, int oy, int oz) {
            return new Offset(dx + ox, dy + oy, dz + oz);
        }
    }

    /**
     * @param dir         stair direction
     * @param cellsToMine non-air cut cells in mining order (may be empty: a natural step)
     * @param breakTicks  sum of their break ticks
     * @param standAt     the bot's feet after the step
     */
    public record Plan(Dir dir, List<Offset> cellsToMine, long breakTicks, Offset standAt) {}

    /**
     * Exactly one of {@code plan} / {@code refusal} / {@code walkToward} is non-null. A
     * {@code walkToward} decision asks the caller to walk one cell in that direction (no height is
     * gained; it counts as a no-progress step) and decide again from there.
     */
    public record Decision(Plan plan, Refusal refusal, Dir walkToward, Map<Dir, Refusal> perDirection) {
        public boolean ok() {
            return plan != null;
        }

        public boolean walk() {
            return walkToward != null;
        }
    }

    private HandDigStairPolicy() {}

    /** Cut cells for {@code dir} in mining order: own overhead, front top, front head. */
    public static List<Offset> cutCells(Dir dir) {
        return List.of(new Offset(0, 2, 0), new Offset(dir.dx, 2, dir.dz), new Offset(dir.dx, 1, dir.dz));
    }

    /** Whether the hand-dig fallback applies: no scaffold blocks at all and steps left. */
    public static boolean applies(int scaffoldBlocks, int stepsTaken) {
        return scaffoldBlocks <= 0 && stepsTaken < MAX_STEPS;
    }

    /** A plan for {@code dir}, or the refusal; returns the refusal via {@code out[0]} when null. */
    static Plan planFor(CellView view, Dir dir, Refusal[] out) {
        Cell tread = view.at(dir.dx, 0, dir.dz);
        if (tread.kind() == Kind.LAVA) {
            out[0] = Refusal.LAVA_EXPOSED;
            return null;
        }
        if (!tread.usableTread()) {
            out[0] = Refusal.NO_TREAD;
            return null;
        }
        List<Offset> cut = cutCells(dir);
        Set<Offset> open = new LinkedHashSet<>();
        open.add(new Offset(0, 0, 0));
        open.add(new Offset(0, 1, 0));
        open.addAll(cut);

        List<Offset> toMine = new ArrayList<>();
        long ticks = 0L;
        Refusal worst = null;
        for (Offset c : cut) {
            Cell cell = view.at(c.dx(), c.dy(), c.dz());
            switch (cell.kind()) {
                case AIR -> { continue; }
                case LAVA -> worst = worse(worst, Refusal.LAVA_EXPOSED);
                case WATER -> worst = worse(worst, Refusal.WATER_EXPOSED);
                case UNBREAKABLE -> worst = worse(worst, Refusal.UNBREAKABLE);
                case SOLID, FALLING -> {
                    if (cell.breakTicks() < 0 || cell.breakTicks() > MAX_CELL_BREAK_TICKS) {
                        worst = worse(worst, Refusal.TOO_SLOW);
                    }
                }
            }
            if (cell.liquid()) {
                continue;
            }
            toMine.add(c);
            ticks += Math.max(0, cell.breakTicks());
        }
        for (Offset c : toMine) {
            int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] f : faces) {
                Offset n = c.plus(f[0], f[1], f[2]);
                if (open.contains(n)) {
                    continue;
                }
                Cell nc = view.at(n.dx(), n.dy(), n.dz());
                if (nc.kind() == Kind.LAVA) {
                    worst = worse(worst, Refusal.LAVA_EXPOSED);
                } else if (nc.kind() == Kind.WATER) {
                    worst = worse(worst, Refusal.WATER_EXPOSED);
                } else if (f[1] == 1 && nc.kind() == Kind.FALLING) {
                    worst = worse(worst, Refusal.FALLING_ABOVE);
                }
            }
        }
        if (worst != null) {
            out[0] = worst;
            return null;
        }
        return new Plan(dir, List.copyOf(toMine), ticks, new Offset(dir.dx, 1, dir.dz));
    }

    /**
     * @param view       cells around the bot
     * @param preference directions in preference order (the ascent direction first); directions not
     *                   listed are tried after, in {@link Dir} order
     * @param stepsTaken hand-dig steps already taken in this ascent
     */
    /**
     * After a hand-dig ascent reached open sky: the side to take one more step on, or null. Bradley's
     * ruling (2026-11-08): when the next cell is a 1-high step onto open ground, the bot takes it and
     * ends level with the ground, so it does not stop in a notch one block below grade beside its
     * pit; it never digs into a hillside. So a side counts only when the block at the bot's feet
     * level there is a usable tread and the three cells above it (feet, head, one more) are already
     * air: no block is mined. The caller also requires open sky over the feet cell. The preferred
     * direction (the stair's) is tried first, then the rest in a fixed order.
     */
    public static Dir stepOntoGround(CellView view, List<Dir> preference) {
        if (view == null) {
            return null;
        }
        java.util.List<Dir> order = new java.util.ArrayList<>();
        if (preference != null) {
            for (Dir d : preference) {
                if (d != null && !order.contains(d)) order.add(d);
            }
        }
        for (Dir d : Dir.values()) {
            if (!order.contains(d)) order.add(d);
        }
        for (Dir d : order) {
            Cell tread = view.at(d.dx, 0, d.dz);
            if (tread == null || !tread.usableTread()) {
                continue;
            }
            boolean clear = true;
            for (int dy = 1; dy <= 3; dy++) {
                Cell c = view.at(d.dx, dy, d.dz);
                if (c == null || c.kind() != Kind.AIR) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return d;
            }
        }
        return null;
    }

    public static Decision decide(CellView view, List<Dir> preference, int stepsTaken) {
        Map<Dir, Refusal> per = new EnumMap<>(Dir.class);
        if (stepsTaken >= MAX_STEPS) {
            return new Decision(null, Refusal.STEP_LIMIT, null, per);
        }
        List<Dir> order = new ArrayList<>();
        if (preference != null) {
            for (Dir d : preference) {
                if (d != null && !order.contains(d)) {
                    order.add(d);
                }
            }
        }
        for (Dir d : Dir.values()) {
            if (!order.contains(d)) {
                order.add(d);
            }
        }
        Plan best = null;
        for (int i = 0; i < order.size(); i++) {
            Dir d = order.get(i);
            Refusal[] out = new Refusal[1];
            Plan p = planFor(view, d, out);
            if (p == null) {
                per.put(d, out[0]);
                continue;
            }
            if (i == 0) {
                // The preferred direction is valid: keep the staircase straight.
                return new Decision(p, null, null, per);
            }
            if (best == null || p.breakTicks() < best.breakTicks()) {
                best = p;
            }
        }
        if (best != null) {
            return new Decision(best, null, null, per);
        }
        Refusal worst = null;
        for (Refusal r : per.values()) {
            worst = worse(worst, r);
        }
        if (worst == Refusal.NO_TREAD) {
            // NO_TREAD is the least severe refusal, so every direction refused only for it: a floor
            // wider than one cell. Walk toward a wall rather than fail the ascent.
            Dir walk = walkDirection(view, order);
            if (walk != null) {
                return new Decision(null, null, walk, per);
            }
        }
        return new Decision(null, worst == null ? Refusal.NO_TREAD : worst, null, per);
    }

    /**
     * The first direction in {@code order} the bot can walk one cell into: feet and head cells air
     * (no fence, no water) and a colliding floor under the destination (no drop, no liquid), or
     * {@code null} when there is none.
     */
    static Dir walkDirection(CellView view, List<Dir> order) {
        for (Dir d : order) {
            if (view.at(d.dx, 0, d.dz).kind() == Kind.AIR
                    && view.at(d.dx, 1, d.dz).kind() == Kind.AIR
                    && view.at(d.dx, -1, d.dz).collides()) {
                return d;
            }
        }
        return null;
    }

    private static Refusal worse(Refusal a, Refusal b) {
        if (a == null) {
            return b;
        }
        return b.ordinal() < a.ordinal() ? b : a;
    }
}
