package net.wcfcarolina13.PlayerUtils;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ToolSelector {

    private static final Logger LOGGER = LoggerFactory.getLogger("tool-selector");

    public static ItemStack selectBestToolForBlock(ServerPlayerEntity bot, BlockState blockState) {
        return selectBestToolForBlock(bot, blockState, true);
    }

    /**
     * @param allowBundleReach when true and no direct-slot tool beats bare hands, a strictly better
     *                         tool sitting inside a bundle is pulled into a direct slot
     *                         ({@code BundleService.reachFirst}, which hops to the server thread as
     *                         needed) and the scan is retried exactly once. Mining callers run on
     *                         {@code MiningTool}'s worker executor.
     */
    private static ItemStack selectBestToolForBlock(ServerPlayerEntity bot, BlockState blockState, boolean allowBundleReach) {
        List<ItemStack> hotbarItems = hotBarUtils.getHotbarItems(bot);
        ItemStack bestTool = ItemStack.EMPTY;
        float highestSpeed = 0.0f;
        int bestHotbarSlot = -1;
        ItemStack priorHeld = bot.getMainHandStack();
        boolean priorWasFiltered = !priorHeld.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, priorHeld);

        if (blockState != null && blockState.isIn(BlockTags.LEAVES)) {
            ItemStack shears = findShears(bot);
            if (!shears.isEmpty() && !DurabilityPolicyService.shouldAvoid(bot, shears)) {
                return shears;
            }
            if (!shears.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, shears)) {
                // Preserved shears below threshold — request fallback refresh, then fall through.
                DurabilityFallbackService.requestRefresh(
                        bot, DurabilityFallbackService.GearCategory.SHEARS);
            }
            return hotBarUtils.getSelectedHotbarItemStack(bot);
        }

        // First, search hotbar (slots 0-8)
        for (int i = 0; i < hotbarItems.size(); i++) {
            ItemStack item = hotbarItems.get(i);
            if (item.isEmpty() || isWeaponOnlyItem(item)) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, item)) continue;

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
            if (item.isEmpty() || isWeaponOnlyItem(item)) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, item)) continue;

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
            if (priorWasFiltered) {
                CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
            }

            // Get the swapped item which is now in hotbar
            bestTool = bot.getInventory().getStack(targetHotbarSlot);
            bestHotbarSlot = targetHotbarSlot;
        }

        // If no tool with speed > 1.0 was found, use current selection
        if (highestSpeed <= 1.0f) {
            // Bundle-aware last resort: a usable tool may be sitting inside a bundle, invisible to the
            // direct-slot scans above. Extract it only when it is strictly better than what we found.
            if (allowBundleReach) {
                ItemStack reached = reachBetterBundledTool(bot, blockState, highestSpeed);
                if (!reached.isEmpty()) {
                    return selectBestToolForBlock(bot, blockState, false);
                }
            }
            // If the reason nothing was found is that a preserved tool is below threshold,
            // request fallback refresh for the appropriate category.
            ItemStack held = hotBarUtils.getSelectedHotbarItemStack(bot);
            if (!held.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, held)) {
                DurabilityFallbackService.GearCategory cat = heldCategoryGuess(held);
                if (cat != null) {
                    DurabilityFallbackService.requestRefresh(bot, cat);
                }
            }

            ItemStack selected = hotBarUtils.getSelectedHotbarItemStack(bot);
            if (!selected.isEmpty() && !isWeaponOnlyItem(selected)) {
                return selected;
            }
            return findHarmlessHotbarFallback(bot);
        }

        return bestTool;
    }

    /**
     * Looks for a bundled tool that mines {@code blockState} strictly faster than {@code bestDirectSpeed}
     * and, if found, pulls it into a direct inventory slot. Bundled stacks yielded by
     * {@link InventoryIterator} are read-only views — they are never selected or equipped directly.
     *
     * @return the (non-empty) bundled candidate that was extracted, or {@link ItemStack#EMPTY}
     */
    private static ItemStack reachBetterBundledTool(ServerPlayerEntity bot, BlockState blockState, float bestDirectSpeed) {
        if (bot == null || blockState == null) {
            return ItemStack.EMPTY;
        }
        ItemStack bestBundled = ItemStack.EMPTY;
        float bestBundledSpeed = 0.0f;
        for (var ref : InventoryIterator.stream(bot).toList()) {
            if (ref.isDirect()) {
                continue;
            }
            ItemStack stack = ref.stack();
            if (stack.isEmpty() || isWeaponOnlyItem(stack)) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            float speed = stack.getMiningSpeedMultiplier(blockState);
            if (speed > bestBundledSpeed) {
                bestBundledSpeed = speed;
                bestBundled = stack;
            }
        }
        if (bestBundled.isEmpty() || bestBundledSpeed <= 1.0f) {
            return ItemStack.EMPTY;
        }
        // Compare on a common integer scale so the decision itself stays unit-testable.
        if (!BundleReachPolicy.shouldReachForBetter(
                Math.round(bestDirectSpeed * 100.0f), Math.round(bestBundledSpeed * 100.0f), false)) {
            return ItemStack.EMPTY;
        }
        final ItemStack wanted = bestBundled;
        boolean reached = net.wcfcarolina13.GameAI.services.BundleService.reachFirst(
                bot, stack -> ItemStack.areItemsAndComponentsEqual(stack, wanted));
        if (!reached) {
            return ItemStack.EMPTY;
        }
        LOGGER.info("Tool select: reached {} out of a bundle (speed {} > {})",
                wanted.getItem(), bestBundledSpeed, bestDirectSpeed);
        return wanted;
    }

    private static boolean isWeaponOnlyItem(ItemStack stack) {
        return BotActions.isCombatClassItem(stack);
    }

    private static ItemStack findHarmlessHotbarFallback(ServerPlayerEntity bot) {
        if (bot == null) {
            return ItemStack.EMPTY;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            if (!isWeaponOnlyItem(stack) && stack.getMiningSpeedMultiplier(Blocks.STONE.getDefaultState()) <= 1.0f) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
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

    private static DurabilityFallbackService.GearCategory heldCategoryGuess(ItemStack stack) {
        String key = stack.getItem().getTranslationKey().toLowerCase(java.util.Locale.ROOT);
        if (key.endsWith("_pickaxe")) return DurabilityFallbackService.GearCategory.PICKAXE;
        if (key.endsWith("_shovel"))  return DurabilityFallbackService.GearCategory.SHOVEL;
        if (key.endsWith("_hoe"))     return DurabilityFallbackService.GearCategory.HOE;
        if (key.endsWith("_axe"))     return DurabilityFallbackService.GearCategory.AXE;
        return null;
    }
}
