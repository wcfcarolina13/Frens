package net.wcfcarolina13.PlayerUtils;

/**
 * Pure decisions for which tool keyword to hand {@code BotActions.selectHarvestToolOrHands} when the
 * bot breaks a scaffold block. Callers pass the block's mineable tags, so dirt and planks no longer fall
 * through to a pickaxe by item-name matching.
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class ScaffoldToolPolicy {

    private ScaffoldToolPolicy() {}

    /**
     * Tool keyword for a block from its mineable tags, pickaxe first, then shovel, then axe.
     * {@code null} means no tool helps: use an empty hand or a harmless item.
     */
    public static String keywordFor(boolean pickaxeMineable, boolean shovelMineable, boolean axeMineable) {
        if (pickaxeMineable) {
            return "pickaxe";
        }
        if (shovelMineable) {
            return "shovel";
        }
        if (axeMineable) {
            return "axe";
        }
        return null;
    }

    /**
     * Scaffold teardown breaks are instant, so a tool only adds wear, except on blocks that need a
     * tool to drop at all (stone, cobblestone, cobbled deepslate, netherrack). Those get a pickaxe,
     * everything else an empty hand or a harmless item ({@code null}).
     */
    public static String teardownKeyword(boolean toolRequiredToDrop) {
        return toolRequiredToDrop ? "pickaxe" : null;
    }
}
