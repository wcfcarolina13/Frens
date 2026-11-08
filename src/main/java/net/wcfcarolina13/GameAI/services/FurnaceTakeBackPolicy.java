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
     * Raw food to take back from the input slot: only the food type this cycle was cooking, and never
     * more than the bot loaded of it less what has cooked since ({@code cookedSinceLoad}), so the
     * owner's same-kind raw food already in the slot stays. When both were in the slot the furnace
     * cooks them as one stack; counting every cooked item against the bot's share keeps the owner's
     * raw food whole, at the cost of the bot sometimes leaving some of its own raw food behind.
     */
    public static int inputToTakeBack(boolean sameItemAsLoaded, int countNow, int addedByBot, int cookedSinceLoad) {
        if (!sameItemAsLoaded || countNow <= 0 || addedByBot <= 0) {
            return 0;
        }
        return Math.min(countNow, Math.max(0, addedByBot - Math.max(0, cookedSinceLoad)));
    }

    /**
     * Fuel to take back after a stop: never more than the bot put in this cycle ({@code addedByBot},
     * counted at each of its refuels), and never more than the slot holds above what it held before
     * the cycle first fuelled it. With nobody else touching the slot, that is the bot's fuel left
     * over (what it added, less what burned for its own cook). Edge case: a player who adds the
     * same fuel item to the slot during the cycle can lose up to what the bot added, since the two
     * stacks merge and burned fuel cannot be told apart. A different fuel item before: none.
     */
    public static int fuelToTakeBack(boolean emptyBefore, boolean sameItem, int countBefore, int countNow, int addedByBot) {
        if (countNow <= 0 || !sameItem || addedByBot <= 0) {
            return 0;
        }
        int aboveBaseline = emptyBefore ? countNow : Math.max(0, countNow - countBefore);
        return Math.min(aboveBaseline, addedByBot);
    }

    /** What one refuel added to the fuel slot: the count it grew by, when the slot kept the same item. */
    public static int refuelAdded(boolean sameItem, int countBefore, int countAfter) {
        return sameItem ? Math.max(0, countAfter - countBefore) : 0;
    }

    /**
     * Cooked food the bot may still take from the output slot this load: what it loaded raw, less
     * what it already took. The rest cooked from the owner's same-kind food and stays for the owner.
     */
    public static int outputAllowance(int inputAddedByBot, int outputTakenSinceLoad) {
        return Math.max(0, inputAddedByBot - Math.max(0, outputTakenSinceLoad));
    }
}
