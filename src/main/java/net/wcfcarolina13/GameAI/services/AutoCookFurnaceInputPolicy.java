package net.wcfcarolina13.GameAI.services;

/**
 * What a food-only cook (auto-cook) does with a furnace whose input slot is already in use. An
 * unfiltered cook used to treat any input as "already loaded" and top up its fuel, so a hungry
 * bot fed its own coal to the owner's iron ore and walked away with its raw food uncooked
 * (sandbox 2026-11-09). The owner's work in that furnace is not the bot's to fuel or to clear.
 * No Minecraft imports.
 */
public final class AutoCookFurnaceInputPolicy {

    private AutoCookFurnaceInputPolicy() {
    }

    /**
     * True when the cook must leave this furnace alone: a food-only cook with no item filter
     * finds an input that does not cook to food there.
     *
     * @param foodOnly          the cook is food-only (auto-cook, the cook-food command)
     * @param noFilter          no item was named
     * @param inputCooksToFood  the input slot's item has a recipe at this station whose result is food
     */
    public static boolean furnaceBusyWithOtherWork(boolean foodOnly, boolean noFilter, boolean inputCooksToFood) {
        return foodOnly && noFilter && !inputCooksToFood;
    }
}
