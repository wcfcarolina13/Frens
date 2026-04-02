package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
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

    private BotBeehiveRegistryService() {}

    public static final class BeehiveRecord {
        public int x;
        public int y;
        public int z;
        public int radius;
        public String blockId;
        public long discoveredAtMs;
        public boolean destroyed;

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
                LOGGER.warn("Failed to save beehive registry: {}", e.getMessage());
            }
        }
    }

    private static String serverWorldKey(MinecraftServer server, ServerWorld world) {
        String level = server != null && server.getSaveProperties() != null
                ? server.getSaveProperties().getLevelName() : "unknown";
        String dim = world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString() : "unknown";
        return level + "/" + dim;
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

    public static boolean isBeehiveBlock(BlockState state) {
        return state != null && (state.isOf(Blocks.BEEHIVE) || state.isOf(Blocks.BEE_NEST));
    }

    public static void registerBeehive(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (!isBeehiveBlock(state)) {
            return;
        }

        WorldData wd = worldData(server, world);
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
        int h = Math.max(AUTO_DISCOVERY_HORIZONTAL_RADIUS, horizontalRadius);
        int v = Math.max(AUTO_DISCOVERY_VERTICAL_RADIUS, verticalRadius);
        verifyBeehives(world);
        for (BlockPos pos : BlockPos.iterate(origin.add(-h, -v, -h), origin.add(h, v, h))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (isBeehiveBlock(world.getBlockState(pos))) {
                registerBeehive(world, pos.toImmutable());
            }
        }
    }

    public static void verifyBeehives(ServerWorld world) {
        if (world == null) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }
        WorldData wd = worldData(server, world);
        boolean changed = false;
        synchronized (LOCK) {
            if (wd.hives == null || wd.hives.isEmpty()) {
                return;
            }
            for (BeehiveRecord record : wd.hives) {
                if (record == null || record.destroyed) {
                    continue;
                }
                BlockPos pos = record.toBlockPos();
                if (!world.isChunkLoaded(pos) || !isBeehiveBlock(world.getBlockState(pos))) {
                    record.destroyed = true;
                    changed = true;
                }
            }
            if (wd.hives.removeIf(record -> record == null || record.destroyed)) {
                changed = true;
            }
        }
        if (changed) {
            flush();
        }
    }

    @Nullable
    public static BeehiveRecord findProtectingBeehive(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return null;
        }
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
        discoverBeehivesNear(world, pos, AUTO_DISCOVERY_HORIZONTAL_RADIUS, AUTO_DISCOVERY_VERTICAL_RADIUS);
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

    public static List<BeehiveRecord> listBeehives(ServerWorld world) {
        if (world == null) {
            return List.of();
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return List.of();
        }
        verifyBeehives(world);
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.hives == null || wd.hives.isEmpty()) {
                return List.of();
            }
            return Collections.unmodifiableList(new ArrayList<>(wd.hives));
        }
    }
}
