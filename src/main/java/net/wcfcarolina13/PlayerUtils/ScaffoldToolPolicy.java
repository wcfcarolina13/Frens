package net.wcfcarolina13.PlayerUtils;

/**
 * Pure decision for which tool keyword to hand {@code BotActions.selectHarvestToolOrHands} when the
 * bot tears down a scaffold block.
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class ScaffoldToolPolicy {

    private ScaffoldToolPolicy() {}

    /**
     * Scaffold teardown breaks are instant, so a tool only adds wear, except on blocks that need a
     * tool to drop at all (stone, cobblestone, cobbled deepslate, netherrack). Those get a pickaxe,
     * everything else an empty hand or a harmless item ({@code null}).
     */
    public static String teardownKeyword(boolean toolRequiredToDrop) {
        return toolRequiredToDrop ? "pickaxe" : null;
    }
}
