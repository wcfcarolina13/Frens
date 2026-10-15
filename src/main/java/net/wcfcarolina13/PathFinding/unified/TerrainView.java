package net.wcfcarolina13.PathFinding.unified;

/**
 * Primitive terrain read by the unified finder. Production wraps a ServerWorld
 * ({@code WorldTerrainView}, slice B); tests use {@code VoxelGrid}. Pure: no {@code net.minecraft.*} types.
 */
public interface TerrainView {
    /** Bit set from {@link CellProps}. Must be deterministic for the life of one search. 0 = unloaded. */
    int props(int x, int y, int z);

    /**
     * On-ground break ticks with the best hotbar tool snapshotted into the context
     * (ObstructionMinePolicy.groundBreakTicks), or a value {@code >= ActionCosts.COST_INF} when the
     * cell is air, PROTECTED, NEVER_BREAK, LOCKED_DOOR, liquid or unloaded. {@code MoveCosts} also
     * refuses anything over {@code SearchContext.maxBreakTicks}, so a view may return the raw ticks.
     */
    int breakTicks(int x, int y, int z);

    int minY();

    int maxY();
}
