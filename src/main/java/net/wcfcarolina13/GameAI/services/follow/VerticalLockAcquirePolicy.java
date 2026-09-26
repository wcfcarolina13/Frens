package net.wcfcarolina13.GameAI.services.follow;

/**
 * Pure-logic policy: whether follow may take a vertical climb lock on a climbable column when the
 * commander is not clearly above the bot (the "independent summit" case: the bot is blocked and
 * stagnant, so it looks for a ladder/vine to climb over the obstacle).
 *
 * <p>No Minecraft imports.
 *
 * <p>1.1.223: with the commander one block BELOW Jake and a fence between them, the summit branch
 * took a ladder behind Jake (stand-to-commander horizontal distance² 202 vs Jake's 80), cleared the
 * door plan that was about to open the gate, and walked him away for ~9 s. A summit is only worth
 * taking when the commander is above the bot, the climb stand is not farther from the commander
 * than the bot already is, and the column reaches the commander's height.
 */
public final class VerticalLockAcquirePolicy {

    private VerticalLockAcquirePolicy() {
    }

    /** Cheap pre-check before the climbable scan: a summit only helps when the commander is above the bot. */
    public static boolean isCommanderAbove(double botY, double targetY) {
        return targetY - botY > 0.0D;
    }

    /**
     * @param botY                  bot feet Y
     * @param targetY               commander feet Y
     * @param botToTargetHorizSq    horizontal distance² from the bot's block to the commander's block
     * @param standToTargetHorizSq  horizontal distance² from the candidate's stand block to the commander's block
     * @param topY                  Y of the top climbable block of the candidate column
     * @return true when the candidate may be used for an independent-summit lock
     */
    public static boolean acceptSummitCandidate(double botY,
                                                double targetY,
                                                double botToTargetHorizSq,
                                                double standToTargetHorizSq,
                                                int topY) {
        if (!isCommanderAbove(botY, targetY)) {
            // Commander level with or below the bot: climbing cannot bring it closer.
            return false;
        }
        if (standToTargetHorizSq > botToTargetHorizSq) {
            // The climb starts farther from the commander than the bot already is.
            return false;
        }
        // Standing on top of the column puts the bot at about topY + 1.
        return topY + 1.0D >= targetY;
    }
}
