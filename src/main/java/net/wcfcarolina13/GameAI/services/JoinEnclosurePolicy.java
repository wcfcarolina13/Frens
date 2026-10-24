package net.wcfcarolina13.GameAI.services;

/**
 * Pure decisions for the join enclosure check (BotEventHandler, tick+40 after a bot joins) and
 * the matching exemption in the idle linger check #0 (BotFleeService).
 *
 * <p>Why: a bot that respawns inside a house that is not a registered base reads as "enclosed,
 * not at the surface". The join check then registered a JOIN_ENCLOSED shelter (whose dawn exit
 * mines upward) or launched a generic break-free, which mines through the wall or the roof. A
 * bot standing in a player-built room with a door is at home, not trapped.
 *
 * <p>No Minecraft types here: the world reading lives in {@link PlayerBuiltStructureScanner}.
 */
public final class JoinEnclosurePolicy {

    /** Player-built blocks needed in the count box, besides a door in the wider door box. */
    public static final int MIN_BUILT_BLOCKS = 6;

    /**
     * A player-built structure only counts when the bot's feet are at most this far below the
     * surface heightmap at its column, or when one of its wooden doors opens to the sky
     * ({@link #isInsidePlayerBuilt(int, boolean, boolean, boolean)}). A house roof or a cellar is
     * a few blocks; a stronghold or a trial chamber is far deeper.
     */
    public static final int MAX_DEPTH_BELOW_SURFACE = 8;

    public enum Decision {
        SKIP_NEAR_BASE,
        SKIP_AT_SURFACE,
        SKIP_COMMANDER_NEAR,
        SKIP_MOVED,
        SKIP_PLAYER_BUILT,
        /** Night and calm: stay put in a JOIN_ENCLOSED shelter until dawn. */
        SHELTER,
        BREAK_FREE;

        public boolean skips() {
            return this != SHELTER && this != BREAK_FREE;
        }
    }

    private JoinEnclosurePolicy() {
    }

    /**
     * The join check after its grace gates (mode, follow, task, ride, teleport, stop, restored
     * shelter) have passed. Gate order matches the call site, so the logged reason is the first
     * one that applies.
     *
     * @param movedAway       the bot has moved 2 blocks or more since it joined
     * @param dayOrThundering {@code isDay() || isThundering()}: a calm night shelters instead
     */
    public static Decision decide(boolean nearBase, boolean atSurface, boolean commanderNear,
                                  boolean movedAway, boolean insidePlayerBuilt,
                                  boolean dayOrThundering) {
        if (nearBase) return Decision.SKIP_NEAR_BASE;
        if (atSurface) return Decision.SKIP_AT_SURFACE;
        if (commanderNear) return Decision.SKIP_COMMANDER_NEAR;
        if (movedAway) return Decision.SKIP_MOVED;
        if (insidePlayerBuilt) return Decision.SKIP_PLAYER_BUILT;
        return dayOrThundering ? Decision.BREAK_FREE : Decision.SHELTER;
    }

    /**
     * Inside a player-built structure: a door nearby and enough player-built blocks around the
     * bot. The door is required: a doorless sealed box (the bot's own wall-off, a cave pocket)
     * is where a break-free is right, and torches the bot lit in its own tunnel must not count.
     */
    public static boolean isInsidePlayerBuilt(int builtBlocks, boolean doorNearby) {
        return doorNearby && builtBlocks >= MIN_BUILT_BLOCKS;
    }

    /**
     * Inside a player-built structure, with the depth gate: a door nearby, enough player-built
     * blocks, and either the bot is shallow ({@link #withinSurfaceDepth(int)}) or one of the
     * wooden doors opens to the sky ({@link #doorCellOpensToSky(int, int)}). The second branch
     * keeps a house dug into a hillside or under a mountain, whose front door faces the open
     * slope. A stronghold door opens to a corridor under stone and stays out.
     */
    public static boolean isInsidePlayerBuilt(int builtBlocks, boolean doorNearby,
                                              boolean withinSurfaceDepth, boolean doorOpensToSky) {
        return isInsidePlayerBuilt(builtBlocks, doorNearby) && (withinSurfaceDepth || doorOpensToSky);
    }

    /** Cells checked on each side of a door, 1 and 2 blocks out. */
    public static final int DOOR_SKY_REACH = 2;

    /**
     * Horizontal offsets {dx, dz} of the cells checked around a door for open sky: 1 and 2 blocks
     * out on both sides, along both horizontal axes. Both axes, so a modded door without a facing
     * property works too; the cells along the wall are wall or roof and never read as sky.
     */
    public static int[][] doorSkyOffsets() {
        int[][] out = new int[DOOR_SKY_REACH * 4][];
        int i = 0;
        for (int d = 1; d <= DOOR_SKY_REACH; d++) {
            out[i++] = new int[]{d, 0};
            out[i++] = new int[]{-d, 0};
            out[i++] = new int[]{0, d};
            out[i++] = new int[]{0, -d};
        }
        return out;
    }

    /** The Y of a door's top half: the block itself for the upper half, one above for the lower. */
    public static int doorTopY(int y, boolean upperHalf) {
        return upperHalf ? y : y + 1;
    }

    /**
     * Whether a cell next to a door is open to the sky at the door's top: the column's surface
     * heightmap (the Y just above its highest motion-blocking block) is at or below the door's
     * top half. {@link Integer#MIN_VALUE} (chunk not loaded) is never sky.
     */
    public static boolean doorCellOpensToSky(int surfaceY, int doorTopY) {
        return surfaceY != Integer.MIN_VALUE && surfaceY <= doorTopY;
    }

    /**
     * Whether the bot is shallow enough for a player-built structure to count.
     *
     * @param depthBelowSurface surface heightmap Y at the bot's column minus the bot's feet Y
     */
    public static boolean withinSurfaceDepth(int depthBelowSurface) {
        return depthBelowSurface <= MAX_DEPTH_BELOW_SURFACE;
    }

    /**
     * Linger check #0: a bot at a non-functional position (no open sky, not near the surface)
     * stays put instead of bypassing the linger tree when one of these holds.
     */
    public static boolean lingerStaysAtNonFunctionalPosition(boolean commanderNear, boolean nearBase,
                                                             boolean insidePlayerBuilt) {
        return commanderNear || nearBase || insidePlayerBuilt;
    }
}
