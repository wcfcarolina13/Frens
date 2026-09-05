package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.wcfcarolina13.PlayerUtils.DebouncedWriter;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Persisted "home" data used for returning to base.
 *
 * <p>Stores:
 * <ul>
 *   <li>Last successful sleep position per bot</li>
 *   <li>Saved base locations per world/dimension</li>
 *   <li>Auto-return-at-sunset toggle per bot</li>
 *   <li>Tactical-shelter automation toggle per bot</li>
 *   <li>Auto-return-at-sunset eligibility for guard/patrol per bot</li>
 *   <li>Idle/ambient hobbies toggle per bot</li>
 *   <li>Auto-hunt-when-starving toggle per bot</li>
 *   <li>Attack-named-mobs toggle per bot (default off — bot ignores name-tagged mobs)</li>
 * </ul>
 *
 * <p>Data is keyed by server save name + dimension key so integrated-server worlds do not collide.
 */
public final class BotHomeService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-home");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "bot_home_data.json";
    private static final Object LOCK = new Object();

    private static RootData DATA = new RootData();
    private static boolean loaded = false;

    /** Default protection radius (blocks) applied when a base has no explicit radius. */
    public static final int DEFAULT_BASE_PROTECTION_RADIUS = 40;

    /**
     * Default ceiling on user-settable base radius. Admins can override per-world via
     * {@link #setMaxBaseRadius}. Kept at 128 for parity with the pre-admin hard cap so
     * existing worlds see no behavior change until an admin tunes it down (or up).
     */
    public static final int DEFAULT_MAX_BASE_RADIUS = 128;

    /**
     * Absolute ceiling. Even an admin can't set a world cap higher than this. 256 blocks
     * is ~16 chunks — already well beyond any realistic base footprint, and bigger values
     * start to interact badly with animal-defense/navigation-artifact scans that multiply
     * the radius (e.g. fast-travel range = radius × 5).
     */
    public static final int HARD_MAX_BASE_RADIUS_LIMIT = 256;

    /**
     * Sentinel {@code ownerUuid} value for bases that belong to the server itself — e.g. the
     * auto-created "Spawn" base, or bases explicitly claimed by an admin for moderation. Not a
     * valid Minecraft UUID (UUIDs are 36 chars with dashes), so there's no collision risk with
     * real player UUIDs.
     */
    public static final String SERVER_OWNER_UUID = "SERVER";

    public static final String SERVER_OWNER_NAME = "Server";

    /**
     * Label prefix for auto-zones mirroring registered bases. The suffix is the base's normalized
     * label (same key used in {@code basesByLabel}), so each base owns exactly one zone and
     * user-created zones (without this prefix) never collide.
     */
    private static final String AUTO_ZONE_LABEL_PREFIX = "base:";

    private static String autoZoneLabel(String baseLabel) {
        return AUTO_ZONE_LABEL_PREFIX + normalizeLabelKey(baseLabel);
    }

    /**
     * Resolve owner UUID for a base into a {@link UUID} suitable for {@link ProtectedZoneService}.
     * Server-owned bases (sentinel "SERVER") and legacy null/blank owners both map to {@code null},
     * which {@code ProtectedZoneService.isMutationAllowed} treats as "owner_only with no owner" —
     * i.e. all bot mutations rejected, which is what we want for spawn/admin bases.
     */
    private static UUID parseZoneOwner(String ownerUuid) {
        if (ownerUuid == null || ownerUuid.isBlank()) return null;
        if (SERVER_OWNER_UUID.equals(ownerUuid)) return null;
        try {
            return UUID.fromString(ownerUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Upsert the auto-zone matching a base. No-op if the world's zone storage hasn't been loaded
     * yet (the post-load migration will catch up). Caller holds no lock.
     */
    private static void upsertAutoZoneForBase(MinecraftServer server, ServerWorld world,
                                              String baseLabel, BlockPos center, int storedRadius,
                                              String ownerUuidStr, String ownerName) {
        if (server == null || world == null || baseLabel == null || center == null) return;
        String worldId = world.getRegistryKey().getValue().toString();
        if (!ProtectedZoneService.isLoaded(worldId)) return;
        int effectiveRadius = storedRadius > 0 ? storedRadius : DEFAULT_BASE_PROTECTION_RADIUS;
        BlockPos min = center.add(-effectiveRadius, -effectiveRadius, -effectiveRadius);
        BlockPos max = center.add(effectiveRadius, effectiveRadius, effectiveRadius);
        UUID owner = parseZoneOwner(ownerUuidStr);
        ProtectedZoneService.upsertZoneInternal(world, autoZoneLabel(baseLabel), min, max,
                owner, ownerName);
    }

    /**
     * Walk all bases registered for the given world and ensure each has a matching auto-zone.
     * Called once at server start after {@link ProtectedZoneService#loadZones} so the in-memory
     * zone map is the green light to write. Idempotent — re-runs harmlessly upsert the same data.
     */
    public static void syncZonesFromBases(MinecraftServer server, ServerWorld world) {
        if (server == null || world == null) return;
        String worldId = world.getRegistryKey().getValue().toString();
        if (!ProtectedZoneService.isLoaded(worldId)) return;
        int synced = 0;
        for (BaseEntry base : listBases(server, world)) {
            if (base == null || base.pos() == null) continue;
            int effectiveRadius = base.radius() > 0 ? base.radius() : DEFAULT_BASE_PROTECTION_RADIUS;
            BlockPos min = base.pos().add(-effectiveRadius, -effectiveRadius, -effectiveRadius);
            BlockPos max = base.pos().add(effectiveRadius, effectiveRadius, effectiveRadius);
            UUID owner = parseZoneOwner(base.ownerUuid());
            if (ProtectedZoneService.upsertZoneInternal(world, autoZoneLabel(base.label()),
                    min, max, owner, base.ownerName())) {
                synced++;
            }
        }
        if (synced > 0) {
            LOGGER.info("Synced {} auto-zones from registered bases for world {}", synced, worldId);
        }
    }

    /** Canonical label used when the server auto-creates a base at world spawn. */
    public static final String AUTO_SPAWN_BASE_LABEL = "Spawn";

    public record BaseEntry(String label, BlockPos pos, int radius, String ownerUuid, String ownerName) {}

    /**
     * Semantic intent for {@link #resolveHomeTarget(ServerPlayerEntity, ReturnIntent)}.
     * Callers pick the mode that matches their situation; the resolver picks the best
     * destination under that mode's rules.
     */
    public enum ReturnIntent {
        /** Sunset return: prefer a recently-used bed (validated) over a stale preferred home. */
        SUNSET_BED,
        /** Commander-led: go to commander if present in same dimension; else fall through to BASE_CENTER_LAZY. */
        COMMANDER_SIDE,
        /** Declared base anchor with a lazy walkable-surface snap when the anchor is unwalkable. */
        BASE_CENTER_LAZY,
        /** Legacy closer-of-bed-or-base (preferred home wins outright if set). */
        DEFAULT
    }

    private BotHomeService() {}

    private static Path stateFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("frens").resolve(FILE_NAME);
    }

    private static String serverWorldKey(MinecraftServer server, ServerWorld world) {
        String level = server != null && server.getSaveProperties() != null
                ? server.getSaveProperties().getLevelName()
                : "unknown";
        String dim = world != null && world.getRegistryKey() != null
                ? world.getRegistryKey().getValue().toString()
                : "unknown";
        return level + ":" + dim;
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
                    LOGGER.warn("Failed to load bot home data: {}", e.getMessage());
                    DATA = new RootData();
                }
            }
            loaded = true;
        }
    }

    // ── Debounced persistence ────────────────────────────────────────────────
    // Every mutator used to write the whole JSON file synchronously on the caller's thread —
    // sometimes the server thread. Mutators now only mark the state dirty; the actual write
    // lands on a daemon scheduler thread after 500 ms of quiet, and never later than 5 s after
    // the first unflushed change. flushNow() forces a synchronous write (server stop).

    private static final long WRITE_QUIET_MS = 500L;
    private static final long WRITE_MAX_LATENCY_MS = 5_000L;

    // volatile: published from restartExecutors() on the server thread, read by mutators on worker threads.
    private static volatile ScheduledExecutorService writeExecutor = newWriteExecutor();
    private static volatile DebouncedWriter writer = new DebouncedWriter(
            BotHomeService::writeToDisk, WRITE_QUIET_MS, WRITE_MAX_LATENCY_MS, writeExecutor);

    private static ScheduledExecutorService newWriteExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "frens-bot-home-writer");
            t.setDaemon(true);
            return t;
        });
    }

    /** The actual whole-file write. Only ever called by {@link #writer}. */
    private static void writeToDisk() {
        synchronized (LOCK) {
            try {
                Path file = stateFile();
                Files.createDirectories(file.getParent());
                try (Writer out = Files.newBufferedWriter(file)) {
                    GSON.toJson(DATA, out);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to save bot home data: {}", e.getMessage());
            }
        }
    }

    /**
     * Marks the home data dirty. Cheap and safe from the server thread — the write is debounced.
     * Keeps the historical name so every mutator call site is unchanged.
     */
    private static void flush() {
        writer.markDirty();
    }

    /** Forces any pending write to disk synchronously on the calling thread. */
    public static void flushNow() {
        writer.flushNow();
    }

    /** Server-stop hook: flush pending state, then stop the writer thread. */
    public static void shutdownExecutors() {
        writer.shutdown();
        writeExecutor.shutdownNow();
    }

    /**
     * Server-start hook: re-arm the writer after a previous SERVER_STOPPING shut it down
     * (integrated server exit-to-title + rejoin keeps static state alive). No-op if alive.
     */
    public static void restartExecutors() {
        if (writeExecutor == null || writeExecutor.isShutdown()) {
            writeExecutor = newWriteExecutor();
            writer = new DebouncedWriter(
                    BotHomeService::writeToDisk, WRITE_QUIET_MS, WRITE_MAX_LATENCY_MS, writeExecutor);
        }
    }

    private static String botKey(ServerPlayerEntity bot) {
        if (bot == null) {
            return "";
        }
        return bot.getName().getString().toLowerCase(Locale.ROOT);
    }

    private static String normalizeLabelKey(String label) {
        if (label == null) {
            return "";
        }
        return label.trim().toLowerCase(Locale.ROOT);
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

    public static void recordLastSleep(ServerPlayerEntity bot, BlockPos bedPos) {
        if (bot == null || bedPos == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.lastSleepByBot == null) {
                wd.lastSleepByBot = new HashMap<>();
            }
            wd.lastSleepByBot.put(botId, SavedSleep.from(bedPos));
        }
        flush();
    }

    public static Optional<BlockPos> getLastSleep(ServerPlayerEntity bot) {
        SavedSleep record = getLastSleepRecord(bot);
        return record != null ? Optional.of(record.toBlockPos()) : Optional.empty();
    }

    public static boolean isNearAnyBase(ServerPlayerEntity bot, double radiusBlocks) {
        if (bot == null || radiusBlocks <= 0.0D) {
            return false;
        }
        Optional<BlockPos> base = findNearestBase(bot);
        if (base.isEmpty()) {
            return false;
        }
        double radiusSq = radiusBlocks * radiusBlocks;
        Vec3d origin = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        return origin.squaredDistanceTo(Vec3d.ofCenter(base.get())) <= radiusSq;
    }

    public static boolean isNearRecentSleep(ServerPlayerEntity bot, double radiusBlocks, long recentWindowMs) {
        if (bot == null || radiusBlocks <= 0.0D || recentWindowMs <= 0L) {
            return false;
        }
        SavedSleep record = getLastSleepRecord(bot);
        if (record == null || record.lastSleepMs <= 0L) {
            return false;
        }
        long ageMs = System.currentTimeMillis() - record.lastSleepMs;
        if (ageMs > recentWindowMs) {
            return false;
        }
        double radiusSq = radiusBlocks * radiusBlocks;
        Vec3d origin = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        return origin.squaredDistanceTo(Vec3d.ofCenter(record.toBlockPos())) <= radiusSq;
    }

    private static SavedSleep getLastSleepRecord(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return null;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return null;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.lastSleepByBot == null) {
                return null;
            }
            return wd.lastSleepByBot.get(botId);
        }
    }

    public static boolean setAutoReturnAtSunset(ServerPlayerEntity bot, boolean enabled) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoReturnAtSunsetByBot == null) {
                wd.autoReturnAtSunsetByBot = new HashMap<>();
            }
            wd.autoReturnAtSunsetByBot.put(botId, enabled);
        }
        flush();
        return true;
    }

    /**
     * If enabled, the bot may use improvised tactical shelter automation:
     * proactive night sheltering and the sunset tactical-shelter fallback.
     *
     * <p>Default: true.
     */
    public static boolean setTacticalShelterEnabled(ServerPlayerEntity bot, boolean enabled) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.tacticalShelterEnabledByBot == null) {
                wd.tacticalShelterEnabledByBot = new HashMap<>();
            }
            wd.tacticalShelterEnabledByBot.put(botId, enabled);
        }
        flush();
        return true;
    }

    public static boolean toggleTacticalShelterEnabled(ServerPlayerEntity bot) {
        boolean next = !isTacticalShelterEnabled(bot);
        return setTacticalShelterEnabled(bot, next);
    }

    /** Default: true (bots may improvise local shelter unless explicitly disabled). */
    public static boolean isTacticalShelterEnabled(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return true;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return true;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return true;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.tacticalShelterEnabledByBot == null) {
                return true;
            }
            Boolean val = wd.tacticalShelterEnabledByBot.get(botId);
            return val == null ? true : Boolean.TRUE.equals(val);
        }
    }

    public static boolean toggleAutoReturnAtSunset(ServerPlayerEntity bot) {
        boolean next = !isAutoReturnAtSunset(bot);
        return setAutoReturnAtSunset(bot, next);
    }

    public static boolean isAutoReturnAtSunset(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoReturnAtSunsetByBot == null) {
                return false;
            }
            Boolean val = wd.autoReturnAtSunsetByBot.get(botId);
            return Boolean.TRUE.equals(val);
        }
    }

    /**
     * If enabled, sunset auto-return may fail over from HOME to nearby survival anchors when HOME is
     * too far, unreachable, or no progress is being made.
     *
     * <p>Default: false (strict HOME return).
     */
    public static boolean setAutoReturnSelfSufficientFallback(ServerPlayerEntity bot, boolean enabled) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoReturnSelfSufficientFallbackByBot == null) {
                wd.autoReturnSelfSufficientFallbackByBot = new HashMap<>();
            }
            wd.autoReturnSelfSufficientFallbackByBot.put(botId, enabled);
        }
        flush();
        return true;
    }

    public static boolean toggleAutoReturnSelfSufficientFallback(ServerPlayerEntity bot) {
        boolean next = !isAutoReturnSelfSufficientFallback(bot);
        return setAutoReturnSelfSufficientFallback(bot, next);
    }

    public static boolean isAutoReturnSelfSufficientFallback(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoReturnSelfSufficientFallbackByBot == null) {
                return false;
            }
            Boolean val = wd.autoReturnSelfSufficientFallbackByBot.get(botId);
            return Boolean.TRUE.equals(val);
        }
    }

    /**
     * If enabled, sunset auto-return will always prefer the bot's last slept bed (if known)
     * over the nearest saved base.
     *
     * <p>Default: false (use the normal {@link #resolveHomeTarget(ServerPlayerEntity)} heuristic).
     */
    public static boolean setAutoReturnPreferLastBedAtSunset(ServerPlayerEntity bot, boolean enabled) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoReturnPreferLastBedAtSunsetByBot == null) {
                wd.autoReturnPreferLastBedAtSunsetByBot = new HashMap<>();
            }
            wd.autoReturnPreferLastBedAtSunsetByBot.put(botId, enabled);
        }
        flush();
        return true;
    }

    public static boolean toggleAutoReturnPreferLastBedAtSunset(ServerPlayerEntity bot) {
        boolean next = !isAutoReturnPreferLastBedAtSunset(bot);
        return setAutoReturnPreferLastBedAtSunset(bot, next);
    }

    public static boolean isAutoReturnPreferLastBedAtSunset(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoReturnPreferLastBedAtSunsetByBot == null) {
                return false;
            }
            Boolean val = wd.autoReturnPreferLastBedAtSunsetByBot.get(botId);
            return Boolean.TRUE.equals(val);
        }
    }

    public static boolean setAttackNamedMobs(ServerPlayerEntity bot, boolean enabled) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.attackNamedMobsByBot == null) {
                wd.attackNamedMobsByBot = new HashMap<>();
            }
            wd.attackNamedMobsByBot.put(botId, enabled);
        }
        flush();
        return true;
    }

    public static boolean toggleAttackNamedMobs(ServerPlayerEntity bot) {
        boolean next = !isAttackNamedMobs(bot);
        return setAttackNamedMobs(bot, next);
    }

    public static boolean isAttackNamedMobs(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.attackNamedMobsByBot == null) {
                return false;
            }
            Boolean val = wd.attackNamedMobsByBot.get(botId);
            return Boolean.TRUE.equals(val);
        }
    }

    public static boolean setAutoReturnGuardPatrolEligible(ServerPlayerEntity bot, boolean enabled) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoReturnGuardPatrolEligibleByBot == null) {
                wd.autoReturnGuardPatrolEligibleByBot = new HashMap<>();
            }
            wd.autoReturnGuardPatrolEligibleByBot.put(botId, enabled);
        }
        flush();
        return true;
    }

    public static boolean toggleAutoReturnGuardPatrolEligible(ServerPlayerEntity bot) {
        boolean next = !isAutoReturnGuardPatrolEligible(bot);
        return setAutoReturnGuardPatrolEligible(bot, next);
    }

    /**
     * Whether GUARD/PATROL modes are eligible for sunset auto-return/sleep.
     * <p>
     * Default: false.
     */
    public static boolean isAutoReturnGuardPatrolEligible(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoReturnGuardPatrolEligibleByBot == null) {
                return false;
            }
            Boolean val = wd.autoReturnGuardPatrolEligibleByBot.get(botId);
            return val == null ? false : Boolean.TRUE.equals(val);
        }
    }

    // ── Auto-return skip-permission ──────────────────────────────────────────

    public static boolean setAutoReturnSkipPermission(ServerPlayerEntity bot, boolean enabled) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        MinecraftServer server = world.getServer();
        if (server == null) return false;
        String botId = botKey(bot);
        if (botId.isBlank()) return false;
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoReturnSkipPermissionByBot == null) wd.autoReturnSkipPermissionByBot = new HashMap<>();
            wd.autoReturnSkipPermissionByBot.put(botId, enabled);
        }
        flush();
        return true;
    }

    public static boolean toggleAutoReturnSkipPermission(ServerPlayerEntity bot) {
        boolean next = !isAutoReturnSkipPermission(bot);
        return setAutoReturnSkipPermission(bot, next);
    }

    /** Default: false (bots ask permission before auto-returning). */
    public static boolean isAutoReturnSkipPermission(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        MinecraftServer server = world.getServer();
        if (server == null) return false;
        String botId = botKey(bot);
        if (botId.isBlank()) return false;
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoReturnSkipPermissionByBot == null) return false;
            Boolean val = wd.autoReturnSkipPermissionByBot.get(botId);
            return Boolean.TRUE.equals(val);
        }
    }

    public static boolean setIdleHobbiesEnabled(ServerPlayerEntity bot, boolean enabled) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.idleHobbiesEnabledByBot == null) {
                wd.idleHobbiesEnabledByBot = new HashMap<>();
            }
            wd.idleHobbiesEnabledByBot.put(botId, enabled);
        }
        flush();
        return true;
    }

    public static boolean toggleIdleHobbiesEnabled(ServerPlayerEntity bot) {
        boolean next = !isIdleHobbiesEnabled(bot);
        return setIdleHobbiesEnabled(bot, next);
    }

    /** Default: false (idle hobbies are opt-in). */
    public static boolean isIdleHobbiesEnabled(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.idleHobbiesEnabledByBot == null) {
                return false;
            }
            Boolean val = wd.idleHobbiesEnabledByBot.get(botId);
            return Boolean.TRUE.equals(val);
        }
    }

    /**
     * Per-hobby enable/disable. Default = enabled. Stored as a "disabled" set so the
     * default behavior (all hobbies allowed) works without a migration step on existing
     * worlds — absent entries mean enabled.
     */
    public static boolean setHobbyEnabled(ServerPlayerEntity bot, String hobbyName, boolean enabled) {
        if (bot == null || hobbyName == null || hobbyName.isBlank()) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        MinecraftServer server = world.getServer();
        if (server == null) return false;
        String botId = botKey(bot);
        if (botId.isBlank()) return false;

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.hobbyDisabledByBot == null) {
                wd.hobbyDisabledByBot = new HashMap<>();
            }
            Map<String, Boolean> perBot = wd.hobbyDisabledByBot.computeIfAbsent(botId, k -> new HashMap<>());
            if (enabled) {
                perBot.remove(hobbyName);
            } else {
                perBot.put(hobbyName, Boolean.TRUE);
            }
        }
        flush();
        return true;
    }

    public static boolean isHobbyEnabled(ServerPlayerEntity bot, String hobbyName) {
        if (bot == null || hobbyName == null || hobbyName.isBlank()) return true;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return true;
        MinecraftServer server = world.getServer();
        if (server == null) return true;
        String botId = botKey(bot);
        if (botId.isBlank()) return true;

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.hobbyDisabledByBot == null) return true;
            Map<String, Boolean> perBot = wd.hobbyDisabledByBot.get(botId);
            if (perBot == null) return true;
            return !Boolean.TRUE.equals(perBot.get(hobbyName));
        }
    }

    /** Returns the set of hobby names currently disabled for this bot (defensive copy). */
    public static java.util.Set<String> getDisabledHobbies(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return java.util.Set.of();
        }
        MinecraftServer server = world.getServer();
        if (server == null) return java.util.Set.of();
        String botId = botKey(bot);
        if (botId.isBlank()) return java.util.Set.of();

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.hobbyDisabledByBot == null) return java.util.Set.of();
            Map<String, Boolean> perBot = wd.hobbyDisabledByBot.get(botId);
            if (perBot == null) return java.util.Set.of();
            java.util.Set<String> out = new java.util.HashSet<>();
            for (Map.Entry<String, Boolean> e : perBot.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) out.add(e.getKey());
            }
            return out;
        }
    }

    public static boolean setAutoHuntStarvingEnabled(ServerPlayerEntity bot, boolean enabled) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoHuntStarvingEnabledByBot == null) {
                wd.autoHuntStarvingEnabledByBot = new HashMap<>();
            }
            wd.autoHuntStarvingEnabledByBot.put(botId, enabled);
        }
        flush();
        return true;
    }

    public static boolean toggleAutoHuntStarvingEnabled(ServerPlayerEntity bot) {
        boolean next = !isAutoHuntStarvingEnabled(bot);
        return setAutoHuntStarvingEnabled(bot, next);
    }

    /** Default: false (auto-hunt while starving is opt-in). */
    public static boolean isAutoHuntStarvingEnabled(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.autoHuntStarvingEnabledByBot == null) {
                return false;
            }
            Boolean val = wd.autoHuntStarvingEnabledByBot.get(botId);
            return Boolean.TRUE.equals(val);
        }
    }

    /** Get navigation mode for a bot. Returns "TELEPORT_DELAY" (fast travel, default) or "WALK". */
    public static String getNavMode(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return "TELEPORT_DELAY";
        }
        MinecraftServer server = world.getServer();
        if (server == null) return "TELEPORT_DELAY";
        String botId = botKey(bot);
        if (botId.isBlank()) return "TELEPORT_DELAY";

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.navModeByBot == null) {
                return "TELEPORT_DELAY";
            }
            return wd.navModeByBot.getOrDefault(botId, "TELEPORT_DELAY");
        }
    }

    /** Set navigation mode for a bot ("WALK" or "TELEPORT_DELAY" / fast travel). */
    public static void setNavMode(ServerPlayerEntity bot, String mode) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) return;
        String botId = botKey(bot);
        if (botId.isBlank()) return;
        String normalized = "WALK".equalsIgnoreCase(mode) ? "WALK" : "TELEPORT_DELAY";

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.navModeByBot == null) {
                wd.navModeByBot = new HashMap<>();
            }
            wd.navModeByBot.put(botId, normalized);
        }
        flush();
    }

    // ------------------------------------------------------------------
    // Remembered water spots (fishing / irrigation), per world, per bot.
    // Score convention matches WaterSpotMemory: higher = better.
    // ------------------------------------------------------------------

    /** Max remembered water spots per bot per world, applied per kind (fishing / irrigation). */
    private static final int WATER_SPOT_CAP = 16;
    /** Forget spots unused for ~14 in-game days. */
    private static final long WATER_SPOT_MAX_AGE_TICKS = 24_000L * 14L;

    /** Remembers a water spot the bot actually used. Persists immediately (caller's thread). */
    public static void recordWaterSpot(ServerPlayerEntity bot, WaterSpotMemory.WaterSpot spot) {
        if (bot == null || spot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) return;
        String botId = botKey(bot);
        if (botId.isBlank()) return;

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.waterSpotsByBot == null) {
                wd.waterSpotsByBot = new HashMap<>();
            }
            List<WaterSpotMemory.WaterSpot> current = fromSaved(wd.waterSpotsByBot.get(botId));
            // spot.lastUsedTick() is "now" for pruning: both callers (FishingSkill,
            // FarmSkill) stamp the spot with the current world time when recording it.
            current = WaterSpotMemory.prune(current, spot.lastUsedTick(), WATER_SPOT_MAX_AGE_TICKS);
            current = WaterSpotMemory.addPerKind(current, spot, WATER_SPOT_CAP);
            wd.waterSpotsByBot.put(botId, toSaved(current));
        }
        flush();
    }

    /** Unmodifiable snapshot of the bot's remembered water spots for its current world. */
    public static List<WaterSpotMemory.WaterSpot> knownWaterSpots(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return List.of();
        }
        MinecraftServer server = world.getServer();
        if (server == null) return List.of();
        String botId = botKey(bot);
        if (botId.isBlank()) return List.of();

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.waterSpotsByBot == null) {
                return List.of();
            }
            return List.copyOf(fromSaved(wd.waterSpotsByBot.get(botId)));
        }
    }

    /** Forgets a remembered water spot (e.g. revalidation found it dried up / built over). */
    public static void forgetWaterSpot(ServerPlayerEntity bot, int x, int y, int z) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) return;
        String botId = botKey(bot);
        if (botId.isBlank()) return;

        WorldData wd = worldData(server, world);
        boolean changed;
        synchronized (LOCK) {
            if (wd.waterSpotsByBot == null) {
                return;
            }
            List<WaterSpotMemory.WaterSpot> current = fromSaved(wd.waterSpotsByBot.get(botId));
            List<WaterSpotMemory.WaterSpot> after = WaterSpotMemory.remove(current, x, y, z);
            changed = after.size() != current.size();
            if (changed) {
                wd.waterSpotsByBot.put(botId, toSaved(after));
            }
        }
        if (changed) {
            flush();
        }
    }

    private static List<WaterSpotMemory.WaterSpot> fromSaved(List<SavedWaterSpot> saved) {
        if (saved == null || saved.isEmpty()) {
            return List.of();
        }
        List<WaterSpotMemory.WaterSpot> out = new ArrayList<>();
        for (SavedWaterSpot s : saved) {
            if (s == null) continue;
            out.add(new WaterSpotMemory.WaterSpot(s.x, s.y, s.z, s.score, s.lastUsedTick,
                    s.kind == null ? WaterSpotMemory.KIND_FISHING : s.kind));
        }
        return out;
    }

    private static List<SavedWaterSpot> toSaved(List<WaterSpotMemory.WaterSpot> spots) {
        List<SavedWaterSpot> out = new ArrayList<>();
        if (spots == null) {
            return out;
        }
        for (WaterSpotMemory.WaterSpot s : spots) {
            if (s == null) continue;
            SavedWaterSpot saved = new SavedWaterSpot();
            saved.x = s.x();
            saved.y = s.y();
            saved.z = s.z();
            saved.score = s.score();
            saved.lastUsedTick = s.lastUsedTick();
            saved.kind = s.kind();
            out.add(saved);
        }
        return out;
    }

    /**
     * Legacy four-arg overload. Creates a base with no owner — i.e. treated as server-owned
     * for permission gating (admin-only edits). Prefer the six-arg overload that stamps the
     * creating player as owner.
     */
    public static boolean addBase(MinecraftServer server, ServerWorld world, String label, BlockPos pos) {
        return addBase(server, world, label, pos, null, null);
    }

    /**
     * Creates a base and stamps the given owner. Owner {@code null}/blank is stored as-is and
     * treated as server-owned by {@link #canEditBase}; pass {@link #SERVER_OWNER_UUID} explicitly
     * to mark a permanent server-owned base (e.g. auto-spawn).
     */
    public static boolean addBase(MinecraftServer server, ServerWorld world, String label, BlockPos pos,
                                  String ownerUuid, String ownerName) {
        if (server == null || world == null || label == null || label.isBlank() || pos == null) {
            return false;
        }
        String normalized = normalizeLabelKey(label);
        String trimmed = label.trim();
        WorldData wd = worldData(server, world);
        int storedRadius;
        synchronized (LOCK) {
            if (wd.basesByLabel == null) {
                wd.basesByLabel = new HashMap<>();
            }
            wd.basesByLabel.put(normalized, new SavedBase(trimmed, SavedPos.from(pos), ownerUuid, ownerName));
            SavedBase saved = wd.basesByLabel.get(normalized);
            storedRadius = saved != null ? saved.radius : 0;
        }
        flush();
        upsertAutoZoneForBase(server, world, trimmed, pos, storedRadius, ownerUuid, ownerName);
        return true;
    }

    /**
     * Replaces the owner on an existing base. Operator-only caller responsibility to gate — this
     * method does not check permissions. Returns false if no base has the given label.
     */
    public static boolean setBaseOwner(MinecraftServer server, ServerWorld world, String label,
                                       String ownerUuid, String ownerName) {
        if (server == null || world == null || label == null || label.isBlank()) {
            return false;
        }
        String normalized = normalizeLabelKey(label);
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.basesByLabel == null) return false;
            SavedBase base = wd.basesByLabel.get(normalized);
            if (base == null) return false;
            base.ownerUuid = ownerUuid;
            base.ownerName = ownerName;
        }
        flush();
        return true;
    }

    /**
     * Permission gate for base edit/rename/resize/delete operations.
     *
     * <p>Rules:
     * <ul>
     *   <li>Operators can edit any base.</li>
     *   <li>Server-owned bases (owner = {@link #SERVER_OWNER_UUID}) are admin-only.</li>
     *   <li>Legacy bases (null/blank owner) are treated the same as server-owned — admin-only —
     *       until an admin explicitly reassigns ownership. Prevents drive-by edits on bases that
     *       existed before ownership was tracked.</li>
     *   <li>A base's stamped owner can edit their own base.</li>
     * </ul>
     *
     * <p>Alliance logic will hook in here in Phase 4.
     */
    public static boolean canEditBase(ServerPlayerEntity player, BaseEntry base) {
        if (player == null) return false;
        if (isOperatorSafe(player)) return true;
        if (base == null) return false;
        String ownerUuid = base.ownerUuid();
        if (ownerUuid == null || ownerUuid.isBlank()) return false; // legacy → admin-only
        if (SERVER_OWNER_UUID.equals(ownerUuid)) return false;
        return ownerUuid.equals(player.getUuid().toString());
    }

    private static boolean isOperatorSafe(ServerPlayerEntity player) {
        try {
            return net.wcfcarolina13.Frens.isOperator(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Creates the auto-spawn base if it hasn't been seeded yet for this world. Idempotent per
     * world: once {@code spawnBaseInitialized} is true, this becomes a no-op even if the admin
     * later deletes the "Spawn" base — matching the user directive that admin deletion should
     * stick across restarts. Call from SERVER_STARTED.
     */
    public static void initializeSpawnBaseIfNeeded(MinecraftServer server, ServerWorld world) {
        if (server == null || world == null) return;
        if (world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) return; // overworld only
        WorldData wd = worldData(server, world);
        boolean needCreate;
        synchronized (LOCK) {
            if (wd.spawnBaseInitialized) return;
            String normalized = normalizeLabelKey(AUTO_SPAWN_BASE_LABEL);
            boolean existsAlready = wd.basesByLabel != null && wd.basesByLabel.containsKey(normalized);
            needCreate = !existsAlready;
            wd.spawnBaseInitialized = true;
        }
        flush();
        if (needCreate) {
            BlockPos spawnPos = BlockPos.ORIGIN;
            try {
                var spawn = world.getSpawnPoint();
                if (spawn != null && spawn.getPos() != null) {
                    spawnPos = spawn.getPos();
                }
            } catch (Throwable ignored) {
                // fall back to origin
            }
            addBase(server, world, AUTO_SPAWN_BASE_LABEL, spawnPos, SERVER_OWNER_UUID, SERVER_OWNER_NAME);
            LOGGER.info("Auto-created server-owned '{}' base at {} for world {}",
                    AUTO_SPAWN_BASE_LABEL, spawnPos.toShortString(), world.getRegistryKey().getValue());
        }
    }

    /**
     * Find a base owned by someone other than {@code requesterUuid} (and not allied with them)
     * whose protection sphere overlaps the proposed {@code (center, radius)}. Used to reject
     * creation or resize attempts that would step on other users' bases.
     *
     * <p>Sphere overlap test: Euclidean distance between centers &lt; (r1 + r2). Operators can
     * still cause overlap — this method does not check operator status; the caller gates.
     *
     * <p>Skip conditions:
     * <ul>
     *   <li>Same base (label match with {@code excludeLabel}) — callers pass the label being
     *       resized so the base doesn't conflict with itself.</li>
     *   <li>Requester owns the conflicting base.</li>
     *   <li>Requester and owner are allied (via {@link PlayerAllianceService#areAllied}).</li>
     * </ul>
     *
     * <p>Legacy and server-owned bases are treated as "another owner" (nobody can overlap them
     * without admin override). That protects the auto-Spawn base from being shadowed by a
     * neighboring player base.
     */
    public static Optional<BaseEntry> findOverlappingBase(MinecraftServer server, ServerWorld world,
                                                          BlockPos center, int radius,
                                                          String requesterUuid, String excludeLabel) {
        if (server == null || world == null || center == null || radius <= 0) {
            return Optional.empty();
        }
        String excludeNorm = excludeLabel != null ? normalizeLabelKey(excludeLabel) : null;
        for (BaseEntry other : listBases(server, world)) {
            if (other == null || other.pos() == null) continue;
            if (excludeNorm != null && normalizeLabelKey(other.label()).equals(excludeNorm)) continue;

            String otherOwner = other.ownerUuid();
            boolean sameOwner = requesterUuid != null && !requesterUuid.isBlank()
                    && requesterUuid.equals(otherOwner);
            if (sameOwner) continue;

            boolean allied = requesterUuid != null && otherOwner != null
                    && !SERVER_OWNER_UUID.equals(otherOwner)
                    && !otherOwner.isBlank()
                    && PlayerAllianceService.areAllied(requesterUuid, otherOwner);
            if (allied) continue;

            int otherRadius = other.radius() > 0 ? other.radius() : DEFAULT_BASE_PROTECTION_RADIUS;
            double sumRadii = (double) radius + (double) otherRadius;
            double distSq = other.pos().getSquaredDistance(center);
            if (distSq < sumRadii * sumRadii) {
                return Optional.of(other);
            }
        }
        return Optional.empty();
    }

    /**
     * Set the protection radius for a saved base. Values &le; 0 reset to default.
     */
    public static boolean setBaseRadius(MinecraftServer server, ServerWorld world, String label, int radius) {
        if (server == null || world == null || label == null || label.isBlank()) {
            return false;
        }
        String normalized = normalizeLabelKey(label);
        WorldData wd = worldData(server, world);
        BlockPos pos;
        int newRadius;
        String displayLabel;
        String ownerUuid;
        String ownerName;
        synchronized (LOCK) {
            if (wd.basesByLabel == null) return false;
            SavedBase base = wd.basesByLabel.get(normalized);
            if (base == null) return false;
            base.radius = Math.max(0, radius);
            pos = base.pos != null ? base.pos.toBlockPos() : null;
            newRadius = base.radius;
            displayLabel = base.label;
            ownerUuid = base.ownerUuid;
            ownerName = base.ownerName;
        }
        flush();
        if (pos != null && displayLabel != null) {
            upsertAutoZoneForBase(server, world, displayLabel, pos, newRadius, ownerUuid, ownerName);
        }
        return true;
    }

    public static Optional<BlockPos> getBaseByLabel(MinecraftServer server, ServerWorld world, String label) {
        if (server == null || world == null || label == null || label.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeLabelKey(label);
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.basesByLabel == null || wd.basesByLabel.isEmpty()) {
                return Optional.empty();
            }
            SavedBase base = wd.basesByLabel.get(normalized);
            if (base == null || base.pos == null) {
                return Optional.empty();
            }
            return Optional.of(base.pos.toBlockPos());
        }
    }

    public static boolean setPreferredHomeBase(ServerPlayerEntity bot, String label) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null || label == null || label.isBlank()) {
            return false;
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return false;
        }
        String normalized = normalizeLabelKey(label);
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.basesByLabel == null || !wd.basesByLabel.containsKey(normalized)) {
                return false;
            }
            if (wd.preferredHomeBaseByBot == null) {
                wd.preferredHomeBaseByBot = new HashMap<>();
            }
            wd.preferredHomeBaseByBot.put(botId, normalized);
        }
        flush();
        return true;
    }

    public static Optional<String> getPreferredHomeBaseLabel(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return Optional.empty();
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return Optional.empty();
        }
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.preferredHomeBaseByBot == null || wd.preferredHomeBaseByBot.isEmpty()
                    || wd.basesByLabel == null || wd.basesByLabel.isEmpty()) {
                return Optional.empty();
            }
            String preferredNorm = wd.preferredHomeBaseByBot.get(botId);
            if (preferredNorm == null || preferredNorm.isBlank()) {
                return Optional.empty();
            }
            SavedBase preferred = wd.basesByLabel.get(preferredNorm);
            if (preferred == null || preferred.label == null || preferred.label.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(preferred.label);
        }
    }

    /** Get the designated home compass name for a bot, or null if not set. */
    public static String getHomeCompassName(ServerPlayerEntity bot) {
        if (bot == null) return null;
        ensureLoaded();
        String botId = botKey(bot);
        if (botId.isBlank()) return null;
        synchronized (LOCK) {
            if (DATA.homeCompassNameByBot == null) {
                DATA.homeCompassNameByBot = new HashMap<>();
            }
            return DATA.homeCompassNameByBot.get(botId);
        }
    }

    /** Set the designated home compass name for a bot. Pass null to clear. */
    public static void setHomeCompassName(ServerPlayerEntity bot, String name) {
        if (bot == null) return;
        String botId = botKey(bot);
        if (botId.isBlank()) return;
        ensureLoaded();
        synchronized (LOCK) {
            if (DATA.homeCompassNameByBot == null) {
                DATA.homeCompassNameByBot = new HashMap<>();
            }
            if (name == null || name.isBlank()) {
                DATA.homeCompassNameByBot.remove(botId);
            } else {
                DATA.homeCompassNameByBot.put(botId, name);
            }
        }
        flush();
    }

    public static boolean removeBase(MinecraftServer server, ServerWorld world, String label) {
        if (server == null || world == null || label == null || label.isBlank()) {
            return false;
        }
        String normalized = normalizeLabelKey(label);
        WorldData wd = worldData(server, world);
        boolean removed = false;
        synchronized (LOCK) {
            if (wd.basesByLabel != null) {
                removed = wd.basesByLabel.remove(normalized) != null;
            }
            if (removed && wd.preferredHomeBaseByBot != null && !wd.preferredHomeBaseByBot.isEmpty()) {
                wd.preferredHomeBaseByBot.entrySet().removeIf(e ->
                        e != null && normalized.equals(e.getValue()));
            }
        }
        if (removed) {
            flush();
            ProtectedZoneService.removeZoneInternal(world, autoZoneLabel(label));
        }
        return removed;
    }

    public static boolean renameBase(MinecraftServer server, ServerWorld world, String oldLabel, String newLabel) {
        if (server == null || world == null) {
            return false;
        }
        if (oldLabel == null || oldLabel.isBlank() || newLabel == null || newLabel.isBlank()) {
            return false;
        }

        String oldNorm = normalizeLabelKey(oldLabel);
        String newTrim = newLabel.trim();
        String newNorm = normalizeLabelKey(newTrim);

        WorldData wd = worldData(server, world);
        boolean changed = false;
        synchronized (LOCK) {
            if (wd.basesByLabel == null || wd.basesByLabel.isEmpty()) {
                return false;
            }

            SavedBase existing = wd.basesByLabel.get(oldNorm);
            if (existing == null || existing.pos == null) {
                return false;
            }

            if (oldNorm.equals(newNorm)) {
                // Same normalized key: just update display label.
                wd.basesByLabel.put(oldNorm, new SavedBase(newTrim, existing.pos));
                changed = true;
            } else {
                if (wd.basesByLabel.containsKey(newNorm)) {
                    // Don't overwrite another base.
                    return false;
                }
                wd.basesByLabel.remove(oldNorm);
                wd.basesByLabel.put(newNorm, new SavedBase(newTrim, existing.pos));
                changed = true;
            }

            if (changed && !oldNorm.equals(newNorm)
                    && wd.preferredHomeBaseByBot != null
                    && !wd.preferredHomeBaseByBot.isEmpty()) {
                for (Map.Entry<String, String> entry : wd.preferredHomeBaseByBot.entrySet()) {
                    if (entry != null && oldNorm.equals(entry.getValue())) {
                        entry.setValue(newNorm);
                    }
                }
            }
        }

        if (changed) {
            flush();
            if (!oldNorm.equals(newNorm)) {
                // Try fast-path rename of the existing auto-zone. If no zone exists yet
                // (e.g. zones weren't loaded when the base was first created), drop the
                // old label silently and upsert under the new label.
                boolean renamed = ProtectedZoneService.renameZoneInternal(world,
                        AUTO_ZONE_LABEL_PREFIX + oldNorm, AUTO_ZONE_LABEL_PREFIX + newNorm);
                if (!renamed) {
                    ProtectedZoneService.removeZoneInternal(world, AUTO_ZONE_LABEL_PREFIX + oldNorm);
                    BlockPos pos;
                    int storedRadius;
                    String displayLabel;
                    String ownerUuid;
                    String ownerName;
                    synchronized (LOCK) {
                        SavedBase moved = wd.basesByLabel.get(newNorm);
                        if (moved == null || moved.pos == null) return true;
                        pos = moved.pos.toBlockPos();
                        storedRadius = moved.radius;
                        displayLabel = moved.label;
                        ownerUuid = moved.ownerUuid;
                        ownerName = moved.ownerName;
                    }
                    upsertAutoZoneForBase(server, world, displayLabel, pos, storedRadius, ownerUuid, ownerName);
                }
            }
        }
        return changed;
    }

    public static List<BaseEntry> listBases(MinecraftServer server, ServerWorld world) {
        if (server == null || world == null) {
            return List.of();
        }
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.basesByLabel == null || wd.basesByLabel.isEmpty()) {
                return List.of();
            }
            List<SavedBase> bases = new ArrayList<>(wd.basesByLabel.values());
            bases.sort(Comparator.comparing(b -> b.label == null ? "" : b.label.toLowerCase(Locale.ROOT)));
            List<BaseEntry> out = new ArrayList<>(bases.size());
            for (SavedBase b : bases) {
                if (b == null || b.pos == null) {
                    continue;
                }
                out.add(new BaseEntry(b.label, b.pos.toBlockPos(),
                        b.radius > 0 ? b.radius : DEFAULT_BASE_PROTECTION_RADIUS,
                        b.ownerUuid, b.ownerName));
            }
            return out;
        }
    }

    public static Optional<BlockPos> findNearestBase(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return Optional.empty();
        }
        WorldData wd = worldData(server, world);
        Vec3d origin = new Vec3d(bot.getX(), bot.getY(), bot.getZ());

        SavedBase best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        synchronized (LOCK) {
            if (wd.basesByLabel == null || wd.basesByLabel.isEmpty()) {
                return Optional.empty();
            }
            for (SavedBase base : wd.basesByLabel.values()) {
                if (base == null || base.pos == null) {
                    continue;
                }
                Vec3d v = Vec3d.ofCenter(base.pos.toBlockPos());
                double sq = origin.squaredDistanceTo(v);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = base;
                }
            }
        }
        return best != null ? Optional.of(best.pos.toBlockPos()) : Optional.empty();
    }

    public static Optional<BaseEntry> findBaseNearPosition(MinecraftServer server, ServerWorld world, BlockPos pos) {
        if (server == null || world == null || pos == null) return Optional.empty();
        List<BaseEntry> bases = listBases(server, world);
        for (BaseEntry base : bases) {
            if (base == null || base.pos() == null) continue;
            int radius = base.radius() > 0 ? base.radius() : DEFAULT_BASE_PROTECTION_RADIUS;
            if (base.pos().isWithinDistance(pos, radius)) {
                return Optional.of(base);
            }
        }
        return Optional.empty();
    }

    /**
     * Chooses the best "home" destination for the bot based on distance.
     *
     * <p>Policy:
     * - consider last slept location (if present)
     * - consider nearest saved base (if present)
     * - choose whichever is closer to the bot
     */
    public static Optional<BlockPos> resolveHomeTarget(ServerPlayerEntity bot) {
        if (bot == null) {
            return Optional.empty();
        }
        Optional<BlockPos> preferredHome = resolvePreferredHomeBase(bot);
        if (preferredHome.isPresent()) {
            return preferredHome;
        }
        Optional<BlockPos> slept = getLastSleep(bot);
        Optional<BlockPos> base = findNearestBase(bot);

        if (slept.isEmpty()) {
            return base;
        }
        if (base.isEmpty()) {
            return slept;
        }

        Vec3d origin = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double sleptSq = origin.squaredDistanceTo(Vec3d.ofCenter(slept.get()));
        double baseSq = origin.squaredDistanceTo(Vec3d.ofCenter(base.get()));
        return sleptSq <= baseSq ? slept : base;
    }

    /**
     * Intent-aware resolution. See {@link ReturnIntent}.
     *
     * <p>Rationale for SUNSET_BED existing as a separate intent: the legacy
     * {@link #resolveHomeTarget(ServerPlayerEntity)} returns the preferred home whenever one is set,
     * ignoring a recently-used bed. That sends the bot on long cross-map walks at dusk when it
     * could sleep at a closer, freshly-used bed. SUNSET_BED flips the priority: validated lastSleep
     * competes on distance against preferred/nearest, and the closest candidate wins.
     */
    public static Optional<BlockPos> resolveHomeTarget(ServerPlayerEntity bot, ReturnIntent intent) {
        if (bot == null || intent == null) {
            return Optional.empty();
        }
        return switch (intent) {
            case SUNSET_BED -> resolveSunsetBed(bot);
            case COMMANDER_SIDE -> resolveCommanderSide(bot);
            case BASE_CENTER_LAZY -> resolveBaseCenterLazy(bot);
            case DEFAULT -> resolveHomeTarget(bot);
        };
    }

    private static Optional<BlockPos> resolveSunsetBed(ServerPlayerEntity bot) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        Optional<BlockPos> sleptOpt = getLastSleep(bot);
        Optional<BlockPos> preferredOpt = resolvePreferredHomeBase(bot);
        Optional<BlockPos> nearestOpt = findNearestBase(bot);

        // Validate lastSleep: chunk loaded AND bed block still present. Unloaded chunk → trust.
        boolean sleepChunkLoaded = false;
        boolean sleepValidated = false;
        if (sleptOpt.isPresent()) {
            BlockPos pos = sleptOpt.get();
            sleepChunkLoaded = world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
            if (sleepChunkLoaded) {
                sleepValidated = world.getBlockState(pos).isIn(BlockTags.BEDS);
                if (!sleepValidated) {
                    LOGGER.debug("SUNSET_BED: lastSleep at {} is no longer a bed; falling through", pos.toShortString());
                }
            } else {
                sleepValidated = true; // trust remote record
            }
        }

        Vec3d origin = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double sleepDistSq = sleepValidated && sleptOpt.isPresent()
                ? origin.squaredDistanceTo(Vec3d.ofCenter(sleptOpt.get()))
                : Double.POSITIVE_INFINITY;
        double preferredDistSq = preferredOpt.isPresent()
                ? origin.squaredDistanceTo(Vec3d.ofCenter(preferredOpt.get()))
                : Double.POSITIVE_INFINITY;
        double nearestDistSq = nearestOpt.isPresent()
                ? origin.squaredDistanceTo(Vec3d.ofCenter(nearestOpt.get()))
                : Double.POSITIVE_INFINITY;

        // Unloaded-chunk lastSleep: only wins if not wildly farther than loaded candidates.
        // "Wildly farther" = 2× distance (4× distSq) of the closest loaded candidate.
        if (sleepValidated && !sleepChunkLoaded) {
            double minLoaded = Math.min(preferredDistSq, nearestDistSq);
            if (Double.isFinite(minLoaded) && sleepDistSq > minLoaded * 4.0) {
                LOGGER.debug("SUNSET_BED: unloaded-chunk lastSleep too far vs loaded alternatives; skipping");
                sleepDistSq = Double.POSITIVE_INFINITY;
            }
        }

        double best = Math.min(sleepDistSq, Math.min(preferredDistSq, nearestDistSq));
        if (!Double.isFinite(best)) {
            return Optional.empty();
        }
        if (best == sleepDistSq) return sleptOpt;
        if (best == preferredDistSq) return preferredOpt;
        return nearestOpt;
    }

    private static Optional<BlockPos> resolveCommanderSide(ServerPlayerEntity bot) {
        // Phase-1 placeholder: commander lookup will be wired when first caller migrates.
        // Semantically this should return commander's walkable position if present in same dim,
        // and fall through otherwise. For now we fall through directly to BASE_CENTER_LAZY.
        return resolveBaseCenterLazy(bot);
    }

    private static Optional<BlockPos> resolveBaseCenterLazy(ServerPlayerEntity bot) {
        // Phase-1 implementation: preferred → lastSleep → nearestBase.
        // Walkable-surface snap will be added when the first caller migrates and we
        // can verify the snap doesn't regress existing pathfinding behavior.
        Optional<BlockPos> preferred = resolvePreferredHomeBase(bot);
        if (preferred.isPresent()) return preferred;
        Optional<BlockPos> slept = getLastSleep(bot);
        if (slept.isPresent()) return slept;
        return findNearestBase(bot);
    }

    /**
     * Returns the preferred-home base as a full {@link BaseEntry} (includes the saved radius).
     * Used by callers that size a scan/operation to the base's declared extent, e.g. the fishing
     * chest-offload fallback that scans for chests inside the home sphere. Returns empty when the
     * bot has no preferred home (nothing to size against).
     */
    public static Optional<BaseEntry> resolvePreferredHomeBaseEntry(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return Optional.empty();
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return Optional.empty();
        }
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.preferredHomeBaseByBot == null || wd.preferredHomeBaseByBot.isEmpty()
                    || wd.basesByLabel == null || wd.basesByLabel.isEmpty()) {
                return Optional.empty();
            }
            String preferredNorm = wd.preferredHomeBaseByBot.get(botId);
            if (preferredNorm == null || preferredNorm.isBlank()) {
                return Optional.empty();
            }
            SavedBase preferred = wd.basesByLabel.get(preferredNorm);
            if (preferred == null || preferred.pos == null) {
                return Optional.empty();
            }
            int r = preferred.radius > 0 ? preferred.radius : DEFAULT_BASE_PROTECTION_RADIUS;
            return Optional.of(new BaseEntry(preferred.label, preferred.pos.toBlockPos(), r,
                    preferred.ownerUuid, preferred.ownerName));
        }
    }

    /**
     * Returns the per-world maximum base radius an operator is allowed to set. This is the
     * admin-tuneable ceiling enforced by the "Set Radius" payload handler. Falls back to
     * {@link #DEFAULT_MAX_BASE_RADIUS} when the world has never had it configured.
     */
    public static int getMaxBaseRadius(MinecraftServer server, ServerWorld world) {
        if (server == null || world == null) {
            return DEFAULT_MAX_BASE_RADIUS;
        }
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            return wd.maxBaseRadius > 0 ? wd.maxBaseRadius : DEFAULT_MAX_BASE_RADIUS;
        }
    }

    /**
     * Sets the per-world maximum base radius. Clamped to [1, {@link #HARD_MAX_BASE_RADIUS_LIMIT}].
     * Does not retroactively shrink existing bases that were saved with a larger radius — those
     * keep their saved value until the owner/admin adjusts them individually. Returns the clamped
     * value that was stored.
     */
    public static int setMaxBaseRadius(MinecraftServer server, ServerWorld world, int value) {
        if (server == null || world == null) {
            return DEFAULT_MAX_BASE_RADIUS;
        }
        int clamped = Math.max(1, Math.min(value, HARD_MAX_BASE_RADIUS_LIMIT));
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            wd.maxBaseRadius = clamped;
        }
        flush();
        return clamped;
    }

    public static Optional<BlockPos> resolvePreferredHomeBase(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return Optional.empty();
        }
        String botId = botKey(bot);
        if (botId.isBlank()) {
            return Optional.empty();
        }

        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.preferredHomeBaseByBot == null || wd.preferredHomeBaseByBot.isEmpty()
                    || wd.basesByLabel == null || wd.basesByLabel.isEmpty()) {
                return Optional.empty();
            }
            String preferredNorm = wd.preferredHomeBaseByBot.get(botId);
            if (preferredNorm == null || preferredNorm.isBlank()) {
                return Optional.empty();
            }
            SavedBase preferred = wd.basesByLabel.get(preferredNorm);
            if (preferred == null || preferred.pos == null) {
                return Optional.empty();
            }
            return Optional.of(preferred.pos.toBlockPos());
        }
    }

    private static final class RootData {
        Map<String, WorldData> worlds = new HashMap<>();
        Map<String, String> homeCompassNameByBot = new HashMap<>();
    }

    private static final class WorldData {
        Map<String, SavedSleep> lastSleepByBot = new HashMap<>();
        Map<String, Boolean> autoReturnAtSunsetByBot = new HashMap<>();
        Map<String, Boolean> tacticalShelterEnabledByBot = new HashMap<>();
        Map<String, Boolean> autoReturnSelfSufficientFallbackByBot = new HashMap<>();
        Map<String, Boolean> autoReturnPreferLastBedAtSunsetByBot = new HashMap<>();
        Map<String, Boolean> autoReturnGuardPatrolEligibleByBot = new HashMap<>();
        Map<String, Boolean> autoReturnSkipPermissionByBot = new HashMap<>();
        Map<String, Boolean> idleHobbiesEnabledByBot = new HashMap<>();
        // Per-bot, per-hobby disabled list. Absent or false = enabled. Null map = all enabled.
        Map<String, Map<String, Boolean>> hobbyDisabledByBot = new HashMap<>();
        Map<String, Boolean> autoHuntStarvingEnabledByBot = new HashMap<>();
        Map<String, Boolean> attackNamedMobsByBot = new HashMap<>();
        Map<String, SavedBase> basesByLabel = new HashMap<>();
        Map<String, String> preferredHomeBaseByBot = new HashMap<>();
        Map<String, String> navModeByBot = new HashMap<>();
        // Remembered water spots (fishing / irrigation) per bot. Null on legacy JSON.
        Map<String, List<SavedWaterSpot>> waterSpotsByBot = new HashMap<>();
        int maxBaseRadius; // 0 = use DEFAULT_MAX_BASE_RADIUS; Gson defaults missing field to 0
        boolean spawnBaseInitialized; // seeded by initializeSpawnBaseIfNeeded; sticky across restarts
    }

    private static final class SavedBase {
        final String label;
        final SavedPos pos;
        int radius; // 0 = use DEFAULT_BASE_PROTECTION_RADIUS; Gson defaults missing field to 0
        // Owner identity. Null/empty on legacy bases loaded from pre-Phase-3 JSON; treated as
        // server-owned (admin-only) for permission gating so unclaimed bases can't be edited by
        // arbitrary players. Use SERVER_OWNER_UUID for explicit server ownership.
        String ownerUuid;
        String ownerName;

        private SavedBase(String label, SavedPos pos) {
            this.label = label;
            this.pos = pos;
        }

        private SavedBase(String label, SavedPos pos, String ownerUuid, String ownerName) {
            this.label = label;
            this.pos = pos;
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
        }

        @Override
        public String toString() {
            return "SavedBase{" + label + " @ " + pos + " owner=" + ownerName + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SavedBase that)) return false;
            return Objects.equals(label, that.label) && Objects.equals(pos, that.pos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, pos);
        }
    }

    private static final class SavedPos {
        int x;
        int y;
        int z;

        private static SavedPos from(BlockPos pos) {
            SavedPos p = new SavedPos();
            p.x = pos.getX();
            p.y = pos.getY();
            p.z = pos.getZ();
            return p;
        }

        private BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SavedPos savedPos)) return false;
            return x == savedPos.x && y == savedPos.y && z == savedPos.z;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }
    }

    private static final class SavedWaterSpot {
        int x;
        int y;
        int z;
        double score; // higher = better (see WaterSpotMemory)
        long lastUsedTick;
        String kind;
    }

    private static final class SavedSleep {
        int x;
        int y;
        int z;
        long lastSleepMs;

        private static SavedSleep from(BlockPos pos) {
            SavedSleep sleep = new SavedSleep();
            sleep.x = pos.getX();
            sleep.y = pos.getY();
            sleep.z = pos.getZ();
            sleep.lastSleepMs = System.currentTimeMillis();
            return sleep;
        }

        private BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }
}
