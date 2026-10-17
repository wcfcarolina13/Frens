package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.FilingSystem.SharedConfig;
import net.wcfcarolina13.GameAI.souls.SoulRuntime;
import net.minecraft.text.Text;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Config networking: authoritative server-side global config, pushed to clients on join and on
 * every accepted change. Client -> server saves are operator-gated.
 *
 * <p>This class is loaded on both logical sides; every client-only API call lives inside a method
 * that is only ever invoked from client screens and uses a fully-qualified reference so the class
 * verifies fine on a dedicated server.
 */
public final class configNetworkManager {
    private configNetworkManager() {}
    private static final ConcurrentHashMap<UUID, Long> LAST_DENIAL_MS = new ConcurrentHashMap<>();
    private static final ExecutorService SOUL_RELOAD_WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "frens-config-soul-reload");
        thread.setDaemon(true);
        return thread;
    });

    // ------------------------------------------------------------------ client -> server

    /** Client-side: push the local config JSON to the server (no-op when the server lacks the mod). */
    public static void sendSaveConfigPacket(String json) {
        if (json == null) {
            return;
        }
        try {
            if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(SaveConfigPayload.ID)) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new SaveConfigPayload(json));
            } else {
                Frens.LOGGER.debug("sendSaveConfigPacket: server cannot receive frens:save_config, skipping");
            }
        } catch (Throwable t) {
            Frens.LOGGER.debug("sendSaveConfigPacket failed: {}", String.valueOf(t));
        }
    }

    /** Client-side: push this player's personal voice-category mute mask to the server. */
    public static void sendVoiceMuteMask(java.util.List<String> mutedCategoryIds) {
        java.util.List<String> ids = mutedCategoryIds == null
                ? java.util.List.of()
                : java.util.List.copyOf(mutedCategoryIds);
        try {
            if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(VoiceMuteMaskPayload.ID)) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new VoiceMuteMaskPayload(ids));
            } else {
                Frens.LOGGER.debug("sendVoiceMuteMask: server cannot receive frens:voice_mute_mask, skipping");
            }
        } catch (Throwable t) {
            Frens.LOGGER.debug("sendVoiceMuteMask failed: {}", String.valueOf(t));
        }
    }

    // ------------------------------------------------------------------ server -> client

    /** Server-side: ask {@code player}'s client to open the config UI with a fresh snapshot. */
    public static void sendOpenConfigPacket(ServerPlayerEntity player) {
        if (!isRealPlayer(player)) {
            return;
        }
        try {
            if (!ServerPlayNetworking.canSend(player, OpenConfigPayload.ID)) {
                return;
            }
            ServerPlayNetworking.send(player, new OpenConfigPayload(serverConfigJson(player.getEntityWorld().getServer())));
        } catch (Throwable t) {
            Frens.LOGGER.warn("sendOpenConfigPacket failed: {}", String.valueOf(t));
        }
    }

    /** Server-side: push the authoritative shared config to a single real player. */
    public static void sendConfigSync(ServerPlayerEntity player) {
        if (!isRealPlayer(player)) {
            return;
        }
        try {
            if (ServerPlayNetworking.canSend(player, ConfigSyncPayload.ID)) {
                ServerPlayNetworking.send(player, new ConfigSyncPayload(serverConfigJson(player.getEntityWorld().getServer())));
            }
        } catch (Throwable t) {
            Frens.LOGGER.warn("sendConfigSync failed: {}", String.valueOf(t));
        }
    }

    /** Server-side: push the authoritative shared config to every connected real player. */
    public static void broadcastConfigSync(MinecraftServer server) {
        if (server == null) {
            return;
        }
        String json = serverConfigJson(server);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!isRealPlayer(player)) {
                continue;
            }
            try {
                if (ServerPlayNetworking.canSend(player, ConfigSyncPayload.ID)) {
                    ServerPlayNetworking.send(player, new ConfigSyncPayload(json));
                }
            } catch (Throwable t) {
                Frens.LOGGER.warn("broadcastConfigSync failed for {}: {}", player.getName().getString(), String.valueOf(t));
            }
        }
    }

    /** The shared config plus this server's world key (see {@link SharedConfig#serverWorldKey}). */
    private static String serverConfigJson(MinecraftServer server) {
        String worldKey = null;
        try {
            worldKey = server != null
                    ? net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(server)
                    : null;
        } catch (Throwable t) {
            Frens.LOGGER.debug("serverConfigJson: no world key: {}", String.valueOf(t));
        }
        return ConfigJsonUtil.configToJson(Frens.CONFIG, worldKey);
    }

    private static boolean isRealPlayer(ServerPlayerEntity player) {
        return player != null && !(player instanceof net.wcfcarolina13.Entity.createFakePlayer);
    }

    /**
     * Server-side: true if {@code player} may edit the shared config — an op, or the host of an
     * integrated (single-player) server, which has no ops.json entry when cheats are off.
     */
    private static boolean canEditConfig(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null) {
            return false;
        }
        if (Frens.isOperator(player)) {
            return true;
        }
        return server.isHost(new net.minecraft.server.PlayerConfigEntry(player.getGameProfile()));
    }

    // ------------------------------------------------------------------ server receivers

    /**
     * Registers the C2S config receivers (config save, voice mute mask). Called once from {@code Frens.onInitialize};
     * global receivers persist for the JVM, so this must not run per server start.
     */
    public static void registerServerReceivers() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.player.getUuid();
            server.execute(() -> LAST_DENIAL_MS.remove(playerId));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LAST_DENIAL_MS.clear());

        ServerPlayNetworking.registerGlobalReceiver(SaveConfigPayload.ID, (payload, context) -> {
            ServerPlayerEntity sender = context.player();
            if (sender == null) {
                return;
            }
            String json = payload.configJson();
            context.server().execute(() -> {
                if (!canEditConfig(context.server(), sender)) {
                    long now = System.currentTimeMillis();
                    UUID senderId = sender.getUuid();
                    Long previous = LAST_DENIAL_MS.get(senderId);
                    if (ConfigSaveReloadPolicy.shouldNotifyDenial(now, previous)) {
                        LAST_DENIAL_MS.put(senderId, now);
                        Frens.LOGGER.warn("Rejected config save from non-operator {}", sender.getName().getString());
                        sender.sendMessage(Text.literal("Only an operator or the host can change this server's Frens settings. Your change was not saved."), false);
                    } else {
                        Frens.LOGGER.debug("Repeated rejected config save from non-operator {}", sender.getName().getString());
                    }
                    sendConfigSync(sender);
                    return;
                }
                SharedConfig before = SharedConfig.capture(Frens.CONFIG);
                if (!ConfigJsonUtil.applyConfigJson(json)) {
                    Frens.LOGGER.warn("Config save from {} could not be applied", sender.getName().getString());
                    sendConfigSync(sender);
                    return;
                }
                ManualConfig config = Frens.CONFIG;
                boolean reloadSouls = ConfigSaveReloadPolicy.needsSoulReload(before, SharedConfig.capture(config));
                if (config != null) {
                    config.save();
                    // The live finder switch is a static copied at server start; mirror a
                    // shared-settings save into it so a multiplayer change applies now.
                    net.wcfcarolina13.PathFinding.PathFinder.ACTIVE = net.wcfcarolina13.PathFinding.PathFinderChoice.fromLegacyFlag(config.isBaritonePathfinderEnabled());
                }
                if (reloadSouls && config != null) {
                    SoulRuntime.current().ifPresent(runtime ->
                            CompletableFuture.supplyAsync(() -> runtime.reloadSettings(config), SOUL_RELOAD_WORKER)
                                    .thenCompose(future -> future)
                                    .whenComplete((ignored, error) -> context.server().execute(() -> {
                                        if (error != null) {
                                            Frens.LOGGER.warn("[souls] reloadSettings failed after shared config save: {}", error.toString());
                                        }
                                    })));
                }
                try {
                    net.wcfcarolina13.GameAI.services.BotControlApplier.refreshBotPreferences(context.server());
                } catch (Throwable t) {
                    Frens.LOGGER.warn("refreshBotPreferences failed after config save: {}", String.valueOf(t));
                }
                broadcastConfigSync(context.server());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(VoiceMuteMaskPayload.ID, (payload, context) -> {
            ServerPlayerEntity sender = context.player();
            if (sender == null) {
                return;
            }
            java.util.UUID senderId = sender.getUuid();
            java.util.List<String> ids = payload.mutedCategoryIds() == null
                    ? java.util.List.of()
                    : java.util.List.copyOf(payload.mutedCategoryIds());
            context.server().execute(() ->
                    net.wcfcarolina13.ChatUtils.VoiceLineMuteService.setPlayerMask(senderId, ids));
        });
    }
}
