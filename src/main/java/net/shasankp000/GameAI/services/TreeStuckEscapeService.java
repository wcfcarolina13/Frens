package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects when a bot is stranded on top of a tree canopy (e.g. after elytra landing
 * on leaves) and helps it escape via safe drop, leaf breaking, elytra descent,
 * or — as a last resort — jumping off.
 *
 * <p>Called from the follow tick in {@link BotEventHandler} when the bot is on the ground
 * and follow-mode is active. The entry-point {@link #tryEscape} is lightweight: it
 * returns {@code false} immediately unless the block below the bot is a leaf block.</p>
 */
public final class TreeStuckEscapeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TreeStuckEscapeService.class);

    // ── State tracking ───────────────────────────────────────────────────────
    /** Tick when the bot first entered a "stuck on tree" state. */
    private static final ConcurrentHashMap<UUID, Long> TREE_STUCK_SINCE_TICK = new ConcurrentHashMap<>();
    /** Throttle escape attempts to every N ticks. */
    private static final ConcurrentHashMap<UUID, Long> LAST_TREE_ESCAPE_ATTEMPT_TICK = new ConcurrentHashMap<>();
    /** Cooldown for dialogue lines. */
    private static final ConcurrentHashMap<UUID, Long> LAST_TREE_STUCK_DIALOGUE_MS = new ConcurrentHashMap<>();
    /** In-flight leaf-mining future per bot (prevent stacking). */
    private static final ConcurrentHashMap<UUID, CompletableFuture<String>> LEAF_MINE_INFLIGHT = new ConcurrentHashMap<>();
    /** Active escape target — steer toward this every tick. */
    private static final ConcurrentHashMap<UUID, BlockPos> ACTIVE_ESCAPE_TARGET = new ConcurrentHashMap<>();
    /** Tick when active escape target was set — used to detect stale targets. */
    private static final ConcurrentHashMap<UUID, Long> ESCAPE_TARGET_SET_TICK = new ConcurrentHashMap<>();
    /** Bots that already tried safe-drop steering and failed — skip to leaf breaking. */
    private static final Set<UUID> SAFE_DROP_EXHAUSTED = ConcurrentHashMap.newKeySet();

    // ── Tuning constants ─────────────────────────────────────────────────────
    /** Ticks the bot must be near the same spot on leaves before we consider it stuck. */
    private static final long STUCK_THRESHOLD_TICKS = 30L;
    /** Minimum ticks between escape strategy re-evaluation. */
    private static final long ESCAPE_ATTEMPT_INTERVAL_TICKS = 40L;
    /** Maximum ticks to steer toward a safe drop before escalating. */
    private static final long ESCAPE_TARGET_STALE_TICKS = 120L;
    /** Dialogue cooldown (ms). */
    private static final long DIALOGUE_COOLDOWN_MS = 12_000L;
    /** Max safe fall height (blocks) — vanilla is ~3.5 for no damage; 4 is survivable. */
    private static final int SAFE_DROP_HEIGHT = 4;
    /** Max survivable fall height (takes damage but lives). */
    private static final int SURVIVABLE_DROP_HEIGHT = 8;
    /** Horizontal scan radius for safe-drop search. */
    private static final int SAFE_DROP_SCAN_RADIUS = 3;
    /** Horizontal scan radius for trunk detection. */
    private static final int TRUNK_SCAN_RADIUS = 2;

    // ── Dialogue lines ───────────────────────────────────────────────────────
    private static final String[] STUCK_LINES = {
            "I'm stuck in this tree, hold on.",
            "Wait, I'm stuck up here."
    };
    private static final String LEAF_BREAK_LINE = "Let me break through these leaves.";
    private static final String JUMP_OFF_LINE = "This is going to hurt...";

    private TreeStuckEscapeService() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Try to escape if the bot is stuck on a tree canopy.
     *
     * @return {@code true} if an escape action was initiated (caller should skip normal follow),
     *         {@code false} if the bot is not stuck or no action was needed this tick.
     */
    public static boolean tryEscape(ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null || server == null || bot.isRemoved() || !bot.isAlive()) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }

        UUID botId = bot.getUuid();
        long now = server.getTicks();

        // If we have an active escape target, keep steering even if briefly airborne
        // (e.g. stepping off leaf edge toward safe drop).
        BlockPos activeTarget = ACTIVE_ESCAPE_TARGET.get(botId);
        if (activeTarget != null) {
            // Only success condition: bot is on solid non-leaf ground (actually descended).
            if (bot.isOnGround()) {
                BlockState belowState = world.getBlockState(bot.getBlockPos().down());
                if (!belowState.isIn(BlockTags.LEAVES)) {
                    LOGGER.info("TreeStuck: {} escaped tree canopy", bot.getName().getString());
                    clearState(botId);
                    return false;
                }
            }

            // If airborne (falling off edge), let gravity work — don't steer.
            if (!bot.isOnGround()) {
                return true;
            }

            // Still on leaves — check for stale target.
            long targetAge = now - ESCAPE_TARGET_SET_TICK.getOrDefault(botId, now);
            if (targetAge > ESCAPE_TARGET_STALE_TICKS) {
                // Couldn't reach safe drop — mark exhausted so we skip to leaf breaking.
                LOGGER.info("TreeStuck: {} safe-drop unreachable, switching to leaf breaking", bot.getName().getString());
                SAFE_DROP_EXHAUSTED.add(botId);
                ACTIVE_ESCAPE_TARGET.remove(botId);
                ESCAPE_TARGET_SET_TICK.remove(botId);
                LAST_TREE_ESCAPE_ATTEMPT_TICK.put(botId, 0L); // Force immediate re-eval
                // Fall through to strategy evaluation below.
            } else {
                // Keep steering toward the active target every tick.
                double distSq = bot.squaredDistanceTo(Vec3d.ofCenter(activeTarget));
                LookController.faceBlock(bot, activeTarget);
                BotActions.sprint(bot, false);
                // Push harder when close to the edge to force stepping into the air column.
                double impulse = distSq <= 2.25 ? 0.35D : 0.28D;
                BotActions.applyMovementInput(bot, Vec3d.ofCenter(activeTarget), impulse);
                // Jump more frequently near the edge to step over leaf boundaries.
                if (distSq <= 2.25) {
                    if (targetAge % 5 == 0) {
                        bot.jump();
                    }
                } else if (targetAge % 15 == 0) {
                    bot.jump();
                }
                return true;
            }
        }

        // If not on ground and no active target, just wait to land.
        if (!bot.isOnGround()) {
            return TREE_STUCK_SINCE_TICK.containsKey(botId); // Stay in escape mode if we were stuck.
        }

        // Fast path: block below feet must be a leaf block.
        BlockPos belowFeet = bot.getBlockPos().down();
        BlockState belowState = world.getBlockState(belowFeet);
        if (!belowState.isIn(BlockTags.LEAVES)) {
            clearState(botId);
            return false;
        }

        // Track how long we've been on leaves.
        TREE_STUCK_SINCE_TICK.putIfAbsent(botId, now);
        long stuckDuration = now - TREE_STUCK_SINCE_TICK.get(botId);
        if (stuckDuration < STUCK_THRESHOLD_TICKS) {
            return false; // Not stuck yet — just traversing leaves briefly.
        }

        // Check if a leaf mine is already in progress.
        CompletableFuture<String> inflight = LEAF_MINE_INFLIGHT.get(botId);
        if (inflight != null && !inflight.isDone()) {
            return true; // Mining a leaf — skip normal follow, wait for it to finish.
        }

        // ── Throttle strategy re-evaluation ──
        long lastAttempt = LAST_TREE_ESCAPE_ATTEMPT_TICK.getOrDefault(botId, 0L);
        if (now - lastAttempt < ESCAPE_ATTEMPT_INTERVAL_TICKS) {
            return true;
        }
        LAST_TREE_ESCAPE_ATTEMPT_TICK.put(botId, now);

        // Show initial stuck dialogue (once per cooldown), but suppress it when the
        // commander is nearby and also on leaves (same tree — bot isn't really "stuck").
        boolean commanderOnSameTree = false;
        ServerPlayerEntity commander = resolveCommander(bot, server);
        if (commander != null && commander.isOnGround() && bot.squaredDistanceTo(commander) < 64.0) {
            BlockState cmdBelow = world.getBlockState(commander.getBlockPos().down());
            commanderOnSameTree = cmdBelow.isIn(BlockTags.LEAVES);
        }
        if (!commanderOnSameTree) {
            showDialogue(bot, STUCK_LINES[new Random().nextInt(STUCK_LINES.length)]);
        }

        // ── Strategy 1: Find a safe drop edge and steer toward it ──
        // Skip if we already tried safe-drop and it was unreachable.
        if (!SAFE_DROP_EXHAUSTED.contains(botId)) {
            BlockPos safeDrop = findSafeDropEdge(world, bot.getBlockPos());
            if (safeDrop != null) {
                LOGGER.info("TreeStuck: {} targeting safe drop at {}", bot.getName().getString(), safeDrop.toShortString());
                ACTIVE_ESCAPE_TARGET.put(botId, safeDrop);
                ESCAPE_TARGET_SET_TICK.put(botId, now);
                LookController.faceBlock(bot, safeDrop);
                BotActions.sprint(bot, false);
                BotActions.applyMovementInput(bot, Vec3d.ofCenter(safeDrop), 0.28D);
                return true;
            }
        }

        // ── Strategy 2: Elytra descent (fastest if available) ──
        if (hasElytra(bot) && !ElytraFlightService.isInFlight(botId)) {
            if (ElytraFlightService.tryAutonomousDescent(bot, server)) {
                LOGGER.info("TreeStuck: {} using elytra descent", bot.getName().getString());
                return true;
            }
        }

        // ── Strategy 3: Break leaves below to fall through ──
        // Simplest and most reliable: just break the leaf under the bot's feet.
        BlockPos leafBelow = bot.getBlockPos().down();
        if (world.getBlockState(leafBelow).isIn(BlockTags.LEAVES)) {
            showDialogue(bot, LEAF_BREAK_LINE);
            LOGGER.info("TreeStuck: {} breaking leaf below at {}", bot.getName().getString(), leafBelow.toShortString());
            startLeafMine(bot, leafBelow);
            return true;
        }

        // ── Strategy 4: Break leaves toward trunk ──
        BlockPos trunk = findNearbyTrunk(world, bot.getBlockPos());
        if (trunk != null) {
            BlockPos leafToBreak = findLeafTowardTrunk(world, bot.getBlockPos(), trunk);
            if (leafToBreak != null) {
                showDialogue(bot, LEAF_BREAK_LINE);
                LOGGER.info("TreeStuck: {} breaking leaf at {} toward trunk at {}",
                        bot.getName().getString(), leafToBreak.toShortString(), trunk.toShortString());
                startLeafMine(bot, leafToBreak);
                return true;
            }
        }

        // ── Strategy 5: Jump off and take the damage ──
        int dropHeight = measureDropBelow(world, bot.getBlockPos());
        if (dropHeight > 0 && dropHeight <= SURVIVABLE_DROP_HEIGHT) {
            showDialogue(bot, JUMP_OFF_LINE);
            LOGGER.info("TreeStuck: {} jumping off tree (drop={})", bot.getName().getString(), dropHeight);
            Vec3d jumpDir = getFollowTargetDirection(bot, server);
            if (jumpDir != null) {
                Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
                LookController.faceBlock(bot, BlockPos.ofFloored(botPos.add(jumpDir)));
                BotActions.applyMovementInput(bot, botPos.add(jumpDir.multiply(2.0)), 0.30D);
            }
            bot.jump();
            clearState(botId);
            return true;
        }

        // No viable escape this tick — remain in stuck-handling state.
        return true;
    }

    /**
     * Clear all per-bot state. Called when the bot is no longer on leaves.
     */
    public static void clearState(UUID botId) {
        if (botId == null) return;
        TREE_STUCK_SINCE_TICK.remove(botId);
        LAST_TREE_ESCAPE_ATTEMPT_TICK.remove(botId);
        ACTIVE_ESCAPE_TARGET.remove(botId);
        ESCAPE_TARGET_SET_TICK.remove(botId);
        SAFE_DROP_EXHAUSTED.remove(botId);
        // Don't clear dialogue cooldown — let it expire naturally.
        LEAF_MINE_INFLIGHT.remove(botId);
    }

    // ── Strategy helpers ─────────────────────────────────────────────────────

    /**
     * Scan a radius around the bot for an edge where the bot can safely walk off
     * and land on solid non-leaf ground within SAFE_DROP_HEIGHT blocks below.
     */
    private static BlockPos findSafeDropEdge(ServerWorld world, BlockPos botPos) {
        BlockPos best = null;
        int bestDrop = Integer.MAX_VALUE;

        for (int dx = -SAFE_DROP_SCAN_RADIUS; dx <= SAFE_DROP_SCAN_RADIUS; dx++) {
            for (int dz = -SAFE_DROP_SCAN_RADIUS; dz <= SAFE_DROP_SCAN_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos candidate = botPos.add(dx, 0, dz);
                // The candidate column should have air (or passable) at foot and head level.
                BlockState footState = world.getBlockState(candidate);
                BlockState headState = world.getBlockState(candidate.up());
                if (!isPassable(footState) || !isPassable(headState)) continue;

                // Probe downward for solid non-leaf ground.
                int drop = 0;
                BlockPos probe = candidate.down();
                while (drop < SAFE_DROP_HEIGHT + 1) {
                    BlockState probeState = world.getBlockState(probe);
                    if (isSolidNonLeafGround(probeState)) {
                        // Found safe landing. Drop is the number of air blocks.
                        if (drop <= SAFE_DROP_HEIGHT && drop < bestDrop) {
                            best = candidate;
                            bestDrop = drop;
                        }
                        break;
                    }
                    if (!isPassable(probeState) && !probeState.isIn(BlockTags.LEAVES)) {
                        break; // Hit something non-passable that isn't leaves — unusable.
                    }
                    drop++;
                    probe = probe.down();
                }
            }
        }
        return best;
    }

    /**
     * Find a log (trunk) block within TRUNK_SCAN_RADIUS at the same Y or ±1.
     */
    private static BlockPos findNearbyTrunk(ServerWorld world, BlockPos botPos) {
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int dx = -TRUNK_SCAN_RADIUS; dx <= TRUNK_SCAN_RADIUS; dx++) {
            for (int dz = -TRUNK_SCAN_RADIUS; dz <= TRUNK_SCAN_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = botPos.add(dx, dy, dz);
                    if (world.getBlockState(candidate).isIn(BlockTags.LOGS)) {
                        double dist = botPos.getSquaredDistance(candidate);
                        if (dist < closestDist) {
                            closest = candidate;
                            closestDist = dist;
                        }
                    }
                }
            }
        }
        return closest;
    }

    /**
     * Find a leaf block between the bot's position and a trunk block that we can break
     * to make a path toward the trunk.
     */
    private static BlockPos findLeafTowardTrunk(ServerWorld world, BlockPos botPos, BlockPos trunk) {
        // Simple: step toward the trunk and find the first leaf block in the way.
        int dx = Integer.signum(trunk.getX() - botPos.getX());
        int dz = Integer.signum(trunk.getZ() - botPos.getZ());

        // Check at foot level and one below.
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos check = botPos.add(dx, dy, dz);
            if (world.getBlockState(check).isIn(BlockTags.LEAVES)) {
                return check;
            }
        }
        // Also check directly below (for descending along trunk).
        BlockPos below = botPos.down();
        if (world.getBlockState(below).isIn(BlockTags.LEAVES)) {
            return below;
        }
        return null;
    }

    /**
     * Measure the drop distance below the bot's feet through air/leaves to solid ground.
     * Returns 0 if no ground found within scan range.
     */
    private static int measureDropBelow(ServerWorld world, BlockPos botPos) {
        int drop = 0;
        BlockPos probe = botPos.down();
        while (drop < SURVIVABLE_DROP_HEIGHT + 2) {
            BlockState state = world.getBlockState(probe);
            if (isSolidNonLeafGround(state)) {
                return drop;
            }
            if (!isPassable(state) && !state.isIn(BlockTags.LEAVES)) {
                return 0; // Non-passable non-leaf — can't fall through.
            }
            drop++;
            probe = probe.down();
        }
        return 0; // Too far or no ground.
    }

    // ── Utility helpers ──────────────────────────────────────────────────────

    private static boolean isPassable(BlockState state) {
        return state.isAir() || state.isReplaceable()
                || !state.isSolidBlock(null, null); // non-solid blocks (flowers, etc.) are passable
    }

    private static boolean isSolidNonLeafGround(BlockState state) {
        if (state.isAir() || state.isIn(BlockTags.LEAVES)) {
            return false;
        }
        // A block with a full collision shape is solid ground.
        return state.isSolidBlock(null, null);
    }

    private static boolean hasElytra(ServerPlayerEntity bot) {
        // Check chest slot.
        if (bot.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            return true;
        }
        // Check inventory.
        for (int i = 0; i < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; i++) {
            if (bot.getInventory().getStack(i).isOf(Items.ELYTRA)) {
                return true;
            }
        }
        return false;
    }

    private static void startLeafMine(ServerPlayerEntity bot, BlockPos leafPos) {
        UUID botId = bot.getUuid();
        CompletableFuture<String> future = MiningTool.mineBlock(bot, leafPos);
        LEAF_MINE_INFLIGHT.put(botId, future);
        future.whenComplete((result, error) -> {
            LEAF_MINE_INFLIGHT.remove(botId, future);
        });
    }

    private static ServerPlayerEntity resolveCommander(ServerPlayerEntity bot, MinecraftServer server) {
        UUID targetUuid = BotEventHandler.getFollowTargetUuid(bot);
        if (targetUuid == null) return null;
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
        if (target == null || target.isRemoved()) return null;
        return target;
    }

    private static Vec3d getFollowTargetDirection(ServerPlayerEntity bot, MinecraftServer server) {
        ServerPlayerEntity target = resolveCommander(bot, server);
        if (target == null) return null;
        Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ());
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d dir = targetPos.subtract(botPos);
        double len = dir.horizontalLength();
        if (len < 0.01) return null;
        return new Vec3d(dir.x / len, 0, dir.z / len);
    }

    private static void showDialogue(ServerPlayerEntity bot, String line) {
        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_TREE_STUCK_DIALOGUE_MS.getOrDefault(botId, 0L);
        if (now - last < DIALOGUE_COOLDOWN_MS) {
            return;
        }
        LAST_TREE_STUCK_DIALOGUE_MS.put(botId, now);
        CompanionOverheadDialogueService.showOverheadLine(bot, line, 3_000, 48.0, "tree-stuck", null);
    }
}
