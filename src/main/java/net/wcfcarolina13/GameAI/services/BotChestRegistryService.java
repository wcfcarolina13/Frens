package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
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
import java.util.Locale;
import java.util.Map;

/**
 * Persistent registry of bot-placed chests per bot per world.
 * Follows the BotHomeService persistence pattern (JSON at config/frens/).
 */
public final class BotChestRegistryService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-chest-registry");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "bot_chest_registry.json";
    private static final Object LOCK = new Object();

    private static RootData DATA = new RootData();
    private static boolean loaded = false;

    private BotChestRegistryService() {}

    // ── Data model ──────────────────────────────────────────────────────

    public static final class ItemSnapshot {
        public String itemId; // e.g. "minecraft:diamond"
        public int count;
        public ItemSnapshot() {}
        public ItemSnapshot(String itemId, int count) {
            this.itemId = itemId;
            this.count = count;
        }
    }

    public static final class ChestRecord {
        public int x;
        public int y;
        public int z;
        public String context;     // "hunt", "woodcut", "manual", etc.
        public long placedAtMs;
        public boolean destroyed;
        public List<ItemSnapshot> contentsSnapshot; // null if never captured

        public ChestRecord() {}

        public ChestRecord(BlockPos pos, String context) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.context = context != null ? context : "unknown";
            this.placedAtMs = System.currentTimeMillis();
            this.destroyed = false;
        }

        public BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    private static final class WorldData {
        Map<String, List<ChestRecord>> chestsByBot = new HashMap<>();
    }

    private static final class RootData {
        Map<String, WorldData> worlds = new HashMap<>();
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) return;
            Path file = stateFile();
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    RootData parsed = GSON.fromJson(reader, RootData.class);
                    if (parsed != null) DATA = parsed;
                } catch (Exception e) {
                    LOGGER.warn("Failed to load chest registry: {}", e.getMessage());
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
                LOGGER.warn("Failed to save chest registry: {}", e.getMessage());
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

    private static String botKey(ServerPlayerEntity bot) {
        return bot.getName().getString().toLowerCase(Locale.ROOT);
    }

    private static WorldData worldData(MinecraftServer server, ServerWorld world) {
        ensureLoaded();
        String key = serverWorldKey(server, world);
        synchronized (LOCK) {
            if (DATA.worlds == null) DATA.worlds = new HashMap<>();
            return DATA.worlds.computeIfAbsent(key, ignored -> new WorldData());
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Register a chest placed by a bot. */
    public static void registerChest(ServerPlayerEntity bot, BlockPos pos, ServerWorld world, String context) {
        if (bot == null || pos == null || world == null) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;

        WorldData wd = worldData(server, world);
        String key = botKey(bot);
        synchronized (LOCK) {
            if (wd.chestsByBot == null) wd.chestsByBot = new HashMap<>();
            List<ChestRecord> records = wd.chestsByBot.computeIfAbsent(key, ignored -> new ArrayList<>());

            // Avoid duplicates at the same position
            for (ChestRecord r : records) {
                if (r.x == pos.getX() && r.y == pos.getY() && r.z == pos.getZ()) {
                    r.destroyed = false;
                    r.context = context != null ? context : r.context;
                    r.placedAtMs = System.currentTimeMillis();
                    flush();
                    return;
                }
            }

            records.add(new ChestRecord(pos, context));
        }
        flush();
        LOGGER.info("Registered chest for {} at {},{},{} ({})",
                bot.getName().getString(), pos.getX(), pos.getY(), pos.getZ(), context);
    }

    /** Get all chest records for a bot in the given world. */
    public static List<ChestRecord> listChests(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) return List.of();
        MinecraftServer server = world.getServer();
        if (server == null) return List.of();

        WorldData wd = worldData(server, world);
        String key = botKey(bot);
        synchronized (LOCK) {
            if (wd.chestsByBot == null) return List.of();
            List<ChestRecord> records = wd.chestsByBot.get(key);
            return records != null ? Collections.unmodifiableList(new ArrayList<>(records)) : List.of();
        }
    }

    /** Verify chest records against the actual world, marking destroyed ones. */
    public static void verifyChests(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;

        WorldData wd = worldData(server, world);
        String key = botKey(bot);
        boolean changed = false;
        synchronized (LOCK) {
            if (wd.chestsByBot == null) return;
            List<ChestRecord> records = wd.chestsByBot.get(key);
            if (records == null) return;
            for (ChestRecord r : records) {
                BlockPos pos = r.toBlockPos();
                boolean isChest = world.getBlockState(pos).isOf(Blocks.CHEST)
                        || world.getBlockState(pos).isOf(Blocks.TRAPPED_CHEST);
                if (!isChest && !r.destroyed) {
                    r.destroyed = true;
                    changed = true;
                } else if (isChest && r.destroyed) {
                    r.destroyed = false;
                    changed = true;
                }
            }
        }
        if (changed) flush();
    }

    /** Refresh contents snapshots for all non-destroyed chests by reading the world's block entities. */
    public static void refreshAllSnapshots(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;

        WorldData wd = worldData(server, world);
        String key = botKey(bot);
        boolean changed = false;
        synchronized (LOCK) {
            if (wd.chestsByBot == null) return;
            List<ChestRecord> records = wd.chestsByBot.get(key);
            if (records == null) return;
            for (ChestRecord r : records) {
                if (r.destroyed) continue;
                BlockPos pos = r.toBlockPos();
                if (!world.isChunkLoaded(pos)) continue;
                var be = world.getBlockEntity(pos);
                if (be instanceof net.minecraft.inventory.Inventory inv) {
                    r.contentsSnapshot = captureContents(inv);
                    changed = true;
                }
            }
        }
        if (changed) flush();
    }

    /** Capture a snapshot of a chest's contents (merges duplicate items across slots). */
    public static List<ItemSnapshot> captureContents(net.minecraft.inventory.Inventory inv) {
        if (inv == null) return List.of();
        List<ItemSnapshot> items = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
            boolean merged = false;
            for (ItemSnapshot existing : items) {
                if (id.equals(existing.itemId)) {
                    existing.count += stack.getCount();
                    merged = true;
                    break;
                }
            }
            if (!merged) items.add(new ItemSnapshot(id, stack.getCount()));
        }
        return items;
    }

    /** Update the contents snapshot for a chest at the given position. */
    public static void updateContentsSnapshot(ServerPlayerEntity bot, BlockPos pos, ServerWorld world,
                                               net.minecraft.inventory.Inventory inv) {
        if (bot == null || pos == null || world == null) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;

        List<ItemSnapshot> snapshot = captureContents(inv);
        WorldData wd = worldData(server, world);
        String key = botKey(bot);
        synchronized (LOCK) {
            if (wd.chestsByBot == null) return;
            List<ChestRecord> records = wd.chestsByBot.get(key);
            if (records == null) return;
            for (ChestRecord r : records) {
                if (r.x == pos.getX() && r.y == pos.getY() && r.z == pos.getZ()) {
                    r.contentsSnapshot = snapshot;
                    break;
                }
            }
        }
        flush();
    }

    /** Remove a specific chest record by position. */
    public static void removeRecord(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (bot == null || world == null || pos == null) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;

        WorldData wd = worldData(server, world);
        String key = botKey(bot);
        synchronized (LOCK) {
            if (wd.chestsByBot == null) return;
            List<ChestRecord> records = wd.chestsByBot.get(key);
            if (records == null) return;
            records.removeIf(r -> r.x == pos.getX() && r.y == pos.getY() && r.z == pos.getZ());
        }
        flush();
    }
}
