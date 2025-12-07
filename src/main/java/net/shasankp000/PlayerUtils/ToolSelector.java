package net.shasankp000.PlayerUtils;

import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ToolSelector {

    private static final Logger LOGGER = LoggerFactory.getLogger("tool-selector");

    public static ItemStack selectBestToolForBlock(ServerPlayerEntity bot, BlockState blockState) {
        List<ItemStack> hotbarItems = hotBarUtils.getHotbarItems(bot);
        ItemStack bestTool = ItemStack.EMPTY;
        float highestSpeed = 0.0f;
        int bestHotbarSlot = -1;

        if (blockState != null && blockState.isIn(BlockTags.LEAVES)) {
            ItemStack shears = findShears(bot);
            if (!shears.isEmpty()) {
                return shears;
            }
            return hotBarUtils.getSelectedHotbarItemStack(bot);
        }

        // First, search hotbar (slots 0-8)
        for (int i = 0; i < hotbarItems.size(); i++) {
            ItemStack item = hotbarItems.get(i);
            if (item.isEmpty()) continue;

            float speed = item.getMiningSpeedMultiplier(blockState);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                bestTool = item;
                bestHotbarSlot = i;
            }
        }

        // Then search main inventory (slots 9-35) for better tools
        int bestMainSlot = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack item = bot.getInventory().getStack(i);
            if (item.isEmpty()) continue;

            float speed = item.getMiningSpeedMultiplier(blockState);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                bestTool = item;
                bestMainSlot = i;
            }
        }

        // If best tool is in main inventory, swap it to an empty hotbar slot or current slot
        if (bestMainSlot != -1) {
            int targetHotbarSlot = bot.getInventory().getSelectedSlot();
            
            // Try to find an empty hotbar slot first
            for (int i = 0; i < 9; i++) {
                if (bot.getInventory().getStack(i).isEmpty()) {
                    targetHotbarSlot = i;
                    break;
                }
            }
            
            LOGGER.info("Swapping best tool from main inventory slot {} to hotbar slot {}", bestMainSlot, targetHotbarSlot);
            
            // Swap the items (main inventory slot -> hotbar slot)
            bot.currentScreenHandler.onSlotClick(bestMainSlot, targetHotbarSlot, SlotActionType.SWAP, bot);
            
            // Get the swapped item which is now in hotbar
            bestTool = bot.getInventory().getStack(targetHotbarSlot);
            bestHotbarSlot = targetHotbarSlot;
        }

        // If no tool with speed > 1.0 was found, use current selection
        if (highestSpeed <= 1.0f) {
            return hotBarUtils.getSelectedHotbarItemStack(bot);
        }

        return bestTool;
    }

    private static ItemStack findShears(ServerPlayerEntity bot) {
        if (bot == null) {
            return ItemStack.EMPTY;
        }
        // Check hotbar first
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isOf(Items.SHEARS)) {
                return stack;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isOf(Items.SHEARS)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
