package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Commands.modCommandRegistry;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.EntityUtil;
import net.wcfcarolina13.ui.BotAnvilScreenHandler;

/** Handles the C2S request to open a bot's anvil screen. */
public final class BotAnvilNetworkManager {

    private static boolean REGISTERED = false;

    private BotAnvilNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(BotAnvilOpenPayload.ID, (payload, context) ->
                context.server().execute(() -> handle(context.server(), context.player(), payload))
        );
    }

    private static void handle(MinecraftServer server, ServerPlayerEntity player, BotAnvilOpenPayload payload) {
        if (server == null || player == null || player.isRemoved()) {
            return;
        }
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
        if (bot == null || !(bot instanceof createFakePlayer)) {
            return;
        }

        if (player.getEntityWorld() != bot.getEntityWorld()
                || player.squaredDistanceTo(bot) > 64.0 * 64.0) {
            return;
        }

        BlockPos anvilPos = modCommandRegistry.findNearestAnvil(player, 4);
        if (anvilPos == null) {
            return;
        }

        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        String displayName = EntityUtil.safeDisplayName(bot.getName().getString());
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, p) -> new BotAnvilScreenHandler(
                        syncId, bot.getInventory(),
                        ScreenHandlerContext.create(world, anvilPos), bot
                ),
                Text.literal(displayName + "'s Anvil")
        ));
    }
}
