package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles scaffolding (pillaring) operations for construction.
 * Extracted from HovelPerimeterBuilder to be reusable across all construction skills.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>pillarUp - jump-place to build a temporary column</li>
 *   <li>teardownScaffolding - break scaffold blocks from top down</li>
 *   <li>scaffold memory - track placed scaffolds for later cleanup</li>
 * </ul>
 */
public final class ScaffoldService {

    private static final Logger LOGGER = LoggerFactory.getLogger("scaffold-service");

    // Blocks suitable for scaffolding (cheap, easily broken)
    public static final List<Item> SCAFFOLD_BLOCKS = List.of(
            Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.NETHERRACK
    );

    private static final int MAX_SCAFFOLD_HEIGHT = ConstructionPlacementRules.DEFAULT_MAX_SCAFFOLD_HEIGHT;
    private static final long JUMP_TIMEOUT_MS = 800L;
    private static final long LAND_TIMEOUT_MS = 1200L;
    private static final long PILLAR_INTERSECT_RETRY_SLEEP_MS = 35L;
    private static final long PILLAR_RECENTER_TIMEOUT_MS = 260L;
    private static final long PILLAR_PLACE_WINDOW_MAX_MS = 560L;
    private static final long PILLAR_PLACE_LOOP_SLEEP_MS = 25L;
    private static final double PILLAR_RECENTER_TOLERANCE_SQ = 0.14D * 0.14D;
    private static final double PILLAR_RECENTER_MAX_DRIFT_SQ = 0.72D * 0.72D;
    private static final double PILLAR_PLACE_MIN_RISE = 0.28D;
    private static final double PILLAR_PLACE_PREFERRED_RISE = 0.52D;

    private ScaffoldService() {}

    /**
     * Session-scoped scaffold tracking for a single task execution.
     */
    public static final class ScaffoldSession {
        private final ServerPlayerEntity bot;
        private final Set<BlockPos> tracked = new HashSet<>();

        private ScaffoldSession(ServerPlayerEntity bot) {
            this.bot = bot;
        }

        public UUID botId() {
            return bot.getUuid();
        }

        public ServerPlayerEntity bot() {
            return bot;
        }

        public Set<BlockPos> trackedPositions() {
            return Collections.unmodifiableSet(tracked);
        }

        public void track(BlockPos pos) {
            if (pos != null) {
                BlockPos key = pos.toImmutable();
                tracked.add(key);
                getScaffoldMemory(bot).add(key);
            }
        }
    }

    /**
     * Tracked scaffold positions per bot (UUID -> positions).
     * This allows multiple bots to build simultaneously without interference.
     */
    private static final Map<UUID, Set<BlockPos>> scaffoldMemory = new ConcurrentHashMap<>();

    private record ScaffoldPlaceAttemptResult(boolean success, BlockPos placedPos, String reason) {}

    public static ScaffoldSession beginSession(ServerPlayerEntity bot) {
        return new ScaffoldSession(bot);
    }

    /**
     * Get or create the scaffold memory set for a bot.
     */
    public static Set<BlockPos> getScaffoldMemory(ServerPlayerEntity bot) {
        return scaffoldMemory.computeIfAbsent(bot.getUuid(), k -> ConcurrentHashMap.newKeySet());
    }

    public static boolean isTrackedScaffold(UUID botId, BlockPos pos) {
        if (botId == null || pos == null) {
            return false;
        }
        Set<BlockPos> memory = scaffoldMemory.get(botId);
        return memory != null && memory.contains(pos);
    }

    /**
     * Clear scaffold memory for a bot.
     */
    public static void clearScaffoldMemory(ServerPlayerEntity bot) {
        scaffoldMemory.remove(bot.getUuid());
    }

