package net.wcfcarolina13.GameAI;

import java.util.List;

/**
 * Pure rules for a teleport-style step that stays inside the bot's current feet cell
 * ({@code BotActions.moveRelative}). Such a step used to be accepted unchecked, so a 0.45
 * step from x=5.5 toward stone at x=6 put the hitbox 0.25 into the stone and the bot
 * suffocated. A same-cell step is now allowed only when it ends clear of solid neighbours
 * or with less overlap than it started with (a wedged bot can still work its way out), and
 * a step that would clip is shortened so the bot stops flush with the wall.
 * <p>
 * Boxes are {@code double[6]} = {minX, minY, minZ, maxX, maxY, maxZ}. Only X and Z move.
 */
public final class SubCellSnapPolicy {

    /** Overlap at or below this counts as touching, not penetrating. */
    public static final double EPS = 1.0E-7;

    private SubCellSnapPolicy() {
    }

    public static double[] box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** The box moved by (dx, 0, dz). */
    public static double[] offset(double[] b, double dx, double dz) {
        return box(b[0] + dx, b[1], b[2] + dz, b[3] + dx, b[4], b[5] + dz);
    }

    /** Overlap of two boxes along one axis (0 = X, 1 = Y, 2 = Z); 0 when they only touch. */
    static double overlap(double[] a, double[] b, int axis) {
        return Math.max(0.0, Math.min(a[axis + 3], b[axis + 3]) - Math.max(a[axis], b[axis]));
    }

    /** Sum of the volumes by which {@code bot} sits inside the solid boxes. */
    public static double penetration(double[] bot, List<double[]> solids) {
        double total = 0.0;
        for (double[] s : solids) {
            double ox = overlap(bot, s, 0);
            double oy = overlap(bot, s, 1);
            double oz = overlap(bot, s, 2);
            if (ox > EPS && oy > EPS && oz > EPS) {
                total += ox * oy * oz;
            }
        }
        return total;
    }

    /** Clear target, or one that overlaps solids less than the start did. */
    public static boolean allowSameCellMove(double[] from, double[] to, List<double[]> solids) {
        double after = penetration(to, solids);
        return after <= EPS || after < penetration(from, solids);
    }

    /**
     * Shortens one axis of a move so a box of half-width {@code half} centred at {@code from}
     * stops flush against a solid spanning [solidMin, solidMax] on that axis. Returns
     * {@code to} when the move does not reach the solid, the flush centre when it lies
     * strictly between {@code from} and {@code to}, and NaN when the bot is already flush or
     * already inside the solid on that side (the clamp would be no move or a reversal).
     */
    public static double clampAxis(double from, double to, double half, double solidMin, double solidMax) {
        double delta = to - from;
        if (Math.abs(delta) <= EPS) {
            return to;
        }
        if (delta > 0) {
            if (to + half <= solidMin + EPS || from - half >= solidMax - EPS) {
                return to; // does not reach it, or the solid is behind the bot
            }
            double flush = solidMin - half;
            return flush > from + EPS && flush < to ? flush : Double.NaN;
        }
        if (to - half >= solidMax - EPS || from + half <= solidMin + EPS) {
            return to;
        }
        double flush = solidMax + half;
        return flush < from - EPS && flush > to ? flush : Double.NaN;
    }

    /**
     * Resolves a same-cell step from {@code from} by (dx, dz): X first, then Z, each axis
     * shortened against the solids the box faces on the other two axes (like vanilla
     * collision, so a diagonal step into a corner slides along the open side). Returns the
     * allowed {dx, dz}, or null when the bot cannot move at all. A step that already ends
     * clear, or overlaps less than the start, is returned unchanged.
     */
    public static double[] resolve(double[] from, double dx, double dz, List<double[]> solids) {
        if (allowSameCellMove(from, offset(from, dx, dz), solids)) {
            return new double[]{dx, dz};
        }
        double rx = clampDelta(from, 0, dx, solids);
        if (Double.isNaN(rx)) {
            rx = 0.0;
        }
        double[] afterX = offset(from, rx, 0.0);
        double rz = clampDelta(afterX, 2, dz, solids);
        if (Double.isNaN(rz)) {
            rz = 0.0;
        }
        if (Math.abs(rx) <= EPS && Math.abs(rz) <= EPS) {
            return null;
        }
        return allowSameCellMove(from, offset(from, rx, rz), solids) ? new double[]{rx, rz} : null;
    }

    /** Shortest allowed delta on {@code axis} (0 = X, 2 = Z) over all solids the box faces. */
    private static double clampDelta(double[] b, int axis, double delta, List<double[]> solids) {
        if (Math.abs(delta) <= EPS) {
            return 0.0;
        }
        int other = axis == 0 ? 2 : 0;
        double half = (b[axis + 3] - b[axis]) / 2.0;
        double from = b[axis] + half;
        double to = from + delta;
        double best = to;
        for (double[] s : solids) {
            if (overlap(b, s, 1) <= EPS || overlap(b, s, other) <= EPS) {
                continue; // not in the box's path on this axis
            }
            double c = clampAxis(from, to, half, s[axis], s[axis + 3]);
            if (Double.isNaN(c)) {
                return Double.NaN;
            }
            best = delta > 0 ? Math.min(best, c) : Math.max(best, c);
        }
        return best - from;
    }
}
