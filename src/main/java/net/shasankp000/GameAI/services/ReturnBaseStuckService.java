package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.skills.support.FallingBlockStabilizer;
import net.shasankp000.GameAI.skills.support.MiningHazardDetector;
import net.shasankp000.GameAI.skills.support.TreeDetector;
import net.shasankp000.PlayerUtils.ToolSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks stagnation during RETURNING_BASE mode and triggers flare signals when stuck.
 * Now includes intermediate escape attempts (backup, sidestep, pillar) before flare.
 * Also includes "panic flee" mode inspired by Minecraft mob AI - when stuck, the bot
 * will temporarily flee in a random direction (like a panicked mob) to navigate around obstacles.
 */
public final class ReturnBaseStuckService {

    private static final Logger LOGGER = LoggerFactory.getLogger("return-base-stuck");

    /**
     * Number of ticks to wait before considering the bot stuck.
     * 20 ticks = 1 second; 600 ticks = 30 seconds.
     */
    private static final int STUCK_THRESHOLD_TICKS = 180;
    
    /** At this threshold, try backing up and sidestepping */
    private static final int BACKUP_ATTEMPT_TICKS = 60;

    /** At this threshold, try clearing immediate obstructions by mining (more aggressive progress). */
    private static final int MINE_ESCAPE_TICKS = 45;

    /** At this threshold, try a sustained staircase-up escape until reaching surface/sky. */
    private static final int MINE_TO_SURFACE_TICKS = 135;

    /** Cooldown between mining escape attempts. */
    private static final long MINE_ESCAPE_COOLDOWN_MS = 1200L;

    /** Time budget for a single obstruction-mining escape attempt (continuous burst). */
    private static final long MINE_ESCAPE_MAX_MS = 9_000L;

    /** Cooldown between mine-to-surface escape attempts (more expensive). */
    private static final long MINE_TO_SURFACE_COOLDOWN_MS = 30_000L;

    /** Time budget for a single mine-to-surface escape attempt. */
    private static final long MINE_TO_SURFACE_MAX_MS = 45_000L;

    /** Maximum number of upward steps during mine-to-surface escape. */
    private static final int MINE_TO_SURFACE_MAX_STEPS = 64;

    /** Per attempt, mine at most this many blocks. */
    private static final int MINE_ESCAPE_MAX_BLOCKS = 12;
    
    /** At this threshold, trigger panic flee (like a mob being attacked) */
    private static final int PANIC_FLEE_TICKS = 90;

    /** Allow retrying panic-flee if target selection fails (cooldown). */
    private static final long PANIC_FLEE_RETRY_COOLDOWN_MS = 5000L;
    
    /** At this threshold, try pillar escape */
    private static final int PILLAR_ATTEMPT_TICKS = 120;
    
    /** How long the panic flee lasts in milliseconds (3 seconds) */
    private static final long PANIC_FLEE_DURATION_MS = 3000L;
    
    /** Distance to flee during panic mode (in blocks) */
    private static final int PANIC_FLEE_DISTANCE = 8;

    /**
     * Cheap micro-escape: try a small nudge/step into nearby open space.
     * This should resolve common "under a cliff" and "just turn/step out" situations
     * without invoking heavier planning or block breaking.
     */
    private static final int QUICK_NUDGE_AFTER_TICKS = 30;
    private static final long QUICK_NUDGE_COOLDOWN_MS = 900L;

    /** Only count progress when distance-to-base improves by at least this many blocks. */
    private static final double MIN_BASE_PROGRESS_BLOCKS = 0.35D;

    /**
     * Minimum distance from base (in blocks) to trigger a flare.
     * If within this distance, bot should be able to reach base without flare.
     */
    private static final double MIN_FLARE_DISTANCE = 20.0;

    /**
     * Cooldown in milliseconds between flare attempts (5 minutes).
     */
    private static final long FLARE_COOLDOWN_MS = 5 * 60 * 1000L;
    
    /** Blocks that can be used for pillaring up */
    private static final Set<Item> PILLAR_BLOCKS = Set.of(
            Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
            Items.GRAVEL, Items.SAND, Items.NETHERRACK,
            Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS,
            Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS
    );

    private static final Map<UUID, BlockPos> LAST_BLOCK_POS = new ConcurrentHashMap<>();
    private static final Map<UUID, Vec3d> LAST_POS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> STAGNANT_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> BEST_BASE_DIST = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_FLARE_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_QUICK_NUDGE_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_MINE_ESCAPE_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_MINE_SURFACE_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> BACKUP_ATTEMPTED = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> PILLAR_ATTEMPTED = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> ESCAPE_IN_PROGRESS = new ConcurrentHashMap<>();
    
    /**
     * NextBot-inspired: Track failed nudge directions to avoid repeating failures.
     * Maps bot UUID to a list of recently failed direction offsets.
     * This enables "wall-following" behavior - if going toward target fails,
     * try perpendicular directions instead of repeatedly hitting the same wall.
     */
    private static final Map<UUID, java.util.Deque<int[]>> FAILED_NUDGE_DIRS = new ConcurrentHashMap<>();
    private static final int MAX_FAILED_DIR_MEMORY = 8;
    
    /**
     * Anchor position: where the bot was when it FIRST became stuck.
     * We only consider it "real progress" if the bot moves significantly from this anchor,
     * not just tiny velocity-induced jiggling.
     */
    private static final Map<UUID, BlockPos> STUCK_ANCHOR_POS = new ConcurrentHashMap<>();
    private static final double MIN_ESCAPE_DISTANCE = 2.0; // Must move 2+ blocks from anchor to clear stuck state
    
    /**
     * Panic flee state: tracks when a bot is in "panic" mode (fleeing like a mob).
     * When in panic mode, the bot ignores return-to-base and just runs in a random direction.
     */
    private static final Map<UUID, Boolean> PANIC_FLEE_ATTEMPTED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_PANIC_FLEE_MS = new ConcurrentHashMap<>();

    private ReturnBaseStuckService() {}

