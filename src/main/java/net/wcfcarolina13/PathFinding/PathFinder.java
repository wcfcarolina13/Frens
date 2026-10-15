package net.wcfcarolina13.PathFinding;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.wcfcarolina13.GameAI.services.BotCampfireAvoidanceService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class PathFinder {

    public static final Logger LOGGER = LoggerFactory.getLogger("frens");
    private static final int MAX_SEGMENT_MANHATTAN_SPAN = 6;

    /**
     * Which finder {@link #calculatePath} uses. Baritone-style is the default since 1.1.240
     * (Bradley, 2026-10-14); the bidirectional A* below is the "classic" fallback. Frens.java
     * copies the persisted config value in at server start.
     */
    public static boolean USE_BARITONE_STYLE = true;

    public static class PathNode {
        public final BlockPos pos;
        public final String blockName;
        public final boolean jump;
        public boolean walkable;

        public PathNode(BlockPos pos, String blockName, boolean walkable, boolean jump) {
            this.pos = pos;
            this.blockName = blockName;
            this.jump = jump;
            this.walkable = walkable;
        }

        @Override
        public String toString() {
            return "PathNode{" +
                    "pos=" + pos +
                    ", blockName='" + blockName + '\'' +
                    ", jump=" + jump +
                    '}';
        }

        public boolean jumpNeeded() {
            return jump;
        }

        public BlockPos getPos() {
            return pos;
        }
    }


    private static class Node implements Comparable<Node> {
        BlockPos position;
        Node parent;
        double gScore;
        double hScore;
        double fScore;

        Node(BlockPos position, Node parent, double gScore, double hScore) {
            this.position = position;
            this.parent = parent;
            this.gScore = gScore;
            this.hScore = hScore;
            this.fScore = gScore + hScore;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(this.fScore, other.fScore);
        }
    }

    private static final long PATHFIND_TIMEOUT_MS = 10_000L; // 10 second hard cap

    public static List<PathNode> calculatePath(BlockPos start, BlockPos target, ServerWorld world) {
        return calculatePath(start, target, world, null, null);
    }

    /**
     * Plans for a known bot. A per-bot {@link PathFinderOverrides} entry wins over the global
     * {@link #USE_BARITONE_STYLE} flag (debug/A-B only, 1.1.240 slice D). When the shadow
     * compare is on, the OTHER finder is queued on the same start/goal for one
     * {@code [PathCompare]} line; its result is never used here.
     */
    public static List<PathNode> calculatePath(BlockPos start, BlockPos target, ServerWorld world,
                                               UUID botUuid, String botName) {
        PathFinderChoice choice = PathFinderOverrides.resolveFor(botUuid, USE_BARITONE_STYLE);
        long startTime = System.nanoTime();
        List<PathNode> result = runFinder(choice, start, target, world);
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        LOGGER.info("[PathFinder] mode={} elapsed={}ms pathLen={} from={} to={}",
                choice.label(), elapsedMs, result.size(), start.toShortString(), target.toShortString());
        if (botName != null && PathCompareService.isEnabled()) {
            PathCompareService.submit(botName, start, target, world, choice, elapsedMs,
                    PathCompareService.positions(result));
        }
        return result;
    }

    /**
     * Runs one finder with the timeout it normally gets (Baritone: its primary timeout; bidir:
     * {@link #PATHFIND_TIMEOUT_MS}). No {@code [PathFinder] mode=} line, so the shadow compare
     * can call it without skewing the mode counts.
     */
    static List<PathNode> runFinder(PathFinderChoice choice, BlockPos start, BlockPos target, ServerWorld world) {
        if (choice == PathFinderChoice.BARITONE) {
            return BaritoneStylePathFinder.calculatePath(start, target, world);
        }
        return calculatePathBidirectional(start, target, world);
    }

    /**
     * Re-checks a finished path against {@link PathMovePolicy} with the world, the way the
     * finders read it: every consecutive pair must be one horizontal step apart and an
     * executable edge (standing landing, headroom for a rise, head lane for a drop). Diagonal
     * corner clearance is not re-checked (the finder owns it). An empty or one-node path has no
     * edges and passes; callers map an empty path to "none" first.
     */
    public static boolean isPathExecutable(List<BlockPos> path, ServerWorld world) {
        if (path == null || world == null) {
            return false;
        }
        for (int i = 0; i + 1 < path.size(); i++) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(i + 1);
            int dy = b.getY() - a.getY();
            if (!PathCompareFormat.isAdjacentStep(b.getX() - a.getX(), b.getZ() - a.getZ())) {
                return false;
            }
            boolean bodyDry = isPassable(world, b);
            boolean bodyWet = !bodyDry && isWadeable(world, b);
            if (!PathMovePolicy.isExecutable(dy,
                    hasSupportedFloor(world, b),
                    bodyDry,
                    bodyWet,
                    isHeadClear(world, b.up(), bodyWet),
                    dy > 0 && isPassable(world, a.up(2)),
                    dy < 0 && isPassable(world, b.up(2)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Quick reachability probe. Returns true if the Baritone pathfinder finds ANY path
     * (even partial) within the given time budget. Does NOT cache or return the path.
     * Use for "can I walk there?" decisions without committing to full navigation.
     */
    public static boolean canReach(BlockPos start, BlockPos goal, ServerWorld world, long timeoutMs) {
        return !BaritoneStylePathFinder.calculatePath(start, goal, world, timeoutMs).isEmpty();
    }

    /** Original bidirectional A* implementation. */
    static List<PathNode> calculatePathBidirectional(BlockPos start, BlockPos target, ServerWorld world) {
        if (start.equals(target)) {
            // Same shape as a merged search result (start..goal inclusive), just one node long.
            // Without this the two frontiers never overlap and the search burns its full timeout.
            return tagBlocks(List.of(start), world);
        }
        LOGGER.info("Starting Bi-directional A* pathfinding with block tagging...");

        PriorityQueue<Node> openForward = new PriorityQueue<>();
        PriorityQueue<Node> openBackward = new PriorityQueue<>();
        Map<BlockPos, Node> openMapForward = new HashMap<>();
        Map<BlockPos, Node> openMapBackward = new HashMap<>();
        Map<BlockPos, Node> closedForward = new HashMap<>();
        Map<BlockPos, Node> closedBackward = new HashMap<>();

        Node startNode = new Node(start, null, 0, getDistance(start, target));
        Node goalNode = new Node(target, null, 0, getDistance(target, start));

        openForward.add(startNode);
        openMapForward.put(start, startNode);

        openBackward.add(goalNode);
        openMapBackward.put(target, goalNode);

        long deadline = System.currentTimeMillis() + PATHFIND_TIMEOUT_MS;
        int iterations = 0;
        CampfireMemo campfires = new CampfireMemo(world);

        while (!openForward.isEmpty() && !openBackward.isEmpty()) {
            // Time-limit check every 512 iterations to avoid System.currentTimeMillis() overhead
            if ((++iterations & 0x1FF) == 0 && System.currentTimeMillis() > deadline) {
                LOGGER.warn("A* pathfinding timed out after {}ms ({} iterations) from {} to {}",
                        PATHFIND_TIMEOUT_MS, iterations, start.toShortString(), target.toShortString());
                return new ArrayList<>();
            }

            // ---------- Expand forward ----------
            Node currentForward = openForward.poll();
            openMapForward.remove(currentForward.position);
            closedForward.put(currentForward.position, currentForward);

            for (BlockPos neighbor : getNeighbors(currentForward.position, world, campfires)) {
                if (closedForward.containsKey(neighbor)) continue;

                double tentativeG = currentForward.gScore + getDistance(currentForward.position, neighbor)
                        + PathMovePolicy.wadeCost(isWadeable(world, neighbor))
                        + net.wcfcarolina13.GameAI.services.navigation.NavHazardCache.penaltyFor(world, neighbor)
                        - net.wcfcarolina13.GameAI.services.navigation.PassageAnchorService.bonusFor(world, neighbor);

                if (openMapForward.containsKey(neighbor)) {
                    Node existing = openMapForward.get(neighbor);
                    if (tentativeG < existing.gScore) {
                        existing.gScore = tentativeG;
                        existing.parent = currentForward;
                        existing.fScore = tentativeG + existing.hScore;
                        // Re-heapify by removing + re-adding
                        openForward.remove(existing);
                        openForward.add(existing);
                    }
                } else {
                    Node neighborNode = new Node(neighbor, currentForward, tentativeG, getDistance(neighbor, target));
                    openForward.add(neighborNode);
                    openMapForward.put(neighbor, neighborNode);
                }

                if (closedBackward.containsKey(neighbor)) {
                LOGGER.debug("Path overlap detected; merging paths");
                    List<BlockPos> rawPath = mergePaths(openMapForward.get(neighbor), closedBackward.get(neighbor));
                    return tagBlocks(rawPath, world);
                }
            }

            // ---------- Expand backward ----------
            Node currentBackward = openBackward.poll();
            openMapBackward.remove(currentBackward.position);
            closedBackward.put(currentBackward.position, currentBackward);

            for (BlockPos neighbor : getNeighbors(currentBackward.position, world, campfires)) {
                if (closedBackward.containsKey(neighbor)) continue;

                double tentativeG = currentBackward.gScore + getDistance(currentBackward.position, neighbor)
                        + PathMovePolicy.wadeCost(isWadeable(world, neighbor))
                        + net.wcfcarolina13.GameAI.services.navigation.NavHazardCache.penaltyFor(world, neighbor)
                        - net.wcfcarolina13.GameAI.services.navigation.PassageAnchorService.bonusFor(world, neighbor);

                if (openMapBackward.containsKey(neighbor)) {
                    Node existing = openMapBackward.get(neighbor);
                    if (tentativeG < existing.gScore) {
                        existing.gScore = tentativeG;
                        existing.parent = currentBackward;
                        existing.fScore = tentativeG + existing.hScore;
                        openBackward.remove(existing);
                        openBackward.add(existing);
                    }
                } else {
                    Node neighborNode = new Node(neighbor, currentBackward, tentativeG, getDistance(neighbor, start));
                    openBackward.add(neighborNode);
                    openMapBackward.put(neighbor, neighborNode);
                }

                if (closedForward.containsKey(neighbor)) {
                    LOGGER.info("Path overlap detected! Merging paths...");
                    List<BlockPos> rawPath = mergePaths(closedForward.get(neighbor), openMapBackward.get(neighbor));
                    return tagBlocks(rawPath, world);
                }
            }
        }

        LOGGER.warn("No path found between {} and {}", start, target);
        return new ArrayList<>();
    }


    public static List<PathNode> simplifyPath(List<PathNode> path, ServerWorld world) {
        if (path.isEmpty()) return path;

        List<PathNode> simplifiedPath = new ArrayList<>();
        PathNode prev = null;

        for (PathNode current : path) {
            BlockPos pos = current.pos;

            BlockPos feetPos = pos.down();
            BlockPos bodyPos = pos;
            BlockPos headPos = pos.up();

            boolean solidBelow = isSolidBlock(world, feetPos);
            boolean bodyDry = isPassable(world, bodyPos);
            boolean bodyWet = !bodyDry && isWadeable(world, bodyPos);
            boolean headClear = isHeadClear(world, headPos, bodyWet);

            boolean canStand = solidBelow && (bodyDry || bodyWet) && headClear;
            boolean jumpNeeded = current.jumpNeeded();

            // 1.1.240: never lift a node onto the block above it (that turned a planned cell
            // into a hop nobody checked). The first node is the bot's own cell and stays where
            // it is even mid-jump; a later cell that stopped being standable since planning
            // is skipped, as before.
            if (!canStand && !simplifiedPath.isEmpty()) {
                LOGGER.debug("Unwalkable cell at {}, skipping.", pos);
                continue;
            }

            String feetBlockType = world.getBlockState(feetPos).getBlock().getName().getString().toLowerCase();

            PathNode node = new PathNode(pos, feetBlockType, canStand, jumpNeeded);

            if (prev == null || !prev.pos.equals(pos)) {
                simplifiedPath.add(node);
                prev = node;
            } else {
                LOGGER.debug("Duplicate node at {}, skipping.", pos);
            }
        }

        LOGGER.debug("Simplified path length after filtering: {}", simplifiedPath.size());
        return simplifiedPath;
    }


    public static Queue<Segment> convertPathToSegments(List<PathNode> simplifiedPath, boolean sprint) {
        Queue<Segment> segments = new LinkedList<>();

        if (simplifiedPath.isEmpty()) {
            return segments;
        }

        PathNode segmentStart = simplifiedPath.get(0);
        String currentAxis = null;
        String currentDirectionKey = null;

        for (int i = 1; i < simplifiedPath.size(); i++) {
            PathNode prev = simplifiedPath.get(i - 1);
            PathNode current = simplifiedPath.get(i);

            // Calculate the difference
            int dx = current.getPos().getX() - prev.getPos().getX();
            int dz = current.getPos().getZ() - prev.getPos().getZ();
            int dy = current.getPos().getY() - prev.getPos().getY();

            String axis = movementAxis(dx, dy, dz);
            if (axis == null) {
                // No movement, skip
                continue;
            }
            String directionKey = movementDirectionKey(dx, dy, dz);

            // Initialize axis and sign for the first comparison
            if (currentAxis == null) {
                currentAxis = axis;
                currentDirectionKey = directionKey;
            }

            boolean axisChanged = !axis.equals(currentAxis);
            boolean directionChanged = !Objects.equals(directionKey, currentDirectionKey);
            boolean verticalStep = dy != 0;
            int segmentSpan = Math.abs(current.getPos().getX() - segmentStart.getPos().getX())
                    + Math.abs(current.getPos().getY() - segmentStart.getPos().getY())
                    + Math.abs(current.getPos().getZ() - segmentStart.getPos().getZ());
            boolean spanBreak = segmentSpan > MAX_SEGMENT_MANHATTAN_SPAN;

            // Check if a jump is needed at either the start or end of the segment
            boolean jumpBreak = current.jumpNeeded() || prev.jumpNeeded();

            if (jumpBreak || verticalStep || axisChanged || directionChanged || spanBreak) {
                // Create the segment up to prev node, use jump flag from either node
                if (!segmentStart.getPos().equals(prev.getPos())) { // Skip zero-length segments
                    segments.add(new Segment(segmentStart.getPos(), prev.getPos(), segmentStart.jumpNeeded() || prev.jumpNeeded(), sprint));
                }
                segmentStart = prev;

                // Update current direction state
                currentAxis = axis;
                currentDirectionKey = directionKey;
            }
        }

        // Add final segment, use jump flag from either start or end node
        PathNode lastNode = simplifiedPath.get(simplifiedPath.size() - 1);
        if (!segmentStart.getPos().equals(lastNode.getPos())) { // Skip zero-length segments
            segments.add(new Segment(segmentStart.getPos(), lastNode.getPos(), segmentStart.jumpNeeded() || lastNode.jumpNeeded(), sprint));
        }

        return segments;
    }

    static String movementAxis(int dx, int dy, int dz) {
        if (dx != 0 && dz != 0) {
            return "diag";
        }
        if (dx != 0) {
            return "x";
        }
        if (dz != 0) {
            return "z";
        }
        if (dy != 0) {
            return "y";
        }
        return null;
    }

    static String movementDirectionKey(int dx, int dy, int dz) {
        String axis = movementAxis(dx, dy, dz);
        if (axis == null) {
            return null;
        }
        if ("diag".equals(axis)) {
            return Integer.signum(dx) + "," + Integer.signum(dz);
        }
        if ("x".equals(axis)) {
            return Integer.toString(Integer.signum(dx));
        }
        if ("z".equals(axis)) {
            return Integer.toString(Integer.signum(dz));
        }
        return Integer.toString(Integer.signum(dy));
    }

    // These two are outdated methods, but I am still keeping them for a while.

//    public static List<String> identifyPrimaryAxis(List<BlockPos> path) {
//
//        int xChanges = 0, yChanges = 0, zChanges = 0;
//
//        BlockPos prevPos = path.get(0);
//
//
//
//        for (BlockPos pos : path) {
//
//            if (pos.getX() != prevPos.getX()) xChanges++;
//
//            if (pos.getY() != prevPos.getY()) yChanges++;
//
//            if (pos.getZ() != prevPos.getZ()) zChanges++;
//
//            prevPos = pos;
//
//        }
//
//
//
//        List<String> axisPriorityList = new ArrayList<>();
//
//        if (xChanges > 0) axisPriorityList.add("x");
//
//        if (yChanges > 0) axisPriorityList.add("y");
//
//        if (zChanges > 0) axisPriorityList.add("z");
//
//
//
//        final int finalX = xChanges, finalY = yChanges, finalZ = zChanges;
//
//
//
//        axisPriorityList.sort((a, b) -> {
//
//            int aChanges = a.equals("x") ? finalX : a.equals("y") ? finalY : finalZ;
//
//            int bChanges = b.equals("x") ? finalX : b.equals("y") ? finalY : finalZ;
//
//            return Integer.compare(bChanges, aChanges);
//
//        });
//
//
//
//        return axisPriorityList;
//
//    }
//
//
//    private static boolean isSameDirection(BlockPos start, BlockPos current, BlockPos segmentStart) {
//        int dx1 = Integer.signum(segmentStart.getX() - start.getX());
//        int dz1 = Integer.signum(segmentStart.getZ() - start.getZ());
//
//        int dx2 = Integer.signum(current.getX() - start.getX());
//        int dz2 = Integer.signum(current.getZ() - start.getZ());
//
//        return dx1 == dx2 && dz1 == dz2;
//    }


    private static List<PathNode> tagBlocks(List<BlockPos> rawPath, ServerWorld world) {
        List<PathNode> taggedPath = new ArrayList<>();
        int wadingCells = 0;

        for (int i = 0; i < rawPath.size(); i++) {
            BlockPos pos = rawPath.get(i);
            BlockPos feetPos = pos.down();
            BlockPos bodyPos = pos;
            BlockPos headPos = pos.up();

            boolean solidBelow = isSolidBlock(world, feetPos);
            boolean bodyIsSlab = isSlab(world, bodyPos);
            boolean canStepUpSlab = solidBelow && bodyIsSlab && isPassable(world, headPos);
            boolean bodyDry = isPassable(world, bodyPos);
            boolean bodyWet = !bodyDry && isWadeable(world, bodyPos);
            boolean bodyClear = bodyDry || bodyWet;
            boolean headClear = isHeadClear(world, headPos, bodyWet);

            boolean canStand = solidBelow && bodyClear && headClear;
            boolean jumpRequired = false;

            BlockState bodyState = world.getBlockState(bodyPos);
            boolean isCrop = bodyState.isIn(BlockTags.CROPS);

            if (bodyClear && isCrop) {
                LOGGER.debug("Detected crops at {}, setting jumpRequired=false", pos);
                jumpRequired = false;
            }

            // Extra: check for stepping up
            if (i > 0) {
                BlockPos prev = rawPath.get(i - 1);
                int dy = pos.getY() - prev.getY();

                if (dy > 0) {
                    // Upward step
                    if (bodyIsSlab || canStepUpSlab) {
                        jumpRequired = false; // treat slab as walkable step
                        LOGGER.debug("Stepping up onto slab at {}, no jump needed", pos);
                    } else {
                        jumpRequired = true;
                        LOGGER.debug("Stepping up full block at {}, jump required", pos);
                    }
                } else {
                    // Same level: check block in front
                    Vec3i dir = pos.subtract(prev);
                    BlockPos forward = prev.add(dir.getX(), 0, dir.getZ());
                    if (isSolidBlock(world, forward) && !isSlab(world, forward)) {
                        jumpRequired = true;
                    } else if (isSlab(world, forward)) {
                        LOGGER.debug("Stepping onto slab in front at {}, no jump needed", forward);
                        jumpRequired = false;
                    }
                }
            }

            // 1.1.240: nodes are never lifted or dropped. getNeighbors only yields standing
            // cells joined by executable edges, so:
            //  - index 0 is the bot's own cell (may be mid-jump or in a slab): emitted as-is;
            //  - the goal may be a block cell (a chest, a log, the ground under a stand
            //    spot): it is trimmed and the walk ends on the standing cell next to it;
            //  - any other non-standing cell breaks the invariant: the path is refused.
            if (!canStand && i > 0) {
                if (i == rawPath.size() - 1) {
                    LOGGER.debug("Goal {} is not a standing cell; path ends at the cell before it", pos);
                    continue;
                }
                LOGGER.warn("[PathFinder] Refusing path: node {} of {} at {} is not a standing cell",
                        i, rawPath.size(), pos.toShortString());
                return new ArrayList<>();
            }

            String feetBlockType = world.getBlockState(feetPos).getBlock().getName().getString().toLowerCase();
            PathNode node = new PathNode(pos, feetBlockType, canStand, jumpRequired);
            taggedPath.add(node);
            if (bodyWet && i > 0) {
                wadingCells++;
            }

            LOGGER.debug("Tag {}: solidBelow={} bodyClear={} headClear={} canStand={} jumpRequired={} canStepUpSlab={}",
                    pos, solidBelow, bodyClear, headClear, canStand, jumpRequired, canStepUpSlab);
        }

        if (wadingCells > 0) {
            LOGGER.info("[PathFinder] Path wades {} of {} cells", wadingCells, taggedPath.size());
        }
        return taggedPath;
    }



    private static boolean isSlab(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isIn(BlockTags.SLABS);
    }



    private static boolean isPassable(ServerWorld world, BlockPos pos) {
        BlockState blockState = world.getBlockState(pos);
        // Deadly blocks (fire, soul fire, lava, magma, campfires, cactus, sweet berry
        // bush, wither rose, powder snow, pointed dripstone) — reject as path cells so
        // the bot routes around them. Some have empty collision shapes (fire) and
        // would otherwise be walked into.
        if (net.wcfcarolina13.GameAI.services.BotHazardService.isDeadlyBlock(blockState)) {
            return false;
        }
        if (blockState.getBlock() instanceof DoorBlock) {
            if (blockState.isOf(Blocks.IRON_DOOR)) {
                return blockState.getCollisionShape(world, pos).isEmpty();
            }
            if (net.wcfcarolina13.GameAI.services.LockableBlockService.isLocked(world, pos)) {
                return false;
            }
            return true;
        }
        if (blockState.getBlock() instanceof TrapdoorBlock) {
            if (blockState.isOf(Blocks.IRON_TRAPDOOR)) {
                return blockState.getCollisionShape(world, pos).isEmpty();
            }
            if (net.wcfcarolina13.GameAI.services.LockableBlockService.isLocked(world, pos)) {
                return false;
            }
            return true;
        }
        // Lava has an empty collision shape but must be rejected.
        FluidState fluid = world.getFluidState(pos);
        if (!fluid.isEmpty()) {
            return false;
        }
        // Air OR thin walkable partial (carpets, pressure plates, rails, tripwire, lily pad, ...).
        // Slabs/stairs/snow layers are NOT whitelisted here — those are handled separately by the
        // step-up logic in tagBlocks (the bot walks onto their top surface, it doesn't walk through
        // the cell laterally). See WalkablePartialBlocks for the rationale.
        return blockState.isAir()
                || net.wcfcarolina13.GameAI.services.WalkablePartialBlocks.isPathable(blockState, world, pos);
    }


    /** Shallow water a body can wade through (see {@link WadeableWater}). */
    private static boolean isWadeable(ServerWorld world, BlockPos pos) {
        return WadeableWater.isWadeable(world.getBlockState(pos), world, pos);
    }

    /**
     * Head cell: always dry passable (never fluid). Over a wading body it must also have no
     * collision (a lily pad on the pond sits at head height).
     */
    private static boolean isHeadClear(ServerWorld world, BlockPos head, boolean overWater) {
        if (!isPassable(world, head)) {
            return false;
        }
        return !overWater || WadeableWater.headFitsOverWater(world.getBlockState(head), world, head);
    }

    private static boolean isSolidBlock(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && state.isOpaque();
    }

    private static boolean hasSupportedFloor(ServerWorld world, BlockPos pos) {
        if (isSolidBlock(world, pos.down())) return true;
        return false;
    }


    private static boolean isWalkableBlock(String blockType) {
        return blockType.equals("air");
    }

    private static boolean isJumpableBlock(String blockType, ServerWorld world, BlockPos pos) {
        // Example: if block is <= 1 block tall and you can jump over it
        BlockPos above = pos.up();
        Block aboveBlock = world.getBlockState(above).getBlock();
        String aboveType = aboveBlock.getName().getString().toLowerCase();

        System.out.println("Block above: " + aboveType);

        return !aboveType.equals("air");  // If block above is air, we could jump it.
    }


    private static List<BlockPos> mergePaths(Node forwardNode, Node backwardNode) {
        List<BlockPos> forwardPath = reconstructPath(forwardNode);
        List<BlockPos> backwardPath = reconstructPath(backwardNode);
        backwardPath.remove(0); // Remove duplicate overlap node
        Collections.reverse(backwardPath);
        forwardPath.addAll(backwardPath);

        LOGGER.info("Bi-directional path merged. Total raw nodes: {}", forwardPath.size());
        return forwardPath;
    }

    private static List<BlockPos> reconstructPath(Node node) {
        List<BlockPos> path = new ArrayList<>();
        while (node != null) {
            path.add(0, node.position);
            node = node.parent;
        }
        return path;
    }

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Neighbours of a standing cell. Every candidate is a standing cell (floor, body, head)
     * joined by an edge {@link PathMovePolicy} calls executable: flat, step-up of one block
     * with headroom above the origin, step-down of one block with a clear head lane. There is
     * no raw vertical "Up" candidate (1.1.240) — it planned pillars no executor can walk.
     * The landing body may be shallow water on a solid floor (wading, slice E); the head may not.
     *
     * <p>The rule is symmetric for standing cells, which the bidirectional search needs: the
     * backward frontier expands with this same function, so every edge must be walkable in
     * both directions.
     */
    private static List<BlockPos> getNeighbors(BlockPos pos, ServerWorld world, CampfireMemo campfires) {
        List<BlockPos> neighbors = new ArrayList<>(6);
        // Lazily read once per node: headroom above the origin, only needed for a step-up.
        Boolean originHeadroom = null;

        for (int[] d : CARDINALS) {
            BlockPos flat = pos.add(d[0], 0, d[1]);
            boolean flatDry = isPassable(world, flat);
            boolean flatWet = !flatDry && isWadeable(world, flat);
            if (flatDry || flatWet) {
                boolean flatHeadDry = isPassable(world, flat.up());
                if (hasSupportedFloor(world, flat)) {
                    // Flat move (the body may wade, slice E).
                    if (PathMovePolicy.isExecutable(0,
                            true,
                            flatDry,
                            flatWet,
                            isHeadClear(world, flat.up(), flatWet),
                            false,
                            false)) {
                        addIfSafe(neighbors, flat, world, campfires);
                    }
                } else {
                    // Step-down: the flat cell is the landing head, flat.up() the head lane. The
                    // landing head is read strictly: floorless water in front means a water head
                    // (deep water), which is refused.
                    BlockPos drop = flat.down();
                    boolean dropDry = isPassable(world, drop);
                    boolean dropWet = !dropDry && isWadeable(world, drop);
                    if (PathMovePolicy.isExecutable(-1,
                            hasSupportedFloor(world, drop),
                            dropDry,
                            dropWet,
                            isHeadClear(world, flat, dropWet),
                            false,
                            flatHeadDry)) {
                        addIfSafe(neighbors, drop, world, campfires);
                    }
                }
            } else if (isSolidBlock(world, flat)) {
                // Step-up onto the block in front.
                BlockPos top = flat.up();
                if (originHeadroom == null) {
                    originHeadroom = isPassable(world, pos.up(2));
                }
                boolean topDry = isPassable(world, top);
                boolean topWet = !topDry && isWadeable(world, top);
                if (PathMovePolicy.isExecutable(1,
                        true,
                        topDry,
                        topWet,
                        isHeadClear(world, top.up(), topWet),
                        originHeadroom,
                        false)) {
                    addIfSafe(neighbors, top, world, campfires);
                }
            }
        }

        // Straight down onto a floor. Only passes from a floorless origin (the bot's own cell
        // read mid-jump, or a floating goal); from a standing cell the body cell is the floor.
        BlockPos below = pos.down();
        boolean belowDry = isPassable(world, below);
        boolean belowWet = !belowDry && isWadeable(world, below);
        if ((belowDry || belowWet) && PathMovePolicy.isExecutable(-1,
                hasSupportedFloor(world, below),
                belowDry,
                belowWet,
                isHeadClear(world, pos, belowWet),
                false,
                isPassable(world, pos.up()))) {
            addIfSafe(neighbors, below, world, campfires);
        }

        return neighbors;
    }

    /** Hazard filters, cheapest first: magma floor, then the (memoised) campfire berth. */
    private static void addIfSafe(List<BlockPos> neighbors, BlockPos candidate, ServerWorld world,
                                  CampfireMemo campfires) {
        // Never path onto magma blocks (solid, but deal contact damage)
        if (world.getBlockState(candidate.down()).isOf(Blocks.MAGMA_BLOCK)) {
            return;
        }
        // Keep the same 2-block berth from exposed campfires the per-candidate scan gave.
        if (campfires.isNearExposedCampfire(candidate)) {
            return;
        }
        neighbors.add(candidate);
    }

    /**
     * Per-search memo for {@link BotCampfireAvoidanceService#isNearExposedCampfire} with
     * radius 2 (box x±2, y±1, z±2), same answer at a fraction of the cost.
     *
     * <p>The old filter read 75 cells for every candidate (about 2,000 block reads per A*
     * iteration). Here each 16³ chunk section the search touches is asked once whether its
     * palette holds a campfire at all ({@code ChunkSection.hasAny}), which is near-free and
     * false almost everywhere. Only a section that does hold one is enumerated, once, and
     * every exposed campfire in it marks the cells within its berth as dangerous. A candidate
     * is then one set lookup. A candidate's box only reaches campfires in the sections its
     * own box overlaps, so scanning exactly those sections keeps the semantics exact.
     * Unloaded chunks are skipped, like the original scan, and never force-loaded.
     */
    static final class CampfireMemo {
        private static final int RADIUS_XZ = 2;
        private static final int RADIUS_Y = 1;

        private final ServerWorld world;
        private final Set<Long> scannedSections = new HashSet<>();
        private final Set<Long> dangerCells = new HashSet<>();

        CampfireMemo(ServerWorld world) {
            this.world = world;
        }

        boolean isNearExposedCampfire(BlockPos pos) {
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            for (int sx = (x - RADIUS_XZ) >> 4; sx <= (x + RADIUS_XZ) >> 4; sx++) {
                for (int sz = (z - RADIUS_XZ) >> 4; sz <= (z + RADIUS_XZ) >> 4; sz++) {
                    for (int sy = (y - RADIUS_Y) >> 4; sy <= (y + RADIUS_Y) >> 4; sy++) {
                        scanSection(sx, sy, sz);
                    }
                }
            }
            return !dangerCells.isEmpty() && dangerCells.contains(pos.asLong());
        }

        private void scanSection(int sx, int sy, int sz) {
            long key = net.minecraft.util.math.ChunkSectionPos.asLong(sx, sy, sz);
            if (!scannedSections.add(key)) {
                return;
            }
            if (!world.isChunkLoaded(sx, sz)) {
                return;
            }
            net.minecraft.world.chunk.WorldChunk chunk = world.getChunk(sx, sz);
            int index = chunk.getSectionIndex(sy << 4);
            if (index < 0 || index >= chunk.countVerticalSections()) {
                return;
            }
            net.minecraft.world.chunk.ChunkSection section = chunk.getSection(index);
            if (section == null || section.isEmpty() || !section.hasAny(CampfireMemo::isCampfire)) {
                return;
            }
            int baseX = sx << 4, baseY = sy << 4, baseZ = sz << 4;
            BlockPos.Mutable cursor = new BlockPos.Mutable();
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        if (!isCampfire(section.getBlockState(lx, ly, lz))) {
                            continue;
                        }
                        BlockPos campfire = cursor.set(baseX + lx, baseY + ly, baseZ + lz).toImmutable();
                        if (!BotCampfireAvoidanceService.isCampfireExposed(world, campfire)) {
                            continue;
                        }
                        markBerth(campfire);
                    }
                }
            }
        }

        private void markBerth(BlockPos campfire) {
            for (int dx = -RADIUS_XZ; dx <= RADIUS_XZ; dx++) {
                for (int dy = -RADIUS_Y; dy <= RADIUS_Y; dy++) {
                    for (int dz = -RADIUS_XZ; dz <= RADIUS_XZ; dz++) {
                        dangerCells.add(BlockPos.asLong(campfire.getX() + dx, campfire.getY() + dy, campfire.getZ() + dz));
                    }
                }
            }
        }

        private static boolean isCampfire(BlockState state) {
            return state != null
                    && (state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE));
        }
    }


    private static double getDistance(BlockPos pos1, BlockPos pos2) {
        return Math.sqrt(pos1.getSquaredDistance(pos2));
    }
}
