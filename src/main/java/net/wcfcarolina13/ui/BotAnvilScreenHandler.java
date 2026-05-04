package net.wcfcarolina13.ui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Anvil screen handler that operates on a <b>bot's</b> inventory and XP
 * while being viewed by a real player.
 *
 * <p>Extends vanilla {@link AnvilScreenHandler} so the client opens the
 * standard {@code AnvilScreen} with zero custom UI. Overrides XP checks
 * and item return to target the bot instead of the viewing player.</p>
 */
public class BotAnvilScreenHandler extends AnvilScreenHandler {

    private final ServerPlayerEntity bot;
    private final ScreenHandlerContext anvilContext;

    /**
     * Client-side factory — no bot reference. The client handler just needs
     * to bypass the XP level check so the output slot is clickable.
     */
    public static BotAnvilScreenHandler clientFactory(int syncId, PlayerInventory playerInventory) {
        return new BotAnvilScreenHandler(syncId, playerInventory);
    }

    /** Client-only constructor. */
    private BotAnvilScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(syncId, playerInventory);
        this.bot = null;
        this.anvilContext = ScreenHandlerContext.EMPTY;
    }

    /** Server constructor — bot's inventory is passed through to super. */
    public BotAnvilScreenHandler(int syncId, PlayerInventory botInventory,
                                  ScreenHandlerContext context, ServerPlayerEntity bot) {
        super(syncId, botInventory, context);
        this.bot = bot;
        this.anvilContext = context;
    }

    @Override
    public ScreenHandlerType<?> getType() {
        return net.wcfcarolina13.Frens.BOT_ANVIL_HANDLER;
    }

    /**
     * Diagnostic: log every slot interaction reaching the server-side handler so we can
     * see whether the output click ever arrives (and what action type).
     */
    @Override
    public void onSlotClick(int slotIndex, int button, net.minecraft.screen.slot.SlotActionType actionType, PlayerEntity player) {
        if (bot != null) {
            String slotDesc = "(invalid)";
            if (slotIndex >= 0 && slotIndex < this.slots.size()) {
                ItemStack stack = this.slots.get(slotIndex).getStack();
                slotDesc = "size=" + this.slots.size() + " stack=" + (stack.isEmpty() ? "<empty>" : (stack.getCount() + "x " + stack.getItem()));
            }
            net.wcfcarolina13.GameAI.services.BotAnvilEnchantDiagnostics.logSlotClick(
                    "anvil-onSlotClick", bot, player, slotIndex, button, actionType, slotDesc, this.getLevelCost());
        }
        super.onSlotClick(slotIndex, button, actionType, player);
    }

    /**
     * Client: always allow taking if cost > 0 (server validates).
     * Server: check the BOT's XP level instead of the clicking player's.
     */
    @Override
    protected boolean canTakeOutput(PlayerEntity player, boolean present) {
        if (bot == null) {
            // Client side — let the click through, server validates
            return this.getLevelCost() > 0;
        }
        int cost = this.getLevelCost();
        boolean enoughXp = bot.experienceLevel >= cost;
        boolean valid = cost > 0;
        boolean allowed = enoughXp && valid;
        if (!allowed) {
            net.wcfcarolina13.GameAI.services.BotAnvilEnchantDiagnostics.logCanTakeOutput(
                    "anvil-canTakeOutput-deny", bot, player, cost, present, enoughXp, valid);
            // Tell the viewing player why the click was rejected so they're not confused
            // by the client-side prediction snap-back.
            if (player instanceof ServerPlayerEntity sp) {
                String reason;
                if (!valid) {
                    reason = "no valid anvil output (incompatible inputs or zero cost)";
                } else {
                    reason = bot.getName().getString() + " has " + bot.experienceLevel
                            + " XP levels but needs " + cost;
                }
                net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
                        sp.getCommandSource(),
                        "§e[Anvil] " + reason + "§r");
            }
        } else {
            net.wcfcarolina13.GameAI.services.BotAnvilEnchantDiagnostics.logCanTakeOutput(
                    "anvil-canTakeOutput-allow", bot, player, cost, present, enoughXp, valid);
        }
        return allowed;
    }

    /**
     * Deducts XP from the bot, not the clicking player.
     *
     * <p>The vanilla {@code onTakeOutput} handles repair-item consumption, input
     * slot clearing, anvil block damage, and XP deduction — all using private fields.
     * Rather than duplicate that logic, we save the real player's XP, let super run
     * (which deducts from player and handles all private-field logic), then restore
     * the player's XP. The bot's XP is deducted separately.</p>
     */
    @Override
    protected void onTakeOutput(PlayerEntity player, ItemStack stack) {
        if (bot == null) {
            super.onTakeOutput(player, stack);
            return;
        }

        net.wcfcarolina13.GameAI.services.BotAnvilEnchantDiagnostics.logTakeOutput(
                "anvil-take-output-pre", bot, player, stack, this.getLevelCost(),
                this.input.getStack(0), this.input.getStack(1));

        // Deduct XP from the BOT (bots are never creative)
        bot.addExperienceLevels(-this.getLevelCost());

        // Save the real player's XP before super deducts from them
        int playerLevelsBefore = player.experienceLevel;
        float playerProgressBefore = player.experienceProgress;
        int playerTotalBefore = player.totalExperience;

        super.onTakeOutput(player, stack);

        // Restore the real player's XP (super incorrectly deducted from them)
        player.experienceLevel = playerLevelsBefore;
        player.experienceProgress = playerProgressBefore;
        player.totalExperience = playerTotalBefore;

        net.wcfcarolina13.GameAI.services.BotAnvilEnchantDiagnostics.logTakeOutput(
                "anvil-take-output-post", bot, player, stack, this.getLevelCost(),
                this.input.getStack(0), this.input.getStack(1));
    }

    /**
     * Return leftover input items to the bot's inventory, not the real player.
     *
     * <p>Vanilla {@link net.minecraft.screen.ForgingScreenHandler#onClosed} calls
     * {@code dropInventory(player, this.input)} which would give items to the
     * viewing player. We intercept to route items back to the bot.</p>
     */
    @Override
    public void onClosed(PlayerEntity player) {
        if (bot == null) {
            super.onClosed(player);
            return;
        }

        net.wcfcarolina13.GameAI.services.BotAnvilEnchantDiagnostics.logScreenClose(
                "anvil-onClosed-pre", bot, player, this.getCursorStack(),
                this.input.getStack(0), this.input.getStack(1));

        // Handle cursor stack — return to bot
        if (player instanceof ServerPlayerEntity) {
            ItemStack cursor = this.getCursorStack();
            if (!cursor.isEmpty()) {
                ItemStack attempt = cursor.copy();
                boolean inserted = bot.getInventory().insertStack(cursor);
                net.wcfcarolina13.GameAI.services.BotAnvilEnchantDiagnostics.logInsert(
                        "anvil-onClosed-cursor-insert", bot, attempt, inserted, cursor);
                if (!inserted) {
                    bot.dropItem(cursor, false);
                }
                this.setCursorStack(ItemStack.EMPTY);
            }
        }

        // Return input slot items to the bot
        // (ForgingScreenHandler.onClosed would drop them at the viewing player)
        this.anvilContext.run((world, pos) -> {
            for (int i = 0; i < this.input.size(); i++) {
                ItemStack stack = this.input.removeStack(i);
                if (!stack.isEmpty()) {
                    ItemStack attempt = stack.copy();
                    boolean inserted = bot.getInventory().insertStack(stack);
                    net.wcfcarolina13.GameAI.services.BotAnvilEnchantDiagnostics.logInsert(
                            "anvil-onClosed-input-insert-" + i, bot, attempt, inserted, stack);
                    if (!inserted) {
                        bot.dropItem(stack, false);
                    }
                }
            }
        });

        // Defensive: persist bot inventory to disk so any concurrent autosave or
        // join-restore path can't roll back what we just committed.
        if (!bot.isRemoved()) {
            net.wcfcarolina13.GameAI.services.BotInventoryStorageService.save(bot);
        }

        net.wcfcarolina13.GameAI.services.BotAnvilEnchantDiagnostics.logScreenClose(
                "anvil-onClosed-post", bot, player, this.getCursorStack(),
                this.input.getStack(0), this.input.getStack(1));
    }
}
