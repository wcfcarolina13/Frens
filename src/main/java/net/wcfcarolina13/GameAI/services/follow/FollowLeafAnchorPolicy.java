package net.wcfcarolina13.GameAI.services.follow;

/**
 * Stillness counter for the follow leaf-clear gate (finding 50).
 *
 * <p>The gate used to need the bot in the same block for 5 ticks, but the local obstacle nudge
 * rocks a bot pinned under a wall-to-wall leaf row across a block boundary every few ticks, which
 * reset the counter and the leaves were never cleared. The bot now counts as still while it stays
 * within {@link #ANCHOR_RADIUS} blocks (horizontally) of an anchor point.</p>
 */
public final class FollowLeafAnchorPolicy {

    public static final double ANCHOR_RADIUS = 0.75D;

    private FollowLeafAnchorPolicy() {}

    public record Anchor(double x, double z, int ticks) {}

    /**
     * @param previous the stored anchor, or null when none
     * @return the anchor to store: the same point with ticks + 1 while the bot stays near it,
     *         otherwise a fresh anchor at the bot with ticks 0
     */
    public static Anchor update(Anchor previous, double botX, double botZ) {
        if (previous != null) {
            double dx = botX - previous.x();
            double dz = botZ - previous.z();
            if (dx * dx + dz * dz <= ANCHOR_RADIUS * ANCHOR_RADIUS) {
                return new Anchor(previous.x(), previous.z(), previous.ticks() + 1);
            }
        }
        return new Anchor(botX, botZ, 0);
    }
}
