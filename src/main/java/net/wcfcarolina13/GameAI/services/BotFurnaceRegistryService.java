package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight tactical-furnace memory shared by bots with the same owner.
 */
public final class BotFurnaceRegistryService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-furnace-registry");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "bot_furnace_registry.json";
    private static final Object LOCK = new Object();

    private static RootData DATA = new RootData();
    private static boolean loaded = false;
    private static boolean loadFailed = false;

    private BotFurnaceRegistryService() {
    }

    public static final class FurnaceRecord {
        public int x;
        public int y;
        public int z;
        public String blockId;
        public String context;
        public String builderAlias;
        public long placedAtMs;
        public boolean destroyed;

        public FurnaceRecord() {
        }

        public FurnaceRecord(BlockPos pos, String blockId, String context, String builderAlias) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.blockId = blockId != null ? blockId : "minecraft:furnace";
            this.context = context != null ? context : "tactical";
            this.builderAlias = builderAlias != null ? builderAlias : "";
            this.placedAtMs = System.currentTimeMillis();
            this.destroyed = false;
        }

        public BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    private static final class WorldData {
        Map<String, List<FurnaceRecord>> furnacesByShareKey = new HashMap<>();
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
                    LOGGER.warn("Failed to load furnace registry: {}", e.getMessage());
                    DATA = new RootData();
                    Path backup = UnreadableStoreFile.quarantine(file, java.time.LocalDateTime.now(), LOGGER, "furnace registry");
                    loadFailed = backup == null;
                }
            }
            loaded = true;
        }
    }

    private static boolean flush() {
        synchronized (LOCK) {
            if (loadFailed) {
                LOGGER.warn("Furnace registry was not saved because its original file could not be read.");
                return false;
            }
            Path temporary = null;
            try {
                Path file = stateFile();
                Files.createDirectories(file.getParent());
                temporary = Files.createTempFile(file.getParent(), "furnace-registry-", ".tmp");
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
                LOGGER.warn("Failed to save furnace registry: {}", e.getMessage());
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
                ? server.getSaveProperties().getLevelName()
                : "unknown";
        String dim = world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString()
                : "unknown";
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
            if (legacy != null && legacy.furnacesByShareKey != null) {
                for (var group : legacy.furnacesByShareKey.entrySet()) {
                    if (group.getValue() == null) continue;
                    for (FurnaceRecord record : group.getValue()) {
                        if (record == null) continue;
                        count++;
                        if (entries.size() < 20) entries.add(record.x + "," + record.y + "," + record.z
                                + " share=" + group.getKey() + " destroyed=" + record.destroyed);
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
                LOGGER.warn("Legacy furnace registry not adopted: its file could not be read.");
                return;
            }
            ensureRootMaps();
            try {
                var adoption = LegacyRegistryRecoveryPolicy.adoptLegacy(DATA.worlds, DATA.legacyWorldClaims,
                        DATA.legacyDeclined, rawLevelName(server), '/', fingerprint, gameTime,
                        BotFurnaceRegistryService::copyFor);
                LegacyRegistryRecoveryPolicy.log(LOGGER, "furnace registry", fingerprint, adoption);
                if (adoption.changed() && !flush()) {
                    LegacyRegistryRecoveryPolicy.undo(adoption, DATA.worlds, DATA.legacyWorldClaims, DATA.legacyDeclined);
                    LOGGER.warn("Legacy furnace registry adoption was not saved and was undone. It is tried again next start.");
                }
            } catch (Exception e) {
                LOGGER.warn("Legacy furnace registry adoption failed: {}", e.getMessage());
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
                LOGGER.warn("furnace registry import from '{}' failed: {}", sourceKey, e.getMessage());
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
        if (data == null || data.furnacesByShareKey == null) return 0;
        int count = 0;
        for (List<FurnaceRecord> group : data.furnacesByShareKey.values()) {
            if (group == null) continue;
            for (FurnaceRecord record : group) if (record != null) count++;
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

    private static String botAlias(ServerPlayerEntity bot) {
        return bot != null ? bot.getName().getString().toLowerCase(Locale.ROOT) : "";
    }

    private static String shareKey(ServerPlayerEntity bot) {
        if (bot == null) {
            return "";
        }
        try {
            if (Frens.CONFIG != null) {
                ManualConfig.BotOwnership ownership = Frens.CONFIG.getOwner(bot.getName().getString());
                if (ownership != null && ownership.ownerUuid() != null && !ownership.ownerUuid().isBlank()) {
                    UUID ownerUuid = UUID.fromString(ownership.ownerUuid());
                    return "owner:" + ownerUuid;
                }
            }
        } catch (Throwable ignored) {
        }
        return "bot:" + botAlias(bot);
    }

    public static void registerFurnace(ServerPlayerEntity bot, BlockPos pos, ServerWorld world, String context) {
        if (bot == null || pos == null || world == null) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }
        String shareKey = shareKey(bot);
        if (shareKey.isBlank()) {
            return;
        }

        BlockState state = world.getBlockState(pos);
        if (!isFurnaceLike(state)) {
            return;
        }
        String blockId = state.getBlock().asItem().toString();
        WorldData wd = worldData(server, world, true);
        synchronized (LOCK) {
            if (wd.furnacesByShareKey == null) {
                wd.furnacesByShareKey = new HashMap<>();
            }
            List<FurnaceRecord> records = wd.furnacesByShareKey.computeIfAbsent(shareKey, ignored -> new ArrayList<>());
            for (FurnaceRecord record : records) {
                if (record.x == pos.getX() && record.y == pos.getY() && record.z == pos.getZ()) {
                    record.destroyed = false;
                    record.context = context != null ? context : record.context;
                    record.blockId = blockId;
                    record.builderAlias = botAlias(bot);
                    record.placedAtMs = System.currentTimeMillis();
                    flush();
                    return;
                }
            }
            records.add(new FurnaceRecord(pos, blockId, context, botAlias(bot)));
        }
        flush();
    }

    public static List<FurnaceRecord> listSharedFurnaces(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return List.of();
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return List.of();
        }
        WorldData wd = worldData(server, world);
        String shareKey = shareKey(bot);
        synchronized (LOCK) {
            if (wd.furnacesByShareKey == null || wd.furnacesByShareKey.isEmpty()) {
                return List.of();
            }
            List<FurnaceRecord> records = wd.furnacesByShareKey.get(shareKey);
            if (records == null || records.isEmpty()) {
                return List.of();
            }
            List<FurnaceRecord> out = new ArrayList<>();
            for (FurnaceRecord record : records) {
                if (record != null && !record.destroyed) {
                    out.add(record);
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(out));
        }
    }

    public static List<BlockPos> listValidSharedFurnacePositions(ServerPlayerEntity bot, ServerWorld world, BlockPos origin, double maxDistanceSq) {
        if (bot == null || world == null) {
            return List.of();
        }
        verifySharedFurnaces(bot, world);
        List<BlockPos> out = new ArrayList<>();
        for (FurnaceRecord record : listSharedFurnaces(bot, world)) {
            BlockPos pos = record.toBlockPos();
            if (origin != null && maxDistanceSq > 0.0D && origin.getSquaredDistance(pos) > maxDistanceSq) {
                continue;
            }
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (!isFurnaceLike(world.getBlockState(pos))) {
                continue;
            }
            out.add(pos.toImmutable());
        }
        if (origin != null) {
            out.sort(Comparator.comparingDouble(origin::getSquaredDistance));
        }
        return Collections.unmodifiableList(out);
    }

    public static void verifySharedFurnaces(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }
        WorldData wd = worldData(server, world);
        String shareKey = shareKey(bot);
        boolean changed = false;
        synchronized (LOCK) {
            if (wd.furnacesByShareKey == null) {
                return;
            }
            List<FurnaceRecord> records = wd.furnacesByShareKey.get(shareKey);
            if (records == null) {
                return;
            }
            for (FurnaceRecord record : records) {
                if (record == null) {
                    continue;
                }
                BlockPos pos = record.toBlockPos();
                boolean exists = world.isChunkLoaded(pos) && isFurnaceLike(world.getBlockState(pos));
                if (record.destroyed == exists) {
                    record.destroyed = !exists;
                    changed = true;
                }
            }
        }
        if (changed) {
            flush();
        }
    }

    public static void removeSharedFurnace(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (bot == null || world == null || pos == null) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }
        WorldData wd = worldData(server, world);
        String shareKey = shareKey(bot);
        synchronized (LOCK) {
            if (wd.furnacesByShareKey == null) {
                return;
            }
            List<FurnaceRecord> records = wd.furnacesByShareKey.get(shareKey);
            if (records == null) {
                return;
            }
            records.removeIf(record -> record != null
                    && record.x == pos.getX()
                    && record.y == pos.getY()
                    && record.z == pos.getZ());
        }
        flush();
    }

    private static boolean isFurnaceLike(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.isOf(Blocks.FURNACE)
                || state.isOf(Blocks.BLAST_FURNACE)
                || state.isOf(Blocks.SMOKER);
    }
}
