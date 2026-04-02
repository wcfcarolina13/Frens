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
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;

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
import java.util.function.Predicate;

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
        public int occupiedSlots = -1;
        public int totalSlots = -1;
        public int emptySlots = -1;
        public long lastVerifiedAtMs;

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

    public record DepositCandidate(BlockPos pos,
                                   String context,
                                   boolean knownCapacity,
                                   int emptySlots,
                                   int totalSlots,
                                   boolean containsPreferredItems,
                                   long lastVerifiedAtMs,
                                   double distSq) {
    }

    private record SnapshotData(List<ItemSnapshot> contents,
                                int occupiedSlots,
                                int totalSlots,
                                int emptySlots,
                                long verifiedAtMs) {
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
        return "bot:" + botKey(bot);
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

    /**
     * Returns all non-destroyed chest records placed by bots that share the same owner as {@code bot}.
     *
     * <p>Unowned bots only see their own records.
     */
    public static List<ChestRecord> listChestsForOwner(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return List.of();
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return List.of();
        }

        String targetShareKey = shareKey(bot);
        if (targetShareKey.isBlank()) {
            return List.of();
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.chestsByBot == null || wd.chestsByBot.isEmpty()) {
                return List.of();
            }
            List<ChestRecord> out = new ArrayList<>();
            for (Map.Entry<String, List<ChestRecord>> entry : wd.chestsByBot.entrySet()) {
                String alias = entry.getKey();
                if (alias == null || entry.getValue() == null) {
                    continue;
                }
                if (!Objects.equals(targetShareKey, shareKeyForAlias(alias))) {
                    continue;
                }
                for (ChestRecord record : entry.getValue()) {
                    if (record != null && !record.destroyed) {
                        out.add(record);
                    }
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(out));
        }
    }

    public static List<DepositCandidate> listDepositCandidatesForOwner(ServerPlayerEntity bot,
                                                                       ServerWorld world,
                                                                       BlockPos origin,
                                                                       Predicate<ItemSnapshot> preferredMatcher,
                                                                       double maxDistSq) {
        if (bot == null || world == null || origin == null) {
            return List.of();
        }
        List<ChestRecord> records = listChestsForOwner(bot, world);
        if (records.isEmpty()) {
            return List.of();
        }
        List<DepositCandidate> candidates = new ArrayList<>();
        for (ChestRecord record : records) {
            if (record == null || record.destroyed) {
                continue;
            }
            BlockPos pos = record.toBlockPos();
            if (pos == null) {
                continue;
            }
            double distSq = origin.getSquaredDistance(pos);
            if (distSq > maxDistSq) {
                continue;
            }
            if (world.isChunkLoaded(pos)) {
                boolean isChest = world.getBlockState(pos).isOf(Blocks.CHEST)
                        || world.getBlockState(pos).isOf(Blocks.TRAPPED_CHEST)
                        || world.getBlockState(pos).isOf(Blocks.BARREL);
                if (!isChest) {
                    continue;
                }
            }
            boolean containsPreferred = false;
            if (preferredMatcher != null && record.contentsSnapshot != null) {
                for (ItemSnapshot snapshot : record.contentsSnapshot) {
                    if (snapshot != null && preferredMatcher.test(snapshot)) {
                        containsPreferred = true;
                        break;
                    }
                }
            }
            boolean knownCapacity = record.totalSlots > 0 && record.emptySlots >= 0;
            candidates.add(new DepositCandidate(
                    pos.toImmutable(),
                    record.context == null ? "unknown" : record.context,
                    knownCapacity,
                    record.emptySlots,
                    record.totalSlots,
                    containsPreferred,
                    record.lastVerifiedAtMs,
                    distSq));
        }
        candidates.sort(Comparator
                .comparing((DepositCandidate candidate) -> !candidate.containsPreferredItems())
                .thenComparing(candidate -> !candidate.knownCapacity())
                .thenComparing(candidate -> candidate.knownCapacity() && candidate.emptySlots() <= 0)
                .thenComparing(Comparator.comparingInt(DepositCandidate::emptySlots).reversed())
                .thenComparingDouble(DepositCandidate::distSq));
        return List.copyOf(candidates);
    }

    private static String shareKeyForAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return "";
        }
        try {
            if (Frens.CONFIG != null) {
                ManualConfig.BotOwnership ownership = Frens.CONFIG.getOwner(alias);
                if (ownership != null && ownership.ownerUuid() != null && !ownership.ownerUuid().isBlank()) {
                    UUID ownerUuid = UUID.fromString(ownership.ownerUuid());
                    return "owner:" + ownerUuid;
                }
            }
        } catch (Throwable ignored) {
        }
        return "bot:" + alias.trim().toLowerCase(Locale.ROOT);
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
                    SnapshotData snapshot = captureSnapshotData(inv);
                    r.contentsSnapshot = snapshot.contents();
                    r.occupiedSlots = snapshot.occupiedSlots();
                    r.totalSlots = snapshot.totalSlots();
                    r.emptySlots = snapshot.emptySlots();
                    r.lastVerifiedAtMs = snapshot.verifiedAtMs();
                    changed = true;
                }
            }
        }
        if (changed) flush();
    }

    /** Capture a snapshot of a chest's contents (merges duplicate items across slots). */
    public static List<ItemSnapshot> captureContents(net.minecraft.inventory.Inventory inv) {
        return captureSnapshotData(inv).contents();
    }

    private static SnapshotData captureSnapshotData(net.minecraft.inventory.Inventory inv) {
        if (inv == null) {
            return new SnapshotData(List.of(), 0, 0, 0, System.currentTimeMillis());
        }
        List<ItemSnapshot> items = new ArrayList<>();
        int occupiedSlots = 0;
        int totalSlots = inv.size();
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) continue;
            occupiedSlots++;
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
        return new SnapshotData(items, occupiedSlots, totalSlots, Math.max(0, totalSlots - occupiedSlots), System.currentTimeMillis());
    }

    /** Update the contents snapshot for a chest at the given position. */
    public static void updateContentsSnapshot(ServerPlayerEntity bot, BlockPos pos, ServerWorld world,
                                               net.minecraft.inventory.Inventory inv) {
        if (bot == null || pos == null || world == null) return;
        MinecraftServer server = world.getServer();
        if (server == null) return;

        SnapshotData snapshot = captureSnapshotData(inv);
        WorldData wd = worldData(server, world);
        String targetShareKey = shareKey(bot);
        synchronized (LOCK) {
            if (wd.chestsByBot == null) return;
            for (Map.Entry<String, List<ChestRecord>> entry : wd.chestsByBot.entrySet()) {
                if (!Objects.equals(targetShareKey, shareKeyForAlias(entry.getKey()))) {
                    continue;
                }
                List<ChestRecord> records = entry.getValue();
                if (records == null) {
                    continue;
                }
                for (ChestRecord r : records) {
                    if (r.x == pos.getX() && r.y == pos.getY() && r.z == pos.getZ()) {
                        r.contentsSnapshot = snapshot.contents();
                        r.occupiedSlots = snapshot.occupiedSlots();
                        r.totalSlots = snapshot.totalSlots();
                        r.emptySlots = snapshot.emptySlots();
                        r.lastVerifiedAtMs = snapshot.verifiedAtMs();
                    }
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
