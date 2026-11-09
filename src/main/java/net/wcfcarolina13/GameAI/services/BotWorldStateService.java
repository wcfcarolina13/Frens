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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
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
    private static boolean loadFailed = false;

    private BotWorldStateService() {}

    private static String worldKey(MinecraftServer server) {
        return WorldStoreKeyPolicy.fingerprint(legacyWorldKey(server), savePathHash(server));
    }

    /** Hash of the save folder path, or "unknown" when it can't be resolved. */
    private static String savePathHash(MinecraftServer server) {
        if (server != null) {
            try {
                Path root = server.getSavePath(WorldSavePath.ROOT);
                if (root != null) {
                    return WorldStoreKeyPolicy.savePathHash(root.toAbsolutePath().normalize().toString());
                }
            } catch (Exception ignored) {
            }
        }
        return WorldStoreKeyPolicy.UNKNOWN_PATH_HASH;
    }

    private static String legacyWorldKey(MinecraftServer server) {
        if (server == null || server.getSaveProperties() == null) {
            return "default";
        }
        return WorldStoreKeyPolicy.levelNameOrDefault(server.getSaveProperties().getLevelName());
    }

    private static Path stateFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("frens").resolve(FILE_NAME);
    }

    private static synchronized void ensureLoaded() {
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
                Path backup = UnreadableStoreFile.quarantineIfCorrupt(file, e, java.time.LocalDateTime.now(), LOGGER, "bot world state");
                loadFailed = backup == null;
                if (!loadFailed) {
                    // The parse may have filled part of the map before it failed.
                    STATE.clear();
                }
            }
        }
        loaded = true;
    }

    private static synchronized boolean flush() {
        if (loadFailed) {
            LOGGER.warn("Bot world state was not saved because its original file could not be read.");
            return false;
        }
        Path temporary = null;
        try {
            Path file = stateFile();
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "bot-world-state-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(STATE, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to save bot world state: {}", e.getMessage());
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    LOGGER.debug("Could not remove world-state temporary file: {}", e.getMessage());
                }
            }
        }
    }

    public static synchronized Optional<BotState> loadState(MinecraftServer server, String alias) {
        ensureLoaded();
        return LegacyWorldStateRecoveryPolicy.current(STATE.get(normalizeAlias(alias)), worldKey(server));
    }

    /** Legacy positions are ambiguous: only an administrator's explicit import may copy them. */
    public static synchronized Optional<BotState> legacyState(MinecraftServer server, String alias) {
        ensureLoaded();
        Map<String, BotState> states = STATE.get(normalizeAlias(alias));
        return states == null ? Optional.empty() : Optional.ofNullable(states.get(legacyWorldKey(server)));
    }

    public static synchronized java.util.List<String> pendingLegacyAliases(MinecraftServer server) {
        ensureLoaded();
        String target = worldKey(server);
        String legacy = legacyWorldKey(server);
        return STATE.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().get(legacy) != null
                        && !entry.getValue().containsKey(target))
                .map(Map.Entry::getKey).sorted().toList();
    }

    public static synchronized LegacyWorldStateRecoveryPolicy.Result importLegacyState(
            MinecraftServer server, String alias, String confirmation) {
        ensureLoaded();
        if (loadFailed) return LegacyWorldStateRecoveryPolicy.Result.SAVE_FAILED;
        Map<String, BotState> states = STATE.get(normalizeAlias(alias));
        String target = worldKey(server);
        var result = LegacyWorldStateRecoveryPolicy.copyLegacy(states, legacyWorldKey(server), target, confirmation);
        if (result == LegacyWorldStateRecoveryPolicy.Result.IMPORTED) {
            BotState imported = states.get(target);
            if (!flush()) {
                LegacyWorldStateRecoveryPolicy.rollback(states, target, imported);
                return LegacyWorldStateRecoveryPolicy.Result.SAVE_FAILED;
            }
        }
        return result;
    }

    public static synchronized void saveState(ServerPlayerEntity bot) {
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

    /**
     * Write a position directly without needing a live {@link ServerPlayerEntity}.
     * Used by the fast travel system to pre-write the destination as the bot's
     * saved position before removing the bot from the world.
     */
    public static synchronized void saveStateManual(MinecraftServer server, String alias,
                                       double x, double y, double z, float yaw, float pitch) {
        if (server == null || alias == null || alias.isBlank()) return;
        ensureLoaded();
        String key = worldKey(server);
        Map<String, BotState> worldMap = STATE.computeIfAbsent(normalizeAlias(alias), k -> new HashMap<>());
        String name = savedDisplayName(alias).orElse(alias.trim());
        worldMap.put(key, new BotState(x, y, z, yaw, Math.max(-90.0F, Math.min(90.0F, pitch)), name));
        flush();
    }

    public static synchronized void saveAll(MinecraftServer server) {
        if (server == null) return;
        ensureLoaded();
        flush();
    }

    /** Clears the saved location snapshot for {@code alias} in the current world. */
    public static synchronized void clearState(MinecraftServer server, String alias) {
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
        boolean removed = LegacyWorldStateRecoveryPolicy.clearCurrent(worldMap, legacyKey, key);
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

    /**
     * The save fingerprint ({@code levelName#savePathHash}): unique per save folder, unlike the
     * level name. Every per-world store keys on this (see {@link WorldStoreKeyPolicy}).
     */
    public static String currentWorldKey(MinecraftServer server) {
        return worldKey(server);
    }

    /** Overworld game time (not time of day), 0 when unknown: the brand-new-world check for store adoption. */
    public static long overworldGameTime(MinecraftServer server) {
        return server == null ? 0L : WorldZoneStore.overworldGameTime(server);
    }

    /** Trimmed level name ("default" when unknown): display only, never a store key. */
    public static String currentLevelName(MinecraftServer server) {
        return legacyWorldKey(server);
    }

    /**
     * Debug helper for identity audits: alias -> world-count snapshot.
     */
    public static synchronized Map<String, Integer> debugAliasWorldCounts() {
        ensureLoaded();
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Map<String, BotState>> entry : STATE.entrySet()) {
            out.put(entry.getKey(), entry.getValue() != null ? entry.getValue().size() : 0);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Read-only copy of alias (lower-cased) to the world keys it has a saved position under.
     * Never migrates or flushes. Call on the server thread.
     */
    public static synchronized Map<String, java.util.Set<String>> worldKeysByAlias() {
        ensureLoaded();
        Map<String, java.util.Set<String>> out = new HashMap<>();
        for (Map.Entry<String, Map<String, BotState>> entry : STATE.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.put(entry.getKey(), new java.util.HashSet<>(entry.getValue().keySet()));
            }
        }
        return out;
    }

    /**
     * {@code name}: the bot's name as spelled (the alias key is lower-cased), null in entries written
     * before this field existed. Auto-spawn spawns under it when the settings file had to be reset, so a bot does
     * not come back as a lower-case, different player.
     */
    /**
     * {@code savedAtMs}: when the entry was last written (0 in older entries); entries are refreshed
     * while the bot is online, so it is about when the bot was last in this world. {@code shelved}:
     * true once the bot was despawned for good here (null in older entries). Both let auto-spawn,
     * after the settings file was reset and lost its spawn flags, bring back only the bots that were
     * active in this world (AutoSpawnActivityPolicy).
     */
    public static record BotState(double x, double y, double z, float yaw, float pitch, String name,
                                  long savedAtMs, Boolean shelved) {
        public BotState(double x, double y, double z, float yaw, float pitch) {
            this(x, y, z, yaw, pitch, null, 0L, null);
        }

        public BotState(double x, double y, double z, float yaw, float pitch, String name) {
            this(x, y, z, yaw, pitch, name, System.currentTimeMillis(), Boolean.FALSE);
        }

        static BotState from(ServerPlayerEntity bot) {
            float rawPitch = bot.getPitch();
            float clampedPitch = Math.max(-90.0F, Math.min(90.0F, rawPitch));
            return new BotState(bot.getX(), bot.getY(), bot.getZ(), bot.getYaw(), clampedPitch, bot.getName().getString());
        }

        BotState shelvedCopy(boolean value) {
            return new BotState(x, y, z, yaw, pitch, name, savedAtMs, value);
        }
    }

    /** Marks this bot's entry in the current world as put away (despawned for good) or not. */
    public static synchronized void markShelved(MinecraftServer server, String alias, boolean value) {
        if (server == null || alias == null || alias.isBlank()) {
            return;
        }
        ensureLoaded();
        Map<String, BotState> worldMap = STATE.get(normalizeAlias(alias));
        String key = worldKey(server);
        BotState st = worldMap == null ? null : worldMap.get(key);
        if (st == null) {
            return;
        }
        worldMap.put(key, st.shelvedCopy(value));
        flush();
    }

    /** The current world's entry for {@code alias} as stored (no legacy fallback), or empty. */
    public static synchronized Optional<BotState> currentEntry(MinecraftServer server, String alias) {
        ensureLoaded();
        Map<String, BotState> worldMap = STATE.get(normalizeAlias(alias));
        return worldMap == null ? Optional.empty() : Optional.ofNullable(worldMap.get(worldKey(server)));
    }

    /** The newest {@code savedAtMs} among this world's entries, 0 when none has one. */
    public static synchronized long newestSavedAtMs(MinecraftServer server) {
        ensureLoaded();
        String key = worldKey(server);
        long newest = 0L;
        for (Map<String, BotState> worlds : STATE.values()) {
            BotState st = worlds == null ? null : worlds.get(key);
            if (st != null && st.savedAtMs() > newest) {
                newest = st.savedAtMs();
            }
        }
        return newest;
    }

    /** The name as spelled that a saved entry for {@code alias} recorded, in any world; empty for older entries. */
    public static synchronized Optional<String> savedDisplayName(String alias) {
        ensureLoaded();
        Map<String, BotState> worlds = STATE.get(normalizeAlias(alias));
        if (worlds == null) {
            return Optional.empty();
        }
        return worlds.values().stream()
                .map(st -> st == null ? null : st.name())
                .filter(n -> n != null && n.equalsIgnoreCase(alias.trim()))
                .findFirst();
    }
}
