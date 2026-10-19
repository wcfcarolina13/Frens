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
}
