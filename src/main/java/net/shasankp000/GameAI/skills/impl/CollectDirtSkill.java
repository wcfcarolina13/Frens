package net.shasankp000.GameAI.skills.impl;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.shasankp000.GameAI.skills.ExplorationMovePolicy;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.EntityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class CollectDirtSkill implements Skill {

    private static final int DEFAULT_COUNT = 10;
    private static final int MAX_ATTEMPTS_WITHOUT_PROGRESS = 3;
    private static final long MAX_RUNTIME_MILLIS = 60_000L;
    private static final int MAX_HORIZONTAL_RADIUS = 16;
    private static final int HORIZONTAL_RADIUS_STEP = 2;
    private static final int MAX_VERTICAL_RANGE = 8;
    private static final int VERTICAL_RANGE_STEP = 1;
    private static final int EXPLORATION_STEP_RADIUS = 6;
    private static final int EXPLORATION_STEP_VERTICAL = 2;
    private static final double DROP_SEARCH_RADIUS = 6.0;
    private static final int MAX_EXPLORATION_ATTEMPTS_PER_CYCLE = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-collect-dirt");

    @Override
    public String name() {
        return "collect_dirt";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        int targetCount = getIntParameter(context, "count", DEFAULT_COUNT);
        int horizontalRadius = getIntParameter(context, "searchRadius", 6);
        int verticalRange = getIntParameter(context, "verticalRange", 4);

        DirtShovelSkill shovelSkill = new DirtShovelSkill();
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity playerForAbortCheck = source.getPlayer();
        if (playerForAbortCheck != null && SkillManager.shouldAbortSkill(playerForAbortCheck)) {
            LOGGER.warn("collect_dirt aborted before starting due to external cancellation request.");
            return SkillExecutionResult.failure("collect_dirt paused due to nearby threat.");
        }

        List<String> optionTokens = getOptionTokens(context.parameters());
        boolean untilMode = optionTokens.contains("until") && !optionTokens.contains("exact");
        boolean squareMode = optionTokens.contains("square");

        int collected = 0;
        int failuresInRow = 0;
        int attempt = 0;
        String lastMessage = "";
        long startTime = System.currentTimeMillis();
        int radiusBoost = 0;
        int verticalBoost = 0;
        Set<BlockPos> unreachable = new HashSet<>();
        int explorationAttempts = 0;
        int baselineDirtCount = playerForAbortCheck != null ? countInventoryItems(playerForAbortCheck, Items.DIRT) : 0;
        if (untilMode && baselineDirtCount >= targetCount) {
            LOGGER.info("collect_dirt: initial inventory already meets requested amount ({} >= {}).", baselineDirtCount, targetCount);
            return SkillExecutionResult.success("Already holding at least " + targetCount + " dirt blocks.");
        }
        int inventoryCollected = 0;
        BlockPos squareCenter = null;
        if (squareMode && playerForAbortCheck != null) {
            squareCenter = playerForAbortCheck.getBlockPos();
            if (context.sharedState() != null) {
                SharedStateUtils.setValue(context.sharedState(), "collectDirt.square.centerX", squareCenter.getX());
                SharedStateUtils.setValue(context.sharedState(), "collectDirt.square.centerY", squareCenter.getY());
                SharedStateUtils.setValue(context.sharedState(), "collectDirt.square.centerZ", squareCenter.getZ());
                SharedStateUtils.setValue(context.sharedState(), "collectDirt.square.radius", horizontalRadius);
            }
        }

        boolean cleanupRequested = playerForAbortCheck != null;
        double cleanupBaseRadius = Math.max(horizontalRadius, DROP_SEARCH_RADIUS);
        SkillExecutionResult outcome = null;

        try {

        while (collected < targetCount
                && failuresInRow < MAX_ATTEMPTS_WITHOUT_PROGRESS
                && System.currentTimeMillis() - startTime < MAX_RUNTIME_MILLIS
                && !Thread.currentThread().isInterrupted()) {

            ServerPlayerEntity loopPlayer = source.getPlayer();
            if (loopPlayer != null) {
                if (SkillManager.shouldAbortSkill(loopPlayer)) {
                    LOGGER.warn("collect_dirt interrupted during iteration {} due to cancellation request.", attempt + 1);
                    outcome = SkillExecutionResult.failure("collect_dirt paused due to nearby threat.");
                    break;
                }
                List<Entity> nearbyHostiles = AutoFaceEntity.detectNearbyEntities(loopPlayer, 10.0D)
                        .stream()
                        .filter(EntityUtil::isHostile)
                        .toList();
                if (!nearbyHostiles.isEmpty()) {
                    LOGGER.warn("collect_dirt detected {} hostile(s) nearby during iteration {}.", nearbyHostiles.size(), attempt + 1);
                    boolean engaged = BotEventHandler.engageImmediateThreats(loopPlayer);
                    if (engaged) {
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                    if (SkillManager.requestSkillPause(loopPlayer, "§cPausing collect_dirt due to nearby threat.")) {
                        outcome = SkillExecutionResult.failure("collect_dirt paused due to nearby threat.");
                        break;
                    }
                }
            }
            if (loopPlayer != null) {
                int currentCount = countInventoryItems(loopPlayer, Items.DIRT);
                int effectiveCollected = untilMode ? currentCount : Math.max(0, currentCount - baselineDirtCount);
                if (effectiveCollected > inventoryCollected) {
                    inventoryCollected = effectiveCollected;
                }
            }
            collected = Math.max(collected, inventoryCollected);
            if (collected >= targetCount) {
                LOGGER.info("collect_dirt requirement already satisfied after inventory check ({} / {}).", collected, targetCount);
                outcome = SkillExecutionResult.success("Collected " + collected + " dirt blocks.");
                break;
            }

            attempt++;
            int effectiveHorizontal = Math.min(horizontalRadius + radiusBoost, MAX_HORIZONTAL_RADIUS);
            int effectiveVertical = Math.min(verticalRange + verticalBoost, MAX_VERTICAL_RANGE);

            LOGGER.info("collect_dirt iteration {} (collected {}/{}, radius={}, vertical={})",
                    attempt, collected, targetCount, effectiveHorizontal, effectiveVertical);

            if (squareMode && context.sharedState() != null) {
                SharedStateUtils.setValue(context.sharedState(), "collectDirt.square.radius", effectiveHorizontal);
            }

            BlockPos activeSquareCenter = (squareMode && squareCenter != null) ? squareCenter : null;

            SkillExecutionResult result = shovelSkill.perform(
                    source,
                    context.sharedState(),
                    effectiveHorizontal,
                    effectiveVertical,
                    unreachable,
                    activeSquareCenter,
                    activeSquareCenter != null ? effectiveHorizontal : null
            );

            lastMessage = result.message();

            if (result.success()) {
                if (loopPlayer != null) {
                    BotActions.selectBestTool(loopPlayer, "shovel", "sword");
                    int currentCount = countInventoryItems(loopPlayer, Items.DIRT);
                    int effectiveCollected = untilMode ? currentCount : Math.max(0, currentCount - baselineDirtCount);
                    if (effectiveCollected > inventoryCollected) {
                        inventoryCollected = effectiveCollected;
                    }
                }
                collected = Math.max(collected, inventoryCollected);
                failuresInRow = 0;
                radiusBoost = 0;
                verticalBoost = 0;
                explorationAttempts = 0;
                if (activeSquareCenter != null && context.sharedState() != null) {
                    SharedStateUtils.setValue(context.sharedState(), "collectDirt.square.radius", effectiveHorizontal);
                }
                moveTowardLoot(source, context.sharedState(), activeSquareCenter != null, activeSquareCenter, effectiveHorizontal);
                LOGGER.info("collect_dirt iteration {} succeeded: {}", attempt, lastMessage);
            } else {
                failuresInRow++;
                LOGGER.warn("collect_dirt iteration {} failed ({} consecutive failures): {}",
                        attempt, failuresInRow, lastMessage);
                if (isUnreachableFailure(lastMessage)) {
                    BlockPos pending = getPendingTarget(context.sharedState());
                    if (pending != null) {
                        unreachable.add(pending);
                        LOGGER.debug("Added {} to unreachable dirt targets (size={})", pending, unreachable.size());
                    }
                }
                if (shouldExpandSearch(lastMessage) && radiusBoost + horizontalRadius < MAX_HORIZONTAL_RADIUS) {
                    radiusBoost = Math.min(radiusBoost + HORIZONTAL_RADIUS_STEP, MAX_HORIZONTAL_RADIUS - horizontalRadius);
                    verticalBoost = Math.min(verticalBoost + VERTICAL_RANGE_STEP, MAX_VERTICAL_RANGE - verticalRange);
                    LOGGER.info("collect_dirt expanding search to radius={} vertical={} after message '{}'",
                            horizontalRadius + radiusBoost, verticalRange + verticalBoost, lastMessage);
                }
                if (explorationAttempts < MAX_EXPLORATION_ATTEMPTS_PER_CYCLE
                        && shouldTriggerExploration(lastMessage, failuresInRow, collected)) {
                    LOGGER.info("collect_dirt attempting exploration step after failure '{}'", lastMessage);
                    BlockPos preExplorePos = source.getPlayer() != null ? source.getPlayer().getBlockPos() : null;
                    if (attemptExplorationStep(source, context.sharedState(), activeSquareCenter != null, activeSquareCenter, effectiveHorizontal)) {
                        failuresInRow = 0;
                        radiusBoost = Math.max(0, radiusBoost - HORIZONTAL_RADIUS_STEP);
                        verticalBoost = Math.max(0, verticalBoost - VERTICAL_RANGE_STEP);
                        if (source.getPlayer() != null && preExplorePos != null) {
                            BlockPos postExplorePos = source.getPlayer().getBlockPos();
                            if (postExplorePos != null && postExplorePos.getSquaredDistance(preExplorePos) > 1) {
                                int cleared = unreachable.size();
                                unreachable.clear();
                                LOGGER.info("collect_dirt exploration cleared {} unreachable targets after relocation", cleared);
                            }
                        } else {
                            int cleared = unreachable.size();
                            unreachable.clear();
                            LOGGER.info("collect_dirt exploration cleared {} unreachable targets after relocation (player lookup unavailable)", cleared);
                        }
                        LOGGER.info("collect_dirt exploration step succeeded; retrying search.");
                        startTime = System.currentTimeMillis();
                        explorationAttempts = 0;
                        continue;
                    }
                    explorationAttempts++;
                }
            }
        }

        if (outcome == null) {
            ServerPlayerEntity finalPlayer = source.getPlayer();
            if (finalPlayer != null) {
                int finalGain = Math.max(0, countInventoryItems(finalPlayer, Items.DIRT) - baselineDirtCount);
                collected = Math.max(collected, finalGain);
            }

            if (collected < targetCount) {
                String failureMsg = lastMessage.isEmpty() ? "Unable to collect the requested amount of dirt." : lastMessage;
                failureMsg += " Collected " + collected + " out of " + targetCount + " requested.";
                LOGGER.warn("collect_dirt finished early with {} dirt gathered after {} attempts (runtime {} ms)",
                        collected, attempt, System.currentTimeMillis() - startTime);
                outcome = SkillExecutionResult.failure(failureMsg);
            } else {
                String summary = "Collected " + collected + " dirt block" + (collected == 1 ? "" : "s") + ".";
                LOGGER.info("collect_dirt completed: {} (runtime {} ms, attempts {}, failures in a row {})",
                        summary, System.currentTimeMillis() - startTime, attempt, failuresInRow);
                outcome = SkillExecutionResult.success(summary);
            }
        }

        return outcome;
        } finally {
            ServerPlayerEntity cleanupPlayer = source.getPlayer();
            if (cleanupRequested && cleanupPlayer != null && !SkillManager.shouldAbortSkill(cleanupPlayer)) {
                runDropCleanup(source, cleanupPlayer, cleanupBaseRadius, squareMode, squareCenter);
            }
        }
    }

    private boolean shouldExpandSearch(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("no dirt block detected")
                || normalized.contains("no safe approach")
                || normalized.contains("failed to reach dirt block")
                || normalized.contains("too far from dirt block");
    }

    private boolean isUnreachableFailure(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("no safe approach")
                || normalized.contains("too far from dirt block");
    }

    private boolean shouldTriggerExploration(String message, int failuresInRow, int collected) {
        if (failuresInRow <= 0) {
            return false;
        }
        if (message == null || message.isBlank()) {
            return failuresInRow >= 2;
        }
        String normalized = message.toLowerCase();
        if (normalized.contains("no dirt block detected")
                || normalized.contains("no reachable dirt")
                || normalized.contains("no safe approach")
                || normalized.contains("failed to reach dirt")
                || normalized.contains("too far from dirt block")
                || normalized.contains("unable to collect")) {
            return true;
        }
        return failuresInRow >= 2 && collected > 0;
    }

    private BlockPos getPendingTarget(Map<String, Object> sharedState) {
        if (sharedState == null) {
            return null;
        }
        Object xObj = SharedStateUtils.getValue(sharedState, "pendingDirtTarget.x");
        Object yObj = SharedStateUtils.getValue(sharedState, "pendingDirtTarget.y");
        Object zObj = SharedStateUtils.getValue(sharedState, "pendingDirtTarget.z");
        if (xObj instanceof Number x && yObj instanceof Number y && zObj instanceof Number z) {
            return new BlockPos(x.intValue(), y.intValue(), z.intValue());
        }
        return null;
    }

    private void moveTowardLoot(ServerCommandSource source,
                                Map<String, Object> sharedState,
                                boolean squareMode,
                                BlockPos squareCenter,
                                int squareRadius) {
        if (sharedState == null) {
            return;
        }
        ServerPlayerEntity player = source.getPlayer();
        if (player != null && SkillManager.shouldAbortSkill(player)) {
            LOGGER.info("collect_dirt loot pickup skipped due to cancellation request.");
            return;
        }
        Object xObj = SharedStateUtils.getValue(sharedState, "lastShoveledBlock.x");
        Object yObj = SharedStateUtils.getValue(sharedState, "lastShoveledBlock.y");
        Object zObj = SharedStateUtils.getValue(sharedState, "lastShoveledBlock.z");
        if (!(xObj instanceof Number xNum) || !(yObj instanceof Number yNum) || !(zObj instanceof Number zNum)) {
            return;
        }

        if (player == null) {
            return;
        }

        BlockPos lootPos = new BlockPos(xNum.intValue(), yNum.intValue(), zNum.intValue());
        ServerWorld world = source.getWorld();

        LinkedHashSet<BlockPos> pickupTargets = new LinkedHashSet<>();
        pickupTargets.add(lootPos);
        for (ItemEntity drop : findNearbyDrops(world, player, lootPos)) {
            BlockPos dropPos = drop.getBlockPos();
            if (squareMode && squareCenter != null && !isWithinSquare(dropPos, squareCenter, squareRadius)) {
                continue;
            }
            pickupTargets.add(dropPos);
        }

        boolean moved = false;
        MovementService.MovementOptions movementOptions = MovementService.MovementOptions.skillLoot();
        for (BlockPos target : pickupTargets) {
            if (squareMode && squareCenter != null && !isWithinSquare(target, squareCenter, squareRadius)) {
                continue;
            }
            Optional<MovementService.MovementPlan> planOpt = MovementService.planLootApproach(player, target, movementOptions);
            if (planOpt.isEmpty()) {
                LOGGER.debug("collect_dirt loot pickup: no movement plan for {}", target);
                continue;
            }

            MovementService.MovementPlan plan = planOpt.get();
            MovementService.MovementResult movement = MovementService.execute(source, player, plan);
            LOGGER.info("collect_dirt loot movement ({}) -> {}", plan.mode(), movement.detail());
            if (!movement.success()) {
                LOGGER.info("collect_dirt loot movement failed for {}; issuing targeted sweep.", target);
                double sweepRadius = Math.max(4.0D, squareRadius + 1.0D);
                try {
                    DropSweeper.sweep(
                            source.withSilent().withMaxLevel(4),
                            sweepRadius,
                            Math.max(6.0D, sweepRadius),
                            6,
                            5000L
                    );
                } catch (Exception sweepError) {
                    LOGGER.warn("Targeted loot sweep near {} failed: {}", target, sweepError.getMessage());
                }
                continue;
            }

            BlockPos recordPos = plan.finalDestination() != null ? plan.finalDestination() : target;
            if (sharedState != null && recordPos != null) {
                SharedStateUtils.setValue(sharedState, "collectDirt.lastLootDestination.x", recordPos.getX());
                SharedStateUtils.setValue(sharedState, "collectDirt.lastLootDestination.y", recordPos.getY());
                SharedStateUtils.setValue(sharedState, "collectDirt.lastLootDestination.z", recordPos.getZ());
            }

            double dropDistanceSq = player.getBlockPos().getSquaredDistance(target);
            if (dropDistanceSq > 0.25D) {
                LOGGER.info("collect_dirt loot pickup: drop at {} still {} blocks away after movement, nudging.", target, String.format("%.2f", Math.sqrt(dropDistanceSq)));
                attemptManualNudge(player, target);
            } else {
                LOGGER.info("collect_dirt loot pickup: arrived within {} blocks of {}", String.format("%.2f", Math.sqrt(dropDistanceSq)), target);
            }

            moved = true;
            break;
        }

        if (!moved) {
            LOGGER.info("collect_dirt loot pickup skipped: no navigable destination near {}", lootPos);
        }
        if (player != null && !SkillManager.shouldAbortSkill(player)) {
            double sweepRadius = Math.max(DROP_SEARCH_RADIUS, 4.0D);
            if (squareMode && squareCenter != null) {
                sweepRadius = Math.max(sweepRadius, squareRadius + 1.0D);
            }
            BotEventHandler.collectNearbyDrops(player, sweepRadius);
        }
    }

    private List<ItemEntity> findNearbyDrops(ServerWorld world, ServerPlayerEntity player, BlockPos center) {
        Box search = Box.enclosing(center, center).expand(DROP_SEARCH_RADIUS, 2.0, DROP_SEARCH_RADIUS);
        List<ItemEntity> drops = new ArrayList<>(world.getEntitiesByClass(ItemEntity.class, search, item -> !item.isRemoved()));
        drops.sort(Comparator.comparingDouble(player::squaredDistanceTo));
        return drops;
    }

    private boolean attemptExplorationStep(ServerCommandSource source,
                                           Map<String, Object> sharedState,
                                           boolean squareMode,
                                           BlockPos squareCenter,
                                           int squareRadius) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return false;
        }

        BlockPos origin = player.getBlockPos();
        List<BlockPos> candidates = gatherExplorationTargets(player, squareMode, squareCenter, squareRadius);
        if (candidates.isEmpty()) {
            LOGGER.info("collect_dirt exploration: no viable reposition targets near {}", origin);
            return false;
        }

        Direction facing = player.getHorizontalFacing();
        candidates.sort(
                Comparator.<BlockPos>comparingDouble(pos -> -ExplorationMovePolicy.score(origin, pos))
                        .thenComparingDouble(pos -> -forwardAlignment(origin, pos, facing))
                        .thenComparingDouble(origin::getSquaredDistance)
        );

        if (sharedState != null) {
            SharedStateUtils.setValue(sharedState, "exploration.origin.x", origin.getX());
            SharedStateUtils.setValue(sharedState, "exploration.origin.y", origin.getY());
            SharedStateUtils.setValue(sharedState, "exploration.origin.z", origin.getZ());
        }

        for (BlockPos destination : candidates) {
            if (sharedState != null) {
                SharedStateUtils.setValue(sharedState, "exploration.pendingTarget.x", destination.getX());
                SharedStateUtils.setValue(sharedState, "exploration.pendingTarget.y", destination.getY());
                SharedStateUtils.setValue(sharedState, "exploration.pendingTarget.z", destination.getZ());
            }

            if (squareMode && squareCenter != null && !isWithinSquare(destination, squareCenter, squareRadius)) {
                continue;
            }

            if (navigateWithPolicy(source, player, destination, "collect_dirt exploration", squareMode, squareCenter, squareRadius)) {
                if (sharedState != null) {
                    SharedStateUtils.setValue(sharedState, "exploration.lastSuccess.pos", destination);
                    SharedStateUtils.setValue(sharedState, "exploration.lastSuccess.x", destination.getX());
                    SharedStateUtils.setValue(sharedState, "exploration.lastSuccess.y", destination.getY());
                    SharedStateUtils.setValue(sharedState, "exploration.lastSuccess.z", destination.getZ());
                }
                return true;
            }
        }

        return false;
    }

    private List<BlockPos> gatherExplorationTargets(ServerPlayerEntity player,
                                                    boolean squareMode,
                                                    BlockPos squareCenter,
                                                    int squareRadius) {
        BlockPos origin = player.getBlockPos();
        List<BlockPos> targets = new ArrayList<>();

        for (int dx = -EXPLORATION_STEP_RADIUS; dx <= EXPLORATION_STEP_RADIUS; dx++) {
            for (int dz = -EXPLORATION_STEP_RADIUS; dz <= EXPLORATION_STEP_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (dx * dx + dz * dz > EXPLORATION_STEP_RADIUS * EXPLORATION_STEP_RADIUS) {
                    continue;
                }
                boolean addedForOffset = false;
                for (int dy = -1; dy <= EXPLORATION_STEP_VERTICAL; dy++) {
                    BlockPos head = origin.add(dx, dy, dz);
                    if (head.equals(origin)) {
                        continue;
                    }
                    if (squareMode && squareCenter != null && !isWithinSquare(head, squareCenter, squareRadius)) {
                        continue;
                    }
                    BlockPos foot = head.down();
                    if (!isNavigableStand(player, foot, head)) {
                        continue;
                    }
                    targets.add(head);
                    addedForOffset = true;
                    break;
                }
                if (!addedForOffset) {
                    // Fallback to level stand locations around the origin.
                    BlockPos head = origin.add(dx, 0, dz);
                    if (squareMode && squareCenter != null && !isWithinSquare(head, squareCenter, squareRadius)) {
                        continue;
                    }
                    BlockPos foot = head.down();
                    if (isNavigableStand(player, foot, head)) {
                        targets.add(head);
                    }
                }
            }
        }
        return targets;
    }

    private boolean navigateWithPolicy(ServerCommandSource source,
                                       ServerPlayerEntity player,
                                       BlockPos destination,
                                       String logContext,
                                       boolean squareMode,
                                       BlockPos squareCenter,
                                       int squareRadius) {
        BlockPos originBlock = player.getBlockPos();
        if (squareMode && squareCenter != null && !isWithinSquare(destination, squareCenter, squareRadius)) {
            LOGGER.debug("{} navigation skipped: destination {} outside square bounds.", logContext, destination);
            return false;
        }
        String result = GoTo.goTo(source, destination.getX(), destination.getY(), destination.getZ(), false);
        boolean goToReportedSuccess = isGoToSuccess(result);

        BlockPos postGoToBlock = player.getBlockPos();
        double distanceSqToDestination = postGoToBlock.getSquaredDistance(destination);
        boolean closeEnough = distanceSqToDestination <= 9.0D;
        boolean success = goToReportedSuccess && closeEnough;

        if (!success) {
            if (!goToReportedSuccess) {
                LOGGER.warn("{} GoTo result indicates failure: {}", logContext, result);
            } else {
                double distance = Math.sqrt(distanceSqToDestination);
                LOGGER.info("{} GoTo reached {} but is {} blocks from target {}; attempting manual nudge.",
                        logContext,
                        postGoToBlock,
                        String.format("%.2f", distance),
                        destination);
            }

            if (attemptManualNudge(player, destination)) {
                BlockPos postNudgeBlock = player.getBlockPos();
                double postNudgeDistanceSq = postNudgeBlock.getSquaredDistance(destination);
                success = postNudgeDistanceSq <= 9.0D;
                if (success) {
                    result = "Manual nudge succeeded";
                    double distAfterNudge = Math.sqrt(postNudgeDistanceSq);
                    LOGGER.info("{} manual nudge reached {} (dist {} blocks)", logContext, postNudgeBlock, String.format("%.2f", distAfterNudge));
                } else {
                    double distAfterNudge = Math.sqrt(postNudgeDistanceSq);
                    LOGGER.warn("{} manual nudge still {} blocks from {}", logContext, String.format("%.2f", distAfterNudge), destination);
                }
            }
        }

        ExplorationMovePolicy.record(originBlock, destination, success);
        if (success) {
            LOGGER.info("{} navigation success to {}: {}", logContext, destination, result);
        } else {
            LOGGER.warn("{} navigation failed to {}: {}", logContext, destination, result);
        }
        return success;
    }

    private boolean isGoToSuccess(String result) {
        if (result == null) {
            return false;
        }
        String lower = result.toLowerCase();
        return !(lower.contains("failed") || lower.contains("error") || lower.contains("not found"));
    }

    private boolean attemptManualNudge(ServerPlayerEntity player, BlockPos destination) {
        BlockPos originBlock = player.getBlockPos();
        LOGGER.info("Manual nudge: attempting to step from {} toward {}", originBlock, destination);

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
                        // try to step down a ledge with an extra move
                        BotActions.moveForward(player);
                    }
                }
            });

            BlockPos currentBlock = player.getBlockPos();
            double distanceSq = currentBlock.getSquaredDistance(destination);
            if (distanceSq <= 9.0D) {
                double distance = Math.sqrt(distanceSq);
                LOGGER.info("Manual nudge succeeded after {} attempts (dist {} blocks)", attempt + 1, String.format("%.2f", distance));
                return true;
            }
        }

        LOGGER.warn("Manual nudge failed to move the bot away from {}", originBlock);
        return false;
    }

    private void runOnServerThread(ServerPlayerEntity player, Runnable action) {
        if (player == null) {
            return;
        }
        ServerWorld world = (player.getEntityWorld() instanceof ServerWorld serverWorld) ? serverWorld : null;
        MinecraftServer server = world != null ? world.getServer() : null;
        if (server == null) {
            action.run();
            return;
        }
        if (server.isOnThread()) {
            action.run();
        } else {
            CompletableFuture<Void> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    action.run();
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            future.join();
        }
    }

    private double forwardAlignment(BlockPos origin, BlockPos candidate, Direction facing) {
        int dx = candidate.getX() - origin.getX();
        int dz = candidate.getZ() - origin.getZ();
        int fx = facing.getOffsetX();
        int fz = facing.getOffsetZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance == 0.0) {
            return -1.0;
        }
        return (dx * fx + dz * fz) / horizontalDistance;
    }

    private boolean isNavigableStand(ServerPlayerEntity player, BlockPos foot, BlockPos head) {
        if (!isStandable(player, foot, head)) {
            return false;
        }
        return player.getEntityWorld().getBlockState(head.up())
                .getCollisionShape(player.getEntityWorld(), head.up()).isEmpty();
    }

    private boolean isStandable(ServerPlayerEntity player, BlockPos foot, BlockPos head) {
        return !player.getEntityWorld().getBlockState(foot).getCollisionShape(player.getEntityWorld(), foot).isEmpty()
                && player.getEntityWorld().getBlockState(head).getCollisionShape(player.getEntityWorld(), head).isEmpty();
    }

    private boolean isWithinSquare(BlockPos pos, BlockPos center, int radius) {
        if (center == null) {
            return true;
        }
        return Math.abs(pos.getX() - center.getX()) <= radius
                && Math.abs(pos.getZ() - center.getZ()) <= radius;
    }

    private int getIntParameter(SkillContext context, String key, int defaultValue) {
        Map<String, Object> params = context.parameters();
        Object value = params.get(key);
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
    private List<String> getOptionTokens(Map<String, Object> parameters) {
        Object raw = parameters.get("options");
        if (raw instanceof List<?> list) {
            List<String> tokens = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof String str && !str.isBlank()) {
                    tokens.add(str.toLowerCase(Locale.ROOT));
                }
            }
            return tokens;
        }
        return List.of();
    }


    private void runDropCleanup(ServerCommandSource source,
                                ServerPlayerEntity player,
                                double baseRadius,
                                boolean squareMode,
                                BlockPos squareCenter) {
        double radius = Math.max(baseRadius, DROP_SEARCH_RADIUS * 1.5D);
        if (squareMode && squareCenter != null) {
            radius = Math.max(radius, baseRadius + 2.0D);
        }
        double vertical = Math.max(radius, 6.0D);
        for (int attempt = 0; attempt < 3; attempt++) {
            BotEventHandler.collectNearbyDrops(player, radius);
            try {
                DropSweeper.sweep(
                        source.withSilent().withMaxLevel(4),
                        radius,
                        vertical,
                        20,
                        9000L
                );
            } catch (Exception sweepError) {
                LOGGER.warn("Cleanup drop sweep attempt {} failed: {}", attempt + 1, sweepError.getMessage());
                break;
            }
            int remaining = source.getWorld().getEntitiesByClass(
                    net.minecraft.entity.ItemEntity.class,
                    player.getBoundingBox().expand(radius, vertical, radius),
                    item -> !item.isRemoved()
            ).size();
            if (remaining <= 0) {
                break;
            }
            radius += 2.0D;
            vertical += 2.0D;
        }
    }

    private int countInventoryItems(ServerPlayerEntity player, Item target) {
        if (player == null) {
            return 0;
        }
        PlayerInventory inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(target)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
