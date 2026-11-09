package net.wcfcarolina13.GameAI.services;

/**
 * How much food a bot can add to a furnace's input slot that already holds some. Same item and
 * components is enough to stack; the counts never have to match. The old check used
 * ItemStack.areEqual, which also compares counts, so the owner's 5 raw beef refused the bot's 8 and
 * the cook ended without a word. No Minecraft imports.
 */
public final class FurnaceInputPolicy {

    private FurnaceInputPolicy() {
    }

    /** Items to move onto the slot's stack: none for a different item, else what fits. */
    public static int transfer(boolean sameItemAndComponents, int inputCount, int maxCount, int offered) {
        if (!sameItemAndComponents || offered <= 0 || inputCount >= maxCount) {
            return 0;
        }
        return Math.min(offered, maxCount - inputCount);
    }
}
