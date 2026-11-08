package net.wcfcarolina13.GameAI;

/**
 * Whether a walking bot should jump to climb the block in front of it.
 *
 * <p>Climbing a full block needs room above the bot as well as above the block: the jump lifts the
 * bot about 1.25 blocks, so the cell two above its feet must be open. In a two-high tunnel that
 * cell is the ceiling. The jump then hits it, the bot lands again, and with the forward input still
 * held it jumps on the next tick, about five times a second. Sprint-jumps cost 0.2 exhaustion each,
 * which emptied a still bot's food bar in about 90 s (PR #70 sandbox run, a bot pressed against a
 * crafting table in its tunnel).
 */
public final class AutoJumpPolicy {

    private AutoJumpPolicy() {
    }

    /**
     * @param frontBlocks       the block in front has a collision shape
     * @param frontTopY         that shape's top, in blocks above the cell's floor
     * @param frontHeadroomOpen the two cells above the front block are open
     * @param ownHeadroomOpen   the cell two above the bot's feet is open (no ceiling over its head)
     */
    public static boolean shouldJump(boolean frontBlocks, double frontTopY,
                                     boolean frontHeadroomOpen, boolean ownHeadroomOpen) {
        if (!frontBlocks) {
            return false;
        }
        // fences and walls are taller than a jump clears: a real obstacle
        if (frontTopY > 1.01D) {
            return false;
        }
        return frontHeadroomOpen && ownHeadroomOpen;
    }
}
