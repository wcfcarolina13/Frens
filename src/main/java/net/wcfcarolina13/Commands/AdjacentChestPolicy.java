package net.wcfcarolina13.Commands;

/**
 * Where {@code /bot place chest <n>} puts the next chest beside the one it just placed. No
 * Minecraft types: cells are int coordinates, a side is a horizontal unit step (dx, dz).
 *
 * <p>The placement clicks the anchor (the chest already placed) on its side facing the step, and the
 * new chest goes into the neighbouring cell. Before the 2026-11-05 fix the target's place cell was computed as
 * {@code candidate.offset(opposite)}, which is the anchor itself, so the second chest of a
 * {@code /bot place chest 2} always failed (the cell was the first chest) and the command answered
 * "I can't reach that spot."
 */
final class AdjacentChestPolicy {

    record Cell(int x, int y, int z) {
    }

    /** The click (anchor) and place cells for one horizontal side, and the clicked face as a step. */
    record Target(Cell hit, int faceDx, int faceDz, Cell place) {
    }

    private AdjacentChestPolicy() {
    }

    static Target target(Cell anchor, int dx, int dz) {
        if (Math.abs(dx) + Math.abs(dz) != 1) {
            throw new IllegalArgumentException("not a horizontal unit step: " + dx + "," + dz);
        }
        return new Target(anchor, dx, dz, new Cell(anchor.x() + dx, anchor.y(), anchor.z() + dz));
    }
}
