package net.wcfcarolina13.GameAI.services;

/**
 * Which cells a tunnelling bot is about to walk through, so a utility block (a crafting table for
 * the pickaxe it is about to make) is never placed in them.
 *
 * <p>Found in the PR #70 sandbox run: a bot sealed in stone dug one tunnel cell, then placed its
 * crafting table in that cell, the only air next to it. A crafting table is a protected workstation
 * that stripmine refuses to break, so the tunnel was blocked by the bot's own table for good.
 *
 * <p>The kept cells are the feet and head cells of the next {@code depth} steps straight ahead of
 * the bot's current block. They are worked out from where the bot stands each time, because the
 * bot moves between placements.
 */
public final class TunnelKeepClearPolicy {

    /** Steps ahead kept clear: the cell being dug now and the one after it. */
    public static final int DEFAULT_DEPTH = 2;

    private TunnelKeepClearPolicy() {
    }

    /**
     * @param dirX the tunnel's x step (-1, 0 or 1); with {@code dirZ}, exactly one is non-zero
     * @param dirZ the tunnel's z step (-1, 0 or 1)
     * @return whether the cell is a feet or head cell 1 to {@code depth} steps ahead of the bot
     */
    public static boolean blocksTunnel(int botX, int botY, int botZ,
                                       int dirX, int dirZ,
                                       int cellX, int cellY, int cellZ,
                                       int depth) {
        if (depth <= 0 || Math.abs(dirX) + Math.abs(dirZ) != 1) {
            return false;
        }
        if (cellY != botY && cellY != botY + 1) {
            return false;
        }
        int dx = cellX - botX;
        int dz = cellZ - botZ;
        // along = steps ahead; lateral must be zero (the cell is on the tunnel's centre line)
        int along = dx * dirX + dz * dirZ;
        int lateral = dx * dirZ - dz * dirX;
        return lateral == 0 && along >= 1 && along <= depth;
    }
}
