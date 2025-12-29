package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import net.shasankp000.GameAI.skills.SkillManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Exterior-ring routing helpers.
 *
 * <p>These helpers convert large moves around the build footprint into a small number of short hops along the
 * exterior ring, reducing oscillation when walls/corners are present.</p>
 */
final class HovelPerimeterRoutingService {

    @FunctionalInterface
    interface FindStandable {
        BlockPos find(ServerWorld world, BlockPos seed, int searchRadius);
    }

    @FunctionalInterface
    interface EnsureStandable {
        void ensure(ServerWorld world, ServerPlayerEntity bot, BlockPos pos);
    }

    @FunctionalInterface
    interface MoveRingWaypoint {
        boolean move(ServerCommandSource source, ServerPlayerEntity bot, BlockPos ringPos, boolean allowDirectFallback);
    }

    private HovelPerimeterRoutingService() {
    }

    static int perimeterRingRadius(int radius, int perimeterRingOffset) {
        if (radius <= 0) return 0;
        return Math.max(2, radius + perimeterRingOffset);
    }

    static boolean moveToViaPerimeterLoop(ServerWorld world,
                                          ServerCommandSource source,
                                          ServerPlayerEntity bot,
                                          BlockPos destination,
                                          BlockPos buildCenter,
                                          int radius,
                                          int perimeterRingOffset,
                                          FindStandable findNearbyStandable,
                                          EnsureStandable ensureRingStandable,
                                          MoveRingWaypoint moveToRingWaypoint) {
        if (world == null || source == null || bot == null || destination == null) {
            return false;
        }
        if (buildCenter == null || radius <= 0) {
            return false;
        }

        int standY = buildCenter.getY() + 1;
        int ringRadius = perimeterRingRadius(radius, perimeterRingOffset);
        List<BlockPos> loop = HovelBlueprint.buildGroundPerimeter(buildCenter, ringRadius, standY);
        if (loop.isEmpty()) {
            return false;
        }

        BlockPos here = bot.getBlockPos();

        int startIdx = 0;
        int goalIdx = 0;
        double bestStart = Double.MAX_VALUE;
        double bestGoal = Double.MAX_VALUE;
        for (int i = 0; i < loop.size(); i++) {
            BlockPos p = loop.get(i);
            double ds = HovelGeometryService.distSqXZ(here, p);
            if (ds < bestStart) {
                bestStart = ds;
                startIdx = i;
            }
            double dg = HovelGeometryService.distSqXZ(destination, p);
            if (dg < bestGoal) {
                bestGoal = dg;
                goalIdx = i;
            }
        }

        // If we're already basically at the goal ring index, don't waste time looping.
        if (startIdx == goalIdx) {
            return false;
        }

        int size = loop.size();
        int cw = (goalIdx - startIdx + size) % size;
        int ccw = (startIdx - goalIdx + size) % size;
        int dir = cw <= ccw ? 1 : -1;
        int remaining = Math.min(cw, ccw);

        // First: step to the nearest loop cell if we're not close (helps "snap" onto the red path).
        BlockPos entrySeed = loop.get(startIdx);
        BlockPos entry = findNearbyStandable.find(world, entrySeed, 4);
        if (entry != null && HovelGeometryService.distSqXZ(here, entry) > 1.0D) {
            ensureRingStandable.ensure(world, bot, entry);
            if (!moveToRingWaypoint.move(source, bot, entry, false)) {
                return false;
            }
        }

        // Walk along the loop in a handful of hops instead of dozens of single-tile steps.
        int hops = 0;
        int idx = startIdx;
        int maxHops = Math.min(24, Math.max(8, remaining + 4));
        int stride = 4;
        while (hops < maxHops && remaining > 0 && !SkillManager.shouldAbortSkill(bot)) {
            int stepWanted = Math.min(stride, remaining);

            // Pick a standable waypoint for this hop.
            // Try shorter steps first, then scan a bit farther ahead to route around small terrain irregularities.
            int chosenStep = -1;
            int candIdx = idx;
            BlockPos chosenStand = null;

            for (int stepTry = stepWanted; stepTry >= 1; stepTry--) {
                int ci = (idx + dir * stepTry) % size;
                if (ci < 0) ci += size;
                BlockPos seed = loop.get(ci);
                BlockPos stand = findNearbyStandable.find(world, seed, 4);
                if (stand != null) {
                    chosenStep = stepTry;
                    candIdx = ci;
                    chosenStand = stand;
                    break;
                }
            }
            if (chosenStand == null) {
                int scan = Math.min(6, remaining);
                for (int stepTry = 1; stepTry <= scan; stepTry++) {
                    int ci = (idx + dir * stepTry) % size;
                    if (ci < 0) ci += size;
                    BlockPos seed = loop.get(ci);
                    BlockPos stand = findNearbyStandable.find(world, seed, 4);
                    if (stand != null) {
                        chosenStep = stepTry;
                        candIdx = ci;
                        chosenStand = stand;
                        break;
                    }
                }
            }
            if (chosenStand == null) {
                // No viable waypoint ahead; caller can fall back to pathing or another strategy.
                return false;
            }

            ensureRingStandable.ensure(world, bot, chosenStand);
            boolean ok = moveToRingWaypoint.move(source, bot, chosenStand, false);
            if (!ok) {
                // Don't commit to the index advance; reduce stride and try a smaller/nearer hop.
                stride = Math.max(1, stride - 1);
                hops++;
                continue;
            }

            // Commit progress.
            idx = candIdx;
            remaining -= chosenStep;
            hops++;

            // If we made it to the ring point nearest the destination, stop looping.
            if (idx == goalIdx) {
                break;
            }
        }

        return idx == goalIdx;
    }

