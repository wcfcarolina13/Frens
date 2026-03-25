package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.wcfcarolina13.GameAI.services.MappedVillageService.MappedVillage;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;
import net.wcfcarolina13.GameAI.services.navigation.VoxelJunctionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Geometry helper for getting a bot outside a mapped village hull.
 */
public final class MappedVillageNavigationService {

    private static final Logger LOGGER = LoggerFactory.getLogger("mapped-village-navigation");
    private static final int[] TANGENT_OFFSETS = {0, 4, -4, 8, -8};
    private static final double EPSILON = 1.0e-6;

    private MappedVillageNavigationService() {
    }

    public record EscapePlan(String villageName,
                             BlockPos nearestEdgePoint,
                             BlockPos exitTarget,
                             List<BlockPos> candidateTargets,
                             int clearance) {
    }

    private record EdgeProjection(double pointX,
                                  double pointZ,
                                  double tangentX,
                                  double tangentZ,
                                  double distanceSq) {
    }

    public static Optional<EscapePlan> planExit(ServerWorld world, BlockPos origin, int clearance) {
        if (world == null || origin == null) {
            return Optional.empty();
        }

        Optional<MappedVillage> villageOpt = MappedVillageService.getVillageAt(world, origin);
        if (villageOpt.isEmpty()) {
            return Optional.empty();
        }

        MappedVillage village = villageOpt.get();
        List<VillageFortificationLayoutService.WallPoint> hull = village.getHullWallPoints();
        if (hull.size() < 2) {
            return Optional.empty();
        }

        EdgeProjection projection = nearestEdgeProjection(hull, origin.getX(), origin.getZ());
        if (projection == null) {
            return Optional.empty();
        }

        double[] outward = outwardUnitVector(village, origin, projection);
        if (outward == null) {
            return Optional.empty();
        }

        BlockPos nearestEdgePoint = resolveSurfaceFeet(world, projection.pointX(), projection.pointZ(), 1);
        if (nearestEdgePoint == null) {
            nearestEdgePoint = fallbackSurfaceFeet(world, projection.pointX(), projection.pointZ(), origin.getY());
        }

        Set<BlockPos> validTargets = new LinkedHashSet<>();
        BlockPos primaryTarget = null;
        for (int offset : TANGENT_OFFSETS) {
            double candidateX = projection.pointX() + projection.tangentX() * offset + outward[0] * clearance;
            double candidateZ = projection.pointZ() + projection.tangentZ() * offset + outward[1] * clearance;

            BlockPos resolved = resolveSurfaceFeet(world, candidateX, candidateZ, 2);
            if (primaryTarget == null) {
                primaryTarget = resolved != null
                        ? resolved
                        : fallbackSurfaceFeet(world, candidateX, candidateZ, origin.getY());
            }
            if (resolved == null) {
                LOGGER.info("Mapped village exit plan: rejecting candidate for '{}' offset={} xz=({}, {}) (reason=non-standable-or-unloaded)",
                        village.getName(), offset, Math.round(candidateX), Math.round(candidateZ));
                continue;
            }
            if (contains(village, resolved)) {
                LOGGER.info("Mapped village exit plan: rejecting candidate {} for '{}' (reason=still-inside-hull)",
                        resolved.toShortString(), village.getName());
                continue;
            }
            if (CompanionSafeZoneService.isProtected(world, resolved, null)) {
                LOGGER.info("Mapped village exit plan: rejecting candidate {} for '{}' (reason=still-protected)",
                        resolved.toShortString(), village.getName());
                continue;
            }
            LOGGER.info("Mapped village exit plan: accepted candidate {} for '{}' offset={}",
                    resolved.toShortString(), village.getName(), offset);
            validTargets.add(resolved.toImmutable());
        }

        if (primaryTarget == null) {
            primaryTarget = nearestEdgePoint;
        }

        return Optional.of(new EscapePlan(
                village.getName(),
                nearestEdgePoint != null ? nearestEdgePoint.toImmutable() : origin.toImmutable(),
                primaryTarget.toImmutable(),
                List.copyOf(validTargets),
                Math.max(1, clearance)
        ));
    }

    public static boolean isOutsideTarget(ServerWorld world, BlockPos pos, String villageName) {
        if (world == null || pos == null || villageName == null || villageName.isBlank()) {
            return false;
        }
        if (world.getServer() != null) {
            Optional<MappedVillage> target = MappedVillageService.load(world.getServer(), world, villageName);
            if (target.isPresent()) {
                return !contains(target.get(), pos);
            }
        }
        return MappedVillageService.getVillageAt(world, pos)
                .map(current -> !current.getName().equalsIgnoreCase(villageName))
                .orElse(true);
    }

