package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.construction.FortificationPersistenceService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Unified protected-zone query that aggregates three zone sources:
 * <ol>
 *   <li>Admin {@link ProtectedZoneService} zones (AABB regions)</li>
 *   <li>Saved bases with per-base configurable radius ({@link BotHomeService})</li>
 *   <li>Fortification convex hulls with configurable buffer ({@link FortificationPersistenceService})</li>
 *   <li>Mapped villages saved as shared no-go convex hulls ({@link MappedVillageService})</li>
 * </ol>
 *
 * <p>Skills call {@link #isProtected(ServerWorld, BlockPos, UUID)} to decide whether a
 * block/entity position should be left alone (no woodcutting, no hunting of passive mobs, etc.).
 */
public final class CompanionSafeZoneService {

    private static final Logger LOGGER = LoggerFactory.getLogger("companion-safe-zone");

    /** Default buffer radius outside fortification wall hull (blocks). */
    public static final int DEFAULT_FORT_BUFFER_RADIUS = 8;

    private static volatile int fortBufferRadius = DEFAULT_FORT_BUFFER_RADIUS;

    private CompanionSafeZoneService() {}

    public static void setFortBufferRadius(int radius) {
        fortBufferRadius = Math.max(0, radius);
    }

    public static int getFortBufferRadius() {
        return fortBufferRadius;
    }

    /**
     * Check if a position is inside any protected zone.
     *
     * @param world    the server world
     * @param pos      block position to check
     * @param botOwner owner UUID for {@link ProtectedZoneService} access checks (nullable)
     * @return true if the position is protected and should not be disturbed
     */
    public static boolean isProtected(ServerWorld world, BlockPos pos, @Nullable UUID botOwner) {
        if (world == null || pos == null) return false;

        // 1. Admin protected zones
        if (isInExplicitProtectedZone(world, pos, botOwner)) {
            return true;
        }

        // 2. Natural beehives / bee nests and their nearby tree volume
        if (isNearRegisteredBeehive(world, pos)) {
            return true;
        }

        // 3. Saved bases with per-base radius
        if (isNearSavedBase(world, pos)) {
            return true;
        }

        // 4. Fortification hulls with buffer
        if (isInsideFortificationZone(world, pos)) {
            return true;
        }

        // 5. Mapped villages
        if (MappedVillageService.isInsideMappedVillage(world, pos)) {
            return true;
        }

        return false;
    }

    public static boolean isInExplicitProtectedZone(ServerWorld world, BlockPos pos, @Nullable UUID botOwner) {
        if (world == null || pos == null) return false;
        return ProtectedZoneService.isProtected(pos, world, botOwner);
    }

    public static boolean isNearRegisteredBeehive(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        return BotBeehiveRegistryService.isProtected(world, pos);
    }

    public static boolean isNearSavedBase(ServerWorld world, BlockPos pos) {
        MinecraftServer server = world.getServer();
        if (server == null) return false;

        for (BotHomeService.BaseEntry base : BotHomeService.listBases(server, world)) {
            if (base.pos() == null) continue;
            int radius = base.radius();
            double rSq = (double) radius * radius;
            if (base.pos().getSquaredDistance(pos) <= rSq) {
                return true;
            }
        }
        return false;
    }

    public static boolean isInsideFortificationZone(ServerWorld world, BlockPos pos) {
        MinecraftServer server = world.getServer();
        if (server == null) return false;

        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        List<FortificationPersistenceService.SavedFortification> forts =
                FortificationPersistenceService.listForWorld(server, worldKey);

        if (forts.isEmpty()) return false;

        int px = pos.getX();
        int pz = pos.getZ();
        int buffer = fortBufferRadius;

        for (FortificationPersistenceService.SavedFortification fort : forts) {
            List<VillageFortificationLayoutService.WallPoint> hull = fort.getHullWallPoints();
            if (hull.size() < 3) continue;

            // Inside the hull itself
            if (VillageFortificationLayoutService.pointInConvexHull(hull, px, pz)) {
                return true;
            }

            // Inside the buffer zone outside the hull.
            // Note: expandHull and pointInConvexHull both assume CCW winding order,
            // which is guaranteed by the monotone-chain algorithm in the layout service.
            if (buffer > 0) {
                List<VillageFortificationLayoutService.WallPoint> expanded =
                        VillageFortificationLayoutService.expandHull(hull, buffer);
                if (VillageFortificationLayoutService.pointInConvexHull(expanded, px, pz)) {
                    return true;
                }
            }
        }
        return false;
    }
}
