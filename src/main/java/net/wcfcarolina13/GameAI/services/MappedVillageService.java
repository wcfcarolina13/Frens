package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.construction.FortificationPersistenceService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;
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
import java.util.Optional;

/**
 * Persisted registry of player-mapped villages represented by convex hulls.
 */
public final class MappedVillageService {

    private static final Logger LOGGER = LoggerFactory.getLogger("mapped-villages");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "mapped_villages.json";
    private static final Object LOCK = new Object();

    private static RootData DATA = new RootData();
    private static boolean loaded = false;
    private static boolean loadFailed = false;

    private MappedVillageService() {
    }

    public static final class MappedVillage {
        String name;
        String worldKey;
        String dimension;
        SavedPos center;
        List<int[]> hullVertices;
        long createdAt;

        public String getName() {
            return name != null ? name : "";
        }

        public String getDimension() {
            return dimension != null ? dimension : "";
        }

        public BlockPos getCenter() {
            return center != null ? center.toBlockPos() : BlockPos.ORIGIN;
        }

        public int getVertexCount() {
            return hullVertices != null ? hullVertices.size() : 0;
        }

        public List<VillageFortificationLayoutService.WallPoint> getHullWallPoints() {
            if (hullVertices == null || hullVertices.isEmpty()) {
                return List.of();
            }
            List<VillageFortificationLayoutService.WallPoint> out = new ArrayList<>(hullVertices.size());
            for (int[] pair : hullVertices) {
                if (pair != null && pair.length >= 2) {
                    out.add(new VillageFortificationLayoutService.WallPoint(pair[0], pair[1]));
                }
            }
            return out;
        }
    }

    static final class SavedPos {
        int x;
        int y;
        int z;

        static SavedPos from(BlockPos pos) {
            SavedPos saved = new SavedPos();
            saved.x = pos.getX();
            saved.y = pos.getY();
            saved.z = pos.getZ();
            return saved;
        }

        BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
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
        Map<String, MappedVillage> villages = new HashMap<>();
    }

    public static MappedVillage create(String name,
                                       String worldKey,
                                       String dimension,
                                       BlockPos center,
                                       List<VillageFortificationLayoutService.WallPoint> hullVertices) {
        MappedVillage village = new MappedVillage();
        village.name = name;
        village.worldKey = worldKey;
        village.dimension = dimension;
        village.center = SavedPos.from(center);
        village.hullVertices = new ArrayList<>();
        if (hullVertices != null) {
            for (VillageFortificationLayoutService.WallPoint point : hullVertices) {
                if (point != null) {
                    village.hullVertices.add(new int[]{point.x(), point.z()});
                }
            }
        }
        village.createdAt = System.currentTimeMillis();
        return village;
    }

    public static List<MappedVillage> listForWorld(MinecraftServer server, ServerWorld world) {
        ensureLoaded();
        if (server == null || world == null) {
            return List.of();
        }
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        synchronized (LOCK) {
            WorldData wd = DATA.worlds.get(worldKey);
            if (wd == null || wd.villages == null || wd.villages.isEmpty()) {
                return List.of();
            }
            return wd.villages.values().stream()
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList();
        }
    }