    /**
     * Pillar up by the specified number of blocks using jump-place technique.
     *
     * @param bot The bot to pillar
     * @param steps Number of blocks to pillar up
     * @param trackForTeardown Whether to track these positions for later teardown
     * @return true if successfully pillared up, false otherwise
     */
    public static boolean pillarUp(ServerPlayerEntity bot, int steps, boolean trackForTeardown) {
        if (bot == null || steps <= 0 || steps > MAX_SCAFFOLD_HEIGHT) {
            return steps <= 0;
        }
        Set<BlockPos> memory = getScaffoldMemory(bot);

        LOGGER.debug("Pillaring up {} blocks for {}", steps, bot.getGameProfile().name());

        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }

            // Stop horizontal movement
            BotActions.stop(bot);
            sleepQuiet(60L);

            // Wait to be on ground
            if (!waitForOnGround(bot, LAND_TIMEOUT_MS)) {
                LOGGER.warn("Not on ground for pillar step {}", i);
                return false;
            }
            stabilizePillarStance(bot, PILLAR_RECENTER_TIMEOUT_MS);

            BlockPos targetPos = bot.getBlockPos();
            double startY = bot.getY();

            // If feet position is occupied (e.g. standing on stairs), shift target up to air
            ServerWorld pillarWorld = (ServerWorld) bot.getEntityWorld();
            var feetState = pillarWorld.getBlockState(targetPos);
            if (!feetState.isAir() && !feetState.isReplaceable()) {
                BlockPos adjusted = null;
                for (int dy = 1; dy <= 2; dy++) {
                    BlockPos candidate = targetPos.up(dy);
                    var cs = pillarWorld.getBlockState(candidate);
                    if (cs.isAir() || cs.isReplaceable()) {
                        adjusted = candidate;
                        break;
                    }
                }
                if (adjusted == null) {
                    LOGGER.info("pillarUp step {}: feet occupied by {} at {}, no air within 2 above — skipping",
                            i, feetState.getBlock().getName().getString(), targetPos.toShortString());
                    return false;
                }
                LOGGER.info("pillarUp step {}: feet occupied by {}, targeting {} instead",
                        i, feetState.getBlock().getName().getString(), adjusted.toShortString());
                targetPos = adjusted;
            }

            // Jump
            BotActions.jump(bot);

            // Wait until airborne
            if (!waitForAirborne(bot, JUMP_TIMEOUT_MS)) {
                LOGGER.warn("Failed to become airborne for pillar step {}", i);
                return false;
            }

            // Wait for good placement window (near apex of jump)
            if (!waitForJumpPlaceWindow(bot, startY, 600L)) {
                if (bot.isOnGround()) {
                    BotActions.stop(bot);
                    return false;
                }
            }

            // Place scaffold block (adaptive retry across the jump window; more tolerant of
            // timing variance and early/late attempts than a single apex-only click)
            ScaffoldPlaceAttemptResult placement = tryPlaceScaffoldAcrossJumpWindow(bot, pillarWorld, targetPos, startY, PILLAR_PLACE_WINDOW_MAX_MS);
            if (!placement.success()) {
                LOGGER.warn("Failed to place scaffold block at {} reason={}",
                        targetPos.toShortString(), placement.reason());
                return false;
            }
            targetPos = placement.placedPos() != null ? placement.placedPos() : targetPos;

            // Track scaffold for later removal
            if (trackForTeardown) {
                memory.add(targetPos.toImmutable());
            }

