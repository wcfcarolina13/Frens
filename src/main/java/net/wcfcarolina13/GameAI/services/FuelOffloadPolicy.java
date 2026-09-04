package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure decision logic for the "no chest available — dump fuel into a furnace instead" fallback.
 *
 * <p>Deliberately free of any Minecraft imports so it can be unit-tested. The caller classifies
 * each inventory stack into a tier and this class decides which stacks (and how many of each)
 * are given away, in what order.
 *
 * <p>Tiers, cheapest first:
 * <ul>
 *   <li>0 — leaf litter (free; burns, no crafting value)</li>
 *   <li>1 — leaves / saplings</li>
 *   <li>2 — sticks</li>
 *   <li>3 — planks (only donated above the scaffold/build reserve)</li>
 *   <li>-1 — never give away</li>
 * </ul>
 */
public final class FuelOffloadPolicy {

    public static final int TIER_LEAF_LITTER = 0;
    public static final int TIER_LEAVES = 1;
    public static final int TIER_STICK = 2;
    public static final int TIER_PLANK = 3;
    public static final int TIER_NEVER = -1;

    private FuelOffloadPolicy() {}

    /**
     * One inventory stack considered for donation.
     *
     * @param itemId registry-ish identifier, for logging only
     * @param slot   inventory slot index (donation order is stable by slot within a tier)
     * @param count  stack size
     * @param tier   see class javadoc; -1 means never donate
     */
    public record Candidate(String itemId, int slot, int count, int tier) {}

    /**
     * Classifies a stack into a donation tier. First matching flag wins.
     */
    public static int tierFor(boolean leafLitter, boolean leavesOrSapling, boolean stick, boolean plank) {
        if (leafLitter) return TIER_LEAF_LITTER;
        if (leavesOrSapling) return TIER_LEAVES;
        if (stick) return TIER_STICK;
        if (plank) return TIER_PLANK;
        return TIER_NEVER;
    }

    /**
     * Builds the give-away list: cheapest tier first, stable slot order within a tier.
     *
     * <p>Planks are special: {@code plankReserve} planks are kept back for scaffolding/building.
     * The reserve is consumed in slot order, so the first plank stacks are (partially) withheld and
     * only the surplus is donated. Stacks with nothing left to give are omitted entirely.
     *
     * @param inventory    candidate stacks (any order); null/empty is tolerated
     * @param plankReserve number of planks to keep; negative is treated as 0
     * @return donations with {@code count} set to the amount to hand over; never null, possibly empty
     */
    public static List<Candidate> giveaways(List<Candidate> inventory, int plankReserve) {
        List<Candidate> out = new ArrayList<>();
        if (inventory == null || inventory.isEmpty()) {
            return out;
        }
        List<Candidate> sorted = new ArrayList<>(inventory);
        sorted.sort(Comparator.comparingInt(Candidate::tier).thenComparingInt(Candidate::slot));

        int remainingReserve = Math.max(0, plankReserve);
        for (Candidate c : sorted) {
            if (c == null || c.tier() == TIER_NEVER || c.tier() < 0 || c.count() <= 0) {
                continue;
            }
            int give = c.count();
            if (c.tier() == TIER_PLANK && remainingReserve > 0) {
                int withheld = Math.min(remainingReserve, c.count());
                remainingReserve -= withheld;
                give = c.count() - withheld;
            }
            if (give > 0) {
                out.add(new Candidate(c.itemId(), c.slot(), give, c.tier()));
            }
        }
        return out;
    }
}
