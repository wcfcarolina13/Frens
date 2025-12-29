package net.shasankp000.ui;

import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.shasankp000.AIPlayer;

public final class BotInventoryAccess {
    private BotInventoryAccess() {}

    /** Unified entry point for opening the bot inventory UI. */
    public static boolean openBotInventory(ServerPlayerEntity viewer, ServerPlayerEntity bot) {
        if (viewer == null || bot == null) return false;
        // Admin/operator QoL: allow remote opens regardless of distance/dimension.
        if (!AIPlayer.isOperator(viewer)) {
            if (viewer.getEntityWorld() != bot.getEntityWorld()) return false;
            if (viewer.squaredDistanceTo(bot) > 64.0) return false;
        }

        viewer.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, player) ->
                        new net.shasankp000.ui.BotPlayerInventoryScreenHandler(syncId, playerInv, bot.getInventory(), bot),
                net.minecraft.text.Text.literal(bot.getName().getString() + "'s Inventory")
        ));
        return true;
    }
}
