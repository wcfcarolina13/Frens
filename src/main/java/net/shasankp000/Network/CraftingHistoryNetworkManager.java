package net.shasankp000.Network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.shasankp000.GameAI.services.CraftingHistoryService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Networking glue for the Crafting history screen. */
public final class CraftingHistoryNetworkManager {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile boolean REGISTERED = false;

    private CraftingHistoryNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RequestCraftingHistoryPayload.ID, (payload, context) ->
                context.server().execute(() -> sendHistoryList(context.player())));
    }

    public static void sendHistoryList(ServerPlayerEntity player) {
        if (player == null || player.isRemoved()) {
            return;
        }
        Set<Identifier> history = CraftingHistoryService.getHistory(player);
        List<String> out = new ArrayList<>(history.size());
        for (Identifier id : history) {
            if (id == null) continue;
            out.add(id.toString());
        }
        String json = GSON.toJson(out);
        ServerPlayNetworking.send(player, new CraftingHistoryPayload(json));
    }
}
