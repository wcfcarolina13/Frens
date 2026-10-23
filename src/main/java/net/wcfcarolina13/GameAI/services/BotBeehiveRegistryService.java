package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent registry of discovered beehives/bee nests per world.
 *
 * <p>These are treated as durable protected anchors for future hive-related skills.
 * If a hive is destroyed, the record is kept only until the next verification pass.
 */
public final class BotBeehiveRegistryService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-beehive-registry");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "bot_beehive_registry.json";
    private static final Object LOCK = new Object();

    public static final int DEFAULT_PROTECTION_RADIUS = 4;
    private static final int AUTO_DISCOVERY_HORIZONTAL_RADIUS = DEFAULT_PROTECTION_RADIUS + 2;
    private static final int AUTO_DISCOVERY_VERTICAL_RADIUS = DEFAULT_PROTECTION_RADIUS + 2;

    private static RootData DATA = new RootData();
    private static boolean loaded = false;
    private static boolean loadFailed = false;

    private BotBeehiveRegistryService() {}

    public static final class BeehiveRecord {
        public int x;
        public int y;
        public int z;
        public int radius;
        public String blockId;
        public long discoveredAtMs;
        public boolean destroyed;
        private transient long verificationRevision;

        public BeehiveRecord() {}

        public BeehiveRecord(BlockPos pos, int radius, String blockId) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.radius = Math.max(0, radius);
            this.blockId = blockId != null ? blockId : "minecraft:beehive";
            this.discoveredAtMs = System.currentTimeMillis();
            this.destroyed = false;
        }

        public BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }

        public boolean contains(BlockPos pos) {
            if (pos == null) {
                return false;
            }
            int dx = Math.abs(pos.getX() - x);
            int dy = Math.abs(pos.getY() - y);
            int dz = Math.abs(pos.getZ() - z);
            return dx <= radius && dy <= radius && dz <= radius;
        }
    }

    private static final class WorldData {
        List<BeehiveRecord> hives = new ArrayList<>();
    }

    private static final class RootData {
        Map<String, WorldData> worlds = new HashMap<>();
        /** Legacy (level-name) key -> fingerprint of the save that adopted it at load. */
        Map<String, String> legacyWorldClaims = new HashMap<>();
        /** Fingerprinted keys of brand-new saves that declined the legacy bucket, so the decline sticks. */
        java.util.Set<String> legacyDeclined = new java.util.HashSet<>();
    }

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
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
                    LOGGER.warn("Failed to load beehive registry: {}", e.getMessage());
                    DATA = new RootData();
                    Path backup = UnreadableStoreFile.quarantine(file, java.time.LocalDateTime.now(), LOGGER, "beehive registry");
                    loadFailed = backup == null;
                }
            }
            loaded = true;
        }
    }

    private static boolean flush() {
        synchronized (LOCK) {
            if (loadFailed) {
                LOGGER.warn("Beehive registry was not saved because its original file could not be read.");
                return false;
            }
            Path temporary = null;
            try {
                Path file = stateFile();
                Files.createDirectories(file.getParent());
                temporary = Files.createTempFile(file.getParent(), "beehive-registry-", ".tmp");
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
                LOGGER.warn("Failed to save beehive registry: {}", e.getMessage());
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

    private static String serverWorldKey(MinecraftServer server, ServerWorld world) {
        String dimension = world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString() : "unknown";
        return WorldStoreKeyPolicy.fingerprintKey(BotWorldStateService.currentWorldKey(server), dimension);
    }

    private static String legacyServerWorldKey(MinecraftServer server, ServerWorld world) {
        String level = server != null && server.getSaveProperties() != null
                ? server.getSaveProperties().getLevelName() : "unknown";
        String dim = world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString() : "unknown";
        return level + "/" + dim;
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
            if (legacy != null && legacy.hives != null) {
                for (BeehiveRecord record : legacy.hives) {
                    if (record == null) continue;
                    count++;
                    if (entries.size() < 20) entries.add(record.x + "," + record.y + "," + record.z
                            + " radius=" + record.radius + " destroyed=" + record.destroyed);
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
                LOGGER.warn("Legacy registry recovery failed: {}", e.getMessage());
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
                LOGGER.warn("Legacy beehive registry not adopted: its file could not be read.");
                return;
            }
            ensureRootMaps();
            try {
                var adoption = LegacyRegistryRecoveryPolicy.adoptLegacy(DATA.worlds, DATA.legacyWorldClaims,
                        DATA.legacyDeclined, rawLevelName(server), '/', fingerprint, gameTime,
                        BotBeehiveRegistryService::copyFor);
                LegacyRegistryRecoveryPolicy.log(LOGGER, "beehive registry", fingerprint, adoption);
                if (adoption.changed() && !flush()) {
                    LegacyRegistryRecoveryPolicy.undo(adoption, DATA.worlds, DATA.legacyWorldClaims, DATA.legacyDeclined);
                    LOGGER.warn("Legacy beehive registry adoption was not saved and was undone. It is tried again next start.");
                }
            } catch (Exception e) {
                LOGGER.warn("Legacy beehive registry adoption failed: {}", e.getMessage());
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
                LOGGER.warn("beehive registry import from '{}' failed: {}", sourceKey, e.getMessage());
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
        if (data == null || data.hives == null) return 0;
        int count = 0;
        for (BeehiveRecord record : data.hives) if (record != null) count++;
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

    public static boolean isBeehiveBlock(BlockState state) {
        return state != null && (state.isOf(Blocks.BEEHIVE) || state.isOf(Blocks.BEE_NEST));
    }

    public static void registerBeehive(ServerWorld world, BlockPos pos) {
        registerBeehive(world, pos, null);
    }

    private static void registerBeehive(ServerWorld world, BlockPos pos, ChunkBlockReader reader) {
        if (world == null || pos == null) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }
        BlockState state = reader != null ? reader.state(pos) : world.getBlockState(pos);
        if (!isBeehiveBlock(state)) {
            return;
        }

        WorldData wd = worldData(server, world, true);
        synchronized (LOCK) {
            if (wd.hives == null) {
                wd.hives = new ArrayList<>();
            }
            for (BeehiveRecord record : wd.hives) {
                if (record != null
                        && record.x == pos.getX()
                        && record.y == pos.getY()
                        && record.z == pos.getZ()) {
                    record.destroyed = false;
                    record.radius = DEFAULT_PROTECTION_RADIUS;
                    record.blockId = state.getBlock().getTranslationKey();
                    record.discoveredAtMs = System.currentTimeMillis();
                    record.verificationRevision++;
                    flush();
                    return;
                }
            }
            wd.hives.add(new BeehiveRecord(pos, DEFAULT_PROTECTION_RADIUS, state.getBlock().getTranslationKey()));
        }
        flush();
        LOGGER.info("Registered beehive protection at {} in {}", pos.toShortString(), serverWorldKey(server, world));
    }

    public static void discoverBeehivesNear(ServerWorld world, BlockPos origin, int horizontalRadius, int verticalRadius) {
        if (world == null || origin == null) {
            return;
        }
        discoverBeehivesNear(world, new ChunkBlockReader(world), origin, horizontalRadius, verticalRadius);
    }

    /**
     * Registers every beehive / bee nest in the box around {@code origin}, reading through
     * {@code reader} (one chunk fetch per chunk, not a server round trip per cell from a worker).
     * Chunk sections whose palette holds no hive are skipped whole; unloaded chunks are skipped
     * (never loaded), as before. Hives are registered in the old {@code BlockPos.iterate} order
     * (x fastest, then y, then z) so the registry list order is unchanged.
     */
    public static void discoverBeehivesNear(ServerWorld world, ChunkBlockReader reader, BlockPos origin,
                                            int horizontalRadius, int verticalRadius) {
        if (world == null || reader == null || origin == null) {
            return;
        }
        int h = Math.max(AUTO_DISCOVERY_HORIZONTAL_RADIUS, horizontalRadius);
        int v = Math.max(AUTO_DISCOVERY_VERTICAL_RADIUS, verticalRadius);
        verifyBeehives(world, reader, origin);
        int minX = origin.getX() - h, maxX = origin.getX() + h;
        int minY = origin.getY() - v, maxY = origin.getY() + v;
        int minZ = origin.getZ() - h, maxZ = origin.getZ() + h;
        List<BlockPos> found = null;
        for (int sz = minZ >> 4; sz <= maxZ >> 4; sz++) {
            for (int sx = minX >> 4; sx <= maxX >> 4; sx++) {
                WorldChunk chunk = reader.chunk(sx, sz);
                if (chunk == null) {
                    continue;
                }
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    int index = chunk.getSectionIndex(sy << 4);
                    if (index < 0 || index >= chunk.countVerticalSections()) {
                        continue;
                    }
                    ChunkSection section = chunk.getSection(index);
                    if (section == null || section.isEmpty() || !section.hasAny(BotBeehiveRegistryService::isBeehiveBlock)) {
                        continue;
                    }
                    int x0 = Math.max(minX, sx << 4), x1 = Math.min(maxX, (sx << 4) + 15);
                    int y0 = Math.max(minY, sy << 4), y1 = Math.min(maxY, (sy << 4) + 15);
                    int z0 = Math.max(minZ, sz << 4), z1 = Math.min(maxZ, (sz << 4) + 15);
                    for (int z = z0; z <= z1; z++) {
                        for (int y = y0; y <= y1; y++) {
                            for (int x = x0; x <= x1; x++) {
                                if (isBeehiveBlock(section.getBlockState(x & 15, y & 15, z & 15))) {
                                    if (found == null) {
                                        found = new ArrayList<>();
                                    }
                                    found.add(new BlockPos(x, y, z));
                                }
                            }
                        }
                    }
                }
            }
        }
        if (found == null) {
            return;
        }
        found.sort(Comparator.comparingInt(BlockPos::getZ)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX));
        for (BlockPos pos : found) {
            registerBeehive(world, pos, reader);
        }
    }

    public static void verifyBeehives(ServerWorld world) {
        verifyBeehives(world, null, null);
    }

    private static void verifyBeehives(ServerWorld world, ChunkBlockReader reader, BlockPos protectedPos) {
        if (world == null) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }
        WorldData wd = worldData(server, world);
        List<BeehiveRecord> hives;
        synchronized (LOCK) {
            if (wd.hives == null || wd.hives.isEmpty()) {
                return;
            }
            hives = wd.hives;
        }
        ChunkBlockReader verificationReader = reader != null ? reader : new ChunkBlockReader(world);
        boolean changed = BeehiveVerificationPolicy.removeMissing(LOCK, hives,
                record -> record.verificationRevision,
                record -> !record.destroyed && (protectedPos == null || record.contains(protectedPos)),
                record -> {
                    // Unknown chunks retain protection, including when called without a scan reader.
                    BlockState state = verificationReader.state(record.toBlockPos());
                    return state != null && !isBeehiveBlock(state);
                });
        synchronized (LOCK) {
            if (hives.removeIf(record -> record == null || record.destroyed)) {
                changed = true;
            }
        }
        if (changed) {
            flush();
        }
    }

    @Nullable
    public static BeehiveRecord findProtectingBeehive(ServerWorld world, BlockPos pos) {
        return findProtectingBeehive(world, null, pos);
    }

    @Nullable
    public static BeehiveRecord findProtectingBeehive(ServerWorld world, ChunkBlockReader reader, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return null;
        }
        reader = reader != null ? reader : new ChunkBlockReader(world);
        // Verify matching records before the cached hit can bypass discovery and its verification.
        verifyBeehives(world, reader, pos);
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.hives != null) {
                for (BeehiveRecord record : wd.hives) {
                    if (record != null && !record.destroyed && record.contains(pos)) {
                        return record;
                    }
                }
            }
        }
        discoverBeehivesNear(world, reader, pos,
                AUTO_DISCOVERY_HORIZONTAL_RADIUS, AUTO_DISCOVERY_VERTICAL_RADIUS);
        wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.hives != null) {
                for (BeehiveRecord record : wd.hives) {
                    if (record != null && !record.destroyed && record.contains(pos)) {
                        return record;
                    }
                }
            }
        }
        return null;
    }

    public static boolean isProtected(ServerWorld world, BlockPos pos) {
        return findProtectingBeehive(world, pos) != null;
    }
}
