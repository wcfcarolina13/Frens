package net.wcfcarolina13.GameAI.services;

/**
 * Pure decision for the automatic post-task return (TaskService). Kept free of Minecraft types
 * so it can be unit tested.
 * <ul>
 *   <li>Already within {@link #ARRIVED_BLOCKS} of the destination: do nothing.</li>
 *   <li>Within {@link #WALK_MAX_BLOCKS}: walk. Fast travel over a few blocks is pointless and its
 *       gear refusal is chat spam.</li>
 *   <li>Further away: fast travel.</li>
 * </ul>
 */
public final class PostTaskReturnPolicy {

    public enum Action { NONE, WALK, FAST_TRAVEL }

    /** Close enough that a return is not needed. */
    public static final double ARRIVED_BLOCKS = 4.0;
    /** Below this a return walks instead of fast-travelling. */
    public static final double WALK_MAX_BLOCKS = 24.0;

    private PostTaskReturnPolicy() {
    }

    public static Action decide(double distSq) {
        return decide(distSq, ARRIVED_BLOCKS, WALK_MAX_BLOCKS);
    }

    /**
     * @param distSq         squared distance from the bot to the destination
     * @param arrivedBlocks  at or within this many blocks the bot is already there
     * @param walkMaxBlocks  at or within this many blocks the bot walks rather than fast-travels
     */
    public static Action decide(double distSq, double arrivedBlocks, double walkMaxBlocks) {
        if (Double.isNaN(distSq) || distSq < 0) {
            return Action.NONE;
        }
        if (distSq <= arrivedBlocks * arrivedBlocks) {
            return Action.NONE;
        }
        if (distSq <= walkMaxBlocks * walkMaxBlocks) {
            return Action.WALK;
        }
        return Action.FAST_TRAVEL;
    }
}
