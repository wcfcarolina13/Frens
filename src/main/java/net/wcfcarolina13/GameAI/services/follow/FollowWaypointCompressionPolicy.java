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

    /**
     * Which of {@code size} kept points survive downsampling to {@code max} (should-fix after
     * finding 50). The first {@code pinned} points stay, then the start, the first corner and the
     * points on either side of it, then the rest evenly to the end. Plain even sampling dropped
     * the corner nearest the bot on a staircase, where every node is a corner.
     *
     * @param firstCorner index into the points of the first corner, or -1 when there is none
     * @return ascending indices, always including the last point
     */
    public static int[] selectIndices(int size, int max, int pinned, int firstCorner) {
        if (size <= 0) {
            return new int[0];
        }
        if (max <= 0 || size <= max) {
            int[] all = new int[size];
            for (int i = 0; i < size; i++) {
                all[i] = i;
            }
            return all;
        }
        int end = size - 1;
        java.util.TreeSet<Integer> keep = new java.util.TreeSet<>();
        int locked = Math.max(0, Math.min(pinned, max - 1));
        for (int i = 0; i < locked; i++) {
            keep.add(i);
        }
        int budget = max - locked - 1; // slots before the end point
        int[] priority = firstCorner >= locked && firstCorner < end
                ? new int[] {locked, firstCorner, firstCorner - 1, firstCorner + 1}
                : new int[] {locked};
        int headMax = locked - 1;
        int used = 0;
        for (int idx : priority) {
            if (used >= budget) {
                break;
            }
            if (idx < locked || idx >= end || keep.contains(idx)) {
                continue;
            }
            keep.add(idx);
            headMax = Math.max(headMax, idx);
            used++;
        }
        int remaining = budget - used;
        int tailStart = headMax + 1;
        if (remaining > 0 && tailStart < end) {
            int span = end - tailStart;
            for (int k = 0; k < remaining; k++) {
                int idx = tailStart + (int) Math.round(((k + 1) / (double) (remaining + 1)) * span);
                if (idx >= tailStart && idx < end) {
                    keep.add(idx);
                }
            }
        }
        keep.add(end);
        int[] out = new int[keep.size()];
        int j = 0;
        for (int idx : keep) {
            out[j++] = idx;
        }
        return out;
    }
}
