package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        /**
         * Owner of the bot that placed this chest, as {@link UUID#toString()}, stamped when the
         * chest is (re-)registered. {@code null} for every record written before 1.1.219 (Gson
         * leaves the absent field null) and for any chest placed by an un-owned bot. Never
         * backfilled from the bot's current config owner — that can change after placement.
         * {@link #recordedOwnersAt} skips a record whose owner is {@code null}, so for companion
         * supplies such a chest reads as if no bot had recorded it (the player's own storage).
         */
        public String ownerUuid;

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
        // Legacy "levelName/dim" key -> fingerprint of the save that adopted it (1.1.274 migration).
        // Absent in older files: Gson leaves it null, read as empty.
        Map<String, String> legacyWorldClaims = new HashMap<>();
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

    /**
     * Store key: save fingerprint + dimension ({@code "New World#1a2b3c:minecraft:overworld"}).
     * Before 1.1.274 this was {@code levelName/dimension}, which two saves with the same level
     * name share; see {@link #legacyServerWorldKey} and {@link #adoptLegacyWorldData}.
     */
    private static String serverWorldKey(MinecraftServer server, ServerWorld world) {
        return ChestRegistryWorldKeys.key(BotWorldStateService.currentWorldKey(server), dimensionId(world));
    }

    private static String legacyServerWorldKey(MinecraftServer server, ServerWorld world) {
        String level = server != null && server.getSaveProperties() != null
                ? server.getSaveProperties().getLevelName() : null;
        return ChestRegistryWorldKeys.legacyKey(level, dimensionId(world));
    }

    private static String dimensionId(ServerWorld world) {
        return world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString() : null;
    }

    /** identityHashCode of the server whose legacy adoption has run; -1 = none yet. */
    private static volatile int adoptedServerIdentity = -1;

    /** Most legacy chest positions checked per dimension before adopting (finding 40). */
    private static final int MAX_LEGACY_POSITIONS_CHECKED = 4;

    /**
     * Keys whose adoption a worker pass could not decide (no checked position loaded). Until the
     * server thread decides them, {@link #worldData} hands out a detached entry instead of
     * creating this save's own empty store. Guarded by {@link #LOCK}.
     */
    private static final Set<String> UNDECIDED_KEYS = new HashSet<>();

    /** Result of checking a legacy entry's recorded chest positions in this save. */
    private record LegacyChestCheck(int candidates, int checked, int chestLike) {}

    /**
     * Once per server start, before anything reads chests: for each dimension with no
     * fingerprinted entry, copy the legacy ({@code levelName/dim}) entry when
     * {@link WorldStoreKeyPolicy#decideChestStore} allows it, and record the claim in
     * {@code legacyWorldClaims}. The legacy key is shared by every save with that level name, so
     * up to {@value #MAX_LEGACY_POSITIONS_CHECKED} of its live chest records are checked first:
     * if none of the checked positions holds a chest here, the entry is another save's and this
     * one starts empty without a claim (finding 40). Legacy entries are never removed or
     * modified. A world that starts empty gets an empty entry so the decision sticks. Call from
     * SERVER_STARTED on the server thread, where reading a position may load its chunk;
     * {@link #worldData} and {@link #recordedOwnersAt} also run it lazily as a fallback, and on a
     * worker thread that pass only reads already-loaded chunks and leaves a key undecided when
     * none of its positions is loaded.
     */
    public static void adoptLegacyWorldData(MinecraftServer server) {
        if (server == null) return;
        ensureLoaded();
        boolean mayLoadChunks = server.isOnThread();
        String fingerprint = BotWorldStateService.currentWorldKey(server);
        long gameTime = WorldZoneStore.overworldGameTime(server);
        boolean changed = false;
        boolean undecided = false;
        synchronized (LOCK) {
            if (DATA.worlds == null) DATA.worlds = new HashMap<>();
            if (DATA.legacyWorldClaims == null) DATA.legacyWorldClaims = new HashMap<>();
            for (ServerWorld world : server.getWorlds()) {
                String key = serverWorldKey(server, world);
                String legacyKey = legacyServerWorldKey(server, world);
                WorldData legacy = DATA.worlds.get(legacyKey);
                String claimant = DATA.legacyWorldClaims.get(legacyKey);
                boolean hasOwn = DATA.worlds.containsKey(key);
                if (hasOwn) {
                    UNDECIDED_KEYS.remove(key);
                    if (legacy != null && mayLoadChunks && fingerprint.equals(claimant)) {
                        changed |= releaseWrongClaim(world, legacy, legacyKey, key, claimant, fingerprint);
                    }
                    continue;
                }
                WorldStoreKeyPolicy.Adoption base = WorldStoreKeyPolicy.decide(
                        false, legacy != null, gameTime, claimant, fingerprint);
                LegacyChestCheck check = new LegacyChestCheck(0, 0, 0);
                if (base == WorldStoreKeyPolicy.Adoption.ADOPT_LEGACY) {
                    check = checkLegacyChests(world, legacy, mayLoadChunks);
                    if (check.candidates() > 0 && check.checked() == 0 && !mayLoadChunks) {
                        // A worker saw none of the positions loaded: no decision, no own store.
                        if (UNDECIDED_KEYS.add(key)) {
                            LOGGER.info("Chest registry '{}' adoption of legacy '{}' deferred: none of {} recorded chest position(s) is loaded on this thread",
                                    key, legacyKey, check.candidates());
                        }
                        undecided = true;
                        continue;
                    }
                }
                UNDECIDED_KEYS.remove(key);
                WorldStoreKeyPolicy.Adoption adoption = WorldStoreKeyPolicy.decideChestStore(
                        false, legacy != null, gameTime, claimant, fingerprint,
                        check.checked(), check.chestLike());
                if (adoption == WorldStoreKeyPolicy.Adoption.ADOPT_LEGACY) {
                    DATA.worlds.put(key, GSON.fromJson(GSON.toJson(legacy), WorldData.class));
                    DATA.legacyWorldClaims.put(legacyKey, fingerprint);
                    LOGGER.info("Adopted legacy chest registry '{}' as '{}' (checked={}, chestLike={})",
                            legacyKey, key, check.checked(), check.chestLike());
                    changed = true;
                } else if (WorldStoreKeyPolicy.mustCreateOwnStore(adoption)) {
                    DATA.worlds.put(key, new WorldData());
                    LOGGER.info("Chest registry '{}' starts empty ({}, checked={}, chestLike={}); legacy '{}' left untouched",
                            key, adoption, check.checked(), check.chestLike(), legacyKey);
                    changed = true;
                }
            }
            // Marked only once the loop has run: a worker that sees the old value runs this too,
            // waits on LOCK, and finds every key already decided, instead of skipping adoption
            // and creating an empty store before the legacy chests are copied. Not marked while a
            // key is undecided, so a later call (or the server-thread pass queued below) decides it.
            if (!undecided) {
                adoptedServerIdentity = System.identityHashCode(server);
            }
        }
        if (changed) {
            flush();
        }
        if (undecided) {
            server.execute(() -> adoptIfNeeded(server));
        }
    }

    /**
     * 1.1.274 adopted a legacy entry by level name alone, so a same-named save may hold the claim
     * while the chests are in another save. When none of the checked legacy positions holds a
     * chest here, drop the claim (this save keeps its own store) so the real owner can still
     * adopt if it has not decided yet. Server thread only; caller holds {@link #LOCK}.
     */
    private static boolean releaseWrongClaim(ServerWorld world, WorldData legacy, String legacyKey,
                                             String key, String claimant, String fingerprint) {
        LegacyChestCheck check = checkLegacyChests(world, legacy, true);
        if (!WorldStoreKeyPolicy.shouldReleaseChestClaim(claimant, fingerprint, check.checked(), check.chestLike())) {
            return false;
        }
        DATA.legacyWorldClaims.remove(legacyKey);
        LOGGER.info("Released claim on legacy chest registry '{}' held by '{}' (checked={}, chestLike={}): its chests are not in this save",
                legacyKey, key, check.checked(), check.chestLike());
        return true;
    }

    /**
     * Checks up to {@value #MAX_LEGACY_POSITIONS_CHECKED} non-destroyed records of a legacy
     * entry for a chest-like block. With {@code mayLoadChunks} false only already-loaded chunks
     * are read, through {@code getWorldChunk}, which never loads or waits for the server thread
     * (in 1.21.11 it returns null on any other thread, so a worker pass checks nothing and
     * defers). Never {@code world.getBlockState} off-thread here: that joins the server thread
     * while this caller holds {@link #LOCK}.
     */
    private static LegacyChestCheck checkLegacyChests(ServerWorld world, WorldData legacy, boolean mayLoadChunks) {
        if (world == null || legacy == null || legacy.chestsByBot == null) {
            return new LegacyChestCheck(0, 0, 0);
        }
        int candidates = 0;
        int checked = 0;
        int chestLike = 0;
        for (List<ChestRecord> records : legacy.chestsByBot.values()) {
            if (records == null) continue;
            for (ChestRecord r : records) {
                if (r == null || r.destroyed) continue;
                if (candidates >= MAX_LEGACY_POSITIONS_CHECKED) {
                    return new LegacyChestCheck(candidates, checked, chestLike);
                }
                candidates++;
                BlockPos pos = new BlockPos(r.x, r.y, r.z);
                BlockState state;
                try {
                    if (mayLoadChunks) {
                        state = world.getBlockState(pos);
                    } else {
                        WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
                        if (chunk == null) continue;
                        state = chunk.getBlockState(pos);
                    }
                } catch (Throwable t) {
                    continue;
                }
                checked++;
                if (isChestLike(state)) {
                    chestLike++;
                }
            }
        }
        return new LegacyChestCheck(candidates, checked, chestLike);
    }

    /** Chest, trapped chest, barrel or any copper chest (1.21.11). */
    private static boolean isChestLike(BlockState state) {
        return state != null
                && (state.isOf(Blocks.CHEST)
                || state.isOf(Blocks.TRAPPED_CHEST)
                || state.isOf(Blocks.BARREL)
                || state.isIn(BlockTags.COPPER_CHESTS));
    }

    private static void adoptIfNeeded(MinecraftServer server) {
        if (server != null && adoptedServerIdentity != System.identityHashCode(server)) {
            adoptLegacyWorldData(server);
        }
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
        adoptIfNeeded(server);
        String key = serverWorldKey(server, world);
        synchronized (LOCK) {
            if (DATA.worlds == null) DATA.worlds = new HashMap<>();
            if (!DATA.worlds.containsKey(key) && UNDECIDED_KEYS.contains(key)) {
                // Adoption still pending (worker saw no loaded chest position): do not create an
                // empty own store that would make the decision for it. Detached, not saved.
                LOGGER.warn("Chest registry '{}' read before its legacy adoption was decided; a write now is not saved", key);
                return new WorldData();
            }
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
        // The bot just placed this chest, so its owner now is the placer's owner.
        UUID placerOwner = CompanionCommunicationPolicy.resolveOwnerUuid(bot);
        String ownerUuid = placerOwner == null ? null : placerOwner.toString();
        synchronized (LOCK) {
            if (wd.chestsByBot == null) wd.chestsByBot = new HashMap<>();
            List<ChestRecord> records = wd.chestsByBot.computeIfAbsent(key, ignored -> new ArrayList<>());

            // Avoid duplicates at the same position
            for (ChestRecord r : records) {
                if (r.x == pos.getX() && r.y == pos.getY() && r.z == pos.getZ()) {
                    r.destroyed = false;
                    r.context = context != null ? context : r.context;
                    r.placedAtMs = System.currentTimeMillis();
                    r.ownerUuid = ownerUuid;
                    flush();
                    return;
                }
            }

            ChestRecord record = new ChestRecord(pos, context);
            record.ownerUuid = ownerUuid;
            records.add(record);
        }
        flush();
        LOGGER.info("Registered chest for {} at {},{},{} ({})",
                bot.getName().getString(), pos.getX(), pos.getY(), pos.getZ(), context);
    }

    /**
     * The recorded owner of every owned record, under any bot alias, at exactly {@code pos} in
     * {@code world} — destroyed records included, since re-placing a chest revives them. One
     * element per record. A record with no owner ({@link ChestRecord#ownerUuid} {@code null}:
     * written before 1.1.219, or placed by an un-owned bot) is skipped, so a position holding only
     * such records returns an empty list, the same as no record at all. Input for
     * {@code SupplyChestRules.ownership}. The registry is keyed by save fingerprint and dimension
     * (1.1.274), so a same-named save's records are not included.
     *
     * <p>A {@code null} world or position, or a world without a server, cannot be looked up and
     * returns a single {@code null} element, which that rule denies. Records never yield a
     * {@code null} element, so it always means a failed lookup.
     */
    public static List<String> recordedOwnersAt(ServerWorld world, BlockPos pos) {
        MinecraftServer server = world == null ? null : world.getServer();
        if (server == null || pos == null) {
            return Collections.singletonList(null);
        }
        ensureLoaded();
        adoptIfNeeded(server);
        String key = serverWorldKey(server, world);
        synchronized (LOCK) {
            WorldData wd = DATA.worlds == null ? null : DATA.worlds.get(key);
            if (wd == null || wd.chestsByBot == null) {
                return List.of();
            }
            List<String> owners = new ArrayList<>();
            for (List<ChestRecord> records : wd.chestsByBot.values()) {
                if (records == null) {
                    continue;
                }
                for (ChestRecord r : records) {
                    // An owner-less record reads as no record; null stays reserved for the sentinel.
                    if (r != null && r.ownerUuid != null
                            && r.x == pos.getX() && r.y == pos.getY() && r.z == pos.getZ()) {
                        owners.add(r.ownerUuid);
                    }
                }
            }
            return owners.isEmpty() ? List.of() : Collections.unmodifiableList(owners);
        }
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
