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
    /** Standing eye height above the feet block's floor. */
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
     * True when a bot with its feet in a block cell can reach the centre of the hive.
     * {@code dx, dy, dz} are hive minus feet cell, in blocks. The eye sits {@link #EYE_HEIGHT}
     * above the feet cell's floor, the hive centre 0.5 above its own floor.
     */
    public static boolean withinReach(int dx, int dy, int dz) {
        double vertical = dy + 0.5D - EYE_HEIGHT;
        return (double) dx * dx + vertical * vertical + (double) dz * dz <= REACH_SQ;
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
