package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.GameAI.services.BlockInteractionService;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.SneakLockService;
import net.shasankp000.GameAI.services.TaskService;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.impl.CollectDirtSkill;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.PlayerUtils.MiningTool;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.PathFinding.PathTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Robust hovel builder that uses a woodcut-inspired pillar-up strategy.
 * This version focuses on building from vantage points and patching holes.
 */
public final class HovelPerimeterBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-shelter");
    private static final double REACH_DISTANCE_SQ = 20.25D; 
    private static final long PLACEMENT_DELAY_MS = 20L;
    private static final long REACH_STALL_PERIMETER_WALK_MS = 2000L;
    private static final int PERIMETER_RING_OFFSET = 2;

    private static final List<Item> BUILD_BLOCKS = List.of(
            Items.DIRT, Items.COARSE_DIRT, Items.ROOTED_DIRT,
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
            Items.SANDSTONE, Items.RED_SANDSTONE,
            Items.ANDESITE, Items.GRANITE, Items.DIORITE,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
            Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
            Items.BAMBOO_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
    );

    private static final List<Item> PILLAR_BLOCKS = List.of(
            Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK
    );

    private record HovelPlan(BlockPos center, Direction doorSide) {}

    // Active build context (set at build start). Used for global safety rules like doorway clearance.
    private BlockPos activeBuildCenter;
    private int activeRadius;
    private Direction activeDoorSide;

    // Tracks scaffold bases (X/Z) used in this build to avoid redundant pillars.
    private final Set<BlockPos> usedScaffoldBasesXZ = new HashSet<>();

    // Persistence wrapper around SkillContext.sharedState() so a resumed shelter build can reuse scaffold memory.
    private final HovelBuildStateStore buildStateStore = new HovelBuildStateStore();

    // Tracks repeated attempts to reach the same destination. If we spend >6s trying, do a perimeter walk.
    private final Map<BlockPos, Long> reachAttemptSinceMs = new HashMap<>();

    // Cache for foundation beam progress so we don't re-check already placed beam segments.
    private final Set<BlockPos> confirmedFoundationBeams = new HashSet<>();
    private final Set<BlockPos> pendingFoundationBeams = new LinkedHashSet<>();

    // Track stage chat messages so we don't spam.
    private final Set<String> stageMessagesSent = new HashSet<>();
    private ServerCommandSource stageMessageSource;
    // Track roof access pillars that couldn't be torn down during the roof pass.
    private final Set<RoofPillar> pendingRoofPillars = new HashSet<>();

    private static BlockPos canonicalXZ(BlockPos p) {
        if (p == null) return null;
        return new BlockPos(p.getX(), 0, p.getZ());
    }

    private Direction parseDirectionOr(Direction fallback, Object raw) {
        if (raw == null) return fallback;
        String s = raw.toString().trim();
        if (s.isEmpty()) return fallback;
        for (Direction d : Direction.values()) {
            if (d == null) continue;
            if (d.asString().equalsIgnoreCase(s) || d.name().equalsIgnoreCase(s)) {
                return d;
            }
        }
        return fallback;
    }

    private boolean shouldUsePerimeterRouting(BlockPos dest) {
        if (dest == null || activeBuildCenter == null || activeRadius <= 0) return false;
        int dx = Math.abs(dest.getX() - activeBuildCenter.getX());
        int dz = Math.abs(dest.getZ() - activeBuildCenter.getZ());
        int limit = activeRadius + 8;
        return dx <= limit && dz <= limit;
    }

    private boolean isScaffoldBaseUsed(BlockPos p) {
        if (p == null) return false;
        BlockPos a = canonicalXZ(p);
        return a != null && usedScaffoldBasesXZ.contains(a);
    }

    private void markScaffoldBaseUsed(BlockPos p) {
        BlockPos a = canonicalXZ(p);
        if (a != null) {
            usedScaffoldBasesXZ.add(a);
            buildStateStore.persistUsedScaffoldBases(usedScaffoldBasesXZ);
        }
    }

    private void resetBuildState(boolean clearScaffoldMemory) {
        reachAttemptSinceMs.clear();
        if (clearScaffoldMemory) {
            usedScaffoldBasesXZ.clear();
        }
        confirmedFoundationBeams.clear();
        pendingFoundationBeams.clear();
        stageMessagesSent.clear();
        stageMessageSource = null;
        if (clearScaffoldMemory) {
            pendingRoofPillars.clear();
        }
    }

    private boolean isForbiddenScaffoldBase(BlockPos foot) {
        if (foot == null) return false;
        // Never scaffold in the doorway gap or its inside/outside clearance cells.
        return isDoorwayReservedCell(foot) || isDoorwayFrontClearanceCell(foot);
    }

    private RoofPillar findKnownRoofPillarByXZ(BlockPos anyAtXZ, RoofPillar current) {
        if (anyAtXZ == null) return null;
        int x = anyAtXZ.getX();
        int z = anyAtXZ.getZ();
        if (current != null && current.base() != null) {
            BlockPos b = current.base();
            if (b.getX() == x && b.getZ() == z) {
                return current;
            }
        }
        for (RoofPillar p : pendingRoofPillars) {
            if (p == null || p.base() == null) continue;
            BlockPos b = p.base();
            if (b.getX() == x && b.getZ() == z) {
                return p;
            }
        }
        return null;
    }

    private void removePendingRoofPillarXZ(BlockPos anyAtXZ) {
        if (anyAtXZ == null || pendingRoofPillars.isEmpty()) return;
        int x = anyAtXZ.getX();
        int z = anyAtXZ.getZ();
        pendingRoofPillars.removeIf(p -> p == null || p.base() == null || (p.base().getX() == x && p.base().getZ() == z));
        buildStateStore.persistPendingRoofPillars(pendingRoofPillars);
    }

    /**
     * Resolve a stable stand position for snapping to the "top" of a pillar.
     *
     * <p>Normally {@code perch} is the feet (air) block above a solid pillar block. For debugging,
     * you may place a marker block in that air cell (as in your red-block screenshot). In that case,
     * we still want to snap to the top face, which becomes {@code perch.up()}.</p>
     */
    private BlockPos resolveStandablePerch(ServerWorld world, BlockPos perch) {
        if (world == null || perch == null) return null;
        if (isStandable(world, perch)) return perch.toImmutable();

        BlockState at = world.getBlockState(perch);
        if (at != null && !at.isAir() && isStandable(world, perch.up())) {
            return perch.up().toImmutable();
        }
        return null;
    }

    private BlockPos pickNearestStandable(ServerWorld world, ServerPlayerEntity bot, Collection<BlockPos> candidates) {
        if (world == null || bot == null || candidates == null || candidates.isEmpty()) return null;
        BlockPos botPos = bot.getBlockPos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos c : candidates) {
            if (c == null) continue;
            BlockPos stand = resolveStandablePerch(world, c);
            if (stand == null) continue;
            double d = distSqXZ(botPos, stand);
            if (d < bestSq) {
                bestSq = d;
                best = stand.toImmutable();
            } else if (d == bestSq && best != null) {
                // Deterministic tie-break to avoid oscillation.
                if (stand.getX() < best.getX()
                        || (stand.getX() == best.getX() && stand.getZ() < best.getZ())
                        || (stand.getX() == best.getX() && stand.getZ() == best.getZ() && stand.getY() < best.getY())) {
                    best = stand.toImmutable();
                }
            }
        }
        return best;
    }

    private boolean elevatorDownKnownRoofPillar(ServerWorld world,
                                                ServerPlayerEntity bot,
                                                RoofPillar pillar,
                                                long perBlockTimeoutMs) {
        if (world == null || bot == null || pillar == null || pillar.base() == null) return false;
        BlockPos base = pillar.base();
        int bottomBlockY = base.getY();
        int topStandY = pillar.topY();
        // Allow one extra block at the very top so debug marker blocks (or incidental clutter)
        // placed in the "stand" cell can be removed and the bot can settle onto the intended perch.
        int topBlockY = topStandY;

        // Safety: don't tear down anything that is inside the hovel footprint.
        if (activeBuildCenter != null && activeRadius > 0) {
            int dx = Math.abs(base.getX() - activeBuildCenter.getX());
            int dz = Math.abs(base.getZ() - activeBuildCenter.getZ());
            if (dx <= activeRadius && dz <= activeRadius) {
                LOGGER.warn("Roof pillar teardown refused: pillar appears inside footprint base={} center={} radius={}",
                        base.toShortString(), activeBuildCenter.toShortString(), activeRadius);
                return false;
            }
        }

        final long stepTimeout = Math.max(250L, perBlockTimeoutMs);
        final int guard = Math.max(8, (topBlockY - bottomBlockY + 1) * 3);

        final int px = base.getX();
        final int pz = base.getZ();

        final boolean[] didWork = new boolean[] { false };
        withSneakLock(bot, () -> {
            int g = guard;
            while (g-- > 0 && !SkillManager.shouldAbortSkill(bot)) {
                BlockPos underFeet = bot.getBlockPos().down();
                if (underFeet.getX() != px || underFeet.getZ() != pz) {
                    // If we drifted off the pillar, stop rather than risk mining something else.
                    break;
                }
                int y = underFeet.getY();
                if (y < bottomBlockY || y > topBlockY) {
                    break;
                }
                if (world.getBlockState(underFeet).isAir()) {
                    // If the block is already gone, just yield to gravity.
                    waitForYDecrease(bot, bot.getBlockY(), stepTimeout);
                    continue;
                }
                net.shasankp000.Entity.LookController.faceBlock(bot, underFeet);
                mineSoft(bot, underFeet);
                didWork[0] = true;
                waitForYDecrease(bot, bot.getBlockY(), stepTimeout);
            }
        });

        if (didWork[0]) {
            removePendingRoofPillarXZ(base);
        }
        return didWork[0];
    }

    private static final class StandableCache {
        private final Map<BlockPos, BlockPos> cache = new HashMap<>();

        BlockPos resolve(ServerWorld world, BlockPos seed, int searchRadius, java.util.function.Function<BlockPos, BlockPos> resolver) {
            if (world == null || seed == null) return null;
            BlockPos key = seed.toImmutable();
            BlockPos hit = cache.get(key);
            if (hit != null) return hit;
            BlockPos out = resolver.apply(key);
            if (out != null) {
                cache.put(key, out.toImmutable());
            }
            return out;
        }
    }

    private static final class BuildCounters {
        int attemptedPlacements = 0;
        int placedBlocks = 0;
        int reachFailures = 0;
        int noMaterials = 0;
    }

    private void withSneakLock(ServerPlayerEntity bot, Runnable body) {
        if (bot == null || body == null) {
            return;
        }
        UUID id = bot.getUuid();
        boolean wasSneaking = bot.isSneaking();
        boolean wasExecutingTask = AutoFaceEntity.isBotExecutingTask();
        SneakLockService.acquire(id);
        try {
            // Cancel any in-flight path jobs that may keep teleporting/moving the bot.
            // (A common cause of "falling off" 1x1 scaffolds.)
            try {
                PathTracer.flushAllMovementTasks();
            } catch (Exception ignored) {
            }
            AutoFaceEntity.setBotExecutingTask(true);

            // Keep the bot crouched and stationary while on 1x1 scaffolds.
            BotActions.sneak(bot, true);
            BotActions.sprint(bot, false);
            BotActions.stop(bot);
            sleepQuiet(60L);
            body.run();
        } finally {
            SneakLockService.release(id);
            AutoFaceEntity.setBotExecutingTask(wasExecutingTask);
            // Restore original sneak intent if no other lock remains.
            if (!wasSneaking) {
                BotActions.sneak(bot, false);
            }
        }
    }

    private SkillExecutionResult abortResult(ServerPlayerEntity bot) {
        if (bot == null) {
            return SkillExecutionResult.failure("Aborted.");
        }
        String reason = TaskService.getCancelReason(bot.getUuid()).orElse("Aborted.");
        return SkillExecutionResult.failure(reason);
    }

    public SkillExecutionResult build(SkillContext context,
                                      ServerCommandSource source,
                                      ServerPlayerEntity bot,
                                      ServerWorld world,
                                      BlockPos origin,
                                      int radius,
                                      int wallHeight,
                                      Direction preferredDoorSide,
                                      boolean resumeRequested) {
        // Attach sharedState persistence hooks as early as possible so resume can reuse prior plan.
        buildStateStore.attach(context, bot);

        HovelPlan plan = resolvePlan(world, bot, origin, radius, preferredDoorSide, context.sharedState(), resumeRequested);
        BlockPos requestedStand = plan.center();
        Direction doorSide = plan.doorSide();
        logStage("start", "plan resolved center=" + requestedStand + " radius=" + radius + " height=" + wallHeight + " door=" + doorSide);

        // Persist the plan anchor immediately so a paused build resumes the same site even if the bot moves.
        buildStateStore.persistPlanAnchor(requestedStand, doorSide);

        // IMPORTANT: keep build geometry anchored to the floor BLOCK layer, but keep movement anchored
        // to the standable (feet) layer. Conflating these is the #1 cause of:
        // - missing interior floor fills (placing at the wrong Y)
        // - missed corner beams (trying to place into air with no support)
        // - "wall-humping" (pathing to non-standable Y)
        int floorBlockY = detectFloorBlockY(world, requestedStand);
        BlockPos buildCenter = new BlockPos(requestedStand.getX(), floorBlockY, requestedStand.getZ());
        BlockPos standCenter = buildCenter.up();

        // (persistence already attached above)

        // Publish active context for safety rules.
        this.activeBuildCenter = buildCenter;
        this.activeRadius = radius;
        this.activeDoorSide = doorSide;

        // Persist/restore scaffold memory so a resumed build doesn't rebuild the same pillars.
        // If the resume doesn't match this build signature, we start clean.
        boolean restored = false;
        if (resumeRequested) {
            restored = buildStateStore.restoreBuildStateIfCompatible(LOGGER, buildCenter, radius, wallHeight, doorSide,
                    usedScaffoldBasesXZ, pendingRoofPillars);
        }
        if (!restored) {
            buildStateStore.clearPersistedBuildState();
        }
        // Always refresh the signature for the current build.
        buildStateStore.persistBuildSignature(buildCenter, radius, wallHeight, doorSide);

        // Clear transient state; only clear scaffold memory if we did not restore it.
        resetBuildState(!restored);
        this.stageMessageSource = context != null && context.requestSource() != null
                ? context.requestSource()
                : source;

        // Initial move is critical; allow pathing with a short budget.
        if (!moveToBuildSiteAllowPathing(source, bot, standCenter)) {
            logStageWarn("init-move", "could not reach initial build stand at " + standCenter);
            return SkillExecutionResult.failure("I could not reach a safe spot to build a hovel.");
        }
        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        BuildCounters counters = new BuildCounters();

        // Stock up BEFORE any leveling / beams / floor work (those phases place blocks too).
        int needed = estimatePlacementNeed(world, buildCenter, radius, wallHeight, doorSide);
        if (countBuildBlocks(bot) < needed) {
            ensureBuildStock(source, bot, needed, standCenter);
        }
        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        sendStageMessage("leveling", "Hovel: leveling build site...");
        logStage("leveling", "start center=" + buildCenter + " radius=" + radius);
        levelBuildSite(world, source, bot, buildCenter, radius, wallHeight, counters);
        logStage("leveling", "done pos=" + bot.getBlockPos() + " interrupted=" + Thread.currentThread().isInterrupted());

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        // Corner pillars are the most commonly missed placements.
        // Build them immediately after leveling, from diagonal outside stances, so later phases
        // don't mis-predict reach from interior stations.
        buildCornerBeamsEarly(world, source, bot, buildCenter, radius, wallHeight, counters);

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        // CRITICAL: do not proceed until the foundation beams are definitely constructed.
        // If a corner is missing due to a terrain hole, later phases will oscillate and wall-hump.
        if (!ensureFoundationBeamsComplete(world, source, bot, buildCenter, radius, wallHeight, counters)) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return abortResult(bot);
            }
            logStageWarn("foundation", "unable to complete corner beams after retries; continuing best-effort");
            sendStageMessage("foundation-warning", "Hovel: WARNING - could not fully complete foundation beams (corners). Continuing best-effort.");
        }

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        // Leveling + early scaffolds can still leave 1x1 floor holes if we never stand near them again.
        // Patch interior floor and remove clutter (grass/flowers) before walls reduce mobility.
        patchInteriorFloorAndClutter(world, source, bot, buildCenter, radius, counters, null);

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        // Ensure the future doorway is traversable before later phases attempt exterior angles.
        ensureDoorwayOpen(world, source, bot, buildCenter, radius, doorSide);

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        List<BlockPos> walls = HovelBlueprint.generateWallBlueprint(buildCenter, radius, wallHeight, doorSide);
        List<BlockPos> roof = HovelBlueprint.generateRoofBlueprint(buildCenter, radius, wallHeight);

        StandableCache standableCache = new StandableCache();

        // Build targets in a stable, layer-by-layer scaffold loop.
        // Key behavior change:
        // - Move to a station ONCE
        // - Stop + stabilize
        // - Scaffold upward one layer at a time
        // - At each layer: place everything within reach
        // - Teardown scaffold only after the station is exhausted
        sendStageMessage("walls", "Hovel: building walls (layer-by-layer scaffolding)...");
        buildByStationsLayered(world, source, bot, buildCenter, radius, wallHeight, walls, counters, standableCache);

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        // Exterior walk patch is especially good at fixing the "single window" hole.
        if (countMissing(world, walls) > 0) {
            sendStageMessage("wall-exterior", "Hovel: exterior wall patch...");
            exteriorWallPerimeterPatch(world, source, bot, buildCenter, radius, walls, counters);
        }

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        // Revisit any missing foundation beam segments after wall placements stabilize the footprint.
        patchPendingFoundationBeams(world, source, bot, buildCenter, radius, wallHeight, counters);

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        List<BlockPos> allTargets = new ArrayList<>(walls.size() + roof.size());
        allTargets.addAll(walls);
        allTargets.addAll(roof);

        // Final floor polish: remove clutter and fill any remaining holes inside the hovel.
        patchInteriorFloorAndClutter(world, source, bot, buildCenter, radius, counters, null);

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        // Last angle sweep.
        if (countMissing(world, allTargets) > 0) {
            sendStageMessage("final-sweep-2", "Hovel: final sweep...");
            finalStationSweep(world, source, bot, buildCenter, radius, allTargets, counters, standableCache);
        }

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        // If roof is still incomplete, fall back to scaffold-based roof patching (doesn't require stepping onto the roof).
        if (radius >= 2 && countMissing(world, roof) > 0) {
            sendStageMessage("roof-scaffold", "Hovel: roof patch (scaffolds)...");
            patchRoofWithScaffolds(world, source, bot, buildCenter, radius, wallHeight, roof, counters);
        }

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        // True final leveling: fill holes AND shave any remaining raised blocks in the interior.
        // (Do this BEFORE amenities so we don't fight clutter/uneven terrain while placing doors/torches.)
        sendStageMessage("interior-level", "Hovel: interior cleanup...");
        cleanupRoofAccessPillars(world, source, bot, buildCenter, radius);
        finalInteriorLevelingPass(world, source, bot, buildCenter, radius, wallHeight, doorSide);

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        // Final amenities pass: door + torches, with retries.
        // These are mandatory usability invariants whenever the bot has the items.
        sendStageMessage("amenities", "Hovel: placing door/torches...");
        finalizeDoorAndTorches(world, source, bot, buildCenter, radius, doorSide);

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        // Re-enforce doorway clearance and re-level once more to guarantee the end state.
        ensureDoorwayOpen(world, source, bot, buildCenter, radius, doorSide);
        finalInteriorLevelingPass(world, source, bot, buildCenter, radius, wallHeight, doorSide);

        if (SkillManager.shouldAbortSkill(bot)) {
            return abortResult(bot);
        }

        int missing = countMissing(world, walls) + countMissing(world, roof);
        LOGGER.info("Hovel build finished. Missing: {}, Placed: {}, Attempts: {}, ReachFail: {}, NoMat: {}",
                missing, counters.placedBlocks, counters.attemptedPlacements, counters.reachFailures, counters.noMaterials);
        if (missing == 0) {
            sweepDrops(source, radius + 5, 6.0, 60, 5000L);
            ChatUtils.sendSystemMessage(source, "Hovel complete!");
            return SkillExecutionResult.success("Hovel built.");
        } else {
            logStageWarn("result", "missing " + missing + " blocks after all passes");
            boolean usable = hasDoorInstalled(world, buildCenter, radius, doorSide)
                    || hasAnyInteriorTorch(world, buildCenter, radius, doorSide);
            ChatUtils.sendSystemMessage(source, "Hovel mostly complete (" + missing + " blocks missing)." + (usable ? " (usable)" : ""));
            return usable
                    ? SkillExecutionResult.success("Hovel mostly complete but usable.")
                    : SkillExecutionResult.failure("Hovel incomplete.");
        }
    }

    private void logStage(String stage, String detail) {
        LOGGER.info("Hovel [{}] {}", stage, detail);
    }

    private void logStageWarn(String stage, String detail) {
        LOGGER.warn("Hovel [{}] {}", stage, detail);
    }

    private void sendStageMessage(String key, String message) {
        ServerCommandSource source = stageMessageSource;
        if (source == null || key == null || message == null) return;
        if (!stageMessagesSent.add(key)) return;
        ChatUtils.sendSystemMessage(source, message);
    }

    private boolean hasDoorInstalled(ServerWorld world, BlockPos center, int radius, Direction doorSide) {
        if (world == null || center == null || doorSide == null) return false;
        int standY = center.getY() + 1;
        BlockPos doorPos = center.offset(doorSide, radius).withY(standY);
        BlockState s = world.getBlockState(doorPos);
        return s.getBlock() instanceof net.minecraft.block.DoorBlock;
    }

    private boolean hasAnyInteriorTorch(ServerWorld world, BlockPos center, int radius, Direction doorSide) {
        if (world == null || center == null) return false;
        int floorY = center.getY();
        int y = floorY + 1;
        List<BlockPos> candidates = List.of(
                // primary targets
                new BlockPos(center.getX(), y, center.getZ() - radius + 1),
                new BlockPos(center.getX(), y, center.getZ() + radius - 1),
                new BlockPos(center.getX() - radius + 1, y, center.getZ()),
                new BlockPos(center.getX() + radius - 1, y, center.getZ()),
                // fallback corners
                center.add(radius - 1, 1, radius - 1),
                center.add(-(radius - 1), 1, radius - 1),
                center.add(radius - 1, 1, -(radius - 1)),
                center.add(-(radius - 1), 1, -(radius - 1))
        );
        for (BlockPos p : candidates) {
            if (p == null) continue;
            if (isDoorwayFrontClearanceCell(center, radius, doorSide, p)) continue;
            if (world.getBlockState(p).isOf(Blocks.TORCH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ensure the four corner pillars (foundation beams) are actually present before proceeding.
     *
     * <p>Why: if a corner base is missing due to a terrain hole, corner scaffolding frequently fails
     * and later phases devolve into repeated stuck/wall-humping attempts.</p>
     */
    private boolean ensureFoundationBeamsComplete(ServerWorld world,
                                                  ServerCommandSource source,
                                                  ServerPlayerEntity bot,
                                                  BlockPos center,
                                                  int radius,
                                                  int wallHeight,
                                                  BuildCounters counters) {
        if (world == null || source == null || bot == null || center == null) return false;
        if (radius <= 0 || wallHeight <= 0) return false;

        int baseY = center.getY();
        int roofY = baseY + wallHeight;
        long startMs = System.currentTimeMillis();
        boolean heartbeatSent = false;
        BlockPos lastTarget = null;

        ArrayList<BlockPos> corners = new ArrayList<>(4);
        corners.add(new BlockPos(center.getX() + radius, baseY, center.getZ() + radius));
        corners.add(new BlockPos(center.getX() - radius, baseY, center.getZ() + radius));
        corners.add(new BlockPos(center.getX() - radius, baseY, center.getZ() - radius));
        corners.add(new BlockPos(center.getX() + radius, baseY, center.getZ() - radius));

        // Only require the pillar above the floor plane (y = baseY+1..roofY).
        // The baseY block may already be solid terrain, which is fine.
        ArrayList<BlockPos> required = new ArrayList<>(4 * (wallHeight + 2));
        for (BlockPos c : corners) {
            for (int y = baseY + 1; y <= roofY; y++) {
                required.add(c.withY(y));
            }
        }

        int missing0 = countMissingFoundationBeams(world, required);
        if (missing0 == 0) {
            return true;
        }

        LOGGER.info("Foundation beams: missing {} blocks; attempting to repair/complete corners...", missing0);
        sendStageMessage("foundation", "Hovel: building foundation beams...");

        int attempts = 0;
        int noProgressStreak = 0;
        int lastMissing = missing0;
        while (attempts++ < 3 && !SkillManager.shouldAbortSkill(bot)) {
            // Ensure corner footings are solid so placement doesn't fail due to holes.
            for (BlockPos c : corners) {
                if (SkillManager.shouldAbortSkill(bot)) return false;
                lastTarget = c;
                ensureCornerFooting(world, source, bot, center, radius, c);
            }

            buildCornerBeamsEarly(world, source, bot, center, radius, wallHeight, counters);
            int missing = countMissingFoundationBeams(world, required);
            LOGGER.info("Foundation beams: attempt {} missing {}", attempts, missing);
            if (missing == 0) {
                return true;
            }
            if (missing >= lastMissing) {
                noProgressStreak++;
                if (noProgressStreak >= 1) {
                    break;
                }
            } else {
                noProgressStreak = 0;
            }
            lastMissing = missing;
            long now = System.currentTimeMillis();
            if (!heartbeatSent && now - startMs >= 30000L) {
                String detail = "Hovel: still working on foundation beams (attempt " + attempts + ", missing " + missing + ")";
                if (lastTarget != null) {
                    detail += " lastCorner=" + lastTarget;
                }
                logStageWarn("foundation", "slow progress " + detail);
                heartbeatSent = true;
            }
        }

        if (SkillManager.shouldAbortSkill(bot)) {
            return false;
        }
        if (stageMessageSource != null && lastTarget != null) {
            ChatUtils.sendSystemMessage(stageMessageSource,
                    "Hovel: foundation beams timed out; continuing (last corner " + lastTarget + ")");
        }
        if (lastTarget != null) {
            logStageWarn("foundation", "timeout after " + attempts + " attempts; lastCorner=" + lastTarget);
        }
        // Track any remaining missing beam segments so later scaffold passes can target them.
        pendingFoundationBeams.clear();
        for (BlockPos p : required) {
            if (p == null) continue;
            if (confirmedFoundationBeams.contains(p)) continue;
            if (isMissing(world, p)) {
                pendingFoundationBeams.add(p.toImmutable());
            } else {
                confirmedFoundationBeams.add(p.toImmutable());
            }
        }
        LOGGER.warn("Foundation beams: proceeding with {} missing segments (will attempt scaffold patch later).",
                pendingFoundationBeams.size());
        return pendingFoundationBeams.isEmpty();
    }

    private int countMissingFoundationBeams(ServerWorld world, List<BlockPos> required) {
        if (world == null || required == null || required.isEmpty()) return 0;
        int missing = 0;
        for (BlockPos p : required) {
            if (p == null) continue;
            if (confirmedFoundationBeams.contains(p)) {
                // Ensure we don't trust stale cache if the block was removed.
                if (isMissing(world, p)) {
                    confirmedFoundationBeams.remove(p);
                } else {
                    continue;
                }
            }
            if (isMissing(world, p)) {
                missing++;
            } else {
                confirmedFoundationBeams.add(p.toImmutable());
            }
        }
        return missing;
    }

    private void patchPendingFoundationBeams(ServerWorld world,
                                             ServerCommandSource source,
                                             ServerPlayerEntity bot,
                                             BlockPos center,
                                             int radius,
                                             int wallHeight,
                                             BuildCounters counters) {
        if (world == null || source == null || bot == null || center == null) return;
        if (pendingFoundationBeams.isEmpty()) return;

        // Refresh pending list based on the world state.
        pendingFoundationBeams.removeIf(p -> p == null || !isMissing(world, p));
        if (pendingFoundationBeams.isEmpty()) return;

        logStageWarn("foundation", "patching " + pendingFoundationBeams.size() + " missing beam blocks via scaffolds");
        sendStageMessage("foundation-patch", "Hovel: patching missing foundation beams...");
        int baseY = center.getY();
        int standY = baseY + 1;
        int roofY = baseY + wallHeight;
        int outside = radius + 2;

        List<BlockPos> corners = sortByDistanceToBot(bot, List.of(
                new BlockPos(center.getX() + radius, baseY, center.getZ() + radius),
                new BlockPos(center.getX() - radius, baseY, center.getZ() + radius),
                new BlockPos(center.getX() - radius, baseY, center.getZ() - radius),
                new BlockPos(center.getX() + radius, baseY, center.getZ() - radius)
        ));

        for (BlockPos corner : corners) {
            if (SkillManager.shouldAbortSkill(bot)) return;
            ArrayList<BlockPos> cornerTargets = new ArrayList<>();
            for (int y = baseY + 1; y <= roofY; y++) {
                BlockPos p = corner.withY(y);
                if (pendingFoundationBeams.contains(p) && isMissing(world, p)) {
                    cornerTargets.add(p);
                }
            }
            if (cornerTargets.isEmpty()) continue;

            int sx = Integer.signum(corner.getX() - center.getX());
            int sz = Integer.signum(corner.getZ() - center.getZ());
            BlockPos stanceSeed = center.add(sx * outside, 0, sz * outside).withY(standY);
            BlockPos base = findNearbyStandableFiltered(world, stanceSeed, 6, (p) -> {
                if (isForbiddenScaffoldBase(p) || isScaffoldBaseUsed(p)) return false;
                int dx = p.getX() - center.getX();
                int dz = p.getZ() - center.getZ();
                if (Integer.signum(dx) != sx || Integer.signum(dz) != sz) return false;
                return Math.abs(dx) >= radius + 1 && Math.abs(dz) >= radius + 1;
            });
            if (base == null) continue;
            ensureRingStandable(world, bot, base);
            if (!moveToRingPosFast(source, bot, base)) continue;

            scaffoldPlaceAndTeardown(world, bot, cornerTargets, roofY - 1, counters, Collections.emptySet(), true);

            // Refresh pending list after each corner pass.
            pendingFoundationBeams.removeIf(p -> p == null || !isMissing(world, p));
            if (pendingFoundationBeams.isEmpty()) break;
        }
    }

    private void cleanupRoofAccessPillars(ServerWorld world,
                                          ServerCommandSource source,
                                          ServerPlayerEntity bot,
                                          BlockPos center,
                                          int radius) {
        if (world == null || source == null || bot == null || center == null) {
            return;
        }
        if (pendingRoofPillars.isEmpty()) {
            return;
        }
        sendStageMessage("roof-cleanup", "Hovel: clearing leftover roof scaffolds...");
        LOGGER.info("Hovel: roof access pillar cleanup starting (pending={})", pendingRoofPillars.size());

        // Deterministically choose the nearest standable pending pillar top and fully tear it down.
        int guard = Math.max(2, pendingRoofPillars.size() * 2);
        while (guard-- > 0 && !pendingRoofPillars.isEmpty() && !SkillManager.shouldAbortSkill(bot)) {
            ArrayList<BlockPos> candidates = new ArrayList<>(pendingRoofPillars.size());
            for (RoofPillar p : pendingRoofPillars) {
                if (p == null || p.base() == null) continue;
                candidates.add(p.base().withY(p.topY()));
            }
            BlockPos exitTop = pickNearestStandable(world, bot, candidates);
            if (exitTop == null) {
                break;
            }
            RoofPillar chosen = findKnownRoofPillarByXZ(exitTop, null);
            if (chosen == null) {
                break;
            }

            // Move near the base first to keep chunk/interaction stable.
            BlockPos base = chosen.base();
            BlockPos approach = findNearbyStandableFiltered(world, base, 5, (p) -> !isDoorwayReservedCell(p));
            if (approach != null) {
                moveToRingPosFast(source, bot, approach);
            }

            // Snap to the chosen top (no oscillation). This is the "elevator" entry point.
            BlockPos snap = resolveStandablePerch(world, exitTop);
            if (snap == null) {
                // If we can't stand on/above it, leave it pending.
                break;
            }
            LOGGER.info("Hovel: roof pillar cleanup snapping to top={} base={} (pending={})",
                    snap.toShortString(), base.toShortString(), pendingRoofPillars.size());
            withSneakLock(bot, () -> {
                BotActions.stop(bot);
                BotActions.sneak(bot, true);
                bot.teleport(world,
                        snap.getX() + 0.5D,
                        snap.getY(),
                        snap.getZ() + 0.5D,
                        EnumSet.noneOf(PositionFlag.class),
                        bot.getYaw(),
                        bot.getPitch(),
                        true);
                bot.setVelocity(Vec3d.ZERO);
                sleepQuiet(80L);
            });

            boolean ok = elevatorDownKnownRoofPillar(world, bot, chosen, 900L);
            LOGGER.info("Hovel: roof pillar cleanup result ok={} base={} remainingPending={}",
                    ok, base.toShortString(), pendingRoofPillars.size());

            // If we failed to do any work, avoid looping forever.
            if (!ok) {
                break;
            }
        }

        buildStateStore.persistPendingRoofPillars(pendingRoofPillars);
    }

    private void clearPendingRoofPillarAt(ServerWorld world, ServerPlayerEntity bot, BlockPos ringPos) {
        if (world == null || bot == null || ringPos == null || pendingRoofPillars.isEmpty()) {
            return;
        }
        Iterator<RoofPillar> iter = pendingRoofPillars.iterator();
        while (iter.hasNext()) {
            RoofPillar pillar = iter.next();
            if (pillar == null || pillar.base() == null) {
                iter.remove();
                continue;
            }
            BlockPos base = pillar.base();
            if (base.getX() != ringPos.getX() || base.getZ() != ringPos.getZ()) {
                continue;
            }
            // IMPORTANT: Do NOT path/move from here.
            // This method is invoked by ensureRingStandable(), which is itself invoked during perimeter routing.
            // Moving from here re-enters the routing stack and can recurse until StackOverflowError.
            boolean clearedAny = false;
            boolean allAir = true;
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos p = base.withY(base.getY() + dy);
                if (!world.getBlockState(p).isAir()) {
                    allAir = false;
                }
                if (!BlockInteractionService.canInteract(bot, p, REACH_DISTANCE_SQ)) {
                    continue;
                }
                if (isPillarMaterial(world.getBlockState(p))) {
                    mineSoft(bot, p);
                    sleepQuiet(60L);
                    clearedAny = true;
                }
            }
            // Only forget this pending pillar if it is actually cleared (or already gone).
            if (allAir || clearedAny) {
                iter.remove();
            }
        }
        buildStateStore.persistPendingRoofPillars(pendingRoofPillars);
    }

    private boolean isPillarMaterial(BlockState state) {
        if (state == null) {
            return false;
        }
        return PILLAR_BLOCKS.contains(state.getBlock().asItem());
    }

    /**
     * Ensure the terrain under a corner is solid enough to support pillar placement.
     * Tries to fill up to 3 blocks downward from (baseY-1) so we can place at the floor/wall layer.
     */
    private void ensureCornerFooting(ServerWorld world,
                                     ServerCommandSource source,
                                     ServerPlayerEntity bot,
                                     BlockPos center,
                                     int radius,
                                     BlockPos cornerBaseY) {
        if (world == null || source == null || bot == null || center == null || cornerBaseY == null) return;

        int baseY = center.getY();
        int standY = baseY + 1;

        int sx = Integer.signum(cornerBaseY.getX() - center.getX());
        int sz = Integer.signum(cornerBaseY.getZ() - center.getZ());
        int outside = radius + 2;

        BlockPos stanceSeed = center.add(sx * outside, 0, sz * outside).withY(standY);
        BlockPos stance = findNearbyStandableFiltered(world, stanceSeed, 6, (p) -> {
            if (isForbiddenScaffoldBase(p)) return false;
            int dx = p.getX() - center.getX();
            int dz = p.getZ() - center.getZ();
            if (Integer.signum(dx) != sx || Integer.signum(dz) != sz) return false;
            return Math.abs(dx) >= radius + 1 && Math.abs(dz) >= radius + 1;
        });
        if (stance != null) {
            ensureRingStandable(world, bot, stance);
            // Use perimeter-aware helper so we don't wall-hump trying to reach the diagonal corner stance.
            moveToBuildSiteWithPerimeterFallback(world, source, bot, stance, center, radius);
            prepareScaffoldBase(world, bot);
        }

        // Fill up to 3 blocks deep beneath the corner.
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos p = cornerBaseY.withY(baseY - dy);
            BlockState s = world.getBlockState(p);
            boolean needs = s.isAir() || s.isReplaceable() || s.isIn(BlockTags.LEAVES) || !world.getFluidState(p).isEmpty()
                    || s.getCollisionShape(world, p).isEmpty();
            if (!needs) {
                // Once we have solid support, higher layers can place against it.
                break;
            }
            // Clear replaceables first.
            if (!s.isAir() && (s.isReplaceable() || s.isIn(BlockTags.LEAVES))) {
                mineSoft(bot, p);
            }
            if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) <= REACH_DISTANCE_SQ) {
                BotActions.placeBlockAt(bot, p, Direction.UP, PILLAR_BLOCKS);
                sleepQuiet(50L);
            }
        }
        // If the corner base itself is air, fill it now so beam placement has a support.
        if (isMissing(world, cornerBaseY) && bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(cornerBaseY)) <= REACH_DISTANCE_SQ) {
            BotActions.placeBlockAt(bot, cornerBaseY, Direction.UP, PILLAR_BLOCKS);
            sleepQuiet(50L);
        }
    }

    private void finalizeDoorAndTorches(ServerWorld world,
                                        ServerCommandSource source,
                                        ServerPlayerEntity bot,
                                        BlockPos center,
                                        int radius,
                                        Direction doorSide) {
        if (world == null || source == null || bot == null || center == null || doorSide == null) return;

        // Always enforce doorway clearance first.
        ensureDoorwayOpen(world, source, bot, center, radius, doorSide);

        // Try to move inside before placing amenities, to avoid wall-humping from outside.
        enterInteriorViaDoor(world, source, bot, center, radius, doorSide);

        // Door: retry a few times if we have one and it's not installed.
        boolean hasDoorItem = hasAnyDoorItem(bot);
        boolean doorPlaced = hasDoorInstalled(world, center, radius, doorSide);
        for (int attempt = 1; attempt <= 3 && hasDoorItem && !doorPlaced && !SkillManager.shouldAbortSkill(bot); attempt++) {
            LOGGER.info("Amenities: door attempt {}", attempt);
            placeDoorIfAvailable(world, source, bot, center, radius, doorSide);
            ensureDoorwayOpen(world, source, bot, center, radius, doorSide);
            doorPlaced = hasDoorInstalled(world, center, radius, doorSide);
        }
        if (hasDoorItem && !doorPlaced && stageMessageSource != null) {
            ChatUtils.sendSystemMessage(stageMessageSource, "Hovel: couldn't place door (blocked or unreachable).");
            logStageWarn("amenities", "door placement failed despite door item");
        }

        // Torches: retry if we have torches and none are present inside.
        boolean hasTorchItem = hasAnyTorchItem(bot);
        for (int attempt = 1; attempt <= 3 && hasTorchItem && !SkillManager.shouldAbortSkill(bot); attempt++) {
            if (hasAnyInteriorTorch(world, center, radius, doorSide)) break;
            LOGGER.info("Amenities: torch attempt {}", attempt);
            placeWallTorches(world, source, bot, center, radius, doorSide);
        }
    }

    private boolean hasAnyTorchItem(ServerPlayerEntity bot) {
        if (bot == null) return false;
        if (!bot.getMainHandStack().isEmpty() && bot.getMainHandStack().getItem() == Items.TORCH) return true;
        if (!bot.getOffHandStack().isEmpty() && bot.getOffHandStack().getItem() == Items.TORCH) return true;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack s = bot.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == Items.TORCH) return true;
        }
        return false;
    }

    private boolean hasAnyDoorItem(ServerPlayerEntity bot) {
        if (bot == null) return false;
        List<Item> candidates = List.of(
                Items.OAK_DOOR, Items.SPRUCE_DOOR, Items.BIRCH_DOOR, Items.JUNGLE_DOOR,
                Items.ACACIA_DOOR, Items.DARK_OAK_DOOR, Items.MANGROVE_DOOR, Items.CHERRY_DOOR,
                Items.BAMBOO_DOOR, Items.CRIMSON_DOOR, Items.WARPED_DOOR,
                Items.IRON_DOOR
        );
        ItemStack main = bot.getMainHandStack();
        if (!main.isEmpty() && candidates.contains(main.getItem())) return true;
        ItemStack off = bot.getOffHandStack();
        if (!off.isEmpty() && candidates.contains(off.getItem())) return true;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack s = bot.getInventory().getStack(i);
            if (!s.isEmpty() && candidates.contains(s.getItem())) return true;
        }
        return false;
    }



    /**
     * Build by visiting a small, stable set of stations (center + corners + outer ring).
     *
     * For each station:
     * - Move there (once)
     * - Stop/crouch
     * - Scaffold upward one layer at a time up to roof-1
     * - At each layer: place all missing targets within reach
     * - Teardown the scaffold only after placement work is done
     */
    private void buildByStationsLayered(ServerWorld world,
                                       ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       BlockPos center,
                                       int radius,
                                       int wallHeight,
                                       List<BlockPos> allTargets,
                                       BuildCounters counters,
                                       StandableCache standableCache) {
        if (world == null || source == null || bot == null || center == null || allTargets == null) {
            return;
        }
        int floorBlockY = center.getY();
        int standY = floorBlockY + 1;
        int roofY = floorBlockY + wallHeight;
        // Standing at roofY-1 is generally the best compromise for placing roof blocks at roofY.
        int targetStandY = roofY - 1;

        LinkedHashSet<BlockPos> stationSeeds = new LinkedHashSet<>();
        stationSeeds.add(center);

        // Interior stations (historically reliable) but keep them farther from walls to reduce "wall-humping".
        int inner = Math.max(0, radius - 2);
        stationSeeds.add(center.add(inner, 0, inner));
        stationSeeds.add(center.add(-inner, 0, inner));
        stationSeeds.add(center.add(-inner, 0, -inner));
        stationSeeds.add(center.add(inner, 0, -inner));
        stationSeeds.add(center.add(inner, 0, 0));
        stationSeeds.add(center.add(-inner, 0, 0));
        stationSeeds.add(center.add(0, 0, inner));
        stationSeeds.add(center.add(0, 0, -inner));

        // NOTE: We intentionally do NOT include outside-ring stations here.
        // Once walls are partially built, outside-ring stations can become unreachable, causing
        // repeated path attempts and "wall-humping".

        for (BlockPos seed : stationSeeds) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (countMissing(world, allTargets) == 0) {
                return;
            }

                BlockPos seedY = seed.withY(standY);
            BlockPos base = (standableCache == null)
                    ? findNearbyStandableFiltered(world, seedY, 5, (p) -> !isForbiddenScaffoldBase(p) && !isScaffoldBaseUsed(p))
                    : standableCache.resolve(world, seedY, 5, (s) -> findNearbyStandableFiltered(world, s, 5, (p) -> !isForbiddenScaffoldBase(p) && !isScaffoldBaseUsed(p)));
            if (base == null) {
                continue;
            }
            if (!moveToBuildSiteWithPerimeterFallback(world, source, bot, base, center, radius)) {
                continue;
            }

            // IMPORTANT: prep must happen AFTER we arrive. Doing it before arrival makes it fail out of reach.
            prepareScaffoldBase(world, bot);

            scaffoldLayeredAndPlace(world, bot, allTargets, targetStandY, counters);
        }
    }

    /**
     * Do a quick non-scaffold placement pass from the same station set.
     * This avoids per-block pathfinding while still giving multiple angles.
     */
    private void finalStationSweep(ServerWorld world,
                                   ServerCommandSource source,
                                   ServerPlayerEntity bot,
                                   BlockPos center,
                                   int radius,
                                   List<BlockPos> allTargets,
                                   BuildCounters counters,
                                   StandableCache standableCache) {
        int standY = center.getY() + 1;
        // Keep sweep stations inside the footprint; outside stations can be unreachable once enclosed.
        int stationDist = Math.max(0, radius - 2);
        List<BlockPos> bases = List.of(
                center,
            center.add(stationDist, 0, 0),
            center.add(-stationDist, 0, 0),
            center.add(0, 0, stationDist),
            center.add(0, 0, -stationDist),
            center.add(stationDist, 0, stationDist),
            center.add(-stationDist, 0, stationDist),
            center.add(-stationDist, 0, -stationDist),
            center.add(stationDist, 0, -stationDist)
        );

        for (BlockPos seed : bases) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (countMissing(world, allTargets) == 0) {
                return;
            }
                BlockPos seedY = seed.withY(standY);
            BlockPos base = (standableCache == null)
                    ? findNearbyStandableFiltered(world, seedY, 5, (p) -> !isForbiddenScaffoldBase(p))
                    : standableCache.resolve(world, seedY, 5, (s) -> findNearbyStandableFiltered(world, s, 5, (p) -> !isForbiddenScaffoldBase(p)));
            if (base == null) {
                continue;
            }
            if (!moveToBuildSiteWithPerimeterFallback(world, source, bot, base, center, radius)) {
                continue;
            }
            prepareScaffoldBase(world, bot);
            placeUntilStalled(world, bot, allTargets, counters, 3);
        }
    }

    /**
     * Core loop: stay on one X/Z station, scaffold up ONE step at a time, and place everything in reach.
     * This prevents the "run into wall while trying to scaffold" failure mode.
     */
    private void scaffoldLayeredAndPlace(ServerWorld world,
                                        ServerPlayerEntity bot,
                                        List<BlockPos> targets,
                                        int standY,
                                        BuildCounters counters) {
        if (bot == null || world == null || targets == null) {
            return;
        }

        final List<BlockPos> pillar = new ArrayList<>();
        try {
            withSneakLock(bot, () -> {
                // Place what we can at the current level first.
                placeUntilStalled(world, bot, targets, counters, 2);

                // If nothing is reachable from this station even at the intended stand height,
                // don't build a pillar here (common cause of "pillar for one unreachable hole").
                double eyeOffset = bot.getEyeY() - bot.getY();
                Vec3d eyeAtTop = new Vec3d(bot.getEyePos().x, (double) standY + eyeOffset, bot.getEyePos().z);
                if (!hasAnyMissingReachableFromEye(world, targets, eyeAtTop)) {
                    return;
                }

                int maxSteps = Math.max(0, standY - bot.getBlockY());
                for (int i = 0; i < maxSteps; i++) {
                    if (SkillManager.shouldAbortSkill(bot)) {
                        return;
                    }

                    // If nothing is reachable from the next layer up, stop scaffolding.
                    Vec3d eyeNext = new Vec3d(bot.getEyePos().x, (double) (bot.getBlockY() + 1) + eyeOffset, bot.getEyePos().z);
                    if (!hasAnyMissingReachableFromEye(world, targets, eyeNext) && !hasAnyMissingReachableFromEye(world, targets, eyeAtTop)) {
                        return;
                    }
                    // Center + stop before each pillar step.
                    BotActions.stop(bot);
                    sleepQuiet(80L);

                    if (!pillarUp(bot, 1, pillar, i == 0)) {
                        return;
                    }
                    // After each step, place again from this new layer.
                    placeUntilStalled(world, bot, targets, counters, 2);
                }

                // Final placement attempt at top.
                placeUntilStalled(world, bot, targets, counters, 3);
            });
        } finally {
            teardownScaffolding(bot, pillar, Collections.emptySet(), targets, counters);
        }
    }

    private boolean hasAnyMissingReachableFromEye(ServerWorld world, List<BlockPos> targets, Vec3d eye) {
        if (world == null || targets == null || targets.isEmpty() || eye == null) {
            return false;
        }
        for (BlockPos p : targets) {
            if (p == null) continue;
            if (!isMissing(world, p)) continue;
            if (eye.squaredDistanceTo(Vec3d.ofCenter(p)) <= REACH_DISTANCE_SQ) {
                return true;
            }
        }
        return false;
    }

    private boolean placeBlockDirectIfWithinReach(ServerPlayerEntity bot, BlockPos pos, BuildCounters counters) {
        if (bot == null || pos == null) return false;
        if (isDoorwayReservedCell(pos)) return false;
        BlockPos feet = bot.getBlockPos();
        if (pos.equals(feet) || pos.equals(feet.up())) return false;

        if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > REACH_DISTANCE_SQ) {
            if (counters != null) counters.reachFailures++;
            return false;
        }
        Item item = selectBuildItem(bot);
        if (item == null) {
            if (counters != null) counters.noMaterials++;
            return false;
        }
        if (counters != null) counters.attemptedPlacements++;

        // Clear any non-air occupant at the target.
        // BotActions.placeBlockAt generally can't place into replaceable clutter (grass/flowers),
        // so if a blueprint cell is occupied we must break it first.
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        BlockState existing = world.getBlockState(pos);
        if (!existing.isAir() && (existing.isReplaceable() || existing.isIn(BlockTags.LEAVES))) {
            mineSoft(bot, pos);
            existing = world.getBlockState(pos);
        }

        // If it's still obstructed (non-air and not something we can replace), mine it.
        if (!existing.isAir() && !existing.isReplaceable()) {
            mineSoft(bot, pos);
        }

        net.shasankp000.Entity.LookController.faceBlock(bot, pos);
        boolean ok = BotActions.placeBlockAt(bot, pos, Direction.UP, List.of(item));
        if (ok && counters != null) counters.placedBlocks++;
        sleepQuiet(PLACEMENT_DELAY_MS);
        return ok;
    }

    private boolean pillarUp(ServerPlayerEntity bot, int steps, List<BlockPos> placed, boolean enforceNoRepeatBase) {
        if (steps <= 0) return true;
        ServerWorld world = (ServerWorld) bot.getEntityWorld();

        // Enforce: never scaffold in doorway clearance. Optionally enforce no-repeat base.
        BlockPos startFoot = bot.getBlockPos();
        if (isForbiddenScaffoldBase(startFoot) || (enforceNoRepeatBase && isScaffoldBaseUsed(startFoot))) {
            return false;
        }

        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) return false;
            if (handleHovelEmergency(bot, "pillar-up")) {
                return false;
            }
            // Kill horizontal drift before attempting a 1x1 tower step.
            BotActions.stop(bot);
            sleepQuiet(60L);

            if (!waitForOnGround(bot, 1200L)) {
                return false;
            }

            BlockPos target = bot.getBlockPos();
            double startY = bot.getY();
            BotActions.jump(bot);

            // Wait until we actually leave the ground and vacate the block space before placing into it.
            if (!waitForAirborne(bot, 800L)) {
                return false;
            }
            // Place near the jump apex (velocity transitions from + to -). This is the most reliable window.
            waitForJumpPlaceWindow(bot, startY, 900L);
            if (!isMissing(world, target)) mineSoft(bot, target);
            boolean ok = false;
            for (int attempt = 0; attempt < 3 && !ok; attempt++) {
                ok = BotActions.placeBlockAt(bot, target, Direction.UP, PILLAR_BLOCKS);
                if (!ok) {
                    sleepQuiet(80L);
                }
            }
            if (!ok) return false;

            // Record the base after the first successful placement.
            if (i == 0) {
                markScaffoldBaseUsed(startFoot);
            }
            if (placed != null) placed.add(target.toImmutable());
            waitForYIncrease(bot, target.getY(), 1000L);
            if (handleHovelEmergency(bot, "pillar-up-post")) {
                return false;
            }
        }
        return true;
    }

    private boolean waitForOnGround(ServerPlayerEntity bot, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }
            if (bot.isOnGround() || bot.isClimbing() || bot.isTouchingWater() || bot.isInLava()) {
                return true;
            }
            sleepQuiet(25L);
        }
        return bot.isOnGround();
    }

    private boolean waitForAirborne(ServerPlayerEntity bot, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }
            if (!bot.isOnGround()) {
                return true;
            }
            sleepQuiet(25L);
        }
        return !bot.isOnGround();
    }

    /**
     * Wait for a stable placement window during a vertical jump.
     *
     * <p>Heuristic:
     * <ul>
     *   <li>Ensure we've risen at least ~0.2 blocks (vacated the block space)</li>
     *   <li>Wait until vertical velocity is near apex (<= ~0.08), so we aren't still accelerating upward</li>
     * </ul>
     */
    private void waitForJumpPlaceWindow(ServerPlayerEntity bot, double startY, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            double y = bot.getY();
            double vy = bot.getVelocity().y;
            if (y >= startY + 0.20D && vy <= 0.08D) {
                return;
            }
            sleepQuiet(25L);
        }
    }

    private void teardownScaffolding(ServerPlayerEntity bot, List<BlockPos> placed, Set<BlockPos> keepBlocks) {
        teardownScaffoldingInternal(bot, placed, keepBlocks, null, null);
    }

    /**
     * Teardown variant that opportunistically places missing targets after each descent step.
     * This makes the bot far more thorough without extra movement/pathfinding.
     */
    private void teardownScaffolding(ServerPlayerEntity bot,
                                    List<BlockPos> placed,
                                    Set<BlockPos> keepBlocks,
                                    List<BlockPos> checkTargets,
                                    BuildCounters counters) {
        teardownScaffoldingInternal(bot, placed, keepBlocks, checkTargets, counters);
    }

    /**
     * Shared scaffold teardown implementation.
     *
     * <p>If {@code checkTargets} is provided, we opportunistically place missing targets during descent
     * (excluding targets that share the scaffold column X/Z), then do a final fill pass once we're down.</p>
     */
    private void teardownScaffoldingInternal(ServerPlayerEntity bot,
                                            List<BlockPos> placed,
                                            Set<BlockPos> keepBlocks,
                                            List<BlockPos> checkTargets,
                                            BuildCounters counters) {
        if (bot == null || placed == null || placed.isEmpty()) return;

        ServerWorld world = (ServerWorld) bot.getEntityWorld();

        // Derive pillar X/Z and vertical span. Used to stabilize teardown.
        int px = placed.get(0).getX();
        int pz = placed.get(0).getZ();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos p : placed) {
            if (p == null) continue;
            px = p.getX();
            pz = p.getZ();
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
        }
        final BlockPos expectedPerch = new BlockPos(px, maxY + 1, pz);

        List<BlockPos> descentTargets = null;
        if (checkTargets != null && !checkTargets.isEmpty()) {
            Set<BlockPos> scaffoldXZ = new HashSet<>();
            for (BlockPos p : placed) {
                if (p == null) continue;
                scaffoldXZ.add(canonicalXZ(p));
            }
            descentTargets = checkTargets.stream()
                    .filter(Objects::nonNull)
                    .filter(p -> {
                        BlockPos xz = canonicalXZ(p);
                        return xz == null || !scaffoldXZ.contains(xz);
                    })
                    .toList();
        }

        // IMPORTANT: You cannot mine a tall pillar from the top by iterating the list;
        // blocks near the bottom are out of reach. Tear down by "mining the block under feet"
        // so every break is within reach and we descend naturally.
        List<BlockPos> finalDescentTargets = descentTargets;
        withSneakLock(bot, () -> {
            // Ensure we're centered on the pillar before descending.
            // If the bot drifted even slightly, it can fail to find/break the block under feet and leave a full pillar.
            BlockPos snap = resolveStandablePerch(world, expectedPerch);
            if (snap != null && !isOnPillarTop(bot, snap)) {
                BotActions.stop(bot);
                BotActions.sneak(bot, true);
                bot.teleport(world,
                        snap.getX() + 0.5D,
                        snap.getY(),
                        snap.getZ() + 0.5D,
                        EnumSet.noneOf(PositionFlag.class),
                        bot.getYaw(),
                        bot.getPitch(),
                        true);
                bot.setVelocity(Vec3d.ZERO);
                sleepQuiet(80L);
            }

            // Work on a copy to avoid surprising callers.
            ArrayList<BlockPos> stack = new ArrayList<>(placed);
            // Top-down by construction order (pillarUp adds bottom->top).
            Collections.reverse(stack);

            // Hard safety limit to avoid infinite loops if something goes weird.
            int guard = Math.max(16, stack.size() * 4);
            while (guard-- > 0 && !stack.isEmpty() && !SkillManager.shouldAbortSkill(bot)) {
                // Prefer removing the block we are standing on (directly under feet).
                BlockPos underFeet = bot.getBlockPos().down();
                int idx = stack.indexOf(underFeet);
                if (idx >= 0) {
                    BlockPos pos = stack.remove(idx);
                    if (keepBlocks != null && keepBlocks.contains(pos)) {
                        // Can't break this one; stop descending via pillar teardown.
                        // We still try to remove reachable blocks below/around, but avoid risky movement.
                    } else if (!world.getBlockState(pos).isAir()) {
                        net.shasankp000.Entity.LookController.faceBlock(bot, pos);
                        mineSoft(bot, pos);
                        waitForYDecrease(bot, bot.getBlockY(), 900L);
                    }

                    // Each time we descend, re-check everything reachable, but avoid filling the scaffold column yet.
                    if (finalDescentTargets != null) {
                        placeUntilStalled(world, bot, finalDescentTargets, counters, 1);
                    }
                    continue;
                }

                // Otherwise, break the highest remaining block we can reach.
                BlockPos reachable = null;
                for (BlockPos p : stack) {
                    if (p == null) continue;
                    if (keepBlocks != null && keepBlocks.contains(p)) continue;
                    if (world.getBlockState(p).isAir()) continue;
                    if (BlockInteractionService.canInteract(bot, p, REACH_DISTANCE_SQ)) {
                        reachable = p;
                        break;
                    }
                }
                if (reachable == null) {
                    break;
                }
                stack.remove(reachable);
                net.shasankp000.Entity.LookController.faceBlock(bot, reachable);
                mineSoft(bot, reachable);
                sleepQuiet(60L);

                if (finalDescentTargets != null) {
                    placeUntilStalled(world, bot, finalDescentTargets, counters, 1);
                }
            }
        });

        // If teardown failed and this pillar is outside the hovel footprint, remember it for deterministic cleanup.
        if (activeBuildCenter != null && activeRadius > 0 && minY != Integer.MAX_VALUE && maxY != Integer.MIN_VALUE) {
            int dx = Math.abs(px - activeBuildCenter.getX());
            int dz = Math.abs(pz - activeBuildCenter.getZ());
            boolean outside = dx > activeRadius || dz > activeRadius;
            if (outside) {
                int remaining = 0;
                for (BlockPos p : placed) {
                    if (p == null) continue;
                    if (keepBlocks != null && keepBlocks.contains(p)) continue;
                    if (!world.getBlockState(p).isAir()) {
                        remaining++;
                    }
                }
                if (remaining >= 3) {
                    RoofPillar pending = new RoofPillar(new BlockPos(px, minY, pz), maxY + 1);
                    pendingRoofPillars.add(pending);
                    buildStateStore.persistPendingRoofPillars(pendingRoofPillars);
                    LOGGER.info("Hovel: recorded pending roof pillar from teardown (base={}, topY={}, remainingBlocks={})",
                            pending.base().toShortString(), pending.topY(), remaining);
                }
            }
        }

        // After we're down, allow filling any remaining targets (including the former scaffold column).
        if (checkTargets != null && !checkTargets.isEmpty()) {
            placeUntilStalled(world, bot, checkTargets, counters, 2);
        }
    }

    private boolean isMissing(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW) || state.isOf(Blocks.SNOW_BLOCK);
    }

    private boolean shouldFillHoleForLeveling(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isIn(BlockTags.LEAVES)) {
            return true;
        }
        if (state.isOf(Blocks.SNOW) || state.isOf(Blocks.SNOW_BLOCK) || state.isIn(BlockTags.WOOL_CARPETS)) {
            return true;
        }
        if (!world.getFluidState(pos).isEmpty()) {
            return true;
        }
        if (state.isReplaceable()) {
            return true;
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    @SuppressWarnings("unused")
    private boolean isSupportHole(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isIn(BlockTags.LEAVES)) {
            return true;
        }
        if (state.isOf(Blocks.SNOW) || state.isIn(BlockTags.WOOL_CARPETS)) {
            return false;
        }
        return state.isReplaceable();
    }

    /**
     * Roof completion that prefers real-world-style scaffolding: build a short-lived tower,
     * work from the top to place many roof blocks, then dismantle the tower.
     *
     * This avoids per-block pathfinding to roof-level positions (which is slow and often fails).
     */
    private void patchRoofWithScaffolds(ServerWorld world,
                                       ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       BlockPos center,
                                       int radius,
                                       int wallHeight,
                                       List<BlockPos> roofBlueprint,
                                       BuildCounters counters) {
        int roofY = center.getY() + wallHeight;
        int standY = center.getY() + 1;
        if (countMissing(world, roofBlueprint) == 0) {
            return;
        }

        int ringRadius = perimeterRingRadius(radius);

        // 0) Corner-first: always put up towers near the four OUTSIDE corners.
        // This tends to cover the largest roof area early, while avoiding interior-corner "spam pillars".
        // Build from a diagonal outside stance (better aim on corner roof blocks).
        int outside = ringRadius;
        Set<BlockPos> cornerBaseSet = new LinkedHashSet<>();
        cornerBaseSet.add(center.add(outside, 0, outside));
        cornerBaseSet.add(center.add(-outside, 0, outside));
        cornerBaseSet.add(center.add(-outside, 0, -outside));
        cornerBaseSet.add(center.add(outside, 0, -outside));
        List<BlockPos> cornerBases = new ArrayList<>(cornerBaseSet);
        for (BlockPos base0 : cornerBases) {
            if (SkillManager.shouldAbortSkill(bot)) return;
            if (countMissing(world, roofBlueprint) == 0) return;

            int sx = Integer.signum(base0.getX() - center.getX());
            int sz = Integer.signum(base0.getZ() - center.getZ());
            BlockPos ideal = base0.withY(standY);
            BlockPos base = findNearbyStandableFiltered(world, ideal, 4, (p) -> {
                if (isForbiddenScaffoldBase(p)) return false;
                int dx = p.getX() - center.getX();
                int dz = p.getZ() - center.getZ();
                // Enforce diagonal outside stance relative to the center.
                if (Integer.signum(dx) != sx || Integer.signum(dz) != sz) return false;
                return Math.abs(dx) >= radius + 1 && Math.abs(dz) >= radius + 1;
            });
            if (base == null) continue;
            if (isScaffoldBaseUsed(base)) continue;
            ensureRingStandable(world, bot, base);
            if (!moveToRingPos(source, bot, base)) continue;

            scaffoldPlaceRoofWalkAndTeardown(world, source, bot, center, radius, roofY,
                    roofBlueprint, roofY + 2, counters, Collections.emptySet(), true);
        }

        // 1) Quick coverage from outside ring positions (cheap and usually accessible)
        List<BlockPos> ringBases = List.of(
            center.add(ringRadius, 0, ringRadius),
            center.add(-ringRadius, 0, ringRadius),
            center.add(-ringRadius, 0, -ringRadius),
            center.add(ringRadius, 0, -ringRadius),
            center.add(ringRadius, 0, 0),
            center.add(-ringRadius, 0, 0),
            center.add(0, 0, ringRadius),
            center.add(0, 0, -ringRadius)
        );

        for (BlockPos base0 : ringBases) {
            if (SkillManager.shouldAbortSkill(bot)) return;
            if (countMissing(world, roofBlueprint) == 0) return;

            BlockPos base = findNearbyStandableFiltered(world, base0.withY(standY), 4,
                    (p) -> !isForbiddenScaffoldBase(p));
            if (base == null) continue;
                if (isScaffoldBaseUsed(base)) continue;
            ensureRingStandable(world, bot, base);
            if (!moveToRingPos(source, bot, base)) continue;

            // Stand ABOVE the roof plane. This reduces "corner hopping" and gives better angles.
            scaffoldPlaceRoofWalkAndTeardown(world, source, bot, center, radius, roofY,
                    roofBlueprint, roofY + 2, counters, Collections.emptySet(), true);
        }

        if (countMissing(world, roofBlueprint) == 0) {
            return;
        }

        // 2) Targeted internal scaffolds: build where the roof is actually missing.
        // Keep any scaffold blocks that intersect the roof plane so we don't re-open holes.
        Set<BlockPos> keepIfRoof = new HashSet<>(roofBlueprint);

        int attempts = 0;
        while (attempts < 12 && countMissing(world, roofBlueprint) > 0) {
            if (SkillManager.shouldAbortSkill(bot)) return;
            attempts++;

            BlockPos target = findNearestMissing(world, bot, roofBlueprint);
            if (target == null) return;

            // Prefer building scaffolds from OUTSIDE the hovel. Picking an interior base near a corner roof
            // block often leads to repeated "stuck" pillars where the wall blocks the click/support.
            int dx = target.getX() - center.getX();
            int dz = target.getZ() - center.getZ();
            Direction side;
            if (Math.abs(dx) >= Math.abs(dz)) {
                side = dx >= 0 ? Direction.EAST : Direction.WEST;
            } else {
                side = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
            }

            BlockPos exteriorSeed;
            if (side == Direction.EAST || side == Direction.WEST) {
                exteriorSeed = new BlockPos(center.getX() + (side == Direction.EAST ? ringRadius : -ringRadius),
                        standY,
                        target.getZ());
            } else {
                exteriorSeed = new BlockPos(target.getX(),
                        standY,
                    center.getZ() + (side == Direction.SOUTH ? ringRadius : -ringRadius));
            }

                BlockPos base = findNearbyStandableFiltered(world, exteriorSeed, 6,
                    (p) -> !isForbiddenScaffoldBase(p));
            if (base == null) {
                // Fall back to any outside ring spot.
                base = findNearbyStandableFiltered(world, center.offset(side, ringRadius).withY(standY), 6,
                    (p) -> !isForbiddenScaffoldBase(p));
            }
            if (base == null) {
                return;
            }
            if (isScaffoldBaseUsed(base)) {
                continue;
            }

            ensureRingStandable(world, bot, base);
            if (!moveToRingPos(source, bot, base)) {
                continue;
            }
            scaffoldPlaceRoofWalkAndTeardown(world, source, bot, center, radius, roofY,
                    roofBlueprint, roofY + 2, counters, keepIfRoof, true);
        }
    }

    @SuppressWarnings("unused")
    private int roofWalkMissingThreshold(int radius) {
        // Allow roof-walking only when the roof is mostly complete. This reduces the odds of stepping into holes.
        int r = Math.max(2, radius);
        return Math.min(32, Math.max(10, (2 * r + 1) * 2));
    }

    private record RoofPatchStep(BlockPos stand, BlockPos target) {
    }

    private RoofPatchStep findNearestRoofPatchStep(ServerWorld world,
                                                  ServerPlayerEntity bot,
                                                  BlockPos center,
                                                  int radius,
                                                  int roofY,
                                                  List<BlockPos> roofBlueprint) {
        if (world == null || bot == null || center == null || radius <= 0 || roofBlueprint == null || roofBlueprint.isEmpty()) {
            return null;
        }

        Vec3d here = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        RoofPatchStep best = null;
        double bestSq = Double.MAX_VALUE;

        for (BlockPos target : roofBlueprint) {
            if (target == null || target.getY() != roofY || !isMissing(world, target)) {
                continue;
            }

            // Find a neighboring roof block that exists so we can stand on it (instead of trying to stand on the hole).
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos neighborRoof = target.offset(d);
                if (neighborRoof == null) continue;

                int dx = Math.abs(neighborRoof.getX() - center.getX());
                int dz = Math.abs(neighborRoof.getZ() - center.getZ());
                if (dx > radius || dz > radius) {
                    continue;
                }

                if (isMissing(world, neighborRoof)) {
                    continue;
                }
                BlockPos stand = neighborRoof.up();
                if (!isStandable(world, stand)) {
                    continue;
                }

                double distSq = here.squaredDistanceTo(Vec3d.ofCenter(stand));
                if (distSq < bestSq) {
                    bestSq = distSq;
                    best = new RoofPatchStep(stand.toImmutable(), target.toImmutable());
                }
            }
        }

        return best;
    }

    private boolean moveOnRoofToStand(ServerWorld world,
                                     ServerCommandSource source,
                                     ServerPlayerEntity bot,
                                     BlockPos stand,
                                     long timeoutMs) {
        if (world == null || bot == null || stand == null) {
            return false;
        }
        if (!isStandable(world, stand)) {
            return false;
        }

        double distSq = bot.squaredDistanceTo(stand.getX() + 0.5D, stand.getY() + 0.5D, stand.getZ() + 0.5D);

        // Roof-walking is safety-critical: prefer local nudges for short hops.
        // This reduces the chance of any higher-level planner deciding to detour off the roof.
        if (distSq <= 9.0D) {
            if (nudgeToStandWithJump(world, bot, stand, Math.min(timeoutMs, 1500L))) {
                return true;
            }
        }

        // For longer moves, try the movement service with local intent.
        if (source != null && distSq <= 18.0D * 18.0D) {
            try {
                MovementService.MovementPlan plan = new MovementService.MovementPlan(
                        MovementService.Mode.DIRECT,
                        stand,
                        stand,
                        null,
                        null,
                        Direction.UP
                );
                // No snap/teleport while roof-walking.
                MovementService.MovementResult result = MovementService.withoutDoorEscape(
                    () -> MovementService.execute(source, bot, plan, false, true, true, false)
                );
                if (result.success()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }

        return nudgeToStandWithJump(world, bot, stand, timeoutMs);
    }

    private void tryRoofWalkPatch(ServerWorld world,
                                 ServerCommandSource source,
                                 ServerPlayerEntity bot,
                                 BlockPos center,
                                 int radius,
                                 int roofY,
                                 List<BlockPos> roofBlueprint,
                                 BuildCounters counters,
                                 long timeoutMs) {
        if (world == null || bot == null || center == null || roofBlueprint == null || roofBlueprint.isEmpty()) {
            return;
        }

        int missing0 = countMissing(world, roofBlueprint);
        if (missing0 <= 0) {
            return;
        }
        // NOTE: We intentionally do NOT hard-gate roof-walking by "missing count".
        // In practice, the old threshold caused roof-walking to never trigger on uneven terrain.
        // Safety is enforced instead by only stepping onto confirmed solid roof blocks.

        long deadline = System.currentTimeMillis() + Math.max(1500L, timeoutMs);

        // First, step onto the roof surface (stand on an existing roof block).
        BlockPos entry = null;
        BlockPos here = bot.getBlockPos();
        for (int dx = -6; dx <= 6 && entry == null; dx++) {
            for (int dz = -6; dz <= 6 && entry == null; dz++) {
                BlockPos roof = new BlockPos(here.getX() + dx, roofY, here.getZ() + dz);
                int ax = Math.abs(roof.getX() - center.getX());
                int az = Math.abs(roof.getZ() - center.getZ());
                if (ax > radius || az > radius) continue;
                if (isMissing(world, roof)) continue;
                BlockPos stand = roof.up();
                if (isStandable(world, stand)) {
                    entry = stand.toImmutable();
                }
            }
        }

        if (entry == null) {
            return;
        }

        if (!moveOnRoofToStand(world, source, bot, entry, 3200L)) {
            return;
        }

        // Deterministic roof traversal (serpentine) to ensure we actually cover the whole roof.
        // This avoids greedy "best station" loops that can miss corners or terminate early.
        List<BlockPos> route = HovelRoofWalkService.buildSerpentineRouteFromNearest(
                center, radius, roofY, bot.getBlockPos());

        int lastMissing = countMissing(world, roofBlueprint);
        int noProgressSteps = 0;
        int visited = 0;

        // Prime: do one good placement pass from the entry.
        placeUntilStalled(world, bot, roofBlueprint, counters, 4);
        lastMissing = countMissing(world, roofBlueprint);
        if (lastMissing <= 0) {
            return;
        }

        for (BlockPos roofPos : route) {
            if (roofPos == null) continue;
            if (System.currentTimeMillis() >= deadline || SkillManager.shouldAbortSkill(bot)) {
                return;
            }

            int missingNow = countMissing(world, roofBlueprint);
            if (missingNow <= 0) {
                return;
            }

            // Try to place the current roof cell (helps maintain traversal continuity).
            if (isMissing(world, roofPos) && hasPlacementSupport(world, roofPos)) {
                placeBlockDirectIfWithinReach(bot, roofPos, counters);
            }

            // Walk to the stand cell above the roof block if it's solid.
            if (!isMissing(world, roofPos)) {
                BlockPos stand = roofPos.up();
                if (isStandable(world, stand)) {
                    moveOnRoofToStand(world, source, bot, stand, 2200L);
                }
            }

            // Opportunistically place from each new angle.
            placeUntilStalled(world, bot, roofBlueprint, counters, 2);

            int after = countMissing(world, roofBlueprint);
            if (after < lastMissing) {
                lastMissing = after;
                noProgressSteps = 0;
            } else {
                noProgressSteps++;
            }

            if (++visited >= 400 || noProgressSteps >= 40) {
                break;
            }
        }

        // Targeted cleanup: walk next to specific missing cells and try to place them.
        int guard = 0;
        int last = countMissing(world, roofBlueprint);
        while (System.currentTimeMillis() < deadline && !SkillManager.shouldAbortSkill(bot) && guard++ < 64) {
            int missing = countMissing(world, roofBlueprint);
            if (missing <= 0) {
                return;
            }

            RoofPatchStep step = findNearestRoofPatchStep(world, bot, center, radius, roofY, roofBlueprint);
            if (step == null) {
                return;
            }
            if (!moveOnRoofToStand(world, source, bot, step.stand(), 4200L)) {
                return;
            }
            placeUntilStalled(world, bot, roofBlueprint, counters, 6);

            int now = countMissing(world, roofBlueprint);
            if (now >= last) {
                // No progress from targeted patching; bail to avoid thrashing.
                return;
            }
            last = now;
        }
    }

    private void scaffoldPlaceRoofWalkAndTeardown(ServerWorld world,
                                                 ServerCommandSource source,
                                                 ServerPlayerEntity bot,
                                                 BlockPos center,
                                                 int radius,
                                                 int roofY,
                                                 List<BlockPos> roofBlueprint,
                                                 int standY,
                                                 BuildCounters counters,
                                                 Set<BlockPos> keepBlocks,
                                                 boolean enforceNoRepeatBase) {
        withSneakLock(bot, () -> {
            int steps = standY - bot.getBlockY();
            if (steps <= 0) {
                placeUntilStalled(world, bot, roofBlueprint, counters, 2);
                return;
            }

            List<BlockPos> pillar = new ArrayList<>();
            if (!pillarUp(bot, steps, pillar, enforceNoRepeatBase)) {
                teardownScaffolding(bot, pillar, keepBlocks);
                return;
            }

            try {
                placeUntilStalled(world, bot, roofBlueprint, counters, 3);

                // Optional roof walking: patch a few final holes faster, but ALWAYS return to this pillar
                // (teardown will snap back to the pillar top first).
                if (source != null && !SkillManager.shouldAbortSkill(bot)) {
                    int missing = countMissing(world, roofBlueprint);
                    long budget = 12_000L + (long) Math.min(20_000L, missing * 550L);
                    tryRoofWalkPatch(world, source, bot, center, radius, roofY, roofBlueprint, counters, budget);
                }
            } finally {
                teardownScaffolding(bot, pillar, keepBlocks, roofBlueprint, counters);
            }
        });
    }

    private void scaffoldPlaceAndTeardown(ServerWorld world,
                                         ServerPlayerEntity bot,
                                         List<BlockPos> targets,
                                         int standY,
                                         BuildCounters counters,
                                         Set<BlockPos> keepBlocks,
                                         boolean enforceNoRepeatBase) {
        withSneakLock(bot, () -> {
            int steps = standY - bot.getBlockY();
            if (steps <= 0) {
                placeUntilStalled(world, bot, targets, counters, 2);
                return;
            }

            List<BlockPos> pillar = new ArrayList<>();
            if (!pillarUp(bot, steps, pillar, enforceNoRepeatBase)) {
                teardownScaffolding(bot, pillar, keepBlocks);
                return;
            }

            try {
                placeUntilStalled(world, bot, targets, counters, 3);
            } finally {
                teardownScaffolding(bot, pillar, keepBlocks, targets, counters);
            }
        });
    }

    private int placeManyWithinReachCount(ServerWorld world,
                                          ServerPlayerEntity bot,
                                          List<BlockPos> targets,
                                          BuildCounters counters) {
        Vec3d eye = bot.getEyePos();
        // IMPORTANT ordering:
        // - Place lower-Y blocks first (prevents "floating" slits where upper blocks fail due to missing supports)
        // - Within the same Y, place closer blocks first to reduce half-placed rings.
        // Pre-score support so we place "edge" blocks first (helps fill larger holes by growing supports inward).
        Map<BlockPos, Integer> supportScore = new HashMap<>();

        List<BlockPos> inRange = targets.stream()
            .filter(p -> isMissing(world, p))
            .filter(p -> !isDoorwayReservedCell(p))
            .filter(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p)) <= REACH_DISTANCE_SQ)
            .peek(p -> supportScore.put(p, placementSupportScore(world, p)))
            .sorted(Comparator
                // IMPORTANT: lower-Y blocks first (prevents floating slits).
                .comparingInt(BlockPos::getY)
                // Within the same layer: place blocks with more/stronger supports first.
                .thenComparingInt((BlockPos p) -> -supportScore.getOrDefault(p, 0))
                // Finally: closer blocks first.
                .thenComparingDouble(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p))))
            .toList();

        int placed = 0;
        for (BlockPos p : inRange) {
            if (SkillManager.shouldAbortSkill(bot)) return placed;
            if (bot.isInsideWall() && handleHovelEmergency(bot, "place-many")) {
                return placed;
            }
            if (placeBlockDirectIfWithinReach(bot, p, counters)) {
                placed++;
            }
        }
        return placed;
    }

    private int placementSupportScore(ServerWorld world, BlockPos target) {
        if (world == null || target == null) {
            return 0;
        }
        int score = 0;
        // Prefer a solid block below (best-case support).
        if (isClickablePlacementSupport(world, target.down())) {
            score += 4;
        }
        // Count horizontal solid neighbors.
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (isClickablePlacementSupport(world, target.offset(d))) {
                score += 2;
            }
        }
        return score;
    }

    private boolean hasPlacementSupport(ServerWorld world, BlockPos target) {
        if (world == null || target == null) {
            return false;
        }
        if (isClickablePlacementSupport(world, target.down())) {
            return true;
        }
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (isClickablePlacementSupport(world, target.offset(d))) {
                return true;
            }
        }
        return false;
    }

    private boolean isClickablePlacementSupport(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.isReplaceable()) {
            return false;
        }
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    /**
     * Place repeatedly from the current stance until no more progress is made.
     * This avoids "build a whole new pillar for one hole" and makes roof passes more thorough.
     */
    private void placeUntilStalled(ServerWorld world,
                                   ServerPlayerEntity bot,
                                   List<BlockPos> targets,
                                   BuildCounters counters,
                                   int maxPasses) {
        int passes = Math.max(1, maxPasses);
        for (int i = 0; i < passes; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (handleHovelEmergency(bot, "place-until-stalled")) {
                return;
            }
            int placed = placeManyWithinReachCount(world, bot, targets, counters);
            if (placed <= 0) {
                return;
            }
        }
    }

    private BlockPos findNearbyStandable(ServerWorld world, BlockPos ideal, int searchRadius) {
        if (world == null || ideal == null) return null;
        int r = Math.max(0, searchRadius);
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;

        // Allow a bit more vertical variance to cope with uneven terrain outside the build site.
        for (BlockPos p : BlockPos.iterate(ideal.add(-r, -5, -r), ideal.add(r, 5, r))) {
            if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
            if (!isStandable(world, p)) continue;
            // Prefer staying close in X/Z to the intended seed (drifting sideways reduces reach).
            // Still allow some vertical variance for uneven terrain.
            int dx = p.getX() - ideal.getX();
            int dz = p.getZ() - ideal.getZ();
            int dy = p.getY() - ideal.getY();
            double sq = (double) (dx * dx + dz * dz) + 0.20D * (double) (dy * dy);
            if (sq < bestSq) {
                bestSq = sq;
                best = p.toImmutable();
            }
        }
        return best;
    }

    private BlockPos findNearbyStandableFiltered(ServerWorld world,
                                                 BlockPos ideal,
                                                 int searchRadius,
                                                 java.util.function.Predicate<BlockPos> accept) {
        if (world == null || ideal == null) return null;
        int r = Math.max(0, searchRadius);
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;

        // Allow a bit more vertical variance to cope with uneven terrain outside the build site.
        for (BlockPos p : BlockPos.iterate(ideal.add(-r, -5, -r), ideal.add(r, 5, r))) {
            if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
            if (accept != null && !accept.test(p)) continue;
            if (!isStandable(world, p)) continue;

            int dx = p.getX() - ideal.getX();
            int dz = p.getZ() - ideal.getZ();
            int dy = p.getY() - ideal.getY();
            double sq = (double) (dx * dx + dz * dz) + 0.20D * (double) (dy * dy);
            if (sq < bestSq) {
                bestSq = sq;
                best = p.toImmutable();
            }
        }
        return best;
    }

    private boolean isStandable(ServerWorld world, BlockPos foot) {
        if (world == null || foot == null) return false;
        if (!world.getFluidState(foot).isEmpty() || !world.getFluidState(foot.up()).isEmpty()) return false;

        BlockPos below = foot.down();
        BlockState belowState = world.getBlockState(below);
        if (belowState.getCollisionShape(world, below).isEmpty()) return false;

        BlockState footState = world.getBlockState(foot);
        if (!footState.getCollisionShape(world, foot).isEmpty()) return false;

        BlockState headState = world.getBlockState(foot.up());
        return headState.getCollisionShape(world, foot.up()).isEmpty();
    }

    private int countMissing(ServerWorld world, List<BlockPos> blueprint) {
        return (int) blueprint.stream().filter(p -> isMissing(world, p)).count();
    }

    private static double distSqXZ(BlockPos a, BlockPos b) {
        return HovelGeometryService.distSqXZ(a, b);
    }

    private int perimeterRingRadius(int radius) {
        return HovelPerimeterRoutingService.perimeterRingRadius(radius, PERIMETER_RING_OFFSET);
    }

    private boolean isInsideFootprint(BlockPos pos, BlockPos center, int radius) {
        return HovelGeometryService.isInsideFootprint(pos, center, radius);
    }

    private boolean isOutsideFootprint(BlockPos pos, BlockPos center, int radius) {
        return HovelGeometryService.isOutsideFootprint(pos, center, radius);
    }

    private Direction resolveDoorSideForExit(ServerPlayerEntity bot) {
        return HovelDoorAccessService.resolveDoorSideForExit(activeDoorSide, bot, activeBuildCenter, activeRadius);
    }

    private boolean ensureExteriorAccessForDestination(ServerWorld world,
                                                       ServerCommandSource source,
                                                       ServerPlayerEntity bot,
                                                       BlockPos destination,
                                                       BlockPos center,
                                                       int radius) {
        if (world == null || source == null || bot == null || destination == null || center == null || radius <= 0) {
            return false;
        }
        if (!isInsideFootprint(bot.getBlockPos(), center, radius)) {
            return true;
        }
        if (!isOutsideFootprint(destination, center, radius)) {
            return true;
        }
        Direction doorSide = resolveDoorSideForExit(bot);
        return exitInteriorViaDoor(world, source, bot, center, radius, doorSide);
    }

    private boolean ensureInteriorAccessForDestination(ServerWorld world,
                                                       ServerCommandSource source,
                                                       ServerPlayerEntity bot,
                                                       BlockPos destination,
                                                       BlockPos center,
                                                       int radius,
                                                       Direction doorSide) {
        if (world == null || source == null || bot == null || destination == null || center == null || radius <= 0) {
            return false;
        }
        if (isInsideFootprint(bot.getBlockPos(), center, radius)) {
            return true;
        }
        if (!isInsideFootprint(destination, center, radius)) {
            return true;
        }
        Direction side = doorSide != null ? doorSide : resolveDoorSideForExit(bot);
        return enterInteriorViaDoor(world, source, bot, center, radius, side);
    }

    private boolean enterInteriorViaDoor(ServerWorld world,
                                         ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         BlockPos center,
                                         int radius,
                                         Direction doorSide) {
        return HovelDoorAccessService.enterInteriorViaDoor(
                world,
                source,
                bot,
                center,
                radius,
                doorSide,
                REACH_DISTANCE_SQ,
                this::findNearbyStandable,
                this::moveToRingPosFast,
                this::directMove,
                this::pathMove,
                this::mineSoft
        );
    }

    private boolean exitInteriorViaDoor(ServerWorld world,
                                        ServerCommandSource source,
                                        ServerPlayerEntity bot,
                                        BlockPos center,
                                        int radius,
                                        Direction doorSide) {
        return HovelDoorAccessService.exitInteriorViaDoor(
                world,
                source,
                bot,
                center,
                radius,
                doorSide,
                REACH_DISTANCE_SQ,
                this::findNearbyStandable,
                this::moveToBuildSiteAllowPathing,
                this::directMove,
                this::pathMove,
                this::ensureRingStandable,
                this::mineSoft
        );
    }

    @SuppressWarnings("unused")
    private void clearDoorwayNearby(ServerWorld world,
                                    ServerPlayerEntity bot,
                                    BlockPos center,
                                    int radius,
                                    Direction doorSide) {
        HovelDoorAccessService.clearDoorwayNearby(
                world,
                bot,
                center,
                radius,
                doorSide,
                REACH_DISTANCE_SQ,
                this::mineSoft
        );
    }

    /**
     * Walk the exterior perimeter loop (the "red block" route) to reach a destination around the corner.
     *
     * Motivation: close-range DIRECT walking tends to try to cut through partially-built walls, causing oscillation.
     * Perimeter routing converts the move into a few short, unobstructed hops along the build site's outer ring.
     */
    private boolean moveToViaPerimeterLoop(ServerWorld world,
                                          ServerCommandSource source,
                                          ServerPlayerEntity bot,
                                          BlockPos destination) {
        if (world == null || source == null || bot == null || destination == null) {
            return false;
        }
        if (activeBuildCenter == null || activeRadius <= 0) {
            return false;
        }
        if (!ensureExteriorAccessForDestination(world, source, bot, destination, activeBuildCenter, activeRadius)) {
            return false;
        }

        return HovelPerimeterRoutingService.moveToViaPerimeterLoop(
                world,
                source,
                bot,
                destination,
                activeBuildCenter,
                activeRadius,
                PERIMETER_RING_OFFSET,
                this::findNearbyStandable,
                this::ensureRingStandable,
                this::moveToRingWaypoint
        );
    }

    private boolean directMove(ServerCommandSource source, ServerPlayerEntity bot, BlockPos dest) {
        if (source == null || bot == null || dest == null) return false;
        if (HovelEmergencyRegroupService.triggerDeepUndergroundRegroupIfNeeded(
                LOGGER, source, bot, "direct-move", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        if (HovelEmergencyRegroupService.triggerInWallRegroupIfNeeded(
                LOGGER, source, bot, "direct-move", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        MovementService.MovementPlan direct = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                dest,
                dest,
                null,
                null,
                Direction.UP
        );
        MovementService.MovementResult res = MovementService.withoutDoorEscape(
            () -> MovementService.execute(source, bot, direct, false, true, false, false)
        );
        return res.success();
    }

    private boolean pathMove(ServerCommandSource source, ServerPlayerEntity bot, BlockPos dest) {
        if (source == null || bot == null || dest == null) return false;
        if (HovelEmergencyRegroupService.triggerDeepUndergroundRegroupIfNeeded(
                LOGGER, source, bot, "path-move", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        if (HovelEmergencyRegroupService.triggerInWallRegroupIfNeeded(
                LOGGER, source, bot, "path-move", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        var planOpt = MovementService.planLootApproach(bot, dest, MovementService.MovementOptions.skillLoot());
        return planOpt.isPresent() && MovementService.withoutDoorEscape(
            () -> MovementService.execute(source, bot, planOpt.get(), false, true, false, false)
        ).success();
    }

    private boolean moveToRingWaypoint(ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       BlockPos ringPos,
                                       boolean allowDirectFallback) {
        if (source == null || bot == null || ringPos == null) return false;
        if (HovelEmergencyRegroupService.triggerDeepUndergroundRegroupIfNeeded(
                LOGGER, source, bot, "ring-waypoint", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        if (HovelEmergencyRegroupService.triggerInWallRegroupIfNeeded(
                LOGGER, source, bot, "ring-waypoint", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(ringPos) <= 1.0D) return true;
        if (pathMove(source, bot, ringPos)) {
            return true;
        }
        if (allowDirectFallback && directMove(source, bot, ringPos)) {
            return true;
        }

        // Perimeter safety fallback: if the path/dir move failed, clear a small column and head/shoulder blocks
        // at the intended ring stand (common corner-hump / suffocation case), then retry once.
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (world != null && activeBuildCenter != null && activeRadius > 0
                && isOutsideFootprint(ringPos, activeBuildCenter, activeRadius)) {
            boolean mined = HovelPerimeterSafetyService.tryClearStandColumn(world, bot, ringPos, REACH_DISTANCE_SQ, this::mineSoft);
            if (mined) {
                sleepQuiet(60L);
                if (pathMove(source, bot, ringPos)) {
                    return true;
                }
                if (allowDirectFallback && directMove(source, bot, ringPos)) {
                    return true;
                }
            }
        }
        return world != null && nudgeToStandWithJump(world, bot, ringPos, 2200L);
    }

    private boolean moveToRingPos(ServerCommandSource source, ServerPlayerEntity bot, BlockPos ringPos) {
        if (source == null || bot == null || ringPos == null) return false;
        if (HovelEmergencyRegroupService.triggerDeepUndergroundRegroupIfNeeded(
                LOGGER, source, bot, "ring-pos", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        if (HovelEmergencyRegroupService.triggerInWallRegroupIfNeeded(
                LOGGER, source, bot, "ring-pos", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(ringPos) <= 1.0D) return true;

        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (world != null && activeBuildCenter != null && activeRadius > 0) {
            if (!ensureExteriorAccessForDestination(world, source, bot, ringPos, activeBuildCenter, activeRadius)) {
                return false;
            }
            if (shouldUsePerimeterRouting(ringPos) && moveToViaPerimeterLoop(world, source, bot, ringPos)) {
                return moveToRingWaypoint(source, bot, ringPos, true);
            }
        }
        return moveToRingWaypoint(source, bot, ringPos, true);
    }

    // (Deep-underground emergency regroup logic extracted to HovelEmergencyRegroupService.)

    /**
     * Exterior ring movement uses the path planner and doorway exits.
     */
    private boolean moveToRingPosFast(ServerCommandSource source, ServerPlayerEntity bot, BlockPos ringPos) {
        return moveToRingPos(source, bot, ringPos);
    }

    /**
     * Prepare the bot's current standing cell for safe scaffolding.
     *
     * Must be called AFTER moving to the station, because it places/mines relative to the bot.
     */
    private void prepareScaffoldBase(ServerWorld world, ServerPlayerEntity bot) {
        if (world == null || bot == null) {
            return;
        }
        // Kill drift (important before 1x1 pillar steps).
        BotActions.stop(bot);
        BotActions.sprint(bot, false);
        sleepQuiet(60L);

        BlockPos foot = bot.getBlockPos();
        if (isForbiddenScaffoldBase(foot)) {
            return;
        }
        BlockPos below = foot.down();
        // Only place a support if the block below has no collision (true hole).
        // This prevents replacing snow layers / leaves with random support blocks.
        if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
            BotActions.placeBlockAt(bot, below, Direction.UP, PILLAR_BLOCKS);
            sleepQuiet(40L);
        }
        // Clear foot + head space only if collision would obstruct movement.
        // Avoid mining tall grass / other non-colliding clutter.
        if (!world.getBlockState(foot).getCollisionShape(world, foot).isEmpty()) {
            mineSoft(bot, foot);
        }
        if (!world.getBlockState(foot.up()).getCollisionShape(world, foot.up()).isEmpty()) {
            mineSoft(bot, foot.up());
        }
        sleepQuiet(40L);
    }

    private void ensureRingStandable(ServerWorld world, ServerPlayerEntity bot, BlockPos ringPos) {
        // NOTE: we intentionally do NOT place a support block here.
        // Bases should already be standable (via findNearbyStandable). Placing supports here can create
        // stray exterior blocks (especially on snow layers).
        clearPendingRoofPillarAt(world, bot, ringPos);
        if (!world.getBlockState(ringPos).getCollisionShape(world, ringPos).isEmpty()) mineSoft(bot, ringPos);
        if (!world.getBlockState(ringPos.up()).getCollisionShape(world, ringPos.up()).isEmpty()) mineSoft(bot, ringPos.up());
        if (!world.getBlockState(ringPos.up(2)).getCollisionShape(world, ringPos.up(2)).isEmpty()) mineSoft(bot, ringPos.up(2));
        if (!world.getBlockState(ringPos.up(3)).getCollisionShape(world, ringPos.up(3)).isEmpty()) mineSoft(bot, ringPos.up(3));
    }

    private boolean nudgeToStandWithJump(ServerWorld world, ServerPlayerEntity bot, BlockPos stand, long timeoutMs) {
        if (world == null || bot == null || stand == null) {
            return false;
        }
        if (!isStandable(world, stand)) {
            return false;
        }
        Vec3d target = Vec3d.ofCenter(stand);
        long deadline = System.currentTimeMillis() + Math.max(600L, timeoutMs);
        double lastDist = Double.MAX_VALUE;
        int stallTicks = 0;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }
            Vec3d pos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
            double dx = pos.x - target.x;
            double dz = pos.z - target.z;
            double distSq = dx * dx + dz * dz;
            if (distSq <= 0.16D) {
                return true;
            }
            BotActions.moveToward(bot, target, 0.45);
            if (stand.getY() > bot.getBlockY() && distSq <= 2.25D && bot.isOnGround()) {
                BotActions.jump(bot);
            }
            if (distSq >= lastDist - 0.01D) {
                stallTicks++;
                if (stallTicks > 8 && stand.getY() > bot.getBlockY() && bot.isOnGround()) {
                    BotActions.jumpForward(bot);
                }
            } else {
                stallTicks = 0;
            }
            lastDist = distSq;
            sleepQuiet(80L);
        }
        Vec3d pos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double dx = pos.x - target.x;
        double dz = pos.z - target.z;
        return (dx * dx + dz * dz) <= 0.25D;
    }

    private void levelBuildSite(ServerWorld world,
                               ServerCommandSource source,
                               ServerPlayerEntity bot,
                               BlockPos center,
                               int radius,
                               int wallHeight,
                               BuildCounters counters) {
        if (world == null || bot == null || center == null) {
            return;
        }

        if (Thread.currentThread().isInterrupted()) {
            logStageWarn("leveling", "thread already interrupted before leveling loop");
        }

        int floorY = center.getY();
        int standY = floorY + 1;
        int inner = Math.max(0, radius - 1);
        int outerBuffer = radius + 1;
        int outerTravelRing = perimeterRingRadius(radius);

        // Leveling must MOVE around; otherwise almost everything is out of reach.
        // Use a small 3x3 grid of interior stations.
        LinkedHashSet<BlockPos> stationSeeds = new LinkedHashSet<>();
        for (int sx : new int[] { -inner, 0, inner }) {
            for (int sz : new int[] { -inner, 0, inner }) {
                stationSeeds.add(center.add(sx, 0, sz));
            }
        }

        for (BlockPos seed : stationSeeds) {
            if (SkillManager.shouldAbortSkill(bot)) {
                logStageWarn("leveling", "abort requested before station seed=" + seed);
                return;
            }
            BlockPos base = findNearbyStandable(world, seed.withY(standY), 4);
            if (base == null) {
                logStageWarn("leveling", "no standable base near seed=" + seed);
                continue;
            }
            if (source != null && !moveToBuildSiteWithPerimeterFallback(world, source, bot, base, center, radius)) {
                logStageWarn("leveling", "move failed seed=" + seed + " base=" + base);
                continue;
            }

            // Local leveling around this station.
            int localR = 4;
            for (int x = base.getX() - localR; x <= base.getX() + localR; x++) {
                for (int z = base.getZ() - localR; z <= base.getZ() + localR; z++) {
                    int dx = x - center.getX();
                    int dz = z - center.getZ();

                    boolean inFootprint = Math.abs(dx) <= radius && Math.abs(dz) <= radius;
                    boolean inOuterRing = !inFootprint && (
                            (Math.abs(dx) == outerBuffer || Math.abs(dz) == outerBuffer)
                                    || (Math.abs(dx) == outerTravelRing || Math.abs(dz) == outerTravelRing)
                    );
                    // Only touch the hovel footprint + exterior buffer/travel rings.
                    if (!inFootprint && !inOuterRing) {
                        continue;
                    }

                    boolean isInterior = Math.abs(dx) < radius && Math.abs(dz) < radius;
                    boolean isPerimeter = inFootprint && !isInterior;

                    // Foundation beams: ensure support under the entire footprint (including perimeter).
                    if (inFootprint) {
                        BlockPos below = new BlockPos(x, floorY - 1, z);
                        if (isMissing(world, below) || world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                            BotActions.placeBlockAt(bot, below, Direction.UP, PILLAR_BLOCKS);
                        }
                    }

                    // Shave any mounds above the intended floor (footprint only).
                    // Keep this bounded so we don't spend forever on large hills.
                    if (inFootprint) {
                        int top = floorY + Math.max(4, wallHeight);
                        for (int y = top; y >= floorY + 1; y--) {
                            BlockPos p = new BlockPos(x, y, z);
                            if (!world.getBlockState(p).getCollisionShape(world, p).isEmpty()) {
                                mineSoft(bot, p);
                            }
                        }
                    } else if (inOuterRing) {
                        for (int y = floorY + 3; y >= floorY + 1; y--) {
                            BlockPos p = new BlockPos(x, y, z);
                            if (!world.getBlockState(p).getCollisionShape(world, p).isEmpty()) {
                                mineSoft(bot, p);
                            }
                        }
                    }

                    // Fill holes / make the interior floor solid at floorY.
                    // Perimeter cells are reserved for the wall base layer (placed later).
                    if (isInterior) {
                        BlockPos floor = new BlockPos(x, floorY, z);
                        BlockState floorState = world.getBlockState(floor);
                        if (shouldFillHoleForLeveling(world, floor)) {
                            if (!floorState.isAir() && floorState.isReplaceable()) {
                                mineSoft(bot, floor);
                            }
                            BotActions.placeBlockAt(bot, floor, Direction.UP, PILLAR_BLOCKS);
                        }
                    } else if (isPerimeter) {
                        BlockPos floor = new BlockPos(x, floorY, z);
                        if (!isDoorwayReservedCell(floor) && shouldFillHoleForLeveling(world, floor)) {
                            BlockState floorState = world.getBlockState(floor);
                            if (!floorState.isAir() && floorState.isReplaceable()) {
                                mineSoft(bot, floor);
                            }
                            BotActions.placeBlockAt(bot, floor, Direction.UP, PILLAR_BLOCKS);
                        }
                    } else if (inOuterRing) {
                        BlockPos floor = new BlockPos(x, floorY, z);
                        if (shouldFillHoleForLeveling(world, floor)) {
                            BlockState floorState = world.getBlockState(floor);
                            if (!floorState.isAir() && floorState.isReplaceable()) {
                                mineSoft(bot, floor);
                            }
                            BotActions.placeBlockAt(bot, floor, Direction.UP, PILLAR_BLOCKS);
                        }
                    }

                    // Clear minimal headspace and proactively remove replaceable clutter (grass/flowers).
                    // NOTE: the perimeter travel ring is where we see most corner-hump / in-wall damage, so clear extra headroom.
                    int headTop = floorY + 3;
                    for (int y = floorY + 1; y <= headTop; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState s = world.getBlockState(p);
                        if (!s.getCollisionShape(world, p).isEmpty() || (s.isReplaceable() && !s.isAir())) {
                            mineSoft(bot, p);
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private boolean isCornerOfRing(BlockPos pos, BlockPos center, int radius) {
        if (pos == null || center == null) return false;
        int dx = pos.getX() - center.getX();
        int dz = pos.getZ() - center.getZ();
        return Math.abs(dx) == radius && Math.abs(dz) == radius;
    }

    /**
     * Try to detect the actual floor block Y under the build center.
     * This helps us patch holes even if the chosen Y represents the player's standing cell.
     */
    private int detectFloorBlockY(ServerWorld world, BlockPos center) {
        return HovelTerrainService.detectFloorBlockY(world, center);
    }

    @SuppressWarnings("unused")
    private void clearIfObstructed(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        if (world == null || bot == null || pos == null) return;
        BlockState s = world.getBlockState(pos);
        if (s.isAir()) return;
        if (s.isReplaceable() || s.isIn(BlockTags.LEAVES) || !s.getCollisionShape(world, pos).isEmpty()) {
            mineSoft(bot, pos);
        }
    }

    /**
     * Fill any interior floor holes and clear replaceable clutter like grass/flowers.
     * Runs from a few interior stances so we don't path to every tile.
     */
    private void patchInteriorFloorAndClutter(ServerWorld world,
                                             ServerCommandSource source,
                                             ServerPlayerEntity bot,
                                             BlockPos center,
                                             int radius,
                                             BuildCounters counters,
                                             StandableCache standableCache) {
        if (world == null || bot == null || center == null) return;
        if (radius <= 0) return;

        int floorBlockY = detectFloorBlockY(world, center);
        int standY = floorBlockY + 1;
        int inner = Math.max(0, radius - 1);

        // Targets: interior footprint only (leave wall base/perimeter to wall code).
        ArrayList<BlockPos> floorTargets = new ArrayList<>();
        ArrayList<BlockPos> clutterTargets = new ArrayList<>();
        for (int dx = -inner; dx <= inner; dx++) {
            for (int dz = -inner; dz <= inner; dz++) {
                BlockPos floor = new BlockPos(center.getX() + dx, floorBlockY, center.getZ() + dz);
                floorTargets.add(floor);
                clutterTargets.add(floor.up());
            }
        }

        List<BlockPos> stations = List.of(
                center,
                center.add(inner, 0, 0),
                center.add(-inner, 0, 0),
                center.add(0, 0, inner),
                center.add(0, 0, -inner)
        );

        List<BlockPos> orderedStations = sortByDistanceToBot(bot, stations);
        for (BlockPos seed : orderedStations) {
            if (SkillManager.shouldAbortSkill(bot)) return;

            BlockPos seedY = seed.withY(standY);
            BlockPos base = (standableCache == null)
                    ? findNearbyStandable(world, seedY, 5)
                    : standableCache.resolve(world, seedY, 5, (s) -> findNearbyStandable(world, s, 5));
            if (base != null && source != null) {
                moveToBuildSiteWithPerimeterFallback(world, source, bot, base, center, radius);
            }
            prepareScaffoldBase(world, bot);

            // Clear replaceables at foot level (grass/flowers/snow layers) so later fills/placements don't fail.
            for (BlockPos p : clutterTargets) {
                if (SkillManager.shouldAbortSkill(bot)) return;
                if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) > REACH_DISTANCE_SQ) continue;
                BlockState s = world.getBlockState(p);
                if (!s.isAir() && (s.isReplaceable() || s.isIn(BlockTags.LEAVES) || s.isOf(Blocks.SNOW) || s.isOf(Blocks.SNOW_BLOCK))) {
                    mineSoft(bot, p);
                }
            }

            // Fill holes in the floor block layer.
            for (BlockPos floor : floorTargets) {
                if (SkillManager.shouldAbortSkill(bot)) return;
                if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(floor)) > REACH_DISTANCE_SQ) continue;

                BlockState fs = world.getBlockState(floor);
                boolean hole = fs.isAir()
                        || fs.isReplaceable()
                    || fs.isOf(Blocks.SNOW)
                    || fs.isOf(Blocks.SNOW_BLOCK)
                        || !world.getFluidState(floor).isEmpty()
                        || fs.getCollisionShape(world, floor).isEmpty();
                if (hole) {
                    // Clear anything replaceable first, then place a cheap support block.
                    if (!fs.isAir() && fs.isReplaceable()) {
                        mineSoft(bot, floor);
                    }
                    BotActions.placeBlockAt(bot, floor, Direction.UP, PILLAR_BLOCKS);
                    sleepQuiet(40L);
                }
            }
        }
    }

    /**
     * Build the four corner pillars as early "foundation beams" to avoid later occlusion/reach issues.
     */
    private void buildCornerBeamsEarly(ServerWorld world,
                                       ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       BlockPos center,
                                       int radius,
                                       int wallHeight,
                                       BuildCounters counters) {
        if (world == null || source == null || bot == null || center == null) return;
        if (radius <= 0 || wallHeight <= 0) return;

        int baseY = center.getY();
        int standY = baseY + 1;
        int roofY = baseY + wallHeight;
        ArrayList<BlockPos> corners = new ArrayList<>(4);
        corners.add(new BlockPos(center.getX() + radius, baseY, center.getZ() + radius));
        corners.add(new BlockPos(center.getX() - radius, baseY, center.getZ() + radius));
        corners.add(new BlockPos(center.getX() - radius, baseY, center.getZ() - radius));
        corners.add(new BlockPos(center.getX() + radius, baseY, center.getZ() - radius));

        // Build each corner from a diagonal outside stance.
        int outside = radius + 2;
        for (BlockPos c : corners) {
            if (SkillManager.shouldAbortSkill(bot)) return;

            int sx = Integer.signum(c.getX() - center.getX());
            int sz = Integer.signum(c.getZ() - center.getZ());
            BlockPos stanceSeed = center.add(sx * outside, 0, sz * outside).withY(standY);
            BlockPos base = findNearbyStandableFiltered(world, stanceSeed, 6, (p) -> {
                if (isForbiddenScaffoldBase(p) || isScaffoldBaseUsed(p)) return false;
                int dx = p.getX() - center.getX();
                int dz = p.getZ() - center.getZ();
                if (Integer.signum(dx) != sx || Integer.signum(dz) != sz) return false;
                return Math.abs(dx) >= radius + 1 && Math.abs(dz) >= radius + 1;
            });
            if (base == null) continue;
            ensureRingStandable(world, bot, base);
            if (!moveToRingPosFast(source, bot, base)) continue;

            ArrayList<BlockPos> beamTargets = new ArrayList<>();
            for (int y = baseY; y < roofY; y++) {
                beamTargets.add(c.withY(y));
            }
            // Also include the roof corner block; it helps roof stability/placement later.
            beamTargets.add(c.withY(roofY));

            // Stand just below roof plane for stable placement angles.
            scaffoldPlaceAndTeardown(world, bot, beamTargets, roofY - 1, counters, Collections.emptySet(), true);
        }
    }

    /**
     * Patch missed corner wall blocks from diagonal outside scaffolds.
     * This specifically improves corner coverage without adding outside stations to the main loop.
     */
    private void exteriorWallPerimeterPatch(ServerWorld world,
                                           ServerCommandSource source,
                                           ServerPlayerEntity bot,
                                           BlockPos center,
                                           int radius,
                                           List<BlockPos> wallTargets,
                                           BuildCounters counters) {
        if (world == null || source == null || bot == null || center == null || wallTargets == null || wallTargets.isEmpty()) {
            return;
        }

        cleanupRoofAccessPillars(world, source, bot, center, radius);
        int standY = center.getY() + 1;
        int ringRadius = perimeterRingRadius(radius);
        List<BlockPos> path = HovelBlueprint.buildGroundPerimeter(center, ringRadius, standY);
        int startIdx = 0;
        double best = Double.MAX_VALUE;
        BlockPos here = bot.getBlockPos();
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            double d = distSqXZ(here, p);
            if (d < best) {
                best = d;
                startIdx = i;
            }
        }
        for (int i = 0; i < path.size(); i++) {
            BlockPos seed = path.get((startIdx + i) % path.size());
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (countMissing(world, wallTargets) == 0) {
                return;
            }
            BlockPos stand = findNearbyStandable(world, seed.withY(standY), 3);
            if (stand == null) {
                continue;
            }
            if (!moveToRingPosFast(source, bot, stand)) {
                clearPendingRoofPillarAt(world, bot, stand);
                continue;
            }
            placeUntilStalled(world, bot, wallTargets, counters, 2);
        }
    }

    private void mineSoft(ServerPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null) return;
        // If we start mining our way out of a deep pit during a surface-hovel build, we waited too long.
        // Trigger emergency pause early (cooldown-gated) to avoid long "mining out" spirals.
        if (HovelEmergencyRegroupService.triggerDeepUndergroundRegroupIfNeeded(
                LOGGER, bot.getCommandSource(), bot, "mining", activeBuildCenter, activeRadius, stageMessageSource)) {
            return;
        }
        if (HovelEmergencyRegroupService.triggerInWallRegroupIfNeeded(
                LOGGER, bot.getCommandSource(), bot, "mining", activeBuildCenter, activeRadius, stageMessageSource)) {
            return;
        }

        if (!BlockInteractionService.canInteract(bot, pos, REACH_DISTANCE_SQ)) return;
        try { MiningTool.mineBlock(bot, pos).get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    /**
     * Hovel-only safety handler.
     *
     * @return true if an emergency action was taken (snap or pause) and the caller should stop its current
     * action and let the outer build loop retry from a new stance.
     */
    private boolean handleHovelEmergency(ServerPlayerEntity bot, String where) {
        if (bot == null) {
            return false;
        }
        ServerCommandSource src = bot.getCommandSource();
        // Deep-underground pause has priority over in-wall snapping.
        if (HovelEmergencyRegroupService.triggerDeepUndergroundRegroupIfNeeded(
                LOGGER, src, bot, where, activeBuildCenter, activeRadius, stageMessageSource)) {
            return true;
        }
        return HovelEmergencyRegroupService.triggerInWallRegroupIfNeeded(
                LOGGER, src, bot, where, activeBuildCenter, activeRadius, stageMessageSource);
    }

    private Item selectBuildItem(ServerPlayerEntity bot) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack s = bot.getInventory().getStack(i);
            if (!s.isEmpty() && BUILD_BLOCKS.contains(s.getItem())) return s.getItem();
        }
        return null;
    }

    private int countBuildBlocks(ServerPlayerEntity bot) {
        int t = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack s = bot.getInventory().getStack(i);
            if (!s.isEmpty() && BUILD_BLOCKS.contains(s.getItem())) t += s.getCount();
        }
        return t;
    }

    private void ensureBuildStock(ServerCommandSource source, ServerPlayerEntity bot, int needed, BlockPos returnPos) {
        int toGather = needed - countBuildBlocks(bot);
        if (toGather <= 0) return;
        Map<String, Object> params = new HashMap<>();
        params.put("count", toGather);
        params.put("descentBlocks", 5);
        new CollectDirtSkill().execute(new SkillContext(source, new HashMap<>(), params));
        moveToBuildSiteAllowPathing(source, bot, returnPos);
    }

    /**
     * Movement helper for critical actions (door/torches/doorway carving).
     * Unlike {@link #moveToBuildSite}, this does not fast-fail close moves; it will path with a short budget.
     */
    private boolean moveToBuildSiteAllowPathing(ServerCommandSource source, ServerPlayerEntity bot, BlockPos dest) {
        if (source == null || bot == null || dest == null) return false;
        if (HovelEmergencyRegroupService.triggerDeepUndergroundRegroupIfNeeded(
                LOGGER, source, bot, "buildsite-allow-pathing", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        if (HovelEmergencyRegroupService.triggerInWallRegroupIfNeeded(
                LOGGER, source, bot, "buildsite-allow-pathing", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (world != null && activeBuildCenter != null && activeRadius > 0) {
            if (!ensureInteriorAccessForDestination(world, source, bot, dest, activeBuildCenter, activeRadius, activeDoorSide)) {
                return false;
            }
            if (!ensureExteriorAccessForDestination(world, source, bot, dest, activeBuildCenter, activeRadius)) {
                return false;
            }
        }
        if (bot.getBlockPos().getSquaredDistance(dest) <= 4.0D) return true;

        double distSq = bot.getBlockPos().getSquaredDistance(dest);

        if (distSq <= 36.0D) {
            MovementService.MovementPlan direct = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    dest,
                    dest,
                    null,
                    null,
                    Direction.UP
            );
            MovementService.MovementResult directRes = MovementService.withoutDoorEscape(
                    () -> MovementService.execute(source, bot, direct, false, true, false, false)
            );
            if (directRes.success()) {
                return true;
            }
        }

        if (distSq <= 144.0D) {
            int[] o = new int[] { 0, 1, -1, 2, -2 };
            for (int dx : o) {
                for (int dz : o) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos alt = new BlockPos(dest.getX() + dx, dest.getY(), dest.getZ() + dz);
                    if (!isStandable((ServerWorld) bot.getEntityWorld(), alt)) continue;
                    MovementService.MovementPlan directAlt = new MovementService.MovementPlan(
                            MovementService.Mode.DIRECT,
                            alt,
                            alt,
                            null,
                            null,
                            Direction.UP
                    );
                    MovementService.MovementResult res = MovementService.withoutDoorEscape(
                            () -> MovementService.execute(source, bot, directAlt, false, true, false, false)
                    );
                    if (res.success()) {
                        return true;
                    }
                }
            }

            // Perimeter fallback: if close-range moves are blocked by walls/corners, route around the footprint.
            // This avoids repeated oscillation and corner wall-humping.
            if (world != null && shouldUsePerimeterRouting(dest)) {
                if (moveToViaPerimeterLoop(world, source, bot, dest)) {
                    // Final short hop (or nearby alt) to the intended destination.
                    if (directMove(source, bot, dest)) {
                        return true;
                    }
                    int[] o2 = new int[] { 0, 1, -1, 2, -2 };
                    for (int dx : o2) {
                        for (int dz : o2) {
                            if (dx == 0 && dz == 0) continue;
                            BlockPos alt = new BlockPos(dest.getX() + dx, dest.getY(), dest.getZ() + dz);
                            if (!isStandable(world, alt)) continue;
                            if (directMove(source, bot, alt)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        var planOpt = MovementService.planLootApproach(bot, dest, MovementService.MovementOptions.skillLoot());
        return planOpt.isPresent() && MovementService.withoutDoorEscape(
            () -> MovementService.execute(source, bot, planOpt.get(), false, true, false, false)
        ).success();
    }

    private boolean shouldPerimeterRecover(BlockPos dest) {
        if (dest == null) return false;
        long now = System.currentTimeMillis();
        BlockPos key = dest.toImmutable();
        Long since = reachAttemptSinceMs.get(key);
        if (since == null) {
            reachAttemptSinceMs.put(key, now);
            return false;
        }
        return (now - since) >= REACH_STALL_PERIMETER_WALK_MS;
    }

    private void markReachAttemptResult(BlockPos dest, boolean success) {
        if (dest == null) return;
        BlockPos key = dest.toImmutable();
        if (success) {
            reachAttemptSinceMs.remove(key);
        } else {
            reachAttemptSinceMs.putIfAbsent(key, System.currentTimeMillis());
        }
    }

    private boolean perimeterRecoveryWalk(ServerWorld world, ServerCommandSource source, ServerPlayerEntity bot, BlockPos center, int radius) {
        if (world == null || source == null || bot == null || center == null) return false;
        if (!ensureExteriorAccessForDestination(world, source, bot,
                center.offset(resolveDoorSideForExit(bot), radius + 1), center, radius)) {
            return false;
        }
        return HovelPerimeterRoutingService.perimeterRecoveryWalk(
                world,
                source,
                bot,
                center,
                radius,
                PERIMETER_RING_OFFSET,
                this::findNearbyStandable,
                this::ensureRingStandable,
                this::moveToRingWaypoint
        );
    }

    private boolean moveToBuildSiteWithPerimeterFallback(ServerWorld world,
                                                        ServerCommandSource source,
                                                        ServerPlayerEntity bot,
                                                        BlockPos dest,
                                                        BlockPos buildCenter,
                                                        int radius) {
        if (source == null || bot == null || dest == null) return false;
        if (HovelEmergencyRegroupService.triggerDeepUndergroundRegroupIfNeeded(
                LOGGER, source, bot, "buildsite-perimeter-fallback", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        if (HovelEmergencyRegroupService.triggerInWallRegroupIfNeeded(
                LOGGER, source, bot, "buildsite-perimeter-fallback", activeBuildCenter, activeRadius, stageMessageSource)) {
            return false;
        }
        if (world != null && buildCenter != null && radius > 0) {
            if (!ensureInteriorAccessForDestination(world, source, bot, dest, buildCenter, radius, activeDoorSide)) {
                markReachAttemptResult(dest, false);
                return false;
            }
            if (!ensureExteriorAccessForDestination(world, source, bot, dest, buildCenter, radius)) {
                markReachAttemptResult(dest, false);
                return false;
            }
            if (isOutsideFootprint(dest, buildCenter, radius) && shouldUsePerimeterRouting(dest)) {
                moveToViaPerimeterLoop(world, source, bot, dest);
                boolean ok = moveToRingWaypoint(source, bot, dest, true);
                markReachAttemptResult(dest, ok);
                return ok;
            }
        }
        if (shouldPerimeterRecover(dest)) {
            boolean turnedCorner = perimeterRecoveryWalk(world, source, bot, buildCenter, radius);
            if (!turnedCorner) {
                // If we can't even turn a corner on the perimeter, we're effectively stuck.
                // Fast-fail the move so the caller can choose a different station/angle.
                markReachAttemptResult(dest, false);
                return false;
            }
            // reset timer window after successful recovery
            reachAttemptSinceMs.put(dest.toImmutable(), System.currentTimeMillis());
        }
        boolean ok = moveToBuildSite(source, bot, dest);
        if (!ok) {
            // First fallback: perimeter waypoint routing (turn the corner / go around the footprint).
            // This is the primary mechanism to avoid back-and-forth oscillation into solid walls.
            if (world != null && shouldUsePerimeterRouting(dest)) {
                LOGGER.info("Build move: direct failed; trying perimeter loop routing to {}", dest);
                if (moveToViaPerimeterLoop(world, source, bot, dest)) {
                    // Final approach to the exact target.
                    if (pathMove(source, bot, dest) || directMove(source, bot, dest)) {
                        ok = true;
                    }
                }
            }
        }
        if (!ok) {
            // Last fallback: bounded pathing. Keep this last; it is the most likely to wall-hump.
            ok = moveToBuildSiteAllowPathing(source, bot, dest);
        }
        markReachAttemptResult(dest, ok);
        return ok;
    }

    private boolean moveToBuildSite(ServerCommandSource source, ServerPlayerEntity bot, BlockPos center) {
        if (bot.getBlockPos().getSquaredDistance(center) <= 4.0D) return true;

        double distSq = bot.getBlockPos().getSquaredDistance(center);

        // Fast path: attempt a cheap direct approach first. This avoids expensive path planning
        // when hopping between close-by build stations.
        if (distSq <= 36.0D) {
            MovementService.MovementPlan direct = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    center,
                    center,
                    null,
                    null,
                    Direction.UP
            );
            // Disable pursuit for build moves; it commonly devolves into wall-humping when stations are unreachable.
            MovementService.MovementResult directRes = MovementService.withoutDoorEscape(
                    () -> MovementService.execute(source, bot, direct, false, true, false, false)
            );
            if (directRes.success()) {
                return true;
            }
        }

        // If the target cell is slightly obstructed (common once walls exist), try a few nearby stand cells
        // with cheap DIRECT moves before invoking full path planning (which is slower and tends to wall-hump).
        if (distSq <= 144.0D) {
            BlockPos t = center;
            // Small ring of alternates around the desired cell.
            int[] o = new int[] { 0, 1, -1, 2, -2 };
            for (int dx : o) {
                for (int dz : o) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos alt = new BlockPos(t.getX() + dx, t.getY(), t.getZ() + dz);
                    if (!isStandable((ServerWorld) bot.getEntityWorld(), alt)) continue;
                    MovementService.MovementPlan directAlt = new MovementService.MovementPlan(
                            MovementService.Mode.DIRECT,
                            alt,
                            alt,
                            null,
                            null,
                            Direction.UP
                    );
                    MovementService.MovementResult res = MovementService.withoutDoorEscape(
                            () -> MovementService.execute(source, bot, directAlt, false, true, false, false)
                    );
                    if (res.success()) {
                        return true;
                    }
                }
            }

            // Fast-fail close moves: if we can't get to a nearby cell cheaply, don't pathfind.
            // Path planning here frequently results in long wall-humping loops.
            return false;
        }

        return pathMove(source, bot, center);
    }

    private int estimatePlacementNeed(ServerWorld world, BlockPos center, int radius, int height, Direction doorSide) {
        return (radius * 2 + 1) * (radius * 2 + 1) * 2 + (radius * 8 * height) + 64;
    }

    private void ensureDoorwayOpen(ServerWorld world,
                                  ServerCommandSource source,
                                  ServerPlayerEntity bot,
                                  BlockPos center,
                                  int radius,
                                  Direction doorSide) {
        if (world == null || bot == null || center == null || doorSide == null) return;
        int floorY = center.getY();
        int standY = floorY + 1;

        BlockPos doorBase = center.offset(doorSide, radius).withY(standY);
        BlockPos doorUpper = doorBase.up();
        BlockPos insideFront = doorBase.offset(doorSide.getOpposite());
        BlockPos outsideFront = doorBase.offset(doorSide);

        // Try to get close enough that the carve is actually in reach.
        BlockPos inside = findNearbyStandable(world, insideFront, 5);
        boolean positioned = false;
        if (inside != null && source != null) {
            positioned = moveToBuildSiteWithPerimeterFallback(world, source, bot, inside, center, radius);
        }
        if (!positioned && source != null) {
            BlockPos outside = findNearbyStandable(world, outsideFront, 6);
            if (outside != null) {
                moveToBuildSiteWithPerimeterFallback(world, source, bot, outside, center, radius);
            }
        }

        // Clear 2-high doorway. Do NOT break a placed door.
        for (BlockPos p : List.of(doorBase, doorUpper)) {
            if (p == null) continue;
            BlockState s = world.getBlockState(p);
            if (s.isAir()) continue;
            if (s.getBlock() instanceof net.minecraft.block.DoorBlock) continue;
            mineSoft(bot, p);
        }

        // Never allow blocks in front of the doorway (inside AND outside).
        for (BlockPos p : List.of(insideFront, insideFront.up(), outsideFront, outsideFront.up())) {
            if (p == null) continue;
            BlockState s = world.getBlockState(p);
            if (s.isAir()) continue;
            if (s.getBlock() instanceof net.minecraft.block.DoorBlock) continue;
            mineSoft(bot, p);
        }
    }

    private boolean isDoorwayFrontClearanceCell(BlockPos center, int radius, Direction doorSide, BlockPos pos) {
        if (center == null || doorSide == null || pos == null) return false;
        int standY = center.getY() + 1;
        BlockPos doorBase = center.offset(doorSide, radius).withY(standY);
        BlockPos insideFront = doorBase.offset(doorSide.getOpposite());
        BlockPos outsideFront = doorBase.offset(doorSide);
        return pos.equals(insideFront) || pos.equals(insideFront.up()) || pos.equals(outsideFront) || pos.equals(outsideFront.up());
    }

    private boolean isDoorwayFrontClearanceCell(BlockPos pos) {
        if (pos == null || activeBuildCenter == null || activeDoorSide == null) return false;
        return isDoorwayFrontClearanceCell(activeBuildCenter, activeRadius, activeDoorSide, pos);
    }

    private boolean isDoorwayReservedCell(BlockPos center, int radius, Direction doorSide, BlockPos pos) {
        if (center == null || doorSide == null || pos == null) return false;
        int standY = center.getY() + 1;
        BlockPos doorBase = center.offset(doorSide, radius).withY(standY);
        BlockPos doorUpper = doorBase.up();
        BlockPos insideFront = doorBase.offset(doorSide.getOpposite());
        BlockPos outsideFront = doorBase.offset(doorSide);
        return pos.equals(doorBase)
                || pos.equals(doorUpper)
                || pos.equals(insideFront)
                || pos.equals(insideFront.up())
                || pos.equals(outsideFront)
                || pos.equals(outsideFront.up());
    }

    private boolean isDoorwayReservedCell(BlockPos pos) {
        if (pos == null || activeBuildCenter == null || activeDoorSide == null) return false;
        return isDoorwayReservedCell(activeBuildCenter, activeRadius, activeDoorSide, pos);
    }

    private void placeDoorIfAvailable(ServerWorld world,
                                      ServerCommandSource source,
                                      ServerPlayerEntity bot,
                                      BlockPos center,
                                      int radius,
                                      Direction doorSide) {
        if (world == null || bot == null || center == null || doorSide == null) {
            return;
        }

        // Pick any vanilla door the bot has.
        Item doorItem = null;
        List<Item> candidates = List.of(
                Items.OAK_DOOR, Items.SPRUCE_DOOR, Items.BIRCH_DOOR, Items.JUNGLE_DOOR,
                Items.ACACIA_DOOR, Items.DARK_OAK_DOOR, Items.MANGROVE_DOOR, Items.CHERRY_DOOR,
                Items.BAMBOO_DOOR, Items.CRIMSON_DOOR, Items.WARPED_DOOR,
                Items.IRON_DOOR
        );

        // Check hands first (players often keep the door equipped).
        ItemStack main = bot.getMainHandStack();
        if (!main.isEmpty() && candidates.contains(main.getItem())) {
            doorItem = main.getItem();
        }
        if (doorItem == null) {
            ItemStack off = bot.getOffHandStack();
            if (!off.isEmpty() && candidates.contains(off.getItem())) {
                doorItem = off.getItem();
            }
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack s = bot.getInventory().getStack(i);
            if (!s.isEmpty() && candidates.contains(s.getItem())) {
                doorItem = s.getItem();
                break;
            }
        }
        if (doorItem == null) {
            return;
        }

        int floorY = center.getY();
        int standY = floorY + 1;
        BlockPos doorPos = center.offset(doorSide, radius).withY(floorY + 1);
        BlockPos outsideFront = center.offset(doorSide, radius + 1).withY(standY);
        // Prefer a farther diagonal stance (~sqrt(5)=2.236 blocks from the door block center).
        // This reduces "crowding" the doorway, improving placement reliability.
        BlockPos outsideFrontFar = center.offset(doorSide, radius + 2).withY(standY);
        BlockPos outsideFrontFarDiagCW = center.offset(doorSide, radius + 2).offset(doorSide.rotateYClockwise()).withY(standY);
        BlockPos outsideFrontFarDiagCCW = center.offset(doorSide, radius + 2).offset(doorSide.rotateYCounterclockwise()).withY(standY);
        BlockPos insideFront = center.offset(doorSide, Math.max(1, radius - 1)).withY(standY);

        // If the door is already present, don't spam attempts.
        BlockState existingDoor = world.getBlockState(doorPos);
        if (existingDoor.getBlock() instanceof net.minecraft.block.DoorBlock) {
            return;
        }

        // Make sure the doorway is clear and has a support block below.
        ensureDoorwayOpen(world, source, bot, center, radius, doorSide);
        BlockPos below = doorPos.down();
        if (isMissing(world, below)) {
            BotActions.placeBlockAt(bot, below, Direction.UP, PILLAR_BLOCKS);
            sleepQuiet(60L);
        }

        // NEW: Prefer placing the door from the interior center.
        // Motivation: outside stances frequently cause the bot to "fight" its own doorway clearance rules
        // (oscillating between reserved cells and outside ring positions). From the center, the click/aim
        // is stable and within reach for typical hovel radii.
        if (source != null) {
            enterInteriorViaDoor(world, source, bot, center, radius, doorSide);
            BlockPos interiorCenter = findNearbyStandableFiltered(world, center.withY(standY), 4,
                    (p) -> !isForbiddenScaffoldBase(p) && !isDoorwayReservedCell(center, radius, doorSide, p));
            if (interiorCenter != null) {
                moveToBuildSiteWithPerimeterFallback(world, source, bot, interiorCenter, center, radius);
                prepareScaffoldBase(world, bot);
                ensureDoorwayOpen(world, source, bot, center, radius, doorSide);

                if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(doorPos)) <= REACH_DISTANCE_SQ
                        && BlockInteractionService.canInteract(bot, below, REACH_DISTANCE_SQ)) {
                    // Face outward so the door tends to open outward.
                    net.shasankp000.Entity.LookController.faceBlock(bot, doorPos.offset(doorSide));
                    // 1s delay before click (gives movement/network state time to settle).
                    sleepQuiet(1000L);
                    if (BotActions.placeBlockAt(bot, doorPos, Direction.UP, List.of(doorItem))) {
                        sleepQuiet(120L);
                        return;
                    }

                    // Retry once facing inward.
                    net.shasankp000.Entity.LookController.faceBlock(bot, doorPos.offset(doorSide.getOpposite()));
                    sleepQuiet(1000L);
                    if (BotActions.placeBlockAt(bot, doorPos, Direction.UP, List.of(doorItem))) {
                        sleepQuiet(120L);
                        return;
                    }
                }
            }
        }

        if (source != null) {
            if (isDoorwayReservedCell(center, radius, doorSide, bot.getBlockPos())) {
                // Move away from the doorway before attempting placement.
                BlockPos far = findNearbyStandable(world, outsideFrontFarDiagCW, 4);
                if (far == null) far = findNearbyStandable(world, outsideFrontFarDiagCCW, 4);
                if (far == null) far = findNearbyStandable(world, outsideFrontFar, 4);
                if (far == null) far = outsideFront;
                moveToRingPosFast(source, bot, far);
            }
            ensureInteriorAccessForDestination(world, source, bot, doorPos, center, radius, doorSide);
            // Always prefer the farther diagonal stance if possible.
            BlockPos far = findNearbyStandable(world, outsideFrontFarDiagCW, 4);
            if (far == null) far = findNearbyStandable(world, outsideFrontFarDiagCCW, 4);
            if (far == null) far = findNearbyStandable(world, outsideFrontFar, 4);
            if (far == null) far = outsideFront;
            moveToRingPosFast(source, bot, far);
        }

        // Try from both an interior and an exterior stance.
        // IMPORTANT: doorPos is usually AIR. Raycast-based canInteract(doorPos) will almost always fail
        // because raycasts hit blocks, not empty space. For placement, we care about being able to
        // interact with the SUPPORT block below.
        LinkedHashSet<BlockPos> stanceSeeds = new LinkedHashSet<>();
        // Order matters: try farther stances first.
        stanceSeeds.add(outsideFrontFarDiagCW);
        stanceSeeds.add(outsideFrontFarDiagCCW);
        stanceSeeds.add(outsideFrontFar);
        stanceSeeds.add(outsideFront);
        stanceSeeds.add(insideFront);
        stanceSeeds.add(center.offset(doorSide, Math.max(0, radius - 1)).withY(standY));
        stanceSeeds.add(center.offset(doorSide, radius + 2).withY(standY));
        stanceSeeds.add(center.offset(doorSide, Math.max(0, radius - 1)).offset(doorSide.rotateYClockwise()).withY(standY));
        stanceSeeds.add(center.offset(doorSide, Math.max(0, radius - 1)).offset(doorSide.rotateYCounterclockwise()).withY(standY));
        stanceSeeds.add(center.offset(doorSide, radius + 2).offset(doorSide.rotateYClockwise()).withY(standY));
        stanceSeeds.add(center.offset(doorSide, radius + 2).offset(doorSide.rotateYCounterclockwise()).withY(standY));

        for (BlockPos stanceSeed : stanceSeeds) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            BlockPos stance = findNearbyStandable(world, stanceSeed, 6);
            if (stance != null && isDoorwayReservedCell(center, radius, doorSide, stance)) {
                continue;
            }
            if (stance != null && source != null) {
                moveToBuildSiteWithPerimeterFallback(world, source, bot, stance, center, radius);
            }
            // Ensure the doorway is clear from this vantage.
            ensureDoorwayOpen(world, source, bot, center, radius, doorSide);

            if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(doorPos)) > REACH_DISTANCE_SQ) {
                continue;
            }
            if (!BlockInteractionService.canInteract(bot, below, REACH_DISTANCE_SQ)) {
                continue;
            }

            // Face outward so the door tends to open outward.
            net.shasankp000.Entity.LookController.faceBlock(bot, doorPos.offset(doorSide));
            if (BotActions.placeBlockAt(bot, doorPos, Direction.UP, List.of(doorItem))) {
                sleepQuiet(80L);
                return;
            }

            // Retry once facing inward (hinge/orientation quirks can sometimes block the first attempt).
            net.shasankp000.Entity.LookController.faceBlock(bot, doorPos.offset(doorSide.getOpposite()));
            if (BotActions.placeBlockAt(bot, doorPos, Direction.UP, List.of(doorItem))) {
                sleepQuiet(80L);
                return;
            }
        }
    }

    private void placeWallTorches(ServerWorld world,
                                 ServerCommandSource source,
                                 ServerPlayerEntity bot,
                                 BlockPos center,
                                 int radius,
                                 Direction doorSide) {
        if (world == null || bot == null || center == null) {
            return;
        }
        if (radius < 2) {
            return;
        }

        // Only bother if we actually have torches.
        boolean hasTorch = false;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack s = bot.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == Items.TORCH) {
                hasTorch = true;
                break;
            }
        }
        if (!hasTorch) {
            return;
        }

        // Try to get inside so we can place torches on the interior floor.
        int floorY = center.getY();
        int standY = floorY + 1;
        if (source != null) {
            enterInteriorViaDoor(world, source, bot, center, radius, doorSide);
        }
        BlockPos inside = findNearbyStandable(world, center.withY(standY), 5);
        if (inside != null && source != null) {
            moveToBuildSiteWithPerimeterFallback(world, source, bot, inside, center, radius);
        }

        // Place standing torches on top of the interior floor (y = floor + 1).
        int y = floorY + 1;
        List<BlockPos> targets = List.of(
            // north interior
                new BlockPos(center.getX(), y, center.getZ() - radius + 1),
            // south interior
                new BlockPos(center.getX(), y, center.getZ() + radius - 1),
            // west interior
                new BlockPos(center.getX() - radius + 1, y, center.getZ()),
            // east interior
                new BlockPos(center.getX() + radius - 1, y, center.getZ())
        );
        List<BlockPos> orderedTargets = sortByDistanceToBot(bot, targets);

        int placed = 0;
        for (BlockPos p : orderedTargets) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (isDoorwayFrontClearanceCell(center, radius, doorSide, p)) {
                continue;
            }

            // If we're too far, step to a closer interior stance first.
            if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) > REACH_DISTANCE_SQ && source != null) {
                BlockPos stance = findNearbyStandableFiltered(world, p.withY(standY), 4, (s) -> !isForbiddenScaffoldBase(s));
                if (stance != null) {
                    moveToBuildSiteWithPerimeterFallback(world, source, bot, stance, center, radius);
                    prepareScaffoldBase(world, bot);
                }
            }

            // Clear replaceable clutter only for torch placement (grass/snow layer/leaf litter).
            BlockState state = world.getBlockState(p);
            if (!state.isAir() && (state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW) || state.isOf(Blocks.SNOW_BLOCK))) {
                mineSoft(bot, p);
            }
            // Ensure there's a floor block to place the torch on.
            if (isMissing(world, p.down())) {
                BotActions.placeBlockAt(bot, p.down(), Direction.UP, PILLAR_BLOCKS);
                sleepQuiet(40L);
            }

            if (world.getBlockState(p).isAir()
                    && !isMissing(world, p.down())
                    && bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) <= REACH_DISTANCE_SQ) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    BotActions.placeBlockAt(bot, p, Direction.UP, List.of(Items.TORCH));
                    sleepQuiet(70L);
                    if (world.getBlockState(p).isOf(Blocks.TORCH) || world.getBlockState(p).isOf(Blocks.WALL_TORCH)) {
                        placed++;
                        break;
                    }
                }
            }
        }

        // Fallback: if the main targets are blocked, at least place two interior corner torches.
        List<BlockPos> floorTargets = List.of(
            center.add(radius - 1, 1, radius - 1),
            center.add(-(radius - 1), 1, radius - 1),
            center.add(radius - 1, 1, -(radius - 1)),
            center.add(-(radius - 1), 1, -(radius - 1))
        );
        List<BlockPos> orderedFallback = sortByDistanceToBot(bot, floorTargets);
        for (BlockPos p : orderedFallback) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (isDoorwayFrontClearanceCell(center, radius, doorSide, p)) {
                continue;
            }

            if (placed >= 2) {
                return;
            }

            if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) > REACH_DISTANCE_SQ && source != null) {
                BlockPos stance = findNearbyStandableFiltered(world, p.withY(standY), 4, (s) -> !isForbiddenScaffoldBase(s));
                if (stance != null) {
                    moveToBuildSiteWithPerimeterFallback(world, source, bot, stance, center, radius);
                    prepareScaffoldBase(world, bot);
                }
            }
            BlockState state = world.getBlockState(p);
            if (!state.isAir() && (state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW) || state.isOf(Blocks.SNOW_BLOCK))) {
                mineSoft(bot, p);
            }

            if (isMissing(world, p.down())) {
                BotActions.placeBlockAt(bot, p.down(), Direction.UP, PILLAR_BLOCKS);
                sleepQuiet(35L);
            }
            if (world.getBlockState(p).isAir()
                    && !isMissing(world, p.down())
                    && bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) <= REACH_DISTANCE_SQ) {
                BotActions.placeBlockAt(bot, p, Direction.UP, List.of(Items.TORCH));
                sleepQuiet(70L);
                if (world.getBlockState(p).isOf(Blocks.TORCH) || world.getBlockState(p).isOf(Blocks.WALL_TORCH)) {
                    placed++;
                }
            }
        }
    }

    /**
     * End-of-build interior leveling.
     *
     * <p>Goals:
     * <ul>
     *   <li>Fill any remaining interior floor holes at y=floor</li>
     *   <li>Mine any remaining raised blocks inside at y=floor+1..roof-1</li>
     * </ul>
     */
    private void finalInteriorLevelingPass(ServerWorld world,
                                          ServerCommandSource source,
                                          ServerPlayerEntity bot,
                                          BlockPos center,
                                          int radius,
                                          int wallHeight,
                                          Direction doorSide) {
        if (world == null || bot == null || center == null) return;
        if (radius <= 0) return;

        int floorY = center.getY();
        int standY = floorY + 1;
        int inner = Math.max(0, radius - 1);
        int roofY = floorY + Math.max(1, wallHeight);
        int maxY = Math.max(floorY + 1, roofY - 1);

        // Also treat this as the last chance to fix any remaining wall holes from inside.
        // (Especially useful when exterior passes were blocked by terrain or doorway thrash.)
        final List<BlockPos> wallTargets = HovelBlueprint.generateWallBlueprint(center, radius, wallHeight, doorSide);

        if (source != null) {
            enterInteriorViaDoor(world, source, bot, center, radius, doorSide);
        }

        List<BlockPos> stations = List.of(
                center,
                center.add(inner, 0, 0),
                center.add(-inner, 0, 0),
                center.add(0, 0, inner),
                center.add(0, 0, -inner)
        );

        for (BlockPos seed : stations) {
            if (SkillManager.shouldAbortSkill(bot)) return;
            BlockPos base = findNearbyStandableFiltered(world, seed.withY(standY), 5, (p) -> !isForbiddenScaffoldBase(p));
            if (base != null && source != null) {
                moveToBuildSiteWithPerimeterFallback(world, source, bot, base, center, radius);
            }
            prepareScaffoldBase(world, bot);

            // From each station, opportunistically fill any missing wall blocks we can reach.
            // (Passing null counters is OK here; this is a correctness pass, not a metrics pass.)
            placeUntilStalled(world, bot, wallTargets, null, 2);

            for (int dx = -inner; dx <= inner; dx++) {
                for (int dz = -inner; dz <= inner; dz++) {
                    if (SkillManager.shouldAbortSkill(bot)) return;
                    BlockPos floor = new BlockPos(center.getX() + dx, floorY, center.getZ() + dz);
                    if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(floor)) <= REACH_DISTANCE_SQ) {
                        BlockState fs = world.getBlockState(floor);
                        boolean hole = fs.isAir()
                                || fs.isReplaceable()
                                || fs.isIn(BlockTags.LEAVES)
                                || fs.isOf(Blocks.SNOW)
                                || fs.isOf(Blocks.SNOW_BLOCK)
                                || !world.getFluidState(floor).isEmpty()
                                || fs.getCollisionShape(world, floor).isEmpty();
                        if (hole) {
                            if (!fs.isAir() && fs.isReplaceable()) {
                                mineSoft(bot, floor);
                            }
                            BotActions.placeBlockAt(bot, floor, Direction.UP, PILLAR_BLOCKS);
                            sleepQuiet(35L);
                        }
                    }

                    // Shave raised blocks inside the interior volume.
                    for (int y = floorY + 1; y <= maxY; y++) {
                        BlockPos p = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                        if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) > REACH_DISTANCE_SQ) continue;
                        if (isDoorwayReservedCell(center, radius, doorSide, p)) continue;
                        BlockState s = world.getBlockState(p);
                        if (s.isAir()) continue;
                        if (s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH)) continue;
                        if (!s.getCollisionShape(world, p).isEmpty()) {
                            mineSoft(bot, p);
                        }
                    }
                }
            }

            // If there are still wall holes above reach, do a short interior scaffold-up pass.
            // This is bounded by the roof plane (stand at roofY-1) and tears down afterwards.
            if (!SkillManager.shouldAbortSkill(bot) && countMissing(world, wallTargets) > 0) {
                scaffoldLayeredAndPlace(world, bot, wallTargets, roofY - 1, null);
            }
        }
    }

    private void sweepDrops(ServerCommandSource source, double radius, double vRange, int maxTargets, long durationMs) {
        try { DropSweeper.sweep(source.withSilent(), radius, vRange, maxTargets, durationMs); } catch (Exception ignored) {}
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void waitForYIncrease(ServerPlayerEntity bot, int fromY, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline
                && bot.getBlockY() <= fromY
                && !SkillManager.shouldAbortSkill(bot)) {
            sleepQuiet(50L);
        }
    }

    private void waitForYDecrease(ServerPlayerEntity bot, int fromY, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline
                && bot.getBlockY() >= fromY
                && !SkillManager.shouldAbortSkill(bot)) {
            sleepQuiet(50L);
        }
    }

    private boolean isOnPillarTop(ServerPlayerEntity bot, BlockPos pillarTop) {
        if (bot == null || pillarTop == null) {
            return false;
        }
        BlockPos here = bot.getBlockPos();
        return here.getX() == pillarTop.getX()
                && here.getZ() == pillarTop.getZ()
                && here.getY() >= pillarTop.getY() - 1;
    }

    private List<BlockPos> sortByDistanceToBot(ServerPlayerEntity bot, Collection<BlockPos> positions) {
        if (bot == null || positions == null || positions.isEmpty()) {
            return Collections.emptyList();
        }
        Vec3d here = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        ArrayList<BlockPos> ordered = new ArrayList<>();
        for (BlockPos p : positions) {
            if (p != null) {
                ordered.add(p);
            }
        }
        ordered.sort(Comparator.comparingDouble(p -> here.squaredDistanceTo(Vec3d.ofCenter(p))));
        return ordered;
    }

    private BlockPos findNearestMissing(ServerWorld world, ServerPlayerEntity bot, List<BlockPos> targets) {
        if (world == null || bot == null || targets == null || targets.isEmpty()) {
            return null;
        }
        Vec3d here = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos p : targets) {
            if (p == null || !isMissing(world, p)) continue;
            double d = here.squaredDistanceTo(Vec3d.ofCenter(p));
            if (d < bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    private HovelPlan resolvePlan(ServerWorld world, ServerPlayerEntity bot, BlockPos origin, int radius, Direction preferredDoorSide, Map<String, Object> sharedState, boolean resumeRequested) {
        Direction fallbackDoor = preferredDoorSide != null ? preferredDoorSide : (bot != null ? bot.getHorizontalFacing() : Direction.NORTH);
        BlockPos fallbackCenter = origin;

            if (resumeRequested && sharedState != null && buildStateStore.isAttached()) {
                Object cx = SharedStateUtils.getValue(sharedState, buildStateStore.key("plan.center.x"));
                Object cy = SharedStateUtils.getValue(sharedState, buildStateStore.key("plan.center.y"));
                Object cz = SharedStateUtils.getValue(sharedState, buildStateStore.key("plan.center.z"));
                Object ds = SharedStateUtils.getValue(sharedState, buildStateStore.key("plan.doorSide"));
            if (cx instanceof Number && cy instanceof Number && cz instanceof Number) {
                BlockPos stored = new BlockPos(((Number) cx).intValue(), ((Number) cy).intValue(), ((Number) cz).intValue());
                Direction storedDoor = parseDirectionOr(fallbackDoor, ds);
                LOGGER.info("Hovel: resume requested; using stored plan center={} doorSide={} (fallbackDoor={})",
                        stored.toShortString(), storedDoor, fallbackDoor);
                return new HovelPlan(stored, storedDoor);
            }
        }

        return new HovelPlan(fallbackCenter, fallbackDoor);
    }
}