    /**
     * Called each tick while bot is in RETURNING_BASE mode.
     * Returns true if bot is considered stuck and should trigger a flare.
     *
     * @param bot       the bot
     * @param baseTarget the target base position
     * @return true if stuck and flare should be triggered
     */
    public static boolean tickAndCheckStuck(ServerPlayerEntity bot, Vec3d baseTarget) {
        if (bot == null || baseTarget == null) {
            LOGGER.debug("ReturnBaseStuck: null bot or baseTarget");
            return false;
        }

        UUID botId = bot.getUuid();
        BlockPos curBlock = bot.getBlockPos();
        Vec3d curPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        int currentStagnant = STAGNANT_TICKS.getOrDefault(botId, 0);

        // Track distance-to-base as the primary notion of "real progress" during return-to-base.
        // This avoids the common failure mode where the bot oscillates between nearby tiles (or jitters)
        // and repeatedly resets stagnation even though it's not getting closer to base.
        double baseDx = baseTarget.x - bot.getX();
        double baseDz = baseTarget.z - bot.getZ();
        double distToBase = Math.sqrt(baseDx * baseDx + baseDz * baseDz);
        Double bestDistBoxed = BEST_BASE_DIST.get(botId);
        double bestDist = bestDistBoxed != null ? bestDistBoxed : distToBase;
        if (bestDistBoxed == null) {
            BEST_BASE_DIST.put(botId, distToBase);
        }
        
        // Log every tick to confirm this is being called
        if (currentStagnant % 20 == 0) {
            LOGGER.info("ReturnBaseStuck TICK: bot={} pos={} stagnant={} escapeInProgress={}", 
                    bot.getName().getString(), curBlock.toShortString(), currentStagnant,
                    ESCAPE_IN_PROGRESS.getOrDefault(botId, false));
        }
        
        // Don't tick if an escape attempt is currently in progress (running async)
        if (ESCAPE_IN_PROGRESS.getOrDefault(botId, false)) {
            LOGGER.debug("ReturnBaseStuck: skipping tick, escape in progress for {}", bot.getName().getString());
            return false;
        }

        // Progress detection:
        // We must NOT treat tiny jiggles (or meaningless detours) as "progress".
        // For return-to-base, meaningful progress is:
        // - distance to base improves by >= MIN_BASE_PROGRESS_BLOCKS, OR
        // - escaping >= MIN_ESCAPE_DISTANCE from the stuck anchor.
        Vec3d prevPos = LAST_POS.get(botId);
        BlockPos prevBlock = LAST_BLOCK_POS.get(botId);
        int stagnant = STAGNANT_TICKS.getOrDefault(botId, 0);

        // Transient debug: show detailed per-tick state when verbose debug is enabled
        DebugToggleService.debug(LOGGER, "ReturnBaseStuck DEBUG: bot={} distToBase={} bestDist={} improvedBaseDistance={} anchor={} escapedFromAnchor={} stagnant={} prevBlock={} prevPos={}",
            bot.getName().getString(), distToBase, bestDist, improvedBaseDistance,
            anchorPos == null ? "null" : anchorPos.toShortString(), escapedFromAnchor,
            stagnant,
            prevBlock == null ? "null" : prevBlock.toShortString(),
            prevPos == null ? "null" : String.format("%.2f,%.2f,%.2f", prevPos.x, prevPos.y, prevPos.z));

        // Track anchor position - where the bot first became stuck
        BlockPos anchorPos = STUCK_ANCHOR_POS.get(botId);
        if (anchorPos == null && stagnant >= QUICK_NUDGE_AFTER_TICKS) {
            // Set anchor when we first start nudging
            STUCK_ANCHOR_POS.put(botId, curBlock.toImmutable());
            anchorPos = curBlock;
            LOGGER.debug("ReturnBaseStuck: set anchor at {} for stuck bot", anchorPos.toShortString());
        }
        
        // Check if we've escaped far enough from the anchor to clear failed direction memory
        boolean escapedFromAnchor = false;
        if (anchorPos != null) {
            double anchorDx = curPos.x - (anchorPos.getX() + 0.5);
            double anchorDz = curPos.z - (anchorPos.getZ() + 0.5);
            double anchorDist = Math.sqrt(anchorDx * anchorDx + anchorDz * anchorDz);
            escapedFromAnchor = anchorDist >= MIN_ESCAPE_DISTANCE;
        }

        boolean improvedBaseDistance = distToBase <= (bestDist - MIN_BASE_PROGRESS_BLOCKS);
        if (improvedBaseDistance) {
            BEST_BASE_DIST.put(botId, distToBase);
        }

        boolean madeMeaningfulProgress = prevPos == null || improvedBaseDistance || escapedFromAnchor;

        if (!madeMeaningfulProgress) {
            stagnant++;
        } else {
            // Meaningful progress, reset stagnation counter
            if (prevPos != null && stagnant > 10 && prevBlock != null) {
                LOGGER.debug("ReturnBaseStuck: {} made meaningful progress, resetting stagnant (was {} ticks, moved from {} to {})",
                        bot.getName().getString(), stagnant, prevBlock.toShortString(), curBlock.toShortString());
            }
            stagnant = 0;
            BEST_BASE_DIST.put(botId, distToBase);
            LAST_QUICK_NUDGE_MS.remove(botId);
            LAST_MINE_ESCAPE_MS.remove(botId);
            BACKUP_ATTEMPTED.remove(botId);
            PILLAR_ATTEMPTED.remove(botId);
            PANIC_FLEE_ATTEMPTED.remove(botId);
            LAST_PANIC_FLEE_MS.remove(botId);

            // Only clear failed direction memory if we've truly escaped the anchor.
            if (escapedFromAnchor) {
                LOGGER.info("ReturnBaseStuck: {} escaped from anchor (dist >= {}), clearing failed dir memory",
                        bot.getName().getString(), MIN_ESCAPE_DISTANCE);
                FAILED_NUDGE_DIRS.remove(botId);
                STUCK_ANCHOR_POS.remove(botId);
            }
        }

        // Always update last-known positions (do not gate on progress), so our per-tick movement
        // calculations stay local and we avoid huge deltas after long stalls.
        LAST_POS.put(botId, curPos);
        LAST_BLOCK_POS.put(botId, curBlock.toImmutable());
        STAGNANT_TICKS.put(botId, stagnant);
        
        // Periodic logging at threshold milestones
        if (stagnant == BACKUP_ATTEMPT_TICKS || stagnant == PANIC_FLEE_TICKS 
                || stagnant == PILLAR_ATTEMPT_TICKS || stagnant == STUCK_THRESHOLD_TICKS) {
            LOGGER.info("ReturnBaseStuck: bot={} reached {} ticks stagnant at pos={} backupAttempted={} panicAttempted={} pillarAttempted={}", 
                    bot.getName().getString(), stagnant, curBlock.toShortString(),
                    BACKUP_ATTEMPTED.getOrDefault(botId, false),
                    PANIC_FLEE_ATTEMPTED.getOrDefault(botId, false),
                    PILLAR_ATTEMPTED.getOrDefault(botId, false));
        }

        // First line of defense: quick nudge into adjacent open space.
        // This resolves most "stuck under a cliff" / "just turn and step out" cases quickly.
        if (stagnant >= QUICK_NUDGE_AFTER_TICKS) {
            long nowMs = System.currentTimeMillis();
            long lastNudge = LAST_QUICK_NUDGE_MS.getOrDefault(botId, -1L);
            if (lastNudge < 0 || (nowMs - lastNudge) >= QUICK_NUDGE_COOLDOWN_MS) {
                boolean nudged = tryQuickNudge(bot, baseTarget, stagnant);
                if (nudged) {
                    LAST_QUICK_NUDGE_MS.put(botId, nowMs);
                    return false;
                }
            }
        }
        
        // At BACKUP_ATTEMPT_TICKS threshold, try backing up and sidestepping
        if (stagnant >= BACKUP_ATTEMPT_TICKS && !BACKUP_ATTEMPTED.getOrDefault(botId, false)) {
            BACKUP_ATTEMPTED.put(botId, true);
            LOGGER.info("Bot {} stuck for {} ticks, attempting backup/sidestep escape", 
                bot.getName().getString(), stagnant);
            DebugToggleService.debug(LOGGER, "ReturnBaseStuck: scheduling backup/sidestep escape for bot={} stagnant={}", bot.getName().getString(), stagnant);
            runEscapeAsync(botId, () -> tryBackupAndSidestep(bot, baseTarget));
            return false; // Don't trigger flare yet
        }

        // More aggressive progress: periodically mine immediate obstructions (headroom/staircase or tunnel).
        // This is intentionally earlier than pillaring and does not require a full path to exist.
        if (stagnant >= MINE_ESCAPE_TICKS) {
            long nowMs = System.currentTimeMillis();
            long lastMine = LAST_MINE_ESCAPE_MS.getOrDefault(botId, -1L);
            if (lastMine < 0 || (nowMs - lastMine) >= MINE_ESCAPE_COOLDOWN_MS) {
                LAST_MINE_ESCAPE_MS.put(botId, nowMs);
                DebugToggleService.debug(LOGGER, "ReturnBaseStuck: scheduling mine-escape for bot={} stagnant={} lastMine={}", bot.getName().getString(), stagnant, lastMine);
                LOGGER.info("ReturnBaseStuck: bot={} stuck for {} ticks, attempting obstruction mining escape toward base",
                        bot.getName().getString(), stagnant);
                runEscapeAsync(botId, () -> tryMineTowardBaseEscape(bot, baseTarget));
                return false;
            } else {
                DebugToggleService.debug(LOGGER, "ReturnBaseStuck: mine-escape suppressed by cooldown for bot={} lastMine={} nowMs={}", bot.getName().getString(), lastMine, nowMs);
            }
        }
        
        // At PANIC_FLEE_TICKS threshold, trigger "panic flee" like a scared mob
        // This uses Minecraft's natural mob pathfinding behavior - flee in a random direction
        // which often successfully navigates around complex obstacles
        if (stagnant >= PANIC_FLEE_TICKS && !PANIC_FLEE_ATTEMPTED.getOrDefault(botId, false)) {
            long nowMs = System.currentTimeMillis();
            long lastPanic = LAST_PANIC_FLEE_MS.getOrDefault(botId, -1L);
            if (lastPanic < 0 || (nowMs - lastPanic) >= PANIC_FLEE_RETRY_COOLDOWN_MS) {
                LAST_PANIC_FLEE_MS.put(botId, nowMs);
                DebugToggleService.debug(LOGGER, "ReturnBaseStuck: attempting panic-flee for bot={} stagnant={} lastPanic={}", bot.getName().getString(), stagnant, lastPanic);
                if (tryTriggerPanicFlee(bot, baseTarget)) {
                    PANIC_FLEE_ATTEMPTED.put(botId, true);
                    DebugToggleService.debug(LOGGER, "ReturnBaseStuck: panic-flee started for bot={}", bot.getName().getString());
                    return false; // Panic flee initiated, don't trigger flare
                } else {
                    DebugToggleService.debug(LOGGER, "ReturnBaseStuck: panic-flee attempt failed for bot={}", bot.getName().getString());
                }
            }
        }
        
        // At PILLAR_ATTEMPT_TICKS threshold, try pillar escape
        if (stagnant >= PILLAR_ATTEMPT_TICKS && !PILLAR_ATTEMPTED.getOrDefault(botId, false)) {
            PILLAR_ATTEMPTED.put(botId, true);
            LOGGER.info("Bot {} stuck for {} ticks, attempting pillar escape", 
                bot.getName().getString(), stagnant);
            DebugToggleService.debug(LOGGER, "ReturnBaseStuck: scheduling pillar-escape for bot={} stagnant={}", bot.getName().getString(), stagnant);
            runEscapeAsync(botId, () -> tryPillarEscape(bot));
            return false; // Don't trigger flare yet
        }

        // Last-resort but still autonomous: mine a staircase upward until we reach surface/sky.
        // This is intended for "buried under terrain" / "trapped in a pocket" situations.
        if (stagnant >= MINE_TO_SURFACE_TICKS) {
            long nowMs = System.currentTimeMillis();
            long last = LAST_MINE_SURFACE_MS.getOrDefault(botId, -1L);
            if (last < 0 || (nowMs - last) >= MINE_TO_SURFACE_COOLDOWN_MS) {
                LAST_MINE_SURFACE_MS.put(botId, nowMs);
                DebugToggleService.debug(LOGGER, "ReturnBaseStuck: scheduling mine-to-surface for bot={} stagnant={} last={}", bot.getName().getString(), stagnant, last);
                LOGGER.info("ReturnBaseStuck: bot={} stuck for {} ticks, attempting mine-to-surface escape",
                        bot.getName().getString(), stagnant);
                runEscapeAsync(botId, () -> tryMineToSurfaceEscape(bot, baseTarget));
                return false;
            } else {
                DebugToggleService.debug(LOGGER, "ReturnBaseStuck: mine-to-surface suppressed by cooldown for bot={} last={} nowMs={}", bot.getName().getString(), last, nowMs);
            }
        }

        // Not stuck long enough for flare yet
        if (stagnant < STUCK_THRESHOLD_TICKS) {
            return false;
        }

        // Check distance from base
        double dx = baseTarget.x - bot.getX();
        double dz = baseTarget.z - bot.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        // Too close to base, no need for flare
        if (horizDist < MIN_FLARE_DISTANCE) {
            LOGGER.debug("Bot {} stuck but within {} blocks of base, not triggering flare", 
                    bot.getName().getString(), MIN_FLARE_DISTANCE);
            return false;
        }

        // Check cooldown
        long now = System.currentTimeMillis();
        Long lastFlare = LAST_FLARE_MS.get(botId);
        if (lastFlare != null && (now - lastFlare) < FLARE_COOLDOWN_MS) {
            LOGGER.debug("Bot {} stuck but flare on cooldown", bot.getName().getString());
            return false;
        }

        // Check if bot is in a protected zone where breaking blocks is not allowed
        if (bot.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld
                && ProtectedZoneService.isProtected(bot.getBlockPos(), serverWorld, null)) {
            LOGGER.debug("Bot {} stuck in protected zone, not triggering flare (would need to break blocks)", 
                    bot.getName().getString());
            return false;
        }

        // Check if bot has fireworks
        if (!hasFireworkRocket(bot)) {
            LOGGER.info("Bot {} is stuck but has no firework rockets for flare", bot.getName().getString());
            return false;
        }

        LOGGER.info("Bot {} stuck for {} ticks at {} blocks from base, triggering flare", 
                bot.getName().getString(), stagnant, (int) horizDist);
        return true;
    }

