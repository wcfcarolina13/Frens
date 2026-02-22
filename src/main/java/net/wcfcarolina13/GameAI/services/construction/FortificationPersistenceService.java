package net.wcfcarolina13.GameAI.services.construction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Persists fortification wall data (hull vertices, build progress) to JSON.
 * Follows the same pattern as {@link net.wcfcarolina13.GameAI.services.BotHomeService}:
 * Gson, per-world keyed, synchronized access, immediate flush on writes.
 *
 * <p>Storage file: {@code config/frens/bot_fortifications.json}
 */
public final class FortificationPersistenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-fortifications");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "bot_fortifications.json";
    private static final Object LOCK = new Object();

    private static RootData DATA = new RootData();
    private static boolean loaded = false;

    private FortificationPersistenceService() {}

    // ── Public data types ───────────────────────────────────────

    /** Minimum ratio of placed/planned blocks for an edge to be considered complete. */
    public static final double EDGE_COMPLETION_THRESHOLD = 0.85;

    /** Saved fortification wall data. */
    public static final class SavedFortification {
        String name;
        String worldKey;
        SavedPos center;
        List<int[]> hullVertices; // [[x,z], ...]
        int searchRadius;
        long createdAt;
        Set<Integer> completedEdges = new HashSet<>();
        int lastEdgeIndex;
        int totalBlocksPlaced;
        boolean complete;
        /** Per-edge planned block counts (edgeIndex -> total planned). Null-safe for old saves. */
        Map<Integer, Integer> edgePlannedCounts;
        /** Per-edge actual placed block counts (edgeIndex -> blocks confirmed placed). Null-safe for old saves. */
        Map<Integer, Integer> edgeActualCounts;
        /** Original terrain Y at each wall column XZ ("x,z" -> Y). Null-safe for old saves. */
        Map<String, Integer> surfaceProfile;

        public String getName() { return name; }
        public String getWorldKey() { return worldKey; }
        public BlockPos getCenter() { return center != null ? center.toBlockPos() : BlockPos.ORIGIN; }
        public int getSearchRadius() { return searchRadius; }
        public long getCreatedAt() { return createdAt; }
        public Set<Integer> getCompletedEdges() { return completedEdges != null ? completedEdges : new HashSet<>(); }
        public int getLastEdgeIndex() { return lastEdgeIndex; }
        public int getTotalBlocksPlaced() { return totalBlocksPlaced; }
        public boolean isComplete() { return complete; }
        public Map<Integer, Integer> getEdgePlannedCounts() { return edgePlannedCounts != null ? edgePlannedCounts : Map.of(); }
        public Map<Integer, Integer> getEdgeActualCounts() { return edgeActualCounts != null ? edgeActualCounts : Map.of(); }
        public Map<String, Integer> getSurfaceProfile() { return surfaceProfile; }
        public void setSurfaceProfile(Map<String, Integer> profile) { this.surfaceProfile = profile; }
        public void setTotalBlocksPlaced(int count) { this.totalBlocksPlaced = count; }

        /** Reconstruct hull vertices as WallPoints for layout regeneration. */
        public List<VillageFortificationLayoutService.WallPoint> getHullWallPoints() {
            if (hullVertices == null) return List.of();
            List<VillageFortificationLayoutService.WallPoint> points = new ArrayList<>();
            for (int[] pair : hullVertices) {
                if (pair.length >= 2) {
                    points.add(new VillageFortificationLayoutService.WallPoint(pair[0], pair[1]));
                }
            }
            return points;
        }
    }

    static final class SavedPos {
        int x, y, z;

        static SavedPos from(BlockPos pos) {
            SavedPos p = new SavedPos();
            p.x = pos.getX();
            p.y = pos.getY();
            p.z = pos.getZ();
            return p;
        }

        BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Save (create or update) a fortification.
     */
    public static void save(MinecraftServer server, SavedFortification fort) {
        ensureLoaded();
        synchronized (LOCK) {
            WorldData wd = worldData(fort.worldKey);
            wd.fortifications.put(normKey(fort.name), fort);
        }
        flush();
        LOGGER.info("Saved fortification '{}' in world '{}'", fort.name, fort.worldKey);
    }

    /**
     * Create a new SavedFortification from current build state.
     *
     * @param edgePlannedCounts per-edge planned block counts (edgeIndex -> total planned)
     */
    public static SavedFortification create(
            String name,
            String worldKey,
            BlockPos center,
            List<VillageFortificationLayoutService.WallPoint> hullVertices,
            int searchRadius,
            Map<Integer, Integer> edgePlannedCounts
    ) {
        SavedFortification fort = new SavedFortification();
        fort.name = name;
        fort.worldKey = worldKey;
        fort.center = SavedPos.from(center);
        fort.hullVertices = new ArrayList<>();
        for (VillageFortificationLayoutService.WallPoint wp : hullVertices) {
            fort.hullVertices.add(new int[]{wp.x(), wp.z()});
        }
        fort.searchRadius = searchRadius;
        fort.createdAt = System.currentTimeMillis();
        fort.completedEdges = new HashSet<>();
        fort.lastEdgeIndex = 0;
        fort.totalBlocksPlaced = 0;
        fort.complete = false;
        fort.edgePlannedCounts = edgePlannedCounts != null ? new HashMap<>(edgePlannedCounts) : new HashMap<>();
        fort.edgeActualCounts = new HashMap<>();
        fort.surfaceProfile = new HashMap<>();
        return fort;
    }

    /**
     * Load a saved fortification by name for a given world.
     */
    public static Optional<SavedFortification> load(MinecraftServer server, String worldKey, String name) {
        ensureLoaded();
        synchronized (LOCK) {
            WorldData wd = DATA.worlds.get(worldKey);
            if (wd == null) return Optional.empty();
            SavedFortification fort = wd.fortifications.get(normKey(name));
            return Optional.ofNullable(fort);
        }
    }

    /**
     * List all saved fortifications for a given world.
     */
    public static List<SavedFortification> listForWorld(MinecraftServer server, String worldKey) {
        ensureLoaded();
        synchronized (LOCK) {
            WorldData wd = DATA.worlds.get(worldKey);
            if (wd == null) return List.of();
            return new ArrayList<>(wd.fortifications.values());
        }
    }

    /**
     * Record blocks placed for an edge and mark it complete only if
     * actual/planned ratio >= {@link #EDGE_COMPLETION_THRESHOLD}.
     *
     * @return true if the edge is now considered complete
     */
    public static boolean markEdgeComplete(MinecraftServer server, String worldKey, String name,
                                            int edgeIndex, int blocksPlacedThisEdge, int plannedBlocks) {
        boolean edgeComplete;
        ensureLoaded();
        synchronized (LOCK) {
            WorldData wd = DATA.worlds.get(worldKey);
            if (wd == null) return false;
            SavedFortification fort = wd.fortifications.get(normKey(name));
            if (fort == null) return false;
            if (fort.completedEdges == null) fort.completedEdges = new HashSet<>();
            if (fort.edgeActualCounts == null) fort.edgeActualCounts = new HashMap<>();
            if (fort.edgePlannedCounts == null) fort.edgePlannedCounts = new HashMap<>();

            // Update actual count for this edge
            fort.edgeActualCounts.merge(edgeIndex, blocksPlacedThisEdge, Integer::sum);
            fort.edgePlannedCounts.putIfAbsent(edgeIndex, plannedBlocks);

            fort.lastEdgeIndex = edgeIndex;
            fort.totalBlocksPlaced += blocksPlacedThisEdge;

            // Check threshold
            int actual = fort.edgeActualCounts.getOrDefault(edgeIndex, 0);
            int planned = fort.edgePlannedCounts.getOrDefault(edgeIndex, 1);
            double ratio = planned > 0 ? (double) actual / planned : 0;
            edgeComplete = ratio >= EDGE_COMPLETION_THRESHOLD;
            if (edgeComplete) {
                fort.completedEdges.add(edgeIndex);
            }
        }
        flush();
        return edgeComplete;
    }

    /**
     * Update the last working edge and blocks placed (for partial progress saves).
     */
    public static void updateProgress(MinecraftServer server, String worldKey, String name,
                                       int lastEdgeIndex, int totalBlocksPlaced) {
        ensureLoaded();
        synchronized (LOCK) {
            WorldData wd = DATA.worlds.get(worldKey);
            if (wd == null) return;
            SavedFortification fort = wd.fortifications.get(normKey(name));
            if (fort == null) return;
            fort.lastEdgeIndex = lastEdgeIndex;
            fort.totalBlocksPlaced = totalBlocksPlaced;
        }
        flush();
    }

    /**
     * Mark a fortification as fully complete.
     */
    public static void markComplete(MinecraftServer server, String worldKey, String name) {
        ensureLoaded();
        synchronized (LOCK) {
            WorldData wd = DATA.worlds.get(worldKey);
            if (wd == null) return;
            SavedFortification fort = wd.fortifications.get(normKey(name));
            if (fort == null) return;
            fort.complete = true;
        }
        flush();
    }

    /**
     * Rename a saved fortification.
     */
    public static boolean rename(MinecraftServer server, String worldKey, String oldName, String newName) {
        ensureLoaded();
        synchronized (LOCK) {
            WorldData wd = DATA.worlds.get(worldKey);
            if (wd == null) return false;
            String oldKey = normKey(oldName);
            String newKey = normKey(newName);
            SavedFortification fort = wd.fortifications.remove(oldKey);
            if (fort == null) return false;
            if (wd.fortifications.containsKey(newKey)) {
                // Restore old entry if new name already exists
                wd.fortifications.put(oldKey, fort);
                return false;
            }
            fort.name = newName;
            wd.fortifications.put(newKey, fort);
        }
        flush();
        LOGGER.info("Renamed fortification '{}' to '{}' in world '{}'", oldName, newName, worldKey);
        return true;
    }

    /**
     * Delete a saved fortification.
     */
    public static boolean delete(MinecraftServer server, String worldKey, String name) {
        ensureLoaded();
        synchronized (LOCK) {
            WorldData wd = DATA.worlds.get(worldKey);
            if (wd == null) return false;
            return wd.fortifications.remove(normKey(name)) != null;
        }
    }

    /**
     * Generate an auto-name from village center coordinates.
     */
    public static String autoName(BlockPos center) {
        return "wall_" + center.getX() + "_" + center.getZ();
    }

    /**
     * Build the world key from server + world, matching BotHomeService pattern.
     */
    public static String serverWorldKey(MinecraftServer server, ServerWorld world) {
        String level = server != null && server.getSaveProperties() != null
                ? server.getSaveProperties().getLevelName()
                : "unknown";
        String dim = world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString()
                : "unknown";
        return level + ":" + dim;
    }

    // ── Internal persistence ────────────────────────────────────

    private static Path stateFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("frens").resolve(FILE_NAME);
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
                    LOGGER.warn("Failed to load fortification data: {}", e.getMessage());
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
                LOGGER.warn("Failed to save fortification data: {}", e.getMessage());
            }
        }
    }

    private static WorldData worldData(String worldKey) {
        if (DATA.worlds == null) DATA.worlds = new HashMap<>();
        return DATA.worlds.computeIfAbsent(worldKey, ignored -> new WorldData());
    }

    private static String normKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    // ── Data model ──────────────────────────────────────────────

    private static final class RootData {
        Map<String, WorldData> worlds = new HashMap<>();
    }

    private static final class WorldData {
        Map<String, SavedFortification> fortifications = new HashMap<>();
    }
}
