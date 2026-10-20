package net.wcfcarolina13.GameAI.services.follow;

/**
 * Which raw path nodes a follow plan must keep as waypoints (finding 50).
 *
 * <p>The old compressor measured direction from the last kept point using X/Z only, with no
 * direction recorded before the first kept point, so an L of "step down, then sideways" could
 * collapse into one diagonal target that cut through a leaf corner. A node is now kept when the
 * raw step direction (dx, dz between consecutive nodes) changes there, or when the next step
 * changes Y (the node before a step up or down).</p>
 */
public final class FollowWaypointCompressionPolicy {

    private FollowWaypointCompressionPolicy() {}

    /**
     * @return true when node {@code i} of the raw path is a corner or the node before a Y change.
     *         The first and last nodes return false; callers handle them.
     */
    public static boolean mustKeep(int[] xs, int[] ys, int[] zs, int i) {
        if (xs == null || ys == null || zs == null) {
            return false;
        }
        int n = Math.min(xs.length, Math.min(ys.length, zs.length));
        if (i <= 0 || i >= n - 1) {
            return false;
        }
        int inDx = xs[i] - xs[i - 1];
        int inDz = zs[i] - zs[i - 1];
        int outDx = xs[i + 1] - xs[i];
        int outDz = zs[i + 1] - zs[i];
        if (inDx != outDx || inDz != outDz) {
            return true;
        }
        return ys[i + 1] != ys[i];
    }
}
