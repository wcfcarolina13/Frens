package net.wcfcarolina13.GameAI.services;

import com.mojang.serialization.DataResult;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class BotInventoryStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-inventory-storage");
    /** Old mod-ID subdirectory used before the ai-player → frens rename. */
    private static final String LEGACY_MOD_DIR = "ai-player";
    private static final AtomicBoolean MIGRATION_DONE = new AtomicBoolean(false);
    private static final long SAVE_LOG_COOLDOWN_MS = 60_000L;
    /**
     * Bots whose join-restore has been scheduled but whose snapshot has not yet been applied.
     * While pending, an EMPTY inventory must never overwrite a snapshot that still holds items:
     * that is blank state from a not-yet-restored bot, not a real deposit. Marked by
     * {@link #markRestorePending}, cleared when {@link #load} applies the snapshot or
     * {@link #shouldRestoreOnJoin} finds nothing on disk to protect.
     */
    private static final Set<UUID> RESTORE_PENDING = ConcurrentHashMap.newKeySet();

    private static final String KEY_ALIAS = "Alias";
    private static final String KEY_UUID = "Uuid";
    private static final String KEY_SELECTED_SLOT = "SelectedSlot";
    private static final String KEY_INVENTORY = "Inventory";
    private static final String KEY_SLOT = "Slot";
    private static final String KEY_STACK = "Stack";
    private static final String KEY_HEALTH = "Health";
    private static final String KEY_FOOD = "FoodLevel";
    private static final String KEY_SATURATION = "FoodSaturation";
    private static final String KEY_XP = "XpLevel";
    private static final String KEY_XP_PROGRESS = "XpProgress";
    private static final String KEY_TOTAL_XP = "XpTotal";

    private static final Pattern SAFE_NAME = Pattern.compile("[^a-z0-9-_]+");
    private static final Map<UUID, String> LAST_SAVE_STATS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SAVE_LOG_MS = new ConcurrentHashMap<>();

    private BotInventoryStorageService() {}

    public static boolean save(ServerPlayerEntity bot) {
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return false;
        }

        Path path = resolveInventoryPath(server, bot);
        if (path == null) {
            return false;
        }

        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.warn("Unable to create inventory directory '{}': {}", path.getParent(), e.getMessage());
            return false;
        }

        DynamicRegistryManager registries = server.getRegistryManager();
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, registries);

        NbtList items = new NbtList();
        PlayerInventory inventory = bot.getInventory();

        String botName = bot.getName().getString();

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            final int slotIndex = slot;
            DataResult<NbtElement> encoded = ItemStack.CODEC.encodeStart(ops, stack);
            encoded.resultOrPartial(error -> LOGGER.warn("Failed to encode item in slot {} for '{}': {}", slotIndex, botName, error))
                    .ifPresent(element -> {
                        NbtCompound entry = new NbtCompound();
                        entry.putInt(KEY_SLOT, slotIndex);
                        entry.put(KEY_STACK, element);
                        items.add(entry);
                    });
        }

        NbtCompound root = new NbtCompound();
        root.putString(KEY_ALIAS, bot.getGameProfile().name());
        root.putString(KEY_UUID, bot.getUuidAsString());
        root.putInt(KEY_SELECTED_SLOT, inventory.getSelectedSlot());
        root.put(KEY_INVENTORY, items);
        
        // Save player stats (health, hunger, XP)
        root.putFloat(KEY_HEALTH, bot.getHealth());
        root.putInt(KEY_FOOD, bot.getHungerManager().getFoodLevel());
        root.putFloat(KEY_SATURATION, bot.getHungerManager().getSaturationLevel());
        root.putInt(KEY_XP, bot.experienceLevel);
        root.putFloat(KEY_XP_PROGRESS, bot.experienceProgress);
        root.putInt(KEY_TOTAL_XP, bot.totalExperience);

        try {
            // Guard: a bot whose snapshot has not been applied yet must not overwrite a snapshot
            // that still holds items with its blank inventory. Once the restore has applied, an
            // empty save is a real deposit/death and is honoured (no item duplication on rejoin).
            // 2026-08-29: this was a 300-byte size heuristic — it protected Jake's large
            // snapshot and let Bob's two-item one be wiped by the first periodic save.
            if (items.isEmpty() && RESTORE_PENDING.contains(bot.getUuid())
                    && Files.exists(path) && snapshotItemCount(path) > 0) {
                LOGGER.warn("Refusing to overwrite snapshot {} (still holds items) with the empty inventory of not-yet-restored '{}'",
                        path.getFileName(), bot.getName().getString());
                return false;
            }

            // Backup: copy existing file to .bak before overwriting.
            // If a crash corrupts the save, the .bak preserves the last good state.
            if (Files.exists(path)) {
                Path backup = path.resolveSibling(path.getFileName().toString() + ".bak");
                try {
                    Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException bakErr) {
                    LOGGER.warn("Failed to create backup for '{}': {}", bot.getName().getString(), bakErr.getMessage());
                }
            }

            net.minecraft.nbt.NbtIo.writeCompressed(root, path);
            maybeLogSave(bot, path);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save inventory for fakeplayer '{}': {}", bot.getName().getString(), e.getMessage());
            return false;
        }
    }

    public static boolean load(ServerPlayerEntity bot) {
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return false;
        }

        Path path = resolveBestInventoryPath(server, bot);
        if (path == null || !Files.exists(path)) {
            return false;
        }

        Path primaryPath = resolveInventoryPath(server, bot);
        if (primaryPath != null && !path.equals(primaryPath)) {
            LOGGER.info("Using fallback inventory snapshot for '{}': {} (expected {})",
                    bot.getName().getString(),
                    path.getFileName(),
                    primaryPath.getFileName());
        }

        // The primary snapshot is authoritative; .bak is only consulted when the primary cannot
        // be parsed. (A size-based ".bak looks bigger" fallback used to live here — it could
        // resurrect items the bot had legitimately deposited.)
        Path loadPath = path;

        NbtCompound root;
        try {
            root = net.minecraft.nbt.NbtIo.readCompressed(loadPath, NbtSizeTracker.ofUnlimitedBytes());
        } catch (IOException e) {
            // Primary/chosen path failed — try .bak as last resort
            Path backup = path.resolveSibling(path.getFileName().toString() + ".bak");
            if (Files.exists(backup) && !backup.equals(loadPath)) {
                LOGGER.warn("Primary load failed for '{}', trying .bak: {}", bot.getName().getString(), e.getMessage());
                try {
                    root = net.minecraft.nbt.NbtIo.readCompressed(backup, NbtSizeTracker.ofUnlimitedBytes());
                } catch (IOException bakErr) {
                    LOGGER.error("Both primary and .bak failed for '{}': {}", bot.getName().getString(), bakErr.getMessage());
                    return false;
                }
            } else {
                LOGGER.error("Failed to read inventory for fakeplayer '{}': {}", bot.getName().getString(), e.getMessage());
                return false;
            }
        }

        String savedAlias = root.getString(KEY_ALIAS).orElse("");
        String savedUuid = root.getString(KEY_UUID).orElse("");
        if (!savedUuid.isBlank() && !savedUuid.equalsIgnoreCase(bot.getUuidAsString())) {
            LOGGER.warn("Inventory snapshot UUID mismatch for '{}': file={} bot={}",
                    bot.getName().getString(),
                    savedUuid,
                    bot.getUuidAsString());
        }
        if (!savedAlias.isBlank()
                && !sanitizeAliasForStorage(savedAlias).equals(sanitizeAliasForStorage(bot.getGameProfile().name()))) {
            LOGGER.warn("Inventory snapshot alias mismatch for '{}': fileAlias='{}' botAlias='{}'",
                    bot.getName().getString(),
                    savedAlias,
                    bot.getGameProfile().name());
        }

        DynamicRegistryManager registries = server.getRegistryManager();
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, registries);
        PlayerInventory inventory = bot.getInventory();

        // Clear existing inventory.
        for (int slot = 0; slot < inventory.size(); slot++) {
            inventory.setStack(slot, ItemStack.EMPTY);
        }

        root.getInt(KEY_SELECTED_SLOT).ifPresent(inventory::setSelectedSlot);

        root.getList(KEY_INVENTORY).ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                NbtCompound entry = list.getCompound(i).orElse(new NbtCompound());
                int slot = entry.getInt(KEY_SLOT).orElse(-1);
                if (slot < 0 || slot >= inventory.size()) {
                    continue;
                }
                NbtElement stackElement = entry.get(KEY_STACK);
                if (stackElement == null) {
                    continue;
                }
                ItemStack stack = ItemStack.CODEC.parse(ops, stackElement)
                        .resultOrPartial(error -> LOGGER.warn("Failed to decode item for '{}': {}", bot.getName().getString(), error))
                        .orElse(ItemStack.EMPTY);
                inventory.setStack(slot, stack);
            }
        });

        inventory.markDirty();
        String before = statsSnapshot(bot);

        // Restore player stats (health, hunger, XP)
        root.getFloat(KEY_HEALTH).ifPresent(bot::setHealth);
        root.getInt(KEY_FOOD).ifPresent(bot.getHungerManager()::setFoodLevel);
        root.getFloat(KEY_SATURATION).ifPresent(bot.getHungerManager()::setSaturationLevel);
        root.getInt(KEY_XP).ifPresent(xp -> bot.experienceLevel = xp);
        root.getFloat(KEY_XP_PROGRESS).ifPresent(progress -> bot.experienceProgress = progress);
        root.getInt(KEY_TOTAL_XP).ifPresent(total -> bot.totalExperience = total);

        LOGGER.info("Loaded inventory and stats for fakeplayer '{}' from {} before={} after={}",
                bot.getName().getString(),
                path.getFileName(),
                before,
                statsSnapshot(bot));
        RESTORE_PENDING.remove(bot.getUuid());
        return true;
    }

    /**
     * True when a snapshot exists for this bot — and a snapshot is the ONLY source of a fake
     * player's items. Vanilla's {@code PlayerManager.loadPlayerData} merely READS the
     * {@code .dat}; applying it to the entity is part of the real-client connect path that
     * fake players never run (Jake's .dat held his items while his in-memory inventory was
     * still empty). On a clean stop vanilla also rewrites that {@code .dat} ~4 s after our
     * pre-shutdown snapshot. The mtime/size heuristics that used to live here therefore judged
     * the snapshot "stale" on every clean shutdown and wiped Bob's two-item inventory on
     * 2026-08-29. There is nothing to compare against: if the file is there, load it.
     */
    public static boolean shouldRestoreOnJoin(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null) {
            return false;
        }
        Path snapshotPath = resolveBestInventoryPath(server, bot);
        boolean present = snapshotPath != null && Files.exists(snapshotPath);
        if (!present) {
            RESTORE_PENDING.remove(bot.getUuid()); // nothing on disk to protect from an empty save
        }
        return present;
    }

    /** Called at join-start, before the next-tick snapshot load — see {@link #RESTORE_PENDING}. */
    public static void markRestorePending(UUID botId) {
        if (botId != null) {
            RESTORE_PENDING.add(botId);
        }
    }

    /** Item entries in a snapshot file; 0 when unreadable (an unreadable file is not worth protecting). */
    private static int snapshotItemCount(Path path) {
        try {
            NbtCompound root = net.minecraft.nbt.NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
            return root.getList(KEY_INVENTORY).map(NbtList::size).orElse(0);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static Path resolveInventoryPath(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null) {
            return null;
        }
        String alias = sanitizeAliasForStorage(bot.getGameProfile().name());
        return resolveInventoryPathForAliasUuid(server, alias, bot.getUuidAsString());
    }

    public static Path resolveInventoryPathForAliasUuid(MinecraftServer server, String alias, String uuidRaw) {
        if (server == null || alias == null || alias.isBlank() || uuidRaw == null || uuidRaw.isBlank()) {
            return null;
        }
        Path inventoriesDir = inventoryDirectory(server);
        if (inventoriesDir == null) {
            return null;
        }
        String safeAlias = sanitizeAliasForStorage(alias);
        String fileName = safeAlias + "_" + uuidRaw.trim() + ".nbt";
        return inventoriesDir.resolve(fileName);
    }

    public static boolean delete(ServerPlayerEntity bot) {
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return false;
        }
        Set<Path> candidates = collectCandidateSnapshots(server, bot.getGameProfile().name(), bot.getUuidAsString());
        if (candidates.isEmpty()) {
            return false;
        }
        boolean deleted = false;
        for (Path path : candidates) {
            try {
                if (Files.deleteIfExists(path)) {
                    deleted = true;
                    LOGGER.info("Deleted inventory snapshot for '{}': {}", bot.getName().getString(), path.getFileName());
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to delete inventory snapshot '{}' for '{}': {}",
                        path.getFileName(),
                        bot.getName().getString(),
                        e.getMessage());
            }
        }
        return deleted;
    }

    private static Path resolveBestInventoryPath(MinecraftServer server, ServerPlayerEntity bot) {
        Path primary = resolveInventoryPath(server, bot);
        if (primary != null && Files.exists(primary)) {
            return primary;
        }
        List<Path> candidates = new ArrayList<>(collectCandidateSnapshots(server, bot.getGameProfile().name(), bot.getUuidAsString()));
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparing(BotInventoryStorageService::safeLastModified).reversed());
        return candidates.get(0);
    }

    private static Set<Path> collectCandidateSnapshots(MinecraftServer server, String aliasRaw, String uuidRaw) {
        Set<Path> out = new HashSet<>();
        Path inventoriesDir = inventoryDirectory(server);
        if (inventoriesDir == null || !Files.isDirectory(inventoriesDir)) {
            return out;
        }

        String safeAlias = sanitizeAliasForStorage(aliasRaw);
        String uuid = uuidRaw == null ? "" : uuidRaw.trim().toLowerCase(Locale.ROOT);
        Path exact = resolveInventoryPathForAliasUuid(server, safeAlias, uuidRaw);
        if (exact != null && Files.exists(exact)) {
            out.add(exact);
        }

        String aliasPrefix = safeAlias + "_";
        String uuidSuffix = uuid.isBlank() ? "" : ("_" + uuid + ".nbt");
        try (var stream = Files.list(inventoriesDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbt"))
                    .forEach(path -> {
                        String file = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (file.startsWith(aliasPrefix) || (!uuidSuffix.isBlank() && file.endsWith(uuidSuffix))) {
                            out.add(path);
                        }
                    });
        } catch (IOException e) {
            LOGGER.warn("Unable to scan inventory snapshots under '{}': {}", inventoriesDir, e.getMessage());
        }
        return out;
    }

    private static FileTime safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0L);
        }
    }

    private static Path inventoryDirectory(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        Path base = server.getSavePath(WorldSavePath.PLAYERDATA);
        if (base == null) {
            return null;
        }
        migrateFromLegacyDir(base);
        return base.resolve("frens").resolve("inventories");
    }

    public static String sanitizeAliasForStorage(String value) {
        if (value == null) {
            return "unknown";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toLowerCase();
        String sanitized = SAFE_NAME.matcher(normalized).replaceAll("_");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }

    /**
     * One-time migration: copy NBT files from the legacy {@code ai-player/inventories/}
     * subdirectory into {@code frens/inventories/} so bots keep their inventories after
     * the mod-ID rename.  Only files that do not already exist under the new path are copied.
     */
    private static void migrateFromLegacyDir(Path playerDataBase) {
        if (MIGRATION_DONE.getAndSet(true)) return;
        Path legacyDir = playerDataBase.resolve(LEGACY_MOD_DIR).resolve("inventories");
        if (!Files.isDirectory(legacyDir)) return;
        Path newDir = playerDataBase.resolve("frens").resolve("inventories");
        try {
            Files.createDirectories(newDir);
            try (var stream = Files.list(legacyDir)) {
                stream.filter(p -> p.toString().endsWith(".nbt")).forEach(src -> {
                    Path dest = newDir.resolve(src.getFileName());
                    if (!Files.exists(dest)) {
                        try {
                            Files.copy(src, dest);
                            LOGGER.info("Migrated legacy inventory: {} -> {}", src.getFileName(), dest);
                        } catch (IOException ex) {
                            LOGGER.warn("Failed to migrate {}: {}", src.getFileName(), ex.getMessage());
                        }
                    }
                });
            }
        } catch (IOException e) {
            LOGGER.warn("Legacy inventory migration failed: {}", e.getMessage());
        }
    }

    private static String statsSnapshot(ServerPlayerEntity bot) {
        if (bot == null) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "hp=%.1f food=%d sat=%.2f xp=%d prog=%.3f total=%d",
                bot.getHealth(),
                bot.getHungerManager().getFoodLevel(),
                bot.getHungerManager().getSaturationLevel(),
                bot.experienceLevel,
                bot.experienceProgress,
                bot.totalExperience);
    }

    private static void maybeLogSave(ServerPlayerEntity bot, Path path) {
        if (bot == null || path == null) {
            return;
        }
        UUID botId = bot.getUuid();
        String stats = statsSnapshot(bot);
        long now = System.currentTimeMillis();
        String lastStats = LAST_SAVE_STATS.get(botId);
        long lastLog = LAST_SAVE_LOG_MS.getOrDefault(botId, 0L);
        boolean changed = !stats.equals(lastStats);
        if (!changed && (now - lastLog) < SAVE_LOG_COOLDOWN_MS) {
            return;
        }
        LAST_SAVE_STATS.put(botId, stats);
        LAST_SAVE_LOG_MS.put(botId, now);
        LOGGER.info("Saved inventory for fakeplayer '{}' to {} stats={}",
                bot.getName().getString(),
                path.getFileName(),
                stats);
    }
}