    static boolean perimeterRecoveryWalk(ServerWorld world,
                                        ServerCommandSource source,
                                        ServerPlayerEntity bot,
                                        BlockPos center,
                                        int radius,
                                        int perimeterRingOffset,
                                        FindStandable findNearbyStandable,
                                        EnsureStandable ensureRingStandable,
                                        MoveRingWaypoint moveToRingWaypoint) {
        if (world == null || source == null || bot == null || center == null) return false;
        if (radius <= 0) return false;

        int standY = center.getY() + 1;
        int ringRadius = perimeterRingRadius(radius, perimeterRingOffset);
        List<BlockPos> loop = HovelBlueprint.buildGroundPerimeter(center, ringRadius, standY);
        if (loop.isEmpty()) return false;

        BlockPos here = bot.getBlockPos();
        int startIdx = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < loop.size(); i++) {
            BlockPos p = loop.get(i);
            double d = here.getSquaredDistance(p);
            if (d < best) {
                best = d;
                startIdx = i;
            }
        }

        // Turning the corner is the goal. Find the next corner ahead on the loop.
        ArrayList<Integer> corners = new ArrayList<>(4);
        for (int i = 0; i < loop.size(); i++) {
            BlockPos p = loop.get(i);
            int dx = p.getX() - center.getX();
            int dz = p.getZ() - center.getZ();
            if (Math.abs(dx) == ringRadius && Math.abs(dz) == ringRadius) {
                corners.add(i);
            }
        }

        // Fallback: if corners weren't detected (shouldn't happen), do a short arc and hope.
        if (corners.isEmpty()) {
            int maxSteps = Math.min(loop.size(), 14);
            int progressed = 0;
            for (int i = 0; i < maxSteps && !SkillManager.shouldAbortSkill(bot); i++) {
                BlockPos seed = loop.get((startIdx + i) % loop.size());
                BlockPos stand = findNearbyStandable.find(world, seed.withY(standY), 3);
                if (stand == null) continue;
                ensureRingStandable.ensure(world, bot, stand);
                if (moveToRingWaypoint.move(source, bot, stand, false)) {
                    progressed++;
                    if (progressed >= 4) return true;
                }
            }
            return false;
        }

        int targetCornerIdx = corners.get(0);
        int bestDelta = Integer.MAX_VALUE;
        for (int ci : corners) {
            int delta = ci >= startIdx ? (ci - startIdx) : (loop.size() - startIdx + ci);
            if (delta == 0) continue;
            if (delta < bestDelta) {
                bestDelta = delta;
                targetCornerIdx = ci;
            }
        }

        // 1) Try to reach the corner.
        BlockPos corner = loop.get(targetCornerIdx);
        BlockPos cornerStand = findNearbyStandable.find(world, corner.withY(standY), 4);
        if (cornerStand == null) {
            return false;
        }
        ensureRingStandable.ensure(world, bot, cornerStand);
        if (!moveToRingWaypoint.move(source, bot, cornerStand, false)) {
            return false;
        }

        // 2) Take a couple more steps past the corner so we really "turn" and don't just jitter at it.
        int progressed = 0;
        for (int i = 1; i <= 4 && !SkillManager.shouldAbortSkill(bot); i++) {
            BlockPos seed = loop.get((targetCornerIdx + i) % loop.size());
            BlockPos stand = findNearbyStandable.find(world, seed.withY(standY), 3);
            if (stand == null) continue;
            ensureRingStandable.ensure(world, bot, stand);
            if (moveToRingWaypoint.move(source, bot, stand, false)) {
                progressed++;
                if (progressed >= 2) {
                    break;
                }
            }
        }
        return true;
    }
}
