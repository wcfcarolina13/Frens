package net.wcfcarolina13.ui;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.wcfcarolina13.EntityUtil;
import net.wcfcarolina13.Frens;

public final class BotInventoryAccess {
    private BotInventoryAccess() {}

    /** Unified entry point for opening the bot inventory UI. */
    public static boolean openBotInventory(ServerPlayerEntity viewer, ServerPlayerEntity bot) {
        if (viewer == null || bot == null) return false;
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

    /** Remote inventory access — skips distance/dimension checks (full-access spell). */
    public static boolean openBotInventoryRemote(ServerPlayerEntity viewer, ServerPlayerEntity bot) {
        if (viewer == null || bot == null) return false;
        viewer.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, player) ->
                        new net.wcfcarolina13.ui.BotPlayerInventoryScreenHandler(syncId, playerInv, bot.getInventory(), bot),
                net.minecraft.text.Text.literal(EntityUtil.safeDisplayName(bot.getName().getString()) + "'s Inventory")
        ));
        return true;
    }
}
