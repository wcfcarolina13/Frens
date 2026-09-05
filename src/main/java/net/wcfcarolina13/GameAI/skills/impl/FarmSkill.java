package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.LeashKnotEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.WaterSpotMemory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.ReturnBaseStuckService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.impl.WoodcutSkill;
import net.wcfcarolina13.GameAI.skills.support.MiningHazardDetector;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Simplified farming skill that follows a clear flow:
 * 1) dig a 2x2 irrigation hole,
 * 2) place water in opposite corners (using nearby infinite still water if buckets are empty),
 * 3) till hydrated plots (up to the number of seeds),
 * 4) plant seeds while avoiding stepping on farmland.
 */
public class FarmSkill implements Skill {
    private static final Logger LOGGER = LoggerFactory.getLogger("farm-skill");

    private static final int HYDRATION_RADIUS = 4; // Vanilla hydration distance from water
    private static final int ACTION_DELAY_MS = 150;
    private static final int WATER_SEARCH_RADIUS = 24;
    private static final double MAX_INTERACTION_RANGE = 4.5;
    private static final int IRRIGATION_ATTEMPTS = 4;
    private static final int TREE_CLEAR_RADIUS = 6;
    // FARM_WOODCUT_BUFFER / FARM_WOODCUT_VERTICAL_RANGE define the farm's "in the
    // way" query region (what counts as a blocker to the farm footprint). They
    // are intentionally tight so we only detect real obstructions.
    private static final int FARM_WOODCUT_BUFFER = 2;
    private static final int FARM_WOODCUT_VERTICAL_RANGE = 9;
    // FARM_WOODCUT_WORK_BUFFER / FARM_WOODCUT_WORK_VERTICAL_RANGE define the
    // larger bounds passed to the inline WoodcutSkill so it has enough elbow
    // room to approach trees, scaffold, and prune canopies whose leaves may
    // overhang the tight "blocker" query region above. Without this, every
    // trunk the detector finds gets rejected as out-of-bounds and the farm
    // falls back to a brute block-by-block clear that leaves floaters.
    private static final int FARM_WOODCUT_WORK_BUFFER = 6;
    private static final int FARM_WOODCUT_WORK_VERTICAL_RANGE = 20;
    private static final int FARM_LOCAL_TREE_CLEAR_LIMIT = 32;
    private static final int AUTO_MAX_CUT_DEPTH = 2;
    private static final int MANUAL_MAX_CUT_DEPTH = 4;
    private static final int MAX_FILL_DEPTH = 3;
    private static final int PRECIPICE_DEPTH = 4;
    private static final int PILLAR_STEP_DELAY_MS = 120;
    private static final Set<Item> PILLAR_BLOCKS = Set.of(
            Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
            Items.GRAVEL, Items.SAND, Items.NETHERRACK,
            Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS,
            Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
            Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS
    );

    private static final List<Item> DIRT_BLOCK_PREFERENCE = List.of(
            Items.DIRT,
            Items.GRASS_BLOCK,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT
    );
    private static final Set<Block> SHOVEL_DIG_BLOCKS = Set.of(
            Blocks.DIRT,
            Blocks.GRASS_BLOCK,
            Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT,
            Blocks.DIRT_PATH,
            Blocks.SAND,
            Blocks.RED_SAND,
            Blocks.GRAVEL,
            Blocks.CLAY
    );

    private static final Set<Block> TILLABLE_SURFACES = Set.of(
            Blocks.DIRT,
            Blocks.GRASS_BLOCK,
            Blocks.DIRT_PATH,
            Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT
    );

    private static final List<Item> SIMPLE_SEEDS = List.of(
            Items.WHEAT_SEEDS,
            Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.POTATO,
            Items.CARROT
    );

    private static final class SkillAbortException extends RuntimeException {
        private SkillAbortException() {
            super("farm-skill-abort");
        }
    }

    private static final class FarmSiteCandidate {
        private final BlockPos center;
        private final int blockingTreeBlocks;
        private final double distSqToSeed;

        private FarmSiteCandidate(BlockPos center, int blockingTreeBlocks, double distSqToSeed) {
            this.center = center;
            this.blockingTreeBlocks = blockingTreeBlocks;
            this.distSqToSeed = distSqToSeed;
        }
    }

    private record FarmFootprint(BlockPos irrigationAnchor) {
        private static FarmFootprint fromIrrigationAnchor(BlockPos irrigationAnchor) {
            return new FarmFootprint(irrigationAnchor.toImmutable());
        }

        private static FarmFootprint fromCenterTarget(BlockPos centerTarget) {
            return fromIrrigationAnchor(centerTarget.add(-1, 0, -1));
        }

        private BlockPos centerTarget() {
            return irrigationAnchor.add(1, 0, 1);
        }

        private int minX() {
            return irrigationAnchor.getX() - HYDRATION_RADIUS;
        }

        private int maxX() {
            return irrigationAnchor.getX() + 1 + HYDRATION_RADIUS;
        }

        private int minZ() {
            return irrigationAnchor.getZ() - HYDRATION_RADIUS;
        }

        private int maxZ() {
            return irrigationAnchor.getZ() + 1 + HYDRATION_RADIUS;
        }

        private boolean isIrrigationColumn(int x, int z) {
            return x >= irrigationAnchor.getX() && x <= irrigationAnchor.getX() + 1
                && z >= irrigationAnchor.getZ() && z <= irrigationAnchor.getZ() + 1;
        }

        private boolean isIrrigationPos(BlockPos pos) {
            return pos != null && isIrrigationColumn(pos.getX(), pos.getZ());
        }

        private List<BlockPos> irrigationCells() {
            return List.of(
                irrigationAnchor,
                irrigationAnchor.add(1, 0, 0),
                irrigationAnchor.add(0, 0, 1),
                irrigationAnchor.add(1, 0, 1)
            );
        }

        private List<BlockPos> plotPositions() {
            List<BlockPos> plots = new ArrayList<>();
            for (int x = minX(); x <= maxX(); x++) {
                for (int z = minZ(); z <= maxZ(); z++) {
                    if (isIrrigationColumn(x, z)) {
                        continue;
                    }
                    plots.add(new BlockPos(x, irrigationAnchor.getY(), z));
                }
            }
            return plots;
        }
    }

    private record FarmTerrainPrepPlan(BlockPos stagingGround,
                                       List<BlockPos> accessPath,
                                       int estimatedFillDeficit,
                                       boolean requiresTerraforming,
                                       String hardRejectReason) {
        private static FarmTerrainPrepPlan unsupported(String hardRejectReason) {
            return new FarmTerrainPrepPlan(null, List.of(), 0, false, hardRejectReason);
        }

        private static FarmTerrainPrepPlan ready(BlockPos stagingGround,
                                                 List<BlockPos> accessPath,
                                                 int estimatedFillDeficit,
                                                 boolean requiresTerraforming) {
            return new FarmTerrainPrepPlan(
                stagingGround == null ? null : stagingGround.toImmutable(),
                accessPath == null ? List.of() : List.copyOf(accessPath),
                Math.max(0, estimatedFillDeficit),
                requiresTerraforming,
                null
            );
        }
    }

    private record FarmWorkingGroundAssessment(boolean acceptable,
                                               int fillBlocks,
                                               boolean requiresTerraforming,
                                               String rejectReason) {
        private static FarmWorkingGroundAssessment accept(int fillBlocks, boolean requiresTerraforming) {
            return new FarmWorkingGroundAssessment(true, Math.max(0, fillBlocks), requiresTerraforming, null);
        }

        private static FarmWorkingGroundAssessment reject(String rejectReason) {
            return new FarmWorkingGroundAssessment(false, 0, false, rejectReason);
        }
    }

    private record FarmStagingCandidate(BlockPos ground,
                                        List<BlockPos> accessPath,
                                        int requiredFill,
                                        boolean requiresTerraforming,
                                        String rejectReason) {
        private static FarmStagingCandidate ready(BlockPos ground,
                                                  List<BlockPos> accessPath,
                                                  int requiredFill,
                                                  boolean requiresTerraforming) {
            return new FarmStagingCandidate(
                ground == null ? null : ground.toImmutable(),
                accessPath == null ? List.of() : List.copyOf(accessPath),
                Math.max(0, requiredFill),
                requiresTerraforming,
                null
            );
        }

        private static FarmStagingCandidate reject(String rejectReason) {
            return new FarmStagingCandidate(null, List.of(), 0, false, rejectReason);
        }
    }

    private record FarmSiteAssessment(boolean acceptable,
                                      Integer targetY,
                                      String rejectReason,
                                      FarmTerrainPrepPlan prepPlan) {
        private static FarmSiteAssessment accept(Integer targetY, FarmTerrainPrepPlan prepPlan) {
            return new FarmSiteAssessment(true, targetY, null, prepPlan);
        }

        private static FarmSiteAssessment reject(Integer targetY, String rejectReason, FarmTerrainPrepPlan prepPlan) {
            return new FarmSiteAssessment(false, targetY, rejectReason, prepPlan);
        }
    }

