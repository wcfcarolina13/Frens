package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;

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

    /** Client-side: push an API key for {@code provider} to the server (operator-only server-side). */
    public static void sendSaveAPIPacket(String provider, String apiKey) {
        if (provider == null || apiKey == null) {
            return;
        }
        try {
            if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(SaveAPIKeyPayload.ID)) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new SaveAPIKeyPayload(provider, apiKey));
            } else {
                Frens.LOGGER.debug("sendSaveAPIPacket: server cannot receive frens:save_api_key, skipping");
            }
        } catch (Throwable t) {
            Frens.LOGGER.debug("sendSaveAPIPacket failed: {}", String.valueOf(t));
        }
    }

    /** Client-side: push the custom provider key + URL to the server (operator-only server-side). */
    public static void sendSaveCustomProviderPacket(String apiKey, String url) {
        if (apiKey == null || url == null) {
            return;
        }
        try {
            if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(SaveCustomProviderPayload.ID)) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new SaveCustomProviderPayload(apiKey, url));
            } else {
                Frens.LOGGER.debug("sendSaveCustomProviderPacket: server cannot receive frens:save_custom_provider, skipping");
            }
        } catch (Throwable t) {
            Frens.LOGGER.debug("sendSaveCustomProviderPacket failed: {}", String.valueOf(t));
        }
    }

    // ------------------------------------------------------------------ server -> client

    /** Server-side: ask {@code player}'s client to open the config UI with a fresh snapshot. */
    public static void sendOpenConfigPacket(ServerPlayerEntity player) {
        if (!isRealPlayer(player)) {
            return;
        }
        try {
            ServerPlayNetworking.send(player, new OpenConfigPayload(ConfigJsonUtil.configToJson()));
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
                ServerPlayNetworking.send(player, new ConfigSyncPayload(ConfigJsonUtil.configToJson()));
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
        String json = ConfigJsonUtil.configToJson();
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

    private static boolean isRealPlayer(ServerPlayerEntity player) {
        return player != null && !(player instanceof net.wcfcarolina13.Entity.createFakePlayer);
    }

    // ------------------------------------------------------------------ server receivers

    /**
     * Registers the three C2S config receivers. Called once from {@code Frens.onInitialize};
     * global receivers persist for the JVM, so this must not run per server start.
     */
    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(SaveConfigPayload.ID, (payload, context) -> {
            ServerPlayerEntity sender = context.player();
            if (sender == null) {
                return;
            }
            String json = payload.configJson();
            context.server().execute(() -> {
                if (!Frens.isOperator(sender)) {
                    Frens.LOGGER.warn("Rejected config save from non-operator {}", sender.getName().getString());
                    sendConfigSync(sender);
                    return;
                }
                if (!ConfigJsonUtil.applyConfigJson(json)) {
                    Frens.LOGGER.warn("Config save from {} could not be applied", sender.getName().getString());
                    sendConfigSync(sender);
                    return;
                }
                ManualConfig config = Frens.CONFIG;
                if (config != null) {
                    config.save();
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

        ServerPlayNetworking.registerGlobalReceiver(SaveAPIKeyPayload.ID, (payload, context) -> {
            ServerPlayerEntity sender = context.player();
            if (sender == null) {
                return;
            }
            String provider = payload.provider();
            String apiKey = payload.apiKey();
            context.server().execute(() -> {
                if (!Frens.isOperator(sender)) {
                    Frens.LOGGER.warn("Rejected API key save from non-operator {}", sender.getName().getString());
                    return;
                }
                ManualConfig config = Frens.CONFIG;
                if (config == null || provider == null || apiKey == null) {
                    return;
                }
                switch (provider.toLowerCase(java.util.Locale.ROOT)) {
                    case "openai" -> config.setOpenAIKey(apiKey);
                    case "gemini" -> config.setGeminiKey(apiKey);
                    case "claude" -> config.setClaudeKey(apiKey);
                    case "grok" -> config.setGrokKey(apiKey);
                    default -> {
                        Frens.LOGGER.warn("Unknown API key provider '{}', ignoring", provider);
                        return;
                    }
                }
                config.save();
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SaveCustomProviderPayload.ID, (payload, context) -> {
            ServerPlayerEntity sender = context.player();
            if (sender == null) {
                return;
            }
            String apiKey = payload.apiKey();
            String url = payload.url();
            context.server().execute(() -> {
                if (!Frens.isOperator(sender)) {
                    Frens.LOGGER.warn("Rejected custom provider save from non-operator {}", sender.getName().getString());
                    return;
                }
                ManualConfig config = Frens.CONFIG;
                if (config == null || apiKey == null || url == null) {
                    return;
                }
                config.setCustomApiKey(apiKey);
                config.setCustomApiUrl(url);
                config.save();
            });
        });
    }
}