    /**
     * Mark that a flare was triggered, updating cooldown.
     */
    public static void markFlareSent(UUID botId) {
        if (botId != null) {
            LAST_FLARE_MS.put(botId, System.currentTimeMillis());
            // Reset stagnation after flare
            STAGNANT_TICKS.put(botId, 0);
        }
    }

    /**
     * Clear tracking state for a bot (e.g., when mode changes or bot arrives).
     */
    public static void clear(UUID botId) {
        if (botId != null) {
            LAST_BLOCK_POS.remove(botId);
            LAST_POS.remove(botId);
            STAGNANT_TICKS.remove(botId);
            BEST_BASE_DIST.remove(botId);
            LAST_QUICK_NUDGE_MS.remove(botId);
            LAST_MINE_ESCAPE_MS.remove(botId);
            LAST_MINE_SURFACE_MS.remove(botId);
            BACKUP_ATTEMPTED.remove(botId);
            PILLAR_ATTEMPTED.remove(botId);
            ESCAPE_IN_PROGRESS.remove(botId);
            FAILED_NUDGE_DIRS.remove(botId);
            STUCK_ANCHOR_POS.remove(botId);
            PANIC_FLEE_ATTEMPTED.remove(botId);
            LAST_PANIC_FLEE_MS.remove(botId);
        }
    }

    /**
     * Last-resort escape: carve a small upward staircase until surface/sky is reached.
     *
     * <p>Safety:
     * <ul>
     *   <li>Respects protected zones and MiningHazardDetector (ores, chests, liquids, structure blocks).</li>
     *   <li>Avoids "human blocks" areas (doors/planks/etc.) via TreeDetector to reduce base griefing.</li>
     *   <li>Uses descent-style falling-block stabilization (sand/gravel settling + re-clear).</li>
     * </ul>
     */
    private static void tryMineToSurfaceEscape(ServerPlayerEntity bot, Vec3d baseTarget) {
        if (bot == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        BlockPos origin = bot.getBlockPos();
        // Don't mine near player builds (doors/planks/etc.). This protects bases/villages.
        if (TreeDetector.isNearHumanBlocks(world, origin, 8)) {
            LOGGER.info("ReturnBaseStuck: mine-to-surface aborted (near human blocks at {})", origin.toShortString());
            return;
        }

        if (isSurfaceReached(world, bot)) {
            LOGGER.info("ReturnBaseStuck: mine-to-surface not needed (already at surface) at {}", origin.toShortString());
            return;
        }

        Direction chosen = chooseBestUpStairDirection(bot, world, baseTarget);
        if (chosen == null) {
            LOGGER.warn("ReturnBaseStuck: mine-to-surface failed to choose a direction at {}", origin.toShortString());
            return;
        }

        long deadline = System.currentTimeMillis() + MINE_TO_SURFACE_MAX_MS;
        int steps = 0;

        while (System.currentTimeMillis() < deadline && steps < MINE_TO_SURFACE_MAX_STEPS && !Thread.currentThread().isInterrupted()) {
            if (isSurfaceReached(world, bot)) {
                LOGGER.info("ReturnBaseStuck: mine-to-surface reached surface after {} step(s) at {}",
                        steps, bot.getBlockPos().toShortString());
                return;
            }

            BlockPos start = bot.getBlockPos();
            BlockPos front = start.offset(chosen);
            BlockPos step = front.up();

            // Ensure we have something to stand on when stepping up.
            if (isPassableForStanding(world, front)) {
                // No step support here; try to nudge forward to find terrain.
                if (isPassable(world, front)) {
                    MovementService.nudgeTowardUntilClose(bot, front, 2.25D, 900L, 0.20, "surface-escape-reposition");
                    sleepQuiet(120);
                    continue;
                }
            }

            // Clear the space we want to occupy (step) and headroom above it. Avoid mining the support block (front).
            java.util.List<BlockPos> breakVolume = new java.util.ArrayList<>();
            breakVolume.add(step);
            breakVolume.add(step.up());
            breakVolume.add(step.up(2));
            // Also clear any low ceiling directly above the bot.
            breakVolume.add(start.up(2));

            // If we're clipping, clear our own feet/head spaces as a priority.
            if (bot.isInsideWall()) {
                breakVolume.add(start);
                breakVolume.add(start.up());
            }

            // Filter and mine.
            int mined = 0;
            for (BlockPos pos : breakVolume) {
                if (pos == null) {
                    continue;
                }
                if (!canMineBlock(bot, world, pos)) {
                    continue;
                }
                // Keep mining local and within reach.
                if (start.getSquaredDistance(pos) > 12.0D) {
                    continue;
                }
                if (tryMineBlock(bot, pos, world)) {
                    mined++;
                    sleepQuiet(120);
                }
            }

            if (mined <= 0) {
                // If we can't mine here, try a different direction.
                Direction alt = chooseBestUpStairDirection(bot, world, baseTarget);
                if (alt != null && alt != chosen) {
                    chosen = alt;
                    continue;
                }
                LOGGER.warn("ReturnBaseStuck: mine-to-surface stalled (no mineable blocks) at {}", start.toShortString());
                return;
            }

            // Falling blocks can refill; wait + re-clear falling refills.
            boolean stabilized = FallingBlockStabilizer.stabilizeAndReclear(
                    bot,
                    null,
                    step,
                    breakVolume,
                    ReturnBaseStuckService::isPassableForMining,
                    ReturnBaseStuckService::isTorchBlock,
                    p -> tryMineBlock(bot, p, world),
                    false
            );
            if (!stabilized) {
                LOGGER.info("ReturnBaseStuck: mine-to-surface paused (falling blocks won't settle) at {}", bot.getBlockPos().toShortString());
                return;
            }

            // Step up if possible.
            if (isPassable(world, step)) {
                MovementService.nudgeTowardUntilClose(bot, step, 2.25D, 1800L, 0.22, "surface-escape-step");
                steps++;
            } else if (isPassable(world, front)) {
                MovementService.nudgeTowardUntilClose(bot, front, 2.25D, 1400L, 0.20, "surface-escape-forward");
            }
            sleepQuiet(120);
        }

        LOGGER.info("ReturnBaseStuck: mine-to-surface ended (steps={}, timeBudgetMs={}) at {}",
                steps, MINE_TO_SURFACE_MAX_MS, bot.getBlockPos().toShortString());
    }

    private static boolean isSurfaceReached(ServerWorld world, ServerPlayerEntity bot) {
        if (world == null || bot == null) {
            return false;
        }
        BlockPos pos = bot.getBlockPos();
        // "Sky visible" above head means we're out from under terrain.
        if (world.isSkyVisible(pos.up(2))) {
            return true;
        }
        // Grass underfoot is a strong proxy for being on/near the surface.
        return world.getBlockState(pos.down()).isOf(Blocks.GRASS_BLOCK);
    }

    private static Direction chooseBestUpStairDirection(ServerPlayerEntity bot, ServerWorld world, Vec3d baseTarget) {
        if (bot == null || world == null) {
            return null;
        }

        // Prefer moving generally toward base when possible, but allow any direction if needed.
        Direction toward = null;
        if (baseTarget != null) {
            double dx = baseTarget.x - bot.getX();
            double dz = baseTarget.z - bot.getZ();
            if (Math.abs(dx) >= Math.abs(dz)) {
                toward = dx >= 0 ? Direction.EAST : Direction.WEST;
            } else {
                toward = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
            }
        }
        Direction[] candidates = toward != null
                ? new Direction[]{toward, toward.rotateYClockwise(), toward.rotateYCounterclockwise(), toward.getOpposite()}
                : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

        BlockPos start = bot.getBlockPos();
        Direction best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Direction dir : candidates) {
            if (dir == null || !dir.getAxis().isHorizontal()) {
                continue;
            }
            BlockPos front = start.offset(dir);
            BlockPos step = front.up();

            // Need solid support at 'front' to step up.
            if (isPassableForStanding(world, front)) {
                continue;
            }

            java.util.List<BlockPos> breakTargets = java.util.List.of(step, step.up(), step.up(2));
            MiningHazardDetector.DetectionResult hazards = MiningHazardDetector.detect(bot, breakTargets, java.util.List.of(step));
            if (hazards.blockingHazard().isPresent()) {
                continue;
            }
            int mineable = 0;
            for (BlockPos p : breakTargets) {
                if (canMineBlock(bot, world, p)) {
                    mineable++;
                }
            }
            // Prefer directions where more of the required volume is mineable (less chance to stall).
            double score = mineable;
            if (toward != null && dir == toward) {
                score += 0.35;
            }
            if (score > bestScore) {
                bestScore = score;
                best = dir;
            }
        }
        return best;
    }

