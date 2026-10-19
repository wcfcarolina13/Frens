package net.wcfcarolina13.GameAI.services;

/**
 * When a bot may narrate "Time to punch some trees" on breaking a log (1.1.273): only when it has
 * no axe anywhere on it. A woodcut bot that took an axe from a chest was still saying it.
 *
 * <p>No {@code net.minecraft.*} types; the caller reads the block and the inventory.</p>
 */
public final class TreePunchLinePolicy {

    private TreePunchLinePolicy() {}

    public static boolean shouldNarrate(boolean brokeLog, boolean hasAxe) {
        return brokeLog && !hasAxe;
    }
}
