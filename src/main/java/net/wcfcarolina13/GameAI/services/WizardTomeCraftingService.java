package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.wcfcarolina13.items.ModItems;

/**
 * Server-side ritual crafting for the Wizard's Tome.
 */
public final class WizardTomeCraftingService {

    // 3x3 "full grid" total: 1 eye + 1 book + 1 diamond + 3 redstone + 3 lapis = 9
    private static final int NEED_EYE = 1;
    private static final int NEED_BOOK = 1;
    private static final int NEED_DIAMOND = 1;
    private static final int NEED_REDSTONE = 3;
    private static final int NEED_LAPIS = 3;

    private WizardTomeCraftingService() {
    }

    /**
     * Sneak-right-click an Enchanting Table in The End while holding an Eye of Ender to craft a Wizard's Tome.
     */
    public static ActionResult tryHandleUseBlock(ServerPlayerEntity player, ServerWorld world, Hand hand, BlockPos pos) {
        if (player == null || world == null || hand == null || pos == null) {
            return ActionResult.PASS;
        }
        if (!player.isSneaking()) {
            return ActionResult.PASS;
        }

        ItemStack held = player.getStackInHand(hand);
        if (held == null || held.isEmpty() || !held.isOf(Items.ENDER_EYE)) {
            return ActionResult.PASS;
        }

        BlockState state = world.getBlockState(pos);
        if (state == null || !state.isOf(Blocks.ENCHANTING_TABLE)) {
            return ActionResult.PASS;
        }

        if (world.getRegistryKey() != World.END) {
            player.sendMessage(net.minecraft.text.Text.literal("The Wizard's Tome can only be crafted in The End."), true);
            return ActionResult.SUCCESS;
        }

        int power = getEnchantingTableBookshelfPower(world, pos);
        if (power < 15) {
            player.sendMessage(net.minecraft.text.Text.literal("Your Enchanting Table isn't fully powered (need 15 bookshelves)."), true);
            return ActionResult.SUCCESS;
        }

        if (!hasIngredients(player)) {
            player.sendMessage(net.minecraft.text.Text.literal(
                    "Missing ingredients for Wizard's Tome: 1 Eye of Ender, 1 Book, 1 Diamond, 3 Redstone, 3 Lapis."), true);
            return ActionResult.SUCCESS;
        }

        // Consume ingredients.
        removeItems(player, Items.ENDER_EYE, NEED_EYE);
        removeItems(player, Items.BOOK, NEED_BOOK);
        removeItems(player, Items.DIAMOND, NEED_DIAMOND);
        removeItems(player, Items.REDSTONE, NEED_REDSTONE);
        removeItems(player, Items.LAPIS_LAZULI, NEED_LAPIS);

        ItemStack out = new ItemStack(ModItems.WIZARD_TOME);
        boolean inserted = player.getInventory().insertStack(out);
        if (!inserted && !out.isEmpty()) {
            player.dropItem(out, false);
        }

        // Enchantment sound effect
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 1.0f, 1.0f);

        // Magic particles around the player
        double px = player.getX();
        double py = player.getY() + 1.0;
        double pz = player.getZ();
        world.spawnParticles(ParticleTypes.ENCHANT, px, py, pz, 40, 0.6, 0.8, 0.6, 0.5);
        world.spawnParticles(ParticleTypes.END_ROD, px, py, pz, 12, 0.4, 0.6, 0.4, 0.02);

        player.sendMessage(net.minecraft.text.Text.literal("You craft a Wizard's Tome."), true);
        return ActionResult.SUCCESS;
    }

    private static boolean hasIngredients(ServerPlayerEntity player) {
        return countItem(player, Items.ENDER_EYE) >= NEED_EYE
                && countItem(player, Items.BOOK) >= NEED_BOOK
                && countItem(player, Items.DIAMOND) >= NEED_DIAMOND
                && countItem(player, Items.REDSTONE) >= NEED_REDSTONE
                && countItem(player, Items.LAPIS_LAZULI) >= NEED_LAPIS;
    }

    private static int countItem(ServerPlayerEntity player, Item item) {
        if (player == null || item == null) {
            return 0;
        }
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (s.isOf(item)) {
                total += s.getCount();
            }
        }
        return total;
    }

    private static void removeItems(ServerPlayerEntity player, Item item, int count) {
        if (player == null || item == null || count <= 0) {
            return;
        }
        int remaining = count;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (s == null || s.isEmpty() || !s.isOf(item)) {
                continue;
            }
            int take = Math.min(remaining, s.getCount());
            s.decrement(take);
            remaining -= take;
        }
    }

    /**
     * Computes the effective bookshelf power around an enchanting table, mirroring vanilla layout checks.
     */
    private static int getEnchantingTableBookshelfPower(ServerWorld world, BlockPos tablePos) {
        if (world == null || tablePos == null) {
            return 0;
        }
        int power = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                BlockPos gap0 = tablePos.add(dx, 0, dz);
                BlockPos gap1 = tablePos.add(dx, 1, dz);
                if (!world.isAir(gap0) || !world.isAir(gap1)) {
                    continue;
                }

                power += bookshelfAt(world, tablePos.add(dx * 2, 0, dz * 2));
                power += bookshelfAt(world, tablePos.add(dx * 2, 1, dz * 2));

                if (dx != 0 && dz != 0) {
                    power += bookshelfAt(world, tablePos.add(dx * 2, 0, dz));
                    power += bookshelfAt(world, tablePos.add(dx * 2, 1, dz));
                    power += bookshelfAt(world, tablePos.add(dx, 0, dz * 2));
                    power += bookshelfAt(world, tablePos.add(dx, 1, dz * 2));
                }
            }
        }
        return power;
    }

    private static int bookshelfAt(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return 0;
        }
        BlockState s = world.getBlockState(pos);
        return (s != null && s.isOf(Blocks.BOOKSHELF)) ? 1 : 0;
    }
}
