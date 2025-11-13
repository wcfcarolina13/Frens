package net.shasankp000.ui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

public class BotMainInventoryView implements Inventory {
    private final ServerPlayerEntity bot;
    private final PlayerInventory inv;

    public BotMainInventoryView(ServerPlayerEntity bot) {
        this.bot = bot;
        this.inv = bot.getInventory();
    }

    @Override public int size() { return 27; }

    private static int map(int slot) { return 9 + slot; } // 0..26 -> 9..35

    @Override public boolean isEmpty() {
        for (int i = 0; i < 27; i++) if (!getStack(i).isEmpty()) return false;
        return true;
    }

    @Override public ItemStack getStack(int slot) { return inv.getStack(map(slot)); }

    @Override public ItemStack removeStack(int slot, int amount) {
        ItemStack s = inv.getStack(map(slot));
        if (s.isEmpty()) return ItemStack.EMPTY;
        int take = Math.min(amount, s.getCount());
        ItemStack out = s.copy(); out.setCount(take);
        s.decrement(take);
        markDirty();
        return out;
    }

    @Override public ItemStack removeStack(int slot) {
        int idx = map(slot);
        ItemStack s = inv.getStack(idx);
        inv.setStack(idx, ItemStack.EMPTY);
        markDirty();
        return s;
    }

    @Override public void setStack(int slot, ItemStack stack) {
        inv.setStack(map(slot), stack);
        markDirty();
    }

    @Override public void markDirty() { /* PlayerInventory + handler syncs changes */ }

    @Override public boolean canPlayerUse(PlayerEntity player) {
        if (player.getEntityWorld() != bot.getEntityWorld()) return false;
        return player.squaredDistanceTo(bot) <= 64.0; // 8 blocks
    }

    @Override public void clear() {
        for (int i = 0; i < 27; i++) inv.setStack(map(i), ItemStack.EMPTY);
    }
}