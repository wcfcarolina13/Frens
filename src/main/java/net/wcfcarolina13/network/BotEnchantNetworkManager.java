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
import net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService;
import net.wcfcarolina13.ui.BotEnchantmentScreenHandler;

/** Handles the C2S request to open a bot's enchanting screen. */
public final class BotEnchantNetworkManager {

    private static boolean REGISTERED = false;

    private BotEnchantNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(BotEnchantOpenPayload.ID, (payload, context) ->
                context.server().execute(() -> handle(context.server(), context.player(), payload))
        );
    }

    private static void handle(MinecraftServer server, ServerPlayerEntity player, BotEnchantOpenPayload payload) {
        if (server == null || player == null || player.isRemoved()) {
            return;
        }
        // Only real players can open this screen.
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

        if (!BotAccessGate.permits(player, bot, "enchant-open")) {
            player.sendMessage(Text.literal("Only this bot's owner, an operator or the host can enchant its items."), false);
            return;
        }

        // Validate same world and reasonable distance (64 blocks, matching inventory access).
        if (!SurvivalRecruitmentService.isAdminPermissionAllowed(player, SpellPermissionPolicy.PERMISSION_KEY)) {
            player.sendMessage(Text.literal(SpellPermissionPolicy.REFUSAL), false);
            return;
        }

        boolean sameWorld = player.getEntityWorld() == bot.getEntityWorld();
        double distanceSq = sameWorld ? player.squaredDistanceTo(bot) : 0.0;
        String botName = EntityUtil.safeDisplayName(bot.getName().getString());
        String tooFar = WorkstationReachPolicy.refusal(botName, sameWorld, distanceSq, true, "an Enchanting Table");
        if (tooFar != null) {
            player.sendMessage(Text.literal(tooFar), false);
            return;
        }

        // Find the nearest enchanting table.
        BlockPos tablePos = modCommandRegistry.findNearestEnchantingTable(player, WorkstationReachPolicy.WORKSTATION_RADIUS);
        if (tablePos == null) {
            player.sendMessage(Text.literal(WorkstationReachPolicy.refusal(botName, true, distanceSq, false, "an Enchanting Table")), false);
            return;
        }

        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        String displayName = EntityUtil.safeDisplayName(bot.getName().getString());
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, p) -> new BotEnchantmentScreenHandler(
                        syncId, bot.getInventory(),
                        ScreenHandlerContext.create(world, tablePos), bot
                ),
                Text.literal(displayName + "'s Enchanting")
        ));
    }
}
