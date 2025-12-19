package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.BlockInteractionService;
import net.shasankp000.GameAI.services.WorkDirectionService;
import net.shasankp000.GameAI.services.CraftingHelper;
import net.shasankp000.GameAI.services.ChestStoreService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.skills.impl.CollectDirtSkill;
import net.shasankp000.GameAI.skills.impl.StripMineSkill;
import net.shasankp000.GameAI.skills.impl.WoodcutSkill;
import net.shasankp000.GameAI.skills.support.TreeDetector;
import net.shasankp000.GameAI.services.SkillResumeService;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.PlayerUtils.MiningTool;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.EntityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Emergency shelter builder: erects a small hovel using cheap blocks (dirt/cobble/etc.)
 * around the bot, clearing interior space and adding a roof and torches.
 */
public final class ShelterSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-shelter");
    private static final List<Item> SCAFFOLD_BLOCKS = List.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.GRAVEL,
            Items.SAND,
            Items.RED_SAND,
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.NETHERRACK
    );
    private static final List<Item> BUILD_BLOCKS = List.of(
            Items.DIRT, Items.COARSE_DIRT, Items.ROOTED_DIRT,
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
            Items.SANDSTONE, Items.RED_SANDSTONE,
            Items.ANDESITE, Items.GRANITE, Items.DIORITE,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
            Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
            Items.BAMBOO_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
    );

    private static final List<Item> DOOR_ITEMS = List.of(
            Items.OAK_DOOR, Items.SPRUCE_DOOR, Items.BIRCH_DOOR, Items.JUNGLE_DOOR,
            Items.ACACIA_DOOR, Items.DARK_OAK_DOOR, Items.MANGROVE_DOOR, Items.CHERRY_DOOR,
            Items.BAMBOO_DOOR, Items.CRIMSON_DOOR, Items.WARPED_DOOR
    );

    private static final class BuildCounters {
        int attemptedPlacements;
        int placedBlocks;
        int reachFailures;
        int noMaterials;
    }

    private record ActiveBuildSite(BlockPos center, int radius, Direction doorSide, int wallHeight) {}
    private static final ThreadLocal<ActiveBuildSite> ACTIVE_BUILD_SITE = new ThreadLocal<>();
    private static final ThreadLocal<Long> LAST_RECENTER_MS = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> LAST_COMBAT_YIELD_MS = ThreadLocal.withInitial(() -> 0L);

    @Override
    public String name() {
        return "shelter";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = Objects.requireNonNull(source.getPlayer(), "player");
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("No world available for shelter.");
        }
        BlockPos origin = bot.getBlockPos();
        String type = getOption(context, "hovel");
        if ("burrow".equalsIgnoreCase(type)) {
            LOGGER.info("Burrow sequence start: descend 5 -> strip 5 -> descend 3 -> hollow");
            SkillExecutionResult burrowResult = buildBurrow(source, bot, world, origin);
            if (!burrowResult.success()) {
                return burrowResult;
            }
            BlockPos chamberCenter = bot.getBlockPos();
            depositCheapLoot(world, bot, chamberCenter);
            ensurePickupSlot(bot);
            if (!inventoryFull(bot)) {
                sweepDrops(source, 12.0, 5.0, 24, 12_000L);
            } else {
                LOGGER.warn("Burrow: inventory still full after deposit; skipping drop sweep.");
            }
            ChatUtils.sendSystemMessage(source, "Emergency burrow built.");
            return SkillExecutionResult.success("Shelter (burrow) built.");
        }

        boolean resumeRequested = SkillResumeService.consumeResumeIntent(bot.getUuid());
        int radius = Math.max(2, Math.min(5, getInt(context, "radius", getInt(context, "count", 3))));
        int wallHeight = 5;
        Direction preferredDoorSide = null;
        Object directionParam = context.parameters().get("direction");
        if (directionParam instanceof Direction dir) {
            preferredDoorSide = dir;
        }

        // If we're in water (common at shorelines), step onto nearby dry land first.
        // Otherwise the "underground" check can misclassify shallow riverbeds and try to mine upward.
        if (tryStepOutOfWater(source, bot, world)) {
            origin = bot.getBlockPos();
        }

        // If we're clearly underground, get to the surface first (then re-plan the hovel site).
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        boolean skyVisible = world.isSkyVisible(origin.up());
        boolean underArtificialRoof = isLikelyUnderArtificialRoof(world, origin, 7);
        if (!skyVisible && !underArtificialRoof && bot.getBlockY() < surfaceY - 3) {
            // If we're simply under a roof/walls near the surface, relocate to a nearby sky-visible opening
            // instead of trying to mine upward (which can hit shoreline water and abort).
            if (tryRelocateToNearbySurfaceOpening(source, bot, world, origin, 8)) {
                origin = bot.getBlockPos();
                surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
                skyVisible = world.isSkyVisible(origin.up());
            }
            if (!skyVisible && bot.getBlockY() < surfaceY - 3) {
                boolean surfaced = tryReachSurface(source, bot, world, origin);
                if (surfaced) {
                    origin = bot.getBlockPos();
                }
            }
        }
        surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        if (!world.isSkyVisible(origin.up()) && !underArtificialRoof && bot.getBlockY() < surfaceY - 2) {
            return SkillExecutionResult.failure("I can't build a surface shelter while underground; I couldn't reach the surface.");
        }

        ExistingHovel existing = detectExistingShelter(world, bot, origin, radius, wallHeight);
        if (existing != null) {
            radius = existing.radius();
            origin = existing.center();
            if (preferredDoorSide == null) {
                preferredDoorSide = existing.doorSide();
            }
            LOGGER.info("Shelter: detected existing footprint near origin; using center={} radius={} door={}",
                    origin.toShortString(), radius, preferredDoorSide);
        }

        HovelPlan plan = selectHovelPlan(world, bot, origin, radius, wallHeight, preferredDoorSide, context.sharedState(), resumeRequested);
        BlockPos center = plan.center();
        Direction doorSide = plan.doorSide();

        if (!moveToBuildSite(source, bot, center)) {
            boolean surfaced = tryReachSurface(source, bot, world, origin);
            if (surfaced) {
                origin = bot.getBlockPos();
                plan = selectHovelPlan(world, bot, origin, radius, wallHeight, preferredDoorSide, context.sharedState(), resumeRequested);
                center = plan.center();
                doorSide = plan.doorSide();
            }
            if (!moveToBuildSite(source, bot, center)) {
                return SkillExecutionResult.failure("I couldn't reach a safe spot to build a hovel.");
            }
        }

        ensureClearBuildSiteWithWoodcut(source, bot, world, center, radius);

        // Pre-clear leaf litter / tree overhang so we don't end up "using trees as walls/roof".
        clearObstructiveVegetation(world, bot, center, radius, wallHeight);

        int neededBlocks = estimatePlacementNeed(world, center, radius, wallHeight, doorSide);
        int available = countBuildBlocks(bot);
        LOGGER.info("Shelter: center={}, radius={}, wallHeight={}, estBlocks={}, available={}", center.toShortString(), radius, wallHeight, neededBlocks, available);
        boolean allowAutoGather = !hasOption(context, "ask", "confirm", "wait", "manual")
                || resumeRequested
                || hasOption(context, "proceed", "collect", "gather", "auto");
        announceResources(source, neededBlocks, available, allowAutoGather);
        int shortage = Math.max(0, neededBlocks - available);
        if (shortage > 0 && !allowAutoGather) {
            String waitMsg = "Shelter needs " + neededBlocks + " cheap blocks (have " + available + ", short " + shortage + "). "
                    + "Run '/bot resume " + bot.getName().getString() + "' to allow me to gather the shortfall, or rerun with 'proceed'.";
            LOGGER.warn("Shelter paused for materials: {}", waitMsg);
            ChatUtils.sendSystemMessage(source, waitMsg);
            return SkillExecutionResult.failure("Shelter waiting for materials.");
        }

        // Ensure we have a nearby chest to dump junk if the build fills the inventory.
        ensureBuildChestAndDeposit(source, world, bot, center, radius);

        try {
            ACTIVE_BUILD_SITE.set(new ActiveBuildSite(center, radius, doorSide, wallHeight));
            LAST_RECENTER_MS.set(0L);
            // If combat/defense dragged us far away, return near the build site before resuming placement loops.
            maybeRecenterToBuildSite(source, bot, world, ACTIVE_BUILD_SITE.get());

            int gathered = allowAutoGather ? ensureBuildStock(source, bot, neededBlocks, true, center) : 0;
            available = countBuildBlocks(bot);
            if (gathered + available < neededBlocks) {
                int oldRadius = radius;
                radius = Math.max(2, radius - 1);
                neededBlocks = estimateBlockNeed(radius, wallHeight);
                LOGGER.warn("Shelter: downsizing radius from {} to {} due to material shortfall. New estBlocks={}", oldRadius, radius, neededBlocks);
                ACTIVE_BUILD_SITE.set(new ActiveBuildSite(center, radius, doorSide, wallHeight));
            }

            BuildCounters counters = new BuildCounters();

            // Clear interior and shape walls/roof
            levelInterior(world, bot, center, radius);
            clearObstructiveVegetation(world, bot, center, radius, wallHeight);
            int lowWallHeight = Math.min(wallHeight, 3);
            buildHovel(world, bot, center, radius, lowWallHeight, doorSide, counters);
            ensureBuildChestAndDeposit(source, world, bot, center, radius);
            if (wallHeight > lowWallHeight) {
                buildUpperShellAndRoofWithScaffolds(world, source, bot, center, radius, wallHeight, lowWallHeight, doorSide, counters);
            } else {
                buildRoof(world, bot, center, radius, wallHeight, counters);
            }
            ensureBuildChestAndDeposit(source, world, bot, center, radius);
            // Patch pass to fill holes (run after scaffold phase so roof exists)
            patchGaps(world, bot, center, radius, wallHeight, doorSide, counters);
            ensureBuildChestAndDeposit(source, world, bot, center, radius);
            if (hasGaps(world, center, radius, wallHeight, doorSide)) {
                LOGGER.warn("Shelter: gaps detected after first patch; gathering more and repatching.");
                ensureBuildStock(source, bot, estimateBlockNeed(radius, wallHeight) / 2, true, center);
                patchGaps(world, bot, center, radius, wallHeight, doorSide, counters);
                if (hasGaps(world, center, radius, wallHeight, doorSide) && wallHeight > lowWallHeight) {
                    LOGGER.warn("Shelter: gaps remain after patch; rerunning scaffold pass for unreachable roof/walls.");
                    buildUpperShellAndRoofWithScaffolds(world, source, bot, center, radius, wallHeight, lowWallHeight, doorSide, counters);
                    patchGaps(world, bot, center, radius, wallHeight, doorSide, counters);
                }
            }
            placeDoor(world, bot, center, radius, doorSide);
            ensureDoorwayOpen(world, bot, center, radius, doorSide);
            placeChest(world, bot, center, radius);
            placeTorches(world, bot, center, radius);
            // Final pre-sweep polish: re-level the interior and repatch any last gaps.
            // (This catches late placement misses from scaffolding or odd footing near edges.)
            levelInterior(world, bot, center, radius);
            patchGaps(world, bot, center, radius, wallHeight, doorSide, counters);
            ensureDoorwayOpen(world, bot, center, radius, doorSide);
            sweepDrops(source, 12.0, 5.0, 24, 12_000L);
            // Final cleanup deposit after sweeping, if a chest is available.
            ensureBuildChestAndDeposit(source, world, bot, center, radius);
            if (counters.attemptedPlacements > 0 && counters.placedBlocks < Math.max(8, (int) Math.round(counters.attemptedPlacements * 0.35))) {
                String msg = "Hovel build incomplete: placed " + counters.placedBlocks + "/" + counters.attemptedPlacements
                        + " blocks (reachFails=" + counters.reachFailures
                        + ", noMaterials=" + counters.noMaterials + ").";
                LOGGER.warn("Shelter: {}", msg);
                ChatUtils.sendSystemMessage(source, msg + " Try rerunning on flatter ground or closer to open space.");
                return SkillExecutionResult.failure("Shelter (hovel) incomplete.");
            }
            ChatUtils.sendSystemMessage(source, "Emergency hovel built.");
            return SkillExecutionResult.success("Shelter (hovel) built.");
        } finally {
            ACTIVE_BUILD_SITE.remove();
            LAST_RECENTER_MS.remove();
            LAST_COMBAT_YIELD_MS.remove();
        }
    }

    private record HovelPlan(BlockPos center, Direction doorSide) {}
    private record ExistingHovel(BlockPos center, int radius, Direction doorSide) {}

    private void buildUpperShellAndRoofWithScaffolds(ServerWorld world,
                                                    ServerCommandSource source,
                                                    ServerPlayerEntity bot,
                                                    BlockPos center,
                                                    int radius,
                                                    int wallHeight,
                                                    int lowWallHeight,
                                                    Direction doorSide,
                                                    BuildCounters counters) {
        if (world == null || source == null || bot == null || center == null) {
            return;
        }
        int floorY = center.getY();
        int roofY = floorY + wallHeight;
        int upperStartY = floorY + lowWallHeight + 1;
        if (upperStartY > roofY) {
            return;
        }

        List<BlockPos> scaffoldBases = computeScaffoldBases(world, center, radius);
        if (scaffoldBases.isEmpty()) {
            LOGGER.warn("Shelter scaffold: no viable base positions inside footprint; proceeding without scaffold.");
            buildRoof(world, bot, center, radius, wallHeight, counters);
            return;
        }

        for (BlockPos base : scaffoldBases) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (!moveToBuildSite(source, bot, base)) {
                continue;
            }
            List<BlockPos> placedPillar = new ArrayList<>();
            int desiredFootY = roofY - 2;
            int steps = Math.max(0, desiredFootY - bot.getBlockY());
            if (steps > 0 && !pillarUp(bot, steps, placedPillar)) {
                descendAndCleanup(bot, placedPillar);
                continue;
            }

            placeUpperWallsDirect(world, bot, center, radius, upperStartY, roofY, doorSide, counters);
            placeRoofDirect(world, bot, center, radius, roofY, counters);

            descendAndCleanup(bot, placedPillar);
        }
    }

    private List<BlockPos> computeScaffoldBases(ServerWorld world, BlockPos center, int radius) {
        if (world == null || center == null) {
            return List.of();
        }
        int interiorOffset = Math.max(0, radius - 2);
        List<BlockPos> seeds = new ArrayList<>();
        seeds.add(center);
        if (interiorOffset > 0) {
            seeds.add(center.add(interiorOffset, 0, interiorOffset));
            seeds.add(center.add(interiorOffset, 0, -interiorOffset));
            seeds.add(center.add(-interiorOffset, 0, interiorOffset));
            seeds.add(center.add(-interiorOffset, 0, -interiorOffset));
        }

        List<BlockPos> bases = new ArrayList<>();
        for (BlockPos seed : seeds) {
            BlockPos candidate = findStandableInside(world, center, radius, seed, 2);
            if (candidate != null && !bases.contains(candidate)) {
                bases.add(candidate);
            }
        }
        return bases;
    }

    private BlockPos findStandableInside(ServerWorld world, BlockPos center, int radius, BlockPos near, int searchRadius) {
        if (world == null || center == null || near == null) {
            return null;
        }
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                BlockPos foot = near.add(dx, 0, dz);
                int relX = foot.getX() - center.getX();
                int relZ = foot.getZ() - center.getZ();
                if (Math.abs(relX) >= radius || Math.abs(relZ) >= radius) {
                    continue; // stay inside the footprint
                }
                if (!isStandable(world, foot)) {
                    continue;
                }
                double sq = near.getSquaredDistance(foot);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = foot.toImmutable();
                }
            }
        }
        return best;
    }

    private void placeUpperWallsDirect(ServerWorld world,
                                       ServerPlayerEntity bot,
                                       BlockPos center,
                                       int radius,
                                       int startY,
                                       int endY,
                                       Direction doorSide,
                                       BuildCounters counters) {
        if (world == null || bot == null || center == null) {
            return;
        }
        int floorY = center.getY();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                if (!perimeter) {
                    continue;
                }
                for (int y = startY; y <= endY; y++) {
                    BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (isDoorGap(pos, center, radius, doorSide, floorY)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(pos);
                    if (!state.isAir() && !state.isReplaceable() && !state.isIn(BlockTags.LEAVES) && !state.isOf(Blocks.SNOW)) {
                        continue;
                    }
                    placeBlockDirectIfWithinReach(bot, pos, counters);
                }
            }
        }
    }

    private void placeRoofDirect(ServerWorld world,
                                 ServerPlayerEntity bot,
                                 BlockPos center,
                                 int radius,
                                 int roofY,
                                 BuildCounters counters) {
        if (world == null || bot == null || center == null) {
            return;
        }
        // Perimeter -> center so each placement has neighbor support.
        for (int ring = radius; ring >= 0; ring--) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos roof = new BlockPos(center.getX() + dx, roofY, center.getZ() + dz);
                    BlockState state = world.getBlockState(roof);
                    if (!state.isAir()
                            && !state.isReplaceable()
                            && !state.isIn(BlockTags.LEAVES)
                            && !state.isIn(BlockTags.LOGS)
                            && !state.isOf(Blocks.SNOW)) {
                        continue;
                    }
                    placeBlockDirectIfWithinReach(bot, roof, counters);
                }
            }
        }
    }

    private boolean placeBlockDirectIfWithinReach(ServerPlayerEntity bot, BlockPos pos, BuildCounters counters) {
        if (bot == null || pos == null) {
            return false;
        }
        if (yieldToImmediateThreats(bot, 2_000L)) {
            return false;
        }
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double maxReachSq = 20.25D; // ~4.5 blocks (survival reach)
        if (botPos.squaredDistanceTo(center) > maxReachSq) {
            return false;
        }
        Item blockItem = selectBuildItem(bot);
        if (blockItem == null) {
            if (counters != null) {
                counters.noMaterials++;
            }
            return false;
        }
        if (counters != null) {
            counters.attemptedPlacements++;
        }
        BlockState state = bot.getEntityWorld().getBlockState(pos);
        if (state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW)) {
            mineSoft(bot, pos);
        } else if (state.isIn(BlockTags.LOGS)) {
            if (bot.getEntityWorld() instanceof ServerWorld world && !TreeDetector.isNearHumanBlocks(world, pos, 3)) {
                mineSoft(bot, pos);
            }
        }
        net.shasankp000.Entity.LookController.faceBlock(bot, pos);
        boolean placed = BotActions.placeBlockAt(bot, pos, Direction.UP, List.of(blockItem));
        if (placed && counters != null) {
            counters.placedBlocks++;
        }
        return placed;
    }

    private boolean pillarUp(ServerPlayerEntity bot, int steps, List<BlockPos> placedPillar) {
        if (steps <= 0 || bot == null) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                bot.setSneaking(wasSneaking);
                return false;
            }
            int baseY = bot.getBlockY();
            BotActions.jump(bot);
            sleepQuiet(160L);
            // Place into the bot's current foot block after jumping, but never above the pre-jump Y.
            BlockPos placeAt = bot.getBlockPos();
            if (placeAt.getY() > baseY) {
                placeAt = new BlockPos(placeAt.getX(), baseY, placeAt.getZ());
            }
            if (!world.getBlockState(placeAt).isAir()) {
                bot.setSneaking(wasSneaking);
                return false;
            }
            BlockPos placed = tryPlaceScaffold(bot, placeAt);
            if (placed == null) {
                bot.setSneaking(wasSneaking);
                return false;
            }
            placedPillar.add(placed.toImmutable());
            sleepQuiet(160L);
        }
        bot.setSneaking(wasSneaking);
        return true;
    }

    private BlockPos tryPlaceScaffold(ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        Item scaffold = selectBuildItem(bot);
        if (scaffold == null) {
            return null;
        }
        if (!isPlaceableTarget(world, target)) {
            mineSoft(bot, target);
        }
        ensureSupportBlock(bot, target);
        if (BotActions.placeBlockAt(bot, target, Direction.UP, List.of(scaffold))) {
            return target;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos alt = target.offset(dir);
            if (!isPlaceableTarget(world, alt)) {
                mineSoft(bot, alt);
            }
            ensureSupportBlock(bot, alt);
            if (BotActions.placeBlockAt(bot, alt, Direction.UP, List.of(scaffold))) {
                return alt;
            }
        }
        return null;
    }

    private void ensureSupportBlock(ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos below = target.down();
        BlockState belowState = world.getBlockState(below);
        if (!belowState.getCollisionShape(world, below).isEmpty()) {
            return;
        }
        if (!isPlaceableTarget(world, below)) {
            mineSoft(bot, below);
        }
        Item scaffold = selectBuildItem(bot);
        if (scaffold == null) {
            return;
        }
        BotActions.placeBlockAt(bot, below, Direction.UP, List.of(scaffold));
    }

    private boolean isPlaceableTarget(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW);
    }

    private void descendAndCleanup(ServerPlayerEntity bot, List<BlockPos> placedPillar) {
        if (bot == null || placedPillar == null || placedPillar.isEmpty()) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        Collections.reverse(placedPillar);
        for (BlockPos placed : placedPillar) {
            if (SkillManager.shouldAbortSkill(bot)) {
                bot.setSneaking(wasSneaking);
                return;
            }
            if (world.getBlockState(placed).isAir()) {
                continue;
            }
            net.shasankp000.Entity.LookController.faceBlock(bot, placed);
            BotActions.selectBestTool(bot, "shovel", "pickaxe");
            mineSoft(bot, placed);
            sleepQuiet(80L);
        }
        bot.setSneaking(wasSneaking);
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private HovelPlan selectHovelPlan(ServerWorld world,
                                      ServerPlayerEntity bot,
                                      BlockPos origin,
                                      int radius,
                                      int wallHeight,
                                      Direction preferredDoorSide,
                                      Map<String, Object> sharedState,
                                      boolean resumeRequested) {
        if (world == null || bot == null) {
            return new HovelPlan(origin, preferredDoorSide != null ? preferredDoorSide : Direction.NORTH);
        }
        Direction fallbackDoor = preferredDoorSide != null ? preferredDoorSide : bot.getHorizontalFacing();
        if (sharedState == null) {
            sharedState = new HashMap<>();
        }

        String prefix = "shelter.hovel." + bot.getUuid() + ".";
        if (resumeRequested) {
            Object xObj = SharedStateUtils.getValue(sharedState, prefix + "center.x");
            Object yObj = SharedStateUtils.getValue(sharedState, prefix + "center.y");
            Object zObj = SharedStateUtils.getValue(sharedState, prefix + "center.z");
            if (xObj instanceof Number x && yObj instanceof Number y && zObj instanceof Number z) {
                BlockPos stored = new BlockPos(x.intValue(), y.intValue(), z.intValue());
                Direction storedDoor = fallbackDoor;
                Object doorObj = SharedStateUtils.getValue(sharedState, prefix + "door");
                if (doorObj instanceof String str) {
                    try {
                        storedDoor = Direction.valueOf(str);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                return new HovelPlan(stored, storedDoor);
            }
        }

        BlockPos best = origin;
        Direction bestDoor = fallbackDoor;
        double bestScore = Double.NEGATIVE_INFINITY;

        int scanRadius = 7;
        int originY = origin.getY();
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, topY, z);
                if (Math.abs(candidate.getY() - originY) > 3) {
                    continue;
                }
                if (TreeDetector.isNearHumanBlocks(world, candidate, radius + 3)) {
                    continue;
                }
                if (!isStandable(world, candidate)) {
                    continue;
                }
                if (hasUnsafeFootprintSupport(world, candidate, radius)) {
                    continue;
                }
                Flatness flatness = evaluateFlatness(world, candidate, radius);
                if (flatness.maxDelta() > 1) {
                    continue;
                }
                int naturalWalls = countNaturalWalls(world, candidate, radius, wallHeight);
                int vegetation = countVegetationInShell(world, candidate, radius, wallHeight);
                double distSq = origin.getSquaredDistance(candidate);
                double score = naturalWalls * 1.75 - vegetation * 2.25 - distSq * 0.08 - flatness.maxDelta() * 6.0;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }

        bestDoor = pickDoorSide(world, best, radius, fallbackDoor);

        SharedStateUtils.setValue(sharedState, prefix + "center.x", best.getX());
        SharedStateUtils.setValue(sharedState, prefix + "center.y", best.getY());
        SharedStateUtils.setValue(sharedState, prefix + "center.z", best.getZ());
        SharedStateUtils.setValue(sharedState, prefix + "door", bestDoor.name());

        return new HovelPlan(best, bestDoor);
    }

    private ExistingHovel detectExistingShelter(ServerWorld world,
                                                ServerPlayerEntity bot,
                                                BlockPos origin,
                                                int requestedRadius,
                                                int wallHeight) {
        if (world == null || bot == null || origin == null) {
            return null;
        }
        int floorY = origin.getY();
        int scanRadius = Math.max(6, requestedRadius + 2);
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int found = 0;

        int minY = floorY + 1;
        int maxY = floorY + Math.max(2, Math.min(wallHeight, 4));

        for (BlockPos pos : BlockPos.iterate(origin.add(-scanRadius, 0, -scanRadius), origin.add(scanRadius, 0, scanRadius))) {
            for (int y = minY; y <= maxY; y++) {
                BlockPos wallPos = new BlockPos(pos.getX(), y, pos.getZ());
                BlockState state = world.getBlockState(wallPos);
                if (state.isAir() || state.isReplaceable()) {
                    continue;
                }
                if (state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) {
                    continue;
                }
                found++;
                minX = Math.min(minX, wallPos.getX());
                maxX = Math.max(maxX, wallPos.getX());
                minZ = Math.min(minZ, wallPos.getZ());
                maxZ = Math.max(maxZ, wallPos.getZ());
            }
        }

        if (found < 16) {
            return null;
        }

        int spanX = maxX - minX;
        int spanZ = maxZ - minZ;
        if (spanX < 4 || spanZ < 4) {
            return null;
        }
        if (Math.abs(spanX - spanZ) > 3) {
            return null;
        }
        int radius = Math.max(spanX, spanZ) / 2;
        radius = Math.max(2, Math.min(6, radius));

        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        BlockPos center = new BlockPos(centerX, floorY, centerZ);
        if (hasUnsafeFootprintSupport(world, center, radius)) {
            return null;
        }
        Direction doorSide = pickDoorSide(world, center, radius, bot.getHorizontalFacing());
        return new ExistingHovel(center, radius, doorSide);
    }

    private int countVegetationInShell(ServerWorld world, BlockPos center, int radius, int wallHeight) {
        if (world == null || center == null) {
            return 0;
        }
        int floorY = center.getY();
        int roofY = floorY + wallHeight;
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                if (!perimeter) {
                    continue;
                }
                for (int y = floorY; y < roofY; y++) {
                    BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) {
                        count++;
                    }
                }
            }
        }
        BlockPos roofMin = new BlockPos(center.getX() - radius, roofY, center.getZ() - radius);
        BlockPos roofMax = new BlockPos(center.getX() + radius, roofY, center.getZ() + radius);
        for (BlockPos pos : BlockPos.iterate(roofMin, roofMax)) {
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) {
                count++;
            }
        }
        return count;
    }

    private record Flatness(int minY, int maxY) {
        int maxDelta() {
            return maxY - minY;
        }
    }

    private Flatness evaluateFlatness(ServerWorld world, BlockPos center, int radius) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                int topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
                minY = Math.min(minY, topY);
                maxY = Math.max(maxY, topY);
            }
        }
        if (minY == Integer.MAX_VALUE) {
            minY = center.getY();
            maxY = center.getY();
        }
        return new Flatness(minY, maxY);
    }

    private boolean isStandable(ServerWorld world, BlockPos foot) {
        if (world == null || foot == null) {
            return false;
        }
        BlockPos below = foot.down();
        BlockState belowState = world.getBlockState(below);
        if (belowState.getCollisionShape(world, below).isEmpty()) {
            return false;
        }
        BlockState footState = world.getBlockState(foot);
        if (!footState.getCollisionShape(world, foot).isEmpty()) {
            return false;
        }
        BlockState headState = world.getBlockState(foot.up());
        return headState.getCollisionShape(world, foot.up()).isEmpty();
    }

    /**
     * Reject build sites that would require building over water/air (e.g., shoreline overhang).
     * We intentionally prefer clearing trees (woodcut) over attempting to build over/into water.
     */
    private boolean hasUnsafeFootprintSupport(ServerWorld world, BlockPos center, int radius) {
        if (world == null || center == null) {
            return true;
        }
        int floorY = center.getY();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos foot = new BlockPos(center.getX() + dx, floorY, center.getZ() + dz);
                // If there's fluid at the intended floor height, we're directly in/over water.
                if (!world.getFluidState(foot).isEmpty()) {
                    return true;
                }
                BlockPos support = foot.down();
                // If the support cell is fluid or non-solid (air), this is an overhang/drop/water edge.
                if (!world.getFluidState(support).isEmpty()) {
                    return true;
                }
                if (world.getBlockState(support).getCollisionShape(world, support).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countNaturalWalls(ServerWorld world, BlockPos center, int radius, int wallHeight) {
        int floorY = center.getY();
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                if (!perimeter) continue;
                for (int y = 0; y < wallHeight; y++) {
                    BlockPos pos = new BlockPos(center.getX() + dx, floorY + y, center.getZ() + dz);
                    BlockState state = world.getBlockState(pos);
                    if (!state.isAir() && !state.isReplaceable() && !state.isIn(BlockTags.LEAVES)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private Direction pickDoorSide(ServerWorld world, BlockPos center, int radius, Direction preferred) {
        if (preferred != null && isDoorViable(world, center, radius, preferred)) {
            return preferred;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (isDoorViable(world, center, radius, dir)) {
                return dir;
            }
        }
        return preferred != null ? preferred : Direction.NORTH;
    }

    private boolean isDoorViable(ServerWorld world, BlockPos center, int radius, Direction side) {
        if (world == null || center == null || side == null) {
            return false;
        }
        int floorY = center.getY();
        BlockPos doorLower = center.offset(side, radius).up(1);
        BlockPos doorUpper = doorLower.up(1);
        BlockState lower = world.getBlockState(doorLower);
        BlockState upper = world.getBlockState(doorUpper);
        if ((!lower.isAir() && !lower.isReplaceable() && !lower.isIn(BlockTags.LEAVES)) ||
                (!upper.isAir() && !upper.isReplaceable() && !upper.isIn(BlockTags.LEAVES))) {
            return false;
        }
        BlockPos support = new BlockPos(doorLower.getX(), floorY, doorLower.getZ());
        return !world.getBlockState(support).getCollisionShape(world, support).isEmpty();
    }

    private boolean moveToBuildSite(ServerCommandSource source, ServerPlayerEntity bot, BlockPos center) {
        if (bot == null || center == null) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(center) <= 4.0D) {
            return true;
        }
        var planOpt = MovementService.planLootApproach(bot, center, MovementService.MovementOptions.skillLoot());
        if (planOpt.isEmpty()) {
            return false;
        }
        var res = MovementService.execute(source, bot, planOpt.get(), false, true, false, false);
        return res.success();
    }

    private int estimatePlacementNeed(ServerWorld world, BlockPos center, int radius, int wallHeight, Direction doorSide) {
        int floorY = center.getY();
        int roofY = floorY + wallHeight;
        int needed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                // Floor support is the block *below* the standable foot layer.
                BlockPos support = new BlockPos(center.getX() + dx, floorY - 1, center.getZ() + dz);
                BlockState supportState = world.getBlockState(support);
                if (supportState.isAir() || supportState.isReplaceable() || supportState.isIn(BlockTags.LEAVES) || supportState.isIn(BlockTags.LOGS)) {
                    needed++;
                }
                if (perimeter) {
                    for (int y = floorY + 1; y <= roofY; y++) {
                        BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                        if (isDoorGap(pos, center, radius, doorSide, floorY)) {
                            continue;
                        }
                        BlockState state = world.getBlockState(pos);
                        if (state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS) || state.isOf(Blocks.SNOW)) {
                            needed++;
                        }
                    }
                }
                BlockPos roof = new BlockPos(center.getX() + dx, roofY, center.getZ() + dz);
                if (isDoorGap(roof, center, radius, doorSide, floorY)) {
                    // roof is above door gap; still needs roof, so do not skip
                }
                BlockState roofState = world.getBlockState(roof);
                if (roofState.isAir() || roofState.isReplaceable() || roofState.isIn(BlockTags.LEAVES) || roofState.isIn(BlockTags.LOGS)) {
                    needed++;
                }
            }
        }
        return needed + 8;
    }

    private boolean isDoorGap(BlockPos pos, BlockPos center, int radius, Direction doorSide, int floorY) {
        if (doorSide == null || pos == null || center == null) {
            return false;
        }
        // Door occupies the two blocks the bot walks through (feet block and head block).
        if (pos.getY() != floorY && pos.getY() != floorY + 1) {
            return false;
        }
        BlockPos doorLower = center.offset(doorSide, radius);
        return pos.equals(doorLower) || pos.equals(doorLower.up(1));
    }

    private int estimateBlockNeed(int radius, int wallHeight) {
        int side = radius * 2 + 1;
        int roof = side * side;
        int perimeter = (side * 4 - 4) * wallHeight;
        return roof + perimeter + 10; // small buffer
    }

    private void announceResources(ServerCommandSource source, int needed, int available, boolean willAutoGather) {
        int shortage = Math.max(0, needed - available);
        String msg = "Shelter needs " + needed + " cheap blocks (have " + available + ", short " + shortage + "). "
                + (shortage == 0 ? "Enough materials on hand."
                : willAutoGather ? "Proceeding to collect the shortfall." : "Say 'proceed' in the command to let me gather.");
        LOGGER.info("Shelter preflight: {}", msg);
        ChatUtils.sendSystemMessage(source, msg);
    }

    private int ensureBuildStock(ServerCommandSource source, ServerPlayerEntity bot, int needed, boolean approved, BlockPos returnPos) {
        int available = countBuildBlocks(bot);
        if (!approved || needed <= available) {
            LOGGER.info("Shelter: {} blocks available (need {}); auto-collect approved? {}", available, needed, approved);
            return 0;
        }
        int before = available;
        int toGather = Math.max(0, needed - available);
        if (toGather <= 0) {
            return 0;
        }

        ChatUtils.sendSystemMessage(source,
                "Gathering shelter materials nearby (staying near the build site).");

        try {
            gatherBuildBlocksNearby(source, bot, needed, returnPos, 14);
        } catch (Exception e) {
            LOGGER.warn("Shelter gather errored: {}", e.getMessage());
        }

        int after = countBuildBlocks(bot);
        LOGGER.info("Shelter gather summary: before={} after={} needed={} collected={}", before, after, needed, Math.max(0, after - before));
        return Math.max(0, after - before);
    }

    private void gatherBuildBlocksNearby(ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         int neededBlocks,
                                         BlockPos returnPos,
                                         int maxHorizontalDistance) {
        if (source == null || bot == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        int needed = Math.max(0, neededBlocks - countBuildBlocks(bot));
        if (needed <= 0) {
            return;
        }

        BlockPos anchor = returnPos != null ? returnPos : bot.getBlockPos();
        int anchorY = anchor.getY();
        int gathered = 0;

        int scan = Math.max(6, maxHorizontalDistance);
        for (int dx = -scan; dx <= scan && gathered < needed; dx++) {
            for (int dz = -scan; dz <= scan && gathered < needed; dz++) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    return;
                }
                if (Math.abs(dx) + Math.abs(dz) < 3) {
                    continue; // avoid chewing up the immediate build site
                }
                int x = anchor.getX() + dx;
                int z = anchor.getZ() + dz;
                int topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (Math.abs(topY - anchorY) > 4) {
                    continue;
                }
                BlockPos surfaceBlock = new BlockPos(x, topY - 1, z);
                if (surfaceBlock.getSquaredDistance(anchor) > (double) scan * scan) {
                    continue;
                }
                if (!isNearbyGatherCandidate(world, surfaceBlock)) {
                    continue;
                }
                if (!mineNear(bot, source, surfaceBlock)) {
                    continue;
                }
                gathered++;

                if (gathered % 10 == 0) {
                    ensureBuildChestAndDeposit(source, world, bot, anchor, 10);
                }
            }
        }

        // Return to the build site after gathering.
        if (returnPos != null) {
            var planOpt = MovementService.planLootApproach(bot, returnPos, MovementService.MovementOptions.skillLoot());
            if (planOpt.isPresent()) {
                MovementService.execute(source, bot, planOpt.get(), false, true, false, false);
            }
        }
    }

    private boolean isNearbyGatherCandidate(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable()) {
            return false;
        }
        // Keep gather local and "shallow": prefer shovel-mineable surface blocks.
        if (!state.isIn(BlockTags.SHOVEL_MINEABLE)) {
            return false;
        }
        if (state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) {
            return false;
        }
        BlockState above = world.getBlockState(pos.up());
        return above.isAir() || above.isReplaceable();
    }

    private boolean mineNear(ServerPlayerEntity bot, ServerCommandSource source, BlockPos target) {
        if (bot == null || source == null || target == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        // Never mine the support block under the bot.
        BlockPos foot = bot.getBlockPos();
        if (target.equals(foot) || target.equals(foot.down())) {
            return false;
        }
        if (!ensureMiningReach(bot, source, target)) {
            return false;
        }
        BotActions.selectBestTool(bot, "shovel", "pickaxe");
        mineSoft(bot, target);
        return world.getBlockState(target).isAir() || world.getBlockState(target).isReplaceable();
    }

    private boolean ensureMiningReach(ServerPlayerEntity bot, ServerCommandSource source, BlockPos target) {
        Vec3d center = Vec3d.ofCenter(target);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double maxReachSq = 20.25D;
        if (botPos.squaredDistanceTo(center) <= maxReachSq) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        List<BlockPos> candidates = findStandableCandidatesNear(world, target, 4, 2, maxReachSq, bot.getBlockPos(), 6);
        for (BlockPos stand : candidates) {
            if (stand == null) continue;
            var planOpt = MovementService.planLootApproach(bot, stand, MovementService.MovementOptions.skillLoot());
            if (planOpt.isEmpty()) continue;
            var res = MovementService.execute(source, bot, planOpt.get(), false, true, false, false);
            if (!res.success()) continue;
            Vec3d newPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
            if (newPos.squaredDistanceTo(center) <= maxReachSq) {
                net.shasankp000.Entity.LookController.faceBlock(bot, target);
                return true;
            }
        }
        return false;
    }

    private void clearObstructiveVegetation(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius, int wallHeight) {
        if (world == null || bot == null || center == null) {
            return;
        }
        int floorY = center.getY();
        int topY = floorY + wallHeight + 2;
        int scanRadius = radius + 1;

        // Avoid griefing: if we're near human builds, do not aggressively clear logs.
        boolean nearHuman = TreeDetector.isNearHumanBlocks(world, center, radius + 3);

        for (BlockPos pos : BlockPos.iterate(center.add(-scanRadius, 0, -scanRadius), center.add(scanRadius, topY - floorY, scanRadius))) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            BlockPos p = pos.toImmutable();
            BlockState state = world.getBlockState(p);
            if (state.isAir()) {
                continue;
            }
            if (state.isIn(BlockTags.LEAVES)) {
                // Use woodcut-like behavior: prefer shears and avoid burning tool durability on axes.
                BotActions.selectBestTool(bot, "shears", "axe");
                mineSoft(bot, p);
                continue;
            }
            if (!nearHuman && state.isIn(BlockTags.LOGS)) {
                // Clear trunks only when we're not near player builds.
                BotActions.selectBestTool(bot, "axe", "pickaxe");
                mineSoft(bot, p);
            }
        }
    }

    private boolean tryReachSurface(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world, BlockPos origin) {
        if (source == null || bot == null || world == null || origin == null) {
            return false;
        }
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        if (bot.getBlockY() >= surfaceY - 2) {
            return false;
        }
        ChatUtils.sendSystemMessage(source, "I'm underground; climbing toward the surface before building...");

        // Prefer a direct pillar escape if we're in a vertical shaft with headroom.
        if (tryPillarUpToSurface(bot, world, surfaceY)) {
            return bot.getBlockY() >= surfaceY - 1;
        }

        CollectDirtSkill stair = new CollectDirtSkill();
        Map<String, Object> shared = new HashMap<>();
        Direction digDir = WorkDirectionService.getDirection(bot.getUuid()).orElse(bot.getHorizontalFacing());
        int attempts = 0;
        while (bot.getBlockY() < surfaceY - 1 && attempts < 10) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }
            // Stop as soon as we're near-surface at our current X/Z (prevents long horizontal wander).
            int currentSurfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, bot.getBlockX(), bot.getBlockZ());
            if (bot.getBlockY() >= currentSurfaceY - 1) {
                LOGGER.info("Surface escape: reached near-surface at current XZ (botY={} currentSurfaceY={})", bot.getBlockY(), currentSurfaceY);
                return true;
            }
            WorkDirectionService.setDirection(bot.getUuid(), digDir);
            Map<String, Object> ascentParams = new HashMap<>();
            int stepTargetY = Math.min(surfaceY, bot.getBlockY() + 10);
            ascentParams.put("ascentTargetY", stepTargetY);
            ascentParams.put("issuerFacing", digDir.asString());
            ascentParams.put("lockDirection", true);
            ascentParams.put("strictWalk", true);
            int beforeY = bot.getBlockY();
            SkillExecutionResult ascent = stair.execute(new SkillContext(source, shared, ascentParams));
            if (bot.getBlockY() >= surfaceY - 1) {
                return true;
            }

            int afterY = bot.getBlockY();
            boolean progressed = afterY > beforeY;
            if (!ascent.success()) {
                LOGGER.warn("Surface escape ascent step failed (progressed={}): {}", progressed, ascent.message());
            }
            if (!progressed) {
                StripMineSkill strip = new StripMineSkill();
                Map<String, Object> stripParams = new HashMap<>();
                stripParams.put("count", 6);
                stripParams.put("issuerFacing", digDir.asString());
                stripParams.put("lockDirection", true);
                SkillExecutionResult stripRes = strip.execute(new SkillContext(source, shared, stripParams));
                if (!stripRes.success()) {
                    LOGGER.warn("Surface escape stripmine failed: {}", stripRes.message());
                    break;
                }
            }
            attempts++;
        }
        return bot.getBlockY() >= surfaceY - 1;
    }

    private boolean tryPillarUpToSurface(ServerPlayerEntity bot, ServerWorld world, int surfaceY) {
        if (bot == null || world == null) {
            return false;
        }
        // Quick check: ensure we have enough vertical clearance to make pillaring plausible.
        BlockPos start = bot.getBlockPos();
        int targetY = Math.max(start.getY() + 1, surfaceY - 1);
        int shaftCheck = Math.min(16, Math.max(0, targetY - start.getY()));
        int headroom = countOpenAbove(world, start, shaftCheck);
        // Only pillar if this looks like a real shaft (lots of open air above).
        if (headroom < Math.min(8, shaftCheck)) {
            return false;
        }
        if (!hasAnyScaffoldBlock(bot)) {
            return false;
        }
        int maxSteps = Math.min(64, Math.max(0, targetY - start.getY()));
        if (maxSteps <= 0) {
            return false;
        }

        LOGGER.info("Surface escape: attempting pillar climb {} steps from Y={} -> targetY={}", maxSteps, start.getY(), targetY);
        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        int stalls = 0;
        for (int i = 0; i < maxSteps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                bot.setSneaking(wasSneaking);
                return false;
            }
            if (bot.getBlockY() >= targetY) {
                bot.setSneaking(wasSneaking);
                return true;
            }
            BlockPos feet = bot.getBlockPos();
            // Need at least 2 blocks of air at/above feet to jump-pillar.
            if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) {
                bot.setSneaking(wasSneaking);
                return false;
            }
            if (!world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                    || !world.getBlockState(feet.up(2)).getCollisionShape(world, feet.up(2)).isEmpty()) {
                bot.setSneaking(wasSneaking);
                return false;
            }
            int beforeY = bot.getBlockY();
            int baseY = bot.getBlockY();
            BotActions.jump(bot);
            sleepQuiet(160L);
            // Place into the bot's current foot block *after jumping* (woodcut-style jump-pillaring).
            BlockPos placeAt = bot.getBlockPos();
            // If our blockPos advanced during the jump, place at the previous cell underfoot to avoid sealing ourselves.
            if (placeAt.getY() > baseY) {
                placeAt = new BlockPos(placeAt.getX(), baseY, placeAt.getZ());
            }
            if (!world.getBlockState(placeAt).isAir()) {
                // Don't try to place above our head; if the intended cell is occupied, abort pillar escape.
                bot.setSneaking(wasSneaking);
                return false;
            }
            boolean placed = BotActions.placeBlockAt(bot, placeAt, Direction.UP, SCAFFOLD_BLOCKS);
            sleepQuiet(160L);
            if (!placed) {
                bot.setSneaking(wasSneaking);
                return false;
            }
            int afterY = bot.getBlockY();
            if (afterY <= beforeY) {
                stalls++;
                if (stalls >= 3) {
                    bot.setSneaking(wasSneaking);
                    return false;
                }
            } else {
                stalls = 0;
            }
        }
        bot.setSneaking(wasSneaking);
        return bot.getBlockY() >= targetY;
    }

    private int countOpenAbove(ServerWorld world, BlockPos start, int max) {
        int open = 0;
        for (int i = 1; i <= max; i++) {
            BlockPos pos = start.up(i);
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                break;
            }
            open++;
        }
        return open;
    }

    private boolean hasAnyScaffoldBlock(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (SCAFFOLD_BLOCKS.contains(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    private boolean tryStepOutOfWater(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world) {
        if (source == null || bot == null || world == null) {
            return false;
        }
        BlockPos origin = bot.getBlockPos();
        boolean inWater = !world.getFluidState(origin).isEmpty()
                || !world.getFluidState(origin.down()).isEmpty()
                || !world.getFluidState(origin.up()).isEmpty();
        if (!inWater) {
            return false;
        }

        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        int radius = 10;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockPos foot = pos.toImmutable();
            if (!isStandable(world, foot)) {
                continue;
            }
            if (!world.getFluidState(foot).isEmpty() || !world.getFluidState(foot.down()).isEmpty()) {
                continue;
            }
            double sq = origin.getSquaredDistance(foot);
            if (sq < bestSq) {
                bestSq = sq;
                best = foot;
            }
        }
        if (best == null) {
            return false;
        }
        var planOpt = MovementService.planLootApproach(bot, best, MovementService.MovementOptions.skillLoot());
        if (planOpt.isEmpty()) {
            return false;
        }
        MovementService.MovementResult res = MovementService.execute(source, bot, planOpt.get(), false, true, false, false);
        return res.success();
    }

    private boolean tryRelocateToNearbySurfaceOpening(ServerCommandSource source,
                                                      ServerPlayerEntity bot,
                                                      ServerWorld world,
                                                      BlockPos origin,
                                                      int radius) {
        if (source == null || bot == null || world == null || origin == null) {
            return false;
        }
        // If we're already sky-visible, there's nothing to do.
        if (world.isSkyVisible(origin.up())) {
            return false;
        }
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockPos foot = pos.toImmutable();
            if (!world.isSkyVisible(foot.up())) {
                continue;
            }
            if (!isStandable(world, foot)) {
                continue;
            }
            if (!world.getFluidState(foot).isEmpty() || !world.getFluidState(foot.down()).isEmpty()) {
                continue;
            }
            double sq = origin.getSquaredDistance(foot);
            if (sq < bestSq) {
                bestSq = sq;
                best = foot;
            }
        }
        if (best == null) {
            return false;
        }
        var planOpt = MovementService.planLootApproach(bot, best, MovementService.MovementOptions.skillLoot());
        if (planOpt.isEmpty()) {
            return false;
        }
        MovementService.MovementResult res = MovementService.execute(source, bot, planOpt.get(), false, true, false, false);
        return res.success();
    }

    /**
     * Heuristic: if we're under a "flat-ish" roof made of common build materials, treat this as being indoors,
     * not underground. This prevents surface-ascent mining from triggering when the bot is inside a completed hovel.
     */
    private boolean isLikelyUnderArtificialRoof(ServerWorld world, BlockPos origin, int maxScanUp) {
        if (world == null || origin == null) {
            return false;
        }
        int scan = Math.max(2, Math.min(12, maxScanUp));
        BlockPos roof = null;
        BlockState roofState = null;
        for (int dy = 1; dy <= scan; dy++) {
            BlockPos pos = origin.up(dy);
            BlockState state = world.getBlockState(pos);
            if (state.getCollisionShape(world, pos).isEmpty()) {
                continue;
            }
            roof = pos;
            roofState = state;
            break;
        }
        if (roof == null || roofState == null) {
            return false;
        }
        if (!isLikelyRoofMaterial(roofState)) {
            return false;
        }
        // Count nearby matching blocks at the roof Y to confirm it's a roof plane, not a single block.
        int count = 0;
        int y = roof.getY();
        int r = 2;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos pos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                BlockState state = world.getBlockState(pos);
                if (state.getCollisionShape(world, pos).isEmpty()) {
                    continue;
                }
                if (isLikelyRoofMaterial(state)) {
                    count++;
                }
            }
        }
        return count >= 6;
    }

    private boolean isLikelyRoofMaterial(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.isIn(BlockTags.PLANKS)) return true;
        if (state.isOf(Blocks.DIRT) || state.isOf(Blocks.COARSE_DIRT) || state.isOf(Blocks.ROOTED_DIRT)) return true;
        if (state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.COBBLED_DEEPSLATE)) return true;
        if (state.isOf(Blocks.ANDESITE) || state.isOf(Blocks.DIORITE) || state.isOf(Blocks.GRANITE)) return true;
        if (state.isOf(Blocks.SANDSTONE) || state.isOf(Blocks.RED_SANDSTONE)) return true;
        if (state.isOf(Blocks.NETHERRACK)) return true;
        return false;
    }

    private void ensureClearBuildSiteWithWoodcut(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world, BlockPos center, int radius) {
        if (source == null || bot == null || world == null || center == null) {
            return;
        }
        int density = countVegetationInShell(world, center, radius, 5);
        if (density < 18) {
            return;
        }
        ChatUtils.sendSystemMessage(source, "Build site is too dense with trees; clearing space with woodcut...");
        WoodcutSkill woodcut = new WoodcutSkill();
        Map<String, Object> params = new HashMap<>();
        params.put("count", 2);
        params.put("searchRadius", Math.max(10, radius + 8));
        params.put("verticalRange", 8);
        woodcut.execute(new SkillContext(source, new HashMap<>(), params));
    }

    private void ensureDoorwayOpen(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius, Direction doorSide) {
        if (world == null || bot == null || center == null || doorSide == null) {
            return;
        }
        BlockPos doorLower = center.offset(doorSide, radius);
        BlockPos doorUpper = doorLower.up(1);
        clearDoorSoft(world, bot, doorLower);
        clearDoorSoft(world, bot, doorUpper);
    }

    private void ensureBuildChestAndDeposit(ServerCommandSource source, ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius) {
        if (source == null || world == null || bot == null || center == null) {
            return;
        }
        int emptySlots = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                emptySlots++;
                if (emptySlots >= 3) {
                    break;
                }
            }
        }
        if (emptySlots >= 3) {
            return;
        }
        ChatUtils.sendSystemMessage(source, "Inventory nearly full; stashing junk items into a nearby chest.");
        BlockPos chestPos = findChestNear(world, center, Math.max(6, radius));
        if (chestPos == null) {
            // Try placing/crafting a chest inside the planned interior.
            if (!hasItem(bot, Items.CHEST)) {
                CraftingHelper.craftGeneric(source, bot, source.getPlayer(), "chest", 1, null);
            }
            if (hasItem(bot, Items.CHEST)) {
                List<BlockPos> candidates = chestPlacementOptions(center);
                for (BlockPos placement : candidates) {
                    if (!world.getBlockState(placement).isAir()) continue;
                    if (!world.getBlockState(placement.down()).isSolidBlock(world, placement.down())) continue;
                    if (!ensureReach(bot, placement)) continue;
                    BotActions.placeBlockAt(bot, placement, Direction.UP, List.of(Items.CHEST));
                    if (world.getBlockState(placement).isOf(Blocks.CHEST) || world.getBlockState(placement).isOf(Blocks.TRAPPED_CHEST)) {
                        chestPos = placement.toImmutable();
                        break;
                    }
                }
            }
        }
        if (chestPos == null) {
            LOGGER.warn("Shelter: inventory full, but no chest available/placable for dumping.");
            return;
        }

        int deposited = ChestStoreService.depositMatchingWalkOnly(source, bot, chestPos, stack -> shouldDepositDuringBuild(stack));
        if (deposited > 0) {
            LOGGER.info("Shelter: deposited {} items into chest {}", deposited, chestPos.toShortString());
            ChatUtils.sendSystemMessage(source, "Deposited " + deposited + " items into the chest.");
        } else {
            LOGGER.info("Shelter: inventory near-full but nothing matched deposit filter.");
        }
    }

    private boolean shouldDepositDuringBuild(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();

        // Never dump tools/gear/ammo.
        String key = item.getTranslationKey().toLowerCase(Locale.ROOT);
        if (key.contains("sword")
                || key.contains("pickaxe")
                || key.contains("axe")
                || key.contains("shovel")
                || key.contains("hoe")
                || key.contains("bow")
                || key.contains("crossbow")
                || key.contains("trident")
                || key.contains("shield")
                || key.contains("helmet")
                || key.contains("chestplate")
                || key.contains("leggings")
                || key.contains("boots")
                || key.contains("arrow")) {
            return false;
        }

        // Leaf litter / woodcut byproducts.
        if (item == Items.STICK) return true;
        if (item == Items.APPLE) return true;
        if (item == Items.OAK_SAPLING || item == Items.SPRUCE_SAPLING || item == Items.BIRCH_SAPLING
                || item == Items.JUNGLE_SAPLING || item == Items.ACACIA_SAPLING || item == Items.DARK_OAK_SAPLING
                || item == Items.MANGROVE_PROPAGULE || item == Items.CHERRY_SAPLING || item == Items.BAMBOO) {
            return true;
        }

        // Common mob drops / clutter.
        if (item == Items.ROTTEN_FLESH) return true;
        if (item == Items.BONE) return true;
        if (item == Items.STRING) return true;
        if (item == Items.SPIDER_EYE) return true;
        if (item == Items.GUNPOWDER) return true;
        if (item == Items.FEATHER) return true;
        if (item == Items.LEATHER) return true;

        // Raw ores (early game clutter; keep refined ingots/tools instead).
        if (item == Items.RAW_IRON || item == Items.RAW_COPPER || item == Items.RAW_GOLD) return true;

        // Keep cheap blocks needed for the shelter itself.
        return false;
    }

    private void levelInterior(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius) {
        int floorY = center.getY();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pos = center.add(dx, 0, dz);
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                for (int y = floorY + 1; y <= floorY + 3; y++) {
                    BlockPos target = new BlockPos(pos.getX(), y, pos.getZ());
                    BlockState state = world.getBlockState(target);
                    if (state.isAir()) continue;
                    if (state.isIn(BlockTags.LEAVES) || state.isReplaceable() || state.isOf(Blocks.SNOW) || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) {
                        mineSoft(bot, target);
                    }
                }
                // Flatten interior bumps (avoid breaking chests/doors/etc; perimeter walls are handled elsewhere).
                if (!perimeter) {
                    BlockPos bump = new BlockPos(pos.getX(), floorY + 1, pos.getZ());
                    BlockState bumpState = world.getBlockState(bump);
                    if (!bumpState.isAir()
                            && bumpState.getCollisionShape(world, bump).isEmpty() == false
                            && !bumpState.isIn(BlockTags.DOORS)
                            && !bumpState.isIn(BlockTags.BEDS)
                            && !bumpState.isIn(BlockTags.SHULKER_BOXES)
                            && !bumpState.isOf(Blocks.CHEST)
                            && !bumpState.isOf(Blocks.TRAPPED_CHEST)
                            && !bumpState.isOf(Blocks.BARREL)
                            && !bumpState.isOf(Blocks.ENDER_CHEST)
                            && !bumpState.isOf(Blocks.CRAFTING_TABLE)
                            && !bumpState.isOf(Blocks.FURNACE)
                            && !bumpState.isOf(Blocks.BLAST_FURNACE)
                            && !bumpState.isOf(Blocks.SMOKER)) {
                        mineSoft(bot, bump);
                    }
                }
                // Only fill holes in the support layer; do not build a "raised floor" in the standable air layer.
                BlockPos support = pos.down();
                BlockState supportState = world.getBlockState(support);
                if (supportState.isAir() || supportState.isReplaceable() || supportState.isIn(BlockTags.LEAVES) || supportState.isIn(BlockTags.LOGS) || supportState.isOf(Blocks.SNOW)) {
                    placeBlock(bot, support);
                }
            }
        }
    }

    private void buildHovel(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius, int wallHeight, Direction doorSide, BuildCounters counters) {
        int floorY = center.getY();
        int roofY = floorY + wallHeight;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos column = center.add(dx, 0, dz);
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                // Clear interior space
                if (!perimeter) {
                    clearColumn(world, bot, column, floorY, roofY - 1);
                }
                // Ensure floor support
                BlockPos support = new BlockPos(column.getX(), floorY - 1, column.getZ());
                BlockState supportState = world.getBlockState(support);
                if (supportState.isAir() || supportState.isReplaceable() || supportState.isIn(BlockTags.LEAVES) || supportState.isIn(BlockTags.LOGS) || supportState.isOf(Blocks.SNOW)) {
                    placeBlock(bot, support, counters);
                }
                // Walls
                if (perimeter) {
                    for (int y = floorY; y < roofY; y++) {
                        BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                        if (isDoorGap(pos, center, radius, doorSide, floorY)) {
                            clearDoorSoft(world, bot, pos);
                            continue;
                        }
                        BlockState state = world.getBlockState(pos);
                        if (!state.isAir()
                                && !state.isReplaceable()
                                && !state.isIn(BlockTags.LEAVES)
                                && !state.isIn(BlockTags.LOGS)) {
                            continue; // terrain wall is fine
                        }
                        placeBlock(bot, pos, counters);
                    }
                }
            }
        }
    }

    /**
     * Places the roof from perimeter -> center so each new roof block has a neighbor support (or perimeter wall below).
     * This avoids many "no support" placement failures when starting roof placement on interior cells.
     */
    private void buildRoof(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius, int wallHeight, BuildCounters counters) {
        if (world == null || bot == null || center == null) {
            return;
        }
        int roofY = center.getY() + wallHeight;
        for (int ring = radius; ring >= 0; ring--) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos roof = new BlockPos(center.getX() + dx, roofY, center.getZ() + dz);
                    BlockState roofState = world.getBlockState(roof);
                    if (roofState.isAir() || roofState.isReplaceable() || roofState.isIn(BlockTags.LEAVES) || roofState.isIn(BlockTags.LOGS)) {
                        placeBlock(bot, roof, counters);
                    }
                }
            }
        }
    }

    private void patchGaps(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius, int wallHeight, Direction doorSide, BuildCounters counters) {
        int floorY = center.getY();
        int roofY = floorY + wallHeight;
        int upperStartY = floorY + 4;
        int roofHoles = 0;
        int wallHoles = 0;
        // Roof patch from perimeter -> center so support exists.
        for (int ring = radius; ring >= 0; ring--) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos roof = center.add(dx, wallHeight, dz);
                    BlockState roofState = world.getBlockState(roof);
                    if (roofState.isAir() || roofState.isReplaceable() || roofState.isIn(BlockTags.LEAVES) || roofState.isIn(BlockTags.LOGS)) {
                        boolean placed = roof.getY() >= upperStartY
                                ? placeBlockDirectIfWithinReach(bot, roof, counters)
                                : placeBlock(bot, roof, counters);
                        if (placed) {
                            roofHoles++;
                        }
                    }
                }
            }
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                if (!perimeter) {
                    continue;
                }
                for (int y = floorY; y < roofY; y++) {
                    BlockPos pos = center.add(dx, y - floorY, dz);
                    if (isDoorGap(pos, center, radius, doorSide, floorY)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) {
                        boolean placed = pos.getY() >= upperStartY
                                ? placeBlockDirectIfWithinReach(bot, pos, counters)
                                : placeBlock(bot, pos, counters);
                        if (placed) {
                            wallHoles++;
                        }
                    }
                }
            }
        }
        LOGGER.info("Shelter patch summary: roofHolesFilled={} wallHolesFilled={}", roofHoles, wallHoles);
    }

    private void clearDoorSoft(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        if (world == null || bot == null || pos == null) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        if (state.isIn(BlockTags.LEAVES) || state.isReplaceable() || state.isOf(Blocks.SNOW) || isLikelyRoofMaterial(state)) {
            mineSoft(bot, pos);
        }
    }

    private void placeDoor(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius, Direction doorSide) {
        if (world == null || bot == null || center == null || doorSide == null) {
            return;
        }
        Item doorItem = selectDoorItem(bot);
        if (doorItem == null) {
            return;
        }
        int floorY = center.getY();
        BlockPos doorLower = center.offset(doorSide, radius);
        BlockPos doorUpper = doorLower.up(1);
        clearDoorSoft(world, bot, doorLower);
        clearDoorSoft(world, bot, doorUpper);
        if (!ensureReach(bot, doorLower)) {
            return;
        }
        if (world.getBlockState(doorLower).isAir() && world.getBlockState(doorUpper).isAir()) {
            BotActions.placeBlockAt(bot, doorLower, Direction.UP, List.of(doorItem));
        }
    }

    private Item selectDoorItem(ServerPlayerEntity bot) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (DOOR_ITEMS.contains(stack.getItem())) {
                return stack.getItem();
            }
        }
        return null;
    }

    private void placeChest(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius) {
        if (world == null || bot == null || center == null) {
            return;
        }
        if (!hasItem(bot, Items.CHEST)) {
            return;
        }
        int floorY = center.getY();
        BlockPos placement = center.add(-radius + 1, 1, -radius + 1);
        BlockPos floor = new BlockPos(placement.getX(), floorY, placement.getZ());
        if (world.getBlockState(floor).getCollisionShape(world, floor).isEmpty()) {
            return;
        }
        BlockState state = world.getBlockState(placement);
        if (!state.isAir() && !state.isReplaceable()) {
            return;
        }
        if (!ensureReach(bot, placement)) {
            return;
        }
        BotActions.placeBlockAt(bot, placement, Direction.UP, List.of(Items.CHEST));
    }

    private void clearColumn(ServerWorld world, ServerPlayerEntity bot, BlockPos column, int floorY, int topY) {
        for (int y = floorY; y <= topY; y++) {
            BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.isIn(BlockTags.LEAVES) || state.isReplaceable() || state.isOf(Blocks.SNOW) || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) {
                mineSoft(bot, pos);
            }
        }
    }

    private void placeTorches(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius) {
        List<BlockPos> spots = new ArrayList<>();
        spots.add(center.add(radius - 1, 1, radius - 1));
        spots.add(center.add(-radius + 1, 1, -radius + 1));
        for (BlockPos pos : spots) {
            BlockPos floor = pos.down();
            if (!world.getBlockState(floor).isSolidBlock(world, floor)) {
                continue;
            }
            if (world.getBlockState(pos).isAir()) {
                BotActions.placeBlockAt(bot, pos, Direction.UP, List.of(Items.TORCH));
            }
        }
    }

    private boolean placeBlock(ServerPlayerEntity bot, BlockPos pos, BuildCounters counters) {
        if (yieldToImmediateThreats(bot, 2_000L)) {
            return false;
        }
        Item blockItem = selectBuildItem(bot);
        if (blockItem == null) {
            if (counters != null) {
                counters.noMaterials++;
            }
            return false;
        }
        if (counters != null) {
            counters.attemptedPlacements++;
        }
        BlockState state = bot.getEntityWorld().getBlockState(pos);
        if (state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW)) {
            mineSoft(bot, pos);
        } else if (state.isIn(BlockTags.LOGS)) {
            if (bot.getEntityWorld() instanceof ServerWorld world && !TreeDetector.isNearHumanBlocks(world, pos, 3)) {
                mineSoft(bot, pos);
            }
        }
        if (!ensureReach(bot, pos)) {
            LOGGER.warn("Shelter: could not reach {} for placement", pos.toShortString());
            if (counters != null) {
                counters.reachFailures++;
            }
            return false;
        }
        boolean placed = BotActions.placeBlockAt(bot, pos, Direction.UP, List.of(blockItem));
        if (placed && counters != null) {
            counters.placedBlocks++;
        }
        return placed;
    }

    private boolean placeBlock(ServerPlayerEntity bot, BlockPos pos) {
        return placeBlock(bot, pos, null);
    }

    private Item selectBuildItem(ServerPlayerEntity bot) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (BUILD_BLOCKS.contains(stack.getItem())) {
                return stack.getItem();
            }
        }
        return null;
    }

    private String getOption(SkillContext context, String defaultVal) {
        Object options = context.parameters().get("options");
        if (options instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first != null) {
                return first.toString();
            }
        }
        return defaultVal;
    }

    private int getInt(SkillContext context, String key, int fallback) {
        Object value = context.parameters().get(key);
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private int countBuildBlocks(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (BUILD_BLOCKS.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countTorches(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == Items.TORCH) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void sweepDrops(ServerCommandSource source, double radius, double vRange, int maxTargets, long durationMs) {
        try {
            DropSweeper.sweep(source.withSilent().withMaxLevel(4), radius, vRange, maxTargets, durationMs);
            DropSweeper.sweep(source.withSilent().withMaxLevel(4), radius + 2.0, vRange + 1.0, Math.max(maxTargets, 40), durationMs + 3000); // second, wider pass
        } catch (Exception e) {
            LOGGER.warn("Shelter drop-sweep failed: {}", e.getMessage());
        }
    }

    private boolean hasOption(SkillContext context, String... names) {
        Object opts = context.parameters().get("options");
        if (!(opts instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        for (Object val : list) {
            if (val == null) continue;
            String opt = val.toString().toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (opt.equals(name.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void mineSoft(ServerPlayerEntity bot, BlockPos pos) {
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        if (yieldToImmediateThreats(bot, 2_000L)) {
            return;
        }
        try {
            MiningTool.mineBlock(bot, pos).get(3_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.warn("Shelter: failed to clear {}: {}", pos.toShortString(), e.getMessage());
        }
    }

    private SkillExecutionResult buildBurrow(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world, BlockPos start) {
        // Require at least 2 torches before starting; avoid mid-run pauses.
        int torches = countTorches(bot);
        if (torches < 2) {
            String msg = "Burrow needs at least 2 torches; provide some and rerun.";
            ChatUtils.sendSystemMessage(source, msg);
            LOGGER.warn("Burrow aborted: {}", msg);
            return SkillExecutionResult.failure(msg);
        }

        // Phase 1: descend 5 blocks using the standard stair descent.
        Direction burrowDir = WorkDirectionService.getDirection(bot.getUuid()).orElse(bot.getHorizontalFacing());
        WorkDirectionService.setDirection(bot.getUuid(), burrowDir);
        faceDirection(bot, burrowDir);
        Map<String, Object> sharedState = new HashMap<>();
        CollectDirtSkill collect = new CollectDirtSkill();
        Map<String, Object> params = new HashMap<>();
        params.put("descentBlocks", 5);
        params.put("issuerFacing", burrowDir.asString());
        params.put("lockDirection", true);
        SkillContext descentCtx = new SkillContext(source, sharedState, params);
        SkillExecutionResult descentResult = collect.execute(descentCtx);
        if (!descentResult.success()) {
            LOGGER.warn("Burrow descent(6) failed: {}", descentResult.message());
            ChatUtils.sendSystemMessage(source, "Burrow paused: " + descentResult.message());
            return descentResult;
        }

        // Phase 2: stripmine forward 4 blocks at this level to create entry throat.
        LOGGER.info("Burrow stripmine throat: direction={} length=4 start={}", burrowDir, bot.getBlockPos().toShortString());
        WorkDirectionService.setDirection(bot.getUuid(), burrowDir);
        faceDirection(bot, burrowDir);
        StripMineSkill strip = new StripMineSkill();
        Map<String, Object> stripParams = new HashMap<>();
        stripParams.put("count", 4);
        stripParams.put("issuerFacing", burrowDir.asString());
        stripParams.put("lockDirection", true);
        SkillExecutionResult stripResult = strip.execute(new SkillContext(source, sharedState, stripParams));
        if (!stripResult.success()) {
            LOGGER.warn("Burrow stripmine throat failed: {}", stripResult.message());
            return stripResult;
        }

        // Phase 3: descend final 3 blocks to the chamber depth.
        Map<String, Object> params2 = new HashMap<>();
        params2.put("descentBlocks", 3);
        params2.put("issuerFacing", burrowDir.asString());
        params2.put("lockDirection", true);
        SkillContext descentCtx2 = new SkillContext(source, sharedState, params2);
        LOGGER.info("Burrow final descent: 3 blocks from {}", bot.getBlockPos().toShortString());
        WorkDirectionService.setDirection(bot.getUuid(), burrowDir);
        faceDirection(bot, burrowDir);
        SkillExecutionResult descentResult2 = collect.execute(descentCtx2);
        if (!descentResult2.success()) {
            LOGGER.warn("Burrow descent(3) failed: {}", descentResult2.message());
            ChatUtils.sendSystemMessage(source, "Burrow paused: " + descentResult2.message());
            return descentResult2;
        }

        BlockPos chamberCenter = bot.getBlockPos();
        Direction ascentDir = resolveAscentDirection(start, chamberCenter);
        Set<BlockPos> protectedSteps = computeProtectedSteps(chamberCenter, ascentDir, 5);
        int radius = 3;
        int height = 3;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 0; dy < height; dy++) {
                    BlockPos pos = chamberCenter.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (protectedSteps.contains(pos)) {
                        continue; // keep the stair spine intact
                    }
                    if (!state.isAir()) {
                        mineSoft(bot, pos);
                    }
                }
            }
        }
        List<BlockPos> torchPositions = new ArrayList<>();
        torchPositions.add(chamberCenter.add(radius, 0, radius));
        torchPositions.add(chamberCenter.add(-radius, 0, -radius));
        torchPositions.add(chamberCenter.add(radius, 0, -radius));
        torchPositions.add(chamberCenter.add(-radius, 0, radius));
        for (BlockPos pos : torchPositions) {
            if (world.getBlockState(pos.down()).isSolidBlock(world, pos.down()) && world.getBlockState(pos).isAir()) {
                BotActions.placeBlockAt(bot, pos, Direction.UP, List.of(Items.TORCH));
            }
        }
        smoothStairExit(world, bot, chamberCenter, ascentDir, 6);
        return SkillExecutionResult.success("Burrow finished.");
    }

    private boolean ensureReach(ServerPlayerEntity bot, BlockPos target) {
        return ensureReach(bot, target, ACTIVE_BUILD_SITE.get());
    }

    private boolean ensureReach(ServerPlayerEntity bot, BlockPos target, ActiveBuildSite buildSite) {
        if (bot == null || target == null) {
            return false;
        }
        if (yieldToImmediateThreats(bot, 3_500L)) {
            return false;
        }
        Vec3d center = Vec3d.ofCenter(target);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double maxReachSq = 20.25D; // ~4.5 blocks (survival reach)
        if (botPos.squaredDistanceTo(center) <= maxReachSq) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        ServerCommandSource source = bot.getCommandSource();
        if (buildSite != null) {
            maybeRecenterToBuildSite(source, bot, world, buildSite);
        }
        List<BlockPos> candidates = findStandableCandidatesNear(world, target, 5, 3, maxReachSq, bot.getBlockPos(), 8);
        if (candidates.isEmpty()) {
            LOGGER.warn("Shelter: no standable spot near {} for placement", target.toShortString());
            return false;
        }

        if (tryCandidatesForReach(source, bot, target, candidates, maxReachSq)) {
            return true;
        }

        // If we're repeatedly blocked inside a structure footprint, try to explicitly exit and retry.
        if (buildSite != null && tryExitBuildFootprint(source, bot, world, buildSite)) {
            candidates = findStandableCandidatesNear(world, target, 5, 3, maxReachSq, bot.getBlockPos(), 8);
            if (tryCandidatesForReach(source, bot, target, candidates, maxReachSq)) {
                return true;
            }
        }

        LOGGER.warn("Shelter: could not approach within reach of {} after trying {} stand positions", target.toShortString(), candidates.size());
        return false;
    }

    private boolean maybeRecenterToBuildSite(ServerCommandSource source,
                                            ServerPlayerEntity bot,
                                            ServerWorld world,
                                            ActiveBuildSite buildSite) {
        if (source == null || bot == null || world == null || buildSite == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long last = LAST_RECENTER_MS.get() == null ? 0L : LAST_RECENTER_MS.get();
        if (now - last < 2_500L) {
            return false;
        }
        BlockPos center = buildSite.center();
        int radius = buildSite.radius();
        double maxDistSq = (radius + 10.0D) * (radius + 10.0D);
        if (bot.getBlockPos().getSquaredDistance(center) <= maxDistSq) {
            return false;
        }

        BlockPos stand = findStandableInside(world, center, radius, center, 3);
        if (stand == null) {
            // Try just outside the footprint (helps when inside is cluttered from combat).
            stand = findStandableCandidatesNear(world, center, radius + 3, 2, 1600.0D, bot.getBlockPos(), 6)
                    .stream()
                    .findFirst()
                    .orElse(null);
        }
        if (stand == null) {
            return false;
        }

        LAST_RECENTER_MS.set(now);
        if (yieldToImmediateThreats(bot, 2_500L)) {
            return false;
        }
        var planOpt = MovementService.planLootApproach(bot, stand, MovementService.MovementOptions.skillLoot());
        if (planOpt.isEmpty()) {
            return false;
        }
        MovementService.MovementResult res = MovementService.execute(source, bot, planOpt.get(), false, true, false, false);
        if (!res.success()) {
            return false;
        }
        return true;
    }

    /**
     * While building, if hostiles are nearby, temporarily yield so the bot can defend itself.
     * This avoids "hopping around" from shelter movement fighting combat movement.
     *
     * @return true if hostiles are still present after waiting (caller should skip its action for now).
     */
    private boolean yieldToImmediateThreats(ServerPlayerEntity bot, long maxWaitMs) {
        if (bot == null) {
            return false;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long last = LAST_COMBAT_YIELD_MS.get() == null ? 0L : LAST_COMBAT_YIELD_MS.get();
        // Throttle so multiple placement attempts in the same tick don't spam scans/sleeps.
        if (now - last < 250L) {
            return false;
        }

        List<Entity> hostiles = AutoFaceEntity.detectNearbyEntities(bot, 10.0D)
                .stream()
                .filter(EntityUtil::isHostile)
                .toList();
        if (hostiles.isEmpty()) {
            return false;
        }

        LAST_COMBAT_YIELD_MS.set(now);
        BotEventHandler.engageImmediateThreats(bot);

        long deadline = now + Math.max(250L, maxWaitMs);
        long lastEngage = now;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return true;
            }
            sleepQuiet(250L);
            hostiles = AutoFaceEntity.detectNearbyEntities(bot, 10.0D)
                    .stream()
                    .filter(EntityUtil::isHostile)
                    .toList();
            if (hostiles.isEmpty()) {
                return false;
            }
            long t = System.currentTimeMillis();
            if (t - lastEngage >= 900L) {
                BotEventHandler.engageImmediateThreats(bot);
                lastEngage = t;
            }
        }
        return true;
    }

    private boolean tryCandidatesForReach(ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         BlockPos target,
                                         List<BlockPos> candidates,
                                         double maxReachSq) {
        if (source == null || bot == null || target == null || candidates == null || candidates.isEmpty()) {
            return false;
        }
        Vec3d targetCenter = Vec3d.ofCenter(target);
        for (BlockPos stand : candidates) {
            if (stand == null) {
                continue;
            }
            var planOpt = MovementService.planLootApproach(bot, stand, MovementService.MovementOptions.skillLoot());
            if (planOpt.isEmpty()) {
                continue;
            }
            var res = MovementService.execute(source, bot, planOpt.get(), false, true, false, false);
            if (!res.success()) {
                continue;
            }
            Vec3d newPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
            if (newPos.squaredDistanceTo(targetCenter) <= maxReachSq) {
                net.shasankp000.Entity.LookController.faceBlock(bot, target);
                return true;
            }
        }
        return false;
    }

    private boolean tryExitBuildFootprint(ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         ServerWorld world,
                                         ActiveBuildSite buildSite) {
        if (source == null || bot == null || world == null || buildSite == null) {
            return false;
        }
        BlockPos center = buildSite.center();
        int radius = buildSite.radius();
        Direction doorSide = buildSite.doorSide();
        int floorY = center.getY();

        BlockPos botPos = bot.getBlockPos();
        boolean insideXZ = Math.abs(botPos.getX() - center.getX()) < radius && Math.abs(botPos.getZ() - center.getZ()) < radius;
        boolean insideY = botPos.getY() >= floorY - 1 && botPos.getY() <= floorY + Math.max(2, buildSite.wallHeight() + 1);
        if (!insideXZ || !insideY) {
            return false;
        }

        // Prefer the planned doorway opening (or placed door) first.
        if (doorSide != null) {
            BlockPos insideApproach = center.offset(doorSide, Math.max(1, radius - 1));
            BlockPos threshold = center.offset(doorSide, radius);
            BlockPos outside = center.offset(doorSide, radius + 2);
            if (attemptExitVia(source, bot, world, insideApproach, threshold, outside, floorY)) {
                return true;
            }
        }

        // Otherwise find any perimeter opening and step out.
        BlockPos opening = findPerimeterExit(world, center, radius);
        if (opening != null) {
            BlockPos insideApproach = opening.offset(approximateToward(opening, center), 1);
            BlockPos outside = opening.offset(approximateToward(center, opening), 2);
            if (attemptExitVia(source, bot, world, insideApproach, opening, outside, floorY)) {
                return true;
            }
        }

        // Last resort: double back a couple steps to escape "push into wall" loops and let pathfinding replan.
        BotActions.moveBackward(bot);
        sleepQuiet(120L);
        BotActions.moveBackward(bot);
        sleepQuiet(120L);
        return false;
    }

    private boolean attemptExitVia(ServerCommandSource source,
                                   ServerPlayerEntity bot,
                                   ServerWorld world,
                                   BlockPos insideApproach,
                                   BlockPos threshold,
                                   BlockPos outside,
                                   int floorY) {
        if (insideApproach == null || threshold == null || outside == null) {
            return false;
        }
        BlockPos approach = new BlockPos(insideApproach.getX(), floorY, insideApproach.getZ());
        BlockPos doorFoot = new BlockPos(threshold.getX(), floorY, threshold.getZ());
        BlockPos out = new BlockPos(outside.getX(), floorY, outside.getZ());
        if (!isStandable(world, approach)) {
            return false;
        }
        var planOpt = MovementService.planLootApproach(bot, approach, MovementService.MovementOptions.skillLoot());
        if (planOpt.isEmpty() || !MovementService.execute(source, bot, planOpt.get(), false, true, false, false).success()) {
            return false;
        }

        clearDoorSoft(world, bot, doorFoot);
        clearDoorSoft(world, bot, doorFoot.up(1));
        // If a door exists here, open it before stepping through.
        MovementService.tryOpenDoorToward(bot, doorFoot);

        // Step through the opening.
        var outPlan = MovementService.planLootApproach(bot, out, MovementService.MovementOptions.skillLoot());
        if (outPlan.isPresent() && MovementService.execute(source, bot, outPlan.get(), false, true, false, false).success()) {
            return true;
        }
        return false;
    }

    private BlockPos findPerimeterExit(ServerWorld world, BlockPos center, int radius) {
        if (world == null || center == null) {
            return null;
        }
        int floorY = center.getY();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                if (!perimeter) continue;
                BlockPos foot = new BlockPos(center.getX() + dx, floorY, center.getZ() + dz);
                BlockPos head = foot.up();
                BlockPos aboveHead = foot.up(2);
                if (!world.getBlockState(foot).getCollisionShape(world, foot).isEmpty()) continue;
                if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) continue;
                if (!world.getBlockState(aboveHead).getCollisionShape(world, aboveHead).isEmpty()) continue;
                BlockPos below = foot.down();
                if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) continue;
                if (!world.getFluidState(foot).isEmpty() || !world.getFluidState(below).isEmpty()) continue;
                double sq = center.getSquaredDistance(foot);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = foot.toImmutable();
                }
            }
        }
        return best;
    }

    private Direction approximateToward(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return Direction.NORTH;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private List<BlockPos> findStandableCandidatesNear(ServerWorld world,
                                                      BlockPos target,
                                                      int radius,
                                                      int ySpan,
                                                      double maxReachSq,
                                                      BlockPos preferNear,
                                                      int maxCandidates) {
        Vec3d targetCenter = Vec3d.ofCenter(target);
        List<BlockPos> candidates = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(target.add(-radius, -ySpan, -radius), target.add(radius, ySpan, radius))) {
            BlockPos foot = pos.toImmutable();
            BlockPos head = foot.up();
            BlockPos below = foot.down();
            if (!world.getBlockState(foot).getCollisionShape(world, foot).isEmpty()) {
                continue;
            }
            if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
                continue;
            }
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                continue;
            }
            Vec3d footCenter = Vec3d.ofCenter(foot);
            if (footCenter.squaredDistanceTo(targetCenter) > maxReachSq) {
                continue;
            }
            double preferSq = preferNear != null ? preferNear.getSquaredDistance(foot) : 0.0D;
            candidates.add(foot);
            scores.add(preferSq);
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        // Stable-ish sort by preferSq (closer to current bot position first).
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(scores.get(a), scores.get(b)));
        List<BlockPos> limited = new ArrayList<>();
        for (int idx : order) {
            limited.add(candidates.get(idx));
            if (limited.size() >= Math.max(1, maxCandidates)) {
                break;
            }
        }
        return limited;
    }

    private boolean hasGaps(ServerWorld world, BlockPos center, int radius, int wallHeight, Direction doorSide) {
        int floorY = center.getY();
        int roofY = floorY + wallHeight;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                BlockPos roof = center.add(dx, wallHeight, dz);
                BlockState roofState = world.getBlockState(roof);
                if (roofState.isAir() || roofState.isReplaceable() || roofState.isIn(BlockTags.LEAVES) || roofState.isIn(BlockTags.LOGS)) {
                    return true;
                }
                if (perimeter) {
                    for (int y = floorY + 1; y <= roofY; y++) {
                        BlockPos pos = center.add(dx, y - floorY, dz);
                        if (isDoorGap(pos, center, radius, doorSide, floorY)) {
                            continue;
                        }
                        BlockState state = world.getBlockState(pos);
                        if (state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private Direction resolveAscentDirection(BlockPos surface, BlockPos chamber) {
        int dx = surface.getX() - chamber.getX();
        int dz = surface.getZ() - chamber.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private Set<BlockPos> computeProtectedSteps(BlockPos chamberCenter, Direction ascentDir, int count) {
        Set<BlockPos> protectedSteps = new HashSet<>();
        for (int i = 0; i < count; i++) {
            BlockPos step = chamberCenter.offset(ascentDir, i).up(i);
            protectedSteps.add(step);
        }
        return protectedSteps;
    }

    private void smoothStairExit(ServerWorld world, ServerPlayerEntity bot, BlockPos chamberCenter, Direction ascentDir, int steps) {
        for (int i = 0; i < steps; i++) {
            BlockPos foot = chamberCenter.offset(ascentDir, i).up(i);
            // Only clear headroom; do not place blocks or ladders to avoid blocking the throat.
            BlockPos head = foot.up();
            BlockPos head2 = head.up();
            if (!world.getBlockState(head).isAir() && !world.getBlockState(head).isReplaceable() && !world.getBlockState(head).isIn(BlockTags.LEAVES)) {
                mineSoft(bot, head);
            }
            if (!world.getBlockState(head2).isAir() && !world.getBlockState(head2).isReplaceable() && !world.getBlockState(head2).isIn(BlockTags.LEAVES)) {
                mineSoft(bot, head2);
            }
        }
        // Add a throat torch for visibility
        BlockPos torchPos = chamberCenter.offset(ascentDir, Math.min(steps, 2)).up(1);
        BlockPos torchFloor = torchPos.down();
        if (world.getBlockState(torchPos).isAir() && world.getBlockState(torchFloor).isSolidBlock(world, torchFloor)) {
            BotActions.placeBlockAt(bot, torchPos, Direction.UP, List.of(Items.TORCH));
        }
    }

    private void depositCheapLoot(ServerWorld world, ServerPlayerEntity bot, BlockPos chamberCenter) {
        BlockPos chestPos = findChestNear(world, chamberCenter, 3);
        if (chestPos == null) {
            if (!hasItem(bot, Items.CHEST)) {
                LOGGER.info("Burrow deposit: no chest nearby and none in inventory; skipping deposit.");
                return;
            }
            List<BlockPos> candidates = chestPlacementOptions(chamberCenter);
            for (BlockPos placement : candidates) {
                if (placement.equals(bot.getBlockPos())) {
                    continue;
                }
                BlockPos floor = placement.down();
                if (!world.getBlockState(floor).isSolidBlock(world, floor)) {
                    continue;
                }
                // Clear space at placement (foot + head)
                mineSoft(bot, placement);
                mineSoft(bot, placement.up());
                if (!ensureReach(bot, placement)) {
                    LOGGER.warn("Burrow deposit: could not reach {} for chest placement.", placement.toShortString());
                    continue;
                }
                BotActions.placeBlockAt(bot, placement, Direction.UP, List.of(Items.CHEST));
                BlockState placed = world.getBlockState(placement);
                if (!placed.isOf(Blocks.CHEST) && !placed.isOf(Blocks.TRAPPED_CHEST)) {
                    LOGGER.warn("Burrow deposit: chest placement at {} failed (state={}).", placement.toShortString(), placed.getBlock().getName().getString());
                    continue;
                }
                chestPos = placement;
                LOGGER.info("Burrow deposit: placed chest at {}", chestPos.toShortString());
                break;
            }
            if (chestPos == null) {
                LOGGER.warn("Burrow deposit: could not place a chest near {} after trying {} spots.", chamberCenter.toShortString(), candidates.size());
                return;
            }
        }
        BlockPos nearby = findChestNear(world, chamberCenter, 3);
        if (nearby != null) {
            chestPos = nearby;
        }
        net.minecraft.block.entity.ChestBlockEntity chest = awaitChest(world, chestPos);
        if (chest == null) {
            BlockState state = world.getBlockState(chestPos);
            LOGGER.warn("Burrow deposit: chest not ready at {} (state={}); skipping deposit.", chestPos.toShortString(), state.getBlock().getName().getString());
            return;
        }
        if (!BlockInteractionService.canInteract(bot, chestPos)) {
            LOGGER.warn("Burrow deposit: chest {} not interactable from {}; skipping remote deposit.",
                    chestPos.toShortString(), bot.getBlockPos().toShortString());
            return;
        }
        Integer moved = callOnServer(world, () -> moveCheapItems(bot, chest));
        if (moved == null) {
            LOGGER.warn("Burrow deposit: could not move items into chest at {}", chestPos.toShortString());
            return;
        }
        LOGGER.info("Burrow deposit: moved {} items into chest at {}", moved, chestPos.toShortString());
    }

    private boolean hasItem(ServerPlayerEntity bot, Item item) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findChestNear(ServerWorld world, BlockPos center, int radius) {
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -1, -radius), center.add(radius, 1, radius))) {
            var state = world.getBlockState(pos);
            if (state.isOf(net.minecraft.block.Blocks.CHEST) || state.isOf(net.minecraft.block.Blocks.TRAPPED_CHEST)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private List<BlockPos> chestPlacementOptions(BlockPos chamberCenter) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(chamberCenter);
        for (Direction dir : Direction.Type.HORIZONTAL) {
            candidates.add(chamberCenter.offset(dir));
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            candidates.add(chamberCenter.offset(dir, 2));
        }
        return candidates;
    }

    private net.minecraft.block.entity.ChestBlockEntity awaitChest(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            var be = callOnServer(world, () -> world.getBlockEntity(pos));
            if (be instanceof net.minecraft.block.entity.ChestBlockEntity chest) {
                return chest;
            }
            BlockState state = callOnServer(world, () -> world.getBlockState(pos));
            if (state == null || !(state.getBlock() instanceof net.minecraft.block.ChestBlock)) {
                return null; // something else replaced the spot
            }
            try {
                Thread.sleep(60L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        var be = callOnServer(world, () -> world.getBlockEntity(pos));
        return be instanceof net.minecraft.block.entity.ChestBlockEntity chest ? chest : null;
    }

    private <T> T callOnServer(ServerWorld world, java.util.function.Supplier<T> task) {
        if (world == null || task == null) {
            return null;
        }
        var server = world.getServer();
        if (server == null) {
            return null;
        }
        if (server.isOnThread()) {
            return task.get();
        }
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.complete(null);
            }
        });
        try {
            return future.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return null;
        }
    }

    private int moveCheapItems(ServerPlayerEntity bot, net.minecraft.block.entity.ChestBlockEntity chest) {
        var chestInv = (net.minecraft.inventory.Inventory) chest;
        int moved = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!isCheap(stack.getItem())) continue;
            ItemStack moving = stack.copy();
            moved += insertIntoChest(bot, chestInv, i, moving);
        }
        return moved;
    }

    private boolean isCheap(Item item) {
        if (BUILD_BLOCKS.contains(item)) return true;
        return item == Items.ROTTEN_FLESH
                || item == Items.BONE
                || item == Items.STRING
                || item == Items.SPIDER_EYE
                || item == Items.GUNPOWDER
                || item == Items.ARROW
                || item == Items.NAUTILUS_SHELL
                || item == Items.FEATHER;
    }

    private int insertIntoChest(ServerPlayerEntity bot, net.minecraft.inventory.Inventory chest, int slot, ItemStack moving) {
        ItemStack remaining = moving;
        for (int c = 0; c < chest.size() && !remaining.isEmpty(); c++) {
            ItemStack chestStack = chest.getStack(c);
            if (chestStack.isEmpty()) {
                chest.setStack(c, remaining.copy());
                bot.getInventory().setStack(slot, ItemStack.EMPTY);
                return moving.getCount();
            }
            if (ItemStack.areItemsEqual(chestStack, remaining)
                    && chestStack.getCount() < chestStack.getMaxCount()) {
                int canAdd = Math.min(chestStack.getMaxCount() - chestStack.getCount(), remaining.getCount());
                chestStack.increment(canAdd);
                remaining.decrement(canAdd);
                chest.setStack(c, chestStack);
            }
        }
        if (remaining.getCount() != moving.getCount()) {
            bot.getInventory().setStack(slot, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
        }
        return moving.getCount() - remaining.getCount();
    }

    private boolean inventoryFull(ServerPlayerEntity bot) {
        return bot.getInventory().getEmptySlot() < 0;
    }

    private void faceDirection(ServerPlayerEntity bot, Direction dir) {
        float yaw = switch (dir) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case WEST -> 90f;
            case EAST -> -90f;
            default -> bot.getYaw();
        };
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
    }

    private SkillExecutionResult stripForward(ServerPlayerEntity bot, ServerCommandSource source, Direction dir, int blocks) {
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        if (world == null) {
            return SkillExecutionResult.failure("No world for stripmine throat.");
        }
        BlockPos start = bot.getBlockPos();
        BlockPos current = start;
        int advanced = 0;
        for (int i = 0; i < blocks; i++) {
            BlockPos target = current.offset(dir);
            BlockPos head = target.up();
            mineSoft(bot, target);
            mineSoft(bot, head);
            BlockPos support = target.down();
            BlockState supportState = world.getBlockState(support);
            if (supportState.isAir() || supportState.isReplaceable()) {
                placeBlock(bot, support); // ensure a floor to stand on
            }
            var plan = new MovementService.MovementPlan(MovementService.Mode.DIRECT, target, target, null, null, dir);
            var res = MovementService.execute(source, bot, plan, false, true, true, true);
            if (!res.success()) {
                return SkillExecutionResult.failure("Stripmine throat movement failed: " + res.detail());
            }
            if (!bot.getBlockPos().equals(target)) {
                // Retry once with a direct push if we didn't actually reach the target.
                LOGGER.warn("Stripmine throat step {}: ended at {} instead of {}; retrying once.", i + 1, bot.getBlockPos().toShortString(), target.toShortString());
                var retryPlan = new MovementService.MovementPlan(MovementService.Mode.DIRECT, target, target, null, null, dir);
                var retry = MovementService.execute(source, bot, retryPlan, false, true, true, true);
                if (!retry.success() || !bot.getBlockPos().equals(target)) {
                    return SkillExecutionResult.failure("Stripmine throat stalled before " + target.toShortString());
                }
            }
            current = bot.getBlockPos();
            advanced++;
        }
        BlockPos expected = start.offset(dir, blocks);
        int forwardGain = Math.abs(current.getX() - start.getX()) + Math.abs(current.getZ() - start.getZ());
        if (!current.equals(expected)) {
            LOGGER.warn("Stripmine throat ended at {} (expected {}), forwardGain={} blocks", current.toShortString(), expected.toShortString(), forwardGain);
        }
        return SkillExecutionResult.success("Stripmine throat complete.");
    }

    private void ensurePickupSlot(ServerPlayerEntity bot) {
        if (bot.getInventory().getEmptySlot() >= 0) {
            return;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (BUILD_BLOCKS.contains(stack.getItem())) {
                bot.dropItem(stack.split(stack.getCount()), false, false);
                LOGGER.warn("Shelter drop-sweep: inventory full, dropped {} to make space.", stack.getItem().getName().getString());
                return;
            }
        }
    }

    private void maybePlaceLadder(ServerPlayerEntity bot, BlockPos foot, Direction ascentDir) {
        // Place ladder on the back face of the step to guarantee climbability; no-op if no ladders.
        BlockPos attach = foot.offset(ascentDir.getOpposite());
        BotActions.placeBlockAt(bot, foot, ascentDir.getOpposite(), List.of(Items.LADDER));
    }
}
