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
import net.shasankp000.GameAI.services.ChestStoreService;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.LavaHazardService;
import net.shasankp000.GameAI.services.TaskService;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.services.SkillResumeService;
import net.shasankp000.GameAI.services.WorkDirectionService;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.EntityUtil;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.skills.support.MiningHazardDetector;
import net.shasankp000.GameAI.skills.support.MiningHazardDetector.DetectionResult;
import net.shasankp000.GameAI.skills.support.MiningHazardDetector.Hazard;
import net.shasankp000.GameAI.skills.support.FallingBlockStabilizer;
import net.shasankp000.GameAI.skills.support.TorchPlacer;
import net.shasankp000.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private static final int SPIRAL_RADIUS_LIMIT = 5;
    private static final int STRAIGHT_STAIR_HEADROOM = 4;
    private static final int MAX_EXPLORATION_ATTEMPTS_PER_CYCLE = 3;
    private static final int MAX_STALLED_FAILURES = 5;
    private static final long INVENTORY_MESSAGE_COOLDOWN_MS = 5000; // 5 seconds between messages
    private static final long ESCAPE_COOLDOWN_MS = 15_000L;
    private static final long FALLING_BLOCK_SETTLE_MAX_MS = 5_000L;
    private static final long FALLING_BLOCK_SETTLE_POLL_MS = 200L;
    private static final long FALLING_BLOCK_MESSAGE_COOLDOWN_MS = 1_500L;
    
    private static final Map<UUID, Long> LAST_INVENTORY_FULL_MESSAGE = new HashMap<>();
    private static final Map<UUID, Long> LAST_ESCAPE_ATTEMPT = new HashMap<>();
    private static final Map<UUID, Long> LAST_FALLING_BLOCK_MESSAGE = new HashMap<>();
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
        boolean allowChestStore = getBooleanParameter(context, "allowChestStore", false);

        DirtShovelSkill shovelSkill = new DirtShovelSkill();
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity playerForAbortCheck = source.getPlayer();
        boolean resumeRequested = playerForAbortCheck != null
                && SkillResumeService.consumeResumeIntent(playerForAbortCheck.getUuid());
        if (!resumeRequested) {
            net.shasankp000.GameAI.skills.support.MiningHazardDetector.clear(playerForAbortCheck);
        }
        if (playerForAbortCheck != null && SkillManager.shouldAbortSkill(playerForAbortCheck)) {
            LOGGER.warn("{} aborted before starting due to external cancellation request.", skillName);
            return SkillExecutionResult.failure(skillName + " paused due to nearby threat.");
        }

        Set<Item> effectiveTrackedItems = resolveTrackedItems();

        List<String> optionTokens = getOptionTokens(context.parameters());
        
        // New ascent/descent parameters
        Integer ascentBlocks = getOptionalIntParameter(context, "ascentBlocks");
        Integer ascentTargetY = getOptionalIntParameter(context, "ascentTargetY");
        Integer descentBlocks = getOptionalIntParameter(context, "descentBlocks");
        Integer descentTargetY = getOptionalIntParameter(context, "descentTargetY");
        
        boolean ascentMode = (ascentBlocks != null || ascentTargetY != null);
        boolean descentMode = (descentBlocks != null || descentTargetY != null);
        
        // Calculate target Y for ascent/descent
        if (ascentMode && ascentBlocks != null) {
            targetCount = Integer.MAX_VALUE;
        } else if (descentMode && descentBlocks != null) {
            targetCount = Integer.MAX_VALUE;
        } else if (ascentMode && ascentTargetY != null) {
            targetCount = Integer.MAX_VALUE;
        } else if (descentMode && descentTargetY != null) {
            targetCount = Integer.MAX_VALUE;
        }
        
        boolean untilMode = optionTokens.contains("until") && !optionTokens.contains("exact");
        boolean squareMode = true;
        boolean spiralRequested = optionTokens.contains("spiral") || getBooleanParameter(context, "spiralMode", false);
        if (spiralRequested) {
            return SkillExecutionResult.failure("The 'spiral' staircase mode is temporarily disabled. Use 'ascent' or 'descent' commands.");
        }
        boolean spiralMode = false;

        // Handle ascent mode
        if (ascentMode && playerForAbortCheck != null) {
            int targetY;
            if (ascentBlocks != null) {
                targetY = playerForAbortCheck.getBlockY() + ascentBlocks;
                LOGGER.info("Ascent mode: climbing from Y={} to Y={} (+{} blocks)", 
                           playerForAbortCheck.getBlockY(), targetY, ascentBlocks);
            } else {
                targetY = ascentTargetY;
                LOGGER.info("Ascent mode: climbing from Y={} to Y={}", 
                           playerForAbortCheck.getBlockY(), targetY);
            }
            return runAscent(context, source, playerForAbortCheck, targetY, resumeRequested, allowChestStore);
        }

        // Handle descent mode
        if (descentMode && playerForAbortCheck != null) {
            int targetY;
            if (descentBlocks != null) {
                targetY = playerForAbortCheck.getBlockY() - descentBlocks;
                LOGGER.info("Descent mode: descending from Y={} to Y={} (-{} blocks)", 
                           playerForAbortCheck.getBlockY(), targetY, descentBlocks);
            } else {
                targetY = descentTargetY;
                LOGGER.info("Descent mode: descending from Y={} to Y={}", 
                           playerForAbortCheck.getBlockY(), targetY);
            }
            return runDescent(context, source, playerForAbortCheck, targetY, resumeRequested, allowChestStore);
        }

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

        if (playerForAbortCheck != null) {
            if (handleInventoryFull(playerForAbortCheck, source, allowChestStore)) {
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
        boolean[] reachedTarget = new boolean[]{false};

        try {

        int currentMaxFails = this.maxFails > 0 ? this.maxFails : MAX_ATTEMPTS_WITHOUT_PROGRESS;

        while (collected < targetCount
                && failuresInRow < currentMaxFails
                && System.currentTimeMillis() - startTime < MAX_RUNTIME_MILLIS
                && !Thread.currentThread().isInterrupted()
                && !TaskService.isAbortRequested(source.getPlayer() != null ? source.getPlayer().getUuid() : null)) {

            ServerPlayerEntity loopPlayer = source.getPlayer();
            if (loopPlayer != null) {
                if (handleInventoryFull(loopPlayer, source, allowChestStore)) {
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
                    false, // depthMode - not used in main loop anymore
                    false, // depthMode - not used in main loop anymore  
                    false, // stairMode - not used in main loop anymore
                    spiralMode,
                    resumeRequested
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

                if (collected < targetCount && shouldForcePauseForFullInventory(loopPlayer, source)) {
                    outcome = SkillExecutionResult.failure("Inventory full — collected " + collected + " out of " + targetCount + ". Clear space and run /bot resume " + loopPlayer.getName().getString() + ".");
                    break;
                }

                moveTowardLoot(source, context.sharedState(), activeSquareCenter != null, activeSquareCenter, effectiveHorizontal);
                if (loopPlayer != null) {
                    int updatedCount = countInventoryItems(loopPlayer, effectiveTrackedItems);
                    int updatedEffective = untilMode ? updatedCount : Math.max(0, updatedCount - baselineHarvestCount);
                    if (updatedEffective > inventoryCollected) {
                        inventoryCollected = updatedEffective;
                    }
                    collected = Math.max(collected, inventoryCollected);
                }
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
                
                // Auto-enable stair digging is disabled - use explicit ascent/descent commands instead
                // Legacy auto-enable code removed to avoid conflicts with new ascent/descent system
                
                if (explorationAttempts < MAX_EXPLORATION_ATTEMPTS_PER_CYCLE
                        && shouldTriggerExploration(lastMessage, failuresInRow, collected)) {
                    LOGGER.info("{} attempting exploration step after failure '{}'", skillName, lastMessage);
                    BlockPos preExplorePos = source.getPlayer() != null ? source.getPlayer().getBlockPos() : null;
                    boolean explorationSucceeded = attemptExplorationStep(source, context.sharedState(), activeSquareCenter != null, activeSquareCenter, effectiveHorizontal);
                    
                    // Only reset failures if bot actually moved significantly
                    boolean botActuallyMoved = false;
                    BlockPos postExplorePos = null;
                    if (explorationSucceeded && source.getPlayer() != null && preExplorePos != null) {
                        postExplorePos = source.getPlayer().getBlockPos();
                        botActuallyMoved = postExplorePos != null && postExplorePos.getSquaredDistance(preExplorePos) > 4;
                    }
                    
                    if (botActuallyMoved) {
                        failuresInRow = 0;
                        radiusBoost = Math.max(0, radiusBoost - HORIZONTAL_RADIUS_STEP);
                        verticalBoost = Math.max(0, verticalBoost - VERTICAL_RANGE_STEP);
                        int cleared = unreachable.size();
                        unreachable.clear();
                        double distance = postExplorePos != null && preExplorePos != null 
                                ? Math.sqrt(postExplorePos.getSquaredDistance(preExplorePos)) : 0.0;
                        LOGGER.info("{} exploration cleared {} unreachable targets after moving {} blocks", 
                                skillName, cleared, String.format("%.1f", distance));
                        LOGGER.info("{} exploration step succeeded; retrying search.", skillName);
                        startTime = System.currentTimeMillis();
                        explorationAttempts = 0;
                        continue;
                    } else if (explorationSucceeded) {
                        LOGGER.warn("{} exploration returned success but bot didn't move significantly - counting as failure", skillName);
                        failuresInRow++; // Increment instead of reset since bot didn't actually relocate
                    }
                    
                    if (!explorationSucceeded) {
                        explorationAttempts++;
                    }
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

            reachedTarget[0] = collected >= targetCount;
            if (collected < targetCount) {
                String failureMsg = lastMessage.isEmpty()
                        ? "Unable to collect the requested amount of " + harvestLabel + "."
                        : lastMessage;
                failureMsg += " Collected " + collected + " out of " + targetCount + " requested.";
                LOGGER.warn("{} finished early with {} {} gathered after {} attempts (runtime {} ms)",
                    skillName, collected, harvestLabel, attempt, System.currentTimeMillis() - startTime);
                outcome = SkillExecutionResult.failure(failureMsg);
            } else {
                int reported = Math.min(collected, targetCount);
                String summary = "Collected " + reported + " " + harvestLabel + " block" + (reported == 1 ? "" : "s") + ".";
                LOGGER.info("{} completed: {} (runtime {} ms, attempts {}, failures in a row {})",
                        skillName, summary, System.currentTimeMillis() - startTime, attempt, failuresInRow);
                outcome = SkillExecutionResult.success(summary);
            }
        }

        return outcome;
        } finally {
            ServerPlayerEntity cleanupPlayer = source.getPlayer();
            boolean inventoryFull = cleanupPlayer != null && isInventoryFull(cleanupPlayer);
            if (cleanupRequested && cleanupPlayer != null && !SkillManager.shouldAbortSkill(cleanupPlayer) && !inventoryFull && !reachedTarget[0]) {
                runDropCleanup(source, cleanupPlayer, cleanupBaseRadius, squareMode, squareCenter);
            } else if (cleanupPlayer != null && inventoryFull) {
                LOGGER.info("{} skipping drop cleanup because inventory is full.", skillName);
                ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                        "I'm out of inventory space, so I'll leave the remaining drops where they fell.");
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
                            source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
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

        if (!success && shouldAttemptEscape(player)) {
            boolean escaped = attemptVerticalEscape(source, player, originBlock);
            if (escaped) {
                result = GoTo.goTo(source, destination.getX(), destination.getY(), destination.getZ(), false);
                goToReportedSuccess = isGoToSuccess(result);
                postGoToBlock = player.getBlockPos();
                distanceSqToDestination = postGoToBlock.getSquaredDistance(destination);
                closeEnough = distanceSqToDestination <= 9.0D;
                success = goToReportedSuccess && closeEnough;
            }
        }

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

    private boolean shouldAttemptEscape(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_ESCAPE_ATTEMPT.get(player.getUuid());
        if (last != null && (now - last) < ESCAPE_COOLDOWN_MS) {
            return false;
        }
        return true;
    }

    private boolean attemptVerticalEscape(ServerCommandSource source, ServerPlayerEntity player, BlockPos referencePos) {
        if (source == null || player == null || !(player.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos start = player.getBlockPos();
        if (!isLikelyTrappedInPit(world, start)) {
            return false;
        }
        LAST_ESCAPE_ATTEMPT.put(player.getUuid(), System.currentTimeMillis());

        ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                "I'm stuck down here — attempting a ladder or stair escape...");

        boolean ladderAttempted = ensureLadders(source, player, 6);
        if (ladderAttempted) {
            Direction supportDir = pickLadderSupportDirection(world, start, 8);
            if (supportDir != null) {
                BlockPos exit = findLadderExit(world, start, supportDir, 8);
                if (exit != null) {
                    int neededHeight = Math.max(1, exit.getY() - start.getY());
                    boolean placed = placeLadderColumn(world, player, start, supportDir, neededHeight);
                    if (placed) {
                        String moveResult = GoTo.goTo(source, exit.getX(), exit.getY(), exit.getZ(), false);
                        if (isGoToSuccess(moveResult) || player.getBlockPos().getSquaredDistance(exit) <= 9.0D) {
                            return true;
                        }
                    }
                }
            }
        }

        int targetY = start.getY() + 4;
        if (referencePos != null) {
            targetY = Math.max(targetY, referencePos.getY());
        }
        SkillExecutionResult ascent = runAscent(new SkillContext(source, new HashMap<>(), new HashMap<>()), source, player, targetY, false, false);
        return ascent.success() || player.getBlockY() >= targetY;
    }

    private boolean ensureLadders(ServerCommandSource source, ServerPlayerEntity player, int needed) {
        if (source == null || player == null) {
            return false;
        }
        int have = countInventoryItems(player, Set.of(Items.LADDER));
        if (have >= needed) {
            return true;
        }
        int crafted = net.shasankp000.GameAI.services.CraftingHelper.craftGeneric(source, player, source.getPlayer(), "ladder", needed - have, null);
        return crafted > 0 || countInventoryItems(player, Set.of(Items.LADDER)) >= needed;
    }

    private boolean isLikelyTrappedInPit(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        // If there's any adjacent walkable stand position, we're not trapped.
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos neighborFoot = feet.offset(dir);
            BlockPos neighborHead = neighborFoot.up();
            BlockPos neighborBelow = neighborFoot.down();
            if (isPassable(world, neighborFoot)
                    && isPassable(world, neighborHead)
                    && world.getBlockState(neighborBelow).isSolidBlock(world, neighborBelow)) {
                return false;
            }
        }
        // Otherwise consider "trapped" if all four horizontal neighbors at foot level are non-passable.
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (isPassable(world, feet.offset(dir))) {
                return false;
            }
        }
        return true;
    }

    private Direction pickLadderSupportDirection(ServerWorld world, BlockPos start, int scanHeight) {
        if (world == null || start == null) {
            return null;
        }
        Direction best = null;
        int bestScore = -1;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            int score = 0;
            for (int dy = 0; dy <= scanHeight; dy++) {
                BlockPos foot = start.up(dy);
                BlockPos support = foot.offset(dir);
                if (world.getBlockState(support).isSolidBlock(world, support)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = dir;
            }
        }
        return bestScore >= 2 ? best : null;
    }

    private BlockPos findLadderExit(ServerWorld world, BlockPos start, Direction supportDir, int scanHeight) {
        if (world == null || start == null || supportDir == null) {
            return null;
        }
        for (int dy = 1; dy <= scanHeight; dy++) {
            BlockPos climbPos = start.up(dy);
            if (!isPassable(world, climbPos) || !world.getBlockState(climbPos.offset(supportDir)).isSolidBlock(world, climbPos.offset(supportDir))) {
                continue;
            }
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos exitFoot = climbPos.offset(dir);
                BlockPos exitHead = exitFoot.up();
                BlockPos exitBelow = exitFoot.down();
                if (isPassable(world, exitFoot)
                        && isPassable(world, exitHead)
                        && world.getBlockState(exitBelow).isSolidBlock(world, exitBelow)) {
                    return exitFoot.toImmutable();
                }
            }
        }
        return null;
    }

    private boolean placeLadderColumn(ServerWorld world, ServerPlayerEntity player, BlockPos start, Direction supportDir, int height) {
        if (world == null || player == null || start == null || supportDir == null) {
            return false;
        }
        boolean any = false;
        Direction face = supportDir.getOpposite();
        for (int dy = 0; dy < height; dy++) {
            if (SkillManager.shouldAbortSkill(player)) {
                break;
            }
            BlockPos pos = start.up(dy);
            if (!isPassable(world, pos)) {
                break;
            }
            BlockPos support = pos.offset(supportDir);
            if (!world.getBlockState(support).isSolidBlock(world, support)) {
                break;
            }
            boolean placed = BotActions.placeBlockAt(player, pos, face, List.of(Items.LADDER));
            any = any || placed;
        }
        return any;
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

    // ==================== DESCENT METHOD (Restored from working version) ====================
    
    private SkillExecutionResult runDescent(SkillContext context,
                                           ServerCommandSource source,
                                           ServerPlayerEntity player,
                                           int targetDepthY,
                                           boolean resumeRequested,
                                           boolean allowChestStore) {
        if (player == null) {
            return SkillExecutionResult.failure("No bot available for descent.");
        }
        if (player.getBlockY() <= targetDepthY) {
            return SkillExecutionResult.success("Already at or below target depth " + targetDepthY + ".");
        }

        // Use the same "mining mode" flag as ascent to avoid aggressive burial-rescue logic fighting
        // an intentional 1x2 stairwell tunnel.
        TaskService.setAscentMode(player.getUuid(), true);

        try {
            boolean strictWalk = getBooleanParameter(context, "strictWalk", false);

            // Check if resuming from a paused position
            Optional<BlockPos> pausePos = WorkDirectionService.getPausePosition(player.getUuid());
            if (resumeRequested && pausePos.isPresent()) {
                BlockPos resumeTarget = pausePos.get();
                LOGGER.info("Descent resuming - navigating back to pause position {}", resumeTarget.toShortString());
                ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                        "Returning to descent position...");

                Direction digDirection = determineStraightStairDirection(context, player, resumeRequested);
                MovementService.MovementPlan plan = new MovementService.MovementPlan(
                        MovementService.Mode.DIRECT, resumeTarget, resumeTarget, null, null, digDirection);
                MovementService.MovementResult movement = MovementService.execute(source, player, plan);

                if (!movement.success() || !player.getBlockPos().equals(resumeTarget)) {
                    LOGGER.warn("Failed to return to pause position {}, continuing from current location", resumeTarget.toShortString());
                    ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                            "Couldn't return to exact position, continuing from here.");
                }
                WorkDirectionService.clearPausePosition(player.getUuid());
            }

            Direction digDirection = determineStraightStairDirection(context, player, resumeRequested);
            runOnServerThread(player, () -> LookController.faceBlock(player, player.getBlockPos().offset(digDirection)));

            final int startY = player.getBlockY();

        int carvedSteps = 0;
        while (player.getBlockY() > targetDepthY) {
            if (SkillManager.shouldAbortSkill(player)) {
                return SkillExecutionResult.failure(skillName + " paused due to nearby threat.");
            }
            
            if (handleInventoryFull(player, source, allowChestStore)) {
                return SkillExecutionResult.failure("Mining paused: inventory full.");
            }
            if (handleWaterHazard(player, source)) {
                return SkillExecutionResult.failure("Mining paused: water flooded the dig site.");
            }
            if (handleLavaHazard(player, source)) {
                return SkillExecutionResult.failure("Mining paused: lava detected.");
            }

            BlockPos currentFeet = player.getBlockPos();
            BlockPos forward = currentFeet.offset(digDirection);
            BlockPos stairFoot = forward.down();
            List<BlockPos> workVolume = buildStraightStairVolume(forward, stairFoot);
            workVolume = workVolume.stream()
                    .filter(pos -> !isTorchBlock(player.getEntityWorld().getBlockState(pos)))
                    .toList();
            DetectionResult detection = MiningHazardDetector.detect(player, workVolume, List.of(stairFoot), true);
            detection.adjacentWarnings().forEach(warning ->
                    ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), warning.chatMessage()));
            if (detection.blockingHazard().isPresent()) {
                Hazard hazard = detection.blockingHazard().get();
                WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                SkillResumeService.flagManualResume(player);
                ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), hazard.chatMessage());
                return SkillExecutionResult.failure(hazard.failureMessage());
            }

                for (BlockPos block : workVolume) {
                    BlockState state = player.getEntityWorld().getBlockState(block);
                    if (state.isAir()) {
                        continue;
                    }
                Block blockType = state.getBlock();
                if (blockType == Blocks.TORCH || blockType == Blocks.WALL_TORCH || 
                    blockType == Blocks.SOUL_TORCH || blockType == Blocks.SOUL_WALL_TORCH ||
                    blockType == Blocks.REDSTONE_TORCH || blockType == Blocks.REDSTONE_WALL_TORCH) {
                    continue;
                }
                    if (!mineStraightStairBlock(player, block)) {
                    WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                    SkillResumeService.flagManualResume(player);
                    ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                            "I couldn't clear a block in the stairwell at " + block.getX() + ", " + block.getY() + ", " + block.getZ() + ". I'll pause here.");
                    return SkillExecutionResult.failure("Descent paused: unable to clear stairwell at " + block + ".");
                    }
                }

            // Falling blocks (sand/gravel/etc.) can refill the stairwell after we mine. Pause briefly until
            // they settle, then re-clear any refilled falling blocks before moving.
            if (!stabilizeFallingBlocks(player, source, workVolume, stairFoot)) {
                return SkillExecutionResult.failure("Descent paused: falling blocks won't settle. Use /bot resume.");
            }

            BlockPos support = stairFoot.down();
            if (!hasSolidSupport(player, support)) {
                SkillResumeService.flagManualResume(player);
                ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), "There's a drop ahead.");
                return SkillExecutionResult.failure("Descent paused: unsafe drop detected.");
            }

            MovementService.MovementPlan plan =
                    new MovementService.MovementPlan(MovementService.Mode.DIRECT, stairFoot, stairFoot, null, null, digDirection);
            boolean allowPursuit = true;
            boolean allowSnap = !strictWalk;
            // When strictWalk is enabled, do not snap during descent; stick to held-input walking only.
            MovementService.MovementResult moveResult = MovementService.execute(source, player, plan, false, true, allowPursuit, allowSnap);
            if (!moveResult.success()) {
                WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                SkillResumeService.flagManualResume(player);
                ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                        "I'm stuck trying to step down to " + stairFoot.getX() + ", " + stairFoot.getY() + ", " + stairFoot.getZ()
                                + " (" + moveResult.detail() + "). I'll pause here.");
                return SkillExecutionResult.failure("Descent aborted: failed to advance (" + moveResult.detail() + ").");
            }
            BlockPos postMove = player.getBlockPos();
            if (!postMove.equals(stairFoot)) {
                boolean nudged = attemptManualNudge(player, stairFoot);
                if (!nudged && !player.getBlockPos().equals(stairFoot)) {
                    WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                    SkillResumeService.flagManualResume(player);
                    ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                            "I couldn't reach the next stair at " + stairFoot.getX() + ", " + stairFoot.getY() + ", " + stairFoot.getZ() + ". I'll pause here.");
                    return SkillExecutionResult.failure("Descent aborted: movement stalled before reaching " + stairFoot + ".");
                }
            }
            carvedSteps++;
            
            // Place torch every 6 steps
            if (carvedSteps % 6 == 0 && TorchPlacer.shouldPlaceTorch(player)) {
                TorchPlacer.PlacementResult torchResult = TorchPlacer.placeTorch(player, digDirection);
                if (torchResult == TorchPlacer.PlacementResult.NO_TORCHES) {
                    SkillResumeService.flagManualResume(player);
                    ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), 
                            "Ran out of torches!");
                    return SkillExecutionResult.failure("Descent paused: out of torches. Provide torches and /bot resume.");
                }
            }
        }

        int endY = player.getBlockY();
        int descendedBlocks = Math.max(0, startY - endY);
        return SkillExecutionResult.success("Descended " + descendedBlocks + " blocks (" + carvedSteps + " steps) to Y=" + endY + ".");
        } finally {
            TaskService.setAscentMode(player.getUuid(), false);
        }
    }

    private boolean stabilizeFallingBlocks(ServerPlayerEntity player,
                                          ServerCommandSource source,
                                          List<BlockPos> workVolume,
                                          BlockPos stairFoot) {
        if (player == null || workVolume == null || workVolume.isEmpty()) {
            return true;
        }

        BlockPos focus = stairFoot != null ? stairFoot : player.getBlockPos();
        boolean ok = FallingBlockStabilizer.stabilizeAndReclear(
                player,
                source,
                focus,
                workVolume,
                this::isPassableForStairs,
                this::isTorchBlock,
                pos -> mineStraightStairBlock(player, pos),
                true
        );
        if (!ok) {
            WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
            SkillResumeService.flagManualResume(player);
        }
        return ok;
    }

    private boolean waitForNearbyFallingBlocksToSettle(ServerPlayerEntity player,
                                                      ServerWorld world,
                                                      BlockPos focus,
                                                      long maxWaitMs) {
        if (player == null || world == null || focus == null || maxWaitMs <= 0) {
            return true;
        }

        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            final boolean[] hasFalling = new boolean[] { false };
            runOnServerThread(player, () -> {
                Box box = new Box(
                    focus.getX(), focus.getY(), focus.getZ(),
                    focus.getX() + 1, focus.getY() + 1, focus.getZ() + 1
                ).expand(3.5D, 10.0D, 3.5D);
                hasFalling[0] = !world.getEntitiesByClass(
                        net.minecraft.entity.FallingBlockEntity.class,
                        box,
                        entity -> entity != null && entity.isAlive()
                ).isEmpty();
            });

            if (!hasFalling[0]) {
                return true;
            }

            runOnServerThread(player, () -> BotActions.stop(player));
            maybeSendFallingBlockWaitMessage(player);
            try {
                Thread.sleep(FALLING_BLOCK_SETTLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void maybeSendFallingBlockWaitMessage(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUuid();
        long now = System.currentTimeMillis();
        Long last = LAST_FALLING_BLOCK_MESSAGE.get(id);
        if (last != null && (now - last) < FALLING_BLOCK_MESSAGE_COOLDOWN_MS) {
            return;
        }
        LAST_FALLING_BLOCK_MESSAGE.put(id, now);
        ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                "Waiting for sand/gravel to settle...");
    }

    private boolean isFallingBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        Block b = state.getBlock();
        return b instanceof net.minecraft.block.FallingBlock;
    }
    
    // ==================== ASCENT METHOD (Walk-and-jump upward) ====================
    
    private SkillExecutionResult runAscent(SkillContext context,
                                          ServerCommandSource source,
                                          ServerPlayerEntity player,
                                          int targetY,
                                          boolean resumeRequested,
                                          boolean allowChestStore) {
        if (player == null) {
            return SkillExecutionResult.failure("No bot available for ascent.");
        }
        if (player.getBlockY() >= targetY) {
            return SkillExecutionResult.success("Already at or above target Y=" + targetY + ".");
        }
        
        // Mark bot as in ascent mode to disable aggressive in-wall damage handling
        TaskService.setAscentMode(player.getUuid(), true);
        
        try {
            Direction direction = determineStraightStairDirection(context, player, resumeRequested);
            runOnServerThread(player, () -> LookController.faceBlock(player, player.getBlockPos().offset(direction)));
            
            BotActions.selectBestTool(player, preferredTool, "sword");
            
            LOGGER.info("Starting ascent from Y={} to Y={} in direction {}", 
                    player.getBlockY(), targetY, direction);
            
            long startTime = System.currentTimeMillis();
            int steps = 0;
            int maxSteps = Math.abs(targetY - player.getBlockY()) * 5; // Generous limit
            
            while (player.getBlockY() < targetY && 
                   System.currentTimeMillis() - startTime < MAX_RUNTIME_MILLIS && 
                   steps < maxSteps) {
                
                // Safety checks
                if (SkillManager.shouldAbortSkill(player)) {
                    WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                    return SkillExecutionResult.failure(skillName + " paused due to nearby threat.");
                }
                if (handleInventoryFull(player, source, allowChestStore)) {
                    WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                    return SkillExecutionResult.failure("Mining paused: inventory full.");
                }
                if (handleWaterHazard(player, source)) {
                    WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                    return SkillExecutionResult.failure("Mining paused: water flooded the area.");
                }
                if (handleLavaHazard(player, source)) {
                    WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                    return SkillExecutionResult.failure("Mining paused: lava detected.");
                }
            
            // Execute one upward step
            SkillExecutionResult stepResult = executeUpwardStep(player, direction, source);
            if (!stepResult.success()) {
                WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                return stepResult;
            }
            
            steps++;
            
            // Place torch every 6 steps
            if (steps % 6 == 0 && TorchPlacer.shouldPlaceTorch(player)) {
                TorchPlacer.PlacementResult torchResult = TorchPlacer.placeTorch(player, direction);
                if (torchResult == TorchPlacer.PlacementResult.NO_TORCHES) {
                    SkillResumeService.flagManualResume(player);
                    WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                    ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), 
                            "Ran out of torches!");
                    return SkillExecutionResult.failure("Ascent paused: out of torches.");
                }
            }
        }
        
        if (player.getBlockY() >= targetY) {
            LOGGER.info("Ascent complete after {} steps, final Y={}", steps, player.getBlockY());
            return SkillExecutionResult.success("Reached target Y=" + targetY + " after " + steps + " steps.");
        }
        
        if (steps >= maxSteps) {
            WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
            return SkillExecutionResult.failure("Reached step limit without completing ascent.");
        }
        
        WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
        return SkillExecutionResult.failure("Timed out after " + steps + " steps.");
        } finally {
            // Always clear ascent mode flag when method exits
            TaskService.setAscentMode(player.getUuid(), false);
        }
    }
    
    private SkillExecutionResult executeUpwardStep(ServerPlayerEntity player, Direction direction, ServerCommandSource source) {
        BlockPos feet = player.getBlockPos();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        
        // 1. Find next step-up block (search forward 1-8 blocks for solid block at Y+1)
        BlockPos stepBlock = null;
        for (int distance = 1; distance <= 8; distance++) {
            BlockPos candidate = feet.offset(direction, distance).up();
            BlockState state = world.getBlockState(candidate);
            if (!state.isAir() && !state.getCollisionShape(world, candidate).isEmpty()) {
                stepBlock = candidate;
                LOGGER.debug("Found step-up block at {} (distance {})", stepBlock.toShortString(), distance);
                break;
            }
        }
        
        if (stepBlock == null) {
            // No step found - walk forward on flat ground to find one
            LOGGER.debug("No step-up block found within 8 blocks - walking forward");
            runOnServerThread(player, () -> {
                LookController.faceBlock(player, feet.offset(direction, 3));
                BotActions.moveForwardStep(player, 1.0);
            });
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            runOnServerThread(player, () -> BotActions.stop(player));
            return SkillExecutionResult.success("Walking forward");
        }
        
        // 2. Walk toward the step block until close enough to mine it
        int beforeY = player.getBlockY();
        BlockPos horizontalTarget = new BlockPos(stepBlock.getX(), feet.getY(), stepBlock.getZ());
        Vec3d targetVec = Vec3d.ofBottomCenter(horizontalTarget);
        final BlockPos finalStepBlock = stepBlock;
        
        // Walk closer until within mining reach (closer than 1.8 blocks horizontally)
        double distanceToStep = Math.sqrt(player.squaredDistanceTo(targetVec));
        while (distanceToStep > 1.8) {
            runOnServerThread(player, () -> {
                LookController.faceBlock(player, finalStepBlock);
                BotActions.moveForwardStep(player, 1.0);
            });
            
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SkillExecutionResult.failure("Interrupted while walking to step.");
            }
            
            runOnServerThread(player, () -> BotActions.stop(player));
            
            double newDistance = Math.sqrt(player.squaredDistanceTo(targetVec));
            if (newDistance >= distanceToStep) {
                // Not making progress walking
                break;
            }
            distanceToStep = newDistance;
        }
        
        // 3. Scan headroom for hazards/rares before clearing, then break step block + 8 above it
        List<BlockPos> headroomVolume = new java.util.ArrayList<>();
        for (int hh = 0; hh <= 8; hh++) {
            BlockPos pos = stepBlock.up(hh);
            if (isTorchBlock(world.getBlockState(pos))) {
                continue;
            }
            headroomVolume.add(pos);
        }
        DetectionResult ascentDetection = MiningHazardDetector.detect(player, headroomVolume, java.util.List.of(stepBlock));
        ascentDetection.adjacentWarnings().forEach(w ->
                ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), w.chatMessage()));
        if (ascentDetection.blockingHazard().isPresent()) {
            Hazard hazard = ascentDetection.blockingHazard().get();
            WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
            SkillResumeService.flagManualResume(player);
            ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), hazard.chatMessage());
            return SkillExecutionResult.failure(hazard.failureMessage());
        }
        // Clear headroom: break the step block itself AND 8 blocks above it
        for (int h = 0; h <= 8; h++) {
            BlockPos clearPos = stepBlock.up(h);
            BlockState state = world.getBlockState(clearPos);
            
            if (!state.isAir()) {
                Block blockType = state.getBlock();
                // Skip torches
                if (blockType == Blocks.TORCH || blockType == Blocks.WALL_TORCH || 
                    blockType == Blocks.SOUL_TORCH || blockType == Blocks.SOUL_WALL_TORCH ||
                    blockType == Blocks.REDSTONE_TORCH || blockType == Blocks.REDSTONE_WALL_TORCH) {
                    continue;
                }
                
                // Mine the block - bot is now close enough
                if (!mineStraightStairBlock(player, clearPos)) {
                    LOGGER.warn("Could not clear headroom block at {}, continuing anyway", clearPos.toShortString());
                }
            }
        }

        // Falling blocks (sand/gravel/etc.) can refill the cleared headroom after mining.
        // Mirror descent safety: wait for settling, then re-clear any falling refills before jumping.
        if (!FallingBlockStabilizer.stabilizeAndReclear(
                player,
                source,
                stepBlock,
                headroomVolume,
                this::isPassableForStairs,
                this::isTorchBlock,
                pos -> mineStraightStairBlock(player, pos),
                true
        )) {
            WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
            SkillResumeService.flagManualResume(player);
            return SkillExecutionResult.failure("Ascent paused: falling blocks won't settle. Use /bot resume.");
        }
        
        // 4. Now jump onto the step block
        for (int attempt = 1; attempt <= 5; attempt++) {
            runOnServerThread(player, () -> {
                LookController.faceBlock(player, finalStepBlock);
                BotActions.moveForwardStep(player, 0.5);
                BotActions.jump(player);
            });
            
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SkillExecutionResult.failure("Interrupted during jump.");
            }
            
            runOnServerThread(player, () -> BotActions.stop(player));
            
            int afterY = player.getBlockY();
            if (afterY > beforeY) {
                LOGGER.info("Successfully climbed from Y={} to Y={} (attempt {})", beforeY, afterY, attempt);
                return SkillExecutionResult.success("Climbed step");
            }
            
            // If we went DOWN instead of up, that's okay - recalculate and continue
            if (afterY < beforeY) {
                LOGGER.debug("Fell from Y={} to Y={} - will recalculate next step", beforeY, afterY);
                return SkillExecutionResult.success("Position adjusted");
            }
            
            LOGGER.debug("Climb attempt {} failed: beforeY={}, afterY={}", attempt, beforeY, afterY);
        }
        
        // Didn't climb after 5 attempts, but don't fail - just report and continue
        LOGGER.warn("Did not climb after 5 attempts, Y remains at {}", player.getBlockY());
        return SkillExecutionResult.success("No progress - will retry");
    }
    

    private List<BlockPos> buildStraightStairVolume(BlockPos forward, BlockPos stairFoot) {
        LinkedHashSet<BlockPos> blocks = new LinkedHashSet<>();
        if (forward != null) {
            for (int i = 0; i < STRAIGHT_STAIR_HEADROOM; i++) {
                blocks.add(forward.up(i));
            }
        }
        if (stairFoot != null) {
            for (int i = 0; i < STRAIGHT_STAIR_HEADROOM; i++) {
                blocks.add(stairFoot.up(i));
            }
        }
        return List.copyOf(blocks);
    }

    private boolean mineStraightStairBlock(ServerPlayerEntity player, BlockPos blockPos) {
        if (player == null || blockPos == null) {
            return true;
        }
        
        // Skip torches - don't break the lights we placed
        BlockState state = player.getEntityWorld().getBlockState(blockPos);
        Block block = state.getBlock();
        if (block == Blocks.TORCH || block == Blocks.WALL_TORCH || 
            block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH ||
            block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH) {
            return true; // Skip torches, treat as already cleared
        }

        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }

        // If the target is already non-solid and non-fluid, treat as cleared.
        if (isPassableForStairs(world, blockPos)) {
            return true;
        }

        // Select the best tool per block type (gravel/sand => shovel; pickaxe for others)
        String toolKeyword = null;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.SHOVEL_MINEABLE)) {
            toolKeyword = "shovel";
        } else if (state.isIn(net.minecraft.registry.tag.BlockTags.PICKAXE_MINEABLE)) {
            toolKeyword = "pickaxe";
        }
        BotActions.selectBestTool(player, toolKeyword != null ? toolKeyword : preferredTool, "sword");

        // In heavily modded worlds, some blocks can "re-fill" quickly (falling blocks, delayed updates).
        // Treat mining timeouts/hiccups as transient and retry a couple times before aborting the descent.
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (SkillManager.shouldAbortSkill(player)) {
                return false;
            }
            if (!isWithinStraightReach(player, blockPos)) {
                int dy = blockPos.getY() - player.getBlockY();
                if (dy > 2) {
                    LOGGER.debug("Descent skipping out-of-reach headroom block at {} (botPos={}, dy={})",
                            blockPos.toShortString(), player.getBlockPos().toShortString(), dy);
                    return true;
                }
                return false;
            }
            LookController.faceBlock(player, blockPos);
            CompletableFuture<String> future = MiningTool.mineBlock(player, blockPos);
            try {
                // MiningTool has its own failsafe timeout; here we just wait for the outcome of this attempt.
                // Use a timeout >= MiningTool's failsafe (12s) so slow tools don't get canceled early.
                String result = future.get(13, TimeUnit.SECONDS);
                if (result != null && result.toLowerCase(Locale.ROOT).contains("out of reach")) {
                    LOGGER.warn("Descent mining attempt {}/3 out-of-reach at {} (botPos={} dist={})",
                            attempt,
                            blockPos.toShortString(),
                            player.getBlockPos().toShortString(),
                            String.format("%.2f", Math.sqrt(player.squaredDistanceTo(Vec3d.ofCenter(blockPos)))));
                } else if (result != null && result.startsWith("⚠️")) {
                    LOGGER.warn("Descent mining attempt {}/3 returned '{}' at {} (botPos={})",
                            attempt, result, blockPos.toShortString(), player.getBlockPos().toShortString());
                }
            } catch (TimeoutException timeout) {
                // Don't cancel: canceling the future stops the MiningTool task early.
                LOGGER.warn("Descent mining attempt {}/3 timed out waiting at {} (state={})",
                        attempt,
                        blockPos.toShortString(),
                        world.getBlockState(blockPos).getBlock().getName().getString());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                LOGGER.warn("Descent mining attempt {}/3 failed at {}: {}", attempt, blockPos.toShortString(), e.getMessage());
            }

            if (isPassableForStairs(world, blockPos)) {
                return true;
            }
            // If it wasn't cleared, wait briefly (lets falling blocks settle / fluids update) and retry.
            try {
                Thread.sleep(180L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        LOGGER.warn("Descent aborted: couldn't clear {} after multiple mining attempts (state={})",
                blockPos.toShortString(),
                world.getBlockState(blockPos).getBlock().getName().getString());
        return false;
    }

    private boolean isPassableForStairs(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        if (!world.getFluidState(pos).isEmpty()) {
            return false;
        }
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private boolean isTorchBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        Block block = state.getBlock();
        return block == Blocks.TORCH
                || block == Blocks.WALL_TORCH
                || block == Blocks.SOUL_TORCH
                || block == Blocks.SOUL_WALL_TORCH
                || block == Blocks.REDSTONE_TORCH
                || block == Blocks.REDSTONE_WALL_TORCH;
    }

    private boolean isWithinStraightReach(ServerPlayerEntity player, BlockPos blockPos) {
        if (player == null || blockPos == null) {
            return false;
        }
        Vec3d center = Vec3d.ofCenter(blockPos);
        // Match BotActions/MiningTool survival reach (~4.5 blocks).
        return player.squaredDistanceTo(center.x, center.y, center.z) <= 20.25D;
    }

    private boolean hasSolidSupport(ServerPlayerEntity player, BlockPos support) {
        if (player == null || support == null) {
            return false;
        }
        return !player.getEntityWorld().getBlockState(support).getCollisionShape(player.getEntityWorld(), support).isEmpty();
    }

    private Direction determineStraightStairDirection(SkillContext context, ServerPlayerEntity player, boolean resumeRequested) {
        Direction explicit = null;
        // Prefer direction captured from the command issuer (raycast/look), if provided.
        Object explicitObj = context.parameters().get("direction");
        if (explicitObj == null) {
            explicitObj = context.parameters().get("issuerFacing");
        }
        if (explicitObj instanceof Direction dir) {
            explicit = dir;
        } else if (explicitObj instanceof String s) {
            explicit = parseDirection(s);
        }

        Direction base = explicit;
        if (base == null) {
            base = scanForButtonDirection(player);
        }
        if (base == null) {
            base = player.getHorizontalFacing();
        }

        Direction current = base;
        Map<String, Object> shared = context.sharedState();
        boolean lock = getBooleanParameter(context, "lockDirection", false) || resumeRequested;
        if (shared != null) {
            String key = "collectDirt.depth.direction." + player.getUuid();
            if (!lock) {
                shared.remove(key); // reset unless locked
            } else {
                Object stored = shared.get(key);
                if (stored instanceof String str) {
                    Direction remembered = parseDirection(str);
                    if (remembered != null) {
                        current = remembered; // reuse stored when locked
                    }
                }
            }
            shared.put(key, current.asString());
        }
        LOGGER.info("Stair/stripmine direction resolved: {} (lockDirection={})", current, lock);
        // Persist button-based direction if derived
        if (context.sharedState() != null && context.sharedState().containsKey("buttonDirection")) {
            context.sharedState().put("collectDirt.depth.direction." + player.getUuid(), context.sharedState().get("buttonDirection"));
        }
        // Immediately orient bot to chosen direction for consistent start (convert horizontal facing to yaw degrees)
        // Use issuerYaw if provided (precise orientation) else map direction
        Object issuerYawObj = context.parameters().get("issuerYaw");
        Float issuerYaw = null;
        if (issuerYawObj instanceof Number n) issuerYaw = n.floatValue();
        float yaw = issuerYaw != null ? issuerYaw : switch (current) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case WEST -> 90f;
            case EAST -> -90f;
            default -> player.getYaw();
        };
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        return current;
    }

    private Direction scanForButtonDirection(ServerPlayerEntity player) {
        if (player == null) return null;
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        BlockPos origin = player.getBlockPos();
        Direction found = null;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 2; dy++) { // slight vertical tolerance
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    Block block = state.getBlock();
                    if (isButtonBlock(block)) {
                        // Determine face: buttons have FACING property for wall, else infer from surrounding air
                        if (state.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
                            found = state.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
                        } else {
                            // Approximate by choosing direction from player to button
                            Vec3d to = Vec3d.ofCenter(pos).subtract(player.getX(), player.getY(), player.getZ());
                            found = horizontalFromVector(to);
                        }
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean isButtonBlock(Block block) {
        return block == Blocks.STONE_BUTTON || block == Blocks.OAK_BUTTON || block == Blocks.BIRCH_BUTTON ||
               block == Blocks.SPRUCE_BUTTON || block == Blocks.JUNGLE_BUTTON || block == Blocks.DARK_OAK_BUTTON ||
               block == Blocks.ACACIA_BUTTON || block == Blocks.CHERRY_BUTTON || block == Blocks.MANGROVE_BUTTON ||
               block == Blocks.CRIMSON_BUTTON || block == Blocks.WARPED_BUTTON || block == Blocks.POLISHED_BLACKSTONE_BUTTON;
    }

    private Direction horizontalFromVector(Vec3d v) {
        if (v == null) return null;
        double ax = Math.abs(v.x); double az = Math.abs(v.z);
        if (ax >= az) {
            return v.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return v.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private Direction parseDirection(String name) {
        if (name == null) {
            return null;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (dir.asString().equalsIgnoreCase(name)) {
                return dir;
            }
        }
        return null;
    }


    private void runDropCleanup(ServerCommandSource source,
                                ServerPlayerEntity player,
                                double baseRadius,
                                boolean squareMode,
                                BlockPos squareCenter) {
        try {
            if (isInventoryFull(player)) {
                LOGGER.info("Drop cleanup skipped because {} inventory is full.", player.getName().getString());
                return;
            }
            double radius = Math.max(baseRadius, DROP_SEARCH_RADIUS * 1.5D);
            if (squareMode && squareCenter != null) {
                radius = Math.max(radius, baseRadius + 2.0D);
            }
            double vertical = Math.max(radius, 6.0D);
            for (int attempt = 0; attempt < 3; attempt++) {
                BotEventHandler.collectNearbyDrops(player, radius);
                try {
                    DropSweeper.sweep(
                            source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
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
        
        // Less strict check: consider inventory "full" if fewer than 3 empty slots
        // This is more practical for mining where partial stacks are common
        int emptyCount = 0;
        for (int i = 0; i < 36; i++) { // Main inventory slots (0-35)
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                emptyCount++;
                if (emptyCount >= 3) {
                    return false; // Still has 3+ empty slots, not full
                }
            }
        }
        
        // Fewer than 3 empty slots = inventory is full enough to warn/pause
        return true;
    }

    private boolean handleWaterHazard(ServerPlayerEntity player, ServerCommandSource source) {
        if (!isTouchingWater(player)) {
            return false;
        }
        SkillResumeService.flagManualResume(player);
        String alias = player.getName().getString();
        ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
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
        LavaHazardService.LavaResponse response = LavaHazardService.respondToLava(
                player,
                source,
                threat.direction(),
                threat.plugPosition(),
                null
        );
        String message = response.usedWater()
                ? "Lava ahead! Used water and backing away."
                : (response.plugged() ? "Lava ahead! Plugged it and backing away." : "Lava ahead! Retreating to stay safe.");
        message += " Use /bot resume " + player.getName().getString() + " when it's safe.";
        ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), message);
        return true;
    }

    private boolean handleInventoryFull(ServerPlayerEntity player, ServerCommandSource source, boolean allowChestStore) {
        if (player == null || source == null) {
            return false;
        }
        if (allowChestStore && isInventoryFull(player)) {
            boolean stored = attemptChestStore(player, source);
            if (stored && !isInventoryFull(player)) {
                LAST_INVENTORY_FULL_MESSAGE.remove(player.getUuid());
                return false;
            }
        }
        boolean shouldPause = SkillPreferences.pauseOnFullInventory(player);
        if (!isInventoryFull(player)) {
            // Inventory has space again - clear cooldown
            LAST_INVENTORY_FULL_MESSAGE.remove(player.getUuid());
            return false;
        }
        
        // Check cooldown to prevent spam
        Long lastMessage = LAST_INVENTORY_FULL_MESSAGE.get(player.getUuid());
        long now = System.currentTimeMillis();
        boolean shouldSendMessage = (lastMessage == null) || ((now - lastMessage) >= INVENTORY_MESSAGE_COOLDOWN_MS);
        
        if (shouldPause) {
            if (shouldSendMessage) {
                SkillResumeService.flagManualResume(player);
                ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                        "Inventory full — pausing mining. Clear space and run /bot resume " + player.getName().getString() + ".");
                BotActions.stop(player);
                LAST_INVENTORY_FULL_MESSAGE.put(player.getUuid(), now);
            }
            return true;
        } else {
            // Continuing despite full inventory - send message if cooldown allows
            if (shouldSendMessage) {
                ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                        "Inventory's full! Continuing…");
                LAST_INVENTORY_FULL_MESSAGE.put(player.getUuid(), now);
            }
            return false;
        }
    }

    private boolean shouldForcePauseForFullInventory(ServerPlayerEntity player, ServerCommandSource source) {
        if (player == null || source == null) {
            return false;
        }
        if (!isInventoryFull(player)) {
            return false;
        }
        SkillResumeService.flagManualResume(player);
        ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                "Inventory full — pausing mining. Clear space and run /bot resume " + player.getName().getString() + ".");
        BotActions.stop(player);
        return true;
    }

    private boolean attemptChestStore(ServerPlayerEntity player, ServerCommandSource source) {
        if (player == null || source == null) {
            return false;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        List<BlockPos> chests = findNearbyChests(world, player.getBlockPos(), 12, 6);
        for (BlockPos chestPos : chests) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, player, chestPos, this::isChestOffloadCandidate);
            if (moved > 0) {
                return true;
            }
        }
        BlockPos placed = ChestStoreService.placeChestNearBot(source, player, false);
        if (placed != null) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, player, placed, this::isChestOffloadCandidate);
            return moved > 0;
        }
        return false;
    }

    private boolean isChestOffloadCandidate(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (ChestStoreService.isOffloadProtected(stack)) {
            return false;
        }
        if (stack.isOf(Items.CHEST) || stack.isOf(Items.CRAFTING_TABLE)) {
            return false;
        }
        return stack.getMaxCount() > 1;
    }

    private List<BlockPos> findNearbyChests(ServerWorld world, BlockPos origin, int radius, int vertical) {
        if (world == null || origin == null) {
            return List.of();
        }
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -vertical, -radius), origin.add(radius, vertical, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                found.add(pos.toImmutable());
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return found;
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
        if (world == null || pos == null) {
            return false;
        }
        var fluid = world.getFluidState(pos);
        if (fluid.isEmpty()) {
            return false;
        }
        if (fluid.isIn(net.minecraft.registry.tag.FluidTags.LAVA)) {
            return true;
        }
        // Modded worlds sometimes use lava-like fluids that are not tagged as LAVA.
        // Fall back to a conservative registry-id heuristic.
        Identifier id = Registries.FLUID.getId(fluid.getFluid());
        if (id == null) {
            return false;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("lava") || path.contains("molten");
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
