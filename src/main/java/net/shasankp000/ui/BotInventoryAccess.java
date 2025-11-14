package net.shasankp000.ui;

import net.minecraft.server.network.ServerPlayerEntity;

import net.minecraft.text.Text;

public final class BotInventoryAccess {
    private BotInventoryAccess() {}

    /** Unified entry point for opening the bot inventory UI. */
    public static boolean openBotInventory(ServerPlayerEntity viewer, ServerPlayerEntity bot) {
        if (viewer == null || bot == null) return false;
        if (viewer.getEntityWorld() != bot.getEntityWorld()) return false;
        if (viewer.squaredDistanceTo(bot) > 64.0) return false;

        viewer.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, player) ->
                        new net.shasankp000.ui.BotPlayerInventoryScreenHandler(syncId, playerInv, bot.getInventory(), bot),
                net.minecraft.text.Text.literal(bot.getName().getString() + "'s Inventory")
        ));
        return true;
    }
}
