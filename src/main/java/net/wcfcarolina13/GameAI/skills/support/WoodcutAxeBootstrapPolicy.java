package net.wcfcarolina13.GameAI.skills.support;

/**
 * Pure decisions for a woodcut that starts with no axe and nothing to make one from: the bot
 * punches logs bare-handed until it holds enough wood for a wooden axe, then crafts one.
 *
 * <p>Why the threshold matters: {@code WoodcutSkill} asks for an axe before every log. Asking after
 * the first punched log made {@code CraftingHelper} spend those planks on a crafting table, then
 * fail the sticks (with chat), and the table craft arms a 60 s cooldown. So the bot only asks once
 * the wood it holds covers the whole chain: the axe head, the sticks if it has fewer than two, and
 * a table if none is in reach.
 *
 * <p>No Minecraft types.
 */
public final class WoodcutAxeBootstrapPolicy {

    /** Planks in a wooden axe head. */
    public static final int AXE_HEAD_PLANKS = 3;
    /** Sticks in a wooden axe. */
    public static final int AXE_STICKS = 2;
    /** Planks for one craft of sticks (two planks make four sticks). */
    public static final int STICK_CRAFT_PLANKS = 2;
    /** Planks in a crafting table. */
    public static final int TABLE_PLANKS = 4;
    /** Planks from one log. */
    public static final int PLANKS_PER_LOG = 4;

    /** The one origin that may not bootstrap: an idle hobby run. */
    public static final String AMBIENT_ORIGIN = "ambient";

    public enum Step {
        /** The wood held covers the whole axe chain: craft now. */
        HAVE_ENOUGH_CRAFT,
        /** Not enough wood yet: keep punching logs bare-handed. */
        PUNCH_LOG
    }

    private WoodcutAxeBootstrapPolicy() {
    }

    /** Planks the wooden axe chain needs, given the sticks held and whether a table is in reach. */
    public static int planksNeeded(int sticks, boolean tableInReach) {
        return AXE_HEAD_PLANKS
                + (sticks >= AXE_STICKS ? 0 : STICK_CRAFT_PLANKS)
                + (tableInReach ? 0 : TABLE_PLANKS);
    }

    /** Planks the bot can make from what it holds. Negative counts read as zero. */
    public static int planksAvailable(int logs, int planks) {
        return Math.max(0, logs) * PLANKS_PER_LOG + Math.max(0, planks);
    }

    public static Step next(int logs, int planks, int sticks, boolean tableInReach) {
        return planksAvailable(logs, planks) >= planksNeeded(sticks, tableInReach)
                ? Step.HAVE_ENOUGH_CRAFT
                : Step.PUNCH_LOG;
    }

    /**
     * Whether a woodcut started from {@code origin} may punch a tree for its first axe. An idle
     * hobby may not: {@code HobbyBackoffPolicy} relies on "no axe" being a repeatable failure so a
     * bot that cannot cut trees retries at a human pace. Every other origin (a command, a hunt's
     * prerequisite, a system task, none) may.
     */
    public static boolean allowBootstrap(String origin) {
        return origin == null || !AMBIENT_ORIGIN.equalsIgnoreCase(origin.trim());
    }
}
