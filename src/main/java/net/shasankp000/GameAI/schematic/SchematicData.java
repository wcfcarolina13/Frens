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
}