            // Wait for bot to land on the new block
            if (!waitForYIncrease(bot, targetPos.getY(), 1000L)) {
                BotActions.stop(bot);
                return false;
            }
        }

        return true;
    }

    /**
     * Pillar the bot up until it reaches (or exceeds) {@code targetY}.
     */
    public static boolean pillarToY(ScaffoldSession session, int targetY) {
        if (session == null || targetY <= Integer.MIN_VALUE / 2) {
            return false;
        }
        ServerPlayerEntity bot = session.bot();
        int botY = bot.getBlockPos().getY();
        int stepsNeeded = Math.max(0, targetY - botY);
        if (stepsNeeded == 0) {
            return true;
        }
        int cappedSteps = Math.min(stepsNeeded, MAX_SCAFFOLD_HEIGHT);
        LOGGER.debug("pillarToY: botY={} targetY={} stepsNeeded={} capped={}",
                botY, targetY, stepsNeeded, cappedSteps);
        List<BlockPos> placed = pillarUpWithPositions(bot, cappedSteps);
        for (BlockPos pos : placed) {
            session.track(pos);
        }
        boolean reached = bot.getBlockPos().getY() >= targetY;
        if (!reached) {
            LOGGER.info("pillarToY incomplete: placed={} botY={} targetY={} onGround={}",
                    placed.size(), bot.getBlockPos().getY(), targetY, bot.isOnGround());
        }
        return reached;
    }

    /**
     * Pillar up and return the list of positions placed.
     */
    public static List<BlockPos> pillarUpWithPositions(ServerPlayerEntity bot, int steps) {
        List<BlockPos> positions = new ArrayList<>();

        if (bot == null || steps <= 0 || steps > MAX_SCAFFOLD_HEIGHT) {
            return positions;
        }

        ServerWorld world = (ServerWorld) bot.getEntityWorld();

        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                LOGGER.debug("pillarUp step {}/{}: aborted", i, steps);
                break;
            }

            BotActions.stop(bot);
            sleepQuiet(60L);

            if (!waitForOnGround(bot, LAND_TIMEOUT_MS)) {
                LOGGER.info("pillarUp step {}/{}: not on ground (Y={})", i, steps, bot.getY());
                break;
            }
            stabilizePillarStance(bot, PILLAR_RECENTER_TIMEOUT_MS);

            BlockPos targetPos = bot.getBlockPos();
            double startY = bot.getY();

            // If feet position is occupied (e.g. standing on stairs), shift target up to air.
            // Torches are a special case: break them in place so the scaffold can go there.
            var feetState = world.getBlockState(targetPos);
            if (!feetState.isAir() && !feetState.isReplaceable()) {
                Block feetBlock = feetState.getBlock();
                if (feetBlock == Blocks.TORCH || feetBlock == Blocks.WALL_TORCH
                        || feetBlock == Blocks.SOUL_TORCH || feetBlock == Blocks.SOUL_WALL_TORCH) {
                    LOGGER.info("pillarUp step {}/{}: clearing torch at {} for scaffold",
                            i, steps, targetPos.toShortString());
                    try {
                        MiningTool.mineBlock(bot, targetPos).get(5_000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (Exception ignored) {}
                    sleepQuiet(60L);
                    // Re-check after mining
                    feetState = world.getBlockState(targetPos);
                }
                if (!feetState.isAir() && !feetState.isReplaceable()) {
                    BlockPos adjusted = null;
                    for (int dy = 1; dy <= 2; dy++) {
                        BlockPos candidate = targetPos.up(dy);
                        var cs = world.getBlockState(candidate);
                        if (cs.isAir() || cs.isReplaceable()) {
                            adjusted = candidate;
                            break;
                        }
                    }
                    if (adjusted == null) {
                        LOGGER.info("pillarUp step {}/{}: feet occupied by {} at {}, no air within 2 above — skipping",
                                i, steps, feetState.getBlock().getName().getString(), targetPos.toShortString());
                        break;
                    }
                    LOGGER.info("pillarUp step {}/{}: feet occupied by {}, targeting {} instead",
                            i, steps, feetState.getBlock().getName().getString(), adjusted.toShortString());
                    targetPos = adjusted;
                }
            }

            // Check if there's head clearance to jump (2 blocks above)
            BlockPos headSpace = targetPos.up().up();
            if (!world.getBlockState(headSpace).isAir()
                    && !world.getBlockState(headSpace).getCollisionShape(world, headSpace).isEmpty()) {
                // Try mining the overhead block instead of giving up — works bare-handed
                // for soft blocks (dirt, sand, gravel, leaves) and with tools for harder blocks
                LOGGER.info("pillarUp step {}/{}: overhead blocked at {} — attempting to mine",
                        i, steps, headSpace.toShortString());
                try {
                    String mineResult = MiningTool.mineBlock(bot, headSpace).get(5_000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (mineResult != null && mineResult.startsWith("\u26a0\ufe0f")) {
                        LOGGER.info("pillarUp step {}/{}: cannot clear overhead at {}: {}",
                                i, steps, headSpace.toShortString(), mineResult);
                        break;
                    }
                    // Verify block was actually cleared
                    if (!world.getBlockState(headSpace).isAir()
                            && !world.getBlockState(headSpace).getCollisionShape(world, headSpace).isEmpty()) {
                        LOGGER.info("pillarUp step {}/{}: overhead still blocked after mining at {}",
                                i, steps, headSpace.toShortString());
                        break;
                    }
                    LOGGER.info("pillarUp step {}/{}: cleared overhead at {}", i, steps, headSpace.toShortString());
                    sleepQuiet(100L); // let world state settle before jump
                } catch (Exception e) {
                    LOGGER.info("pillarUp step {}/{}: overhead mining failed at {}: {}",
                            i, steps, headSpace.toShortString(), e.getMessage());
                    break;
                }
            }

            BotActions.jump(bot);

            if (!waitForAirborne(bot, JUMP_TIMEOUT_MS)) {
                LOGGER.info("pillarUp step {}/{}: failed to become airborne", i, steps);
                break;
            }

            if (!waitForJumpPlaceWindow(bot, startY, 600L)) {
                LOGGER.info("pillarUp step {}/{}: missed place window", i, steps);
                if (bot.isOnGround()) {
                    BotActions.stop(bot);
                    break;
                }
            }

            ScaffoldPlaceAttemptResult placement = tryPlaceScaffoldAcrossJumpWindow(bot, world, targetPos, startY, PILLAR_PLACE_WINDOW_MAX_MS);
            if (!placement.success()) {
                LOGGER.info("pillarUp step {}/{}: failed to place at {} reason={}",
                        i, steps, targetPos.toShortString(), placement.reason());
                break;
            }
            targetPos = placement.placedPos() != null ? placement.placedPos() : targetPos;

            positions.add(targetPos.toImmutable());
            if (!waitForYIncrease(bot, targetPos.getY(), 1000L)) {
                LOGGER.info("pillarUp step {}/{}: Y didn't increase after placing at {}", i, steps, targetPos.toShortString());
                BotActions.stop(bot);
                break;
            }
        }

        LOGGER.debug("pillarUp complete: {}/{} steps, placed={}", positions.size(), steps, positions);
        return positions;
    }

    /**
     * Tear down scaffold blocks that were tracked.
     * Removes from top to bottom and clears memory.
     *
     * @param bot The bot doing the teardown
     * @return Number of blocks removed
     */
    public static int teardownTrackedScaffolds(ServerPlayerEntity bot) {
        Set<BlockPos> memory = scaffoldMemory.get(bot.getUuid());
        if (memory == null || memory.isEmpty()) {
            return 0;
        }

        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        int removed = teardownScaffolds(bot, world, new ArrayList<>(memory), Collections.emptySet());
        memory.clear();
        return removed;
    }

    /**
     * Tear down only scaffolds tracked in the provided session.
     */
    public static int teardown(ScaffoldSession session, Set<BlockPos> keepBlocks) {
        if (session == null || session.tracked.isEmpty()) {
            return 0;
        }
        ServerPlayerEntity bot = session.bot();
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return 0;
        }
        int removed = teardownScaffolds(bot, world, new ArrayList<>(session.tracked), keepBlocks);
        Set<BlockPos> memory = scaffoldMemory.get(session.botId());
        if (memory != null) {
            memory.removeAll(session.tracked);
            if (memory.isEmpty()) {
                scaffoldMemory.remove(session.botId());
            }
        }
        session.tracked.clear();
        return removed;
    }

    /**
     * Tear down specific scaffold positions.
     * 
     * @param bot The bot doing the teardown
     * @param world The world
     * @param scaffolds List of positions to tear down
     * @param keepBlocks Positions to NOT tear down (e.g., if they became part of the structure)
     * @return Number of blocks removed
     */
    public static int teardownScaffolds(ServerPlayerEntity bot, ServerWorld world,
                                         List<BlockPos> scaffolds, Set<BlockPos> keepBlocks) {
        if (scaffolds.isEmpty()) {
            return 0;
        }

        // Sort from top to bottom for safe teardown
        List<BlockPos> sorted = new ArrayList<>(scaffolds);
        sorted.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        var server = world.getServer();
        int removed = 0;
        for (BlockPos pos : sorted) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }

            // Skip if in keep set
            if (keepBlocks != null && keepBlocks.contains(pos)) {
                continue;
            }

            // Check if we're standing on this block
            BlockPos botPos = bot.getBlockPos();
            if (botPos.equals(pos.up())) {
                // We're standing on it - let gravity work
                sleepQuiet(100L);
            }

            // Schedule block breaking on the server thread via the bot's
            // interaction manager so the break is vanilla-compliant (physical,
            // not remote world mutation).
            var future = new java.util.concurrent.CompletableFuture<Boolean>();
            server.execute(() -> {
                try {
                    var state = world.getBlockState(pos);
                    if (state.isAir()) {
                        future.complete(false);
                        return;
                    }
                    boolean standingOnBlock = bot.getBlockPos().equals(pos.up());
                    if (!standingOnBlock && !BlockInteractionService.canInteract(bot, pos)) {
                        LOGGER.info("Skipping remote scaffold teardown at {} for {}",
                                pos.toShortString(),
                                bot.getName().getString());
                        future.complete(false);
                        return;
                    }
                    boolean isScaffoldType = SCAFFOLD_BLOCKS.stream()
                            .anyMatch(item -> state.getBlock().asItem().equals(item));
                    if (isScaffoldType) {
                        var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, pos);
                        if (!auth.allowed()) {
                            future.complete(false);
                            return;
                        }
                        boolean broke = bot.interactionManager.tryBreakBlock(pos);
                        if (broke) {
                            bot.swingHand(Hand.MAIN_HAND, true);
                        }
                        future.complete(broke);
                    } else {
                        future.complete(false);
                    }
                } catch (Exception e) {
                    future.complete(false);
                }
            });

            try {
                boolean broke = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
                if (broke) {
                    removed++;
                    sleepQuiet(50L);
                }
            } catch (Exception e) {
                LOGGER.debug("Scaffold teardown timed out at {}: {}", pos.toShortString(), e.getMessage());
            }
        }

        return removed;
    }

    /**
     * Get the height a bot needs to scaffold to reach a target position.
     * Accounts for eye height and reach distance.
     */
    public static int calculateScaffoldHeight(ServerPlayerEntity bot, BlockPos target) {
        int currentY = bot.getBlockPos().getY();
        // Bot's eyes are at Y+1.62, reach is ~4.5 blocks
        // To reach a block at targetY, we need bot at approximately targetY-3
        int optimalY = target.getY() - 1;
        return Math.max(0, optimalY - currentY);
    }

    // ========== Helper Methods ==========

    private static boolean waitForOnGround(ServerPlayerEntity bot, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (!bot.isOnGround() && (System.currentTimeMillis() - start) < timeoutMs) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return false;
            }
            sleepQuiet(20L);
        }
        return bot.isOnGround();
    }

    private static boolean waitForAirborne(ServerPlayerEntity bot, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (bot.isOnGround() && (System.currentTimeMillis() - start) < timeoutMs) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return false;
            }
            sleepQuiet(20L);
        }
        return !bot.isOnGround();
    }

    private static boolean waitForJumpPlaceWindow(ServerPlayerEntity bot, double startY, long maxWaitMs) {
        long start = System.currentTimeMillis();
        double lastY = bot.getY();
        
        while ((System.currentTimeMillis() - start) < maxWaitMs) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return false;
            }
            sleepQuiet(20L);
            double currentY = bot.getY();
            
            // Broad "ready to place" window: either near-apex (rise slowing) or already high enough.
            if (currentY > startY + PILLAR_PLACE_PREFERRED_RISE && currentY <= lastY + 0.10) {
                return true; // Near apex
            }
            if (currentY > startY + (PILLAR_PLACE_PREFERRED_RISE + 0.18D)) {
                return true; // Near apex
            }
            lastY = currentY;
        }
        double currentY = bot.getY();
        boolean stillAirborneHighEnough = !bot.isOnGround() && currentY > startY + PILLAR_PLACE_MIN_RISE;
        if (!stillAirborneHighEnough) {
            LOGGER.debug("pillarUp: missed jump place window startY={} currentY={} onGround={}",
                    String.format(Locale.ROOT, "%.2f", startY),
                    String.format(Locale.ROOT, "%.2f", currentY),
                    bot.isOnGround());
        }
        return stillAirborneHighEnough;
    }

    private static boolean isIntersectTargetReason(String reason) {
        return reason != null && reason.startsWith("bot-intersects-target");
    }

    private static boolean isHardScaffoldPlaceFailure(String reason) {
        if (reason == null) return false;
        return reason.startsWith("no-block-item-available")
                || reason.startsWith("selected-item-not-block")
                || reason.startsWith("no-world-or-target");
    }

    private static boolean isRetryableScaffoldPlaceFailure(String reason) {
        if (reason == null) return true;
        return isIntersectTargetReason(reason)
                || reason.startsWith("timeout")
                || reason.startsWith("out-of-reach")
                || reason.startsWith("no-line-of-sight")
                || reason.startsWith("place-rejected")
                || reason.startsWith("no-solid-support")
                || reason.startsWith("occupied=");
    }

    private static BlockPos recomputePillarTarget(ServerWorld world, ServerPlayerEntity bot, BlockPos currentTarget) {
        if (world == null || bot == null) return currentTarget;
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos feet = bot.getBlockPos();
        candidates.add(feet);
        candidates.add(feet.down());
        if (currentTarget != null) {
            candidates.add(currentTarget);
            candidates.add(currentTarget.down());
        }
        for (BlockPos candidate : candidates) {
            if (candidate == null) continue;
            var state = world.getBlockState(candidate);
            if (state.isAir() || state.isReplaceable()) {
                return candidate.toImmutable();
            }
        }
        return currentTarget;
    }

    private static ScaffoldPlaceAttemptResult tryPlaceScaffoldWithAdaptiveTarget(ServerPlayerEntity bot,
                                                                                 ServerWorld world,
                                                                                 BlockPos initialTarget,
                                                                                 int maxAttempts) {
        BlockPos targetPos = initialTarget;
        String lastReason = "unknown";
        for (int attempt = 0; attempt < Math.max(1, maxAttempts); attempt++) {
            BotActions.PlaceResult result = BotActions.tryPlaceBlockAt(bot, targetPos, Direction.UP, SCAFFOLD_BLOCKS);
            if (result.success()) {
                return new ScaffoldPlaceAttemptResult(true, targetPos, null);
            }
            lastReason = result.reason();
            if (isIntersectTargetReason(lastReason)) {
                sleepQuiet(PILLAR_INTERSECT_RETRY_SLEEP_MS);
                BlockPos recomputed = recomputePillarTarget(world, bot, targetPos);
                if (recomputed != null && !recomputed.equals(targetPos)) {
                    LOGGER.debug("pillarUp adaptive retry: retarget {} -> {} after {}", 
                            targetPos.toShortString(), recomputed.toShortString(), lastReason);
                    targetPos = recomputed;
                }
            } else {
                sleepQuiet(50L);
            }
        }
        return new ScaffoldPlaceAttemptResult(false, targetPos, lastReason);
    }

    private static ScaffoldPlaceAttemptResult tryPlaceScaffoldAcrossJumpWindow(ServerPlayerEntity bot,
                                                                               ServerWorld world,
                                                                               BlockPos initialTarget,
                                                                               double startY,
                                                                               long maxWaitMs) {
        if (bot == null || world == null || initialTarget == null) {
            return new ScaffoldPlaceAttemptResult(false, initialTarget, "no-world-or-target");
        }
        long start = System.currentTimeMillis();
        double lastY = bot.getY();
        BlockPos targetPos = initialTarget.toImmutable();
        String lastReason = "no-attempt";
        int attempts = 0;

        while ((System.currentTimeMillis() - start) < Math.max(120L, maxWaitMs)) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return new ScaffoldPlaceAttemptResult(false, targetPos, "aborted");
            }
            double currentY = bot.getY();
            double rise = currentY - startY;
            boolean descending = currentY <= lastY + 0.02D;
            boolean readyForFirstAttempt = rise >= PILLAR_PLACE_MIN_RISE
                    && (descending || rise >= PILLAR_PLACE_PREFERRED_RISE || (System.currentTimeMillis() - start) > 150L);
            boolean readyForRetryAttempt = rise >= 0.12D;
            if (attempts == 0 ? !readyForFirstAttempt : !readyForRetryAttempt) {
                if (bot.isOnGround() && rise < 0.12D) {
                    break;
                }
                lastY = currentY;
                sleepQuiet(15L);
                continue;
            }

            attempts++;
            ScaffoldPlaceAttemptResult attempt = tryPlaceScaffoldWithAdaptiveTarget(bot, world, targetPos, attempts == 1 ? 2 : 1);
            targetPos = attempt.placedPos() != null ? attempt.placedPos() : targetPos;
            if (attempt.success()) {
                return attempt;
            }

            lastReason = attempt.reason();
            if (isHardScaffoldPlaceFailure(lastReason)) {
                return new ScaffoldPlaceAttemptResult(false, targetPos, lastReason);
            }
            if (!isRetryableScaffoldPlaceFailure(lastReason)) {
                return new ScaffoldPlaceAttemptResult(false, targetPos, lastReason);
            }
            BlockPos checkPos = targetPos;
            boolean becameScaffold = SCAFFOLD_BLOCKS.stream()
                    .anyMatch(item -> item.equals(world.getBlockState(checkPos).getBlock().asItem()));
            if (becameScaffold) {
                return new ScaffoldPlaceAttemptResult(true, targetPos, "placed-detected-after-retry");
            }
            if (bot.isOnGround() && attempts >= 1) {
                break;
            }
            lastY = currentY;
            sleepQuiet(PILLAR_PLACE_LOOP_SLEEP_MS);
        }
        return new ScaffoldPlaceAttemptResult(false, targetPos, lastReason);
    }

    private static void stabilizePillarStance(ServerPlayerEntity bot, long timeoutMs) {
        if (bot == null || !bot.isOnGround()) {
            return;
        }
        BlockPos stand = bot.getBlockPos();
        Vec3d center = Vec3d.ofCenter(stand);
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < Math.max(80L, timeoutMs)) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return;
            }
            if (!bot.isOnGround()) {
                return;
            }
            if (!bot.getBlockPos().equals(stand)) {
                return;
            }
            double dx = center.x - bot.getX();
            double dz = center.z - bot.getZ();
            double offsetSq = dx * dx + dz * dz;
            if (offsetSq <= PILLAR_RECENTER_TOLERANCE_SQ) {
                BotActions.stop(bot);
                return;
            }
            if (offsetSq > PILLAR_RECENTER_MAX_DRIFT_SQ) {
                return;
            }
            BotActions.applyMovementInput(bot, new Vec3d(center.x, bot.getY(), center.z), 0.08D);
            sleepQuiet(30L);
        }
        BotActions.stop(bot);
    }

    private static boolean waitForYIncrease(ServerPlayerEntity bot, int targetBlockY, long maxWaitMs) {
        long start = System.currentTimeMillis();
        while (bot.getBlockPos().getY() <= targetBlockY && (System.currentTimeMillis() - start) < maxWaitMs) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return false;
            }
            sleepQuiet(30L);
        }
        return bot.getBlockPos().getY() > targetBlockY;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
