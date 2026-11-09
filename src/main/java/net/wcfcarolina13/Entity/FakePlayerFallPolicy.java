package net.wcfcarolina13.Entity;

/**
 * Bots never took fall damage: in 1.21.11 {@code Entity.move} calls {@code fall(...)} only when
 * {@code isLogicalSideForUpdatingMovement()}, which on the server is {@code !isControlledByPlayer()},
 * and a player always counts as player-controlled. A real player's falls come from the network
 * handler instead ({@code ServerPlayNetworkHandler.onPlayerMove} calls {@code handleFall} with the
 * packet's movement), which a bot never sends. So the bot feeds {@code handleFall} itself once per
 * tick from how far it moved. This class holds the pure part: which tick moves count as a fall
 * and which are teleports. No Minecraft types, so JUnit can test it.
 */
public final class FakePlayerFallPolicy {

    /**
     * Fastest honest vertical move in one tick. Falling speed tops out at about 3.92 blocks per
     * tick (minecraft.wiki, Entity: terminal velocity of a player); anything beyond this is a
     * teleport or a position set, not a fall.
     */
    public static final double MAX_TICK_DROP = 4.0D;
    /** Fastest honest horizontal move in one tick (a firework-boosted glide stays well under). */
    public static final double MAX_TICK_HORIZONTAL = 4.0D;

    public enum Kind {
        /** Feed the move to {@code handleFall}, as the network handler does for a real player. */
        MOVE,
        /** A teleport or position set: skip it and clear the fall distance, as a vanilla teleport does. */
        TELEPORT,
        /** Nothing to track this tick (riding, spectating, dead or removed). */
        SKIP
    }

    private FakePlayerFallPolicy() {
    }

    public static Kind classify(double dx, double dy, double dz, boolean riding, boolean spectator, boolean alive) {
        if (riding || spectator || !alive) {
            return Kind.SKIP;
        }
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) {
            return Kind.TELEPORT;
        }
        if (Math.abs(dy) > MAX_TICK_DROP) {
            return Kind.TELEPORT;
        }
        if (dx * dx + dz * dz > MAX_TICK_HORIZONTAL * MAX_TICK_HORIZONTAL) {
            return Kind.TELEPORT;
        }
        return Kind.MOVE;
    }
}
