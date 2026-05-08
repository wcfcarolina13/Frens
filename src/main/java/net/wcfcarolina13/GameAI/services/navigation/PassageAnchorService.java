package net.wcfcarolina13.GameAI.services.navigation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
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
 * Per-save × per-dimension cache of "passage anchors" — door / fence-gate / trapdoor cells
 * that the bot has been observed to traverse. Each successful traversal increments a score
 * which pathfinders consult as a small <em>negative</em> cost on the anchor cell and its
 * two cardinal entry/exit neighbors, biasing future routes through known-good doorways.
 *
 * <p>This is the inverse of {@link NavHazardCache}: NavHazard punishes cells where movement
 * was rejected, PassageAnchor rewards cells where movement succeeded through a
 * door-class block. Together they steer Baritone toward known-good corridors.
 *
 * <p>Recording site: {@link #onBotTick(ServerPlayerEntity, World)} — called from
 * {@code BotActions.applyMovementInput} on the server thread. Detects when the bot's foot
 * cell changes and the previous-or-current cell contains a door/gate/trapdoor.
 *
 * <p>Persistence: single JSON file at {@code <configDir>/frens/passage_anchors.json},
 * partitioned by {@code worldKey -> dimensionId -> "x,y,z"}. Mirrors {@link NavHazardCache}.
 */
public final class PassageAnchorService {
    private static final Logger LOGGER = LoggerFactory.getLogger("passage-anchor");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "passage_anchors.json";

    static final float TRAVERSE_INCREMENT = 1.0F;
    static final float MAX_SCORE = 30.0F;
    // Anchors decay slowly — a route used once a day still pays back. Tuned so an unused
    // anchor decays to the eviction threshold over ~7 in-game days (28h real-time).
    static final float DECAY_PER_TICK = 1.0F / 4000.0F;          // 0.005/sec @ 20 TPS
    static final double BONUS_SCALE = 0.6D;
    static final double BONUS_CAP = 6.0D;
    // Adjacent cells (entry + exit) get a fraction of the anchor's bonus so paths are
    // attracted to the corridor, not just the door itself.
    static final double NEIGHBOR_BONUS_FRACTION = 0.7D;
    static final long EVICTION_AGE_TICKS = 24_000L * 7L;          // 7 in-game days
    static final float EVICTION_SCORE_THRESHOLD = 0.05F;
    static final long DECAY_INTERVAL_TICKS = 200L;                // 10 s
    static final long FLUSH_INTERVAL_TICKS = 600L;                // 30 s
    static final long DIAGNOSTIC_BONUS_LOG_THROTTLE_MS = 1000L;

    private static final ConcurrentHashMap<String,
            ConcurrentHashMap<String,
                    ConcurrentHashMap<Long, AnchorEntry>>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, FootCell> LAST_FOOT_CELL = new ConcurrentHashMap<>();

    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);
    private static volatile ScheduledExecutorService FLUSH_EXEC = null;
    private static long lastDecayTick = 0L;
    private static long lastFlushTick = 0L;
    private static volatile long lastBonusLogMs = 0L;

    private PassageAnchorService() {}

    /**
     * Per-tick observation hook from {@code BotActions.applyMovementInput}. Cheap-path:
     * if the bot's foot cell is the same as last observed, returns immediately. When the
     * cell changes, scans the previous and current cells (and one block above each, since
     * doors are 2-tall) for a door / fence-gate / trapdoor block. If found, records
     * a traversal at that block.
     */
    public static void onBotTick(ServerPlayerEntity bot, World world) {
        if (bot == null || !(world instanceof ServerWorld serverWorld)) return;
        MinecraftServer server = serverWorld.getServer();
        if (server == null) return;
        UUID id = bot.getUuid();
        String worldKey = BotWorldStateService.currentWorldKey(server);
        String dim = dimensionId(serverWorld);
        BlockPos current = bot.getBlockPos();
        FootCell prev = LAST_FOOT_CELL.get(id);
        FootCell now = new FootCell(worldKey, dim, current.asLong());

        if (prev != null && prev.posLong == now.posLong
                && prev.worldKey.equals(worldKey)
                && prev.dimensionId.equals(dim)) {
            return; // unchanged cell, nothing to record
        }
        LAST_FOOT_CELL.put(id, now);

        if (prev == null
                || !prev.worldKey.equals(worldKey)
                || !prev.dimensionId.equals(dim)) {
            return; // first sight or cross-dimension move — skip recording
        }

        // Look for a door-class block at either endpoint of the transition. We check the
        // foot cell + the head cell (foot.up()) at both endpoints because doors occupy
        // two cells vertically. Trapdoors and fence gates only occupy one but the extra
        // probe is cheap.
        BlockPos prevPos = BlockPos.fromLong(prev.posLong);
        BlockPos[] probes = new BlockPos[] {
                prevPos, prevPos.up(), current, current.up()
        };
        for (BlockPos probe : probes) {
            BlockState state = serverWorld.getBlockState(probe);
            if (state == null || state.isAir()) continue;
            if (state.getBlock() instanceof DoorBlock
                    || state.getBlock() instanceof FenceGateBlock
                    || state.getBlock() instanceof TrapdoorBlock) {
                recordTraversal(serverWorld, probe);
                return; // one anchor per transition is plenty
            }
        }
    }

    static void recordTraversal(ServerWorld world, BlockPos doorCell) {
        if (world == null || doorCell == null) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;
        String worldKey = BotWorldStateService.currentWorldKey(server);
        String dim = dimensionId(world);
        long tick = world.getTime();
        long key = doorCell.asLong();

        ConcurrentHashMap<Long, AnchorEntry> dimMap = CACHE
                .computeIfAbsent(worldKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        AnchorEntry entry = dimMap.computeIfAbsent(key, k -> {
            AnchorEntry e = new AnchorEntry();
            e.firstSeenTick = tick;
            return e;
        });
        synchronized (entry) {
            entry.traversals++;
            entry.lastTick = tick;
            entry.score = Math.min(MAX_SCORE, entry.score + TRAVERSE_INCREMENT);
        }
        DIRTY.set(true);
    }

    /** Negative cost (attractor) for the cell at (x,y,z). Returns 0.0 if not an anchor. */
    public static double bonusFor(World world, BlockPos pos) {
        if (pos == null) return 0.0D;
        return bonusFor(world, pos.getX(), pos.getY(), pos.getZ());
    }

    public static double bonusFor(World world, int x, int y, int z) {
        if (!(world instanceof ServerWorld serverWorld)) return 0.0D;
        MinecraftServer server = serverWorld.getServer();
        if (server == null) return 0.0D;
        ConcurrentHashMap<String, ConcurrentHashMap<Long, AnchorEntry>> worldMap = CACHE.get(
                BotWorldStateService.currentWorldKey(server));
        if (worldMap == null) return 0.0D;
        ConcurrentHashMap<Long, AnchorEntry> dimMap = worldMap.get(dimensionId(serverWorld));
        if (dimMap == null) return 0.0D;

        // Primary anchor: validate the cell still contains a door-class block (or air, if the
        // door was removed but the opening preserved). Any other block means the world has
        // changed and the anchor is stale — zero the score for fast eviction at next flush.
        AnchorEntry entry = dimMap.get(BlockPos.asLong(x, y, z));
        double primary = 0.0D;
        if (entry != null && entry.score > 0.0F) {
            if (isAnchorCellValid(serverWorld, x, y, z)) {
                primary = Math.min(BONUS_CAP, entry.score * BONUS_SCALE);
            } else {
                synchronized (entry) {
                    entry.score = 0.0F;
                }
                DIRTY.set(true);
            }
        }
        // 6-neighbor probe: if any cardinal neighbor is an anchor, give this cell a
        // fraction of that anchor's bonus. This widens the attractor band so a path
        // that's one block off-center still feels the pull. Halo entries skip the
        // block validation — fractional contribution (0.7 × cap = 4.2) is bounded
        // and the staleness is corrected when the halo cell is queried directly.
        double neighbor = 0.0D;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) != 1) continue;
                    AnchorEntry n = dimMap.get(BlockPos.asLong(x + dx, y + dy, z + dz));
                    if (n != null && n.score > 0.0F) {
                        neighbor = Math.max(neighbor,
                                Math.min(BONUS_CAP, n.score * BONUS_SCALE) * NEIGHBOR_BONUS_FRACTION);
                    }
                }
            }
        }
        double total = primary + neighbor;
        if (total <= 0.0D) return 0.0D;
        long now = System.currentTimeMillis();
        if (LOGGER.isDebugEnabled() && now - lastBonusLogMs > DIAGNOSTIC_BONUS_LOG_THROTTLE_MS) {
            lastBonusLogMs = now;
            LOGGER.debug("passage-anchor bonus cell=({},{},{}) primary={} neighbor={} total={}",
                    x, y, z, primary, neighbor, total);
        }
        return total;
    }

    /**
     * Anchor cell is valid iff it still contains the kind of block we'd record an anchor for,
     * OR is air (door removed, opening preserved — bot can still walk through). Any other
     * block means the player walled it off / replaced the door, and the anchor should die.
     */
    private static boolean isAnchorCellValid(ServerWorld world, int x, int y, int z) {
        BlockState state = world.getBlockState(new BlockPos(x, y, z));
        if (state == null || state.isAir()) return true;
        var block = state.getBlock();
        return block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof TrapdoorBlock;
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) return;
        long tick = overworld.getTime();
        if (tick - lastDecayTick >= DECAY_INTERVAL_TICKS) {
            applyDecay(tick);
            lastDecayTick = tick;
        }
        if (tick - lastFlushTick >= FLUSH_INTERVAL_TICKS) {
            pruneAndFlushAsync(tick);
            lastFlushTick = tick;
        }
    }

    private static void applyDecay(long tick) {
        for (ConcurrentHashMap<String, ConcurrentHashMap<Long, AnchorEntry>> worldMap : CACHE.values()) {
            for (ConcurrentHashMap<Long, AnchorEntry> dimMap : worldMap.values()) {
                for (AnchorEntry entry : dimMap.values()) {
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

    private static void pruneAndFlushAsync(long tick) {
        int pruned = 0;
        for (ConcurrentHashMap<String, ConcurrentHashMap<Long, AnchorEntry>> worldMap : CACHE.values()) {
            for (ConcurrentHashMap<Long, AnchorEntry> dimMap : worldMap.values()) {
                for (Map.Entry<Long, AnchorEntry> me : new ArrayList<>(dimMap.entrySet())) {
                    AnchorEntry entry = me.getValue();
                    if (entry.score < EVICTION_SCORE_THRESHOLD
                            && tick - entry.lastTick > EVICTION_AGE_TICKS) {
                        dimMap.remove(me.getKey());
                        pruned++;
                    }
                }
            }
        }
        if (pruned > 0) {
            LOGGER.info("passage-anchor pruned {} stale entries", pruned);
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
                ConcurrentHashMap<String, ConcurrentHashMap<Long, AnchorEntry>> worldMap =
                        new ConcurrentHashMap<>();
                for (Map.Entry<String, JsonElement> de : we.getValue().getAsJsonObject().entrySet()) {
                    if (!de.getValue().isJsonObject()) continue;
                    ConcurrentHashMap<Long, AnchorEntry> dimMap = new ConcurrentHashMap<>();
                    for (Map.Entry<String, JsonElement> ce : de.getValue().getAsJsonObject().entrySet()) {
                        long key = parseCellKey(ce.getKey());
                        if (key == Long.MIN_VALUE) continue;
                        SerializedEntry se = GSON.fromJson(ce.getValue(), SerializedEntry.class);
                        if (se == null) continue;
                        AnchorEntry entry = new AnchorEntry();
                        entry.score = se.score;
                        entry.traversals = se.traversals;
                        entry.lastTick = se.lastTick;
                        entry.firstSeenTick = se.firstSeenTick;
                        dimMap.put(key, entry);
                    }
                    worldMap.put(de.getKey(), dimMap);
                }
                CACHE.put(we.getKey(), worldMap);
            }
            LOGGER.info("passage-anchor loaded from {}", file);
        } catch (Exception e) {
            LOGGER.warn("passage-anchor load failed: {}", e.getMessage());
        }
    }

    public static void flushSync() {
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
            Thread t = new Thread(r, "frens-passage-anchor-flush");
            t.setDaemon(true);
            return t;
        });
    }

    public static List<DebugRow> debugTopCells(MinecraftServer server, ServerWorld world, int limit) {
        if (server == null || world == null) return List.of();
        ConcurrentHashMap<String, ConcurrentHashMap<Long, AnchorEntry>> worldMap = CACHE.get(
                BotWorldStateService.currentWorldKey(server));
        if (worldMap == null) return List.of();
        ConcurrentHashMap<Long, AnchorEntry> dimMap = worldMap.get(dimensionId(world));
        if (dimMap == null) return List.of();
        long now = world.getTime();
        List<DebugRow> rows = new ArrayList<>(dimMap.size());
        for (Map.Entry<Long, AnchorEntry> me : dimMap.entrySet()) {
            BlockPos cell = BlockPos.fromLong(me.getKey());
            AnchorEntry e = me.getValue();
            rows.add(new DebugRow(cell, e.score, e.traversals, now - e.lastTick));
        }
        rows.sort(Comparator.comparingDouble((DebugRow r) -> r.score).reversed());
        return rows.subList(0, Math.min(limit, rows.size()));
    }

    public static String stateFilePath() {
        return stateFile().toAbsolutePath().toString();
    }

    private static Map<String, Map<String, Map<String, SerializedEntry>>> snapshot() {
        Map<String, Map<String, Map<String, SerializedEntry>>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ConcurrentHashMap<String, ConcurrentHashMap<Long, AnchorEntry>>> we : CACHE.entrySet()) {
            Map<String, Map<String, SerializedEntry>> worldOut = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, ConcurrentHashMap<Long, AnchorEntry>> de : we.getValue().entrySet()) {
                Map<String, SerializedEntry> dimOut = new java.util.LinkedHashMap<>();
                for (Map.Entry<Long, AnchorEntry> ce : de.getValue().entrySet()) {
                    BlockPos cell = BlockPos.fromLong(ce.getKey());
                    AnchorEntry e = ce.getValue();
                    SerializedEntry se = new SerializedEntry();
                    se.score = e.score;
                    se.traversals = e.traversals;
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
            LOGGER.warn("passage-anchor flush failed: {}", e.getMessage());
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

    static final class AnchorEntry {
        volatile float score = 0.0F;
        volatile int traversals = 0;
        volatile long lastTick = 0L;
        volatile long firstSeenTick = 0L;
    }

    private static final class SerializedEntry {
        float score;
        int traversals;
        long lastTick;
        long firstSeenTick;
    }

    private record FootCell(String worldKey, String dimensionId, long posLong) {}

    public record DebugRow(BlockPos cell, float score, int traversals, long ageTicks) {}
}
