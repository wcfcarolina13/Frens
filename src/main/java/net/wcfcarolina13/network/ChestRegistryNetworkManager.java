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

        ServerPlayNetworking.registerGlobalReceiver(StoreTargetPayload.ID, (payload, context) ->
                context.server().execute(() -> handleStoreTarget(context.player(), context.server(), payload.json())));
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

            String mode = parsed.get("mode") instanceof String s2 ? s2 : "collect";
            String returnTo = parsed.get("returnTo") instanceof String s3 ? s3 : "stay";

            BlockPos chestPos = new BlockPos(x, y, z);
            double distance = bot.getBlockPos().getManhattanDistance(chestPos);
            boolean crossDim = false;
            double mult = NavigationArtifactService.artifactDelayMultiplier(bot, player);
            int delayTicks = NavigationArtifactService.calculateDelayTicks(distance, crossDim, mult);

            if ("go".equals(mode)) {
                // Go mode: fast travel to chest, no withdrawal.
                LOGGER.info("Chest go: {} sending {} to chest at {},{},{} (dist={})",
                        player.getName().getString(), botName, x, y, z, (int) distance);
                NavigationArtifactService.beginDelayedTravel(
                        server, bot, botName, chestPos,
                        ((ServerWorld) bot.getEntityWorld()).getRegistryKey(), delayTicks, player.getUuid());
                net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
                        player.getCommandSource(),
                        botName + " is fast-traveling to the chest (ETA ~" + Math.max(1, delayTicks / 20) + "s).");
            } else {
                // Collect mode: fast travel to chest, withdraw on arrival, then optionally return.
                LOGGER.info("Chest collect: {} sending {} to chest at {},{},{} (dist={}, return={})",
                        player.getName().getString(), botName, x, y, z, (int) distance, returnTo);
                // Store return mode in action type: "withdraw:stay", "withdraw:player", "withdraw:home".
                NavigationArtifactService.schedulePostArrival(botName,
                        new NavigationArtifactService.PostArrivalAction(
                                "withdraw:" + returnTo, chestPos, player.getUuid(),
                                net.minecraft.util.math.Vec3d.of(bot.getBlockPos())));
                NavigationArtifactService.beginDelayedTravel(
                        server, bot, botName, chestPos,
                        ((ServerWorld) bot.getEntityWorld()).getRegistryKey(), delayTicks, player.getUuid());
                String etaMsg = botName + " is fast-traveling to the chest (ETA ~" + Math.max(1, delayTicks / 20) + "s).";
                if (!"stay".equals(returnTo)) {
                    etaMsg += " Will return to " + returnTo + " after collecting.";
                }
                net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(player.getCommandSource(), etaMsg);
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

    // ── Store Here (ad-hoc deposit into player-targeted chest) ──────────

    private static void handleStoreTarget(ServerPlayerEntity player, MinecraftServer server, String json) {
        if (player == null || player.isRemoved() || server == null || json == null) return;
        try {
            Map<String, Object> parsed = GSON.fromJson(json,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
            if (parsed == null) return;

            String botName = parsed.get("botName") instanceof String s ? s : null;
            if (botName == null || botName.isBlank()) return;

            String mode = parsed.get("mode") instanceof String s2 ? s2 : "store";
            boolean deposit = "store".equals(mode);

            if (!(parsed.get("x") instanceof Number nx)
                    || !(parsed.get("y") instanceof Number ny)
                    || !(parsed.get("z") instanceof Number nz)) {
                LOGGER.warn("Store target payload missing coordinates");
                return;
            }
            int x = nx.intValue(), y = ny.intValue(), z = nz.intValue();

            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
            if (bot == null) {
                net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
                        player.getCommandSource(), "\u00A7c" + botName + " is not available.\u00A7r");
                return;
            }

            BlockPos chestPos = new BlockPos(x, y, z);

            // Check bot is not busy
            if (net.wcfcarolina13.GameAI.services.TaskService.hasActiveTask(bot.getUuid())
                    || net.wcfcarolina13.GameAI.services.BotCombatCalloutService.isInCombat(bot.getUuid())) {
                net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
                        player.getCommandSource(), "\u00A7c" + botName + " is busy. Stop the current task first.\u00A7r");
                return;
            }

            // Validate distance (32 blocks max)
            double dist = bot.getBlockPos().getManhattanDistance(chestPos);
            if (dist > 32) {
                net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
                        player.getCommandSource(), "\u00A7c" + botName + " is too far from the chest (" + (int) dist + " blocks).\u00A7r");
                return;
            }

            // Acquire task ticket so /bot stop works and concurrent tasks are blocked
            String taskName = deposit ? "quick-store" : "quick-fetch";
            var ticketOpt = net.wcfcarolina13.GameAI.services.TaskService.beginSkill(
                    taskName, bot.getCommandSource(), bot.getUuid());
            if (ticketOpt.isEmpty()) {
                net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
                        player.getCommandSource(), "\u00A7c" + botName + " is busy with another task.\u00A7r");
                return;
            }
            var ticket = ticketOpt.get();

            String action = deposit ? "deposit" : "fetch";
            net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
                    player.getCommandSource(),
                    botName + " is heading to the chest to " + action + " items...");

            // Run on worker thread — ChestStoreService.depositAll/withdrawAllFrom is blocking (walks, then transfers)
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    int moved;
                    if (deposit) {
                        moved = net.wcfcarolina13.GameAI.services.ChestStoreService.depositAll(
                                bot.getCommandSource(), bot, chestPos);
                    } else {
                        moved = net.wcfcarolina13.GameAI.services.ChestStoreService.withdrawAllFrom(
                                bot.getCommandSource(), bot, chestPos);
                    }

                    String verb = deposit ? "deposited" : "fetched";
                    String msg;
                    if (moved > 0) {
                        msg = "\u00A7a" + botName + " " + verb + " " + moved + " items.\u00A7r";
                    } else {
                        msg = "\u00A7e" + botName + " could not " + action + " any items. "
                                + (deposit ? "Chest may be full or bot inventory empty." : "Chest may be empty or bot inventory full.")
                                + "\u00A7r";
                    }
                    server.execute(() -> net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
                            player.getCommandSource(), msg));
                    LOGGER.info("Quick {}: {} {} {} items at {},{},{}", action, botName, verb, moved, x, y, z);
                } catch (Exception e) {
                    LOGGER.warn("Quick {} failed for {}: {}", action, botName, e.getMessage());
                    server.execute(() -> net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
                            player.getCommandSource(),
                            "\u00A7c" + botName + " failed to " + action + " items: " + e.getMessage() + "\u00A7r"));
                } finally {
                    net.wcfcarolina13.GameAI.services.TaskService.complete(ticket, true);
                }
            });

        } catch (Exception e) {
            LOGGER.warn("Failed to handle store target: {}", e.getMessage());
        }
    }
}
