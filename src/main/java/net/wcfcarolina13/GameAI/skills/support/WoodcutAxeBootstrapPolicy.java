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

    /**
     * Failed axe crafts a run allows before it ends. Each attempt can craft a table and arm
     * CraftingHelper's 60 s table cooldown, so a bot on a slope must not retry at every new Y.
     */
    public static final int MAX_CRAFT_ATTEMPTS = 2;

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

    /** Why a first-axe craft with enough wood failed. */
    public enum CraftFailureCause {
        /** A crafting table was crafted (or held) but could not be put down where the bot stood. */
        TABLE_PLACEMENT,
        /** The wood ran short (something else ate it, or the count was wrong). */
        MISSING_WOOD,
        /** Anything else (sticks, the axe recipe itself). */
        OTHER
    }

    /** Where a first-axe craft failed (the bot's block position) and why. */
    public record CraftFailure(int x, int y, int z, CraftFailureCause cause) {
    }

    /**
     * The cause of a failed craft, read from the inventory afterwards. A crafting table still held
     * means the craft got as far as the table and only its placement failed (1.1.234 playthrough:
     * "bot-intersects-target" on flat ground). Otherwise, wood below the threshold means the wood
     * was missing; anything else is OTHER.
     */
    public static CraftFailureCause classifyCraftFailure(boolean holdsCraftingTable, boolean enoughWoodNow) {
        if (holdsCraftingTable) {
            return CraftFailureCause.TABLE_PLACEMENT;
        }
        return enoughWoodNow ? CraftFailureCause.OTHER : CraftFailureCause.MISSING_WOOD;
    }

    /**
     * Whether the bot may try the first-axe craft where it stands. Only on the ground: up a pillar
     * the crafting table has nowhere to go (1.1.233 playthrough: y127, "couldn't place it here"),
     * and the failure used to end the whole run. {@code grounded} is the entity's on-ground flag
     * (false mid-jump); {@code onOwnScaffold} is true when the block underfoot is one this run
     * placed. After a failure ({@code lastFailure} is {@code null} when nothing failed yet) a retry
     * is allowed at a different Y, and after a table-placement failure also from a different stand
     * cell at the same Y (x or z moved by at least one block): on flat ground the Y never changes,
     * and a new cell gives the table new neighbours.
     */
    public static boolean mayAttemptCraft(boolean grounded,
                                          boolean onOwnScaffold,
                                          CraftFailure lastFailure,
                                          int x,
                                          int y,
                                          int z) {
        if (!grounded || onOwnScaffold) {
            return false;
        }
        if (lastFailure == null || y != lastFailure.y()) {
            return true;
        }
        return lastFailure.cause() == CraftFailureCause.TABLE_PLACEMENT
                && (x != lastFailure.x() || z != lastFailure.z());
    }

    /**
     * Whether a recorded craft failure ends the run. Nothing failed: never. The attempt budget is
     * spent: always. A table-placement failure never ends it early: the bot only has to step to
     * another cell, which the next tree's approach does, and the loop's own failure and relocation
     * limits still bound the run. Otherwise only when the bot stands on real ground and still may
     * not retry (same Y as the failure), so a failure that happened off the ground waits for a
     * grounded retry.
     */
    public static boolean craftFailureEndsRun(int failedAttempts,
                                              boolean grounded,
                                              boolean onOwnScaffold,
                                              CraftFailure lastFailure,
                                              int x,
                                              int y,
                                              int z) {
        if (failedAttempts <= 0 || lastFailure == null) {
            return false;
        }
        if (failedAttempts >= MAX_CRAFT_ATTEMPTS) {
            return true;
        }
        if (lastFailure.cause() == CraftFailureCause.TABLE_PLACEMENT) {
            return false;
        }
        return grounded && !onOwnScaffold && !mayAttemptCraft(grounded, onOwnScaffold, lastFailure, x, y, z);
    }

    /**
     * Whether a bootstrap bot that just reached a tree's stand gathers hand scaffold again. The
     * gather at the run's start can come up short (1.1.234 playthrough: a new world's Spawn base
     * protects the cells around spawn), so the bot tries once more at each tree, which usually
     * stands outside any base. At most once per tree ({@code alreadyGatheredAtThisTree}).
     */
    public static boolean mayRegatherAtTree(boolean bootstrapActive,
                                            int handScaffoldHeld,
                                            int handScaffoldTarget,
                                            boolean alreadyGatheredAtThisTree) {
        return bootstrapActive && !alreadyGatheredAtThisTree && handScaffoldHeld < handScaffoldTarget;
    }

    /**
     * Whether the bot may pillar or place scaffold to reach a log. Not while it is bootstrapping
     * without an axe: the first wood goes to the axe on the ground, and logs that need a pillar
     * wait until there is one.
     */
    public static boolean mayPillar(boolean bootstrapActive, boolean hasAxe) {
        return !bootstrapActive || hasAxe;
    }

    /** Whether logs may be turned into plank scaffold. Never without an axe: that wood is the axe. */
    public static boolean mayConvertLogsToScaffold(boolean hasAxe) {
        return hasAxe;
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
