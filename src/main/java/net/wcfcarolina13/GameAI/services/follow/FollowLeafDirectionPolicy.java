package net.wcfcarolina13.GameAI.services.follow;

/**
 * Which horizontal directions to check for a leaf obstruction toward a follow goal.
 *
 * <p>The old single direction broke |dx| == |dz| ties toward X, so for a diagonal goal the leaf
 * check looked at the wrong side. For any goal off both axes both axis directions are returned,
 * larger |d| first (X first on a tie, as before).</p>
 */
public final class FollowLeafDirectionPolicy {

    private FollowLeafDirectionPolicy() {}

    /**
     * @return unit steps {dx, dz}, each with exactly one non-zero component; empty for (0, 0)
     */
    public static int[][] candidates(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return new int[0][];
        }
        int[] xStep = {dx >= 0 ? 1 : -1, 0};
        int[] zStep = {0, dz >= 0 ? 1 : -1};
        if (dz == 0) {
            return new int[][] {xStep};
        }
        if (dx == 0) {
            return new int[][] {zStep};
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return new int[][] {xStep, zStep};
        }
        return new int[][] {zStep, xStep};
    }
}
