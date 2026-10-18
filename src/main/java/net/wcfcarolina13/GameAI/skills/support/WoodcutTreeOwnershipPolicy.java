package net.wcfcarolina13.GameAI.skills.support;

import java.util.List;

/**
 * Pure check: does a log belong to the tree being felled, or to a neighbour?
 *
 * <p>1.1.268 field (finding 3): the leftover-log list was every log the leaf-linked cluster BFS
 * reached in a box wider than the tree, so in a forest it held the neighbours' logs. The bot
 * climbed toward neighbour canopies, reported "cluster-logs-remained" on trees it had finished,
 * and relocated early. A log is now kept only when it is near this trunk and not better explained
 * by another rooted trunk, with the same rules the canopy split uses for leaves
 * ({@code TreeDetector.leafBelongsToSelectedTree}):
 * <ul>
 *   <li>its column is within Chebyshev {@code radius} of the trunk base;</li>
 *   <li>it is not in another rooted base's column (base-1 .. top+1);</li>
 *   <li>it is not horizontally closer to another base than to this one by more than the margin
 *       (squared distance + 1, so equidistant logs stay owned).</li>
 * </ul>
 * Rooted bases within 1 block of the target base are the same tree (the other columns of a 2x2
 * trunk) and are ignored.
 *
 * <p>No Minecraft types.
 */
public final class WoodcutTreeOwnershipPolicy {

    /** Horizontal reach of one tree's logs from its trunk base. */
    public static final int DEFAULT_RADIUS = 5;

    private WoodcutTreeOwnershipPolicy() {
    }

    /**
     * @param otherBases other rooted trunks near the tree, each {@code {x, z, baseY, topY}}
     */
    public static boolean ownsLog(int lx, int ly, int lz,
                                  int bx, int bz,
                                  int radius,
                                  List<int[]> otherBases) {
        if (Math.max(Math.abs(lx - bx), Math.abs(lz - bz)) > radius) {
            return false;
        }
        if (otherBases == null || otherBases.isEmpty()) {
            return true;
        }
        long selfDistSq = horizontalDistanceSq(lx, lz, bx, bz);
        for (int[] other : otherBases) {
            if (other == null || other.length < 4) {
                continue;
            }
            int ox = other[0];
            int oz = other[1];
            if (Math.abs(ox - bx) <= 1 && Math.abs(oz - bz) <= 1) {
                continue; // another column of this tree's own 2x2 trunk
            }
            if (lx == ox && lz == oz && ly >= other[2] - 1 && ly <= other[3] + 1) {
                return false;
            }
            if (horizontalDistanceSq(lx, lz, ox, oz) + 1 < selfDistSq) {
                return false;
            }
        }
        return true;
    }

    private static long horizontalDistanceSq(int ax, int az, int bx, int bz) {
        long dx = ax - bx;
        long dz = az - bz;
        return dx * dx + dz * dz;
    }
}
