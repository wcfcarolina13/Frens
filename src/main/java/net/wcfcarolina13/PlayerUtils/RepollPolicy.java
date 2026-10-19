package net.wcfcarolina13.PlayerUtils;

/**
 * Pure decisions for {@code MiningTool}'s periodic tool re-poll (no Minecraft types).
 *
 * <p>1.1.272 sandbox: a stone axe on a block it is not effective on was swapped for a harmless
 * oak log every re-poll, something put the axe back between polls, and each swap reset the
 * break progress, so the block never finished. Progress now survives a swap that is not faster,
 * and once a swap has been undone by someone else the re-poll stops fighting over a swap that
 * brings no speed gain.
 */
public final class RepollPolicy {

    private RepollPolicy() {
    }

    /**
     * Whether a tool swap should restart break progress. Only a faster tool (fewer required
     * ticks) resets, so the old tool's ticks cannot carry over and break the block early; an
     * equal or slower swap keeps the ticks already spent.
     */
    public static boolean shouldResetProgress(int oldRequiredTicks, int newRequiredTicks) {
        return newRequiredTicks < oldRequiredTicks;
    }

    /**
     * Whether the re-poll should swap from the held item to the newly selected one. A faster
     * tool always wins. A swap with no speed gain is made only until one earlier swap in this
     * mining run was undone externally; after that the held item stays.
     */
    public static boolean shouldSwap(float heldSpeed, float newSpeed, boolean undoneBefore) {
        if (newSpeed > heldSpeed) {
            return true;
        }
        return !undoneBefore;
    }
}
