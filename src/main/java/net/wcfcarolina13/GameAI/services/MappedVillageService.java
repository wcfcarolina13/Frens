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

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persisted registry of player-mapped villages represented by convex hulls.
 */
public final class MappedVillageService {

    private static final Logger LOGGER = LoggerFactory.getLogger("mapped-villages");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "mapped_villages.json";
    private static final Path LEGACY_FILE = FabricLoader.getInstance().getConfigDir().resolve("frens_mapped_villages.json");
    private static final Object LOCK = new Object();

    private static RootData DATA = new RootData();
    private static boolean loaded = false;
    private static final Set<String> LEGACY_IMPORTED_DIMENSIONS = new HashSet<>();

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

        public String getWorldKey() {
            return worldKey != null ? worldKey : "";
        }

        public String getDimension() {
            return dimension != null ? dimension : "";
        }

        public BlockPos getCenter() {
            return center != null ? center.toBlockPos() : BlockPos.ORIGIN;
        }

        public long getCreatedAt() {
            return createdAt;
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
    }

    private static final class WorldData {
        Map<String, MappedVillage> villages = new HashMap<>();
    }

    private static final class LegacyVillageData {
        String name;
        String dimension;
        List<LegacyWallPoint> hull;
    }

    private static final class LegacyWallPoint {
        int x;
        int z;
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
        ensureWorldReady(server, world);
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
        ensureWorldReady(server, world);
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
        ensureWorldReady(server, world);
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
        ensureWorldReady(server, world);
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
        ensureWorldReady(server, world);
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

    private static void ensureWorldReady(MinecraftServer server, ServerWorld world) {
        ensureLoaded();
        importLegacyIfNeeded(server, world);
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
                }
            }
            loaded = true;
        }
    }

    private static void importLegacyIfNeeded(MinecraftServer server, ServerWorld world) {
        if (server == null || world == null || !Files.exists(LEGACY_FILE)) {
            return;
        }
        String dimension = world.getRegistryKey().getValue().toString();
        synchronized (LOCK) {
            if (LEGACY_IMPORTED_DIMENSIONS.contains(dimension)) {
                return;
            }
        }

        List<LegacyVillageData> legacyEntries;
        try (Reader reader = Files.newBufferedReader(LEGACY_FILE)) {
            LegacyVillageData[] parsed = GSON.fromJson(reader, LegacyVillageData[].class);
            legacyEntries = parsed != null ? List.of(parsed) : List.of();
        } catch (Exception e) {
            LOGGER.warn("Failed to import legacy mapped villages: {}", e.getMessage());
            synchronized (LOCK) {
                LEGACY_IMPORTED_DIMENSIONS.add(dimension);
            }
            return;
        }

        if (legacyEntries.isEmpty()) {
            synchronized (LOCK) {
                LEGACY_IMPORTED_DIMENSIONS.add(dimension);
            }
            return;
        }

        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        boolean importedAny = false;
        synchronized (LOCK) {
            WorldData wd = worldData(worldKey);
            for (LegacyVillageData legacy : legacyEntries) {
                if (legacy == null || legacy.name == null || legacy.name.isBlank()) {
                    continue;
                }
                if (legacy.dimension == null || !legacy.dimension.equals(dimension)) {
                    continue;
                }
                String key = normKey(legacy.name);
                if (wd.villages.containsKey(key)) {
                    continue;
                }
                List<VillageFortificationLayoutService.WallPoint> hull = fromLegacyHull(legacy.hull);
                if (hull.size() < 3) {
                    continue;
                }
                BlockPos center = estimateCenter(hull);
                wd.villages.put(key, create(legacy.name.trim(), worldKey, dimension, center, hull));
                importedAny = true;
            }
            LEGACY_IMPORTED_DIMENSIONS.add(dimension);
        }

        if (importedAny) {
            LOGGER.info("Imported legacy mapped villages for dimension '{}'", dimension);
            flush();
        }
    }

    private static List<VillageFortificationLayoutService.WallPoint> fromLegacyHull(List<LegacyWallPoint> hull) {
        if (hull == null || hull.isEmpty()) {
            return List.of();
        }
        List<VillageFortificationLayoutService.WallPoint> out = new ArrayList<>(hull.size());
        for (LegacyWallPoint point : hull) {
            if (point != null) {
                out.add(new VillageFortificationLayoutService.WallPoint(point.x, point.z));
            }
        }
        return out;
    }

    private static BlockPos estimateCenter(Collection<VillageFortificationLayoutService.WallPoint> hull) {
        if (hull == null || hull.isEmpty()) {
            return BlockPos.ORIGIN;
        }
        long sumX = 0L;
        long sumZ = 0L;
        int count = 0;
        for (VillageFortificationLayoutService.WallPoint point : hull) {
            if (point == null) {
                continue;
            }
            sumX += point.x();
            sumZ += point.z();
            count++;
        }
        if (count <= 0) {
            return BlockPos.ORIGIN;
        }
        return new BlockPos((int) Math.round((double) sumX / count), 64, (int) Math.round((double) sumZ / count));
    }

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
    }

    private static void flush() {
        synchronized (LOCK) {
            try {
                Path file = stateFile();
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(DATA, writer);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to save mapped villages: {}", e.getMessage());
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
