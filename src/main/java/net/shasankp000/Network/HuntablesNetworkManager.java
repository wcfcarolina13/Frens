package net.shasankp000.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.GameAI.services.HuntCatalog;
import net.shasankp000.GameAI.services.HuntHistoryService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Networking glue for the Huntables menu. */
public final class HuntablesNetworkManager {

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