    public static Optional<MappedVillage> load(MinecraftServer server, ServerWorld world, String name) {
        ensureLoaded();
        if (server == null || world == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        synchronized (LOCK) {
            WorldData wd = DATA.worlds.get(FortificationPersistenceService.serverWorldKey(server, world));
            if (wd == null || wd.villages == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(wd.villages.get(normKey(name)));
        }
    }

    public static boolean save(MinecraftServer server, ServerWorld world, MappedVillage village) {
        ensureLoaded();
        if (server == null || world == null || village == null || village.getName().isBlank()) {
            return false;
        }
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        village.worldKey = worldKey;
        village.dimension = world.getRegistryKey().getValue().toString();
        synchronized (LOCK) {
            worldData(worldKey).villages.put(normKey(village.getName()), village);
        }
        flush();
        return true;
    }

    public static boolean delete(MinecraftServer server, ServerWorld world, String name) {
        ensureLoaded();
        if (server == null || world == null || name == null || name.isBlank()) {
            return false;
        }
        boolean removed;
        synchronized (LOCK) {
            WorldData wd = DATA.worlds.get(FortificationPersistenceService.serverWorldKey(server, world));
            removed = wd != null && wd.villages != null && wd.villages.remove(normKey(name)) != null;
        }
        if (removed) {
            flush();
        }
        return removed;
    }

    public static boolean rename(MinecraftServer server, ServerWorld world, String oldName, String newName) {
        ensureLoaded();
        if (server == null || world == null || oldName == null || oldName.isBlank() || newName == null || newName.isBlank()) {
            return false;
        }
        String oldKey = normKey(oldName);
        String newKey = normKey(newName);
        synchronized (LOCK) {
            WorldData wd = DATA.worlds.get(FortificationPersistenceService.serverWorldKey(server, world));
            if (wd == null || wd.villages == null) {
                return false;
            }
            MappedVillage existing = wd.villages.get(oldKey);
            if (existing == null) {
                return false;
            }
            if (!oldKey.equals(newKey) && wd.villages.containsKey(newKey)) {
                return false;
            }
            wd.villages.remove(oldKey);
            existing.name = newName.trim();
            wd.villages.put(newKey, existing);
        }
        flush();
        return true;
    }

    public static boolean containsLabel(MinecraftServer server, ServerWorld world, String name) {
        return load(server, world, name).isPresent();
    }

    public static boolean isInsideMappedVillage(ServerWorld world, BlockPos pos) {
        return getVillageAt(world, pos).isPresent();
    }

    public static boolean isNearMappedVillage(ServerWorld world, BlockPos pos, int margin) {
        if (world == null || pos == null || world.getServer() == null) {
            return false;
        }
        int extra = Math.max(0, margin);
        for (MappedVillage village : listForWorld(world.getServer(), world)) {
            List<VillageFortificationLayoutService.WallPoint> hull = village.getHullWallPoints();
            if (hull.isEmpty()) {
                continue;
            }
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (VillageFortificationLayoutService.WallPoint point : hull) {
                if (point == null) {
                    continue;
                }
                minX = Math.min(minX, point.x());
                maxX = Math.max(maxX, point.x());
                minZ = Math.min(minZ, point.z());
                maxZ = Math.max(maxZ, point.z());
            }
            if (minX == Integer.MAX_VALUE) {
                continue;
            }
            if (pos.getX() >= minX - extra && pos.getX() <= maxX + extra
                    && pos.getZ() >= minZ - extra && pos.getZ() <= maxZ + extra) {
                return true;
            }
        }
        return false;
    }

    public static Optional<MappedVillage> getVillageAt(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null || world.getServer() == null) {
            return Optional.empty();
        }
        for (MappedVillage village : listForWorld(world.getServer(), world)) {
            if (VillageFortificationLayoutService.pointInConvexHull(village.getHullWallPoints(), pos.getX(), pos.getZ())) {
                return Optional.of(village);
            }
        }
        return Optional.empty();
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
                    if (parsed != null && parsed.worlds != null) {
                        DATA = parsed;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to load mapped villages: {}", e.getMessage());
                    DATA = new RootData();
                    Path backup = UnreadableStoreFile.quarantineIfCorrupt(file, e, java.time.LocalDateTime.now(), LOGGER, "mapped villages");
                    loadFailed = backup == null;
                }
            }
            loaded = true;
        }
    }

    public static LegacyRegistryRecoveryPolicy.Preview previewLegacy(ServerWorld world) {
        ensureLoaded();
        MinecraftServer server = world.getServer();
        String legacyKey = FortificationPersistenceService.legacyServerWorldKey(server, world);
        String targetKey = FortificationPersistenceService.serverWorldKey(server, world);
        synchronized (LOCK) {
            if (DATA.worlds == null) DATA.worlds = new HashMap<>();
            WorldData legacy = DATA.worlds.get(legacyKey);
            List<String> entries = new ArrayList<>();
            int count = 0;
            if (legacy != null && legacy.villages != null) {
                for (MappedVillage record : legacy.villages.values()) {
                    if (record == null) continue;
                    count++;
                    if (entries.size() < 20) {
                        String center = record.center != null
                                ? record.center.x + "," + record.center.y + "," + record.center.z : "unknown";
                        entries.add(record.name + " center=" + center + " dimension=" + record.dimension
                                + " vertices=" + (record.hullVertices != null ? record.hullVertices.size() : 0));
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
        String targetKey = FortificationPersistenceService.serverWorldKey(server, world);
        synchronized (LOCK) {
            if (loadFailed) return LegacyRegistryRecoveryPolicy.Result.SAVE_FAILED;
            if (DATA.worlds == null) DATA.worlds = new HashMap<>();
            try {
                var copy = LegacyRegistryRecoveryPolicy.copyLegacy(DATA.worlds,
                        FortificationPersistenceService.legacyServerWorldKey(server, world), targetKey,
                        BotWorldStateService.currentWorldKey(server), confirmation,
                        legacy -> copyFor(legacy, targetKey, dimensionId(world)));
                if (copy.result() == LegacyRegistryRecoveryPolicy.Result.IMPORTED && !flush()) {
                    LegacyRegistryRecoveryPolicy.rollback(DATA.worlds, targetKey, copy.imported());
                    return LegacyRegistryRecoveryPolicy.Result.SAVE_FAILED;
                }
                return copy.result();
            } catch (Exception e) {
                LOGGER.warn("Legacy structure recovery failed: {}", e.getMessage());
                return LegacyRegistryRecoveryPolicy.Result.SAVE_FAILED;
            }
        }
    }

    /**
     * Deep copy of one bucket for {@code targetKey}, used by manual import and load-time adoption:
     * only the copy's embedded world key and dimension change, the legacy or source bucket is never touched.
     */
    private static WorldData copyFor(WorldData legacy, String targetKey, String dimension) {
        WorldData copied = GSON.fromJson(GSON.toJson(legacy), WorldData.class);
        if (copied != null && copied.villages != null) {
            for (MappedVillage record : copied.villages.values()) {
                if (record == null) continue;
                record.worldKey = targetKey;
                record.dimension = dimension;
            }
        }
        return copied;
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
                LOGGER.warn("Legacy mapped villages not adopted: its file could not be read.");
                return;
            }
            ensureRootMaps();
            try {
                var adoption = LegacyRegistryRecoveryPolicy.adoptLegacy(DATA.worlds, DATA.legacyWorldClaims,
                        DATA.legacyDeclined, rawLevelName(server), ':', fingerprint, gameTime,
                        MappedVillageService::copyFor);
                LegacyRegistryRecoveryPolicy.log(LOGGER, "mapped villages", fingerprint, adoption);
                if (adoption.changed() && !flush()) {
                    LegacyRegistryRecoveryPolicy.undo(adoption, DATA.worlds, DATA.legacyWorldClaims, DATA.legacyDeclined);
                    LOGGER.warn("Legacy mapped villages adoption was not saved and was undone. It is tried again next start.");
                }
            } catch (Exception e) {
                LOGGER.warn("Legacy mapped villages adoption failed: {}", e.getMessage());
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
        String targetKey = FortificationPersistenceService.serverWorldKey(server, world);
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
                LOGGER.warn("mapped villages import from '{}' failed: {}", sourceKey, e.getMessage());
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
        if (data == null || data.villages == null) return 0;
        int count = 0;
        for (MappedVillage record : data.villages.values()) if (record != null) count++;
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

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
    }

    private static boolean flush() {
        synchronized (LOCK) {
            if (loadFailed) {
                LOGGER.warn("Mapped villages was not saved because its original file could not be read.");
                return false;
            }
            Path temporary = null;
            try {
                Path file = stateFile();
                Files.createDirectories(file.getParent());
                temporary = Files.createTempFile(file.getParent(), "villages-", ".tmp");
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
                LOGGER.warn("Failed to save mapped villages: {}", e.getMessage());
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

    private static WorldData worldData(String worldKey) {
        if (DATA.worlds == null) {
            DATA.worlds = new HashMap<>();
        }
        return DATA.worlds.computeIfAbsent(worldKey, ignored -> new WorldData());
    }

    private static String normKey(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
