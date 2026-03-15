package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Commands.modCommandRegistry;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.ui.BotInventoryAccess;

/**
 * Server-side handler for guide-initiated remote bot inventory opens.
 * Both admins and non-admins can access the bot inventory via the guide.
 * Full inventory access (Actions/Dialogue/shared inventory) requires proximity,
 * operator status, or appropriate artifacts (Wizard's Tome / Enchanting Table).
 * Without these, only the Admin tab is usable.
 */
public final class GuideInventoryNetworkManager {

    private static volatile boolean REGISTERED = false;

    private GuideInventoryNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(GuideOpenInventoryPayload.ID, (payload, context) ->
                context.server().execute(() -> handle(context.server(), context.player(), payload))
        );
    }

    private static void handle(MinecraftServer server, ServerPlayerEntity player, GuideOpenInventoryPayload payload) {
        if (server == null || player == null || player.isRemoved()) {
            return;
        }
        // Only real players can use this.
        if (player instanceof createFakePlayer) {
            return;
        }

        String botAlias = payload != null && payload.botAlias() != null && !payload.botAlias().isBlank()
                ? payload.botAlias().trim()
                : null;
        if (botAlias == null) {
            return;
        }

        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botAlias);
        if (bot == null) {
            return;
        }

        // Determine full inventory access: proximity or Remote Inventory spell artifacts.
        // Operator status is intentionally NOT included — in singleplayer the host is always
        // an operator, which would defeat the purpose of access gating. Operators who want
        // full remote access should carry a Wizard's Tome like everyone else.
        boolean nearby = player.getEntityWorld() == bot.getEntityWorld()
                && player.squaredDistanceTo(bot) <= 64.0;
        boolean hasSpellbook = modCommandRegistry.hasSpellbookToken(player);
        boolean nearTable = modCommandRegistry.isNearEnchantingTable(player, 4);
        boolean fullAccess = nearby || hasSpellbook || nearTable;

        // Build a human-readable reason for why full access was granted.
        String reason = "";
        if (fullAccess && !nearby) {
            if (hasSpellbook && nearTable) {
                reason = "Remote Inventory active \u2014 Wizard's Tome + near enchanting table";
            } else if (hasSpellbook) {
                reason = "Remote Inventory active \u2014 " + botAlias + " holds a Wizard's Tome";
            } else if (nearTable) {
                reason = "Remote Inventory active \u2014 near enchanting table";
            }
        }

        // Always open the inventory (Admin tab needs it regardless of access level).
        BotInventoryAccess.openBotInventoryRemote(player, bot);

        // Tell the client whether full access is granted and why.
        ServerPlayNetworking.send(player, new GuideInventoryAccessPayload(botAlias, fullAccess, reason));
    }
}
