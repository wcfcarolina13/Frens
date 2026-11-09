package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.PlayerUtils.MiningTool;
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
    /** The hole the bot is edging onto and when it started (see {@link #tryEdgeIntoHole}). */
    private static final ConcurrentHashMap<UUID, HoleNudge> HOLE_NUDGE = new ConcurrentHashMap<>();

    // ── Tuning constants ─────────────────────────────────────────────────────
    /** Ticks the bot must be near the same spot on leaves before we consider it stuck. */
    private static final long STUCK_THRESHOLD_TICKS = 30L;
    /** Minimum ticks between escape strategy re-evaluation. */
    private static final long ESCAPE_ATTEMPT_INTERVAL_TICKS = 40L;
    /**
     * Stuck this long (10 s), the bot breaks the leaf under it even over a fall that costs health, as
     * before the safe-fall check: a few hearts beat staying stranded on the tree.
     */
    private static final long LAST_RESORT_TICKS = 200L;
    /** Bots whose last evaluation found no safe way down yet: follow runs between evaluations. */
    private static final Set<UUID> WAITING_FOR_SAFE_WAY = ConcurrentHashMap.newKeySet();
    /** The leaf broken as a last resort: the nudge may edge the bot onto that hole over a deeper drop. */
    private static final ConcurrentHashMap<UUID, BlockPos> LAST_RESORT_HOLE = new ConcurrentHashMap<>();
    /** Feet Y when the bot first stood on leaves this time: the canopy level. */
    private static final ConcurrentHashMap<UUID, Integer> CANOPY_FEET_Y = new ConcurrentHashMap<>();
    /** Grounded ticks off leaves at canopy level (on a log, on a lip) since it last stood on leaves. */
    private static final ConcurrentHashMap<UUID, Integer> OFF_LEAF_TICKS = new ConcurrentHashMap<>();
    /**
     * Grounded ticks off leaves at canopy level that keep the stuck clock: a tick on a log or on a lip
     * while follow runs used to restart it, so the 10 s last resort was never reached.
     */
    private static final int OFF_LEAF_GRACE_TICKS = 20;
    /** Maximum ticks to steer toward a safe drop before escalating. */
    private static final long ESCAPE_TARGET_STALE_TICKS = 120L;
    /** Dialogue cooldown (ms). */
    private static final long DIALOGUE_COOLDOWN_MS = 12_000L;
    /**
     * Max safe fall height (blocks). Vanilla fall damage is fall distance minus 3, so 3 is the
     * most that costs nothing; 4 cost a heart once bots took fall damage (sandbox 2026-11-09).
     */
    private static final int SAFE_DROP_HEIGHT = TreeStuckDropPolicy.SAFE_DROP_HEIGHT;
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
                    dropCanopyWaypoints(bot);
                    clearState(botId);
                    return false;
                }
            }

            // If airborne (falling off edge), let gravity work — don't steer. Once over the chosen
            // column, drop the sideways momentum so the bot comes down that column: the edge pushes
            // and hops carried it on past it, over the deeper drop beyond (sandbox 2026-11-09).
            if (!bot.isOnGround()) {
                Vec3d v = bot.getVelocity();
                if (TreeStuckDropPolicy.holdOverTarget(bot.getX(), bot.getZ(),
                        activeTarget.getX(), activeTarget.getZ(), v.x, v.z)) {
                    bot.setVelocity(0.0D, v.y, 0.0D);
                    bot.velocityDirty = true;
                }
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
                // Hop only over a block in the way. The old timed hops near the edge (every 5
                // ticks) added up to 1.25 blocks to the fall: a hop into a 3-deep column fell
                // about 4 and cost health (review of the canopy fix, 2026-11-09).
                BotActions.autoJumpIfNeeded(bot);
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
            if (tryEdgeIntoHole(bot, world, server, belowFeet, now)) {
                return true;
            }
            Integer canopyY = CANOPY_FEET_Y.get(botId);
            Long stuckSince = TREE_STUCK_SINCE_TICK.get(botId);
            boolean reallyStuck = stuckSince != null
                    && (now - stuckSince >= STUCK_THRESHOLD_TICKS || WAITING_FOR_SAFE_WAY.contains(botId));
            if (reallyStuck && canopyY != null && bot.getBlockY() >= canopyY
                    && OFF_LEAF_TICKS.merge(botId, 1, Integer::sum) <= OFF_LEAF_GRACE_TICKS) {
                return false; // still up at the canopy level: keep the stuck clock
            }
            BlockPos lastResortHole = LAST_RESORT_HOLE.get(botId);
            if (lastResortHole != null && bot.getBlockY() <= lastResortHole.getY()) {
                LAST_RESORT_HOLE.remove(botId);
            }
            HoleNudge spent = HOLE_NUDGE.get(botId);
            if (LAST_TREE_ESCAPE_ATTEMPT_TICK.containsKey(botId)
                    || (spent != null && !spent.column().equals(bot.getBlockPos()))) {
                // Only after an escape actually ran: walking off a leaf patch is not leaving a canopy.
                dropCanopyWaypoints(bot);
            }
            clearState(botId);
            if (spent != null && spent.column().equals(bot.getBlockPos())) {
                HOLE_NUDGE.put(botId, spent); // keep this hole's spent budget while the bot stays on it
            }
            return false;
        }
        HOLE_NUDGE.remove(botId);
        OFF_LEAF_TICKS.remove(botId);

        // Track how long we've been on leaves.
        if (TREE_STUCK_SINCE_TICK.putIfAbsent(botId, now) == null) {
            CANOPY_FEET_Y.put(botId, bot.getBlockY());
        }
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
            return !WAITING_FOR_SAFE_WAY.contains(botId);
        }
        WAITING_FOR_SAFE_WAY.remove(botId);
        LAST_TREE_ESCAPE_ATTEMPT_TICK.put(botId, now);

        // Show initial stuck dialogue (once per cooldown), but suppress it when the
        // commander is nearby and also on leaves (same tree — bot isn't really "stuck").
        boolean commanderOnSameTree = false;
        ServerPlayerEntity commander = resolveCommander(bot, server);
        if (commander != null && commander.isOnGround() && bot.squaredDistanceTo(commander) < 64.0) {
            BlockState cmdBelow = world.getBlockState(commander.getBlockPos().down());
            // Not a commander on a lower leaf layer, another tree's lower canopy or a bush below.
            commanderOnSameTree = cmdBelow.isIn(BlockTags.LEAVES) && commander.getBlockY() >= bot.getBlockY() - 1;
        }
        if (commanderOnSameTree) {
            // Up here with the commander, the bot is not stranded: digging under it only drops it a
            // block and it hops back (sandbox 2026-11-09, commander one block away on the canopy).
            TREE_STUCK_SINCE_TICK.put(botId, now);
            LAST_TREE_ESCAPE_ATTEMPT_TICK.remove(botId);
            return false;
        }
        boolean lastResort = stuckDuration >= LAST_RESORT_TICKS;
        showDialogue(bot, STUCK_LINES[new Random().nextInt(STUCK_LINES.length)]);

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
        int fallThroughLeaf = fallThroughLeaf(world, leafBelow, SAFE_DROP_HEIGHT);
        boolean belowIsSafe = TreeStuckDropPolicy.isSafeDrop(fallThroughLeaf);
        if (!belowIsSafe && lastResort) {
            // A fall that costs a few hearts, never lava, fire or a deadly drop.
            fallThroughLeaf = fallThroughLeaf(world, leafBelow, SURVIVABLE_DROP_HEIGHT);
        }
        boolean lastResortBreak = !belowIsSafe && lastResort && fallThroughLeaf <= SURVIVABLE_DROP_HEIGHT;
        if (world.getBlockState(leafBelow).isIn(BlockTags.LEAVES) && (belowIsSafe || lastResortBreak)) {
            if (lastResortBreak) {
                LAST_RESORT_HOLE.put(botId, leafBelow);
            }
            showDialogue(bot, LEAF_BREAK_LINE);
            LOGGER.info("TreeStuck: {} breaking leaf below at {} (fall {})", bot.getName().getString(),
                    leafBelow.toShortString(), fallThroughLeaf);
            startLeafMine(bot, leafBelow);
            return true;
        }

        // ── Strategy 4: Break leaves toward trunk ──
        BlockPos trunk = findNearbyTrunk(world, bot.getBlockPos());
        if (trunk != null) {
            BlockPos leafToBreak = findLeafTowardTrunk(world, bot.getBlockPos(), trunk, belowIsSafe);
            if (leafToBreak != null) {
                showDialogue(bot, LEAF_BREAK_LINE);
                LOGGER.info("TreeStuck: {} breaking leaf at {} toward trunk at {}",
                        bot.getName().getString(), leafToBreak.toShortString(), trunk.toShortString());
                startLeafMine(bot, leafToBreak);
                return true;
            }
        }

        // No safe way down yet: let follow run (its planner may find a way along the trunk) until the
        // next evaluation, and until the last resort breaks the leaf below anyway.
        if (!lastResort) {
            WAITING_FOR_SAFE_WAY.add(botId);
            return false;
        }
        return true;
    }

    /**
     * The bot stands on the lip of a leaf with the cell under it open, usually the hole it broke to
     * get down: edge it onto that hole so it drops through (see
     * {@link TreeStuckDropPolicy#shouldEdgeIntoHole}). Runs on the server thread, from follow.
     */
    private static boolean tryEdgeIntoHole(ServerPlayerEntity bot, ServerWorld world, MinecraftServer server,
                                           BlockPos belowFeet, long now) {
        UUID botId = bot.getUuid();
        BlockPos column = bot.getBlockPos();
        if (SneakLockService.isLocked(botId)) {
            // A sneaking player is held at ledges (PlayerEntity.clipAtLedge) and BotActions.stop keeps a
            // locked sneak on, so the move would do nothing for 40 ticks; leave it to the lock's owner.
            return false;
        }
        boolean belowOpen = world.getBlockState(belowFeet).isAir();
        BlockPos support = bot.supportingBlockPos.orElse(null);
        boolean supportIsLeaf = support != null && !support.equals(belowFeet)
                && world.getBlockState(support).isIn(BlockTags.LEAVES);
        // A hole broken as the last resort may be deeper than a safe drop: that is why it was broken.
        int maxDrop = belowFeet.equals(LAST_RESORT_HOLE.get(botId)) ? SURVIVABLE_DROP_HEIGHT : SAFE_DROP_HEIGHT;
        int holeDrop = 0;
        BlockPos probe = belowFeet;
        while (holeDrop <= maxDrop && world.getBlockState(probe).isAir()) {
            holeDrop++;
            probe = probe.down();
        }
        if (!isSafeLanding(world, probe)) {
            // Only air on the way down and a landing that does no harm: lava, fire, magma, cactus or
            // a campfire under the hole is no way down (review of the canopy hole fix, 2026-11-09).
            holeDrop = Integer.MAX_VALUE;
        }
        ServerPlayerEntity commander = resolveCommander(bot, server);
        Integer commanderY = commander == null ? null : commander.getBlockY();
        HoleNudge nudge = HOLE_NUDGE.get(botId);
        if (nudge == null || !nudge.column().equals(column)) {
            nudge = new HoleNudge(column, now);
        }
        if (!TreeStuckDropPolicy.shouldEdgeIntoHole(bot.isOnGround(), belowOpen, supportIsLeaf, holeDrop,
                column.getY(), commanderY, now - nudge.since(), maxDrop)) {
            return false;
        }
        if (nudge.since() == now) {
            LOGGER.info("TreeStuck: {} on the lip of the hole at {} (drop {}), edging onto it",
                    bot.getName().getString(), belowFeet.toShortString(), holeDrop);
        }
        HOLE_NUDGE.put(botId, nudge);
        BotActions.stop(bot);
        Vec3d v = bot.getVelocity();
        bot.setVelocity(TreeStuckDropPolicy.edgeSpeed(bot.getX(), column.getX() + 0.5D), v.y,
                TreeStuckDropPolicy.edgeSpeed(bot.getZ(), column.getZ() + 0.5D));
        bot.velocityDirty = true;
        return true;
    }

    /**
     * How far the bot falls if the leaf at {@code leafPos} under it goes: that cell plus the air
     * below, onto a landing that does no harm; {@link Integer#MAX_VALUE} when the landing is unsafe or
     * deeper than {@code maxFall}. The leaf break used to open columns over a terrace 4-6 blocks down
     * (sandbox 2026-11-09: 1-3 HP lost in 3 of 6 runs).
     */
    private static int fallThroughLeaf(ServerWorld world, BlockPos leafPos, int maxFall) {
        int fall = 1;
        BlockPos probe = leafPos.down();
        while (fall <= maxFall && world.getBlockState(probe).isAir()) {
            fall++;
            probe = probe.down();
        }
        return isSafeLanding(world, probe) ? fall : Integer.MAX_VALUE;
    }

    /**
     * Forget the follow path planned from the canopy. Once the bot is down, its next waypoint can sit
     * six blocks over its head and follow keeps it ("waypoints preserved") while the bot stands at the
     * trunk (sandbox 2026-11-09, 2 of 6 runs).
     */
    private static void dropCanopyWaypoints(ServerPlayerEntity bot) {
        if (FollowStateService.FOLLOW_WAYPOINTS.remove(bot.getUuid()) != null) {
            LOGGER.info("TreeStuck: {} off the canopy, dropping the follow path planned up there", bot.getName().getString());
        }
    }

    private static boolean isSafeLanding(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isIn(BlockTags.LEAVES)) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER);
        }
        if (state.isOf(net.minecraft.block.Blocks.MAGMA_BLOCK) || state.isOf(net.minecraft.block.Blocks.CACTUS)
                || state.isIn(BlockTags.CAMPFIRES) || state.isIn(BlockTags.FIRE)) {
            return false;
        }
        return state.isSolidBlock(world, pos);
    }

    private record HoleNudge(BlockPos column, long since) {}

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
        HOLE_NUDGE.remove(botId);
        WAITING_FOR_SAFE_WAY.remove(botId);
        CANOPY_FEET_Y.remove(botId);
        OFF_LEAF_TICKS.remove(botId);
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
                        if (TreeStuckDropPolicy.isSafeDrop(drop) && drop < bestDrop) {
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
    private static BlockPos findLeafTowardTrunk(ServerWorld world, BlockPos botPos, BlockPos trunk, boolean belowAllowed) {
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
        if (belowAllowed && world.getBlockState(below).isIn(BlockTags.LEAVES)) {
            return below;
        }
        return null;
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
        // Tree-stuck escape is autonomous bot self-recovery, not a user skill. If a stale
        // ABORT_LATCH is present (e.g. from a misfired teleport detector or a prior /bot stop),
        // every leaf-mine tick aborts on SkillManager.shouldAbortSkill and the bot is trapped
        // in the canopy until something external moves it. Per `feedback_abort_latch_ownership.md`,
        // non-skill operations need to clear the latch to avoid inheriting stale state.
        TaskService.clearAbortLatch(botId);
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
