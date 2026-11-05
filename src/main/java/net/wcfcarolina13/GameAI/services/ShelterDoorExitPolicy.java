package net.wcfcarolina13.GameAI.services;

/**
 * Pure decisions for leaving an enclosure through a door instead of mining out (break-free).
 *
 * <p>Why: the generic break-free mines the first solid wall it finds, and a JOIN_ENCLOSED
 * shelter's dawn exit mines the block above the bot and pillars up. Inside a house the join
 * scanner did not recognise (fewer built blocks near the bot, a forced break-free, a failed
 * shelter validation), that is the player's wall or roof. A wooden door that opens to the sky is
 * the way out a player would take, so it is tried first. The village-house exit already works
 * this way; the bot's own shelters (dig-down pit, cliff, wall-off) keep their own exits.
 *
 * <p>No Minecraft types here: the world reading is
 * {@link PlayerBuiltStructureScanner#findSkyDoor}.
 */
public final class ShelterDoorExitPolicy {

    /** A door is looked for within x/z +-this of the bot's feet, and feet Y -1..+1. */
    public static final int DOOR_RADIUS = 6;

    private ShelterDoorExitPolicy() {
    }

    /**
     * Whether a break-free tries a door first. {@code shelterTypeName} is the shelter record's
     * type name, or null when there is no record (generic break-free). Only the generic path and
     * JOIN_ENCLOSED qualify: the other types are shelters the bot built or dug itself, and their
     * exits remove exactly what the bot placed.
     */
    public static boolean tryDoorFirst(String shelterTypeName) {
        return shelterTypeName == null || "JOIN_ENCLOSED".equals(shelterTypeName);
    }

    /**
     * Whether the bot ended on the other side of the doorway from where it started, along the
     * door's facing axis. A bot that started in the door cell itself, or did not get past it, has
     * not crossed.
     */
    public static boolean crossedDoorway(int doorCoord, int startCoord, int endCoord) {
        int start = Integer.signum(startCoord - doorCoord);
        int end = Integer.signum(endCoord - doorCoord);
        return start != 0 && end == -start;
    }

    /** Whether candidate A is nearer than the current best (squared distances; -1 = no best). */
    public static boolean nearer(int candidateDistSq, int bestDistSq) {
        return bestDistSq < 0 || candidateDistSq < bestDistSq;
    }

    /**
     * Which way along the door's facing axis is the far side from the bot: +1 or -1, or 0 when the
     * bot stands in the door's own row on that axis (no side to call far).
     */
    public static int farSign(int doorCoord, int botCoord) {
        return -Integer.signum(botCoord - doorCoord);
    }
}
