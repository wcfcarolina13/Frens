package net.wcfcarolina13.GameAI.services;

/**
 * Pure decision behind {@code SmeltingService.cookAllFoodSync} losing furnace reach mid-cook (the
 * bot was pushed, knocked back, or walked off between polls): walk back to the furnace's approach
 * cell and carry on, or give up as before.
 *
 * <p>A walk back only helps while the bot is in the furnace's world (a portal or a teleport to
 * another dimension is final) and only a bounded number of times per cook cycle, so a bot that
 * keeps getting pulled away (a follow command, a mob shoving it) stops instead of fighting it.
 *
 * <p>No Minecraft types.
 */
public final class FurnaceReachRecoveryPolicy {

    /** Walk-backs allowed per {@code cookAllFoodSync} call, across all batches. */
    public static final int MAX_WALK_BACKS = 2;

    public enum Decision { WALK_BACK, GIVE_UP }

    private FurnaceReachRecoveryPolicy() {}

    /**
     * @param sameWorld       the bot is still in the furnace's world
     * @param walkBacksUsed   walk-backs already made this cook cycle
     * @param stopRequested   the worker was interrupted or the server is stopping
     */
    public static Decision onReachLost(boolean sameWorld, int walkBacksUsed, boolean stopRequested) {
        if (stopRequested || !sameWorld || walkBacksUsed < 0 || walkBacksUsed >= MAX_WALK_BACKS) {
            return Decision.GIVE_UP;
        }
        return Decision.WALK_BACK;
    }
}
