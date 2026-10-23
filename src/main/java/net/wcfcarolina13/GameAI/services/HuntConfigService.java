package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-bot persistent hunt configuration: depopulation toggle, zone size, selected targets.
 * Follows the BotHomeService persistence pattern.
 */
public final class HuntConfigService {
    private static final Logger LOGGER = LoggerFactory.getLogger("hunt-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "hunt_config.json";
    private static final Object LOCK = new Object();

    private static RootData DATA = new RootData();
    private static boolean loaded = false;
    private static boolean loadFailed = false;

    private HuntConfigService() {}

    // ── Zone sizes ──────────────────────────────────────────────────────

    public enum HuntZone {
        STANDARD(48, 8, "Standard"),
        EXPANDED(80, 12, "Expanded"),
        SPRAWLING(120, 16, "Sprawling");

        public final int radius;
        public final int ySpan;
        public final String label;

        HuntZone(int radius, int ySpan, String label) {
            this.radius = radius;
            this.ySpan = ySpan;
            this.label = label;
        }

        public HuntZone next() {
            HuntZone[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public static HuntZone fromName(String name) {
            if (name == null) return STANDARD;
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return STANDARD;
            }
        }
    }

    // ── Config data record ──────────────────────────────────────────────

    public static final class HuntConfig {
        public boolean depopulationEnabled = true;
        public String zone = HuntZone.STANDARD.name();
        public List<String> selectedTargets = new ArrayList<>();

        public HuntConfig() {}

        public HuntConfig(boolean depopulationEnabled, HuntZone zone, List<String> selectedTargets) {
            this.depopulationEnabled = depopulationEnabled;
            this.zone = zone.name();
            this.selectedTargets = selectedTargets != null ? new ArrayList<>(selectedTargets) : new ArrayList<>();
        }

        public HuntZone huntZone() {
            return HuntZone.fromName(zone);
        }

        public HuntConfig copy() {
            HuntConfig snapshot = new HuntConfig();
            snapshot.depopulationEnabled = depopulationEnabled;
            snapshot.zone = zone;
            snapshot.selectedTargets = selectedTargets == null ? new ArrayList<>() : new ArrayList<>(selectedTargets);
            return snapshot;
        }
    }

    // ── Persistence plumbing ────────────────────────────────────────────

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
    }

    private static String serverWorldKey(MinecraftServer server, ServerWorld world) {
        String dimension = world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString() : "unknown";
        return WorldStoreKeyPolicy.fingerprintKey(BotWorldStateService.currentWorldKey(server), dimension);
    }

    private static String legacyServerWorldKey(MinecraftServer server, ServerWorld world) {
        String level = server != null && server.getSaveProperties() != null
                ? server.getSaveProperties().getLevelName()
                : "unknown";
        String dim = world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString()
                : "unknown";
        return level + ":" + dim;
    }

    private static String botKey(ServerPlayerEntity bot) {
        return bot.getName().getString().toLowerCase(Locale.ROOT);
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) return;
            Path file = stateFile();
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    RootData parsed = GSON.fromJson(reader, RootData.class);
                    if (parsed != null) {
                        DATA = parsed;
                    }
                } catch (Exception e) {
                    loadFailed = true;
                    LOGGER.warn("Failed to load hunt config: {}", e.getMessage());
                    DATA = new RootData();
                }
            }
            loaded = true;
        }
    }

    private static boolean flush() {
        synchronized (LOCK) {
            if (loadFailed) {
                LOGGER.warn("Hunt config was not saved because its original file could not be read.");
                return false;
            }
            Path temporary = null;
            try {
                Path file = stateFile();
                Files.createDirectories(file.getParent());
                temporary = Files.createTempFile(file.getParent(), "hunt-config-", ".tmp");
                try (Writer writer = Files.newBufferedWriter(temporary)) {
                    GSON.toJson(DATA, writer);
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (Exception e) {
                LOGGER.warn("Failed to save hunt config: {}", e.getMessage());
                return false;
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException e) {
                        LOGGER.debug("Could not remove hunt-config temporary file: {}", e.getMessage());
                    }
                }
            }
        }
    }

    private static WorldData worldData(MinecraftServer server, ServerWorld world, boolean create) {
        ensureLoaded();
        String key = serverWorldKey(server, world);
        synchronized (LOCK) {
            if (DATA.worlds == null) {
                if (!create) return new WorldData();
                DATA.worlds = new HashMap<>();
            }
            WorldData own = DATA.worlds.get(key);
            if (own != null) return own;
            if (!create) return new WorldData();
            own = new WorldData();
            DATA.worlds.put(key, own);
            return own;
        }
    }

    public static LegacyRegistryRecoveryPolicy.Preview previewLegacy(ServerWorld world) {
        ensureLoaded();
        MinecraftServer server = world.getServer();
        String legacyKey = legacyServerWorldKey(server, world);
        String targetKey = serverWorldKey(server, world);
        synchronized (LOCK) {
            WorldData legacy = DATA.worlds == null ? null : DATA.worlds.get(legacyKey);
            List<String> entries = new ArrayList<>();
            int count = 0;
            if (legacy != null && legacy.configByBot != null) {
                for (var entry : legacy.configByBot.entrySet()) {
                    HuntConfig config = entry.getValue();
                    if (config == null) continue;
                    count++;
                    if (entries.size() < 20) entries.add(entry.getKey() + " depopulation=" + config.depopulationEnabled
                            + " zone=" + config.zone + " targets=" + config.selectedTargets);
                }
            }
            return new LegacyRegistryRecoveryPolicy.Preview(legacyKey, targetKey, count,
                    DATA.worlds != null && DATA.worlds.containsKey(targetKey), List.copyOf(entries));
        }
    }

    public static LegacyRegistryRecoveryPolicy.Result importLegacy(ServerWorld world, String confirmation) {
        ensureLoaded();
        MinecraftServer server = world.getServer();
        String targetKey = serverWorldKey(server, world);
        synchronized (LOCK) {
            if (loadFailed) return LegacyRegistryRecoveryPolicy.Result.SAVE_FAILED;
            if (DATA.worlds == null) DATA.worlds = new HashMap<>();
            try {
                var copy = LegacyRegistryRecoveryPolicy.copyLegacy(DATA.worlds,
                        legacyServerWorldKey(server, world), targetKey,
                        BotWorldStateService.currentWorldKey(server), confirmation,
                        legacy -> GSON.fromJson(GSON.toJson(legacy), WorldData.class));
                if (copy.result() == LegacyRegistryRecoveryPolicy.Result.IMPORTED && !flush()) {
                    LegacyRegistryRecoveryPolicy.rollback(DATA.worlds, targetKey, copy.imported());
                    return LegacyRegistryRecoveryPolicy.Result.SAVE_FAILED;
                }
                return copy.result();
            } catch (Exception e) {
                LOGGER.warn("Legacy hunt-config recovery failed: {}", e.getMessage());
                return LegacyRegistryRecoveryPolicy.Result.SAVE_FAILED;
            }
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    public static HuntConfig getConfig(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new HuntConfig();
        }
        MinecraftServer server = world.getServer();
        if (server == null) return new HuntConfig();

        WorldData wd = worldData(server, world, false);
        synchronized (LOCK) {
            HuntConfig config = wd.configByBot == null ? null : wd.configByBot.get(botKey(bot));
            return config == null ? new HuntConfig() : config.copy();
        }
    }

    public static void setDepopulation(ServerPlayerEntity bot, boolean enabled) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;

        WorldData wd = worldData(server, world, true);
        synchronized (LOCK) {
            if (wd.configByBot == null) wd.configByBot = new HashMap<>();
            HuntConfig cfg = wd.configByBot.computeIfAbsent(botKey(bot), k -> new HuntConfig());
            cfg.depopulationEnabled = enabled;
        }
        flush();
    }

    public static void setZone(ServerPlayerEntity bot, HuntZone zone) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;

        WorldData wd = worldData(server, world, true);
        synchronized (LOCK) {
            if (wd.configByBot == null) wd.configByBot = new HashMap<>();
            HuntConfig cfg = wd.configByBot.computeIfAbsent(botKey(bot), k -> new HuntConfig());
            cfg.zone = zone.name();
        }
        flush();
    }

    public static void setSelectedTargets(ServerPlayerEntity bot, List<String> targets) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;

        WorldData wd = worldData(server, world, true);
        synchronized (LOCK) {
            if (wd.configByBot == null) wd.configByBot = new HashMap<>();
            HuntConfig cfg = wd.configByBot.computeIfAbsent(botKey(bot), k -> new HuntConfig());
            cfg.selectedTargets = targets != null ? new ArrayList<>(targets) : new ArrayList<>();
        }
        flush();
    }

    public static void saveConfig(ServerPlayerEntity bot, HuntConfig config) {
        if (bot == null || config == null || !(bot.getEntityWorld() instanceof ServerWorld world)) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;

        WorldData wd = worldData(server, world, true);
        synchronized (LOCK) {
            if (wd.configByBot == null) wd.configByBot = new HashMap<>();
            wd.configByBot.put(botKey(bot), config.copy());
        }
        flush();
    }

    // ── Data hierarchy ──────────────────────────────────────────────────

    private static final class RootData {
        Map<String, WorldData> worlds = new HashMap<>();
    }

    private static final class WorldData {
        Map<String, HuntConfig> configByBot = new HashMap<>();
    }
}
