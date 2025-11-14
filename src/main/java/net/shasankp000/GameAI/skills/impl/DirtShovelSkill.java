package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.skills.DirtNavigationPolicy;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillPreferences;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.PlayerUtils.MiningTool;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.Entity.LookController;
import net.minecraft.registry.Registries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Set;

public final class DirtShovelSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-dirt-shovel");
    private static final int DEFAULT_HORIZONTAL_RADIUS = 6;
    private static final int DEFAULT_VERTICAL_RANGE = 4;
    private static final int MAX_TUNNEL_DEPTH = 4;
    private static final int MAX_STAIRS_DEPTH = 8;
    private static final int[] STAIR_VERTICAL_PREFERENCE = new int[]{0, 1, -1};
    private static final double MAX_MINING_DISTANCE_SQ = 25.0D;
    private static final long MANUAL_STEP_DELAY_MS = 120L;

    @Override
    public String name() {
        return "dirt_shovel";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity player = Objects.requireNonNull(source.getPlayer(), "player");

        int horizontalRadius = getIntParameter(context, "searchRadius", DEFAULT_HORIZONTAL_RADIUS);
        int verticalRange = getIntParameter(context, "verticalRange", DEFAULT_VERTICAL_RANGE);
        Set<BlockPos> excluded = extractExcluded(context.parameters());
        BlockPos squareCenter = extractSquareCenter(context.parameters());
        Integer squareRadius = extractSquareRadius(context.parameters());
        Set<Identifier> targetBlocks = extractTargetBlocks(context.parameters());
        String harvestLabel = extractHarvestLabel(context.parameters());
        String preferredTool = extractPreferredTool(context.parameters());
        boolean diggingDown = getBooleanParameter(context, "diggingDown", false);
        boolean allowAnyBlock = getBooleanParameter(context, "allowAnyBlock", false);
        boolean stairMode = getBooleanParameter(context, "stairsMode", false);

        String label = harvestLabel != null ? harvestLabel : "target";
        try {
            List<BlockPos> candidates = gatherCandidateDirt(player, horizontalRadius, verticalRange, excluded, squareCenter, squareRadius, targetBlocks, diggingDown, allowAnyBlock);
            if (candidates.isEmpty()) {
                return failure(context, "No " + label + " block detected within radius " + horizontalRadius + ".");
            }

            boolean stairFailure = false;
            for (BlockPos detectedPos : candidates) {
                if (SkillManager.shouldAbortSkill(player)) {
                    LOGGER.warn("dirt_shovel interrupted before targeting {} due to cancellation request.", detectedPos);
                    return failure(context, "dirt_shovel paused due to nearby threat.");
                }
                BlockPos originBeforeMove = player.getBlockPos();
                if (context.sharedState() != null) {
                    SharedStateUtils.setValue(context.sharedState(), "pendingDirtTarget.x", detectedPos.getX());
                    SharedStateUtils.setValue(context.sharedState(), "pendingDirtTarget.y", detectedPos.getY());
                    SharedStateUtils.setValue(context.sharedState(), "pendingDirtTarget.z", detectedPos.getZ());
                }

                ApproachPlan approachPlan = computeApproachPlan(player, detectedPos, stairMode);
                if (approachPlan == null) {
                    LOGGER.debug("Skipping {} no approach available", detectedPos);
                    if (!SkillPreferences.teleportDuringSkills(player) && stairMode) {
                        stairFailure = true;
                    }
                    if (excluded != null) {
                        excluded.add(detectedPos);
                    }
                    DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                    continue;
                }

                LookController.faceBlock(player, detectedPos);

                if (!prepareApproach(source, player, approachPlan)) {
                    LOGGER.warn("Failed to prepare approach toward {}", detectedPos);
                    if (excluded != null) {
                        excluded.add(detectedPos);
                    }
                    DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                    continue;
                }

                BlockPos currentPos = player.getBlockPos();
                if (currentPos.getSquaredDistance(detectedPos) > 9.0D) {
                    LOGGER.warn("Too far from {} after approach preparation (player at {})", detectedPos, currentPos);
                    if (excluded != null) {
                        excluded.add(detectedPos);
                    }
                    DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                    continue;
                }

                DirtNavigationPolicy.record(originBeforeMove, detectedPos, true);

                BotActions.selectBestTool(player, preferredTool, "sword");

                if (!isWithinMiningReach(player, detectedPos)) {
                    LOGGER.warn("Blocked from mining {} because it is outside vanilla reach.", detectedPos);
                    if (excluded != null) {
                        excluded.add(detectedPos);
                    }
                    DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                    continue;
                }
                CompletableFuture<String> miningFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return MiningTool.mineBlock(player, detectedPos).get();
                    } catch (Exception e) {
                        LOGGER.error("Error while mining harvest block", e);
                        return "⚠️ Failed to harvest block: " + e.getMessage();
                    }
                });

                String result;
                try {
                    result = miningFuture.get(10, TimeUnit.SECONDS);
                } catch (TimeoutException timeout) {
                    miningFuture.cancel(true);
                    LOGGER.warn("Mining {} timed out", detectedPos);
                    if (excluded != null) {
                        excluded.add(detectedPos);
                    }
                    DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                    continue;
                }
                if (result == null) {
                    result = "Unknown shovel result";
                }
                if (player.getEntityWorld().getBlockState(detectedPos).getBlock() != Blocks.AIR) {
                    LOGGER.warn("{} block {} still present after mining", label, detectedPos);
                    if (excluded != null) {
                        excluded.add(detectedPos);
                    }
                    DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                    continue;
                }
                if (!result.toLowerCase().contains("complete")) {
                    LOGGER.warn("Mining result for {} did not signal completion: {}", detectedPos, result);
                    if (excluded != null) {
                        excluded.add(detectedPos);
                    }
                    DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                    continue;
                }

                if (context.sharedState() != null) {
                    SharedStateUtils.setValue(context.sharedState(), "lastDetectedBlock.pos", detectedPos);
                    SharedStateUtils.setValue(context.sharedState(), "lastDetectedBlock.x", detectedPos.getX());
                    SharedStateUtils.setValue(context.sharedState(), "lastDetectedBlock.y", detectedPos.getY());
                    SharedStateUtils.setValue(context.sharedState(), "lastDetectedBlock.z", detectedPos.getZ());
                    SharedStateUtils.setValue(context.sharedState(), "lastShoveledBlock.x", detectedPos.getX());
                    SharedStateUtils.setValue(context.sharedState(), "lastShoveledBlock.y", detectedPos.getY());
                    SharedStateUtils.setValue(context.sharedState(), "lastShoveledBlock.z", detectedPos.getZ());
                    SharedStateUtils.setValue(context.sharedState(), "lastShovelStatus", "success");
                }
                return SkillExecutionResult.success(result);
            }

            if (stairFailure && !SkillPreferences.teleportDuringSkills(player)) {
                return failure(context, "No safe stair path to target block without teleport.");
            }
            return failure(context, "No reachable " + label + " blocks found within radius " + horizontalRadius + ".");
        } catch (Exception e) {
            LOGGER.error("Dirt shovel skill failed", e);
            return failure(context, "Error while shoveling " + label + ": " + e.getMessage());
        }
    }

    private int getIntParameter(SkillContext context, String key, int defaultValue) {
        Object value = context.parameters().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Set<BlockPos> extractExcluded(Map<String, Object> parameters) {
        Object value = parameters.get("excludedBlocks");
        if (value instanceof Set<?> set) {
            try {
                return (Set<BlockPos>) set;
            } catch (ClassCastException ignored) {
            }
        }
        return null;
    }

    private BlockPos extractSquareCenter(Map<String, Object> parameters) {
        Object xObj = parameters.get("squareCenterX");
        Object yObj = parameters.get("squareCenterY");
        Object zObj = parameters.get("squareCenterZ");
        if (xObj instanceof Number x && yObj instanceof Number y && zObj instanceof Number z) {
            return new BlockPos(x.intValue(), y.intValue(), z.intValue());
        }
        return null;
    }

    private Integer extractSquareRadius(Map<String, Object> parameters) {
        Object value = parameters.get("squareRadius");
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Set<Identifier> extractTargetBlocks(Map<String, Object> parameters) {
        if (parameters == null) {
            return Collections.emptySet();
        }
        Object raw = parameters.get("targetBlocks");
        if (raw instanceof Set<?> rawSet) {
            try {
                return (Set<Identifier>) rawSet;
            } catch (ClassCastException e) {
                LOGGER.warn("targetBlocks parameter contained unexpected value: {}", rawSet);
            }
        }
        return Collections.emptySet();
    }

    private String extractHarvestLabel(Map<String, Object> parameters) {
        if (parameters == null) {
            return null;
        }
        Object label = parameters.get("harvestLabel");
        return label instanceof String str ? str : null;
    }

    private String extractPreferredTool(Map<String, Object> parameters) {
        if (parameters == null) {
            return "shovel";
        }
        Object raw = parameters.get("preferredTool");
        return raw instanceof String str && !str.isBlank() ? str : "shovel";
    }

    private List<BlockPos> gatherCandidateDirt(ServerPlayerEntity player,
                                               int horizontalRadius,
                                               int verticalRange,
                                               Set<BlockPos> excluded,
                                               BlockPos squareCenter,
                                               Integer squareRadius,
                                               Set<Identifier> targetBlocks,
                                               boolean diggingDown,
                                               boolean allowAnyBlock) {
        BlockPos origin = player.getBlockPos();
        List<BlockPos> candidates = new ArrayList<>();

        if (diggingDown) {
            for (int dy = -1; dy >= -verticalRange; dy--) {
                BlockPos candidate = origin.add(0, dy, 0);
                if (excluded != null && excluded.contains(candidate)) {
                    continue;
                }
                BlockState state = player.getEntityWorld().getBlockState(candidate);
                if ((allowAnyBlock && !state.isAir()) || matchesTargetBlock(state, targetBlocks)) {
                    candidates.add(candidate);
                }
            }
            if (!candidates.isEmpty()) {
                Comparator<BlockPos> comparator = Comparator.comparingDouble(pos -> origin.getSquaredDistance(pos));
                if (allowAnyBlock) {
                    comparator = Comparator.<BlockPos>comparingInt(BlockPos::getY)
                            .thenComparing(comparator);
                }
                candidates.sort(comparator);
                return candidates;
            }
        }

        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                if (dx * dx + dz * dz > horizontalRadius * horizontalRadius) {
                    continue;
                }
                for (int dy = -verticalRange; dy <= verticalRange; dy++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (excluded != null && excluded.contains(candidate)) {
                        continue;
                    }
                    if (squareCenter != null && squareRadius != null
                            && (Math.abs(candidate.getX() - squareCenter.getX()) > squareRadius
                            || Math.abs(candidate.getZ() - squareCenter.getZ()) > squareRadius)) {
                        continue;
                    }
                    boolean sameColumn = dx == 0 && dz == 0;
                    if ((diggingDown && sameColumn) || (sameColumn && dy <= 0 && !diggingDown)) {
                        continue;
                    }
                    BlockState state = player.getEntityWorld().getBlockState(candidate);
                    if (!allowAnyBlock && !matchesTargetBlock(state, targetBlocks)) {
                        continue;
                    }
                    candidates.add(candidate);
                }
            }
        }
        Comparator<BlockPos> comparator =
                Comparator.<BlockPos>comparingDouble(pos -> -DirtNavigationPolicy.score(origin, pos))
                        .thenComparingDouble(pos -> origin.getSquaredDistance(pos));
        if (diggingDown && allowAnyBlock) {
            comparator = Comparator.<BlockPos>comparingInt(BlockPos::getY)
                    .thenComparing(comparator);
        }
        candidates.sort(comparator);
        return candidates;
    }

    private ApproachPlan computeApproachPlan(ServerPlayerEntity player, BlockPos target, boolean stairMode) {
        ApproachPlan localPlan = computeLocalPlan(player, target);
        if (localPlan == null) {
            return null;
        }
        if (SkillPreferences.teleportDuringSkills(player) || !stairMode) {
            return localPlan;
        }
        BlockPos start = player.getBlockPos();
        BlockPos entry = localPlan.entryPosition();
        int verticalDistance = Math.abs(start.getY() - entry.getY());
        if (verticalDistance <= 1) {
            return localPlan;
        }
        ApproachPlan stairPlan = includeStairPlan(player, target, localPlan);
        return stairPlan != null ? stairPlan : localPlan;
    }

    private ApproachPlan computeLocalPlan(ServerPlayerEntity player, BlockPos target) {
        List<ApproachPlan> fallbackPlans = new ArrayList<>();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            for (int offset : STAIR_VERTICAL_PREFERENCE) {
                BlockPos stand = target.offset(direction).add(0, offset, 0);
                BlockPos support = stand.down();
                ApproachPlan plan = evaluateStandOption(player, support, stand);
                if (plan == null) {
                    plan = evaluateObstructedApproach(player, target, direction, offset);
                }
                if (plan == null) {
                    continue;
                }
                if (plan.steps().isEmpty()) {
                    return plan;
                }
                fallbackPlans.add(plan);
            }
        }

        if (fallbackPlans.isEmpty()) {
            return null;
        }
        fallbackPlans.sort(Comparator.comparingInt(plan -> plan.steps().size()));
        return fallbackPlans.get(0);
    }

    private ApproachPlan evaluateStandOption(ServerPlayerEntity player, BlockPos support, BlockPos stand) {
        if (support == null || stand == null) {
            return null;
        }
        if (!isSupportSolid(player, support)) {
            return null;
        }
        BlockPos head = stand.up();
        boolean standClear = isPassable(player, stand);
        if (!standClear) {
            return null;
        }
        boolean headClear = isPassable(player, head);
        if (headClear) {
            return new ApproachPlan(stand, stand, Collections.emptyList());
        }
        ObstructionStep headClearStep = new ObstructionStep(null, head, stand);
        return new ApproachPlan(stand, stand, List.of(headClearStep));
    }

    private ApproachPlan evaluateObstructedApproach(ServerPlayerEntity player,
                                                    BlockPos target,
                                                    Direction direction,
                                                    int verticalOffset) {
        BlockPos originStand = target.offset(direction).add(0, verticalOffset, 0);
        BlockPos originSupport = originStand.down();
        if (!isSupportSolid(player, originSupport)) {
            return null;
        }

        BlockPos entryPosition = null;
        int entryDepth = -1;
        for (int depth = 0; depth <= MAX_TUNNEL_DEPTH; depth++) {
            BlockPos stand = target.offset(direction, depth + 1).add(0, verticalOffset, 0);
            BlockPos support = stand.down();
            if (!isSupportSolid(player, support)) {
                return null;
            }
            BlockPos head = stand.up();
            if (isPassable(player, stand) && isPassable(player, head)) {
                entryPosition = stand;
                entryDepth = depth;
                break;
            }
        }

        if (entryPosition == null) {
            return null;
        }

        List<ObstructionStep> steps = new ArrayList<>();
        for (int depth = entryDepth - 1; depth >= 0; depth--) {
            BlockPos stand = target.offset(direction, depth + 1).add(0, verticalOffset, 0);
            BlockPos head = stand.up();
            BlockPos blockToClear = isPassable(player, stand) ? null : stand;
            BlockPos headToClear = isPassable(player, head) ? null : head;
            steps.add(new ObstructionStep(blockToClear, headToClear, stand));
        }

        return new ApproachPlan(entryPosition, originStand, List.copyOf(steps));
    }

    private ApproachPlan includeStairPlan(ServerPlayerEntity player,
                                          BlockPos target,
                                          ApproachPlan basePlan) {
        BlockPos start = player.getBlockPos();
        BlockPos entry = basePlan.entryPosition();
        if (start.equals(entry)) {
            return basePlan;
        }
        List<BlockPos> walkwayNodes = planStairPath(player, start, entry);
        if (walkwayNodes.isEmpty() && !start.equals(entry)) {
            LOGGER.warn("Unable to build stair-step path from {} to {} for {}", start, entry, target);
            return null;
        }
        List<ObstructionStep> combined = new ArrayList<>(walkwayNodes.size() + basePlan.steps().size());
        for (BlockPos node : walkwayNodes) {
            combined.add(stepForStand(player, node));
        }
        combined.addAll(basePlan.steps());
        return new ApproachPlan(start, basePlan.standPosition(), combined);
    }

    private List<BlockPos> planStairPath(ServerPlayerEntity player,
                                         BlockPos start,
                                         BlockPos goal) {
        if (start == null || goal == null || player == null) {
            return List.of();
        }
        if (start.equals(goal)) {
            return List.of();
        }
        if (Math.abs(goal.getY() - start.getY()) > MAX_STAIRS_DEPTH) {
            return List.of();
        }
        Deque<BlockPos> frontier = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        frontier.add(start);
        parent.put(start, start);
        int explored = 0;
        int maxHorizontalRange = 6;
        while (!frontier.isEmpty() && explored < 512) {
            BlockPos current = frontier.pollFirst();
            explored++;
            if (current.equals(goal)) {
                break;
            }
            for (BlockPos neighbor : stairNeighbors(player, current)) {
                if (parent.containsKey(neighbor)) {
                    continue;
                }
                if (Math.abs(neighbor.getX() - start.getX()) > maxHorizontalRange
                        || Math.abs(neighbor.getZ() - start.getZ()) > maxHorizontalRange) {
                    continue;
                }
                if (Math.abs(neighbor.getY() - start.getY()) > MAX_STAIRS_DEPTH) {
                    continue;
                }
                parent.put(neighbor, current);
                frontier.addLast(neighbor);
                if (neighbor.equals(goal)) {
                    frontier.clear();
                    break;
                }
            }
        }
        if (!parent.containsKey(goal)) {
            return List.of();
        }
        List<BlockPos> path = new ArrayList<>();
        BlockPos cursor = goal;
        while (!cursor.equals(start)) {
            path.add(cursor);
            cursor = parent.get(cursor);
            if (cursor == null) {
                return List.of();
            }
        }
        Collections.reverse(path);
        return path;
    }

    private List<BlockPos> stairNeighbors(ServerPlayerEntity player, BlockPos current) {
        List<BlockPos> neighbors = new ArrayList<>();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos horizontal = current.offset(direction);
            if (isStandCandidate(player, horizontal)) {
                neighbors.add(horizontal);
            }
            BlockPos up = horizontal.up();
            if (isStandCandidate(player, up)) {
                neighbors.add(up);
            }
            BlockPos down = horizontal.down();
            if (isStandCandidate(player, down)) {
                neighbors.add(down);
            }
        }
        return neighbors;
    }

    private boolean isStandCandidate(ServerPlayerEntity player, BlockPos stand) {
        if (player == null || stand == null) {
            return false;
        }
        BlockPos support = stand.down();
        if (!isSupportSolid(player, support)) {
            return false;
        }
        return canClear(player, stand) && canClear(player, stand.up());
    }

    private boolean canClear(ServerPlayerEntity player, BlockPos pos) {
        if (player == null || pos == null) {
            return false;
        }
        if (player.getEntityWorld() instanceof ServerWorld world) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                return true;
            }
            if (state.isOf(Blocks.BEDROCK)) {
                return false;
            }
            return state.getHardness(world, pos) >= 0.0F;
        }
        return false;
    }

    private ObstructionStep stepForStand(ServerPlayerEntity player, BlockPos stand) {
        BlockPos block = isPassable(player, stand) ? null : stand;
        BlockPos head = isPassable(player, stand.up()) ? null : stand.up();
        return new ObstructionStep(block, head, stand);
    }

    private Direction chooseHorizontalDirection(BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        if (dx == 0 && dz == 0) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private Direction chooseSpiralDirection(BlockPos from, BlockPos target, int rotation) {
        Direction primary = chooseHorizontalDirection(from, target);
        if (primary == null) {
            primary = Direction.NORTH;
        }
        Direction[] order = new Direction[]{primary, primary.rotateYClockwise(), primary.rotateYCounterclockwise(), primary.getOpposite()};
        return order[Math.floorMod(rotation, order.length)];
    }

    private boolean prepareApproach(ServerCommandSource source,
                                    ServerPlayerEntity player,
                                    ApproachPlan plan) {
        if (plan == null || player == null) {
            return false;
        }
        if (!moveToPosition(source, player, plan.entryPosition())) {
            LOGGER.warn("Unable to move to approach entry {}", plan.entryPosition());
            return false;
        }

        for (ObstructionStep step : plan.steps()) {
            if (step.blockToClear() != null && !mineWithinReach(player, step.blockToClear())) {
                return false;
            }
            if (step.headToClear() != null && !mineWithinReach(player, step.headToClear())) {
                return false;
            }
            if (!moveToPosition(source, player, step.destinationStand())) {
                LOGGER.warn("Failed to advance to cleared tunnel position {}", step.destinationStand());
                return false;
            }
        }

        if (player.getBlockPos().getSquaredDistance(plan.standPosition()) > 4.0D) {
            if (!moveToPosition(source, player, plan.standPosition())) {
                LOGGER.warn("Failed final repositioning to {}", plan.standPosition());
                return false;
            }
        }
        return true;
    }

    private boolean moveToPosition(ServerCommandSource source,
                                   ServerPlayerEntity player,
                                   BlockPos destination) {
        if (player == null || destination == null) {
            return false;
        }
        double distanceSq = player.getBlockPos().getSquaredDistance(destination);
        if (distanceSq <= 4.0D) {
            return true;
        }
        if (SkillPreferences.teleportDuringSkills(player)) {
            String goToResult = GoTo.goTo(source, destination.getX(), destination.getY(), destination.getZ(), false);
            if (!isGoToSuccess(goToResult)) {
                LOGGER.warn("GoTo navigation to {} failed: {}", destination, goToResult);
                return false;
            }
            double postDistanceSq = player.getBlockPos().getSquaredDistance(destination);
            if (postDistanceSq > 4.0D && !attemptManualNudge(player, destination)) {
                LOGGER.warn("GoTo reached {} but remains {} blocks away", player.getBlockPos(), String.format("%.2f", Math.sqrt(postDistanceSq)));
                return false;
            }
            return true;
        }

        boolean walked = walkManually(player, destination);
        if (!walked) {
            LOGGER.warn("Manual walk to {} failed", destination);
        }
        return walked;
    }

    private boolean walkManually(ServerPlayerEntity player, BlockPos destination) {
        for (int attempt = 0; attempt < 48; attempt++) {
            BlockPos current = player.getBlockPos();
            if (current.getSquaredDistance(destination) <= 4.0D) {
                return true;
            }
            Direction stepDirection = chooseStepDirection(current, destination);
            if (stepDirection == null) {
                int verticalDelta = destination.getY() - current.getY();
                if (verticalDelta == 0) {
                    return true;
                }
                BlockPos stand = verticalDelta > 0 ? current.up() : current.down();
                if (!ensureWalkable(player, stand)) {
                    return false;
                }
                BlockPos targetStand = stand;
                runOnServerThread(player, () -> {
                    LookController.faceBlock(player, destination);
                    if (targetStand.getY() > current.getY()) {
                        BotActions.jump(player);
                    } else {
                        BotActions.moveForward(player);
                    }
                });
            } else {
                BlockPos nextStand = current.offset(stepDirection);
                if (!ensureWalkable(player, nextStand)) {
                    LOGGER.warn("Unable to clear walkway toward {} while walking manually", nextStand);
                    return false;
                }
                BlockPos stepTarget = nextStand;
                runOnServerThread(player, () -> {
                    LookController.faceBlock(player, stepTarget);
                    if (stepTarget.getY() > current.getY()) {
                        BotActions.jumpForward(player);
                    } else {
                        BotActions.moveForward(player);
                        if (stepTarget.getY() < current.getY()) {
                            BotActions.moveForward(player);
                        }
                    }
                });
            }

            try {
                Thread.sleep(MANUAL_STEP_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return player.getBlockPos().getSquaredDistance(destination) <= 4.0D;
    }

    private boolean mineWithinReach(ServerPlayerEntity player, BlockPos blockPos) {
        if (blockPos == null || player == null) {
            return true;
        }
        if (!isWithinMiningReach(player, blockPos)) {
            Vec3d target = Vec3d.ofCenter(blockPos);
            double distance = Math.sqrt(player.squaredDistanceTo(target.x, target.y, target.z));
            LOGGER.warn("Block {} is out of reach for {} (distance {}).", blockPos, player.getName().getString(),
                    String.format("%.2f", distance));
            return false;
        }
        LookController.faceBlock(player, blockPos);
        CompletableFuture<String> future = MiningTool.mineBlock(player, blockPos);
        try {
            String result = future.get(6, TimeUnit.SECONDS);
            if (result == null || !result.toLowerCase().contains("complete")) {
                LOGGER.warn("Mining {} returned unexpected result: {}", blockPos, result);
                return false;
            }
            if (!player.getEntityWorld().getBlockState(blockPos).isAir()) {
                LOGGER.warn("{} still present after mining attempt", blockPos);
                return false;
            }
            return true;
        } catch (TimeoutException timeout) {
            future.cancel(true);
            LOGGER.warn("Mining {} timed out", blockPos);
        } catch (Exception e) {
            LOGGER.warn("Mining {} failed", blockPos, e);
        }
        return false;
    }

    // latest.log (around 23:45:52) showed repeated "Drop sweep manual nudge failed" warnings when the
    // tunnel forced the bot into crawl spaces. Ensuring each column has headroom keeps the bot upright.
    private boolean ensureWalkable(ServerPlayerEntity player, BlockPos stand) {
        if (player == null || stand == null) {
            return false;
        }
        BlockPos support = stand.down();
        if (!isSupportSolid(player, support)) {
            return false;
        }
        boolean clear = true;
        if (!isPassable(player, stand)) {
            clear &= mineWithinReach(player, stand);
        }
        if (!isPassable(player, stand.up())) {
            clear &= mineWithinReach(player, stand.up());
        }
        return clear;
    }

    private Direction chooseStepDirection(BlockPos current, BlockPos destination) {
        int dx = Integer.compare(destination.getX(), current.getX());
        int dz = Integer.compare(destination.getZ(), current.getZ());
        if (dx == 0 && dz == 0) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean isWithinMiningReach(ServerPlayerEntity player, BlockPos blockPos) {
        if (player == null || blockPos == null) {
            return false;
        }
        Vec3d target = Vec3d.ofCenter(blockPos);
        return player.squaredDistanceTo(target.x, target.y, target.z) <= MAX_MINING_DISTANCE_SQ;
    }

    private boolean isPassable(ServerPlayerEntity player, BlockPos pos) {
        return player.getEntityWorld().getBlockState(pos).getCollisionShape(player.getEntityWorld(), pos).isEmpty();
    }

    private boolean isSupportSolid(ServerPlayerEntity player, BlockPos support) {
        return support != null && !isPassable(player, support);
    }

    private boolean matchesTargetBlock(BlockState state, Set<Identifier> targetBlocks) {
        if (state.isAir()) {
            return false;
        }
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        if (targetBlocks != null && !targetBlocks.isEmpty()) {
            return id != null && targetBlocks.contains(id);
        }
        return isDefaultDirtBlock(state, id);
    }

    private boolean isDefaultDirtBlock(BlockState state, Identifier id) {
        if (state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.ROOTED_DIRT)
                || state.isOf(Blocks.GRASS_BLOCK)) {
            return true;
        }
        return id != null && id.getPath().contains("dirt");
    }

    public SkillExecutionResult perform(ServerCommandSource source,
                                        Map<String, Object> sharedState,
                                        int horizontalRadius,
                                        int verticalRange,
                                        Set<BlockPos> excluded,
                                        BlockPos squareCenter,
                                        Integer squareRadius,
                                        Set<Identifier> targetBlocks,
                                        String harvestLabel,
                                        String preferredTool,
                                        boolean diggingDown,
                                        boolean allowAnyBlock,
                                        boolean stairMode) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("searchRadius", horizontalRadius);
        params.put("verticalRange", verticalRange);
        if (excluded != null) {
            params.put("excludedBlocks", excluded);
        }
        if (squareCenter != null && squareRadius != null) {
            params.put("squareCenterX", squareCenter.getX());
            params.put("squareCenterY", squareCenter.getY());
            params.put("squareCenterZ", squareCenter.getZ());
            params.put("squareRadius", squareRadius);
        }
        if (targetBlocks != null && !targetBlocks.isEmpty()) {
            params.put("targetBlocks", targetBlocks);
        }
        if (harvestLabel != null && !harvestLabel.isBlank()) {
            params.put("harvestLabel", harvestLabel);
        }
        if (preferredTool != null && !preferredTool.isBlank()) {
            params.put("preferredTool", preferredTool);
        }
        params.put("diggingDown", diggingDown);
        if (allowAnyBlock) {
            params.put("allowAnyBlock", true);
        }
        if (stairMode) {
            params.put("stairsMode", true);
        }
        return execute(new SkillContext(source, sharedState, params));
    }

    private SkillExecutionResult failure(SkillContext context, String message) {
        if (context.sharedState() != null) {
            SharedStateUtils.setValue(context.sharedState(), "lastShovelStatus", "failure");
        }
        return SkillExecutionResult.failure(message);
    }

    private boolean getBooleanParameter(SkillContext context, String key, boolean defaultValue) {
        Map<String, Object> params = context.parameters();
        Object value = params.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return defaultValue;
    }

    private boolean attemptManualNudge(ServerPlayerEntity player, BlockPos destination) {
        if (player == null || destination == null) {
            return false;
        }
        BlockPos originBlock = player.getBlockPos();
        for (int attempt = 0; attempt < 3; attempt++) {
            runOnServerThread(player, () -> {
                LookController.faceBlock(player, destination);
                BlockPos currentBlock = player.getBlockPos();
                int dy = destination.getY() - currentBlock.getY();
                if (dy > 0) {
                    BotActions.jumpForward(player);
                } else {
                    BotActions.moveForward(player);
                    if (dy < 0) {
                        BotActions.moveForward(player);
                    }
                }
            });

            double distanceSq = player.getBlockPos().getSquaredDistance(destination);
            if (distanceSq <= 4.0D) {
                return true;
            }
        }
        LOGGER.warn("Manual nudge failed to move the bot away from {}", originBlock);
        return false;
    }

    private void runOnServerThread(ServerPlayerEntity player, Runnable action) {
        if (player == null || action == null) {
            return;
        }
        ServerWorld world = player.getEntityWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
        MinecraftServer server = world != null ? world.getServer() : null;
        if (server == null) {
            action.run();
            return;
        }
        if (server.isOnThread()) {
            action.run();
        } else {
            server.execute(action);
        }
    }

    private boolean isGoToSuccess(String result) {
        if (result == null) {
            return false;
        }
        String lower = result.toLowerCase(Locale.ROOT);
        return !(lower.contains("failed") || lower.contains("error") || lower.contains("not found"));
    }

    private record ApproachPlan(BlockPos entryPosition, BlockPos standPosition, List<ObstructionStep> steps) {
    }

    private record ObstructionStep(BlockPos blockToClear, BlockPos headToClear, BlockPos destinationStand) {
    }
}
