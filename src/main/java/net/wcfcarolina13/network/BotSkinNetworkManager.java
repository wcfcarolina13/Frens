package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.services.BotSkinService;
import net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Server-side handler for {@link BotSkinPayload}.
 * Permissions and skin policy are server-authoritative.
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

        boolean operator = Frens.isOperator(player);
        boolean allowEveryoneSkinChange = SurvivalRecruitmentService.isAllowEveryoneSkinChange(server);
        if (!operator && !allowEveryoneSkinChange) {
            player.sendMessage(net.minecraft.text.Text.literal("§cNot authorized to change bot skins."), true);
            return;
        }

        String alias = payload.botAlias() != null ? payload.botAlias().trim() : "";
        String skinSourceRaw = payload.skinSource() != null ? payload.skinSource().trim() : "";
        String skinValueRaw = payload.skinValue() != null ? payload.skinValue().trim() : "";
        if (alias.isEmpty() || skinValueRaw.isEmpty()) return;

        BotSkinService.SkinSource skinSource = BotSkinService.SkinSource.fromWire(skinSourceRaw);

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

        BotSkinService.SkinSelection selection;
        boolean fallbackApplied = false;

        if (skinSource == BotSkinService.SkinSource.CUSTOM_URL) {
            if (!BotSkinService.isValidCustomTextureUrl(skinValueRaw)) {
                player.sendMessage(net.minecraft.text.Text.literal("§cInvalid custom skin URL."), true);
                return;
            }

            boolean allowCustomSkins = SurvivalRecruitmentService.isAllowCustomSkins(server);
            if (!operator && !allowCustomSkins) {
                fallbackApplied = true;
                selection = new BotSkinService.SkinSelection(
                        BotSkinService.SkinSource.PRESET,
                        BotSkinService.SAFE_PRESET_ID,
                        player.getUuidAsString(),
                        player.getName().getString(),
                        false,
                        System.currentTimeMillis());
            } else {
                selection = BotSkinService.SkinSelection.customUrl(
                        skinValueRaw,
                        player.getUuidAsString(),
                        player.getName().getString(),
                        operator);
            }
        } else {
            String presetId = skinValueRaw.toLowerCase(Locale.ROOT);
            if ("random".equals(presetId)) {
                presetId = BotSkinService.randomPresetId();
            }
            if (BotSkinService.presetById(presetId) == null) {
                player.sendMessage(net.minecraft.text.Text.literal("§cUnknown skin: " + presetId), true);
                return;
            }
            selection = new BotSkinService.SkinSelection(
                    BotSkinService.SkinSource.PRESET,
                    presetId,
                    player.getUuidAsString(),
                    player.getName().getString(),
                    operator,
                    System.currentTimeMillis());
        }

        boolean ok = BotSkinService.changeSkin(server, bot, selection);
        if (ok) {
            if (fallbackApplied) {
                player.sendMessage(net.minecraft.text.Text.literal(
                        "§eCustom skins are disabled for non-admins; applied safe skin '" + BotSkinService.SAFE_PRESET_ID + "'."), true);
            } else if (selection.isCustomUrl()) {
                player.sendMessage(net.minecraft.text.Text.literal("Custom URL skin applied."), true);
            } else {
                player.sendMessage(net.minecraft.text.Text.literal("Skin applied: " + selection.value()), true);
            }
        } else {
            player.sendMessage(net.minecraft.text.Text.literal("§cFailed to apply skin."), true);
        }
    }
}
