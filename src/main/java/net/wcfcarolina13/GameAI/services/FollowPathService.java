package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.fluid.Fluids;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import static net.wcfcarolina13.GameAI.services.BotCampfireAvoidanceService.isNearExposedCampfire;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Lightweight, bounded follow path planning designed for short-range "around the corner" cases.
 *
 * Key properties:
 * - Snapshot capture runs on the server thread but is bounded to a small region.
 * - Planning runs off-thread on the immutable snapshot.
 * - Doors are treated as passable for planning (wood can be opened on approach; iron remains blocked).
 */
public final class FollowPathService {

    private FollowPathService() {}

    public static final long PLAN_COOLDOWN_MS = 1200L;
    public static final int MAX_REGION_RADIUS = 24; // -> max dimension 49
    public static final int MIN_REGION_MARGIN = 8;
    public static final int MIN_Y_SPAN = 6; // +/- 3 for flat terrain
    public static final int MAX_Y_SPAN_CAP = 20; // hard upper bound for dynamic Y span
    public static final double WAYPOINT_REACH_SQ = 2.25D;
    private static final ThreadLocal<Integer> LAST_TRANSITION_REJECT_COUNT = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> LAST_SWIM_TRANSITION_REJECT_COUNT = ThreadLocal.withInitial(() -> 0);

    public record FollowSnapshot(int minX, int minY, int minZ,
                                 int sizeX, int sizeY, int sizeZ,
                                 boolean[] standable,
                                 boolean[] passable,
                                 boolean[] swimmable,
                                 byte[] doorType,
                                 BlockPos start,
                                 BlockPos goal) {
        public boolean inBounds(BlockPos pos) {
            if (pos == null) return false;
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            return x >= minX && x < minX + sizeX
                    && y >= minY && y < minY + sizeY
                    && z >= minZ && z < minZ + sizeZ;
        }

        public int index(int x, int y, int z) {
            int lx = x - minX;
            int ly = y - minY;
            int lz = z - minZ;
            return (ly * sizeX + lx) * sizeZ + lz;
        }

        public BlockPos posFromIndex(int idx) {
            int ly = idx / (sizeX * sizeZ);
            int rem = idx - ly * sizeX * sizeZ;
            int lx = rem / sizeZ;
            int lz = rem - lx * sizeZ;
            return new BlockPos(minX + lx, minY + ly, minZ + lz);
        }

        public boolean isStandable(BlockPos pos) {
            if (!inBounds(pos)) return false;
            return standable[index(pos.getX(), pos.getY(), pos.getZ())];
        }

        public boolean isPassable(BlockPos pos) {
            if (!inBounds(pos)) return false;
            return passable[index(pos.getX(), pos.getY(), pos.getZ())];
        }

        public boolean isSwimmable(BlockPos pos) {
            if (!inBounds(pos)) return false;
            return swimmable[index(pos.getX(), pos.getY(), pos.getZ())];
        }

        public boolean isDoorCell(BlockPos pos) {
            if (!inBounds(pos)) return false;
            return doorType[index(pos.getX(), pos.getY(), pos.getZ())] != 0;
        }

        public boolean isWoodDoorCell(BlockPos pos) {
            if (!inBounds(pos)) return false;
            return doorType[index(pos.getX(), pos.getY(), pos.getZ())] == 1;
        }
    }

    public static FollowSnapshot capture(ServerWorld world, BlockPos startPos, BlockPos goalPos) {
        return capture(world, startPos, goalPos, false);
    }

