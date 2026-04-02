package net.wcfcarolina13.GameAI.services;

import net.minecraft.fluid.FluidState;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.TrapdoorBlock;
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
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import net.wcfcarolina13.Entity.AutoFaceEntity;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.PathFinding.GoTo;
import net.wcfcarolina13.PathFinding.PathFinder;
import net.wcfcarolina13.PathFinding.Segment;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import net.wcfcarolina13.GameAI.services.construction.ConstructionProtectionService;
import net.wcfcarolina13.GameAI.services.construction.ConstructionRepairService;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.skills.support.MiningHazardDetector;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import java.util.function.Supplier;

public final class MovementService {

    private static final Logger LOGGER = LoggerFactory.getLogger("movement-service");
    private static final double ARRIVAL_DISTANCE_SQ = 9.0D;
    private static final double CLOSE_ENOUGH_DISTANCE_SQ = 2.25D; // ~1.5 blocks
    private static final double PATH_SAME_TARGET_DISTANCE_SQ = 9.0D; // ~3 blocks
    private static final long PATH_ATTEMPT_MIN_INTERVAL_MS = 400L;
    private static final long PATH_FAILURE_BACKOFF_MS = 2500L;
    // Hard anti-stuck tuning: stop pushing into walls; try local "turn the corner" probes before giving up.
    private static final int STUCK_SAME_BLOCK_STEPS_TRIGGER = 10; // ~350ms at 35ms sleeps
    private static final int STUCK_STAGNANT_STEPS_TRIGGER = 4;
    private static final ScheduledExecutorService DOOR_CLOSE_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bot-door-close");
        t.setDaemon(true);
        return t;
    });
    /** Interrupt door-close scheduler. Called during server shutdown. */
    public static void shutdownExecutors() {
        DOOR_CLOSE_SCHEDULER.shutdownNow();
    }

    private static final Map<UUID, Map<BlockPos, Long>> DOOR_CLOSE_COOLDOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPos, Long>> DOOR_DEBUG_COOLDOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPos, Long>> DOOR_IRON_WARN_COOLDOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPos, Long>> DOOR_RECENTLY_CLOSED_UNTIL = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> DOOR_AUTO_CLOSE_SUPPRESS_UNTIL_MS = new ConcurrentHashMap<>();
    
    // Track doors that the bot has recently failed to traverse (oscillation prevention).
    private static final Map<UUID, Map<BlockPos, Long>> DOOR_ESCAPE_FAILED = new ConcurrentHashMap<>();
    private static final long DOOR_ESCAPE_FAIL_DURATION_MS = 30_000L;

    // Prevent recursive “nudge -> assist -> nudge -> assist ...” loops.
    private static final ThreadLocal<Integer> NUDGE_DEPTH = ThreadLocal.withInitial(() -> 0);
    /** Cooldown map for nudgeToward warnings: label-prefix -> last warn timestamp. */
    private static final ConcurrentHashMap<String, Long> NUDGE_WARN_COOLDOWN = new ConcurrentHashMap<>();
    private static final long NUDGE_WARN_INTERVAL_MS = 10_000L;

    // When disabled, we suppress the "door subgoal" and "door escape" assists.
    // This is useful for construction tasks where nearby unrelated doors can become distractions.
    // NOTE: We still allow the simpler "open door directly ahead" and "traverse doorway" assists.
    private static final ThreadLocal<Boolean> DOOR_ESCAPE_DISABLED = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> OBSTRUCTION_MINE_DISABLED = ThreadLocal.withInitial(() -> false);

    private static final long OBSTRUCTION_MINE_COOLDOWN_MS = 4500L;
    private static final Map<UUID, Map<BlockPos, Long>> OBSTRUCTION_MINE_COOLDOWN = new ConcurrentHashMap<>();

    // Leaves are an extremely common "soft" obstruction (foliage). We want to avoid them first, and only
    // break a single natural leaf occasionally as a last resort. Also, never block the server thread.
    private static final long LEAF_MINE_GLOBAL_COOLDOWN_MS = 1200L;
    private static final Map<UUID, Long> LEAF_MINE_LAST_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<String>> LEAF_MINE_INFLIGHT = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> LEAF_MINE_TARGET = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LEAF_BYPASS_FAILURE_STREAK = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> LEAF_LAST_BYPASS_ORIGIN = new ConcurrentHashMap<>();
    private static final int LEAF_BYPASS_FORCE_MINE_THRESHOLD = 3;
    private static final Map<UUID, LocalEscapeDirective> LOCAL_ESCAPE_DIRECTIVES = new ConcurrentHashMap<>();
    private static final Map<UUID, LocalEscapeCommitment> LOCAL_ESCAPE_COMMITMENTS = new ConcurrentHashMap<>();
    private static final long LOCAL_ESCAPE_COMMITMENT_MS = 2200L;

    private MovementService() {
    }

    public static <T> T withoutDoorEscape(Supplier<T> body) {
        boolean prev = Boolean.TRUE.equals(DOOR_ESCAPE_DISABLED.get());
        DOOR_ESCAPE_DISABLED.set(true);
        try {
            return body.get();
        } finally {
            DOOR_ESCAPE_DISABLED.set(prev);
        }
    }

    public static void withoutDoorEscape(Runnable body) {
        boolean prev = Boolean.TRUE.equals(DOOR_ESCAPE_DISABLED.get());
        DOOR_ESCAPE_DISABLED.set(true);
        try {
            body.run();
        } finally {
            DOOR_ESCAPE_DISABLED.set(prev);
        }
    }

    private static boolean doorEscapeEnabled() {
        return !Boolean.TRUE.equals(DOOR_ESCAPE_DISABLED.get());
    }

    public static <T> T withoutObstructionMining(Supplier<T> body) {
        boolean prev = Boolean.TRUE.equals(OBSTRUCTION_MINE_DISABLED.get());
        OBSTRUCTION_MINE_DISABLED.set(true);
        try {
            return body.get();
        } finally {
            OBSTRUCTION_MINE_DISABLED.set(prev);
        }
    }

    public static void withoutObstructionMining(Runnable body) {
        boolean prev = Boolean.TRUE.equals(OBSTRUCTION_MINE_DISABLED.get());
        OBSTRUCTION_MINE_DISABLED.set(true);
        try {
            body.run();
        } finally {
            OBSTRUCTION_MINE_DISABLED.set(prev);
        }
    }

    public static void suppressDoorAutoClose(UUID botId, long durationMs) {
        if (botId == null) {
            return;
        }
        long effectiveDurationMs = Math.max(500L, durationMs);
        long untilMs = System.currentTimeMillis() + effectiveDurationMs;
        DOOR_AUTO_CLOSE_SUPPRESS_UNTIL_MS.put(botId, untilMs);
        // TTL cleanup to avoid stale suppression state when no additional close checks occur.
        DOOR_CLOSE_SCHEDULER.schedule(() -> {
            Long currentUntil = DOOR_AUTO_CLOSE_SUPPRESS_UNTIL_MS.get(botId);
            if (currentUntil != null && currentUntil <= System.currentTimeMillis()) {
                DOOR_AUTO_CLOSE_SUPPRESS_UNTIL_MS.remove(botId);
            }
        }, effectiveDurationMs + 100L, TimeUnit.MILLISECONDS);
    }

    public static boolean isDoorAutoCloseSuppressed(UUID botId) {
        if (botId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long untilMs = DOOR_AUTO_CLOSE_SUPPRESS_UNTIL_MS.get(botId);
        if (untilMs == null) {
            return false;
        }
        if (now >= untilMs) {
            DOOR_AUTO_CLOSE_SUPPRESS_UNTIL_MS.remove(botId);
            return false;
        }
        return true;
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

    public enum LeafClearAction {
        NO_OBSTRUCTION,
        BYPASS_STEP,
        BYPASS_PROGRESS,
        MINING_STARTED,
        MINING_IN_PROGRESS,
        NO_CLEAR
    }

    public record LeafClearResult(LeafClearAction action,
                                  boolean leafInPath,
                                  int bypassFailures,
                                  BlockPos leafTarget,
                                  String detail) {
        public boolean shouldShortCircuitFollowTick() {
            return action == LeafClearAction.MINING_STARTED || action == LeafClearAction.MINING_IN_PROGRESS;
        }

        public boolean countsAsCleared() {
            return action == LeafClearAction.MINING_STARTED
                    || action == LeafClearAction.MINING_IN_PROGRESS
                    || action == LeafClearAction.BYPASS_PROGRESS;
        }
    }

    private enum LeafMineResult {
        NONE,
        STARTED,
        IN_PROGRESS
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

    private record LocalEscapeDirective(BlockPos target, long expiresAtMs, String reason) {}

    private record LocalEscapeCommitment(BlockPos routeTarget,
                                         BlockPos destinationHint,
                                         boolean precision,
                                         long expiresAtMs,
                                         String reason) {}

    private record LocalEscapeCandidate(BlockPos target,
                                        double score,
                                        boolean stepUp,
                                        boolean leavesTrap,
                                        boolean overrideTarget,
                                        String reason,
                                        List<BlockPos> route) {}

    private record LocalEscapeMove(BlockPos target, boolean stepUp) {}

    private record LocalEscapeSearchState(BlockPos pos, List<BlockPos> route) {}

    public record DoorSubgoalPlan(BlockPos doorBase, BlockPos approachPos, BlockPos stepThroughPos, double improveSq) {}

    public static void setLocalEscapeDirective(ServerPlayerEntity bot, BlockPos target, long ttlMs, String reason) {
        if (bot == null || target == null) {
            return;
        }
        long expiresAtMs = System.currentTimeMillis() + Math.max(500L, ttlMs);
        LOCAL_ESCAPE_DIRECTIVES.put(bot.getUuid(), new LocalEscapeDirective(target.toImmutable(), expiresAtMs, reason));
    }

    public static void clearLocalEscapeDirective(UUID botId) {
        if (botId != null) {
            LOCAL_ESCAPE_DIRECTIVES.remove(botId);
            LOCAL_ESCAPE_COMMITMENTS.remove(botId);
        }
    }

    /**
     * Finds a nearby openable (door/gate) that would bring the bot closer to the goal if it steps through it.
     * Intended for "enclosure escape" situations where the goal is around a corner and raycasts to the goal
     * never directly intersect the blocking openable.
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
                    if (!(raw.getBlock() instanceof DoorBlock) && !(raw.getBlock() instanceof FenceGateBlock)) {
                        continue;
                    }
                    BlockPos doorBase = normalizeOpenableBase(world, scan);
                    if (doorBase == null) {
                        continue;
                    }
                    BlockState state = world.getBlockState(doorBase);
                    if (!(state.getBlock() instanceof DoorBlock) && !(state.getBlock() instanceof FenceGateBlock)) {
                        continue;
                    }
                    if (state.getBlock() instanceof DoorBlock && state.isOf(net.minecraft.block.Blocks.IRON_DOOR)) {
                        continue;
                    }
                    if (botPos.getSquaredDistance(doorBase) > 25.0D) { // keep it local (enclosure escape)
                        continue;
                    }

                    Direction facing;
                    if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.FACING)) {
                        facing = state.get(DoorBlock.FACING);
                    } else if (state.getBlock() instanceof FenceGateBlock && state.contains(FenceGateBlock.FACING)) {
                        facing = state.get(FenceGateBlock.FACING);
                    } else {
                        facing = bot.getHorizontalFacing();
                    }
                        if (facing == null || !facing.getAxis().isHorizontal()) {
                            facing = bot.getHorizontalFacing();
                        }

                        BlockPos sideASeed = doorBase.offset(facing);
                        BlockPos sideBSeed = doorBase.offset(facing.getOpposite());

                        BlockPos approachSeed = botPos.getSquaredDistance(sideASeed) <= botPos.getSquaredDistance(sideBSeed)
                                ? sideASeed
                                : sideBSeed;
                        BlockPos stepSeed = approachSeed.equals(sideASeed) ? sideBSeed : sideASeed;

                        boolean approachFrontSide = isOnDoorFrontSide(doorBase, facing, approachSeed);
                        boolean stepFrontSide = isOnDoorFrontSide(doorBase, facing, stepSeed);
                        BlockPos approach = findStandableSameDoorSide(world, doorBase, facing, approachFrontSide, approachSeed, 2);
                        BlockPos step = findStandableSameDoorSide(world, doorBase, facing, stepFrontSide, stepSeed, 2);
                        if (approach == null || step == null || approach.equals(step)) {
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
        
        // Prune expired failed-door entries for this bot.
        UUID uuid = bot.getUuid();
        long now = System.currentTimeMillis();
        Map<BlockPos, Long> failedMap = DOOR_ESCAPE_FAILED.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        failedMap.entrySet().removeIf(e -> now >= e.getValue());

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
                    if (!(raw.getBlock() instanceof DoorBlock) && !(raw.getBlock() instanceof FenceGateBlock)) {
                        continue;
                    }
                    BlockPos doorBase = normalizeOpenableBase(world, scan);
                    if (doorBase == null) {
                        continue;
                    }
                    if (avoidDoorBase != null && avoidDoorBase.equals(doorBase)) {
                        continue;
                    }
                    // Skip doors that have recently failed (oscillation prevention).
                    if (failedMap.containsKey(doorBase)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(doorBase);
                    if (!(state.getBlock() instanceof DoorBlock) && !(state.getBlock() instanceof FenceGateBlock)) {
                        continue;
                    }
                    if (state.getBlock() instanceof DoorBlock && state.isOf(net.minecraft.block.Blocks.IRON_DOOR)) {
                        continue;
                    }
                    double distToDoorSq = botPos.getSquaredDistance(doorBase);
                    if (distToDoorSq > 400.0D) { // keep it local-ish (enclosure/building escape)
                        continue;
                    }

                    Direction facing;
                    if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.FACING)) {
                        facing = state.get(DoorBlock.FACING);
                    } else if (state.getBlock() instanceof FenceGateBlock && state.contains(FenceGateBlock.FACING)) {
                        facing = state.get(FenceGateBlock.FACING);
                    } else {
                        facing = bot.getHorizontalFacing();
                    }
                        if (facing == null || !facing.getAxis().isHorizontal()) {
                            facing = bot.getHorizontalFacing();
                        }

                        BlockPos sideASeed = doorBase.offset(facing);
                        BlockPos sideBSeed = doorBase.offset(facing.getOpposite());

                        BlockPos approachSeed = botPos.getSquaredDistance(sideASeed) <= botPos.getSquaredDistance(sideBSeed)
                                ? sideASeed
                                : sideBSeed;
                        BlockPos stepSeed = approachSeed.equals(sideASeed) ? sideBSeed : sideASeed;

                        boolean approachFrontSide = isOnDoorFrontSide(doorBase, facing, approachSeed);
                        boolean stepFrontSide = isOnDoorFrontSide(doorBase, facing, stepSeed);
                        BlockPos approach = findStandableSameDoorSide(world, doorBase, facing, approachFrontSide, approachSeed, 2);
                        BlockPos step = findStandableSameDoorSide(world, doorBase, facing, stepFrontSide, stepSeed, 2);
                        if (approach == null || step == null) {
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

    /**
     * Mark a door as failed for door-escape purposes. The bot will avoid this door
     * for {@link #DOOR_ESCAPE_FAIL_DURATION_MS} to prevent oscillation.
     */
    public static void markDoorEscapeFailed(ServerPlayerEntity bot, BlockPos doorBase) {
        if (bot == null || doorBase == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        long expiry = System.currentTimeMillis() + DOOR_ESCAPE_FAIL_DURATION_MS;
        DOOR_ESCAPE_FAILED.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(doorBase.toImmutable(), expiry);
        LOGGER.debug("[markDoorEscapeFailed] uuid={} door={}", uuid, doorBase);
    }
    
    /**
     * Clear all failed-door entries for a bot (e.g., after successful goal completion).
     */
    public static void clearDoorEscapeFailures(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        Map<BlockPos, Long> map = DOOR_ESCAPE_FAILED.remove(bot.getUuid());
        if (map != null && !map.isEmpty()) {
            LOGGER.debug("[clearDoorEscapeFailures] uuid={} cleared {} entries", bot.getUuid(), map.size());
        }
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
            LOGGER.info("Movement choosing path walk: {} -> {}", player.getName().getString(), destination);
            WalkResult walkResult = walkTo(source, player, destination, mode, label, fastReplan, allowSnap);
            if (!walkResult.success() && allowPursuit) {
                long pursuitBudget = fastReplan ? 1200L : TimeUnit.SECONDS.toMillis(3);
                boolean pursued = pursuitUntilClose(player, destination, pursuitBudget, CLOSE_ENOUGH_DISTANCE_SQ, label);
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
            if (abortRequested(player) || isAbortedWalk(walkResult)) {
                return new MovementResult(false, mode, walkResult.arrivedAt(), walkResult.detail());
            }
            LOGGER.info("Walk failed near {} ({}), trying teleport fallback", destination, walkResult.detail());
        }
        if (distanceSq <= 900) {
            WalkResult walkResult = walkTo(source, player, destination, mode, label, fastReplan, allowSnap);
            if (walkResult.success()) {
                return new MovementResult(true, mode, walkResult.arrivedAt(), label + ": walked");
            }
            if (abortRequested(player) || isAbortedWalk(walkResult)) {
                return new MovementResult(false, mode, walkResult.arrivedAt(), walkResult.detail());
            }
            LOGGER.info("Walk failed mid-range near {} ({}), trying teleport fallback", destination, walkResult.detail());
        }
        if (abortRequested(player)) {
            return new MovementResult(false, mode, player.getBlockPos(), label + ": aborted");
        }

        ServerWorld world = getWorld(player);
        BlockPos safe = findNearbyStandable(world, destination, 2, 2);
        if (safe == null) safe = destination;
        player.teleport(world, safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, java.util.Set.of(), player.getYaw(), player.getPitch(), true);
        boolean success = true;
        String rawResult = "Teleported successfully";

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

    private static boolean isAbortedWalk(WalkResult walkResult) {
        if (walkResult == null || walkResult.detail() == null) {
            return false;
        }
        return walkResult.detail().toLowerCase(Locale.ROOT).contains("aborted");
    }

    private static WalkResult walkTo(ServerCommandSource source,
                                     ServerPlayerEntity player,
                                     BlockPos destination,
                                     Mode mode,
                                     String label,
                                     boolean fastReplan,
                                     boolean allowSnap) {
        if (player == null) {
            return new WalkResult(false, destination, label + ": no player for walking");
        }
        ServerWorld world = getWorld(player);
        BlockPos currentPos = player.getBlockPos();
        if (world == null || destination == null) {
            return recordAttempt(player.getUuid(), destination,
                    new WalkResult(false, currentPos, label + ": no world/destination for walking"));
        }
        // Guard: if the caller requests a non-standable destination, adjust to a nearby standable tile.
        // This prevents repeated attempts to "walk into" solid blocks.
        if (!isSolidStandable(world, destination.down(), destination)) {
            BlockPos fallback = findNearbyStandable(world, destination, 2, 2);
            if (fallback != null && !fallback.equals(destination)) {
                LOGGER.debug("walkTo adjusted non-standable dest: {} -> {} label={}", destination.toShortString(), fallback.toShortString(), label);
                destination = fallback;
            }
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
        if (tryImmediateLocalEscape(player, destination, label + "-prewalk")) {
            currentPos = player.getBlockPos();
            if (currentPos.getSquaredDistance(destination) <= CLOSE_ENOUGH_DISTANCE_SQ) {
                return recordAttempt(player.getUuid(), destination,
                        new WalkResult(true, currentPos, label + ": escaped locally"));
            }
        }

        // For very short moves, avoid pathfinding entirely (it can be expensive and noisy).
        if (currentPos != null) {
            double distSq = currentPos.getSquaredDistance(destination);
            int dy = Math.abs(destination.getY() - currentPos.getY());
            if (distSq <= 144.0D && dy <= 2) {
                boolean direct = walkDirect(player, destination, fastReplan ? 900L : 2400L);
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
            if (preclearPlannedStepUps(player, rawPath, "walkTo-preclear")) {
                rawPath = PathFinder.calculatePath(player.getBlockPos(), destination, world);
            }
            List<PathFinder.PathNode> simplified = PathFinder.simplifyPath(rawPath, world);
            boolean useRawSegments = shouldUseRawPathSegmentsForLocalEscape(rawPath, player.getBlockPos(), destination);
            if (player.getBlockPos().getSquaredDistance(destination) <= 256.0D) {
                LOGGER.info("walkTo lead-in [{}]: strategy={} rawFirst={}",
                        label,
                        useRawSegments ? "raw" : "simplified",
                        describeLeadingPathNodes(rawPath, 5));
            }
            Queue<Segment> segments = useRawSegments
                    ? convertRawPathToSegments(rawPath, world, shouldSprintForDestination(player, destination))
                    : PathFinder.convertPathToSegments(simplified, shouldSprintForDestination(player, destination));
            LOGGER.debug("walkTo path (attempt={}): raw={} simplified={} segments={} firstSegment={}",
                    attempt + 1,
                    rawPath.size(),
                    simplified.size(),
                    segments.size(),
                    segments.peek());
            if (segments.isEmpty()) {
                if (tryImmediateLocalEscape(player, destination, label + "-nop-path")) {
                    continue;
                }
                boolean straightWalk = walkDirect(player, destination, fastReplan ? 1400L : 3500L);
                if (straightWalk) {
                    return recordAttempt(player.getUuid(), destination,
                            new WalkResult(true, destination, label + ": walked directly (no path)"));
                }
                return recordAttempt(player.getUuid(), destination,
                        new WalkResult(false, player.getBlockPos(), label + ": no walkable path"));
            }

            boolean allOk = true;
            for (Segment segment : segments) {
                if (tryImmediateLocalEscape(player, segment.end(), label + "-segment-pre")) {
                    lastReached = player.getBlockPos();
                    if (lastReached.getSquaredDistance(segment.end()) <= CLOSE_ENOUGH_DISTANCE_SQ) {
                        continue;
                    }
                    if (!replanned) {
                        replanned = true;
                        LOGGER.info("walkTo local-escape adopted [{}]: replanning from {}", label, lastReached.toShortString());
                        allOk = false;
                        break;
                    }
                }
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

    static boolean shouldUseRawPathSegmentsForLocalEscape(List<PathFinder.PathNode> rawPath, BlockPos start, BlockPos destination) {
        if (rawPath == null || rawPath.size() < 2 || start == null || destination == null) {
            return false;
        }
        if (start.getSquaredDistance(destination) > 256.0D) {
            return false;
        }
        int inspect = Math.min(rawPath.size() - 1, 4);
        for (int i = 1; i <= inspect; i++) {
            BlockPos prev = rawPath.get(i - 1).getPos();
            BlockPos cur = rawPath.get(i).getPos();
            boolean diagonal = cur.getX() != prev.getX() && cur.getZ() != prev.getZ();
            boolean vertical = cur.getY() != prev.getY();
            if (diagonal || vertical) {
                return true;
            }
        }
        return false;
    }

    private static Queue<Segment> convertRawPathToSegments(List<PathFinder.PathNode> rawPath,
                                                           ServerWorld world,
                                                           boolean sprint) {
        ArrayDeque<Segment> segments = new ArrayDeque<>();
        if (rawPath == null || rawPath.size() < 2) {
            return segments;
        }
        for (int i = 1; i < rawPath.size(); i++) {
            PathFinder.PathNode prev = rawPath.get(i - 1);
            PathFinder.PathNode cur = rawPath.get(i);
            if (prev.getPos().equals(cur.getPos())) {
                continue;
            }
            segments.add(new Segment(prev.getPos(), cur.getPos(), prev.jumpNeeded() || cur.jumpNeeded(), sprint));
        }
        return collapseFluidRawSegments(segments, world);
    }

    private static ArrayDeque<Segment> collapseFluidRawSegments(Queue<Segment> rawSegments, ServerWorld world) {
        ArrayDeque<Segment> collapsed = new ArrayDeque<>();
        if (rawSegments == null || rawSegments.isEmpty()) {
            return collapsed;
        }
        Segment current = null;
        for (Segment next : rawSegments) {
            if (next == null) {
                continue;
            }
            if (current == null) {
                current = next;
                continue;
            }
            if (canMergeFluidSegment(current, next, world)) {
                current = new Segment(current.start(), next.end(), false, current.sprint() || next.sprint());
            } else {
                collapsed.add(current);
                current = next;
            }
        }
        if (current != null) {
            collapsed.add(current);
        }
        return collapsed;
    }

    private static boolean canMergeFluidSegment(Segment current, Segment next, ServerWorld world) {
        if (current == null || next == null || world == null) {
            return false;
        }
        if (current.jump() || next.jump()) {
            return false;
        }
        if (!current.end().equals(next.start())) {
            return false;
        }
        if (current.start().getY() != current.end().getY()
                || next.start().getY() != next.end().getY()
                || current.end().getY() != next.end().getY()) {
            return false;
        }
        if (isDiagonalMove(current.start(), current.end()) || isDiagonalMove(next.start(), next.end())) {
            return false;
        }
        if (current.start().getSquaredDistance(next.end()) > 25.0D) {
            return false;
        }
        return !isTrapLikeLocalCell(world, current.end()) && !isTrapLikeLocalCell(world, next.end());
    }

    private static boolean isDiagonalMove(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return false;
        }
        return from.getX() != to.getX() && from.getZ() != to.getZ();
    }

    private static String describeLeadingPathNodes(List<PathFinder.PathNode> rawPath, int limit) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "(empty)";
        }
        StringBuilder builder = new StringBuilder();
        int count = Math.min(rawPath.size(), Math.max(1, limit));
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(" -> ");
            }
            builder.append(rawPath.get(i).getPos().toShortString());
        }
        return builder.toString();
    }

    private static String describeBlockRoute(List<BlockPos> route, int limit) {
        if (route == null || route.isEmpty()) {
            return "(empty)";
        }
        StringBuilder builder = new StringBuilder();
        int count = Math.min(route.size(), Math.max(1, limit));
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(" -> ");
            }
            builder.append(route.get(i).toShortString());
        }
        if (route.size() > count) {
            builder.append(" -> ...");
        }
        return builder.toString();
    }

    private static boolean tryImmediateLocalEscape(ServerPlayerEntity player, BlockPos destination, String label) {
        return tryImmediateLocalEscape(player, destination, label, false);
    }

    private static boolean tryImmediateLocalEscape(ServerPlayerEntity player,
                                                   BlockPos destination,
                                                   String label,
                                                   boolean stagnant) {
        if (shouldSuppressLocalEscapeReplan(player, destination, label, stagnant)) {
            return false;
        }
        LocalEscapeCandidate candidate = findBestLocalEscapeCandidate(player, destination);
        if (candidate == null || candidate.target() == null) {
            return false;
        }
        LOGGER.info("movement local escape [{}]: mode=precision bot={} target={} route={} stepUp={} leavesTrap={} override={} score={} reason={}",
                label,
                player.getName().getString(),
                candidate.target().toShortString(),
                describeBlockRoute(candidate.route(), 4),
                candidate.stepUp(),
                candidate.leavesTrap(),
                candidate.overrideTarget(),
                String.format(Locale.ROOT, "%.2f", candidate.score()),
                candidate.reason());
        boolean success = executeLocalEscapeCandidate(player, candidate, destination, label);
        if (success && player.getUuid() != null) {
            LocalEscapeDirective directive = LOCAL_ESCAPE_DIRECTIVES.get(player.getUuid());
            if (directive != null && directive.target().equals(candidate.target())) {
                LOCAL_ESCAPE_DIRECTIVES.remove(player.getUuid());
            }
        }
        return success;
    }

    private static boolean executeLocalEscapeCandidate(ServerPlayerEntity player,
                                                       LocalEscapeCandidate candidate,
                                                       BlockPos destination,
                                                       String label) {
        if (player == null || candidate == null) {
            return false;
        }
        List<BlockPos> route = candidate.route() == null || candidate.route().isEmpty()
                ? List.of(candidate.target())
                : candidate.route();
        BlockPos firstHop = route.get(0);
        BlockPos beforeHop = player.getBlockPos();
        if (beforeHop.equals(firstHop)) {
            commitLocalEscape(player, candidate, destination);
            return true;
        }
        if (firstHop.getY() > beforeHop.getY()) {
            tryTrimStepUpClearance(player, beforeHop, firstHop, label + "-route");
        }
        boolean reached = nudgeTowardExactBlock(player, firstHop, firstHop.getY() > beforeHop.getY(), label + "-route-1");
        if (reached) {
            commitLocalEscape(player, candidate, destination);
        } else {
            clearLocalEscapeCommitment(player.getUuid());
        }
        return reached || !player.getBlockPos().equals(beforeHop);
    }

    private static boolean nudgeTowardExactBlock(ServerPlayerEntity bot,
                                                 BlockPos target,
                                                 boolean stepUp,
                                                 String label) {
        if (bot == null || target == null) {
            return false;
        }
        long timeoutMs = stepUp ? 1600L : 1100L;
        boolean reached = nudgeTowardUntilClose(bot, target, 0.64D, timeoutMs, 0.18D, label);
        if (bot.getBlockPos().equals(target)) {
            return true;
        }
        return reached && bot.squaredDistanceTo(Vec3d.ofCenter(target)) <= 0.64D;
    }

    private static void commitLocalEscape(ServerPlayerEntity player,
                                          LocalEscapeCandidate candidate,
                                          BlockPos destination) {
        if (player == null || candidate == null || player.getUuid() == null) {
            return;
        }
        List<BlockPos> route = candidate.route() == null || candidate.route().isEmpty()
                ? List.of(candidate.target())
                : candidate.route();
        BlockPos routeTarget = route.get(route.size() - 1).toImmutable();
        boolean precision = candidate.overrideTarget()
                || candidate.stepUp()
                || route.size() <= 1
                || hasVerticalMove(route, player.getBlockPos());
        LOCAL_ESCAPE_COMMITMENTS.put(player.getUuid(), new LocalEscapeCommitment(
                routeTarget,
                destination == null ? routeTarget : destination.toImmutable(),
                precision,
                System.currentTimeMillis() + LOCAL_ESCAPE_COMMITMENT_MS,
                candidate.reason()
        ));
    }

    private static boolean hasVerticalMove(List<BlockPos> route, BlockPos start) {
        if (route == null || route.isEmpty() || start == null) {
            return false;
        }
        BlockPos prev = start;
        for (BlockPos pos : route) {
            if (pos != null && pos.getY() != prev.getY()) {
                return true;
            }
            if (pos != null) {
                prev = pos;
            }
        }
        return false;
    }

    private static void clearLocalEscapeCommitment(UUID botId) {
        if (botId != null) {
            LOCAL_ESCAPE_COMMITMENTS.remove(botId);
        }
    }

    private static boolean shouldSuppressLocalEscapeReplan(ServerPlayerEntity player,
                                                           BlockPos destination,
                                                           String label,
                                                           boolean stagnant) {
        if (player == null || destination == null || player.getUuid() == null) {
            return false;
        }
        LocalEscapeCommitment commitment = LOCAL_ESCAPE_COMMITMENTS.get(player.getUuid());
        if (commitment == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now >= commitment.expiresAtMs()) {
            LOCAL_ESCAPE_COMMITMENTS.remove(player.getUuid());
            return false;
        }
        ServerWorld world = getWorld(player);
        if (world == null) {
            LOCAL_ESCAPE_COMMITMENTS.remove(player.getUuid());
            return false;
        }
        if (stagnant || commitment.precision() || isTrapLikeLocalCell(world, player.getBlockPos())) {
            return false;
        }
        double destinationShiftSq = destination.getSquaredDistance(commitment.destinationHint());
        double routeShiftSq = destination.getSquaredDistance(commitment.routeTarget());
        if (destinationShiftSq > 100.0D && routeShiftSq > 64.0D) {
            LOCAL_ESCAPE_COMMITMENTS.remove(player.getUuid());
            return false;
        }
        if (player.getBlockPos().getSquaredDistance(commitment.routeTarget()) <= 2.25D) {
            LOCAL_ESCAPE_COMMITMENTS.remove(player.getUuid());
            return false;
        }
        if (player.getBlockPos().getSquaredDistance(destination) > 256.0D) {
            return false;
        }
        LOGGER.info("movement local escape [{}]: mode=fluid suppress=true routeTarget={} reason={}",
                label,
                commitment.routeTarget().toShortString(),
                commitment.reason());
        return true;
    }

    private static boolean isTrapLikeLocalCell(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        int openNeighbors = countOpenCardinalNeighbors(world, pos);
        int reachableArea = estimateLocalEscapeReachableArea(world, pos, 2);
        return openNeighbors < 3 && reachableArea < 10;
    }

    private static LocalEscapeCandidate findBestLocalEscapeCandidate(ServerPlayerEntity player, BlockPos destination) {
        if (player == null || destination == null) {
            return null;
        }
        ServerWorld world = getWorld(player);
        if (world == null) {
            return null;
        }
        BlockPos start = player.getBlockPos();
        LocalEscapeCandidate directiveCandidate = peekLocalEscapeDirective(player, destination, world, start);
        if (directiveCandidate != null) {
            return directiveCandidate;
        }

        int currentOpenNeighbors = countOpenCardinalNeighbors(world, start);
        int currentReachableArea = estimateLocalEscapeReachableArea(world, start, 2);
        double startDistBlocks = Math.sqrt(start.getSquaredDistance(destination));
        if (currentOpenNeighbors >= 3 && currentReachableArea >= 7 && startDistBlocks > 4.5D) {
            return null;
        }
        LocalEscapeCandidate best = searchForLocalEscapeRoute(
                world,
                start,
                destination,
                currentOpenNeighbors,
                currentReachableArea,
                startDistBlocks
        );
        if (best != null) {
            return best;
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos flat = start.add(dx, 0, dz);
                best = chooseBetterLocalEscape(best,
                        evaluateLocalEscapeCandidate(world, start, destination, flat, false, currentOpenNeighbors, startDistBlocks));

                BlockPos step = start.add(dx, 1, dz);
                best = chooseBetterLocalEscape(best,
                        evaluateLocalEscapeCandidate(world, start, destination, step, true, currentOpenNeighbors, startDistBlocks));
            }
        }
        return best;
    }

    private static LocalEscapeCandidate peekLocalEscapeDirective(ServerPlayerEntity player,
                                                                 BlockPos destination,
                                                                 ServerWorld world,
                                                                 BlockPos start) {
        if (player == null || destination == null || world == null || start == null) {
            return null;
        }
        LocalEscapeDirective directive = LOCAL_ESCAPE_DIRECTIVES.get(player.getUuid());
        if (directive == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now >= directive.expiresAtMs()) {
            LOCAL_ESCAPE_DIRECTIVES.remove(player.getUuid());
            return null;
        }
        if (start.getSquaredDistance(directive.target()) <= CLOSE_ENOUGH_DISTANCE_SQ) {
            LOCAL_ESCAPE_DIRECTIVES.remove(player.getUuid());
            return null;
        }
        boolean stepUp = directive.target().getY() > start.getY();
        if (!isValidLocalEscapeCandidate(world, start, directive.target(), stepUp)) {
            return null;
        }
        int currentOpen = countOpenCardinalNeighbors(world, start);
        int candidateOpen = countOpenCardinalNeighbors(world, directive.target());
        boolean leavesTrap = stepUp || candidateOpen > currentOpen;
                double progressBlocks = Math.sqrt(start.getSquaredDistance(destination))
                - Math.sqrt(directive.target().getSquaredDistance(destination));
        double score = scoreLocalEscapeCandidate(progressBlocks, currentOpen, candidateOpen, stepUp, leavesTrap, true);
        return new LocalEscapeCandidate(
                directive.target(),
                score,
                stepUp,
                leavesTrap,
                true,
                directive.reason(),
                List.of(directive.target().toImmutable())
        );
    }

    private static LocalEscapeCandidate chooseBetterLocalEscape(LocalEscapeCandidate best, LocalEscapeCandidate candidate) {
        if (candidate == null) {
            return best;
        }
        if (best == null || candidate.score() > best.score()) {
            return candidate;
        }
        return best;
    }

    private static LocalEscapeCandidate searchForLocalEscapeRoute(ServerWorld world,
                                                                  BlockPos start,
                                                                  BlockPos destination,
                                                                  int currentOpenNeighbors,
                                                                  int currentReachableArea,
                                                                  double startDistBlocks) {
        if (world == null || start == null || destination == null) {
            return null;
        }
        int maxHops = startDistBlocks <= 6.0D ? 4 : 3;
        ArrayDeque<LocalEscapeSearchState> frontier = new ArrayDeque<>();
        Map<BlockPos, Integer> bestDepth = new HashMap<>();
        frontier.add(new LocalEscapeSearchState(start, List.of(start.toImmutable())));
        bestDepth.put(start.toImmutable(), 0);

        LocalEscapeCandidate best = null;
        while (!frontier.isEmpty()) {
            LocalEscapeSearchState state = frontier.poll();
            int depth = state.route().size() - 1;
            if (depth >= maxHops) {
                continue;
            }

            for (LocalEscapeMove move : enumerateLocalEscapeMoves(world, state.pos())) {
                BlockPos next = move.target().toImmutable();
                int nextDepth = depth + 1;
                Integer previousDepth = bestDepth.get(next);
                if (previousDepth != null && previousDepth <= nextDepth) {
                    continue;
                }

                List<BlockPos> route = new ArrayList<>(state.route());
                route.add(next);
                bestDepth.put(next, nextDepth);

                int candidateOpenNeighbors = countOpenCardinalNeighbors(world, next);
                int candidateReachableArea = estimateLocalEscapeReachableArea(world, next, 2);
                int elevationGain = next.getY() - start.getY();
                boolean leavesTrap = isCompellingLocalEscapeEndpoint(
                        currentOpenNeighbors,
                        currentReachableArea,
                        candidateOpenNeighbors,
                        candidateReachableArea,
                        elevationGain,
                        nextDepth
                );
                double progressBlocks = startDistBlocks - Math.sqrt(next.getSquaredDistance(destination));
                double score = scoreLocalEscapeRoute(
                        progressBlocks,
                        currentOpenNeighbors,
                        candidateOpenNeighbors,
                        currentReachableArea,
                        candidateReachableArea,
                        elevationGain,
                        leavesTrap,
                        false,
                        nextDepth
                );
                if (leavesTrap && route.size() > 1) {
                    BlockPos firstHop = route.get(1);
                    best = chooseBetterLocalEscape(best, new LocalEscapeCandidate(
                            firstHop,
                            score,
                            firstHop.getY() > start.getY(),
                            true,
                            false,
                            describeLocalEscapeReason(route, candidateOpenNeighbors, candidateReachableArea, elevationGain),
                            List.copyOf(route.subList(1, route.size()))
                    ));
                }

                if (nextDepth < maxHops) {
                    frontier.add(new LocalEscapeSearchState(next, List.copyOf(route)));
                }
            }
        }

        return best;
    }

    private static List<LocalEscapeMove> enumerateLocalEscapeMoves(ServerWorld world, BlockPos start) {
        List<LocalEscapeMove> moves = new ArrayList<>(24);
        if (world == null || start == null) {
            return moves;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                BlockPos flat = start.add(dx, 0, dz);
                if (isValidLocalEscapeMove(world, start, flat)) {
                    moves.add(new LocalEscapeMove(flat.toImmutable(), false));
                }

                BlockPos stepUp = start.add(dx, 1, dz);
                if (isValidLocalEscapeMove(world, start, stepUp)) {
                    moves.add(new LocalEscapeMove(stepUp.toImmutable(), true));
                }

                BlockPos stepDown = start.add(dx, -1, dz);
                if (isValidLocalEscapeMove(world, start, stepDown)) {
                    moves.add(new LocalEscapeMove(stepDown.toImmutable(), false));
                }
            }
        }
        return moves;
    }

    private static int estimateLocalEscapeReachableArea(ServerWorld world, BlockPos start, int maxDepth) {
        if (world == null || start == null || maxDepth < 0) {
            return 0;
        }
        ArrayDeque<LocalEscapeSearchState> frontier = new ArrayDeque<>();
        Map<BlockPos, Integer> seen = new HashMap<>();
        BlockPos root = start.toImmutable();
        frontier.add(new LocalEscapeSearchState(root, List.of(root)));
        seen.put(root, 0);

        while (!frontier.isEmpty()) {
            LocalEscapeSearchState state = frontier.poll();
            int depth = state.route().size() - 1;
            if (depth >= maxDepth) {
                continue;
            }
            for (LocalEscapeMove move : enumerateLocalEscapeMoves(world, state.pos())) {
                BlockPos next = move.target();
                int nextDepth = depth + 1;
                Integer priorDepth = seen.get(next);
                if (priorDepth != null && priorDepth <= nextDepth) {
                    continue;
                }
                seen.put(next, nextDepth);
                List<BlockPos> route = new ArrayList<>(state.route());
                route.add(next);
                frontier.add(new LocalEscapeSearchState(next, List.copyOf(route)));
            }
        }
        return seen.size();
    }

    static boolean isCompellingLocalEscapeEndpoint(int currentOpenNeighbors,
                                                   int currentReachableArea,
                                                   int candidateOpenNeighbors,
                                                   int candidateReachableArea,
                                                   int elevationGain,
                                                   int pathLength) {
        if (candidateOpenNeighbors >= 3 || candidateReachableArea >= 7) {
            return true;
        }
        if (elevationGain > 0 && candidateReachableArea >= currentReachableArea + 2) {
            return true;
        }
        if (pathLength >= 2 && candidateReachableArea > currentReachableArea) {
            return true;
        }
        return false;
    }

    static double scoreLocalEscapeRoute(double progressBlocks,
                                        int currentOpenNeighbors,
                                        int candidateOpenNeighbors,
                                        int currentReachableArea,
                                        int candidateReachableArea,
                                        int elevationGain,
                                        boolean leavesTrap,
                                        boolean overrideTarget,
                                        int pathLength) {
        double score = progressBlocks * 4.0D;
        score += Math.max(0, candidateOpenNeighbors - currentOpenNeighbors) * 1.5D;
        score += Math.max(0, candidateReachableArea - currentReachableArea) * 1.2D;
        score += Math.max(0, elevationGain) * 1.0D;
        score -= Math.max(0, pathLength - 1) * 0.75D;
        if (leavesTrap) {
            score += 5.0D;
        }
        if (overrideTarget) {
            score += 6.0D;
        }
        return score;
    }

    private static String describeLocalEscapeReason(List<BlockPos> route,
                                                    int candidateOpenNeighbors,
                                                    int candidateReachableArea,
                                                    int elevationGain) {
        String routeKind = elevationGain > 0 ? "route-step-up" : "route-flat";
        return routeKind
                + " len=" + Math.max(0, route.size() - 1)
                + " open=" + candidateOpenNeighbors
                + " area=" + candidateReachableArea;
    }

    private static LocalEscapeCandidate evaluateLocalEscapeCandidate(ServerWorld world,
                                                                     BlockPos start,
                                                                     BlockPos destination,
                                                                     BlockPos candidate,
                                                                     boolean stepUp,
                                                                     int currentOpenNeighbors,
                                                                     double startDistBlocks) {
        if (world == null || start == null || destination == null || candidate == null) {
            return null;
        }
        if (!isValidLocalEscapeCandidate(world, start, candidate, stepUp)) {
            return null;
        }
        int candidateOpenNeighbors = countOpenCardinalNeighbors(world, candidate);
        boolean leavesTrap = stepUp || candidateOpenNeighbors > currentOpenNeighbors;
        double candidateDistBlocks = Math.sqrt(candidate.getSquaredDistance(destination));
        double progressBlocks = startDistBlocks - candidateDistBlocks;
        if (progressBlocks < -0.35D && !leavesTrap) {
            return null;
        }
        double score = scoreLocalEscapeCandidate(
                progressBlocks,
                currentOpenNeighbors,
                candidateOpenNeighbors,
                stepUp,
                leavesTrap,
                false
        );
        return new LocalEscapeCandidate(
                candidate.toImmutable(),
                score,
                stepUp,
                leavesTrap,
                false,
                stepUp ? "step-up" : "flat",
                List.of(candidate.toImmutable())
        );
    }

    static double scoreLocalEscapeCandidate(double progressBlocks,
                                            int currentOpenNeighbors,
                                            int candidateOpenNeighbors,
                                            boolean stepUp,
                                            boolean leavesTrap,
                                            boolean overrideTarget) {
        return scoreLocalEscapeRoute(
                progressBlocks,
                currentOpenNeighbors,
                candidateOpenNeighbors,
                currentOpenNeighbors * 2,
                candidateOpenNeighbors * 2,
                stepUp ? 1 : 0,
                leavesTrap,
                overrideTarget,
                1
        );
    }

    private static boolean isValidLocalEscapeCandidate(ServerWorld world, BlockPos start, BlockPos candidate, boolean stepUp) {
        if (world == null || start == null || candidate == null) {
            return false;
        }
        int dy = candidate.getY() - start.getY();
        if (stepUp) {
            if (dy != 1) {
                return false;
            }
        } else if (dy != 0) {
            return false;
        }
        int dx = candidate.getX() - start.getX();
        int dz = candidate.getZ() - start.getZ();
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0)) {
            return false;
        }
        if (!isSolidStandable(world, candidate.down(), candidate)) {
            return false;
        }
        if (!isLocalEscapePathClear(world, start, candidate, stepUp)) {
            return false;
        }
        return true;
    }

    private static boolean isValidLocalEscapeMove(ServerWorld world, BlockPos start, BlockPos candidate) {
        if (world == null || start == null || candidate == null) {
            return false;
        }
        int dy = candidate.getY() - start.getY();
        if (dy < -1 || dy > 1) {
            return false;
        }
        int dx = candidate.getX() - start.getX();
        int dz = candidate.getZ() - start.getZ();
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0)) {
            return false;
        }
        if (!isSolidStandable(world, candidate.down(), candidate)) {
            return false;
        }
        return isLocalEscapePathClear(world, start, candidate, dy > 0);
    }

    private static boolean isLocalEscapePathClear(ServerWorld world, BlockPos origin, BlockPos candidate, boolean stepUp) {
        if (world == null || origin == null || candidate == null) {
            return false;
        }
        int dx = candidate.getX() - origin.getX();
        int dz = candidate.getZ() - origin.getZ();

        if (stepUp && !hasClearance(world, origin)) {
            return false;
        }

        if (dx != 0 && dz != 0) {
            BlockPos xAdj = origin.add(dx, 0, 0);
            BlockPos zAdj = origin.add(0, 0, dz);
            boolean xBlocked = isBlockingPassage(world, xAdj);
            boolean zBlocked = isBlockingPassage(world, zAdj);
            if (xBlocked && zBlocked) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlockingPassage(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return true;
        }
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                || !world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty();
    }

    private static int countOpenCardinalNeighbors(ServerWorld world, BlockPos center) {
        if (world == null || center == null) {
            return 0;
        }
        int open = 0;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos candidate = center.offset(dir);
            if (isSolidStandable(world, candidate.down(), candidate)) {
                open++;
            }
        }
        return open;
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
        if (tryImmediateLocalEscape(player, segment.end(), "walkSegment-entry")) {
            if (player.getBlockPos().getSquaredDistance(segment.end()) <= CLOSE_ENOUGH_DISTANCE_SQ) {
                return new SegmentResult(true, false);
            }
        }

        double lastDistanceSq = Double.MAX_VALUE;
        int stagnantSteps = 0;
        BlockPos lastBlockPos = player.getBlockPos();
        int sameBlockSteps = 0;

        for (int steps = 0; System.currentTimeMillis() < endTime && steps < maxSteps; steps++) {
            if (abortRequested(player)) {
                BotActions.stop(player);
                return new SegmentResult(false, false);
            }
            BlockPos currentBlock = player.getBlockPos();
            if (currentBlock.equals(lastBlockPos)) {
                sameBlockSteps++;
            } else {
                lastBlockPos = currentBlock;
                sameBlockSteps = 0;
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
                    if (tryImmediateLocalEscape(player, segment.end(), "walkSegment-local-escape", true)) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryOpenDoorToward(player, segment.end())) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    // If the door is already open but we're not crossing the threshold, step through it decisively.
                    if (tryTraverseDoorway(player, segment.end(), "walkSegment")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryDoorSubgoalToward(player, segment.end(), "walkSegment")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    // Around-corner escape: allow temporary distance increases if that's what it takes.
                    if (tryDoorEscapeToward(player, segment.end(), null, "walkSegment")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    // Common micro-obstacle case: a single placed block in the walking line. If yaw is slightly off,
                    // BotActions.autoJumpIfNeeded() may miss it (cardinal-only). Try a directed step-up.
                    if (tryStepUpToward(player, segment.end(), "walkSegment")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    // If we're executing a skill and keep colliding, proactively mine a safe obstruction block
                    // (instead of pushing into it until IN_WALL rescue triggers).
                    if (tryMineObstructionToward(player, segment.end(), "walkSegment")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    // Local corner probe: try a nearby standable tile to break out of collision jitter.
                    if (tryLocalUnstick(player, segment.end(), "walkSegment")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                }
                if (stagnantSteps >= 5) {
                    if (trySidestepAround(player, segment.end())) {
                        // Reset progress tracking after a successful sidestep.
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryDoorEscapeToward(player, segment.end(), null, "walkSegment-hard")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryMineObstructionToward(player, segment.end(), "walkSegment-hard")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryLocalUnstick(player, segment.end(), "walkSegment-hard")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    LOGGER.debug("walkSegment stalled near {} dist={}", segment.end(), Math.sqrt(distanceSq));
                    break;
                }
            } else {
                stagnantSteps = 0;
                lastDistanceSq = distanceSq;
            }
            // Hard anti-wall-hump trigger: if we're stuck in the same block AND not improving, stop pushing and escape.
            if (sameBlockSteps >= STUCK_SAME_BLOCK_STEPS_TRIGGER && stagnantSteps >= STUCK_STAGNANT_STEPS_TRIGGER) {
                BotActions.stop(player);
                if (tryStepUpToward(player, segment.end(), "walkSegment-stuck")) {
                    stagnantSteps = 0;
                    lastDistanceSq = Double.MAX_VALUE;
                    sameBlockSteps = 0;
                    continue;
                }
                if (tryDoorEscapeToward(player, segment.end(), null, "walkSegment-stuck")) {
                    stagnantSteps = 0;
                    lastDistanceSq = Double.MAX_VALUE;
                    sameBlockSteps = 0;
                    continue;
                }
                if (tryMineObstructionToward(player, segment.end(), "walkSegment-stuck")) {
                    stagnantSteps = 0;
                    lastDistanceSq = Double.MAX_VALUE;
                    sameBlockSteps = 0;
                    continue;
                }
                if (tryLocalUnstick(player, segment.end(), "walkSegment-stuck")) {
                    stagnantSteps = 0;
                    lastDistanceSq = Double.MAX_VALUE;
                    sameBlockSteps = 0;
                    continue;
                }
            }
            if (!inputStepToward(player, segment.end(), target, segment.sprint(), segment.jump())) {
                BotActions.stop(player);
                return new SegmentResult(false, false);
            }
            sleep(35L);
        }
        double remaining = Math.sqrt(player.squaredDistanceTo(target));
        LOGGER.debug("walkSegment timed out near {} remainingDist={}", segment.end(), remaining);
        BotActions.stop(player);
        // Force a small snap forward when very close to planned segment end to avoid infinite loop.
        // Gated behind the per-bot teleport preference so bots with teleport disabled
        // never snap through walls/obstacles.
        if (allowSnap && remaining <= 2.2D && SkillPreferences.teleportDuringSkills(player)) {
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
        BlockPos openablePos = normalizeOpenableBase(world, candidate);
        if (openablePos == null) {
            return false;
        }
        BlockState state = world.getBlockState(openablePos);
        if (!isOpenableBlock(state)) {
            return false;
        }
        if (state.getBlock() instanceof DoorBlock && state.isOf(net.minecraft.block.Blocks.IRON_DOOR)) {
            maybeWarnIronDoor(player, openablePos);
            return false;
        }

        Direction toward = approximateToward(player.getBlockPos(), openablePos);
        if (!toward.getAxis().isHorizontal()) {
            toward = player.getHorizontalFacing();
        }
        final Direction hitFacing = toward;
        final Direction travelDir = computeOpenableTravelDir(world, openablePos, player.getBlockPos(), hitFacing);

        if (state.contains(Properties.OPEN) && Boolean.TRUE.equals(state.get(Properties.OPEN))) {
            // Treat an already-open openable as a success so callers can commit to stepping through.
            if (isAutoCloseOpenable(state) && doorAttemptAllowed(player.getUuid(), openablePos)) {
                scheduleDoorClose(player, world.getRegistryKey(), openablePos.toImmutable(), travelDir);
            }
            return true;
        }
        if (!doorAttemptAllowed(player.getUuid(), openablePos)) {
            return false;
        }
        // Enforce survival-like interaction constraints; prevents opening through walls.
        // Use continuous distance so diagonal-adjacent positions don't get incorrectly treated as "far".
        double selfOpenableDistSq = player.squaredDistanceTo(Vec3d.ofCenter(openablePos).add(0, 0.35, 0));
        if (selfOpenableDistSq > 2.25D && !BlockInteractionService.canInteract(player, openablePos)) {
            maybeLogDoor(player, openablePos, "door-open blocked: cannot interact (reach/LoS)");
            return false;
        }

        boolean opened = runOnServerThread(player, () -> {
            Vec3d hitVec = Vec3d.ofCenter(openablePos).add(0, 0.35, 0);
            BlockHitResult hit = new BlockHitResult(hitVec, hitFacing, openablePos, false);
            ActionResult result = player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND, hit);
            if (result.isAccepted()) {
                player.swingHand(Hand.MAIN_HAND, true);
            }
        });
        if (!opened) {
            maybeLogDoor(player, openablePos, "door-open failed: interactBlock dispatch failed");
            return false;
        }
        // If the interaction did open the door/gate, schedule a close once we've passed through.
        BlockState after = world.getBlockState(openablePos);
        if (after.contains(Properties.OPEN) && Boolean.TRUE.equals(after.get(Properties.OPEN))) {
            if (isAutoCloseOpenable(after)) {
                scheduleDoorClose(player, world.getRegistryKey(), openablePos.toImmutable(), travelDir);
            }
            maybeLogDoor(player, openablePos, "door-open success");
            return true;
        }
        maybeLogDoor(player, openablePos, "door-open failed: door did not toggle open");
        return false;
    }

    /**
     * Commit to crossing a specific blocking openable (door/gate) toward a goal.
     *
     * <p>Why: raycasting to a far-away goal can miss a nearby doorway if the path is around a corner,
     * and general "door escape" selection can get pulled by competing heuristics. This helper is
     * a deterministic "approach -> open -> step through" for a known openable.</p>
     */
    public static boolean tryTraverseOpenableToward(ServerPlayerEntity bot,
                                                    BlockPos openablePos,
                                                    BlockPos goal,
                                                    String label) {
        if (bot == null || openablePos == null) {
            return false;
        }
        ServerWorld world = getWorld(bot);
        if (world == null) {
            return false;
        }

        BlockPos base = normalizeOpenableBase(world, openablePos);
        if (base == null) {
            return false;
        }
        BlockState state = world.getBlockState(base);
        if (!isOpenableBlock(state)) {
            return false;
        }

        // Determine the two sides of the doorway.
        Direction facing = null;
        if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.FACING)) {
            facing = state.get(DoorBlock.FACING);
        } else if (state.getBlock() instanceof FenceGateBlock && state.contains(FenceGateBlock.FACING)) {
            facing = state.get(FenceGateBlock.FACING);
        }
        if (facing == null || !facing.getAxis().isHorizontal()) {
            facing = bot.getHorizontalFacing();
        }

        BlockPos sideASeed = base.offset(facing);
        BlockPos sideBSeed = base.offset(facing.getOpposite());

        // Pick approach as the side we're currently closest to.
        BlockPos botPos = bot.getBlockPos();
        BlockPos approachSeed = botPos.getSquaredDistance(sideASeed) <= botPos.getSquaredDistance(sideBSeed) ? sideASeed : sideBSeed;

        // Pick step-through as the side that best progresses toward the goal (if provided), otherwise the opposite.
        BlockPos stepSeed;
        if (goal != null) {
            stepSeed = goal.getSquaredDistance(sideASeed) <= goal.getSquaredDistance(sideBSeed) ? sideASeed : sideBSeed;
            if (stepSeed.equals(approachSeed)) {
                stepSeed = approachSeed.equals(sideASeed) ? sideBSeed : sideASeed;
            }
        } else {
            stepSeed = approachSeed.equals(sideASeed) ? sideBSeed : sideASeed;
        }

        // Hovel learnings: the tile directly “in front” of a door isn't always standable (stairs/half-blocks/etc).
        // Pick a nearby standable tile on the SAME side of the doorway instead of giving up.
        boolean approachFrontSide = isOnDoorFrontSide(base, facing, approachSeed);
        boolean stepFrontSide = isOnDoorFrontSide(base, facing, stepSeed);
        BlockPos approach = findStandableSameDoorSide(world, base, facing, approachFrontSide, approachSeed, 2);
        BlockPos step = findStandableSameDoorSide(world, base, facing, stepFrontSide, stepSeed, 2);
        if (approach == null || step == null) {
            return false;
        }

        maybeLogDoor(bot, base, "doorway-commit: label=" + label
                + " approach=" + approach.toShortString()
                + " step=" + step.toShortString()
                + " goal=" + (goal != null ? goal.toShortString() : "null"));

        boolean approached = botPos.getSquaredDistance(approach) <= 2.25D
                || nudgeTowardUntilClose(bot, approach, 2.25D, 2000L, 0.18, label + "-door-approach");
        if (!approached) {
            return false;
        }

        tryOpenDoorAt(bot, base);
        // Be slightly forgiving: stepping "enough" across the threshold is usually sufficient for replans.
        return nudgeTowardUntilClose(bot, step, 4.0D, 3200L, 0.22, label + "-door-step");
    }

    private static Direction computeOpenableTravelDir(ServerWorld world,
                                                     BlockPos openableBase,
                                                     BlockPos botPos,
                                                     Direction fallback) {
        if (world == null || openableBase == null || botPos == null || fallback == null) {
            return fallback;
        }

        try {
            BlockState state = world.getBlockState(openableBase);
            Direction facing = null;
            if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.FACING)) {
                facing = state.get(DoorBlock.FACING);
            } else if (state.getBlock() instanceof FenceGateBlock && state.contains(FenceGateBlock.FACING)) {
                facing = state.get(FenceGateBlock.FACING);
            }
            if (facing == null || !facing.getAxis().isHorizontal()) {
                return fallback;
            }

            BlockPos front = openableBase.offset(facing);
            BlockPos back = openableBase.offset(facing.getOpposite());
            BlockPos nearSide = botPos.getSquaredDistance(front) <= botPos.getSquaredDistance(back) ? front : back;
            BlockPos farSide = nearSide.equals(front) ? back : front;
            Direction travel = approximateToward(openableBase, farSide);
            return travel.getAxis().isHorizontal() ? travel : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean isOpenableBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof TrapdoorBlock;
    }

    private static boolean isAutoCloseOpenable(BlockState state) {
        if (state == null) {
            return false;
        }
        // Auto-closing trapdoors can be annoying and some are used as floor hatches.
        // Stick to doors and fence gates for now.
        return state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof FenceGateBlock;
    }

    private static BlockPos normalizeOpenableBase(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock) {
            return normalizeDoorBase(world, pos);
        }
        if (state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof TrapdoorBlock) {
            return pos;
        }
        // For door raycasts and edge cases, probe one up/down.
        BlockState up = world.getBlockState(pos.up());
        if (up.getBlock() instanceof DoorBlock || up.getBlock() instanceof FenceGateBlock || up.getBlock() instanceof TrapdoorBlock) {
            return normalizeOpenableBase(world, pos.up());
        }
        BlockState down = world.getBlockState(pos.down());
        if (down.getBlock() instanceof DoorBlock || down.getBlock() instanceof FenceGateBlock || down.getBlock() instanceof TrapdoorBlock) {
            return normalizeOpenableBase(world, pos.down());
        }
        return null;
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
        net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(
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
            if (world == null || bot == null || bot.isRemoved() || !world.isChunkLoaded(doorPos.getX() >> 4, doorPos.getZ() >> 4)) {
                return;
            }
            if (isDoorAutoCloseSuppressed(botUuid)) {
                scheduleDoorCloseInternal(server, botUuid, worldKey, doorPos, travelDir, attempt + 1);
                return;
            }
            BlockState state = world.getBlockState(doorPos);
            if (!isAutoCloseOpenable(state) || !state.contains(Properties.OPEN) || !Boolean.TRUE.equals(state.get(Properties.OPEN))) {
                return;
            }

            // Close once the bot has actually passed through (wolf-like behavior: open, go through, close behind).
            BlockPos botPos = bot.getBlockPos();
            // Never close while the bot is still in/too close to the doorway.
            if (botPos.equals(doorPos) || botPos.getSquaredDistance(doorPos) <= 4.0D) {
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
            // Prefer closing only after crossing the door plane.
            // IMPORTANT: don't close just because we backed away; that can create open/close oscillation.
            if (dot < 1) {
                // If we're far away (bot abandoned this doorway), close anyway after a few retries.
                // Also close if we've been stuck near the door for too long (oscillation scenario) -
                // being stuck near a door that stays open causes more problems than closing "early".
                boolean farAway = attempt >= 12 && botPos.getSquaredDistance(doorPos) >= 36.0D;
                boolean stuckNearDoor = attempt >= 8 && botPos.getSquaredDistance(doorPos) <= 16.0D; // <= 4 blocks
                if (!farAway && !stuckNearDoor) {
                    maybeLogDoor(bot, doorPos, "door-close wait: not past door yet (attempt=" + attempt + " dist=" + String.format("%.1f", Math.sqrt(botPos.getSquaredDistance(doorPos))) + ")");
                    scheduleDoorCloseInternal(server, botUuid, worldKey, doorPos, travelDir, attempt + 1);
                    return;
                }
                if (stuckNearDoor) {
                    maybeLogDoor(bot, doorPos, "door-close: stuck near door, closing anyway after " + attempt + " attempts");
                }
            }

            maybeLogDoor(bot, doorPos, "door-close attempt");
            
            // Only close the door if the bot is within survival reach distance (~4.5 blocks).
            // This prevents "remote" door closing which breaks survival gamemode mechanics.
            double distSqToDoor = bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(doorPos));
            if (distSqToDoor > 20.25D) { // 4.5 * 4.5 = 20.25
                maybeLogDoor(bot, doorPos, "door-close skip: beyond survival reach dist=" 
                        + String.format("%.2f", Math.sqrt(distSqToDoor)));
                scheduleDoorCloseInternal(server, botUuid, worldKey, doorPos, travelDir, attempt + 1);
                return;
            }
            
            Direction toward = bot.getHorizontalFacing();
            Vec3d hitVec = Vec3d.ofCenter(doorPos).add(0, 0.35, 0);
            BlockHitResult hit = new BlockHitResult(hitVec, toward, doorPos, false);
            ActionResult result = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
            if (result.isAccepted()) {
                bot.swingHand(Hand.MAIN_HAND, true);
            }

            BlockState after = world.getBlockState(doorPos);
            if (isAutoCloseOpenable(after) && after.contains(Properties.OPEN) && !Boolean.TRUE.equals(after.get(Properties.OPEN))) {
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
        if (abortRequested(player)) return false;
        Direction toward = Direction.getFacing(goal.getX() - player.getX(), 0, goal.getZ() - player.getZ());
        if (!toward.getAxis().isHorizontal()) {
            toward = player.getHorizontalFacing();
        }
        BlockPos start = player.getBlockPos();

        // When foliage is involved, a diagonal step is often enough to "go around" without breaking.
        boolean leafAhead = hasLeafInImmediatePath(world, start, toward);
        Direction left = toward.rotateYCounterclockwise();
        Direction right = toward.rotateYClockwise();

        List<BlockPos> standCandidates = new ArrayList<>();
        if (leafAhead) {
            standCandidates.add(start.offset(toward).offset(left));
            standCandidates.add(start.offset(toward).offset(right));
            standCandidates.add(start.offset(left));
            standCandidates.add(start.offset(right));
        } else {
            standCandidates.add(start.offset(right));
            standCandidates.add(start.offset(left));
        }
        // Always keep a "back out" option.
        standCandidates.add(start.offset(toward.getOpposite()));

        for (BlockPos stand : standCandidates) {
            if (abortRequested(player)) break;
            if (!isSolidStandable(world, stand.down(), stand)) {
                continue;
            }
            LOGGER.debug("walkSegment sidestep attempt stand={}", stand.toShortString());
            LookController.faceBlock(player, stand);
            if (pursuitUntilClose(player, stand, 900L, 2.25D, "sidestep")) {
                return true;
            }
            // If pursuit is disabled elsewhere, try a short manual nudge.
            Vec3d standCenter = centerOf(stand);
            long deadline = System.currentTimeMillis() + 900L;
            while (System.currentTimeMillis() < deadline && !abortRequested(player)) {
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
        // If we're stuck against leaves (common with foliage), start mining a SINGLE natural leaf as a last resort.
        // Do this asynchronously and with cooldown so we don't turn forests into clearcuts.
        for (BlockPos leaf : leafCandidates(start, toward)) {
            if (!isBreakableLeaf(world, leaf) || !isWithinReach(player, leaf)) {
                continue;
            }
            if (startLeafMining(player, leaf, "walkSegment")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLeafInImmediatePath(ServerWorld world, BlockPos start, Direction toward) {
        if (world == null || start == null || toward == null) {
            return false;
        }
        BlockPos front = start.offset(toward);
        // Only consider leaf blocks that are plausibly colliding with the bot's body/head.
        return isLeafBlock(world, front)
                || isLeafBlock(world, front.up())
                || isLeafBlock(world, front.up(2));
    }

    private static boolean isLeafBlock(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        return world.getBlockState(pos).isIn(BlockTags.LEAVES);
    }

    private static boolean tryLeafBypassInputStep(ServerPlayerEntity bot, Direction toward) {
        if (bot == null || toward == null) {
            return false;
        }
        ServerWorld world = getWorld(bot);
        if (world == null) {
            return false;
        }
        BlockPos start = bot.getBlockPos();
        Direction left = toward.rotateYCounterclockwise();
        Direction right = toward.rotateYClockwise();

        // Prefer diagonal-forward to slip around leaf hedges without breaking.
        BlockPos[] candidates = new BlockPos[] {
                start.offset(toward).offset(left),
                start.offset(toward).offset(right),
                start.offset(left),
                start.offset(right)
        };

        BlockPos best = null;
        for (BlockPos cand : candidates) {
            if (cand == null) {
                continue;
            }
            if (isSolidStandable(world, cand.down(), cand)) {
                best = cand;
                break;
            }
        }
        if (best == null) {
            return false;
        }

        final BlockPos bestPos = best.toImmutable();
        final Vec3d bestCenter = Vec3d.ofCenter(bestPos);
        return runOnServerThread(bot, () -> {
            LookController.faceBlock(bot, bestPos);
            BotActions.sprint(bot, false);
            BotActions.autoJumpIfNeeded(bot);
            BotActions.applyMovementInput(bot, bestCenter, 0.16);
        });
    }

    private static LeafMineResult startLeafMiningDetailed(ServerPlayerEntity bot, BlockPos leafPos, String label) {
        if (bot == null || leafPos == null) {
            return LeafMineResult.NONE;
        }
        UUID id = bot.getUuid();
        if (id == null) {
            return LeafMineResult.NONE;
        }

        CompletableFuture<String> inflight = LEAF_MINE_INFLIGHT.get(id);
        if (inflight != null && !inflight.isDone()) {
            BlockPos target = LEAF_MINE_TARGET.get(id);
            // If we're already mining this leaf, treat it as "handled".
            if (target != null && target.equals(leafPos)) {
                return LeafMineResult.IN_PROGRESS;
            }
            // Only mine one leaf at a time per bot.
            return LeafMineResult.NONE;
        }

        long now = System.currentTimeMillis();
        long last = LEAF_MINE_LAST_MS.getOrDefault(id, 0L);
        if (now - last < LEAF_MINE_GLOBAL_COOLDOWN_MS) {
            return LeafMineResult.NONE;
        }

        LEAF_MINE_LAST_MS.put(id, now);
        LEAF_MINE_TARGET.put(id, leafPos.toImmutable());
        selectHarmlessForLeaves(bot);

        // UX: when foliage is actually blocking us, show a short overhead line (not chat).
        CompanionOverheadDialogueService.tryShowLeafStuck(bot, label);

        LOGGER.debug("leaf-mine start [{}]: bot={} pos={}",
                label,
                bot.getName().getString(),
                leafPos.toShortString());

        CompletableFuture<String> future = MiningTool.mineBlock(bot, leafPos, true);
        LEAF_MINE_INFLIGHT.put(id, future);
        future.whenComplete((result, error) -> {
            LEAF_MINE_INFLIGHT.remove(id, future);
            LEAF_MINE_TARGET.remove(id);
        });
        return LeafMineResult.STARTED;
    }

    private static boolean startLeafMining(ServerPlayerEntity bot, BlockPos leafPos, String label) {
        LeafMineResult result = startLeafMiningDetailed(bot, leafPos, label);
        return result == LeafMineResult.STARTED || result == LeafMineResult.IN_PROGRESS;
    }

    private static boolean isBreakableLeaf(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        var state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.LEAVES)) {
            return false;
        }
        // Respect player-placed leaves: vanilla sets PERSISTENT=true when placed.
        if (state.contains(LeavesBlock.PERSISTENT) && Boolean.TRUE.equals(state.get(LeavesBlock.PERSISTENT))) {
            return false;
        }
        return true;
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
        // Prefer only the blocks that plausibly collide with the bot's body/head in the travel direction.
        // Avoid scanning high canopy blocks (which caused unnecessary leaf breaking).
        BlockPos front = start.offset(toward);
        BlockPos front2 = front.offset(toward);
        return List.of(
                front,
                front.up(),
                front.up(2),
                front2,
                front2.up(),
                front2.up(2),
                // If we're already wedged into leaves, try the body/head blocks too.
                start.up(),
                start.up(2)
        );
    }

    public static LeafClearResult clearLeafObstructionDetailed(ServerPlayerEntity player, Direction toward) {
        if (player == null || toward == null) {
            return new LeafClearResult(LeafClearAction.NO_CLEAR, false, 0, null, "invalid-input");
        }
        ServerWorld world = getWorld(player);
        if (world == null) {
            return new LeafClearResult(LeafClearAction.NO_CLEAR, false, 0, null, "no-world");
        }

        UUID id = player.getUuid();
        int bypassFailures = id != null ? LEAF_BYPASS_FAILURE_STREAK.getOrDefault(id, 0) : 0;
        boolean leafInPath = hasLeafInImmediatePath(world, player.getBlockPos(), toward);
        if (!leafInPath) {
            if (id != null) {
                LEAF_BYPASS_FAILURE_STREAK.remove(id);
                LEAF_LAST_BYPASS_ORIGIN.remove(id);
            }
            return new LeafClearResult(LeafClearAction.NO_OBSTRUCTION, false, 0, null, "no-leaf-in-path");
        }

        if (id != null) {
            BlockPos previousBypassOrigin = LEAF_LAST_BYPASS_ORIGIN.get(id);
            if (previousBypassOrigin != null && !previousBypassOrigin.equals(player.getBlockPos())) {
                LEAF_BYPASS_FAILURE_STREAK.remove(id);
                LEAF_LAST_BYPASS_ORIGIN.remove(id);
                return new LeafClearResult(LeafClearAction.BYPASS_PROGRESS, true, 0, null, "bypass-progress");
            }
            CompletableFuture<String> inflight = LEAF_MINE_INFLIGHT.get(id);
            if (inflight != null && !inflight.isDone()) {
                BlockPos target = LEAF_MINE_TARGET.get(id);
                return new LeafClearResult(LeafClearAction.MINING_IN_PROGRESS, true, bypassFailures, target, "inflight");
            }
        }

        // First try a non-destructive "route around" nudge. This is safe to run even on the server thread.
        boolean forceMineOnly = bypassFailures >= LEAF_BYPASS_FORCE_MINE_THRESHOLD;
        if (!forceMineOnly && tryLeafBypassInputStep(player, toward)) {
            int nextFailures = bypassFailures + 1;
            if (id != null) {
                LEAF_BYPASS_FAILURE_STREAK.put(id, nextFailures);
                LEAF_LAST_BYPASS_ORIGIN.put(id, player.getBlockPos().toImmutable());
            }
            return new LeafClearResult(LeafClearAction.BYPASS_STEP, true, nextFailures, null, "bypass-step");
        }

        // If bypass isn't possible, start mining a single natural leaf asynchronously (no blocking waits).
        BlockPos start = player.getBlockPos();
        for (BlockPos leaf : leafCandidates(start, toward)) {
            if (!isBreakableLeaf(world, leaf) || !isWithinReach(player, leaf)) {
                continue;
            }
            LeafMineResult mineResult = startLeafMiningDetailed(player, leaf, "clearLeafObstruction");
            if (mineResult == LeafMineResult.STARTED || mineResult == LeafMineResult.IN_PROGRESS) {
                if (id != null) {
                    LEAF_BYPASS_FAILURE_STREAK.remove(id);
                    LEAF_LAST_BYPASS_ORIGIN.remove(id);
                }
                return new LeafClearResult(
                        mineResult == LeafMineResult.STARTED ? LeafClearAction.MINING_STARTED : LeafClearAction.MINING_IN_PROGRESS,
                        true,
                        0,
                        leaf.toImmutable(),
                        mineResult == LeafMineResult.STARTED ? "mine-started" : "mine-in-progress");
            }
        }

        // Leaves are present in the immediate collision space, but we couldn't clear them (persistent/unreachable).
        // Emit an overhead hint so it doesn't feel like silent failure.
        if (id != null) {
            LEAF_BYPASS_FAILURE_STREAK.put(id, Math.min(10, bypassFailures + 1));
            LEAF_LAST_BYPASS_ORIGIN.put(id, player.getBlockPos().toImmutable());
        }
        CompanionOverheadDialogueService.tryShowLeafStuck(player, "clearLeafObstruction-noClear");
        return new LeafClearResult(LeafClearAction.NO_CLEAR, true, id != null ? LEAF_BYPASS_FAILURE_STREAK.getOrDefault(id, 0) : bypassFailures + 1, null, "no-clear");
    }

    public static boolean clearLeafObstruction(ServerPlayerEntity player, Direction toward) {
        LeafClearResult result = clearLeafObstructionDetailed(player, toward);
        if (result == null) {
            return false;
        }
        return result.countsAsCleared() || result.action() == LeafClearAction.BYPASS_STEP;
    }

    public static boolean hasLeafObstruction(ServerPlayerEntity player, Direction toward) {
        if (player == null || toward == null) {
            return false;
        }
        ServerWorld world = getWorld(player);
        if (world == null) {
            return false;
        }
        // Only treat leaves as an obstruction when they're in the immediate forward collision space.
        return hasLeafInImmediatePath(world, player.getBlockPos(), toward);
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
        if (tryImmediateLocalEscape(player, destination, "walkDirect-entry")) {
            if (player.getBlockPos().getSquaredDistance(destination) <= CLOSE_ENOUGH_DISTANCE_SQ) {
                return true;
            }
        }
        double lastDistanceSq = Double.MAX_VALUE;
        int stagnantSteps = 0;
        boolean sprint = shouldSprintForDestination(player, destination);
        BlockPos lastBlockPos = player.getBlockPos();
        int sameBlockSteps = 0;

        for (int steps = 0; System.currentTimeMillis() < deadline && steps < maxSteps; steps++) {
            if (abortRequested(player)) {
                BotActions.stop(player);
                return false;
            }
            BlockPos currentBlock = player.getBlockPos();
            if (currentBlock.equals(lastBlockPos)) {
                sameBlockSteps++;
            } else {
                lastBlockPos = currentBlock;
                sameBlockSteps = 0;
            }
            double distanceSq = player.squaredDistanceTo(target);
            if (distanceSq <= CLOSE_ENOUGH_DISTANCE_SQ) {
                BotActions.stop(player);
                return true;
            }
            if (distanceSq >= lastDistanceSq - 0.01) {
                stagnantSteps++;
                if (stagnantSteps == 3) {
                    if (tryImmediateLocalEscape(player, destination, "walkDirect-local-escape", true)) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryOpenDoorToward(player, destination)) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    // Door might already be open; commit to stepping through the doorway to avoid frame-jitter stalls.
                    if (tryTraverseDoorway(player, destination, "walkDirect")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    // If the target is around a corner (enclosure exit), raycasts may never intersect the door;
                    // treat a nearby door as a sub-goal to escape and continue.
                    if (tryDoorSubgoalToward(player, destination, "walkDirect")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    // Broader escape: allow detours that temporarily worsen distance (adjacent-wall doors, corners).
                    if (tryDoorEscapeToward(player, destination, null, "walkDirect")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryStepUpToward(player, destination, "walkDirect")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryMineObstructionToward(player, destination, "walkDirect")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryLocalUnstick(player, destination, "walkDirect")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                }
                if (stagnantSteps >= 5) {
                    if (trySidestepAround(player, destination)) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryDoorEscapeToward(player, destination, null, "walkDirect-hard")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryMineObstructionToward(player, destination, "walkDirect-hard")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    if (tryLocalUnstick(player, destination, "walkDirect-hard")) {
                        stagnantSteps = 0;
                        lastDistanceSq = Double.MAX_VALUE;
                        sameBlockSteps = 0;
                        continue;
                    }
                    break;
                }
            } else {
                stagnantSteps = 0;
                lastDistanceSq = distanceSq;
            }
            if (sameBlockSteps >= STUCK_SAME_BLOCK_STEPS_TRIGGER && stagnantSteps >= STUCK_STAGNANT_STEPS_TRIGGER) {
                BotActions.stop(player);
                if (tryStepUpToward(player, destination, "walkDirect-stuck")) {
                    stagnantSteps = 0;
                    lastDistanceSq = Double.MAX_VALUE;
                    sameBlockSteps = 0;
                    continue;
                }
                if (tryDoorEscapeToward(player, destination, null, "walkDirect-stuck")) {
                    stagnantSteps = 0;
                    lastDistanceSq = Double.MAX_VALUE;
                    sameBlockSteps = 0;
                    continue;
                }
                if (tryMineObstructionToward(player, destination, "walkDirect-stuck")) {
                    stagnantSteps = 0;
                    lastDistanceSq = Double.MAX_VALUE;
                    sameBlockSteps = 0;
                    continue;
                }
                if (tryLocalUnstick(player, destination, "walkDirect-stuck")) {
                    stagnantSteps = 0;
                    lastDistanceSq = Double.MAX_VALUE;
                    sameBlockSteps = 0;
                    continue;
                }
            }
            if (!inputStepToward(player, destination, target, sprint, false)) {
                BotActions.stop(player);
                return false;
            }
            sleep(35L);
        }
        BotActions.stop(player);
        return false;
    }

    /**
     * Small, iterative pursuit toward a static target. Stops once within reachSq.
     */
    public static boolean executeStuckRecoveryHeuristics(ServerPlayerEntity bot, BlockPos target, String label, boolean allowMineObstruction) {
        if (tryOpenDoorToward(bot, target)) return true;
        if (tryTraverseDoorway(bot, target, label)) return true;
        if (tryDoorSubgoalToward(bot, target, label)) return true;
        if (tryDoorEscapeToward(bot, target, null, label)) return true;
        if (tryStepUpToward(bot, target, label)) return true;
        if (tryLocalUnstick(bot, target, label)) return true;
        // NOTE: trySidestepAround intentionally NOT called here — it causes unbounded
        // indirect recursion (pursuitUntilClose → executeStuckRecoveryHeuristics →
        // trySidestepAround → pursuitUntilClose → ...). Callers like walkSegment and
        // walkDirect invoke trySidestepAround directly when needed.
        if (allowMineObstruction && tryMineObstructionToward(bot, target, label)) return true;
        return false;
    }

    public static boolean nudgeTowardUntilClose(ServerPlayerEntity bot,
                                                BlockPos target,
                                                double reachSq,
                                                long timeoutMs,
                                                double impulse,
                                                String label) {
        if (bot == null || target == null) {
            return false;
        }
        int depth = NUDGE_DEPTH.get();
        NUDGE_DEPTH.set(depth + 1);
        boolean allowAssists = depth == 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        final Vec3d targetCenter = Vec3d.ofCenter(target);
        double best = bot.squaredDistanceTo(targetCenter);
        double start = best;
        LOGGER.debug("nudgeToward start [{}]: from={} to={} dist={}", label, bot.getBlockPos().toShortString(), target.toShortString(), Math.sqrt(best));
        int noProgress = 0;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (abortRequested(bot)) {
                    BotActions.stop(bot);
                    return false;
                }
                if (bot.isInsideWall()) {
                    LOGGER.warn("nudgeToward detected {} inside a wall during [{}] at {}",
                            bot.getName().getString(),
                            label,
                            bot.getBlockPos().toShortString());
                    BotActions.stop(bot);
                    BotRescueService.rescueFromBurial(bot);
                    sleep(100L);
                    if (bot.isInsideWall()) {
                        return false;
                    }
                }
                double distSq = bot.squaredDistanceTo(targetCenter);
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
                BotActions.applyMovementInput(bot, targetCenter, tunedImpulse);
                sleep(90L);

                double nowDist = bot.squaredDistanceTo(targetCenter);
                if (nowDist + 0.05D >= best) {
                    noProgress++;

                    // If we're stalled, try the same kinds of deterministic assists we use for follow/pathing.
                    // This is the primary fix for "nudgeToward failed" loops near doors/corners.
                    if (allowAssists && (noProgress == 4 || noProgress == 6)) {
                        BotActions.stop(bot);

                        boolean allowMine = noProgress >= 6;
                        if (executeStuckRecoveryHeuristics(bot, target, label + "-nudge", allowMine)) {
                            best = Double.MAX_VALUE;
                            noProgress = 0;
                            continue;
                        }
                    }

                    if (noProgress >= 7) {
                        LOGGER.debug("nudgeToward stalled [{}]: bestDist={}", label, Math.sqrt(best));
                        BotActions.stop(bot);
                        break;
                    }
                } else {
                    best = nowDist;
                    noProgress = 0;
                }
            }
        } finally {
            NUDGE_DEPTH.set(depth);
        }

        // Throttle: only WARN once per 10s per label prefix; downgrade to DEBUG otherwise
        String labelPrefix = label.contains("-") ? label.substring(0, label.indexOf('-')) : label;
        long now = System.currentTimeMillis();
        Long lastWarn = NUDGE_WARN_COOLDOWN.get(labelPrefix);
        if (lastWarn == null || now - lastWarn >= NUDGE_WARN_INTERVAL_MS) {
            NUDGE_WARN_COOLDOWN.put(labelPrefix, now);
            LOGGER.warn("nudgeToward failed [{}]: finalDist={}", label, Math.sqrt(bot.squaredDistanceTo(targetCenter)));
        } else {
            LOGGER.debug("nudgeToward failed [{}]: finalDist={}", label, Math.sqrt(bot.squaredDistanceTo(targetCenter)));
        }
        BotActions.stop(bot);
        return bot.squaredDistanceTo(targetCenter) <= reachSq;
    }

    private static boolean isOnDoorFrontSide(BlockPos doorBase, Direction facing, BlockPos pos) {
        if (doorBase == null || facing == null || pos == null) {
            return true;
        }
        int dx = pos.getX() - doorBase.getX();
        int dz = pos.getZ() - doorBase.getZ();
        int dot = dx * facing.getOffsetX() + dz * facing.getOffsetZ();
        // doorBase.offset(facing) will have dot == 1.
        return dot >= 0;
    }

    private static BlockPos findStandableSameDoorSide(ServerWorld world,
                                                     BlockPos doorBase,
                                                     Direction facing,
                                                     boolean wantFrontSide,
                                                     BlockPos seed,
                                                     int radius) {
        if (world == null || doorBase == null || facing == null || seed == null) {
            return null;
        }
        int r = Math.max(0, radius);
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos cand = seed.add(dx, dy, dz);

                    int relX = cand.getX() - doorBase.getX();
                    int relZ = cand.getZ() - doorBase.getZ();
                    int dot = relX * facing.getOffsetX() + relZ * facing.getOffsetZ();
                    boolean front = dot >= 0;
                    if (front != wantFrontSide) {
                        continue;
                    }

                    if (!isSolidStandable(world, cand.down(), cand)) {
                        continue;
                    }

                    // Prefer: close to the seed and not too far from the door plane.
                    double score = cand.getSquaredDistance(seed) + 0.25D * cand.getSquaredDistance(doorBase);
                    if (score < bestScore) {
                        bestScore = score;
                        best = cand.toImmutable();
                    }
                }
            }
        }

        return best;
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
        final Vec3d targetCenter = Vec3d.ofCenter(target);
        double best = bot.squaredDistanceTo(targetCenter);
        int stagnant = 0;
        BlockPos lastBlockPos = bot.getBlockPos();
        int sameBlockSteps = 0;
        LOGGER.debug("pursuit start [{}]: from={} to={} dist={}", label, bot.getBlockPos().toShortString(), target.toShortString(), Math.sqrt(best));
        while (System.currentTimeMillis() < deadline) {
            if (abortRequested(bot)) {
                BotActions.stop(bot);
                return false;
            }
            double distSq = bot.squaredDistanceTo(targetCenter);
            if (distSq <= reachSq) {
                BotActions.stop(bot);
                LOGGER.debug("pursuit success [{}]: dist={}", label, Math.sqrt(distSq));
                return true;
            }
            BlockPos currentBlock = bot.getBlockPos();
            if (currentBlock.equals(lastBlockPos)) {
                sameBlockSteps++;
            } else {
                lastBlockPos = currentBlock;
                sameBlockSteps = 0;
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
            double nowDist = bot.squaredDistanceTo(targetCenter);
            if (nowDist + 0.02D >= best) {
                stagnant++;
                if (stagnant == 4) {
                    if (executeStuckRecoveryHeuristics(bot, target, label, true)) {
                        stagnant = 0;
                        best = Double.MAX_VALUE;
                        sameBlockSteps = 0;
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

            if (sameBlockSteps >= STUCK_SAME_BLOCK_STEPS_TRIGGER && stagnant >= STUCK_STAGNANT_STEPS_TRIGGER) {
                BotActions.stop(bot);
                if (executeStuckRecoveryHeuristics(bot, target, label + "-stuck", true)) {
                    stagnant = 0;
                    best = Double.MAX_VALUE;
                    sameBlockSteps = 0;
                    continue;
                }
            }
        }
        double finalDist = Math.sqrt(bot.squaredDistanceTo(targetCenter));
        if (shouldWarnOnPursuitFailure(label)) {
            LOGGER.warn("pursuit failed [{}]: finalDist={}", label, finalDist);
        } else {
            LOGGER.info("pursuit failed [{}]: finalDist={}", label, finalDist);
        }
        BotActions.stop(bot);
        return false;
    }

    private static boolean shouldWarnOnPursuitFailure(String label) {
        if (label == null || label.isBlank()) {
            return true;
        }
        String normalized = label.toLowerCase(Locale.ROOT);
        return !(normalized.contains("woodcut")
                || normalized.contains("drop")
                || normalized.contains("sweep")
                || normalized.contains("loot")
                || normalized.contains("craft")
                || normalized.contains("table")
                || normalized.contains("chest")
                || normalized.contains("station"));
    }

    public static boolean tryTraverseDoorway(ServerPlayerEntity bot, BlockPos destination, String label) {
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
        BlockPos doorBase = normalizeOpenableBase(world, doorHit);
        if (doorBase == null) {
            return false;
        }
        BlockState doorState = world.getBlockState(doorBase);
        if (!isOpenableBlock(doorState)) {
            return false;
        }
        // Ensure it's open (or try to open it).
        if (doorState.contains(Properties.OPEN) && !Boolean.TRUE.equals(doorState.get(Properties.OPEN))) {
            tryOpenDoorAt(bot, doorBase);
            doorState = world.getBlockState(doorBase);
        }
        if (!doorState.contains(Properties.OPEN) || !Boolean.TRUE.equals(doorState.get(Properties.OPEN))) {
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
        if (!doorEscapeEnabled()) {
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
        return nudgeTowardUntilClose(bot, plan.stepThroughPos(), 4.0D, 2400L, 0.22, label + "-door-step");
    }

    /**
     * Broader door escape helper that can pick an adjacent-wall doorway even if it initially increases goal distance.
     */
    private static boolean tryDoorEscapeToward(ServerPlayerEntity bot, BlockPos goal, BlockPos avoidDoorBase, String label) {
        if (bot == null) {
            return false;
        }
        if (!doorEscapeEnabled()) {
            return false;
        }
        BlockPos avoid = avoidDoorBase;
        for (int attempt = 0; attempt < 2; attempt++) {
            DoorSubgoalPlan plan = findDoorEscapePlan(bot, goal, avoid);
            if (plan == null) {
                return false;
            }
            avoid = plan.doorBase();

            maybeLogDoor(bot, plan.doorBase(), "door-escape: label=" + label
                    + " approach=" + plan.approachPos().toShortString()
                    + " step=" + plan.stepThroughPos().toShortString()
                    + " improve=" + String.format(Locale.ROOT, "%.2f", plan.improveSq()));

            BlockPos botPos = bot.getBlockPos();
            boolean approached = botPos.getSquaredDistance(plan.approachPos()) <= 2.25D
                    || nudgeTowardUntilClose(bot, plan.approachPos(), 2.25D, 2200L, 0.18, label + "-door-escape-approach");
            if (!approached) {
                continue;
            }
            tryOpenDoorAt(bot, plan.doorBase());
            if (nudgeTowardUntilClose(bot, plan.stepThroughPos(), 4.0D, 3200L, 0.22, label + "-door-escape-step")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Local unstick probe: pick a nearby standable tile (within 2 blocks) and move there.
     * This is a generic "turn a corner" maneuver to break collision jitter.
     */
    private static boolean tryLocalUnstick(ServerPlayerEntity bot, BlockPos goal, String label) {
        if (bot == null) {
            return false;
        }
        ServerWorld world = getWorld(bot);
        if (world == null) {
            return false;
        }
        BlockPos start = bot.getBlockPos();

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    // Diamond radius (more corner-friendly than square).
                    if (Math.abs(dx) + Math.abs(dz) > 2) {
                        continue;
                    }
                    BlockPos cand = start.add(dx, dy, dz);
                    if (!isSolidStandable(world, cand.down(), cand)) {
                        continue;
                    }
                    // Prefer staying level; stepping up/down is allowed but penalized.
                    double score = (goal != null ? cand.getSquaredDistance(goal) : cand.getSquaredDistance(start)) + (Math.abs(dy) * 4.0D);
                    if (score < bestScore) {
                        bestScore = score;
                        best = cand.toImmutable();
                    }
                }
            }
        }

        if (best == null) {
            return false;
        }
        LOGGER.debug("local-unstick [{}]: from={} to={} goal={} score={}",
                label,
                start.toShortString(),
                best.toShortString(),
                goal != null ? goal.toShortString() : "null",
                String.format(Locale.ROOT, "%.2f", bestScore));
        BotActions.stop(bot);
        return nudgeTowardUntilClose(bot, best, 2.25D, 1400L, 0.18, label + "-unstick");
    }

    /**
     * Directed 1-block step-up assist.
     *
     * <p>Why: our movement input is velocity-based and diagonal moves can leave yaw slightly off.
     * In that situation {@link BotActions#autoJumpIfNeeded(ServerPlayerEntity)} (cardinal-facing)
     * may miss a single block directly in the movement line (often a bot-placed dirt/cobble).
     * This helper uses the goal vector to identify the blocking cell and attempts to hop onto it.
     */
    private static boolean tryStepUpToward(ServerPlayerEntity bot, BlockPos goal, String label) {
        if (bot == null || goal == null) {
            return false;
        }
        ServerWorld world = getWorld(bot);
        if (world == null) {
            return false;
        }
        BlockPos start = bot.getBlockPos();

        // Build a small ordered set of candidate directions.
        int dx = goal.getX() - start.getX();
        int dz = goal.getZ() - start.getZ();

        Direction primary;
        Direction secondary;
        if (Math.abs(dx) >= Math.abs(dz)) {
            primary = dx >= 0 ? Direction.EAST : Direction.WEST;
            secondary = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        } else {
            primary = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
            secondary = dx >= 0 ? Direction.EAST : Direction.WEST;
        }

        Direction[] candidates = new Direction[] {
                primary,
                secondary,
                primary.rotateYClockwise(),
                primary.rotateYCounterclockwise(),
                primary.getOpposite()
        };

        for (Direction toward : candidates) {
            if (toward == null || !toward.getAxis().isHorizontal()) {
                continue;
            }
            BlockPos front = start.offset(toward);
            BlockState frontState = world.getBlockState(front);
            if (frontState.getBlock() instanceof DoorBlock) {
                continue;
            }
            if (frontState.getCollisionShape(world, front).isEmpty()) {
                continue;
            }
            // Don't try to climb tall collision shapes (fences/walls); treat them as true obstacles.
            double maxY = frontState.getCollisionShape(world, front).getMax(Direction.Axis.Y);
            if (maxY > 1.01D) {
                continue;
            }

            BlockPos step = front.up();
            if (!isSolidStandable(world, step.down(), step)) {
                continue;
            }
            tryTrimStepUpClearance(bot, start, step, label + "-trim");

            LOGGER.debug("step-up assist [{}]: from={} dir={} front={} step={}",
                    label,
                    start.toShortString(),
                    toward,
                    front.toShortString(),
                    step.toShortString());

            // nudgeToward will jump automatically for dy>0.6
            if (nudgeTowardUntilClose(bot, step, 2.25D, 1200L, 0.18, label + "-stepup")) {
                return true;
            }
        }

        return false;
    }

    private static boolean preclearPlannedStepUps(ServerPlayerEntity bot, List<PathFinder.PathNode> rawPath, String label) {
        if (bot == null || rawPath == null || rawPath.size() < 2) {
            return false;
        }
        BlockPos origin = bot.getBlockPos();
        boolean trimmedAny = false;
        int inspectedUpSteps = 0;

        for (int i = 1; i < rawPath.size() && inspectedUpSteps < 4; i++) {
            BlockPos from = rawPath.get(i - 1).getPos();
            BlockPos to = rawPath.get(i).getPos();
            if (to.getY() <= from.getY()) {
                continue;
            }
            if (from.getSquaredDistance(origin) > 20.25D && to.getSquaredDistance(origin) > 20.25D) {
                break;
            }
            inspectedUpSteps++;
            if (tryTrimStepUpClearance(bot, from, to, label)) {
                trimmedAny = true;
            }
        }
        return trimmedAny;
    }

    private static boolean tryTrimStepUpClearance(ServerPlayerEntity bot, BlockPos from, BlockPos step, String label) {
        if (bot == null || from == null || step == null) {
            return false;
        }
        ServerWorld world = getWorld(bot);
        if (world == null) {
            return false;
        }
        if (step.getY() - from.getY() != 1) {
            return false;
        }
        int horizontal = Math.abs(step.getX() - from.getX()) + Math.abs(step.getZ() - from.getZ());
        if (horizontal > 1) {
            return false;
        }

        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        candidates.add(from.up(2));
        candidates.add(step);
        candidates.add(step.up());
        candidates.add(step.up(2));
        if (bot.isInsideWall()) {
            candidates.add(from);
            candidates.add(from.up());
        }

        boolean trimmedAny = false;
        for (BlockPos pos : candidates) {
            if (tryMinePlannedStepTrim(bot, world, pos, label)) {
                trimmedAny = true;
            }
        }
        if (trimmedAny) {
            LOGGER.info("movement planned step trim [{}]: bot={} from={} step={}",
                    label,
                    bot.getName().getString(),
                    from.toShortString(),
                    step.toShortString());
        }
        return trimmedAny;
    }

    private static boolean tryMinePlannedStepTrim(ServerPlayerEntity bot, ServerWorld world, BlockPos pos, String label) {
        if (bot == null || world == null || pos == null) {
            return false;
        }
        if (!isWithinReach(bot, pos)) {
            return false;
        }

        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable() || state.getCollisionShape(world, pos).isEmpty()) {
            return false;
        }
        if (!plannedStepTrimAllowed(bot, world, pos, state)) {
            return false;
        }
        if (!obstructionMineAllowed(bot.getUuid(), pos)) {
            return false;
        }

        if (state.isIn(BlockTags.LEAVES)) {
            selectHarmlessForLeaves(bot);
        } else if (state.isIn(BlockTags.PICKAXE_MINEABLE)) {
            BotActions.selectHarvestToolOrHands(bot, "pickaxe");
        } else if (state.isIn(BlockTags.SHOVEL_MINEABLE)) {
            BotActions.selectHarvestToolOrHands(bot, "shovel");
        } else if (state.isIn(BlockTags.AXE_MINEABLE)) {
            BotActions.selectHarvestToolOrHands(bot, "axe");
        }

        LOGGER.info("movement planned step trim [{}]: bot={} pos={} state={}",
                label,
                bot.getName().getString(),
                pos.toShortString(),
                state.getBlock().getTranslationKey());

        try {
            boolean leafBlock = state.isIn(BlockTags.LEAVES);
            MiningTool.mineBlock(bot, pos, leafBlock).get(6, TimeUnit.SECONDS);
            sleep(120L);
            boolean mined = world.getBlockState(pos).isAir();
            if (mined && ConstructionRepairService.hasActiveSession(bot.getUuid())) {
                ConstructionRepairService.noteDamageAndAttemptImmediateRepair(
                        bot,
                        world,
                        pos,
                        "movement-planned-step-trim:" + label,
                        20.25D
                );
            }
            return mined;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean plannedStepTrimAllowed(ServerPlayerEntity bot, ServerWorld world, BlockPos pos, BlockState state) {
        if (bot == null || world == null || pos == null || state == null) {
            return false;
        }
        var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, pos);
        if (!auth.allowed()) {
            return false;
        }
        if (ProtectedZoneService.isProtected(pos, world, BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot))) {
            return false;
        }
        if (TreeDetector.isNearHumanBlocks(world, pos, 6)) {
            return false;
        }
        MiningHazardDetector.DetectionResult hazards = MiningHazardDetector.detect(bot, List.of(pos), List.of(), false);
        if (hazards.blockingHazard().isPresent()) {
            return false;
        }
        if (ConstructionProtectionService.protectionReason(bot.getUuid(), pos) != null) {
            return false;
        }
        if (world.getBlockEntity(pos) != null) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.getBlock() instanceof DoorBlock) {
            return false;
        }
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL) || state.isOf(Blocks.ENDER_CHEST)) {
            return false;
        }
        if (state.isIn(BlockTags.BEDS) || state.isIn(BlockTags.SHULKER_BOXES)) {
            return false;
        }
        if (state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.WALLS) || state.isIn(BlockTags.FENCE_GATES)) {
            return false;
        }
        if (state.isOf(Blocks.DIRT_PATH)) {
            return false;
        }
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOOL)) {
            return false;
        }
        if (ScaffoldService.isTrackedScaffold(bot.getUuid(), pos)) {
            return true;
        }
        if (state.isIn(BlockTags.LEAVES)) {
            return isBreakableLeaf(world, pos);
        }
        float hardness = state.getHardness(world, pos);
        return hardness >= 0.0F && hardness < 12.0F;
    }

    private static boolean tryMineObstructionToward(ServerPlayerEntity bot, BlockPos goal, String label) {
        if (bot == null || goal == null) {
            return false;
        }
        if (Boolean.TRUE.equals(OBSTRUCTION_MINE_DISABLED.get())) {
            return false;
        }
        // Keep this conservative: only mine during skills/build tasks, not during guard/patrol/follow.
        if (!AutoFaceEntity.isBotExecutingTask()) {
            return false;
        }
        if (ConstructionProtectionService.hasActiveProtection(bot.getUuid())) {
            LOGGER.info("movement obstruction disabled [{}]: bot={} goal={} reason=active-construction-protection",
                    label,
                    bot.getName().getString(),
                    goal.toShortString());
            return false;
        }
        ServerWorld world = getWorld(bot);
        if (world == null) {
            return false;
        }

        // If we're not grounded (often true during step-up attempts), briefly settle so mining targets are stable.
        if (!bot.isOnGround() && !bot.isClimbing() && !bot.isTouchingWater()) {
            BotActions.stop(bot);
            sleep(80L);
        }

        BlockPos start = bot.getBlockPos();

        // Candidate offsets: cardinal primary/secondary and diagonal toward goal.
        int sx = Integer.compare(goal.getX(), start.getX());
        int sz = Integer.compare(goal.getZ(), start.getZ());

        Set<BlockPos> candidates = new HashSet<>();

        // If we're already clipping, also consider clearing the bot's own head/feet spaces.
        // This prevents the "keep pushing until rescue" pattern.
        if (bot.isInsideWall()) {
            candidates.add(start);
            candidates.add(start.up());
        }
        // Diagonal first when applicable.
        if (sx != 0 || sz != 0) {
            candidates.add(start.add(sx, 0, sz));
        }

        // Also probe primary/secondary cardinal directions.
        int dx = goal.getX() - start.getX();
        int dz = goal.getZ() - start.getZ();
        Direction primary;
        Direction secondary;
        if (Math.abs(dx) >= Math.abs(dz)) {
            primary = dx >= 0 ? Direction.EAST : Direction.WEST;
            secondary = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        } else {
            primary = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
            secondary = dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        candidates.add(start.offset(primary));
        candidates.add(start.offset(secondary));

        // Expand to head/ceiling obstruction candidates.
        ArrayDeque<BlockPos> toTry = new ArrayDeque<>();
        for (BlockPos front : candidates) {
            if (front == null) {
                continue;
            }
            toTry.add(front);
            toTry.add(front.up());
            toTry.add(front.up(2));
        }

        while (!toTry.isEmpty()) {
            BlockPos pos = toTry.poll();
            if (pos == null) {
                continue;
            }
            // Keep it extremely local (prevents "mine a tunnel" behavior).
            if (start.getSquaredDistance(pos) > 3.0D) {
                continue;
            }
            if (!isWithinReach(bot, pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || state.isReplaceable()) {
                continue;
            }
            String protectionReason = ConstructionProtectionService.protectionReason(bot.getUuid(), pos);
            if (protectionReason != null) {
                LOGGER.info("movement obstruction skip [{}]: bot={} pos={} state={} protection={}",
                        label,
                        bot.getName().getString(),
                        pos.toShortString(),
                        state.getBlock().getTranslationKey(),
                        protectionReason);
                continue;
            }
            if (!obstructionMineAllowed(bot.getUuid(), pos)) {
                continue;
            }
            if (state.getBlock() instanceof DoorBlock) {
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                continue;
            }
            if (world.getBlockEntity(pos) != null) {
                continue;
            }
            // Never grief obvious player storage/beds or unstick-protected blocks.
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL) || state.isOf(Blocks.ENDER_CHEST)) {
                continue;
            }
            if (state.isIn(BlockTags.BEDS) || state.isIn(BlockTags.SHULKER_BOXES)) {
                continue;
            }
            if (state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.WALLS) || state.isIn(BlockTags.FENCE_GATES)) {
                continue;
            }
            // Avoid ripping up common build materials during generic movement.
            if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOOL)) {
                continue;
            }
            // Never tunnel through natural terrain — go around instead.
            if (state.isIn(BlockTags.DIRT) || state.isOf(Blocks.GRAVEL)
                    || state.isOf(Blocks.SAND) || state.isOf(Blocks.RED_SAND)
                    || state.isOf(Blocks.CLAY)) {
                continue;
            }
            // Protect placed furnaces (block entity check above should catch these,
            // but add explicit guard as a safety net for race conditions).
            if (state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER)) {
                continue;
            }
            // Only attempt when it actually blocks movement space (has collision).
            if (state.getCollisionShape(world, pos).isEmpty()) {
                continue;
            }

            boolean leafBlock = state.isIn(BlockTags.LEAVES);
            if (leafBlock && !isBreakableLeaf(world, pos)) {
                continue;
            }

            // Pick a reasonable tool for this block type.
            if (leafBlock) {
                selectHarmlessForLeaves(bot);
            } else if (state.isIn(BlockTags.PICKAXE_MINEABLE)) {
                BotActions.selectHarvestToolOrHands(bot, "pickaxe");
            } else if (state.isIn(BlockTags.SHOVEL_MINEABLE)) {
                BotActions.selectHarvestToolOrHands(bot, "shovel");
            } else if (state.isIn(BlockTags.AXE_MINEABLE)) {
                BotActions.selectHarvestToolOrHands(bot, "axe");
            }

            LOGGER.info("movement obstruction mine [{}]: bot={} pos={} state={}",
                    label,
                    bot.getName().getString(),
                    pos.toShortString(),
                    state.getBlock().getTranslationKey());

            try {
                MiningTool.mineBlock(bot, pos, leafBlock).get(6, TimeUnit.SECONDS);
                // Small pause to let physics/collision settle.
                sleep(120L);
                boolean mined = world.getBlockState(pos).isAir();
                if (mined && ConstructionRepairService.hasActiveSession(bot.getUuid())) {
                    ConstructionRepairService.noteDamageAndAttemptImmediateRepair(
                            bot,
                            world,
                            pos,
                            "movement-obstruction-mine:" + label,
                            20.25D
                    );
                }
                return mined;
            } catch (Exception ignored) {
                // Cooldown is already marked; treat this as a failed attempt.
                return false;
            }
        }

        return false;
    }

    private static boolean obstructionMineAllowed(UUID botUuid, BlockPos pos) {
        if (botUuid == null || pos == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Map<BlockPos, Long> perBot = OBSTRUCTION_MINE_COOLDOWN.computeIfAbsent(botUuid, __ -> new ConcurrentHashMap<>());
        BlockPos key = pos.toImmutable();
        Long last = perBot.get(key);
        if (last != null && now - last < OBSTRUCTION_MINE_COOLDOWN_MS) {
            return false;
        }
        perBot.put(key, now);
        return true;
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
     * Let vanilla handle all water physics. No artificial buoyancy, no forced
     * swim mode. Bot sinks, swims, and wades exactly like a real player.
     * Only unsneaks if the bot was sneaking without a lock (prevents drowning
     * from sneak-sinking).
     */
            private static void encourageSurfaceDrift(ServerPlayerEntity player) {
        if (player == null) return;
        // Unsneak in water to prevent sneak-sinking (unless locked)
        if (player.isTouchingWater() && player.isSneaking()
                && !SneakLockService.isLocked(player.getUuid())) {
            player.setSneaking(false);
        }
        
        // Always surface when drowning or low on air
        if (player.isTouchingWater() && player.getAir() < player.getMaxAir()) {
            player.addVelocity(0, 0.04, 0);
            player.velocityDirty = true;
            player.jump();
        }
    }
}
