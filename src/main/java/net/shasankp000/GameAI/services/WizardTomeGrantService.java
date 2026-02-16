package net.shasankp000.GameAI.services;

import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.items.ModItems;

/** Shared helper for granting Wizard's Tome items without duplicating inventory/drop logic. */
public final class WizardTomeGrantService {

    private WizardTomeGrantService() {
    }

    public static int normalizeAmount(int amount) {
        return Math.max(1, Math.min(64, amount));
    }

    /**
     * Grants the requested number of tomes to the player; drops overflow at player feet.
     *
     * @return actual amount processed
     */
    public static int grant(ServerPlayerEntity player, int amount) {
        if (player == null || player.isRemoved()) {
            return 0;
        }
        int n = normalizeAmount(amount);
        for (int i = 0; i < n; i++) {
            ItemStack stack = new ItemStack(ModItems.WIZARD_TOME);
            boolean inserted = player.getInventory().insertStack(stack);
            if (!inserted && !stack.isEmpty()) {
                player.dropItem(stack, false);
            }
        }
        return n;
    }
}
