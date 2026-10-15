package net.wcfcarolina13.GameAI.skills.impl.shelter;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Stateless helpers for generating hovel build blueprints/paths.
 */
final class HovelBlueprint {

    private HovelBlueprint() {
    }

    static List<BlockPos> generateWallBlueprint(BlockPos center, int radius, int height, Direction doorSide) {
        List<BlockPos> blueprint = new ArrayList<>();
        int floorY = center.getY();
        for (int y = floorY; y < floorY + height; y++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius || Math.abs(dz) == radius) {
                        BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                        if (!isDoorGap(pos, center, radius, doorSide, floorY)) {
                            blueprint.add(pos);
                        }
                    }
                }
            }
        }
        return blueprint;
    }

    static List<BlockPos> generateRoofBlueprint(BlockPos center, int radius, int wallHeight) {
        List<BlockPos> blueprint = new ArrayList<>();
        int roofY = center.getY() + wallHeight;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                blueprint.add(new BlockPos(center.getX() + dx, roofY, center.getZ() + dz));
            }
        }
        return blueprint;
    }

    /**
     * Ordered ground-level loop around a square ring (same pattern as roof perimeter, but at a given Y).
     * Useful for "walk the exterior" patch passes.
     */
    static List<BlockPos> buildGroundPerimeter(BlockPos center, int ringRadius, int y) {
        List<BlockPos> out = new ArrayList<>();
        int cx = center.getX();
        int cz = center.getZ();

        // East edge (north -> south)
        for (int dz = -ringRadius; dz <= ringRadius; dz++) {
            out.add(new BlockPos(cx + ringRadius, y, cz + dz));
        }
        // South edge (east-1 -> west)
        for (int dx = ringRadius - 1; dx >= -ringRadius; dx--) {
            out.add(new BlockPos(cx + dx, y, cz + ringRadius));
        }
        // West edge (south-1 -> north)
        for (int dz = ringRadius - 1; dz >= -ringRadius; dz--) {
            out.add(new BlockPos(cx - ringRadius, y, cz + dz));
        }
        // North edge (west+1 -> east-1)
        for (int dx = -ringRadius + 1; dx <= ringRadius - 1; dx++) {
            out.add(new BlockPos(cx + dx, y, cz - ringRadius));
        }

        LinkedHashSet<BlockPos> uniq = new LinkedHashSet<>(out);
        return new ArrayList<>(uniq);
    }

    static boolean isDoorGap(BlockPos pos, BlockPos center, int radius, Direction doorSide, int floorY) {
        if (doorSide == null) return false;
        if (pos.getY() != floorY + 1 && pos.getY() != floorY + 2) return false;
        BlockPos doorPos = center.offset(doorSide, radius);
        return pos.getX() == doorPos.getX() && pos.getZ() == doorPos.getZ();
    }
}
