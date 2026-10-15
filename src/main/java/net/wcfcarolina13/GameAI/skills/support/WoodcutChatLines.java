package net.wcfcarolina13.GameAI.skills.support;

/**
 * Player-facing woodcut sentences. The detailed numbers (trees felled, the run's minimum) stay in
 * the log; chat says what the player can see. No em dashes or semicolons in these lines.
 *
 * <p>No Minecraft types.
 */
public final class WoodcutChatLines {

    private WoodcutChatLines() {
    }

    /**
     * The line after the bot finished with one tree. {@code logs} is what it cut from that tree,
     * {@code restNeedsAxe} is true when logs were left standing because the bot has no axe yet
     * (it does not pillar for them until it has one). Returns {@code null} when there is nothing
     * worth saying (no logs and nothing left).
     */
    public static String treeHarvested(int logs, boolean restNeedsAxe) {
        int count = Math.max(0, logs);
        if (count == 0) {
            return restNeedsAxe ? "That tree needs an axe." : null;
        }
        String got = "Got " + count + (count == 1 ? " log" : " logs");
        return restNeedsAxe
                ? got + ", the rest of that tree needs an axe."
                : got + " from that tree.";
    }
}
