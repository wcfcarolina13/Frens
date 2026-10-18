package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.GameAI.skills.support.WoodcutBaseAccessPolicy;
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
            Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.NETHERRACK,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
            Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.CHERRY_PLANKS, Items.MANGROVE_PLANKS,
            Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
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
            ScaffoldPlaceAttemptResult placement = tryPlaceScaffoldAcrossJumpWindow(bot, pillarWorld, targetPos, startY, PILLAR_PLACE_WINDOW_MAX_MS, false);
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
     * Outcome of a pillar attempt: the blocks placed plus the reason the loop stopped early.
     *
     * @param placed positions successfully placed (may be empty)
     * @param reason "" when the pillar completed all steps or placed at least one block;
     *               otherwise a short kebab-case break reason
     */
    public record PillarResult(List<BlockPos> placed, String reason) {}

    /**
     * Pillar up and return the list of positions placed.
     */
    public static List<BlockPos> pillarUpWithPositions(ServerPlayerEntity bot, int steps) {
        return pillarUpDetailed(bot, steps).placed();
    }

    /**
     * {@link #pillarUpWithPositions(ServerPlayerEntity, int)} with the explicit own-scaffold flag
     * (1.1.268). Only WoodcutSkill's tree-fell climb pillars pass {@code true}: inside a SERVER-owned
     * base with the admin toggle on, their scaffold placements may then go ahead and are registered
     * as this bot's own ({@link OwnedScaffoldRegistry}). Every other caller keeps the old behaviour.
     */
    public static List<BlockPos> pillarUpWithPositions(ServerPlayerEntity bot, int steps, boolean ownScaffold) {
        return pillarUpDetailed(bot, steps, ownScaffold).placed();
    }

    /**
     * Pillar up, reporting both the placed positions and (when nothing was placed)
     * the reason the per-step loop broke out.
     */
    public static PillarResult pillarUpDetailed(ServerPlayerEntity bot, int steps) {
        return pillarUpDetailed(bot, steps, false);
    }

    /** {@link #pillarUpDetailed(ServerPlayerEntity, int)} with the own-scaffold flag (WoodcutSkill only). */
    public static PillarResult pillarUpDetailed(ServerPlayerEntity bot, int steps, boolean ownScaffold) {
        List<BlockPos> positions = new ArrayList<>();
        String breakReason = "";

        if (bot == null || steps <= 0 || steps > MAX_SCAFFOLD_HEIGHT) {
            return new PillarResult(positions, "invalid-steps");
        }

        ServerWorld world = (ServerWorld) bot.getEntityWorld();

        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                LOGGER.debug("pillarUp step {}/{}: aborted", i, steps);
                breakReason = "aborted";
                break;
            }

            BotActions.stop(bot);
            sleepQuiet(60L);

            if (!waitForOnGround(bot, LAND_TIMEOUT_MS)) {
                LOGGER.info("pillarUp step {}/{}: not on ground (Y={})", i, steps, bot.getY());
                breakReason = "not-on-ground";
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
                        breakReason = "feet-occupied";
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
                // Refuse to mine through any user-registered base. Pillar recovery is for
                // escaping pits in the wild, not chewing through home roofs/ceilings.
                // Phase 2 layers this on top of the zone gate; this is the cheap belt-and-suspenders.
                if (world.getServer() != null
                        && BotHomeService.findBaseNearPosition(world.getServer(), world, headSpace).isPresent()) {
                    // 1.1.268: an own-scaffold woodcut pillar in the Spawn base (toggle on) may go on
                    // when the overhead block is a log or leaves; MiningTool's gate still decides it.
                    var overhead = world.getBlockState(headSpace);
                    boolean treeOverhead = overhead.isIn(BlockTags.LOGS) || overhead.isIn(BlockTags.LEAVES);
                    boolean serverOnly = ownScaffold && treeOverhead
                            && onlyServerOwnedBasesCover(world, headSpace);
                    boolean toggle = serverOnly && BotHomeService.isSpawnBaseWoodcutAllowed(world.getServer());
                    if (!WoodcutBaseAccessPolicy.overheadBaseAbortExempt(ownScaffold, serverOnly, treeOverhead, toggle)) {
                        LOGGER.info("pillarUp step {}/{}: overhead at {} is inside a registered base — aborting pillar",
                                i, steps, headSpace.toShortString());
                        breakReason = "overhead-in-base";
                        break;
                    }
                    LOGGER.info("pillarUp step {}/{}: overhead {} at {} is in the Spawn base; own-scaffold pillar may clear it",
                            i, steps, overhead.getBlock().getName().getString(), headSpace.toShortString());
                }
                // Try mining the overhead block instead of giving up — works bare-handed
                // for soft blocks (dirt, sand, gravel, leaves) and with tools for harder blocks
                LOGGER.info("pillarUp step {}/{}: overhead blocked at {} — attempting to mine",
                        i, steps, headSpace.toShortString());
                try {
                    String mineResult = MiningTool.mineBlock(bot, headSpace).get(5_000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (mineResult != null && mineResult.startsWith("\u26a0\ufe0f")) {
                        LOGGER.info("pillarUp step {}/{}: cannot clear overhead at {}: {}",
                                i, steps, headSpace.toShortString(), mineResult);
                        breakReason = "overhead-mine-failed";
                        break;
                    }
                    // Verify block was actually cleared
                    if (!world.getBlockState(headSpace).isAir()
                            && !world.getBlockState(headSpace).getCollisionShape(world, headSpace).isEmpty()) {
                        LOGGER.info("pillarUp step {}/{}: overhead still blocked after mining at {}",
                                i, steps, headSpace.toShortString());
                        breakReason = "overhead-mine-failed";
                        break;
                    }
                    LOGGER.info("pillarUp step {}/{}: cleared overhead at {}", i, steps, headSpace.toShortString());
                    sleepQuiet(100L); // let world state settle before jump
                } catch (Exception e) {
                    LOGGER.info("pillarUp step {}/{}: overhead mining failed at {}: {}",
                            i, steps, headSpace.toShortString(), e.getMessage());
                    breakReason = "overhead-mine-failed";
                    break;
                }
            }

            BotActions.jump(bot);

            if (!waitForAirborne(bot, JUMP_TIMEOUT_MS)) {
                LOGGER.info("pillarUp step {}/{}: failed to become airborne", i, steps);
                breakReason = "not-airborne";
                break;
            }

            if (!waitForJumpPlaceWindow(bot, startY, 600L)) {
                LOGGER.info("pillarUp step {}/{}: missed place window", i, steps);
                if (bot.isOnGround()) {
                    BotActions.stop(bot);
                    breakReason = "missed-place-window";
                    break;
                }
            }

            ScaffoldPlaceAttemptResult placement = tryPlaceScaffoldAcrossJumpWindow(bot, world, targetPos, startY,
                    PILLAR_PLACE_WINDOW_MAX_MS, ownScaffold);
            if (!placement.success()) {
                LOGGER.info("pillarUp step {}/{}: failed to place at {} reason={}",
                        i, steps, targetPos.toShortString(), placement.reason());
                breakReason = "place-rejected:" + (placement.reason() == null ? "unknown" : placement.reason());
                break;
            }
            targetPos = placement.placedPos() != null ? placement.placedPos() : targetPos;

            positions.add(targetPos.toImmutable());
            if (!waitForYIncrease(bot, targetPos.getY(), 1000L)) {
                LOGGER.info("pillarUp step {}/{}: Y didn't increase after placing at {}", i, steps, targetPos.toShortString());
                BotActions.stop(bot);
                breakReason = "no-y-increase";
                break;
            }
        }

        LOGGER.debug("pillarUp complete: {}/{} steps, placed={}", positions.size(), steps, positions);
        return new PillarResult(positions, positions.isEmpty() ? breakReason : "");
    }

    /**
     * Tear down scaffold blocks that were tracked, top to bottom. Only positions that are gone
     * afterwards (broken, already air, or no longer a scaffold block) leave the memory: a stop
     * mid-teardown, a block out of reach or refused by protection, and the server-thread refusal
     * all keep the rest tracked for a later teardown or cleanup.
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
        TeardownResult result = teardownScaffoldsDetailed(bot, world, new ArrayList<>(memory), Collections.emptySet());
        memory.removeAll(ScaffoldTeardownPolicy.toForget(memory, result.gone(), Collections.emptySet()));
        return result.removed();
    }

    /**
     * Tear down only scaffolds tracked in the provided session. Positions in {@code keepBlocks}
     * became part of the structure and are forgotten (so a later teardown never breaks them);
     * everything else stays tracked unless it is gone.
     */
    public static int teardown(ScaffoldSession session, Set<BlockPos> keepBlocks) {
        if (session == null || session.tracked.isEmpty()) {
            return 0;
        }
        ServerPlayerEntity bot = session.bot();
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return 0;
        }
        TeardownResult result = teardownScaffoldsDetailed(bot, world, new ArrayList<>(session.tracked), keepBlocks);
        Set<BlockPos> forget = ScaffoldTeardownPolicy.toForget(session.tracked, result.gone(), keepBlocks);
        Set<BlockPos> memory = scaffoldMemory.get(session.botId());
        if (memory != null) {
            memory.removeAll(forget);
            if (memory.isEmpty()) {
                scaffoldMemory.remove(session.botId());
            }
        }
        session.tracked.removeAll(forget);
        return result.removed();
    }

    /** What one teardown did: blocks it broke, and every position now known to be gone. */
    private record TeardownResult(int removed, Set<BlockPos> gone) {
        static final TeardownResult NONE = new TeardownResult(0, Set.of());
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
        return teardownScaffoldsDetailed(bot, world, scaffolds, keepBlocks).removed();
    }

    private static TeardownResult teardownScaffoldsDetailed(ServerPlayerEntity bot, ServerWorld world,
                                                            List<BlockPos> scaffolds, Set<BlockPos> keepBlocks) {
        if (scaffolds.isEmpty()) {
            return TeardownResult.NONE;
        }

        // Sort from top to bottom for safe teardown
        List<BlockPos> sorted = new ArrayList<>(scaffolds);
        sorted.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        var server = world.getServer();
        if (server == null) {
            return TeardownResult.NONE;
        }
        if (server.isOnThread()) {
            // Each break is a timed survival mine that ticks through server.execute; blocking on it
            // here would deadlock the server thread.
            LOGGER.warn("Refusing scaffold teardown of {} block(s) for {} on the server thread",
                    sorted.size(), bot.getName().getString());
            return TeardownResult.NONE;
        }
        int removed = 0;
        Set<BlockPos> gone = new HashSet<>();
        for (BlockPos pos : sorted) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }

            // Skip if in keep set
            if (keepBlocks != null && keepBlocks.contains(pos)) {
                continue;
            }

            // Pre-check on the server thread: still a scaffold block, reachable, and allowed.
            var precheck = new java.util.concurrent.CompletableFuture<ScaffoldTeardownPolicy.Precheck>();
            server.execute(() -> {
                try {
                    var state = world.getBlockState(pos);
                    if (state.isAir()) {
                        precheck.complete(ScaffoldTeardownPolicy.Precheck.GONE);
                        return;
                    }
                    boolean isScaffoldType = SCAFFOLD_BLOCKS.stream()
                            .anyMatch(item -> state.getBlock().asItem().equals(item));
                    if (!isScaffoldType) {
                        precheck.complete(ScaffoldTeardownPolicy.Precheck.NOT_SCAFFOLD);
                        return;
                    }
                    boolean standingOnBlock = bot.getBlockPos().equals(pos.up());
                    if (!standingOnBlock && !BlockInteractionService.canInteract(bot, pos)) {
                        LOGGER.info("Skipping remote scaffold teardown at {} for {}",
                                pos.toShortString(),
                                bot.getName().getString());
                        precheck.complete(ScaffoldTeardownPolicy.Precheck.SKIP);
                        return;
                    }
                    // 1.1.268: the bot's own Spawn-base scaffold asks as OWN_SCAFFOLD_BREAK (the gate
                    // re-checks ownership and the live material itself); everything else as before.
                    boolean owned = OwnedScaffoldRegistry.isOwned(bot, world, pos);
                    var kind = ScaffoldOwnershipPolicy.breakKind(false, owned,
                            owned && OwnedScaffoldRegistry.isScaffoldMaterial(state));
                    var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, pos, kind);
                    precheck.complete(auth.allowed()
                            ? ScaffoldTeardownPolicy.Precheck.BREAK
                            : ScaffoldTeardownPolicy.Precheck.SKIP);
                } catch (Exception e) {
                    precheck.complete(ScaffoldTeardownPolicy.Precheck.SKIP);
                }
            });

            ScaffoldTeardownPolicy.Precheck verdict;
            try {
                verdict = precheck.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.debug("Scaffold teardown pre-check timed out at {}: {}", pos.toShortString(), e.getMessage());
                continue;
            }
            if (ScaffoldTeardownPolicy.isGoneWithoutBreaking(verdict)) {
                gone.add(pos);
                continue;
            }
            if (verdict != ScaffoldTeardownPolicy.Precheck.BREAK) {
                continue;
            }

            // Survival-timed break: MiningTool picks the tool (axe for planks, pickaxe for stone,
            // else hands) and only breaks once the vanilla break time has elapsed.
            if (mineScaffoldBlock(bot, pos)) {
                removed++;
                gone.add(pos);
                sleepQuiet(50L);
            } else if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
        }

        return new TeardownResult(removed, gone);
    }

    /** Hard ceiling on one teardown break; MiningTool's own timeout normally fires first. */
    private static final long TEARDOWN_BREAK_CEILING_MS = 30_000L;

    /**
     * Mine one scaffold block at survival speed from a worker thread, polling so a skill abort
     * stops the swing. Returns true when MiningTool reports the block gone.
     */
    private static boolean mineScaffoldBlock(ServerPlayerEntity bot, BlockPos pos) {
        java.util.concurrent.CompletableFuture<String> mining = MiningTool.mineBlock(bot, pos, false, false);
        long start = System.currentTimeMillis();
        while (true) {
            try {
                String result = mining.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                boolean ok = result != null && !result.startsWith("\u26a0\ufe0f");
                if (!ok) {
                    LOGGER.info("Scaffold teardown at {} for {} not completed: {}",
                            pos.toShortString(), bot.getName().getString(), result);
                }
                return ok;
            } catch (java.util.concurrent.TimeoutException te) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    mining.cancel(true);
                    return false;
                }
                if (System.currentTimeMillis() - start > TEARDOWN_BREAK_CEILING_MS) {
                    LOGGER.warn("Scaffold teardown at {} exceeded {}ms; giving up",
                            pos.toShortString(), TEARDOWN_BREAK_CEILING_MS);
                    mining.cancel(true);
                    return false;
                }
            } catch (InterruptedException ie) {
                mining.cancel(true);
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                mining.cancel(true);
                return false;
            }
        }
    }

    /**
     * True when at least one base covers {@code pos} (same radius rule as
     * {@link BotHomeService#findBaseNearPosition}) and every base covering it is owned literally
     * by SERVER. A player base overlapping the Spawn base makes this false.
     */
    private static boolean onlyServerOwnedBasesCover(ServerWorld world, BlockPos pos) {
        var server = world.getServer();
        if (server == null || pos == null) {
            return false;
        }
        boolean covered = false;
        for (BotHomeService.BaseEntry base : BotHomeService.listBases(server, world)) {
            if (base == null || base.pos() == null) {
                continue;
            }
            int radius = base.radius() > 0 ? base.radius() : BotHomeService.DEFAULT_BASE_PROTECTION_RADIUS;
            if (!base.pos().isWithinDistance(pos, radius)) {
                continue;
            }
            if (!WoodcutBaseAccessPolicy.isServerOwnedBaseOwner(base.ownerUuid())) {
                return false;
            }
            covered = true;
        }
        return covered;
    }

    // ---------------------------------------------------------------
    // Own Spawn-base scaffold teardown (1.1.268)
    // ---------------------------------------------------------------

    /** R4: fixed time budget for the abort-safe teardown of a fell's own Spawn-base scaffold. */
    public static final long OWNED_TEARDOWN_BUDGET_MS = 20_000L;

    /** Reach (squared, to the block centre) a remembered-pass nudge aims for. */
    private static final double OWNED_APPROACH_REACH_SQ = 9.0D;
    private static final long OWNED_APPROACH_TIMEOUT_MS = 3_000L;

    /**
     * @param removed          blocks mined, or found already gone
     * @param total            this bot's owned positions considered
     * @param removedPositions the positions counted in {@code removed}
     */
    public record OwnedTeardownResult(int removed, int total, List<BlockPos> removedPositions) {
        static final OwnedTeardownResult NONE = new OwnedTeardownResult(0, 0, List.of());
    }

    private enum OwnedPrecheck { GONE, CHANGED, NOT_OWNED, OUT_OF_REACH, BREAK, UNKNOWN }

    /**
     * Bounded teardown of this bot's own scaffold (positions in {@link OwnedScaffoldRegistry}).
     * Worker thread only (refused on the server thread). Top to bottom; a position that is no
     * longer a scaffold material is forgotten, never broken; a block that cannot be reached or
     * mined is given up and the rest of its column is left (no floating blocks below it).
     *
     * @param abortSafe R4: the skill abort latch and thread interrupts do not stop it (the
     *                  interrupt flag is restored on return). It still stops on a server stop, at
     *                  {@code budgetMs}, or when a new task takes this bot's task slot.
     * @param approach  R5 remembered pass: nudge toward an out-of-reach block before giving up on it
     */
    public static OwnedTeardownResult teardownOwnedScaffold(ServerPlayerEntity bot, Collection<BlockPos> positions,
                                                            long budgetMs, boolean abortSafe, boolean approach) {
        if (bot == null || positions == null || positions.isEmpty()
                || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return OwnedTeardownResult.NONE;
        }
        var server = world.getServer();
        if (server == null || TaskService.isServerStopping()) {
            return OwnedTeardownResult.NONE;
        }
        if (server.isOnThread()) {
            LOGGER.warn("[spawn-scaffold] refusing own scaffold teardown for {} on the server thread",
                    bot.getName().getString());
            return OwnedTeardownResult.NONE;
        }
        List<BlockPos> owned = new ArrayList<>();
        for (BlockPos pos : new LinkedHashSet<>(positions)) {
            if (pos != null && OwnedScaffoldRegistry.isOwned(bot, world, pos)) {
                owned.add(pos.toImmutable());
            }
        }
        if (owned.isEmpty()) {
            return OwnedTeardownResult.NONE;
        }
        BlockPos from = bot.getBlockPos();
        owned.sort(Comparator.comparingInt(BlockPos::getY).reversed()
                .thenComparingDouble(p -> p.getSquaredDistance(from)));

        // Abort-safe: a stop interrupts this worker; clear the flag so waits work, restore it at the end.
        java.util.concurrent.atomic.AtomicBoolean interrupted =
                new java.util.concurrent.atomic.AtomicBoolean(abortSafe && Thread.interrupted());
        boolean hadTaskAtStart = TaskService.hasActiveTask(bot.getUuid());
        long deadline = System.currentTimeMillis() + Math.max(1_000L, budgetMs);
        Set<Long> givenUpColumns = new HashSet<>();
        List<BlockPos> removed = new ArrayList<>();
        try {
            for (BlockPos pos : owned) {
                if (System.currentTimeMillis() >= deadline || TaskService.isServerStopping()) {
                    break;
                }
                if (!abortSafe && SkillManager.shouldAbortSkill(bot)) {
                    break;
                }
                if (abortSafe && !hadTaskAtStart && TaskService.hasActiveTask(bot.getUuid())) {
                    LOGGER.info("[spawn-scaffold] {} own scaffold teardown stops: a new task took over",
                            bot.getName().getString());
                    break;
                }
                long column = BlockPos.asLong(pos.getX(), 0, pos.getZ());
                if (givenUpColumns.contains(column)) {
                    continue;
                }
                OwnedPrecheck verdict = precheckOwned(server, world, bot, pos, abortSafe, interrupted);
                if (verdict == OwnedPrecheck.OUT_OF_REACH && approach && !abortSafe) {
                    MovementService.nudgeTowardUntilClose(bot, pos, OWNED_APPROACH_REACH_SQ,
                            OWNED_APPROACH_TIMEOUT_MS, 0.20D, "own-scaffold-cleanup");
                    verdict = precheckOwned(server, world, bot, pos, abortSafe, interrupted);
                }
                if (verdict == OwnedPrecheck.GONE) {
                    removed.add(pos);
                    continue;
                }
                if (verdict == OwnedPrecheck.CHANGED || verdict == OwnedPrecheck.NOT_OWNED) {
                    continue;
                }
                if (verdict != OwnedPrecheck.BREAK) {
                    givenUpColumns.add(column);
                    continue;
                }
                if (mineOwnedScaffoldBlock(bot, pos, deadline, abortSafe, interrupted)) {
                    removed.add(pos);
                    pauseOwned(150L, abortSafe, interrupted); // let gravity settle the bot
                } else {
                    givenUpColumns.add(column);
                }
                if (abortSafe && Thread.interrupted()) {
                    interrupted.set(true);
                }
            }
        } finally {
            if (interrupted.get()) {
                Thread.currentThread().interrupt();
            }
        }
        return new OwnedTeardownResult(removed.size(), owned.size(), removed);
    }

    /** Server-thread pre-check of one owned position; forgets it when it is gone or changed. */
    private static OwnedPrecheck precheckOwned(net.minecraft.server.MinecraftServer server, ServerWorld world,
                                               ServerPlayerEntity bot, BlockPos pos, boolean abortSafe,
                                               java.util.concurrent.atomic.AtomicBoolean interrupted) {
        var future = new java.util.concurrent.CompletableFuture<OwnedPrecheck>();
        server.execute(() -> {
            try {
                var state = world.getBlockState(pos);
                boolean owned = OwnedScaffoldRegistry.isOwned(bot, world, pos);
                boolean gone = state.isAir();
                boolean material = OwnedScaffoldRegistry.isScaffoldMaterial(state);
                if (ScaffoldOwnershipPolicy.shouldForget(owned, gone, material)) {
                    OwnedScaffoldRegistry.forget(bot, world, pos);
                    future.complete(gone ? OwnedPrecheck.GONE : OwnedPrecheck.CHANGED);
                    return;
                }
                if (!ScaffoldOwnershipPolicy.mayBreakOwned(owned, material)) {
                    future.complete(OwnedPrecheck.NOT_OWNED);
                    return;
                }
                boolean standingOnBlock = bot.getBlockPos().equals(pos.up());
                future.complete(standingOnBlock || BlockInteractionService.canInteract(bot, pos)
                        ? OwnedPrecheck.BREAK : OwnedPrecheck.OUT_OF_REACH);
            } catch (Exception e) {
                future.complete(OwnedPrecheck.UNKNOWN);
            }
        });
        long until = System.currentTimeMillis() + 2_000L;
        while (true) {
            try {
                return future.get(Math.max(1L, until - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                interrupted.set(true);
                if (!abortSafe) {
                    Thread.currentThread().interrupt();
                    return OwnedPrecheck.UNKNOWN;
                }
                // Abort-safe: a second stop must not drop the check; the caller restores the flag.
            } catch (Exception e) {
                return OwnedPrecheck.UNKNOWN;
            }
        }
    }

    /** Survival-timed mine of one owned scaffold block; true when MiningTool reports it gone. */
    private static boolean mineOwnedScaffoldBlock(ServerPlayerEntity bot, BlockPos pos, long deadline, boolean abortSafe,
                                                  java.util.concurrent.atomic.AtomicBoolean interrupted) {
        java.util.concurrent.CompletableFuture<String> mining = MiningTool.mineBlock(bot, pos, false, false, abortSafe);
        long ceiling = Math.min(deadline, System.currentTimeMillis() + TEARDOWN_BREAK_CEILING_MS);
        while (true) {
            try {
                String result = mining.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                boolean ok = result != null && !result.startsWith("\u26a0\ufe0f");
                if (!ok) {
                    LOGGER.info("[spawn-scaffold] own scaffold at {} for {} not removed: {}",
                            pos.toShortString(), bot.getName().getString(), result);
                }
                return ok;
            } catch (java.util.concurrent.TimeoutException te) {
                if ((!abortSafe && SkillManager.shouldAbortSkill(bot))
                        || TaskService.isServerStopping()
                        || System.currentTimeMillis() > ceiling) {
                    mining.cancel(true);
                    return false;
                }
            } catch (InterruptedException ie) {
                interrupted.set(true);
                if (!abortSafe) {
                    mining.cancel(true);
                    Thread.currentThread().interrupt();
                    return false;
                }
                // Abort-safe: the stop already happened; keep the bounded swing going. The caller
                // restores the interrupt flag when the teardown ends.
            } catch (Exception e) {
                mining.cancel(true);
                return false;
            }
        }
    }

    /** Sleep; an interrupt is recorded (and re-set at once unless abort-safe). */
    private static void pauseOwned(long ms, boolean abortSafe, java.util.concurrent.atomic.AtomicBoolean interrupted) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            interrupted.set(true);
            if (!abortSafe) {
                Thread.currentThread().interrupt();
            }
        }
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
                                                                                 int maxAttempts,
                                                                                 boolean ownScaffold) {
        BlockPos targetPos = initialTarget;
        String lastReason = "unknown";
        for (int attempt = 0; attempt < Math.max(1, maxAttempts); attempt++) {
            BotActions.PlaceResult result = BotActions.tryPlaceBlockAt(bot, targetPos, Direction.UP, SCAFFOLD_BLOCKS,
                    false, ownScaffold);
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
                                                                               long maxWaitMs,
                                                                               boolean ownScaffold) {
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
            ScaffoldPlaceAttemptResult attempt = tryPlaceScaffoldWithAdaptiveTarget(bot, world, targetPos,
                    attempts == 1 ? 2 : 1, ownScaffold);
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
                // Adoption, not a placement: the block may not be this bot's, so it is never
                // registered as own scaffold (only BotActions' accepted placement registers).
                return new ScaffoldPlaceAttemptResult(true, targetPos, ScaffoldOwnershipPolicy.PLACED_DETECTED_AFTER_RETRY);
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

    // ---------------------------------------------------------------
    // Ladder column helpers (moved verbatim from CollectDirtSkill)
    // ---------------------------------------------------------------

    /**
     * Picks the horizontal direction with the most solid support blocks along a vertical scan,
     * suitable for anchoring a ladder column. Returns null if no direction has at least 2 supports.
     */
    public static Direction pickLadderSupportDirection(ServerWorld world, BlockPos start, int scanHeight) {
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

    /**
     * Finds a walkable exit position reachable from a ladder column anchored against {@code supportDir}.
     */
    public static BlockPos findLadderExit(ServerWorld world, BlockPos start, Direction supportDir, int scanHeight) {
        if (world == null || start == null || supportDir == null) {
            return null;
        }
        for (int dy = 1; dy <= scanHeight; dy++) {
            BlockPos climbPos = start.up(dy);
            if (!isLadderPassable(world, climbPos) || !world.getBlockState(climbPos.offset(supportDir)).isSolidBlock(world, climbPos.offset(supportDir))) {
                continue;
            }
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos exitFoot = climbPos.offset(dir);
                BlockPos exitHead = exitFoot.up();
                BlockPos exitBelow = exitFoot.down();
                if (isLadderPassable(world, exitFoot)
                        && isLadderPassable(world, exitHead)
                        && world.getBlockState(exitBelow).isSolidBlock(world, exitBelow)) {
                    return exitFoot.toImmutable();
                }
            }
        }
        return null;
    }

    /**
     * Places a ladder column from {@code start} upward, facing away from {@code supportDir}.
     *
     * @return true if at least one ladder was placed
     */
    public static boolean placeLadderColumn(ServerWorld world, ServerPlayerEntity player, BlockPos start, Direction supportDir, int height) {
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
            if (!isLadderPassable(world, pos)) {
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

    private static boolean isLadderPassable(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty() && world.getFluidState(pos).isEmpty();
    }
}
