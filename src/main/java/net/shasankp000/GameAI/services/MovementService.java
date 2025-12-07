package net.shasankp000.GameAI.services;

import net.minecraft.fluid.FluidState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.registry.tag.FluidTags;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.skills.SkillPreferences;
import net.shasankp000.Entity.LookController;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.PathFinding.PathFinder;
import net.shasankp000.PathFinding.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
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

public final class MovementService {

    private static final Logger LOGGER = LoggerFactory.getLogger("movement-service");
    private static final double ARRIVAL_DISTANCE_SQ = 9.0D;
    private static final double CLOSE_ENOUGH_DISTANCE_SQ = 2.25D; // ~1.5 blocks
    private static final double PATH_SAME_TARGET_DISTANCE_SQ = 9.0D; // ~3 blocks
    private static final long PATH_ATTEMPT_MIN_INTERVAL_MS = 400L;
    private static final long PATH_FAILURE_BACKOFF_MS = 2500L;

    private MovementService() {
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

        LOGGER.info("walkTo start: bot={} dest={} label={} fastReplan={} currentPos={} dist={}",
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
        long deadline = System.currentTimeMillis() + (fastReplan ? TimeUnit.SECONDS.toMillis(5) : TimeUnit.SECONDS.toMillis(20));
        BlockPos lastReached = player.getBlockPos();
        boolean replanned = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            List<PathFinder.PathNode> rawPath = PathFinder.calculatePath(player.getBlockPos(), destination, world);
            List<PathFinder.PathNode> simplified = PathFinder.simplifyPath(rawPath, world);
            Queue<Segment> segments = PathFinder.convertPathToSegments(simplified, false);
            LOGGER.info("walkTo path (attempt={}): raw={} simplified={} segments={} firstSegment={}",
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
                        LOGGER.warn("walkTo replan after stall near {}", segment.end());
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

    private static SegmentResult walkSegment(ServerPlayerEntity player, Segment segment, long deadlineMs, boolean allowSnap) {
        if (player == null || segment == null) {
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
        LOGGER.info("walkSegment start: from={} to={} dist={}/steps={} allowanceMs={}",
                player.getBlockPos().toShortString(),
                segment.end().toShortString(),
                String.format("%.2f", segmentDistance),
                maxSteps,
                segmentAllowance);

        double lastDistanceSq = Double.MAX_VALUE;
        int stagnantSteps = 0;

        for (int steps = 0; System.currentTimeMillis() < endTime && steps < maxSteps; steps++) {
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
                if (stagnantSteps >= 5) {
                    LOGGER.info("walkSegment stalled near {} dist={}", segment.end(), Math.sqrt(distanceSq));
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
        LOGGER.info("walkSegment timed out near {} remainingDist={}", segment.end(), remaining);
        // Force a small snap forward when very close to planned segment end to avoid infinite loop.
        if (allowSnap && remaining <= 2.2D) {
            LOGGER.warn("walkSegment snap forward to {}", segment.end());
            snapTo(player, segment.end());
            return new SegmentResult(true, true);
        }
        return new SegmentResult(false, false);
    }

    private static boolean walkDirect(ServerPlayerEntity player, BlockPos destination, long timeoutMs) {
        Vec3d target = centerOf(destination);
        long now = System.currentTimeMillis();
        long deadline = now + Math.min(timeoutMs, 4500L); // bounded to avoid long blocking but generous for short marches
        int maxSteps = 42;
        LOGGER.info("walkDirect start: from={} to={} dist={} timeoutMs={} steps={}",
                player.getBlockPos().toShortString(),
                destination.toShortString(),
                String.format("%.2f", Math.sqrt(player.getBlockPos().getSquaredDistance(destination))),
                timeoutMs,
                maxSteps);
        double lastDistanceSq = Double.MAX_VALUE;
        int stagnantSteps = 0;

        for (int steps = 0; System.currentTimeMillis() < deadline && steps < maxSteps; steps++) {
            double distanceSq = player.squaredDistanceTo(target);
            if (distanceSq <= CLOSE_ENOUGH_DISTANCE_SQ) {
                BotActions.stop(player);
                return true;
            }
            if (distanceSq >= lastDistanceSq - 0.01) {
                stagnantSteps++;
                if (stagnantSteps >= 5) {
                    break;
                }
            } else {
                stagnantSteps = 0;
                lastDistanceSq = distanceSq;
            }
            if (!inputStepToward(player, destination, target, true, false)) {
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
        LOGGER.info("nudgeToward start [{}]: from={} to={} dist={}", label, bot.getBlockPos().toShortString(), target.toShortString(), Math.sqrt(best));
        int noProgress = 0;
        while (System.currentTimeMillis() < deadline) {
            double distSq = bot.getBlockPos().getSquaredDistance(target);
            if (distSq <= reachSq && (start - distSq > 0.25D || distSq <= reachSq * 0.6D)) {
                BotActions.stop(bot);
                LOGGER.info("nudgeToward success [{}]: dist={}", label, Math.sqrt(distSq));
                return true;
            }
            LookController.faceBlock(bot, target);
            boolean sprint = distSq > 9.0D;
            bot.setSprinting(sprint);
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
                    LOGGER.info("nudgeToward stalled [{}]: bestDist={}", label, Math.sqrt(best));
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
        LOGGER.info("pursuit start [{}]: from={} to={} dist={}", label, bot.getBlockPos().toShortString(), target.toShortString(), Math.sqrt(best));
        while (System.currentTimeMillis() < deadline) {
            double distSq = bot.getBlockPos().getSquaredDistance(target);
            if (distSq <= reachSq) {
                BotActions.stop(bot);
                LOGGER.info("pursuit success [{}]: dist={}", label, Math.sqrt(distSq));
                return true;
            }
            LookController.faceBlock(bot, target);
            boolean sprint = distSq > 9.0D;
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
                if (stagnant >= 8) {
                    LOGGER.info("pursuit stalled [{}]: bestDist={}", label, Math.sqrt(best));
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
            return;
        }
        boolean headSubmerged = world.getFluidState(feet.up()).isIn(FluidTags.WATER);
        Vec3d velocity = player.getVelocity();
        double upward = headSubmerged ? 0.08D : 0.04D;
        if (velocity.y < 0.02D) {
            player.setVelocity(velocity.x, 0.02D, velocity.z);
        }
        player.addVelocity(0.0D, upward, 0.0D);
        player.velocityDirty = true;
        if (player.isSneaking()) {
            player.setSneaking(false);
        }
    }
}
