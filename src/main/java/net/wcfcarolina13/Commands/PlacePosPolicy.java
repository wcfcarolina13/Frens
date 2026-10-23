package net.wcfcarolina13.Commands;

/**
 * Pure decisions behind {@code /bot place} (1.1.295, no Minecraft types so it is unit-testable).
 *
 * <ul>
 *   <li>Which cell the item goes into: the looked-at cell itself when that block is replaceable
 *       (a snow layer, grass), otherwise the cell next to the clicked face. Same rule as
 *       {@code /bot build} and {@code /bot shelter}.</li>
 *   <li>How many attempts: on the server thread a walk never moves the bot (the tick that would
 *       move it is the one running the command), so only one in-reach attempt is made there.</li>
 *   <li>Which way to sidestep after a walk: a horizontal turn of the clicked face, or of the
 *       bot's facing when the face is UP or DOWN (rotating a vertical face throws).</li>
 * </ul>
 */
public final class PlacePosPolicy {

    /** Attempts when the bot may walk between them (off the server thread). */
    public static final int WALKING_ATTEMPTS = 10;
    /** Attempts on the server thread: in reach only. */
    public static final int IN_REACH_ATTEMPTS = 1;

    /** Mirrors the names of Minecraft's {@code Direction} so callers map by {@code name()}. */
    public enum Face { DOWN, UP, NORTH, SOUTH, WEST, EAST;
        public boolean vertical() {
            return this == DOWN || this == UP;
        }
    }

    public enum Cell { HIT, BESIDE_FACE }

    private PlacePosPolicy() {}

    public static Cell placementCell(boolean hitReplaceable) {
        return hitReplaceable ? Cell.HIT : Cell.BESIDE_FACE;
    }

    public static boolean mayWalk(boolean onServerThread) {
        return !onServerThread;
    }

    public static int maxAttempts(boolean onServerThread) {
        return mayWalk(onServerThread) ? WALKING_ATTEMPTS : IN_REACH_ATTEMPTS;
    }

    /**
     * Horizontal facing for a yaw in degrees, the same rounding as Minecraft's
     * {@code Direction.fromHorizontalDegrees}: 0 = SOUTH, 90 = WEST, 180 = NORTH, 270 = EAST.
     */
    public static Face facingFromYaw(float yaw) {
        int index = Math.floorMod((int) Math.floor(yaw / 90.0D + 0.5D), 4);
        return switch (index) {
            case 0 -> Face.SOUTH;
            case 1 -> Face.WEST;
            case 2 -> Face.NORTH;
            default -> Face.EAST;
        };
    }

    /** Clockwise (seen from above) turn of a horizontal face. */
    static Face rotateClockwise(Face face) {
        return switch (face) {
            case NORTH -> Face.EAST;
            case EAST -> Face.SOUTH;
            case SOUTH -> Face.WEST;
            case WEST -> Face.NORTH;
            default -> throw new IllegalArgumentException("not horizontal: " + face);
        };
    }

    /** Always horizontal: never throws for UP or DOWN. */
    public static Face sidestep(Face face, float botYaw) {
        Face base = (face == null || face.vertical()) ? facingFromYaw(botYaw) : face;
        return rotateClockwise(base);
    }
}
