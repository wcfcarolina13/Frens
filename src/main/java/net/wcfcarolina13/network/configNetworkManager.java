package net.wcfcarolina13.network;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Frens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Networking glue for the ConfigManager GUI and related config operations. */
public final class configNetworkManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("config-network");
    private static volatile boolean SAVE_CONFIG_REGISTERED = false;
    private static volatile boolean SAVE_API_KEY_REGISTERED = false;
    private static volatile boolean SAVE_CUSTOM_PROVIDER_REGISTERED = false;

    private configNetworkManager() {}

    // ═══════════════════════════════════════════════════════════════════════
    // CLIENT-SIDE: Send packets to server
    // ═══════════════════════════════════════════════════════════════════════

    /** Client -> Server: save the entire config JSON */
    @Environment(EnvType.CLIENT)
    public static void sendSaveConfigPacket(String json) {
        if (json == null || json.isBlank()) {
            LOGGER.warn("sendSaveConfigPacket called with null/blank json");
            return;
        }
        ClientPlayNetworking.send(new SaveConfigPayload(json));
        LOGGER.info("Sent SaveConfigPayload to server");
    }

    /** Client -> Server: save API key for a specific provider */
    @Environment(EnvType.CLIENT)
    public static void sendSaveAPIPacket(String provider, String apiKey) {
        if (provider == null || provider.isBlank()) {
            LOGGER.warn("sendSaveAPIPacket called with null/blank provider");
            return;
        }
        ClientPlayNetworking.send(new SaveAPIKeyPayload(provider, apiKey != null ? apiKey : ""));
        LOGGER.info("Sent SaveAPIKeyPayload to server for provider: {}", provider);
    }

    /** Client -> Server: save custom provider config */
    @Environment(EnvType.CLIENT)
    public static void sendSaveCustomProviderPacket(String apiKey, String url) {
        ClientPlayNetworking.send(new SaveCustomProviderPayload(
            apiKey != null ? apiKey : "",
            url != null ? url : ""
        ));
        LOGGER.info("Sent SaveCustomProviderPayload to server");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SERVER-SIDE: Send packet to client & register receivers
    // ═══════════════════════════════════════════════════════════════════════

    /** Server -> Client: request client to open config GUI */
    public static void sendOpenConfigPacket(ServerPlayerEntity player) {
        if (player == null || player.isRemoved()) {
            LOGGER.warn("sendOpenConfigPacket called with null/removed player");
            return;
        }

        // Serialize current config to JSON and send to client
        String configJson = ConfigJsonUtil.configToJson();
        ServerPlayNetworking.send(player, new OpenConfigPayload(configJson));
        LOGGER.info("Sent OpenConfigPayload to player: {}", player.getName().getString());
    }

    /** Register server receiver for SaveConfigPayload (entire config JSON) */
    public static void registerServerModelNameSaveReceiver(MinecraftServer server) {
        if (SAVE_CONFIG_REGISTERED) {
            return;
        }
        SAVE_CONFIG_REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(SaveConfigPayload.ID, (payload, context) ->
            context.server().execute(() -> handleSaveConfig(context.player(), payload.configJson())));
        
        LOGGER.info("Registered SaveConfigPayload receiver on server");
    }

    /** Register server receiver for SaveAPIKeyPayload */
    public static void registerServerAPIKeySaveReceiver(MinecraftServer server) {
        if (SAVE_API_KEY_REGISTERED) {
            return;
        }
        SAVE_API_KEY_REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(SaveAPIKeyPayload.ID, (payload, context) ->
            context.server().execute(() -> handleSaveAPIKey(
                context.player(), 
                payload.provider(), 
                payload.apiKey()
            )));
        
        LOGGER.info("Registered SaveAPIKeyPayload receiver on server");
    }

    /** Register server receiver for SaveCustomProviderPayload */
    public static void registerServerCustomProviderSaveReceiver(MinecraftServer server) {
        if (SAVE_CUSTOM_PROVIDER_REGISTERED) {
            return;
        }
        SAVE_CUSTOM_PROVIDER_REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(SaveCustomProviderPayload.ID, (payload, context) ->
            context.server().execute(() -> handleSaveCustomProvider(
                context.player(),
                payload.apiKey(),
                payload.url()
            )));
        
        LOGGER.info("Registered SaveCustomProviderPayload receiver on server");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SERVER-SIDE: Packet handlers
    // ═══════════════════════════════════════════════════════════════════════

    private static void handleSaveConfig(ServerPlayerEntity player, String configJson) {
        if (player == null || player.isRemoved()) {
            return;
        }
        if (!Frens.hasBotCommandPermission(player.getCommandSource())) {
            LOGGER.warn("Player {} attempted to save config without permission", player.getName().getString());
            return;
        }

        LOGGER.info("Received config save from player {}: {}", player.getName().getString(), configJson);
        
        // Apply the config JSON to the server-side config
        ConfigJsonUtil.applyConfigJson(configJson);
        
        // Save the config to disk
        if (Frens.CONFIG != null) {
            Frens.CONFIG.save();
            LOGGER.info("Config saved to disk by player {}", player.getName().getString());
        }
    }

    private static void handleSaveAPIKey(ServerPlayerEntity player, String provider, String apiKey) {
        if (player == null || player.isRemoved()) {
            return;
        }
        if (!Frens.hasBotCommandPermission(player.getCommandSource())) {
            LOGGER.warn("Player {} attempted to save API key without permission", player.getName().getString());
            return;
        }

        LOGGER.info("Received API key save from player {} for provider: {}", 
            player.getName().getString(), provider);
        
        // Apply to config based on provider
        if (Frens.CONFIG != null) {
            switch (provider.toLowerCase()) {
                case "openai" -> Frens.CONFIG.setOpenAIKey(apiKey);
                case "claude" -> Frens.CONFIG.setClaudeKey(apiKey);
                case "gemini" -> Frens.CONFIG.setGeminiKey(apiKey);
                case "grok" -> Frens.CONFIG.setGrokKey(apiKey);
                default -> LOGGER.warn("Unknown provider: {}", provider);
            }
            Frens.CONFIG.save();
            LOGGER.info("API key for {} saved by player {}", provider, player.getName().getString());
        }
    }

    private static void handleSaveCustomProvider(ServerPlayerEntity player, String apiKey, String url) {
        if (player == null || player.isRemoved()) {
            return;
        }
        if (!Frens.hasBotCommandPermission(player.getCommandSource())) {
            LOGGER.warn("Player {} attempted to save custom provider without permission", 
                player.getName().getString());
            return;
        }

        LOGGER.info("Received custom provider save from player {}", player.getName().getString());
        
        if (Frens.CONFIG != null) {
            Frens.CONFIG.setCustomApiKey(apiKey);
            Frens.CONFIG.setCustomApiUrl(url);
            Frens.CONFIG.save();
            LOGGER.info("Custom provider config saved by player {}", player.getName().getString());
        }
    }
}

