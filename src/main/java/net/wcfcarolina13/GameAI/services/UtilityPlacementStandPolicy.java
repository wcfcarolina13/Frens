package net.wcfcarolina13.GameAI.services;

/**
 * Pure stand/place decisions for placing a utility block (crafting table, chest) next to the bot.
 *
 * <p>A bot's hitbox is 0.6 wide, so a bot whose block position is the planned stand can still
 * reach into the neighbouring place cell when it stands near that edge; vanilla then refuses the
 * placement ("bot-intersects-target"). The fix is to step back to the middle of the stand, never to
 * a stand that is itself the place cell (or whose body column holds it).
 *
 * <p>A bot's footprint is the inclusive range of block cells its hitbox covers. No Minecraft types.
 */
public final class UtilityPlacementStandPolicy {

    /** What to do before (re)trying a utility placement. */
    public enum Action {
        /** The bot is clear of the place cell: place from where it stands. */
        PLACE_NOW,
        /** The bot overlaps the place cell: walk (or re-center) onto the planned stand first. */
        STEP_TO_STAND,
        /** The bot overlaps the place cell and the plan has no usable stand: find another stand. */
        RELOCATE
    }

    private UtilityPlacementStandPolicy() {
    }

    /** Whether a footprint (inclusive block ranges) covers the cell. */
    public static boolean footprintOverlapsCell(int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                                                int px, int py, int pz) {
        return px >= minX && px <= maxX
                && py >= minY && py <= maxY
                && pz >= minZ && pz <= maxZ;
    }

    /**
     * Whether a bot standing in the middle of the stand cell would occupy the place cell: same
     * column, and the place cell is its feet or head cell.
     */
    public static boolean standOverlapsPlace(int sx, int sy, int sz, int px, int py, int pz) {
        return sx == px && sz == pz && (py == sy || py == sy + 1);
    }

    /**
     * The step before placing. A bot already clear of the place cell places directly, even when its
     * block is not the planned stand; one that overlaps it steps to the planned stand (including a
     * re-center when its block already is the stand) unless that stand would overlap the place cell
     * too, in which case it relocates.
     */
    public static Action decide(boolean botOverlapsPlace,
                                boolean hasStand,
                                int sx, int sy, int sz,
                                int px, int py, int pz) {
        if (!botOverlapsPlace) {
            return Action.PLACE_NOW;
        }
        if (hasStand && !standOverlapsPlace(sx, sy, sz, px, py, pz)) {
            return Action.STEP_TO_STAND;
        }
        return Action.RELOCATE;
    }
}
