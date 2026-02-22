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

    private static final Map<String, MountState> STATE = new HashMap<>();
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
        String worldId = mount.getEntityWorld().getRegistryKey().getValue().toString();
        String mountType = EntityType.getId(mount.getType()).toString();
        boolean saddled = mount instanceof MobEntity mob && mob.hasSaddleEquipped();
        float health = mount instanceof LivingEntity living ? living.getHealth() : -1.0f;
        MountState state = new MountState(mount.getUuid(), worldId, mount.getX(), mount.getY(), mount.getZ(),
                mountType, saddled, health, wasMounted);
        STATE.put(alias, state);
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
        MountState state = STATE.get(alias);
        if (state == null) {
            return;
        }
        server.execute(() -> scheduleRestore(server, bot, alias, state));
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
            boolean restored = restoreMount(server, bot, pending.alias(), pending.state(), pending.attempt());
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
        return STATE.get(alias);
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

    private static void scheduleRestore(MinecraftServer server, ServerPlayerEntity bot, String alias, MountState state) {
        if (server == null || bot == null || state == null) {
            return;
        }
        PENDING_RESTORE.put(bot.getUuid(), new PendingRestore(alias, state, 0, server.getTicks()));
    }

    private static boolean restoreMount(MinecraftServer server, ServerPlayerEntity bot, String alias, MountState state, int attempt) {
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
            maybeSecureOnRejoin(bot, entity, alias, state);
            return true;
        }
        if (entity != null) {
            LOGGER.info("Mount restore: found non-mob {} at {}", state.mountType(), pos.toShortString());
            maybeSecureOnRejoin(bot, entity, alias, state);
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
            STATE.put(alias, updated);
            flush();
            LOGGER.warn("Mount restore: matched existing {} near {}", state.mountType(), pos.toShortString());
            maybeSecureOnRejoin(bot, nearby, alias, updated);
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
                        MountState st = GSON.fromJson(GSON.toJson(entry.getValue()), MountState.class);
                        STATE.put(alias, st);
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

    private static void maybeSecureOnRejoin(ServerPlayerEntity bot, Entity mount, String alias, MountState state) {
        if (bot == null || mount == null || state == null || !state.wasMounted()) {
            return;
        }
        RideSyncService.secureMountAfterRejoin(bot, mount);
        MountState updated = new MountState(state.mountUuid(), state.worldId(), state.x(), state.y(), state.z(),
                state.mountType(), state.saddled(), state.health(), false);
        STATE.put(alias, updated);
        flush();
    }

    private static final class PendingRestore {
        private final String alias;
        private final MountState state;
        private int attempt;
        private long nextTick;

        private PendingRestore(String alias, MountState state, int attempt, long nextTick) {
            this.alias = alias;
            this.state = state;
            this.attempt = attempt;
            this.nextTick = nextTick;
        }

        private String alias() {
            return alias;
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
