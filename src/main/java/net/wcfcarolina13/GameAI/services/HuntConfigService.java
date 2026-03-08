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
    }

    // ── Persistence plumbing ────────────────────────────────────────────

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
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
                    LOGGER.warn("Failed to load hunt config: {}", e.getMessage());
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
                LOGGER.warn("Failed to save hunt config: {}", e.getMessage());
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

    // ── Public API ──────────────────────────────────────────────────────

    public static HuntConfig getConfig(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new HuntConfig();
        }
        MinecraftServer server = world.getServer();
        if (server == null) return new HuntConfig();

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.configByBot == null) {
                wd.configByBot = new HashMap<>();
            }
            return wd.configByBot.computeIfAbsent(botKey(bot), k -> new HuntConfig());
        }
    }

    public static void setDepopulation(ServerPlayerEntity bot, boolean enabled) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;

        WorldData wd = worldData(server, world);
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

        WorldData wd = worldData(server, world);
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

        WorldData wd = worldData(server, world);
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

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.configByBot == null) wd.configByBot = new HashMap<>();
            wd.configByBot.put(botKey(bot), config);
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
