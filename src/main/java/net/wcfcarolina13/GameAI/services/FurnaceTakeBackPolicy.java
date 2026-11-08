package net.wcfcarolina13.GameAI.services;

/**
 * What a cook cycle does with the furnace when it is stopped part way. Before, a /bot stop left the
 * raw food, the cooked food and the coal in the furnace (sandbox 2026-11-08: 5 raw beef, 2 cooked,
 * 7 coal left behind). The food comes back: the input slot and the output slot. From the fuel slot
 * only what the bot added this cycle comes back; fuel the furnace already held stays (it may be the
 * owner's). No Minecraft imports.
 */
public final class FurnaceTakeBackPolicy {

    public static final int INPUT_SLOT = 0;
    public static final int FUEL_SLOT = 1;
    public static final int OUTPUT_SLOT = 2;
    /** Output first, so the cooked food lands in the pack before the raw food fills it. */
    public static final int[] SLOTS = { OUTPUT_SLOT, INPUT_SLOT };

    private FurnaceTakeBackPolicy() {
    }

    /**
     * Only a stop takes the food back, and only from a cycle that put food in: a stop before the first
     * load would otherwise empty a shared furnace of what someone else is cooking. A shutting-down
     * server leaves the furnace as it is.
     */
    public static boolean shouldTakeBack(boolean stoppedEarly, boolean serverStopping, boolean loadedThisCycle) {
        return stoppedEarly && !serverStopping && loadedThisCycle;
    }

    /**
     * Fuel to take back after a stop: what the slot holds above what it held before the cycle first
     * fuelled it. An empty slot before: all of it. A different fuel item before: none (the slot was
     * topped up from neither, or it is not the bot's).
     */
    public static int fuelToTakeBack(boolean emptyBefore, boolean sameItem, int countBefore, int countNow) {
        if (countNow <= 0 || !sameItem) {
            return 0;
        }
        return emptyBefore ? countNow : Math.max(0, countNow - countBefore);
    }
}
