package net.wcfcarolina13.GameAI.services;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.BeesComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.commons.lang3.math.Fraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class BundleService {
    private static final Logger LOGGER = LoggerFactory.getLogger("bundle-service");

    private BundleService() {}

    public static boolean packInventory(ServerCommandSource source, ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null) {
            return false;
        }
        if (!isInventoryFull(bot)) {
            return false;
        }
        boolean packed = packExistingBundles(bot);
        if (packed && !isInventoryFull(bot)) {
            return true;
        }
        if (source == null) {
            return packed;
        }
        ServerPlayerEntity historyOwner = commander != null ? commander : bot;
        if (!ToolProvisionService.ensureBundle(bot, source, historyOwner, 1)) {
            return packed;
        }
        return packExistingBundles(bot) && !isInventoryFull(bot);
    }

    public static boolean packExistingBundles(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        PlayerInventory inventory = bot.getInventory();
        List<Integer> bundleSlots = findBundleSlots(inventory);
        if (bundleSlots.isEmpty()) {
            return false;
        }
        int emptyBefore = countEmptySlots(inventory);
        boolean changed = false;
        for (int bundleSlot : bundleSlots) {
            ItemStack bundleStack = inventory.getStack(bundleSlot);
            if (bundleStack.isEmpty()) {
                continue;
            }
            if (packIntoBundle(bot, inventory, bundleStack, bundleSlot)) {
                changed = true;
            }
            if (countEmptySlots(inventory) > emptyBefore) {
                break;
            }
        }
        if (changed) {
            LOGGER.info("Bundled items for {}. Empty slots: {} -> {}",
                    bot.getName().getString(), emptyBefore, countEmptySlots(inventory));
        }
        return changed;
    }

    private static boolean packIntoBundle(ServerPlayerEntity bot,
                                          PlayerInventory inventory,
                                          ItemStack bundleStack,
                                          int bundleSlot) {
        BundleContentsComponent contents = bundleStack.getOrDefault(
                DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
        BundleContentsComponent.Builder builder = new BundleContentsComponent.Builder(contents);
        boolean changed = false;
        if (tryPackFullStacks(bot, inventory, builder, bundleSlot)) {
            changed = true;
        }
        if (isInventoryFull(bot)) {
            if (tryPackPartialStacks(bot, inventory, builder, bundleSlot)) {
                changed = true;
            }
        }
        if (changed) {
            bundleStack.set(DataComponentTypes.BUNDLE_CONTENTS, builder.build());
        }
        return changed;
    }

    private static boolean tryPackFullStacks(ServerPlayerEntity bot,
                                             PlayerInventory inventory,
                                             BundleContentsComponent.Builder builder,
                                             int bundleSlot) {
        List<BundleCandidate> candidates = collectCandidates(inventory, bundleSlot);
        if (candidates.isEmpty()) {
            return false;
        }
        candidates.sort(java.util.Comparator.comparingDouble(c -> c.stackOccupancy().doubleValue()));
        boolean changed = false;
        for (BundleCandidate candidate : candidates) {
            ItemStack stack = inventory.getStack(candidate.slot());
            if (!isBundlable(stack)) {
                continue;
            }
            Fraction remaining = Fraction.ONE.subtract(builder.getOccupancy());
            if (remaining.doubleValue() + 1.0E-6 < candidate.stackOccupancy().doubleValue()) {
                continue;
            }
            ItemStack insert = stack.copy();
            int added = builder.add(insert);
            if (added <= 0 || added < stack.getCount()) {
                continue;
            }
            stack.decrement(added);
            if (stack.isEmpty()) {
                inventory.setStack(candidate.slot(), ItemStack.EMPTY);
            }
            changed = true;
            if (!isInventoryFull(bot)) {
                break;
            }
        }
        return changed;
    }

    private static boolean tryPackPartialStacks(ServerPlayerEntity bot,
                                                PlayerInventory inventory,
                                                BundleContentsComponent.Builder builder,
                                                int bundleSlot) {
        List<BundleCandidate> candidates = collectCandidates(inventory, bundleSlot);
        if (candidates.isEmpty()) {
            return false;
        }
        candidates.sort(java.util.Comparator.comparingDouble(c -> c.perItemOccupancy().doubleValue()));
        boolean changed = false;
        for (BundleCandidate candidate : candidates) {
            ItemStack stack = inventory.getStack(candidate.slot());
            if (!isBundlable(stack)) {
                continue;
            }
            ItemStack insert = stack.copy();
            int added = builder.add(insert);
            if (added <= 0) {
                continue;
            }
            int toRemove = Math.min(added, stack.getCount());
            stack.decrement(toRemove);
            if (stack.isEmpty()) {
                inventory.setStack(candidate.slot(), ItemStack.EMPTY);
            }
            changed = true;
            if (!isInventoryFull(bot)) {
                break;
            }
        }
        return changed;
    }

    private static List<BundleCandidate> collectCandidates(PlayerInventory inventory, int bundleSlot) {
        List<BundleCandidate> candidates = new ArrayList<>();
        if (inventory == null) {
            return candidates;
        }
        for (int i = 0; i < 36; i++) {
            if (i == bundleSlot) {
                continue;
            }
            ItemStack stack = inventory.getStack(i);
            if (!isBundlable(stack)) {
                continue;
            }
            Fraction perItem = occupancyPerItem(stack);
            Fraction total = perItem.multiplyBy(Fraction.getFraction(stack.getCount(), 1));
            candidates.add(new BundleCandidate(i, perItem, total));
        }
        return candidates;
    }

    private record BundleCandidate(int slot, Fraction perItemOccupancy, Fraction stackOccupancy) {}

    private static Fraction occupancyPerItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Fraction.ONE;
        }
        BeesComponent bees = stack.getOrDefault(DataComponentTypes.BEES, BeesComponent.DEFAULT);
        if (bees != null && bees.bees() != null && !bees.bees().isEmpty()) {
            return Fraction.ONE;
        }
        int max = Math.max(1, stack.getMaxCount());
        return Fraction.getFraction(1, max);
    }

    private static boolean isBundlable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof BundleItem) {
            return false;
        }
        if (stack.getMaxCount() <= 1) {
            return false;
        }
        return BundleContentsComponent.canBeBundled(stack);
    }

    private static List<Integer> findBundleSlots(PlayerInventory inventory) {
        List<Integer> slots = new ArrayList<>();
        if (inventory == null) {
            return slots;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BundleItem) {
                slots.add(i);
            }
        }
        return slots;
    }

    private static boolean isInventoryFull(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        return player.getInventory().getEmptySlot() == -1;
    }

    private static int countEmptySlots(PlayerInventory inventory) {
        if (inventory == null) {
            return 0;
        }
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (inventory.getStack(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }
}
