package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Walks every stack a bot has effective access to — main inventory, armor, offhand,
 * bundles, shulker-box contents, ender chest, and nested bundles/shulkers inside those.
 * Powers the navigation-artifact tier resolver (spyglass, torch/lantern, maps, compasses,
 * etc.) so the same scan surface applies regardless of where the player stashed the item.
 *
 * <p>Vanilla can't nest shulkers in shulkers, so recursion is naturally bounded.
 */
public final class ArtifactScanner {

    private ArtifactScanner() {}

    /** All stacks accessible to {@code bot}: main inventory + ender chest + nested containers. */
    public static Stream<ItemStack> stream(ServerPlayerEntity bot) {
        if (bot == null) {
            return Stream.empty();
        }
        PlayerInventory inv = bot.getInventory();
        Stream<ItemStack> main = IntStream.range(0, inv.size())
                .mapToObj(inv::getStack)
                .flatMap(ArtifactScanner::expandStack);

        EnderChestInventory ec = bot.getEnderChestInventory();
        Stream<ItemStack> ender = ec == null
                ? Stream.empty()
                : IntStream.range(0, ec.size())
                        .mapToObj(ec::getStack)
                        .flatMap(ArtifactScanner::expandStack);

        return Stream.concat(main, ender);
    }

    private static Stream<ItemStack> expandStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Stream.empty();
        }
        Stream<ItemStack> self = Stream.of(stack);

        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        Stream<ItemStack> bundled = bundle == null
                ? Stream.empty()
                : StreamSupport.stream(bundle.iterate().spliterator(), false)
                        .flatMap(ArtifactScanner::expandStack);

        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        Stream<ItemStack> contained;
        if (container != null
                && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            contained = container.streamNonEmpty()
                    .flatMap(ArtifactScanner::expandStack);
        } else {
            contained = Stream.empty();
        }

        return Stream.concat(self, Stream.concat(bundled, contained));
    }

    // ── Item probes ────────────────────────────────────────────────────────

    public static boolean has(ServerPlayerEntity bot, Item item) {
        if (bot == null || item == null) {
            return false;
        }
        return stream(bot).anyMatch(s -> s.isOf(item));
    }

    public static boolean hasAny(ServerPlayerEntity bot, Item... items) {
        if (bot == null || items == null || items.length == 0) {
            return false;
        }
        return stream(bot).anyMatch(s -> {
            for (Item item : items) {
                if (item != null && s.isOf(item)) {
                    return true;
                }
            }
            return false;
        });
    }

    public static boolean hasSpyglass(ServerPlayerEntity bot) {
        return has(bot, Items.SPYGLASS);
    }

    public static boolean hasTorchOrLantern(ServerPlayerEntity bot) {
        return hasAny(bot, Items.TORCH, Items.LANTERN);
    }

    public static boolean hasCompass(ServerPlayerEntity bot) {
        return has(bot, Items.COMPASS);
    }

    public static boolean hasMap(ServerPlayerEntity bot) {
        return has(bot, Items.FILLED_MAP);
    }

    // ── Map rendering check ────────────────────────────────────────────────

    /**
     * Does the bot have at least one filled map whose rendered pixel at {@code (x,z)}
     * in {@code dimension} is non-zero? Checks all accessible maps (inventory, bundles,
     * shulkers, ender chest).
     *
     * <p>Strict pixel match — matches the in-game realism that the bot must have
     * actually explored that block to have "mapped" it.
     */
    public static boolean hasRenderedMapAt(ServerPlayerEntity bot, int x, int z,
                                           RegistryKey<World> dimension) {
        if (bot == null || dimension == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        return stream(bot).anyMatch(stack -> mapCoversAndRenders(stack, x, z, dimension, world));
    }

    private static boolean mapCoversAndRenders(ItemStack stack, int x, int z,
                                               RegistryKey<World> dimension, ServerWorld world) {
        if (stack == null || !stack.isOf(Items.FILLED_MAP)) {
            return false;
        }
        MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
        if (id == null) {
            return false;
        }
        MapState state = FilledMapItem.getMapState(id, world);
        if (state == null || state.dimension == null || !state.dimension.equals(dimension)) {
            return false;
        }
        int blocksPerPixel = 1 << state.scale;
        int halfCoverage = 64 * blocksPerPixel;
        int dx = x - state.centerX;
        int dz = z - state.centerZ;
        if (dx < -halfCoverage || dx >= halfCoverage) {
            return false;
        }
        if (dz < -halfCoverage || dz >= halfCoverage) {
            return false;
        }
        int pixelX = Math.floorDiv(dx, blocksPerPixel) + 64;
        int pixelZ = Math.floorDiv(dz, blocksPerPixel) + 64;
        if (pixelX < 0 || pixelX >= 128 || pixelZ < 0 || pixelZ >= 128) {
            return false;
        }
        return state.colors[pixelZ * 128 + pixelX] != 0;
    }
}
