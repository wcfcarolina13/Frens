package net.wcfcarolina13.GameAI.services;

import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

public final class NavigationArtifactService {

    private NavigationArtifactService() {}

    public enum NavTier { NONE, BASIC, ENHANCED }

    /**
     * Determine navigation tier from bot + player inventories.
     * ENHANCED: either holds Eye of Ender.
     * BASIC: bot holds Compass, Recovery Compass, Map, or Filled Map.
     */
    public static NavTier getBotNavigationTier(ServerPlayerEntity bot, ServerPlayerEntity player) {
        if (hasItemInInventory(bot, Items.ENDER_EYE) || hasItemInInventory(player, Items.ENDER_EYE)) {
            return NavTier.ENHANCED;
        }
        if (hasItemInInventory(bot, Items.COMPASS)
                || hasItemInInventory(bot, Items.RECOVERY_COMPASS)
                || hasItemInInventory(bot, Items.FILLED_MAP)
                || hasItemInInventory(bot, Items.MAP)) {
            return NavTier.BASIC;
        }
        return NavTier.NONE;
    }

    /** Check if both player and bot each hold at least one ender pearl. */
    public static boolean bothHaveEnderPearl(ServerPlayerEntity player, ServerPlayerEntity bot) {
        return hasItemInInventory(player, Items.ENDER_PEARL)
                && hasItemInInventory(bot, Items.ENDER_PEARL);
    }

    /** Check if both hold an ender pearl AND a chorus fruit. */
    public static boolean bothHaveChorusRecallItems(ServerPlayerEntity player, ServerPlayerEntity bot) {
        return hasItemInInventory(player, Items.ENDER_PEARL)
                && hasItemInInventory(player, Items.CHORUS_FRUIT)
                && hasItemInInventory(bot, Items.ENDER_PEARL)
                && hasItemInInventory(bot, Items.CHORUS_FRUIT);
    }

    /** Consume one item of a given type from the player's inventory. Returns true if consumed. */
    public static boolean consumeItem(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(item)) {
                stack.decrement(1);
                if (stack.isEmpty()) inv.setStack(i, net.minecraft.item.ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    private static boolean hasItemInInventory(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(item)) return true;
        }
        return false;
    }
}
