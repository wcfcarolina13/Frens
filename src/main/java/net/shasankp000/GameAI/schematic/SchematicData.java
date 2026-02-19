package net.shasankp000.GameAI.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Represents a parsed schematic/structure file.
 * Contains the palette of block states and the block placements.
 */
public record SchematicData(
        String name,
        int sizeX,
        int sizeY,
        int sizeZ,
        List<BlockState> palette,
        List<BlockPlacement> blocks
) {
    /**
     * Represents a single block placement within the schematic.
     */
    public record BlockPlacement(
            BlockPos relativePos,
            int paletteIndex
    ) {}

    /**
     * Get the total number of non-air blocks in this schematic.
     */
    public int blockCount() {
        return blocks.size();
    }

    /**
     * Get the BlockState at a specific palette index.
     */
    public BlockState getState(int paletteIndex) {
        if (paletteIndex < 0 || paletteIndex >= palette.size()) {
            return null;
        }
        return palette.get(paletteIndex);
    }

    /**
     * Check if this schematic is small enough to reasonably build.
     */
    public boolean isReasonableSize() {
        return sizeX <= 64 && sizeY <= 64 && sizeZ <= 64 && blockCount() <= 5000;
    }

    /**
     * Returns a new SchematicData rotated clockwise by the given number of quarter turns (0-3).
     * Only transforms XZ coordinates; palette is shared unchanged.
     * 90° CW: (x,y,z) → (sizeZ-1-z, y, x), swap sizeX/sizeZ.
     */
    public SchematicData rotated(int quarterTurns) {
        int turns = ((quarterTurns % 4) + 4) % 4; // normalize to 0-3
        if (turns == 0) return this;

        int curSizeX = sizeX;
        int curSizeZ = sizeZ;
        List<BlockPlacement> curBlocks = blocks;

        for (int t = 0; t < turns; t++) {
            List<BlockPlacement> rotated = new java.util.ArrayList<>(curBlocks.size());
            for (BlockPlacement bp : curBlocks) {
                BlockPos p = bp.relativePos();
                // 90° CW: (x, y, z) → (curSizeZ - 1 - z, y, x)
                BlockPos rp = new BlockPos(curSizeZ - 1 - p.getZ(), p.getY(), p.getX());
                rotated.add(new BlockPlacement(rp, bp.paletteIndex()));
            }
            // swap dimensions
            int tmp = curSizeX;
            curSizeX = curSizeZ;
            curSizeZ = tmp;
            curBlocks = rotated;
        }

        return new SchematicData(name + "_r" + turns, curSizeX, sizeY, curSizeZ, palette, curBlocks);
    }
}
