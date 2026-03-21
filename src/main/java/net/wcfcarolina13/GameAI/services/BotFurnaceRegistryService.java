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
                LOGGER.warn("Failed to save furnace registry: {}", e.getMessage());
            }
        }
    }

    private static String serverWorldKey(MinecraftServer server, ServerWorld world) {
        String level = server != null && server.getSaveProperties() != null
                ? server.getSaveProperties().getLevelName()
                : "unknown";
        String dim = world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString()
                : "unknown";
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
        WorldData wd = worldData(server, world);
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
