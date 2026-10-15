package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Corner positions for construction stations (BuildSchematicSkill cycles them for approach angles).
 */
public final class PerimeterService {

    private PerimeterService() {}

    /**
     * Generate corner positions for a rectangular structure.
     *
     * @param center Center of the structure
     * @param radiusX Half-width in X direction
     * @param radiusZ Half-width in Z direction
     * @param standY Y level to stand at
     * @return List of corner positions (NW, NE, SW, SE)
     */
    public static List<BlockPos> getCornerPositions(BlockPos center, int radiusX, int radiusZ, int standY) {
        int cx = center.getX();
        int cz = center.getZ();
        
        return List.of(
                new BlockPos(cx - radiusX, standY, cz - radiusZ), // NW
                new BlockPos(cx + radiusX, standY, cz - radiusZ), // NE
                new BlockPos(cx - radiusX, standY, cz + radiusZ), // SW
                new BlockPos(cx + radiusX, standY, cz + radiusZ)  // SE
        );
    }
}
