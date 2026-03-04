package net.wcfcarolina13.GameAI.services;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

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
        String levelName = legacyWorldKey(server);
        String rootFingerprint = "unknown";
        if (server != null) {
            try {
                Path root = server.getSavePath(WorldSavePath.ROOT);
                if (root != null) {
                    String normalized = root.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
                    rootFingerprint = Integer.toUnsignedString(normalized.hashCode(), 16);
                }
            } catch (Exception ignored) {
            }
        }
        return levelName + "#" + rootFingerprint;
    }

    private static String legacyWorldKey(MinecraftServer server) {
        if (server == null || server.getSaveProperties() == null) {
            return "default";
        }
        String levelName = server.getSaveProperties().getLevelName();
        if (levelName == null || levelName.isBlank()) {
            return "default";
        }
        return levelName.trim();
    }

    private static Path stateFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("frens").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        Path file = stateFile();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                Map<?, ?> raw = GSON.fromJson(reader, Map.class);
                if (raw != null) {
                    for (Map.Entry<?, ?> entry : raw.entrySet()) {
                        String alias = normalizeAlias(entry.getKey().toString());
                        if (alias.isBlank()) {
                            continue;
                        }
                        Map<String, BotState> worldMap = new HashMap<>();
                        Object val = entry.getValue();
                        if (val instanceof Map<?, ?> rawWorlds) {
                            for (Map.Entry<?, ?> w : rawWorlds.entrySet()) {
                                String wk = w.getKey().toString();
                                BotState st = GSON.fromJson(GSON.toJson(w.getValue()), BotState.class);
                                worldMap.put(wk, st);
                            }
                        }
                        STATE.computeIfAbsent(alias, ignored -> new HashMap<>()).putAll(worldMap);
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
        String legacyKey = legacyWorldKey(server);
        Map<String, BotState> worldMap = STATE.get(normalizeAlias(alias));
        if (worldMap == null) return Optional.empty();
        BotState state = worldMap.get(key);
        if (state != null) {
            return Optional.of(state);
        }

        // Backward compatibility: pre-fingerprint entries were keyed only by levelName.
        BotState legacyState = worldMap.get(legacyKey);
        if (legacyState != null) {
            worldMap.put(key, legacyState);
            worldMap.remove(legacyKey);
            flush();
            LOGGER.info("Migrated legacy bot world-state key for alias '{}' from '{}' to '{}'.", alias, legacyKey, key);
            return Optional.of(legacyState);
        }

        return Optional.empty();
    }

    public static void saveState(ServerPlayerEntity bot) {
        if (bot == null || bot.getCommandSource().getServer() == null) return;
        ensureLoaded();
        String alias = normalizeAlias(bot.getName().getString());
        if (alias.isBlank()) {
            return;
        }
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

    /** Clears the saved location snapshot for {@code alias} in the current world. */
    public static void clearState(MinecraftServer server, String alias) {
        if (server == null || alias == null || alias.isBlank()) {
            return;
        }
        ensureLoaded();
        String key = worldKey(server);
        String legacyKey = legacyWorldKey(server);
        String normalizedAlias = normalizeAlias(alias);
        Map<String, BotState> worldMap = STATE.get(normalizedAlias);
        if (worldMap == null) {
            return;
        }
        boolean removed = worldMap.remove(key) != null;
        removed = (worldMap.remove(legacyKey) != null) || removed;
        if (removed) {
            if (worldMap.isEmpty()) {
                STATE.remove(normalizedAlias);
            }
            flush();
        }
    }

    private static String normalizeAlias(String alias) {
        return alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
    }

    public static String currentWorldKey(MinecraftServer server) {
        return worldKey(server);
    }

    /**
     * Debug helper for identity audits: alias -> world-count snapshot.
     */
    public static Map<String, Integer> debugAliasWorldCounts() {
        ensureLoaded();
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Map<String, BotState>> entry : STATE.entrySet()) {
            out.put(entry.getKey(), entry.getValue() != null ? entry.getValue().size() : 0);
        }
        return Collections.unmodifiableMap(out);
    }

    public static record BotState(double x, double y, double z, float yaw, float pitch) {
        static BotState from(ServerPlayerEntity bot) {
            return new BotState(bot.getX(), bot.getY(), bot.getZ(), bot.getYaw(), bot.getPitch());
        }
    }
}
