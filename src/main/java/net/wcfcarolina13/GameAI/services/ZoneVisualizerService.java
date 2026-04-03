package net.wcfcarolina13.GameAI.services;

import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles server-side particle visualization for saved protected zones.
 * Players can toggle viewing individual zones; particles are sent every 20 ticks.
 */
public final class ZoneVisualizerService {

    private static final int TICK_INTERVAL = 20;
    private static final int MAX_PARTICLES_PER_ZONE = 2000;
    private static final int Y_CLAMP_RANGE = 64;
    private static final int XZ_CLAMP_RANGE = 128;
    private static final int CYAN_COLOR = 0x00CCCC;

    /** zone label -> set of player UUIDs currently viewing that zone */
    private static final Map<String, Set<UUID>> viewingPlayers = new ConcurrentHashMap<>();

    private static int tickCounter = 0;

    private ZoneVisualizerService() {}

    public static void startViewing(String label, UUID playerUuid) {
        viewingPlayers.computeIfAbsent(label, k -> ConcurrentHashMap.newKeySet()).add(playerUuid);
    }

    public static void stopViewing(String label, UUID playerUuid) {
        Set<UUID> viewers = viewingPlayers.get(label);
        if (viewers != null) {
            viewers.remove(playerUuid);
            if (viewers.isEmpty()) viewingPlayers.remove(label);
        }
    }

    public static void renameZone(String oldLabel, String newLabel) {
        Set<UUID> viewers = viewingPlayers.remove(oldLabel);
        if (viewers != null && !viewers.isEmpty()) {
            viewingPlayers.put(newLabel, viewers);
        }
    }

    public static void removeZone(String label) {
        viewingPlayers.remove(label);
    }

    public static void onPlayerDisconnect(UUID playerUuid) {
        for (Map.Entry<String, Set<UUID>> entry : viewingPlayers.entrySet()) {
            entry.getValue().remove(playerUuid);
        }
        viewingPlayers.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public static boolean isViewing(String label, UUID playerUuid) {
        Set<UUID> viewers = viewingPlayers.get(label);
        return viewers != null && viewers.contains(playerUuid);
    }

    public static void onServerTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter < TICK_INTERVAL) return;
        tickCounter = 0;

        if (viewingPlayers.isEmpty()) return;

        for (Map.Entry<String, Set<UUID>> entry : viewingPlayers.entrySet()) {
            String label = entry.getKey();
            Set<UUID> viewers = entry.getValue();
            if (viewers.isEmpty()) continue;

            for (ServerWorld world : server.getWorlds()) {
                ProtectedZoneService.ProtectedZone zone = findZoneByLabel(world, label);
                if (zone == null) continue;

                for (UUID viewerUuid : viewers) {
                    ServerPlayerEntity viewer = server.getPlayerManager().getPlayer(viewerUuid);
                    if (viewer == null) continue;
                    ServerWorld viewerWorld = viewer.getCommandSource().getWorld();
                    if (viewerWorld != world) continue;
                    spawnEdgeParticles(world, zone, viewer);
                }
                break;
            }
        }
    }

    private static ProtectedZoneService.ProtectedZone findZoneByLabel(ServerWorld world, String label) {
        for (ProtectedZoneService.ProtectedZone z : ProtectedZoneService.listZones(world)) {
            if (label.equals(z.getLabel())) return z;
        }
        return null;
    }

    private static void spawnEdgeParticles(ServerWorld world, ProtectedZoneService.ProtectedZone zone,
                                           ServerPlayerEntity viewer) {
        BlockPos min = zone.getMinCorner();
        BlockPos max = zone.getMaxCorner();
        int viewerY = viewer.getBlockY();
        int viewerX = viewer.getBlockX();
        int viewerZ = viewer.getBlockZ();

        int visMinY = Math.max(min.getY(), viewerY - Y_CLAMP_RANGE);
        int visMaxY = Math.min(max.getY(), viewerY + Y_CLAMP_RANGE);
        int visMinX = Math.max(min.getX(), viewerX - XZ_CLAMP_RANGE);
        int visMaxX = Math.min(max.getX(), viewerX + XZ_CLAMP_RANGE);
        int visMinZ = Math.max(min.getZ(), viewerZ - XZ_CLAMP_RANGE);
        int visMaxZ = Math.min(max.getZ(), viewerZ + XZ_CLAMP_RANGE);

        // Estimate particles and compute spacing to stay under cap
        int dx = Math.max(0, visMaxX - visMinX + 1);
        int dy = Math.max(0, visMaxY - visMinY + 1);
        int dz = Math.max(0, visMaxZ - visMinZ + 1);
        int estimatedParticles = 4 * dy + 4 * dx + 4 * dz;
        int spacing = 1;
        while (estimatedParticles / spacing > MAX_PARTICLES_PER_ZONE && spacing < 8) {
            spacing *= 2;
        }

        DustParticleEffect particle = new DustParticleEffect(CYAN_COLOR, 1.0f);

        // 4 vertical edges
        int[][] verticalEdges = {
                {min.getX(), min.getZ()}, {min.getX(), max.getZ()},
                {max.getX(), min.getZ()}, {max.getX(), max.getZ()}
        };
        for (int[] edge : verticalEdges) {
            int ex = edge[0]; int ez = edge[1];
            if (ex < visMinX || ex > visMaxX || ez < visMinZ || ez > visMaxZ) continue;
            for (int y = visMinY; y <= visMaxY; y += spacing) {
                world.spawnParticles(particle, ex + 0.5, y + 0.5, ez + 0.5,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
        }

        // 4 X-axis edges
        int[][] xEdges = {
                {min.getY(), min.getZ()}, {min.getY(), max.getZ()},
                {max.getY(), min.getZ()}, {max.getY(), max.getZ()}
        };
        for (int[] edge : xEdges) {
            int ey = edge[0]; int ez = edge[1];
            if (ey < visMinY || ey > visMaxY || ez < visMinZ || ez > visMaxZ) continue;
            for (int x = visMinX; x <= visMaxX; x += spacing) {
                world.spawnParticles(particle, x + 0.5, ey + 0.5, ez + 0.5,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
        }

        // 4 Z-axis edges
        int[][] zEdges = {
                {min.getY(), min.getX()}, {min.getY(), max.getX()},
                {max.getY(), min.getX()}, {max.getY(), max.getX()}
        };
        for (int[] edge : zEdges) {
            int ey = edge[0]; int ex = edge[1];
            if (ey < visMinY || ey > visMaxY || ex < visMinX || ex > visMaxX) continue;
            for (int z = visMinZ; z <= visMaxZ; z += spacing) {
                world.spawnParticles(particle, ex + 0.5, ey + 0.5, z + 0.5,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }
}
