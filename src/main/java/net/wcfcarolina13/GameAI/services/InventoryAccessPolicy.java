package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Frens;

public final class InventoryAccessPolicy {
    private InventoryAccessPolicy() {}

    public static boolean canOpen(ServerPlayerEntity viewer, ServerPlayerEntity bot) {
        if (viewer == null || bot == null) return false;
        // Operators always allowed
        if (Frens.isOperator(viewer)) return true;

        // Ownership check — owner or un-owned bot only
        if (!CompanionCommunicationPolicy.isAllowedToControl(viewer, bot)) return false;

        // Same world + within 8 blocks (64.0 squared)
        if (viewer.getEntityWorld() != bot.getEntityWorld()) return false;
        if (viewer.squaredDistanceTo(bot) > 64.0) return false;

        return true;
    }
}
