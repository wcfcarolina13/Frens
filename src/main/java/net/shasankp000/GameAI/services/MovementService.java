package net.shasankp000.GameAI.services;

import net.minecraft.fluid.FluidState;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.skills.SkillPreferences;
import net.shasankp000.GameAI.services.BlockInteractionService;
import net.shasankp000.Entity.LookController;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.PathFinding.PathFinder;
import net.shasankp000.PathFinding.Segment;
import net.shasankp000.PlayerUtils.MiningTool;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class MovementService {

    private static final Logger LOGGER = LoggerFactory.getLogger("movement-service");
    private static final double ARRIVAL_DISTANCE_SQ = 9.0D;
    private static final double CLOSE_ENOUGH_DISTANCE_SQ = 2.25D; // ~1.5 blocks
    private static final double PATH_SAME_TARGET_DISTANCE_SQ = 9.0D; // ~3 blocks
    private static final long PATH_ATTEMPT_MIN_INTERVAL_MS = 400L;
    private static final long PATH_FAILURE_BACKOFF_MS = 2500L;
    private static final ScheduledExecutorService DOOR_CLOSE_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bot-door-close");
        t.setDaemon(true);
        return t;
    });
    private static final Map<UUID, Map<BlockPos, Long>> DOOR_CLOSE_COOLDOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPos, Long>> DOOR_DEBUG_COOLDOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPos, Long>> DOOR_IRON_WARN_COOLDOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPos, Long>> DOOR_RECENTLY_CLOSED_UNTIL = new ConcurrentHashMap<>();

    private MovementService() {
    }

    private static boolean abortRequested(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        return TaskService.isAbortRequested(player.getUuid());
    }

    public enum Mode {
        DIRECT,
        WADE,
        BRIDGE
    }

    public record MovementOptions(boolean allowWade,
                                  int maxWadeDepth,
                                  boolean allowBridge,
                                  int maxBridgeDepth) {
        public static MovementOptions lootCollection() {
            return new MovementOptions(true, 1, true, 1);
        }

        public static MovementOptions skillLoot() {
            return new MovementOptions(true, 1, true, 2);
        }
    }

    public record MovementPlan(Mode mode,
                               BlockPos finalDestination,
                               BlockPos approachDestination,
                               BlockPos wadeTarget,
                               BlockPos bridgeTarget,
                               Direction direction) {
        public MovementPlan {
            if (mode == Mode.DIRECT) {
                if (finalDestination == null) {
                    throw new IllegalArgumentException("finalDestination required for DIRECT moves");
                }
                if (approachDestination == null) {
                    approachDestination = finalDestination;
                }
            }
        }
    }

    public record MovementResult(boolean success,
                                 Mode mode,
                                 BlockPos arrivedAt,
                                 String detail) {
    }

    public record DoorSubgoalPlan(BlockPos doorBase, BlockPos approachPos, BlockPos stepThroughPos, double improveSq) {}

    /**
     * Finds a nearby wooden door that would bring the bot closer to the goal if it steps through it.
     * Intended for "enclosure escape" situations where the goal is around a corner and raycasts to the goal
     * never directly intersect the door block.
     */
    public static DoorSubgoalPlan findDoorSubgoalPlan(ServerPlayerEntity bot, BlockPos goal) {
        if (bot == null || goal == null) {
            return null;
        }
        ServerWorld world = getWorld(bot);
        if (world == null) {
            return null;
        }

        BlockPos botPos = bot.getBlockPos();
        double currentGoalDistSq = botPos.getSquaredDistance(goal);

        BlockPos bestDoor = null;
        BlockPos bestApproach = null;
        BlockPos bestStep = null;
        double bestImprove = 0.0D;

        int radius = 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos scan = botPos.add(dx, dy, dz);
                    BlockState raw = world.getBlockState(scan);
                    if (!(raw.getBlock() instanceof DoorBlock)) {
                        continue;
                    }
                    BlockPos doorBase = normalizeDoorBase(world, scan);
                    if (doorBase == null) {
                        continue;
                    }
                    BlockState state = world.getBlockState(doorBase);
                    if (!(state.getBlock() instanceof DoorBlock)) {
                        continue;
                    }
                    if (state.isOf(net.minecraft.block.Blocks.IRON_DOOR)) {
                        continue;
                    }
                    if (botPos.getSquaredDistance(doorBase) > 25.0D) { // keep it local (enclosure escape)
                        continue;
                    }

                    Direction facing = state.contains(DoorBlock.FACING) ? state.get(DoorBlock.FACING) : bot.getHorizontalFacing();
                    BlockPos front = doorBase.offset(facing);
                    BlockPos back = doorBase.offset(facing.getOpposite());

                    BlockPos approach = botPos.getSquaredDistance(front) <= botPos.getSquaredDistance(back) ? front : back;
                    BlockPos step = approach.equals(front) ? back : front;

                    if (!isSolidStandable(world, approach.down(), approach) || !isSolidStandable(world, step.down(), step)) {
                        continue;
                    }

                    double improve = currentGoalDistSq - step.getSquaredDistance(goal);
                    if (improve < 1.0D) {
                        continue;
                    }

                    // If we're not right next to it, ensure this door is unobstructed (no selecting doors "through walls").
                    // Do NOT require survival reach here; we can walk to the door before interacting.
                    if (botPos.getSquaredDistance(doorBase) > 4.0D && !BlockInteractionService.canInteract(bot, doorBase, 400.0D)) {
                        continue;
                    }

                    if (improve > bestImprove) {
                        bestImprove = improve;
                        bestDoor = doorBase.toImmutable();
                        bestApproach = approach.toImmutable();
                        bestStep = step.toImmutable();
                    }
                }
            }
        }

        if (bestDoor == null || bestApproach == null || bestStep == null) {
            return null;
        }
        return new DoorSubgoalPlan(bestDoor, bestApproach, bestStep, bestImprove);
    }

    /**
     * Follow-oriented enclosure escape helper.
     * <p>
     * Similar to {@link #findDoorSubgoalPlan(ServerPlayerEntity, BlockPos)}, but does not require that stepping
     * through the door immediately reduces distance to the goal (since the correct path may initially increase it,
     * e.g. "around the corner" obstacle courses). The chosen door is still biased toward improving progress when possible.
     */
    public static DoorSubgoalPlan findDoorEscapePlan(ServerPlayerEntity bot, BlockPos goal, BlockPos avoidDoorBase) {
        if (bot == null) {
            return null;
        }
        ServerWorld world = getWorld(bot);
        if (world == null) {
            return null;
        }

        BlockPos botPos = bot.getBlockPos();
        double currentGoalDistSq = goal != null ? botPos.getSquaredDistance(goal) : 0.0D;

        BlockPos bestDoor = null;
        BlockPos bestApproach = null;
        BlockPos bestStep = null;
        double bestImprove = Double.NEGATIVE_INFINITY;
        double bestScore = Double.NEGATIVE_INFINITY;

        int radius = 14;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos scan = botPos.add(dx, dy, dz);
                    BlockState raw = world.getBlockState(scan);
                    if (!(raw.getBlock() instanceof DoorBlock)) {
                        continue;
                    }
                    BlockPos doorBase = normalizeDoorBase(world, scan);
                    if (doorBase == null) {
                        continue;
                    }
                    if (avoidDoorBase != null && avoidDoorBase.equals(doorBase)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(doorBase);
                    if (!(state.getBlock() instanceof DoorBlock)) {
                        continue;
                    }
                    if (state.isOf(net.minecraft.block.Blocks.IRON_DOOR)) {
                        continue;
                    }
                    double distToDoorSq = botPos.getSquaredDistance(doorBase);
                    if (distToDoorSq > 400.0D) { // keep it local-ish (enclosure/building escape)
                        continue;
                    }

                    Direction facing = state.contains(DoorBlock.FACING) ? state.get(DoorBlock.FACING) : bot.getHorizontalFacing();
                    BlockPos front = doorBase.offset(facing);
                    BlockPos back = doorBase.offset(facing.getOpposite());

                    BlockPos approach = botPos.getSquaredDistance(front) <= botPos.getSquaredDistance(back) ? front : back;
                    BlockPos step = approach.equals(front) ? back : front;

                    if (!isSolidStandable(world, approach.down(), approach) || !isSolidStandable(world, step.down(), step)) {
                        continue;
                    }

                    // Require unobstructed LoS to the door, but don't require survival reach (we'll path to it).
                    if (distToDoorSq > 4.0D && !BlockInteractionService.canInteract(bot, doorBase, 400.0D)) {
                        continue;
                    }

                    double improve = 0.0D;
                    if (goal != null) {
                        improve = currentGoalDistSq - step.getSquaredDistance(goal);
                    }

                    // Prefer doors that improve progress, but allow negative improvements when that's the only way out.
                    double score = improve - distToDoorSq * 0.05D;
                    if (score > bestScore) {
                        bestScore = score;
                        bestImprove = improve;
                        bestDoor = doorBase.toImmutable();
                        bestApproach = approach.toImmutable();
                        bestStep = step.toImmutable();
                    }
                }
            }
        }

        if (bestDoor == null || bestApproach == null || bestStep == null) {
            return null;
        }
        return new DoorSubgoalPlan(bestDoor, bestApproach, bestStep, bestImprove);
    }

    private record WalkResult(boolean success, BlockPos arrivedAt, String detail) {
    }

    private record SegmentResult(boolean success, boolean snapped) {
    }

    private record PathAttempt(BlockPos target, long timeMs, boolean success, String detail) {
    }

    private static final Map<UUID, PathAttempt> LAST_WALK_ATTEMPT = new ConcurrentHashMap<>();

    public static Optional<MovementPlan> planLootApproach(ServerPlayerEntity player,
                                                          BlockPos target,
                                                          MovementOptions options) {
        ServerWorld world = getWorld(player);
        if (world == null || target == null) {
            return Optional.empty();
        }

        // Prefer existing standable tiles around the target.
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos foot = target.offset(direction);
            BlockPos head = foot.up();
            if (isSolidStandable(world, foot, head)) {
                LOGGER.debug("Movement plan: direct access to {} via {}", target, head);
                return Optional.of(new MovementPlan(Mode.DIRECT, head, head, null, null, direction));
            }
        }

        // Consider water-adjacent approaches.
        for (Direction direction : Direction.Type.HORIZONTAL) {
            MovementPlan plan = evaluateWaterApproach(world, target, direction, options);
            if (plan != null) {
                LOGGER.debug("Movement plan: {} approach to {} via {}", plan.mode(), target, plan);
                return Optional.of(plan);
            }
        }

        BlockPos fallback = findNearbyStandable(world, target, 6, 6);
        if (fallback != null) {
            LOGGER.debug("Movement plan: fallback standable {} for {}", fallback, target);
            return Optional.of(new MovementPlan(Mode.DIRECT, fallback, fallback, null, null, Direction.UP));
        }

        // Fallback: stand directly on target if possible.
        BlockPos foot = target.down();
        if (isSolidStandable(world, foot, target)) {
            LOGGER.debug("Movement plan: direct stand on target {}", target);
            return Optional.of(new MovementPlan(Mode.DIRECT, target, target, null, null, Direction.UP));
        }

        LOGGER.debug("No viable movement plan for {}", target);
        return Optional.empty();
    }

    public static MovementResult execute(ServerCommandSource source,
                                         ServerPlayerEntity player,
                                         MovementPlan plan) {
        return execute(source, player, plan, null, false, true, true);
    }

    public static MovementResult execute(ServerCommandSource source,
                                         ServerPlayerEntity player,
                                         MovementPlan plan,
                                         Boolean allowTeleportOverride) {
        return execute(source, player, plan, allowTeleportOverride, false, true, true);
    }

    public static MovementResult execute(ServerCommandSource source,
                                         ServerPlayerEntity player,
                                         MovementPlan plan,
                                         Boolean allowTeleportOverride,
                                         boolean fastReplan) {
        return execute(source, player, plan, allowTeleportOverride, fastReplan, true, true);
    }

    public static MovementResult execute(ServerCommandSource source,
                                         ServerPlayerEntity player,
                                         MovementPlan plan,
                                         Boolean allowTeleportOverride,
                                         boolean fastReplan,
                                         boolean allowPursuit,
                                         boolean allowSnap) {
        if (source == null || player == null || plan == null) {
            return new MovementResult(false, Mode.DIRECT, player != null ? player.getBlockPos() : null, "Invalid movement request.");
        }
        LOGGER.info("Movement execute: mode={} dest={} allowTpOverride={} fastReplan={} player={}",
                plan.mode(), plan.finalDestination(), allowTeleportOverride, fastReplan, player.getName().getString());
        return switch (plan.mode()) {
            case DIRECT -> moveDirect(source, player, plan.finalDestination(), allowTeleportOverride, fastReplan, allowPursuit, allowSnap);
            case WADE -> moveWithWade(source, player, plan, allowTeleportOverride, fastReplan, allowPursuit, allowSnap);
            case BRIDGE -> moveWithBridge(source, player, plan, allowTeleportOverride, fastReplan, allowPursuit, allowSnap);
        };
    }

    private static MovementResult moveDirect(ServerCommandSource source,
                                             ServerPlayerEntity player,
                                             BlockPos destination,
                                             Boolean allowTeleportOverride,
                                             boolean fastReplan,
                                             boolean allowPursuit,
                                             boolean allowSnap) {
        MovementResult goTo = moveTo(source, player, destination, Mode.DIRECT, "direct", allowTeleportOverride, fastReplan, allowPursuit, allowSnap);
        if (!goTo.success()) {
            return goTo;
        }
        return new MovementResult(true, Mode.DIRECT, destination, goTo.detail());
    }

    private static MovementResult moveWithWade(ServerCommandSource source,
                                               ServerPlayerEntity player,
                                               MovementPlan plan,
                                               Boolean allowTeleportOverride,
                                               boolean fastReplan,
                                               boolean allowPursuit,
                                               boolean allowSnap) {
        MovementResult approach = moveTo(source, player, plan.approachDestination(), Mode.WADE, "wade-approach", allowTeleportOverride, fastReplan, allowPursuit, allowSnap);
        if (!approach.success()) {
            return approach;
        }
        encourageSurfaceDrift(player);

        boolean waded = performManualStep(player, plan.wadeTarget());
        String detail = waded ? "Manual wade succeeded toward " + plan.wadeTarget()
                : "Manual wade failed for " + plan.wadeTarget();
        encourageSurfaceDrift(player);
        return new MovementResult(waded, Mode.WADE, plan.wadeTarget(), detail);
    }

    private static MovementResult moveWithBridge(ServerCommandSource source,
                                                 ServerPlayerEntity player,
                                                 MovementPlan plan,
                                                 Boolean allowTeleportOverride,
                                                 boolean fastReplan,
                                                 boolean allowPursuit,
                                                 boolean allowSnap) {
        MovementResult approach = moveTo(source, player, plan.approachDestination(), Mode.BRIDGE, "bridge-approach", allowTeleportOverride, fastReplan, allowPursuit, allowSnap);
        if (!approach.success()) {
            return approach;
        }

        boolean bridged = placeBridgeBlock(player, plan.bridgeTarget());
        if (!bridged) {
            return new MovementResult(false, Mode.BRIDGE, plan.bridgeTarget(), "Unable to place bridge block at " + plan.bridgeTarget());
        }

        MovementResult finalMove = moveTo(source, player, plan.finalDestination(), Mode.BRIDGE, "bridge-final", allowTeleportOverride, fastReplan, allowPursuit, allowSnap);
        if (!finalMove.success()) {
            return finalMove;
        }
        return new MovementResult(true, Mode.BRIDGE, plan.finalDestination(), "Bridge placed and destination reached.");
    }

    private static MovementPlan evaluateWaterApproach(ServerWorld world,
                                                      BlockPos target,
                                                      Direction direction,
                                                      MovementOptions options) {
        BlockPos walkwayFoot = target.offset(direction);
        BlockPos walkwayHead = walkwayFoot.up();
        BlockPos approachFoot = walkwayFoot.offset(direction);
        BlockPos approachHead = approachFoot.up();

        boolean approachStandable = isSolidStandable(world, approachFoot, approachHead);
        boolean headClear = hasClearance(world, walkwayHead);

        if (options.allowBridge()
                && approachStandable
                && headClear
                && isBridgeCandidate(world, walkwayFoot, options.maxBridgeDepth())) {
            return new MovementPlan(Mode.BRIDGE, walkwayHead, approachHead, null, walkwayFoot, direction);
        }

        if (options.allowWade()
                && approachStandable
                && headClear
                && isShallowWater(world, walkwayFoot, options.maxWadeDepth())) {
            return new MovementPlan(Mode.WADE, target, approachHead, walkwayFoot, null, direction);
        }

        return null;
    }

    private static MovementResult moveTo(ServerCommandSource source,
                                         ServerPlayerEntity player,
                                         BlockPos destination,
                                         Mode mode,
                                         String label,
                                         Boolean allowTeleportOverride,
                                         boolean fastReplan,
                                         boolean allowPursuit,
                                         boolean allowSnap) {
        if (destination == null) {
            return new MovementResult(false, mode, player.getBlockPos(), "No destination specified for " + label);
        }
        boolean allowTeleport = allowTeleportOverride != null
                ? allowTeleportOverride
                : SkillPreferences.teleportDuringSkills(player);
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d destinationCenter = new Vec3d(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5);
        if (playerPos.squaredDistanceTo(destinationCenter) <= CLOSE_ENOUGH_DISTANCE_SQ) {
            return new MovementResult(true, mode, destination, label + ": already at destination");
        }
        if (!allowTeleport) {
            LOGGER.info("Movement choosing walk only: {} -> {}", player.getName().getString(), destination);
            WalkResult walkResult = walkTo(source, player, destination, mode, label, fastReplan, allowSnap);
            if (!walkResult.success() && allowPursuit) {
                boolean pursued = pursuitUntilClose(player, destination, TimeUnit.SECONDS.toMillis(3), CLOSE_ENOUGH_DISTANCE_SQ, label);
                if (pursued) {
                    return new MovementResult(true, mode, destination, label + ": pursuit fallback");
                }
            }
            return new MovementResult(walkResult.success(), mode, walkResult.arrivedAt(), walkResult.detail());
        }
        // Prefer walking when close or visible; reserve teleport for long-range catch-up.
        double distanceSq = player.getBlockPos().getSquaredDistance(destination);
        if (mode == Mode.DIRECT && distanceSq <= 256) {
            LOGGER.info("Movement prefers walk (near): {} -> {}", player.getName().getString(), destination);
            WalkResult walkResult = walkTo(source, player, destination, mode, label, fastReplan, allowSnap);
            if (walkResult.success()) {
                return new MovementResult(true, mode, walkResult.arrivedAt(), label + ": walked");
            }
            LOGGER.info("Walk failed near {} ({}), trying teleport fallback", destination, walkResult.detail());
        }
        if (distanceSq <= 900) {
            WalkResult walkResult = walkTo(source, player, destination, mode, label, fastReplan, allowSnap);
            if (walkResult.success()) {
                return new MovementResult(true, mode, walkResult.arrivedAt(), label + ": walked");
            }
            LOGGER.info("Walk failed mid-range near {} ({}), trying teleport fallback", destination, walkResult.detail());
        }

        String rawResult = GoTo.goTo(source, destination.getX(), destination.getY(), destination.getZ(), false);
        boolean success = isGoToSuccess(rawResult);
        if (success) {
            BlockPos post = player.getBlockPos();
            double postDistanceSq = post.getSquaredDistance(destination);
            success = postDistanceSq <= ARRIVAL_DISTANCE_SQ;
            if (!success) {
                rawResult = rawResult + " (ended " + Math.sqrt(postDistanceSq) + " blocks away)";
            } else if (mode == Mode.WADE) {
                encourageSurfaceDrift(player);
            }
        } else if (mode == Mode.WADE) {
            encourageSurfaceDrift(player);
        }
        LOGGER.info("Movement result: mode={} dest={} success={} detail={}", mode, destination, success, rawResult);
        return new MovementResult(success, mode, destination, label + ": " + rawResult);
    }

    private static WalkResult walkTo(ServerCommandSource source,
                                     ServerPlayerEntity player,
                                     BlockPos destination,
                                     Mode mode,
                                     String label,
                                     boolean fastReplan,
                                     boolean allowSnap) {
        ServerWorld world = getWorld(player);
        BlockPos currentPos = player != null ? player.getBlockPos() : null;
        if (world == null || destination == null) {
            return recordAttempt(player != null ? player.getUuid() : null, destination,
                    new WalkResult(false, currentPos, label + ": no world/destination for walking"));
        }
        if (abortRequested(player)) {
            BotActions.stop(player);
            return recordAttempt(player.getUuid(), destination,
                    new WalkResult(false, currentPos, label + ": aborted"));
        }

        LOGGER.debug("walkTo start: bot={} dest={} label={} fastReplan={} currentPos={} dist={}",
                player.getName().getString(),
                destination.toShortString(),
                label,
                fastReplan,
                currentPos != null ? currentPos.toShortString() : "null",
                currentPos != null ? String.format("%.2f", Math.sqrt(currentPos.getSquaredDistance(destination))) : "n/a");
        WalkResult throttled = fastReplan ? null : maybeThrottle(player.getUuid(), destination, currentPos, label);
        if (throttled != null) {
            return throttled;
        }

        // For very short moves, avoid pathfinding entirely (it can be expensive and noisy).
        if (currentPos != null) {
            double distSq = currentPos.getSquaredDistance(destination);
            int dy = Math.abs(destination.getY() - currentPos.getY());
            if (distSq <= 144.0D && dy <= 2) {
                boolean direct = walkDirect(player, destination, fastReplan ? 2200L : 3600L);
                if (direct) {
                    return recordAttempt(player.getUuid(), destination,
                            new WalkResult(true, destination, label + ": walked directly"));
                }
            }
        }

        long deadline = System.currentTimeMillis() + (fastReplan ? TimeUnit.SECONDS.toMillis(5) : TimeUnit.SECONDS.toMillis(20));
        BlockPos lastReached = player.getBlockPos();
        boolean replanned = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (abortRequested(player)) {
                BotActions.stop(player);
                return recordAttempt(player.getUuid(), destination,
                        new WalkResult(false, player.getBlockPos(), label + ": aborted"));
            }
            List<PathFinder.PathNode> rawPath = PathFinder.calculatePath(player.getBlockPos(), destination, world);
            List<PathFinder.PathNode> simplified = PathFinder.simplifyPath(rawPath, world);
            Queue<Segment> segments = PathFinder.convertPathToSegments(simplified, shouldSprintForDestination(player, destination));
            LOGGER.debug("walkTo path (attempt={}): raw={} simplified={} segments={} firstSegment={}",
                    attempt + 1,
                    rawPath.size(),
                    simplified.size(),
                    segments.size(),
                    segments.peek());
            if (segments.isEmpty()) {
                boolean straightWalk = walkDirect(player, destination, fastReplan ? TimeUnit.SECONDS.toMillis(3) : TimeUnit.SECONDS.toMillis(6));
                if (straightWalk) {
                    return recordAttempt(player.getUuid(), destination,
                            new WalkResult(true, destination, label + ": walked directly (no path)"));
                }
                return recordAttempt(player.getUuid(), destination,
                        new WalkResult(false, player.getBlockPos(), label + ": no walkable path"));
            }

            boolean allOk = true;
            for (Segment segment : segments) {
                SegmentResult seg = walkSegment(player, segment, deadline, allowSnap);
                if (!seg.success()) {
                    lastReached = player.getBlockPos();
                    allOk = false;
                    if (!replanned) {
                        replanned = true;
                        LOGGER.debug("walkTo replan after stall near {}", segment.end());
                        break; // rebuild path from new position
                    }
                    return recordAttempt(player.getUuid(), destination,
                            new WalkResult(false, lastReached, label + ": walk blocked near " + segment.end()));
                }
                lastReached = segment.end();
            }
            if (allOk) {
                double finalDistanceSq = player.getBlockPos().getSquaredDistance(destination);
                boolean arrived = finalDistanceSq <= ARRIVAL_DISTANCE_SQ;
                String detail = arrived
                        ? label + ": walked to destination"
                        : label + ": walk ended " + String.format("%.2f", Math.sqrt(finalDistanceSq)) + " blocks away";
                return recordAttempt(player.getUuid(), destination,
                        new WalkResult(arrived, player.getBlockPos(), detail));
            }
        }
        return recordAttempt(player.getUuid(), destination,
                new WalkResult(false, player.getBlockPos(), label + ": walk blocked after replans"));
    }

    private static WalkResult maybeThrottle(UUID botId, BlockPos destination, BlockPos currentPos, String label) {
        if (botId == null) {
            return null;
        }
        PathAttempt last = LAST_WALK_ATTEMPT.get(botId);
        if (last == null) {
            return null;
        }
        if (last.success()) {
            return null; // do not throttle successful walks
        }
        long now = System.currentTimeMillis();
        if (now - last.timeMs < PATH_ATTEMPT_MIN_INTERVAL_MS) {
            return new WalkResult(false, currentPos,
                    label + ": throttled (recent path attempt) – last: " + last.detail());
        }
        if (!last.success()
                && last.target() != null
                && destination != null
                && last.target().getSquaredDistance(destination) <= PATH_SAME_TARGET_DISTANCE_SQ
                && now - last.timeMs < PATH_FAILURE_BACKOFF_MS) {
            return new WalkResult(false, currentPos,
                    label + ": cooling down after failure near " + last.target().toShortString());
        }
        return null;
    }

    private static WalkResult recordAttempt(UUID botId, BlockPos destination, WalkResult result) {
        if (botId != null && destination != null && result != null) {
            LAST_WALK_ATTEMPT.put(botId, new PathAttempt(destination, System.currentTimeMillis(), result.success(), result.detail()));
        }
        return result;
    }

    public static void clearRecentWalkAttempt(UUID botId) {
        if (botId != null) {
            LAST_WALK_ATTEMPT.remove(botId);
        }
    }

    private static boolean shouldSprintForDestination(ServerPlayerEntity player, BlockPos destination) {
        if (player == null || destination == null) {
            return false;
        }
        // Prefer sprinting for medium+ moves; short shuffles are more stable as walk.
        return player.getBlockPos().getSquaredDistance(destination) > 16.0D; // >4 blocks
    }

    private static SegmentResult walkSegment(ServerPlayerEntity player, Segment segment, long deadlineMs, boolean allowSnap) {
        if (player == null || segment == null) {
            return new SegmentResult(false, false);
        }
        if (abortRequested(player)) {
            BotActions.stop(player);
            return new SegmentResult(false, false);
        }
        Vec3d target = centerOf(segment.end());
        // Scale effort with distance so long hallway segments don't fail after a handful of steps.
        double segmentDistance = Math.sqrt(player.squaredDistanceTo(target));
        long now = System.currentTimeMillis();
        long remainingBudget = Math.max(0L, deadlineMs - now);
        long segmentAllowance = Math.min(remainingBudget, Math.max(900L, (long) (segmentDistance * 300))); // allow slower tick progression
        long endTime = now + segmentAllowance;
        int maxSteps = (int) Math.min(160, Math.max(16, Math.ceil(segmentDistance * 14)));
        LOGGER.debug("walkSegment start: from={} to={} dist={}/steps={} allowanceMs={}",
                player.getBlockPos().toShortString(),
                segment.end().toShortString(),
                String.format("%.2f", segmentDistance),
                maxSteps,
                segmentAllowance);

        double lastDistanceSq = Double.MAX_VALUE;
        int stagnantSteps = 0;

        for (int steps = 0; System.currentTimeMillis() < endTime && steps < maxSteps; steps++) {
            if (abortRequested(player)) {
                BotActions.stop(player);
                return new SegmentResult(false, false);
            }
            double distanceSq = player.squaredDistanceTo(target);
            if (distanceSq <= CLOSE_ENOUGH_DISTANCE_SQ) {
                BotActions.stop(player);
                return new SegmentResult(true, false);
            }
            if (steps == 0) {
                LOGGER.debug("walkSegment step0 pos={} dist={}", player.getBlockPos().toShortString(), Math.sqrt(distanceSq));
            }
            // Bail out early if we stopped making progress (e.g., collision).
            if (distanceSq >= lastDistanceSq - 0.01) {
                stagnantSteps++;
	                if (stagnantSteps == 3) {
	                    if (tryOpenDoorToward(player, segment.end())) {
	                        stagnantSteps = 0;
	                        lastDistanceSq = Double.MAX_VALUE;
	                        continue;
	                    }
	                    // If the door is already open but we're not crossing the threshold, step through it decisively.
	                    if (tryTraverseDoorway(player, segment.end(), "walkSegment")) {
	                        stagnantSteps = 0;
	                        lastDistanceSq = Double.MAX_VALUE;
	                        continue;
	                    }
	                    if (tryDoorSubgoalToward(player, segment.end(), "walkSegment")) {
	                        stagnantSteps = 0;
	                        lastDistanceSq = Double.MAX_VALUE;
	                        continue;
	                    }
	                }
                if (stagnantSteps >= 5) {
                    if (trySidestepAround(player, segment.end())) {
                        // Reset progress tracking after a successful sidestep.
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        continue;
                    }
                    LOGGER.debug("walkSegment stalled near {} dist={}", segment.end(), Math.sqrt(distanceSq));
                    break;
                }
            } else {
                stagnantSteps = 0;
                lastDistanceSq = distanceSq;
            }
            if (!inputStepToward(player, segment.end(), target, segment.sprint(), segment.jump())) {
                return new SegmentResult(false, false);
            }
            sleep(35L);
        }
        double remaining = Math.sqrt(player.squaredDistanceTo(target));
        LOGGER.debug("walkSegment timed out near {} remainingDist={}", segment.end(), remaining);
        // Force a small snap forward when very close to planned segment end to avoid infinite loop.
        if (allowSnap && remaining <= 2.2D) {
            LOGGER.warn("walkSegment snap forward to {}", segment.end());
            snapTo(player, segment.end());
            return new SegmentResult(true, true);
        }
        return new SegmentResult(false, false);
    }

    public static boolean tryOpenDoorToward(ServerPlayerEntity player, BlockPos goal) {
        ServerWorld world = getWorld(player);
        if (player == null || goal == null || world == null) {
            return false;
        }
        Direction toward = approximateToward(player.getBlockPos(), goal);
        // Doors are often 1-2 blocks ahead when we're boxed in; probe a couple positions.
        for (int ahead = 1; ahead <= 2; ahead++) {
            BlockPos candidate = player.getBlockPos().offset(toward, ahead);
            if (tryOpenDoorAt(player, candidate)) {
                return true;
            }
        }
        return false;
    }

    public static boolean tryOpenDoorAt(ServerPlayerEntity player, BlockPos candidate) {
        ServerWorld world = getWorld(player);
        if (player == null || candidate == null || world == null) {
            return false;
        }
        BlockPos doorPos = normalizeDoorBase(world, candidate);
        if (doorPos == null) {
            return false;
        }
        BlockState state = world.getBlockState(doorPos);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return false;
        }
        if (state.isOf(net.minecraft.block.Blocks.IRON_DOOR)) {
            maybeWarnIronDoor(player, doorPos);
            return false;
        }
        if (state.contains(DoorBlock.OPEN) && Boolean.TRUE.equals(state.get(DoorBlock.OPEN))) {
            return false; // already open
        }
        if (!doorAttemptAllowed(player.getUuid(), doorPos)) {
            return false;
        }
        // Enforce survival-like interaction constraints; prevents opening through walls.
        double selfDoorDistSq = player.getBlockPos().getSquaredDistance(doorPos);
        if (selfDoorDistSq > 1.0D && !BlockInteractionService.canInteract(player, doorPos)) {
            maybeLogDoor(player, doorPos, "door-open blocked: cannot interact (reach/LoS)");
            return false;
        }

        Direction toward = approximateToward(player.getBlockPos(), doorPos);
        if (!toward.getAxis().isHorizontal()) {
            toward = player.getHorizontalFacing();
        }
        final Direction hitFacing = toward;
        final Direction travelDir = toward;

        boolean opened = runOnServerThread(player, () -> {
            Vec3d hitVec = Vec3d.ofCenter(doorPos).add(0, 0.35, 0);
            BlockHitResult hit = new BlockHitResult(hitVec, hitFacing, doorPos, false);
            ActionResult result = player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND, hit);
            if (result.isAccepted()) {
                player.swingHand(Hand.MAIN_HAND, true);
            }
        });
        if (!opened) {
            maybeLogDoor(player, doorPos, "door-open failed: interactBlock dispatch failed");
            return false;
        }
        // If the interaction did open the door, schedule a close once we've passed through.
        if (world.getBlockState(doorPos).getBlock() instanceof DoorBlock
                && world.getBlockState(doorPos).contains(DoorBlock.OPEN)
                && Boolean.TRUE.equals(world.getBlockState(doorPos).get(DoorBlock.OPEN))) {
            scheduleDoorClose(player, world.getRegistryKey(), doorPos.toImmutable(), travelDir);
            maybeLogDoor(player, doorPos, "door-open success");
            return true;
        }
        maybeLogDoor(player, doorPos, "door-open failed: door did not toggle open");
        return false;
    }

    private static void maybeWarnIronDoor(ServerPlayerEntity player, BlockPos doorPos) {
        if (player == null || doorPos == null) {
            return;
        }
        UUID id = player.getUuid();
        long now = System.currentTimeMillis();
        Map<BlockPos, Long> perBot = DOOR_IRON_WARN_COOLDOWN.computeIfAbsent(id, __ -> new ConcurrentHashMap<>());
        Long last = perBot.get(doorPos);
        if (last != null && now - last < 10_000L) {
            return;
        }
        perBot.put(doorPos, now);
        // Send to the bot's command source so the commander sees it in chat context.
        net.shasankp000.ChatUtils.ChatUtils.sendSystemMessage(
                player.getCommandSource(),
                "I can't open iron doors without redstone (button/lever/pressure plate).");
        maybeLogDoor(player, doorPos, "iron-door: cannot open without redstone");
    }

    private static void maybeLogDoor(ServerPlayerEntity player, BlockPos doorPos, String message) {
        if (player == null || doorPos == null || message == null) {
            return;
        }
        UUID id = player.getUuid();
        long now = System.currentTimeMillis();
        Map<BlockPos, Long> perBot = DOOR_DEBUG_COOLDOWN.computeIfAbsent(id, __ -> new ConcurrentHashMap<>());
        Long last = perBot.get(doorPos);
        if (last != null && now - last < 1500L) {
            return;
        }
        perBot.put(doorPos, now);
        LOGGER.info("Door debug: bot={} door={} msg={}", player.getName().getString(), doorPos.toShortString(), message);
    }

    private static boolean doorAttemptAllowed(UUID botUuid, BlockPos doorPos) {
        if (botUuid == null || doorPos == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Map<BlockPos, Long> perBot = DOOR_CLOSE_COOLDOWN.computeIfAbsent(botUuid, __ -> new ConcurrentHashMap<>());
        Long last = perBot.get(doorPos);
        if (last != null && now - last < 1500L) {
            return false;
        }
        perBot.put(doorPos, now);
        return true;
    }

    private static BlockPos normalizeDoorBase(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock) {
            if (state.contains(DoorBlock.HALF)
                    && state.get(DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER) {
                return pos.down();
            }
            return pos;
        }
        // Sometimes the collision is on the upper half; try one block up/down.
        BlockState up = world.getBlockState(pos.up());
        if (up.getBlock() instanceof DoorBlock) {
            return normalizeDoorBase(world, pos.up());
        }
        BlockState down = world.getBlockState(pos.down());
        if (down.getBlock() instanceof DoorBlock) {
            return normalizeDoorBase(world, pos.down());
        }
        return null;
    }

    private static Direction approximateToward(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return Direction.NORTH;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void scheduleDoorClose(ServerPlayerEntity player,
                                          net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey,
                                          BlockPos doorPos,
                                          Direction travelDir) {
        MinecraftServer server = player != null ? player.getCommandSource().getServer() : null;
        UUID botUuid = player != null ? player.getUuid() : null;
        if (server == null || botUuid == null || worldKey == null || doorPos == null || travelDir == null) {
            return;
        }
        scheduleDoorCloseInternal(server, botUuid, worldKey, doorPos, travelDir, 0);
    }

    private static void scheduleDoorCloseInternal(MinecraftServer server,
                                                  UUID botUuid,
                                                  net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey,
                                                  BlockPos doorPos,
                                                  Direction travelDir,
                                                  int attempt) {
        if (server == null || botUuid == null || worldKey == null || doorPos == null || travelDir == null) {
            return;
        }
        // Retry a few times so doors close even if the bot pauses briefly after opening.
        if (attempt > 20) {
            return;
        }
        DOOR_CLOSE_SCHEDULER.schedule(() -> server.execute(() -> {
            ServerWorld world = server.getWorld(worldKey);
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botUuid);
            if (world == null || bot == null || bot.isRemoved() || !world.isChunkLoaded(doorPos)) {
                return;
            }
            BlockState state = world.getBlockState(doorPos);
            if (!(state.getBlock() instanceof DoorBlock) || !state.contains(DoorBlock.OPEN) || !Boolean.TRUE.equals(state.get(DoorBlock.OPEN))) {
                return;
            }

            // Close once the bot has actually passed through (wolf-like behavior: open, go through, close behind).
            BlockPos botPos = bot.getBlockPos();
            // Never close while the bot is still in/too close to the doorway.
            if (botPos.equals(doorPos) || botPos.getSquaredDistance(doorPos) <= 2.25D) {
                maybeLogDoor(bot, doorPos, "door-close wait: bot too close");
                scheduleDoorCloseInternal(server, botUuid, worldKey, doorPos, travelDir, attempt + 1);
                return;
            }
            int dx = travelDir.getOffsetX();
            int dz = travelDir.getOffsetZ();
            if (dx == 0 && dz == 0) {
                return;
            }
            int dot = (botPos.getX() - doorPos.getX()) * dx + (botPos.getZ() - doorPos.getZ()) * dz;
            // Prefer closing only after crossing the door plane; but if our travelDir guess was wrong
            // (or the bot sidestepped), close once we're clearly away from the doorway.
            if (dot < 1 && attempt < 4) {
                maybeLogDoor(bot, doorPos, "door-close wait: not past door yet");
                scheduleDoorCloseInternal(server, botUuid, worldKey, doorPos, travelDir, attempt + 1);
                return;
            }

            maybeLogDoor(bot, doorPos, "door-close attempt");
            Direction toward = bot.getHorizontalFacing();
            Vec3d hitVec = Vec3d.ofCenter(doorPos).add(0, 0.35, 0);
            BlockHitResult hit = new BlockHitResult(hitVec, toward, doorPos, false);
            ActionResult result = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
            if (result.isAccepted()) {
                bot.swingHand(Hand.MAIN_HAND, true);
            }

            BlockState after = world.getBlockState(doorPos);
            if (after.getBlock() instanceof DoorBlock && after.contains(DoorBlock.OPEN) && !Boolean.TRUE.equals(after.get(DoorBlock.OPEN))) {
                markDoorRecentlyClosed(botUuid, doorPos, 8_000L);
                // Also throttle re-open attempts after we close a door behind us; reduces follow “door loops”.
                Map<BlockPos, Long> perBot = DOOR_CLOSE_COOLDOWN.computeIfAbsent(botUuid, __ -> new ConcurrentHashMap<>());
                perBot.put(doorPos.toImmutable(), System.currentTimeMillis());
                maybeLogDoor(bot, doorPos, "door-closed: marked recently closed");
            }
        }), 520L, TimeUnit.MILLISECONDS);
    }

    public static boolean isDoorRecentlyClosed(UUID botUuid, BlockPos doorPos) {
        if (botUuid == null || doorPos == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Map<BlockPos, Long> perBot = DOOR_RECENTLY_CLOSED_UNTIL.get(botUuid);
        if (perBot == null) {
            return false;
        }
        BlockPos key = doorPos.toImmutable();
        Long until = perBot.get(key);
        if (until == null) {
            return false;
        }
        if (now >= until) {
            perBot.remove(key);
            if (perBot.isEmpty()) {
                DOOR_RECENTLY_CLOSED_UNTIL.remove(botUuid);
            }
            return false;
        }
        return true;
    }

    private static void markDoorRecentlyClosed(UUID botUuid, BlockPos doorPos, long durationMs) {
        if (botUuid == null || doorPos == null) {
            return;
        }
        long until = System.currentTimeMillis() + Math.max(1_000L, durationMs);
        Map<BlockPos, Long> perBot = DOOR_RECENTLY_CLOSED_UNTIL.computeIfAbsent(botUuid, __ -> new ConcurrentHashMap<>());
        perBot.put(doorPos.toImmutable(), until);
    }

    private static boolean trySidestepAround(ServerPlayerEntity player, BlockPos goal) {
        ServerWorld world = getWorld(player);
        if (world == null || goal == null) {
            return false;
        }
        Direction toward = Direction.getFacing(goal.getX() - player.getX(), 0, goal.getZ() - player.getZ());
        if (!toward.getAxis().isHorizontal()) {
            toward = player.getHorizontalFacing();
        }
        Direction[] options = new Direction[] {
                toward.rotateYClockwise(),
                toward.rotateYCounterclockwise(),
                toward.getOpposite()
        };

        BlockPos start = player.getBlockPos();
        for (Direction dir : options) {
            BlockPos stand = start.offset(dir);
            if (!isSolidStandable(world, stand.down(), stand)) {
                continue;
            }
            LOGGER.debug("walkSegment sidestep attempt dir={} stand={}", dir, stand.toShortString());
            LookController.faceBlock(player, stand);
            if (pursuitUntilClose(player, stand, 900L, 2.25D, "sidestep")) {
                return true;
            }
            // If pursuit is disabled elsewhere, try a short manual nudge.
            Vec3d standCenter = centerOf(stand);
            long deadline = System.currentTimeMillis() + 900L;
            while (System.currentTimeMillis() < deadline) {
                double distSq = player.squaredDistanceTo(standCenter);
                if (distSq <= 2.25D) {
                    BotActions.stop(player);
                    return true;
                }
                boolean ok = runOnServerThread(player, () -> {
                    LookController.faceBlock(player, stand);
                    BotActions.sprint(player, false);
                    BotActions.autoJumpIfNeeded(player);
                    BotActions.applyMovementInput(player, standCenter, 0.12);
                });
                if (!ok) {
                    break;
                }
                sleep(90L);
            }
        }
        // If we're stuck against leaves (common with foliage), clear leaf blocks around headroom using shears/hand.
        BlockPos front = start.offset(toward);
        BlockPos head = start.up(2);
        BlockPos ceiling = start.up(3);
        List<BlockPos> candidates = leafCandidates(start, toward);
        int cleared = 0;
        for (BlockPos leaf : candidates) {
            if (isBreakableLeaf(world, leaf) && isWithinReach(player, leaf)) {
                LOGGER.debug("walkSegment leaf-stuck: breaking leaf at {}", leaf.toShortString());
                selectHarmlessForLeaves(player);
                try {
                    MiningTool.mineBlock(player, leaf).get(4, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
                cleared++;
                if (cleared >= 2) {
                    return true;
                }
            }
        }
        return cleared > 0;
    }

    private static boolean isBreakableLeaf(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        var state = world.getBlockState(pos);
        return state.isIn(BlockTags.LEAVES);
    }

    private static boolean isWithinReach(ServerPlayerEntity player, BlockPos pos) {
        if (player == null || pos == null) {
            return false;
        }
        return player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 20.25D; // ~4.5 blocks
    }

    private static void selectHarmlessForLeaves(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        // Prefer shears, otherwise an empty slot or a non-tool/weapon hotbar item.
        if (BotActions.selectBestTool(player, "shears", "")) {
            return;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                BotActions.selectHotbarSlot(player, i);
                return;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase();
            if (key.contains("sword") || key.contains("axe") || key.contains("pickaxe") || key.contains("shovel") || key.contains("hoe")) {
                continue;
            }
            BotActions.selectHotbarSlot(player, i);
            return;
        }
    }

    private static List<BlockPos> leafCandidates(BlockPos start, Direction toward) {
        if (start == null || toward == null) {
            return List.of();
        }
        BlockPos front = start.offset(toward);
        BlockPos head = start.up(2);
        BlockPos ceiling = start.up(3);
        return List.of(
                head, head.up(), ceiling,
                head.offset(toward), head.offset(toward).up(), head.offset(toward).up(2),
                front.up(), front.up(2), front.up(3), front,
                head.offset(toward.rotateYClockwise()), head.offset(toward.rotateYClockwise()).up(),
                head.offset(toward.rotateYCounterclockwise()), head.offset(toward.rotateYCounterclockwise()).up()
        );
    }

    public static boolean clearLeafObstruction(ServerPlayerEntity player, Direction toward) {
        if (player == null || toward == null) {
            return false;
        }
        ServerWorld world = getWorld(player);
        if (world == null) {
            return false;
        }
        BlockPos start = player.getBlockPos();
        List<BlockPos> candidates = leafCandidates(start, toward);
        int cleared = 0;
        for (BlockPos leaf : candidates) {
            if (isBreakableLeaf(world, leaf) && isWithinReach(player, leaf)) {
                LOGGER.debug("clearLeafObstruction: breaking leaf at {}", leaf.toShortString());
                selectHarmlessForLeaves(player);
                try {
                    MiningTool.mineBlock(player, leaf).get(4, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
                return true;
            }
        }
        return false;
    }

    public static boolean hasLeafObstruction(ServerPlayerEntity player, Direction toward) {
        if (player == null || toward == null) {
            return false;
        }
        ServerWorld world = getWorld(player);
        if (world == null) {
            return false;
        }
        List<BlockPos> candidates = leafCandidates(player.getBlockPos(), toward);
        for (BlockPos leaf : candidates) {
            if (isBreakableLeaf(world, leaf)) {
                return true;
            }
        }
        return false;
    }

    private static boolean walkDirect(ServerPlayerEntity player, BlockPos destination, long timeoutMs) {
        Vec3d target = centerOf(destination);
        long now = System.currentTimeMillis();
        long deadline = now + Math.min(timeoutMs, 4500L); // bounded to avoid long blocking but generous for short marches
        int maxSteps = 42;
        LOGGER.debug("walkDirect start: from={} to={} dist={} timeoutMs={} steps={}",
                player.getBlockPos().toShortString(),
                destination.toShortString(),
                String.format("%.2f", Math.sqrt(player.getBlockPos().getSquaredDistance(destination))),
                timeoutMs,
                maxSteps);
        double lastDistanceSq = Double.MAX_VALUE;
        int stagnantSteps = 0;
        boolean sprint = shouldSprintForDestination(player, destination);

        for (int steps = 0; System.currentTimeMillis() < deadline && steps < maxSteps; steps++) {
            if (abortRequested(player)) {
                BotActions.stop(player);
                return false;
            }
            double distanceSq = player.squaredDistanceTo(target);
            if (distanceSq <= CLOSE_ENOUGH_DISTANCE_SQ) {
                BotActions.stop(player);
                return true;
            }
            if (distanceSq >= lastDistanceSq - 0.01) {
                stagnantSteps++;
	                if (stagnantSteps == 3) {
	                    if (tryOpenDoorToward(player, destination)) {
	                        stagnantSteps = 0;
	                        lastDistanceSq = Double.MAX_VALUE;
	                        continue;
	                    }
	                    // Door might already be open; commit to stepping through the doorway to avoid frame-jitter stalls.
	                    if (tryTraverseDoorway(player, destination, "walkDirect")) {
	                        stagnantSteps = 0;
	                        lastDistanceSq = Double.MAX_VALUE;
	                        continue;
	                    }
	                    // If the target is around a corner (enclosure exit), raycasts may never intersect the door;
	                    // treat a nearby door as a sub-goal to escape and continue.
	                    if (tryDoorSubgoalToward(player, destination, "walkDirect")) {
	                        stagnantSteps = 0;
	                        lastDistanceSq = Double.MAX_VALUE;
	                        continue;
	                    }
	                }
                if (stagnantSteps >= 5) {
                    if (trySidestepAround(player, destination)) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        continue;
                    }
                    break;
                }
            } else {
                stagnantSteps = 0;
                lastDistanceSq = distanceSq;
            }
            if (!inputStepToward(player, destination, target, sprint, false)) {
                return false;
            }
            sleep(35L);
        }
        return false;
    }

    /**
     * Small, iterative pursuit toward a static target. Stops once within reachSq.
     */
    public static boolean nudgeTowardUntilClose(ServerPlayerEntity bot,
                                                BlockPos target,
                                                double reachSq,
                                                long timeoutMs,
                                                double impulse,
                                                String label) {
        if (bot == null || target == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        double best = bot.getBlockPos().getSquaredDistance(target);
        double start = best;
        LOGGER.debug("nudgeToward start [{}]: from={} to={} dist={}", label, bot.getBlockPos().toShortString(), target.toShortString(), Math.sqrt(best));
        int noProgress = 0;
        while (System.currentTimeMillis() < deadline) {
            if (abortRequested(bot)) {
                BotActions.stop(bot);
                return false;
            }
            double distSq = bot.getBlockPos().getSquaredDistance(target);
            if (distSq <= reachSq && (start - distSq > 0.25D || distSq <= reachSq * 0.6D)) {
                BotActions.stop(bot);
                LOGGER.debug("nudgeToward success [{}]: dist={}", label, Math.sqrt(distSq));
                return true;
            }
            boolean sprint = distSq > 9.0D;
            runOnServerThread(bot, () -> LookController.faceBlock(bot, target));
            BotActions.sprint(bot, sprint);
            double dy = target.getY() - bot.getY();
            if (dy > 0.6D) {
                BotActions.jump(bot);
            } else if (distSq > CLOSE_ENOUGH_DISTANCE_SQ) {
                BotActions.autoJumpIfNeeded(bot);
            }
            double tunedImpulse = distSq > 16.0D ? Math.max(impulse, 0.16) : impulse;
            BotActions.applyMovementInput(bot, Vec3d.ofCenter(target), tunedImpulse);
            sleep(90L);

            double nowDist = bot.getBlockPos().getSquaredDistance(target);
            if (nowDist + 0.05D >= best) {
                noProgress++;
                if (noProgress >= 7) {
                    LOGGER.debug("nudgeToward stalled [{}]: bestDist={}", label, Math.sqrt(best));
                    break;
                }
            } else {
                best = nowDist;
                noProgress = 0;
            }
        }
        LOGGER.warn("nudgeToward failed [{}]: finalDist={}", label, Math.sqrt(bot.getBlockPos().getSquaredDistance(target)));
        return bot.getBlockPos().getSquaredDistance(target) <= reachSq;
    }

    /**
     * Follow-style pursuit that keeps stepping toward the target using movement input until close.
     */
    private static boolean pursuitUntilClose(ServerPlayerEntity bot,
                                             BlockPos target,
                                             long timeoutMs,
                                             double reachSq,
                                             String label) {
        if (bot == null || target == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        double best = bot.getBlockPos().getSquaredDistance(target);
        int stagnant = 0;
        LOGGER.debug("pursuit start [{}]: from={} to={} dist={}", label, bot.getBlockPos().toShortString(), target.toShortString(), Math.sqrt(best));
        while (System.currentTimeMillis() < deadline) {
            if (abortRequested(bot)) {
                BotActions.stop(bot);
                return false;
            }
            double distSq = bot.getBlockPos().getSquaredDistance(target);
            if (distSq <= reachSq) {
                BotActions.stop(bot);
                LOGGER.debug("pursuit success [{}]: dist={}", label, Math.sqrt(distSq));
                return true;
            }
            boolean sprint = distSq > 9.0D;
            runOnServerThread(bot, () -> LookController.faceBlock(bot, target));
            BotActions.sprint(bot, sprint);
            double dy = target.getY() - bot.getY();
            if (dy > 0.6D) {
                BotActions.jump(bot);
            } else {
                BotActions.autoJumpIfNeeded(bot);
            }
            double impulse = sprint ? 0.19 : 0.15;
            BotActions.applyMovementInput(bot, Vec3d.ofCenter(target), impulse);
            sleep(80L);
            double nowDist = bot.getBlockPos().getSquaredDistance(target);
            if (nowDist + 0.02D >= best) {
                stagnant++;
                if (stagnant == 4) {
                    // First: if we're blocked by a closed door, open it.
                    if (tryOpenDoorToward(bot, target)) {
                        stagnant = 0;
                        best = Double.MAX_VALUE;
                        continue;
                    }
                    // If the door is already open but we're not getting through, commit to stepping through the doorway.
                    if (tryTraverseDoorway(bot, target, label)) {
                        stagnant = 0;
                        best = Double.MAX_VALUE;
                        continue;
                    }
                    // If the target is around a corner, the blocking door may not be on the ray to the target.
                    if (tryDoorSubgoalToward(bot, target, label)) {
                        stagnant = 0;
                        best = Double.MAX_VALUE;
                        continue;
                    }
                }
                if (stagnant >= 8) {
                    LOGGER.debug("pursuit stalled [{}]: bestDist={}", label, Math.sqrt(best));
                    break;
                }
            } else {
                best = nowDist;
                stagnant = 0;
            }
        }
        LOGGER.warn("pursuit failed [{}]: finalDist={}", label, Math.sqrt(bot.getBlockPos().getSquaredDistance(target)));
        return false;
    }

    private static boolean tryTraverseDoorway(ServerPlayerEntity bot, BlockPos destination, String label) {
        if (bot == null || destination == null) {
            return false;
        }
        ServerWorld world = getWorld(bot);
        if (world == null) {
            return false;
        }
        BlockPos doorHit = BlockInteractionService.findDoorAlongLine(bot, Vec3d.ofCenter(destination), 6.0D);
        if (doorHit == null) {
            return false;
        }
        BlockPos doorBase = normalizeDoorBase(world, doorHit);
        if (doorBase == null) {
            return false;
        }
        BlockState doorState = world.getBlockState(doorBase);
        if (!(doorState.getBlock() instanceof DoorBlock)) {
            return false;
        }
        // Ensure it's open (or try to open it).
        if (doorState.contains(DoorBlock.OPEN) && !Boolean.TRUE.equals(doorState.get(DoorBlock.OPEN))) {
            tryOpenDoorAt(bot, doorBase);
            doorState = world.getBlockState(doorBase);
        }
        if (!doorState.contains(DoorBlock.OPEN) || !Boolean.TRUE.equals(doorState.get(DoorBlock.OPEN))) {
            return false;
        }
        Direction toward = approximateToward(doorBase, destination);
        if (!toward.getAxis().isHorizontal()) {
            toward = bot.getHorizontalFacing();
        }
        BlockPos stepThrough = doorBase.offset(toward);
        if (!isSolidStandable(world, stepThrough.down(), stepThrough)) {
            // Try slight offsets around the doorway.
            BlockPos left = stepThrough.offset(toward.rotateYCounterclockwise());
            BlockPos right = stepThrough.offset(toward.rotateYClockwise());
            if (isSolidStandable(world, left.down(), left)) {
                stepThrough = left;
            } else if (isSolidStandable(world, right.down(), right)) {
                stepThrough = right;
            } else {
                return false;
            }
        }
        LOGGER.debug("pursuit doorway assist [{}]: door={} step={}", label, doorBase.toShortString(), stepThrough.toShortString());
        return nudgeTowardUntilClose(bot, stepThrough, 2.25D, 1600L, 0.22, label + "-doorway");
    }

    /**
     * When the target is around a corner (e.g., bot is inside an enclosure and the goal is outside),
     * the "blocking" door may never be intersected by a raycast to the goal. In those cases, pick a
     * nearby wooden door that would move us closer to the goal if we step through it, then treat it
     * as a sub-goal: approach -> open -> step through.
     */
    private static boolean tryDoorSubgoalToward(ServerPlayerEntity bot, BlockPos goal, String label) {
        if (bot == null || goal == null) {
            return false;
        }
        DoorSubgoalPlan plan = findDoorSubgoalPlan(bot, goal);
        if (plan == null) {
            return false;
        }
        BlockPos botPos = bot.getBlockPos();
        maybeLogDoor(bot, plan.doorBase(), "door-subgoal: label=" + label
                + " approach=" + plan.approachPos().toShortString()
                + " step=" + plan.stepThroughPos().toShortString()
                + " improve=" + String.format(Locale.ROOT, "%.2f", plan.improveSq()));

        boolean approached = botPos.getSquaredDistance(plan.approachPos()) <= 2.25D
                || nudgeTowardUntilClose(bot, plan.approachPos(), 2.25D, 1700L, 0.18, label + "-door-approach");
        if (!approached) {
            return false;
        }
        tryOpenDoorAt(bot, plan.doorBase());
        return nudgeTowardUntilClose(bot, plan.stepThroughPos(), 2.25D, 2000L, 0.22, label + "-door-step");
    }

    private static void snapTo(ServerPlayerEntity player, BlockPos target) {
        if (player == null || target == null) {
            return;
        }
        Vec3d center = centerOf(target);
        player.refreshPositionAndAngles(center.x, center.y, center.z, player.getYaw(), player.getPitch());
        player.setVelocity(Vec3d.ZERO);
        player.velocityDirty = true;
        LOGGER.info("Snap repositioned {} to {}", player.getName().getString(), target.toShortString());
    }

    private static boolean inputStepToward(ServerPlayerEntity player,
                                           BlockPos blockTarget,
                                           Vec3d vecTarget,
                                           boolean sprint,
                                           boolean forceJump) {
        Runnable step = () -> {
            LookController.faceBlock(player, blockTarget);
            BotActions.sprint(player, sprint);
            if (forceJump || vecTarget.y - player.getY() > 0.6D) {
                BotActions.jump(player);
            } else {
                BotActions.autoJumpIfNeeded(player);
            }
            // Small impulse; repeated calls mimic held WASD.
            BotActions.applyMovementInput(player, vecTarget, sprint ? 0.16 : 0.12);
        };
        return runOnServerThread(player, step);
    }

    private static boolean runOnServerThread(ServerPlayerEntity player, Runnable action) {
        MinecraftServer server = player != null && player.getCommandSource() != null
                ? player.getCommandSource().getServer()
                : null;
        if (server == null) {
            action.run();
            return true;
        }
        if (server.isOnThread()) {
            action.run();
            return true;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            future.get(750, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("Walk step failed: {}", e.getMessage());
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Vec3d centerOf(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static BlockPos findNearbyStandable(ServerWorld world, BlockPos target, int horizontalRange, int verticalRange) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(target);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            int dx = Math.abs(current.getX() - target.getX());
            int dz = Math.abs(current.getZ() - target.getZ());
            int dy = Math.abs(current.getY() - target.getY());
            if (dx > horizontalRange || dz > horizontalRange || dy > verticalRange) {
                continue;
            }

            BlockPos foot = current.down();
            if (isSolidStandable(world, foot, current)) {
                double distance = current.getSquaredDistance(target);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = current;
                }
            }

            for (Direction direction : Direction.values()) {
                queue.add(current.offset(direction));
            }
        }
        return best;
    }

    private static boolean performManualStep(ServerPlayerEntity player, BlockPos target) {
        if (player == null || target == null) {
            return false;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        ServerWorld world = getWorld(player);
        MinecraftServer server = world != null ? world.getServer() : null;
        if (server == null) {
            return false;
        }
        server.execute(() -> {
            try {
                LookController.faceBlock(player, target);
                BotActions.moveForward(player);
                BotActions.jumpForward(player);
                encourageSurfaceDrift(player);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            future.get(1, TimeUnit.SECONDS);
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            return false;
        }
        Vec3d targetCenter = Vec3d.ofCenter(target);
        return player.squaredDistanceTo(targetCenter) <= ARRIVAL_DISTANCE_SQ;
    }

    private static boolean placeBridgeBlock(ServerPlayerEntity player, BlockPos target) {
        if (player == null || target == null) {
            return false;
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ServerWorld world = getWorld(player);
        MinecraftServer server = world != null ? world.getServer() : null;
        if (server == null) {
            return false;
        }
        server.execute(() -> {
            try {
                LookController.faceBlock(player, target);
                future.complete(BotActions.placeBlockAt(player, target));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBridgeCandidate(ServerWorld world, BlockPos pos, int maxDepth) {
        return isShallowWater(world, pos, maxDepth);
    }

    private static boolean isShallowWater(ServerWorld world, BlockPos pos, int maxDepth) {
        if (world == null || pos == null) {
            return false;
        }
        FluidState fluid = world.getFluidState(pos);
        if (!fluid.isIn(FluidTags.WATER)) {
            return false;
        }
        int depth = 0;
        BlockPos cursor = pos;
        while (depth < maxDepth) {
            FluidState state = world.getFluidState(cursor);
            if (!state.isIn(FluidTags.WATER)) {
                break;
            }
            depth++;
            cursor = cursor.down();
        }
        if (depth == 0) {
            return false;
        }
        if (world.getFluidState(cursor).isIn(FluidTags.WATER)) {
            return false;
        }
        return isFootSolid(world, cursor);
    }

    private static boolean isSolidStandable(ServerWorld world, BlockPos foot, BlockPos head) {
        return isFootSolid(world, foot) && hasClearance(world, head);
    }

    private static boolean isFootSolid(World world, BlockPos foot) {
        if (world == null || foot == null) {
            return false;
        }
        return !world.getBlockState(foot).getCollisionShape(world, foot).isEmpty();
    }

    private static boolean hasClearance(World world, BlockPos head) {
        if (world == null || head == null) {
            return false;
        }
        return world.getBlockState(head).getCollisionShape(world, head).isEmpty()
                && world.getBlockState(head.up()).getCollisionShape(world, head.up()).isEmpty();
    }

    private static boolean isGoToSuccess(String result) {
        if (result == null) {
            return false;
        }
        String lowered = result.toLowerCase();
        for (String token : new String[]{"failed", "error", "not found"}) {
            if (lowered.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static ServerWorld getWorld(ServerPlayerEntity player) {
        return player != null && player.getEntityWorld() instanceof ServerWorld world ? world : null;
    }

    /**
     * Keep the bot buoyant so that shallow water crossings look like proper wading instead of sinking.
     */
        private static void encourageSurfaceDrift(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        ServerWorld world = getWorld(player);
        if (world == null) {
            return;
        }
        BlockPos feet = player.getBlockPos();
        FluidState fluidState = world.getFluidState(feet);
        if (!fluidState.isIn(FluidTags.WATER)) {
            player.setSwimming(false);
            return;
        }
        
        player.setSwimming(true);
        if (player.isSneaking()) {
            player.setSneaking(false);
        }

        boolean headSubmerged = world.getFluidState(feet.up()).isIn(FluidTags.WATER);
        if (headSubmerged) {
            // Swim up
            Vec3d velocity = player.getVelocity();
            if (velocity.y < 0.05D) {
                player.addVelocity(0.0D, 0.06D, 0.0D);
                player.velocityDirty = true;
            }
        }
    }
}
