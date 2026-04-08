package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MountPersistenceService {
    private static final Logger LOGGER = LoggerFactory.getLogger("mount-persistence");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "bot_mount_state.json";

    private static final Map<String, Map<String, MountState>> STATE = new HashMap<>();
    private static boolean loaded = false;
    private static final Map<UUID, PendingRestore> PENDING_RESTORE = new HashMap<>();
    private static final int MAX_RESTORE_ATTEMPTS = 10;
    private static final long RESTORE_RETRY_TICKS = 20L;

    private MountPersistenceService() {}

    public static void recordMount(ServerPlayerEntity bot, Entity mount) {
        if (bot == null || mount == null) {
            return;
        }
        boolean wasMounted = bot.hasVehicle() && bot.getVehicle() == mount;
        recordMount(bot, mount, wasMounted);
    }

    public static void recordMount(ServerPlayerEntity bot, Entity mount, boolean wasMounted) {
        if (bot == null || mount == null || bot.getCommandSource().getServer() == null) {
            return;
        }
        if (mount instanceof MobEntity mob && !mob.isPersistent()) {
            mob.setPersistent();
        }
        ensureLoaded();
        String alias = bot.getName().getString().toLowerCase();
        String saveWorldKey = BotWorldStateService.currentWorldKey(bot.getCommandSource().getServer());
        String worldId = mount.getEntityWorld().getRegistryKey().getValue().toString();
        String mountType = EntityType.getId(mount.getType()).toString();
        boolean saddled = mount instanceof MobEntity mob && mob.hasSaddleEquipped();
        float health = mount instanceof LivingEntity living ? living.getHealth() : -1.0f;
        MountState state = new MountState(mount.getUuid(), worldId, mount.getX(), mount.getY(), mount.getZ(),
                mountType, saddled, health, wasMounted);
        STATE.computeIfAbsent(alias, k -> new HashMap<>()).put(saveWorldKey, state);
        flush();
        LOGGER.info("Mount record: bot={} mount={} type={} pos={} wasMounted={} saddled={} health={}",
                alias,
                mount.getUuid(),
                mountType,
                BlockPos.ofFloored(mount.getX(), mount.getY(), mount.getZ()).toShortString(),
                wasMounted,
                saddled,
                health);
    }

    /**
     * Record a mount with explicit position coordinates (e.g., at destination rather than current pos).
     */
    public static void recordMountAtPosition(ServerPlayerEntity bot, Entity mount,
                                              double x, double y, double z, boolean wasMounted) {
        if (bot == null || mount == null || bot.getCommandSource().getServer() == null) {
            return;
        }
        if (mount instanceof MobEntity mob && !mob.isPersistent()) {
            mob.setPersistent();
        }
        ensureLoaded();
        String alias = bot.getName().getString().toLowerCase();
        String saveWorldKey = BotWorldStateService.currentWorldKey(bot.getCommandSource().getServer());
        String worldId = mount.getEntityWorld().getRegistryKey().getValue().toString();
        String mountType = EntityType.getId(mount.getType()).toString();
        boolean saddled = mount instanceof MobEntity mob && mob.hasSaddleEquipped();
        float health = mount instanceof LivingEntity living ? living.getHealth() : -1.0f;
        MountState state = new MountState(mount.getUuid(), worldId, x, y, z,
                mountType, saddled, health, wasMounted);
        STATE.computeIfAbsent(alias, k -> new HashMap<>()).put(saveWorldKey, state);
        flush();
        LOGGER.info("Mount record (manual pos): bot={} mount={} type={} pos=({},{},{}) wasMounted={}",
                alias, mount.getUuid(), mountType, (int)x, (int)y, (int)z, wasMounted);
    }

    public static void onBotJoin(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return;
        }
        ensureLoaded();
        String alias = bot.getName().getString().toLowerCase();
        String saveWorldKey = BotWorldStateService.currentWorldKey(server);
        Map<String, MountState> worldMap = STATE.get(alias);
        MountState state = worldMap != null ? worldMap.get(saveWorldKey) : null;
        if (state == null && worldMap != null) {
            // Promote legacy entry on first access
            MountState legacy = worldMap.remove("_legacy");
            if (legacy != null) {
                worldMap.put(saveWorldKey, legacy);
                flush();
                state = legacy;
            }
        }
        if (state == null) {
            return;
        }
        final MountState finalState = state;
        server.execute(() -> scheduleRestore(server, bot, alias, saveWorldKey, finalState));
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || PENDING_RESTORE.isEmpty()) {
            return;
        }
        long now = server.getTicks();
        PENDING_RESTORE.entrySet().removeIf(entry -> {
            PendingRestore pending = entry.getValue();
            if (pending == null) {
                return true;
            }
            if (now < pending.nextTick()) {
                return false;
            }
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(entry.getKey());
            if (bot == null || bot.isRemoved()) {
                return true;
            }
            boolean restored = restoreMount(server, bot, pending.alias(), pending.saveWorldKey(), pending.state(), pending.attempt());
            if (restored) {
                return true;
            }
            int nextAttempt = pending.attempt() + 1;
            if (nextAttempt >= MAX_RESTORE_ATTEMPTS) {
                return true;
            }
            pending.attempt = nextAttempt;
            pending.nextTick = now + RESTORE_RETRY_TICKS;
            return false;
        });
    }

    public static MountState getRecordedState(ServerPlayerEntity bot) {
        if (bot == null) {
            return null;
        }
        ensureLoaded();
        String alias = bot.getName().getString().toLowerCase();
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return null;
        }
        String saveWorldKey = BotWorldStateService.currentWorldKey(server);
        Map<String, MountState> worldMap = STATE.get(alias);
        if (worldMap == null) {
            return null;
        }
        MountState state = worldMap.get(saveWorldKey);
        if (state != null) {
            return state;
        }
        // Promote legacy entry to current world on first access
        MountState legacy = worldMap.remove("_legacy");
        if (legacy != null) {
            worldMap.put(saveWorldKey, legacy);
            flush();
            return legacy;
        }
        return null;
    }

    public static Entity findRecordedMount(ServerWorld world, MountState state) {
        if (world == null || state == null) {
            return null;
        }
        BlockPos pos = BlockPos.ofFloored(state.x(), state.y(), state.z());
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getChunkManager().getChunk(chunkX + dx, chunkZ + dz, ChunkStatus.FULL, true);
            }
        }
        return world.getEntity(state.mountUuid());
    }

    private static void scheduleRestore(MinecraftServer server, ServerPlayerEntity bot, String alias, String saveWorldKey, MountState state) {
        if (server == null || bot == null || state == null) {
            return;
        }
        PENDING_RESTORE.put(bot.getUuid(), new PendingRestore(alias, saveWorldKey, state, 0, server.getTicks()));
    }

    private static boolean restoreMount(MinecraftServer server, ServerPlayerEntity bot, String alias, String saveWorldKey, MountState state, int attempt) {
        RegistryKey<net.minecraft.world.World> worldKey =
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of(state.worldId()));
        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            LOGGER.warn("Mount restore: world {} not found", state.worldId());
            return false;
        }
        BlockPos pos = BlockPos.ofFloored(state.x(), state.y(), state.z());
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getChunkManager().getChunk(chunkX + dx, chunkZ + dz, ChunkStatus.FULL, true);
            }
        }
        Entity entity = world.getEntity(state.mountUuid());
        if (entity instanceof MobEntity mob) {
            mob.setPersistent();
            LOGGER.info("Mount restore: found {} at {}", state.mountType(), pos.toShortString());
            maybeSecureOnRejoin(bot, entity, alias, saveWorldKey, state);
            return true;
        }
        if (entity != null) {
            LOGGER.info("Mount restore: found non-mob {} at {}", state.mountType(), pos.toShortString());
            maybeSecureOnRejoin(bot, entity, alias, saveWorldKey, state);
            return true;
        }
        if (!state.wasMounted()) {
            if (attempt + 1 >= MAX_RESTORE_ATTEMPTS) {
                LOGGER.warn("Mount restore: mount {} missing near {}; skipping fuzzy match (was not mounted).",
                        state.mountType(), pos.toShortString());
            }
            return false;
        }
        Identifier typeId = parseTypeId(state.mountType());
        if (typeId == null || !Registries.ENTITY_TYPE.containsId(typeId)) {
            LOGGER.warn("Mount restore: unknown type {} for missing mount", state.mountType());
            return false;
        }
        EntityType<?> type = Registries.ENTITY_TYPE.get(typeId);
        double radius = Math.min(32.0D + (attempt * 12.0D), 96.0D);
        Entity nearby = findNearbyMount(world, type, pos, state.saddled(), radius);
        if (nearby == null && bot != null && bot.getEntityWorld() == world) {
            nearby = findNearbyMount(world, type, bot.getBlockPos(), false, Math.min(radius * 2.0D, 128.0D));
        }
        if (nearby instanceof MobEntity mob) {
            mob.setPersistent();
            MountState updated = new MountState(nearby.getUuid(), state.worldId(), nearby.getX(), nearby.getY(),
                    nearby.getZ(), state.mountType(), state.saddled(), state.health(), state.wasMounted());
            STATE.computeIfAbsent(alias, k -> new HashMap<>()).put(saveWorldKey, updated);
            flush();
            LOGGER.warn("Mount restore: matched existing {} near {}", state.mountType(), pos.toShortString());
            maybeSecureOnRejoin(bot, nearby, alias, saveWorldKey, updated);
            return true;
        }
        if (attempt + 1 >= MAX_RESTORE_ATTEMPTS) {
            LOGGER.warn("Mount restore: mount {} not found near {}; skipping respawn to avoid duplicates",
                    state.mountType(), pos.toShortString());
        }
        return false;
    }

    private static Entity findNearbyMount(ServerWorld world,
                                          EntityType<?> type,
                                          BlockPos pos,
                                          boolean needsSaddle,
                                          double radius) {
        if (world == null || type == null || pos == null) {
            return null;
        }
        Box search = new Box(pos).expand(radius);
        List<Entity> matches = world.getEntitiesByClass(Entity.class, search,
                entity -> entity != null
                        && entity.isAlive()
                        && entity.getType() == type
                        && (!needsSaddle || (entity instanceof MobEntity mob && mob.hasSaddleEquipped())));
        matches.sort((a, b) -> Double.compare(a.squaredDistanceTo(Vec3d.ofCenter(pos)),
                b.squaredDistanceTo(Vec3d.ofCenter(pos))));
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static Identifier parseTypeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Identifier id = Identifier.tryParse(raw);
        if (id != null) {
            return id;
        }
        String trimmed = raw;
        if (raw.startsWith("EntityType{") && raw.endsWith("}")) {
            trimmed = raw.substring("EntityType{".length(), raw.length() - 1);
        }
        return Identifier.tryParse(trimmed);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        Path file = stateFile();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                Map<?, ?> raw = GSON.fromJson(reader, Map.class);
                if (raw != null) {
                    for (Map.Entry<?, ?> entry : raw.entrySet()) {
                        String alias = entry.getKey().toString();
                        Object value = entry.getValue();
                        if (value instanceof Map<?, ?> innerMap) {
                            // Check if this is old format (MountState fields directly)
                            // or new format (worldKey → MountState nested objects)
                            if (innerMap.containsKey("mountUuid") || innerMap.containsKey("worldId")) {
                                // Old format: alias → MountState directly
                                MountState st = GSON.fromJson(GSON.toJson(value), MountState.class);
                                Map<String, MountState> worldMap = new HashMap<>();
                                worldMap.put("_legacy", st);
                                STATE.put(alias, worldMap);
                            } else {
                                // New format: alias → { worldKey → MountState }
                                Map<String, MountState> worldMap = new HashMap<>();
                                for (Map.Entry<?, ?> worldEntry : innerMap.entrySet()) {
                                    String worldKey = worldEntry.getKey().toString();
                                    MountState st = GSON.fromJson(GSON.toJson(worldEntry.getValue()), MountState.class);
                                    worldMap.put(worldKey, st);
                                }
                                STATE.put(alias, worldMap);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to load mount state: {}", e.getMessage());
            }
        }
        loaded = true;
    }

    private static void flush() {
        try {
            Path file = stateFile();
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(STATE, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save mount state: {}", e.getMessage());
        }
    }

    private static Path stateFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("frens").resolve(FILE_NAME);
    }

    public record MountState(UUID mountUuid,
                             String worldId,
                             double x,
                             double y,
                             double z,
                             String mountType,
                             boolean saddled,
                             float health,
                             boolean wasMounted) {}

    private static void maybeSecureOnRejoin(ServerPlayerEntity bot, Entity mount, String alias, String saveWorldKey, MountState state) {
        if (bot == null || mount == null || state == null) {
            return;
        }
        RideSyncService.secureMountAfterRejoin(bot, mount);
        // Always attempt remount when a recorded mount exists. The earlier
        // behavior gated on state.wasMounted() == true, but legacy state
        // files written by the old restore path (which always flipped
        // wasMounted to false after locating the mount) would permanently
        // skip the remount even after a real ride session. The guards
        // inside tryRemountAfterRejoin (bot already has a vehicle, mount has
        // passengers, bot/mount in different worlds) prevent bad outcomes.
        boolean remounted = tryRemountAfterRejoin(bot, mount);
        MountState updated = new MountState(state.mountUuid(), state.worldId(), state.x(), state.y(), state.z(),
                state.mountType(), state.saddled(), state.health(), remounted);
        STATE.computeIfAbsent(alias, k -> new HashMap<>()).put(saveWorldKey, updated);
        flush();
        LOGGER.info("Mount rejoin restore: bot={} mount={} type={} wasMounted={} remounted={}",
                alias, mount.getUuid(), state.mountType(), state.wasMounted(), remounted);
    }

    private static boolean tryRemountAfterRejoin(ServerPlayerEntity bot, Entity mount) {
        if (bot == null || mount == null) {
            LOGGER.info("Mount rejoin skip: null bot or mount");
            return false;
        }
        if (mount.isRemoved()) {
            LOGGER.info("Mount rejoin skip: mount {} is removed", mount.getUuid());
            return false;
        }
        // Skip if the bot or mount is already part of another vehicle stack
        // (e.g. a player grabbed the horse before the restore tick landed).
        if (bot.hasVehicle()) {
            boolean sameMount = bot.getVehicle() == mount;
            LOGGER.info("Mount rejoin skip: bot={} already has vehicle sameMount={}",
                    bot.getName().getString(), sameMount);
            return sameMount;
        }
        if (mount.hasPassengers()) {
            // Log WHO is riding the mount — a phantom passenger from stale
            // save state is a known failure mode and shows up here.
            String passengerInfo = mount.getPassengerList().stream()
                    .map(p -> p.getName().getString() + "(" + p.getUuid() + ")")
                    .reduce((a, b) -> a + "," + b)
                    .orElse("<none>");
            LOGGER.info("Mount rejoin skip: mount {} already has passengers: {}",
                    mount.getUuid(), passengerInfo);
            return false;
        }
        // Mount must be in the bot's current world. Restore across worlds is
        // intentionally skipped — bot would teleport unexpectedly.
        if (bot.getEntityWorld() != mount.getEntityWorld()) {
            LOGGER.info("Mount rejoin skip: bot={} and mount={} in different worlds",
                    bot.getName().getString(), mount.getUuid());
            return false;
        }
        // Teleport the bot onto the mount before calling startRiding so a
        // large bot-mount distance doesn't cause the mount to snap toward the
        // bot (or vice versa) when attaching the passenger relationship.
        double distSq = bot.squaredDistanceTo(mount);
        if (distSq > 9.0D) {
            LOGGER.info("Mount rejoin: bot={} mount={} distance={} — teleporting bot to mount for clean remount",
                    bot.getName().getString(), mount.getUuid(),
                    String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(distSq)));
            bot.refreshPositionAndAngles(mount.getX(), mount.getY(), mount.getZ(),
                    bot.getYaw(), bot.getPitch());
        }
        try {
            boolean ok = bot.startRiding(mount, true, true);
            if (!ok) {
                LOGGER.warn("Mount rejoin: startRiding returned false bot={} mount={} (distSq={})",
                        bot.getName().getString(), mount.getUuid(),
                        String.format(java.util.Locale.ROOT, "%.2f", distSq));
            }
            return ok;
        } catch (Throwable t) {
            LOGGER.warn("Mount rejoin startRiding threw bot={} mount={}: {}",
                    bot.getName().getString(), mount.getUuid(), t.toString());
            return false;
        }
    }

    private static final class PendingRestore {
        private final String alias;
        private final String saveWorldKey;
        private final MountState state;
        private int attempt;
        private long nextTick;

        private PendingRestore(String alias, String saveWorldKey, MountState state, int attempt, long nextTick) {
            this.alias = alias;
            this.saveWorldKey = saveWorldKey;
            this.state = state;
            this.attempt = attempt;
            this.nextTick = nextTick;
        }

        private String alias() {
            return alias;
        }

        private String saveWorldKey() {
            return saveWorldKey;
        }

        private MountState state() {
            return state;
        }

        private int attempt() {
            return attempt;
        }

        private long nextTick() {
            return nextTick;
        }
    }
}
