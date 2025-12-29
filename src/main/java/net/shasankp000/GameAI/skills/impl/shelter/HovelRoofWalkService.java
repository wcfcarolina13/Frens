package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic roof-walk routing helpers.
 *
 * <p>The goal is to produce a predictable, mostly-adjacent traversal order over the roof plane
 * so the bot can "walk the roof" and expose new placement angles, rather than repeatedly picking
 * greedy stations that may miss corners.</p>
 */
final class HovelRoofWalkService {

    private HovelRoofWalkService() {
    }

    /**
     * Build a serpentine (scanline) route over the roof block positions at {@code roofY}.
     *
     * <p>The returned list is ordered so consecutive entries are adjacent in most cases.
     * We start near {@code near} (by X/Z distance) to reduce initial travel cost, then
     * traverse forward to the end, and finally traverse backward to cover the earlier half.
     * This avoids a large "wraparound" jump.</p>
     */
    static List<BlockPos> buildSerpentineRouteFromNearest(BlockPos center, int radius, int roofY, BlockPos near) {
        if (center == null || radius <= 0) {
            return List.of();
        }

        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;

        ArrayList<BlockPos> base = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        int row = 0;
        for (int z = minZ; z <= maxZ; z++, row++) {
            boolean forward = (row % 2) == 0;
            if (forward) {
                for (int x = minX; x <= maxX; x++) {
                    base.add(new BlockPos(x, roofY, z));
                }
            } else {
                for (int x = maxX; x >= minX; x--) {
                    base.add(new BlockPos(x, roofY, z));
                }
            }
        }

        int startIdx = nearestIndexXZ(base, near);
        if (startIdx <= 0) {
            return base;
        }

        ArrayList<BlockPos> route = new ArrayList<>(base.size());
        for (int i = startIdx; i < base.size(); i++) {
            route.add(base.get(i));
        }
        for (int i = startIdx - 1; i >= 0; i--) {
            route.add(base.get(i));
        }
        return route;
    }

    private static int nearestIndexXZ(List<BlockPos> route, BlockPos near) {
        if (route == null || route.isEmpty() || near == null) {
            return 0;
        }
        int bestIdx = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            BlockPos p = route.get(i);
            if (p == null) continue;
            double d = HovelGeometryService.distSqXZ(p, near);
            if (d < best) {
                best = d;
                bestIdx = i;
            }
        }
        return bestIdx;
    }
}
