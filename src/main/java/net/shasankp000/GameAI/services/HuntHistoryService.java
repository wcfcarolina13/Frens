package net.shasankp000.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-world persisted hunting history (food-mob kills).
 */
public final class HuntHistoryService {
    private static final Logger LOGGER = LoggerFactory.getLogger("hunt-history");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "hunt_history.json";
    private static final Object LOCK = new Object();

    private static RootData DATA = new RootData();
    private static boolean loaded = false;

    private HuntHistoryService() {}

    private static Path stateFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("ai-player").resolve(FILE_NAME);
    }

    private static String serverWorldKey(MinecraftServer server, ServerWorld world) {
        String level = server != null && server.getSaveProperties() != null
                ? server.getSaveProperties().getLevelName()
                : "unknown";
        String dim = world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString()
                : "unknown";
        return level + ":" + dim;
    }

    private static String playerKey(ServerPlayerEntity player) {
        if (player == null) {
            return "";
        }
        return player.getName().getString().toLowerCase(Locale.ROOT);
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            Path file = stateFile();
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    RootData parsed = GSON.fromJson(reader, RootData.class);
                    if (parsed != null) {
                        DATA = parsed;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to load hunt history: {}", e.getMessage());
                    DATA = new RootData();
                }
            }
            loaded = true;
        }
    }

    private static void flush() {
        synchronized (LOCK) {
            try {
                Path file = stateFile();
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(DATA, writer);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to save hunt history: {}", e.getMessage());
            }
        }
    }

    private static WorldData worldData(MinecraftServer server, ServerWorld world) {
        ensureLoaded();
        String key = serverWorldKey(server, world);
        synchronized (LOCK) {
            if (DATA.worlds == null) {
                DATA.worlds = new HashMap<>();
            }
            return DATA.worlds.computeIfAbsent(key, ignored -> new WorldData());
        }
    }

    public static void recordHunt(ServerPlayerEntity player, Identifier entityId) {
        if (player == null || entityId == null || !(player.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }
        String playerId = playerKey(player);
        if (playerId.isBlank()) {
            return;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.huntedByPlayer == null) {
                wd.huntedByPlayer = new HashMap<>();
            }
            List<String> list = wd.huntedByPlayer.computeIfAbsent(playerId, k -> new java.util.ArrayList<>());
            String idStr = entityId.toString();
            if (!list.contains(idStr)) {
                list.add(idStr);
            }
        }
        flush();
    }

    public static Set<Identifier> getHistory(ServerPlayerEntity player) {
        if (player == null || !(player.getEntityWorld() instanceof ServerWorld world)) {
            return Collections.emptySet();
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return Collections.emptySet();
        }
        String playerId = playerKey(player);
        if (playerId.isBlank()) {
            return Collections.emptySet();
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.huntedByPlayer == null) {
                return Collections.emptySet();
            }
            List<String> list = wd.huntedByPlayer.get(playerId);
            if (list == null || list.isEmpty()) {
                return Collections.emptySet();
            }
            Set<Identifier> out = new LinkedHashSet<>();
            for (String s : list) {
                try {
                    Identifier id;
                    int idx = s.indexOf(':');
                    if (idx >= 0) {
                        id = Identifier.of(s.substring(0, idx), s.substring(idx + 1));
                    } else {
                        id = Identifier.of("minecraft", s);
                    }
                    out.add(id);
                } catch (Exception ignored) {
                }
            }
            return Collections.unmodifiableSet(out);
        }
    }

    public static boolean hasAnyFoodKill(ServerWorld world) {
        if (world == null) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.huntedByPlayer == null || wd.huntedByPlayer.isEmpty()) {
                return false;
            }
            for (List<String> list : wd.huntedByPlayer.values()) {
                if (list != null && !list.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }

    public static boolean hasFoodKill(ServerPlayerEntity player) {
        if (player == null || !(player.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        WorldData wd = worldData(server, world);
        String playerId = playerKey(player);
        if (playerId.isBlank()) {
            return false;
        }
        synchronized (LOCK) {
            if (wd.huntedByPlayer == null) {
                return false;
            }
            List<String> list = wd.huntedByPlayer.get(playerId);
            return list != null && !list.isEmpty();
        }
    }

    public static Set<Identifier> getWorldHistory(ServerWorld world) {
        if (world == null) {
            return Collections.emptySet();
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return Collections.emptySet();
        }
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.huntedByPlayer == null || wd.huntedByPlayer.isEmpty()) {
                return Collections.emptySet();
            }
            Set<Identifier> out = new LinkedHashSet<>();
            for (List<String> list : wd.huntedByPlayer.values()) {
                if (list == null) {
                    continue;
                }
                for (String s : list) {
                    try {
                        Identifier id;
                        int idx = s.indexOf(':');
                        if (idx >= 0) {
                            id = Identifier.of(s.substring(0, idx), s.substring(idx + 1));
                        } else {
                            id = Identifier.of("minecraft", s);
                        }
                        out.add(id);
                    } catch (Exception ignored) {
                    }
                }
            }
            return Collections.unmodifiableSet(out);
        }
    }

    private static final class RootData {
        Map<String, WorldData> worlds = new HashMap<>();
    }

    private static final class WorldData {
        Map<String, List<String>> huntedByPlayer = new HashMap<>();
    }
}
