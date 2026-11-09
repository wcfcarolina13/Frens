package net.wcfcarolina13.GameAI.services;

/**
 * What a food-only cook (auto-cook) does with a furnace that is already in use. An unfiltered cook
 * used to treat any input as "already loaded" and top up its fuel, so a hungry bot fed its own coal
 * to the owner's iron ore and walked away with its raw food uncooked (sandbox 2026-11-09). It also
 * swept the owner's smelted ingots into its pack on the way. The owner's work in that furnace is
 * not the bot's to fuel, clear or collect. No Minecraft imports.
 */
public final class AutoCookFurnaceInputPolicy {

    private AutoCookFurnaceInputPolicy() {
    }

    /**
     * True when the cook must leave this furnace alone: a food-only cook with no item filter finds
     * an input that isn't one of the foods the bot itself is about to cook (someone else's ore, or
     * someone else's different raw food), or an output that isn't food.
     *
     * @param foodOnly         the cook is food-only (auto-cook)
     * @param noFilter         no item was named
     * @param inputIsBotsFood  the input slot is empty or holds a food the bot carries to cook here
     * @param outputIsFood     the output slot is empty or holds food
     */
    public static boolean furnaceBusyWithOtherWork(boolean foodOnly, boolean noFilter,
                                                   boolean inputIsBotsFood, boolean outputIsFood) {
        return foodOnly && noFilter && (!inputIsBotsFood || !outputIsFood);
    }
}
