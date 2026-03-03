package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.services.BotSkinService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Server-side handler for {@link BotSkinPayload}.
 * Only operators can change bot skins.
 */
public final class BotSkinNetworkManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BotSkinNetworkManager");
    private static boolean registered;

    private BotSkinNetworkManager() {}

    public static void registerReceiversOnce() {
        if (registered) return;
        registered = true;

        ServerPlayNetworking.registerGlobalReceiver(BotSkinPayload.ID, (payload, context) ->
                context.server().execute(() -> handle(context.server(), context.player(), payload)));
    }

    private static void handle(MinecraftServer server, ServerPlayerEntity player, BotSkinPayload payload) {
        if (server == null || player == null || player.isRemoved()) return;
        if (player instanceof createFakePlayer) return;

        if (!Frens.isOperator(player)) {
            player.sendMessage(net.minecraft.text.Text.literal("§cNot authorized to change bot skins."), true);
            return;
        }

        String alias = payload.botAlias() != null ? payload.botAlias().trim() : "";
        String presetId = payload.skinPresetId() != null ? payload.skinPresetId().trim().toLowerCase(Locale.ROOT) : "";
        if (alias.isEmpty() || presetId.isEmpty()) return;

        // Find the online bot.
        ServerPlayerEntity bot = null;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p instanceof createFakePlayer && p.getGameProfile().name().equalsIgnoreCase(alias)) {
                bot = p;
                break;
            }
        }

        if (bot == null) {
            player.sendMessage(net.minecraft.text.Text.literal("§cBot '" + alias + "' is not online."), true);
            return;
        }

        // Handle "random" choice.
        if ("random".equals(presetId)) {
            String chosen = BotSkinService.applyRandomSkin(bot.getGameProfile());
            // Still need to do the remove/re-add dance for live bots.
            // applyRandomSkin only sets the property; we need the full packet dance.
            // Re-use changeSkin with the chosen id.
            BotSkinService.changeSkin(server, bot, chosen);
            player.sendMessage(net.minecraft.text.Text.literal("Randomized skin for " + alias + " → " + chosen), true);
            return;
        }

        if (BotSkinService.presetById(presetId) == null) {
            player.sendMessage(net.minecraft.text.Text.literal("§cUnknown skin: " + presetId), true);
            return;
        }

        boolean ok = BotSkinService.changeSkin(server, bot, presetId);
        if (ok) {
            player.sendMessage(net.minecraft.text.Text.literal("Skin applied: " + presetId), true);
        } else {
            player.sendMessage(net.minecraft.text.Text.literal("§cFailed to apply skin."), true);
        }
    }
}
