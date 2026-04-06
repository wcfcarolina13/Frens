package net.wcfcarolina13.ui;

import java.util.List;
import java.util.Optional;
import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.Util;
import net.minecraft.util.math.random.Random;

/**
 * Enchanting screen handler that operates on a <b>bot's</b> inventory and XP
 * while being viewed by a real player.
 *
 * <p>Extends vanilla {@link EnchantmentScreenHandler} so the client opens the
 * standard {@code EnchantmentScreen} with zero custom UI. The super's private
 * fields ({@code inventory}, {@code context}, {@code random}, {@code seed})
 * are inaccessible, so we keep parallel references.</p>
 */
public class BotEnchantmentScreenHandler extends EnchantmentScreenHandler {

    /**
     * Client-side factory for the custom screen handler type. On the client,
     * we create a vanilla {@link EnchantmentScreenHandler} that overrides
     * {@link #onButtonClick} to skip the local player level check — the server
     * does all real validation via the server-side handler.
     */
    public static BotEnchantmentScreenHandler clientFactory(int syncId, PlayerInventory playerInventory) {
        return new BotEnchantmentScreenHandler(syncId, playerInventory);
    }

    /** Client-only constructor — no bot reference, no table context. */
    private BotEnchantmentScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(syncId, playerInventory);
        this.bot = null;
        this.tableContext = ScreenHandlerContext.EMPTY;
        this.enchantSlots = null; // unused on client
    }

    @Override
    public ScreenHandlerType<?> getType() {
        return net.wcfcarolina13.Frens.BOT_ENCHANT_HANDLER;
    }

    private final ServerPlayerEntity bot;
    private final ScreenHandlerContext tableContext;
    private final Random enchantRandom = Random.create();
    // The super's SimpleInventory(2) is private. We can read slot contents
    // via this.slots.get(0) / this.slots.get(1), but for onClosed we need
    // a direct Inventory reference. We build one that delegates to the same slots.
    private final Inventory enchantSlots;

    public BotEnchantmentScreenHandler(int syncId, PlayerInventory botInventory,
                                       ScreenHandlerContext context, ServerPlayerEntity bot) {
        super(syncId, botInventory, context);
        this.bot = bot;
        this.tableContext = context;
        // The super constructor already created the 2-slot SimpleInventory and added
        // slots 0 (item) and 1 (lapis). We wrap access to those slots for onClosed.
        this.enchantSlots = new SimpleInventory(2) {
            @Override
            public ItemStack getStack(int slot) {
                return BotEnchantmentScreenHandler.this.slots.get(slot).getStack();
            }

            @Override
            public ItemStack removeStack(int slot) {
                ItemStack stack = BotEnchantmentScreenHandler.this.slots.get(slot).getStack().copy();
                BotEnchantmentScreenHandler.this.slots.get(slot).setStack(ItemStack.EMPTY);
                return stack;
            }

            @Override
            public ItemStack removeStack(int slot, int amount) {
                return BotEnchantmentScreenHandler.this.slots.get(slot).takeStack(amount);
            }

            @Override
            public void setStack(int slot, ItemStack stack) {
                BotEnchantmentScreenHandler.this.slots.get(slot).setStack(stack);
            }

            @Override
            public int size() {
                return 2;
            }

            @Override
            public boolean isEmpty() {
                return getStack(0).isEmpty() && getStack(1).isEmpty();
            }

            @Override
            public void markDirty() {
            }
        };
    }

    /**
     * Full reimplementation — vanilla captures the {@code player} param in a lambda
     * and uses it for XP checks, stat credits, and re-seeding. We substitute {@link #bot}.
     */
    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        if (id < 0 || id >= this.enchantmentPower.length) {
            return false;
        }

        // Client side: skip player-level checks entirely. The server handler
        // validates the bot's XP. We just need to confirm there's a valid
        // enchantment power so the click packet gets sent.
        if (bot == null) {
            return this.enchantmentPower[id] > 0
                    && !this.slots.get(0).getStack().isEmpty();
        }

        ItemStack itemStack = this.slots.get(0).getStack();
        ItemStack lapisStack = this.slots.get(1).getStack();
        int cost = id + 1;

        // Lapis check (bots are never in creative)
        if (lapisStack.isEmpty() || lapisStack.getCount() < cost) {
            return false;
        }

        // Level and power check against the BOT
        if (this.enchantmentPower[id] <= 0
                || itemStack.isEmpty()
                || bot.experienceLevel < cost
                || bot.experienceLevel < this.enchantmentPower[id]) {
            return false;
        }

        this.tableContext.run((world, pos) -> {
            List<EnchantmentLevelEntry> list = this.generateEnchantmentsForBot(
                    world.getRegistryManager(), itemStack, id, this.enchantmentPower[id]);

            if (!list.isEmpty()) {
                // Deduct XP from the BOT
                bot.applyEnchantmentCosts(itemStack, cost);

                ItemStack resultStack = itemStack;
                if (itemStack.isOf(Items.BOOK)) {
                    resultStack = itemStack.withItem(Items.ENCHANTED_BOOK);
                    this.slots.get(0).setStack(resultStack);
                }

                for (EnchantmentLevelEntry entry : list) {
                    resultStack.addEnchantment(entry.enchantment(), entry.level());
                }

                // Deduct lapis (bots are never creative, so always decrement)
                lapisStack.decrement(cost);
                if (lapisStack.isEmpty()) {
                    this.slots.get(1).setStack(ItemStack.EMPTY);
                }

                // Credit stats and advancements to the BOT
                bot.incrementStat(Stats.ENCHANT_ITEM);
                Criteria.ENCHANTED_ITEM.trigger(bot, resultStack, cost);

                // Re-seed from bot's enchantment seed
                this.enchantRandom.setSeed(bot.getEnchantingTableSeed());
                this.onContentChanged(this.enchantSlots);

                world.playSound(null, pos, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE,
                        SoundCategory.BLOCKS, 1.0F, world.random.nextFloat() * 0.1F + 0.9F);
            }
        });

        return true;
    }

    /**
     * Return leftover enchanting-slot items to the <b>bot's</b> inventory,
     * not the real player who is viewing the screen.
     */
    @Override
    public void onClosed(PlayerEntity player) {
        // Handle cursor stack for the real player (super.super)
        if (player instanceof ServerPlayerEntity) {
            ItemStack cursor = this.getCursorStack();
            if (!cursor.isEmpty()) {
                // Give cursor item back to the bot, not the real player
                if (!bot.getInventory().insertStack(cursor)) {
                    bot.dropItem(cursor, false);
                }
                this.setCursorStack(ItemStack.EMPTY);
            }
        }

        // Return enchanting slot contents to bot
        this.tableContext.run((world, pos) -> {
            for (int i = 0; i < 2; i++) {
                ItemStack stack = this.enchantSlots.removeStack(i);
                if (!stack.isEmpty()) {
                    if (!bot.getInventory().insertStack(stack)) {
                        bot.dropItem(stack, false);
                    }
                }
            }
        });
    }

    /**
     * Reimplementation of the private {@code generateEnchantments} method.
     * Uses our parallel {@link #enchantRandom} seeded from the bot's enchantment seed.
     */
    private List<EnchantmentLevelEntry> generateEnchantmentsForBot(
            DynamicRegistryManager registryManager, ItemStack stack, int slot, int level) {
        this.enchantRandom.setSeed(bot.getEnchantingTableSeed() + slot);

        Optional<RegistryEntryList.Named<Enchantment>> optional = registryManager
                .getOrThrow(RegistryKeys.ENCHANTMENT)
                .getOptional(EnchantmentTags.IN_ENCHANTING_TABLE);

        if (optional.isEmpty()) {
            return List.of();
        }

        List<EnchantmentLevelEntry> list = EnchantmentHelper.generateEnchantments(
                this.enchantRandom, stack, level, optional.get().stream());

        if (stack.isOf(Items.BOOK) && list.size() > 1) {
            list.remove(this.enchantRandom.nextInt(list.size()));
        }

        return list;
    }
}
