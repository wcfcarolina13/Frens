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

import net.wcfcarolina13.PlayerUtils.InventoryIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /**
     * Extracts a single bundled entry back into the bot's inventory.
     *
     * <p>Rebuilds the bundle in {@code slot} without the entry at {@code bundleIndex} and inserts the
     * removed stack into the inventory via {@code PlayerInventory.insertStack}. If insertion fails
     * (inventory full), the removed stack is put back into the bundle and {@link Optional#empty()} is
     * returned, leaving the bot's inventory unchanged.
     *
     * <p><b>Server thread only</b> — mutates inventory and item components. Callers are responsible
     * for scheduling (see {@code NavigationArtifactService}'s food-extraction path for the pattern).
     *
     * <p>Not covered by the JUnit suite: the project's test policy forbids {@code net.minecraft.*}
     * types in tests, so this method is verified in-game only. The pure traversal it builds on is
     * tested via {@code InventoryIteratorTest}.
     *
     * @return the extracted stack (a copy of the bundle entry) on success, empty otherwise
     */
    public static Optional<ItemStack> extract(ServerPlayerEntity bot, int slot, int bundleIndex) {
        if (bot == null || bundleIndex < 0) {
            return Optional.empty();
        }
        PlayerInventory inventory = bot.getInventory();
        if (inventory == null || slot < 0 || slot >= inventory.size()) {
            return Optional.empty();
        }
        ItemStack bundleStack = inventory.getStack(slot);
        if (bundleStack.isEmpty()) {
            return Optional.empty();
        }
        BundleContentsComponent contents = bundleStack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (contents == null || contents.size() <= 0) {
            return Optional.empty();
        }

        List<ItemStack> remaining = new ArrayList<>();
        ItemStack target = ItemStack.EMPTY;
        int idx = 0;
        for (ItemStack bundled : contents.iterate()) {
            if (idx == bundleIndex && target.isEmpty()) {
                target = bundled.copy();
            } else {
                remaining.add(bundled.copy());
            }
            idx++;
        }
        if (target.isEmpty()) {
            return Optional.empty();
        }

        BundleContentsComponent.Builder builder =
                new BundleContentsComponent.Builder(BundleContentsComponent.DEFAULT);
        for (ItemStack item : remaining) {
            builder.add(item);
        }
        bundleStack.set(DataComponentTypes.BUNDLE_CONTENTS, builder.build());

        ItemStack extracted = target.copy();
        if (!inventory.insertStack(target)) {
            // Roll back: restore the original bundle contents untouched.
            bundleStack.set(DataComponentTypes.BUNDLE_CONTENTS, contents);
            LOGGER.debug("Bundle extract failed for {}: inventory full", bot.getName().getString());
            return Optional.empty();
        }
        return Optional.of(extracted);
    }

    /**
     * Finds the first <em>bundled</em> stack matching {@code match} and extracts it.
     *
     * <p><b>Server thread only</b> (see {@link #extract}). Not unit-testable under the project's
     * no-{@code net.minecraft.*}-in-tests policy.
     *
     * @return the direct inventory slot the extracted stack landed in, or empty if nothing matched
     *         or the extraction/insertion failed
     */
    public static Optional<Integer> extractFirst(ServerPlayerEntity bot, java.util.function.Predicate<ItemStack> match) {
        if (bot == null || match == null) {
            return Optional.empty();
        }
        var hit = InventoryIterator.stream(bot)
                .filter(ref -> !ref.isDirect())
                .filter(ref -> match.test(ref.stack()))
                .findFirst();
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        InventoryIterator.SlotRef<ItemStack> ref = hit.get();
        Optional<ItemStack> extracted = extract(bot, ref.slot(), ref.bundleIndex());
        if (extracted.isEmpty()) {
            return Optional.empty();
        }
        ItemStack wanted = extracted.get();
        PlayerInventory inventory = bot.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack candidate = inventory.getStack(i);
            if (candidate.isEmpty() || !ItemStack.areItemsAndComponentsEqual(candidate, wanted)) {
                continue;
            }
            if (candidate.getCount() >= wanted.getCount()) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
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
