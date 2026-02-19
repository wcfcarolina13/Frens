package net.shasankp000.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.Entity.createFakePlayer;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.BotHomeService;
import net.shasankp000.GameAI.services.construction.FortificationPersistenceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Networking glue for the in-inventory Bases manager screen. */
public final class BaseNetworkManager {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile boolean REGISTERED = false;
    private static final Map<UUID, String> PLAYER_BOT_ALIAS_CONTEXT = new ConcurrentHashMap<>();

    private BaseNetworkManager() {}

    /** @param wallStatus null for regular bases; "complete" or "2/14 edges" for fortification walls */
    public record BaseDto(String label, int x, int y, int z, boolean home, String wallStatus) {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RequestBasesPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        return;
                    }
                    String botAlias = sanitizeBotAlias(payload != null ? payload.query() : null);
                    rememberBotAliasContext(player, botAlias);
                    sendBasesList(player, botAlias);
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseSetPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases can only be saved in the Overworld.");
                        return;
                    }
                    String label = payload.label();
                    if (label == null || label.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Enter a base name first.");
                        return;
                    }
                    BlockPos pos = player.getBlockPos().toImmutable();
                        boolean ok = BotHomeService.addBase(player.getCommandSource().getServer(), world, label, pos);
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? "Saved base '" + label + "' at " + pos.toShortString() + "."
                            : "Failed to save base.");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseRemovePayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases are only managed in the Overworld.");
                        return;
                    }
                    String label = payload.label();
                    if (label == null || label.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Select a base to remove.");
                        return;
                    }
                    MinecraftServer srv = player.getCommandSource().getServer();
                    boolean removed = BotHomeService.removeBase(srv, world, label);
                    if (!removed) {
                        // Try removing a fortification wall with that name
                        String wKey = FortificationPersistenceService.serverWorldKey(srv, world);
                        removed = FortificationPersistenceService.delete(srv, wKey, label);
                    }
                    ChatUtils.sendSystemMessage(player.getCommandSource(), removed
                            ? "Removed '" + label + "'."
                            : "No base or wall named '" + label + "' found.");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseRenamePayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases are only managed in the Overworld.");
                        return;
                    }
                    String oldLabel = payload.oldLabel();
                    String newLabel = payload.newLabel();
                    if (oldLabel == null || oldLabel.isBlank() || newLabel == null || newLabel.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Select a base and enter a new name.");
                        return;
                    }
                    MinecraftServer srv = player.getCommandSource().getServer();
                    boolean ok = BotHomeService.renameBase(srv, world, oldLabel, newLabel);
                    if (!ok) {
                        // Try renaming a fortification wall
                        String wKey = FortificationPersistenceService.serverWorldKey(srv, world);
                        ok = FortificationPersistenceService.rename(srv, wKey, oldLabel, newLabel);
                    }
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? "Renamed '" + oldLabel + "' -> '" + newLabel + "'."
                            : "Rename failed (does it exist? is the new name already used?).");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseSetHomePayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        return;
                    }
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases are only managed in the Overworld.");
                        return;
                    }
                    String botAlias = sanitizeBotAlias(payload != null ? payload.botAlias() : null);
                    String label = payload != null ? payload.label() : null;
                    ServerPlayerEntity bot = resolveControlledBot(player.getCommandSource().getServer(), botAlias);
                    if (bot == null) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bot '" + botAlias + "' is not available.");
                        return;
                    }
                    if (!(bot.getEntityWorld() instanceof ServerWorld botWorld)
                            || botWorld.getRegistryKey() != world.getRegistryKey()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Set Home only works when you and the bot are in the same dimension.");
                        return;
                    }
                    boolean ok = BotHomeService.setPreferredHomeBase(bot, label);
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? bot.getName().getString() + " will treat '" + label + "' as home."
                            : "Couldn't set home. Does that base exist?");
                    rememberBotAliasContext(player, botAlias);
                    sendBasesList(player, botAlias);
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseGoToPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        return;
                    }
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases are only managed in the Overworld.");
                        return;
                    }
                    String botAlias = sanitizeBotAlias(payload != null ? payload.botAlias() : null);
                    String label = payload != null ? payload.label() : null;
                    ServerPlayerEntity bot = resolveControlledBot(player.getCommandSource().getServer(), botAlias);
                    if (bot == null) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bot '" + botAlias + "' is not available.");
                        return;
                    }
                    if (!(bot.getEntityWorld() instanceof ServerWorld botWorld)
                            || botWorld.getRegistryKey() != world.getRegistryKey()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), bot.getName().getString() + " can't go to that base across dimensions.");
                        return;
                    }

                    var baseOpt = BotHomeService.getBaseByLabel(player.getCommandSource().getServer(), world, label);
                    if (baseOpt.isEmpty()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "No base named '" + label + "' found.");
                        return;
                    }

                    String result = BotEventHandler.setReturnToBase(bot, Vec3d.ofCenter(baseOpt.get()));
                    ChatUtils.sendSystemMessage(player.getCommandSource(), result);
                    rememberBotAliasContext(player, botAlias);
                    sendBasesList(player, botAlias);
                }));
    }

    public static void sendBasesList(ServerPlayerEntity player) {
        sendBasesList(player, currentBotAliasContext(player));
    }

    public static void sendBasesList(ServerPlayerEntity player, String botAliasContext) {
        if (player == null || player.isRemoved()) {
            return;
        }
        ServerWorld world = player.getCommandSource().getWorld();
        if (world.getRegistryKey() != World.OVERWORLD) {
            // Still respond (empty list) so UI can clear itself.
            ServerPlayNetworking.send(player, new BasesListPayload("[]"));
            return;
        }

        String homeLabel = null;
        ServerPlayerEntity selectedBot = resolveControlledBot(player.getCommandSource().getServer(), botAliasContext);
        if (selectedBot != null) {
            homeLabel = BotHomeService.getPreferredHomeBaseLabel(selectedBot).orElse(null);
        }
        String homeNorm = homeLabel != null ? homeLabel.trim().toLowerCase(java.util.Locale.ROOT) : "";

        MinecraftServer server = player.getCommandSource().getServer();
        List<BotHomeService.BaseEntry> bases = BotHomeService.listBases(server, world);
        List<BaseDto> out = new ArrayList<>(bases.size());
        for (BotHomeService.BaseEntry b : bases) {
            if (b == null || b.pos() == null) continue;
            String label = b.label() != null ? b.label() : "";
            boolean home = !homeNorm.isBlank() && homeNorm.equals(label.trim().toLowerCase(java.util.Locale.ROOT));
            out.add(new BaseDto(label, b.pos().getX(), b.pos().getY(), b.pos().getZ(), home, null));
        }

        // Include saved fortification walls
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        java.util.Set<String> baseLabelsLower = new java.util.HashSet<>();
        for (BaseDto dto : out) {
            if (dto.label() != null) baseLabelsLower.add(dto.label().trim().toLowerCase(java.util.Locale.ROOT));
        }
        for (FortificationPersistenceService.SavedFortification f : FortificationPersistenceService.listForWorld(server, worldKey)) {
            String fName = f.getName();
            if (fName == null) continue;
            // Skip if already present as a base
            if (baseLabelsLower.contains(fName.trim().toLowerCase(java.util.Locale.ROOT))) continue;
            net.minecraft.util.math.BlockPos center = f.getCenter();
            int totalEdges = f.getHullWallPoints().size();
            String status = f.isComplete() ? "complete"
                    : f.getCompletedEdges().size() + "/" + totalEdges + " edges";
            out.add(new BaseDto(fName, center.getX(), center.getY(), center.getZ(), false, status));
        }

        String json = GSON.toJson(out);
        ServerPlayNetworking.send(player, new BasesListPayload(json));
    }

    private static void rememberBotAliasContext(ServerPlayerEntity player, String botAlias) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return;
        }
        String alias = sanitizeBotAlias(botAlias);
        if (alias.isBlank()) {
            PLAYER_BOT_ALIAS_CONTEXT.remove(playerId);
            return;
        }
        PLAYER_BOT_ALIAS_CONTEXT.put(playerId, alias);
    }

    private static String currentBotAliasContext(ServerPlayerEntity player) {
        if (player == null || player.getUuid() == null) {
            return "";
        }
        return sanitizeBotAlias(PLAYER_BOT_ALIAS_CONTEXT.get(player.getUuid()));
    }

    private static String sanitizeBotAlias(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    private static ServerPlayerEntity resolveControlledBot(MinecraftServer server, String alias) {
        if (server == null || alias == null || alias.isBlank()) {
            return null;
        }
        ServerPlayerEntity bot = null;
        if (server.getPlayerManager() != null) {
            bot = server.getPlayerManager().getPlayer(alias);
            if (bot == null) {
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p != null && p.getName() != null
                            && alias.equalsIgnoreCase(p.getName().getString())) {
                        bot = p;
                        break;
                    }
                }
            }
        }
        if (bot == null || bot.isRemoved()) {
            return null;
        }
        if (!(bot instanceof createFakePlayer)) {
            return null;
        }
        if (!BotEventHandler.isRegisteredBot(bot)) {
            return null;
        }
        return bot;
    }
}
