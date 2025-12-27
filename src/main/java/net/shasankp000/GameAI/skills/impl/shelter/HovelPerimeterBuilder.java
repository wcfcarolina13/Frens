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
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.impl.CollectDirtSkill;
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
    private static final int PERIMETER_RING_OFFSET = 1;

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
        }
    }

    private void resetBuildState() {
        reachAttemptSinceMs.clear();
        usedScaffoldBasesXZ.clear();
        confirmedFoundationBeams.clear();
        pendingFoundationBeams.clear();
        stageMessagesSent.clear();
        stageMessageSource = null;
        pendingRoofPillars.clear();
    }

    private boolean isForbiddenScaffoldBase(BlockPos foot) {
        if (foot == null) return false;
        // Never scaffold in the doorway gap or its inside/outside clearance cells.
        return isDoorwayReservedCell(foot) || isDoorwayFrontClearanceCell(foot);
    }

    private record RoofPillar(BlockPos base, int topY) {
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

    public SkillExecutionResult build(SkillContext context,
                                      ServerCommandSource source,
                                      ServerPlayerEntity bot,
                                      ServerWorld world,
                                      BlockPos origin,
                                      int radius,
                                      int wallHeight,
                                      Direction preferredDoorSide,
                                      boolean resumeRequested) {
        
        HovelPlan plan = resolvePlan(world, bot, origin, radius, preferredDoorSide, context.sharedState(), resumeRequested);
        BlockPos requestedStand = plan.center();
        Direction doorSide = plan.doorSide();
        logStage("start", "plan resolved center=" + requestedStand + " radius=" + radius + " height=" + wallHeight + " door=" + doorSide);

        // IMPORTANT: keep build geometry anchored to the floor BLOCK layer, but keep movement anchored
        // to the standable (feet) layer. Conflating these is the #1 cause of:
        // - missing interior floor fills (placing at the wrong Y)
        // - missed corner beams (trying to place into air with no support)
        // - "wall-humping" (pathing to non-standable Y)
        int floorBlockY = detectFloorBlockY(world, requestedStand);
        BlockPos buildCenter = new BlockPos(requestedStand.getX(), floorBlockY, requestedStand.getZ());
        BlockPos standCenter = buildCenter.up();

        // Publish active context for safety rules.
        this.activeBuildCenter = buildCenter;
        this.activeRadius = radius;
        this.activeDoorSide = doorSide;
        resetBuildState();
        this.stageMessageSource = context != null && context.requestSource() != null
                ? context.requestSource()
                : source;

        // Initial move is critical; allow pathing with a short budget.
        if (!moveToBuildSiteAllowPathing(source, bot, standCenter)) {
            logStageWarn("init-move", "could not reach initial build stand at " + standCenter);
            return SkillExecutionResult.failure("I could not reach a safe spot to build a hovel.");
        }

        BuildCounters counters = new BuildCounters();

        // Stock up BEFORE any leveling / beams / floor work (those phases place blocks too).
        int needed = estimatePlacementNeed(world, buildCenter, radius, wallHeight, doorSide);
        if (countBuildBlocks(bot) < needed) {
            ensureBuildStock(source, bot, needed, standCenter);
        }

        sendStageMessage("leveling", "Hovel: leveling build site...");
        logStage("leveling", "start center=" + buildCenter + " radius=" + radius);
        levelBuildSite(world, source, bot, buildCenter, radius, wallHeight, counters);
        logStage("leveling", "done pos=" + bot.getBlockPos() + " interrupted=" + Thread.currentThread().isInterrupted());

        // Corner pillars are the most commonly missed placements.
        // Build them immediately after leveling, from diagonal outside stances, so later phases
        // don't mis-predict reach from interior stations.
        buildCornerBeamsEarly(world, source, bot, buildCenter, radius, wallHeight, counters);

        // CRITICAL: do not proceed until the foundation beams are definitely constructed.
        // If a corner is missing due to a terrain hole, later phases will oscillate and wall-hump.
        if (!ensureFoundationBeamsComplete(world, source, bot, buildCenter, radius, wallHeight, counters)) {
            logStageWarn("foundation", "unable to complete corner beams after retries; continuing best-effort");
            sendStageMessage("foundation-warning", "Hovel: WARNING - could not fully complete foundation beams (corners). Continuing best-effort.");
        }

        // Leveling + early scaffolds can still leave 1x1 floor holes if we never stand near them again.
        // Patch interior floor and remove clutter (grass/flowers) before walls reduce mobility.
        patchInteriorFloorAndClutter(world, source, bot, buildCenter, radius, counters, null);

        // Ensure the future doorway is traversable before later phases attempt exterior angles.
        ensureDoorwayOpen(world, source, bot, buildCenter, radius, doorSide);

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

        // Exterior walk patch is especially good at fixing the "single window" hole.
        if (countMissing(world, walls) > 0) {
            sendStageMessage("wall-exterior", "Hovel: exterior wall patch...");
            exteriorWallPerimeterPatch(world, source, bot, buildCenter, radius, walls, counters);
        }

        // Revisit any missing foundation beam segments after wall placements stabilize the footprint.
        patchPendingFoundationBeams(world, source, bot, buildCenter, radius, wallHeight, counters);

        List<BlockPos> allTargets = new ArrayList<>(walls.size() + roof.size());
        allTargets.addAll(walls);
        allTargets.addAll(roof);

        // If we still have missing roof/edge blocks, do a dedicated roof pass:
        // build a pillar just outside the hovel, climb above the roof plane, step onto the roof,
        // then walk the roof perimeter to place remaining blocks.
        if (radius >= 2 && countMissing(world, allTargets) > 0) {
            sendStageMessage("roof-perimeter", "Hovel: roof perimeter pass...");
            roofPerimeterPass(world, source, bot, buildCenter, radius, wallHeight, doorSide, allTargets, roof, counters);
        }

        // Final floor polish: remove clutter and fill any remaining holes inside the hovel.
        patchInteriorFloorAndClutter(world, source, bot, buildCenter, radius, counters, null);

        // Last angle sweep.
        if (countMissing(world, allTargets) > 0) {
            sendStageMessage("final-sweep-2", "Hovel: final sweep...");
            finalStationSweep(world, source, bot, buildCenter, radius, allTargets, counters, standableCache);
        }

        // If roof is still incomplete, fall back to scaffold-based roof patching (doesn't require stepping onto the roof).
        if (radius >= 2 && countMissing(world, roof) > 0) {
            sendStageMessage("roof-scaffold", "Hovel: roof patch (scaffolds)...");
            patchRoofWithScaffolds(world, source, bot, buildCenter, radius, wallHeight, roof, counters);
        }

        // True final leveling: fill holes AND shave any remaining raised blocks in the interior.
        // (Do this BEFORE amenities so we don't fight clutter/uneven terrain while placing doors/torches.)
        sendStageMessage("interior-level", "Hovel: interior cleanup...");
        if (isOutsideFootprint(bot.getBlockPos(), buildCenter, radius)) {
            cleanupRoofAccessPillars(world, source, bot, buildCenter, radius);
        }
        finalInteriorLevelingPass(world, source, bot, buildCenter, radius, wallHeight, doorSide);

        // Final amenities pass: door + torches, with retries.
        // These are mandatory usability invariants whenever the bot has the items.
        sendStageMessage("amenities", "Hovel: placing door/torches...");
        finalizeDoorAndTorches(world, source, bot, buildCenter, radius, doorSide);

        // Re-enforce doorway clearance and re-level once more to guarantee the end state.
        ensureDoorwayOpen(world, source, bot, buildCenter, radius, doorSide);
        finalInteriorLevelingPass(world, source, bot, buildCenter, radius, wallHeight, doorSide);

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
        long lastHeartbeatMs = startMs;
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
                lastHeartbeatMs = now;
            }
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
        List<RoofPillar> snapshot = new ArrayList<>(pendingRoofPillars);
        pendingRoofPillars.clear();
        ArrayList<RoofPillar> stillPending = new ArrayList<>();
        for (RoofPillar pillar : snapshot) {
            if (SkillManager.shouldAbortSkill(bot)) {
                pendingRoofPillars.addAll(stillPending);
                return;
            }
            if (pillar == null || pillar.base() == null) {
                continue;
            }
            BlockPos base = pillar.base();
            boolean cleared = false;
            BlockPos approach = findNearbyStandableFiltered(world, base, 4, (p) -> !isDoorwayReservedCell(p));
            if (approach != null) {
                moveToRingPosFast(source, bot, approach);
            }
            if (moveToRingPosFast(source, bot, base) || BlockInteractionService.canInteract(bot, base, REACH_DISTANCE_SQ)) {
                // Clear the bottom couple of blocks so the perimeter path isn't obstructed.
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos p = base.withY(base.getY() + dy);
                    if (world.getBlockState(p).isAir()) {
                        continue;
                    }
                    if (BlockInteractionService.canInteract(bot, p, REACH_DISTANCE_SQ)
                            && isPillarMaterial(world.getBlockState(p))) {
                        mineSoft(bot, p);
                        sleepQuiet(60L);
                        cleared = true;
                    }
                }
            }
            if (!cleared) {
                stillPending.add(pillar);
            }
        }
        pendingRoofPillars.addAll(stillPending);
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
            BlockPos approach = findNearbyStandableFiltered(world, base, 3, (p) -> !isDoorwayReservedCell(p));
            if (approach != null) {
                moveToRingPosFast(bot.getCommandSource(), bot, approach);
            }
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos p = base.withY(base.getY() + dy);
                if (!BlockInteractionService.canInteract(bot, p, REACH_DISTANCE_SQ)) {
                    continue;
                }
                if (isPillarMaterial(world.getBlockState(p))) {
                    mineSoft(bot, p);
                    sleepQuiet(60L);
                }
            }
            iter.remove();
        }
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

    private boolean roofPerimeterPass(ServerWorld world,
                                  ServerCommandSource source,
                                  ServerPlayerEntity bot,
                                  BlockPos center,
                                  int radius,
                                  int wallHeight,
                                  Direction doorSide,
                                  List<BlockPos> allTargets,
                                  List<BlockPos> roofBlueprint,
                                  BuildCounters counters) {
        if (world == null || source == null || bot == null || center == null || allTargets == null || roofBlueprint == null) {
            return false;
        }
        if (countMissing(world, roofBlueprint) == 0 && countMissing(world, allTargets) == 0) {
            return false;
        }

        // Prefer the opposite side of the door to avoid fighting the doorway/entry terrain.
        LinkedHashSet<Direction> sides = new LinkedHashSet<>();
        if (doorSide != null) {
            sides.add(doorSide.getOpposite());
            sides.add(doorSide.rotateYClockwise());
            sides.add(doorSide.rotateYCounterclockwise());
            sides.add(doorSide);
        }
        sides.add(Direction.EAST);
        sides.add(Direction.SOUTH);
        sides.add(Direction.WEST);
        sides.add(Direction.NORTH);

        boolean steppedOnRoof = false;
        for (Direction side : sides) {
            if (countMissing(world, allTargets) == 0) {
                return steppedOnRoof;
            }
            if (SkillManager.shouldAbortSkill(bot)) {
                return steppedOnRoof;
            }
            if (roofPerimeterPassOnSide(world, source, bot, center, radius, wallHeight, side, allTargets, roofBlueprint, counters)) {
                steppedOnRoof = true;
                break;
            }
        }
        return steppedOnRoof;
    }

    private boolean roofPerimeterPassOnSide(ServerWorld world,
                                           ServerCommandSource source,
                                           ServerPlayerEntity bot,
                                           BlockPos center,
                                           int radius,
                                           int wallHeight,
                                           Direction side,
                                           List<BlockPos> allTargets,
                                           List<BlockPos> roofBlueprint,
                                           BuildCounters counters) {
        if (world == null || source == null || bot == null || center == null || allTargets == null || roofBlueprint == null || side == null) {
            return false;
        }
        int missingBefore = countMissing(world, allTargets);

        int roofY = center.getY() + wallHeight;
        int roofStandY = roofY + 1;
        int towerTopY = roofStandY; // stand just above the roof plane; step sideways onto roof

        int standY = center.getY() + 1;

        BlockPos pillarSeed = center.offset(side, radius + 1).withY(standY);
        // Prefer a base that is not directly obstructed above the roof plane.
        // If the top "stand cell" is blocked, the bot may step onto the roof but be unable to return to the pillar,
        // which later devolves into unsafe roof jumps.
        BlockPos pillarBase = findNearbyStandableFiltered(world, pillarSeed, 6, (p) -> {
            if (isForbiddenScaffoldBase(p) || isScaffoldBaseUsed(p)) return false;
            // Must be outside the footprint on this side.
            int dx = p.getX() - center.getX();
            int dz = p.getZ() - center.getZ();
            if (side == Direction.EAST && dx < radius + 1) return false;
            if (side == Direction.WEST && dx > -(radius + 1)) return false;
            if (side == Direction.SOUTH && dz < radius + 1) return false;
            if (side == Direction.NORTH && dz > -(radius + 1)) return false;

            // Ensure the pillar top cell + headspace are clear enough to stand.
            BlockPos top = p.withY(towerTopY);
            BlockPos head = top.up();
            BlockState ts = world.getBlockState(top);
            BlockState hs = world.getBlockState(head);
            boolean topClear = ts.getCollisionShape(world, top).isEmpty() && (ts.isAir() || ts.isReplaceable() || ts.isIn(BlockTags.LEAVES));
            boolean headClear = hs.getCollisionShape(world, head).isEmpty() && (hs.isAir() || hs.isReplaceable() || hs.isIn(BlockTags.LEAVES));
            return topClear && headClear;
        });
        if (pillarBase == null) {
            return false;
        }
        ensureRingStandable(world, bot, pillarBase);
        if (!moveToRingPosFast(source, bot, pillarBase)) {
            return false;
        }

        // Entry point on the roof: directly adjacent inward from the pillar.
        BlockPos entryRoofBlock = center.offset(side, radius).withY(roofY);
        BlockPos entryStand = entryRoofBlock.up();
        BlockPos pillarTop = pillarBase.withY(towerTopY);

        List<BlockPos> pillar = new ArrayList<>();
        final boolean[] stepped = new boolean[] { false };
        final boolean[] fellOffRoof = new boolean[] { false };
        final boolean[] onPillarForTeardown = new boolean[] { false };
        try {
            withSneakLock(bot, () -> {
                if (SkillManager.shouldAbortSkill(bot)) {
                    return;
                }

                prepareScaffoldBase(world, bot);

                int climb = Math.max(0, towerTopY - bot.getBlockY());
                if (!pillarUp(bot, climb, pillar, true)) {
                    return;
                }

                // Widen the top of the roof access pillar to reduce the "1x1 edge" transfer failures.
                // This is a temporary platform (outside the footprint) and will be torn down with the pillar.
                try {
                    Direction cw = side.rotateYClockwise();
                    Direction ccw = side.rotateYCounterclockwise();
                    BlockPos topBlock = bot.getBlockPos().down(); // should be at roofY after climb
                    for (Direction d : List.of(cw, ccw)) {
                        BlockPos p = topBlock.offset(d);
                        int dx = p.getX() - center.getX();
                        int dz = p.getZ() - center.getZ();
                        boolean outsideFootprint = Math.abs(dx) > radius || Math.abs(dz) > radius;
                        if (!outsideFootprint) continue;
                        if (isMissing(world, p)) {
                            if (BotActions.placeBlockAt(bot, p, Direction.UP, PILLAR_BLOCKS)) {
                                pillar.add(p.toImmutable());
                                sleepQuiet(40L);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }

                // Always try to place from this high vantage, even if we fail to step onto the roof.
                placeUntilStalled(world, bot, allTargets, counters, 2);

                // Ensure we have a solid roof block to step onto.
                if (isMissing(world, entryRoofBlock)) {
                    placeBlockDirectIfWithinReach(bot, entryRoofBlock, counters);
                }
                if (isMissing(world, entryRoofBlock)) {
                    return;
                }

                // Step onto the roof (stand position is one above the roof block).
                if (!nudgeToStand(world, bot, entryStand, 1600L)) {
                    return;
                }
                stepped[0] = true;
                // Safety: re-assert sneaking after the transition onto the roof.
                BotActions.sneak(bot, true);

                // Clear any incidental clutter that could block small roof movements
                // (e.g., grass/flowers from an incomplete roof, or replaceables like snow layers).
                prepareScaffoldBase(world, bot);

                // Walk multiple roof rings (outer -> inner) and place missing blocks from multiple angles.
                // Limiting to a few inner rings keeps it safe/time-bounded while improving coverage.
                int ringMin = Math.max(0, radius - 6);
                for (int ring = radius; ring >= ringMin; ring -= 1) {
                    if (SkillManager.shouldAbortSkill(bot)) {
                        return;
                    }
                    if (countMissing(world, allTargets) == 0) {
                        break;
                    }

                    List<BlockPos> perimeter = HovelBlueprint.buildRoofPerimeter(center, ring, roofY);
                    // Rotate so we start near our entry (outer ring only).
                    int startIdx = 0;
                    if (ring == radius) {
                        for (int i = 0; i < perimeter.size(); i++) {
                            if (perimeter.get(i).equals(entryRoofBlock)) {
                                startIdx = i;
                                break;
                            }
                        }
                    }

                    for (int step = 0; step < perimeter.size(); step++) {
                        if (SkillManager.shouldAbortSkill(bot)) {
                            return;
                        }
                        if (step % 8 == 0 && countMissing(world, allTargets) == 0) {
                            return;
                        }

                        // If we fell off the roof plane, abort the roof-walk quickly.
                        // The later scaffold-based roof patch stage will finish the job without requiring roof traversal.
                        if (stepped[0] && bot.getBlockY() < roofY) {
                            fellOffRoof[0] = true;
                            return;
                        }

                        BlockPos roofBlock = perimeter.get((startIdx + step) % perimeter.size());
                        BlockPos stand = roofBlock.up();

                        // Before moving: ensure the roof block exists, otherwise we can't stand there.
                        if (isMissing(world, roofBlock)) {
                            placeBlockDirectIfWithinReach(bot, roofBlock, counters);
                        }
                        if (isMissing(world, roofBlock)) {
                            // Can't safely step here; skip.
                            continue;
                        }

                        // Try to move to this stand cell; if it fails, keep placing from where we are.
                        if (stepped[0] && bot.getBlockY() < roofY) {
                            fellOffRoof[0] = true;
                            return;
                        }
                        if (isStandable(world, stand)) {
                            nudgeToStand(world, bot, stand, 1200L);
                        }

                        // Place everything we can reach from here (repeat until no more progress).
                        placeUntilStalled(world, bot, allTargets, counters, 3);
                    }
                }

                // Snap-teleport only if we're on the roof plane and not already on the pillar.
                // This avoids disrupting the roof walk when the bot isn't actually on the roof.
                if (!isOnPillarTop(bot, pillarTop)
                        && isStandable(world, pillarTop)
                        && bot.getBlockY() >= roofY
                        && isInsideFootprint(bot.getBlockPos(), center, radius)) {
                    BotActions.stop(bot);
                    BotActions.sneak(bot, true);
                    bot.teleport(world,
                            pillarTop.getX() + 0.5D,
                            pillarTop.getY(),
                            pillarTop.getZ() + 0.5D,
                            EnumSet.noneOf(PositionFlag.class),
                            bot.getYaw(),
                            bot.getPitch(),
                            true);
                    bot.setVelocity(Vec3d.ZERO);
                    sleepQuiet(80L);
                }
                onPillarForTeardown[0] = isOnPillarTop(bot, pillarTop);
            });
        } finally {
            if (onPillarForTeardown[0]) {
                teardownScaffolding(bot, pillar, Collections.emptySet(), allTargets, counters);
            } else {
                // Safety: if we couldn't get back onto the pillar, do NOT tear it down.
                // Leaving a stray pillar is better than the bot attempting an unsafe roof leap.
                LOGGER.info("Roof pass: skipping pillar teardown because bot did not return to pillarTop (side={}, base={})", side, pillarBase);
                pendingRoofPillars.add(new RoofPillar(pillarBase.toImmutable(), towerTopY));
            }
        }

        if (stepped[0]) {
            int missingAfter = countMissing(world, allTargets);
            LOGGER.info("Roof perimeter pass succeeded on side={} base={} missing {} -> {}",
                    side, pillarBase, missingBefore, missingAfter);
        } else {
            int missingAfter = countMissing(world, allTargets);
            LOGGER.info("Roof perimeter pass could not step onto roof on side={} base={} missing {} -> {}",
                    side, pillarBase, missingBefore, missingAfter);
        }

        // Treat a fall as "we stepped onto the roof" so the caller doesn't keep attempting more roof sides.
        // Subsequent scaffold roof patching will handle any remaining holes.
        return stepped[0] || fellOffRoof[0];
    }

    private boolean nudgeToStand(ServerWorld world, ServerPlayerEntity bot, BlockPos stand, long timeoutMs) {
        if (world == null || bot == null || stand == null) {
            return false;
        }
        if (!isStandable(world, stand)) {
            return false;
        }
        Vec3d target = Vec3d.ofCenter(stand);
        long deadline = System.currentTimeMillis() + Math.max(250L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }
            // Horizontal-only convergence is far more reliable than full XYZ convergence
            // (player Y can fluctuate while on edges/slabs/steps).
            Vec3d pos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
            double dx = pos.x - target.x;
            double dz = pos.z - target.z;
            if ((dx * dx + dz * dz) <= 0.16D) { // ~0.4 blocks
                return true;
            }
            BotActions.moveToward(bot, target, 0.45);
            sleepQuiet(60L);
        }
        Vec3d pos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double dx = pos.x - target.x;
        double dz = pos.z - target.z;
        return (dx * dx + dz * dz) <= 0.25D;
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
        }
        return true;
    }

    private boolean waitForOnGround(ServerPlayerEntity bot, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
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
            double y = bot.getY();
            double vy = bot.getVelocity().y;
            if (y >= startY + 0.20D && vy <= 0.08D) {
                return;
            }
            sleepQuiet(25L);
        }
    }

    private void teardownScaffolding(ServerPlayerEntity bot, List<BlockPos> placed, Set<BlockPos> keepBlocks) {
        if (bot == null || placed == null || placed.isEmpty()) return;
        ServerWorld world = (ServerWorld) bot.getEntityWorld();

        // IMPORTANT: You cannot mine a tall pillar from the top by iterating the list;
        // blocks near the bottom are out of reach. Tear down by "mining the block under feet"
        // so every break is within reach and we descend naturally.
        withSneakLock(bot, () -> {
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
            }
        });
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
        if (bot == null || placed == null || placed.isEmpty()) return;
        if (checkTargets == null || checkTargets.isEmpty()) {
            teardownScaffolding(bot, placed, keepBlocks);
            return;
        }

        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        Set<BlockPos> scaffoldXZ = new HashSet<>();
        for (BlockPos p : placed) {
            if (p == null) continue;
            scaffoldXZ.add(canonicalXZ(p));
        }
        List<BlockPos> descentTargets = checkTargets.stream()
                .filter(p -> p != null)
                .filter(p -> {
                    BlockPos xz = canonicalXZ(p);
                    return xz == null || !scaffoldXZ.contains(xz);
                })
                .toList();
        withSneakLock(bot, () -> {
            ArrayList<BlockPos> stack = new ArrayList<>(placed);
            Collections.reverse(stack);

            int guard = Math.max(16, stack.size() * 4);
            while (guard-- > 0 && !stack.isEmpty() && !SkillManager.shouldAbortSkill(bot)) {
                BlockPos underFeet = bot.getBlockPos().down();
                int idx = stack.indexOf(underFeet);
                if (idx >= 0) {
                    BlockPos pos = stack.remove(idx);
                    if (keepBlocks != null && keepBlocks.contains(pos)) {
                        // Can't break this one.
                    } else if (!world.getBlockState(pos).isAir()) {
                        net.shasankp000.Entity.LookController.faceBlock(bot, pos);
                        mineSoft(bot, pos);
                        waitForYDecrease(bot, bot.getBlockY(), 900L);
                    }

                    // Each time we descend, re-check everything reachable, but avoid filling the scaffold column yet.
                    placeUntilStalled(world, bot, descentTargets, counters, 1);
                    continue;
                }

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

                placeUntilStalled(world, bot, descentTargets, counters, 1);
            }
        });

        // After we're down, allow filling any remaining targets (including the former scaffold column).
        placeUntilStalled(world, bot, checkTargets, counters, 2);
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

        // 0) Corner-first: always put up towers near the four OUTSIDE corners.
        // This tends to cover the largest roof area early, while avoiding interior-corner "spam pillars".
        // Build from a diagonal outside stance (better aim on corner roof blocks).
        int outside = radius + 2;
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
            ensureRingStandable(world, bot, base);
            if (!moveToRingPos(source, bot, base)) continue;

            scaffoldPlaceAndTeardown(world, bot, roofBlueprint, roofY + 2, counters, Collections.emptySet(), false);
        }

        // 1) Quick coverage from outside ring positions (cheap and usually accessible)
        List<BlockPos> ringBases = List.of(
                center.add(radius + 1, 0, radius + 1),
                center.add(-(radius + 1), 0, radius + 1),
                center.add(-(radius + 1), 0, -(radius + 1)),
                center.add(radius + 1, 0, -(radius + 1)),
                center.add(radius + 1, 0, 0),
                center.add(-(radius + 1), 0, 0),
                center.add(0, 0, radius + 1),
                center.add(0, 0, -(radius + 1))
        );

        for (BlockPos base0 : ringBases) {
            if (SkillManager.shouldAbortSkill(bot)) return;
            if (countMissing(world, roofBlueprint) == 0) return;

            BlockPos base = findNearbyStandableFiltered(world, base0.withY(standY), 4,
                    (p) -> !isForbiddenScaffoldBase(p));
            if (base == null) continue;
            ensureRingStandable(world, bot, base);
            if (!moveToRingPos(source, bot, base)) continue;

            // Stand ABOVE the roof plane. This reduces "corner hopping" and gives better angles.
            scaffoldPlaceAndTeardown(world, bot, roofBlueprint, roofY + 2, counters, Collections.emptySet(), false);
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
                exteriorSeed = new BlockPos(center.getX() + (side == Direction.EAST ? (radius + 1) : -(radius + 1)),
                        standY,
                        target.getZ());
            } else {
                exteriorSeed = new BlockPos(target.getX(),
                        standY,
                        center.getZ() + (side == Direction.SOUTH ? (radius + 1) : -(radius + 1)));
            }

                BlockPos base = findNearbyStandableFiltered(world, exteriorSeed, 6,
                    (p) -> !isForbiddenScaffoldBase(p));
            if (base == null) {
                // Fall back to any outside ring spot.
                base = findNearbyStandableFiltered(world, center.offset(side, radius + 1).withY(standY), 6,
                    (p) -> !isForbiddenScaffoldBase(p));
            }
            if (base == null) {
                return;
            }

            ensureRingStandable(world, bot, base);
            if (!moveToRingPos(source, bot, base)) {
                continue;
            }
            scaffoldPlaceAndTeardown(world, bot, roofBlueprint, roofY + 2, counters, keepIfRoof, false);
        }
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
        List<BlockPos> inRange = targets.stream()
                .filter(p -> isMissing(world, p))
                .filter(p -> !isDoorwayReservedCell(p))
                .filter(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p)) <= REACH_DISTANCE_SQ)
            .sorted(Comparator
                .comparingInt(BlockPos::getY)
                .thenComparingDouble(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p))))
                .toList();

        int placed = 0;
        for (BlockPos p : inRange) {
            if (SkillManager.shouldAbortSkill(bot)) return placed;
            if (placeBlockDirectIfWithinReach(bot, p, counters)) {
                placed++;
            }
        }
        return placed;
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
        if (a == null || b == null) return Double.MAX_VALUE;
        long dx = (long) a.getX() - (long) b.getX();
        long dz = (long) a.getZ() - (long) b.getZ();
        return (double) (dx * dx + dz * dz);
    }

    private int perimeterRingRadius(int radius) {
        if (radius <= 0) return 0;
        return Math.max(2, radius + PERIMETER_RING_OFFSET);
    }

    private boolean isInsideFootprint(BlockPos pos, BlockPos center, int radius) {
        if (pos == null || center == null || radius <= 0) return false;
        int dx = Math.abs(pos.getX() - center.getX());
        int dz = Math.abs(pos.getZ() - center.getZ());
        return dx < radius && dz < radius;
    }

    private boolean isOutsideFootprint(BlockPos pos, BlockPos center, int radius) {
        if (pos == null || center == null || radius <= 0) return false;
        int dx = Math.abs(pos.getX() - center.getX());
        int dz = Math.abs(pos.getZ() - center.getZ());
        return dx > radius || dz > radius;
    }

    private Direction resolveDoorSideForExit(ServerPlayerEntity bot) {
        if (activeDoorSide != null) return activeDoorSide;
        if (bot == null) return Direction.NORTH;
        if (activeBuildCenter == null || activeRadius <= 0) {
            return bot.getHorizontalFacing();
        }
        BlockPos pos = bot.getBlockPos();
        Direction best = bot.getHorizontalFacing();
        double bestSq = Double.MAX_VALUE;
        for (Direction dir : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            BlockPos outside = activeBuildCenter.offset(dir, activeRadius + 1).withY(pos.getY());
            double d = distSqXZ(pos, outside);
            if (d < bestSq) {
                bestSq = d;
                best = dir;
            }
        }
        return best;
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
        if (world == null || source == null || bot == null || center == null || doorSide == null) {
            return false;
        }
        int standY = center.getY() + 1;
        BlockPos outsideFront = center.offset(doorSide, radius + 1).withY(standY);
        BlockPos insideFront = center.offset(doorSide, Math.max(1, radius - 1)).withY(standY);

        BlockPos outside = findNearbyStandable(world, outsideFront, 6);
        if (outside == null) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(outside) > 1.0D) {
            if (!moveToRingPosFast(source, bot, outside)) {
                return false;
            }
        }
        clearDoorwayNearby(world, bot, center, radius, doorSide);

        BlockPos inside = findNearbyStandable(world, insideFront, 6);
        if (inside == null) {
            return false;
        }
        if (!directMove(source, bot, inside) && !pathMove(source, bot, inside)) {
            return false;
        }
        clearDoorwayNearby(world, bot, center, radius, doorSide);
        return true;
    }

    private boolean exitInteriorViaDoor(ServerWorld world,
                                        ServerCommandSource source,
                                        ServerPlayerEntity bot,
                                        BlockPos center,
                                        int radius,
                                        Direction doorSide) {
        if (world == null || source == null || bot == null || center == null || doorSide == null) {
            return false;
        }

        int standY = center.getY() + 1;
        BlockPos insideFront = center.offset(doorSide, Math.max(1, radius - 1)).withY(standY);
        BlockPos outsideFront = center.offset(doorSide, radius + 1).withY(standY);

        BlockPos insideStand = findNearbyStandable(world, insideFront, 5);
        if (insideStand == null) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(insideStand) > 1.0D) {
            if (!moveToBuildSiteAllowPathing(source, bot, insideStand)) {
                return false;
            }
        }
        clearDoorwayNearby(world, bot, center, radius, doorSide);

        BlockPos outsideStand = findNearbyStandable(world, outsideFront, 6);
        if (outsideStand == null) {
            return false;
        }
        ensureRingStandable(world, bot, outsideStand);
        if (!directMove(source, bot, outsideStand) && !pathMove(source, bot, outsideStand)) {
            return false;
        }
        clearDoorwayNearby(world, bot, center, radius, doorSide);
        return true;
    }

    private void clearDoorwayNearby(ServerWorld world,
                                    ServerPlayerEntity bot,
                                    BlockPos center,
                                    int radius,
                                    Direction doorSide) {
        if (world == null || bot == null || center == null || doorSide == null) return;
        int standY = center.getY() + 1;
        BlockPos doorBase = center.offset(doorSide, radius).withY(standY);
        BlockPos doorUpper = doorBase.up();
        BlockPos insideFront = doorBase.offset(doorSide.getOpposite());
        BlockPos outsideFront = doorBase.offset(doorSide);

        for (BlockPos p : List.of(doorBase, doorUpper, insideFront, insideFront.up(), outsideFront, outsideFront.up())) {
            if (p == null) continue;
            if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) > REACH_DISTANCE_SQ) continue;
            BlockState s = world.getBlockState(p);
            if (s.isAir()) continue;
            if (s.getBlock() instanceof net.minecraft.block.DoorBlock) continue;
            mineSoft(bot, p);
        }
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

        int standY = activeBuildCenter.getY() + 1;
        int ringRadius = perimeterRingRadius(activeRadius);
        List<BlockPos> loop = HovelBlueprint.buildGroundPerimeter(activeBuildCenter, ringRadius, standY);
        if (loop.isEmpty()) {
            return false;
        }

        BlockPos here = bot.getBlockPos();

        int startIdx = 0;
        int goalIdx = 0;
        double bestStart = Double.MAX_VALUE;
        double bestGoal = Double.MAX_VALUE;
        for (int i = 0; i < loop.size(); i++) {
            BlockPos p = loop.get(i);
            double ds = distSqXZ(here, p);
            if (ds < bestStart) {
                bestStart = ds;
                startIdx = i;
            }
            double dg = distSqXZ(destination, p);
            if (dg < bestGoal) {
                bestGoal = dg;
                goalIdx = i;
            }
        }

        // If we're already basically at the goal ring index, don't waste time looping.
        if (startIdx == goalIdx) {
            return false;
        }

        int size = loop.size();
        int cw = (goalIdx - startIdx + size) % size;
        int ccw = (startIdx - goalIdx + size) % size;
        int dir = cw <= ccw ? 1 : -1;
        int remaining = Math.min(cw, ccw);

        // First: step to the nearest loop cell if we're not close (helps "snap" onto the red path).
        BlockPos entrySeed = loop.get(startIdx);
        BlockPos entry = findNearbyStandable(world, entrySeed, 4);
        if (entry != null && distSqXZ(here, entry) > 1.0D) {
            ensureRingStandable(world, bot, entry);
            if (!moveToRingWaypoint(source, bot, entry, false)) {
                return false;
            }
        }

        // Walk along the loop in a handful of hops instead of dozens of single-tile steps.
        int hops = 0;
        int idx = startIdx;
        int maxHops = Math.min(24, Math.max(8, remaining + 4));
        int stride = 4;
        while (hops < maxHops && remaining > 0 && !SkillManager.shouldAbortSkill(bot)) {
            int stepWanted = Math.min(stride, remaining);

            // Pick a standable waypoint for this hop.
            // Try shorter steps first, then scan a bit farther ahead to route around small terrain irregularities.
            int chosenStep = -1;
            int candIdx = idx;
            BlockPos chosenStand = null;

            for (int stepTry = stepWanted; stepTry >= 1; stepTry--) {
                int ci = (idx + dir * stepTry) % size;
                if (ci < 0) ci += size;
                BlockPos seed = loop.get(ci);
                BlockPos stand = findNearbyStandable(world, seed, 4);
                if (stand != null) {
                    chosenStep = stepTry;
                    candIdx = ci;
                    chosenStand = stand;
                    break;
                }
            }
            if (chosenStand == null) {
                int scan = Math.min(6, remaining);
                for (int stepTry = 1; stepTry <= scan; stepTry++) {
                    int ci = (idx + dir * stepTry) % size;
                    if (ci < 0) ci += size;
                    BlockPos seed = loop.get(ci);
                    BlockPos stand = findNearbyStandable(world, seed, 4);
                    if (stand != null) {
                        chosenStep = stepTry;
                        candIdx = ci;
                        chosenStand = stand;
                        break;
                    }
                }
            }
            if (chosenStand == null) {
                // No viable waypoint ahead; caller can fall back to pathing or another strategy.
                return false;
            }

            ensureRingStandable(world, bot, chosenStand);
            boolean ok = moveToRingWaypoint(source, bot, chosenStand, false);
            if (!ok) {
                // Don't commit to the index advance; reduce stride and try a smaller/nearer hop.
                stride = Math.max(1, stride - 1);
                hops++;
                continue;
            }

            // Commit progress.
            idx = candIdx;
            remaining -= chosenStep;
            hops++;

            // If we made it to the ring point nearest the destination, stop looping.
            if (idx == goalIdx) {
                break;
            }
        }

        return idx == goalIdx;
    }

    private boolean directMove(ServerCommandSource source, ServerPlayerEntity bot, BlockPos dest) {
        if (source == null || bot == null || dest == null) return false;
        MovementService.MovementPlan direct = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                dest,
                dest,
                null,
                null,
                Direction.UP
        );
        MovementService.MovementResult res = MovementService.execute(source, bot, direct, false, true, false, false);
        return res.success();
    }

    private boolean pathMove(ServerCommandSource source, ServerPlayerEntity bot, BlockPos dest) {
        if (source == null || bot == null || dest == null) return false;
        var planOpt = MovementService.planLootApproach(bot, dest, MovementService.MovementOptions.skillLoot());
        return planOpt.isPresent() && MovementService.execute(source, bot, planOpt.get(), false, true, false, false).success();
    }

    private boolean moveToRingWaypoint(ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       BlockPos ringPos,
                                       boolean allowDirectFallback) {
        if (source == null || bot == null || ringPos == null) return false;
        if (bot.getBlockPos().getSquaredDistance(ringPos) <= 1.0D) return true;
        if (pathMove(source, bot, ringPos)) {
            return true;
        }
        if (allowDirectFallback && directMove(source, bot, ringPos)) {
            return true;
        }
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        return world != null && nudgeToStandWithJump(world, bot, ringPos, 2200L);
    }

    private boolean moveToRingPos(ServerCommandSource source, ServerPlayerEntity bot, BlockPos ringPos) {
        if (source == null || bot == null || ringPos == null) return false;
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
        int outer = radius + 1;

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
                    boolean inOuterRing = !inFootprint && (Math.abs(dx) == outer || Math.abs(dz) == outer);
                    // Only touch the hovel footprint + one-block buffer ring.
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
                        for (int y = floorY + 2; y >= floorY + 1; y--) {
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
                    int headTop = inOuterRing ? floorY + 2 : floorY + 3;
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
        if (world == null || center == null) return 0;
        int start = center.getY();
        for (int y = start; y >= start - 6; y--) {
            BlockPos p = new BlockPos(center.getX(), y, center.getZ());
            if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
            BlockState s = world.getBlockState(p);
            if (!world.getFluidState(p).isEmpty()) continue;
            if (!s.getCollisionShape(world, p).isEmpty()) {
                return y;
            }
        }
        return start - 1;
    }

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
        if (!BlockInteractionService.canInteract(bot, pos, REACH_DISTANCE_SQ)) return;
        try { MiningTool.mineBlock(bot, pos).get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
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
            MovementService.MovementResult directRes = MovementService.execute(source, bot, direct, false, true, false, false);
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
                    MovementService.MovementResult res = MovementService.execute(source, bot, directAlt, false, true, false, false);
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
        return planOpt.isPresent() && MovementService.execute(source, bot, planOpt.get(), false, true, false, false).success();
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
        int standY = center.getY() + 1;
        int ringRadius = perimeterRingRadius(radius);
        List<BlockPos> loop = HovelBlueprint.buildGroundPerimeter(center, ringRadius, standY);
        if (loop.isEmpty()) return false;

        BlockPos here = bot.getBlockPos();
        int startIdx = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < loop.size(); i++) {
            BlockPos p = loop.get(i);
            double d = here.getSquaredDistance(p);
            if (d < best) {
                best = d;
                startIdx = i;
            }
        }

        // Turning the corner is the goal. Find the next corner ahead on the loop.
        ArrayList<Integer> corners = new ArrayList<>(4);
        for (int i = 0; i < loop.size(); i++) {
            BlockPos p = loop.get(i);
            int dx = p.getX() - center.getX();
            int dz = p.getZ() - center.getZ();
            if (Math.abs(dx) == ringRadius && Math.abs(dz) == ringRadius) {
                corners.add(i);
            }
        }

        // Fallback: if corners weren't detected (shouldn't happen), do a short arc and hope.
        if (corners.isEmpty()) {
            int maxSteps = Math.min(loop.size(), 14);
            int progressed = 0;
            for (int i = 0; i < maxSteps && !SkillManager.shouldAbortSkill(bot); i++) {
                BlockPos seed = loop.get((startIdx + i) % loop.size());
                BlockPos stand = findNearbyStandable(world, seed.withY(standY), 3);
                if (stand == null) continue;
                ensureRingStandable(world, bot, stand);
                if (moveToRingWaypoint(source, bot, stand, false)) {
                    progressed++;
                    if (progressed >= 4) return true;
                }
            }
            return false;
        }

        int targetCornerIdx = corners.get(0);
        int bestDelta = Integer.MAX_VALUE;
        for (int ci : corners) {
            int delta = ci >= startIdx ? (ci - startIdx) : (loop.size() - startIdx + ci);
            if (delta == 0) continue;
            if (delta < bestDelta) {
                bestDelta = delta;
                targetCornerIdx = ci;
            }
        }

        // 1) Try to reach the corner.
        BlockPos corner = loop.get(targetCornerIdx);
        BlockPos cornerStand = findNearbyStandable(world, corner.withY(standY), 4);
        if (cornerStand == null) {
            return false;
        }
        ensureRingStandable(world, bot, cornerStand);
        if (!moveToRingWaypoint(source, bot, cornerStand, false)) {
            return false;
        }

        // 2) Take a couple more steps past the corner so we really "turn" and don't just jitter at it.
        int progressed = 0;
        for (int i = 1; i <= 4 && !SkillManager.shouldAbortSkill(bot); i++) {
            BlockPos seed = loop.get((targetCornerIdx + i) % loop.size());
            BlockPos stand = findNearbyStandable(world, seed.withY(standY), 3);
            if (stand == null) continue;
            ensureRingStandable(world, bot, stand);
            if (moveToRingWaypoint(source, bot, stand, false)) {
                progressed++;
                if (progressed >= 2) {
                    break;
                }
            }
        }
        return true;
    }

    private boolean moveToBuildSiteWithPerimeterFallback(ServerWorld world,
                                                        ServerCommandSource source,
                                                        ServerPlayerEntity bot,
                                                        BlockPos dest,
                                                        BlockPos buildCenter,
                                                        int radius) {
        if (source == null || bot == null || dest == null) return false;
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
            MovementService.MovementResult directRes = MovementService.execute(source, bot, direct, false, true, false, false);
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
                    MovementService.MovementResult res = MovementService.execute(source, bot, directAlt, false, true, false, false);
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
        while (System.currentTimeMillis() < deadline && bot.getBlockY() <= fromY) {
            sleepQuiet(50L);
        }
    }

    private void waitForYDecrease(ServerPlayerEntity bot, int fromY, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && bot.getBlockY() >= fromY) {
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
        return new HovelPlan(origin, preferredDoorSide != null ? preferredDoorSide : bot.getHorizontalFacing());
    }
}
