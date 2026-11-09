package net.wcfcarolina13.GameAI.services;

import java.util.Locale;
import java.util.Set;

/**
 * Which inventory items the bot may burn when it picks fuel on its own (the auto-cook, and
 * {@code /bot cook <item> auto}). Vanilla burns many crafted things, and the old cheapest-first
 * pick took carpets, wool, bowls, scaffolding, wooden tools, chests and crafting tables before
 * coal, because they burn for less time. Only plain fuel qualifies here; a fuel the player names
 * outright ({@code /bot cook beef lava_bucket}) is not limited by this.
 */
public final class AutoFuelPolicy {

    /** Item ids (path, without {@code minecraft:}) that are fuel and nothing else worth keeping. */
    static final Set<String> PLAIN_FUEL_IDS = Set.of(
            "leaf_litter", "dead_bush", "short_dry_grass", "tall_dry_grass",
            "stick", "coal", "charcoal", "coal_block", "dried_kelp_block");

    private AutoFuelPolicy() {}

    /**
     * True when the bot may burn this item without being told to: a plain fuel id, leaves,
     * saplings, a log that burns, or planks.
     */
    public static boolean isAutoFuel(String itemPath, boolean leaves, boolean sapling, boolean burnableLog, boolean planks) {
        if (leaves || sapling || burnableLog || planks) {
            return true;
        }
        if (itemPath == null) {
            return false;
        }
        String path = itemPath.trim().toLowerCase(Locale.ROOT);
        if (path.startsWith("minecraft:")) {
            path = path.substring("minecraft:".length());
        }
        return PLAIN_FUEL_IDS.contains(path);
    }
}
