package net.shasankp000.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
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

/**
 * Persisted "home" data used for returning to base.
 *
 * <p>Stores:
 * <ul>
 *   <li>Last successful sleep position per bot</li>
 *   <li>Saved base locations per world/dimension</li>
 *   <li>Auto-return-at-sunset toggle per bot</li>
 *   <li>Auto-return-at-sunset eligibility for guard/patrol per bot</li>
 *   <li>Idle/ambient hobbies toggle per bot</li>
 *   <li>Auto-hunt-when-starving toggle per bot</li>
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

    public record BaseEntry(String label, BlockPos pos) {}

    private BotHomeService() {}

    private static Path stateFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("ai-player").resolve(FILE_NAME);
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

    private static void flush() {
        synchronized (LOCK) {
            try {
                Path file = stateFile();
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(DATA, writer);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to save bot home data: {}", e.getMessage());
            }
        }
    }

    private static String botKey(ServerPlayerEntity bot) {
        if (bot == null) {
            return "";
        }
        return bot.getName().getString().toLowerCase(Locale.ROOT);
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
            wd.lastSleepByBot.put(botId, SavedPos.from(bedPos));
        }
        flush();
    }

    public static Optional<BlockPos> getLastSleep(ServerPlayerEntity bot) {
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
            if (wd.lastSleepByBot == null) {
                return Optional.empty();
            }
            SavedPos pos = wd.lastSleepByBot.get(botId);
            return pos != null ? Optional.of(pos.toBlockPos()) : Optional.empty();
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
     * Default: true (preserves the historical behavior where sunset automation did not
     * differentiate modes).
     */
    public static boolean isAutoReturnGuardPatrolEligible(ServerPlayerEntity bot) {
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
            if (wd.autoReturnGuardPatrolEligibleByBot == null) {
                return true;
            }
            Boolean val = wd.autoReturnGuardPatrolEligibleByBot.get(botId);
            return val == null ? true : Boolean.TRUE.equals(val);
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

    public static boolean addBase(MinecraftServer server, ServerWorld world, String label, BlockPos pos) {
        if (server == null || world == null || label == null || label.isBlank() || pos == null) {
            return false;
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        WorldData wd = worldData(server, world);
        synchronized (LOCK) {
            if (wd.basesByLabel == null) {
                wd.basesByLabel = new HashMap<>();
            }
            wd.basesByLabel.put(normalized, new SavedBase(label.trim(), SavedPos.from(pos)));
        }
        flush();
        return true;
    }

    public static boolean removeBase(MinecraftServer server, ServerWorld world, String label) {
        if (server == null || world == null || label == null || label.isBlank()) {
            return false;
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        WorldData wd = worldData(server, world);
        boolean removed = false;
        synchronized (LOCK) {
            if (wd.basesByLabel != null) {
                removed = wd.basesByLabel.remove(normalized) != null;
            }
        }
        if (removed) {
            flush();
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

        String oldNorm = oldLabel.trim().toLowerCase(Locale.ROOT);
        String newTrim = newLabel.trim();
        String newNorm = newTrim.toLowerCase(Locale.ROOT);

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
        }

        if (changed) {
            flush();
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
                out.add(new BaseEntry(b.label, b.pos.toBlockPos()));
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

    private static final class RootData {
        Map<String, WorldData> worlds = new HashMap<>();
    }

    private static final class WorldData {
        Map<String, SavedPos> lastSleepByBot = new HashMap<>();
        Map<String, Boolean> autoReturnAtSunsetByBot = new HashMap<>();
        Map<String, Boolean> autoReturnPreferLastBedAtSunsetByBot = new HashMap<>();
        Map<String, Boolean> autoReturnGuardPatrolEligibleByBot = new HashMap<>();
        Map<String, Boolean> idleHobbiesEnabledByBot = new HashMap<>();
        Map<String, Boolean> autoHuntStarvingEnabledByBot = new HashMap<>();
        Map<String, SavedBase> basesByLabel = new HashMap<>();
    }

    private static final class SavedBase {
        final String label;
        final SavedPos pos;

        private SavedBase(String label, SavedPos pos) {
            this.label = label;
            this.pos = pos;
        }

        @Override
        public String toString() {
            return "SavedBase{" + label + " @ " + pos + "}";
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
}
