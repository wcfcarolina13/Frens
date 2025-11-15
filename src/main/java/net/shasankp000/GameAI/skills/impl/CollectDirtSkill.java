package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.Registries;
import net.shasankp000.GameAI.skills.ExplorationMovePolicy;
import net.shasankp000.GameAI.skills.BlockDropRegistry;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillPreferences;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.services.SkillResumeService;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.EntityUtil;
import net.shasankp000.ChatUtils.ChatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class CollectDirtSkill implements Skill {

    private static final int DEFAULT_COUNT = 10;
    private static final int MAX_ATTEMPTS_WITHOUT_PROGRESS = 3;
    private static final long MAX_RUNTIME_MILLIS = 60_000L;
    private static final int MAX_HORIZONTAL_RADIUS = 12;
    private static final int HORIZONTAL_RADIUS_STEP = 2;
    private static final int MAX_VERTICAL_RANGE = 8;
    private static final int VERTICAL_RANGE_STEP = 1;
    private static final int EXPLORATION_STEP_RADIUS = 6;
    private static final int EXPLORATION_STEP_VERTICAL = 2;
    private static final double DROP_SEARCH_RADIUS = 6.0;
    private static final int MAX_EXPLORATION_ATTEMPTS_PER_CYCLE = 3;
    private static final int MAX_STALLED_FAILURES = 5;
    private static final List<Item> LAVA_SAFETY_BLOCKS = List.of(
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.STONE,
            Items.DEEPSLATE,
            Items.DEEPSLATE_BRICKS
    );
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-collect-dirt");

    private final String skillName;
    private final String harvestLabel;
    private final Set<Item> trackedItems;
    private final Set<Identifier> targetBlockIds;
    private final String preferredTool;
    private final int maxFails;

    public CollectDirtSkill() {
        this(
                "collect_dirt",
                "dirt",
                itemSet(
                        Items.DIRT,
                        Items.COARSE_DIRT,
                        Items.ROOTED_DIRT,
                        Items.GRASS_BLOCK,
                        Items.GRAVEL,
                        Items.SAND,
                        Items.RED_SAND,
                        Items.MUD
                ),
                blockIds(
                        Blocks.DIRT,
                        Blocks.COARSE_DIRT,
                        Blocks.GRASS_BLOCK,
                        Blocks.ROOTED_DIRT,
                        Blocks.PODZOL,
                        Blocks.MUD,
                        Blocks.MYCELIUM,
                        Blocks.GRAVEL,
                        Blocks.SAND,
                        Blocks.RED_SAND
                ),
                "shovel",
                0 // Default maxFails
        );
    }

    protected CollectDirtSkill(String skillName,
                               String harvestLabel,
                               Set<Item> trackedItems,
                               Set<Identifier> targetBlockIds,
                               String preferredTool,
                               int maxFails) {
        this.skillName = skillName;
        this.harvestLabel = harvestLabel;
        this.trackedItems = trackedItems;
        this.targetBlockIds = targetBlockIds;
        this.preferredTool = preferredTool;
        this.maxFails = maxFails;
    }

    protected static Set<Item> itemSet(Item... items) {
        LinkedHashSet<Item> values = new LinkedHashSet<>();
        for (Item item : items) {
            values.add(item);
        }
        return Set.copyOf(values);
    }

    protected static Set<Identifier> blockIds(Block... blocks) {
        LinkedHashSet<Identifier> ids = new LinkedHashSet<>();
        for (Block block : blocks) {
            ids.add(Registries.BLOCK.getId(block));
        }
        return Set.copyOf(ids);
    }

    @Override
    public String name() {
        return skillName;
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
            LOGGER.warn("{} aborted before starting due to external cancellation request.", skillName);
            return SkillExecutionResult.failure(skillName + " paused due to nearby threat.");
        }

        Set<Item> effectiveTrackedItems = resolveTrackedItems();

        List<String> optionTokens = getOptionTokens(context.parameters());
        Integer targetDepthY = getOptionalIntParameter(context, "targetDepthY");
        boolean depthMode = targetDepthY != null;
        boolean stairMode = depthMode && getBooleanParameter(context, "stairsMode", false);
        if (depthMode) {
            targetCount = Integer.MAX_VALUE;
        }
        boolean untilMode = optionTokens.contains("until") && !optionTokens.contains("exact");
        boolean spiralMode = optionTokens.contains("spiral") || getBooleanParameter(context, "spiralMode", false);
        boolean squareMode = optionTokens.contains("square") || spiralMode;

        int collected = 0;
        int failuresInRow = 0;
        int attempt = 0;
        String lastMessage = "";
        long startTime = System.currentTimeMillis();
        int radiusBoost = 0;
        int verticalBoost = 0;
        Set<BlockPos> unreachable = new HashSet<>();
        int explorationAttempts = 0;
        int baselineHarvestCount = playerForAbortCheck != null ? countInventoryItems(playerForAbortCheck, effectiveTrackedItems) : 0;
        if (untilMode && baselineHarvestCount >= targetCount) {
            LOGGER.info("{}: initial inventory already meets requested amount ({} >= {}).", skillName, baselineHarvestCount, targetCount);
            return SkillExecutionResult.success("Already holding at least " + targetCount + " " + harvestLabel + " blocks.");
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

        if (depthMode && playerForAbortCheck != null && playerForAbortCheck.getBlockY() <= targetDepthY) {
            return SkillExecutionResult.success("Reached target depth " + targetDepthY + ".");
        }
        if (playerForAbortCheck != null) {
            if (handleInventoryFull(playerForAbortCheck, source)) {
                return SkillExecutionResult.failure("Mining paused: inventory full.");
            }
            if (handleWaterHazard(playerForAbortCheck, source)) {
                return SkillExecutionResult.failure("Mining paused: water flooded the dig site.");
            }
            if (handleLavaHazard(playerForAbortCheck, source)) {
                return SkillExecutionResult.failure("Mining paused: lava detected.");
            }
        }

        boolean cleanupRequested = playerForAbortCheck != null;
        double cleanupBaseRadius = Math.max(horizontalRadius, DROP_SEARCH_RADIUS);
        SkillExecutionResult outcome = null;

        try {

        int currentMaxFails = this.maxFails > 0 ? this.maxFails : MAX_ATTEMPTS_WITHOUT_PROGRESS;

        while (collected < targetCount
                && failuresInRow < currentMaxFails
                && System.currentTimeMillis() - startTime < MAX_RUNTIME_MILLIS
                && !Thread.currentThread().isInterrupted()) {

            ServerPlayerEntity loopPlayer = source.getPlayer();
            if (loopPlayer != null) {
                if (depthMode && loopPlayer.getBlockY() <= targetDepthY) {
                    outcome = SkillExecutionResult.success("Reached target depth " + targetDepthY + ".");
                    break;
                }
                if (handleInventoryFull(loopPlayer, source)) {
                    outcome = SkillExecutionResult.failure("Mining paused: inventory full.");
                    break;
                }
                if (handleWaterHazard(loopPlayer, source)) {
                    outcome = SkillExecutionResult.failure("Mining paused: water flooded the dig site.");
                    break;
                }
                if (handleLavaHazard(loopPlayer, source)) {
                    outcome = SkillExecutionResult.failure("Mining paused: lava detected.");
                    break;
                }
                if (SkillManager.shouldAbortSkill(loopPlayer)) {
                    LOGGER.warn("{} interrupted during iteration {} due to cancellation request.", skillName, attempt + 1);
                    outcome = SkillExecutionResult.failure(skillName + " paused due to nearby threat.");
                    break;
                }
                List<Entity> nearbyHostiles = AutoFaceEntity.detectNearbyEntities(loopPlayer, 10.0D)
                        .stream()
                        .filter(EntityUtil::isHostile)
                        .toList();
                if (!nearbyHostiles.isEmpty()) {
                    LOGGER.warn("{} detected {} hostile(s) nearby during iteration {}.", skillName, nearbyHostiles.size(), attempt + 1);
                    boolean engaged = BotEventHandler.engageImmediateThreats(loopPlayer);
                    if (engaged) {
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                    if (SkillManager.requestSkillPause(loopPlayer, "§cPausing " + skillName + " due to nearby threat.")) {
                        outcome = SkillExecutionResult.failure(skillName + " paused due to nearby threat.");
                        break;
                    }
                }
            }
            if (loopPlayer != null) {
                int currentCount = countInventoryItems(loopPlayer, effectiveTrackedItems);
                int effectiveCollected = untilMode ? currentCount : Math.max(0, currentCount - baselineHarvestCount);
                if (effectiveCollected > inventoryCollected) {
                    inventoryCollected = effectiveCollected;
                }
            }
            collected = Math.max(collected, inventoryCollected);
            if (collected >= targetCount) {
                LOGGER.info("{} requirement already satisfied after inventory check ({} / {}).", skillName, collected, targetCount);
                outcome = SkillExecutionResult.success("Collected " + collected + " " + harvestLabel + " blocks.");
                break;
            }

            attempt++;
            int effectiveHorizontal = Math.min(horizontalRadius + radiusBoost, MAX_HORIZONTAL_RADIUS);
            int effectiveVertical = Math.min(verticalRange + verticalBoost, MAX_VERTICAL_RANGE);

            LOGGER.info("{} iteration {} (collected {}/{}, radius={}, vertical={})",
                    skillName, attempt, collected, targetCount, effectiveHorizontal, effectiveVertical);

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
                    activeSquareCenter != null ? effectiveHorizontal : null,
                    targetBlockIds,
                    harvestLabel,
                    preferredTool,
                    depthMode,
                    depthMode,
                    stairMode
            );

            lastMessage = result.message();

            if (result.success()) {
                if (loopPlayer != null) {
                    BotActions.selectBestTool(loopPlayer, preferredTool, "sword");
                    int currentCount = countInventoryItems(loopPlayer, effectiveTrackedItems);
                    int effectiveCollected = untilMode ? currentCount : Math.max(0, currentCount - baselineHarvestCount);
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
                LOGGER.info("{} iteration {} succeeded: {}", skillName, attempt, lastMessage);
            } else {
                if (result.message() != null && result.message().startsWith("Hazard:")) {
                    return result;
                }
                failuresInRow++;
                LOGGER.warn("{} iteration {} failed ({} consecutive failures): {}",
                        skillName, attempt, failuresInRow, lastMessage);
                if (isUnreachableFailure(lastMessage)) {
                    BlockPos pending = getPendingTarget(context.sharedState());
                    if (pending != null) {
                        unreachable.add(pending);
                        LOGGER.debug("Added {} to unreachable {} targets (size={})", pending, harvestLabel, unreachable.size());
                    }
                }
                if (shouldExpandSearch(lastMessage) && radiusBoost + horizontalRadius < MAX_HORIZONTAL_RADIUS) {
                    radiusBoost = Math.min(radiusBoost + HORIZONTAL_RADIUS_STEP, MAX_HORIZONTAL_RADIUS - horizontalRadius);
                    verticalBoost = Math.min(verticalBoost + VERTICAL_RANGE_STEP, MAX_VERTICAL_RANGE - verticalRange);
                    LOGGER.info("{} expanding search to radius={} vertical={} after message '{}'",
                            skillName, horizontalRadius + radiusBoost, verticalRange + verticalBoost, lastMessage);
                }
                if (explorationAttempts < MAX_EXPLORATION_ATTEMPTS_PER_CYCLE
                        && shouldTriggerExploration(lastMessage, failuresInRow, collected)) {
                    LOGGER.info("{} attempting exploration step after failure '{}'", skillName, lastMessage);
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
                                LOGGER.info("{} exploration cleared {} unreachable targets after relocation", skillName, cleared);
                            }
                        } else {
                            int cleared = unreachable.size();
                            unreachable.clear();
                            LOGGER.info("{} exploration cleared {} unreachable targets after relocation (player lookup unavailable)", skillName, cleared);
                        }
                        LOGGER.info("{} exploration step succeeded; retrying search.", skillName);
                        startTime = System.currentTimeMillis();
                        explorationAttempts = 0;
                        continue;
                    }
                    explorationAttempts++;
                }

                boolean hitSearchCeiling = horizontalRadius + radiusBoost >= MAX_HORIZONTAL_RADIUS
                        && verticalRange + verticalBoost >= MAX_VERTICAL_RANGE;
                if (hitSearchCeiling && failuresInRow >= currentMaxFails) {
                    LOGGER.warn("{} giving up after {} stalled attempts at max search range.", skillName, failuresInRow);
                    outcome = SkillExecutionResult.failure("No reachable " + harvestLabel + " within safe range. Move closer and retry.");
                    break;
                }
            }
        }

        if (outcome == null) {
            ServerPlayerEntity finalPlayer = source.getPlayer();
            if (finalPlayer != null) {
                int finalGain = Math.max(0, countInventoryItems(finalPlayer, effectiveTrackedItems) - baselineHarvestCount);
                collected = Math.max(collected, finalGain);
            }

            if (collected < targetCount) {
                String failureMsg = lastMessage.isEmpty()
                        ? "Unable to collect the requested amount of " + harvestLabel + "."
                        : lastMessage;
                failureMsg += " Collected " + collected + " out of " + targetCount + " requested.";
                LOGGER.warn("{} finished early with {} {} gathered after {} attempts (runtime {} ms)",
                        skillName, collected, harvestLabel, attempt, System.currentTimeMillis() - startTime);
                outcome = SkillExecutionResult.failure(failureMsg);
            } else {
                String summary = "Collected " + collected + " " + harvestLabel + " block" + (collected == 1 ? "" : "s") + ".";
                LOGGER.info("{} completed: {} (runtime {} ms, attempts {}, failures in a row {})",
                        skillName, summary, System.currentTimeMillis() - startTime, attempt, failuresInRow);
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
        String normalized = message.toLowerCase(Locale.ROOT);
        return mentionsHarvestIssue(normalized, "no target block detected")
                || mentionsHarvestIssue(normalized, "no safe approach")
                || mentionsHarvestIssue(normalized, "failed to reach target block")
                || mentionsHarvestIssue(normalized, "too far from target block")
                || mentionsHarvestIssue(normalized, "safe stair path");
    }

    private boolean isUnreachableFailure(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return mentionsHarvestIssue(normalized, "no safe approach")
                || mentionsHarvestIssue(normalized, "too far from target block")
                || mentionsHarvestIssue(normalized, "safe stair path");
    }

    private boolean shouldTriggerExploration(String message, int failuresInRow, int collected) {
        if (failuresInRow <= 0) {
            return false;
        }
        if (message == null || message.isBlank()) {
            return failuresInRow >= 2;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (mentionsHarvestIssue(normalized, "no target block detected")
                || mentionsHarvestIssue(normalized, "no reachable target")
                || mentionsHarvestIssue(normalized, "no safe approach")
                || mentionsHarvestIssue(normalized, "failed to reach target")
                || mentionsHarvestIssue(normalized, "too far from target block")
                || normalized.contains("unable to collect")) {
            return true;
        }
        return failuresInRow >= 2 && collected > 0;
    }

    private boolean mentionsHarvestIssue(String normalized, String phrase) {
        if (normalized.contains(phrase)) {
            return true;
        }
        if (normalized.contains("dirt") && phrase.contains("target")) {
            String dirtVariant = phrase.replace("target", "dirt");
            if (normalized.contains(dirtVariant)) {
                return true;
            }
        }
        if (harvestLabel != null && !harvestLabel.isBlank() && phrase.contains("target")) {
            String labelVariant = phrase.replace("target", harvestLabel.toLowerCase(Locale.ROOT));
            if (normalized.contains(labelVariant)) {
                return true;
            }
        }
        return false;
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
            LOGGER.info("{} loot pickup skipped due to cancellation request.", skillName);
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
                LOGGER.debug("{} loot pickup: no movement plan for {}", skillName, target);
                continue;
            }

            MovementService.MovementPlan plan = planOpt.get();
            MovementService.MovementResult movement = MovementService.execute(source, player, plan);
            LOGGER.info("{} loot movement ({}) -> {}", skillName, plan.mode(), movement.detail());
            if (!movement.success()) {
                LOGGER.info("{} loot movement failed for {}; issuing targeted sweep.", skillName, target);
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
                LOGGER.info("{} loot pickup: drop at {} still {} blocks away after movement, nudging.", skillName, target, String.format("%.2f", Math.sqrt(dropDistanceSq)));
                attemptManualNudge(player, target);
            } else {
                LOGGER.info("{} loot pickup: arrived within {} blocks of {}", skillName, String.format("%.2f", Math.sqrt(dropDistanceSq)), target);
            }

            moved = true;
            break;
        }

        if (!moved) {
            LOGGER.info("{} loot pickup skipped: no navigable destination near {}", skillName, lootPos);
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
            LOGGER.info("{} exploration: no viable reposition targets near {}", skillName, origin);
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

            if (navigateWithPolicy(source, player, destination, skillName + " exploration", squareMode, squareCenter, squareRadius)) {
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

    private Integer getOptionalIntParameter(SkillContext context, String key) {
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
        return null;
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
        try {
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
        } finally {
            BotEventHandler.setExternalOverrideActive(false);
        }
    }

    private boolean isTouchingWater(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos feet = player.getBlockPos();
        return world.getFluidState(feet).isIn(net.minecraft.registry.tag.FluidTags.WATER)
                || world.getFluidState(feet.up()).isIn(net.minecraft.registry.tag.FluidTags.WATER);
    }

    private boolean isInventoryFull(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        if (inventory.getEmptySlot() != -1) {
            return false;
        }
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                return false;
            }
            if (stack.getCount() < stack.getMaxCount()) {
                return false;
            }
        }
        return true;
    }

    private boolean handleWaterHazard(ServerPlayerEntity player, ServerCommandSource source) {
        if (!isTouchingWater(player)) {
            return false;
        }
        SkillResumeService.flagManualResume(player);
        String alias = player.getName().getString();
        ChatUtils.sendChatMessages(source.withSilent().withMaxLevel(4),
                "Water flooded the dig site — pausing. Use /bot resume " + alias + " when it's clear.");
        BotActions.moveBackward(player);
        if (player.isOnGround()) {
            BotActions.jump(player);
        }
        BotActions.stop(player);
        return true;
    }

    private boolean handleLavaHazard(ServerPlayerEntity player, ServerCommandSource source) {
        LavaThreat threat = detectLavaThreat(player);
        if (threat == null) {
            return false;
        }
        SkillResumeService.flagManualResume(player);
        boolean plugged = false;
        if (threat.plugPosition() != null) {
            plugged = BotActions.placeBlockAt(player, threat.plugPosition(), LAVA_SAFETY_BLOCKS);
        }
        retreatFromHazard(player, threat.direction());
        String message = plugged
                ? "Lava ahead! Plugged it and backing away."
                : "Lava ahead! Retreating to stay safe.";
        message += " Use /bot resume " + player.getName().getString() + " when it's safe.";
        ChatUtils.sendChatMessages(source.withSilent().withMaxLevel(4), message);
        return true;
    }

    private boolean handleInventoryFull(ServerPlayerEntity player, ServerCommandSource source) {
        if (player == null || source == null) {
            return false;
        }
        if (!SkillPreferences.pauseOnFullInventory(player)) {
            return false;
        }
        if (!isInventoryFull(player)) {
            return false;
        }
        SkillResumeService.flagManualResume(player);
        ChatUtils.sendChatMessages(source.withSilent().withMaxLevel(4),
                "Inventory full — pausing mining. Clear space and run /bot resume " + player.getName().getString() + ".");
        BotActions.stop(player);
        return true;
    }

    private void retreatFromHazard(ServerPlayerEntity player, Direction dangerDirection) {
        if (player == null || dangerDirection == null) {
            return;
        }
        Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d target = pos.add(
                dangerDirection.getOpposite().getOffsetX() * 3,
                0,
                dangerDirection.getOpposite().getOffsetZ() * 3
        );
        BotActions.moveToward(player, target, 3.0D);
        if (player.isOnGround()) {
            BotActions.jump(player);
        }
        BotActions.stop(player);
    }

    private LavaThreat detectLavaThreat(ServerPlayerEntity player) {
        if (player == null || !(player.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        BlockPos feet = player.getBlockPos();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos adjacent = feet.offset(direction);
            if (isLava(world, adjacent) || isLava(world, adjacent.up())) {
                return new LavaThreat(direction, null);
            }
            if (isPassable(world, adjacent)) {
                BlockPos ahead = adjacent.offset(direction);
                if (isLava(world, ahead) || isLava(world, ahead.up())) {
                    return new LavaThreat(direction, adjacent);
                }
            }
        }
        return null;
    }

    private boolean isLava(ServerWorld world, BlockPos pos) {
        return world.getFluidState(pos).isIn(net.minecraft.registry.tag.FluidTags.LAVA);
    }

    private boolean isPassable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty() && world.getFluidState(pos).isEmpty();
    }

    private Set<Item> resolveTrackedItems() {
        if (targetBlockIds == null || targetBlockIds.isEmpty()) {
            return trackedItems;
        }
        LinkedHashSet<Item> merged = new LinkedHashSet<>(trackedItems);
        for (Identifier identifier : targetBlockIds) {
            merged.addAll(BlockDropRegistry.dropsFor(identifier));
        }
        return Set.copyOf(merged);
    }

    private int countInventoryItems(ServerPlayerEntity player, Set<Item> targets) {
        if (player == null) {
            return 0;
        }
        if (targets == null || targets.isEmpty()) {
            return 0;
        }
        PlayerInventory inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && targets.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private record LavaThreat(Direction direction, BlockPos plugPosition) {}

    private boolean isOnSurface(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        BlockPos botPos = player.getBlockPos();
        ServerWorld world = (ServerWorld) player.getEntityWorld();

        // Check if there's air above the bot (at least 2 blocks for head room)
        if (!world.getBlockState(botPos.up(1)).isAir() || !world.getBlockState(botPos.up(2)).isAir()) {
            return false;
        }

        // Check if the block the bot is standing on is solid
        if (world.getBlockState(botPos.down()).isAir()) {
            return false;
        }

        // Check if the bot is at a reasonable surface Y-level (e.g., above sea level)
        return botPos.getY() >= world.getSeaLevel();
    }

    @SuppressWarnings("unchecked")
    private Set<Identifier> getTargetBlockIdsParameter(SkillContext context, Set<Identifier> defaultIds) {
        Object raw = context.parameters().get("targetBlocks");
        if (raw instanceof Set<?> set) {
            try {
                return (Set<Identifier>) set;
            } catch (ClassCastException e) {
                LOGGER.warn("targetBlocks parameter contained unexpected value: {}", set);
            }
        }
        return defaultIds;
    }
}