    @Override
    public String name() {
        return "farm";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No active bot.");
        }
        ServerWorld world = source.getWorld();
        PlayerInventory inventory = bot.getInventory();
        try {
            abortIfRequested(bot);
            logPos("start", bot);

            int seedCount = countSeeds(inventory);
            if (seedCount <= 0) {
                ChatUtils.sendSystemMessage(source, "I need seeds before I can farm.");
                return SkillExecutionResult.failure("Missing seeds.");
            }

            if (!hasHoe(inventory)) {
                ChatUtils.sendSystemMessage(source, "I need a hoe to till the soil.");
                return SkillExecutionResult.failure("Missing hoe.");
            }

            if (!ensureHoeInHotbar(bot)) {
                ChatUtils.sendSystemMessage(source, "I need a hoe in my hotbar to till.");
                return SkillExecutionResult.failure("Hoe not accessible.");
            }

            ensureNotOnFarmland(bot, world, source);
            net.wcfcarolina13.GameAI.BotEventHandler.rescueFromBurial(bot);
            logPos("postSafeMove", bot);
            abortIfRequested(bot);

            boolean hasExplicitTarget = hasExplicitFarmTarget(context);
            boolean manualPlacement = hasExplicitTarget || getBooleanParameter(context, "manual", false);
            boolean reuseExistingIrrigation = false;
            FarmSiteAssessment siteAssessment = null;
            Set<BlockPos> temporaryFarmAccess = new HashSet<>();

            // Prefer an existing enclosed 2x2 still-water basin as the farm center.
            BlockPos existingIrrigation = manualPlacement
                    ? null
                    : findNearbyEnclosedIrrigation2x2(world, bot.getBlockPos(), WATER_SEARCH_RADIUS);

            BlockPos farmCenter;
            BlockPos refillSource;
            if (existingIrrigation != null) {
                reuseExistingIrrigation = true;
                farmCenter = existingIrrigation.toImmutable();
                refillSource = farmCenter;
                LOGGER.info("Using existing enclosed 2x2 still-water basin as farm center at {}", farmCenter);
            } else {
                BlockPos rough = hasExplicitTarget
                        ? getExplicitFarmCenterTarget(context, bot)
                        : findFarmCenter(world, bot.getBlockPos()).add(1, 0, 1);
                if (!hasExplicitTarget) {
                    BlockPos roughAnchor = clampCenterToGroundNear(world, rough.add(-1, 0, -1), bot.getBlockPos().getY());
                    rough = FarmFootprint.fromIrrigationAnchor(roughAnchor).centerTarget();
                }

                BlockPos chosen = hasExplicitTarget
                        ? FarmFootprint.fromCenterTarget(rough).irrigationAnchor()
                        : selectReachableNewFarmCenterOnGrid(bot, world, source, rough.add(-1, 0, -1));
                if (chosen == null) {
                    ChatUtils.sendSystemMessage(source, "No suitable reachable spot on the farm grid (avoid oceans/doors/cliffs). Try a flatter / more open spot.");
                    return SkillExecutionResult.failure("No clear reachable grid cell available.");
                }
                if (!manualPlacement) {
                    chosen = offsetAwayFromNearbyFarmland(world, chosen, HYDRATION_RADIUS + 3);
                    chosen = clampCenterToGroundNear(world, chosen, bot.getBlockPos().getY());
                }
                siteAssessment = assessFarmSite(world, FarmFootprint.fromIrrigationAnchor(chosen), manualPlacement);
                if (!siteAssessment.acceptable()) {
                    ChatUtils.sendSystemMessage(source, "Farm site rejected: " + siteAssessment.rejectReason());
                    return SkillExecutionResult.failure("Rejected farm site: " + siteAssessment.rejectReason());
                }
                farmCenter = chosen;

                // Prefer refill sources near the farm center.
                List<BlockPos> refillSources = findStillWaterSources(world, farmCenter, WATER_SEARCH_RADIUS);
                refillSource = refillSources.isEmpty() ? null : refillSources.get(0);
                if (refillSource != null) {
                    LOGGER.info("Found still water at {} for irrigation/refills", refillSource);
                    rememberIrrigationSource(bot, world, refillSource);
                } else {
                    LOGGER.warn("No viable still water found within {} blocks of {}", WATER_SEARCH_RADIUS, farmCenter);
                    refillSource = recallRememberedWaterSource(bot, world, farmCenter);
                    if (refillSource != null) {
                        LOGGER.info("Falling back to remembered water source at {} for irrigation/refills", refillSource);
                    }
                }
            }

            if (!reuseExistingIrrigation && isLikelyIrrigationCenter(world, farmCenter) && isEnclosed2x2Water(world, farmCenter)) {
                reuseExistingIrrigation = true;
                refillSource = farmCenter;
                LOGGER.info("Reusing existing enclosed basin at manually selected farm center {}", farmCenter);
            }

            FarmFootprint footprint = FarmFootprint.fromIrrigationAnchor(farmCenter);

            Set<BlockPos> protectedStillWater = (reuseExistingIrrigation || !manualPlacement)
                    ? findProtectedStillWaterInFarmArea(world, footprint)
                    : new HashSet<>();
            for (BlockPos p : footprint.irrigationCells()) {
                if (isStillWater(world, p)) {
                    protectedStillWater.add(p.toImmutable());
                }
            }

            // Note: previously ran hasSimplePrecipice here as a pre-assessment
            // terrain guard. Removed — the check operated on WORLD_SURFACE Y
            // which pointed at forest canopies and produced phantom precipice
            // rejections. assessFarmSite's per-column grade-cut / fill-too-deep
            // loop (run after tree clearing) is the authoritative terrain gate.

            escapeTreeAndWoodcut(bot, world, source, context, footprint);
            clearBlockingTrees(bot, world, source, context, footprint);
            abortIfRequested(bot);

            Integer targetFarmY = siteAssessment != null && siteAssessment.targetY() != null
                    ? siteAssessment.targetY()
                    : computeFarmTargetY(world, footprint, protectedStillWater);
            if (targetFarmY != null) {
                if (manualPlacement && !reuseExistingIrrigation) {
                    String manualPrepFailure = prepareManualFarmSite(bot, world, source, context, footprint, siteAssessment, temporaryFarmAccess);
                    if (manualPrepFailure != null) {
                        ChatUtils.sendSystemMessage(source, "Farm site rejected: " + manualPrepFailure);
                        return SkillExecutionResult.failure("Rejected farm site: " + manualPrepFailure);
                    }
                    siteAssessment = assessFarmSite(world, footprint, true);
                    if (!siteAssessment.acceptable()) {
                        ChatUtils.sendSystemMessage(source, "Farm site rejected: " + siteAssessment.rejectReason());
                        return SkillExecutionResult.failure("Rejected farm site: " + siteAssessment.rejectReason());
                    }
                    targetFarmY = siteAssessment.targetY();
                }
                if (!ensureWaterSupply(bot, world, source, farmCenter, refillSource)) {
                    return SkillExecutionResult.failure("No water available for irrigation.");
                }
                abortIfRequested(bot);
                LOGGER.info("[FarmIrrigation] targetY={} centerBefore={}", targetFarmY, farmCenter);
                levelGround(bot, world, source, footprint, targetFarmY, context, protectedStillWater,
                    manualPlacement ? MANUAL_MAX_CUT_DEPTH : AUTO_MAX_CUT_DEPTH,
                    siteAssessment != null && siteAssessment.prepPlan() != null ? siteAssessment.prepPlan().stagingGround() : null,
                    temporaryFarmAccess);
                if (targetFarmY != farmCenter.getY()) {
                    BlockPos normalizedCenter = new BlockPos(farmCenter.getX(), targetFarmY, farmCenter.getZ());
                    // If we are reusing an existing enclosed basin, keep Y aligned with actual water if normalization misses it.
                    if (reuseExistingIrrigation && !isLikelyIrrigationCenter(world, normalizedCenter)) {
                        LOGGER.warn("[FarmIrrigation] center-normalize skipped for existing basin oldY={} newY={} center={}",
                                farmCenter.getY(), targetFarmY, farmCenter);
                    } else {
                        LOGGER.info("[FarmIrrigation] center-normalized oldY={} newY={} center={}",
                                farmCenter.getY(), targetFarmY, farmCenter);
                        farmCenter = normalizedCenter;
                        footprint = FarmFootprint.fromIrrigationAnchor(farmCenter);
                        protectedStillWater = (reuseExistingIrrigation || !manualPlacement)
                                ? findProtectedStillWaterInFarmArea(world, footprint)
                                : new HashSet<>();
                        for (BlockPos irrigationCell : footprint.irrigationCells()) {
                            if (isStillWater(world, irrigationCell)) {
                                protectedStillWater.add(irrigationCell.toImmutable());
                            }
                        }
                    }
                }
            } else {
                LOGGER.warn("[FarmIrrigation] no targetY samples available at center={}, skipping leveling", farmCenter);
                if (!ensureWaterSupply(bot, world, source, farmCenter, refillSource)) {
                    return SkillExecutionResult.failure("No water available for irrigation.");
                }
            }
            abortIfRequested(bot);

            if (!reuseExistingIrrigation) {
                ReturnBaseStuckService.clear(bot.getUuid());
                LOGGER.info("[FarmIrrigation] cleared stale stuck state before irrigation at {}", farmCenter);
                if (!digIrrigationHole(bot, world, source, farmCenter)) {
                    return SkillExecutionResult.failure("Failed to dig irrigation hole.");
                }

                reinforceIrrigationEdges(bot, world, source, farmCenter);
                if (!stabilizeIrrigationBasin(bot, world, source, farmCenter)) {
                    return SkillExecutionResult.failure("Failed to stabilize irrigation basin.");
                }

                if (!fillIrrigation(bot, world, source, farmCenter, refillSource)) {
                    return SkillExecutionResult.failure("Failed to fill irrigation hole.");
                }
            } else {
                LOGGER.info("Skipping irrigation dig/fill: using existing enclosed water basin at {}", farmCenter);
            }
            abortIfRequested(bot);

            int tilled = tillPlots(bot, world, source, farmCenter, seedCount);
            int planted = plantSeeds(bot, world, source, farmCenter, seedCount);
            if (!reuseExistingIrrigation) {
                secureIrrigationContainment(bot, world, source, farmCenter);
            }
            repairDamagedPlots(bot, world, source, farmCenter, protectedStillWater);
            // After repairs, try to use remaining seeds on any newly leveled plots
            int remainingSeeds = countSeeds(inventory);
            if (remainingSeeds > 0) {
                tilled += tillPlots(bot, world, source, farmCenter, remainingSeeds);
                planted += plantSeeds(bot, world, source, farmCenter, remainingSeeds);
            }

            // One more tree sweep before final water fetch to ensure sunlight is clear
            clearBlockingTrees(bot, world, source, context, footprint);

            finalTopOffBuckets(bot, world, source, farmCenter, refillSource);
            cleanupTemporaryFarmAccess(bot, world, source, footprint, temporaryFarmAccess);

            return SkillExecutionResult.success(
                    "Prepared a hydrated farm and planted " + planted + " seeds (tilled " + tilled + ")."
            );
        } catch (SkillAbortException abort) {
            String reason = TaskService.getCancelReason(bot.getUuid()).orElse("§cCurrent task aborted.");
            LOGGER.info("[FarmIrrigation] farm aborted: bot={} reason={}", bot.getName().getString(), reason);
            return SkillExecutionResult.failure(reason);
        }
    }

    private static boolean digIrrigationHole(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos center) {
        abortIfRequested(bot);
        LOGGER.info("[FarmIrrigation] Digging 2x2 irrigation hole at {}", center);
        // Move near the center before digging to ensure reach
        MovementService.MovementResult approach = moveTo(source, bot, center.up(), false);
        waitUntilClose(bot, center, 3.0, 20, false);
        double dist = bot.getEyePos().distanceTo(Vec3d.ofCenter(center));
        if (dist > 4.5D) {
            LOGGER.warn("Unable to reach irrigation hole center {} (dist={}): {}", center, String.format("%.2f", dist), approach.detail());
            ChatUtils.sendSystemMessage(source, "I couldn't reach the irrigation hole location (path blocked). Try a flatter / more open spot.");
            return false;
        }
        if (!world.isChunkLoaded(center)) {
            LOGGER.warn("Irrigation hole center chunk not loaded at {}", center);
            ChatUtils.sendSystemMessage(source, "I couldn't load the area for the irrigation hole.");
            return false;
        }

        BlockPos[] waterSpots = {
                center,
                center.add(1, 0, 0),
                center.add(0, 0, 1),
                center.add(1, 0, 1)
        };

        // First pass: ensure solid foundation 2 blocks deep under each water spot
        for (BlockPos pos : waterSpots) {
            abortIfRequested(bot);
            ensureStandingOffFarmland(bot, world, source, pos);
            for (int dy = 1; dy <= 2; dy++) {
                BlockPos base = pos.down(dy);
                BlockState baseState = world.getBlockState(base);
                if (baseState.getCollisionShape(world, base).isEmpty()) {
                    if (!fillWithDirt(bot, world, base)) {
                        moveTo(source, bot, pos.up(), false);
                        waitUntilClose(bot, base, 4.4, 16, false);
                        if (!fillWithDirt(bot, world, base)) {
                            LOGGER.warn("[FarmIrrigation] failed to fill basin foundation at {}", base.toShortString());
                            return false;
                        }
                    }
                }
            }
        }

        // Second pass: clear the hole spaces
        for (BlockPos pos : waterSpots) {
            abortIfRequested(bot);
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && !state.isOf(Blocks.WATER)) {
                mineBlock(bot, pos, world);
                sleep(120);
            }
        }
        // Verify hole
        for (BlockPos pos : waterSpots) {
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && !state.isOf(Blocks.WATER)) {
                LOGGER.warn("Irrigation hole not clear at {}", pos);
                return false;
            }
        }
        clearAboveHole(bot, world, waterSpots);
        clearIrrigationAccessRing(bot, world, center);
        return true;
    }

    private static void clearAboveHole(ServerPlayerEntity bot, ServerWorld world, BlockPos[] waterSpots) {
        for (BlockPos pos : waterSpots) {
            abortIfRequested(bot);
            for (int dy = 1; dy <= 3; dy++) {
                BlockPos check = pos.up(dy);
                BlockState state = world.getBlockState(check);
                if (state.isAir()) continue;
                mineBlock(bot, check, world);
                sleep(50);
            }
        }
    }

    private static void clearIrrigationAccessRing(ServerPlayerEntity bot, ServerWorld world, BlockPos center) {
        int cleared = 0;
        int minX = center.getX() - 1;
        int maxX = center.getX() + 2;
        int minZ = center.getZ() - 1;
        int maxZ = center.getZ() + 2;
        for (int x = minX; x <= maxX; x++) {
            abortIfRequested(bot);
            for (int z = minZ; z <= maxZ; z++) {
                boolean insideHole = x >= center.getX() && x <= center.getX() + 1
                        && z >= center.getZ() && z <= center.getZ() + 1;
                if (insideHole) {
                    continue;
                }
                BlockPos ground = new BlockPos(x, center.getY(), z);
                for (int dy = 1; dy <= 2; dy++) {
                    BlockPos check = ground.up(dy);
                    BlockState state = world.getBlockState(check);
                    if (state.isAir() || !state.isReplaceable()) {
                        continue;
                    }
                    mineBlock(bot, check, world);
                    cleared++;
                    sleep(40);
                }
            }
        }
        if (cleared > 0) {
            LOGGER.info("[FarmIrrigation] cleared {} replaceable blocks around irrigation access ring", cleared);
        }
    }

    private static void reinforceIrrigationEdges(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos center) {
        int minX = center.getX() - 1;
        int maxX = center.getX() + 2;
        int minZ = center.getZ() - 1;
        int maxZ = center.getZ() + 2;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean isEdge = x < center.getX() || x > center.getX() + 1 || z < center.getZ() || z > center.getZ() + 1;
                if (!isEdge) continue;
                BlockPos pos = new BlockPos(x, center.getY(), z);
                BlockState state = world.getBlockState(pos);
                if (state.isOf(Blocks.WATER) || state.getCollisionShape(world, pos).isEmpty()) {
                    if (!state.isAir() && !state.isReplaceable()) {
                        mineBlock(bot, pos, world);
                    }
                    fillWithDirt(bot, world, pos);
                }
                // Seal 3 blocks deep to handle uneven terrain and prevent leaks
                for (int dy = 1; dy <= 3; dy++) {
                    BlockPos below = pos.down(dy);
                    BlockState belowState = world.getBlockState(below);
                    if (belowState.getCollisionShape(world, below).isEmpty()) {
                        fillWithDirt(bot, world, below);
                    } else {
                        break; // hit solid ground, stop
                    }
                }
            }
        }
    }

    private static boolean stabilizeIrrigationBasin(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos center) {
        BlockPos[] hole = {center, center.add(1, 0, 0), center.add(0, 0, 1), center.add(1, 0, 1)};
        for (int pass = 1; pass <= 3; pass++) {
            abortIfRequested(bot);
            boolean changed = false;

            for (BlockPos pos : hole) {
                abortIfRequested(bot);
                BlockState topState = world.getBlockState(pos);
                if (!topState.isAir() && !topState.isOf(Blocks.WATER)) {
                    mineBlock(bot, pos, world);
                    changed = true;
                }
                for (int depth = 1; depth <= 3; depth++) {
                    BlockPos below = pos.down(depth);
                    if (!isSolidContainmentBlock(world, below)) {
                        if (!fillWithDirt(bot, world, below)) {
                            moveTo(source, bot, pos.up(), false);
                            waitUntilClose(bot, below, 4.4, 16, false);
                            if (!fillWithDirt(bot, world, below)) {
                                LOGGER.warn("[FarmIrrigation] basin stabilization failed at {}", below.toShortString());
                                return false;
                            }
                        }
                        changed = true;
                    }
                }
            }

            int minX = center.getX() - 1;
            int maxX = center.getX() + 2;
            int minZ = center.getZ() - 1;
            int maxZ = center.getZ() + 2;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean insideHole = x >= center.getX() && x <= center.getX() + 1
                            && z >= center.getZ() && z <= center.getZ() + 1;
                    if (insideHole) {
                        continue;
                    }
                    for (int depth = 0; depth <= 2; depth++) {
                        BlockPos check = new BlockPos(x, center.getY() - depth, z);
                        if (!isSolidContainmentBlock(world, check)) {
                            if (!fillWithDirt(bot, world, check)) {
                                moveTo(source, bot, check.up(), false);
                                waitUntilClose(bot, check, 4.4, 16, false);
                                if (!fillWithDirt(bot, world, check)) {
                                    LOGGER.warn("[FarmIrrigation] basin ring seal failed at {}", check.toShortString());
                                    // Ring patches are best-effort; keep going unless the core 2x2 basin is unstable.
                                    continue;
                                }
                            }
                            changed = true;
                        }
                    }
                }
            }

            String leakReason = irrigationLeakReason(world, center);
            if (leakReason == null) {
                LOGGER.info("[FarmIrrigation] basin stabilized at {} on pass {}", center.toShortString(), pass);
                return true;
            }

            LOGGER.warn("[FarmIrrigation] basin still leaking at {} pass={} reason={}", center.toShortString(), pass, leakReason);
            if (!changed) {
                break;
            }
        }

        String finalLeakReason = irrigationLeakReason(world, center);
        if (finalLeakReason != null) {
            LOGGER.warn("[FarmIrrigation] basin stabilization failed at {} reason={}", center.toShortString(), finalLeakReason);
            return false;
        }
        return true;
    }

    private static boolean isSolidContainmentBlock(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable() || state.isOf(Blocks.WATER)) {
            return false;
        }
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static String irrigationLeakReason(ServerWorld world, BlockPos center) {
        BlockPos[] hole = {center, center.add(1, 0, 0), center.add(0, 0, 1), center.add(1, 0, 1)};
        for (BlockPos pos : hole) {
            BlockState top = world.getBlockState(pos);
            if (!top.isAir() && !top.isOf(Blocks.WATER)) {
                return "blocked-top pos=" + pos.toShortString() + " block=" + top.getBlock().getTranslationKey();
            }
            for (int depth = 1; depth <= 2; depth++) {
                BlockPos below = pos.down(depth);
                if (!isSolidContainmentBlock(world, below)) {
                    return "open-below pos=" + below.toShortString();
                }
            }
        }
        return null;
    }

    private static boolean treeNear(ServerPlayerEntity bot, ServerWorld world, BlockPos center) {
        return countBlockingTreeBlocks(world, center) > 0;
    }

    private static SkillExecutionResult runWoodcutInline(ServerCommandSource source, SkillContext ctx) {
        return runWoodcutInline(source, ctx, null);
    }

    private static SkillExecutionResult runWoodcutInline(ServerCommandSource source, SkillContext ctx, FarmFootprint footprint) {
        ServerPlayerEntity bot = source.getPlayer();
        abortIfRequested(bot);
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("internal", true);
            params.put("replantSaplings", false);
            params.put("count", 1);
            params.put("searchRadius", TREE_CLEAR_RADIUS + 2);
            params.put("verticalRange", 8);
            if (footprint != null) {
                // NOTE: pass the *wider* work bounds, not the query bounds used
                // by isWithinFarmWoodcutBounds / collectBlockingTreeBlocks. The
                // wider box gives WoodcutSkill room to approach, scaffold, and
                // prune the canopy of trees whose trunks root inside the farm.
                int anchorY = footprint.irrigationAnchor().getY();
                params.put("minX", footprint.minX() - FARM_WOODCUT_WORK_BUFFER);
                params.put("maxX", footprint.maxX() + FARM_WOODCUT_WORK_BUFFER);
                params.put("minY", anchorY - 2);
                params.put("maxY", anchorY + FARM_WOODCUT_WORK_VERTICAL_RANGE);
                params.put("minZ", footprint.minZ() - FARM_WOODCUT_WORK_BUFFER);
                params.put("maxZ", footprint.maxZ() + FARM_WOODCUT_WORK_BUFFER);
            }
            return new WoodcutSkill().execute(new SkillContext(source, ctx.sharedState(), params));
        } catch (SkillAbortException abort) {
            throw abort;
        } catch (Exception e) {
            LOGGER.warn("Inline woodcut failed: {}", e.getMessage(), e);
            return SkillExecutionResult.failure("Woodcut error: " + e.getMessage());
        }
    }

    private static BlockPos clampCenterToGround(ServerWorld world, BlockPos center) {
        if (world == null || center == null) {
            return center;
        }

        // Prefer surface selection. The old "scan down until solid" approach can snap into caves/ravines,
        // producing an underground center Y that the bot may not be able to reach.
        int x = center.getX();
        int z = center.getZ();
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (topY > world.getBottomY()) {
            BlockPos top = new BlockPos(x, topY - 1, z);
            BlockState topState = world.getBlockState(top);
            if (!topState.isAir()
                    && !topState.isReplaceable()
                    && !topState.isIn(BlockTags.LOGS)
                    && !topState.isIn(BlockTags.LEAVES)
                    && !topState.isOf(Blocks.WATER)) {
                return top;
            }
        }

        // Fallback: limited downward scan.
        for (int dy = 0; dy <= 12; dy++) {
            BlockPos candidate = center.down(dy);
            BlockState state = world.getBlockState(candidate);
            if (state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.WATER)) {
                continue;
            }
            return new BlockPos(center.getX(), candidate.getY(), center.getZ());
        }
        return center;
    }

    /**
     * Clamp a (x,z) to a walkable surface near a preferred Y (avoids snapping to ocean floors / cave bottoms).
     */
    private static BlockPos clampCenterToGroundNear(ServerWorld world, BlockPos center, int preferredY) {
        if (world == null || center == null) {
            return center;
        }

        int x = center.getX();
        int z = center.getZ();

        // First try: land surface (WORLD_SURFACE includes fluids; we explicitly reject water surfaces).
        int surfaceTop = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
        if (surfaceTop > world.getBottomY()) {
            BlockPos surface = new BlockPos(x, surfaceTop - 1, z);
            BlockState surfaceState = world.getBlockState(surface);
            if (!surfaceState.isAir()
                    && !surfaceState.isReplaceable()
                    && !surfaceState.isOf(Blocks.WATER)
                    && !surfaceState.isIn(BlockTags.LOGS) && !surfaceState.isIn(BlockTags.LEAVES)) {
                if (Math.abs(surface.getY() - preferredY) <= 8) {
                    return surface;
                }
            }
        }

        // Second try: find any walkable ground near preferredY with headroom.
        int minY = Math.max(world.getBottomY() + 1, preferredY - 8);
        int maxY = Math.min(world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z), preferredY + 8);
        BlockPos best = null;
        int bestDy = Integer.MAX_VALUE;
        for (int y = maxY; y >= minY; y--) {
            BlockPos ground = new BlockPos(x, y, z);
            BlockState groundState = world.getBlockState(ground);
            if (groundState.isAir() || groundState.isReplaceable() || groundState.isOf(Blocks.WATER)
                    || groundState.isIn(BlockTags.LOGS) || groundState.isIn(BlockTags.LEAVES)) {
                continue;
            }
            if (groundState.getCollisionShape(world, ground).isEmpty()) {
                continue;
            }
            if (!isPassableStandingSpace(world, ground.up())) {
                continue;
            }
            int dy = Math.abs(y - preferredY);
            if (dy < bestDy) {
                bestDy = dy;
                best = ground;
            }
        }
        if (best != null) {
            return best;
        }

        // Fallback: previous behavior.
        return clampCenterToGround(world, center);
    }

    /**
     * Finds a nearby enclosed 2x2 square of still water and returns its NW corner as the irrigation center.
     * "Enclosed" means the immediate ring around the 2x2 is not water/air/replaceable.
     */
    private static BlockPos findNearbyEnclosedIrrigation2x2(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return null;
        }

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos nw = origin.add(dx, dy, dz);
                    if (!isStillWater(world, nw)) {
                        continue;
                    }
                    BlockPos ne = nw.add(1, 0, 0);
                    BlockPos sw = nw.add(0, 0, 1);
                    BlockPos se = nw.add(1, 0, 1);
                    if (!isStillWater(world, ne) || !isStillWater(world, sw) || !isStillWater(world, se)) {
                        continue;
                    }
                    if (!isEnclosed2x2Water(world, nw)) {
                        continue;
                    }

                    double distSq = origin.getSquaredDistance(nw);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = nw.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private static boolean isEnclosed2x2Water(ServerWorld world, BlockPos nw) {
        int minX = nw.getX();
        int minZ = nw.getZ();
        int y = nw.getY();
        int maxX = minX + 1;
        int maxZ = minZ + 1;

        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                boolean inside = (x >= minX && x <= maxX && z >= minZ && z <= maxZ);
                if (inside) {
                    continue;
                }
                BlockPos ring = new BlockPos(x, y, z);
                BlockState s = world.getBlockState(ring);
                if (s.isAir() || s.isReplaceable() || s.isOf(Blocks.WATER)) {
                    return false;
                }
                if (s.getCollisionShape(world, ring).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Collects still-water tiles inside the farm footprint that we should keep (integrate into the site).
     */
    private static Set<BlockPos> findProtectedStillWaterInFarmArea(ServerWorld world, BlockPos center) {
        return findProtectedStillWaterInFarmArea(world, FarmFootprint.fromIrrigationAnchor(center));
    }

    private static Set<BlockPos> findProtectedStillWaterInFarmArea(ServerWorld world, FarmFootprint footprint) {
        Set<BlockPos> set = new HashSet<>();
        if (world == null || footprint == null) {
            return set;
        }
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = new BlockPos(x, footprint.irrigationAnchor().getY() + dy, z);
                    if (isStillWater(world, p)) {
                        set.add(p.toImmutable());
                    }
                }
            }
        }
        return set;
    }

    private static boolean hasExplicitFarmTarget(SkillContext context) {
        return context != null
            && context.parameters().containsKey("targetX")
            && context.parameters().containsKey("targetY")
            && context.parameters().containsKey("targetZ");
    }

    private static BlockPos getExplicitFarmCenterTarget(SkillContext context, ServerPlayerEntity bot) {
        BlockPos fallback = bot == null ? BlockPos.ORIGIN : bot.getBlockPos().add(1, 0, 1);
        return new BlockPos(
            getIntParameter(context, "targetX", fallback.getX()),
            getIntParameter(context, "targetY", fallback.getY()),
            getIntParameter(context, "targetZ", fallback.getZ())
        );
    }

    private static int getIntParameter(SkillContext context, String key, int defaultValue) {
        if (context == null || key == null || !context.parameters().containsKey(key)) {
            return defaultValue;
        }
        Object value = context.parameters().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static boolean getBooleanParameter(SkillContext context, String key, boolean defaultValue) {
        if (context == null || key == null || !context.parameters().containsKey(key)) {
            return defaultValue;
        }
        Object value = context.parameters().get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean hasSupportWithinTwoBelowGrade(ServerWorld world, int x, int z, int targetY) {
        for (int y = targetY; y >= targetY - 2; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                continue;
            }
            if (!state.getCollisionShape(world, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static FarmSiteAssessment assessFarmSite(ServerWorld world, FarmFootprint footprint, boolean manualPlacement) {
        if (world == null || footprint == null) {
            return FarmSiteAssessment.reject(null, "invalid-footprint", FarmTerrainPrepPlan.unsupported("invalid-footprint"));
        }

        int minY = footprint.irrigationAnchor().getY() - 1;
        int maxY = footprint.irrigationAnchor().getY() + 3;
        String tetherHazard = findFenceOrTetherHazard(world, footprint.irrigationAnchor(),
            footprint.minX(), footprint.maxX(), footprint.minZ(), footprint.maxZ(), minY, maxY);
        if (tetherHazard != null) {
            return FarmSiteAssessment.reject(null, tetherHazard, FarmTerrainPrepPlan.unsupported(tetherHazard));
        }

        List<Integer> dryHeights = new ArrayList<>();
        List<BlockPos> waterSurfaces = new ArrayList<>();
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                // Use walkable ground Y (skips logs/leaves) instead of WORLD_SURFACE,
                // which would return canopy tops inside forests and inflate the
                // median farm Y into phantom-precipice territory.
                int walkableY = SafePositionService.getWalkableGroundY(world, x, z);
                if (walkableY <= world.getBottomY()) {
                    String rejectReason = "no-surface x=" + x + " z=" + z;
                    return FarmSiteAssessment.reject(null, rejectReason, FarmTerrainPrepPlan.unsupported(rejectReason));
                }
                BlockPos surface = new BlockPos(x, walkableY - 1, z);
                BlockState surfaceState = world.getBlockState(surface);
                if (surfaceState.isOf(Blocks.FARMLAND)) {
                    String rejectReason = "existing-farmland pos=" + surface.toShortString();
                    return FarmSiteAssessment.reject(null, rejectReason, FarmTerrainPrepPlan.unsupported(rejectReason));
                }
                // Water check: getWalkableGroundY skips water (no collision),
                // so the block AT walkableY is what sits directly above the
                // first solid block — water there means submerged column.
                BlockState aboveSurface = world.getBlockState(new BlockPos(x, walkableY, z));
                if (aboveSurface.isOf(Blocks.WATER) || surfaceState.isOf(Blocks.WATER)) {
                    waterSurfaces.add(surface);
                    continue;
                }
                dryHeights.add(surface.getY());
            }
        }

        if (dryHeights.isEmpty()) {
            return FarmSiteAssessment.reject(null, "no-solid-surface-samples", FarmTerrainPrepPlan.unsupported("no-solid-surface-samples"));
        }

        List<Integer> sortedY = new ArrayList<>(dryHeights);
        sortedY.sort(Integer::compareTo);
        int targetY = selectFarmTargetY(sortedY, manualPlacement);
        BlockPos centerGround = new BlockPos(footprint.centerTarget().getX(), targetY, footprint.centerTarget().getZ());
        // Note: the hasSimplePrecipice guard was removed here. The per-column
        // grade-cut-too-deep / fill-too-deep loop below is strictly better:
        // it examines every footprint column explicitly, whereas the precipice
        // check sampled a 7x7 area around center at centerGround.y and
        // false-positived whenever center Y was inflated by upstream canopy
        // detection (fixed by the walkable-ground-Y change above). The two
        // checks were redundant for footprint-sized farms; the per-column
        // check is the authoritative one.

        if (!manualPlacement && !waterSurfaces.isEmpty()) {
            String rejectReason = "water-surface pos=" + waterSurfaces.get(0).toShortString();
            return FarmSiteAssessment.reject(targetY, rejectReason, FarmTerrainPrepPlan.unsupported(rejectReason));
        }

        int maxCutDepth = manualPlacement ? MANUAL_MAX_CUT_DEPTH : AUTO_MAX_CUT_DEPTH;
        int moderateOutliers = 0;
        int severeOutliers = 0;
        int worstDelta = 0;
        BlockPos worstPos = centerGround;
        int sampledColumns = 0;
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                BlockPos columnBase = new BlockPos(x, targetY, z);
                Integer surfaceY = findSurfaceY(world, columnBase);
                if (surfaceY == null) {
                    String rejectReason = "no-solid-support pos=" + columnBase.toShortString();
                    return FarmSiteAssessment.reject(targetY, rejectReason, FarmTerrainPrepPlan.unsupported(rejectReason));
                }
                int diff = surfaceY - targetY;
                // Check what's sitting on top of the walkable ground: WORLD_SURFACE
                // would see canopy tops in a forest. getWalkableGroundY skips
                // logs/leaves, so the block at walkableY is what sits directly
                // above the first solid column block — water there means the
                // column is underwater.
                int columnWalkableY = SafePositionService.getWalkableGroundY(world, x, z);
                BlockState topState = world.getBlockState(new BlockPos(x, columnWalkableY, z));
                boolean waterSurface = topState.isOf(Blocks.WATER);
                if (diff > maxCutDepth) {
                    String rejectReason = "grade-cut-too-deep pos=" + columnBase.toShortString() + " diff=" + diff + " targetY=" + targetY;
                    return FarmSiteAssessment.reject(targetY, rejectReason, FarmTerrainPrepPlan.unsupported(rejectReason));
                }
                if (diff < -MAX_FILL_DEPTH) {
                    String rejectReason = "fill-too-deep pos=" + columnBase.toShortString() + " diff=" + diff + " targetY=" + targetY;
                    return FarmSiteAssessment.reject(targetY, rejectReason, FarmTerrainPrepPlan.unsupported(rejectReason));
                }
                if (manualPlacement && waterSurface && !footprint.isIrrigationColumn(x, z)
                        && !hasSupportWithinTwoBelowGrade(world, x, z, targetY)) {
                    String rejectReason = "unsupported-shoreline pos=" + columnBase.toShortString() + " targetY=" + targetY;
                    return FarmSiteAssessment.reject(targetY, rejectReason, FarmTerrainPrepPlan.unsupported(rejectReason));
                }
                if (!waterSurface) {
                    int delta = Math.abs(surfaceY - targetY);
                    if (delta > 2) {
                        moderateOutliers++;
                    }
                    if (delta > 4) {
                        severeOutliers++;
                    }
                    if (delta > worstDelta) {
                        worstDelta = delta;
                        worstPos = new BlockPos(x, surfaceY, z);
                    }
                    sampledColumns++;
                }
            }
        }

        if (!manualPlacement && sampledColumns > 0) {
            int maxModerateOutliers = Math.max(8, sampledColumns / 3);
            int maxSevereOutliers = Math.max(2, sampledColumns / 12);
            if (severeOutliers > maxSevereOutliers || moderateOutliers > maxModerateOutliers) {
                String rejectReason = "steep-terrain medianY=" + targetY
                        + " worstDelta=" + worstDelta
                        + " worstPos=" + worstPos.toShortString()
                        + " moderate=" + moderateOutliers + "/" + sampledColumns
                        + " severe=" + severeOutliers + "/" + sampledColumns;
                return FarmSiteAssessment.reject(targetY, rejectReason, FarmTerrainPrepPlan.unsupported(rejectReason));
            }
        }

        if (manualPlacement) {
            FarmTerrainPrepPlan prepPlan = buildFarmTerrainPrepPlan(world, footprint, targetY);
            if (prepPlan.hardRejectReason() != null) {
                return FarmSiteAssessment.reject(targetY, prepPlan.hardRejectReason(), prepPlan);
            }
            return FarmSiteAssessment.accept(targetY, prepPlan);
        }

        return FarmSiteAssessment.accept(targetY, FarmTerrainPrepPlan.ready(findStandingSpot(world, centerGround), List.of(), 0, moderateOutliers > 0));
    }

    private static FarmTerrainPrepPlan buildFarmTerrainPrepPlan(ServerWorld world, FarmFootprint footprint, int targetY) {
        if (world == null || footprint == null) {
            return FarmTerrainPrepPlan.unsupported("invalid-footprint");
        }

        String fallbackReason = null;
        for (BlockPos candidate : enumerateFarmStagingCandidates(footprint, targetY)) {
            FarmStagingCandidate staging = assessFarmStagingCandidate(world, footprint, candidate, targetY, MANUAL_MAX_CUT_DEPTH);
            if (staging.rejectReason() != null) {
                if (fallbackReason == null) {
                    fallbackReason = staging.rejectReason();
                }
                continue;
            }
            int fillDeficit = staging.requiredFill() + estimateFarmFillDeficit(world, footprint, targetY);
            return FarmTerrainPrepPlan.ready(
                staging.ground(),
                staging.accessPath(),
                fillDeficit,
                staging.requiresTerraforming()
            );
        }

        if (fallbackReason == null) {
            fallbackReason = "no-reachable-staging-pad pos=" + footprint.centerTarget().toShortString();
        }
        return FarmTerrainPrepPlan.unsupported(fallbackReason);
    }

    private static String prepareManualFarmSite(ServerPlayerEntity bot,
                                                ServerWorld world,
                                                ServerCommandSource source,
                                                SkillContext ctx,
                                                FarmFootprint footprint,
                                                FarmSiteAssessment siteAssessment,
                                                Set<BlockPos> temporaryFarmAccess) {
        if (bot == null || world == null || source == null || footprint == null || siteAssessment == null) {
            return "manual-site-prep-failed";
        }
        FarmTerrainPrepPlan prepPlan = siteAssessment.prepPlan();
        if (prepPlan == null || prepPlan.hardRejectReason() != null || siteAssessment.targetY() == null) {
            return prepPlan != null && prepPlan.hardRejectReason() != null ? prepPlan.hardRejectReason() : "manual-site-prep-failed";
        }
        if (!ensureFarmFillMaterials(bot, source, prepPlan.estimatedFillDeficit())) {
            return "insufficient-fill-material need=" + prepPlan.estimatedFillDeficit() + " have=" + countAvailableFillBlocks(bot.getInventory());
        }
        if (!prepareFarmWorkPad(bot, world, source, footprint, prepPlan, siteAssessment.targetY(), temporaryFarmAccess)) {
            return "no-reachable-staging-pad pos=" + footprint.centerTarget().toShortString();
        }
        terraformFarmFootprint(bot, world, source, ctx, footprint, siteAssessment.targetY(), prepPlan.stagingGround(), temporaryFarmAccess, MANUAL_MAX_CUT_DEPTH);
        return null;
    }

    private static int selectFarmTargetY(List<Integer> sortedY, boolean manualPlacement) {
        if (sortedY == null || sortedY.isEmpty()) {
            return 0;
        }
        int targetY = sortedY.get(sortedY.size() / 2);
        if (!manualPlacement) {
            return targetY;
        }
        int minSurfaceY = sortedY.get(0);
        int maxSurfaceY = sortedY.get(sortedY.size() - 1);
        int minRecoverableY = maxSurfaceY - MANUAL_MAX_CUT_DEPTH;
        int maxRecoverableY = minSurfaceY + MAX_FILL_DEPTH;
        if (minRecoverableY > maxRecoverableY) {
            return targetY;
        }
        return Math.max(minRecoverableY, Math.min(maxRecoverableY, targetY));
    }

    private static List<BlockPos> enumerateFarmStagingCandidates(FarmFootprint footprint, int targetY) {
        List<BlockPos> ordered = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        BlockPos centerTarget = footprint.centerTarget();
        addOrderedStagingCandidate(ordered, seen, new BlockPos(centerTarget.getX(), targetY, centerTarget.getZ()));

        List<BlockPos> perimeter = new ArrayList<>();
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            perimeter.add(new BlockPos(x, targetY, footprint.minZ()));
            perimeter.add(new BlockPos(x, targetY, footprint.maxZ()));
        }
        for (int z = footprint.minZ() + 1; z <= footprint.maxZ() - 1; z++) {
            perimeter.add(new BlockPos(footprint.minX(), targetY, z));
            perimeter.add(new BlockPos(footprint.maxX(), targetY, z));
        }
        perimeter.sort((a, b) -> Integer.compare(
            squaredHorizontalDistance(a, footprint.centerTarget()),
            squaredHorizontalDistance(b, footprint.centerTarget())
        ));
        for (BlockPos pos : perimeter) {
            addOrderedStagingCandidate(ordered, seen, pos);
        }

        for (int ring = 1; ring <= 2; ring++) {
            List<BlockPos> spillover = new ArrayList<>();
            int minX = footprint.minX() - ring;
            int maxX = footprint.maxX() + ring;
            int minZ = footprint.minZ() - ring;
            int maxZ = footprint.maxZ() + ring;
            for (int x = minX; x <= maxX; x++) {
                spillover.add(new BlockPos(x, targetY, minZ));
                spillover.add(new BlockPos(x, targetY, maxZ));
            }
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                spillover.add(new BlockPos(minX, targetY, z));
                spillover.add(new BlockPos(maxX, targetY, z));
            }
            spillover.sort((a, b) -> Integer.compare(
                squaredHorizontalDistance(a, footprint.centerTarget()),
                squaredHorizontalDistance(b, footprint.centerTarget())
            ));
            for (BlockPos pos : spillover) {
                addOrderedStagingCandidate(ordered, seen, pos);
            }
        }

        return ordered;
    }

    private static void addOrderedStagingCandidate(List<BlockPos> ordered, Set<BlockPos> seen, BlockPos candidate) {
        if (candidate == null) {
            return;
        }
        BlockPos immutable = candidate.toImmutable();
        if (seen.add(immutable)) {
            ordered.add(immutable);
        }
    }

    private static FarmStagingCandidate assessFarmStagingCandidate(ServerWorld world,
                                                                   FarmFootprint footprint,
                                                                   BlockPos candidate,
                                                                   int targetY,
                                                                   int maxCutDepth) {
        if (world == null || footprint == null || candidate == null) {
            return FarmStagingCandidate.reject("no-reachable-staging-pad");
        }
        if (footprint.isIrrigationPos(candidate)) {
            return FarmStagingCandidate.reject("no-reachable-staging-pad pos=" + candidate.toShortString());
        }
        List<BlockPos> accessPath = buildStagingAccessPath(footprint, candidate, targetY);
        int requiredFill = 0;
        boolean requiresTerraforming = false;

        FarmWorkingGroundAssessment candidateGround = assessFarmWorkingGround(world, candidate, targetY, true, maxCutDepth);
        if (!candidateGround.acceptable()) {
            return FarmStagingCandidate.reject(candidateGround.rejectReason());
        }
        requiredFill += candidateGround.fillBlocks();
        requiresTerraforming |= candidateGround.requiresTerraforming();

        for (BlockPos pathPos : accessPath) {
            if (footprint.isIrrigationPos(pathPos)) {
                return FarmStagingCandidate.reject("unbridgeable-access-gap pos=" + pathPos.toShortString());
            }
            FarmWorkingGroundAssessment pathGround = assessFarmWorkingGround(world, pathPos, targetY, false, maxCutDepth);
            if (!pathGround.acceptable()) {
                return FarmStagingCandidate.reject(pathGround.rejectReason());
            }
            requiredFill += pathGround.fillBlocks();
            requiresTerraforming |= pathGround.requiresTerraforming();
        }
        return FarmStagingCandidate.ready(candidate, accessPath, requiredFill, requiresTerraforming);
    }

    private static FarmWorkingGroundAssessment assessFarmWorkingGround(ServerWorld world,
                                                                       BlockPos ground,
                                                                       int targetY,
                                                                       boolean stagingPad,
                                                                       int maxCutDepth) {
        if (world == null || ground == null) {
            return FarmWorkingGroundAssessment.reject(stagingPad ? "no-reachable-staging-pad" : "unbridgeable-access-gap");
        }
        if (hasSimplePrecipice(world, ground)) {
            return FarmWorkingGroundAssessment.reject("precipice-near-work-path pos=" + ground.toShortString());
        }

        Integer surfaceY = findSurfaceY(world, ground);
        if (surfaceY != null && surfaceY - targetY > maxCutDepth) {
            return FarmWorkingGroundAssessment.reject("grade-cut-too-deep pos=" + ground.toShortString() + " diff=" + (surfaceY - targetY));
        }

        Integer supportY = findSolidSupportWithinDepth(world, ground, targetY, 3);
        if (supportY == null) {
            String reason = stagingPad
                ? "staging-pad-fill-too-deep pos=" + ground.toShortString()
                : "unbridgeable-access-gap pos=" + ground.toShortString();
            return FarmWorkingGroundAssessment.reject(reason);
        }

        boolean requiresTerraforming = false;
        int fillBlocks = Math.max(0, targetY - supportY);
        BlockState topState = world.getBlockState(ground);
        if (topState.isOf(Blocks.WATER) && !hasSupportWithinTwoBelowGrade(world, ground.getX(), ground.getZ(), targetY)) {
            String reason = stagingPad
                ? "staging-pad-fill-too-deep pos=" + ground.toShortString()
                : "unbridgeable-access-gap pos=" + ground.toShortString();
            return FarmWorkingGroundAssessment.reject(reason);
        }
        if (!isSafeStandingGround(world, ground)) {
            requiresTerraforming = true;
        }
        for (int y = targetY + 1; y <= targetY + 2; y++) {
            BlockPos headPos = new BlockPos(ground.getX(), y, ground.getZ());
            if (!canClearForStanding(world, headPos)) {
                return FarmWorkingGroundAssessment.reject("no-reachable-staging-pad pos=" + ground.toShortString());
            }
            if (!isPassableStandingSpace(world, headPos)) {
                requiresTerraforming = true;
            }
        }
        return FarmWorkingGroundAssessment.accept(fillBlocks, requiresTerraforming);
    }

    private static Integer findSolidSupportWithinDepth(ServerWorld world, BlockPos ground, int targetY, int maxDepth) {
        for (int y = targetY; y >= targetY - maxDepth; y--) {
            BlockPos check = new BlockPos(ground.getX(), y, ground.getZ());
            BlockState state = world.getBlockState(check);
            if (!state.getFluidState().isEmpty()) {
                continue;
            }
            if (!state.getCollisionShape(world, check).isEmpty()) {
                return y;
            }
        }
        return null;
    }

    private static boolean canClearForStanding(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (isPassableStandingSpace(world, pos)) {
            return true;
        }
        if (state.getHardness(world, pos) < 0.0f) {
            return false;
        }
        return state.isReplaceable()
            || state.isIn(BlockTags.LOGS)
            || state.isIn(BlockTags.LEAVES)
            || SHOVEL_DIG_BLOCKS.contains(state.getBlock())
            || state.isOf(Blocks.STONE)
            || state.isOf(Blocks.COBBLESTONE)
            || state.isOf(Blocks.MOSS_BLOCK);
    }

    private static List<BlockPos> buildStagingAccessPath(FarmFootprint footprint, BlockPos candidate, int targetY) {
        if (footprint == null || candidate == null) {
            return List.of();
        }
        int targetX = Math.max(footprint.minX(), Math.min(footprint.maxX(), candidate.getX()));
        int targetZ = Math.max(footprint.minZ(), Math.min(footprint.maxZ(), candidate.getZ()));
        List<BlockPos> path = new ArrayList<>();
        int x = candidate.getX();
        int z = candidate.getZ();
        while (x != targetX) {
            x += Integer.signum(targetX - x);
            path.add(new BlockPos(x, targetY, z));
        }
        while (z != targetZ) {
            z += Integer.signum(targetZ - z);
            path.add(new BlockPos(x, targetY, z));
        }
        return path;
    }

    private static int estimateFarmFillDeficit(ServerWorld world, FarmFootprint footprint, int targetY) {
        int deficit = 0;
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                if (footprint.isIrrigationColumn(x, z)) {
                    continue;
                }
                BlockPos columnBase = new BlockPos(x, targetY, z);
                Integer supportY = findSolidSupportWithinDepth(world, columnBase, targetY, MAX_FILL_DEPTH);
                if (supportY != null && supportY < targetY) {
                    deficit += targetY - supportY;
                }
            }
        }
        return deficit;
    }

    /**
     * For new farms (no existing irrigation), choose a reachable grid-aligned center.
     */
    private static BlockPos selectReachableNewFarmCenterOnGrid(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos seedCenter) {
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(seedCenter, "seedCenter");

        int grid = HYDRATION_RADIUS * 2 + 2; // 10-wide footprint
        int baseX = Math.floorDiv(seedCenter.getX(), grid) * grid;
        int baseZ = Math.floorDiv(seedCenter.getZ(), grid) * grid;

        List<FarmSiteCandidate> rankedCandidates = new ArrayList<>();
        int[] offsets = {0, 1, -1, 2, -2};
        for (int dx : offsets) {
            for (int dz : offsets) {
                BlockPos candidate = new BlockPos(baseX + dx * grid, seedCenter.getY(), baseZ + dz * grid);
                candidate = clampCenterToGroundNear(world, candidate, bot.getBlockPos().getY());
                candidate = alignCenterToFarmAreaMedianY(world, candidate);
                if (!farmAreaClear(world, candidate)) {
                    continue;
                }
                int treeBlocks = countBlockingTreeBlocks(world, candidate);
                double distSq = candidate.getSquaredDistance(seedCenter);
                rankedCandidates.add(new FarmSiteCandidate(candidate, treeBlocks, distSq));
            }
        }

        rankedCandidates.sort((a, b) -> {
            if (a.blockingTreeBlocks != b.blockingTreeBlocks) {
                return Integer.compare(a.blockingTreeBlocks, b.blockingTreeBlocks);
            }
            return Double.compare(a.distSqToSeed, b.distSqToSeed);
        });

        for (FarmSiteCandidate candidate : rankedCandidates) {
            LOGGER.info("[FarmIrrigation] candidate {} treeBlocks={} dist={}",
                    candidate.center.toShortString(),
                    candidate.blockingTreeBlocks,
                    String.format("%.2f", Math.sqrt(candidate.distSqToSeed)));
            LOGGER.info("Farm site candidate {}: approaching to verify reachability", candidate.center);
            moveTo(source, bot, candidate.center.up());
            waitUntilClose(bot, candidate.center, 4.0, 30);
            double dist = bot.getEyePos().distanceTo(Vec3d.ofCenter(candidate.center));
            if (dist <= 5.0D) {
                LOGGER.info("Farm site selected at {} (reachable dist={} treeBlocks={})",
                        candidate.center,
                        String.format("%.2f", dist),
                        candidate.blockingTreeBlocks);
                return candidate.center;
            }

            LOGGER.info("Farm site rejected at {} (unreachable dist={})", candidate.center, String.format("%.2f", dist));
        }

        return null;
    }

    private static BlockPos alignCenterToFarmAreaMedianY(ServerWorld world, BlockPos center) {
        Integer medianY = estimateFarmAreaMedianSurfaceY(world, center);
        if (medianY == null || medianY == center.getY()) {
            return center;
        }
        return new BlockPos(center.getX(), medianY, center.getZ());
    }

    private static boolean isInsideTree(ServerPlayerEntity bot, ServerWorld world) {
        BlockPos feet = bot.getBlockPos();
        BlockPos head = feet.up();
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        return feetState.isIn(BlockTags.LEAVES) || feetState.isIn(BlockTags.LOGS)
                || headState.isIn(BlockTags.LEAVES) || headState.isIn(BlockTags.LOGS);
    }

    private static void secureIrrigationContainment(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos center) {
        BlockPos[] hole = {center, center.add(1, 0, 0), center.add(0, 0, 1), center.add(1, 0, 1)};
        Set<BlockPos> holeSet = Set.of(hole);

        // First pass: seal multiple layers below the hole to prevent downward leaks
        for (BlockPos pos : hole) {
            for (int dy = 1; dy <= 3; dy++) {
                BlockPos below = pos.down(dy);
                BlockState belowState = world.getBlockState(below);
                if (belowState.isAir() || belowState.isOf(Blocks.WATER)) {
                    fillWithDirt(bot, world, below);
                } else if (belowState.isSolidBlock(world, below)) {
                    break; // hit solid, stop filling
                }
            }
        }

        // Second pass: seal horizontal edges, extending outward and deeper to contain water
        int horizontalSealSteps = 3; // how far outward to seal
        int verticalSealDepth = 4; // how deep below edges to seal
        for (BlockPos pos : hole) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                for (int step = 1; step <= horizontalSealSteps; step++) {
                    BlockPos edge = pos.offset(dir, step);
                    if (holeSet.contains(edge)) continue; // inside the hole
                    for (int dy = 0; dy <= verticalSealDepth; dy++) {
                        BlockPos checkPos = edge.down(dy);
                        BlockState edgeState = world.getBlockState(checkPos);
                        if (edgeState.getCollisionShape(world, checkPos).isEmpty() || edgeState.isOf(Blocks.WATER) || edgeState.isReplaceable()) {
                            fillWithDirt(bot, world, checkPos);
                        } else {
                            break; // hit solid, stop deeper sealing for this column
                        }
                    }
                }
            }
        }

        // Third pass: aggressively clear any flowing water exiting the basin in a wider area
        int scanRadius = Math.max(3, HYDRATION_RADIUS + 1);
        int cleanupAttempts = 3;
        for (int pass = 0; pass < cleanupAttempts; pass++) {
            boolean foundFlow = false;
            for (int dx = -scanRadius; dx <= scanRadius; dx++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    BlockPos check = center.add(dx, 0, dz);
                    if (holeSet.contains(check)) continue;
                    BlockState state = world.getBlockState(check);
                    if (state.isOf(Blocks.WATER) && !state.getFluidState().isStill()) {
                        LOGGER.info("Removing stray flowing water at {} (pass={})", check, pass);
                        pickupWater(bot, world, check);
                        fillWithDirt(bot, world, check);
                        foundFlow = true;
                    }
                    // Also check one and two levels below for water seepage
                    for (int down = 1; down <= 2; down++) {
                        BlockPos checkBelow = check.down(down);
                        BlockState stateBelow = world.getBlockState(checkBelow);
                        if (stateBelow.isOf(Blocks.WATER) && !stateBelow.getFluidState().isStill()) {
                            LOGGER.info("Removing seepage flowing water at {} (pass={})", checkBelow, pass);
                            pickupWater(bot, world, checkBelow);
                            fillWithDirt(bot, world, checkBelow);
                            foundFlow = true;
                        }
                    }
                }
            }
            if (!foundFlow) break;
        }
    }

    private static void escapeTreeAndWoodcut(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, SkillContext ctx, FarmFootprint footprint) {
        abortIfRequested(bot);
        BlockPos center = footprint == null ? null : footprint.irrigationAnchor();
        boolean insideTree = isInsideTree(bot, world);
        boolean skyBlocked = !world.isSkyVisible(bot.getBlockPos().up(3));
        int nearbyTreeBlocks = countBlockingTreeBlocks(world, footprint);
        boolean nearbyTree = nearbyTreeBlocks > 0;
        if (!insideTree && !skyBlocked && !nearbyTree) {
            return;
        }

        ChatUtils.sendSystemMessage(source, "Freeing myself from nearby trees before farming.");
        for (int attempt = 1; attempt <= 2; attempt++) {
            abortIfRequested(bot);
            LOGGER.info("escapeTree attempt {} insideTree={} skyBlocked={} nearbyTree={} blockingTreeBlocks={}",
                    attempt, insideTree, skyBlocked, nearbyTree, nearbyTreeBlocks);
            int before = nearbyTreeBlocks;
            SkillExecutionResult result = runWoodcutInline(source, new SkillContext(source, ctx.sharedState()), footprint);
            LOGGER.info("escapeTree woodcut result success={} msg={}", result.success(), result.message());
            // Only fall back to the brute per-block clear when WoodcutSkill
            // couldn't reduce the blocker count. When woodcut IS making
            // progress, running the brute clear in parallel is what used to
            // leave floaters (no topology awareness, arbitrary block order).
            int afterWoodcut = countBlockingTreeBlocks(world, footprint);
            int locallyCleared = 0;
            if (afterWoodcut > 0 && afterWoodcut >= before) {
                LOGGER.info("escapeTree: woodcut made no progress ({} -> {}); falling back to local brute clear", before, afterWoodcut);
                locallyCleared = clearBlockingTreeBlocksLocally(bot, world, source, footprint);
            }
            if (locallyCleared > 0) {
                LOGGER.info("escapeTree local bounded clear removed {} in-bounds blocker(s)", locallyCleared);
            }
            net.wcfcarolina13.GameAI.BotEventHandler.rescueFromBurial(bot);
            insideTree = isInsideTree(bot, world);
            skyBlocked = !world.isSkyVisible(bot.getBlockPos().up(3));
            nearbyTreeBlocks = countBlockingTreeBlocks(world, footprint);
            nearbyTree = nearbyTreeBlocks > 0;
            if (!insideTree && !nearbyTree) {
                break;
            }
            if (nearbyTree && nearbyTreeBlocks >= before) {
                LOGGER.info("escapeTree stopping early: no tree-clear progress (before={}, after={})", before, nearbyTreeBlocks);
                break;
            }
        }
    }

    private static boolean hasSimplePrecipice(ServerWorld world, BlockPos center) {
        BlockState baseState = world.getBlockState(center);
        if (baseState.isIn(BlockTags.LOGS) || baseState.isIn(BlockTags.LEAVES)) {
            return false; // perched in a tree; handle via woodcut, not terrain abort
        }
        int radius = 3;
        int depth = 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos pos = center.add(dx, 0, dz);
                int airBelow = 0;
                for (int dy = 1; dy <= depth; dy++) {
                    BlockPos check = pos.down(dy);
                    if (world.getBlockState(check).isAir()) {
                        airBelow++;
                        if (airBelow >= depth) {
                            LOGGER.warn("Detected drop near {} at {}", center, check);
                            return true;
                        }
                    } else {
                        break;
                    }
                }
            }
        }
        return false;
    }

    private static void clearBlockingTrees(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, SkillContext ctx, FarmFootprint footprint) {
        abortIfRequested(bot);
        int attempts = 0;
        int blockingTreeBlocks = countBlockingTreeBlocks(world, footprint);
        while (attempts < 3 && blockingTreeBlocks > 0) {
            abortIfRequested(bot);
            attempts++;
            ChatUtils.sendSystemMessage(source, "Clearing trees near the farm area (pass " + attempts + ").");
            SkillContext woodcutCtx = new SkillContext(source, ctx.sharedState());
            SkillExecutionResult woodcutResult = runWoodcutInline(source, woodcutCtx, footprint);
            // Last-ditch brute clear: only fires when the inline WoodcutSkill
            // failed to reduce the blocker count on this pass. Prevents the
            // floater-producing parallel run of brute-clear + woodcut.
            int afterWoodcut = countBlockingTreeBlocks(world, footprint);
            int locallyCleared = 0;
            if (afterWoodcut > 0 && afterWoodcut >= blockingTreeBlocks) {
                LOGGER.info("clearBlockingTrees pass={}: woodcut made no progress ({} -> {}); falling back to local brute clear",
                        attempts, blockingTreeBlocks, afterWoodcut);
                locallyCleared = clearBlockingTreeBlocksLocally(bot, world, source, footprint);
            }
            int nextBlockingTreeBlocks = countBlockingTreeBlocks(world, footprint);
            LOGGER.info("clearBlockingTrees pass={} result={} before={} after={}",
                    attempts, woodcutResult.success(), blockingTreeBlocks, nextBlockingTreeBlocks);
            if (locallyCleared > 0) {
                LOGGER.info("clearBlockingTrees pass={} local bounded clear removed {} blocker(s)", attempts, locallyCleared);
            }
            if (!woodcutResult.success()) {
                ChatUtils.sendSystemMessage(source, "Tree clearing attempt " + attempts + " failed; trying again if needed.");
            } else if (nextBlockingTreeBlocks <= 0) {
                break;
            }
            if (nextBlockingTreeBlocks >= blockingTreeBlocks) {
                LOGGER.info("clearBlockingTrees stopping: no progress reducing blocking trees (before={}, after={})",
                        blockingTreeBlocks, nextBlockingTreeBlocks);
                break;
            }
            blockingTreeBlocks = nextBlockingTreeBlocks;
        }
    }

    private static int countBlockingTreeBlocks(ServerWorld world, BlockPos center) {
        return countBlockingTreeBlocks(world, FarmFootprint.fromIrrigationAnchor(center));
    }

    private static boolean isWithinFarmWoodcutBounds(FarmFootprint footprint, BlockPos pos) {
        if (footprint == null || pos == null) {
            return false;
        }
        return pos.getX() >= footprint.minX() - FARM_WOODCUT_BUFFER
                && pos.getX() <= footprint.maxX() + FARM_WOODCUT_BUFFER
                && pos.getY() >= footprint.irrigationAnchor().getY()
                && pos.getY() <= footprint.irrigationAnchor().getY() + FARM_WOODCUT_VERTICAL_RANGE
                && pos.getZ() >= footprint.minZ() - FARM_WOODCUT_BUFFER
                && pos.getZ() <= footprint.maxZ() + FARM_WOODCUT_BUFFER;
    }

    private static List<BlockPos> collectBlockingTreeBlocks(ServerWorld world, FarmFootprint footprint) {
        if (world == null || footprint == null) {
            return List.of();
        }
        List<BlockPos> blockers = new ArrayList<>();
        int minX = footprint.minX() - FARM_WOODCUT_BUFFER;
        int maxX = footprint.maxX() + FARM_WOODCUT_BUFFER;
        int minZ = footprint.minZ() - FARM_WOODCUT_BUFFER;
        int maxZ = footprint.maxZ() + FARM_WOODCUT_BUFFER;
        int minY = footprint.irrigationAnchor().getY();
        int maxY = footprint.irrigationAnchor().getY() + FARM_WOODCUT_VERTICAL_RANGE;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {
                        blockers.add(pos);
                    }
                }
            }
        }
        return blockers;
    }

    private static BlockPos findLocalTreeClearStand(ServerWorld world, FarmFootprint footprint, BlockPos target) {
        if (world == null || footprint == null || target == null) {
            return null;
        }
        BlockPos direct = findStandingSpot(world, target);
        if (direct != null && isWithinFarmWoodcutBounds(footprint, direct) && isLocalTreeClearStandGround(world, direct)) {
            return direct;
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                candidates.add(target.add(dx, 0, dz));
                candidates.add(target.add(dx, -1, dz));
                candidates.add(target.add(dx, 1, dz));
            }
        }
        return candidates.stream()
                .filter(candidate -> isWithinFarmWoodcutBounds(footprint, candidate))
                .filter(candidate -> isLocalTreeClearStandGround(world, candidate))
                .min(Comparator.comparingDouble(candidate -> candidate.getSquaredDistance(target)))
                .orElse(null);
    }

    private static boolean isLocalTreeClearStandGround(ServerWorld world, BlockPos ground) {
        if (world == null || ground == null || !isSafeStandingGround(world, ground)) {
            return false;
        }
        BlockState state = world.getBlockState(ground);
        return !state.isIn(BlockTags.LOGS) && !state.isIn(BlockTags.LEAVES);
    }

    private static Comparator<BlockPos> localTreeBlockerComparator(ServerWorld world,
                                                                   ServerPlayerEntity bot,
                                                                   FarmFootprint footprint,
                                                                   BlockPos clusterAnchor) {
        return Comparator
                .comparingInt((BlockPos pos) -> world.getBlockState(pos).isIn(BlockTags.LOGS) ? 0 : 1)
                .thenComparingInt(pos -> clusterAnchor != null && pos.getSquaredDistance(clusterAnchor) <= 9.0D ? 0 : 1)
                .thenComparingDouble(pos -> bot.getBlockPos().getSquaredDistance(pos))
                .thenComparingDouble(pos -> pos.getSquaredDistance(footprint.centerTarget()));
    }

    private static int clearBlockingTreeBlocksLocally(ServerPlayerEntity bot,
                                                      ServerWorld world,
                                                      ServerCommandSource source,
                                                      FarmFootprint footprint) {
        if (bot == null || world == null || source == null || footprint == null) {
            return 0;
        }
        int cleared = 0;
        int stalled = 0;
        Set<Long> skipped = new HashSet<>();
        BlockPos clusterAnchor = null;
        while (cleared < FARM_LOCAL_TREE_CLEAR_LIMIT && stalled < 8) {
            abortIfRequested(bot);
            List<BlockPos> blockers = collectBlockingTreeBlocks(world, footprint).stream()
                    .filter(pos -> !skipped.contains(pos.asLong()))
                    .filter(pos -> isMutationAuthorized(bot, world, pos))
                    .sorted(localTreeBlockerComparator(world, bot, footprint, clusterAnchor))
                    .toList();
            if (blockers.isEmpty()) {
                break;
            }
            BlockPos blocker = blockers.get(0);
            if (!isWithinFarmWoodcutBounds(footprint, blocker)) {
                skipped.add(blocker.asLong());
                continue;
            }
            BlockState state = world.getBlockState(blocker);
            if (!state.isIn(BlockTags.LOGS) && !state.isIn(BlockTags.LEAVES)) {
                skipped.add(blocker.asLong());
                continue;
            }
            if (!isWithinReach(bot, blocker)) {
                BlockPos stand = findLocalTreeClearStand(world, footprint, blocker);
                if (stand == null) {
                    skipped.add(blocker.asLong());
                    stalled++;
                    continue;
                }
                moveTo(source, bot, stand.up(), false);
                waitUntilClose(bot, stand, 2.5, 18, false);
            }
            if (!isWithinReach(bot, blocker)) {
                skipped.add(blocker.asLong());
                stalled++;
                continue;
            }
            int clearedThisCluster = 0;
            List<BlockPos> reachableCluster = collectBlockingTreeBlocks(world, footprint).stream()
                    .filter(pos -> isWithinReach(bot, pos))
                    .sorted(localTreeBlockerComparator(world, bot, footprint, blocker))
                    .limit(8)
                    .toList();
            for (BlockPos target : reachableCluster) {
                abortIfRequested(bot);
                BlockState targetState = world.getBlockState(target);
                if (!targetState.isIn(BlockTags.LOGS) && !targetState.isIn(BlockTags.LEAVES)) {
                    continue;
                }
                mineBlock(bot, target, world);
                if (!world.getBlockState(target).isIn(BlockTags.LOGS) && !world.getBlockState(target).isIn(BlockTags.LEAVES)) {
                    cleared++;
                    clearedThisCluster++;
                    clusterAnchor = target.toImmutable();
                    skipped.remove(target.asLong());
                    if (cleared >= FARM_LOCAL_TREE_CLEAR_LIMIT) {
                        break;
                    }
                }
            }
            if (clearedThisCluster == 0) {
                skipped.add(blocker.asLong());
                stalled++;
            } else {
                stalled = 0;
            }
        }
        return cleared;
    }

    private static int countBlockingTreeBlocks(ServerWorld world, FarmFootprint footprint) {
        if (world == null || footprint == null) {
            return 0;
        }
        return collectBlockingTreeBlocks(world, footprint).size();
    }

    private static BlockPos snapToFarmGrid(ServerWorld world, BlockPos center) {
        int grid = HYDRATION_RADIUS * 2 + 2; // 10-wide footprint
        int baseX = Math.floorDiv(center.getX(), grid) * grid;
        int baseZ = Math.floorDiv(center.getZ(), grid) * grid;
        BlockPos best = new BlockPos(baseX, center.getY(), baseZ);
        if (farmAreaClear(world, best)) {
            return best;
        }
        // Search nearby grid cells for a clear spot
        int[] offsets = {0, 1, -1, 2, -2};
        for (int dx : offsets) {
            for (int dz : offsets) {
                BlockPos candidate = new BlockPos(baseX + dx * grid, center.getY(), baseZ + dz * grid);
                if (farmAreaClear(world, candidate)) {
                    return candidate;
                }
            }
        }
        return center; // fallback: original center if no clear grid cell
    }

    private static boolean farmAreaClear(ServerWorld world, BlockPos center) {
        String rejectReason = farmAreaRejectReason(world, center);
        if (rejectReason != null) {
            LOGGER.info("[FarmIrrigation] site-reject center={} reason={}", center, rejectReason);
            return false;
        }
        return true;
    }

    private static String farmAreaRejectReason(ServerWorld world, BlockPos center) {
        return assessFarmSite(world, FarmFootprint.fromIrrigationAnchor(center), false).rejectReason();
    }

    private static Integer estimateFarmAreaMedianSurfaceY(ServerWorld world, BlockPos center) {
        if (world == null || center == null) {
            return null;
        }
        List<Integer> heights = new ArrayList<>();
        int minX = center.getX() - HYDRATION_RADIUS;
        int maxX = center.getX() + 1 + HYDRATION_RADIUS;
        int minZ = center.getZ() - HYDRATION_RADIUS;
        int maxZ = center.getZ() + 1 + HYDRATION_RADIUS;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Walkable ground Y — see assessFarmSite for the rationale.
                int walkableY = SafePositionService.getWalkableGroundY(world, x, z);
                if (walkableY <= world.getBottomY()) {
                    continue;
                }
                BlockPos surface = new BlockPos(x, walkableY - 1, z);
                BlockState state = world.getBlockState(surface);
                if (state.isAir() || state.isOf(Blocks.WATER)) {
                    continue;
                }
                BlockState aboveSurface = world.getBlockState(new BlockPos(x, walkableY, z));
                if (aboveSurface.isOf(Blocks.WATER)) {
                    continue;
                }
                heights.add(surface.getY());
            }
        }
        if (heights.isEmpty()) {
            return null;
        }
        heights.sort(Integer::compareTo);
        return heights.get(heights.size() / 2);
    }

    private static String findFenceOrTetherHazard(ServerWorld world,
                                                  BlockPos center,
                                                  int minX,
                                                  int maxX,
                                                  int minZ,
                                                  int maxZ,
                                                  int minY,
                                                  int maxY) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.FENCE_GATES) || state.isIn(BlockTags.WALLS)) {
                        return "fence-block pos=" + pos.toShortString() + " block=" + state.getBlock().getTranslationKey();
                    }
                }
            }
        }

        Box scanBox = new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        List<LeashKnotEntity> knots = world.getEntitiesByClass(
                LeashKnotEntity.class,
                scanBox,
                knot -> knot != null && knot.isAlive()
        );
        if (!knots.isEmpty()) {
            LeashKnotEntity knot = knots.get(0);
            return "leash-knot pos=" + knot.getBlockPos().toShortString();
        }

        List<MobEntity> leashed = world.getEntitiesByClass(
                MobEntity.class,
                scanBox,
                mob -> mob != null && mob.isAlive() && mob.isLeashed()
        );
        if (!leashed.isEmpty()) {
            MobEntity mob = leashed.get(0);
            return "leashed-mob type=" + mob.getType().toString() + " pos=" + mob.getBlockPos().toShortString();
        }

        return null;
    }

    private static boolean needsWoodcut(ServerPlayerEntity bot, ServerWorld world) {
        var tree = TreeDetector.findNearestTree(bot, TREE_CLEAR_RADIUS, 6, null);
        return tree.isPresent();
    }

    private static boolean fillIrrigation(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos center, BlockPos refillSource) {
        BlockPos[] corners = {center, center.add(1, 0, 1)};
        BlockPos[] hole = {center, center.add(1, 0, 0), center.add(0, 0, 1), center.add(1, 0, 1)};
        int noProgressAttempts = 0;
        String previousEndState = null;

        for (int attempt = 1; attempt <= IRRIGATION_ATTEMPTS; attempt++) {
            abortIfRequested(bot);
            String startState = describeHoleWater(world, hole);
            LOGGER.info("[FarmIrrigation] fillIrrigation attempt {} holeState={}", attempt, startState);
            int[] startCounts = countHoleWater(world, hole);
            if (isAcceptableIrrigation(startCounts)) {
                LOGGER.info("[FarmIrrigation] irrigation already acceptable at attempt {} (still={}, water={}, flow={}, dry={})",
                        attempt, startCounts[1], startCounts[0], startCounts[2], startCounts[3]);
                return true;
            }
            // If we got pulled into water during bucket refills, get back onto land before placing.
            escapeWaterIfNeeded(bot, world, source, false);

            for (BlockPos target : corners) {
                abortIfRequested(bot);
                if (isStillWater(world, target)) {
                    continue;
                }
                if (!hasWaterPlacementSupport(world, target)) {
                    LOGGER.warn("[FarmIrrigation] no support faces at {}; reinforcing edges before placement", target.toShortString());
                    reinforceIrrigationEdges(bot, world, source, center);
                }
                if (!ensureWaterBucketInHand(bot, world, source, center, refillSource)) {
                    LOGGER.warn("[FarmIrrigation] no water bucket ready for placement at {}", target);
                    return false;
                }

                List<BlockPos> stands = enumerateStandingAroundHole(world, target, center);
                if (stands.isEmpty()) {
                    clearIrrigationAccessRing(bot, world, center);
                    stands = enumerateStandingAroundHole(world, target, center);
                }
                if (stands.size() > 8) {
                    stands = new ArrayList<>(stands.subList(0, 8));
                }
                boolean placed = false;
                for (BlockPos stand : stands) {
                    abortIfRequested(bot);
                    moveTo(source, bot, stand, false);
                    waitUntilClose(bot, target, 3.0, 24, false);
                    if (placeWater(bot, world, target)) {
                        cleanupImmediateIrrigationSpill(bot, world, center);
                        placed = true;
                        break;
                    }
                }

                if (!placed) {
                    LOGGER.warn("[FarmIrrigation] failed to place water at {} after {} stand attempts", target, stands.size());
                    // Try stepping into the hole and reattempt once more
                    if (tryPlaceFromInside(bot, world, source, target, center)) {
                        cleanupImmediateIrrigationSpill(bot, world, center);
                        placed = true;
                    } else {
                        pickupMisplacedWater(bot, world, center);
                    }
                }
            }

            // Water physics can take a moment; wait and re-check a few times before declaring failure.
            if (awaitAcceptableIrrigation(world, hole, 8, 250)) {
                int[] c = countHoleWater(world, hole);
                LOGGER.info("[FarmIrrigation] Irrigation hole filled on attempt {} (still={}, water={}, flow={}, dry={})",
                        attempt, c[1], c[0], c[2], c[3]);
                return true;
            }

            String endState = describeHoleWater(world, hole);
            if (endState.equals(startState)) {
                noProgressAttempts++;
            } else {
                noProgressAttempts = 0;
            }
            if (previousEndState != null && previousEndState.equals(endState)) {
                noProgressAttempts++;
            }
            previousEndState = endState;
            if (noProgressAttempts >= 2) {
                LOGGER.warn("[FarmIrrigation] no-progress early-stop at attempt {} state={} noProgressAttempts={}",
                        attempt, endState, noProgressAttempts);
                return false;
            }

            LOGGER.warn("[FarmIrrigation] incomplete after attempt {}, cleaning stray flow and retrying", attempt);
            cleanupImmediateIrrigationSpill(bot, world, center);
            pickupMisplacedWater(bot, world, center);
            // If we lost water buckets by placing incorrectly, try to refill before next attempt
            ensureWaterSupply(bot, world, source, center, refillSource);
        }

        LOGGER.warn("[FarmIrrigation] failed to establish stable irrigation after {} attempts", IRRIGATION_ATTEMPTS);
        return false;
    }

    private static int tillPlots(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos center, int seedLimit) {
        List<BlockPos> plots = farmPlots(center);
        int tilled = 0;

        for (BlockPos plot : plots) {
            abortIfRequested(bot);
            if (tilled >= seedLimit) {
                break; // Do not till more than we can plant
            }

            BlockState state = world.getBlockState(plot);
            if (state.isOf(Blocks.FARMLAND)) {
                continue;
            }
            if (!TILLABLE_SURFACES.contains(state.getBlock())) {
                continue;
            }

            ensureStandingOffFarmland(bot, world, source, plot);

            // Prefer an adjacent non-farmland stand. If none exists (interior of a large farm),
            // stand on the plot itself (still dirt/grass at this point) while sneaking.
            BlockPos stand = findStandingSpot(world, plot);
            boolean sneak = false;
            if (stand == null) {
                stand = plot;
                sneak = true;
            }
            BotActions.sneak(bot, sneak);
            moveTo(source, bot, stand.up());

            clearAbove(bot, world, plot);
            LookController.faceBlock(bot, plot);
            sleep(ACTION_DELAY_MS);

            if (BotActions.useHoe(bot, plot)) {
                tilled++;
                sleep(120);
            }
        }

        BotActions.sneak(bot, false);

        LOGGER.info("Tilled {} plots (limit set by {} seeds)", tilled, seedLimit);
        return tilled;
    }

    private static int plantSeeds(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos center, int seedLimit) {
        List<BlockPos> plots = farmPlots(center);
        int planted = 0;

        for (BlockPos plot : plots) {
            abortIfRequested(bot);
            if (planted >= seedLimit) {
                break;
            }
            if (countSeeds(bot.getInventory()) <= 0) {
                break;
            }

            BlockState base = world.getBlockState(plot);
            if (!base.isOf(Blocks.FARMLAND)) {
                continue;
            }
            if (!world.isAir(plot.up())) {
                continue;
            }

            // Prefer non-farmland stand; if none, sneak on the farmland itself to avoid trampling
            BlockPos stand = findStandingSpot(world, plot);
            boolean sneak = false;
            if (stand == null) {
                stand = plot;
                sneak = true;
            } else {
                ensureStandingOffFarmland(bot, world, source, plot);
            }

            BotActions.sneak(bot, sneak);
            logVerticalIfJump("plant-preMove", bot);
            moveTo(source, bot, stand.up());
            logVerticalIfJump("plant-postMove", bot);

            int seedSlot = ensureAnySeedHotbar(bot);
            if (seedSlot < 0) {
                LOGGER.warn("Seeds disappeared while planting.");
                break;
            }
            selectHotbarSlot(bot, seedSlot);

            LookController.faceBlock(bot, plot);
            sleep(ACTION_DELAY_MS);

            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(plot).add(0, 0.5, 0),
                    Direction.UP,
                    plot,
                    false
            );

                if (!isMutationAuthorized(bot, world, plot.up())) {
                continue;
                }

            ActionResult result = bot.interactionManager.interactBlock(
                    bot,
                    world,
                    bot.getMainHandStack(),
                    Hand.MAIN_HAND,
                    hit
            );

            if (result.isAccepted()) {
                bot.swingHand(Hand.MAIN_HAND, true);
                planted++;
                sleep(120);
            }
        }
        BotActions.sneak(bot, false);

        LOGGER.info("Planted {} seeds", planted);
        return planted;
    }

    private static BlockPos findFarmCenter(ServerWorld world, BlockPos botPos) {
        BlockPos pos = botPos;
        // Drift down to the first solid block
        for (int i = 0; i < 6 && world.getBlockState(pos).isAir(); i++) {
            pos = pos.down();
        }
        if (!world.getBlockState(pos).isSolidBlock(world, pos) && world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) {
            pos = pos.down();
        }
        return pos;
    }

    private static boolean ensureWaterSupply(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos searchOrigin, BlockPos refillSource) {
        abortIfRequested(bot);
        PlayerInventory inventory = bot.getInventory();
        int waterBuckets = countItem(inventory, Items.WATER_BUCKET);
        int emptyBuckets = countItem(inventory, Items.BUCKET);
        BlockPos origin = (searchOrigin != null ? searchOrigin : bot.getBlockPos());
        LOGGER.info("ensureWaterSupply: waterBuckets={}, emptyBuckets={}, searchOrigin={}, refillSource={}",
                waterBuckets, emptyBuckets, origin, refillSource);

        if (waterBuckets >= 2) {
            return true; // enough for irrigation; leave extra empties for final top-off
        }

        if (emptyBuckets > 0 || waterBuckets < 2) {
            List<BlockPos> sources = new ArrayList<>();
            if (refillSource != null && world.getBlockState(refillSource).isOf(Blocks.WATER)) {
                sources.add(refillSource);
            } else if (refillSource != null) {
                LOGGER.warn("[FarmIrrigation] refillSource {} is no longer water; skipping preferred refill", refillSource.toShortString());
            }
            // Add additional still sources nearby (dedup with refillSource)
            for (BlockPos src : findStillWaterSources(world, origin, WATER_SEARCH_RADIUS)) {
                if (!sources.contains(src)) {
                    sources.add(src);
                }
            }
            if (sources.isEmpty()) {
                sources = findAnyWaterSources(world, origin, WATER_SEARCH_RADIUS);
            }

            for (BlockPos src : sources) {
                abortIfRequested(bot);
                LOGGER.info("Attempting to fill buckets from source {}", src);
                fillBucketsAt(bot, world, source, src);
                waterBuckets = countItem(inventory, Items.WATER_BUCKET);
                emptyBuckets = countItem(inventory, Items.BUCKET);
                LOGGER.info("Post-source {}: waterBuckets={}, emptyBuckets={}", src, waterBuckets, emptyBuckets);
                if (emptyBuckets == 0) {
                    break;
                }
            }
        }

        // Fallback: if no still source was found, try any water within range
        if (waterBuckets == 0 && emptyBuckets > 0 && refillSource == null) {
            BlockPos nearbyWater = findAnyWaterSource(world, origin, WATER_SEARCH_RADIUS);
            if (nearbyWater != null) {
                LOGGER.info("Fallback water (any) found at {}, attempting refill", nearbyWater);
                fillBucketsAt(bot, world, source, nearbyWater);
                waterBuckets = countItem(inventory, Items.WATER_BUCKET);
            } else {
                LOGGER.warn("No water (still or flowing) found within {} blocks of {}", WATER_SEARCH_RADIUS, origin);
            }
        }

        LOGGER.info("Water supply result: waterBuckets={}, emptyBuckets={}", waterBuckets, emptyBuckets);

        if (emptyBuckets == 0) {
            if (waterBuckets >= 2) {
                return true;
            }
            if (waterBuckets >= 1) {
                ChatUtils.sendSystemMessage(source, "Proceeding with one water bucket; could not find enough water to fill two.");
                return true;
            }
        }

        ChatUtils.sendSystemMessage(source, "Could not fill all buckets (have " + waterBuckets + " water, empty left " + emptyBuckets + ").");
        return waterBuckets >= 2;
    }

    private static void fillBucketsAt(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos waterSource) {
        abortIfRequested(bot);
        if (!world.getBlockState(waterSource).isOf(Blocks.WATER)) {
            LOGGER.warn("[FarmIrrigation] fillBucketsAt skipped non-water source {}", waterSource.toShortString());
            return;
        }
        List<BlockPos> stands = enumerateStandingNearWater(world, waterSource);
        if (stands.isEmpty()) {
            LOGGER.warn("Unable to find stand position near water at {}", waterSource);
            // Avoid walking into open water (can strand the bot). Let the caller try another source.
            return;
        }

        boolean filledAny = false;
        int refillAttempts = 0;
        while (true) {
            abortIfRequested(bot);
            if (++refillAttempts > 8) {
                LOGGER.warn("[FarmIrrigation] fillBucketsAt attempt limit reached at source {}", waterSource.toShortString());
                break;
            }
            int bucketSlot = findInventoryItemSlot(bot.getInventory(), Items.BUCKET);
            if (bucketSlot == -1) {
                LOGGER.info("fillBucketsAt: no more empty buckets");
                break;
            }
            bucketSlot = ensureHotbarAccess(bot, bucketSlot);
            if (bucketSlot == -1) {
                LOGGER.warn("fillBucketsAt: could not move bucket {} to hotbar", bucketSlot);
                break;
            }

            boolean scooped = false;
            for (BlockPos stand : stands) {
                abortIfRequested(bot);
                moveTo(source, bot, stand);
                waitUntilClose(bot, waterSource, 2.4, 24);
                LOGGER.info("Trying refill from stand {}", stand);
                if (scoopWater(bot, world, waterSource, bucketSlot)) {
                    scooped = true;
                    break;
                }
            }

            if (!scooped) {
                LOGGER.warn("scoopWater failed after trying all stand positions at {}", waterSource);
                break;
            }

            filledAny = true;
            sleep(120);

            int waterBuckets = countItem(bot.getInventory(), Items.WATER_BUCKET);
            int emptyBuckets = countItem(bot.getInventory(), Items.BUCKET);
            LOGGER.info("Refill progress: waterBuckets={}, emptyBuckets={}", waterBuckets, emptyBuckets);
            if (emptyBuckets == 0) {
                break;
            }
        }

        // After filling, ensure we can escape from water and return to solid ground
        escapeWaterIfNeeded(bot, world, source);

        if (filledAny) {
            ChatUtils.sendSystemMessage(source, "Filled all empty buckets with water.");
        }
    }

    private static boolean ensureWaterBucketInHand(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos searchOrigin, BlockPos refillSource) {
        abortIfRequested(bot);
        PlayerInventory inventory = bot.getInventory();
        int slot = findHotbarItemSlot(inventory, Items.WATER_BUCKET);
        if (slot == -1) {
            int invSlot = findInventoryItemSlot(inventory, Items.WATER_BUCKET);
            if (invSlot != -1) {
                slot = ensureHotbarAccess(bot, invSlot);
            }
        }

        if (slot == -1) {
            int emptySlot = findInventoryItemSlot(inventory, Items.BUCKET);
            if (emptySlot != -1) {
                emptySlot = ensureHotbarAccess(bot, emptySlot);
                if (emptySlot != -1) {
                    BlockPos origin = (refillSource != null ? refillSource : (searchOrigin != null ? searchOrigin : bot.getBlockPos()));
                    List<BlockPos> sources = new ArrayList<>();
                    if (refillSource != null && world.getBlockState(refillSource).isOf(Blocks.WATER)) {
                        sources.add(refillSource);
                    } else if (refillSource != null) {
                        LOGGER.warn("[FarmIrrigation] preferred refill source {} is dry; searching alternatives", refillSource.toShortString());
                    }
                    // Fall back to any still water near the farm site.
                    for (BlockPos src : findStillWaterSources(world, origin, WATER_SEARCH_RADIUS)) {
                        if (!sources.contains(src)) {
                            sources.add(src);
                        }
                    }
                    if (sources.isEmpty()) {
                        for (BlockPos src : findAnyWaterSources(world, origin, WATER_SEARCH_RADIUS)) {
                            if (!sources.contains(src)) {
                                sources.add(src);
                            }
                        }
                    }

                    for (BlockPos src : sources) {
                        abortIfRequested(bot);
                        if (scoopFromSourceLocation(bot, world, source, src, emptySlot)) {
                            slot = emptySlot;
                            break;
                        }
                    }
                }
            }
        }

        if (slot == -1) {
            LOGGER.warn("No water bucket obtainable even after refilling attempts.");
            return false;
        }
        selectHotbarSlot(bot, slot);
        return bot.getMainHandStack().isOf(Items.WATER_BUCKET);
    }

    private static boolean scoopFromSourceLocation(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos waterSource, int bucketSlot) {
        abortIfRequested(bot);
        if (!world.getBlockState(waterSource).isOf(Blocks.WATER)) {
            LOGGER.warn("[FarmIrrigation] scoopFromSourceLocation skipped non-water source {}", waterSource.toShortString());
            return false;
        }
        BlockPos stand = findStandingNearWater(world, waterSource);
        if (stand == null) {
            LOGGER.warn("No solid spot near water at {}", waterSource);
            return false;
        }
        moveTo(source, bot, stand);
        LOGGER.info("scoopFromSourceLocation standing at {} for water {}", stand, waterSource);
        return scoopWater(bot, world, waterSource, bucketSlot);
    }

    private static boolean placeWater(ServerPlayerEntity bot, ServerWorld world, BlockPos waterPos) {
        abortIfRequested(bot);
        if (!isMutationAuthorized(bot, world, waterPos)) {
            return false;
        }
        if (!isWithinReach(bot, waterPos)) {
            LOGGER.debug("Water position {} out of reach", waterPos);
            return false;
        }
        if (!ensureWaterBucketSelected(bot)) {
            LOGGER.warn("[FarmIrrigation] water bucket not selected for placement at {} (hand={})",
                    waterPos.toShortString(), bot.getMainHandStack().getItem());
            return false;
        }

        LookController.faceBlock(bot, waterPos);
        sleep(ACTION_DELAY_MS);

        // Build solid block faces that, when clicked, would place water at waterPos.
        List<BlockHitResult> hits = buildWaterPlacementHits(world, waterPos);
        if (hits.isEmpty()) {
            LOGGER.warn("[FarmIrrigation] placeWater: no support faces at {}", waterPos.toShortString());
        }

        if (placeWaterOnServerThread(bot, world, waterPos, hits)) {
            return true;
        }

        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos adj = waterPos.offset(dir);
            if (world.getBlockState(adj).isOf(Blocks.WATER)) {
                pickupWater(bot, world, adj);
            }
        }

        return false;
    }

    private static boolean placeWaterOnServerThread(ServerPlayerEntity bot, ServerWorld world, BlockPos waterPos, List<BlockHitResult> hits) {
        var server = bot.getCommandSource().getServer();
        if (server == null) {
            LOGGER.warn("[FarmIrrigation] placeWater: no server available for {}", waterPos.toShortString());
            return false;
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                if (!isMutationAuthorized(bot, world, waterPos)) {
                    future.complete(false);
                    return;
                }
                if (!ensureWaterBucketSelected(bot)) {
                    LOGGER.warn("[FarmIrrigation] water bucket unavailable on server thread at {}", waterPos.toShortString());
                    future.complete(false);
                    return;
                }

                for (BlockHitResult hit : hits) {
                    if (!ensureWaterBucketSelected(bot)) {
                        LOGGER.warn("[FarmIrrigation] hand changed before useOnBlock at {} (hand={})",
                                waterPos.toShortString(), bot.getMainHandStack().getItem());
                        future.complete(false);
                        return;
                    }
                    ItemStack handStack = bot.getMainHandStack();
                    boolean hadWaterBucket = handStack.isOf(Items.WATER_BUCKET);
                    ActionResult result = handStack.useOnBlock(new ItemUsageContext(bot, Hand.MAIN_HAND, hit));
                    ItemStack handAfter = bot.getMainHandStack();
                    boolean bucketConsumed = hadWaterBucket && !handAfter.isOf(Items.WATER_BUCKET);
                    LOGGER.info("[FarmIrrigation] placeWater useOnBlock at {} via {} face={} result={} hand={} consumed={}",
                            waterPos.toShortString(), hit.getBlockPos().toShortString(), hit.getSide(), result, handAfter.getItem(), bucketConsumed);
                    // Bucket consumption is the only reliable proof of placement.
                    // World state (isOf(Blocks.WATER)) would false-positive on
                    // pre-existing flowing water from a neighboring source that
                    // has already spread into waterPos (e.g. during the 2nd
                    // corner placement of a 2x2 infinite-source build).
                    if (bucketConsumed) {
                        bot.swingHand(Hand.MAIN_HAND, true);
                        future.complete(true);
                        return;
                    }
                }

                if (!ensureWaterBucketSelected(bot)) {
                    future.complete(false);
                    return;
                }
                LookController.faceBlock(bot, waterPos);
                ItemStack fallbackBefore = bot.getMainHandStack();
                boolean hadWaterBucketFallback = fallbackBefore.isOf(Items.WATER_BUCKET);
                ActionResult itemResult = bot.interactionManager.interactItem(
                        bot,
                        world,
                        bot.getMainHandStack(),
                        Hand.MAIN_HAND
                );
                ItemStack fallbackAfter = bot.getMainHandStack();
                boolean fallbackBucketConsumed = hadWaterBucketFallback && !fallbackAfter.isOf(Items.WATER_BUCKET);
                LOGGER.info("[FarmIrrigation] placeWater interactItem fallback at {} result={} hand={} consumed={}",
                        waterPos.toShortString(), itemResult, fallbackAfter.getItem(), fallbackBucketConsumed);
                if (fallbackBucketConsumed) {
                    bot.swingHand(Hand.MAIN_HAND, true);
                    future.complete(true);
                    return;
                }

                future.complete(false);
            } catch (Throwable t) {
                LOGGER.warn("[FarmIrrigation] placeWater server-thread failure at {}: {}", waterPos.toShortString(), t.toString());
                future.complete(false);
            }
        });

        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[FarmIrrigation] placeWater server-thread timeout/failure at {}: {}", waterPos.toShortString(), e.getMessage());
            return false;
        }
    }

    private static boolean ensureWaterBucketSelected(ServerPlayerEntity bot) {
        int waterSlot = findHotbarItemSlot(bot.getInventory(), Items.WATER_BUCKET);
        if (waterSlot == -1) {
            int inventorySlot = findInventoryItemSlot(bot.getInventory(), Items.WATER_BUCKET);
            if (inventorySlot != -1) {
                waterSlot = ensureHotbarAccess(bot, inventorySlot);
            }
        }
        if (waterSlot == -1) {
            LOGGER.warn("[FarmIrrigation] no water bucket available for selection");
            return false;
        }
        selectHotbarSlot(bot, waterSlot);
        return bot.getMainHandStack().isOf(Items.WATER_BUCKET);
    }

    private static boolean hasWaterPlacementSupport(ServerWorld world, BlockPos waterPos) {
        return !buildWaterPlacementHits(world, waterPos).isEmpty();
    }

    private static List<BlockHitResult> buildWaterPlacementHits(ServerWorld world, BlockPos waterPos) {
        List<BlockHitResult> hits = new ArrayList<>();

        BlockPos below = waterPos.down();
        BlockState belowState = world.getBlockState(below);
        if (belowState.isSolidBlock(world, below) && !belowState.isOf(Blocks.CHEST) && !belowState.isOf(Blocks.TRAPPED_CHEST)) {
            Vec3d hitVec = Vec3d.of(below).add(0.5, 1.0, 0.5);
            hits.add(new BlockHitResult(hitVec, Direction.UP, below, false));
        }

        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = waterPos.offset(dir);
            BlockState neighborState = world.getBlockState(neighbor);
            if (neighborState.isSolidBlock(world, neighbor) && !neighborState.isOf(Blocks.CHEST) && !neighborState.isOf(Blocks.TRAPPED_CHEST)) {
                Direction hitFace = dir.getOpposite();
                double dx = dir.getOffsetX() * 0.5;
                double dz = dir.getOffsetZ() * 0.5;
                Vec3d hitVec = Vec3d.of(neighbor).add(0.5 - dx, 0.5, 0.5 - dz);
                hits.add(new BlockHitResult(hitVec, hitFace, neighbor, false));
            }
        }

        return hits;
    }

    private static boolean tryPlaceFromInside(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos waterPos, BlockPos center) {
        LOGGER.info("[FarmIrrigation] attempting inside-hole placement at {}", waterPos);
        BlockPos exit = findStandingAroundHole(world, waterPos, center);
        Vec3d enter = Vec3d.ofCenter(waterPos).add(0, 0.2, 0);
        BotActions.moveToward(bot, enter, 0.5);
        waitUntilClose(bot, waterPos, 1.5, 14, false);
        boolean placed = placeWater(bot, world, waterPos);
        if (exit != null) {
            moveTo(source, bot, exit, false);
        }
        return placed;
    }

    private static void pickupMisplacedWater(ServerPlayerEntity bot, ServerWorld world, BlockPos center) {
        BlockPos[] hole = {center, center.add(1, 0, 0), center.add(0, 0, 1), center.add(1, 0, 1)};
        Set<BlockPos> holeSet = Set.of(hole);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos check = center.add(dx, 0, dz);
                if (holeSet.contains(check)) {
                    continue;
                }
                BlockState state = world.getBlockState(check);
                // Only clean up flowing spill water; nearby still water may be a legitimate source.
                if (state.isOf(Blocks.WATER) && !state.getFluidState().isStill()) {
                    LOGGER.info("Picking up misplaced flowing water at {}", check);
                    pickupWater(bot, world, check);
                }
            }
        }
    }

    private static void cleanupImmediateIrrigationSpill(ServerPlayerEntity bot, ServerWorld world, BlockPos center) {
        BlockPos[] hole = {center, center.add(1, 0, 0), center.add(0, 0, 1), center.add(1, 0, 1)};
        Set<BlockPos> holeSet = Set.of(hole);
        int cleaned = 0;
        for (int x = center.getX() - 1; x <= center.getX() + 2; x++) {
            for (int z = center.getZ() - 1; z <= center.getZ() + 2; z++) {
                for (int y = center.getY(); y <= center.getY() + 1; y++) {
                    BlockPos check = new BlockPos(x, y, z);
                    if (holeSet.contains(check)) {
                        continue;
                    }
                    if (world.getBlockState(check).isOf(Blocks.WATER) && pickupWater(bot, world, check)) {
                        cleaned++;
                    }
                }
            }
        }
        if (cleaned > 0) {
            LOGGER.info("[FarmIrrigation] cleaned {} misplaced water tile(s) near {}", cleaned, center.toShortString());
        }
    }

    private static void clearAbove(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        BlockPos above = pos.up();
        BlockState state = world.getBlockState(above);
        if (state.isAir()) {
            return;
        }
        if (state.isReplaceable()) {
            mineBlock(bot, above, world);
        }
    }

    private static List<BlockPos> farmPlots(BlockPos center) {
        return farmPlots(FarmFootprint.fromIrrigationAnchor(center));
    }

    private static List<BlockPos> farmPlots(FarmFootprint footprint) {
        return footprint.plotPositions();
    }

    private static boolean isStillWater(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isOf(Blocks.WATER) && state.getFluidState().isStill();
    }

    /**
     * Returns {waterCount, stillCount, flowCount, dryCount} for the 2x2 irrigation hole.
     */
    private static int[] countHoleWater(ServerWorld world, BlockPos[] hole) {
        int water = 0;
        int still = 0;
        int flow = 0;
        int dry = 0;
        for (BlockPos pos : hole) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Blocks.WATER)) {
                water++;
                if (state.getFluidState().isStill()) {
                    still++;
                } else {
                    flow++;
                }
            } else {
                dry++;
            }
        }
        return new int[]{water, still, flow, dry};
    }

    /**
     * Waits briefly for water to settle. Accepts "good enough" irrigation: at least two still sources
     * in the 2x2 hole (even if the remaining tiles are still flowing/empty momentarily).
     */
    private static boolean awaitAcceptableIrrigation(ServerWorld world, BlockPos[] hole, int checks, int sleepMs) {
        for (int i = 0; i < Math.max(1, checks); i++) {
            sleep(Math.max(0, sleepMs));
            int[] c = countHoleWater(world, hole);
            if (isAcceptableIrrigation(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAcceptableIrrigation(int[] counts) {
        int water = counts[0];
        int still = counts[1];
        // Ideal: full still basin (2 diagonal sources propagate to 4).
        if (still >= 4) {
            return true;
        }
        // Good-enough: two source corners with near-full coverage while block
        // updates settle to turn the remaining cells into sources.
        if (still >= 2 && water >= 3) {
            return true;
        }
        // NOTE: historically there was a "still >= 1 && water >= 4" fallback
        // here. It was removed because it accepted irrigation holes that had
        // only ONE actual source and 3 flowing cells — NOT an infinite supply.
        // The fallback only ever fired when placeWaterOnServerThread falsely
        // reported success for the 2nd corner (false positive on pre-existing
        // flowing water). The placeWater check now uses bucket consumption as
        // the proof of placement, so this fallback is no longer needed.
        return false;
    }

    private static String describeHoleWater(ServerWorld world, BlockPos[] hole) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos pos : hole) {
            BlockState state = world.getBlockState(pos);
            String type = state.isAir() ? "air" : state.getBlock().getTranslationKey();
            String still = state.getFluidState().isStill() ? "still" : (state.getFluidState().isEmpty() ? "dry" : "flow");
            sb.append("[").append(pos.getX()).append(",").append(pos.getY()).append(",").append(pos.getZ()).append("=").append(type).append("/").append(still).append("]");
        }
        return sb.toString();
    }

    private static int stillNeighborCount(ServerWorld world, BlockPos pos) {
        int stillNeighbors = 0;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockState neighbor = world.getBlockState(pos.offset(dir));
            if (neighbor.isOf(Blocks.WATER) && neighbor.getFluidState().isStill()) {
                stillNeighbors++;
            }
        }
        return stillNeighbors;
    }

    /**
     * Higher means more likely to be on a shoreline (easier/safer to refill from).
     * Ocean-interior blocks typically have a score of 0.
     */
    private static int shoreScore(ServerWorld world, BlockPos pos) {
        int score = 0;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (!world.getBlockState(pos.offset(dir)).isOf(Blocks.WATER)) {
                score++;
            }
        }
        return score;
    }

    private static boolean isViableBucketRefillSource(ServerWorld world, BlockPos waterPos) {
        if (world == null || waterPos == null) {
            return false;
        }
        if (!world.getBlockState(waterPos).isOf(Blocks.WATER)) {
            return false;
        }
        // Prefer shoreline-accessible water (reduces "run into the ocean" behavior).
        if (shoreScore(world, waterPos) <= 0) {
            return false;
        }
        if (enumerateStandingNearWater(world, waterPos).isEmpty()) {
            return false;
        }
        // Avoid deep water where we can't easily get back to land.
        return findSolidGroundNearby(world, waterPos, 12) != null;
    }

    /** Distance within which a remembered water source is worth using for refills. */
    private static final int REMEMBERED_WATER_RADIUS = 48;

    private static long currentFarmTick(ServerWorld world) {
        return world.getServer() != null ? world.getServer().getOverworld().getTime() : world.getTime();
    }

    /**
     * Remembers a viable bucket-refill source. Score convention is
     * {@link WaterSpotMemory}'s: higher = better, and a confirmed still-water source
     * scores a flat 1.0.
     */
    private static void rememberIrrigationSource(ServerPlayerEntity bot, ServerWorld world, BlockPos source) {
        if (bot == null || world == null || source == null) {
            return;
        }
        try {
            BotHomeService.recordWaterSpot(bot, new WaterSpotMemory.WaterSpot(
                    source.getX(), source.getY(), source.getZ(),
                    1.0D,
                    currentFarmTick(world),
                    WaterSpotMemory.KIND_IRRIGATION));
        } catch (Exception e) {
            LOGGER.debug("Failed to remember irrigation source: {}", e.toString());
        }
    }

    /**
     * Consults remembered water spots (either kind — a fishing spot is still water we can
     * dip a bucket in) before giving up on a refill source. Stale entries are forgotten.
     */
    private static BlockPos recallRememberedWaterSource(ServerPlayerEntity bot, ServerWorld world, BlockPos origin) {
        if (bot == null || world == null || origin == null) {
            return null;
        }
        List<WaterSpotMemory.WaterSpot> known;
        try {
            known = BotHomeService.knownWaterSpots(bot);
        } catch (Exception e) {
            LOGGER.debug("Failed to read remembered water spots: {}", e.toString());
            return null;
        }
        if (known.isEmpty()) {
            return null;
        }
        List<WaterSpotMemory.WaterSpot> ranked = WaterSpotMemory.rank(
                known, origin.getX(), origin.getY(), origin.getZ(), currentFarmTick(world));
        double maxDistSq = (double) REMEMBERED_WATER_RADIUS * REMEMBERED_WATER_RADIUS;
        for (WaterSpotMemory.WaterSpot spot : ranked) {
            BlockPos pos = new BlockPos(spot.x(), spot.y(), spot.z());
            if (origin.getSquaredDistance(pos) > maxDistSq) {
                continue;
            }
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (isStillWater(world, pos) && isViableBucketRefillSource(world, pos)) {
                return pos.toImmutable();
            }
            BotHomeService.forgetWaterSpot(bot, spot.x(), spot.y(), spot.z());
        }
        return null;
    }

    private static List<BlockPos> findStillWaterSources(ServerWorld world, BlockPos origin, int radius) {
        class Candidate {
            BlockPos pos;
            int shore;
            double distSq;
            Candidate(BlockPos p, int s, double d) { pos = p; shore = s; distSq = d; }
        }
        List<Candidate> list = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos check = origin.add(dx, dy, dz);
                    if (isStillWater(world, check) && isViableBucketRefillSource(world, check)) {
                        int shore = shoreScore(world, check);
                        double distSq = origin.getSquaredDistance(check);
                        list.add(new Candidate(check.toImmutable(), shore, distSq));
                    }
                }
            }
        }
        list.sort((a, b) -> {
            if (a.shore != b.shore) return Integer.compare(b.shore, a.shore);
            return Double.compare(a.distSq, b.distSq);
        });
        List<BlockPos> results = new ArrayList<>();
        for (Candidate c : list) {
            results.add(c.pos);
        }
        return results;
    }

    private static BlockPos findAnyWaterSource(ServerWorld world, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        int bestShore = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos check = origin.add(dx, dy, dz);
                    if (world.getBlockState(check).isOf(Blocks.WATER) && isViableBucketRefillSource(world, check)) {
                        int shore = shoreScore(world, check);
                        double distSq = origin.getSquaredDistance(check);
                        if (shore > bestShore || (shore == bestShore && distSq < bestDistSq)) {
                            bestDistSq = distSq;
                            bestShore = shore;
                            best = check.toImmutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    private static List<BlockPos> findAnyWaterSources(ServerWorld world, BlockPos origin, int radius) {
        List<BlockPos> list = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos check = origin.add(dx, dy, dz);
                    if (world.getBlockState(check).isOf(Blocks.WATER) && isViableBucketRefillSource(world, check)) {
                        list.add(check.toImmutable());
                    }
                }
            }
        }
        list.sort((a, b) -> {
            int sa = shoreScore(world, a);
            int sb = shoreScore(world, b);
            if (sa != sb) {
                return Integer.compare(sb, sa);
            }
            return Double.compare(origin.getSquaredDistance(a), origin.getSquaredDistance(b));
        });
        return list;
    }

    private static BlockPos findStandingAroundHole(ServerWorld world, BlockPos waterPos, BlockPos center) {
        List<BlockPos> stands = enumerateStandingAroundHole(world, waterPos, center);
        return stands.isEmpty() ? null : stands.get(0);
    }

    private static List<BlockPos> enumerateStandingAroundHole(ServerWorld world, BlockPos waterPos, BlockPos center) {
        java.util.LinkedHashSet<BlockPos> stands = new java.util.LinkedHashSet<>();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos ground = waterPos.offset(dir).up(dy);
                if (isSafeStandingGroundForIrrigation(world, ground) && !isInsideHole(ground, center)) {
                    stands.add(ground.up());
                }
            }
        }
        // fallback to any safe spot nearby
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos ground = waterPos.add(dx, dy, dz);
                    if (isSafeStandingGroundForIrrigation(world, ground) && !isInsideHole(ground, center)) {
                        stands.add(ground.up());
                    }
                }
            }
        }
        return new ArrayList<>(stands);
    }

    private static boolean isInsideHole(BlockPos pos, BlockPos center) {
        return pos.getX() >= center.getX() && pos.getX() <= center.getX() + 1 &&
                pos.getZ() >= center.getZ() && pos.getZ() <= center.getZ() + 1 &&
                pos.getY() == center.getY();
    }

    private static BlockPos findStandingNearWater(ServerWorld world, BlockPos water) {
        List<BlockPos> positions = enumerateStandingNearWater(world, water);
        return positions.isEmpty() ? null : positions.get(0);
    }

    private static BlockPos offsetAwayFromWater(ServerWorld world, BlockPos center, BlockPos water, int minDistance) {
        int dx = center.getX() - water.getX();
        int dz = center.getZ() - water.getZ();
        if (dx == 0 && dz == 0) {
            dx = 1;
        }
        double len = Math.sqrt(dx * dx + dz * dz);
        double scale = (minDistance / Math.max(1.0, len));
        int shiftX = (int) Math.round(dx * scale);
        int shiftZ = (int) Math.round(dz * scale);
        BlockPos candidate = new BlockPos(center.getX() + shiftX, center.getY(), center.getZ() + shiftZ);
        // adjust Y to surface if needed
        for (int y = candidate.getY() + 2; y >= candidate.getY() - 3; y--) {
            BlockPos check = new BlockPos(candidate.getX(), y, candidate.getZ());
            if (!world.getBlockState(check).isAir()) {
                candidate = check;
                break;
            }
        }
        return candidate;
    }

    private static BlockPos offsetAwayFromNearbyFarmland(ServerWorld world, BlockPos center, int minDistance) {
        BlockPos nearestFarm = null;
        double best = Double.MAX_VALUE;
        for (int dx = -minDistance; dx <= minDistance; dx++) {
            for (int dz = -minDistance; dz <= minDistance; dz++) {
                BlockPos check = center.add(dx, 0, dz);
                if (world.getBlockState(check).isOf(Blocks.FARMLAND)) {
                    double d = center.getSquaredDistance(check);
                    if (d < best) {
                        best = d;
                        nearestFarm = check;
                    }
                }
            }
        }
        if (nearestFarm == null || Math.sqrt(best) >= minDistance) {
            return center;
        }
        int dx = center.getX() - nearestFarm.getX();
        int dz = center.getZ() - nearestFarm.getZ();
        if (dx == 0 && dz == 0) {
            dx = 1;
        }
        double len = Math.sqrt(dx * dx + dz * dz);
        double scale = (minDistance / Math.max(1.0, len));
        int shiftX = (int) Math.round(dx * scale);
        int shiftZ = (int) Math.round(dz * scale);
        BlockPos candidate = new BlockPos(center.getX() + shiftX, center.getY(), center.getZ() + shiftZ);
        return candidate;
    }
    private static List<BlockPos> enumerateStandingNearWater(ServerWorld world, BlockPos water) {
        BlockPos fallback = null;
        List<BlockPos> result = new ArrayList<>();
        // Search small ring around the water at water Y +/-1 and one block above.
        for (int radius = 1; radius <= 2; radius++) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos basePos = water.offset(dir, radius).up(dy);
                    BlockPos standPos = basePos.up();
                    BlockState base = world.getBlockState(basePos);
                    boolean headroom = isPassableStandingSpace(world, standPos) && isPassableStandingSpace(world, standPos.up());
                    if (!base.isSolidBlock(world, basePos) || !headroom) {
                        continue;
                    }
                    if (!base.isOf(Blocks.FARMLAND)) {
                        result.add(standPos);
                        continue;
                    }
                    if (fallback == null) {
                        fallback = standPos;
                    }
                }
            }
            if (fallback != null) {
                result.add(fallback);
            }
        }
        return result;
    }

    private static BlockPos findStandingSpot(ServerWorld world, BlockPos target) {
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos ground = target.offset(dir);
            if (isSafeStandingGround(world, ground)) {
                return ground;
            }
        }
        return null;
    }

    private static boolean isSafeStandingGround(ServerWorld world, BlockPos ground) {
        BlockState base = world.getBlockState(ground);
        return !base.getCollisionShape(world, ground).isEmpty()
                && base.getFluidState().isEmpty()
                && !base.isOf(Blocks.FARMLAND)
                && isPassableStandingSpace(world, ground.up());
    }

    private static boolean isSafeStandingGroundForIrrigation(ServerWorld world, BlockPos ground) {
        BlockState base = world.getBlockState(ground);
        if (base.getCollisionShape(world, ground).isEmpty()) {
            return false;
        }
        if (base.isOf(Blocks.FARMLAND) || !base.getFluidState().isEmpty()) {
            return false;
        }
        return isPassableStandingSpace(world, ground.up()) && isPassableStandingSpace(world, ground.up().up());
    }

    private static boolean isPassableStandingSpace(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.isAir() || state.isReplaceable()) {
            return true;
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static void ensureStandingOffFarmland(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos target) {
        BlockState under = world.getBlockState(bot.getBlockPos().down());
        if (!under.isOf(Blocks.FARMLAND)) {
            return;
        }
        BlockPos stand = findStandingSpot(world, target);
        if (stand != null) {
            moveTo(source, bot, stand.up());
        }
    }

    private static void ensureNotOnFarmland(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source) {
        BlockState under = world.getBlockState(bot.getBlockPos().down());
        if (under.isOf(Blocks.FARMLAND)) {
            BlockPos safe = findNearestNonFarmland(bot, world, 3);
            if (safe != null) {
                BotActions.sneak(bot, true);
                moveTo(source, bot, safe.up());
                BotActions.sneak(bot, false);
                logPos("movedOffFarmland", bot);
            }
        }
    }

    private static boolean scoopWater(ServerPlayerEntity bot, ServerWorld world, BlockPos waterPos, int bucketSlot) {
        abortIfRequested(bot);
        selectHotbarSlot(bot, bucketSlot);
        if (!bot.getMainHandStack().isOf(Items.BUCKET)) {
            LOGGER.warn("scoopWater: slot {} does not hold a bucket (holds {})", bucketSlot, bot.getMainHandStack().getItem());
            // Try to re-find a bucket in hotbar/inventory
            int retry = findHotbarItemSlot(bot.getInventory(), Items.BUCKET);
            if (retry == -1) retry = findInventoryItemSlot(bot.getInventory(), Items.BUCKET);
            if (retry != -1) {
                retry = ensureHotbarAccess(bot, retry);
                if (retry != -1) {
                    selectHotbarSlot(bot, retry);
                }
            }
        }
        if (!bot.getMainHandStack().isOf(Items.BUCKET)) {
            LOGGER.warn("scoopWater: no bucket in hand after retry (hand {})", bot.getMainHandStack().getItem());
            return false;
        }
        double dist = bot.getEyePos().distanceTo(Vec3d.ofCenter(waterPos));
        LOGGER.info("scoopWater: dist={}, botPos={}, waterPos={}", String.format("%.2f", dist), bot.getBlockPos(), waterPos);

        // If far, move closer to an adjacent stand spot or the block above the water
        if (dist > 2.5) {
            BlockPos closer = findStandingNearWater(world, waterPos);
            if (closer != null) {
                LOGGER.info("scoopWater: moving closer to {}", closer);
                moveTo(bot.getCommandSource(), bot, closer, false);
                waitUntilClose(bot, waterPos, 2.5, 24, false);
            } else {
                LOGGER.info("scoopWater: no stand found, walking to water block {}", waterPos.up());
                moveTo(bot.getCommandSource(), bot, waterPos.up(), false);
                waitUntilClose(bot, waterPos, 2.2, 24, false);
            }
        }

        if (!isWithinReach(bot, waterPos)) {
            LOGGER.warn("scoopWater: water {} still out of reach after move (eyePos={}, dist={})", waterPos, bot.getEyePos(), bot.getEyePos().distanceTo(Vec3d.ofCenter(waterPos)));
        }

        LookController.faceBlock(bot, waterPos);
        sleep(150);

        // Execute scooping on the server thread for proper synchronization
        return scoopWaterOnServerThread(bot, world, waterPos, bucketSlot);
    }

    /**
     * Performs the actual water scooping on the server thread for proper synchronization.
     * Uses multiple strategies: interactBlock, interactItem, and fallback step-into approach.
     */
    private static boolean scoopWaterOnServerThread(ServerPlayerEntity bot, ServerWorld world, BlockPos waterPos, int bucketSlot) {
        var server = bot.getCommandSource().getServer();
        if (server == null) {
            LOGGER.warn("scoopWaterOnServerThread: no server available");
            return false;
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                if (!isMutationAuthorized(bot, world, waterPos)) {
                    future.complete(false);
                    return;
                }
                // Ensure bucket is selected on server thread
                selectHotbarSlot(bot, bucketSlot);
                if (!bot.getMainHandStack().isOf(Items.BUCKET)) {
                    LOGGER.warn("scoopWaterOnServerThread: expected BUCKET but have {}", bot.getMainHandStack().getItem());
                    future.complete(false);
                    return;
                }

                // Verify the water block is actually water before attempting
                BlockState waterState = world.getBlockState(waterPos);
                if (!waterState.isOf(Blocks.WATER)) {
                    LOGGER.warn("scoopWater: target {} is not water (is {})", waterPos, waterState.getBlock());
                    future.complete(false);
                    return;
                }

                // Strategy 1: interactItem - bucket's use() is triggered by player looking at water
                // This relies on the raycast from the bot's eye position
                ActionResult result = bot.interactionManager.interactItem(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND);
                LOGGER.info("scoopWater interactItem result={} handItem={}", result, bot.getMainHandStack().getItem());
                if (result.isAccepted() && bot.getMainHandStack().isOf(Items.WATER_BUCKET)) {
                    bot.swingHand(Hand.MAIN_HAND, true);
                    LOGGER.info("scoopWater success via interactItem at {}", waterPos);
                    future.complete(true);
                    return;
                }

                // Strategy 2: interactBlock with explicit hit targeting the water block (top face)
                Vec3d hitVec = Vec3d.ofCenter(waterPos).add(0, 0.4, 0);
                BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, waterPos, true);
                result = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
                LOGGER.info("scoopWater interactBlock result={} handItem={}", result, bot.getMainHandStack().getItem());
                if (result.isAccepted() && bot.getMainHandStack().isOf(Items.WATER_BUCKET)) {
                    bot.swingHand(Hand.MAIN_HAND, true);
                    LOGGER.info("scoopWater success via interactBlock at {}", waterPos);
                    future.complete(true);
                    return;
                }

                // Strategy 3: Try targeting different faces of the water block
                for (Direction face : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    Vec3d faceHit = Vec3d.ofCenter(waterPos).add(
                        face.getOffsetX() * 0.4,
                        face.getOffsetY() * 0.4,
                        face.getOffsetZ() * 0.4
                    );
                    BlockHitResult faceBlockHit = new BlockHitResult(faceHit, face, waterPos, true);
                    result = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, faceBlockHit);
                    if (result.isAccepted() && bot.getMainHandStack().isOf(Items.WATER_BUCKET)) {
                        bot.swingHand(Hand.MAIN_HAND, true);
                        LOGGER.info("scoopWater success via interactBlock (face={}) at {}", face, waterPos);
                        future.complete(true);
                        return;
                    }
                }

                LOGGER.warn("scoopWater primary strategies failed at {} (lastResult: {}, hand: {})", waterPos, result, bot.getMainHandStack().getItem());
                future.complete(false);
            } catch (Exception e) {
                LOGGER.error("scoopWaterOnServerThread exception: {}", e.getMessage());
                future.complete(false);
            }
        });

        try {
            boolean result = future.get(5, TimeUnit.SECONDS);
            if (result) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("scoopWater interrupted");
            return false;
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("scoopWater server thread execution failed: {}", e.getMessage());
            return false;
        }

        // Fallback: step into the water block and try again.
        // Only do this if we can also find nearby solid ground to get back out (avoid ocean stranding).
        if (findSolidGroundNearby(world, waterPos, 12) == null) {
            LOGGER.warn("scoopWater fallback suppressed (no nearby land exit) at {}", waterPos);
            return false;
        }

        Vec3d enterPos = Vec3d.ofCenter(waterPos).add(0, 0.2, 0);
        BlockPos stand = findStandingNearWater(world, waterPos);
        if (stand != null) {
            moveTo(bot.getCommandSource(), bot, stand, false);
        }
        BotActions.moveToward(bot, enterPos, 0.4);
        waitUntilClose(bot, waterPos, 1.5, 10, false);
        LookController.faceBlock(bot, waterPos);
        sleep(120);

        // Re-execute on server thread from inside water
        CompletableFuture<Boolean> fallbackFuture = new CompletableFuture<>();
        server.execute(() -> {
            try {
                if (!isMutationAuthorized(bot, world, waterPos)) {
                    fallbackFuture.complete(false);
                    return;
                }
                selectHotbarSlot(bot, bucketSlot);
                if (!bot.getMainHandStack().isOf(Items.BUCKET)) {
                    fallbackFuture.complete(false);
                    return;
                }

                // From inside water, try interactItem first
                ActionResult inside = bot.interactionManager.interactItem(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND);
                LOGGER.info("scoopWater fallback interactItem result={} hand={}", inside, bot.getMainHandStack().getItem());
                if (inside.isAccepted() && bot.getMainHandStack().isOf(Items.WATER_BUCKET)) {
                    bot.swingHand(Hand.MAIN_HAND, true);
                    fallbackFuture.complete(true);
                    return;
                }

                // Try interactBlock with different faces
                for (Direction face : Direction.values()) {
                    Vec3d faceHit = Vec3d.ofCenter(waterPos).add(
                        face.getOffsetX() * 0.3,
                        face.getOffsetY() * 0.3,
                        face.getOffsetZ() * 0.3
                    );
                    BlockHitResult insideHit = new BlockHitResult(faceHit, face, waterPos, true);
                    inside = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, insideHit);
                    LOGGER.info("scoopWater fallback interactBlock (face={}) result={} hand={}", face, inside, bot.getMainHandStack().getItem());
                    if (inside.isAccepted() && bot.getMainHandStack().isOf(Items.WATER_BUCKET)) {
                        bot.swingHand(Hand.MAIN_HAND, true);
                        fallbackFuture.complete(true);
                        return;
                    }
                }

                fallbackFuture.complete(false);
            } catch (Exception e) {
                LOGGER.error("scoopWater fallback exception: {}", e.getMessage());
                fallbackFuture.complete(false);
            }
        });

        try {
            boolean fallbackResult = fallbackFuture.get(5, TimeUnit.SECONDS);
            if (fallbackResult) {
                // Move out of water
                BlockPos exit = findStandingNearWater(world, waterPos);
                if (exit != null) {
                    moveTo(bot.getCommandSource(), bot, exit, false);
                }
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("scoopWater fallback execution failed: {}", e.getMessage());
        }

        return false;
    }

    private static void mineBlock(ServerPlayerEntity bot, BlockPos pos, ServerWorld world) {
        if (!isWithinReach(bot, pos)) {
            return;
        }
        Block block = world.getBlockState(pos).getBlock();
        BlockState state = world.getBlockState(pos);
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.AXE_MINEABLE)) {
            BotActions.selectBestTool(bot, "axe", "pickaxe");
        } else if (state.isIn(BlockTags.LEAVES) || state.isReplaceable() || state.isOf(Blocks.SNOW)) {
            BotActions.selectBestTool(bot, "axe", "shovel");
        } else if (SHOVEL_DIG_BLOCKS.contains(block)) {
            BotActions.selectBestTool(bot, "shovel", "pickaxe");
        } else if (block.getHardness() >= 1.5f) {
            BotActions.selectBestTool(bot, "pickaxe", "shovel");
        }
        LookController.faceBlock(bot, pos);
        sleep(ACTION_DELAY_MS);

        CompletableFuture<String> future = MiningTool.mineBlock(bot, pos);
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkillAbortException();
        } catch (Exception e) {
            LOGGER.debug("Mining {} failed: {}", pos, e.getMessage());
        }
    }

    private static boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        Vec3d eye = bot.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        return eye.distanceTo(target) <= MAX_INTERACTION_RANGE;
    }

    private static boolean isMutationAuthorized(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (bot == null || world == null || pos == null) {
            return false;
        }
        var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, pos);
        return auth.allowed();
    }

    private static boolean fillWithDirt(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        // Allow filling into air, replaceables, and water (for sealing / repairing leaks).
        if (!state.isAir() && !state.isReplaceable() && !state.isOf(Blocks.WATER)) {
            return true;
        }
        return BotActions.placeBlockAt(bot, pos, Direction.UP, DIRT_BLOCK_PREFERENCE);
    }

    private static int countAvailableFillBlocks(PlayerInventory inventory) {
        int available = 0;
        for (Item item : DIRT_BLOCK_PREFERENCE) {
            available += countItem(inventory, item);
        }
        return available;
    }

    private static boolean ensureFarmFillMaterials(ServerPlayerEntity bot, ServerCommandSource source, int requiredBlocks) {
        if (bot == null || source == null || requiredBlocks <= 0) {
            return true;
        }
        int available = countAvailableFillBlocks(bot.getInventory());
        if (available >= requiredBlocks) {
            return true;
        }

        int needed = Math.max(4, requiredBlocks - available);
        LOGGER.info("Farm terraform collecting fill blocks need={} available={}", requiredBlocks, available);
        ChatUtils.sendSystemMessage(source, "Collecting dirt for farm terraforming (" + needed + ").");
        Map<String, Object> params = new HashMap<>();
        params.put("count", needed);
        params.put("searchRadius", 8);
        params.put("verticalRange", 4);
        params.put("allowChestStore", false);
        SkillExecutionResult result = new CollectDirtSkill().execute(new SkillContext(source, new HashMap<>(), params));
        if (!result.success()) {
            LOGGER.warn("Farm terraform dirt collection failed: {}", result.message());
        }
        return countAvailableFillBlocks(bot.getInventory()) >= requiredBlocks;
    }

    private static boolean prepareFarmWorkPad(ServerPlayerEntity bot,
                                              ServerWorld world,
                                              ServerCommandSource source,
                                              FarmFootprint footprint,
                                              FarmTerrainPrepPlan prepPlan,
                                              int targetY,
                                              Set<BlockPos> temporaryFarmAccess) {
        if (bot == null || world == null || source == null || footprint == null || prepPlan == null || prepPlan.stagingGround() == null) {
            return false;
        }
        for (BlockPos pathPos : prepPlan.accessPath()) {
            if (!prepareFarmStandingCell(bot, world, source, footprint, pathPos, targetY, temporaryFarmAccess)) {
                return false;
            }
        }
        if (!prepareFarmStandingCell(bot, world, source, footprint, prepPlan.stagingGround(), targetY, temporaryFarmAccess)) {
            return false;
        }
        moveTo(source, bot, prepPlan.stagingGround().up(), false);
        waitUntilClose(bot, prepPlan.stagingGround(), 2.5, 24, false);
        return bot.getEyePos().distanceTo(Vec3d.ofCenter(prepPlan.stagingGround())) <= 3.0D;
    }

    private static void terraformFarmFootprint(ServerPlayerEntity bot,
                                               ServerWorld world,
                                               ServerCommandSource source,
                                               SkillContext ctx,
                                               FarmFootprint footprint,
                                               int targetY,
                                               BlockPos stagingGround,
                                               Set<BlockPos> temporaryFarmAccess,
                                               int maxCutDepth) {
        if (bot == null || world == null || source == null || footprint == null) {
            return;
        }
        List<int[]> columns = new ArrayList<>();
        BlockPos orderingCenter = stagingGround != null ? stagingGround : footprint.centerTarget();
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                if (footprint.isIrrigationColumn(x, z)) {
                    continue;
                }
                columns.add(new int[]{x, z});
            }
        }
        columns.sort((a, b) -> Integer.compare(
            (a[0] - orderingCenter.getX()) * (a[0] - orderingCenter.getX()) + (a[1] - orderingCenter.getZ()) * (a[1] - orderingCenter.getZ()),
            (b[0] - orderingCenter.getX()) * (b[0] - orderingCenter.getX()) + (b[1] - orderingCenter.getZ()) * (b[1] - orderingCenter.getZ())
        ));

        for (int[] column : columns) {
            abortIfRequested(bot);
            BlockPos targetPos = new BlockPos(column[0], targetY, column[1]);
            Integer surfaceY = findSurfaceY(world, targetPos);
            if (surfaceY != null) {
                BlockPos topCheck = new BlockPos(targetPos.getX(), surfaceY, targetPos.getZ());
                BlockState topState = world.getBlockState(topCheck);
                if ((topState.isIn(BlockTags.LOGS) || topState.isIn(BlockTags.LEAVES)) && ctx != null) {
                    runWoodcutInline(source, new SkillContext(source, ctx.sharedState()), footprint);
                    surfaceY = findSurfaceY(world, targetPos);
                }
            }
            if (surfaceY == null) {
                continue;
            }
            int diff = surfaceY - targetY;
            if (diff > maxCutDepth || diff < -MAX_FILL_DEPTH) {
                LOGGER.debug("terraformFarmFootprint: skipping unrecoverable column at {} diff={}", targetPos, diff);
                continue;
            }

            BlockPos stand = findOrCreateFarmWorkingStand(bot, world, source, footprint, targetPos, targetY, temporaryFarmAccess);
            if (stand != null) {
                moveTo(source, bot, stand.up(), false);
                waitUntilClose(bot, targetPos, 4.6, 16, false);
            }

            if (surfaceY > targetY) {
                for (int y = surfaceY; y > targetY; y--) {
                    BlockPos cutPos = new BlockPos(targetPos.getX(), y, targetPos.getZ());
                    if (isWithinReach(bot, cutPos) && !world.getBlockState(cutPos).isOf(Blocks.WATER)) {
                        mineBlock(bot, cutPos, world);
                    }
                }
            }

            clearStandingHeadroom(bot, world, targetPos, targetY);
            Integer supportY = findSolidSupportWithinDepth(world, targetPos, targetY, 3);
            if (supportY != null && supportY < targetY) {
                for (int y = supportY + 1; y <= targetY; y++) {
                    BlockPos fillPos = new BlockPos(targetPos.getX(), y, targetPos.getZ());
                    if (isWithinReach(bot, fillPos)) {
                        fillWithDirt(bot, world, fillPos);
                    }
                }
            }
        }
    }

    private static BlockPos findOrCreateFarmWorkingStand(ServerPlayerEntity bot,
                                                         ServerWorld world,
                                                         ServerCommandSource source,
                                                         FarmFootprint footprint,
                                                         BlockPos target,
                                                         int targetY,
                                                         Set<BlockPos> temporaryFarmAccess) {
        if (bot == null || world == null || source == null || footprint == null || target == null) {
            return null;
        }
        BlockPos currentGround = bot.getBlockPos().down();
        if (isUsableFarmStandingGround(world, footprint, currentGround)
                && bot.getEyePos().distanceTo(Vec3d.ofCenter(target)) <= 4.6D) {
            return currentGround;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 1; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) != radius) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos(target.getX() + dx, targetY, target.getZ() + dz);
                    if (!isWithinFarmWorkBounds(footprint, candidate, 2)) {
                        continue;
                    }
                    candidates.add(candidate);
                }
            }
        }
        candidates.sort((a, b) -> Integer.compare(
            squaredHorizontalDistance(a, bot.getBlockPos()),
            squaredHorizontalDistance(b, bot.getBlockPos())
        ));

        for (BlockPos candidate : candidates) {
            if (isUsableFarmStandingGround(world, footprint, candidate)) {
                return candidate;
            }
            if (prepareFarmStandingCell(bot, world, source, footprint, candidate, targetY, temporaryFarmAccess)
                    && isUsableFarmStandingGround(world, footprint, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean prepareFarmStandingCell(ServerPlayerEntity bot,
                                                   ServerWorld world,
                                                   ServerCommandSource source,
                                                   FarmFootprint footprint,
                                                   BlockPos ground,
                                                   int targetY,
                                                   Set<BlockPos> temporaryFarmAccess) {
        if (bot == null || world == null || source == null || footprint == null || ground == null) {
            return false;
        }

        BlockPos approach = findStandingSpot(world, ground);
        if (approach != null) {
            moveTo(source, bot, approach.up(), false);
            waitUntilClose(bot, ground, 4.6, 16, false);
        } else if (bot.getEyePos().distanceTo(Vec3d.ofCenter(ground)) > 4.6D) {
            moveTo(source, bot, ground.up(), false);
            waitUntilClose(bot, ground, 4.6, 20, false);
        }

        Integer supportY = findSolidSupportWithinDepth(world, ground, targetY, 3);
        if (supportY == null) {
            return false;
        }
        clearStandingHeadroom(bot, world, ground, targetY);
        for (int y = supportY + 1; y <= targetY; y++) {
            BlockPos fillPos = new BlockPos(ground.getX(), y, ground.getZ());
            if (!isWithinReach(bot, fillPos)) {
                continue;
            }
            if (fillWithDirt(bot, world, fillPos) && !isInsideFarmFootprint(footprint, fillPos)) {
                temporaryFarmAccess.add(fillPos.toImmutable());
            }
        }
        return isUsableFarmStandingGround(world, footprint, ground);
    }

    private static void clearStandingHeadroom(ServerPlayerEntity bot, ServerWorld world, BlockPos ground, int targetY) {
        for (int y = targetY + 1; y <= targetY + 2; y++) {
            BlockPos clearPos = new BlockPos(ground.getX(), y, ground.getZ());
            if (isWithinReach(bot, clearPos) && !isPassableStandingSpace(world, clearPos) && canClearForStanding(world, clearPos)) {
                mineBlock(bot, clearPos, world);
            }
        }
    }

    private static boolean isUsableFarmStandingGround(ServerWorld world, FarmFootprint footprint, BlockPos ground) {
        return ground != null
            && !footprint.isIrrigationPos(ground)
            && isSafeStandingGround(world, ground)
            && !hasSimplePrecipice(world, ground);
    }

    private static boolean isWithinFarmWorkBounds(FarmFootprint footprint, BlockPos pos, int spillover) {
        if (footprint == null || pos == null) {
            return false;
        }
        return pos.getX() >= footprint.minX() - spillover
            && pos.getX() <= footprint.maxX() + spillover
            && pos.getZ() >= footprint.minZ() - spillover
            && pos.getZ() <= footprint.maxZ() + spillover;
    }

    private static boolean isInsideFarmFootprint(FarmFootprint footprint, BlockPos pos) {
        if (footprint == null || pos == null) {
            return false;
        }
        return pos.getX() >= footprint.minX()
            && pos.getX() <= footprint.maxX()
            && pos.getZ() >= footprint.minZ()
            && pos.getZ() <= footprint.maxZ();
    }

    private static int squaredHorizontalDistance(BlockPos a, BlockPos b) {
        if (a == null || b == null) {
            return Integer.MAX_VALUE;
        }
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static void cleanupTemporaryFarmAccess(ServerPlayerEntity bot,
                                                   ServerWorld world,
                                                   ServerCommandSource source,
                                                   FarmFootprint footprint,
                                                   Set<BlockPos> temporaryFarmAccess) {
        if (bot == null || world == null || source == null || footprint == null || temporaryFarmAccess == null || temporaryFarmAccess.isEmpty()) {
            return;
        }

        BlockPos retreat = findStandingSpot(world, footprint.centerTarget());
        if (retreat != null) {
            moveTo(source, bot, retreat.up(), false);
            waitUntilClose(bot, retreat, 3.0, 16, false);
        }

        for (BlockPos pos : new ArrayList<>(temporaryFarmAccess)) {
            if (isInsideFarmFootprint(footprint, pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (!state.isOf(Blocks.DIRT) && !state.isOf(Blocks.GRASS_BLOCK) && !state.isOf(Blocks.COARSE_DIRT) && !state.isOf(Blocks.ROOTED_DIRT)) {
                continue;
            }
            if (!world.getBlockState(pos.up()).isAir() && !world.getBlockState(pos.up()).isReplaceable()) {
                continue;
            }
            if (bot.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > 4.6D) {
                moveTo(source, bot, pos.up(), false);
                waitUntilClose(bot, pos, 4.6, 16, false);
            }
            if (isWithinReach(bot, pos)) {
                mineBlock(bot, pos, world);
            }
        }
    }

    private static void finalTopOffBuckets(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos center, BlockPos refillSource) {
        int waterBuckets = countItem(bot.getInventory(), Items.WATER_BUCKET);
        int emptyBuckets = countItem(bot.getInventory(), Items.BUCKET);
        if (emptyBuckets == 0) {
            return; // nothing to top off
        }
        List<BlockPos> sources = new ArrayList<>();
        if (refillSource != null) {
            sources.add(refillSource);
        }
        for (BlockPos s : findStillWaterSources(world, center, WATER_SEARCH_RADIUS)) {
            if (!sources.contains(s)) sources.add(s);
        }
        for (BlockPos s : findAnyWaterSources(world, center, WATER_SEARCH_RADIUS)) {
            if (!sources.contains(s)) sources.add(s);
        }
        for (BlockPos src : sources) {
            LOGGER.info("finalTopOffBuckets using source {}", src);
            fillBucketsAt(bot, world, source, src);
            waterBuckets = countItem(bot.getInventory(), Items.WATER_BUCKET);
            emptyBuckets = countItem(bot.getInventory(), Items.BUCKET);
            if (emptyBuckets == 0) break;
        }
        if (emptyBuckets > 0) {
            LOGGER.warn("finalTopOffBuckets: could not fill all buckets (left empty={})", emptyBuckets);
        }
    }

    private static void repairDamagedPlots(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos center, Set<BlockPos> protectedStillWater) {
        List<BlockPos> plots = farmPlots(center);
        int dirtAvailable = 0;
        for (Item item : DIRT_BLOCK_PREFERENCE) {
            dirtAvailable += countItem(bot.getInventory(), item);
        }
        boolean announcedDirtShortage = false;

        BotActions.sneak(bot, true);
        int retilled = 0;
        int replanted = 0;

        for (BlockPos plot : plots) {
            if (protectedStillWater != null && protectedStillWater.contains(plot)) {
                continue;
            }
            // Ensure plot top is at farm level; fill or trim to center Y
            if (plot.getY() != center.getY()) {
                adjustPlotHeight(bot, world, plot, center.getY());
            }

            BlockState state = world.getBlockState(plot);

            // Fill holes or *flowing* spill water with dirt if available (preserve still sources).
            boolean isFlowingWater = state.isOf(Blocks.WATER) && !state.getFluidState().isStill();
            if ((state.isAir() || isFlowingWater) && dirtAvailable > 0) {
                int filled = 0;
                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos fillPos = plot.down(dy);
                    if (protectedStillWater != null && protectedStillWater.contains(fillPos)) {
                        continue;
                    }
                    BlockState fillState = world.getBlockState(fillPos);
                    boolean fillIsFlowingWater = fillState.isOf(Blocks.WATER) && !fillState.getFluidState().isStill();
                    if (fillState.isAir() || fillIsFlowingWater) {
                        if (fillWithDirt(bot, world, fillPos)) {
                            filled++;
                        }
                    } else {
                        break;
                    }
                }
                if (filled > 0) {
                    dirtAvailable = 0;
                    for (Item item : DIRT_BLOCK_PREFERENCE) {
                        dirtAvailable += countItem(bot.getInventory(), item);
                    }
                    state = world.getBlockState(plot);
                }
            } else if ((state.isAir() || isFlowingWater) && dirtAvailable == 0 && !announcedDirtShortage) {
                ChatUtils.sendSystemMessage(source, "I need dirt to fill damaged plots but none is available.");
                announcedDirtShortage = true;
            }

            // Re-till if not farmland
            if (!state.isOf(Blocks.FARMLAND) && TILLABLE_SURFACES.contains(state.getBlock())) {
                BlockPos stand = findStandingSpot(world, plot);
                if (stand != null) {
                    moveTo(source, bot, stand.up());
                } else {
                    moveTo(source, bot, plot);
                }
                LookController.faceBlock(bot, plot);
                sleep(ACTION_DELAY_MS);
                if (BotActions.useHoe(bot, plot)) {
                    retilled++;
                    sleep(80);
                }
            }

            // Replant if farmland is empty and seeds remain
            if (countSeeds(bot.getInventory()) <= 0) {
                continue;
            }
            if (world.getBlockState(plot).isOf(Blocks.FARMLAND) && world.isAir(plot.up())) {
                int seedSlot = ensureAnySeedHotbar(bot);
                if (seedSlot < 0) {
                    break;
                }
                selectHotbarSlot(bot, seedSlot);
                LookController.faceBlock(bot, plot);
                sleep(ACTION_DELAY_MS);
                BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(plot).add(0, 0.5, 0), Direction.UP, plot, false);
                if (!isMutationAuthorized(bot, world, plot.up())) {
                    continue;
                }
                ActionResult result = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
                if (result.isAccepted()) {
                    bot.swingHand(Hand.MAIN_HAND, true);
                    replanted++;
                    sleep(60);
                }
            }
        }
        BotActions.sneak(bot, false);

        LOGGER.info("Repair pass complete: retilled={}, replanted={}, dirtLeft={}", retilled, replanted, dirtAvailable);
    }

    private static void adjustPlotHeight(ServerPlayerEntity bot, ServerWorld world, BlockPos plot, int targetY) {
        int y = plot.getY();
        if (y == targetY) {
            return;
        }
        if (y < targetY) {
            for (int dy = y + 1; dy <= targetY; dy++) {
                BlockPos fillPos = new BlockPos(plot.getX(), dy, plot.getZ());
                fillWithDirt(bot, world, fillPos);
            }
        } else {
            for (int dy = y; dy > targetY; dy--) {
                BlockPos digPos = new BlockPos(plot.getX(), dy, plot.getZ());
                BlockState state = world.getBlockState(digPos);
                if (!state.isOf(Blocks.WATER)) {
                    mineBlock(bot, digPos, world);
                }
            }
        }
    }

    private static int countSeeds(PlayerInventory inventory) {
        int count = 0;
        for (Item seed : SIMPLE_SEEDS) {
            count += countItem(inventory, seed);
        }
        return count;
    }

    private static int ensureAnySeedHotbar(ServerPlayerEntity bot) {
        PlayerInventory inv = bot.getInventory();
        for (Item seed : SIMPLE_SEEDS) {
            int slot = findHotbarItemSlot(inv, seed);
            if (slot != -1) return slot;
        }
        for (Item seed : SIMPLE_SEEDS) {
            int invSlot = findInventoryItemSlot(inv, seed);
            if (invSlot != -1) {
                return ensureHotbarAccess(bot, invSlot);
            }
        }
        return -1;
    }

    private static Integer computeFarmTargetY(ServerWorld world, BlockPos center, Set<BlockPos> protectedStillWater) {
        return computeFarmTargetY(world, FarmFootprint.fromIrrigationAnchor(center), protectedStillWater);
    }

    private static Integer computeFarmTargetY(ServerWorld world, FarmFootprint footprint, Set<BlockPos> protectedStillWater) {
        List<Integer> heights = new ArrayList<>();
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                BlockPos base = new BlockPos(x, footprint.irrigationAnchor().getY(), z);
                if (protectedStillWater != null && protectedStillWater.contains(base)) {
                    continue;
                }
                Integer y = findSurfaceY(world, base);
                if (y != null) {
                    heights.add(y);
                }
            }
        }
        if (heights.isEmpty()) {
            return null;
        }
        heights.sort(Integer::compareTo);
        int targetY = heights.get(heights.size() / 2);
        LOGGER.info("[FarmIrrigation] levelGround targetY={} samples={} protectedWater={}",
                targetY, heights.size(), protectedStillWater == null ? 0 : protectedStillWater.size());
        return targetY;
    }

    private static boolean isLikelyIrrigationCenter(ServerWorld world, BlockPos center) {
        BlockPos[] hole = {center, center.add(1, 0, 0), center.add(0, 0, 1), center.add(1, 0, 1)};
        int still = 0;
        for (BlockPos pos : hole) {
            if (isStillWater(world, pos)) {
                still++;
            }
        }
        return still >= 2;
    }

    private static void levelGround(ServerPlayerEntity bot,
                                    ServerWorld world,
                                    ServerCommandSource source,
                                    BlockPos center,
                                    int targetY,
                                    SkillContext ctx,
                                    Set<BlockPos> protectedStillWater) {
        levelGround(bot, world, source, FarmFootprint.fromIrrigationAnchor(center), targetY, ctx, protectedStillWater, AUTO_MAX_CUT_DEPTH, null, new HashSet<>());
    }

    private static void levelGround(ServerPlayerEntity bot,
                                    ServerWorld world,
                                    ServerCommandSource source,
                                    FarmFootprint footprint,
                                    int targetY,
                                    SkillContext ctx,
                                    Set<BlockPos> protectedStillWater,
                                    int maxCutDepth,
                                    BlockPos stagingGround,
                                    Set<BlockPos> temporaryFarmAccess) {
        abortIfRequested(bot);
        List<int[]> columns = new ArrayList<>();
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                columns.add(new int[]{x, z});
            }
        }
        BlockPos centerTarget = stagingGround != null ? stagingGround : footprint.centerTarget();
        columns.sort((a, b) -> Integer.compare(
            (a[0] - centerTarget.getX()) * (a[0] - centerTarget.getX()) + (a[1] - centerTarget.getZ()) * (a[1] - centerTarget.getZ()),
            (b[0] - centerTarget.getX()) * (b[0] - centerTarget.getX()) + (b[1] - centerTarget.getZ()) * (b[1] - centerTarget.getZ())
        ));

        for (int[] col : columns) {
            abortIfRequested(bot);
            BlockPos columnBase = new BlockPos(col[0], footprint.irrigationAnchor().getY(), col[1]);

            if (protectedStillWater != null && protectedStillWater.contains(columnBase)) {
                continue;
            }
            
            // Re-sample surface Y each iteration since previous operations may have changed terrain
            Integer surfaceY = findSurfaceY(world, columnBase);
            if (surfaceY == null) continue;
            int diff = surfaceY - targetY;
            
            // Skip steep columns to avoid carving into hillsides / creating ugly cliffs.
            if (diff > maxCutDepth || diff < -MAX_FILL_DEPTH) {
                LOGGER.debug("levelGround: skipping steep column at {} (diff={})", columnBase, diff);
                continue;
            }

            BlockPos topCheck = new BlockPos(columnBase.getX(), surfaceY, columnBase.getZ());
            BlockState topCheckState = world.getBlockState(topCheck);
            if (topCheckState.isIn(BlockTags.LOGS) || topCheckState.isIn(BlockTags.LEAVES)) {
                LOGGER.info("levelGround encountered tree block at {}, invoking woodcut", topCheck);
                runWoodcutInline(source, new SkillContext(source, ctx.sharedState()), footprint);
                // Re-sample after woodcut
                surfaceY = findSurfaceY(world, columnBase);
                if (surfaceY == null) continue;
                diff = surfaceY - targetY;
                if (diff > maxCutDepth || diff < -MAX_FILL_DEPTH) {
                    LOGGER.debug("levelGround: column still exceeds grading budget at {} (diff={})", columnBase, diff);
                    continue;
                }
            }

            BlockPos top = new BlockPos(columnBase.getX(), targetY, columnBase.getZ());
            if (protectedStillWater != null && protectedStillWater.contains(top)) {
                continue;
            }
            BlockState topState = world.getBlockState(top);
            boolean needsTopFix = !TILLABLE_SURFACES.contains(topState.getBlock())
                    && !topState.isOf(Blocks.FARMLAND)
                    && !topState.isOf(Blocks.WATER);

            // Snow-covered but otherwise level columns should be skipped quickly.
            if (diff == 0 && !needsTopFix) {
                continue;
            }

            double distToColumn = bot.getEyePos().distanceTo(Vec3d.ofCenter(columnBase));
            if (distToColumn > 4.6D) {
                BlockPos stand = findOrCreateFarmWorkingStand(bot, world, source, footprint, columnBase, targetY,
                    temporaryFarmAccess == null ? new HashSet<>() : temporaryFarmAccess);
                if (stand == null) {
                    LOGGER.debug("levelGround: no safe stand near {}, skipping work for this column", columnBase);
                    continue;
                }
                // Leveling iterates many columns; only reposition when needed and skip stuck ticks here.
                moveTo(source, bot, stand.up(), false);
                waitUntilClose(bot, columnBase, 4.6, 16, false);
            }

            // Mine down excess layers
            if (diff > 0) {
                for (int y = surfaceY; y > targetY; y--) {
                    BlockPos pos = new BlockPos(columnBase.getX(), y, columnBase.getZ());
                    if (protectedStillWater != null && protectedStillWater.contains(pos)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(pos);
                    if (state.isOf(Blocks.WATER)) continue;
                    mineBlock(bot, pos, world);
                }
            } 
            // Fill up missing layers
            else if (diff < 0) {
                for (int y = surfaceY + 1; y <= targetY; y++) {
                    BlockPos pos = new BlockPos(columnBase.getX(), y, columnBase.getZ());
                    if (protectedStillWater != null && protectedStillWater.contains(pos)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(pos);
                    if (state.isOf(Blocks.WATER) && state.getFluidState().isStill()) {
                        continue; // integrate still water
                    }
                    fillWithDirt(bot, world, pos);
                }
            }

            // Ensure top surface is tillable
            if (!TILLABLE_SURFACES.contains(topState.getBlock()) && !topState.isOf(Blocks.FARMLAND) && !topState.isOf(Blocks.WATER)) {
                if (!topState.isAir() && !topState.isOf(Blocks.WATER)) {
                    mineBlock(bot, top, world);
                }
                fillWithDirt(bot, world, top);
            }
        }
        
        // Third pass: verify and fix any remaining gaps
        LOGGER.info("levelGround: verification pass");
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            abortIfRequested(bot);
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                BlockPos columnBase = new BlockPos(x, footprint.irrigationAnchor().getY(), z);
                BlockPos targetPos = new BlockPos(columnBase.getX(), targetY, columnBase.getZ());
                if (protectedStillWater != null && protectedStillWater.contains(targetPos)) {
                    continue;
                }
                BlockState state = world.getBlockState(targetPos);
                
                // If target level is air or replaceable, fill it
                if (state.isAir() || state.isReplaceable()) {
                    fillWithDirt(bot, world, targetPos);
                }
                // If there's a block above target level that shouldn't be there
                BlockPos above = targetPos.up();
                BlockState aboveState = world.getBlockState(above);
                if (!aboveState.isAir() && !aboveState.isOf(Blocks.WATER) && !aboveState.isReplaceable()
                        && !aboveState.isIn(BlockTags.LOGS) && !aboveState.isIn(BlockTags.LEAVES)) {
                    // Only mine if it's not essential structure
                    if (SHOVEL_DIG_BLOCKS.contains(aboveState.getBlock()) || aboveState.isOf(Blocks.STONE) || aboveState.isOf(Blocks.COBBLESTONE)) {
                        mineBlock(bot, above, world);
                    }
                }
            }
        }
        // Fourth pass: deep-fill any small pits up to 3 blocks deep relative to targetY
        LOGGER.info("levelGround: deep-fill pass (up to 3 blocks)");
        int maxDeepFill = 3;
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            abortIfRequested(bot);
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                BlockPos columnBase = new BlockPos(x, footprint.irrigationAnchor().getY(), z);
                if (protectedStillWater != null && protectedStillWater.contains(columnBase)) continue;
                for (int d = 0; d < maxDeepFill; d++) {
                    int y = targetY - d;
                    if (y <= world.getBottomY()) break;
                    BlockPos fillPos = new BlockPos(columnBase.getX(), y, columnBase.getZ());
                    if (protectedStillWater != null && protectedStillWater.contains(fillPos)) break;
                    BlockState s = world.getBlockState(fillPos);
                    boolean isFlowing = s.isOf(Blocks.WATER) && !s.getFluidState().isStill();
                    if (s.isAir() || s.isReplaceable() || isFlowing) {
                        fillWithDirt(bot, world, fillPos);
                    } else {
                        break; // encountered solid, stop deeper fill on this column
                    }
                }
            }
        }
    }

    private static Integer findSurfaceY(ServerWorld world, BlockPos base) {
        for (int dy = 4; dy >= -4; dy--) {
            BlockPos pos = base.up(dy);
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Blocks.WATER)) {
                continue;
            }
            if (!state.isAir() && !state.isReplaceable()) {
                return pos.getY();
            }
        }
        return null;
    }

    private static boolean pickupWater(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (!isMutationAuthorized(bot, world, pos)) {
            return false;
        }
        int bucketSlot = findHotbarItemSlot(bot.getInventory(), Items.BUCKET);
        if (bucketSlot == -1) bucketSlot = findInventoryItemSlot(bot.getInventory(), Items.BUCKET);
        if (bucketSlot == -1) {
            LOGGER.warn("pickupWater: no empty bucket to remove water at {}", pos);
            return false;
        }
        bucketSlot = ensureHotbarAccess(bot, bucketSlot);
        if (bucketSlot == -1) {
            LOGGER.warn("pickupWater: hotbar locked, could not access bucket for {}", pos);
            return false;
        }
        selectHotbarSlot(bot, bucketSlot);
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        ActionResult result = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
        LOGGER.info("pickupWater at {} result={} hand={}", pos, result, bot.getMainHandStack().getItem());
        if (result.isAccepted() && bot.getMainHandStack().isOf(Items.WATER_BUCKET)) {
            bot.swingHand(Hand.MAIN_HAND, true);
            return true;
        }
        result = bot.interactionManager.interactItem(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND);
        LOGGER.info("pickupWater interactItem at {} result={} hand={}", pos, result, bot.getMainHandStack().getItem());
        if (result.isAccepted() && bot.getMainHandStack().isOf(Items.WATER_BUCKET)) {
            bot.swingHand(Hand.MAIN_HAND, true);
            return true;
        }
        return false;
    }

    private static boolean hasHoe(PlayerInventory inventory) {
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).getItem() instanceof HoeItem) {
                return true;
            }
        }
        return false;
    }

    private static boolean ensureHoeInHotbar(ServerPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i).getItem() instanceof HoeItem) {
                return true;
            }
        }
        for (int i = 9; i < inventory.size(); i++) {
            if (inventory.getStack(i).getItem() instanceof HoeItem) {
                int hotbarSlot = ensureHotbarAccess(bot, i);
                return hotbarSlot != -1;
            }
        }
        return false;
    }

    private static int countItem(PlayerInventory inventory, Item item) {
        int count = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int ensureHotbarItem(ServerPlayerEntity bot, Item item) {
        PlayerInventory inventory = bot.getInventory();
        int hotbar = findHotbarItemSlot(inventory, item);
        if (hotbar != -1) {
            return hotbar;
        }
        int slot = findInventoryItemSlot(inventory, item);
        if (slot == -1) {
            return -1;
        }
        return ensureHotbarAccess(bot, slot);
    }

    /**
     * Resolve a usable hotbar slot for {@code slot}, swapping the stack into the hotbar
     * when needed. Returns -1 when the hotbar is locked and the item lives outside it —
     * callers must treat that as "item unavailable" rather than clamping to slot 0.
     */
    private static int ensureHotbarAccess(ServerPlayerEntity bot, int slot) {
        PlayerInventory inventory = bot.getInventory();
        boolean locked = net.wcfcarolina13.GameAI.services.HotbarLockService.isLocked(bot);
        int target = net.wcfcarolina13.PlayerUtils.ScaffoldSlotPolicy.resolveHotbarTarget(
                slot, findEmptyHotbarSlot(inventory), locked, inventory.getSelectedSlot());
        if (locked) {
            return target;
        }
        if (target == slot) {
            return slot;
        }
        swapStacks(inventory, slot, target);
        return target;
    }

    private static int findHotbarItemSlot(PlayerInventory inventory, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private static int findInventoryItemSlot(PlayerInventory inventory, Item item) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private static int findEmptyHotbarSlot(PlayerInventory inventory) {
        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static void swapStacks(PlayerInventory inventory, int a, int b) {
        if (a == b) {
            return;
        }
        ItemStack first = inventory.getStack(a);
        ItemStack second = inventory.getStack(b);
        inventory.setStack(a, second);
        inventory.setStack(b, first);
        inventory.markDirty();
    }

    private static void selectHotbarSlot(ServerPlayerEntity bot, int slot) {
        if (slot < 0 || slot >= 9) {
            return;
        }
        PlayerInventory inventory = bot.getInventory();
        inventory.setSelectedSlot(slot);
        bot.setStackInHand(Hand.MAIN_HAND, inventory.getStack(slot));
        inventory.markDirty();
        bot.playerScreenHandler.syncState();
    }

    private static MovementService.MovementResult moveTo(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        return moveTo(source, bot, target, false);
    }

    private static MovementService.MovementResult moveTo(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target, boolean runStuckTick) {
        abortIfRequested(bot);
        LOGGER.debug("moveTo start from={} to={} eyeY={} vel={} onGround={} sneaking={}", bot.getBlockPos(), target, String.format("%.3f", bot.getY()), bot.getVelocity(), bot.isOnGround(), bot.isSneaking());
        logVerticalIfJump("moveTo-start", bot);
        int startY = bot.getBlockPos().getY();
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                target,
                target,
                null,
                null,
                bot.getHorizontalFacing()
        );
        MovementService.MovementResult result = MovementService.execute(source, bot, plan, false);
        LOGGER.debug("moveTo issued to {} currentPos={} eyeY={} vel={} onGround={} sneaking={}", target, bot.getBlockPos(), String.format("%.3f", bot.getY()), bot.getVelocity(), bot.isOnGround(), bot.isSneaking());
        logVerticalIfJump("moveTo-issued", bot);
        // Check if we fell into a hole and need pillar escape
        int currentY = bot.getBlockPos().getY();
        int depthFallen = startY - currentY;
        if (depthFallen >= 2 && target.getY() > currentY + 1) {
            LOGGER.info("moveTo detected fall: startY={} currentY={} targetY={}, attempting pillar escape", startY, currentY, target.getY());
            checkAndEscapeHole(bot, target.getY());
        }
        if (runStuckTick) {
            // Run return-base stuck tick to allow quick-nudge / mine escapes when the bot is stalled.
            try {
                ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(target));
            } catch (Throwable e) {
                LOGGER.debug("ReturnBaseStuck tick failed: {}", e.getMessage());
            }
        }
        abortIfRequested(bot);
        return result;
    }

    private static void waitUntilClose(ServerPlayerEntity bot, BlockPos target, double maxDistance, int attempts) {
        waitUntilClose(bot, target, maxDistance, attempts, false);
    }

    private static void waitUntilClose(ServerPlayerEntity bot, BlockPos target, double maxDistance, int attempts, boolean runStuckTick) {
        double lastY = bot.getY();
        for (int i = 0; i < attempts; i++) {
            abortIfRequested(bot);
            double dist = bot.getEyePos().distanceTo(Vec3d.ofCenter(target));
            if (dist <= maxDistance) {
                return;
            }
            if (i == 0 || i % 5 == 0 || Math.abs(bot.getY() - lastY) > 0.6) {
                LOGGER.debug("waitUntilClose tick={} dist={} pos={} eyeY={} vel={} onGround={} sneaking={}",
                        i, String.format("%.2f", dist), bot.getBlockPos(), String.format("%.3f", bot.getY()),
                        bot.getVelocity(), bot.isOnGround(), bot.isSneaking());
            }
            lastY = bot.getY();
            logVerticalIfJump("waitUntilClose", bot);
            sleep(50);
        }
        LOGGER.debug("waitUntilClose timeout: dist={} target={} pos={}", bot.getEyePos().distanceTo(Vec3d.ofCenter(target)), target, bot.getBlockPos());
        if (runStuckTick) {
            // If we timed out getting close, allow return-base stuck logic to attempt micro-escapes.
            try {
                ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(target));
            } catch (Throwable e) {
                LOGGER.debug("ReturnBaseStuck tick failed during waitUntilClose: {}", e.getMessage());
            }
        }
    }

    private static BlockPos findNonFarmlandAdjacent(ServerPlayerEntity bot, ServerWorld world) {
        BlockPos botPos = bot.getBlockPos();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos checkPos = botPos.offset(dir);
            BlockState groundState = world.getBlockState(checkPos.down());
            BlockState atPos = world.getBlockState(checkPos);
            if (!groundState.isOf(Blocks.FARMLAND)
                    && groundState.isSolidBlock(world, checkPos.down())
                    && atPos.isAir()) {
                return checkPos;
            }
        }
        return null;
    }

    private static BlockPos findNearestNonFarmland(ServerPlayerEntity bot, ServerWorld world, int radius) {
        BlockPos botPos = bot.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos check = botPos.add(dx, 0, dz);
                BlockState groundState = world.getBlockState(check.down());
                BlockState atPos = world.getBlockState(check);
                if (!groundState.isOf(Blocks.FARMLAND)
                        && groundState.isSolidBlock(world, check.down())
                        && atPos.isAir()) {
                    double dist = botPos.getSquaredDistance(check);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = check;
                    }
                }
            }
        }
        return best;
    }

    private static void sleep(int ms) {
        if (Thread.currentThread().isInterrupted()) {
            throw new SkillAbortException();
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkillAbortException();
        }
    }

    private static void abortIfRequested(ServerPlayerEntity bot) {
        if (bot != null && (SkillManager.shouldAbortSkill(bot) || !TaskService.hasActiveTask(bot.getUuid()))) {
            throw new SkillAbortException();
        }
    }

    private static void logPos(String tag, ServerPlayerEntity bot) {
        Vec3d pos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        if (LAST_POS != null) {
            double dy = pos.y - LAST_POS.y;
            if (Math.abs(dy) > 1.25) {
                LOGGER.warn("verticalAnomaly tag={} dy={} prevY={} currY={} prevPos={} currPos={}", tag, String.format("%.2f", dy), String.format("%.3f", LAST_POS.y), String.format("%.3f", pos.y), LAST_POS.toString(), pos.toString());
            }
        }
        LAST_POS = pos;
        LOGGER.info("posLog {} pos={} vel={} onGround={} sneaking={}", tag, bot.getBlockPos(), bot.getVelocity(), bot.isOnGround(), bot.isSneaking());
    }

    private static Vec3d LAST_POS = null;

    private static void logVerticalIfJump(String tag, ServerPlayerEntity bot) {
        double y = bot.getY();
        if (!Double.isNaN(LAST_Y)) {
            double dy = y - LAST_Y;
            if (Math.abs(dy) > 0.8) {
                LOGGER.warn("verticalAnomaly tag={} dy={} pos={} vel={} onGround={} sneaking={}", tag, String.format("%.2f", dy), bot.getBlockPos(), bot.getVelocity(), bot.isOnGround(), bot.isSneaking());
            }
        }
        LAST_Y = y;
    }

    private static double LAST_Y = Double.NaN;

    // ----------------------- Pillar/Scaffold Escape Helpers -----------------------

    /**
     * Counts available scaffold/pillar blocks in bot inventory.
     */
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

    /**
     * Attempts to place a scaffold block at or below the bot's feet.
     */
    private static boolean tryPlacePillarBlock(ServerPlayerEntity bot, BlockPos target) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (countPillarBlocks(bot) == 0) {
            LOGGER.warn("Farm pillar: no scaffold blocks available");
            return false;
        }
        if (!world.getBlockState(target).isAir() && !world.getBlockState(target).isReplaceable()) {
            return true; // already solid
        }
        // Select a pillar block in hotbar
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && PILLAR_BLOCKS.contains(stack.getItem())) {
                slot = i;
                break;
            }
        }
        if (slot == -1) {
            // Move from main inventory to hotbar
            for (int i = 9; i < bot.getInventory().size(); i++) {
                ItemStack stack = bot.getInventory().getStack(i);
                if (!stack.isEmpty() && PILLAR_BLOCKS.contains(stack.getItem())) {
                    int hotbarSlot = ensureHotbarAccess(bot, i);
                    if (hotbarSlot != -1) {
                        slot = hotbarSlot;
                        break;
                    }
                }
            }
        }
        if (slot == -1) {
            return false;
        }
        selectHotbarSlot(bot, slot);
        // Place block looking down
        LookController.faceBlock(bot, target);
        return BotActions.placeBlockAt(bot, target, Direction.UP, new ArrayList<>(PILLAR_BLOCKS));
    }

    /**
     * Attempts to pillar up out of a hole by jumping and placing blocks beneath.
     * @param bot The bot player
     * @param steps Number of blocks to climb
     * @return true if successfully pillared up all steps
     */
    private static boolean pillarEscape(ServerPlayerEntity bot, int steps) {
        if (steps <= 0) return true;
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        int available = countPillarBlocks(bot);
        if (available < steps) {
            LOGGER.warn("Farm pillar: insufficient blocks ({}) to climb {} steps", available, steps);
            steps = available; // climb as far as we can
            if (steps == 0) return false;
        }
        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        LOGGER.info("Farm pillar: starting {} steps from {}", steps, bot.getBlockPos().toShortString());
        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                bot.setSneaking(wasSneaking);
                return false;
            }
            // Jump
            BotActions.jump(bot);
            sleepQuiet(PILLAR_STEP_DELAY_MS);
            // Place block at the position we just vacated
            BlockPos placeTarget = bot.getBlockPos().down();
            if (world.getBlockState(placeTarget).isAir() || world.getBlockState(placeTarget).isReplaceable()) {
                if (!tryPlacePillarBlock(bot, placeTarget)) {
                    LOGGER.warn("Farm pillar: failed to place block at step {}", i);
                    bot.setSneaking(wasSneaking);
                    return false;
                }
                LOGGER.debug("Farm pillar: placed at {} (step {})", placeTarget.toShortString(), i);
            }
            sleepQuiet(PILLAR_STEP_DELAY_MS);
        }
        bot.setSneaking(wasSneaking);
        LOGGER.info("Farm pillar: completed {} steps, now at {}", steps, bot.getBlockPos().toShortString());
        return true;
    }

    /**
     * Checks if the bot has fallen into a hole relative to the target and attempts pillar escape.
     * @param bot The bot player
     * @param targetY The Y coordinate we're trying to reach
     * @return true if no hole or successfully escaped
     */
    private static boolean checkAndEscapeHole(ServerPlayerEntity bot, int targetY) {
        int botY = bot.getBlockPos().getY();
        int diff = targetY - botY;
        if (diff <= 1) {
            return true; // Not in a hole or only 1 block difference
        }
        if (diff > 6) {
            LOGGER.warn("Farm pillar: bot is {} blocks below target, too deep to pillar", diff);
            return false;
        }
        LOGGER.info("Farm pillar: bot fell into hole (depth={}), attempting pillar escape", diff);
        return pillarEscape(bot, diff);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Escapes from water by swimming to surface and finding solid ground.
     * If stuck at a lower Y level, attempts pillar escape to climb back up.
     */
    private static void escapeWaterIfNeeded(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source) {
        escapeWaterIfNeeded(bot, world, source, true);
    }

    private static void escapeWaterIfNeeded(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, boolean runStuckTick) {
        BlockPos botPos = bot.getBlockPos();
        BlockState feetState = world.getBlockState(botPos);
        
        // Check if we're in water
        if (!feetState.isOf(Blocks.WATER)) {
            return; // Not in water, nothing to do
        }
        
        LOGGER.info("Farm escape: bot is in water at {}, attempting escape", botPos.toShortString());
        
        // First, try to swim up by holding jump for a bit
        for (int i = 0; i < 20 && world.getBlockState(bot.getBlockPos()).isOf(Blocks.WATER); i++) {
            BotActions.jump(bot);
            sleepQuiet(100);
        }
        
        // Try to find solid ground nearby and walk to it
        BlockPos exit = findSolidGroundNearby(world, bot.getBlockPos(), 16);
        if (exit != null) {
            LOGGER.info("Farm escape: found solid ground at {}, navigating", exit.toShortString());
            moveTo(source, bot, exit, runStuckTick);
            sleep(500);
        }
        
        // If still in water, try a broader scan once.
        if (world.getBlockState(bot.getBlockPos()).isOf(Blocks.WATER)) {
            BlockPos broader = findSolidGroundNearby(world, bot.getBlockPos(), 24);
            if (broader != null) {
                LOGGER.info("Farm escape: still in water; broader exit found at {}, navigating", broader.toShortString());
                moveTo(source, bot, broader, runStuckTick);
                sleep(700);
            }
        }
        // Check if we're still stuck below where we need to be
        // If we're on solid ground now but below the exit level, pillar up
        int currentY = bot.getBlockPos().getY();
        if (exit != null && exit.getY() > currentY + 1) {
            int climb = exit.getY() - currentY;
            if (climb <= 6) {
                LOGGER.info("Farm escape: need to climb {} blocks to reach solid ground", climb);
                pillarEscape(bot, climb);
            }
        }
    }
    
    /**
     * Finds nearby solid ground that's not water.
     */
    private static BlockPos findSolidGroundNearby(ServerWorld world, BlockPos center, int radius) {
        BlockPos best = null;
        int bestDistSq = Integer.MAX_VALUE;
        int bestY = Integer.MIN_VALUE;
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 4; dy++) {
                    BlockPos check = center.add(dx, dy, dz);
                    BlockState below = world.getBlockState(check.down());
                    BlockState at = world.getBlockState(check);
                    BlockState above = world.getBlockState(check.up());
                    
                    // Need: solid below, air/passable at feet and head, not water
                    if (below.isSolidBlock(world, check.down()) 
                            && !below.isOf(Blocks.WATER)
                            && (at.isAir() || at.isReplaceable())
                            && (above.isAir() || above.isReplaceable())) {
                        int distSq = dx * dx + dz * dz;
                        // Prefer higher ground, then closer
                        if (check.getY() > bestY || (check.getY() == bestY && distSq < bestDistSq)) {
                            best = check;
                            bestY = check.getY();
                            bestDistSq = distSq;
                        }
                    }
                }
            }
        }
        return best;
    }
}