    private static boolean contains(MappedVillage village, BlockPos pos) {
        if (village == null || pos == null) {
            return false;
        }
        List<VillageFortificationLayoutService.WallPoint> hull = village.getHullWallPoints();
        return hull.size() >= 3 && VillageFortificationLayoutService.pointInConvexHull(hull, pos.getX(), pos.getZ());
    }

    private static EdgeProjection nearestEdgeProjection(List<VillageFortificationLayoutService.WallPoint> hull,
                                                        double px,
                                                        double pz) {
        if (hull == null || hull.size() < 2) {
            return null;
        }

        EdgeProjection best = null;
        int n = hull.size();
        for (int i = 0; i < n; i++) {
            VillageFortificationLayoutService.WallPoint a = hull.get(i);
            VillageFortificationLayoutService.WallPoint b = hull.get((i + 1) % n);
            if (a == null || b == null) {
                continue;
            }

            double ax = a.x();
            double az = a.z();
            double dx = b.x() - ax;
            double dz = b.z() - az;
            double lenSq = dx * dx + dz * dz;
            double t = lenSq <= EPSILON ? 0.0 : clamp(((px - ax) * dx + (pz - az) * dz) / lenSq, 0.0, 1.0);
            double qx = ax + dx * t;
            double qz = az + dz * t;
            double tangentLen = Math.sqrt(Math.max(lenSq, EPSILON));
            double tangentX = tangentLen <= EPSILON ? 1.0 : dx / tangentLen;
            double tangentZ = tangentLen <= EPSILON ? 0.0 : dz / tangentLen;
            double distSq = squaredDistance(px, pz, qx, qz);

            if (best == null || distSq < best.distanceSq()) {
                best = new EdgeProjection(qx, qz, tangentX, tangentZ, distSq);
            }
        }
        return best;
    }

    private static double[] outwardUnitVector(MappedVillage village, BlockPos origin, EdgeProjection projection) {
        BlockPos center = village.getCenter();
        double ox = projection.pointX() - center.getX();
        double oz = projection.pointZ() - center.getZ();
        double len = Math.sqrt(ox * ox + oz * oz);

        if (len <= EPSILON) {
            ox = projection.pointX() - origin.getX();
            oz = projection.pointZ() - origin.getZ();
            len = Math.sqrt(ox * ox + oz * oz);
        }

        if (len <= EPSILON) {
            ox = -projection.tangentZ();
            oz = projection.tangentX();
            double centerDx = projection.pointX() - center.getX();
            double centerDz = projection.pointZ() - center.getZ();
            if (ox * centerDx + oz * centerDz < 0.0) {
                ox = -ox;
                oz = -oz;
            }
            len = Math.sqrt(ox * ox + oz * oz);
        }

        if (len <= EPSILON) {
            return null;
        }
        return new double[]{ox / len, oz / len};
    }

    private static BlockPos resolveSurfaceFeet(ServerWorld world, double x, double z, int searchRadius) {
        if (world == null) {
            return null;
        }
        int baseX = (int) Math.round(x);
        int baseZ = (int) Math.round(z);
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 0; radius <= Math.max(0, searchRadius); radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int cx = baseX + dx;
                    int cz = baseZ + dz;
                    BlockPos column = new BlockPos(cx, world.getBottomY(), cz);
                    if (!world.isChunkLoaded(column)) {
                        continue;
                    }
                    int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                    if (topY <= world.getBottomY()) {
                        continue;
                    }
                    BlockPos feet = new BlockPos(cx, topY, cz);
                    if (VoxelJunctionService.isStandable(world, feet)) {
                        candidates.add(feet.toImmutable());
                    }
                }
            }
            if (!candidates.isEmpty()) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos candidate : candidates) {
            double distSq = squaredDistance(x, z, candidate.getX(), candidate.getZ());
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = candidate;
            }
        }
        return best;
    }

    private static BlockPos fallbackSurfaceFeet(ServerWorld world, double x, double z, int fallbackY) {
        if (world == null) {
            return null;
        }
        int bx = (int) Math.round(x);
        int bz = (int) Math.round(z);
        BlockPos column = new BlockPos(bx, fallbackY, bz);
        if (!world.isChunkLoaded(column)) {
            return null;
        }
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        int feetY = topY > world.getBottomY() ? topY : fallbackY;
        return new BlockPos(bx, feetY, bz);
    }

    private static double squaredDistance(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
