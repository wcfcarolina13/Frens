package net.wcfcarolina13.GameAI.skills.impl;

import java.util.Map;

/**
 * Why one beehive was or was not harvested by {@code honey_collect}, and the player line for a run
 * that harvested nothing. Pure (no Minecraft types) so the wording and the reach geometry are tested.
 */
public enum HoneyHarvestOutcome {
    /** Not a full, smoked hive any more (bees took it back, someone else harvested, smoke went out). */
    NOT_HARVESTABLE,
    /** No stand cell within reach of the hive could be walked to. */
    UNREACHABLE,
    /** Shears path with no free slot for the honeycombs, even after packing and depositing. */
    INVENTORY_FULL,
    /** The bottle or shears could not be put in the main hand. */
    EQUIP_FAILED,
    /** The block interaction did not accept the use. */
    INTERACT_REJECTED,
    /** Honey collected. */
    HARVESTED;

    public static final String NO_HIVES = "No harvestable beehives nearby.";
    public static final String COULD_NOT_REACH = "Couldn't reach any beehives.";
    public static final String COULD_NOT_HARVEST = "Couldn't harvest any beehives.";

    /** Vanilla survival block reach, squared (matches BlockInteractionService.SURVIVAL_REACH_SQ). */
    static final double REACH_SQ = 20.25D;
    /** Standing eye height above the feet block's floor (line-of-sight ray origin). */
    static final double EYE_HEIGHT = 1.62D;

    /**
     * The player line for a run with no harvest, from the per-hive outcomes.
     * Any rejected interaction says the harvest failed. Every hive unreachable says so. Every hive
     * no longer harvestable (or no hive at all) says none are harvestable. Any other mix: the harvest failed.
     */
    public static String failureMessage(Map<HoneyHarvestOutcome, Integer> outcomes) {
        if (outcomes == null || total(outcomes) == 0) {
            return NO_HIVES;
        }
        if (count(outcomes, INTERACT_REJECTED) > 0) {
            return COULD_NOT_HARVEST;
        }
        int total = total(outcomes);
        if (count(outcomes, UNREACHABLE) == total) {
            return COULD_NOT_REACH;
        }
        if (count(outcomes, NOT_HARVESTABLE) == total) {
            return NO_HIVES;
        }
        return COULD_NOT_HARVEST;
    }

    /**
     * True when a bot standing at the centre of a feet cell passes the reach half of
     * {@code BlockInteractionService.canInteract}: the squared distance from the player's position
     * (the feet, at the cell's floor centre) to the hive's block centre is at most {@link #REACH_SQ}.
     * {@code dx, dy, dz} are hive minus feet cell, in blocks, so the offset to the hive centre is
     * {@code (dx, dy + 0.5, dz)}.
     */
    public static boolean withinReach(int dx, int dy, int dz) {
        double vertical = dy + 0.5D;
        return (double) dx * dx + vertical * vertical + (double) dz * dz <= REACH_SQ;
    }

    /**
     * Whether {@code honey_collect} should walk to a chest with its honey: only when the main
     * inventory is still full after packing ({@code emptyMainSlots <= 0}) and there is honey
     * (honeycombs or honey bottles) loose in it to put away.
     */
    public static boolean shouldDeposit(int emptyMainSlots, int looseHoneyStacks) {
        return emptyMainSlots <= 0 && looseHoneyStacks > 0;
    }

    private static int count(Map<HoneyHarvestOutcome, Integer> outcomes, HoneyHarvestOutcome key) {
        Integer n = outcomes.get(key);
        return n == null ? 0 : Math.max(0, n);
    }

    private static int total(Map<HoneyHarvestOutcome, Integer> outcomes) {
        int sum = 0;
        for (HoneyHarvestOutcome o : values()) {
            sum += count(outcomes, o);
        }
        return sum;
    }
}
