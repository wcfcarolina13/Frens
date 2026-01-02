package net.shasankp000.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-world persisted crafting history.
 * <p>
 * Stores a list of crafted recipe identifiers per bot per world so the client can present
 * a "recently crafted" list that survives restarts.
 */
public final class CraftingHistoryService {
    private static final Logger LOGGER = LoggerFactory.getLogger("crafting-history");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "crafting_history.json";
    private static final Object LOCK = new Object();

    private static RootData DATA = new RootData();
    private static boolean loaded = false;

    private CraftingHistoryService() {}

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

    private static String botKey(ServerPlayerEntity bot) {
        if (bot == null) return "";
        return bot.getName().getString().toLowerCase(Locale.ROOT);
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
                    LOGGER.warn("Failed to load crafting history: {}", e.getMessage());
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
                LOGGER.warn("Failed to save crafting history: {}", e.getMessage());
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

    public static void recordCraft(ServerPlayerEntity bot, Identifier recipeId) {
        if (bot == null || recipeId == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        Identifier airId = Registries.ITEM.getId(Items.AIR);
        if (recipeId.equals(airId) || ("minecraft".equals(recipeId.getNamespace()) && "air".equals(recipeId.getPath()))) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) return;
        String botId = botKey(bot);
        if (botId.isBlank()) return;

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.recipesByBot == null) {
                wd.recipesByBot = new HashMap<>();
            }
            List<String> list = wd.recipesByBot.computeIfAbsent(botId, k -> new ArrayList<>());
            String idStr = recipeId.toString();
            if (!list.contains(idStr)) {
                list.add(idStr);
            }
        }
        flush();
    }

    public static Set<Identifier> getHistory(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Collections.emptySet();
        }
        MinecraftServer server = world.getServer();
        if (server == null) return Collections.emptySet();
        String botId = botKey(bot);
        if (botId.isBlank()) return Collections.emptySet();

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.recipesByBot == null) return Collections.emptySet();
            List<String> list = wd.recipesByBot.get(botId);
            if (list == null || list.isEmpty()) return Collections.emptySet();
            Set<Identifier> out = new LinkedHashSet<>();
            boolean removedAir = false;
            for (String s : list) {
                try {
                    Identifier id;
                    int idx = s.indexOf(':');
                    if (idx >= 0) {
                        id = Identifier.of(s.substring(0, idx), s.substring(idx + 1));
                    } else {
                        id = Identifier.of("minecraft", s);
                    }
                    if ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath())) {
                        removedAir = true;
                        continue;
                    }
                    out.add(id);
                } catch (Exception ignored) {
                }
            }
            if (removedAir) {
                list.removeIf(entry -> "minecraft:air".equals(entry) || "air".equals(entry));
                flush();
            }
            return Collections.unmodifiableSet(out);
        }
    }

    private static final class RootData {
        Map<String, WorldData> worlds = new HashMap<>();
    }

    private static final class WorldData {
        Map<String, List<String>> recipesByBot = new HashMap<>();
    }
}