    /**
     * Aggressive stuck escape: mine a couple of immediately-blocking blocks in the direction of base.
     * This favors "staircase" progress by clearing headroom above a 1-block step (front.up/front.up(2)),
     * and falls back to tunneling (front/front.up) if needed.
     */
    private static void tryMineTowardBaseEscape(ServerPlayerEntity bot, Vec3d baseTarget) {
        if (bot == null || baseTarget == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        long deadline = System.currentTimeMillis() + MINE_ESCAPE_MAX_MS;
        int minedTotal = 0;
        int passes = 0;
        boolean clearedHeadroomOnce = false;

        // Continuous burst: mine+move repeatedly until we either clear the local obstruction volume
        // or hit a hard budget (time/blocks).
        while (System.currentTimeMillis() < deadline
                && minedTotal < MINE_ESCAPE_MAX_BLOCKS
                && !Thread.currentThread().isInterrupted()) {
            BlockPos start = bot.getBlockPos();
            int remaining = MINE_ESCAPE_MAX_BLOCKS - minedTotal;
            int minedThisPass = 0;

            // If we're under a low ceiling/overhang, clear the block directly above the bot's head first.
            // For a standing player occupying y and y+1, the block directly above head is y+2.
            if (!clearedHeadroomOnce && remaining > 0) {
                minedThisPass += tryMineEscapeCandidates(
                        bot,
                        world,
                        start,
                        new BlockPos[]{start.up(2)},
                        remaining
                );
                remaining = MINE_ESCAPE_MAX_BLOCKS - (minedTotal + minedThisPass);
                clearedHeadroomOnce = true;
            }

            // If we're clipping, clear our own feet/head spaces as a priority.
            if (remaining > 0 && bot.isInsideWall()) {
                minedThisPass += tryMineEscapeCandidates(
                        bot,
                        world,
                        start,
                        new BlockPos[]{start, start.up()},
                        remaining
                );
                remaining = MINE_ESCAPE_MAX_BLOCKS - (minedTotal + minedThisPass);
            }

            // Prioritize mining in the direction toward base, then perpendiculars, then away.
            Direction toward;
            double dx = baseTarget.x - bot.getX();
            double dz = baseTarget.z - bot.getZ();
            if (Math.abs(dx) >= Math.abs(dz)) {
                toward = dx >= 0 ? Direction.EAST : Direction.WEST;
            } else {
                toward = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
            }
            Direction left = toward.rotateYCounterclockwise();
            Direction right = toward.rotateYClockwise();
            Direction away = toward.getOpposite();
            Direction[] dirs = new Direction[]{toward, left, right, away};

            for (Direction dir : dirs) {
                if (remaining <= 0 || System.currentTimeMillis() >= deadline) {
                    break;
                }
                if (dir == null || !dir.getAxis().isHorizontal()) {
                    continue;
                }
                BlockPos front = start.offset(dir);
                // "Staircase" headroom first, then tunnel.
                // If stepping up onto `front.up()`, the bot will need headroom at `front.up(3)` (two blocks above the step).
                BlockPos step = front.up();
                BlockPos[] candidates = new BlockPos[]{
                        front.up(3),
                        front.up(2),
                        step.up(1),
                        step,
                        front,
                        front.add(dir.getOffsetX(), 0, dir.getOffsetZ()).up()
                };

                int minedDir = tryMineEscapeCandidates(bot, world, start, candidates, remaining);
                if (minedDir > 0) {
                    minedThisPass += minedDir;
                    remaining = MINE_ESCAPE_MAX_BLOCKS - (minedTotal + minedThisPass);

                    // After mining, attempt a short nudge to exploit the opening.
                    // Prefer stepping up onto the front block if possible.
                    if (isPassable(world, step)) {
                        LOGGER.info("ReturnBaseStuck: mine-escape nudging up onto step {}", step.toShortString());
                        MovementService.nudgeTowardUntilClose(bot, step, 2.25D, 1600L, 0.22, "mine-escape-step");
                    } else if (isPassable(world, front)) {
                        LOGGER.info("ReturnBaseStuck: mine-escape nudging into {}", front.toShortString());
                        MovementService.nudgeTowardUntilClose(bot, front, 2.25D, 1400L, 0.20, "mine-escape-forward");
                    }
                }
            }

            minedTotal += minedThisPass;
            passes++;
            if (minedThisPass <= 0) {
                break;
            }
        }

        LOGGER.info("ReturnBaseStuck: mine-escape finished (mined={}, passes={}, budgetMs={}) at {}",
                minedTotal,
                passes,
                MINE_ESCAPE_MAX_MS,
                bot.getBlockPos().toShortString());
    }

    private static final Map<UUID, Map<BlockPos, Long>> OBSTRUCTION_MINE_COOLDOWN = new ConcurrentHashMap<>();
    private static final long OBSTRUCTION_MINE_COOLDOWN_MS = 2_500L;

    private static int tryMineEscapeCandidates(ServerPlayerEntity bot,
                                               ServerWorld world,
                                               BlockPos origin,
                                               BlockPos[] candidates,
                                               int remainingBudget) {
        if (bot == null || world == null || origin == null || candidates == null || remainingBudget <= 0) {
            return 0;
        }
        int mined = 0;
        for (BlockPos pos : candidates) {
            if (mined >= remainingBudget) {
                break;
            }
            if (pos == null) {
                continue;
            }
            // Keep mining extremely local.
            if (origin.getSquaredDistance(pos) > 6.0D) {
                continue;
            }
            if (!obstructionMineAllowed(bot.getUuid(), pos)) {
                continue;
            }
            if (!canMineBlock(bot, world, pos)) {
                continue;
            }

            boolean success = tryMineBlock(bot, pos, world);
            if (success) {
                mined++;
            }
        }
        return mined;
    }

    private static boolean obstructionMineAllowed(UUID botUuid, BlockPos pos) {
        if (botUuid == null || pos == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Map<BlockPos, Long> perBot = OBSTRUCTION_MINE_COOLDOWN.computeIfAbsent(botUuid, __ -> new ConcurrentHashMap<>());
        BlockPos key = pos.toImmutable();
        Long last = perBot.get(key);
        if (last != null && now - last < OBSTRUCTION_MINE_COOLDOWN_MS) {
            return false;
        }
        perBot.put(key, now);
        return true;
    }

    /**
     * Fast, low-cost micro escape to handle common cases:
     * - bot is tucked under an overhang/cliff and needs a sidestep
     * - bot is in a small enclosure but an opening is adjacent (e.g., "turn around and walk out")
     *
     * This intentionally avoids full path planning and avoids block breaking.
     */
    private static boolean tryQuickNudge(ServerPlayerEntity bot, Vec3d baseTarget, int stagnant) {
        if (bot == null || baseTarget == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }

        BlockPos origin = bot.getBlockPos();
        // 8-way neighborhood (including diagonals) tends to resolve "cliff hugging" quickly.
        int[][] offsets = new int[][]{
                { 1, 0}, {-1, 0}, { 0, 1}, { 0,-1},
                { 1, 1}, { 1,-1}, {-1, 1}, {-1,-1}
        };

        UUID botId = bot.getUuid();
        BlockPos best = null;
        int[] bestOffset = null;
        double bestScore = Double.POSITIVE_INFINITY;
        
        // Calculate direction toward base to penalize when stuck
        double toBaseDx = baseTarget.x - bot.getX();
        double toBaseDz = baseTarget.z - bot.getZ();
        double toBaseLen = Math.sqrt(toBaseDx * toBaseDx + toBaseDz * toBaseDz);
        if (toBaseLen > 0.01) {
            toBaseDx /= toBaseLen;
            toBaseDz /= toBaseLen;
        }
        
        // NextBot-inspired: Get recent failed directions to avoid
        java.util.Deque<int[]> failedDirs = FAILED_NUDGE_DIRS.computeIfAbsent(botId, k -> new java.util.LinkedList<>());

        for (int[] off : offsets) {
            BlockPos candidate = origin.add(off[0], 0, off[1]);
            if (!isPassable(world, candidate)) {
                continue;
            }
            
            // CRITICAL: Check if there's actually a clear path to this tile (not just the tile itself)
            // This prevents nudging into walls/cliffs
            if (!isPathClearForNudge(world, origin, candidate)) {
                LOGGER.debug("Quick-nudge: {} blocked by obstacle", candidate.toShortString());
                continue;
            }

            // Openness heuristic: prefer positions with more adjacent passable tiles.
            int openNeighbors = 0;
            for (int[] nOff : offsets) {
                BlockPos n = candidate.add(nOff[0], 0, nOff[1]);
                if (isPassable(world, n)) {
                    openNeighbors++;
                }
            }

            double dx = baseTarget.x - (candidate.getX() + 0.5);
            double dz = baseTarget.z - (candidate.getZ() + 0.5);
            double distToBase = Math.sqrt(dx * dx + dz * dz);
            double moveCost = (off[0] != 0 && off[1] != 0) ? 1.41 : 1.0;
            
            // Calculate how aligned this direction is with "toward base"
            // dot product: 1.0 = same direction, -1.0 = opposite, 0 = perpendicular
            double dirX = off[0];
            double dirZ = off[1];
            double dirLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (dirLen > 0.01) {
                dirX /= dirLen;
                dirZ /= dirLen;
            }
            double towardBaseDot = dirX * toBaseDx + dirZ * toBaseDz;
            
            // When stuck for longer, increasingly penalize "toward base" direction
            // This encourages going AROUND obstacles instead of into them
            double stuckPenalty = 0.0;
            if (stagnant > 60 && towardBaseDot > 0.5) {
                // Heavily penalize if we're stuck and still trying to go toward base
                stuckPenalty = towardBaseDot * (stagnant / 30.0);
            }
            
            // NextBot-inspired: Penalize directions we've recently tried and failed
            // This implements "wall-following" - if we keep failing going one way,
            // we'll naturally try perpendicular or opposite directions
            double failedPenalty = 0.0;
            int failedCount = 0;
            for (int[] failed : failedDirs) {
                if (failed[0] == off[0] && failed[1] == off[1]) {
                    failedCount++;
                }
            }
            if (failedCount > 0) {
                // Each recent failure adds 3.0 penalty - strongly discourages repeating
                failedPenalty = failedCount * 3.0;
            }
            
            // NextBot-inspired: When very stuck (>120 ticks), add randomness to break patterns
            double explorePenalty = 0.0;
            if (stagnant > 120) {
                // Add random factor to encourage exploration of different directions
                explorePenalty = (Math.random() - 0.5) * 4.0;
            }

            // Lower is better. Combine all penalties.
            double score = distToBase + (moveCost * 0.25) - (openNeighbors * 0.45) 
                         + stuckPenalty + failedPenalty + explorePenalty;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
                bestOffset = off;
            }
        }

        if (best == null) {
            LOGGER.warn("ReturnBaseStuck: quick-nudge failed - ALL 8 directions blocked (stagnant={})", stagnant);
            return false;
        }
        
        // Record this direction attempt - we'll check later if we actually made progress
        // For now, optimistically record it. If we remain stuck, it'll accumulate as a "failure"
        if (bestOffset != null) {
            // Track the direction we're about to try
            failedDirs.addFirst(bestOffset);
            // Keep only recent history
            while (failedDirs.size() > MAX_FAILED_DIR_MEMORY) {
                failedDirs.removeLast();
            }
        }

        // Apply a small horizontal velocity toward the chosen tile.
        Vec3d cur = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d tgt = Vec3d.ofCenter(best);
        Vec3d dir = tgt.subtract(cur);
        double lenSq = (dir.x * dir.x) + (dir.z * dir.z);
        if (lenSq < 1.0e-6) {
            return false;
        }
        double len = Math.sqrt(lenSq);
        Vec3d horiz = new Vec3d(dir.x / len, 0.0, dir.z / len);
        Vec3d curVel = bot.getVelocity();
        bot.setVelocity(horiz.x * 0.32, curVel.y, horiz.z * 0.32);
        bot.velocityDirty = true;

        // Face the direction as well (helps if the movement controller expects facing).
        LookController.faceBlock(bot, best);

        LOGGER.info("ReturnBaseStuck: quick-nudge to {} (stagnant={}, score={}, failedMem={})",
                best.toShortString(), stagnant, String.format("%.2f", bestScore), failedDirs.size());
        return true;
    }

    /**
     * Check if the bot has a firework rocket in their inventory.
     */
    private static boolean hasFireworkRocket(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.FIREWORK_ROCKET)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Trigger "panic flee" mode - like a scared mob, the bot runs in a random direction
     * away from the obstacle for a few seconds. Minecraft mobs use this behavior when attacked
     * and it naturally navigates around complex terrain.
     * 
     * @return true if panic flee was successfully triggered
     */
    private static boolean tryTriggerPanicFlee(ServerPlayerEntity bot, Vec3d baseTarget) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        
        BlockPos botPos = bot.getBlockPos();
        
        // Calculate direction AWAY from base (we want to flee in a direction that isn't toward the obstacle)
        double toBaseDx = baseTarget.x - bot.getX();
        double toBaseDz = baseTarget.z - bot.getZ();
        double toBaseLen = Math.sqrt(toBaseDx * toBaseDx + toBaseDz * toBaseDz);
        
        // Normalize direction toward base
        double baseDirX = toBaseLen > 0.01 ? toBaseDx / toBaseLen : 0;
        double baseDirZ = toBaseLen > 0.01 ? toBaseDz / toBaseLen : 0;
        
        // Try multiple random escape directions, preferring those NOT toward base
        java.util.Random random = new java.util.Random();
        BlockPos bestFleeTarget = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        
        for (int attempt = 0; attempt < 12; attempt++) {
            // Generate random angle with bias AWAY from base
            double angle;
            if (attempt < 6) {
                // First 6 attempts: bias toward opposite of base direction (flee behavior)
                double baseAngle = Math.atan2(baseDirZ, baseDirX);
                // Add PI to go opposite direction, plus some randomness
                angle = baseAngle + Math.PI + (random.nextDouble() - 0.5) * Math.PI;
            } else {
                // Last 6 attempts: completely random direction
                angle = random.nextDouble() * 2 * Math.PI;
            }
            
            // Calculate flee target position
            int fleeX = botPos.getX() + (int)(Math.cos(angle) * PANIC_FLEE_DISTANCE);
            int fleeZ = botPos.getZ() + (int)(Math.sin(angle) * PANIC_FLEE_DISTANCE);
            
            // Find walkable Y at this position
            BlockPos fleeTarget = findWalkableY(world, new BlockPos(fleeX, botPos.getY(), fleeZ));
            if (fleeTarget == null) {
                continue;
            }
            
            // Score this target: prefer open areas (more passable neighbors)
            int openNeighbors = 0;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                if (isPassable(world, fleeTarget.offset(dir))) {
                    openNeighbors++;
                }
            }
            
            // Prefer directions away from base
            double fleeDirX = fleeTarget.getX() - bot.getX();
            double fleeDirZ = fleeTarget.getZ() - bot.getZ();
            double fleeDirLen = Math.sqrt(fleeDirX * fleeDirX + fleeDirZ * fleeDirZ);
            double awayFromBaseDot = 0;
            if (fleeDirLen > 0.01) {
                awayFromBaseDot = -(fleeDirX / fleeDirLen * baseDirX + fleeDirZ / fleeDirLen * baseDirZ);
            }
            
            double score = openNeighbors * 2.0 + awayFromBaseDot * 3.0 + random.nextDouble();
            if (score > bestScore) {
                bestScore = score;
                bestFleeTarget = fleeTarget;
            }
        }
        
