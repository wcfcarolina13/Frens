package net.shasankp000.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for fake-player bots tracked by the mod.
 *
 * <p>Stage-2 refactor: start extracting global/static bot state out of {@code BotEventHandler}.</p>
 */
public final class BotRegistry {

    private static final Set<UUID> REGISTERED_BOTS = ConcurrentHashMap.newKeySet();

    private BotRegistry() {}

    public static void register(UUID uuid) {
        if (uuid != null) {
            REGISTERED_BOTS.add(uuid);
        }
    }

    public static void unregister(UUID uuid) {
        if (uuid != null) {
            REGISTERED_BOTS.remove(uuid);
        }
    }

    public static boolean isRegistered(UUID uuid) {
        return uuid != null && REGISTERED_BOTS.contains(uuid);
    }

    public static boolean isEmpty() {
        return REGISTERED_BOTS.isEmpty();
    }

    public static void clear() {
        REGISTERED_BOTS.clear();
    }

    /**
     * Live view for iteration. Prefer {@link #getPlayers(MinecraftServer)} when you need entities.
     */
    public static Set<UUID> ids() {
        return REGISTERED_BOTS;
    }

    public static List<ServerPlayerEntity> getPlayers(MinecraftServer server) {
        if (server == null) {
            return List.of();
        }
        List<ServerPlayerEntity> bots = new ArrayList<>();
        for (UUID uuid : REGISTERED_BOTS) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                bots.add(player);
            }
        }
        return bots;
    }
}

