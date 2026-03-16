package net.wcfcarolina13.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.BotChestRegistryService;
import net.wcfcarolina13.GameAI.services.NavigationArtifactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-side network handlers for the chest registry screen. */
public final class ChestRegistryNetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("chest-registry-network");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile boolean REGISTERED = false;

    private ChestRegistryNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) return;
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RequestChestRegistryPayload.ID, (payload, context) ->
                context.server().execute(() -> handleRequest(context.player(), context.server(), payload.botName())));

        ServerPlayNetworking.registerGlobalReceiver(ChestCollectPayload.ID, (payload, context) ->
                context.server().execute(() -> handleCollect(context.player(), context.server(), payload.json())));

        ServerPlayNetworking.registerGlobalReceiver(ChestDismissPayload.ID, (payload, context) ->
                context.server().execute(() -> handleDismiss(context.player(), context.server(), payload.json())));
    }

    private static void handleRequest(ServerPlayerEntity player, MinecraftServer server, String botName) {
        if (player == null || player.isRemoved() || server == null) return;
        if (botName == null || botName.isBlank()) return;

        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
        if (bot == null) return;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return;

        // Verify chest states and capture fresh contents snapshots.
        BotChestRegistryService.verifyChests(bot, world);
        BotChestRegistryService.refreshAllSnapshots(bot, world);

        List<BotChestRegistryService.ChestRecord> records = BotChestRegistryService.listChests(bot, world);
        List<Map<String, Object>> out = new ArrayList<>();
        for (BotChestRegistryService.ChestRecord r : records) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("x", r.x);
            entry.put("y", r.y);
            entry.put("z", r.z);
            entry.put("context", r.context);
            entry.put("placedAtMs", r.placedAtMs);
            entry.put("destroyed", r.destroyed);
            if (r.contentsSnapshot != null && !r.contentsSnapshot.isEmpty()) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (var snap : r.contentsSnapshot) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", snap.itemId);
                    item.put("n", snap.count);
                    items.add(item);
                }
                entry.put("contents", items);
            }
            out.add(entry);
        }
        String json = GSON.toJson(out);
        ServerPlayNetworking.send(player, new ChestRegistryPayload(json));
    }

    private static void handleCollect(ServerPlayerEntity player, MinecraftServer server, String json) {
        if (player == null || player.isRemoved() || server == null || json == null) return;
        try {
            Map<String, Object> parsed = GSON.fromJson(json,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
            if (parsed == null) return;

            String botName = parsed.get("botName") instanceof String s ? s : null;
            if (botName == null || botName.isBlank()) return;

            if (!(parsed.get("x") instanceof Number nx)
                    || !(parsed.get("y") instanceof Number ny)
                    || !(parsed.get("z") instanceof Number nz)) {
                LOGGER.warn("Chest collect payload missing coordinates");
                return;
            }
            int x = nx.intValue();
            int y = ny.intValue();
            int z = nz.intValue();

            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
            if (bot == null) return;

            BlockPos chestPos = new BlockPos(x, y, z);
            double distance = bot.getBlockPos().getManhattanDistance(chestPos);

            // Always fast travel to chest, then withdraw on arrival.
            {
                LOGGER.info("Chest collect (fast travel): {} sending {} to chest at {},{},{} (dist={})",
                        player.getName().getString(), botName, x, y, z, (int) distance);
                boolean crossDim = false;
                int delayTicks = NavigationArtifactService.calculateDelayTicks(distance, crossDim);
                // Schedule post-arrival withdrawal.
                NavigationArtifactService.schedulePostArrival(botName,
                        new NavigationArtifactService.PostArrivalAction("withdraw", chestPos, player.getUuid(), null));
                NavigationArtifactService.beginDelayedTravel(
                        server, bot, botName, chestPos,
                        ((ServerWorld) bot.getEntityWorld()).getRegistryKey(), delayTicks, player.getUuid());
                net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
                        player.getCommandSource(),
                        botName + " is fast-traveling to the chest (ETA ~" + Math.max(1, delayTicks / 20) + "s). Items will be collected on arrival.");
            }
            if (false) {
                // Close enough: walk to chest and withdraw.
                LOGGER.info("Chest collect (walk): {} -> bot store withdraw all {}", player.getName().getString(), botName);
                // Use regroup-style approach: set return-to-base toward chest position,
                // which uses follow mode with stuck-escape/burrowing logic.
                net.wcfcarolina13.GameAI.BotEventHandler.setReturnToBase(bot,
                        net.minecraft.util.math.Vec3d.ofCenter(chestPos));
                net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
                        player.getCommandSource(),
                        botName + " is heading to the chest at " + x + ", " + y + ", " + z + ".");
            }

        } catch (Exception e) {
            LOGGER.warn("Failed to handle chest collect: {}", e.getMessage());
        }
    }

    private static void handleDismiss(ServerPlayerEntity player, MinecraftServer server, String json) {
        if (player == null || player.isRemoved() || server == null || json == null) return;
        try {
            Map<String, Object> parsed = GSON.fromJson(json,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
            if (parsed == null) return;

            String botName = parsed.get("botName") instanceof String s ? s : null;
            if (botName == null || botName.isBlank()) return;

            if (!(parsed.get("x") instanceof Number nx)
                    || !(parsed.get("y") instanceof Number ny)
                    || !(parsed.get("z") instanceof Number nz)) {
                LOGGER.warn("Chest dismiss payload missing coordinates");
                return;
            }
            int x = nx.intValue();
            int y = ny.intValue();
            int z = nz.intValue();

            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
            if (bot == null) return;
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) return;

            BotChestRegistryService.removeRecord(bot, world, new BlockPos(x, y, z));
            LOGGER.info("Dismissed chest record for {} at {},{},{}", botName, x, y, z);

            // Re-send updated list
            handleRequest(player, server, botName);

        } catch (Exception e) {
            LOGGER.warn("Failed to handle chest dismiss: {}", e.getMessage());
        }
    }
}
