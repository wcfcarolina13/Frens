package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
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
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.CraftingHelper;
import net.shasankp000.GameAI.services.ChestStoreService;
import net.shasankp000.GameAI.services.BlockInteractionService;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.SkillResumeService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.skills.SkillPreferences;
import net.shasankp000.GameAI.skills.support.TreeDetector;
import net.shasankp000.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
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
    private static final int MAX_FAILURES = 3;
    private static final int SUNSET_TIME_OF_DAY = 12000;
    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks (survival reach)
    private static final long PILLAR_STEP_DELAY_MS = 160L;
    private static final long MINING_TIMEOUT_MS = 12_000L;
    private static final int MAX_RETRY_MINING = 5;
    private static final int MAX_LOS_CLEAR_ATTEMPTS = 3;
    private static final List<Item> PILLAR_BLOCKS = List.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.GRAVEL,
            Items.SAND,
            Items.RED_SAND,
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.NETHERRACK
    );
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

    @Override
    public String name() {
        return "woodcut";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = Objects.requireNonNull(source.getPlayer(), "player");
        SkillResumeService.consumeResumeIntent(bot.getUuid());

        boolean internal = getBooleanParameter(context.parameters(), "internal", false);
        int defaultTrees = internal ? DEFAULT_TREE_COUNT_INTERNAL : DEFAULT_TREE_COUNT_STANDALONE;
        int targetTrees = Math.max(1, getIntParameter(context.parameters(), "count", defaultTrees));
        int searchRadius = Math.max(6, getIntParameter(context.parameters(), "searchRadius", DEFAULT_SEARCH_RADIUS));
        int verticalRange = Math.max(4, getIntParameter(context.parameters(), "verticalRange", DEFAULT_VERTICAL_RANGE));
        int startTimeOfDay = bot.getEntityWorld() != null ? (int) (bot.getEntityWorld().getTimeOfDay() % 24000L) : 0;
        if (!internal && startTimeOfDay >= SUNSET_TIME_OF_DAY) {
            return SkillExecutionResult.failure("It's getting late; I'll cut trees tomorrow.");
        }

        if (!ensureAxeAvailable(source, bot)) {
            return SkillExecutionResult.failure("Out of axes and missing materials to craft one.");
        }

        Set<BlockPos> visitedBases = new HashSet<>();
        int felled = 0;
        int failures = 0;
        boolean swept = false;
        BlockPos startPos = bot.getBlockPos();
        int minX = startPos.getX();
        int maxX = startPos.getX();
        int minY = startPos.getY();
        int maxY = startPos.getY();
        int minZ = startPos.getZ();
        int maxZ = startPos.getZ();

        while (felled < targetTrees && failures < MAX_FAILURES) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("woodcut paused due to nearby threat.");
            }
            if (!internal) {
                int timeOfDay = (int) (bot.getEntityWorld().getTimeOfDay() % 24000L);
                if (timeOfDay >= SUNSET_TIME_OF_DAY) {
                    ChatUtils.sendSystemMessage(source, "It's getting late; I'm stopping woodcut for the day.");
                    break;
                }
            }
            if (!ensureWoodSpaceOrDeposit(source, bot)) {
                return SkillExecutionResult.failure("Inventory full; couldn't deposit wood into any chest.");
            }
            logDetectionSummary(bot, searchRadius, verticalRange, visitedBases);
            Optional<TreeDetector.TreeTarget> targetOpt = TreeDetector.findNearestTree(bot, searchRadius, verticalRange, visitedBases);
            if (targetOpt.isEmpty()) {
                logDetectionDiagnostics(bot, searchRadius, verticalRange, visitedBases);
                Optional<BlockPos> floaters = TreeDetector.findFloatingLog(bot, searchRadius, verticalRange, visitedBases);
                if (floaters.isPresent()) {
                    LOGGER.warn("Woodcut: cleaning floating log at {}", floaters.get().toShortString());
                    TreeDetector.TreeTarget synthetic = new TreeDetector.TreeTarget(floaters.get(), floaters.get(), 1);
                    targetOpt = Optional.of(synthetic);
                }
                Optional<BlockPos> stray = TreeDetector.findNearestLooseLog(bot, searchRadius, verticalRange, visitedBases);
                if (stray.isEmpty()) {
                    Optional<BlockPos> anyLog = TreeDetector.findNearestAnyLog(bot, searchRadius, verticalRange, visitedBases);
                    if (anyLog.isEmpty()) {
                        LOGGER.warn("Woodcut: found no detectable trees/logs within {}x{}", searchRadius, verticalRange);
                        break;
                    }
                    LOGGER.warn("Woodcut: falling back to permissive log at {}", anyLog.get().toShortString());
                    TreeDetector.TreeTarget synthetic = new TreeDetector.TreeTarget(anyLog.get(), anyLog.get(), 1);
                    targetOpt = Optional.of(synthetic);
                } else {
                    LOGGER.warn("Woodcut: using stray log cleanup at {}", stray.get().toShortString());
                    TreeDetector.TreeTarget synthetic = new TreeDetector.TreeTarget(stray.get(), stray.get(), 1);
                    targetOpt = Optional.of(synthetic);
                }
            }
            TreeDetector.TreeTarget target = targetOpt.get();
            visitedBases.add(target.base());

            // Track footprint to size post-run drop sweep.
            BlockPos posNow = bot.getBlockPos();
            minX = Math.min(minX, posNow.getX());
            maxX = Math.max(maxX, posNow.getX());
            minY = Math.min(minY, posNow.getY());
            maxY = Math.max(maxY, posNow.getY());
            minZ = Math.min(minZ, posNow.getZ());
            maxZ = Math.max(maxZ, posNow.getZ());

            if (!approachBase(source, bot, target.base())) {
                failures++;
                continue;
            }
            if (!fellTree(source, bot, target)) {
                failures++;
                continue;
            }
            felled++;
            ChatUtils.sendSystemMessage(source, "Tree cut (" + felled + "/" + targetTrees + ")");
        }

        if (felled > 0 && !swept) {
            ensureWoodSpaceOrDeposit(source, bot);
            double horizRadius = Math.max(6.0,
                    Math.max(Math.abs(maxX - startPos.getX()), Math.abs(minX - startPos.getX())) + 3.0);
            double vertRange = Math.max(4.0, (maxY - minY) + 3.0);
            DropSweeper.safeSweep(bot, source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), horizRadius, vertRange);
            swept = true;
        }

        if (felled == 0) {
            return SkillExecutionResult.failure("No valid trees nearby. Try moving closer or adjust radius.");
        }
        if (failures >= MAX_FAILURES) {
            return SkillExecutionResult.failure("Stopped after cutting " + felled + " tree(s); repeated failures reaching remaining targets (path/LOS/inventory).");
        }
        if (felled < targetTrees) {
            return SkillExecutionResult.success("Stopped after cutting " + felled + " tree(s).");
        }
        return SkillExecutionResult.success("Cut " + felled + " tree(s).");
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

    private boolean fellTree(ServerCommandSource source, ServerPlayerEntity bot, TreeDetector.TreeTarget target) {
        if (!(source.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        List<BlockPos> placedPillar = new ArrayList<>();
        boolean success = false;
        int unreachable = 0;
        try {
            Set<BlockPos> broken = new HashSet<>();

            // Fell straight trunk first.
            List<BlockPos> trunk = TreeDetector.collectTrunk(world, target.base());
            for (BlockPos log : trunk) {
                if (!mineWithRetries(bot, source, log, placedPillar, true)) {
                    LOGGER.warn("Failed to break trunk segment {} for base {}", log.toShortString(), target.base().toShortString());
                    return false;
                }
                broken.add(log);
            }

            // Then clean up nearby connected logs (limited radius) without pursuit-cheese.
            Set<BlockPos> remaining = new HashSet<>(TreeDetector.collectConnectedLogs(world, target.base(), 3, 8));
            remaining.removeAll(broken);

            while (!remaining.isEmpty()) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    return false;
                }
                BlockPos next = remaining.stream()
                        .min((a, b) -> {
                            int cmpY = Integer.compare(a.getY(), b.getY());
                            if (cmpY != 0) return cmpY;
                            double da = bot.getBlockPos().getSquaredDistance(a);
                            double db = bot.getBlockPos().getSquaredDistance(b);
                            return Double.compare(da, db);
                        })
                        .orElse(null);
                if (next == null) {
                    break;
                }
                if (mineWithRetries(bot, source, next, placedPillar, true)) {
                    remaining.remove(next);
                    remaining.addAll(TreeDetector.collectConnectedLogs(world, target.base(), 3, 8));
                    remaining.removeAll(broken);
                    broken.add(next);
                } else {
                    LOGGER.warn("Abandoning unreachable log {} for base {}", next.toShortString(), target.base().toShortString());
                    remaining.remove(next);
                    unreachable++;
                    if (unreachable >= 4) {
                        LOGGER.warn("Stopping cleanup for base {} after {} unreachable logs", target.base().toShortString(), unreachable);
                        break;
                    }
                }
            }
            success = true;
            return true;
        } finally {
            if (success) {
                plantSaplings(bot, source, target.base());
            }
            if (!placedPillar.isEmpty()) {
                descendAndCleanup(bot, placedPillar);
                cleanupNearbyScaffold(bot, target.base());
                cleanupNearbyScaffold(bot, bot.getBlockPos());
            }
            if (!success) {
                LOGGER.warn("Woodcut cleanup: pillar removed after failure");
            }
        }
    }

    private boolean approachBase(ServerCommandSource source, ServerPlayerEntity bot, BlockPos base) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        clearBaseObstacles(world, bot, base);
        if (isTrunkWithinReach(world, base, bot)) {
            return true;
        }
        // First try a low, human-like standable near the base.
        BlockPos nearby = findStandableNear(world, base, 4, 4);
        if (nearby != null) {
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    nearby,
                    nearby,
                    null,
                    null,
                    bot.getHorizontalFacing());
            MovementService.MovementResult res = MovementService.execute(source, bot, plan, false, true, false, false);
            if (res.success() || isTrunkWithinReach(world, base, bot)) {
                return true;
            }
            MovementService.clearRecentWalkAttempt(bot.getUuid());
        }
        // Fallback to planner if simple stand failed.
        MovementService.MovementOptions options = MovementService.MovementOptions.skillLoot();
        Optional<MovementService.MovementPlan> planOpt = MovementService.planLootApproach(bot, base, options);
        if (planOpt.isPresent()) {
            MovementService.MovementResult result = MovementService.execute(source, bot, planOpt.get(), false, true, false, false);
            if (result.success() || isTrunkWithinReach(world, base, bot)) {
                return true;
            }
            LOGGER.warn("Failed to approach tree base {}: {}", base.toShortString(), result.detail());
        }
        // Last resort: try to pillar from here to reach the trunk directly.
        List<BlockPos> tempPillar = new ArrayList<>();
        if (prepareReach(bot, source, base, tempPillar)) {
            descendAndCleanup(bot, tempPillar);
            return true;
        }
        LOGGER.warn("No path to tree base {}", base.toShortString());
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

    private boolean ensureWoodSpaceOrDeposit(ServerCommandSource source, ServerPlayerEntity bot) {
        if (bot == null || source == null) {
            return true;
        }
        int empty = countEmptySlots(bot);
        int woodCount = countWood(bot);
        boolean needsDeposit = empty <= 2 || woodCount > 256;
        LOGGER.info("Woodcut inventory: emptySlots={} woodCount={} needsDeposit={}", empty, woodCount, needsDeposit);
        if (!needsDeposit) {
            return true;
        }
        if (woodCount <= 0) {
            LOGGER.warn("Inventory full but no wood detected for deposit; pausing woodcut.");
            return false;
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

        BlockPos placed = ChestStoreService.placeChestNearBot(source, bot, true);
        if (placed != null) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, bot, placed, this::isWoodStack);
            LOGGER.info("Woodcut deposit attempt (placed chest): chest={} moved={}", placed.toShortString(), moved);
            if (moved > 0) {
                ChatUtils.sendSystemMessage(source, "Deposited wood into the new chest.");
                return true;
            }
        }

        LOGGER.warn("Inventory full and couldn't deposit wood (no reachable/usable chest, or chests are full).");
        ChatUtils.sendSystemMessage(source, "Inventory is full and I couldn't deposit wood (no reachable chest or chests are full).");
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
                                 List<BlockPos> placedPillar) {
        boolean onPillar = placedPillar != null && !placedPillar.isEmpty();
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
            if (tryReposition(bot, source, target)) {
                LOGGER.debug("Woodcut reach: repositioned near {}", target.toShortString());
                return true;
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
        if (pillarUp(bot, needed, placedPillar, source)) {
            return true;
        }
        // Emergency: try a single underfoot placement to break climb-stall
        LOGGER.warn("Pillar placement failed; attempting emergency underfoot scaffold");
        return emergencyStep(bot, placedPillar);
    }

    private boolean tryReposition(ServerPlayerEntity bot, ServerCommandSource source, BlockPos target) {
        MovementService.MovementOptions options = MovementService.MovementOptions.skillLoot();
        Optional<MovementService.MovementPlan> planOpt = MovementService.planLootApproach(bot, target, options);
        if (planOpt.isEmpty()) {
            return false;
        }
        MovementService.MovementResult res = MovementService.execute(source, bot, planOpt.get(), false, true, false, false);
        return res.success() || isWithinReach(bot, target);
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
            MovementService.execute(source, bot, plan, false);
            if (bot.getBlockY() <= target.getY() + 1) {
                return isWithinReach(bot, target);
            }
        }
        return false;
    }

    private boolean pillarUp(ServerPlayerEntity bot, int steps, List<BlockPos> placedPillar, ServerCommandSource source) {
        if (steps <= 0) {
            return true;
        }
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (!ensurePillarStock(bot, steps, source)) {
            LOGGER.warn("No scaffold blocks available to pillar up {} steps", steps);
            return false;
        }
        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        LOGGER.info("Woodcut pillar: starting {} steps from {} with {} scaffold blocks",
                steps, bot.getBlockPos().toShortString(), countPillarBlocks(bot));
        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
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
            boolean placed = tryPlaceScaffold(bot, candidate);
            if (!placed) {
                LOGGER.warn("Failed to place scaffold block at {}", candidate.toShortString());
                bot.setSneaking(wasSneaking);
                return false;
            }
            placedPillar.add(candidate.toImmutable());
            LOGGER.debug("Woodcut pillar: placed at {}", candidate.toShortString());
            sleepQuiet(PILLAR_STEP_DELAY_MS);
        }
        bot.setSneaking(wasSneaking);
        return true;
    }

    private void logDetectionSummary(ServerPlayerEntity bot, int radius, int vertical, Set<BlockPos> visited) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos origin = bot.getBlockPos();
        int totalLogs = 0;
        int visitedLogs = 0;
        int humanProx = 0;
        int protectedCount = 0;
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -vertical, -radius), origin.add(radius, vertical, radius))) {
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            totalLogs++;
            if (visited != null && visited.contains(pos)) {
                visitedLogs++;
                continue;
            }
            if (TreeDetector.isNearHumanBlocks(world, pos, 3)) {
                humanProx++;
                continue;
            }
            if (TreeDetector.isProtected(world, pos)) {
                protectedCount++;
                continue;
            }
            double d = origin.getSquaredDistance(pos);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = pos.toImmutable();
            }
        }
        LOGGER.info("Woodcut detect: logs={} visited={} humanProx={} protected={} nearest={}",
                totalLogs, visitedLogs, humanProx, protectedCount, nearest == null ? "none" : nearest.toShortString());
    }

    private void logDetectionDiagnostics(ServerPlayerEntity bot, int radius, int vertical, Set<BlockPos> visited) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos origin = bot.getBlockPos();
        int totalLogs = 0;
        int visitedCount = 0;
        int soilFail = 0;
        int leafFail = 0;
        int humanFail = 0;
        int protectedFail = 0;
        int heightFail = 0;
        int sample = 0;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -vertical, -radius), origin.add(radius, vertical, radius))) {
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            totalLogs++;
            if (visited != null && visited.contains(pos)) {
                visitedCount++;
                continue;
            }
            Optional<TreeDetector.TreeTarget> detected = TreeDetector.detectTreeAt(world, pos);
            if (detected.isPresent()) {
                continue;
            }
            sample++;
            if (!TreeDetector.isValidSoil(world.getBlockState(pos.down()))) {
                soilFail++;
            }
            if (!world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                heightFail++;
            }
            if (!TreeDetector.hasLeavesNearby(world, pos, 4, 4)) {
                leafFail++;
            }
            if (TreeDetector.isNearHumanBlocks(world, pos, 4)) {
                humanFail++;
            }
            if (TreeDetector.isProtected(world, pos)) {
                protectedFail++;
            }
            if (sample <= 3) {
                LOGGER.info("Woodcut detect reject sample {} at {} (soilFail={}, leafFail={}, humanFail={}, protected={})",
                        sample, pos.toShortString(), !TreeDetector.isValidSoil(world.getBlockState(pos.down())),
                        !TreeDetector.hasLeavesNearby(world, pos, 4, 4),
                        TreeDetector.isNearHumanBlocks(world, pos, 4),
                        TreeDetector.isProtected(world, pos));
            }
        }
        LOGGER.info("Woodcut detect diagnostics: logs={} visited={} soilFail={} leafFail={} humanFail={} protectedFail={}",
                totalLogs, visitedCount, soilFail, leafFail, humanFail, protectedFail);
    }

    private void clearBlockingLeaves(ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        selectLeafTool(bot);
        // Clear a modest shell around the target to avoid infinite LOS spam
        int radius = 3;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos leafPos = target.add(dx, dy, dz);
                    BlockState state = world.getBlockState(leafPos);
                    if (!state.isIn(BlockTags.LEAVES)) {
                        continue;
                    }
                    breakLeaf(bot, leafPos);
                }
            }
        }
    }

    private void breakLeaf(ServerPlayerEntity bot, BlockPos pos) {
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        if (SkillManager.shouldAbortSkill(bot)) {
            return;
        }
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        if (botPos.squaredDistanceTo(center) > REACH_DISTANCE_SQ) {
            return;
        }
        LookController.faceBlock(bot, pos);
        CompletableFuture<String> mining;
        try {
            mining = MiningTool.mineBlock(bot, pos);
        } catch (Exception e) {
            LOGGER.warn("Leaf break scheduling failed at {}: {}", pos.toShortString(), e.getMessage());
            return;
        }
        try {
            mining.get(3_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            mining.cancel(true);
        }
    }

    private boolean tryPlaceScaffold(ServerPlayerEntity bot, BlockPos target) {
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
        ensureSupportBlock(bot, placePos);
        if (BotActions.placeBlockAt(bot, placePos, Direction.UP, PILLAR_BLOCKS)) {
            return true;
        }
        // Try nearby offsets to recover from collision/leaf interference
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos alt = placePos.offset(dir);
            if (!isPlaceableTarget(world, alt)) {
                breakSoftBlock(world, bot, alt);
            }
            ensureSupportBlock(bot, alt);
            if (BotActions.placeBlockAt(bot, alt, Direction.UP, PILLAR_BLOCKS)) {
                LOGGER.debug("Woodcut pillar: placed via offset {} at {}", dir, alt.toShortString());
                return true;
            }
        }
        return false;
    }

    private void ensureSupportBlock(ServerPlayerEntity bot, BlockPos target) {
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

    private boolean emergencyStep(ServerPlayerEntity bot, List<BlockPos> placedPillar) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        BlockPos foot = bot.getBlockPos();
        BlockPos below = foot.down();
        breakSoftBlock(world, bot, foot);
        breakSoftBlock(world, bot, below);
        if (tryPlaceScaffold(bot, below)) {
            placedPillar.add(below.toImmutable());
            LOGGER.info("Emergency scaffold placed at {}", below.toShortString());
            return true;
        }
        LOGGER.warn("Emergency scaffold placement failed at {}", below.toShortString());
        return false;
    }

    private boolean mineBlock(ServerPlayerEntity bot, BlockPos pos, boolean preferAxe) {
        if (SkillManager.shouldAbortSkill(bot)) {
            return false;
        }
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double distSq = botPos.squaredDistanceTo(center);
        if (distSq > REACH_DISTANCE_SQ) {
            LOGGER.warn("Refusing to mine {} - out of reach (dist={})", pos.toShortString(), Math.sqrt(distSq));
            return false;
        }
        if (!hasLineOfSight(bot, center)) {
            LOGGER.warn("LOS blocked for {} from eye {} (bot={})", pos.toShortString(), bot.getEyePos(), bot.getBlockPos().toShortString());
            clearHeadroom(bot);
            clearBlockingLeaves(bot, pos);
            clearObstructionAlongRay(bot, center);
            if (!hasLineOfSight(bot, center)) {
                return false;
            }
        }
        LookController.faceBlock(bot, pos);
        if (preferAxe) {
            ensureAxeEquipped(bot);
        }
        CompletableFuture<String> miningFuture = MiningTool.mineBlock(bot, pos);
        try {
            String result = miningFuture.get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return result != null && result.toLowerCase().contains("complete");
        } catch (TimeoutException timeout) {
            LOGGER.warn("Mining {} timed out", pos.toShortString());
            miningFuture.cancel(true);
            return false;
        } catch (Exception e) {
            LOGGER.error("Failed to mine {}: {}", pos.toShortString(), e.getMessage());
            miningFuture.cancel(true);
            return false;
        }
    }

    private void descendAndCleanup(ServerPlayerEntity bot, List<BlockPos> placedPillar) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        LOGGER.info("Woodcut pillar: descending, blocks placed={}", placedPillar.size());
        Collections.reverse(placedPillar);
        for (BlockPos placed : placedPillar) {
            if (SkillManager.shouldAbortSkill(bot)) {
                bot.setSneaking(wasSneaking);
                return;
            }
            if (world.getBlockState(placed).isAir()) {
                continue;
            }
            LookController.faceBlock(bot, placed);
            mineBlock(bot, placed, false);
            sleepQuiet(80L);
        }
        bot.setSneaking(wasSneaking);
    }

    private boolean ensureAxeAvailable(ServerCommandSource source, ServerPlayerEntity bot) {
        if (selectAxe(bot)) {
            return true;
        }
        int crafted = CraftingHelper.craftGeneric(source, bot, source.getPlayer(), "axe", 1, null);
        if (crafted > 0) {
            return selectAxe(bot);
        }
        ChatUtils.sendSystemMessage(source, "I'm out of axes and can't craft one (missing sticks/planks/stone/ingots).");
        return false;
    }

    private boolean selectAxe(ServerPlayerEntity bot) {
        return ensureAxeEquipped(bot);
    }

    private boolean selectLeafTool(ServerPlayerEntity bot) {
        // Prefer shears; otherwise pick an innocuous item/empty hand (never axe/shovel/pick/hoe).
        if (BotActions.selectBestTool(bot, "shears", "")) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            var stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                BotActions.selectHotbarSlot(bot, i);
                return true;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase();
            if (key.contains("sword") || key.contains("axe") || key.contains("pickaxe") || key.contains("shovel") || key.contains("hoe")) {
                continue;
            }
            BotActions.selectHotbarSlot(bot, i);
            return true;
        }
        // Fallback: use the first empty slot if any; otherwise leave current hand (but we skipped axes).
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                BotActions.selectHotbarSlot(bot, i);
                break;
            }
        }
        return true;
    }

    private boolean ensurePillarStock(ServerPlayerEntity bot, int needed, ServerCommandSource source) {
        int available = countPillarBlocks(bot);
        if (available >= needed) {
            LOGGER.debug("Pillar stock ok: {} blocks available for {} needed", available, needed);
            return true;
        }
        int toGather = needed - available;
        LOGGER.info("Pillar stock shortfall: need {} additional blocks to reach target", toGather);
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos origin = bot.getBlockPos();
        int radius = 3;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -1, -radius), origin.add(radius, 1, radius))) {
            if (toGather <= 0) {
                break;
            }
            if (pos.equals(origin) || pos.equals(origin.down())) {
                continue; // avoid dropping the bot
            }
            BlockState state = world.getBlockState(pos);
            Item blockItem = state.getBlock().asItem();
            if (state.isOf(net.minecraft.block.Blocks.SNOW)) {
                BlockPos below = pos.down();
                BlockState belowState = world.getBlockState(below);
                if (PILLAR_BLOCKS.contains(belowState.getBlock().asItem())) {
                    mineBlock(bot, pos, false); // clear snow
                    state = belowState;
                    pos = below;
                    blockItem = state.getBlock().asItem();
                }
            }
            if (!PILLAR_BLOCKS.contains(blockItem)) {
                continue;
            }
            LookController.faceBlock(bot, pos);
            BotActions.selectBestTool(bot, "shovel", "axe");
            if (mineBlock(bot, pos, false)) {
                toGather--;
                LOGGER.debug("Woodcut scaffold collected at {}", pos.toShortString());
            }
        }
        if (countPillarBlocks(bot) >= needed) {
            return true;
        }
        // Fallback: collect dirt/gravel/sand via dedicated skill
        LOGGER.warn("Still short on scaffold after local gather. Triggering dirt collection.");
        Map<String, Object> params = new HashMap<>();
        params.put("count", Math.max(toGather, 12));
        try {
            CollectDirtSkill collect = new CollectDirtSkill();
            SkillContext ctx = new SkillContext(source, new HashMap<>(), params);
            var res = collect.execute(ctx);
            if (!res.success()) {
                LOGGER.warn("Collect dirt failed: {}", res.message());
            }
        } catch (Exception e) {
            LOGGER.warn("Collect dirt invocation failed: {}", e.getMessage());
        }
        int finalCount = countPillarBlocks(bot);
        LOGGER.info("Pillar stock after gather/collect: {} (needed {})", finalCount, needed);
        return countPillarBlocks(bot) >= needed;
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
            stand = findStandableNear(world, target, 1, 3);
        }
        if (stand == null) {
            LOGGER.warn("No standable spot under/near {} to align for mining", target.toShortString());
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
        MovementService.MovementResult res = MovementService.execute(source, bot, plan, false, true, false, false);
        if (!res.success()) {
            MovementService.clearRecentWalkAttempt(bot.getUuid());
        }
        return bot.getBlockPos().getSquaredDistance(stand) <= 4.0 || isWithinReach(bot, target);
    }

    private void forcePillarToward(ServerPlayerEntity bot,
                                   ServerCommandSource source,
                                   BlockPos target,
                                   List<BlockPos> placedPillar) {
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        int needed = target.getY() - bot.getBlockY();
        if (needed <= 0) {
            return;
        }
        if (!ensurePillarStock(bot, needed, source)) {
            LOGGER.warn("Pillar stock insufficient (need {}) to reach {}", needed, target.toShortString());
            return;
        }
        moveUnderTarget(source, bot, target);
        pillarUp(bot, needed, placedPillar, source);
    }

    private BlockPos findColumnStand(ServerWorld world, BlockPos target) {
        BlockPos cursor = target.down();
        for (int i = 0; i < 6 && cursor.getY() > world.getBottomY(); i++) {
            BlockPos foot = cursor.toImmutable();
            BlockPos head = foot.up();
            BlockPos below = foot.down();
            if (!world.getBlockState(foot).getCollisionShape(world, foot).isEmpty()) {
                cursor = cursor.down();
                continue;
            }
            if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
                cursor = cursor.down();
                continue;
            }
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                cursor = cursor.down();
                continue;
            }
            return foot;
        }
        return null;
    }

    private boolean mineWithRetries(ServerPlayerEntity bot,
                                    ServerCommandSource source,
                                    BlockPos target,
                                    List<BlockPos> placedPillar,
                                    boolean preferAxe) {
        for (int attempt = 0; attempt < MAX_RETRY_MINING; attempt++) {
            LOGGER.info("Woodcut mining attempt {} for {}", attempt + 1, target.toShortString());
            if (horizontalDistance(bot.getBlockPos(), target) > 2.5) {
                moveUnderTarget(source, bot, target);
            }
            if (!prepareReach(bot, source, target, placedPillar)) {
                LOGGER.warn("Prepare reach failed for {} on attempt {}", target.toShortString(), attempt + 1);
                continue;
            }
            clearBlockingLeaves(bot, target);
            boolean wasSneak = bot.isSneaking();
            if (!placedPillar.isEmpty()) {
                bot.setSneaking(true);
            }
            boolean mined = mineBlock(bot, target, preferAxe);
            if (!placedPillar.isEmpty()) {
                bot.setSneaking(wasSneak);
            }
            if (mined) {
                return true;
            }
            // Force a pillar attempt toward the target before retrying.
            forcePillarToward(bot, source, target, placedPillar);
            // If still blocked after mining attempt, try a small lift to break LOS/pursuit stalls (only once).
            if (attempt == 0) {
                emergencyStep(bot, placedPillar);
            }
        }
        LOGGER.warn("Failed to mine {} after {} attempts", target.toShortString(), MAX_RETRY_MINING);
        return false;
    }

    private double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private BlockPos findStandableNear(ServerWorld world, BlockPos center, int radius, int ySpan) {
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -ySpan, -radius), center.add(radius, ySpan, radius))) {
            BlockPos foot = pos.toImmutable();
            BlockPos head = foot.up();
            BlockPos below = foot.down();
            if (!world.getBlockState(foot).getCollisionShape(world, foot).isEmpty()) {
                continue;
            }
            if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
                continue;
            }
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                continue;
            }
            return foot;
        }
        return null;
    }

    private boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        return botPos.squaredDistanceTo(center) <= REACH_DISTANCE_SQ;
    }

    private void clearHeadroom(ServerPlayerEntity bot) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos head = bot.getBlockPos().up();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = head.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isIn(BlockTags.LEAVES) || state.isReplaceable() || state.isOf(Blocks.SNOW)) {
                        breakLeaf(bot, pos);
                    }
                }
            }
        }
    }

    private void clearObstructionAlongRay(ServerPlayerEntity bot, Vec3d targetCenter) {
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
        if (state.isIn(BlockTags.LEAVES) || state.isReplaceable() || state.isOf(Blocks.SNOW) || state.isIn(BlockTags.LOGS)) {
            LOGGER.debug("Clearing LOS obstruction at {}", hitPos.toShortString());
            breakLeaf(bot, hitPos);
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

    private void cleanupNearbyScaffold(ServerPlayerEntity bot, BlockPos base) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        int radius = 8;
        for (BlockPos pos : BlockPos.iterate(base.add(-radius, -2, -radius), base.add(radius, 12, radius))) {
            if (!PILLAR_BLOCKS.contains(world.getBlockState(pos).getBlock().asItem())) {
                continue;
            }
            // Avoid touching actual logs/planks to prevent structure damage
            if (world.getBlockState(pos).isIn(BlockTags.LOGS) || world.getBlockState(pos).isIn(BlockTags.PLANKS)) {
                continue;
            }
            mineBlock(bot, pos, false);
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
                net.shasankp000.GameAI.DropSweeper.sweep(
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
