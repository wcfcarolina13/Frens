package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.skills.DirtNavigationPolicy;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.PlayerUtils.MiningTool;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.Entity.LookController;
import net.minecraft.registry.Registries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Set;

public final class DirtShovelSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-dirt-shovel");
    private static final int DEFAULT_HORIZONTAL_RADIUS = 6;
    private static final int DEFAULT_VERTICAL_RANGE = 4;

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

        String label = harvestLabel != null ? harvestLabel : "target";
        try {
            List<BlockPos> candidates = gatherCandidateDirt(player, horizontalRadius, verticalRange, excluded, squareCenter, squareRadius, targetBlocks, diggingDown);
            if (candidates.isEmpty()) {
                return failure(context, "No " + label + " block detected within radius " + horizontalRadius + ".");
            }

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

                ApproachPlan approachPlan = computeApproachPlan(player, detectedPos);
                if (approachPlan == null) {
                    LOGGER.debug("Skipping {} no approach available", detectedPos);
                    if (excluded != null) {
                        excluded.add(detectedPos);
                    }
                    DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                    continue;
                }

                if (!approachPlan.blocksToClear().isEmpty()) {
                    LOGGER.info("Carving approach to {} by clearing {}", detectedPos, approachPlan.blocksToClear());
                    if (!carveApproach(player, approachPlan)) {
                        LOGGER.warn("Failed to carve approach blocks {} for {}", approachPlan.blocksToClear(), detectedPos);
                        if (excluded != null) {
                            excluded.add(detectedPos);
                        }
                        DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                        continue;
                    }
                }

                BlockPos approachPos = approachPlan.standPosition();
                LookController.faceBlock(player, detectedPos);

                BlockPos currentPos = player.getBlockPos();
                double distanceSqToApproach = currentPos.getSquaredDistance(approachPos);
                double distanceToApproach = Math.sqrt(distanceSqToApproach);
                if (distanceSqToApproach > 4.0D) {
                    LOGGER.info("Navigating to approach position {} (distance {})", approachPos, String.format("%.2f", distanceToApproach));
                    String goToResult = GoTo.goTo(source, approachPos.getX(), approachPos.getY(), approachPos.getZ(), false);
                    if (goToResult.toLowerCase().contains("failed")) {
                        LOGGER.warn("Navigation to {} failed: {}", approachPos, goToResult);
                        if (excluded != null) {
                            excluded.add(detectedPos);
                        }
                        DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                        continue;
                    }
                    currentPos = player.getBlockPos();
                } else {
                    LOGGER.info("Approach position {} already within reach (distance {}), skipping navigation", approachPos, String.format("%.2f", distanceToApproach));
                }

                if (currentPos.getSquaredDistance(detectedPos) > 9.0D) {
                    LOGGER.warn("Too far from {} after navigation (player at {})", detectedPos, currentPos);
                    if (excluded != null) {
                        excluded.add(detectedPos);
                    }
                    DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                    continue;
                }

                BotActions.selectBestTool(player, preferredTool, "sword");
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
                DirtNavigationPolicy.record(originBeforeMove, detectedPos, true);
                return SkillExecutionResult.success(result);
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
                                               boolean diggingDown) {
        BlockPos origin = player.getBlockPos();
        List<BlockPos> candidates = new ArrayList<>();

        if (diggingDown) {
            for (int dy = -1; dy >= -verticalRange; dy--) {
                BlockPos candidate = origin.add(0, dy, 0);
                if (excluded != null && excluded.contains(candidate)) {
                    continue;
                }
                BlockState state = player.getEntityWorld().getBlockState(candidate);
                if (matchesTargetBlock(state, targetBlocks)) {
                    candidates.add(candidate);
                }
            }
            if (!candidates.isEmpty()) {
                candidates.sort(Comparator.comparingDouble(pos -> origin.getSquaredDistance(pos)));
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
                    if (sameColumn && dy <= 0 && !diggingDown) { // Only skip if not explicitly digging down
                        continue;
                    }
                    BlockState state = player.getEntityWorld().getBlockState(candidate);
                    if (!matchesTargetBlock(state, targetBlocks)) {
                        continue;
                    }
                    candidates.add(candidate);
                }
            }
        }
        candidates.sort(
                Comparator.<BlockPos>comparingDouble(pos -> -DirtNavigationPolicy.score(origin, pos))
                        .thenComparingDouble(pos -> origin.getSquaredDistance(pos))
        );
        return candidates;
    }

    private ApproachPlan computeApproachPlan(ServerPlayerEntity player, BlockPos target) {
        List<ApproachPlan> fallbackPlans = new ArrayList<>();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            ApproachPlan plan = evaluateStandOption(player, target.offset(direction), target.offset(direction).up());
            if (plan == null) {
                continue;
            }
            if (plan.blocksToClear().isEmpty()) {
                return plan;
            }
            fallbackPlans.add(plan);
        }

        ApproachPlan abovePlan = evaluateStandOption(player, target, target.up());
        if (abovePlan != null) {
            if (abovePlan.blocksToClear().isEmpty()) {
                return abovePlan;
            }
            fallbackPlans.add(abovePlan);
        }

        if (fallbackPlans.isEmpty()) {
            return null;
        }
        fallbackPlans.sort(Comparator.comparingInt(plan -> plan.blocksToClear().size()));
        return fallbackPlans.get(0);
    }

    private ApproachPlan evaluateStandOption(ServerPlayerEntity player, BlockPos support, BlockPos stand) {
        BlockPos head = stand.up();
        boolean supportSolid = !isPassable(player, support);
        if (!supportSolid) {
            return null;
        }

        boolean standClear = isPassable(player, stand);
        boolean headClear = isPassable(player, head);
        if (standClear && headClear) {
            return new ApproachPlan(stand, Collections.emptyList());
        }

        List<BlockPos> toClear = new ArrayList<>();
        if (!standClear) {
            toClear.add(stand);
        }
        if (!headClear) {
            toClear.add(head);
        }
        if (toClear.isEmpty()) {
            return null;
        }
        return new ApproachPlan(stand, List.copyOf(toClear));
    }

    private boolean carveApproach(ServerPlayerEntity player, ApproachPlan plan) {
        for (BlockPos blockPos : plan.blocksToClear()) {
            if (player.getEntityWorld().getBlockState(blockPos).isAir()) {
                continue;
            }

            LookController.faceBlock(player, blockPos);
            CompletableFuture<String> future = MiningTool.mineBlock(player, blockPos);
            String result;
            try {
                result = future.get(6, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                future.cancel(true);
                LOGGER.warn("Clearing block {} timed out", blockPos);
                return false;
            } catch (Exception e) {
                LOGGER.warn("Failed to clear block {} while carving approach", blockPos, e);
                return false;
            }

            if (result == null || !result.toLowerCase().contains("complete")) {
                LOGGER.warn("Clearing block {} reported unsuccessful result: {}", blockPos, result);
                return false;
            }
            if (!player.getEntityWorld().getBlockState(blockPos).isAir()) {
                LOGGER.warn("Block {} still present after carve attempt", blockPos);
                return false;
            }
        }
        return true;
    }

    private boolean isPassable(ServerPlayerEntity player, BlockPos pos) {
        return player.getEntityWorld().getBlockState(pos).getCollisionShape(player.getEntityWorld(), pos).isEmpty();
    }

    private boolean isStandable(ServerPlayerEntity player, BlockPos support, BlockPos stand) {
        BlockPos head = stand.up();
        return !isPassable(player, support)
                && isPassable(player, stand)
                && isPassable(player, head);
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
                                        boolean diggingDown) {
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

    private record ApproachPlan(BlockPos standPosition, List<BlockPos> blocksToClear) {
    }
}
