package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
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
        BlockPos center = plan.center();
        Direction doorSide = plan.doorSide();

        if (!moveToBuildSite(source, bot, center)) {
            return SkillExecutionResult.failure("I could not reach a safe spot to build a hovel.");
        }

        BuildCounters counters = new BuildCounters();
        ChatUtils.sendSystemMessage(source, "Hovel: leveling build site...");
        levelBuildSite(world, source, bot, center, radius, wallHeight, counters);
        
        int needed = estimatePlacementNeed(world, center, radius, wallHeight, doorSide);
        if (countBuildBlocks(bot) < needed) {
            ensureBuildStock(source, bot, needed, center);
        }

        List<BlockPos> walls = HovelBlueprint.generateWallBlueprint(center, radius, wallHeight, doorSide);
        List<BlockPos> roof = HovelBlueprint.generateRoofBlueprint(center, radius, wallHeight);

        StandableCache standableCache = new StandableCache();

        // Build targets in a stable, layer-by-layer scaffold loop.
        // Key behavior change:
        // - Move to a station ONCE
        // - Stop + stabilize
        // - Scaffold upward one layer at a time
        // - At each layer: place everything within reach
        // - Teardown scaffold only after the station is exhausted
        ChatUtils.sendSystemMessage(source, "Hovel: building walls (layer-by-layer scaffolding)...");
        buildByStationsLayered(world, source, bot, center, radius, wallHeight, walls, counters, standableCache);

        // Quick sweep for wall stragglers from multiple angles.
        if (countMissing(world, walls) > 0) {
            ChatUtils.sendSystemMessage(source, "Hovel: wall finishing sweep...");
            finalStationSweep(world, source, bot, center, radius, walls, counters, standableCache);
        }

        // Exterior walk patch is especially good at fixing the "single window" hole.
        if (countMissing(world, walls) > 0) {
            ChatUtils.sendSystemMessage(source, "Hovel: exterior wall patch...");
            exteriorWallPerimeterPatch(world, source, bot, center, radius, walls, counters);
        }

        ChatUtils.sendSystemMessage(source, "Hovel: building roof...");
        buildByStationsLayered(world, source, bot, center, radius, wallHeight, roof, counters, standableCache);

        List<BlockPos> allTargets = new ArrayList<>(walls.size() + roof.size());
        allTargets.addAll(walls);
        allTargets.addAll(roof);

        // One more non-scaffold sweep from stations to catch stragglers without per-block movement.
        if (countMissing(world, allTargets) > 0) {
            ChatUtils.sendSystemMessage(source, "Hovel: finishing sweep...");
            finalStationSweep(world, source, bot, center, radius, allTargets, counters, standableCache);
        }

        // If we still have missing roof/edge blocks, do a dedicated roof pass:
        // build a pillar just outside the hovel, climb above the roof plane, step onto the roof,
        // then walk the roof perimeter to place remaining blocks.
        if (radius >= 2 && countMissing(world, allTargets) > 0) {
            ChatUtils.sendSystemMessage(source, "Hovel: roof perimeter pass...");
            roofPerimeterPass(world, source, bot, center, radius, wallHeight, doorSide, allTargets, roof, counters);
        }

        // Patch any missing base-layer wall blocks (common last-mile failure).
        if (countMissing(world, allTargets) > 0) {
            ChatUtils.sendSystemMessage(source, "Hovel: base perimeter patch...");
            basePerimeterPatch(world, source, bot, center, radius, doorSide, allTargets, counters);
        }

        // Last angle sweep.
        if (countMissing(world, allTargets) > 0) {
            ChatUtils.sendSystemMessage(source, "Hovel: final sweep...");
            finalStationSweep(world, source, bot, center, radius, allTargets, counters, standableCache);
        }

        // If roof is still incomplete, fall back to scaffold-based roof patching (doesn't require stepping onto the roof).
        if (radius >= 2 && countMissing(world, roof) > 0) {
            ChatUtils.sendSystemMessage(source, "Hovel: roof patch (scaffolds)...");
            patchRoofWithScaffolds(world, source, bot, center, radius, wallHeight, roof, counters);
        }

        ensureDoorwayOpen(world, source, bot, center, radius, doorSide);
        placeDoorIfAvailable(world, source, bot, center, radius, doorSide);
        placeWallTorches(world, source, bot, center, radius);

        int missing = countMissing(world, walls) + countMissing(world, roof);
        LOGGER.info("Hovel build finished. Missing: {}, Placed: {}, Attempts: {}, ReachFail: {}, NoMat: {}",
                missing, counters.placedBlocks, counters.attemptedPlacements, counters.reachFailures, counters.noMaterials);
        if (missing == 0) {
            sweepDrops(source, radius + 5, 6.0, 60, 5000L);
            ChatUtils.sendSystemMessage(source, "Hovel complete!");
            return SkillExecutionResult.success("Hovel built.");
        } else {
            ChatUtils.sendSystemMessage(source, "Hovel mostly complete (" + missing + " blocks missing).");
            return SkillExecutionResult.failure("Hovel incomplete.");
        }
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

        BlockPos pillarSeed = center.offset(side, radius + 1).withY(center.getY());
        BlockPos pillarBase = findNearbyStandable(world, pillarSeed, 5);
        if (pillarBase == null) {
            return false;
        }
        ensureRingStandable(world, bot, pillarBase);
        if (!moveToBuildSite(source, bot, pillarBase)) {
            return false;
        }

        // Entry point on the roof: directly adjacent inward from the pillar.
        BlockPos entryRoofBlock = center.offset(side, radius).withY(roofY);
        BlockPos entryStand = entryRoofBlock.up();
        BlockPos pillarTop = pillarBase.withY(towerTopY);

        List<BlockPos> pillar = new ArrayList<>();
        final boolean[] stepped = new boolean[] { false };
        final boolean[] onPillarForTeardown = new boolean[] { false };
        try {
            withSneakLock(bot, () -> {
                if (SkillManager.shouldAbortSkill(bot)) {
                    return;
                }

                prepareScaffoldBase(world, bot);

                int climb = Math.max(0, towerTopY - bot.getBlockY());
                if (!pillarUp(bot, climb, pillar)) {
                    return;
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
                        if (isStandable(world, stand)) {
                            nudgeToStand(world, bot, stand, 1200L);
                        }

                        // Place everything we can reach from here (repeat until no more progress).
                        placeUntilStalled(world, bot, allTargets, counters, 3);
                    }
                }

                // Return to the roof entry point before stepping back to the pillar.
                // This is much more reliable than trying to cross the roof diagonally from an inner ring.
                if (isStandable(world, entryStand)) {
                    nudgeToStand(world, bot, entryStand, 1500L);
                }

                // Try to get back to the pillar top so we can tear it down cleanly.
                if (isStandable(world, pillarTop)) {
                    if (!nudgeToStand(world, bot, pillarTop, 1200L)) {
                        // One retry from the entry.
                        if (isStandable(world, entryStand)) {
                            nudgeToStand(world, bot, entryStand, 1200L);
                        }
                        nudgeToStand(world, bot, pillarTop, 1200L);
                    }
                }

                // Only tear down if we are safely back on the pillar top.
                onPillarForTeardown[0] = bot.getBlockPos().withY(towerTopY).equals(pillarTop)
                        || bot.getBlockPos().equals(pillarTop)
                        || bot.getBlockPos().getSquaredDistance(pillarTop) <= 1.0D;
            });
        } finally {
            if (onPillarForTeardown[0]) {
                teardownScaffolding(bot, pillar, Collections.emptySet(), allTargets, counters);
            } else {
                // Safety: if we couldn't get back onto the pillar, do NOT tear it down.
                // Leaving a stray pillar is better than the bot attempting an unsafe roof leap.
                LOGGER.info("Roof pass: skipping pillar teardown because bot did not return to pillarTop (side={}, base={})", side, pillarBase);
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

        return stepped[0];
    }

    private void basePerimeterPatch(ServerWorld world,
                                   ServerCommandSource source,
                                   ServerPlayerEntity bot,
                                   BlockPos center,
                                   int radius,
                                   Direction doorSide,
                                   List<BlockPos> allTargets,
                                   BuildCounters counters) {
        if (world == null || source == null || bot == null || center == null || allTargets == null) {
            return;
        }
        if (radius <= 0) {
            return;
        }

        int floorY = center.getY();
        List<BlockPos> baseLayer = HovelBlueprint.generateWallBlueprint(center, radius, 1, doorSide);
        // Supports directly under base-layer wall blocks (prevents "floating" edges / holes from leveling).
        List<BlockPos> supports = baseLayer.stream().map(BlockPos::down).distinct().toList();

        ArrayList<BlockPos> patchTargets = new ArrayList<>(supports.size() + baseLayer.size());
        patchTargets.addAll(supports);
        patchTargets.addAll(baseLayer);

        // Walk the outside ring; from each side, place what we can reach.
        // Using a slightly farther ring improves angles for some placements.
        List<BlockPos> ringSeeds = HovelBlueprint.buildOuterRingSeeds(center, radius + 2);
        for (BlockPos seed : ringSeeds) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (countMissing(world, allTargets) == 0) {
                return;
            }

            BlockPos base = findNearbyStandable(world, seed.withY(floorY), 5);
            if (base == null) {
                continue;
            }
            ensureRingStandable(world, bot, base);
            if (!moveToBuildSite(source, bot, base)) {
                continue;
            }
            prepareScaffoldBase(world, bot);
            placeUntilStalled(world, bot, patchTargets, counters, 2);
        }

        // Also place from the interior (helps if holes are on inside corners).
        BlockPos inside = findNearbyStandable(world, center.withY(floorY), 5);
        if (inside != null && moveToBuildSite(source, bot, inside)) {
            prepareScaffoldBase(world, bot);
            placeUntilStalled(world, bot, patchTargets, counters, 2);
        }
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
        int floorY = center.getY();
        int roofY = center.getY() + wallHeight;
        // Standing at roofY-1 is generally the best compromise for placing roof blocks at roofY.
        int targetStandY = roofY - 1;

        // Standing slightly farther from the walls improves placement angles and reduces collision issues.
        int stationDist = radius + 2;

        LinkedHashSet<BlockPos> stationSeeds = new LinkedHashSet<>();
        stationSeeds.add(center);

        // Interior stations (these were historically the most reliable for fixing mid-wall slits/holes).
        int inner = Math.max(0, radius - 1);
        stationSeeds.add(center.add(inner, 0, inner));
        stationSeeds.add(center.add(-inner, 0, inner));
        stationSeeds.add(center.add(-inner, 0, -inner));
        stationSeeds.add(center.add(inner, 0, -inner));
        stationSeeds.add(center.add(inner, 0, 0));
        stationSeeds.add(center.add(-inner, 0, 0));
        stationSeeds.add(center.add(0, 0, inner));
        stationSeeds.add(center.add(0, 0, -inner));

        // Outer ring stations (handle larger radii / awkward terrain)
        stationSeeds.add(center.add(stationDist, 0, stationDist));
        stationSeeds.add(center.add(-stationDist, 0, stationDist));
        stationSeeds.add(center.add(-stationDist, 0, -stationDist));
        stationSeeds.add(center.add(stationDist, 0, -stationDist));
        stationSeeds.add(center.add(stationDist, 0, 0));
        stationSeeds.add(center.add(-stationDist, 0, 0));
        stationSeeds.add(center.add(0, 0, stationDist));
        stationSeeds.add(center.add(0, 0, -stationDist));

        for (BlockPos seed : stationSeeds) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (countMissing(world, allTargets) == 0) {
                return;
            }

            BlockPos seedY = seed.withY(floorY);
            BlockPos base = (standableCache == null)
                    ? findNearbyStandable(world, seedY, 5)
                    : standableCache.resolve(world, seedY, 5, (s) -> findNearbyStandable(world, s, 5));
            if (base == null) {
                continue;
            }
            if (!moveToBuildSite(source, bot, base)) {
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
        int floorY = center.getY();
        int stationDist = radius + 2;
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
            BlockPos seedY = seed.withY(floorY);
            BlockPos base = (standableCache == null)
                    ? findNearbyStandable(world, seedY, 5)
                    : standableCache.resolve(world, seedY, 5, (s) -> findNearbyStandable(world, s, 5));
            if (base == null) {
                continue;
            }
            if (!moveToBuildSite(source, bot, base)) {
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

                    if (!pillarUp(bot, 1, pillar)) {
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

    private boolean pillarUp(ServerPlayerEntity bot, int steps, List<BlockPos> placed) {
        if (steps <= 0) return true;
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
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

                    // Each time we descend, re-check everything reachable.
                    placeUntilStalled(world, bot, checkTargets, counters, 1);
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

                placeUntilStalled(world, bot, checkTargets, counters, 1);
            }
        });
    }

    private boolean isMissing(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW) || state.isOf(Blocks.SNOW_BLOCK);
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
        if (countMissing(world, roofBlueprint) == 0) {
            return;
        }

        // 0) Corner-first: always put up towers near the four OUTSIDE corners.
        // This tends to cover the largest roof area early, while avoiding interior-corner "spam pillars".
        int outside = radius + 1;
        Set<BlockPos> cornerBaseSet = new LinkedHashSet<>();
        cornerBaseSet.add(center.add(outside, 0, outside));
        cornerBaseSet.add(center.add(-outside, 0, outside));
        cornerBaseSet.add(center.add(-outside, 0, -outside));
        cornerBaseSet.add(center.add(outside, 0, -outside));
        List<BlockPos> cornerBases = new ArrayList<>(cornerBaseSet);
        for (BlockPos base0 : cornerBases) {
            if (SkillManager.shouldAbortSkill(bot)) return;
            if (countMissing(world, roofBlueprint) == 0) return;

            BlockPos base = findNearbyStandable(world, base0.withY(center.getY()), 3);
            if (base == null) continue;
            ensureRingStandable(world, bot, base);
            if (!moveToBuildSite(source, bot, base)) continue;

            scaffoldPlaceAndTeardown(world, bot, roofBlueprint, roofY + 2, counters, Collections.emptySet());
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

            BlockPos base = findNearbyStandable(world, base0.withY(center.getY()), 4);
            if (base == null) continue;
            ensureRingStandable(world, bot, base);
            if (!moveToRingPos(source, bot, base)) continue;

            // Stand ABOVE the roof plane. This reduces "corner hopping" and gives better angles.
            scaffoldPlaceAndTeardown(world, bot, roofBlueprint, roofY + 2, counters, Collections.emptySet());
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

            BlockPos target = roofBlueprint.stream().filter(p -> isMissing(world, p)).findFirst().orElse(null);
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
                        center.getY(),
                        target.getZ());
            } else {
                exteriorSeed = new BlockPos(target.getX(),
                        center.getY(),
                        center.getZ() + (side == Direction.SOUTH ? (radius + 1) : -(radius + 1)));
            }

            BlockPos base = findNearbyStandable(world, exteriorSeed, 6);
            if (base == null) {
                // Fall back to any outside ring spot.
                base = findNearbyStandable(world, center.offset(side, radius + 1).withY(center.getY()), 6);
            }
            if (base == null) {
                return;
            }

            ensureRingStandable(world, bot, base);
            if (!moveToBuildSite(source, bot, base)) {
                continue;
            }
            scaffoldPlaceAndTeardown(world, bot, roofBlueprint, roofY + 2, counters, keepIfRoof);
        }
    }

    private void scaffoldPlaceAndTeardown(ServerWorld world,
                                         ServerPlayerEntity bot,
                                         List<BlockPos> targets,
                                         int standY,
                                         BuildCounters counters,
                                         Set<BlockPos> keepBlocks) {
        withSneakLock(bot, () -> {
            int steps = standY - bot.getBlockY();
            if (steps <= 0) {
                placeUntilStalled(world, bot, targets, counters, 2);
                return;
            }

            List<BlockPos> pillar = new ArrayList<>();
            if (!pillarUp(bot, steps, pillar)) {
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

    private boolean moveToRingPos(ServerCommandSource source, ServerPlayerEntity bot, BlockPos ringPos) {
        if (bot.getBlockPos().getSquaredDistance(ringPos) <= 1.0D) return true;
        MovementService.MovementPlan direct = new MovementService.MovementPlan(MovementService.Mode.DIRECT, ringPos, ringPos, null, null, Direction.UP);
        MovementService.MovementResult res = MovementService.execute(source, bot, direct, false, true, true, false);
        if (res.success()) return true;
        var planOpt = MovementService.planLootApproach(bot, ringPos, MovementService.MovementOptions.skillLoot());
        return planOpt.isPresent() && MovementService.execute(source, bot, planOpt.get(), false, true, true, false).success();
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
        if (!world.getBlockState(ringPos).getCollisionShape(world, ringPos).isEmpty()) mineSoft(bot, ringPos);
        if (!world.getBlockState(ringPos.up()).getCollisionShape(world, ringPos.up()).isEmpty()) mineSoft(bot, ringPos.up());
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

        int floorY = center.getY();
        int inner = Math.max(0, radius - 1);

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
                return;
            }
            BlockPos base = findNearbyStandable(world, seed.withY(floorY), 4);
            if (base == null) {
                continue;
            }
            if (source != null && !moveToBuildSite(source, bot, base)) {
                continue;
            }

            // Local leveling around this station.
            int localR = 4;
            for (int x = base.getX() - localR; x <= base.getX() + localR; x++) {
                for (int z = base.getZ() - localR; z <= base.getZ() + localR; z++) {
                    int dx = x - center.getX();
                    int dz = z - center.getZ();

                    // Only touch the hovel footprint.
                    if (Math.abs(dx) > radius || Math.abs(dz) > radius) {
                        continue;
                    }

                    boolean isInterior = Math.abs(dx) < radius && Math.abs(dz) < radius;
                    if (!isInterior) {
                        // Don't landscape outside the perimeter; it's how we end up with stray exterior blocks.
                        continue;
                    }

                    BlockPos below = new BlockPos(x, floorY - 1, z);
                    if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                        BotActions.placeBlockAt(bot, below, Direction.UP, PILLAR_BLOCKS);
                    }

                    // Shave any mounds above the intended floor.
                    // Keep this bounded so we don't spend forever on large hills.
                    int top = floorY + Math.max(4, wallHeight);
                    for (int y = top; y >= floorY + 1; y--) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!world.getBlockState(p).getCollisionShape(world, p).isEmpty()) {
                            mineSoft(bot, p);
                        }
                    }

                    // Fill holes / make the floor solid at floorY.
                    BlockPos floor = new BlockPos(x, floorY, z);
                    BlockState floorState = world.getBlockState(floor);
                    if (floorState.isReplaceable()) {
                        mineSoft(bot, floor);
                        floorState = world.getBlockState(floor);
                    }
                    if (floorState.isAir() || !world.getFluidState(floor).isEmpty()) {
                        BotActions.placeBlockAt(bot, floor, Direction.UP, PILLAR_BLOCKS);
                    }

                    // Clear minimal headspace.
                    for (int y = floorY + 1; y <= floorY + 3; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!world.getBlockState(p).getCollisionShape(world, p).isEmpty()) {
                            mineSoft(bot, p);
                        }
                    }
                }
            }
        }
    }

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

        int floorY = center.getY();
        List<BlockPos> path = HovelBlueprint.buildGroundPerimeter(center, radius + 2, floorY);
        for (BlockPos seed : path) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (countMissing(world, wallTargets) == 0) {
                return;
            }
            BlockPos stand = findNearbyStandable(world, seed.withY(floorY), 3);
            if (stand == null) {
                continue;
            }
            if (!moveToRingPos(source, bot, stand)) {
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
        moveToBuildSite(source, bot, returnPos);
    }

    private boolean moveToBuildSite(ServerCommandSource source, ServerPlayerEntity bot, BlockPos center) {
        if (bot.getBlockPos().getSquaredDistance(center) <= 4.0D) return true;
        var planOpt = MovementService.planLootApproach(bot, center, MovementService.MovementOptions.skillLoot());
        return planOpt.isPresent() && MovementService.execute(source, bot, planOpt.get(), false, true, true, false).success();
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
        BlockPos doorPos = center.offset(doorSide, radius);

        // Try to get close enough that the carve is actually in reach.
        BlockPos insideSeed = center.offset(doorSide, Math.max(0, radius - 1)).withY(center.getY());
        BlockPos inside = findNearbyStandable(world, insideSeed, 4);
        if (inside != null && source != null) {
            moveToBuildSite(source, bot, inside);
        }

        mineSoft(bot, doorPos.withY(center.getY() + 1));
        mineSoft(bot, doorPos.withY(center.getY() + 2));
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
        BlockPos doorPos = center.offset(doorSide, radius).withY(floorY + 1);

        // Make sure the doorway is clear and has a support block below.
        ensureDoorwayOpen(world, source, bot, center, radius, doorSide);
        BlockPos below = doorPos.down();
        if (isMissing(world, below)) {
            BotActions.placeBlockAt(bot, below, Direction.UP, PILLAR_BLOCKS);
        }

        // Stand just inside the doorway if possible (better orientation and less collision risk).
        BlockPos insideSeed = center.offset(doorSide, Math.max(0, radius - 1)).withY(floorY);
        BlockPos inside = findNearbyStandable(world, insideSeed, 4);
        if (inside != null) {
            if (source != null) {
                moveToBuildSite(source, bot, inside);
            }
        }

        // If we're still not in reach, try an exterior stance.
        if (!BlockInteractionService.canInteract(bot, doorPos, REACH_DISTANCE_SQ)) {
            BlockPos outsideSeed = center.offset(doorSide, radius + 2).withY(floorY);
            BlockPos outside = findNearbyStandable(world, outsideSeed, 5);
            if (outside != null && source != null) {
                moveToBuildSite(source, bot, outside);
            }
        }

        // Final carve attempt if we got closer.
        ensureDoorwayOpen(world, source, bot, center, radius, doorSide);

        // Face outward so the door tends to open outward.
        net.shasankp000.Entity.LookController.faceBlock(bot, doorPos.offset(doorSide));
        BotActions.placeBlockAt(bot, doorPos, Direction.UP, List.of(doorItem));
        sleepQuiet(80L);
    }

    private void placeWallTorches(ServerWorld world,
                                 ServerCommandSource source,
                                 ServerPlayerEntity bot,
                                 BlockPos center,
                                 int radius) {
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

        // Try to get inside so we can place wall torches on interior walls.
        int floorY = center.getY();
        BlockPos inside = findNearbyStandable(world, center.withY(floorY), 5);
        if (inside != null && source != null) {
            moveToBuildSite(source, bot, inside);
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

        for (BlockPos p : targets) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            // Clear replaceable clutter only for torch placement (grass/snow layer/leaf litter).
            BlockState state = world.getBlockState(p);
            if (state.isReplaceable() && !state.isAir()) {
                mineSoft(bot, p);
            }
            // Ensure there's a floor block to place the torch on.
            if (isMissing(world, p.down())) {
                BotActions.placeBlockAt(bot, p.down(), Direction.UP, PILLAR_BLOCKS);
                sleepQuiet(40L);
            }

            if ((world.getBlockState(p).isAir() || world.getBlockState(p).isReplaceable())
                    && !isMissing(world, p.down())
                    && BlockInteractionService.canInteract(bot, p, REACH_DISTANCE_SQ)) {
                BotActions.placeBlockAt(bot, p, Direction.UP, List.of(Items.TORCH));
                sleepQuiet(60L);
            }
        }

        // Fallback: if the main targets are blocked, at least place two interior corner torches.
        List<BlockPos> floorTargets = List.of(
            center.add(radius - 1, 1, radius - 1),
            center.add(-(radius - 1), 1, radius - 1),
            center.add(radius - 1, 1, -(radius - 1)),
            center.add(-(radius - 1), 1, -(radius - 1))
        );
        for (BlockPos p : floorTargets) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            BlockState state = world.getBlockState(p);
            if (state.isReplaceable() && !state.isAir()) {
                mineSoft(bot, p);
            }
            if (world.getBlockState(p).isAir() && !isMissing(world, p.down())) {
                BotActions.placeBlockAt(bot, p, Direction.UP, List.of(Items.TORCH));
                sleepQuiet(60L);
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

    private HovelPlan resolvePlan(ServerWorld world, ServerPlayerEntity bot, BlockPos origin, int radius, Direction preferredDoorSide, Map<String, Object> sharedState, boolean resumeRequested) {
        return new HovelPlan(origin, preferredDoorSide != null ? preferredDoorSide : bot.getHorizontalFacing());
    }
}
