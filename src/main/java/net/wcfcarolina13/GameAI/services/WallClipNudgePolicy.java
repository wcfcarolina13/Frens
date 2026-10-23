package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.List;

/** Horizontal wall columns touched by the body, with a bounded nudge away from each. */
final class WallClipNudgePolicy {
    private static final int[][] OFFSETS = {
            {0, -1}, {0, 1}, {-1, 0}, {1, 0},
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
    };

    record Nudge(int wallX, int wallZ, double dx, double dz) {}

    private WallClipNudgePolicy() {}

    static List<Nudge> candidates(double x, double z, double minX, double maxX,
                                  double minZ, double maxZ) {
        int cellX = (int) Math.floor(x);
        int cellZ = (int) Math.floor(z);
        List<Nudge> result = new ArrayList<>();
        for (int[] offset : OFFSETS) {
            int wallX = cellX + offset[0];
            int wallZ = cellZ + offset[1];
            if (maxX <= wallX || minX >= wallX + 1 || maxZ <= wallZ || minZ >= wallZ + 1) {
                continue;
            }
            double scale = 0.4D / Math.hypot(offset[0], offset[1]);
            result.add(new Nudge(wallX, wallZ, -offset[0] * scale, -offset[1] * scale));
            if (offset[0] != 0 && offset[1] != 0) {
                // Near a corner, splitting the impulse may leave both axes overlapping.
                result.add(new Nudge(wallX, wallZ, -offset[0] * 0.4D, 0));
                result.add(new Nudge(wallX, wallZ, 0, -offset[1] * 0.4D));
            }
        }
        return result;
    }
}
