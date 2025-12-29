package net.shasankp000.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.AIPlayer;

public final class InventoryAccessPolicy {
    private InventoryAccessPolicy() {}

    public static boolean canOpen(ServerPlayerEntity viewer, ServerPlayerEntity bot) {
        if (viewer == null || bot == null) return false;
        // Operators always allowed
        if (AIPlayer.isOperator(viewer)) return true;

        // Same world + within 8 blocks (64.0 squared)
        if (viewer.getEntityWorld() != bot.getEntityWorld()) return false;
        if (viewer.squaredDistanceTo(bot) > 64.0) return false;

        // TODO: hook real ownership model here (e.g., BotOwnership.getOwner(bot) == viewer.getUuid())
        return true; // allow by default if nearby
    }
}
