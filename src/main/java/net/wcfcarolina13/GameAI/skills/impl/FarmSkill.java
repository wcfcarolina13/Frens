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

            boolean reuseExistingIrrigation = false;

            // Prefer an existing enclosed 2x2 still-water basin as the farm center.
            BlockPos existingIrrigation = findNearbyEnclosedIrrigation2x2(world, bot.getBlockPos(), WATER_SEARCH_RADIUS);

            BlockPos farmCenter;
            BlockPos refillSource;
            if (existingIrrigation != null) {
                reuseExistingIrrigation = true;
                farmCenter = existingIrrigation.toImmutable();
                refillSource = farmCenter;
                LOGGER.info("Using existing enclosed 2x2 still-water basin as farm center at {}", farmCenter);
            } else {
                BlockPos rough = findFarmCenter(world, bot.getBlockPos());
                rough = clampCenterToGroundNear(world, rough, bot.getBlockPos().getY());

                BlockPos chosen = selectReachableNewFarmCenterOnGrid(bot, world, source, rough);
                if (chosen == null) {
                    ChatUtils.sendSystemMessage(source, "No suitable reachable spot on the farm grid (avoid oceans/doors/cliffs). Try a flatter / more open spot.");
                    return SkillExecutionResult.failure("No clear reachable grid cell available.");
                }
                farmCenter = chosen;

                // Prefer refill sources near the farm center.
                List<BlockPos> refillSources = findStillWaterSources(world, farmCenter, WATER_SEARCH_RADIUS);
                refillSource = refillSources.isEmpty() ? null : refillSources.get(0);
                if (refillSource != null) {
                    LOGGER.info("Found still water at {} for irrigation/refills", refillSource);
                } else {
                    LOGGER.warn("No viable still water found within {} blocks of {}", WATER_SEARCH_RADIUS, farmCenter);
                }

                farmCenter = offsetAwayFromNearbyFarmland(world, farmCenter, HYDRATION_RADIUS + 3);
                farmCenter = clampCenterToGroundNear(world, farmCenter, bot.getBlockPos().getY());
            }

            // Preserve nearby still-water tiles inside the farm footprint (integrate them rather than bulldozing).
            Set<BlockPos> protectedStillWater = findProtectedStillWaterInFarmArea(world, farmCenter);
            for (BlockPos p : new BlockPos[]{farmCenter, farmCenter.add(1, 0, 0), farmCenter.add(0, 0, 1), farmCenter.add(1, 0, 1)}) {
                if (isStillWater(world, p)) {
                    protectedStillWater.add(p.toImmutable());
                }
            }

            // Check for dangerous terrain BEFORE tree clearing to avoid false positives
            // from temporary height changes during woodcut operations
            if (hasSimplePrecipice(world, farmCenter)) {
                ChatUtils.sendSystemMessage(source, "Unsafe drop near farm site; find flatter ground.");
                return SkillExecutionResult.failure("Unsafe terrain near farm site.");
            }

            escapeTreeAndWoodcut(bot, world, source, context, farmCenter);
            clearBlockingTrees(bot, world, source, context, farmCenter);
            abortIfRequested(bot);

            if (!ensureWaterSupply(bot, world, source, farmCenter, refillSource)) {
                return SkillExecutionResult.failure("No water available for irrigation.");
            }
            abortIfRequested(bot);

            Integer targetFarmY = computeFarmTargetY(world, farmCenter, protectedStillWater);
            if (targetFarmY != null) {
                LOGGER.info("[FarmIrrigation] targetY={} centerBefore={}", targetFarmY, farmCenter);
                levelGround(bot, world, source, farmCenter, targetFarmY, context, protectedStillWater);
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
                    }
                }
            } else {
                LOGGER.warn("[FarmIrrigation] no targetY samples available at center={}, skipping leveling", farmCenter);
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
            clearBlockingTrees(bot, world, source, context, farmCenter);

            finalTopOffBuckets(bot, world, source, farmCenter, refillSource);

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
        ServerPlayerEntity bot = source.getPlayer();
        abortIfRequested(bot);
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("internal", true);
            params.put("replantSaplings", false);
            params.put("count", 1);
            params.put("searchRadius", TREE_CLEAR_RADIUS + 2);
            params.put("verticalRange", 8);
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
        Set<BlockPos> set = new HashSet<>();
        if (world == null || center == null) {
            return set;
        }
        int minX = center.getX() - HYDRATION_RADIUS;
        int maxX = center.getX() + 1 + HYDRATION_RADIUS;
        int minZ = center.getZ() - HYDRATION_RADIUS;
        int maxZ = center.getZ() + 1 + HYDRATION_RADIUS;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = new BlockPos(x, center.getY() + dy, z);
                    if (isStillWater(world, p)) {
                        set.add(p.toImmutable());
                    }
                }
            }
        }
        return set;
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

    private static void escapeTreeAndWoodcut(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, SkillContext ctx, BlockPos center) {
        abortIfRequested(bot);
        boolean insideTree = isInsideTree(bot, world);
        boolean skyBlocked = !world.isSkyVisible(bot.getBlockPos().up(3));
        int nearbyTreeBlocks = countBlockingTreeBlocks(world, center);
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
            SkillExecutionResult result = runWoodcutInline(source, new SkillContext(source, ctx.sharedState()));
            LOGGER.info("escapeTree woodcut result success={} msg={}", result.success(), result.message());
            net.wcfcarolina13.GameAI.BotEventHandler.rescueFromBurial(bot);
            insideTree = isInsideTree(bot, world);
            skyBlocked = !world.isSkyVisible(bot.getBlockPos().up(3));
            nearbyTreeBlocks = countBlockingTreeBlocks(world, center);
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

    private static void clearBlockingTrees(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, SkillContext ctx, BlockPos center) {
        abortIfRequested(bot);
        int attempts = 0;
        int blockingTreeBlocks = countBlockingTreeBlocks(world, center);
        while (attempts < 3 && blockingTreeBlocks > 0) {
            abortIfRequested(bot);
            attempts++;
            ChatUtils.sendSystemMessage(source, "Clearing trees near the farm area (pass " + attempts + ").");
            SkillContext woodcutCtx = new SkillContext(source, ctx.sharedState());
            SkillExecutionResult woodcutResult = runWoodcutInline(source, woodcutCtx);
            int nextBlockingTreeBlocks = countBlockingTreeBlocks(world, center);
            LOGGER.info("clearBlockingTrees pass={} result={} before={} after={}",
                    attempts, woodcutResult.success(), blockingTreeBlocks, nextBlockingTreeBlocks);
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
        int minX = center.getX() - HYDRATION_RADIUS - 1;
        int maxX = center.getX() + HYDRATION_RADIUS + 2;
        int minZ = center.getZ() - HYDRATION_RADIUS - 1;
        int maxZ = center.getZ() + HYDRATION_RADIUS + 2;
        int minY = center.getY();
        int maxY = center.getY() + 9;
        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {
                        count++;
                    }
                }
            }
        }
        return count;
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
        int minX = center.getX() - HYDRATION_RADIUS;
        int maxX = center.getX() + 1 + HYDRATION_RADIUS;
        int minZ = center.getZ() - HYDRATION_RADIUS;
        int maxZ = center.getZ() + 1 + HYDRATION_RADIUS;
        int minY = center.getY() - 1;
        int maxY = center.getY() + 3;

        String tetherHazard = findFenceOrTetherHazard(world, center, minX, maxX, minZ, maxZ, minY, maxY);
        if (tetherHazard != null) {
            return tetherHazard;
        }

        List<Integer> sampledY = new ArrayList<>();
        List<BlockPos> sampledPos = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                if (topY <= world.getBottomY()) {
                    return "no-surface x=" + x + " z=" + z;
                }
                BlockPos surface = new BlockPos(x, topY - 1, z);
                BlockState surfaceState = world.getBlockState(surface);

                // Reject underwater / liquid surface cells (prevents ocean-floor farming).
                if (surfaceState.isOf(Blocks.WATER)) {
                    return "water-surface pos=" + surface.toShortString();
                }
                // Avoid building a new farm on top of an existing farm.
                if (surfaceState.isOf(Blocks.FARMLAND)) {
                    return "existing-farmland pos=" + surface.toShortString();
                }
                sampledY.add(surface.getY());
                sampledPos.add(surface);
            }
        }

        if (sampledY.isEmpty()) {
            return "no-surface-samples";
        }

        List<Integer> sortedY = new ArrayList<>(sampledY);
        sortedY.sort(Integer::compareTo);
        int medianY = sortedY.get(sortedY.size() / 2);
        int moderateOutliers = 0;
        int severeOutliers = 0;
        int worstDelta = 0;
        BlockPos worstPos = center;
        for (int i = 0; i < sampledY.size(); i++) {
            int delta = Math.abs(sampledY.get(i) - medianY);
            if (delta > 2) {
                moderateOutliers++;
            }
            if (delta > 4) {
                severeOutliers++;
            }
            if (delta > worstDelta) {
                worstDelta = delta;
                worstPos = sampledPos.get(i);
            }
        }
        int total = sampledY.size();
        int maxModerateOutliers = Math.max(8, total / 3);
        int maxSevereOutliers = Math.max(2, total / 12);
        if (severeOutliers > maxSevereOutliers || moderateOutliers > maxModerateOutliers) {
            return "steep-terrain medianY=" + medianY
                    + " worstDelta=" + worstDelta
                    + " worstPos=" + worstPos.toShortString()
                    + " moderate=" + moderateOutliers + "/" + total
                    + " severe=" + severeOutliers + "/" + total;
        }
        return null;
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
                int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                if (topY <= world.getBottomY()) {
                    continue;
                }
                BlockPos surface = new BlockPos(x, topY - 1, z);
                BlockState state = world.getBlockState(surface);
                if (state.isAir() || state.isOf(Blocks.WATER)) {
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
                    ActionResult result = handStack.useOnBlock(new ItemUsageContext(bot, Hand.MAIN_HAND, hit));
                    LOGGER.info("[FarmIrrigation] placeWater useOnBlock at {} via {} face={} result={} hand={}",
                            waterPos.toShortString(), hit.getBlockPos().toShortString(), hit.getSide(), result, bot.getMainHandStack().getItem());
                    if (result.isAccepted() || world.getBlockState(waterPos).isOf(Blocks.WATER)) {
                        bot.swingHand(Hand.MAIN_HAND, true);
                        if (world.getBlockState(waterPos).isOf(Blocks.WATER)) {
                            future.complete(true);
                            return;
                        }
                    }
                }

                if (!ensureWaterBucketSelected(bot)) {
                    future.complete(false);
                    return;
                }
                LookController.faceBlock(bot, waterPos);
                ActionResult itemResult = bot.interactionManager.interactItem(
                        bot,
                        world,
                        bot.getMainHandStack(),
                        Hand.MAIN_HAND
                );
                LOGGER.info("[FarmIrrigation] placeWater interactItem fallback at {} result={} hand={}",
                        waterPos.toShortString(), itemResult, bot.getMainHandStack().getItem());
                if (itemResult.isAccepted() || world.getBlockState(waterPos).isOf(Blocks.WATER)) {
                    bot.swingHand(Hand.MAIN_HAND, true);
                    if (world.getBlockState(waterPos).isOf(Blocks.WATER)) {
                        future.complete(true);
                        return;
                    }
                }

                future.complete(world.getBlockState(waterPos).isOf(Blocks.WATER));
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
        List<BlockPos> plots = new ArrayList<>();
        int minX = center.getX() - HYDRATION_RADIUS;
        int maxX = center.getX() + 1 + HYDRATION_RADIUS;
        int minZ = center.getZ() - HYDRATION_RADIUS;
        int maxZ = center.getZ() + 1 + HYDRATION_RADIUS;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (x >= center.getX() && x <= center.getX() + 1 &&
                        z >= center.getZ() && z <= center.getZ() + 1) {
                    continue; // skip 2x2 water
                }
                plots.add(new BlockPos(x, center.getY(), z));
            }
        }
        return plots;
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
        // Ideal: full still basin.
        if (still >= 4) {
            return true;
        }
        // Good-enough: two source corners with near-full coverage while updates settle.
        if (still >= 2 && water >= 3) {
            return true;
        }
        // Snow/cold-biome fallback: if one source fills all 4 cells, keep going.
        // This still hydrates nearby farmland and avoids false hard-fail loops.
        if (still >= 1 && water >= 4) {
            return true;
        }
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
                selectHotbarSlot(bot, retry);
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
        if (SHOVEL_DIG_BLOCKS.contains(block)) {
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
        int radius = HYDRATION_RADIUS;
        List<Integer> heights = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos base = center.add(dx, 0, dz);
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
        abortIfRequested(bot);
        // Keep leveling limited to the actual farm footprint to avoid destructive landscaping.
        int radius = HYDRATION_RADIUS; // 4 blocks: covers the full 10x10 farm area

        // Second pass: process each column systematically
        // Process in order from center outward in a spiral-like pattern for more consistent results
        List<int[]> columns = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                columns.add(new int[]{dx, dz});
            }
        }
        // Sort by distance from center for more consistent leveling
        columns.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));

        for (int[] col : columns) {
            abortIfRequested(bot);
            int dx = col[0];
            int dz = col[1];
            BlockPos columnBase = center.add(dx, 0, dz);

            if (protectedStillWater != null && protectedStillWater.contains(columnBase)) {
                continue;
            }
            
            // Re-sample surface Y each iteration since previous operations may have changed terrain
            Integer surfaceY = findSurfaceY(world, columnBase);
            if (surfaceY == null) continue;
            int diff = surfaceY - targetY;
            
            // Skip steep columns to avoid carving into hillsides / creating ugly cliffs.
            if (Math.abs(diff) > 2) {
                LOGGER.debug("levelGround: skipping steep column at {} (diff={})", columnBase, diff);
                continue;
            }

            BlockPos topCheck = new BlockPos(columnBase.getX(), surfaceY, columnBase.getZ());
            BlockState topCheckState = world.getBlockState(topCheck);
            if (topCheckState.isIn(BlockTags.LOGS) || topCheckState.isIn(BlockTags.LEAVES)) {
                LOGGER.info("levelGround encountered tree block at {}, invoking woodcut", topCheck);
                runWoodcutInline(source, new SkillContext(source, ctx.sharedState()));
                // Re-sample after woodcut
                surfaceY = findSurfaceY(world, columnBase);
                if (surfaceY == null) continue;
                diff = surfaceY - targetY;
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
                BlockPos stand = findStandingSpot(world, columnBase);
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
        for (int dx = -radius; dx <= radius; dx++) {
            abortIfRequested(bot);
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos columnBase = center.add(dx, 0, dz);
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
        for (int dx = -radius; dx <= radius; dx++) {
            abortIfRequested(bot);
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos columnBase = center.add(dx, 0, dz);
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

    private static int ensureHotbarAccess(ServerPlayerEntity bot, int slot) {
        PlayerInventory inventory = bot.getInventory();
        if (slot < 9) {
            return slot;
        }
        int empty = findEmptyHotbarSlot(inventory);
        int target = empty == -1 ? inventory.getSelectedSlot() : empty;
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
