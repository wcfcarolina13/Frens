package net.shasankp000.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Persists per-alias, per-world location state so bots resume where they were
 * in each world (distinct from dimensions).
 */
public final class BotWorldStateService {
    private static final Logger LOGGER = LoggerFactory.getLogger("bot-world-state");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "bot_world_state.json";

    private static final Map<String, Map<String, BotState>> STATE = new HashMap<>();
    private static boolean loaded = false;

    private BotWorldStateService() {}

    private static String worldKey(MinecraftServer server) {
        return server.getSaveProperties().getLevelName();
    }

    private static Path stateFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("ai-player").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        Path file = stateFile();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                Map<?, ?> raw = GSON.fromJson(reader, Map.class);
                if (raw != null) {
                    for (Map.Entry<?, ?> entry : raw.entrySet()) {
                        String alias = entry.getKey().toString();
                        Map<String, BotState> worldMap = new HashMap<>();
                        Object val = entry.getValue();
                        if (val instanceof Map<?, ?> rawWorlds) {
                            for (Map.Entry<?, ?> w : rawWorlds.entrySet()) {
                                String wk = w.getKey().toString();
                                BotState st = GSON.fromJson(GSON.toJson(w.getValue()), BotState.class);
                                worldMap.put(wk, st);
                            }
                        }
                        STATE.put(alias, worldMap);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to load bot world state: {}", e.getMessage());
            }
        }
        loaded = true;
    }

    private static void flush() {
        try {
            Path file = stateFile();
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(STATE, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save bot world state: {}", e.getMessage());
        }
    }

    public static Optional<BotState> loadState(MinecraftServer server, String alias) {
        ensureLoaded();
        String key = worldKey(server);
        Map<String, BotState> worldMap = STATE.get(alias.toLowerCase());
        if (worldMap == null) return Optional.empty();
        return Optional.ofNullable(worldMap.get(key));
    }

    public static void saveState(ServerPlayerEntity bot) {
        if (bot == null || bot.getCommandSource().getServer() == null) return;
        ensureLoaded();
        String alias = bot.getName().getString().toLowerCase();
        String key = worldKey(bot.getCommandSource().getServer());
        Map<String, BotState> worldMap = STATE.computeIfAbsent(alias, k -> new HashMap<>());
        worldMap.put(key, BotState.from(bot));
        flush();
    }

    public static void saveAll(MinecraftServer server) {
        if (server == null) return;
        ensureLoaded();
        flush();
    }

    public static String currentWorldKey(MinecraftServer server) {
        return worldKey(server);
    }

    public static record BotState(double x, double y, double z, float yaw, float pitch) {
        static BotState from(ServerPlayerEntity bot) {
            return new BotState(bot.getX(), bot.getY(), bot.getZ(), bot.getYaw(), bot.getPitch());
        }
    }
}