        if (bestFleeTarget == null) {
            LOGGER.warn("ReturnBaseStuck: panic flee failed - couldn't find valid flee target");
            return false;
        }

        LOGGER.info("ReturnBaseStuck: {} entering PANIC FLEE mode! Fleeing to {} for ~{}ms",
            bot.getName().getString(), bestFleeTarget.toShortString(), PANIC_FLEE_DURATION_MS);

        // Run the flee as an escape attempt so it can safely block/sleep without stalling the server tick.
        // IMPORTANT: We temporarily disable FOLLOW mode while fleeing; otherwise FOLLOW will fight movement.
        UUID id = bot.getUuid();
        final BlockPos fleeTargetFinal = bestFleeTarget;
        runEscapeAsync(id, () -> runPanicFlee(bot, baseTarget, fleeTargetFinal));
        return true;
    }
    
    /**
     * Find a walkable Y level at the given XZ position.
     * Searches up and down from the starting Y to find solid ground with headroom.
     */
    private static BlockPos findWalkableY(ServerWorld world, BlockPos start) {
        // Search up to 8 blocks up and down
        for (int dy = 0; dy <= 8; dy++) {
            // Check going down first (more likely for panic flee)
            BlockPos down = start.down(dy);
            if (isPassable(world, down) && isPassable(world, down.up()) 
                    && !world.getBlockState(down.down()).isAir()) {
                return down;
            }
            // Then check going up
            if (dy > 0) {
                BlockPos up = start.up(dy);
                if (isPassable(world, up) && isPassable(world, up.up())
                        && !world.getBlockState(up.down()).isAir()) {
                    return up;
                }
            }
        }
        return null;
    }
    
    /**
     * Execute panic flee for a short time budget, then resume return-to-base.
     * This runs off-thread (via runEscapeAsync) and schedules any state mutations on the server thread.
     */
    private static void runPanicFlee(ServerPlayerEntity bot, Vec3d baseTarget, BlockPos fleeTarget) {
        if (bot == null || baseTarget == null || fleeTarget == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        UUID botId = bot.getUuid();

        // 1) Temporarily disable FOLLOW so it won't fight movement during the flee.
        CountDownLatch latch = new CountDownLatch(1);
        server.execute(() -> {
            try {
                BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
                if (state != null) {
                    // Preserve baseTarget so we can resume return-to-base.
                    state.mode = BotEventHandler.Mode.IDLE;
                    state.followTargetUuid = null;
                    state.followFixedGoal = null;
                    state.followNoTeleport = true;
                    state.followStopRange = 0.0D;
                    state.followStandoffRange = 0.0D;
                    state.comeBestGoalDistSq = Double.NaN;
                    state.comeTicksSinceBest = 0;
                    state.comeNextSkillTick = 0L;
                    state.comeAllowRecoverySkills = true;
                }
                FollowStateService.clearAll(botId);
                FollowDebugService.clear(botId);
                MovementService.clearRecentWalkAttempt(botId);
                BotActions.stop(bot);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // 2) Spend a short time budget fleeing toward a standable target.
        boolean fled = false;
        try {
            // Use the same nudge/assist loop used elsewhere (doors/corners/jumps).
            fled = MovementService.nudgeTowardUntilClose(bot, fleeTarget, 2.25D, PANIC_FLEE_DURATION_MS, 0.22, "panic-flee");
        } catch (Exception e) {
            LOGGER.warn("ReturnBaseStuck: panic flee threw exception: {}", e.getMessage());
        }

        LOGGER.info("ReturnBaseStuck: {} panic flee finished (fled={}, target={}), resuming return-to-base",
                bot.getName().getString(), fled, fleeTarget.toShortString());

        // 3) Resume return-to-base on the server thread.
        CountDownLatch latch2 = new CountDownLatch(1);
        server.execute(() -> {
            try {
                BotEventHandler.setReturnToBase(bot, baseTarget);
                // Fresh stuck counters for the resumed return-to-base.
                clear(botId);
            } finally {
                latch2.countDown();
            }
        });
        try {
            latch2.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Run an escape attempt asynchronously to avoid blocking the server tick.
     */
    private static void runEscapeAsync(UUID botId, Runnable escapeTask) {
        ESCAPE_IN_PROGRESS.put(botId, true);
        DebugToggleService.debug(LOGGER, "runEscapeAsync: scheduling escape task for botId={}", botId);
        java.util.concurrent.ForkJoinPool.commonPool().execute(() -> {
            DebugToggleService.debug(LOGGER, "runEscapeAsync: starting escape task for botId={}", botId);
            try {
                escapeTask.run();
            } catch (Exception e) {
                LOGGER.warn("Escape attempt failed: {}", e.getMessage());
            } finally {
                ESCAPE_IN_PROGRESS.remove(botId);
                DebugToggleService.debug(LOGGER, "runEscapeAsync: finished escape task for botId={}", botId);
            }
        });
    }
    
    /**
     * Try backing up and sidestepping to find a clear path.
     * Tries all 4 cardinal directions, with jumping, to escape.
     * If all directions are blocked, delegates to mining (pillar escape).
     */
    private static void tryBackupAndSidestep(ServerPlayerEntity bot, Vec3d target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            LOGGER.warn("Stuck escape: world not ServerWorld, aborting");
            return;
        }
        BlockPos botPos = bot.getBlockPos();
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        
        LOGGER.info("Stuck escape: starting backup/sidestep from {} toward target {}", 
                botPos.toShortString(), String.format("%.0f, %.0f, %.0f", target.x, target.y, target.z));
        
        // Try all 4 cardinal directions, prioritizing away from target
        Direction[] directions = prioritizeDirections(bot, target);
        
        // Log what each direction looks like
        StringBuilder dirStatus = new StringBuilder();
        int blockedCount = 0;
        for (Direction dir : directions) {
            BlockPos adjacent = botPos.offset(dir);
            boolean passable = isPassable(world, adjacent);
            boolean pathClear = isPathClear(world, botPos, adjacent);
            BlockState adjState = world.getBlockState(adjacent);
            dirStatus.append(dir.name()).append("=").append(passable && pathClear ? "OPEN" : "BLOCKED")
                    .append("(").append(adjState.getBlock().getName().getString()).append(") ");
            if (!passable || !pathClear) blockedCount++;
        }
        LOGGER.info("Stuck escape: directions: {} blockedCount={}", dirStatus.toString().trim(), blockedCount);
        
        // If fully enclosed, skip directly to mining
        if (blockedCount >= 4) {
            LOGGER.info("Stuck escape: all 4 directions blocked, will need to mine out");
            return; // Let pillar escape handle mining at the pillar threshold
        }
        
        for (Direction dir : directions) {
            // Try 1-4 blocks. This helps with "turn around and walk out" cases where the opening
            // isn't immediately adjacent.
            for (int dist = 1; dist <= 4; dist++) {
                BlockPos escapePos = botPos.offset(dir, dist);
                if (isPassable(world, escapePos) && isPathClear(world, botPos, escapePos)) {
                    LOGGER.info("Stuck escape: moving {} {} block(s) to {}", dir, dist, escapePos.toShortString());
                    
                    // Jump before and during movement to help with small obstacles
                    if (server != null) {
                        server.execute(() -> BotActions.jump(bot));
                    }
                    sleepQuiet(100);
                    
                    boolean success = MovementService.nudgeTowardUntilClose(bot, escapePos, 1.5D, 600L, 0.25, "stuck-escape-" + dir);
                    if (success) {
                        LOGGER.info("Stuck escape: successfully moved to {}", escapePos.toShortString());
                        return;
                    } else {
                        LOGGER.info("Stuck escape: {} direction failed, trying next", dir);
                    }
                }
            }
        }
        
        // If normal movement failed, try jump-sprint in each direction briefly
        LOGGER.info("Stuck escape: normal movement failed, trying jump-sprint escape");
        for (Direction dir : directions) {
            if (server != null) {
                final Direction escapeDir = dir;
                final BlockPos startPos = bot.getBlockPos();
                // Face the escape direction by looking at a block in that direction
                final BlockPos lookTarget = botPos.offset(escapeDir, 3);
                server.execute(() -> {
                    LookController.faceBlock(bot, lookTarget);
                    BotActions.jump(bot);
                    BotActions.moveForward(bot);
                });
                sleepQuiet(300);
                server.execute(() -> BotActions.stop(bot));
                
                // Check if we moved
                if (!bot.getBlockPos().equals(startPos)) {
                    LOGGER.info("Stuck escape: jump-sprint {} succeeded, now at {}", escapeDir, bot.getBlockPos().toShortString());
                    return;
                }
            }
        }
        
        LOGGER.info("Stuck escape: all backup/sidestep attempts failed");
    }
    
    /**
     * Order directions prioritizing away from target, then perpendicular, then toward.
     */
    private static Direction[] prioritizeDirections(ServerPlayerEntity bot, Vec3d target) {
        double dx = target.x - bot.getX();
        double dz = target.z - bot.getZ();
        
        // Determine primary direction toward target
        Direction towardTarget;
        if (Math.abs(dx) > Math.abs(dz)) {
            towardTarget = dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            towardTarget = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        
        // Return in order: opposite of target, perpendiculars, toward target
        Direction away = towardTarget.getOpposite();
        Direction left = towardTarget.rotateYCounterclockwise();
        Direction right = towardTarget.rotateYClockwise();
        
        return new Direction[] { away, left, right, towardTarget };
    }
    
    /**
     * Check if the path from start to end is clear (no solid blocks in the way).
     */
    private static boolean isPathClear(ServerWorld world, BlockPos start, BlockPos end) {
        // Simple check: ensure the intermediate blocks are passable
        int dx = Integer.signum(end.getX() - start.getX());
        int dz = Integer.signum(end.getZ() - start.getZ());
        
        BlockPos current = start;
        while (!current.equals(end)) {
            current = current.add(dx, 0, dz);
            BlockState at = world.getBlockState(current);
            BlockState above = world.getBlockState(current.up());
            if (!at.isAir() && !at.isReplaceable() && at.isSolidBlock(world, current)) {
                return false;
            }
            if (!above.isAir() && !above.isReplaceable() && above.isSolidBlock(world, current.up())) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Helper to check if a block is truly blocking (solid and not passable).
     * Matches the logic in isPathClear().
     */
    private static boolean isBlockBlocking(ServerWorld world, BlockPos pos, BlockState state) {
        // Air and replaceable blocks (grass, flowers, etc.) are passable
        if (state.isAir() || state.isReplaceable()) {
            return false;
        }
        return state.isSolidBlock(world, pos);
    }
    
    /**
     * Check if the path from origin to candidate is clear for a quick nudge.
     * Uses same passability logic as isPathClear() to avoid false rejections.
     * For adjacent tiles (1 block away), checks if the intervening blocks are passable.
     */
    private static boolean isPathClearForNudge(ServerWorld world, BlockPos origin, BlockPos candidate) {
        // For adjacent tiles, we need to check the blocks at the candidate position AND
        // any intermediate blocks for diagonal movement
        int dx = candidate.getX() - origin.getX();
        int dz = candidate.getZ() - origin.getZ();
        
        // Check the candidate position itself (lower body and head height)
        BlockState candAt = world.getBlockState(candidate);
        BlockState candAbove = world.getBlockState(candidate.up());
        if (isBlockBlocking(world, candidate, candAt) || isBlockBlocking(world, candidate.up(), candAbove)) {
            LOGGER.debug("[ReturnStuck] Dir ({},{}) blocked at candidate: {} at={} above={}", 
                dx, dz, candidate, candAt.getBlock().getName().getString(), candAbove.getBlock().getName().getString());
            return false;
        }
        
        // For diagonal movement, also check both cardinal intermediate positions
        // (bot can't cut corners through solid blocks)
        if (dx != 0 && dz != 0) {
            // Check X-adjacent position
            BlockPos xAdj = origin.add(dx, 0, 0);
            BlockState xAt = world.getBlockState(xAdj);
            BlockState xAbove = world.getBlockState(xAdj.up());
            boolean xBlocked = isBlockBlocking(world, xAdj, xAt) || isBlockBlocking(world, xAdj.up(), xAbove);
            
            // Check Z-adjacent position
            BlockPos zAdj = origin.add(0, 0, dz);
            BlockState zAt = world.getBlockState(zAdj);
            BlockState zAbove = world.getBlockState(zAdj.up());
            boolean zBlocked = isBlockBlocking(world, zAdj, zAt) || isBlockBlocking(world, zAdj.up(), zAbove);
            
            // For diagonal movement, need at least one clear cardinal path
            // (can't cut through a corner if BOTH cardinals are blocked)
            if (xBlocked && zBlocked) {
                LOGGER.debug("[ReturnStuck] Dir ({},{}) blocked: both corner cardinals blocked", dx, dz);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Try pillaring up 1-2 blocks to get over an obstacle, or mine through if needed.
     */
    private static void tryPillarEscape(ServerPlayerEntity bot) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        
        BlockPos botPos = bot.getBlockPos();
        
        // Check if we're enclosed - walls on multiple sides
        // Count a direction as blocked if EITHER the lower OR upper block is solid (not just both)
        int wallCount = 0;
        StringBuilder wallDirs = new StringBuilder();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos adjacent = botPos.offset(dir);
            BlockState adjState = world.getBlockState(adjacent);
            BlockState adjUpState = world.getBlockState(adjacent.up());
            boolean lowerBlocked = adjState.isSolidBlock(world, adjacent);
            boolean upperBlocked = adjUpState.isSolidBlock(world, adjacent.up());
            // A direction is blocked if we can't walk through (need both clear for 2-high passage)
            if (lowerBlocked || upperBlocked) {
                wallCount++;
                wallDirs.append(dir.name()).append("(").append(lowerBlocked ? "L" : "").append(upperBlocked ? "U" : "").append(") ");
            }
        }
        
        boolean enclosed = wallCount >= 2;
        LOGGER.info("Stuck pillar: wallCount={} enclosed={} blockedDirs=[{}]", wallCount, enclosed, wallDirs.toString().trim());
        
        // Check if there's a roof above us
        BlockPos roofPos = botPos.up(2);
        BlockState roofState = world.getBlockState(roofPos);
        boolean hasRoof = !roofState.isAir() && !roofState.isReplaceable();
        
        if (hasRoof && enclosed) {
            // We're in an enclosed structure with a roof - try to mine the roof
            LOGGER.info("Stuck pillar: enclosed with roof at {}, attempting to mine out", roofPos.toShortString());
            if (tryMineBlock(bot, roofPos, world)) {
                sleepQuiet(200);
                // Jump up into the newly opened space
                BotActions.jump(bot);
                sleepQuiet(200);
                BotActions.jump(bot);
                LOGGER.info("Stuck pillar: mined roof and jumped, now at {}", bot.getBlockPos().toShortString());
                return;
            }
        }
        
        // Try mining through walls - check all directions for mineable blocks
        // This works whether or not we detected "enclosed" - just try to clear a path
        LOGGER.info("Stuck pillar: checking for mineable walls in all directions");
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos wallPos = botPos.offset(dir);
            BlockPos wallUpPos = wallPos.up();
            BlockState wallState = world.getBlockState(wallPos);
            BlockState wallUpState = world.getBlockState(wallUpPos);
            
            boolean lowerBlocked = wallState.isSolidBlock(world, wallPos);
            boolean upperBlocked = wallUpState.isSolidBlock(world, wallUpPos);
            
            // If this direction is blocked, try to mine through
            if (lowerBlocked || upperBlocked) {
                LOGGER.info("Stuck pillar: direction {} blocked (lower={} upper={})", 
                        dir.name(), lowerBlocked, upperBlocked);
                
                boolean minedLower = false;
                boolean minedUpper = false;
                
                // Mine lower block if blocked and mineable
                if (lowerBlocked && canMineBlock(bot, world, wallPos)) {
                    LOGGER.info("Stuck pillar: mining lower wall at {} ({})", 
                            wallPos.toShortString(), wallState.getBlock().getName().getString());
                    minedLower = tryMineBlock(bot, wallPos, world);
                    if (minedLower) sleepQuiet(200);
                }
                
                // Mine upper block if blocked and mineable
                if (upperBlocked && canMineBlock(bot, world, wallUpPos)) {
                    LOGGER.info("Stuck pillar: mining upper wall at {} ({})", 
                            wallUpPos.toShortString(), wallUpState.getBlock().getName().getString());
                    minedUpper = tryMineBlock(bot, wallUpPos, world);
                    if (minedUpper) sleepQuiet(200);
                }
                
                // If we mined anything, try to move through
                if (minedLower || minedUpper) {
                    LOGGER.info("Stuck pillar: mined wall in {} direction, attempting to walk through", dir.name());
                    // Face the cleared direction and move forward
                    LookController.faceBlock(bot, wallPos);
                    sleepQuiet(100);
                    BotActions.moveForward(bot);
                    sleepQuiet(400);
                    BotActions.stop(bot);
                    LOGGER.info("Stuck pillar: after mining, now at {}", bot.getBlockPos().toShortString());
                    return;
                }
            }
        }
        
        // Traditional pillar approach: place blocks under and climb up
        // Count available pillar blocks
        int available = countPillarBlocks(bot);
        if (available >= 2) {
            int steps = Math.min(2, available);
            LOGGER.info("Stuck pillar: attempting to pillar up {} blocks", steps);
            
            boolean wasSneaking = bot.isSneaking();
            bot.setSneaking(true);
            
            for (int i = 0; i < steps; i++) {
                BotActions.jump(bot);
                sleepQuiet(120);
                BlockPos placeTarget = bot.getBlockPos().down();
                if (world.getBlockState(placeTarget).isAir() || world.getBlockState(placeTarget).isReplaceable()) {
                    if (tryPlacePillarBlock(bot, placeTarget)) {
                        LOGGER.debug("Stuck pillar: placed block at {}", placeTarget.toShortString());
                    } else {
                        LOGGER.warn("Stuck pillar: failed to place block at step {}", i);
                        break;
                    }
                }
                sleepQuiet(120);
            }
            
            bot.setSneaking(wasSneaking);
        } else {
            LOGGER.info("Stuck pillar: not enough blocks ({}) to pillar up", available);
        }
    }
    
    /**
     * Check if a block can be mined (not too hard, not protected).
     */
    private static boolean canMineBlock(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (bot == null || world == null || pos == null) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable()) {
            return false;
        }

        // Respect protected zones and avoid griefing player structures/storage.
        if (ProtectedZoneService.isProtected(pos, world, null)) {
            return false;
        }
        // Avoid mining near obvious human constructions (doors/planks/etc.).
        if (TreeDetector.isNearHumanBlocks(world, pos, 6)) {
            return false;
        }

        // Align with mining skills: ores/liquids/containers/structures should block mining.
        MiningHazardDetector.DetectionResult hazards = MiningHazardDetector.detect(bot, java.util.List.of(pos), java.util.List.of(), false);
        if (hazards.blockingHazard().isPresent()) {
            return false;
        }

        if (world.getBlockEntity(pos) != null) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL) || state.isOf(Blocks.ENDER_CHEST)) {
            return false;
        }
        if (state.isIn(BlockTags.BEDS) || state.isIn(BlockTags.SHULKER_BOXES)) {
            return false;
        }
        if (state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.WALLS) || state.isIn(BlockTags.FENCE_GATES)) {
            return false;
        }
        if (state.isIn(BlockTags.LEAVES)) {
            return false;
        }
        // Avoid ripping up common build materials during generic stuck recovery.
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOOL)) {
            return false;
        }

        float hardness = state.getHardness(world, pos);
        // Skip unbreakable blocks (bedrock, barriers, etc.) and very hard blocks
        return hardness >= 0 && hardness < 50.0f;
    }

    private static boolean isTorchBlock(BlockState state) {
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

    private static boolean isPassableForMining(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        if (!world.getFluidState(pos).isEmpty()) {
            return false;
        }
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isPassableForStanding(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }
    
    /**
     * Try to mine a block at the given position.
     * Uses interactionManager.tryBreakBlock for physical breaking.
     * Mining operations are executed on the server thread for thread safety.
     */
    private static boolean tryMineBlock(ServerPlayerEntity bot, BlockPos target, ServerWorld world) {
        MinecraftServer server = world.getServer();
        if (server == null) {
            LOGGER.warn("Stuck pillar: no server available for mining");
            return false;
        }
        
        // Face the block (can be done from any thread via LookController)
        LookController.faceBlock(bot, target);
        sleepQuiet(50);
        
        BlockState state = world.getBlockState(target);
        if (state.isAir()) {
            LOGGER.info("Stuck pillar: target {} is already air", target.toShortString());
            return true; // Already air
        }
        
        LOGGER.info("Stuck pillar: attempting to mine {} at {}", 
                state.getBlock().getName().getString(), target.toShortString());
        
        // Use ToolSelector to pick the best tool for this block
        ItemStack bestTool = ToolSelector.selectBestToolForBlock(bot, state);
        if (!bestTool.isEmpty()) {
            // Find and equip the tool (on server thread)
            int toolSlot = findToolSlot(bot, bestTool);
            if (toolSlot >= 0 && toolSlot < 9) {
                final int slot = toolSlot;
                server.execute(() -> BotActions.selectHotbarSlot(bot, slot));
            }
        }
        sleepQuiet(100);
        
        // Calculate mining time based on hardness and tool
        float hardness = state.getHardness(world, target);
        ItemStack tool = bot.getMainHandStack();
        float speed = tool.isEmpty() ? 1.0f : Math.max(1.0f, tool.getMiningSpeedMultiplier(state));
        int miningTimeMs = (int) ((hardness / speed) * 1500) + 100;
        miningTimeMs = Math.min(miningTimeMs, 3000); // Cap at 3 seconds
        
        LOGGER.info("Stuck pillar: mining {} (hardness={}, speed={}, time={}ms)", 
                state.getBlock().getName().getString(), hardness, speed, miningTimeMs);
        
        // Use bot's interaction manager to break the block - MUST run on server thread
        AtomicBoolean startedBreaking = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        
        server.execute(() -> {
            try {
                boolean started = bot.interactionManager.tryBreakBlock(target);
                startedBreaking.set(started);
                if (started) {
                    bot.swingHand(Hand.MAIN_HAND, true);
                }
            } catch (Exception e) {
                LOGGER.warn("Stuck pillar: exception starting block break: {}", e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        
        // Wait for the server thread to process
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        
        if (!startedBreaking.get()) {
            LOGGER.info("Stuck pillar: could not start breaking block at {} (tryBreakBlock returned false)", target.toShortString());
            return false;
        }
        
        // Wait for mining to complete
        sleepQuiet(miningTimeMs);
        
        // Check if the block is now gone
        boolean success = world.getBlockState(target).isAir();
        if (success) {
            LOGGER.info("Stuck pillar: successfully mined block at {}", target.toShortString());
        } else {
            // Try one more time with a longer duration
            LOGGER.info("Stuck pillar: first attempt did not clear block, trying again");
            CountDownLatch latch2 = new CountDownLatch(1);
            server.execute(() -> {
                try {
                    bot.interactionManager.tryBreakBlock(target);
                    bot.swingHand(Hand.MAIN_HAND, true);
                } finally {
                    latch2.countDown();
                }
            });
            try {
                latch2.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sleepQuiet(500);
            success = world.getBlockState(target).isAir();
            LOGGER.info("Stuck pillar: second attempt result={} at {}", success, target.toShortString());
        }
        return success;
    }
    
    /**
     * Find the hotbar slot containing a matching tool.
     */
    private static int findToolSlot(ServerPlayerEntity bot, ItemStack targetTool) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (ItemStack.areItemsEqual(stack, targetTool)) {
                return i;
            }
        }
        return -1;
    }
    
    private static boolean isPassable(ServerWorld world, BlockPos pos) {
        BlockState at = world.getBlockState(pos);
        BlockState above = world.getBlockState(pos.up());
        BlockState below = world.getBlockState(pos.down());
        return (at.isAir() || at.isReplaceable()) 
                && (above.isAir() || above.isReplaceable())
                && below.isSolidBlock(world, pos.down());
    }
    
    private static int countPillarBlocks(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && PILLAR_BLOCKS.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }
    
    private static boolean tryPlacePillarBlock(ServerPlayerEntity bot, BlockPos target) {
        // Find a pillar block in hotbar
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && PILLAR_BLOCKS.contains(stack.getItem())) {
                slot = i;
                break;
            }
        }
        // Move from main inventory to hotbar if needed
        if (slot == -1) {
            for (int i = 9; i < bot.getInventory().size(); i++) {
                ItemStack stack = bot.getInventory().getStack(i);
                if (!stack.isEmpty() && PILLAR_BLOCKS.contains(stack.getItem())) {
                    // Swap with first hotbar slot
                    ItemStack hotbarStack = bot.getInventory().getStack(0);
                    bot.getInventory().setStack(0, stack);
                    bot.getInventory().setStack(i, hotbarStack);
                    bot.getInventory().markDirty();
                    slot = 0;
                    break;
                }
            }
        }
        if (slot == -1) {
            return false;
        }
        bot.getInventory().setSelectedSlot(slot);
        LookController.faceBlock(bot, target);
        return BotActions.placeBlockAt(bot, target, Direction.UP, new java.util.ArrayList<>(PILLAR_BLOCKS));
    }
    
    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
