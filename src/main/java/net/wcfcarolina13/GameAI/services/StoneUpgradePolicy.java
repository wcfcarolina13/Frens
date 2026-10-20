package net.wcfcarolina13.GameAI.services;

/**
 * Whether the idle stone upgrade should craft a stone tool of one type. Pure so it can be unit
 * tested.
 */
public final class StoneUpgradePolicy {

    private StoneUpgradePolicy() {
    }

    /**
     * @param hasOnlyWooden the bot carries this tool type, and only at wooden tier
     * @param hasNone       the bot carries no tool of this type at all (the caller passes false
     *                      for types another fallback provisions)
     */
    public static boolean wants(boolean hasOnlyWooden, boolean hasNone) {
        return hasOnlyWooden || hasNone;
    }

    /**
     * What the rest of an idle upgrade batch still needs: {@code stone} counts cobblestone,
     * cobbled deepslate and blackstone together, {@code sticks} is sticks (planks make up a stick
     * shortfall, see {@link #planksForSticks}).
     */
    public record BatchReserve(int stone, int sticks) {
        public static final BatchReserve NONE = new BatchReserve(0, 0);

        public boolean isEmpty() {
            return stone <= 0 && sticks <= 0;
        }
    }

    /**
     * The stone heads and sticks the wanted stone tools take (pickaxe 3 + 2, sword 2 + 1, axe 3 + 2,
     * shovel 1 + 2). The make-room drop keeps that much while the batch runs, so a craft early in
     * the batch does not throw away the cobblestone a later one needs (finding 57, 1.1.279).
     */
    public static BatchReserve batchReserve(boolean pickaxe, boolean sword, boolean axe, boolean shovel) {
        int stone = (pickaxe ? 3 : 0) + (sword ? 2 : 0) + (axe ? 3 : 0) + (shovel ? 1 : 0);
        int sticks = (pickaxe ? 2 : 0) + (sword ? 1 : 0) + (axe ? 2 : 0) + (shovel ? 2 : 0);
        return new BatchReserve(stone, sticks);
    }

    /** Planks still needed to craft the missing sticks: 2 planks make 4 sticks. */
    public static int planksForSticks(int sticksNeeded, int sticksHeld) {
        int missing = sticksNeeded - Math.max(0, sticksHeld);
        if (missing <= 0) {
            return 0;
        }
        return 2 * ((missing + 3) / 4);
    }
}
