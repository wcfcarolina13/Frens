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
     * Linger check #0: a bot at a non-functional position (no open sky, not near the surface)
     * stays put instead of bypassing the linger tree when one of these holds.
     */
    public static boolean lingerStaysAtNonFunctionalPosition(boolean commanderNear, boolean nearBase,
                                                             boolean insidePlayerBuilt) {
        return commanderNear || nearBase || insidePlayerBuilt;
    }
}
