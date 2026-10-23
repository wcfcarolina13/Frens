package net.wcfcarolina13.GameAI.services;

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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
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
 * Per-world persisted hunting history (food-mob kills).
 */
public final class HuntHistoryService {
    private static final Logger LOGGER = LoggerFactory.getLogger("hunt-history");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "hunt_history.json";
    private static final Object LOCK = new Object();

    private static RootData DATA = new RootData();
    private static boolean loaded = false;
    private static boolean loadFailed = false;

    private HuntHistoryService() {}

    private static Path stateFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("frens").resolve(FILE_NAME);
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
                    Path backup = UnreadableStoreFile.quarantineIfCorrupt(file, e, java.time.LocalDateTime.now(), LOGGER, "hunt history");
                    loadFailed = backup == null;
                }
            }
            loaded = true;
        }
    }

    private static boolean flush() {
        synchronized (LOCK) {
            if (loadFailed) {
                LOGGER.warn("Hunt history was not saved because its original file could not be read.");
                return false;
            }
            Path temporary = null;
            try {
                Path file = stateFile();
                Files.createDirectories(file.getParent());
                temporary = Files.createTempFile(file.getParent(), "hunt-history-", ".tmp");
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
                LOGGER.warn("Failed to save hunt history: {}", e.getMessage());
                return false;
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException e) {
                        LOGGER.debug("Could not remove registry temporary file: {}", e.getMessage());
                    }
                }
            }
        }
    }

    private static WorldData worldData(MinecraftServer server, ServerWorld world) {
        return worldData(server, world, false);
    }

    private static WorldData worldData(MinecraftServer server, ServerWorld world, boolean create) {
        ensureLoaded();
        String key = serverWorldKey(server, world);
        synchronized (LOCK) {
            if (DATA.worlds == null) {
                DATA.worlds = new HashMap<>();
            }
            WorldData own = DATA.worlds.get(key);
            if (own != null) return own;
            if (create) {
                own = new WorldData();
                DATA.worlds.put(key, own);
                return own;
            }
            return new WorldData();
        }
    }

    public static LegacyRegistryRecoveryPolicy.Preview previewLegacy(ServerWorld world) {
        ensureLoaded();
        MinecraftServer server = world.getServer();
        String legacyKey = legacyServerWorldKey(server, world);
        String targetKey = serverWorldKey(server, world);
        synchronized (LOCK) {
            if (DATA.worlds == null) DATA.worlds = new HashMap<>();
            WorldData legacy = DATA.worlds.get(legacyKey);
            List<String> entries = new ArrayList<>();
            int count = 0;
            if (legacy != null && legacy.huntedByPlayer != null) {
                for (var group : legacy.huntedByPlayer.entrySet()) {
                    if (group.getValue() == null) continue;
                    for (String id : group.getValue()) {
                        if (id == null) continue;
                        count++;
                        if (entries.size() < 20) entries.add(group.getKey() + ": " + id);
                    }
                }
            }
            return new LegacyRegistryRecoveryPolicy.Preview(legacyKey, targetKey, count,
                    DATA.worlds.containsKey(targetKey), List.copyOf(entries),
                    siblingSummaries(server, world, targetKey), adoptedHere(server, legacyKey));
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
                LOGGER.warn("Legacy history recovery failed: {}", e.getMessage());
                return LegacyRegistryRecoveryPolicy.Result.SAVE_FAILED;
            }
        }
    }

    /** Deep copy of one bucket for another key: the legacy or source bucket is never shared or changed. */
    private static WorldData copyFor(WorldData legacy, String targetKey, String dimension) {
        return GSON.fromJson(GSON.toJson(legacy), WorldData.class);
    }

    /**
     * Once per server start (Frens SERVER_STARTED): an existing save adopts this level name's
     * legacy buckets, every dimension, into its own fingerprinted ones (see
     * {@link LegacyRegistryRecoveryPolicy#adoptLegacy}). A brand-new save records a decline, a
     * bucket another save claimed is left alone, an own bucket always wins. No empty bucket is written.
     */
    public static void adoptLegacyWorldData(MinecraftServer server) {
        if (server == null) return;
        ensureLoaded();
        String fingerprint = BotWorldStateService.currentWorldKey(server);
        long gameTime = BotWorldStateService.overworldGameTime(server);
        synchronized (LOCK) {
            if (loadFailed) {
                LOGGER.warn("Legacy hunt history not adopted: its file could not be read.");
                return;
            }
            ensureRootMaps();
            try {
                var adoption = LegacyRegistryRecoveryPolicy.adoptLegacy(DATA.worlds, DATA.legacyWorldClaims,
                        DATA.legacyDeclined, rawLevelName(server), ':', fingerprint, gameTime,
                        HuntHistoryService::copyFor);
                LegacyRegistryRecoveryPolicy.log(LOGGER, "hunt history", fingerprint, adoption);
                if (adoption.changed() && !flush()) {
                    LegacyRegistryRecoveryPolicy.undo(adoption, DATA.worlds, DATA.legacyWorldClaims, DATA.legacyDeclined);
                    LOGGER.warn("Legacy hunt history adoption was not saved and was undone. It is tried again next start.");
                }
            } catch (Exception e) {
                LOGGER.warn("Legacy hunt history adoption failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Explicit copy from another fingerprinted bucket of this level name and dimension (a moved
     * instance): absent target only, deep copy, source kept, rolled back when the save fails.
     */
    public static LegacyRegistryRecoveryPolicy.Result importFromSibling(ServerWorld world, String confirmation,
                                                                        String sourceKey) {
        ensureLoaded();
        MinecraftServer server = world.getServer();
        String targetKey = serverWorldKey(server, world);
        String dimension = dimensionId(world);
        synchronized (LOCK) {
            if (loadFailed) return LegacyRegistryRecoveryPolicy.Result.SAVE_FAILED;
            ensureRootMaps();
            try {
                var copy = LegacyRegistryRecoveryPolicy.copyFromSibling(DATA.worlds, sourceKey,
                        BotWorldStateService.currentLevelName(server), dimension, targetKey,
                        BotWorldStateService.currentWorldKey(server), confirmation,
                        source -> copyFor(source, targetKey, dimension));
                if (copy.result() == LegacyRegistryRecoveryPolicy.Result.IMPORTED && !flush()) {
                    LegacyRegistryRecoveryPolicy.rollback(DATA.worlds, targetKey, copy.imported());
                    return LegacyRegistryRecoveryPolicy.Result.SAVE_FAILED;
                }
                return copy.result();
            } catch (Exception e) {
                LOGGER.warn("hunt history import from '{}' failed: {}", sourceKey, e.getMessage());
                return LegacyRegistryRecoveryPolicy.Result.SAVE_FAILED;
            }
        }
    }

    private static List<String> siblingSummaries(MinecraftServer server, ServerWorld world, String targetKey) {
        List<String> out = new ArrayList<>();
        if (DATA.worlds == null) return out;
        for (String key : WorldStoreKeyPolicy.siblingKeys(DATA.worlds.keySet(),
                BotWorldStateService.currentLevelName(server), dimensionId(world), targetKey)) {
            out.add(key + " records=" + recordCount(DATA.worlds.get(key)));
        }
        return List.copyOf(out);
    }

    private static boolean adoptedHere(MinecraftServer server, String legacyKey) {
        return DATA.legacyWorldClaims != null
                && BotWorldStateService.currentWorldKey(server).equals(DATA.legacyWorldClaims.get(legacyKey));
    }

    private static int recordCount(WorldData data) {
        if (data == null || data.huntedByPlayer == null) return 0;
        int count = 0;
        for (List<String> group : data.huntedByPlayer.values()) {
            if (group == null) continue;
            for (String id : group) if (id != null) count++;
        }
        return count;
    }

    private static void ensureRootMaps() {
        if (DATA.worlds == null) DATA.worlds = new HashMap<>();
        if (DATA.legacyWorldClaims == null) DATA.legacyWorldClaims = new HashMap<>();
        if (DATA.legacyDeclined == null) DATA.legacyDeclined = new java.util.HashSet<>();
    }

    /** Raw level name, as the legacy keys were written ("unknown" when missing). */
    private static String rawLevelName(MinecraftServer server) {
        return server != null && server.getSaveProperties() != null
                ? server.getSaveProperties().getLevelName() : "unknown";
    }

    private static String dimensionId(ServerWorld world) {
        return world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString() : "unknown";
    }

    /**
     * Records a hunt kill. Returns {@code true} if this is the first time the player has killed
     * this mob type (i.e., a new discovery), {@code false} if already recorded.
     */
    public static boolean recordHunt(ServerPlayerEntity player, Identifier entityId) {
        if (player == null || entityId == null || !(player.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String playerId = playerKey(player);
        if (playerId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world, true);
        boolean isNew;
        synchronized (LOCK) {
            if (wd.huntedByPlayer == null) {
                wd.huntedByPlayer = new HashMap<>();
            }
            List<String> list = wd.huntedByPlayer.computeIfAbsent(playerId, k -> new java.util.ArrayList<>());
            String idStr = entityId.toString();
            isNew = !list.contains(idStr);
            if (isNew) {
                list.add(idStr);
            }
        }
        flush();
        return isNew;
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
        /** Legacy (level-name) key -> fingerprint of the save that adopted it at load. */
        Map<String, String> legacyWorldClaims = new HashMap<>();
        /** Fingerprinted keys of brand-new saves that declined the legacy bucket, so the decline sticks. */
        java.util.Set<String> legacyDeclined = new java.util.HashSet<>();
    }

    private static final class WorldData {
        Map<String, List<String>> huntedByPlayer = new HashMap<>();
    }
}
