package net.shasankp000.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.BlockHitResult;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;

public final class ChestStoreService {

    private ChestStoreService() {}

    public static int handleDeposit(ServerCommandSource source, ServerPlayerEntity bot, String amountRaw, String itemRaw) {
        ChestBlockEntity chest = resolveChest(source);
        if (chest == null || bot == null) {
            ChatUtils.sendSystemMessage(source, "Look at a chest within reach.");
            return 0;
        }
        int amount = parseAmount(amountRaw, Integer.MAX_VALUE);
        String itemName = itemRaw.toLowerCase();
        int moved = moveItems(bot.getInventory(), chest, itemName, amount, true);
        ChatUtils.sendSystemMessage(source, moved > 0 ? "Deposited " + moved + " " + itemName + "." : "No matching items to deposit.");
        return moved > 0 ? 1 : 0;
    }

    public static int handleWithdraw(ServerCommandSource source, ServerPlayerEntity bot, String amountRaw, String itemRaw) {
        ChestBlockEntity chest = resolveChest(source);
        if (chest == null || bot == null) {
            ChatUtils.sendSystemMessage(source, "Look at a chest within reach.");
            return 0;
        }
        int amount = parseAmount(amountRaw, Integer.MAX_VALUE);
        String itemName = itemRaw.toLowerCase();
        int moved = moveItems(chest, bot.getInventory(), itemName, amount, false);
        ChatUtils.sendSystemMessage(source, moved > 0 ? "Withdrew " + moved + " " + itemName + "." : "Chest doesn't have that item.");
        return moved > 0 ? 1 : 0;
    }

    private static int parseAmount(String raw, int fallback) {
        if ("all".equalsIgnoreCase(raw)) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static ChestBlockEntity resolveChest(ServerCommandSource source) {
        var player = source.getPlayer();
        if (player == null) return null;
        var hit = player.raycast(6.0D, 1.0F, false);
        if (hit instanceof BlockHitResult bhr) {
            BlockPos pos = bhr.getBlockPos();
            var state = source.getWorld().getBlockState(pos);
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                var be = source.getWorld().getBlockEntity(pos);
                if (be instanceof ChestBlockEntity chest) {
                    // Walk to chest (respect teleport pref)
                    MovementService.MovementPlan plan = new MovementService.MovementPlan(
                            MovementService.Mode.DIRECT, pos, pos, null, null, player.getHorizontalFacing());
                    MovementService.execute(source, player, plan);
                    return chest;
                }
            }
        }
        return null;
    }

    private static int moveItems(Inventory from, Inventory to, String itemName, int amount, boolean exactName) {
        int moved = 0;
        for (int i = 0; i < from.size() && moved < amount; i++) {
            ItemStack stack = from.getStack(i);
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString().toLowerCase();
            if (!name.contains(itemName)) continue;
            int toMove = Math.min(stack.getCount(), amount - moved);
            ItemStack split = stack.split(toMove);
            if (split.isEmpty()) {
                continue;
            }
            ItemStack remainder = insertInto(to, split);
            if (!remainder.isEmpty()) {
                // Put back what didn't fit
                stack.increment(remainder.getCount());
                from.setStack(i, stack);
                break;
            }
            moved += toMove;
        }
        return moved;
    }

    private static ItemStack insertInto(Inventory inv, ItemStack stack) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack slot = inv.getStack(i);
            if (slot.isEmpty()) {
                inv.setStack(i, stack.copy());
                return ItemStack.EMPTY;
            }
            if (ItemStack.areItemsEqual(slot, stack) && ItemStack.areEqual(slot, stack) && slot.getCount() < slot.getMaxCount()) {
                int canAdd = Math.min(slot.getMaxCount() - slot.getCount(), stack.getCount());
                slot.increment(canAdd);
                stack.decrement(canAdd);
                if (stack.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        return stack;
    }
}
