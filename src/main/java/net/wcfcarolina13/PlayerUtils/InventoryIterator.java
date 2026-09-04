package net.wcfcarolina13.PlayerUtils;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Bundle-aware, slot-indexed traversal of a bot's player inventory.
 *
 * <p>Where {@code ArtifactScanner} yields a flat stream of stacks across inventory + ender chest +
 * bundles + shulkers, this iterator is a <em>slot-indexed</em> view over the player inventory only
 * (main + armor + offhand, i.e. {@code bot.getInventory().size()}). Every emitted element carries the
 * inventory slot it came from, and, for bundled items, the index within that bundle — enough
 * information for a caller to go back and extract the item (see
 * {@code BundleService.extract(ServerPlayerEntity, int, int)}).
 *
 * <p>No ender chest, no shulker boxes, no recursion into nested bundles (vanilla forbids bundles
 * inside bundles).
 *
 * <p>The traversal core ({@link #flatten}) is deliberately generic over the stack type so it can be
 * unit-tested without any {@code net.minecraft.*} types on the test classpath.
 */
public final class InventoryIterator {

    private InventoryIterator() {}

    /**
     * One located stack.
     *
     * @param slot        inventory slot index the stack (or its containing bundle) occupies
     * @param bundleIndex {@code -1} for a stack sitting directly in the slot; otherwise the 0-based
     *                    index of this entry inside the bundle occupying {@code slot}
     * @param stack       the stack itself (for bundled entries this is the bundle's live entry copy
     *                    as yielded by {@code BundleContentsComponent.iterate()} — treat as read-only)
     */
    public record SlotRef<T>(int slot, int bundleIndex, T stack) {
        /** @return true when this stack sits directly in an inventory slot (not inside a bundle). */
        public boolean isDirect() {
            return bundleIndex < 0;
        }
    }

    // ------------------------------------------------------------------
    // Generic traversal core (no Minecraft types — unit-testable)
    // ------------------------------------------------------------------

    /**
     * Flattens an indexed container into a stream of {@link SlotRef}s.
     *
     * <p>Ordering contract: slots are visited in index order {@code 0..size-1}. Empty slots are
     * skipped entirely. Each non-empty slot emits its direct stack first ({@code bundleIndex == -1}),
     * immediately followed by that stack's bundle children in order ({@code bundleIndex 0..n-1}).
     * Empty children are skipped but still consume their index, so a child's {@code bundleIndex}
     * always matches its position in the underlying bundle contents. No recursion: children are
     * never themselves expanded.
     *
     * @param size           number of slots
     * @param slotAt         slot index -&gt; stack (may return null / empty)
     * @param isEmpty        emptiness test for a stack
     * @param bundleChildren bundle contents of a stack, or an empty/null list when it is not a bundle
     */
    public static <T> Stream<SlotRef<T>> flatten(int size,
                                                 IntFunction<T> slotAt,
                                                 Predicate<T> isEmpty,
                                                 Function<T, List<T>> bundleChildren) {
        List<SlotRef<T>> out = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) {
            T stack = slotAt.apply(slot);
            if (stack == null || isEmpty.test(stack)) {
                continue;
            }
            out.add(new SlotRef<>(slot, -1, stack));
            List<T> children = bundleChildren == null ? null : bundleChildren.apply(stack);
            if (children == null || children.isEmpty()) {
                continue;
            }
            for (int i = 0; i < children.size(); i++) {
                T child = children.get(i);
                if (child == null || isEmpty.test(child)) {
                    continue;
                }
                out.add(new SlotRef<>(slot, i, child));
            }
        }
        return out.stream();
    }

    // ------------------------------------------------------------------
    // ItemStack API (what callers use)
    // ------------------------------------------------------------------

    /**
     * Streams every stack in the bot's inventory, bundle contents included.
     * See {@link #flatten} for the ordering contract.
     */
    public static Stream<SlotRef<ItemStack>> stream(ServerPlayerEntity bot) {
        if (bot == null || bot.getInventory() == null) {
            return Stream.empty();
        }
        var inventory = bot.getInventory();
        return flatten(inventory.size(),
                inventory::getStack,
                ItemStack::isEmpty,
                InventoryIterator::bundleContents);
    }

    /**
     * Total item count matching {@code match}, summing {@code getCount()} over both direct stacks
     * and bundled stacks.
     *
     * <p>Note: a bundle stack is itself tested against the predicate like any other direct stack.
     * It is only counted if the predicate says so — e.g. a predicate matching {@code Items.BUNDLE}
     * counts the bundle, while a predicate matching {@code Items.TORCH} counts the torches inside it
     * but not the bundle holding them.
     */
    public static int count(ServerPlayerEntity bot, Predicate<ItemStack> match) {
        if (match == null) {
            return 0;
        }
        return stream(bot).filter(ref -> match.test(ref.stack()))
                .mapToInt(ref -> ref.stack().getCount())
                .sum();
    }

    /** Total item count matching {@code match} in direct slots only (bundle contents ignored). */
    public static int countDirect(ServerPlayerEntity bot, Predicate<ItemStack> match) {
        if (match == null) {
            return 0;
        }
        return stream(bot).filter(SlotRef::isDirect)
                .filter(ref -> match.test(ref.stack()))
                .mapToInt(ref -> ref.stack().getCount())
                .sum();
    }

    /** First match in traversal order, direct or bundled. */
    public static Optional<SlotRef<ItemStack>> findFirst(ServerPlayerEntity bot, Predicate<ItemStack> match) {
        if (match == null) {
            return Optional.empty();
        }
        return stream(bot).filter(ref -> match.test(ref.stack())).findFirst();
    }

    /** @return the bundle contents of {@code stack}, or an empty list when it is not a bundle. */
    public static List<ItemStack> bundleContents(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        BundleContentsComponent contents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (contents == null || contents.size() <= 0) {
            return List.of();
        }
        List<ItemStack> out = new ArrayList<>(contents.size());
        for (ItemStack bundled : contents.iterate()) {
            out.add(bundled);
        }
        return out;
    }
}
