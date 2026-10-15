package net.wcfcarolina13.PathFinding.unified;

/** A terrain the {@link MoveSimulator} may change: break a planned cell, place planned scaffold. */
public interface MutableTerrainView extends TerrainView {
    /** The cell becomes air. */
    void removeBlock(int x, int y, int z);

    /** The cell becomes a plain scaffold block (dirt). */
    void placeBlock(int x, int y, int z);
}
