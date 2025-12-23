package net.shasankp000.GameAI.skills.impl.shelter;

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

    static List<BlockPos> buildOuterRingSeeds(BlockPos center, int ringRadius) {
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        int cx = center.getX();
        int cz = center.getZ();

        for (int dz = -ringRadius; dz <= ringRadius; dz++) {
            out.add(new BlockPos(cx + ringRadius, center.getY(), cz + dz));
            out.add(new BlockPos(cx - ringRadius, center.getY(), cz + dz));
        }
        for (int dx = -ringRadius; dx <= ringRadius; dx++) {
            out.add(new BlockPos(cx + dx, center.getY(), cz + ringRadius));
            out.add(new BlockPos(cx + dx, center.getY(), cz - ringRadius));
        }
        return new ArrayList<>(out);
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

    /**
     * Returns roof block positions (not stand positions) in a single loop around the perimeter.
     * The caller can use {@code pos.up()} as the walk/stand cell.
     */
    static List<BlockPos> buildRoofPerimeter(BlockPos center, int radius, int roofY) {
        List<BlockPos> out = new ArrayList<>();
        int cx = center.getX();
        int cz = center.getZ();

        // East edge (north -> south)
        for (int dz = -radius; dz <= radius; dz++) {
            out.add(new BlockPos(cx + radius, roofY, cz + dz));
        }
        // South edge (east-1 -> west)
        for (int dx = radius - 1; dx >= -radius; dx--) {
            out.add(new BlockPos(cx + dx, roofY, cz + radius));
        }
        // West edge (south-1 -> north)
        for (int dz = radius - 1; dz >= -radius; dz--) {
            out.add(new BlockPos(cx - radius, roofY, cz + dz));
        }
        // North edge (west+1 -> east-1)
        for (int dx = -radius + 1; dx <= radius - 1; dx++) {
            out.add(new BlockPos(cx + dx, roofY, cz - radius));
        }

        // Remove any accidental duplicates while preserving order.
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
