package net.wcfcarolina13.Commands;

/**
 * {@code /bot come} with "teleport during skills" on: within this range the movement service would
 * walk before teleporting, and the command runs on the server thread, where that walk sleeps. The
 * tick-driven come-walk is used instead; teleporting stays for longer distances.
 */
final class ComeTeleportPolicy {
    /** Matches MovementService.moveTo's walk-first band (distanceSq ≤ 900, i.e. 30 blocks). */
    static final double WALK_RANGE_SQ = 900.0D;

    private ComeTeleportPolicy() {
    }

    static boolean walkInsteadOfTeleport(double distanceSq) {
        return distanceSq <= WALK_RANGE_SQ;
    }
}
