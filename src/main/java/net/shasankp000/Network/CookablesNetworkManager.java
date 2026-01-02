package net.shasankp000.Network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.SmeltingService;
import net.shasankp000.ui.BotPlayerInventoryScreenHandler;

import java.util.ArrayList;
import java.util.List;

/** Networking glue for the Cookables menu. */
public final class CookablesNetworkManager {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile boolean REGISTERED = false;

    private CookablesNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RequestCookablesPayload.ID, (payload, context) ->
                context.server().execute(() -> sendCookablesList(context.player(), payload.query())));
    }

    public static void sendCookablesList(ServerPlayerEntity player, String query) {
        if (player == null || player.isRemoved()) {
            return;
        }
        ServerPlayerEntity bot = null;
        if (player.currentScreenHandler instanceof BotPlayerInventoryScreenHandler handler) {
            bot = handler.getBotRef();
        }
        if (bot == null) {
            bot = BotEventHandler.bot;
        }
        if (bot == null || bot.isRemoved()) {
            ServerPlayNetworking.send(player, new CookablesPayload("[]"));
            return;
        }
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        if (world == null) {
            ServerPlayNetworking.send(player, new CookablesPayload("[]"));
            return;
        }
        BlockState furnaceState = pickFurnaceState(query);
        List<Identifier> cookables = SmeltingService.listCookableIds(bot, world, furnaceState);
        List<Identifier> fuels = SmeltingService.listFuelIds(bot, world);
        List<String> cookablesOut = new ArrayList<>(cookables.size());
        for (Identifier id : cookables) {
            if (id == null) continue;
            cookablesOut.add(id.toString());
        }
        List<String> fuelsOut = new ArrayList<>(fuels.size());
        for (Identifier id : fuels) {
            if (id == null) continue;
            fuelsOut.add(id.toString());
        }
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("cookables", cookablesOut);
        payload.put("fuels", fuelsOut);
        String json = GSON.toJson(payload);
        ServerPlayNetworking.send(player, new CookablesPayload(json));
    }

    private static BlockState pickFurnaceState(String query) {
        if (query != null) {
            String q = query.toLowerCase(java.util.Locale.ROOT);
            if (q.contains("smoker")) {
                return Blocks.SMOKER.getDefaultState();
            }
            if (q.contains("blast")) {
                return Blocks.BLAST_FURNACE.getDefaultState();
            }
        }
        return Blocks.FURNACE.getDefaultState();
    }
}
