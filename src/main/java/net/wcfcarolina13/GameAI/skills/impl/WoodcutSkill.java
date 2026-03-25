package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.wcfcarolina13.GameAI.services.CompanionOverheadHologramService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.CraftingHelper;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.services.BotFleeService;
import net.wcfcarolina13.GameAI.services.ToolProvisionService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.MappedVillageService;
import net.wcfcarolina13.GameAI.services.MappedVillageNavigationService;
import net.wcfcarolina13.GameAI.services.ReturnBaseStuckService;
import net.wcfcarolina13.GameAI.services.SkillResumeService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.GameAI.services.WoodcutCleanupMemoryService;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import net.wcfcarolina13.PathFinding.GoTo;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fell natural trees while avoiding player-built structures. Uses an axe for logs and
 * switches to non-axes for leaves when clearing access. Crafts an axe if materials exist.
 */
public final class WoodcutSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-woodcut");
    private static final int DEFAULT_TREE_COUNT_INTERNAL = 1;
    private static final int DEFAULT_TREE_COUNT_STANDALONE = 4;
    private static final int DEFAULT_SEARCH_RADIUS = 12;
    private static final int DEFAULT_VERTICAL_RANGE = 6;
    private static final int MAX_CONSECUTIVE_FAILURES = 6;
    private static final int SUNSET_TIME_OF_DAY = 12000;
    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks (survival reach)
    private static final long PILLAR_STEP_DELAY_MS = 100L;
    private static final long MINING_TIMEOUT_MS = 12_000L;
    private static final int MAX_RETRY_MINING = 5;
    private static final int MAX_LOS_CLEAR_ATTEMPTS = 3;
    private static final int MAX_LEAF_CLEAR_BLOCKS_PER_TARGET = 24;
    private static final int MAX_LEAF_CLEAR_BLOCKS_PER_ATTEMPT = 8;
    private static final int MAX_TRUNK_LOS_RECOVERY_ATTEMPTS = 2;
    private static final int MAX_IDENTICAL_REPOSITION_FAILURES = 2;
    private static final int MAX_MINOR_TERRAIN_CORRECTIONS_PER_TARGET = 2;
    private static final int MAX_RELOCATIONS = 3;
    private static final int PROTECTED_ONLY_SCAN_STREAK_FOR_RELOCATION = 2;
    private static final int RELOCATION_SEARCH_RADIUS = 120;
    private static final int RELOCATION_VERTICAL_RANGE = 24;
    private static final int RELOCATION_NEARBY_RADIUS = 20;
    private static final int RELOCATION_NEARBY_VERTICAL_RANGE = 10;
    private static final long RELOCATION_NEARBY_TIME_BUDGET_MS = 400L;
    private static final long RELOCATION_BROAD_TIME_BUDGET_MS = 1_500L;
    private static final long RELOCATION_SLOW_SUMMARY_THRESHOLD_MS = 3_000L;
    private static final double QUICK_TREE_SWEEP_RADIUS = 6.0D;
    private static final double QUICK_TREE_SWEEP_VERTICAL_RANGE = 4.0D;
    private static final double QUICK_TREE_SWEEP_RADIUS_SCAFFOLD = 8.0D;
    private static final double QUICK_TREE_SWEEP_VERTICAL_RANGE_SCAFFOLD = 6.0D;
    private static final int LOCAL_TREE_CLEANUP_RADIUS = 6;
    private static final int LOCAL_TREE_CLEANUP_VERTICAL_RANGE = 8;
    private static final long LOCAL_TREE_CLEANUP_DURATION_MS = 6_000L;
    private static final int LOCAL_TREE_CLEANUP_MAX_LOGS = 16;
    private static final int LOCAL_TREE_CLEANUP_MAX_SCAFFOLD = 24;
    private static final int BLIND_WALK_DISTANCE = 80;
    private static final int MAPPED_VILLAGE_EXIT_CLEARANCE = 12;
    private static final MovementService.MovementOptions WOODCUT_MOVEMENT_OPTIONS =
            new MovementService.MovementOptions(false, 0, false, 0);
    private static final List<Item> PILLAR_BLOCKS = List.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.SCAFFOLDING,
            Items.GRAVEL,
            Items.SAND,
            Items.RED_SAND,
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.NETHERRACK
    );

    // Ephemeral memory (persists across woodcut/woodcut_cleanup until another skill starts).
    private static final String WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY = "woodcut.scaffoldMemory.positions";
    private static final String WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY = "woodcut.scaffoldMemory.dimension";
    private static final String WOODCUT_SCAFFOLD_MEMORY_UPDATED_AT_KEY = "woodcut.scaffoldMemory.updatedAt";
    private static final int WOODCUT_SCAFFOLD_MEMORY_MAX = 2048;
    private static final List<Item> SAPLING_ITEMS = List.of(
            Items.OAK_SAPLING,
            Items.SPRUCE_SAPLING,
            Items.BIRCH_SAPLING,
            Items.JUNGLE_SAPLING,
            Items.ACACIA_SAPLING,
            Items.DARK_OAK_SAPLING,
            Items.MANGROVE_PROPAGULE,
            Items.CHERRY_SAPLING,
            Items.BAMBOO
    );
    private static final List<Item> SEED_ITEMS = List.of(
            Items.WHEAT_SEEDS,
            Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.TORCHFLOWER_SEEDS,
            Items.PITCHER_POD
    );
    private static final List<Item> RAW_FOOD_ITEMS = List.of(
            Items.BEEF,
            Items.PORKCHOP,
            Items.CHICKEN,
            Items.MUTTON,
            Items.RABBIT,
            Items.COD,
            Items.SALMON,
            Items.TROPICAL_FISH,
            Items.PUFFERFISH
    );

    private record WoodcutDetectionSnapshot(
            Optional<TreeDetector.TreeTarget> nearestTree,
            BlockPos floatingLog,
            BlockPos looseLog,
            BlockPos anyLog,
            int totalLogs,
            int visitedLogs,
            int protectedLogs,
            int humanAdjacentLogs,
            int soilFail,
            int leafFail,
            Map<String, Integer> protectedReasonCounts,
            List<String> rejectSamples,
            boolean allLocalLogsProtectedOrHuman) {
    }

    private enum WoodcutFailureReason {
        NO_TARGET,
        PROTECTED_AT_MINING,
        PATH_OR_REACH_FAILURE,
        INVENTORY_BLOCKED,
        TRUNK_NEVER_STARTED,
        PILLAR_NO_REACH,
        SCAFFOLD_CLEANUP_INCOMPLETE
    }

    private record MineAttemptResult(boolean success,
                                     WoodcutFailureReason failureReason,
                                     String detail) {
    }

    private record TreeHarvestResult(boolean success,
                                     WoodcutFailureReason failureReason,
                                     String detail) {
    }

    private static final class WoodcutReachSession {
        private final List<BlockPos> placedBlocks = new ArrayList<>();
        private final Set<Long> placedKeys = new HashSet<>();
        private int leafBlocksBroken;
        private int losClearAttempts;
        private int trunkMineAttemptsStarted;
        private int scaffoldPlaced;
        private int scaffoldRemoved;
        private int strictTreeRejects;
        private boolean cleanupIncomplete;
        private boolean usedScaffold;
        private boolean preReachLeafClearAttempted;
        private int repeatedRepositionFailures;
        private int minorTerrainCorrections;
        private String lastRepositionFailureSignature;

        private void recordPlacement(BlockPos pos) {
            if (pos == null) {
                return;
            }
            long key = pos.asLong();
            if (placedKeys.add(key)) {
                placedBlocks.add(pos.toImmutable());
                scaffoldPlaced++;
                usedScaffold = true;
            }
        }

        private void recordRemoval(BlockPos pos) {
            if (pos == null) {
                return;
            }
            if (placedKeys.remove(pos.asLong())) {
                scaffoldRemoved++;
            }
        }

        private List<BlockPos> placementsDescending() {
            List<BlockPos> snapshot = new ArrayList<>(placedBlocks);
            Collections.reverse(snapshot);
            return snapshot;
        }

        private boolean hasPlacements() {
            return !placedKeys.isEmpty();
        }

        private void recordRepositionFailure(BlockPos target, BlockPos approach) {
            String signature = (target == null ? "null" : target.toShortString())
                    + "->"
                    + (approach == null ? "null" : approach.toShortString());
            if (signature.equals(lastRepositionFailureSignature)) {
                repeatedRepositionFailures++;
            } else {
                lastRepositionFailureSignature = signature;
                repeatedRepositionFailures = 1;
            }
        }

        private void clearRepositionFailureTracking() {
            lastRepositionFailureSignature = null;
            repeatedRepositionFailures = 0;
        }
    }

    @Override
    public String name() {
        return "woodcut";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = Objects.requireNonNull(source.getPlayer(), "player");
        SkillResumeService.consumeResumeIntent(bot.getUuid());

        Map<String, Object> sharedState = context.sharedState();

        boolean internal = getBooleanParameter(context.parameters(), "internal", false);
        boolean replantSaplings = getBooleanParameter(context.parameters(), "replantSaplings", !internal);
        boolean isHuntPrerequisite = "hunt_prerequisite".equals(context.parameters().get("_origin"));
        boolean openEnded = isOpenEnded(context.parameters());
        int targetTrees;
        if (openEnded) {
            targetTrees = Integer.MAX_VALUE;
        } else {
            int defaultTrees = internal ? DEFAULT_TREE_COUNT_INTERNAL : DEFAULT_TREE_COUNT_STANDALONE;
            targetTrees = Math.max(1, getIntParameter(context.parameters(), "count", defaultTrees));
        }
        int searchRadius = Math.max(6, getIntParameter(context.parameters(), "searchRadius", DEFAULT_SEARCH_RADIUS));
        int verticalRange = Math.max(4, getIntParameter(context.parameters(), "verticalRange", DEFAULT_VERTICAL_RANGE));
        int startTimeOfDay = bot.getEntityWorld() != null ? (int) (bot.getEntityWorld().getTimeOfDay() % 24000L) : 0;
        if (!internal && startTimeOfDay >= SUNSET_TIME_OF_DAY) {
            return SkillExecutionResult.failure("It's getting late; I'll cut trees tomorrow.");
        }

        if (bot.getEntityWorld() instanceof ServerWorld world && !BotFleeService.ensureAtSurface(bot, world)) {
            // ensureAtSurface failed — try a simple 1-block reposition before giving up.
            // The bot may be standing on an irregular block (slab, half-step) at its exact
            // feet position but have a perfectly valid neighbor within 1-2 blocks.
            BlockPos repositioned = SafePositionService.findSafeColumn(world, bot.getBlockPos(), -2, 2);
            if (repositioned != null && !repositioned.equals(bot.getBlockPos())) {
                net.wcfcarolina13.GameAI.services.MovementService.nudgeTowardUntilClose(
                        bot, repositioned, 1.5D, 3_000L, 0.20D, "woodcut-reposition");
            }
            if (!BotFleeService.isAtSurface(bot, world)) {
                // Still sweep drops before giving up.
                try {
                    DropSweeper.safeSweep(bot, source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), 8.0D, 4.0D);
                } catch (Exception ignored) {
                }
                return SkillExecutionResult.failure("I couldn't reach the surface to cut trees.");
            }
        }

        prepareWoodcutTooling(source, bot);

        Set<BlockPos> visitedBases = new HashSet<>();
        Set<BlockPos> failedBases = new HashSet<>();
        Map<BlockPos, String> failedBaseReasons = new HashMap<>();
        Set<BlockPos> pendingFloaters = new HashSet<>();
        int felled = 0;
        int consecutiveFailures = 0;
        int totalFailures = 0;
        int sinceCleanup = 0;
        int relocations = 0;
        int protectedOnlyScanStreak = 0;
        int selectedTargets = 0;
        int miningProtectedFailures = 0;
        int pathFailures = 0;
        int trunkMineStarts = 0;
        int leafBlocksBroken = 0;
        int scaffoldPlaced = 0;
        int scaffoldRemoved = 0;
        int strictTreeRejects = 0;
        boolean abortRequested = false;
        WoodcutFailureReason lastFailureReason = WoodcutFailureReason.NO_TARGET;
        BlockPos startPos = bot.getBlockPos();
        int minX = startPos.getX();
        int maxX = startPos.getX();
        int minY = startPos.getY();
        int maxY = startPos.getY();
        int minZ = startPos.getZ();
        int maxZ = startPos.getZ();

        SkillExecutionResult finalResult;
        try {
            while (felled < targetTrees) {
                if (isAbortRequested(bot)) {
                    abortRequested = true;
                    break;
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    if (relocations >= MAX_RELOCATIONS) {
                        break;
                    }
                    relocations++;
                    if (relocateToUnprotectedTree(source, bot, effectiveSearchRadius(bot, searchRadius), failedBases, failedBaseReasons)) {
                        consecutiveFailures = 0;
                        visitedBases.clear();
                        LOGGER.info("Woodcut: relocated ({}/{}), resuming search from {}",
                                relocations, MAX_RELOCATIONS, bot.getBlockPos().toShortString());
                        continue;
                    }
                    // Relocation failed — but don't break; let the loop try again
                    // with the next iteration (bot may have moved partway)
                    consecutiveFailures = 0;
                    visitedBases.clear();
                    LOGGER.info("Woodcut: relocation {}/{} failed, retrying from {}",
                            relocations, MAX_RELOCATIONS, bot.getBlockPos().toShortString());
                    continue;
                }
                if (!internal) {
                    int timeOfDay = (int) (bot.getEntityWorld().getTimeOfDay() % 24000L);
                    if (timeOfDay >= SUNSET_TIME_OF_DAY) {
                        ChatUtils.sendSystemMessage(source, "It's getting late; I'm stopping woodcut for the day.");
                        break;
                    }
                }

                if (!ensureWoodSpaceOrDeposit(source, bot, isHuntPrerequisite)) {
                    totalFailures++;
                    consecutiveFailures++;
                    lastFailureReason = WoodcutFailureReason.INVENTORY_BLOCKED;
                    ChatUtils.sendSystemMessage(source, "Inventory is full and I couldn't store items; stopping woodcut.");
                    break;
                }

                int effectiveSearchRadius = effectiveSearchRadius(bot, searchRadius);
                CompanionOverheadHologramService.show(bot, "Scanning for trees...", 5_000);
                long detectionStartedAt = System.nanoTime();
                WoodcutDetectionSnapshot detection = scanDetectionSnapshot(bot, effectiveSearchRadius, verticalRange, visitedBases, failedBases, failedBaseReasons);
                long detectionDurationMs = (System.nanoTime() - detectionStartedAt) / 1_000_000L;
                logDetectionSnapshot(detection, detectionDurationMs);
                strictTreeRejects += detection.rejectSamples().stream().filter(sample -> sample.contains("strictStandingReject=true")).count();
                Optional<TreeDetector.TreeTarget> targetOpt = detection.nearestTree();
                if (targetOpt.isEmpty()) {
                    if (detection.floatingLog() != null) {
                        LOGGER.warn("Woodcut: cleaning floating log at {}", detection.floatingLog().toShortString());
                        TreeDetector.TreeTarget synthetic = new TreeDetector.TreeTarget(detection.floatingLog(), detection.floatingLog(), 1);
                        targetOpt = Optional.of(synthetic);
                    } else {
                        if (detection.allLocalLogsProtectedOrHuman()) {
                            protectedOnlyScanStreak++;
                            LOGGER.info("Woodcut: protected-only local scan streak {}/{} at {}",
                                    protectedOnlyScanStreak,
                                    PROTECTED_ONLY_SCAN_STREAK_FOR_RELOCATION,
                                    bot.getBlockPos().toShortString());
                            if (protectedOnlyScanStreak >= PROTECTED_ONLY_SCAN_STREAK_FOR_RELOCATION) {
                                LOGGER.info("Woodcut: protected-only scans triggered staged relocation after {}ms local-scan time",
                                        detectionDurationMs);
                                consecutiveFailures = MAX_CONSECUTIVE_FAILURES;
                                continue;
                            }
                        } else {
                            protectedOnlyScanStreak = 0;
                        }
                        if (detection.totalLogs() <= 0) {
                            LOGGER.warn("Woodcut: found no detectable trees/logs within {}x{}", effectiveSearchRadius, verticalRange);
                            consecutiveFailures = MAX_CONSECUTIVE_FAILURES;
                            continue;
                        }
                        consecutiveFailures++;
                        continue;
                    }
                }

                TreeDetector.TreeTarget target = targetOpt.get();
                protectedOnlyScanStreak = 0;
                if (failedBases.contains(target.base())) {
                    LOGGER.info("Woodcut: skipping previously failed base {} (reason={})",
                            target.base().toShortString(),
                            failedBaseReasons.getOrDefault(target.base(), "previous-failure"));
                    totalFailures++;
                    consecutiveFailures++;
                    lastFailureReason = WoodcutFailureReason.PATH_OR_REACH_FAILURE;
                    continue;
                }
                LOGGER.info("Woodcut: selected tree target base={} top={} height={}",
                        target.base().toShortString(), target.top().toShortString(), target.height());
                selectedTargets++;
                if (bot.getEntityWorld() instanceof ServerWorld targetWorld) {
                    TreeDetector.WoodcutProtectionDecision detectionProtection =
                            TreeDetector.getWoodcutProtectionDecision(targetWorld, target.base(), Math.max(4, target.height()));
                    TreeDetector.WoodcutProtectionDecision miningProtection =
                            getWoodcutMutationDecision(targetWorld, target.base(), target.base());
                    LOGGER.info("Woodcut target permission: base={} detectionReason={} miningReason={}",
                            target.base().toShortString(),
                            detectionProtection.reason(),
                            miningProtection.reason());
                    if (!Objects.equals(detectionProtection.reason(), miningProtection.reason())
                            || detectionProtection.blocked() != miningProtection.blocked()) {
                        LOGGER.warn("Woodcut protection mismatch for {}: detectionBlocked={} detectionReason={} miningBlocked={} miningReason={}",
                                target.base().toShortString(),
                                detectionProtection.blocked(),
                                detectionProtection.reason(),
                                miningProtection.blocked(),
                                miningProtection.reason());
                    }
                    if (detectionProtection.blocked()) {
                        LOGGER.info("Woodcut: rejecting protected target at {} (reason={})",
                                target.base().toShortString(), detectionProtection.reason());
                        totalFailures++;
                        consecutiveFailures++;
                        lastFailureReason = WoodcutFailureReason.PROTECTED_AT_MINING;
                        continue;
                    }
                }
                visitedBases.add(target.base());
                WoodcutReachSession reachSession = new WoodcutReachSession();

                // Track footprint to size post-run drop sweep.
                BlockPos posNow = bot.getBlockPos();
                minX = Math.min(minX, posNow.getX());
                maxX = Math.max(maxX, posNow.getX());
                minY = Math.min(minY, posNow.getY());
                maxY = Math.max(maxY, posNow.getY());
                minZ = Math.min(minZ, posNow.getZ());
                maxZ = Math.max(maxZ, posNow.getZ());

                if (!approachBase(source, bot, target.base(), sharedState, reachSession)) {
                    totalFailures++;
                    consecutiveFailures++;
                    pathFailures++;
                    failedBases.add(target.base().toImmutable());
                    failedBaseReasons.put(target.base().toImmutable(), "approach-failed");
                    trunkMineStarts += reachSession.trunkMineAttemptsStarted;
                    leafBlocksBroken += reachSession.leafBlocksBroken;
                    scaffoldPlaced += reachSession.scaffoldPlaced;
                    scaffoldRemoved += reachSession.scaffoldRemoved;
                    lastFailureReason = WoodcutFailureReason.PATH_OR_REACH_FAILURE;
                    logTargetTrace(target, reachSession, "APPROACH_FAILED");
                    continue;
                }
                TreeHarvestResult harvest = fellTree(source, bot, target, sharedState, replantSaplings, pendingFloaters, reachSession);
                trunkMineStarts += reachSession.trunkMineAttemptsStarted;
                leafBlocksBroken += reachSession.leafBlocksBroken;
                scaffoldPlaced += reachSession.scaffoldPlaced;
                scaffoldRemoved += reachSession.scaffoldRemoved;
                if (!harvest.success()) {
                    totalFailures++;
                    consecutiveFailures++;
                    failedBases.add(target.base().toImmutable());
                    failedBaseReasons.put(target.base().toImmutable(), harvest.detail());
                    if (harvest.failureReason() == WoodcutFailureReason.PROTECTED_AT_MINING) {
                        miningProtectedFailures++;
                    } else if (harvest.failureReason() == WoodcutFailureReason.PATH_OR_REACH_FAILURE) {
                        pathFailures++;
                        // Recover to surface before trying the next tree — bot likely fell
                        // into a hole during the failed harvest attempt.
                        if (bot.getEntityWorld() instanceof ServerWorld recoveryWorld) {
                            int groundY = SafePositionService.getWalkableGroundY(
                                    recoveryWorld, bot.getBlockX(), bot.getBlockZ());
                            if (groundY - bot.getBlockY() >= 2) {
                                recoverSurfacePosition(bot, recoveryWorld, source, groundY,
                                        reachSession, sharedState, "inter-tree-recovery");
                            }
                        }
                        // Sweep drops from partial harvest if the bot mined anything.
                        if (reachSession.trunkMineAttemptsStarted > 0 && !isInventoryFull(bot)) {
                            runQuickPerTreeDropSweep(bot, source, reachSession);
                        }
                    }
                    lastFailureReason = harvest.failureReason();
                    logTargetTrace(target, reachSession, harvest.failureReason().name());
                    continue;
                }
                logTargetTrace(target, reachSession, "SUCCESS");

                felled++;
                consecutiveFailures = 0;
                sinceCleanup++;
                if (openEnded) {
                    ChatUtils.sendSystemMessage(source, "Tree cut (" + felled + ")");
                } else {
                    ChatUtils.sendSystemMessage(source, "Tree cut (" + felled + "/" + targetTrees + ")");
                }
                runPerTreeMaintenance(context, source, bot, target, reachSession, pendingFloaters);

                if (openEnded && sinceCleanup >= 5) {
                    runWoodcutCleanup(context, source, bot, startPos, minX, maxX, minY, maxY, minZ, maxZ,
                            Math.max(searchRadius + 6, 16), Math.max(verticalRange + 8, 12),
                            false);
                    sinceCleanup = 0;
                }
            }

            // Second-pass cleanup: attempt unreachable logs from partial harvests from a fresh position
            if (!pendingFloaters.isEmpty() && !abortRequested && bot.getEntityWorld() instanceof ServerWorld floaterWorld) {
                LOGGER.info("Woodcut: attempting cleanup of {} floater log(s) from partial harvests", pendingFloaters.size());
                for (BlockPos floater : new HashSet<>(pendingFloaters)) {
                    if (!floaterWorld.getBlockState(floater).isIn(BlockTags.LOGS)) {
                        forgetCleanupFloater(bot, sharedState, floaterWorld, floater);
                        pendingFloaters.remove(floater);
                        continue;
                    }
                    if (isAbortRequested(bot)) break;
                    WoodcutReachSession floaterSession = new WoodcutReachSession();
                    // Approach from below — different angle than original attempt
                    MovementService.planLootApproach(bot, floater.down(), WOODCUT_MOVEMENT_OPTIONS)
                            .ifPresent(plan -> MovementService.execute(source, bot, plan,
                                    false, true, true, false));
                    MineAttemptResult floaterResult = mineWithRetries(bot, source, floater, floaterSession, false, sharedState, floater);
                    if (floaterResult.success() || !floaterWorld.getBlockState(floater).isIn(BlockTags.LOGS)) {
                        forgetCleanupFloater(bot, sharedState, floaterWorld, floater);
                        pendingFloaters.remove(floater);
                    }
                    cleanupReachSession(source, bot, floater, floaterSession, sharedState);
                }
            }

            if (abortRequested) {
                finalResult = SkillExecutionResult.failure("woodcut paused due to nearby threat.");
            } else if (felled == 0 && selectedTargets > 0) {
                finalResult = switch (lastFailureReason) {
                    case PROTECTED_AT_MINING -> SkillExecutionResult.failure("Found a tree, but couldn’t cut it; protection checks blocked the trunk.");
                    case PATH_OR_REACH_FAILURE -> SkillExecutionResult.failure("Found a tree, but couldn’t finish cutting it after repeated reach/path failures.");
                    case INVENTORY_BLOCKED -> SkillExecutionResult.failure("Inventory is full and I couldn't store items; stopping woodcut.");
                    case TRUNK_NEVER_STARTED -> SkillExecutionResult.failure("Found a tree, but never reached a valid trunk-hit state.");
                    case PILLAR_NO_REACH -> SkillExecutionResult.failure("Found a tree, but pillar reach still never reached the trunk.");
                    case SCAFFOLD_CLEANUP_INCOMPLETE -> SkillExecutionResult.failure("Stopped after reaching the tree, but scaffold cleanup could not finish cleanly.");
                    case NO_TARGET -> SkillExecutionResult.failure("No valid trees nearby. Try moving closer or adjust radius.");
                };
            } else if (felled == 0) {
                finalResult = SkillExecutionResult.failure("No valid trees nearby. Try moving closer or adjust radius.");
            } else if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                finalResult = SkillExecutionResult.failure("Stopped after cutting " + felled + " tree(s); repeated failures reaching remaining targets (path/LOS/inventory).");
            } else if (felled < targetTrees) {
                finalResult = SkillExecutionResult.success("Stopped after cutting " + felled + " tree(s).");
            } else {
                finalResult = SkillExecutionResult.success("Cut " + felled + " tree(s).");
            }
        } finally {
            // Best-effort: if we did any work, attempt cleanup (when not aborted) and always perform a
            // wide drop sweep at the end so items don't get left on the ground even after termination.
        if ((felled > 0 || !visitedBases.isEmpty() || totalFailures > 0)
                && !TaskService.isServerStopping()
                && !isAbortRequested(bot)) {
            try {
                ensureWoodSpaceOrDeposit(source, bot, isHuntPrerequisite);
            } catch (Exception ignored) {
            }

                double horizRadius = Math.max(6.0,
                        Math.max(Math.abs(maxX - startPos.getX()), Math.abs(minX - startPos.getX())) + 3.0);
                double vertRange = Math.max(4.0, (maxY - minY) + 3.0);

                if (!abortRequested) {
                    // Cleanup pass first (break floating logs/scaffolds).
                    runWoodcutCleanup(context, source, bot, startPos, minX, maxX, minY, maxY, minZ, maxZ,
                            (int) Math.ceil(horizRadius), (int) Math.ceil(vertRange),
                            false);
                } else {
                    LOGGER.info("Woodcut: abort requested; skipping cleanup pass and performing final sweep.");
                }

                // Always attempt a wide drop sweep at the end (even if the run was terminated). Still
                // avoid sweeping when the inventory is full.
                if (!isInventoryFull(bot)) {
                    try {
                        DropSweeper.safeSweep(bot, source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), horizRadius, vertRange);
                    } catch (Exception e) {
                        LOGGER.warn("Drop sweep after woodcut failed: {}", e.getMessage());
                    }
                }
            }
        }

        LOGGER.info("Woodcut end summary: selectedTargets={} miningProtectedFailures={} pathFailures={} failedBaseCount={} trunkMineStarts={} leafBlocksBroken={} scaffoldPlaced={} scaffoldRemoved={} strictTreeRejects={} finalReason={}",
                selectedTargets, miningProtectedFailures, pathFailures, failedBases.size(),
                trunkMineStarts, leafBlocksBroken, scaffoldPlaced, scaffoldRemoved, strictTreeRejects, lastFailureReason);

        return finalResult;
    }

    private void recordScaffoldPlacement(Map<String, Object> sharedState, ServerWorld world, BlockPos pos) {
        if (sharedState == null || world == null || pos == null) {
            return;
        }
        String dimension = world.getRegistryKey().getValue().toString();
        try {
            Object rawDim = sharedState.get(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY);
            if (rawDim instanceof String s && !s.equals(dimension)) {
                sharedState.remove(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
            }
            sharedState.put(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY, dimension);
            sharedState.put(WOODCUT_SCAFFOLD_MEMORY_UPDATED_AT_KEY, System.currentTimeMillis());

            Object raw = sharedState.get(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
            @SuppressWarnings("unchecked")
            Set<Long> set = raw instanceof Set<?> existing ? (Set<Long>) existing : null;
            if (set == null) {
                set = new HashSet<>();
                sharedState.put(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY, set);
            }
            set.add(pos.asLong());
            if (set.size() > WOODCUT_SCAFFOLD_MEMORY_MAX) {
                int toRemove = set.size() - WOODCUT_SCAFFOLD_MEMORY_MAX;
                var it = set.iterator();
                while (toRemove > 0 && it.hasNext()) {
                    it.next();
                    it.remove();
                    toRemove--;
                }
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Set<Long> getScaffoldMemory(Map<String, Object> sharedState, ServerWorld world, boolean create) {
        if (sharedState == null || world == null) {
            return null;
        }
        String dimension = world.getRegistryKey().getValue().toString();
        Object rawDim = sharedState.get(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY);
        if (rawDim instanceof String stored && !stored.equals(dimension)) {
            sharedState.remove(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
        }
        sharedState.put(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY, dimension);

        Object raw = sharedState.get(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
        if (raw instanceof Set<?> existing) {
            try {
                return (Set<Long>) existing;
            } catch (ClassCastException ignored) {
                sharedState.remove(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
            }
        }
        if (!create) {
            return null;
        }
        Set<Long> created = new HashSet<>();
        sharedState.put(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY, created);
        return created;
    }

    private void forgetScaffoldPlacement(Map<String, Object> sharedState, ServerWorld world, BlockPos pos) {
        if (sharedState == null || world == null || pos == null) {
            return;
        }
        Set<Long> memory = getScaffoldMemory(sharedState, world, false);
        if (memory != null) {
            memory.remove(pos.asLong());
        }
    }

    private void recordCleanupFloater(ServerPlayerEntity bot, Map<String, Object> sharedState, ServerWorld world, BlockPos pos) {
        if (bot == null || sharedState == null || world == null || pos == null) {
            return;
        }
        WoodcutCleanupMemoryService.remember(sharedState, bot.getUuid(), world, pos);
    }

    private void forgetCleanupFloater(ServerPlayerEntity bot, Map<String, Object> sharedState, ServerWorld world, BlockPos pos) {
        if (bot == null || sharedState == null || world == null || pos == null) {
            return;
        }
        WoodcutCleanupMemoryService.forget(sharedState, bot.getUuid(), world, pos);
    }

    private void runWoodcutCleanup(SkillContext context,
                                  ServerCommandSource source,
                                  ServerPlayerEntity bot,
                                  BlockPos startPos,
                                  int minX,
                                  int maxX,
                                  int minY,
                                  int maxY,
                                  int minZ,
                                  int maxZ,
                                  int radius,
                                  int verticalRange,
                                  boolean sweepDrops) {
        if (bot == null || source == null) {
            return;
        }
        if (isAbortRequested(bot)) {
            return;
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("radius", Math.max(8, radius));
            params.put("verticalRange", Math.max(6, verticalRange));
            params.put("maxLogs", 64);
            params.put("maxScaffold", 96);
            params.put("durationMs", 35_000L);
            params.put("sweep", sweepDrops);
            params.put("scaffold", true);

            // Provide a tighter region for the cleanup scan.
            params.put("minX", minX);
            params.put("maxX", maxX);
            params.put("minY", minY);
            params.put("maxY", maxY);
            params.put("minZ", minZ);
            params.put("maxZ", maxZ);

            // Keep internal child call from changing behavior like sunset gating.
            params.put("internal", true);

            WoodcutCleanupSkill cleanup = new WoodcutCleanupSkill();
            SkillContext cleanupCtx = new SkillContext(source, context.sharedState(), params, context.requestSource());
            var res = cleanup.execute(cleanupCtx);
            LOGGER.info("Woodcut cleanup pass result: success={} message='{}'", res.success(), res.message());
        } catch (Exception e) {
            LOGGER.warn("Woodcut cleanup pass failed: {}", e.getMessage());
        }
    }

    private void runPerTreeMaintenance(SkillContext context,
                                       ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       TreeDetector.TreeTarget target,
                                       WoodcutReachSession reachSession,
                                       Set<BlockPos> pendingFloaters) {
        if (context == null || source == null || bot == null || target == null || isAbortRequested(bot)) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (shouldRunLocalTreeCleanup(bot, world, target.base(), reachSession, pendingFloaters, context.sharedState())) {
            runLocalTreeCleanup(context, source, bot, target.base());
        }
        if (!isInventoryFull(bot)) {
            runQuickPerTreeDropSweep(bot, source, reachSession);
        }
    }

    private boolean shouldRunLocalTreeCleanup(ServerPlayerEntity bot,
                                              ServerWorld world,
                                              BlockPos base,
                                              WoodcutReachSession reachSession,
                                              Set<BlockPos> pendingFloaters,
                                              Map<String, Object> sharedState) {
        if (bot == null || world == null || base == null) {
            return false;
        }
        if (reachSession != null && (reachSession.cleanupIncomplete
                || reachSession.scaffoldPlaced > reachSession.scaffoldRemoved
                || reachSession.usedScaffold)) {
            return true;
        }
        if (pendingFloaters != null) {
            for (BlockPos floater : pendingFloaters) {
                if (floater != null
                        && Math.abs(floater.getX() - base.getX()) <= LOCAL_TREE_CLEANUP_RADIUS
                        && Math.abs(floater.getY() - base.getY()) <= LOCAL_TREE_CLEANUP_VERTICAL_RANGE
                        && Math.abs(floater.getZ() - base.getZ()) <= LOCAL_TREE_CLEANUP_RADIUS) {
                    return true;
                }
            }
        }
        Set<Long> memory = getScaffoldMemory(sharedState, world, false);
        if (memory != null) {
            for (Long packed : memory) {
                if (packed == null) {
                    continue;
                }
                BlockPos pos = BlockPos.fromLong(packed);
                if (Math.abs(pos.getX() - base.getX()) <= LOCAL_TREE_CLEANUP_RADIUS
                        && Math.abs(pos.getY() - base.getY()) <= LOCAL_TREE_CLEANUP_VERTICAL_RANGE
                        && Math.abs(pos.getZ() - base.getZ()) <= LOCAL_TREE_CLEANUP_RADIUS) {
                    return true;
                }
            }
        }
        return false;
    }

    private void runLocalTreeCleanup(SkillContext context,
                                     ServerCommandSource source,
                                     ServerPlayerEntity bot,
                                     BlockPos base) {
        if (context == null || source == null || bot == null || base == null || isAbortRequested(bot)) {
            return;
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("radius", LOCAL_TREE_CLEANUP_RADIUS);
            params.put("verticalRange", LOCAL_TREE_CLEANUP_VERTICAL_RANGE);
            params.put("maxLogs", LOCAL_TREE_CLEANUP_MAX_LOGS);
            params.put("maxScaffold", LOCAL_TREE_CLEANUP_MAX_SCAFFOLD);
            params.put("durationMs", LOCAL_TREE_CLEANUP_DURATION_MS);
            params.put("sweep", false);
            params.put("scaffold", true);
            params.put("minX", base.getX() - LOCAL_TREE_CLEANUP_RADIUS);
            params.put("maxX", base.getX() + LOCAL_TREE_CLEANUP_RADIUS);
            params.put("minY", base.getY() - 3);
            params.put("maxY", base.getY() + LOCAL_TREE_CLEANUP_VERTICAL_RANGE);
            params.put("minZ", base.getZ() - LOCAL_TREE_CLEANUP_RADIUS);
            params.put("maxZ", base.getZ() + LOCAL_TREE_CLEANUP_RADIUS);
            params.put("internal", true);
            WoodcutCleanupSkill cleanup = new WoodcutCleanupSkill();
            SkillContext cleanupCtx = new SkillContext(source, context.sharedState(), params, context.requestSource());
            var res = cleanup.execute(cleanupCtx);
            LOGGER.info("Woodcut local cleanup result for {}: success={} message='{}'",
                    base.toShortString(), res.success(), res.message());
        } catch (Exception e) {
            LOGGER.warn("Woodcut local cleanup failed near {}: {}", base.toShortString(), e.getMessage());
        }
    }

    private void runQuickPerTreeDropSweep(ServerPlayerEntity bot,
                                          ServerCommandSource source,
                                          WoodcutReachSession reachSession) {
        if (bot == null || source == null || isInventoryFull(bot) || TaskService.isServerStopping() || isAbortRequested(bot)) {
            return;
        }
        double radius = QUICK_TREE_SWEEP_RADIUS;
        double verticalRange = QUICK_TREE_SWEEP_VERTICAL_RANGE;
        if (reachSession != null && (reachSession.usedScaffold || reachSession.cleanupIncomplete)) {
            radius = QUICK_TREE_SWEEP_RADIUS_SCAFFOLD;
            verticalRange = QUICK_TREE_SWEEP_VERTICAL_RANGE_SCAFFOLD;
        }
        try {
            DropSweeper.safeSweep(bot, source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), radius, verticalRange);
        } catch (Exception sweepError) {
            LOGGER.warn("Woodcut per-tree drop sweep failed: {}", sweepError.getMessage());
        }
    }

    private boolean isOpenEnded(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        if (getBooleanParameter(params, "open_ended", false)) {
            return true;
        }
        Object opts = params.get("options");
        if (opts instanceof Iterable<?> iterable) {
            for (Object o : iterable) {
                if (o instanceof String str) {
                    if ("until_sunset".equalsIgnoreCase(str)
                            || "sunset".equalsIgnoreCase(str)
                            || "open_ended".equalsIgnoreCase(str)
                            || "open-ended".equalsIgnoreCase(str)) {
                        return true;
                    }
                }
            }
        }
        Object origin = params.get("_origin");
        if (origin instanceof String s && "ambient".equalsIgnoreCase(s)) {
            return true;
        }
        return false;
    }

    private boolean getBooleanParameter(Map<String, Object> parameters, String key, boolean fallback) {
        if (parameters == null || key == null) {
            return fallback;
        }
        Object value = parameters.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        }
        return fallback;
    }

    private TreeHarvestResult fellTree(ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       TreeDetector.TreeTarget target,
                                       Map<String, Object> sharedState,
                                       boolean replantSaplings,
                                       Set<BlockPos> pendingFloaters,
                                       WoodcutReachSession reachSession) {
        if (!(source.getWorld() instanceof ServerWorld world)) {
            return new TreeHarvestResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "no-world");
        }
        boolean success = false;
        int unreachable = 0;
        try {
            Set<Long> failedOwnedLogs = new HashSet<>();

            // Fell straight trunk first.
            List<BlockPos> trunk = TreeDetector.collectTrunk(world, target.base());
            for (BlockPos log : trunk) {
                MineAttemptResult mineResult = mineWithRetries(bot, source, log, reachSession, true, sharedState, target.base());
                if (!mineResult.success()) {
                    if (reachSession.trunkMineAttemptsStarted == 0) {
                        LOGGER.warn("Woodcut target {} never started trunk mining before failure", target.base().toShortString());
                        return new TreeHarvestResult(false, WoodcutFailureReason.TRUNK_NEVER_STARTED, mineResult.detail());
                    }
                    LOGGER.warn("Failed to break trunk segment {} for base {}", log.toShortString(), target.base().toShortString());
                    return new TreeHarvestResult(false, mineResult.failureReason(), mineResult.detail());
                }
            }

            // Then drain any same-tree owned logs inside the selected tree envelope.
            while (true) {
                if (isAbortRequested(bot)) {
                    return new TreeHarvestResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "abort-requested");
                }
                BlockPos next = selectNextOwnedLogTarget(world, bot, target, failedOwnedLogs);
                if (next == null) {
                    break;
                }
                MineAttemptResult cleanupResult = mineWithRetries(bot, source, next, reachSession, true, sharedState, target.base());
                if (cleanupResult.success()) {
                    failedOwnedLogs.remove(next.asLong());
                    forgetCleanupFloater(bot, sharedState, world, next);
                    unreachable = 0;
                } else {
                    LOGGER.warn("Owned log {} for base {} remained after harvest attempt", next.toShortString(), target.base().toShortString());
                    failedOwnedLogs.add(next.asLong());
                    pendingFloaters.add(next.toImmutable());
                    recordCleanupFloater(bot, sharedState, world, next);
                    unreachable++;
                    if (unreachable >= 4) {
                        LOGGER.warn("Stopping same-tree completion for base {} after {} unreachable owned logs", target.base().toShortString(), unreachable);
                        break;
                    }
                }
            }
            List<BlockPos> leftoverOwnedLogs = TreeDetector.collectOwnedTreeLogs(world, target);
            if (!leftoverOwnedLogs.isEmpty()) {
                leftoverOwnedLogs.forEach(pos -> {
                    pendingFloaters.add(pos.toImmutable());
                    recordCleanupFloater(bot, sharedState, world, pos);
                });
                LOGGER.warn("Tree {} still has {} owned log(s) after harvest: {}",
                        target.base().toShortString(),
                        leftoverOwnedLogs.size(),
                        leftoverOwnedLogs.stream().limit(4).map(BlockPos::toShortString).toList());
                return new TreeHarvestResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "owned-logs-remained");
            }
            success = true;
            return new TreeHarvestResult(true, WoodcutFailureReason.NO_TARGET, "success");
        } finally {
            if (success && replantSaplings) {
                plantSaplings(bot, source, target.base());
            }
            if (reachSession != null && reachSession.hasPlacements()) {
                if (!isAbortRequested(bot)) {
                    cleanupReachSession(source, bot, target.base(), reachSession, sharedState);
                    cleanupNearbyScaffold(bot, target.base(), sharedState);
                    cleanupNearbyScaffold(bot, bot.getBlockPos(), sharedState);
                }
            }
            if (!success && reachSession != null && reachSession.hasPlacements()) {
                LOGGER.warn("Woodcut cleanup: pillar removed after failure");
            }
        }
    }

    private BlockPos selectNextOwnedLogTarget(ServerWorld world,
                                              ServerPlayerEntity bot,
                                              TreeDetector.TreeTarget target,
                                              Set<Long> failedOwnedLogs) {
        if (world == null || bot == null || target == null) {
            return null;
        }
        return TreeDetector.collectOwnedTreeLogs(world, target).stream()
                .filter(pos -> failedOwnedLogs == null || !failedOwnedLogs.contains(pos.asLong()))
                .min(Comparator
                        .comparingInt((BlockPos pos) -> scoreOwnedLogCandidate(bot, target.base(), pos))
                        .thenComparingDouble(pos -> bot.getBlockPos().getSquaredDistance(pos)))
                .orElse(null);
    }

    private int scoreOwnedLogCandidate(ServerPlayerEntity bot, BlockPos base, BlockPos candidate) {
        if (bot == null || candidate == null) {
            return Integer.MAX_VALUE;
        }
        int score = 0;
        if (isReadyToMineTarget(bot, candidate)) {
            score -= 100;
        } else if (isWithinReach(bot, candidate)) {
            score -= 50;
        }
        score += (int) Math.round(horizontalDistance(bot.getBlockPos(), candidate) * 12.0D);
        score += Math.abs(candidate.getY() - bot.getBlockY()) * 8;
        if (base != null
                && Math.abs(candidate.getX() - base.getX()) <= 1
                && Math.abs(candidate.getZ() - base.getZ()) <= 1) {
            score -= 12;
        }
        if (candidate.getY() >= bot.getBlockY()) {
            score -= 4;
        }
        if (!hasLineOfSight(bot, Vec3d.ofCenter(candidate))) {
            score += 10;
        }
        return score;
    }

    private boolean approachBase(ServerCommandSource source,
                                 ServerPlayerEntity bot,
                                 BlockPos base,
                                 Map<String, Object> sharedState,
                                 WoodcutReachSession reachSession) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        LOGGER.info("Woodcut approach: target base={} from {}", base.toShortString(), bot.getBlockPos().toShortString());
        clearBaseObstacles(world, bot, base);
        if (isTrunkWithinReach(world, base, bot)) {
            return true;
        }
        // First try a low, human-like standable near the base.
        BlockPos nearby = findDryStandableNear(world, base, 4, 4);
        if (nearby != null) {
            LOGGER.info("Woodcut approach: direct dry stand {} for {}", nearby.toShortString(), base.toShortString());
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    nearby,
                    nearby,
                    null,
                    null,
                    bot.getHorizontalFacing());
            MovementService.MovementResult res = MovementService.execute(source, bot, plan, false, true, true, false);
            if (res.success() || isTrunkWithinReach(world, base, bot)) {
                return true;
            }
            LOGGER.info("Woodcut approach: abandoned dry stand {} for {} (reason=path failed detail={})",
                    nearby.toShortString(), base.toShortString(), res.detail());
            ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(base), ReturnBaseStuckService.StuckProfile.WOODCUT);
            MovementService.clearRecentWalkAttempt(bot.getUuid());
        }
        // Fallback to planner if simple stand failed.
        MovementService.MovementOptions options = WOODCUT_MOVEMENT_OPTIONS;
        Optional<MovementService.MovementPlan> planOpt = MovementService.planLootApproach(bot, base, options);
        if (planOpt.isPresent()) {
            MovementService.MovementPlan plan = planOpt.get();
            BlockPos approach = plan.approachDestination() != null ? plan.approachDestination() : plan.finalDestination();
            if (!isUsableWoodcutStand(world, approach)) {
                LOGGER.info("Woodcut approach: planner candidate {} for {} rejected (reason=no dry stand)",
                        approach.toShortString(), base.toShortString());
            } else {
                LOGGER.info("Woodcut approach: planner target {} for {} mode={}",
                        approach.toShortString(), base.toShortString(), plan.mode());
                MovementService.MovementResult result = MovementService.execute(source, bot, plan, false, true, true, false);
                if (result.success() || isTrunkWithinReach(world, base, bot)) {
                    return true;
                }
                LOGGER.warn("Woodcut approach: planner move to {} for {} failed (reason=path failed detail={})",
                        approach.toShortString(), base.toShortString(), result.detail());
            }
            if (tryDryRepositionAroundTrunk(source, bot, world, base, reachSession, sharedState)) {
                return true;
            }
            ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(base), ReturnBaseStuckService.StuckProfile.WOODCUT);
        } else {
            LOGGER.info("Woodcut approach: no planner path for {} (reason=no dry stand)", base.toShortString());
        }
        // Last resort: try to pillar from here to reach the trunk directly.
        if (prepareReach(bot, source, base, reachSession, sharedState)) {
            return true;
        }
        if (reachSession != null && reachSession.hasPlacements()) {
            LOGGER.warn("Woodcut approach: cleaning incomplete temporary scaffold for {}", base.toShortString());
            cleanupReachSession(source, bot, base, reachSession, sharedState);
        }
        ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(base), ReturnBaseStuckService.StuckProfile.WOODCUT);
        LOGGER.warn("Woodcut approach: no trunk reach for {} (reason=no trunk reach)", base.toShortString());
        return false;
    }

    private boolean tryDryRepositionAroundTrunk(ServerCommandSource source,
                                                ServerPlayerEntity bot,
                                                ServerWorld world,
                                                BlockPos base,
                                                WoodcutReachSession reachSession,
                                                Map<String, Object> sharedState) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 1; radius <= 2; radius++) {
            for (Direction direction : Direction.Type.HORIZONTAL) {
                candidates.add(base.offset(direction, radius).toImmutable());
                candidates.add(base.offset(direction, radius).down().toImmutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> bot.getBlockPos().getSquaredDistance(p)));
        for (BlockPos candidate : candidates) {
            BlockPos stand = findDryStandableNear(world, candidate, 1, 2);
            if (stand == null || !isUsableWoodcutStand(world, stand)) {
                continue;
            }
            LOGGER.info("Woodcut approach: retrying trunk reposition via {}", stand.toShortString());
            if (moveToStand(source, bot, world, stand, base)) {
                if (reachSession != null) {
                    reachSession.clearRepositionFailureTracking();
                }
                return true;
            }
            if (reachSession != null) {
                reachSession.recordRepositionFailure(base, stand);
                if (reachSession.repeatedRepositionFailures >= MAX_IDENTICAL_REPOSITION_FAILURES
                        && tryBoundedTerrainCorrection(bot, source, world, base, stand, reachSession, sharedState)) {
                    reachSession.clearRepositionFailureTracking();
                    return true;
                }
            }
        }
        LOGGER.info("Woodcut approach: abandoned {} (reason=no trunk reach)", base.toShortString());
        return false;
    }

    private void clearBaseObstacles(ServerWorld world, ServerPlayerEntity bot, BlockPos base) {
        int radius = 2;
        for (BlockPos pos : BlockPos.iterate(base.add(-radius, 0, -radius), base.add(radius, 2, radius))) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.isOf(Blocks.SNOW) || state.isIn(BlockTags.LEAVES) || state.isReplaceable()) {
                breakSoftBlock(world, bot, pos);
            }
        }
    }

    private boolean ensureWoodSpaceOrDeposit(ServerCommandSource source, ServerPlayerEntity bot, boolean huntPrerequisite) {
        if (bot == null || source == null) {
            return true;
        }
        int empty = countEmptySlots(bot);
        int woodCount = countWood(bot);
        // Hunt prerequisite only needs ~8 logs; 1 empty slot is enough. Normal runs need more room.
        int minEmptySlots = huntPrerequisite ? 0 : 2;
        boolean needsDeposit = empty <= minEmptySlots || woodCount > 256;
        LOGGER.info("Woodcut inventory: emptySlots={} woodCount={} needsDeposit={}", empty, woodCount, needsDeposit);
        if (!needsDeposit) {
            return true;
        }
        List<BlockPos> candidates = findNearbyChests(bot, 18, 6);
        for (BlockPos chestPos : candidates) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, bot, chestPos, this::isWoodStack);
            LOGGER.info("Woodcut deposit attempt: chest={} moved={}", chestPos.toShortString(), moved);
            if (moved > 0) {
                ChatUtils.sendSystemMessage(source, "Deposited wood into a nearby chest.");
                return true;
            }
        }

        for (BlockPos chestPos : candidates) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, bot, chestPos, this::isWoodcutOffloadCandidate);
            LOGGER.info("Woodcut deposit attempt (fallback): chest={} moved={}", chestPos.toShortString(), moved);
            if (moved > 0) {
                ChatUtils.sendSystemMessage(source, "Stored extra items in a nearby chest.");
                return true;
            }
        }

        BlockPos placed = ChestStoreService.placeChestNearBot(source, bot, true);
        if (placed != null) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, bot, placed, this::isWoodStack);
            LOGGER.info("Woodcut deposit attempt (placed chest): chest={} moved={}", placed.toShortString(), moved);
            if (moved > 0) {
                ChatUtils.sendSystemMessage(source, "Deposited wood into the new chest.");
                return true;
            }
            int movedFallback = ChestStoreService.depositMatchingWalkOnly(source, bot, placed, this::isWoodcutOffloadCandidate);
            LOGGER.info("Woodcut deposit attempt (placed chest fallback): chest={} moved={}", placed.toShortString(), movedFallback);
            if (movedFallback > 0) {
                ChatUtils.sendSystemMessage(source, "Stored extra items in the new chest.");
                return true;
            }
        }

        LOGGER.warn("Inventory full and couldn't store items (no reachable/usable chest, or chests are full).");
        ChatUtils.sendSystemMessage(source, "Inventory is full and I couldn't store items (no reachable chest or chests are full).");
        return false;
    }

    private List<BlockPos> findNearbyChests(ServerPlayerEntity bot, int radius, int vertical) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return List.of();
        }
        BlockPos origin = bot.getBlockPos();
        List<BlockPos> found = new ArrayList<>();
        int scanned = 0;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -vertical, -radius), origin.add(radius, vertical, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (!state.isOf(Blocks.CHEST) && !state.isOf(Blocks.TRAPPED_CHEST)) {
                continue;
            }
            scanned++;
            found.add(pos.toImmutable());
        }
        found.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        LOGGER.info("Chest scan: scanned={} found={} nearest={}", scanned, found.size(), found.isEmpty() ? "none" : found.get(0).toShortString());
        return found;
    }

    private boolean prepareReach(ServerPlayerEntity bot,
                                 ServerCommandSource source,
                                 BlockPos target,
                                 WoodcutReachSession reachSession,
                                 Map<String, Object> sharedState) {
        boolean onPillar = reachSession != null && reachSession.hasPlacements();
        if (isWithinReach(bot, target)) {
            LOGGER.debug("Woodcut reach: {} already within reach", target.toShortString());
            return true;
        }
        if (!onPillar) {
            moveUnderTarget(source, bot, target);
            if (isWithinReach(bot, target)) {
                LOGGER.debug("Woodcut reach: moved under {}", target.toShortString());
                return true;
            }
            if (tryReposition(bot, source, target, reachSession, sharedState)) {
                LOGGER.debug("Woodcut reach: repositioned near {}", target.toShortString());
                return true;
            }
            boolean canAttemptLeafClear = reachSession == null || !reachSession.preReachLeafClearAttempted;
            if (canAttemptLeafClear) {
                if (reachSession != null) {
                    reachSession.preReachLeafClearAttempted = true;
                }
                // Uneven terrain and low canopies often need one small leaf-clear pass before a usable stand opens up.
                clearObstructionAlongRay(bot, Vec3d.ofCenter(target), target);
                clearBlockingLeaves(bot, target, target, reachSession);
                if (isReadyToMineTarget(bot, target)) {
                    LOGGER.debug("Woodcut reach: leaf-clear opened LOS to {}", target.toShortString());
                    return true;
                }
                moveUnderTarget(source, bot, target);
                if (isWithinReach(bot, target)) {
                    LOGGER.debug("Woodcut reach: moved under {} after leaf-clear", target.toShortString());
                    return true;
                }
                if (tryReposition(bot, source, target, reachSession, sharedState)) {
                    LOGGER.debug("Woodcut reach: repositioned near {} after leaf-clear", target.toShortString());
                    return true;
                }
            }
        }
        int needed = target.getY() - bot.getBlockY() - 1;
        if (needed <= 0) {
            // We are above the target (canopy case). Try descending near the trunk and re-evaluate reach.
            if (descendTowardTarget(bot, source, target)) {
                return true;
            }
            return false;
        }
        LOGGER.info("Woodcut reach: pillaring {} blocks to reach {}", needed, target.toShortString());
        if (pillarUp(bot, needed, reachSession, source, sharedState)) {
            if (isReadyToMineTarget(bot, target)) {
                return true;
            }
            LOGGER.warn("Woodcut reach: pillar built but no trunk reach for {}", target.toShortString());
            if (tryReposition(bot, source, target, reachSession, sharedState) && isReadyToMineTarget(bot, target)) {
                LOGGER.info("Woodcut reach: post-pillar reposition succeeded for {}", target.toShortString());
                return true;
            }
            LOGGER.warn("Woodcut reach: post-pillar reposition failed for {}", target.toShortString());
            return false;
        }
        // Emergency: try a single underfoot placement to break climb-stall
        LOGGER.warn("Pillar placement failed; attempting emergency underfoot scaffold");
        if (!emergencyStep(bot, reachSession, sharedState)) {
            return false;
        }
        return isReadyToMineTarget(bot, target);
    }

    private boolean tryReposition(ServerPlayerEntity bot,
                                  ServerCommandSource source,
                                  BlockPos target,
                                  WoodcutReachSession reachSession,
                                  Map<String, Object> sharedState) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MovementService.MovementOptions options = WOODCUT_MOVEMENT_OPTIONS;
        Optional<MovementService.MovementPlan> planOpt = MovementService.planLootApproach(bot, target, options);
        if (planOpt.isEmpty()) {
            LOGGER.info("Woodcut approach: reposition around {} failed (reason=no dry stand)", target.toShortString());
            if (reachSession != null && tryBoundedTerrainCorrection(bot, source, world, target, null, reachSession, sharedState)) {
                reachSession.clearRepositionFailureTracking();
                return true;
            }
            return false;
        }
        MovementService.MovementPlan plan = planOpt.get();
        BlockPos approach = plan.approachDestination() != null ? plan.approachDestination() : plan.finalDestination();
        if (!isUsableWoodcutStand(world, approach)) {
            LOGGER.info("Woodcut approach: reposition candidate {} for {} rejected (reason=no dry stand)",
                    approach.toShortString(), target.toShortString());
            if (reachSession != null && tryBoundedTerrainCorrection(bot, source, world, target, approach, reachSession, sharedState)) {
                reachSession.clearRepositionFailureTracking();
                return true;
            }
            return false;
        }
        LOGGER.info("Woodcut approach: repositioning toward {} via {}", target.toShortString(), approach.toShortString());
        MovementService.MovementResult res = MovementService.execute(source, bot, plan, false, true, true, false);
        if (!res.success() && !isWithinReach(bot, target)) {
            LOGGER.info("Woodcut approach: reposition toward {} failed (reason=path failed detail={})",
                    target.toShortString(), res.detail());
            if (reachSession != null) {
                reachSession.recordRepositionFailure(target, approach);
                if (reachSession.repeatedRepositionFailures >= MAX_IDENTICAL_REPOSITION_FAILURES
                        && tryBoundedTerrainCorrection(bot, source, world, target, approach, reachSession, sharedState)) {
                    reachSession.clearRepositionFailureTracking();
                    return true;
                }
            }
        } else if (reachSession != null) {
            reachSession.clearRepositionFailureTracking();
        }
        return res.success() || isWithinReach(bot, target);
    }

    private boolean tryBoundedTerrainCorrection(ServerPlayerEntity bot,
                                                ServerCommandSource source,
                                                ServerWorld world,
                                                BlockPos target,
                                                BlockPos preferredStand,
                                                WoodcutReachSession reachSession,
                                                Map<String, Object> sharedState) {
        if (bot == null || source == null || world == null || target == null || reachSession == null) {
            return false;
        }
        if (reachSession.minorTerrainCorrections >= MAX_MINOR_TERRAIN_CORRECTIONS_PER_TARGET) {
            return false;
        }
        List<BlockPos> candidates = collectTerrainCorrectionCandidates(world, target, preferredStand);
        for (BlockPos stand : candidates) {
            if (stand == null || TaskService.isServerStopping() || isAbortRequested(bot)) {
                break;
            }
            if (isUsableWoodcutStand(world, stand)) {
                if (moveToStand(source, bot, world, stand, target)) {
                    LOGGER.info("Woodcut terrain recovery: reused sloped stand {} for {}", stand.toShortString(), target.toShortString());
                    return true;
                }
                continue;
            }
            if (!canCreateMinorSupportStand(world, stand)) {
                continue;
            }
            BlockPos support = stand.down();
            LOGGER.info("Woodcut terrain recovery: placing temporary support {} for {}", support.toShortString(), target.toShortString());
            if (!tryPlaceScaffold(bot, support, sharedState, reachSession)) {
                continue;
            }
            reachSession.minorTerrainCorrections++;
            if (moveToStand(source, bot, world, stand, target)) {
                return true;
            }
        }
        return false;
    }

    private List<BlockPos> collectTerrainCorrectionCandidates(ServerWorld world,
                                                              BlockPos target,
                                                              BlockPos preferredStand) {
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        if (preferredStand != null) {
            candidates.add(preferredStand.toImmutable());
            for (Direction direction : Direction.Type.HORIZONTAL) {
                candidates.add(preferredStand.offset(direction).toImmutable());
                candidates.add(preferredStand.offset(direction).down().toImmutable());
            }
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            candidates.add(target.offset(direction).toImmutable());
            candidates.add(target.offset(direction).down().toImmutable());
        }
        List<BlockPos> ordered = new ArrayList<>(candidates);
        ordered.removeIf(Objects::isNull);
        ordered.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(target)));
        return ordered;
    }

    private boolean canCreateMinorSupportStand(ServerWorld world, BlockPos stand) {
        if (world == null || stand == null || !world.isChunkLoaded(stand) || !world.isChunkLoaded(stand.down())) {
            return false;
        }
        BlockPos head = stand.up();
        BlockPos support = stand.down();
        BlockPos supportBelow = support.down();
        if (!world.isChunkLoaded(head) || !world.isChunkLoaded(supportBelow)) {
            return false;
        }
        if (!world.getFluidState(stand).isEmpty()
                || !world.getFluidState(head).isEmpty()
                || !world.getFluidState(support).isEmpty()) {
            return false;
        }
        if (!isPlaceableTarget(world, stand) || !isPlaceableTarget(world, support)) {
            return false;
        }
        if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
            return false;
        }
        return !world.getBlockState(supportBelow).getCollisionShape(world, supportBelow).isEmpty();
    }

    private boolean moveToStand(ServerCommandSource source,
                                ServerPlayerEntity bot,
                                ServerWorld world,
                                BlockPos stand,
                                BlockPos target) {
        if (source == null || bot == null || world == null || stand == null) {
            return false;
        }
        breakSoftBlock(world, bot, stand);
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                stand,
                stand,
                null,
                null,
                bot.getHorizontalFacing());
        MovementService.MovementResult result = MovementService.execute(source, bot, plan, false, true, true, false);
        if (!result.success()) {
            MovementService.clearRecentWalkAttempt(bot.getUuid());
        }
        return result.success()
                || bot.getBlockPos().getSquaredDistance(stand) <= 4.0D
                || (target != null && isWithinReach(bot, target));
    }

    private boolean isReadyToMineTarget(ServerPlayerEntity bot, BlockPos target) {
        return bot != null
                && target != null
                && isWithinReach(bot, target)
                && hasLineOfSight(bot, Vec3d.ofCenter(target));
    }

    private void logTargetTrace(TreeDetector.TreeTarget target,
                                WoodcutReachSession reachSession,
                                String terminalReason) {
        if (target == null || reachSession == null) {
            return;
        }
        LOGGER.info("Woodcut target trace: base={} top={} height={} detectionMode={} trunkMineStarts={} leafBlocksBroken={} scaffoldPlaced={} scaffoldRemoved={} terminalReason={}",
                target.base().toShortString(),
                target.top().toShortString(),
                target.height(),
                TreeDetector.TreeDetectionMode.STRICT_STANDING,
                reachSession.trunkMineAttemptsStarted,
                reachSession.leafBlocksBroken,
                reachSession.scaffoldPlaced,
                reachSession.scaffoldRemoved,
                terminalReason);
        if (reachSession.trunkMineAttemptsStarted == 0) {
            LOGGER.warn("Woodcut target {} selected but no trunk mining call was ever issued", target.base().toShortString());
        }
        if (reachSession.cleanupIncomplete) {
            LOGGER.warn("Woodcut target {} left scaffold cleanup incomplete", target.base().toShortString());
        }
    }

    private boolean descendTowardTarget(ServerPlayerEntity bot, ServerCommandSource source, BlockPos target) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        int maxSteps = 6;
        for (int i = 0; i < maxSteps; i++) {
            if (isWithinReach(bot, target)) {
                return true;
            }
            BlockPos feet = bot.getBlockPos();
            BlockPos below = feet.down();
            BlockState belowState = world.getBlockState(below);
            // If we can step down safely, walk to the block above target (same X/Z, lower Y)
            if (world.isAir(below) || belowState.isReplaceable()) {
                // clear replaceable to drop a step
                mineBlock(bot, below, false);
            }
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    target.up(), // aim just above the target trunk
                    target.up(),
                    null,
                    null,
                    bot.getHorizontalFacing()
            );
            MovementService.execute(source, bot, plan, false, true, true, false);
            if (bot.getBlockY() <= target.getY() + 1) {
                return isWithinReach(bot, target);
            }
        }
        return false;
    }

    private boolean pillarUp(ServerPlayerEntity bot,
                             int steps,
                             WoodcutReachSession reachSession,
                             ServerCommandSource source,
                             Map<String, Object> sharedState) {
        if (steps <= 0) {
            return true;
        }
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (!ensurePillarStock(bot, steps, source, bot.getBlockY() + steps, reachSession, sharedState)) {
            LOGGER.warn("No scaffold blocks available to pillar up {} steps", steps);
            return false;
        }
        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        LOGGER.info("Woodcut pillar: starting {} steps from {} with {} scaffold blocks",
                steps, bot.getBlockPos().toShortString(), countPillarBlocks(bot));
        for (int i = 0; i < steps; i++) {
            if (isAbortRequested(bot)) {
                bot.setSneaking(wasSneaking);
                return false;
            }
            BlockPos candidate = bot.getBlockPos();
            if (!world.getBlockState(candidate).isAir()) {
                candidate = candidate.up();
            }
            BotActions.jump(bot);
            sleepQuiet(PILLAR_STEP_DELAY_MS);
            if (!world.getBlockState(candidate).isAir()) {
                candidate = candidate.up();
            }
            boolean placed = tryPlaceScaffold(bot, candidate, sharedState, reachSession);
            if (!placed) {
                LOGGER.warn("Failed to place scaffold block at {}", candidate.toShortString());
                bot.setSneaking(wasSneaking);
                return false;
            }
            if (reachSession != null) {
                reachSession.recordPlacement(candidate);
            }
            LOGGER.debug("Woodcut pillar: placed at {}", candidate.toShortString());
            sleepQuiet(PILLAR_STEP_DELAY_MS);
        }
        bot.setSneaking(wasSneaking);
        return true;
    }

    private WoodcutDetectionSnapshot scanDetectionSnapshot(ServerPlayerEntity bot,
                                                           int radius,
                                                           int vertical,
                                                           Set<BlockPos> visited,
                                                           Set<BlockPos> failedBases,
                                                           Map<BlockPos, String> failedBaseReasons) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new WoodcutDetectionSnapshot(Optional.empty(), null, null, null, 0, 0, 0, 0, 0, 0, Map.of(), List.of(), false);
        }
        BlockPos origin = bot.getBlockPos();
        int totalLogs = 0;
        int visitedLogs = 0;
        int protectedCount = 0;
        int humanProx = 0;
        int soilFail = 0;
        int leafFail = 0;
        TreeDetector.TreeTarget nearestTree = null;
        double nearestTreeDist = Double.MAX_VALUE;
        BlockPos bestFloating = null;
        double bestFloatingDist = Double.MAX_VALUE;
        BlockPos bestLoose = null;
        double bestLooseDist = Double.MAX_VALUE;
        BlockPos bestAny = null;
        double bestAnyDist = Double.MAX_VALUE;
        Map<String, Integer> protectedReasons = new LinkedHashMap<>();
        List<String> rejectSamples = new ArrayList<>(3);

        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -vertical, -radius), origin.add(radius, vertical, radius))) {
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            totalLogs++;

            double distSq = origin.getSquaredDistance(pos);
            boolean nearHuman = TreeDetector.isNearHumanBlocks(world, pos, 4);
            TreeDetector.WoodcutProtectionDecision protectionDecision =
                    TreeDetector.getWoodcutProtectionDecision(world, pos, 4);
            boolean protectedTarget = protectionDecision.blocked();
            boolean soilValid = TreeDetector.isValidSoil(world.getBlockState(pos.down()));
            boolean leavesNearby = TreeDetector.hasLeavesNearby(world, pos, 4, 4);

            if (nearHuman) {
                humanProx++;
            }
            if (protectedTarget) {
                protectedCount++;
                protectedReasons.merge(protectionDecision.reason(), 1, Integer::sum);
            }
            if (!soilValid) {
                soilFail++;
            }
            if (!leavesNearby) {
                leafFail++;
            }

            if (!nearHuman && !protectedTarget) {
                Optional<TreeDetector.TreeTarget> treeOpt = TreeDetector.detectTreeAtForWoodcut(
                        world, pos, TreeDetector.TreeDetectionMode.STRICT_STANDING);
                if (treeOpt.isPresent()) {
                    TreeDetector.TreeTarget tree = treeOpt.get();
                    if (visited != null && visited.contains(tree.base())) {
                        visitedLogs++;
                        continue;
                    }
                    if (failedBases != null && failedBases.contains(tree.base())) {
                        if (rejectSamples.size() < 3) {
                            rejectSamples.add(String.format("%s (failedBase=true, reason=%s)",
                                    tree.base().toShortString(),
                                    failedBaseReasons == null ? "previous-failure" : failedBaseReasons.getOrDefault(tree.base(), "previous-failure")));
                        }
                        continue;
                    }
                    double treeDistSq = origin.getSquaredDistance(tree.base());
                    if (treeDistSq < nearestTreeDist) {
                        nearestTreeDist = treeDistSq;
                        nearestTree = tree;
                    }
                    continue;
                }

                TreeDetector.CleanupLogDisposition cleanupDisposition =
                        TreeDetector.classifyCleanupLog(world, pos, false);
                if (cleanupDisposition.actionable() && distSq < bestFloatingDist) {
                    bestFloatingDist = distSq;
                    bestFloating = pos.toImmutable();
                }
                if (leavesNearby && distSq < bestLooseDist) {
                    bestLooseDist = distSq;
                    bestLoose = pos.toImmutable();
                }
                if (distSq < bestAnyDist) {
                    bestAnyDist = distSq;
                    bestAny = pos.toImmutable();
                }
            }

            if (rejectSamples.size() < 3) {
                boolean strictStandingReject = !nearHuman
                        && !protectedTarget
                        && TreeDetector.detectTreeAtForWoodcut(world, pos, TreeDetector.TreeDetectionMode.LOOSE_FRAGMENT).isPresent()
                        && TreeDetector.detectTreeAtForWoodcut(world, pos, TreeDetector.TreeDetectionMode.STRICT_STANDING).isEmpty();
                rejectSamples.add(String.format("%s (soilFail=%s, leafFail=%s, humanFail=%s, protected=%s, protection=%s%s)",
                        pos.toShortString(),
                        !soilValid,
                        !leavesNearby,
                        nearHuman,
                        protectedTarget,
                        protectionDecision.reason(),
                        strictStandingReject ? ", strictStandingReject=true" : ""));
            }
        }

        int unvisitedLogs = Math.max(0, totalLogs - visitedLogs);
        boolean allProtectedOrHuman = nearestTree == null
                && bestFloating == null
                && bestLoose == null
                && bestAny == null
                && unvisitedLogs > 0
                && protectedCount + humanProx >= Math.max(1, unvisitedLogs - 1);

        return new WoodcutDetectionSnapshot(
                Optional.ofNullable(nearestTree),
                bestFloating,
                bestLoose,
                bestAny,
                totalLogs,
                visitedLogs,
                protectedCount,
                humanProx,
                soilFail,
                leafFail,
                Map.copyOf(protectedReasons),
                List.copyOf(rejectSamples),
                allProtectedOrHuman
        );
    }

    private void logDetectionSnapshot(WoodcutDetectionSnapshot snapshot, long durationMs) {
        BlockPos nearest = snapshot.nearestTree().map(TreeDetector.TreeTarget::base).orElse(null);
        LOGGER.info("Woodcut detect: durationMs={} logs={} visited={} humanProx={} protected={} nearest={}",
                durationMs,
                snapshot.totalLogs(),
                snapshot.visitedLogs(),
                snapshot.humanAdjacentLogs(),
                snapshot.protectedLogs(),
                nearest == null ? "none" : nearest.toShortString());
        for (int i = 0; i < snapshot.rejectSamples().size(); i++) {
            LOGGER.info("Woodcut detect reject sample {} at {}", i + 1, snapshot.rejectSamples().get(i));
        }
        LOGGER.info("Woodcut detect diagnostics: logs={} visited={} soilFail={} leafFail={} humanFail={} protectedFail={} protectReasons={} allProtectedOrHuman={}",
                snapshot.totalLogs(),
                snapshot.visitedLogs(),
                snapshot.soilFail(),
                snapshot.leafFail(),
                snapshot.humanAdjacentLogs(),
                snapshot.protectedLogs(),
                TreeDetector.summarizeProtectionReasons(snapshot.protectedReasonCounts()),
                snapshot.allLocalLogsProtectedOrHuman());
    }

    private void clearBlockingLeaves(ServerPlayerEntity bot, BlockPos target) {
        clearBlockingLeaves(bot, target, target, null);
    }

    private void clearBlockingLeaves(ServerPlayerEntity bot,
                                     BlockPos target,
                                     BlockPos associatedTargetBase,
                                     WoodcutReachSession reachSession) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (reachSession != null && reachSession.leafBlocksBroken >= MAX_LEAF_CLEAR_BLOCKS_PER_TARGET) {
            return;
        }
        selectLeafTool(bot);
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        Vec3d center = Vec3d.ofCenter(target);
        RaycastContext ctx = new RaycastContext(
                bot.getEyePos(),
                center,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot);
        var hit = world.raycast(ctx);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = hit.getBlockPos();
            if (!hitPos.equals(target)) {
                candidates.add(hitPos.toImmutable());
                for (Direction direction : Direction.values()) {
                    candidates.add(hitPos.offset(direction).toImmutable());
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 1) {
                        candidates.add(target.add(dx, dy, dz).toImmutable());
                    }
                }
            }
        }
        int clearedThisAttempt = 0;
        for (BlockPos leafPos : candidates) {
            if (reachSession != null && reachSession.leafBlocksBroken >= MAX_LEAF_CLEAR_BLOCKS_PER_TARGET) {
                break;
            }
            if (clearedThisAttempt >= MAX_LEAF_CLEAR_BLOCKS_PER_ATTEMPT) {
                break;
            }
            BlockState state = world.getBlockState(leafPos);
            if (!state.isIn(BlockTags.LEAVES)) {
                continue;
            }
            if (breakLeaf(bot, leafPos, associatedTargetBase, reachSession)) {
                clearedThisAttempt++;
            }
        }
        if (reachSession != null && clearedThisAttempt > 0) {
            reachSession.losClearAttempts++;
        }
    }

    private void breakLeaf(ServerPlayerEntity bot, BlockPos pos) {
        breakLeaf(bot, pos, null, null);
    }

    private boolean breakLeaf(ServerPlayerEntity bot,
                              BlockPos pos,
                              BlockPos associatedTargetBase,
                              WoodcutReachSession reachSession) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (isAbortRequested(bot)) {
            return false;
        }
        TreeDetector.WoodcutProtectionDecision decision = getWoodcutMutationDecision(world, pos, associatedTargetBase);
        if (decision.blocked()) {
            LOGGER.info("Woodcut: refusing to mine {} (reason={})", pos.toShortString(), decision.reason());
            return false;
        }
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        if (botPos.squaredDistanceTo(center) > REACH_DISTANCE_SQ) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isIn(BlockTags.LOGS)) {
            ensureAxeEquipped(bot);
        } else {
            selectLeafTool(bot);
        }
        LookController.faceBlock(bot, pos);
        CompletableFuture<String> mining;
        try {
            mining = MiningTool.mineBlock(bot, pos, true, false);
        } catch (Exception e) {
            LOGGER.warn("Leaf break scheduling failed at {}: {}", pos.toShortString(), e.getMessage());
            return false;
        }
        try {
            String result = mining.get(3_000, TimeUnit.MILLISECONDS);
            boolean success = result != null && result.toLowerCase().contains("complete");
            if (success && reachSession != null) {
                reachSession.leafBlocksBroken++;
            }
            return success || world.getBlockState(pos).isAir();
        } catch (Exception e) {
            mining.cancel(true);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private int effectiveSearchRadius(ServerPlayerEntity bot, int configuredRadius) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return configuredRadius;
        }
        int adjusted = Math.max(6, configuredRadius);
        if (world.getServer() != null) {
            for (BotHomeService.BaseEntry base : BotHomeService.listBases(world.getServer(), world)) {
                if (base == null || base.pos() == null) {
                    continue;
                }
                double dist = Math.sqrt(bot.getBlockPos().getSquaredDistance(base.pos()));
                if (dist <= base.radius()) {
                    int exitDistance = (int) Math.ceil(base.radius() - dist);
                    adjusted = Math.max(adjusted, exitDistance + 8);
                }
            }
        }
        if (TreeDetector.isProtected(world, bot.getBlockPos(), 4)) {
            adjusted = Math.max(adjusted, configuredRadius + 12);
        }
        return adjusted;
    }

    /**
     * When all nearby trees are protected, search outward in a 360-degree ring for
     * the nearest unprotected tree and walk there.  If the ring search fails (village
     * protection extends beyond the search radius), blind-walk away from village
     * signals and let the next loop iteration search from the new position.
     */
    private boolean relocateToUnprotectedTree(ServerCommandSource source,
                                               ServerPlayerEntity bot,
                                               int currentSearchRadius,
                                               Set<BlockPos> failedBases,
                                               Map<BlockPos, String> failedBaseReasons) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (isAbortRequested(bot)) {
            return false;
        }

        long relocationStartedAt = System.nanoTime();
        long immediateStageMs = 0L;
        long nearbyStageMs = 0L;
        long broadStageMs = 0L;
        BlockPos origin = bot.getBlockPos();
        long immediateStageStartedAt = System.nanoTime();
        logFailedBaseSkips(origin, failedBases, 4, "immediate-local", failedBaseReasons);
        BlockPos immediateTarget = TreeDetector.findNearestUnprotectedTreeNearby(
                world, origin, 4, RELOCATION_NEARBY_VERTICAL_RANGE, failedBases);
        immediateStageMs = (System.nanoTime() - immediateStageStartedAt) / 1_000_000L;
        if (immediateTarget != null) {
            double immediateDist = Math.sqrt(origin.getSquaredDistance(immediateTarget));
            LOGGER.info("Woodcut relocation: immediate local tree candidate at {} (dist={}) from {}",
                    immediateTarget.toShortString(), (int) immediateDist, origin.toShortString());
            if (origin.getSquaredDistance(immediateTarget) <= 9.0D || walkToTarget(source, bot, immediateTarget)) {
                logRelocationStageTimings(relocationStartedAt, immediateStageMs, nearbyStageMs, broadStageMs, "immediate-local");
                return true;
            }
        }

        if (MappedVillageService.isInsideMappedVillage(world, origin)
                && tryExitMappedVillage(source, bot, world, origin)) {
            logRelocationStageTimings(relocationStartedAt, immediateStageMs, nearbyStageMs, broadStageMs, "mapped-village-exit");
            return true;
        }

        origin = bot.getBlockPos();
        long nearbyStageStartedAt = System.nanoTime();
        logFailedBaseSkips(origin, failedBases, Math.max(RELOCATION_NEARBY_RADIUS, currentSearchRadius + 8), "nearby-sampled", failedBaseReasons);
        BlockPos nearbyTarget = TreeDetector.findNearestUnprotectedTreeBySampling(
                world, origin,
                Math.max(2, currentSearchRadius),
                Math.max(RELOCATION_NEARBY_RADIUS, currentSearchRadius + 8),
                RELOCATION_NEARBY_VERTICAL_RANGE,
                RELOCATION_NEARBY_TIME_BUDGET_MS,
                6,
                16,
                failedBases);
        nearbyStageMs = (System.nanoTime() - nearbyStageStartedAt) / 1_000_000L;
        if (nearbyTarget != null) {
            double dist = Math.sqrt(origin.getSquaredDistance(nearbyTarget));
            LOGGER.info("Woodcut relocation: nearby sampled tree at {} (dist={}) from {} after {}ms",
                    nearbyTarget.toShortString(), (int) dist, origin.toShortString(), nearbyStageMs);
            if (walkToTarget(source, bot, nearbyTarget)) {
                logRelocationStageTimings(relocationStartedAt, immediateStageMs, nearbyStageMs, broadStageMs, "nearby-sampled");
                return true;
            }
        }

        origin = bot.getBlockPos();
        long broadStageStartedAt = System.nanoTime();
        logFailedBaseSkips(origin, failedBases, RELOCATION_SEARCH_RADIUS, "broad-sampled", failedBaseReasons);
        BlockPos target = TreeDetector.findNearestUnprotectedTreeBySampling(
                world, origin,
                Math.max(currentSearchRadius, RELOCATION_NEARBY_RADIUS),
                RELOCATION_SEARCH_RADIUS,
                RELOCATION_VERTICAL_RANGE,
                RELOCATION_BROAD_TIME_BUDGET_MS,
                8,
                24,
                failedBases);
        broadStageMs = (System.nanoTime() - broadStageStartedAt) / 1_000_000L;

        if (target == null) {
            LOGGER.info("Woodcut relocation: no unprotected tree within {} blocks of {} after {}ms sampled search, trying blind walk",
                    RELOCATION_SEARCH_RADIUS, origin.toShortString(), broadStageMs);
            boolean blindWalkResult = blindWalkAwayFromProtection(source, bot, world, origin);
            logRelocationStageTimings(relocationStartedAt, immediateStageMs, nearbyStageMs, broadStageMs,
                    blindWalkResult ? "blind-walk-success" : "blind-walk-failed");
            return blindWalkResult;
        }

        double dist = Math.sqrt(origin.getSquaredDistance(target));
        LOGGER.info("Woodcut relocation: broad sampled tree at {} (dist={}) from {} after {}ms",
                target.toShortString(), (int) dist, origin.toShortString(), broadStageMs);
        boolean walked = walkToTarget(source, bot, target);
        logRelocationStageTimings(relocationStartedAt, immediateStageMs, nearbyStageMs, broadStageMs,
                walked ? "broad-sampled" : "broad-sampled-walk-failed");
        return walked;
    }

    private void logRelocationStageTimings(long relocationStartedAt,
                                           long immediateStageMs,
                                           long nearbyStageMs,
                                           long broadStageMs,
                                           String outcome) {
        long totalMs = (System.nanoTime() - relocationStartedAt) / 1_000_000L;
        LOGGER.info("Woodcut relocation: stage timings immediate={}ms nearby={}ms broad={}ms total={}ms outcome={}",
                immediateStageMs, nearbyStageMs, broadStageMs, totalMs, outcome);
        if (totalMs < RELOCATION_SLOW_SUMMARY_THRESHOLD_MS) {
            return;
        }
        String slowStage = "immediate";
        long slowStageMs = immediateStageMs;
        if (nearbyStageMs >= slowStageMs) {
            slowStage = "nearby-sampled";
            slowStageMs = nearbyStageMs;
        }
        if (broadStageMs >= slowStageMs) {
            slowStage = "broad-sampled";
            slowStageMs = broadStageMs;
        }
        LOGGER.warn("Woodcut relocation: slow startup total={}ms dominantStage={}({}ms) outcome={}",
                totalMs, slowStage, slowStageMs, outcome);
    }

    private boolean tryExitMappedVillage(ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         ServerWorld world,
                                         BlockPos origin) {
        Optional<MappedVillageNavigationService.EscapePlan> planOpt =
                MappedVillageNavigationService.planExit(world, origin, MAPPED_VILLAGE_EXIT_CLEARANCE);
        if (planOpt.isEmpty()) {
            LOGGER.info("Woodcut relocation: mapped-village exit mode skipped at {} (reason=no-exit-geometry)",
                    origin.toShortString());
            return false;
        }

        MappedVillageNavigationService.EscapePlan plan = planOpt.get();
        LOGGER.info("Woodcut relocation: mapped-village exit mode village='{}' from {} edge={} target={} validCandidates={}/5 clearance={}",
                plan.villageName(),
                origin.toShortString(),
                plan.nearestEdgePoint().toShortString(),
                plan.exitTarget().toShortString(),
                plan.candidateTargets().size(),
                plan.clearance());

        if (plan.candidateTargets().isEmpty()) {
            LOGGER.info("Woodcut relocation: mapped-village exit village='{}' has no usable candidates (reason=exit-candidates-all-invalid)",
                    plan.villageName());
            return false;
        }

        for (BlockPos candidate : plan.candidateTargets()) {
            if (isAbortRequested(bot)) {
                return false;
            }
            if (!isDryStableWoodcutStand(world, candidate)) {
                LOGGER.info("Woodcut relocation: mapped-village exit candidate {} rejected (reason=no-dry-stand)",
                        candidate.toShortString());
                continue;
            }
            LOGGER.info("Woodcut relocation: trying mapped-village exit candidate {} for '{}'",
                    candidate.toShortString(), plan.villageName());
            if (!walkToTarget(source, bot, candidate)) {
                LOGGER.info("Woodcut relocation: mapped-village exit candidate {} failed (reason=path-unreachable)",
                        candidate.toShortString());
                continue;
            }

            BlockPos postWalk = bot.getBlockPos();
            boolean outside = MappedVillageNavigationService.isOutsideTarget(world, postWalk, plan.villageName());
            LOGGER.info("Woodcut relocation: mapped-village exit post-walk village='{}' nowAt={} outside={}",
                    plan.villageName(), postWalk.toShortString(), outside);
            if (outside) {
                LOGGER.info("Woodcut relocation: left mapped village '{}' and will resume normal tree search from {}",
                        plan.villageName(), postWalk.toShortString());
                return true;
            }

            LOGGER.info("Woodcut relocation: mapped-village exit candidate {} ended at {} but bot is still inside village '{}' (reason=movement-ended-still-inside)",
                    candidate.toShortString(), postWalk.toShortString(), plan.villageName());
        }

        LOGGER.info("Woodcut relocation: mapped-village exit village='{}' exhausted valid candidates (reason=exit-candidates-path-failed)",
                plan.villageName());
        return false;
    }

    /**
     * Walk away from village protection by sampling 8 compass directions and trying
     * each unprotected direction until a walk succeeds.
     */
    private boolean blindWalkAwayFromProtection(ServerCommandSource source,
                                                 ServerPlayerEntity bot,
                                                 ServerWorld world,
                                                 BlockPos origin) {
        // 8 compass directions: N, NE, E, SE, S, SW, W, NW
        int[][] dirs = {
            {0, -1}, {1, -1}, {1, 0}, {1, 1},
            {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}
        };

        int triedCount = 0;
        for (int[] dir : dirs) {
            if (isAbortRequested(bot)) return false;
            int tx = origin.getX() + dir[0] * BLIND_WALK_DISTANCE;
            int tz = origin.getZ() + dir[1] * BLIND_WALK_DISTANCE;
            BlockPos probe = new BlockPos(tx, origin.getY(), tz);
            if (!world.isChunkLoaded(probe)) continue;
            int surfaceY = findSurfaceY(world, probe);
            if (surfaceY <= world.getBottomY()) continue;
            BlockPos surfaceProbe = new BlockPos(tx, surfaceY, tz);
            if (TreeDetector.isProtectedForWoodcut(world, surfaceProbe, 4)) continue;
            BlockPos dryProbe = findDryStandableNear(world, surfaceProbe, 3, 3);
            if (dryProbe == null) {
                LOGGER.info("Woodcut blind walk: rejecting direction ({},{}) at {} (reason=no-dry-stand)",
                        dir[0], dir[1], surfaceProbe.toShortString());
                continue;
            }

            triedCount++;
            LOGGER.info("Woodcut blind walk: trying direction ({},{}) toward {} (attempt {})",
                    dir[0], dir[1], dryProbe.toShortString(), triedCount);
            if (walkToTarget(source, bot, dryProbe)) {
                return true;
            }
            // Walk failed — try the next direction from current position
            origin = bot.getBlockPos();
        }

        if (triedCount == 0) {
            LOGGER.info("Woodcut blind walk: all {} directions still protected at {} blocks",
                    dirs.length, BLIND_WALK_DISTANCE);
        } else {
            LOGGER.info("Woodcut blind walk: tried {} directions, all walks failed", triedCount);
        }
        return false;
    }

    private static int findSurfaceY(ServerWorld world, BlockPos column) {
        return SafePositionService.getWalkableGroundY(world, column.getX(), column.getZ());
    }

    private boolean walkToTarget(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MovementService.MovementPlan plan = MovementService.planLootApproach(
                        bot,
                        target,
                        WOODCUT_MOVEMENT_OPTIONS)
                .orElseGet(() -> new MovementService.MovementPlan(
                        MovementService.Mode.DIRECT,
                        target,
                        target,
                        null,
                        null,
                        bot.getHorizontalFacing()
                ));
        BlockPos approach = plan.approachDestination() != null ? plan.approachDestination() : plan.finalDestination();
        if (!isUsableWoodcutStand(world, approach)) {
            LOGGER.info("Woodcut relocation: rejected target {} via {} (reason=no-dry-stand)",
                    target.toShortString(), approach.toShortString());
            return false;
        }
        LOGGER.info("Woodcut relocation: movement plan mode={} final={} approach={}",
                plan.mode(),
                plan.finalDestination().toShortString(),
                plan.approachDestination() != null ? plan.approachDestination().toShortString() : "null");
        MovementService.MovementResult res = MovementService.execute(source, bot, plan, false, true, true, false);
        boolean success = res.success();
        if (success) {
            double postDistSq = bot.getBlockPos().getSquaredDistance(target);
            // Relocation doesn't need pinpoint accuracy — within 5 blocks is fine
            success = postDistSq <= 25.0;
        }
        if (!success) {
            // Even if movement reported failure, check if we got close enough
            double postDistSq = bot.getBlockPos().getSquaredDistance(target);
            if (postDistSq <= 25.0) {
                success = true;
            }
        }
        if (success) {
            LOGGER.info("Woodcut relocation: arrived near {} (now at {})",
                    target.toShortString(), bot.getBlockPos().toShortString());
        } else {
            LOGGER.info("Woodcut relocation: walk to {} failed: {}", target.toShortString(), res.detail());
        }
        return success;
    }



    private boolean tryPlaceScaffold(ServerPlayerEntity bot,
                                     BlockPos target,
                                     Map<String, Object> sharedState,
                                     WoodcutReachSession reachSession) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (countPillarBlocks(bot) == 0) {
            LOGGER.warn("No valid scaffold blocks available to place at {}", target.toShortString());
            return false;
        }
        BlockPos placePos = target;
        if (!isPlaceableTarget(world, placePos)) {
            // try to clear replaceable block
            breakSoftBlock(world, bot, placePos);
        }
        ensureSupportBlock(bot, placePos, sharedState, reachSession);
        if (BotActions.placeBlockAt(bot, placePos, Direction.UP, PILLAR_BLOCKS)) {
            recordScaffoldPlacement(sharedState, world, placePos);
            if (reachSession != null) {
                reachSession.recordPlacement(placePos);
            }
            return true;
        }
        // Try nearby offsets to recover from collision/leaf interference
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos alt = placePos.offset(dir);
            if (!isPlaceableTarget(world, alt)) {
                breakSoftBlock(world, bot, alt);
            }
            ensureSupportBlock(bot, alt, sharedState, reachSession);
            if (BotActions.placeBlockAt(bot, alt, Direction.UP, PILLAR_BLOCKS)) {
                recordScaffoldPlacement(sharedState, world, alt);
                if (reachSession != null) {
                    reachSession.recordPlacement(alt);
                }
                LOGGER.debug("Woodcut pillar: placed via offset {} at {}", dir, alt.toShortString());
                return true;
            }
        }
        return false;
    }

    private void ensureSupportBlock(ServerPlayerEntity bot,
                                    BlockPos target,
                                    Map<String, Object> sharedState,
                                    WoodcutReachSession reachSession) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos below = target.down();
        BlockState belowState = world.getBlockState(below);
        if (!belowState.getCollisionShape(world, below).isEmpty()) {
            return;
        }
        if (!isPlaceableTarget(world, below)) {
            breakSoftBlock(world, bot, below);
        }
        if (BotActions.placeBlockAt(bot, below, Direction.UP, PILLAR_BLOCKS)) {
            recordScaffoldPlacement(sharedState, world, below);
            if (reachSession != null) {
                reachSession.recordPlacement(below);
            }
            LOGGER.debug("Woodcut pillar: placed support at {}", below.toShortString());
        }
    }

    private boolean isPlaceableTarget(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(net.minecraft.block.Blocks.SNOW);
    }

    private void breakSoftBlock(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        if (state.isIn(BlockTags.LEAVES) || state.isOf(net.minecraft.block.Blocks.SNOW) || state.isReplaceable()) {
            breakLeaf(bot, pos);
        }
    }

    private boolean emergencyStep(ServerPlayerEntity bot,
                                  WoodcutReachSession reachSession,
                                  Map<String, Object> sharedState) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        BlockPos foot = bot.getBlockPos();
        BlockPos below = foot.down();
        breakSoftBlock(world, bot, foot);
        breakSoftBlock(world, bot, below);
        if (tryPlaceScaffold(bot, below, sharedState, reachSession)) {
            if (reachSession != null) {
                reachSession.recordPlacement(below);
            }
            LOGGER.info("Emergency scaffold placed at {}", below.toShortString());
            return true;
        }
        LOGGER.warn("Emergency scaffold placement failed at {}", below.toShortString());
        return false;
    }

    private boolean mineBlock(ServerPlayerEntity bot, BlockPos pos, boolean preferAxe) {
        return mineBlockDetailed(bot, pos, preferAxe, null, null).success();
    }

    private MineAttemptResult mineBlockDetailed(ServerPlayerEntity bot,
                                                BlockPos pos,
                                                boolean preferAxe,
                                                BlockPos associatedTargetBase,
                                                WoodcutReachSession reachSession) {
        if (isAbortRequested(bot)) {
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "abort-requested");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "no-world");
        }
        TreeDetector.WoodcutProtectionDecision decision = getWoodcutMutationDecision(world, pos, associatedTargetBase);
        if (decision.blocked()) {
            LOGGER.info("Woodcut: refusing to mine {} (reason={})", pos.toShortString(), decision.reason());
            return new MineAttemptResult(false, WoodcutFailureReason.PROTECTED_AT_MINING, decision.reason());
        }
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double distSq = botPos.squaredDistanceTo(center);
        if (distSq > REACH_DISTANCE_SQ) {
            LOGGER.warn("Refusing to mine {} - out of reach (dist={})", pos.toShortString(), Math.sqrt(distSq));
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "out-of-reach");
        }
        if (!hasLineOfSight(bot, center)) {
            LOGGER.warn("LOS blocked for {} from eye {} (bot={})", pos.toShortString(), bot.getEyePos(), bot.getBlockPos().toShortString());
            if (reachSession != null && reachSession.hasPlacements()) {
                clearHeadroom(bot, associatedTargetBase != null ? associatedTargetBase : pos, reachSession);
            }
            clearObstructionAlongRay(bot, center, associatedTargetBase != null ? associatedTargetBase : pos);
            clearBlockingLeaves(bot, pos, associatedTargetBase != null ? associatedTargetBase : pos, reachSession);
            if (!hasLineOfSight(bot, center)) {
                return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "los-blocked");
            }
        }
        LookController.faceBlock(bot, pos);
        if (preferAxe) {
            ensureAxeEquipped(bot);
        } else {
            selectScaffoldToolOrHands(bot);
        }
        if (reachSession != null && associatedTargetBase != null && world.getBlockState(pos).isIn(BlockTags.LOGS)) {
            reachSession.trunkMineAttemptsStarted++;
        }
        CompletableFuture<String> miningFuture = MiningTool.mineBlock(bot, pos, true, false);
        try {
            String result = miningFuture.get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String lower = result == null ? "" : result.toLowerCase();
            if (lower.contains("complete")) {
                return new MineAttemptResult(true, WoodcutFailureReason.NO_TARGET, "complete");
            }
            if (result != null && result.startsWith("⚠️")) {
                LOGGER.warn("Woodcut mining returned '{}' at {}", result, pos.toShortString());
                if (lower.contains("protected village block") || lower.contains("protected claim")) {
                    return new MineAttemptResult(false, WoodcutFailureReason.PROTECTED_AT_MINING, result);
                }
                if (lower.contains("no line of sight")) {
                    return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "los-blocked");
                }
                if (lower.contains("out of reach")) {
                    return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "out-of-reach");
                }
                if (lower.contains("aborted")) {
                    return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "abort-requested");
                }
            }
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE,
                    result == null || result.isBlank() ? "mining-incomplete" : result);
        } catch (TimeoutException timeout) {
            LOGGER.warn("Mining {} timed out", pos.toShortString());
            miningFuture.cancel(true);
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "timeout");
        } catch (Exception e) {
            LOGGER.error("Failed to mine {}: {}", pos.toShortString(), e.getMessage());
            miningFuture.cancel(true);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "exception");
        }
    }

    private TreeDetector.WoodcutProtectionDecision getWoodcutMutationDecision(ServerWorld world,
                                                                              BlockPos pos,
                                                                              BlockPos associatedTargetBase) {
        if (world == null || pos == null) {
            return new TreeDetector.WoodcutProtectionDecision(false, "none");
        }
        BlockState state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.LOGS) && !state.isIn(BlockTags.LEAVES)) {
            return new TreeDetector.WoodcutProtectionDecision(false, "none");
        }
        BlockPos decisionPos = associatedTargetBase != null ? associatedTargetBase : pos;
        return TreeDetector.getWoodcutProtectionDecision(world, decisionPos, 4);
    }

    private void cleanupReachSession(ServerCommandSource source,
                                     ServerPlayerEntity bot,
                                     BlockPos base,
                                     WoodcutReachSession reachSession,
                                     Map<String, Object> sharedState) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (reachSession == null || !reachSession.hasPlacements()) {
            return;
        }
        prepareCleanupSurfacePosition(source, bot, world, base, reachSession, sharedState);
        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        LOGGER.info("Woodcut pillar: descending, blocks placed={}", reachSession.placedBlocks.size());
        for (BlockPos placed : reachSession.placementsDescending()) {
            if (isAbortRequested(bot) || TaskService.isServerStopping()) {
                bot.setSneaking(wasSneaking);
                return;
            }
            if (world.getBlockState(placed).isAir()) {
                forgetScaffoldPlacement(sharedState, world, placed);
                reachSession.recordRemoval(placed);
                continue;
            }
            if (!isWithinReach(bot, placed) && !moveNearScaffoldForCleanup(source, bot, world, placed, base)) {
                LOGGER.warn("Woodcut scaffold cleanup: unreachable {} from {}",
                        placed.toShortString(), bot.getBlockPos().toShortString());
                reachSession.cleanupIncomplete = true;
                continue;
            }
            LookController.faceBlock(bot, placed);
            if (mineBlock(bot, placed, false) || world.getBlockState(placed).isAir()) {
                forgetScaffoldPlacement(sharedState, world, placed);
                reachSession.recordRemoval(placed);
            } else {
                reachSession.cleanupIncomplete = true;
            }
            sleepQuiet(80L);
        }
        bot.setSneaking(wasSneaking);
    }

    private boolean moveNearScaffoldForCleanup(ServerCommandSource source,
                                               ServerPlayerEntity bot,
                                               ServerWorld world,
                                               BlockPos placed,
                                               BlockPos base) {
        if (isWithinReach(bot, placed)) {
            return true;
        }
        BlockPos stand = findDryStandableNear(world, placed, 1, 3);
        if (stand == null && base != null) {
            stand = findDryStandableNear(world, base, 2, 3);
        }
        if (stand == null) {
            return false;
        }
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                stand,
                stand,
                null,
                null,
                bot.getHorizontalFacing());
        MovementService.MovementResult result = MovementService.execute(source, bot, plan, false, true, true, false);
        return result.success() || isWithinReach(bot, placed);
    }

    private void prepareCleanupSurfacePosition(ServerCommandSource source,
                                               ServerPlayerEntity bot,
                                               ServerWorld world,
                                               BlockPos base,
                                               WoodcutReachSession reachSession,
                                               Map<String, Object> sharedState) {
        int surfaceY = findSurfaceY(world, base);
        boolean skyVisible = world.isSkyVisible(bot.getBlockPos().up());
        if (!WoodcutScaffoldRecoveryHeuristics.shouldRepositionForCleanup(bot.getBlockY(), base.getY(), surfaceY, skyVisible)) {
            return;
        }
        LOGGER.info("Woodcut scaffold cleanup: restoring surface position near {} from {}",
                base.toShortString(), bot.getBlockPos().toShortString());
        recoverSurfacePosition(bot, world, source, Math.max(base.getY(), surfaceY), reachSession, sharedState, "cleanup");
        BlockPos stand = findDryStandableNear(world, base, 3, 4);
        if (stand == null || bot.getBlockPos().getSquaredDistance(stand) <= 4.0D) {
            return;
        }
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                stand,
                stand,
                null,
                null,
                bot.getHorizontalFacing());
        MovementService.execute(source, bot, plan, false, true, true, false);
    }

    private void prepareWoodcutTooling(ServerCommandSource source, ServerPlayerEntity bot) {
        if (selectAxe(bot)) {
            return;
        }
        boolean crafted = ToolProvisionService.ensureAxe(bot, source, source.getPlayer());
        if (crafted) {
            if (selectAxe(bot)) {
                return;
            }
        }
        // Continue with hands/non-tools so woodcut still works when no axe is available.
        selectHandsOrHarmlessItem(bot);
        ChatUtils.sendSystemMessage(source, "No axe available; I'll chop with my hands for now.");
    }

    private boolean selectAxe(ServerPlayerEntity bot) {
        return ensureAxeEquipped(bot);
    }

    private boolean selectLeafTool(ServerPlayerEntity bot) {
        // Prefer shears; otherwise pick an innocuous item/empty hand (never axe/shovel/pick/hoe).
        if (BotActions.selectBestTool(bot, "shears", "")) {
            return true;
        }
        return selectHandsOrHarmlessItem(bot);
    }

    private boolean hasToolKeyword(ServerPlayerEntity bot, String keyword) {
        if (bot == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        String needle = keyword.toLowerCase();
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase();
            if (key.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean selectHandsOrHarmlessItem(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                BotActions.selectHotbarSlot(bot, i);
                return true;
            }
        }
        for (int i = 0; i < 9; i++) {
            var stack = bot.getInventory().getStack(i);
            String key = stack.getItem().getTranslationKey().toLowerCase();
            if (key.contains("sword") || key.contains("axe") || key.contains("pickaxe") || key.contains("shovel") || key.contains("hoe")) {
                continue;
            }
            BotActions.selectHotbarSlot(bot, i);
            return true;
        }
        // Last resort: reset to slot 0 to avoid sticking on a previously selected tool.
        BotActions.selectHotbarSlot(bot, 0);
        return true;
    }

    private void selectScaffoldToolOrHands(ServerPlayerEntity bot) {
        if (hasToolKeyword(bot, "shovel")) {
            BotActions.selectBestTool(bot, "shovel", "axe");
            return;
        }
        selectHandsOrHarmlessItem(bot);
    }

    private boolean ensurePillarStock(ServerPlayerEntity bot,
                                      int needed,
                                      ServerCommandSource source,
                                      int workingY,
                                      WoodcutReachSession reachSession,
                                      Map<String, Object> sharedState) {
        if (bot == null || source == null || TaskService.isServerStopping() || isAbortRequested(bot)) {
            return false;
        }
        int available = countPillarBlocks(bot);
        if (available >= needed) {
            LOGGER.debug("Pillar stock ok: {} blocks available for {} needed", available, needed);
            return true;
        }
        int toGather = needed - available;
        LOGGER.info("Pillar stock shortfall: need {} additional blocks to reach target", toGather);
        CompanionOverheadHologramService.show(bot, "Collecting scaffolding material...", 8_000);
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (gatherNearbyPillarBlocks(bot, world, toGather) > 0 && countPillarBlocks(bot) >= needed) {
            runShortRecoveryDropSweep(bot, source, "local-scaffold-recovery");
            return true;
        }
        int current = countPillarBlocks(bot);
        if (current >= needed) {
            return true;
        }
        int surfaceY = findSurfaceY(world, bot.getBlockPos());
        boolean skyVisible = world.isSkyVisible(bot.getBlockPos().up());
        if (WoodcutScaffoldRecoveryHeuristics.shouldRecoverSurface(bot.getBlockY(), workingY, surfaceY, skyVisible)) {
            LOGGER.warn("Still short on scaffold after local gather. Recovering surface position instead of dirt farming (need={}, have={}, workingY={}, surfaceY={}).",
                    needed, current, workingY, surfaceY);
            boolean recovered = recoverSurfacePosition(bot, world, source, surfaceY, reachSession, sharedState, "scaffold-recovery");
            if (!recovered) {
                LOGGER.warn("Woodcut scaffold recovery failed to regain a usable surface position; skipping elevated target.");
                runShortRecoveryDropSweep(bot, source, "failed-scaffold-recovery");
                return false;
            }
            current = countPillarBlocks(bot);
            if (current < needed) {
                gatherNearbyPillarBlocks(bot, world, needed - current);
            }
        }
        runShortRecoveryDropSweep(bot, source, "post-scaffold-recovery");
        int finalCount = countPillarBlocks(bot);
        LOGGER.info("Pillar stock after bounded recovery: {} (needed {})", finalCount, needed);
        return finalCount >= needed;
    }

    private int gatherNearbyPillarBlocks(ServerPlayerEntity bot, ServerWorld world, int toGather) {
        if (bot == null || world == null || toGather <= 0) {
            return 0;
        }
        int gathered = 0;
        BlockPos origin = bot.getBlockPos();
        int radius = 3;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -1, -radius), origin.add(radius, 1, radius))) {
            if (gathered >= toGather || TaskService.isServerStopping() || isAbortRequested(bot)) {
                break;
            }
            if (pos.equals(origin) || pos.equals(origin.down())) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            Item blockItem = state.getBlock().asItem();
            if (state.isOf(net.minecraft.block.Blocks.SNOW)) {
                BlockPos below = pos.down();
                BlockState belowState = world.getBlockState(below);
                if (PILLAR_BLOCKS.contains(belowState.getBlock().asItem())) {
                    mineBlock(bot, pos, false);
                    state = belowState;
                    pos = below;
                    blockItem = state.getBlock().asItem();
                }
            }
            if (!PILLAR_BLOCKS.contains(blockItem)) {
                continue;
            }
            LookController.faceBlock(bot, pos);
            selectScaffoldToolOrHands(bot);
            if (mineBlock(bot, pos, false)) {
                gathered++;
                LOGGER.debug("Woodcut scaffold collected at {}", pos.toShortString());
            }
        }
        return gathered;
    }

    private boolean recoverSurfacePosition(ServerPlayerEntity bot,
                                           ServerWorld world,
                                           ServerCommandSource source,
                                           int targetSurfaceY,
                                           WoodcutReachSession reachSession,
                                           Map<String, Object> sharedState,
                                           String label) {
        if (bot == null || world == null || source == null || TaskService.isServerStopping() || isAbortRequested(bot)) {
            return false;
        }
        if (world.isSkyVisible(bot.getBlockPos().up()) && bot.getBlockY() + 1 >= targetSurfaceY) {
            return true;
        }
        int available = countPillarBlocks(bot);
        int pillarSteps = WoodcutScaffoldRecoveryHeuristics.desiredPillarRecoverySteps(bot.getBlockY(), targetSurfaceY, available);
        if (pillarSteps > 0) {
            LOGGER.info("Woodcut {}: pillar-up {} step(s) from {} toward surfaceY={}",
                    label, pillarSteps, bot.getBlockPos().toShortString(), targetSurfaceY);
            List<BlockPos> placed = ScaffoldService.pillarUpWithPositions(bot, pillarSteps);
            recordRecoveryPlacements(world, placed, sharedState, reachSession);
            if (world.isSkyVisible(bot.getBlockPos().up()) && bot.getBlockY() + 1 >= targetSurfaceY) {
                return true;
            }
        }
        LOGGER.info("Woodcut {}: switching to synchronous ascent recovery from {} toward surfaceY={}",
                label, bot.getBlockPos().toShortString(), targetSurfaceY);
        Map<String, Object> params = new HashMap<>();
        params.put("ascentToSurface", true);
        params.put("skipTorches", true);
        params.put("allowChestStore", false);
        SkillExecutionResult result = new CollectDirtSkill().execute(new SkillContext(source, new HashMap<>(), params));
        if (!result.success()) {
            LOGGER.warn("Woodcut {} ascent failed: {}", label, result.message());
        }
        return world.isSkyVisible(bot.getBlockPos().up()) || bot.getBlockY() + 1 >= targetSurfaceY;
    }

    private void recordRecoveryPlacements(ServerWorld world,
                                          List<BlockPos> placements,
                                          Map<String, Object> sharedState,
                                          WoodcutReachSession reachSession) {
        if (world == null || placements == null || placements.isEmpty()) {
            return;
        }
        for (BlockPos placed : placements) {
            if (placed == null) {
                continue;
            }
            recordScaffoldPlacement(sharedState, world, placed);
            if (reachSession != null) {
                reachSession.recordPlacement(placed);
            }
        }
    }

    private void runShortRecoveryDropSweep(ServerPlayerEntity bot, ServerCommandSource source, String label) {
        if (bot == null || source == null || isInventoryFull(bot) || TaskService.isServerStopping() || isAbortRequested(bot)) {
            return;
        }
        try {
            DropSweeper.safeSweep(bot, source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), 6.0D, 4.0D);
        } catch (Exception sweepError) {
            LOGGER.warn("Woodcut {} drop sweep failed: {}", label, sweepError.getMessage());
        }
    }

    private int countPillarBlocks(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (PILLAR_BLOCKS.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean ensureAxeEquipped(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        ItemStack best = ItemStack.EMPTY;
        int bestSlot = -1;
        float bestSpeed = 0.0f;
        BlockState ref = net.minecraft.block.Blocks.OAK_LOG.getDefaultState();
        for (int slot = 0; slot < bot.getInventory().size(); slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase();
            if (!key.contains("axe") || key.contains("pickaxe")) {
                continue;
            }
            float speed = stack.getMiningSpeedMultiplier(ref);
            if (stack.isDamageable()) {
                int remaining = stack.getMaxDamage() - stack.getDamage();
                if (remaining < 8) {
                    speed -= 0.5f;
                }
            }
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
                best = stack;
            }
        }
        if (bestSlot == -1 || best.isEmpty()) {
            return false;
        }
        int hotbarSlot = bestSlot < 9 ? bestSlot : bot.getInventory().getSelectedSlot();
        if (bestSlot >= 9) {
            ItemStack hotbarStack = bot.getInventory().getStack(hotbarSlot);
            bot.getInventory().setStack(bestSlot, hotbarStack);
            bot.getInventory().setStack(hotbarSlot, best);
        }
        BotActions.selectHotbarSlot(bot, hotbarSlot);
        return true;
    }

    private boolean hasLineOfSight(ServerPlayerEntity bot, Vec3d targetCenter) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        Vec3d eye = bot.getEyePos();
        RaycastContext ctx = new RaycastContext(
                eye,
                targetCenter,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot);
        var hit = world.raycast(ctx);
        return hit != null && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(BlockPos.ofFloored(targetCenter));
    }

    private boolean isTrunkWithinReach(ServerWorld world, BlockPos base, ServerPlayerEntity bot) {
        List<BlockPos> trunk = TreeDetector.collectTrunk(world, base);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        for (BlockPos log : trunk) {
            if (botPos.squaredDistanceTo(Vec3d.ofCenter(log)) <= REACH_DISTANCE_SQ) {
                return true;
            }
        }
        return false;
    }

    private boolean moveUnderTarget(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos stand = findColumnStand(world, target);
        if (stand == null) {
            stand = findDryStandableNear(world, target, 1, 3);
        }
        if (stand == null) {
            LOGGER.warn("Woodcut approach: no dry stand under/near {} (reason=no dry stand)", target.toShortString());
            return false;
        }
        breakSoftBlock(world, bot, stand);
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                stand,
                stand,
                null,
                null,
                bot.getHorizontalFacing());
        MovementService.MovementResult res = MovementService.execute(source, bot, plan, false, true, true, false);
        if (!res.success()) {
            MovementService.clearRecentWalkAttempt(bot.getUuid());
        }
        return bot.getBlockPos().getSquaredDistance(stand) <= 4.0 || isWithinReach(bot, target);
    }

    private void forcePillarToward(ServerPlayerEntity bot,
                                   ServerCommandSource source,
                                   BlockPos target,
                                   WoodcutReachSession reachSession,
                                   Map<String, Object> sharedState) {
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        int needed = target.getY() - bot.getBlockY();
        if (needed <= 0) {
            return;
        }
        if (!ensurePillarStock(bot, needed, source, target.getY(), reachSession, sharedState)) {
            LOGGER.warn("Pillar stock insufficient (need {}) to reach {}", needed, target.toShortString());
            return;
        }
        moveUnderTarget(source, bot, target);
        pillarUp(bot, needed, reachSession, source, sharedState);
    }

    private BlockPos findColumnStand(ServerWorld world, BlockPos target) {
        BlockPos cursor = target.down();
        for (int i = 0; i < 6 && cursor.getY() > world.getBottomY(); i++) {
            BlockPos foot = cursor.toImmutable();
            if (isUsableWoodcutStand(world, foot)) {
                return foot;
            }
            cursor = cursor.down();
        }
        return null;
    }

    private MineAttemptResult mineWithRetries(ServerPlayerEntity bot,
                                              ServerCommandSource source,
                                              BlockPos target,
                                              WoodcutReachSession reachSession,
                                              boolean preferAxe,
                                              Map<String, Object> sharedState,
                                              BlockPos associatedTargetBase) {
        WoodcutFailureReason lastReason = WoodcutFailureReason.PATH_OR_REACH_FAILURE;
        String lastDetail = "unknown";
        for (int attempt = 0; attempt < MAX_RETRY_MINING; attempt++) {
            LOGGER.info("Woodcut mining attempt {} for {}", attempt + 1, target.toShortString());
            if (horizontalDistance(bot.getBlockPos(), target) > 2.5) {
                moveUnderTarget(source, bot, target);
            }
            if (!prepareReach(bot, source, target, reachSession, sharedState)) {
                LOGGER.warn("Prepare reach failed for {} on attempt {}", target.toShortString(), attempt + 1);
                lastReason = WoodcutFailureReason.PATH_OR_REACH_FAILURE;
                lastDetail = "prepare-reach-failed";
                continue;
            }
            boolean wasSneak = bot.isSneaking();
            if (reachSession != null && reachSession.hasPlacements()) {
                bot.setSneaking(true);
            }
            MineAttemptResult mined = mineBlockDetailed(bot, target, preferAxe, associatedTargetBase, reachSession);
            if (reachSession != null && reachSession.hasPlacements()) {
                bot.setSneaking(wasSneak);
            }
            if (mined.success()) {
                return mined;
            }
            if (mined.failureReason() == WoodcutFailureReason.PROTECTED_AT_MINING) {
                return mined;
            }
            lastReason = mined.failureReason();
            lastDetail = mined.detail();
            if ("los-blocked".equals(mined.detail()) && attempt < MAX_TRUNK_LOS_RECOVERY_ATTEMPTS) {
                LOGGER.info("Woodcut mining: LOS recovery for {} after failed attempt {}",
                        target.toShortString(), attempt + 1);
                // If the target is above us and LOS is blocked by the trunk itself,
                // repositioning horizontally won't help — we need to pillar up.
                int heightDiff = target.getY() - bot.getBlockY();
                if (heightDiff >= 3 && (reachSession == null || !reachSession.hasPlacements())) {
                    int needed = heightDiff - 1;
                    LOGGER.info("Woodcut mining: LOS blocked by trunk, pillaring {} to reach {}",
                            needed, target.toShortString());
                    pillarUp(bot, needed, reachSession, source, sharedState);
                } else {
                    tryReposition(bot, source, associatedTargetBase != null ? associatedTargetBase : target, reachSession, sharedState);
                }
            }
        }
        LOGGER.warn("Failed to mine {} after {} attempts", target.toShortString(), MAX_RETRY_MINING);
        return new MineAttemptResult(false, lastReason, lastDetail);
    }

    private double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private BlockPos findStandableNear(ServerWorld world, BlockPos center, int radius, int ySpan) {
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -ySpan, -radius), center.add(radius, ySpan, radius))) {
            BlockPos foot = pos.toImmutable();
            if (isUsableWoodcutStand(world, foot)) {
                return foot;
            }
        }
        return null;
    }

    private BlockPos findDryStandableNear(ServerWorld world, BlockPos center, int radius, int ySpan) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -ySpan, -radius), center.add(radius, ySpan, radius))) {
            BlockPos foot = pos.toImmutable();
            if (isUsableWoodcutStand(world, foot)) {
                candidates.add(foot);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        return candidates.get(0);
    }

    private boolean isUsableWoodcutStand(ServerWorld world, BlockPos foot) {
        return isDryStableWoodcutStand(world, foot) || isMinorSlopeWoodcutStand(world, foot);
    }

    private boolean isDryStableWoodcutStand(ServerWorld world, BlockPos foot) {
        if (!isDryWoodcutStandCell(world, foot)) {
            return false;
        }
        int stableExits = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = foot.offset(direction);
            if (isDryWoodcutStandCell(world, neighbor)) {
                stableExits++;
            }
        }
        return stableExits >= 1;
    }

    private boolean isMinorSlopeWoodcutStand(ServerWorld world, BlockPos foot) {
        if (!isDryWoodcutStandCell(world, foot)) {
            return false;
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = foot.offset(direction);
            if (isDryWoodcutStandCell(world, neighbor)
                    || isDryWoodcutStandCell(world, neighbor.down())
                    || isDryWoodcutStandCell(world, neighbor.up())) {
                return true;
            }
        }
        return false;
    }

    private boolean isDryWoodcutStandCell(ServerWorld world, BlockPos foot) {
        if (world == null || foot == null || !world.isChunkLoaded(foot)) {
            return false;
        }
        BlockPos head = foot.up();
        BlockPos below = foot.down();
        if (!world.isChunkLoaded(head) || !world.isChunkLoaded(below)) {
            return false;
        }
        if (!world.getFluidState(foot).isEmpty()
                || !world.getFluidState(head).isEmpty()
                || !world.getFluidState(below).isEmpty()) {
            return false;
        }
        if (!world.getBlockState(foot).getCollisionShape(world, foot).isEmpty()) {
            return false;
        }
        if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
            return false;
        }
        return !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
    }

    private boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        return botPos.squaredDistanceTo(center) <= REACH_DISTANCE_SQ;
    }

    private void clearHeadroom(ServerPlayerEntity bot) {
        clearHeadroom(bot, null, null);
    }

    private void clearHeadroom(ServerPlayerEntity bot,
                               BlockPos associatedTargetBase,
                               WoodcutReachSession reachSession) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos head = bot.getBlockPos().up();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = head.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW)) {
                        breakLeaf(bot, pos, associatedTargetBase, reachSession);
                    }
                }
            }
        }
    }

    private void clearObstructionAlongRay(ServerPlayerEntity bot, Vec3d targetCenter, BlockPos associatedTargetBase) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        RaycastContext ctx = new RaycastContext(
                bot.getEyePos(),
                targetCenter,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot);
        var hit = world.raycast(ctx);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos hitPos = hit.getBlockPos();
        BlockPos targetPos = BlockPos.ofFloored(targetCenter);
        if (hitPos.equals(targetPos)) {
            return;
        }
        BlockState state = world.getBlockState(hitPos);
        if (state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW) || state.isIn(BlockTags.LOGS)) {
            LOGGER.debug("Clearing LOS obstruction at {}", hitPos.toShortString());
            breakLeaf(bot, hitPos, associatedTargetBase, null);
        }
    }

    private void logFailedBaseSkips(BlockPos origin,
                                    Set<BlockPos> failedBases,
                                    int radius,
                                    String stage,
                                    Map<BlockPos, String> failedBaseReasons) {
        if (origin == null || failedBases == null || failedBases.isEmpty()) {
            return;
        }
        int radiusSq = radius * radius;
        for (BlockPos failedBase : failedBases) {
            if (failedBase == null || failedBase.getSquaredDistance(origin) > radiusSq) {
                continue;
            }
            LOGGER.info("Woodcut relocation: skipping failed base {} during {} search (reason={})",
                    failedBase.toShortString(),
                    stage,
                    failedBaseReasons == null ? "previous-failure" : failedBaseReasons.getOrDefault(failedBase, "previous-failure"));
        }
    }

    private void plantSaplings(ServerPlayerEntity bot, ServerCommandSource source, BlockPos base) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        int toPlant = 0;
        for (Item sap : SAPLING_ITEMS) {
            toPlant += countItem(bot, sap);
        }
        if (toPlant <= 0) {
            return;
        }
        int radius = 4;
        for (BlockPos soil : BlockPos.iterate(base.add(-radius, -1, -radius), base.add(radius, 1, radius))) {
            if (toPlant <= 0) {
                break;
            }
            BlockPos target = soil.up();
            if (!canPlantSapling(world, soil, target)) {
                continue;
            }
            List<Item> availableSaplings = availableSaplingItems(bot);
            if (availableSaplings.isEmpty()) {
                break;
            }
            if (BotActions.placeBlockAt(bot, target, Direction.UP, availableSaplings)) {
                toPlant--;
                LOGGER.info("Planted sapling at {}", target.toShortString());
            }
        }
    }

    private boolean canPlantSapling(ServerWorld world, BlockPos soil, BlockPos target) {
        BlockState soilState = world.getBlockState(soil);
        BlockState targetState = world.getBlockState(target);
        if (!targetState.isAir()) {
            return false;
        }
        if (!soilState.isIn(BlockTags.DIRT) && !soilState.isOf(net.minecraft.block.Blocks.FARMLAND)) {
            return false;
        }
        int checkRadius = 3;
        for (BlockPos pos : BlockPos.iterate(target.add(-checkRadius, -1, -checkRadius), target.add(checkRadius, 1, checkRadius))) {
            if (world.getBlockState(pos).isIn(BlockTags.SAPLINGS)) {
                return false;
            }
        }
        return true;
    }

    private List<Item> availableSaplingItems(ServerPlayerEntity bot) {
        List<Item> found = new ArrayList<>();
        for (Item sap : SAPLING_ITEMS) {
            if (countItem(bot, sap) > 0) {
                found.add(sap);
            }
        }
        return found;
    }

    private int countItem(ServerPlayerEntity bot, Item item) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countEmptySlots(ServerPlayerEntity bot) {
        int empty = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private boolean isInventoryFull(ServerPlayerEntity bot) {
        return bot != null && bot.getInventory().getEmptySlot() == -1;
    }

    private int countWood(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (isWoodStack(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }


    private boolean isWoodStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.isIn(ItemTags.LOGS_THAT_BURN) || stack.isIn(ItemTags.PLANKS)) {
            return true;
        }
        if (stack.isOf(Items.STICK)) {
            return true;
        }
        if (stack.getItem() instanceof BlockItem bi) {
            BlockState state = bi.getBlock().getDefaultState();
            return state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS);
        }
        return false;
    }

    private boolean isWoodcutOffloadCandidate(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (ChestStoreService.isOffloadProtected(stack)) {
            return false;
        }
        if (stack.isOf(Items.CHEST) || stack.isOf(Items.CRAFTING_TABLE)) {
            return false;
        }
        if (PILLAR_BLOCKS.contains(stack.getItem())) {
            return false;
        }
        if (isWoodStack(stack)) {
            return true;
        }
        if (SAPLING_ITEMS.contains(stack.getItem())) {
            return true;
        }
        if (SEED_ITEMS.contains(stack.getItem())) {
            return true;
        }
        if (RAW_FOOD_ITEMS.contains(stack.getItem())) {
            return true;
        }
        if (stack.isOf(Items.LEAF_LITTER)) {
            return true;
        }
        return stack.isOf(Items.APPLE);
    }

    private void cleanupNearbyScaffold(ServerPlayerEntity bot, BlockPos base, Map<String, Object> sharedState) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world) || base == null || sharedState == null) {
            return;
        }
        Set<Long> memory = getScaffoldMemory(sharedState, world, false);
        if (memory == null || memory.isEmpty()) {
            return;
        }
        int radius = 10;
        List<Long> snapshot = new ArrayList<>(memory);
        for (Long packed : snapshot) {
            if (packed == null) {
                continue;
            }
            BlockPos pos = BlockPos.fromLong(packed);
            if (Math.abs(pos.getX() - base.getX()) > radius
                    || Math.abs(pos.getY() - base.getY()) > 12
                    || Math.abs(pos.getZ() - base.getZ()) > radius) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                memory.remove(packed);
                continue;
            }
            if (!PILLAR_BLOCKS.contains(state.getBlock().asItem())) {
                memory.remove(packed);
                continue;
            }
            // Avoid touching actual logs/planks to prevent structure damage
            if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) {
                continue;
            }
            if (mineBlock(bot, pos, false) || world.getBlockState(pos).isAir()) {
                memory.remove(packed);
            }
        }
    }

    private BlockPos findNearestOverheadLog(ServerWorld world, BlockPos botPos, BlockPos base) {
        BlockPos best = null;
        int bestY = Integer.MIN_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 6; dy++) {
                    BlockPos candidate = botPos.add(dx, dy, dz);
                    if (!world.getBlockState(candidate).isIn(BlockTags.LOGS)) {
                        continue;
                    }
                    if (candidate.getY() < botPos.getY()) {
                        continue;
                    }
                    if (Math.abs(candidate.getX() - base.getX()) > 2 || Math.abs(candidate.getZ() - base.getZ()) > 2) {
                        continue; // stay near trunk column
                    }
                    if (candidate.getY() > bestY) {
                        bestY = candidate.getY();
                        best = candidate.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private static int getIntParameter(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private boolean isAbortRequested(ServerPlayerEntity bot) {
        return bot != null && (TaskService.isServerStopping() || SkillManager.shouldAbortSkill(bot) || !TaskService.hasActiveTask(bot.getUuid()));
    }

    private void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Minimal drop sweep hook to avoid pulling in full skill stack.
     */
    private static final class DropSweeper {
        static void safeSweep(ServerPlayerEntity bot, ServerCommandSource source, double radius, double verticalRange) {
            try {
                net.wcfcarolina13.GameAI.DropSweeper.sweep(
                        source,
                        radius,
                        verticalRange,
                        Math.max(12, (int) Math.ceil(radius * 2)),
                        12_000L
                );
            } catch (Exception e) {
                LOGGER.warn("Drop sweep after woodcut failed: {}", e.getMessage());
            }
        }
    }
}