    /**
     * Captures a bounded collision-aware grid used for follow planning.
     * <p>
     * When {@code centerOnStart} is true, the snapshot is always centered on the bot position. This is important
     * for “corner in enclosure” cases where the correct first move is to go to a door that is not between the bot
     * and the target (so midpoint/goal-centered windows can exclude the door entirely).
     */
    public static FollowSnapshot capture(ServerWorld world, BlockPos startPos, BlockPos goalPos, boolean centerOnStart) {
        if (world == null || startPos == null || goalPos == null) {
            return null;
        }

        // If the goal is too far, skip bounded planning (follow will rely on direct steering / teleport rules).
        int dx = goalPos.getX() - startPos.getX();
        int dz = goalPos.getZ() - startPos.getZ();
        int horiz = Math.abs(dx) + Math.abs(dz);
        if (horiz > 60) {
            return null;
        }

        int maxSize = MAX_REGION_RADIUS * 2 + 1;
        int minX, maxX, minZ, maxZ;
        if (centerOnStart) {
            minX = startPos.getX() - MAX_REGION_RADIUS;
            maxX = startPos.getX() + MAX_REGION_RADIUS;
            minZ = startPos.getZ() - MAX_REGION_RADIUS;
            maxZ = startPos.getZ() + MAX_REGION_RADIUS;
        } else {
            int desiredMinX = Math.min(startPos.getX(), goalPos.getX()) - MIN_REGION_MARGIN;
            int desiredMaxX = Math.max(startPos.getX(), goalPos.getX()) + MIN_REGION_MARGIN;
            int desiredMinZ = Math.min(startPos.getZ(), goalPos.getZ()) - MIN_REGION_MARGIN;
            int desiredMaxZ = Math.max(startPos.getZ(), goalPos.getZ()) + MIN_REGION_MARGIN;
            int midX = (startPos.getX() + goalPos.getX()) / 2;
            int midZ = (startPos.getZ() + goalPos.getZ()) / 2;

            minX = desiredMinX;
            maxX = desiredMaxX;
            minZ = desiredMinZ;
            maxZ = desiredMaxZ;

            if ((maxX - minX + 1) > maxSize) {
                minX = midX - MAX_REGION_RADIUS;
                maxX = midX + MAX_REGION_RADIUS;
            }
            if ((maxZ - minZ + 1) > maxSize) {
                minZ = midZ - MAX_REGION_RADIUS;
                maxZ = midZ + MAX_REGION_RADIUS;
            }
        }

        int minY = Math.min(startPos.getY(), goalPos.getY()) - 2;
        int maxY = Math.max(startPos.getY(), goalPos.getY()) + 2;
        int absDeltaY = Math.abs(startPos.getY() - goalPos.getY());
        int effectiveYSpan = Math.max(MIN_Y_SPAN, Math.min(MAX_Y_SPAN_CAP, absDeltaY + 4));
        int desiredMinY = startPos.getY() - (effectiveYSpan / 2);
        int desiredMaxY = startPos.getY() + (effectiveYSpan / 2);
        if (maxY - minY > effectiveYSpan) {
            minY = Math.max(minY, desiredMinY);
            maxY = Math.min(maxY, desiredMaxY);
        }

        int sizeX = Math.max(1, maxX - minX + 1);
        int sizeZ = Math.max(1, maxZ - minZ + 1);
        int sizeY = Math.max(1, maxY - minY + 1);

        // Hard cap to avoid pathological lag if constraints drift.
        if (sizeX > maxSize || sizeZ > maxSize || sizeY > MAX_Y_SPAN_CAP + 1) {
            return null;
        }

        int total = sizeX * sizeY * sizeZ;
        boolean[] standable = new boolean[total];
        boolean[] passable = new boolean[total];
        boolean[] swimmable = new boolean[total];
        byte[] doorType = new byte[total]; // 0=none, 1=wood, 2=iron

        for (int y = minY; y < minY + sizeY; y++) {
            for (int x = minX; x < minX + sizeX; x++) {
                for (int z = minZ; z < minZ + sizeZ; z++) {
                    BlockPos foot = new BlockPos(x, y, z);
                    BlockPos head = foot.up();
                    BlockPos below = foot.down();

                    if (!world.isChunkLoaded(foot) || !world.isChunkLoaded(head) || !world.isChunkLoaded(below)) {
                        continue;
                    }

                    BlockState footState = world.getBlockState(foot);
                    BlockState headState = world.getBlockState(head);
                    BlockState belowState = world.getBlockState(below);

                    boolean solidBelow = !belowState.getCollisionShape(world, below).isEmpty();
                    boolean footPassable = isPassableForPlan(world, foot, footState);
                    boolean headPassable = isPassableForPlan(world, head, headState);
                    boolean footWater = world.getFluidState(foot).isOf(Fluids.WATER);
                    boolean headWater = world.getFluidState(head).isOf(Fluids.WATER);
                    
                    // Campfire avoidance: mark cells near exposed campfires as non-standable
                    boolean nearCampfire = isNearExposedCampfire(world, foot, 2);

                    int idx = ((y - minY) * sizeX + (x - minX)) * sizeZ + (z - minZ);
                    passable[idx] = footPassable && !nearCampfire;
                    standable[idx] = solidBelow && footPassable && headPassable && !nearCampfire;
                    swimmable[idx] = (footWater || headWater) && footPassable && headPassable && !nearCampfire;
                    if (footState.getBlock() instanceof DoorBlock) {
                        doorType[idx] = footState.isOf(Blocks.IRON_DOOR) ? (byte) 2 : (byte) 1;
                    } else if (footState.getBlock() instanceof FenceGateBlock) {
                        // Gates behave like doors for our purposes (openable barriers between spaces).
                        doorType[idx] = (byte) 1;
                    }
                }
            }
        }

        return new FollowSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, standable, passable, swimmable, doorType, startPos.toImmutable(), goalPos.toImmutable());
    }

    public static int consumeLastTransitionRejectCount() {
        int value = LAST_TRANSITION_REJECT_COUNT.get();
        LAST_TRANSITION_REJECT_COUNT.set(0);
        return value;
    }

    public static int consumeLastSwimTransitionRejectCount() {
        int value = LAST_SWIM_TRANSITION_REJECT_COUNT.get();
        LAST_SWIM_TRANSITION_REJECT_COUNT.set(0);
        return value;
    }

    private static boolean isPassableForPlan(ServerWorld world, BlockPos pos, BlockState state) {
        if (state == null) {
            return true;
        }
        if (state.isAir()) {
            return true;
        }
        if (state.getBlock() instanceof DoorBlock) {
            if (state.isOf(Blocks.IRON_DOOR)) {
                return state.getCollisionShape(world, pos).isEmpty();
            }
            if (LockableBlockService.isLocked(world, pos)) {
                return false;
            }
            return true;
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            if (LockableBlockService.isLocked(world, pos)) {
                return false;
            }
            return true;
        }
        if (state.getBlock() instanceof TrapdoorBlock) {
            if (LockableBlockService.isLocked(world, pos)) {
                return false;
            }
            return state.getCollisionShape(world, pos).isEmpty();
        }
        if (state.isOf(Blocks.WATER)) {
            return true;
        }

        // Important: our bounded planner operates in a simplified “standable tile” grid based on
        // block positions (i.e., {@link net.minecraft.entity.Entity#getBlockPos()}). In that coordinate
        // system, common walkable partial blocks (carpet, slabs, stairs, snow layers, pressure plates,
        // rails, tripwire, lily pads, ...) may appear in the bot's current block position even though
        // the entity is standing on top of their collision shape.
        //
        // If we treat these as non-passable, the snapshot can end up with *no standable tiles* in
        // carpeted/interior areas, producing repeated “no path found” and follow stalls.
        //
        // Delegates to the shared WalkablePartialBlocks.isStandable classification so this planner,
        // the pathfinders, and the rescue service all agree on what counts as walkable partial
        // terrain. isStandable returns true for the full set (carpets/plates/rails/slabs/stairs/snow/
        // tripwire/lily/pale-moss/moss plus a thin-shape fallback), which matches the "bot might be
        // standing here" semantics the bounded planner needs.
        return WalkablePartialBlocks.isStandable(state, world, pos);
    }

    public static List<BlockPos> planWaypoints(FollowSnapshot snapshot, BlockPos avoidDoorBase) {
        return planWaypoints(snapshot, avoidDoorBase, false, false);
    }

    public static List<BlockPos> planWaypoints(FollowSnapshot snapshot, BlockPos avoidDoorBase, boolean stuckMode) {
        return planWaypoints(snapshot, avoidDoorBase, stuckMode, false);
    }

    public static List<BlockPos> planWaypoints(FollowSnapshot snapshot, BlockPos avoidDoorBase, boolean stuckMode, boolean waterStuckMode) {
        int[] transitionRejects = new int[] { 0 };
        LAST_SWIM_TRANSITION_REJECT_COUNT.set(0);
        try {
            if (snapshot == null) {
                return List.of();
            }
            BlockPos start = snapshot.start();
            BlockPos goal = snapshot.goal();
            if (!snapshot.inBounds(start)) {
                return List.of();
            }

            BlockPos startStand = findNearestStandable(snapshot, start, 2);
            if (startStand == null) {
                return List.of();
            }

            // If the goal isn't in the captured window, we can still produce an "exit the nearest door" plan.
            // This mirrors the common dweller/stalker behavior: first escape the local enclosure, then replan.
            if (!snapshot.inBounds(goal)) {
                return planEscapeDoorWaypoints(snapshot, startStand, avoidDoorBase, stuckMode, waterStuckMode, transitionRejects);
            }

            List<BlockPos> goalCandidates = findGoalCandidates(snapshot, goal, 2);
            if (goalCandidates.isEmpty()) {
                BlockPos nearGoal = findNearestStandable(snapshot, goal, 3);
                if (nearGoal != null) {
                    goalCandidates = List.of(nearGoal);
                }
            }
            if (goalCandidates.isEmpty()) {
                return List.of();
            }

            List<BlockPos> raw = aStar(snapshot, startStand, new HashSet<>(goalCandidates), transitionRejects);
            // If we're already at (or immediately adjacent to) the goal, A* may return a single node.
            // Treat that as "no navigation plan needed" and allow callers to fall back to door-escape plans.
            if (raw.size() > 1) {
                int maxWaypoints = waterStuckMode ? 16 : (stuckMode ? 14 : 7);
                int preserveLeadIn = waterStuckMode ? 5 : (stuckMode ? 4 : 0);
                return compressWaypoints(snapshot, raw, maxWaypoints, preserveLeadIn);
            }

            // Fallback: if the commander is "around the corner" such that reaching them requires initially moving away,
            // exit the nearest reachable wooden door first, then follow will replan toward the commander.
            return planEscapeDoorWaypoints(snapshot, startStand, avoidDoorBase, stuckMode, waterStuckMode, transitionRejects);
        } finally {
            LAST_TRANSITION_REJECT_COUNT.set(transitionRejects[0]);
        }
    }

    /**
     * Returns waypoints that move the bot to and through the nearest reachable wooden door in the snapshot.
     * Useful as a fallback when the commander is far away (outside the bounded window) or when the correct
     * path requires an initial move away from the commander to escape an enclosure.
     */
    public static List<BlockPos> planEscapeWaypoints(FollowSnapshot snapshot, BlockPos avoidDoorBase) {
        return planEscapeWaypoints(snapshot, avoidDoorBase, false, false);
    }

    public static List<BlockPos> planEscapeWaypoints(FollowSnapshot snapshot, BlockPos avoidDoorBase, boolean stuckMode) {
        return planEscapeWaypoints(snapshot, avoidDoorBase, stuckMode, false);
    }

    public static List<BlockPos> planEscapeWaypoints(FollowSnapshot snapshot, BlockPos avoidDoorBase, boolean stuckMode, boolean waterStuckMode) {
        int[] transitionRejects = new int[] { 0 };
        LAST_SWIM_TRANSITION_REJECT_COUNT.set(0);
        try {
            if (snapshot == null) {
                return List.of();
            }
            BlockPos start = snapshot.start();
            if (!snapshot.inBounds(start)) {
                return List.of();
            }
            BlockPos startStand = findNearestStandable(snapshot, start, 2);
            if (startStand == null) {
                return List.of();
            }
            return planEscapeDoorWaypoints(snapshot, startStand, avoidDoorBase, stuckMode, waterStuckMode, transitionRejects);
        } finally {
            LAST_TRANSITION_REJECT_COUNT.set(transitionRejects[0]);
        }
    }

    private static List<BlockPos> planEscapeDoorWaypoints(FollowSnapshot snapshot, BlockPos startStand, BlockPos avoidDoorBase) {
        return planEscapeDoorWaypoints(snapshot, startStand, avoidDoorBase, false, false, new int[] { 0 });
    }

    private static List<BlockPos> planEscapeDoorWaypoints(FollowSnapshot snapshot, BlockPos startStand, BlockPos avoidDoorBase, boolean stuckMode) {
        return planEscapeDoorWaypoints(snapshot, startStand, avoidDoorBase, stuckMode, false, new int[] { 0 });
    }

    private static List<BlockPos> planEscapeDoorWaypoints(FollowSnapshot snapshot,
                                                          BlockPos startStand,
                                                          BlockPos avoidDoorBase,
                                                          boolean stuckMode,
                                                          boolean waterStuckMode,
                                                          int[] transitionRejects) {
        if (snapshot == null || startStand == null) {
            return List.of();
        }

        BlockPos desiredGoal = snapshot.goal();
        double startGoalDistSq = desiredGoal != null ? startStand.getSquaredDistance(desiredGoal) : 0.0D;

        // Build candidate approach/step pairs adjacent to any wooden door cell.
        java.util.HashMap<BlockPos, BlockPos> approachToStep = new java.util.HashMap<>();
        java.util.HashMap<BlockPos, Double> approachToScore = new java.util.HashMap<>();
        Set<BlockPos> approachGoals = new HashSet<>();

        int minX = snapshot.minX();
        int minY = snapshot.minY();
        int minZ = snapshot.minZ();
        int maxX = minX + snapshot.sizeX();
        int maxY = minY + snapshot.sizeY();
        int maxZ = minZ + snapshot.sizeZ();

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    BlockPos doorPos = new BlockPos(x, y, z);
                    if (!snapshot.isWoodDoorCell(doorPos)) {
                        continue;
                    }
                    if (avoidDoorBase != null && (doorPos.equals(avoidDoorBase) || doorPos.equals(avoidDoorBase.up()) || doorPos.equals(avoidDoorBase.down()))) {
                        continue;
                    }
                    List<BlockPos> neighbors = new ArrayList<>(4);
                    for (Direction dir : Direction.Type.HORIZONTAL) {
                        BlockPos n = doorPos.offset(dir);
                        if (snapshot.isStandable(n)) {
                            neighbors.add(n.toImmutable());
                        }
                    }
                    if (neighbors.size() < 2) {
                        continue;
                    }
                    neighbors.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(startStand)));
                    BlockPos approach = neighbors.get(0);
                    BlockPos step = neighbors.stream()
                            .max(Comparator.comparingDouble(p -> p.getSquaredDistance(approach)))
                            .orElse(neighbors.get(1));
                    approachGoals.add(approach);
                    approachToStep.put(approach, step);
                    if (desiredGoal != null) {
                        double stepGoalDistSq = step.getSquaredDistance(desiredGoal);
                        double improve = startGoalDistSq - stepGoalDistSq;
                        // Prefer "better after stepping through" doors, but still consider distance to the door.
                        double score = improve - Math.sqrt(startStand.getSquaredDistance(approach)) * 1.25D;
                        approachToScore.put(approach, score);
                    }
                }
            }
        }

        if (approachGoals.isEmpty()) {
            return List.of();
        }

        // Instead of picking the closest door by heuristic, compute costs to all approach goals and select the best.
        int total = snapshot.standable().length;
        int[] cameFrom = new int[total];
        int[] gScore = new int[total];
        Arrays.fill(cameFrom, -1);
        Arrays.fill(gScore, Integer.MAX_VALUE);
        int startIdx = snapshot.index(startStand.getX(), startStand.getY(), startStand.getZ());
        gScore[startIdx] = 0;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingInt(Node::fScore));
        open.add(new Node(startIdx, 0));

        while (!open.isEmpty()) {
            Node current = open.poll();
            int curIdx = current.idx();
            int curScore = current.fScore();
            if (curScore != gScore[curIdx]) {
                continue;
            }

            BlockPos curPos = snapshot.posFromIndex(curIdx);
            int cx = curPos.getX(), cy = curPos.getY(), cz = curPos.getZ();
            for (Direction dir : Direction.Type.HORIZONTAL) {
                int nx = cx + dir.getOffsetX();
                int nz = cz + dir.getOffsetZ();

                int[] dyOrder = snapshot.isSwimmable(curPos) ? new int[] { 1, 0, -1 } : new int[] { 0, 1, -1 };
                for (int dY : dyOrder) {
                    int ny = cy + dY;
                    BlockPos nPos = new BlockPos(nx, ny, nz);
                    if (!snapshot.inBounds(nPos)) {
                        continue;
                    }
                    if (!isTransitionTraversable(snapshot, curPos, nPos, dY, transitionRejects)) {
                        continue;
                    }
                    int nIdx = snapshot.index(nx, ny, nz);
                    int tentative = gScore[curIdx] + 10 + (dY == 0 ? 0 : 4);
                    if (tentative < gScore[nIdx]) {
                        cameFrom[nIdx] = curIdx;
                        gScore[nIdx] = tentative;
                        open.add(new Node(nIdx, tentative));
                    }
                    break;
                }
            }
        }

        BlockPos bestApproach = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BlockPos approach : approachGoals) {
            int idx = snapshot.index(approach.getX(), approach.getY(), approach.getZ());
            int cost = gScore[idx];
            if (cost == Integer.MAX_VALUE) {
                continue;
            }
            double pathLen = cost / 10.0D;
            double goalScore = desiredGoal != null
                    ? approachToScore.getOrDefault(approach, 0.0D)
                    : -pathLen;
            // Break ties toward closer, easier-to-reach door approaches.
            double score = goalScore - pathLen * 0.35D;
            if (score > bestScore) {
                bestScore = score;
                bestApproach = approach;
            }
        }

        if (bestApproach == null) {
            return List.of();
        }

        int bestApproachIdx = snapshot.index(bestApproach.getX(), bestApproach.getY(), bestApproach.getZ());
        List<BlockPos> raw = reconstruct(snapshot, cameFrom, bestApproachIdx);
        if (raw.isEmpty()) {
            return List.of();
        }

        BlockPos finalApproach = bestApproach;
        BlockPos step = approachToStep.get(finalApproach);
        if (step == null) {
            // Find any other standable neighbor of an adjacent door cell.
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos door = finalApproach.offset(dir);
                if (snapshot.isWoodDoorCell(door)) {
                    for (Direction dir2 : Direction.Type.HORIZONTAL) {
                        BlockPos n = door.offset(dir2);
                        if (!n.equals(finalApproach) && snapshot.isStandable(n)) {
                            step = n.toImmutable();
                            break;
                        }
                    }
                }
                if (step != null) break;
            }
        }
        int maxWaypoints = waterStuckMode ? 16 : (stuckMode ? 14 : 6);
        int preserveLeadIn = waterStuckMode ? 5 : (stuckMode ? 4 : 0);
        if (step == null) {
            return compressWaypoints(snapshot, raw, maxWaypoints, preserveLeadIn);
        }
        List<BlockPos> compressed = new ArrayList<>(compressWaypoints(snapshot, raw, maxWaypoints, preserveLeadIn));
        if (compressed.isEmpty() || !compressed.get(compressed.size() - 1).equals(finalApproach)) {
            compressed.add(finalApproach.toImmutable());
        }
        if (!compressed.get(compressed.size() - 1).equals(step)) {
            compressed.add(step.toImmutable());
        }
        return compressed;
    }

    private static List<BlockPos> findGoalCandidates(FollowSnapshot snapshot, BlockPos goal, int radius) {
        List<BlockPos> options = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = goal.add(dx, dy, dz);
                    if (!snapshot.inBounds(p)) continue;
                    if (!snapshot.isStandable(p)) continue;
                    double distSq = p.getSquaredDistance(goal);
                    if (distSq <= radius * radius + 1) {
                        options.add(p.toImmutable());
                    }
                }
            }
        }
        options.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(goal)));
        return options;
    }

    private static BlockPos findNearestStandable(FollowSnapshot snapshot, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (!snapshot.inBounds(p)) continue;
                    if (!snapshot.isStandable(p)) continue;
                    double distSq = p.getSquaredDistance(origin);
                    if (distSq < bestSq) {
                        bestSq = distSq;
                        best = p.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private record Node(int idx, int fScore) {}

    private static List<BlockPos> aStar(FollowSnapshot snapshot, BlockPos start, Set<BlockPos> goals, int[] transitionRejects) {
        int total = snapshot.standable().length;
        int[] cameFrom = new int[total];
        int[] gScore = new int[total];
        Arrays.fill(cameFrom, -1);
        Arrays.fill(gScore, Integer.MAX_VALUE);

        int startIdx = snapshot.index(start.getX(), start.getY(), start.getZ());
        gScore[startIdx] = 0;

        // Pick a representative goal for heuristic (closest by Manhattan).
        BlockPos heuristicGoal = goals.stream()
                .min(Comparator.comparingInt(p -> manhattan(start, p)))
                .orElse(start);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingInt(Node::fScore));
        open.add(new Node(startIdx, heuristic(start, heuristicGoal)));

        boolean[] inOpen = new boolean[total];
        inOpen[startIdx] = true;

        while (!open.isEmpty()) {
            Node current = open.poll();
            int curIdx = current.idx();
            inOpen[curIdx] = false;

            BlockPos curPos = snapshot.posFromIndex(curIdx);
            if (goals.contains(curPos)) {
                return reconstruct(snapshot, cameFrom, curIdx);
            }

            int cx = curPos.getX(), cy = curPos.getY(), cz = curPos.getZ();
            for (Direction dir : Direction.Type.HORIZONTAL) {
                int nx = cx + dir.getOffsetX();
                int nz = cz + dir.getOffsetZ();

                // Prefer same-level movement, then step up, then step down.
                int[] dyOrder = snapshot.isSwimmable(curPos) ? new int[] { 1, 0, -1 } : new int[] { 0, 1, -1 };
                for (int dY : dyOrder) {
                    int ny = cy + dY;
                    BlockPos nPos = new BlockPos(nx, ny, nz);
                    if (!snapshot.inBounds(nPos)) {
                        continue;
                    }
                    if (!isTransitionTraversable(snapshot, curPos, nPos, dY, transitionRejects)) {
                        continue;
                    }
                    int nIdx = snapshot.index(nx, ny, nz);
                    int tentative = gScore[curIdx] + 10 + (dY == 0 ? 0 : 4);
                    if (tentative < gScore[nIdx]) {
                        cameFrom[nIdx] = curIdx;
                        gScore[nIdx] = tentative;
                        int f = tentative + heuristic(nPos, heuristicGoal);
                        if (!inOpen[nIdx]) {
                            open.add(new Node(nIdx, f));
                            inOpen[nIdx] = true;
                        } else {
                            // Reinsert; small queues keep this acceptable.
                            open.add(new Node(nIdx, f));
                        }
                    }
                    break;
                }
            }
        }
        return List.of();
    }

    private static boolean isTransitionTraversable(FollowSnapshot snapshot,
                                                   BlockPos from,
                                                   BlockPos to,
                                                   int dY,
                                                   int[] transitionRejects) {
        if (snapshot == null || from == null || to == null) {
            return false;
        }
        if (!snapshot.inBounds(from) || !snapshot.inBounds(to)) {
            return false;
        }
        boolean fromStandable = snapshot.isStandable(from);
        boolean fromSwimmable = snapshot.isSwimmable(from);
        boolean toStandable = snapshot.isStandable(to);
        boolean toSwimmable = snapshot.isSwimmable(to);

        if (!fromStandable && !fromSwimmable) {
            return false;
        }
        if (Math.abs(dY) > 1) {
            if (transitionRejects != null && transitionRejects.length > 0) {
                transitionRejects[0]++;
            }
            return false;
        }

        // Allow water lane movement in caves where standable tiles are sparse.
        if (toSwimmable) {
            if (!snapshot.isPassable(to)) {
                if (transitionRejects != null && transitionRejects.length > 0) {
                    transitionRejects[0]++;
                }
                LAST_SWIM_TRANSITION_REJECT_COUNT.set(LAST_SWIM_TRANSITION_REJECT_COUNT.get() + 1);
                return false;
            }
            return fromStandable || fromSwimmable;
        }

        if (!toStandable) {
            if (fromSwimmable) {
                if (transitionRejects != null && transitionRejects.length > 0) {
                    transitionRejects[0]++;
                }
                LAST_SWIM_TRANSITION_REJECT_COUNT.set(LAST_SWIM_TRANSITION_REJECT_COUNT.get() + 1);
            }
            return false;
        }

        // Swimming -> standable requires a clean shore/exit corridor.
        if (fromSwimmable) {
            BlockPos exitHead = to.up();
            if (!snapshot.inBounds(exitHead) || !snapshot.isPassable(exitHead)) {
                if (transitionRejects != null && transitionRejects.length > 0) {
                    transitionRejects[0]++;
                }
                LAST_SWIM_TRANSITION_REJECT_COUNT.set(LAST_SWIM_TRANSITION_REJECT_COUNT.get() + 1);
                return false;
            }
            if (dY > 0) {
                BlockPos jumpClearance = from.up(2);
                if (!snapshot.inBounds(jumpClearance) || !snapshot.isPassable(jumpClearance)) {
                    if (transitionRejects != null && transitionRejects.length > 0) {
                        transitionRejects[0]++;
                    }
                    LAST_SWIM_TRANSITION_REJECT_COUNT.set(LAST_SWIM_TRANSITION_REJECT_COUNT.get() + 1);
                    return false;
                }
            }
            return true;
        }

        if (dY <= 0) {
            return true;
        }

        // Upward steps require extra headroom at the launch block. Without this, A* can propose
        // "standable" y+1 nodes that are practically unjumpable under low canopy leaves.
        BlockPos jumpClearance = from.up(2);
        if (!snapshot.inBounds(jumpClearance) || !snapshot.isPassable(jumpClearance)) {
            if (transitionRejects != null && transitionRejects.length > 0) {
                transitionRejects[0]++;
            }
            return false;
        }
        return true;
    }

    private static int heuristic(BlockPos from, BlockPos to) {
        return manhattan(from, to) * 10;
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    private static List<BlockPos> reconstruct(FollowSnapshot snapshot, int[] cameFrom, int goalIdx) {
        ArrayDeque<BlockPos> stack = new ArrayDeque<>();
        int cur = goalIdx;
        int guard = 0;
        while (cur != -1 && guard++ < 2000) {
            stack.addFirst(snapshot.posFromIndex(cur));
            cur = cameFrom[cur];
        }
        return new ArrayList<>(stack);
    }

    private static List<BlockPos> compressWaypoints(FollowSnapshot snapshot, List<BlockPos> raw, int maxWaypoints) {
        return compressWaypoints(snapshot, raw, maxWaypoints, 0);
    }

    private static List<BlockPos> compressWaypoints(FollowSnapshot snapshot, List<BlockPos> raw, int maxWaypoints, int preserveLeadIn) {
        if (raw == null || raw.size() <= 1) {
            return List.of();
        }
        ArrayList<BlockPos> points = new ArrayList<>();
        int pinned = Math.max(0, Math.min(preserveLeadIn, raw.size() - 1));
        for (int i = 1; i <= pinned; i++) {
            points.add(raw.get(i).toImmutable());
        }

        BlockPos lastKept = raw.get(0);
        Direction lastDir = null;
        for (int i = 1; i < raw.size(); i++) {
            if (i <= pinned) {
                lastKept = raw.get(i);
                continue;
            }
            BlockPos p = raw.get(i);
            Direction dir = Direction.getFacing(p.getX() - lastKept.getX(), 0, p.getZ() - lastKept.getZ());
            boolean dirChanged = lastDir != null && dir != lastDir;
            boolean farEnough = lastKept.getSquaredDistance(p) >= 9.0D; // ~3 blocks
            boolean door = snapshot.isDoorCell(p);
            if (door || dirChanged || farEnough) {
                points.add(p.toImmutable());
                lastDir = dir;
                lastKept = p;
            }
        }

        if (points.isEmpty() || !points.get(points.size() - 1).equals(raw.get(raw.size() - 1))) {
            points.add(raw.get(raw.size() - 1).toImmutable());
        }

        // Downsample if too many.
        if (points.size() > maxWaypoints) {
            int locked = Math.max(0, Math.min(pinned, maxWaypoints - 1));
            ArrayList<BlockPos> sampled = new ArrayList<>(maxWaypoints);
            for (int i = 0; i < locked; i++) {
                sampled.add(points.get(i));
            }

            int remainingSlots = maxWaypoints - locked;
            if (remainingSlots <= 1) {
                sampled.add(points.get(points.size() - 1));
            } else {
                int startIdx = locked;
                int endIdx = points.size() - 1;
                sampled.add(points.get(startIdx));
                for (int i = 1; i < remainingSlots - 1; i++) {
                    int idx = startIdx + (int) Math.round((i / (double) (remainingSlots - 1)) * (endIdx - startIdx));
                    sampled.add(points.get(Math.min(endIdx, Math.max(startIdx, idx))));
                }
                sampled.add(points.get(endIdx));
            }
            points = sampled;
        }
        return points;
    }

    public static boolean shouldPlan(ServerPlayerEntity bot, ServerPlayerEntity target, boolean canSee, int stagnantTicks) {
        if (bot == null || target == null) {
            return false;
        }
        // Only plan when we likely need navigation, not when we're in simple LoS pursuit.
        double distSq = bot.getBlockPos().getSquaredDistance(target.getBlockPos());
        // If we've stopped making progress, plan even if we technically have line-of-sight (fences/doors can
        // still block navigation while canSee() returns true through gaps).
        if (stagnantTicks >= 4) {
            return true;
        }
        if (distSq <= 36.0D && canSee) {
            return false;
        }
        return !canSee;
    }

    /**
     * Checks if a target position is reachable from the start position using bounded A* planning.
     * This is useful for pre-filtering targets before committing to navigation.
     *
     * @param snapshot    The pre-captured collision snapshot.
     * @param targetPos   The position to check reachability for.
     * @return true if a path exists, false otherwise.
     */
    public static boolean isReachable(FollowSnapshot snapshot, BlockPos targetPos) {
        if (snapshot == null || targetPos == null) {
            return false;
        }
        BlockPos start = snapshot.start();
        if (!snapshot.inBounds(start) || !snapshot.inBounds(targetPos)) {
            return false;
        }

        BlockPos startStand = findNearestStandable(snapshot, start, 2);
        if (startStand == null) {
            return false;
        }

        // If already at target, it's reachable.
        if (startStand.getSquaredDistance(targetPos) <= 2.25D) {
            return true;
        }

        List<BlockPos> goalCandidates = findGoalCandidates(snapshot, targetPos, 2);
        if (goalCandidates.isEmpty()) {
            BlockPos nearGoal = findNearestStandable(snapshot, targetPos, 3);
            if (nearGoal != null) {
                goalCandidates = List.of(nearGoal);
            }
        }
        if (goalCandidates.isEmpty()) {
            return false;
        }

        List<BlockPos> path = aStar(snapshot, startStand, new HashSet<>(goalCandidates), new int[] { 0 });
        return !path.isEmpty();
    }
}
