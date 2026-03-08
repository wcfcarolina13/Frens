package net.wcfcarolina13.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.GameAI.services.HuntCatalog;
import net.wcfcarolina13.GameAI.services.HuntConfigService;
import net.wcfcarolina13.GameAI.services.HuntHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Networking glue for the Huntables menu. */
public final class HuntablesNetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("huntables-network");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile boolean REGISTERED = false;

    private HuntablesNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RequestHuntablesPayload.ID, (payload, context) ->
                context.server().execute(() -> sendHuntablesList(context.player())));

        ServerPlayNetworking.registerGlobalReceiver(SaveHuntConfigPayload.ID, (payload, context) ->
                context.server().execute(() -> handleSaveHuntConfig(context.player(), context.server(), payload.configJson())));
    }

    private static void handleSaveHuntConfig(ServerPlayerEntity player, net.minecraft.server.MinecraftServer server, String json) {
        if (player == null || player.isRemoved() || json == null || server == null) return;
        try {
            Map<String, Object> parsed = GSON.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
            if (parsed == null) return;

            String botName = parsed.get("botName") instanceof String s ? s : null;
            if (botName == null || botName.isBlank()) return;

            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
            if (bot == null) return;

            boolean depop = parsed.get("depopulationEnabled") instanceof Boolean b ? b : true;
            String zoneName = parsed.get("zone") instanceof String s ? s : "STANDARD";
            List<String> targets = parsed.get("selectedTargets") instanceof List<?> list
                    ? list.stream().filter(o -> o instanceof String).map(o -> (String) o).toList()
                    : List.of();

            HuntConfigService.HuntConfig config = new HuntConfigService.HuntConfig(
                    depop, HuntConfigService.HuntZone.fromName(zoneName), targets);
            HuntConfigService.saveConfig(bot, config);

            sendHuntConfig(player, bot);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse hunt config save: {}", e.getMessage());
        }
    }

    public static void sendHuntConfig(ServerPlayerEntity player, ServerPlayerEntity bot) {
        if (player == null || player.isRemoved() || bot == null) return;
        HuntConfigService.HuntConfig config = HuntConfigService.getConfig(bot);
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("botName", bot.getName().getString());
        out.put("depopulationEnabled", config.depopulationEnabled);
        out.put("zone", config.zone);
        out.put("selectedTargets", config.selectedTargets);
        String json = GSON.toJson(out);
        ServerPlayNetworking.send(player, new HuntConfigPayload(json));
    }

    public static void sendHuntablesList(ServerPlayerEntity player) {
        if (player == null || player.isRemoved()) {
            return;
        }

        Set<net.minecraft.util.Identifier> unlocked = HuntHistoryService.getHistory(player);
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (HuntCatalog.HuntTarget target : HuntCatalog.listAll()) {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("id", target.id().toString());
            entry.put("label", target.label());
            entry.put("food", target.foodMob());
            entry.put("unlocked", unlocked.contains(target.id()));
            out.add(entry);
        }
        String json = GSON.toJson(out);
        ServerPlayNetworking.send(player, new HuntablesPayload(json));
    }
}
