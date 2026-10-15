package net.wcfcarolina13.PathFinding.unified;

import java.util.Arrays;

/**
 * One executable step. Cells in {@code toBreak} and {@code toPlace} are packed longs in the
 * {@code BlockPos.asLong} layout ({@link CellPos}).
 *
 * @param toBreak           mined in this order before the step (top cell first), empty for a plain walk
 * @param toPlace           placed before the step, at most one cell in 1.1.242
 * @param ticks             the full edge cost: base table cost plus wade and hazard penalty minus the clamped anchor bonus
 * @param scaffoldLeftAfter node state after this move, so CostVerifier re-evaluates move i without replaying the prefix
 * @param swimRunAfter      consecutive swim cells including this landing, 0 on land
 */
public record PlannedMove(MoveKind kind,
                          int fromX, int fromY, int fromZ,
                          int toX, int toY, int toZ,
                          long[] toBreak,
                          long[] toPlace,
                          double ticks,
                          int scaffoldLeftAfter,
                          int swimRunAfter) {

    static final long[] NONE = new long[0];

    public PlannedMove {
        toBreak = toBreak == null ? NONE : toBreak;
        toPlace = toPlace == null ? NONE : toPlace;
    }

    public boolean isAction() {
        return toBreak.length > 0 || toPlace.length > 0 || kind == MoveKind.SWIM || kind == MoveKind.FALL
                || kind == MoveKind.DOWNWARD;
    }

    public long fromPacked() {
        return CellPos.pack(fromX, fromY, fromZ);
    }

    public long toPacked() {
        return CellPos.pack(toX, toY, toZ);
    }

    public int dx() {
        return Integer.signum(toX - fromX);
    }

    public int dz() {
        return Integer.signum(toZ - fromZ);
    }

    /** Same move with a different cost (used by tests and CostVerifier reporting). */
    public PlannedMove withTicks(double newTicks) {
        return new PlannedMove(kind, fromX, fromY, fromZ, toX, toY, toZ, toBreak, toPlace, newTicks,
                scaffoldLeftAfter, swimRunAfter);
    }

    /** Same kind, cells and actions (cost ignored). */
    public boolean sameStep(PlannedMove o) {
        return o != null && kind == o.kind && fromX == o.fromX && fromY == o.fromY && fromZ == o.fromZ
                && toX == o.toX && toY == o.toY && toZ == o.toZ
                && Arrays.equals(toBreak, o.toBreak) && Arrays.equals(toPlace, o.toPlace);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PlannedMove o)) {
            return false;
        }
        return sameStep(o) && Double.compare(ticks, o.ticks) == 0
                && scaffoldLeftAfter == o.scaffoldLeftAfter && swimRunAfter == o.swimRunAfter;
    }

    @Override
    public int hashCode() {
        int h = kind.hashCode();
        h = 31 * h + Long.hashCode(fromPacked());
        h = 31 * h + Long.hashCode(toPacked());
        h = 31 * h + Arrays.hashCode(toBreak);
        h = 31 * h + Arrays.hashCode(toPlace);
        h = 31 * h + Double.hashCode(ticks);
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(kind).append(' ').append(fromX).append(',').append(fromY).append(',').append(fromZ)
                .append("->").append(toX).append(',').append(toY).append(',').append(toZ)
                .append(String.format(java.util.Locale.ROOT, " %.2ft", ticks));
        if (toBreak.length > 0) {
            sb.append(" break=[");
            for (int i = 0; i < toBreak.length; i++) {
                sb.append(i == 0 ? "" : " ").append(CellPos.format(toBreak[i]));
            }
            sb.append(']');
        }
        if (toPlace.length > 0) {
            sb.append(" place=[");
            for (int i = 0; i < toPlace.length; i++) {
                sb.append(i == 0 ? "" : " ").append(CellPos.format(toPlace[i]));
            }
            sb.append(']');
        }
        return sb.toString();
    }
}
