package net.shasankp000.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/** Minimal stubbed network helper used by GUI/commands to avoid compile errors during triage.
 *  All methods are no-ops or simple placeholders; replace with real networking logic later.
 */
public final class configNetworkManager {
    private configNetworkManager() {}

    public static void sendSaveConfigPacket(String json) {
        // no-op placeholder for compile-time
    }

    public static void sendSaveAPIPacket(String provider, String apiKey) {
        // no-op placeholder
    }

    public static void sendSaveCustomProviderPacket(String apiKey, String url) {
        // no-op placeholder
    }

    public static void sendOpenConfigPacket(ServerPlayerEntity player) {
        // no-op placeholder
    }

    public static void registerServerModelNameSaveReceiver(MinecraftServer server) {
        // no-op placeholder
    }

    public static void registerServerAPIKeySaveReceiver(MinecraftServer server) {
        // no-op placeholder
    }

    public static void registerServerCustomProviderSaveReceiver(MinecraftServer server) {
        // no-op placeholder
    }
}

