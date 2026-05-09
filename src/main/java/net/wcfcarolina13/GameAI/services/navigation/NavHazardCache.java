package net.wcfcarolina13.GameAI.services.navigation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.wcfcarolina13.GameAI.services.BotWorldStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-save × per-dimension cache of cells where bot movement-impulse rejections
 * have repeated enough to warrant routing around them. Accumulates a {@code score}
 * per cell that pathfinders consult as an additive cost penalty. Score decays
 * linearly with wall-clock time and decrements faster on successful traversal,
 * so resolved obstacles age out automatically.
 *
 * <p>Recording site: {@code BotActions.applyMovementInput} rejection branch
 * (server thread). Pathfinder reads happen from worker threads; thread-safety
 * via {@link ConcurrentHashMap} + volatile fields on the entry. Atomic update
 * is not required — worst case is a stale score read on the pathfinder side,
 * which the next tick corrects.
 *
 * <p>Persistence: single JSON file at {@code <configDir>/frens/nav_hazard_cache.json},
 * partitioned by {@code worldKey -> dimensionId -> "x,y,z"}. Mirrors
 * {@link BotWorldStateService} for save-root-hash scoping.
 */
public final class NavHazardCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("nav-hazard");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "nav_hazard_cache.json";

    static final float REJECT_INCREMENT = 1.5F;
    static final float SUCCESS_DECREMENT = 3.0F;
    static final float MAX_SCORE = 50.0F;
    static final float DECAY_PER_TICK = 1.0F / 400.0F;          // 0.05/sec @ 20 TPS
    static final double PENALTY_SCALE = 0.4D;
    static final double PENALTY_CAP = 12.0D;
    static final long STREAK_WINDOW_TICKS = 40L;
    static final int STREAK_PROMOTION_THRESHOLD = 3;
    static final long EVICTION_AGE_TICKS = 12_000L;             // 10 min idle
    static final float EVICTION_SCORE_THRESHOLD = 0.1F;
    static final long DECAY_INTERVAL_TICKS = 200L;              // 10 s
    static final long FLUSH_INTERVAL_TICKS = 600L;              // 30 s, mirrors BotPersistenceService
    static final long DIAGNOSTIC_PENALTY_LOG_THROTTLE_MS = 1000L;
    static final long SUMMARY_LOG_INTERVAL_TICKS = 6_000L;      // 5 min: periodic learning summary

    private static final ConcurrentHashMap<String,
            ConcurrentHashMap<String,
                    ConcurrentHashMap<Long, HazardEntry>>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, FootCell> LAST_FOOT_CELL = new ConcurrentHashMap<>();

    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);
    private static volatile ScheduledExecutorService FLUSH_EXEC = null;
    private static long lastDecayTick = 0L;
    private static long lastFlushTick = 0L;
    private static long lastSummaryTick = 0L;
    private static volatile long lastPenaltyLogMs = 0L;

    private NavHazardCache() {}

    public static void recordRejection(ServerWorld world, double x, double y, double z) {
        if (world == null) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;
        String worldKey = BotWorldStateService.currentWorldKey(server);
        String dim = dimensionId(world);
        long tick = world.getTime();

        BlockPos cell = BlockPos.ofFloored(x, y, z);
        long key = cell.asLong();

        ConcurrentHashMap<Long, HazardEntry> dimMap = CACHE
                .computeIfAbsent(worldKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(dim, k -> new ConcurrentHashMap<>());

        HazardEntry entry = dimMap.computeIfAbsent(key, k -> {
            HazardEntry e = new HazardEntry();
            e.firstSeenTick = tick;
            return e;
        });

        synchronized (entry) {
            entry.rejects++;
            if (tick - entry.lastTick > STREAK_WINDOW_TICKS) {
                entry.streakRejects = 1;
                entry.firstSeenTick = tick;
            } else {
                entry.streakRejects++;
            }
            entry.lastTick = tick;
            if (entry.streakRejects >= STREAK_PROMOTION_THRESHOLD) {
                entry.score = Math.min(MAX_SCORE, entry.score + REJECT_INCREMENT);
            }
        }
        DIRTY.set(true);
    }

    public static double penaltyFor(World world, BlockPos pos) {
        if (pos == null) return 0.0D;
        return penaltyFor(world, pos.getX(), pos.getY(), pos.getZ());
    }

    public static double penaltyFor(World world, int x, int y, int z) {
        if (!(world instanceof ServerWorld serverWorld)) return 0.0D;
        MinecraftServer server = serverWorld.getServer();
        if (server == null) return 0.0D;
        ConcurrentHashMap<String, ConcurrentHashMap<Long, HazardEntry>> worldMap = CACHE.get(
                BotWorldStateService.currentWorldKey(server));
        if (worldMap == null) return 0.0D;
        ConcurrentHashMap<Long, HazardEntry> dimMap = worldMap.get(dimensionId(serverWorld));
        if (dimMap == null) return 0.0D;
        HazardEntry entry = dimMap.get(BlockPos.asLong(x, y, z));
        if (entry == null || entry.score <= 0.0F) return 0.0D;
        double penalty = Math.min(PENALTY_CAP, entry.score * PENALTY_SCALE);
        long now = System.currentTimeMillis();
        // Throttled INFO so the user can verify cache penalties are reaching
        // the pathfinder. Only logs cells with a meaningful penalty (>= 1.0)
        // to avoid drowning the log in low-score noise.
        if (penalty >= 1.0D && now - lastPenaltyLogMs > DIAGNOSTIC_PENALTY_LOG_THROTTLE_MS) {
            lastPenaltyLogMs = now;
            LOGGER.info("nav-hazard penalty cell=({},{},{}) score={} penalty={}",
                    x, y, z, entry.score, penalty);
        }
        return penalty;
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) return;
        long tick = overworld.getTime();

        recordSuccessSignals(server);

        if (tick - lastDecayTick >= DECAY_INTERVAL_TICKS) {
            applyDecay(server, tick);
            lastDecayTick = tick;
        }
        if (tick - lastFlushTick >= FLUSH_INTERVAL_TICKS) {
            pruneAndFlushAsync(server, tick);
            lastFlushTick = tick;
        }
        if (tick - lastSummaryTick >= SUMMARY_LOG_INTERVAL_TICKS) {
            logLearningSummary(server);
            lastSummaryTick = tick;
        }
    }

    /**
     * Periodic INFO-level summary so the user can see the cache is actually
     * learning without crawling debug logs. Reports total cell count, the top
     * 5 hottest cells, and how many cells crossed the penalty threshold.
     * Silent when the cache is empty (server just started or fresh world).
     */
    private static void logLearningSummary(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) return;
        List<DebugRow> top = debugTopCells(server, overworld, 5);
        int totalCells = 0;
        int penaltyCells = 0;
        String worldKey = BotWorldStateService.currentWorldKey(server);
        ConcurrentHashMap<String, ConcurrentHashMap<Long, HazardEntry>> worldMap = CACHE.get(worldKey);
        if (worldMap != null) {
            for (ConcurrentHashMap<Long, HazardEntry> dimMap : worldMap.values()) {
                totalCells += dimMap.size();
                for (HazardEntry e : dimMap.values()) {
                    if (e.score * PENALTY_SCALE >= 1.0D) penaltyCells++;
                }
            }
        }
        if (totalCells == 0) return;
        StringBuilder topStr = new StringBuilder();
        for (DebugRow row : top) {
            if (topStr.length() > 0) topStr.append(", ");
            topStr.append(row.cell().toShortString())
                    .append("(score=").append(String.format("%.1f", row.score()))
                    .append(",rejects=").append(row.rejects())
                    .append(",successes=").append(row.successes())
                    .append(")");
        }
        LOGGER.info("nav-hazard summary: cells={} active-penalty={} top=[{}]",
                totalCells, penaltyCells, topStr);
    }

    private static void recordSuccessSignals(MinecraftServer server) {
        String worldKey = BotWorldStateService.currentWorldKey(server);
        ConcurrentHashMap<String, ConcurrentHashMap<Long, HazardEntry>> worldMap = CACHE.get(worldKey);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!(player instanceof net.wcfcarolina13.Entity.createFakePlayer)) continue;
            UUID uuid = player.getUuid();
            ServerWorld currentWorld = (ServerWorld) player.getEntityWorld();
            if (currentWorld == null) continue;
            String dim = dimensionId(currentWorld);
            long currentPos = player.getBlockPos().asLong();
            FootCell prev = LAST_FOOT_CELL.get(uuid);
            FootCell now = new FootCell(worldKey, dim, currentPos);
            if (prev != null && prev.posLong != currentPos
                    && prev.worldKey.equals(worldKey)
                    && prev.dimensionId.equals(dim)
                    && worldMap != null) {
                ConcurrentHashMap<Long, HazardEntry> dimMap = worldMap.get(dim);
                if (dimMap != null) {
                    HazardEntry entry = dimMap.get(prev.posLong);
                    if (entry != null) {
                        synchronized (entry) {
                            entry.successes++;
                            entry.score = Math.max(0.0F, entry.score - SUCCESS_DECREMENT);
                        }
                        DIRTY.set(true);
                    }
                }
            }
            LAST_FOOT_CELL.put(uuid, now);
        }
    }

    private static void applyDecay(MinecraftServer server, long tick) {
        for (ConcurrentHashMap<String, ConcurrentHashMap<Long, HazardEntry>> worldMap : CACHE.values()) {
            for (ConcurrentHashMap<Long, HazardEntry> dimMap : worldMap.values()) {
                for (HazardEntry entry : dimMap.values()) {
                    if (entry.score > 0.0F) {
                        synchronized (entry) {
                            entry.score = Math.max(0.0F,
                                    entry.score - DECAY_PER_TICK * DECAY_INTERVAL_TICKS);
                        }
                        DIRTY.set(true);
                    }
                }
            }
        }
    }

    private static void pruneAndFlushAsync(MinecraftServer server, long tick) {
        int pruned = 0;
        for (ConcurrentHashMap<String, ConcurrentHashMap<Long, HazardEntry>> worldMap : CACHE.values()) {
            for (ConcurrentHashMap<Long, HazardEntry> dimMap : worldMap.values()) {
                for (Map.Entry<Long, HazardEntry> me : new ArrayList<>(dimMap.entrySet())) {
                    HazardEntry entry = me.getValue();
                    if (entry.score < EVICTION_SCORE_THRESHOLD
                            && tick - entry.lastTick > EVICTION_AGE_TICKS) {
                        dimMap.remove(me.getKey());
                        pruned++;
                    }
                }
            }
        }
        if (pruned > 0) {
            LOGGER.info("nav-hazard pruned {} stale entries", pruned);
            DIRTY.set(true);
        }
        if (DIRTY.compareAndSet(true, false)) {
            ScheduledExecutorService exec = FLUSH_EXEC;
            if (exec != null && !exec.isShutdown()) {
                Map<String, Map<String, Map<String, SerializedEntry>>> snapshot = snapshot();
                exec.execute(() -> writeFile(snapshot));
            }
        }
    }

    public static void load(MinecraftServer server) {
        if (LOADED.getAndSet(true)) return;
        Path file = stateFile();
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) return;
            JsonObject worldsObj = root.getAsJsonObject();
            for (Map.Entry<String, JsonElement> we : worldsObj.entrySet()) {
                if (!we.getValue().isJsonObject()) continue;
                ConcurrentHashMap<String, ConcurrentHashMap<Long, HazardEntry>> worldMap =
                        new ConcurrentHashMap<>();
                for (Map.Entry<String, JsonElement> de : we.getValue().getAsJsonObject().entrySet()) {
                    if (!de.getValue().isJsonObject()) continue;
                    ConcurrentHashMap<Long, HazardEntry> dimMap = new ConcurrentHashMap<>();
                    for (Map.Entry<String, JsonElement> ce : de.getValue().getAsJsonObject().entrySet()) {
                        long key = parseCellKey(ce.getKey());
                        if (key == Long.MIN_VALUE) continue;
                        SerializedEntry se = GSON.fromJson(ce.getValue(), SerializedEntry.class);
                        if (se == null) continue;
                        HazardEntry entry = new HazardEntry();
                        entry.score = se.score;
                        entry.rejects = se.rejects;
                        entry.successes = se.successes;
                        entry.lastTick = se.lastTick;
                        entry.firstSeenTick = se.firstSeenTick;
                        entry.streakRejects = 0; // streak resets on boot — only persistent score matters
                        dimMap.put(key, entry);
                    }
                    worldMap.put(de.getKey(), dimMap);
                }
                CACHE.put(we.getKey(), worldMap);
            }
            LOGGER.info("nav-hazard loaded from {}", file);
        } catch (Exception e) {
            LOGGER.warn("nav-hazard load failed: {}", e.getMessage());
        }
    }

    public static void flushSync(MinecraftServer server) {
        Map<String, Map<String, Map<String, SerializedEntry>>> snapshot = snapshot();
        writeFile(snapshot);
    }

    public static void shutdownExecutors() {
        ScheduledExecutorService exec = FLUSH_EXEC;
        if (exec != null) {
            exec.shutdown();
            try {
                exec.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            FLUSH_EXEC = null;
        }
    }

    public static void restartExecutors() {
        if (FLUSH_EXEC != null && !FLUSH_EXEC.isShutdown()) return;
        FLUSH_EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "frens-nav-hazard-flush");
            t.setDaemon(true);
            return t;
        });
    }

    public static List<DebugRow> debugTopCells(MinecraftServer server, ServerWorld world, int limit) {
        if (server == null || world == null) return List.of();
        ConcurrentHashMap<String, ConcurrentHashMap<Long, HazardEntry>> worldMap = CACHE.get(
                BotWorldStateService.currentWorldKey(server));
        if (worldMap == null) return List.of();
        ConcurrentHashMap<Long, HazardEntry> dimMap = worldMap.get(dimensionId(world));
        if (dimMap == null) return List.of();
        long now = world.getTime();
        List<DebugRow> rows = new ArrayList<>(dimMap.size());
        for (Map.Entry<Long, HazardEntry> me : dimMap.entrySet()) {
            BlockPos cell = BlockPos.fromLong(me.getKey());
            HazardEntry e = me.getValue();
            rows.add(new DebugRow(cell, e.score, e.rejects, e.successes, now - e.lastTick));
        }
        rows.sort(Comparator.comparingDouble((DebugRow r) -> r.score).reversed());
        return rows.subList(0, Math.min(limit, rows.size()));
    }

    public static String stateFilePath() {
        return stateFile().toAbsolutePath().toString();
    }

    private static Map<String, Map<String, Map<String, SerializedEntry>>> snapshot() {
        Map<String, Map<String, Map<String, SerializedEntry>>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ConcurrentHashMap<String, ConcurrentHashMap<Long, HazardEntry>>> we : CACHE.entrySet()) {
            Map<String, Map<String, SerializedEntry>> worldOut = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, ConcurrentHashMap<Long, HazardEntry>> de : we.getValue().entrySet()) {
                Map<String, SerializedEntry> dimOut = new java.util.LinkedHashMap<>();
                for (Map.Entry<Long, HazardEntry> ce : de.getValue().entrySet()) {
                    BlockPos cell = BlockPos.fromLong(ce.getKey());
                    HazardEntry e = ce.getValue();
                    SerializedEntry se = new SerializedEntry();
                    se.score = e.score;
                    se.rejects = e.rejects;
                    se.successes = e.successes;
                    se.lastTick = e.lastTick;
                    se.firstSeenTick = e.firstSeenTick;
                    dimOut.put(cell.getX() + "," + cell.getY() + "," + cell.getZ(), se);
                }
                if (!dimOut.isEmpty()) worldOut.put(de.getKey(), dimOut);
            }
            if (!worldOut.isEmpty()) out.put(we.getKey(), worldOut);
        }
        return out;
    }

    private static void writeFile(Map<String, Map<String, Map<String, SerializedEntry>>> snapshot) {
        try {
            Path file = stateFile();
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(snapshot, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("nav-hazard flush failed: {}", e.getMessage());
        }
    }

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
    }

    private static String dimensionId(World world) {
        return world.getRegistryKey().getValue().toString();
    }

    private static long parseCellKey(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) return Long.MIN_VALUE;
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            return BlockPos.asLong(x, y, z);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    static final class HazardEntry {
        volatile float score = 0.0F;
        volatile int rejects = 0;
        volatile int successes = 0;
        volatile long lastTick = 0L;
        volatile long firstSeenTick = 0L;
        volatile int streakRejects = 0;
    }

    private static final class SerializedEntry {
        float score;
        int rejects;
        int successes;
        long lastTick;
        long firstSeenTick;
    }

    private record FootCell(String worldKey, String dimensionId, long posLong) {}

    public record DebugRow(BlockPos cell, float score, int rejects, int successes, long ageTicks) {}
}
