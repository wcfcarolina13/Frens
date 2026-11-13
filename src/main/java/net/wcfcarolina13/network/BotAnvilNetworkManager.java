package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Commands.modCommandRegistry;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.EntityUtil;
import net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService;
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

        if (!BotAccessGate.permits(player, bot, "anvil-open")) {
            ChatUtils.sendSystemMessage(player.getCommandSource(),
                    "§cYou don't have permission to open this bot's anvil.§r");
            return;
        }

        if (!SurvivalRecruitmentService.isAdminPermissionAllowed(player, SpellPermissionPolicy.PERMISSION_KEY)) {
            player.sendMessage(Text.literal(SpellPermissionPolicy.REFUSAL), false);
            return;
        }

        boolean sameWorld = player.getEntityWorld() == bot.getEntityWorld();
        double distanceSq = sameWorld ? player.squaredDistanceTo(bot) : 0.0;
        String botName = EntityUtil.safeDisplayName(bot.getName().getString());
        String tooFar = WorkstationReachPolicy.refusal(botName, sameWorld, distanceSq, true, "an anvil");
        if (tooFar != null) {
            player.sendMessage(Text.literal(tooFar), false);
            return;
        }

        BlockPos anvilPos = modCommandRegistry.findNearestAnvil(player, WorkstationReachPolicy.WORKSTATION_RADIUS);
        if (anvilPos == null) {
            player.sendMessage(Text.literal(WorkstationReachPolicy.refusal(botName, true, distanceSq, false, "an anvil")), false);
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
