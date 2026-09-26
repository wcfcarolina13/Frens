package net.wcfcarolina13.ui;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.wcfcarolina13.EntityUtil;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.network.BotAccessGate;

public final class BotInventoryAccess {
    private BotInventoryAccess() {}

    /**
     * Unified entry point for opening the bot inventory UI. Only the bot's owner, an operator or
     * the integrated host ({@link BotAccessGate}); a non-op also needs the bot within 8 blocks.
     */
    public static boolean openBotInventory(ServerPlayerEntity viewer, ServerPlayerEntity bot) {
        if (viewer == null || bot == null) return false;
        if (!BotAccessGate.permits(viewer, bot, "inventory-open")) return false;
        // Admin/operator QoL: allow remote opens regardless of distance/dimension.
        if (!Frens.isOperator(viewer)) {
            if (viewer.getEntityWorld() != bot.getEntityWorld()) return false;
            if (viewer.squaredDistanceTo(bot) > 64.0) return false;
        }

        viewer.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, player) ->
                        new net.wcfcarolina13.ui.BotPlayerInventoryScreenHandler(syncId, playerInv, bot.getInventory(), bot),
                net.minecraft.text.Text.literal(EntityUtil.safeDisplayName(bot.getName().getString()) + "'s Inventory")
        ));
        return true;
    }

    /**
     * Remote inventory access — skips distance/dimension checks (full-access spell). Still only
     * for the bot's owner, an operator or the integrated host ({@link BotAccessGate}).
     */
    public static boolean openBotInventoryRemote(ServerPlayerEntity viewer, ServerPlayerEntity bot) {
        if (viewer == null || bot == null) return false;
        if (!BotAccessGate.permits(viewer, bot, "inventory-remote")) return false;
        viewer.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, player) ->
                        // remoteAuthorized: canUse keeps re-checking registration + ownership,
                        // but not the owner's 8-block proximity rule.
                        new net.wcfcarolina13.ui.BotPlayerInventoryScreenHandler(syncId, playerInv, bot.getInventory(), bot, true),
                net.minecraft.text.Text.literal(EntityUtil.safeDisplayName(bot.getName().getString()) + "'s Inventory")
        ));
        return true;
    }
}
