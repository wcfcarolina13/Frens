package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.shasankp000.GameAI.skills.DirtNavigationPolicy;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.PlayerUtils.MiningTool;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.Entity.LookController;
import net.minecraft.registry.Registries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

        try {
            List<BlockPos> candidates = gatherCandidateDirt(player, horizontalRadius, verticalRange, excluded);
            if (candidates.isEmpty()) {
                return failure(context, "No dirt block detected within radius " + horizontalRadius + ".");
            }

            for (BlockPos detectedPos : candidates) {
                BlockPos originBeforeMove = player.getBlockPos();
                if (context.sharedState() != null) {
                    SharedStateUtils.setValue(context.sharedState(), "pendingDirtTarget.x", detectedPos.getX());
                    SharedStateUtils.setValue(context.sharedState(), "pendingDirtTarget.y", detectedPos.getY());
                    SharedStateUtils.setValue(context.sharedState(), "pendingDirtTarget.z", detectedPos.getZ());
                }

                BlockPos approachPos = computeApproachPosition(player, detectedPos);
                if (approachPos == null) {
                    LOGGER.debug("Skipping {} no approach available", detectedPos);
                    if (excluded != null) {
                        excluded.add(detectedPos);
                    }
                    DirtNavigationPolicy.record(originBeforeMove, detectedPos, false);
                    continue;
                }

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

                CompletableFuture<String> miningFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return MiningTool.mineBlock(player, detectedPos).get();
                    } catch (Exception e) {
                        LOGGER.error("Error while mining dirt block", e);
                        return "⚠️ Failed to shovel dirt: " + e.getMessage();
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
                    LOGGER.warn("Dirt block {} still present after mining", detectedPos);
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

            return failure(context, "No reachable dirt blocks found within radius " + horizontalRadius + ".");
        } catch (Exception e) {
            LOGGER.error("Dirt shovel skill failed", e);
            return failure(context, "Error while shoveling dirt: " + e.getMessage());
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

    private List<BlockPos> gatherCandidateDirt(ServerPlayerEntity player, int horizontalRadius, int verticalRange, Set<BlockPos> excluded) {
        BlockPos origin = player.getBlockPos();
        List<BlockPos> candidates = new ArrayList<>();

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
                    boolean sameColumn = dx == 0 && dz == 0;
                    if (sameColumn && dy <= 0) {
                        continue;
                    }
                    BlockState state = player.getEntityWorld().getBlockState(candidate);
                    if (!isDirtBlock(state)) {
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

    private BlockPos computeApproachPosition(ServerPlayerEntity player, BlockPos target) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos support = target.offset(direction);
            BlockPos stand = support.up();
            if (isStandable(player, support, stand)) {
                return stand;
            }
        }

        BlockPos supportAbove = target;
        BlockPos standAbove = target.up();
        if (isStandable(player, supportAbove, standAbove)) {
            return standAbove;
        }

        BlockPos supportBelow = target.down();
        BlockPos standBelow = supportBelow.up();
        if (isStandable(player, supportBelow, standBelow)) {
            return standBelow;
        }

        return null;
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

    private boolean isDirtBlock(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.isOf(Blocks.DIRT) || state.isOf(Blocks.COARSE_DIRT) || state.isOf(Blocks.ROOTED_DIRT) || state.isOf(Blocks.GRASS_BLOCK)) {
            return true;
        }
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        return id != null && id.getPath().contains("dirt");
    }

    public SkillExecutionResult perform(ServerCommandSource source, Map<String, Object> sharedState, int horizontalRadius, int verticalRange, Set<BlockPos> excluded) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("searchRadius", horizontalRadius);
        params.put("verticalRange", verticalRange);
        if (excluded != null) {
            params.put("excludedBlocks", excluded);
        }
        return execute(new SkillContext(source, sharedState, params));
    }

    private SkillExecutionResult failure(SkillContext context, String message) {
        if (context.sharedState() != null) {
            SharedStateUtils.setValue(context.sharedState(), "lastShovelStatus", "failure");
        }
        return SkillExecutionResult.failure(message);
    }
}
