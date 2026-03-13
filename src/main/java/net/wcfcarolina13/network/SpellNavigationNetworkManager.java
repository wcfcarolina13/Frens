package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.NavigationArtifactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles navigation-related network communication:
 * - NavigationResponsePayload (C2S, player accepts/dismisses auto-return)
 * - BotNavTierPayload (S2C, sent on spells screen open via helper method)
 */
public final class SpellNavigationNetworkManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpellNavigationNetworkManager.class);
    private static boolean REGISTERED = false;

    private SpellNavigationNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) return;
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(NavigationResponsePayload.ID, (payload, context) ->
                context.server().execute(() -> handleNavigationResponse(context.server(), context.player(), payload))
        );
    }

    private static void handleNavigationResponse(MinecraftServer server, ServerPlayerEntity player,
                                                  NavigationResponsePayload payload) {
        if (server == null || player == null || player.isRemoved()) return;
        if (!payload.accepted()) {
            LOGGER.debug("Player {} dismissed auto-return for bot {}", player.getName().getString(), payload.botAlias());
            return;
        }

        // Find the bot via player manager
        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(payload.botAlias());
        if (bot == null || bot.isRemoved()) {
            LOGGER.warn("Auto-return accepted but bot '{}' not found", payload.botAlias());
            return;
        }

        // resolveHomeTarget returns Optional<BlockPos>
        BotHomeService.resolveHomeTarget(bot).ifPresent(homePos ->
                BotEventHandler.setReturnToBase(bot, Vec3d.ofCenter(homePos)));
    }

    /** Send nav tier to client when spells screen is opened. */
    public static void sendNavTierToClient(ServerPlayerEntity player, ServerPlayerEntity bot, String botAlias) {
        if (player == null || bot == null) return;
        NavigationArtifactService.NavTier tier = NavigationArtifactService.getBotNavigationTier(bot, player);
        ServerPlayNetworking.send(player, new BotNavTierPayload(
                botAlias != null ? botAlias : "", tier.ordinal()));
    }
}
