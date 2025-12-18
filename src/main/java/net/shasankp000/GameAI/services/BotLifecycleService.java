package net.shasankp000.GameAI.services;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * Stage-2 refactor: small, focused holder for lifecycle-related shared state that
 * used to live directly on {@code BotEventHandler}.
 *
 * <p>This is intentionally minimal and state-only for now; behavior extraction
 * can follow once the state access is centralized.</p>
 */
public final class BotLifecycleService {

    public record SpawnSnapshot(RegistryKey<World> worldKey, Vec3d position, float yaw, float pitch) {}

    private static UUID primaryBotUuid;
    private static String lastBotName;
    private static SpawnSnapshot lastSpawn;
    private static volatile boolean pendingBotRespawn;

    private BotLifecycleService() {}

    public static UUID getPrimaryBotUuid() {
        return primaryBotUuid;
    }

    public static void setPrimaryBotUuid(UUID uuid) {
        primaryBotUuid = uuid;
    }

    public static String getLastBotName() {
        return lastBotName;
    }

    public static void setLastBotName(String name) {
        lastBotName = name;
    }

    public static boolean isPendingBotRespawn() {
        return pendingBotRespawn;
    }

    public static void setPendingBotRespawn(boolean pending) {
        pendingBotRespawn = pending;
    }

    public static SpawnSnapshot getLastSpawn() {
        return lastSpawn;
    }

    public static void rememberSpawn(ServerWorld world, Vec3d pos, float yaw, float pitch) {
        RegistryKey<World> worldKey = world != null ? world.getRegistryKey() : null;
        lastSpawn = new SpawnSnapshot(worldKey, pos, yaw, pitch);
    }

    public static void clear() {
        primaryBotUuid = null;
        lastBotName = null;
        lastSpawn = null;
        pendingBotRespawn = false;
    }
}

