package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
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

        // If we're clearly underground, get to the surface first (then re-plan the hovel site).
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        if (bot.getBlockY() < surfaceY - 3) {
            boolean surfaced = tryReachSurface(source, bot, world, origin);
            if (surfaced) {
                origin = bot.getBlockPos();
            }
        }
        surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        if (bot.getBlockY() < surfaceY - 2) {
            return SkillExecutionResult.failure("I can't build a surface shelter while underground; I couldn't reach the surface.");
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

        int gathered = allowAutoGather ? ensureBuildStock(source, bot, neededBlocks, true, center) : 0;
        available = countBuildBlocks(bot);
        if (gathered + available < neededBlocks) {
            int oldRadius = radius;
            radius = Math.max(2, radius - 1);
            neededBlocks = estimateBlockNeed(radius, wallHeight);
            LOGGER.warn("Shelter: downsizing radius from {} to {} due to material shortfall. New estBlocks={}", oldRadius, radius, neededBlocks);
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
    }

    private record HovelPlan(BlockPos center, Direction doorSide) {}

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
            BlockPos candidate = bot.getBlockPos();
            if (!world.getBlockState(candidate).isAir()) {
                candidate = candidate.up();
            }
            BotActions.jump(bot);
            sleepQuiet(160L);
            if (!world.getBlockState(candidate).isAir()) {
                candidate = candidate.up();
            }
            BlockPos placed = tryPlaceScaffold(bot, candidate);
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

        int scanRadius = 12;
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
                if (hasLiquidInFootprint(world, candidate, radius)) {
                    continue;
                }
                Flatness flatness = evaluateFlatness(world, candidate, radius);
                if (flatness.maxDelta() > 1) {
                    continue;
                }
                int naturalWalls = countNaturalWalls(world, candidate, radius, wallHeight);
                int vegetation = countVegetationInShell(world, candidate, radius, wallHeight);
                double distSq = origin.getSquaredDistance(candidate);
                double score = naturalWalls * 1.75 - vegetation * 2.25 - distSq * 0.02 - flatness.maxDelta() * 6.0;
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
                for (int y = floorY + 1; y <= roofY; y++) {
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

    private boolean hasLiquidInFootprint(ServerWorld world, BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pos = center.add(dx, 0, dz);
                if (!world.getFluidState(pos).isEmpty() || !world.getFluidState(pos.down()).isEmpty()) {
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
                for (int y = 1; y <= wallHeight; y++) {
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
                BlockPos floor = new BlockPos(center.getX() + dx, floorY, center.getZ() + dz);
                BlockState floorState = world.getBlockState(floor);
                if (floorState.isAir() || floorState.isReplaceable() || floorState.isIn(BlockTags.LEAVES) || floorState.isIn(BlockTags.LOGS)) {
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
        if (pos.getY() != floorY + 1 && pos.getY() != floorY + 2) {
            return false;
        }
        BlockPos doorLower = center.offset(doorSide, radius).up(1);
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
        int collected = 0;
        int toGather = Math.max(0, needed - available);
        if (toGather <= 0) {
            return 0;
        }

        ChatUtils.sendSystemMessage(source,
                "Gathering shelter materials: descending 6, stripmining for " + toGather + " blocks, then returning up the same stairs.");

        // Cleaner gather: carve a 6-block descent and then stripmine until we have enough blocks,
        // then climb back up the same staircase (reverse direction).
        try {
            collected = gatherBuildBlocksViaStairsAndStripmine(source, bot, needed, returnPos);
        } catch (Exception e) {
            LOGGER.warn("Shelter gather errored: {}", e.getMessage());
        }

        int after = countBuildBlocks(bot);
        LOGGER.info("Shelter gather summary: before={} after={} needed={} collected={}", before, after, needed, Math.max(0, after - before));
        return Math.max(0, after - before);
    }

    private int gatherBuildBlocksViaStairsAndStripmine(ServerCommandSource source, ServerPlayerEntity bot, int neededBlocks, BlockPos returnPos) {
        if (source == null || bot == null) {
            return 0;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return 0;
        }
        int before = countBuildBlocks(bot);
        if (before >= neededBlocks) {
            return 0;
        }

        BlockPos startPos = bot.getBlockPos();
        int startY = startPos.getY();
        Direction digDir = WorkDirectionService.getDirection(bot.getUuid()).orElse(bot.getHorizontalFacing());
        WorkDirectionService.setDirection(bot.getUuid(), digDir);

        LOGGER.info("Shelter gather: descending 6 blocks, then stripmining for shortfall (dir={})", digDir);

        CollectDirtSkill stair = new CollectDirtSkill();
        Map<String, Object> shared = new HashMap<>();
        Map<String, Object> descentParams = new HashMap<>();
        descentParams.put("descentBlocks", 6);
        descentParams.put("issuerFacing", digDir.asString());
        descentParams.put("lockDirection", true);
        descentParams.put("strictWalk", true);
        SkillExecutionResult descent = stair.execute(new SkillContext(source, shared, descentParams));
        if (!descent.success()) {
            LOGGER.warn("Shelter gather descent failed: {}", descent.message());
            ChatUtils.sendSystemMessage(source, "Descent failed while gathering: " + descent.message());
        }

        StripMineSkill strip = new StripMineSkill();
        int loops = 0;
        while (countBuildBlocks(bot) < neededBlocks && loops < 8 && !SkillManager.shouldAbortSkill(bot)) {
            ensureBuildChestAndDeposit(source, world, bot, returnPos != null ? returnPos : startPos, 12);
            int shortfall = neededBlocks - countBuildBlocks(bot);
            int segment = Math.max(4, Math.min(14, shortfall / 4));
            Map<String, Object> stripParams = new HashMap<>();
            stripParams.put("count", segment);
            stripParams.put("issuerFacing", digDir.asString());
            stripParams.put("lockDirection", true);
            SkillExecutionResult stripRes = strip.execute(new SkillContext(source, shared, stripParams));
            if (!stripRes.success()) {
                LOGGER.warn("Shelter gather stripmine failed: {}", stripRes.message());
                ChatUtils.sendSystemMessage(source, "Stripmine failed while gathering: " + stripRes.message());
                break;
            }
            loops++;
        }

        // Return up the same staircase by reversing direction.
        Direction climbDir = digDir.getOpposite();
        WorkDirectionService.setDirection(bot.getUuid(), climbDir);
        Map<String, Object> ascentParams = new HashMap<>();
        ascentParams.put("ascentTargetY", startY);
        ascentParams.put("issuerFacing", climbDir.asString());
        ascentParams.put("lockDirection", true);
        ascentParams.put("strictWalk", true);
        SkillExecutionResult ascent = stair.execute(new SkillContext(source, shared, ascentParams));
        if (!ascent.success()) {
            LOGGER.warn("Shelter gather ascent failed: {}", ascent.message());
            ChatUtils.sendSystemMessage(source, "Ascent failed while returning from gather: " + ascent.message());
        }

        // Snap back to the build site if we ended up offset.
        if (returnPos != null) {
            var planOpt = net.shasankp000.GameAI.services.MovementService.planLootApproach(bot, returnPos, net.shasankp000.GameAI.services.MovementService.MovementOptions.skillLoot());
            if (planOpt.isPresent()) {
                var res = net.shasankp000.GameAI.services.MovementService.execute(source, bot, planOpt.get(), false, true, false, false);
                if (!res.success()) {
                    LOGGER.warn("Shelter: failed to return to build site {} after gather: {}", returnPos.toShortString(), res.detail());
                }
            }
        }

        int after = countBuildBlocks(bot);
        return Math.max(0, after - before);
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
            BotActions.jump(bot);
            sleepQuiet(160L);
            // Place into the bot's current foot block *after jumping* (woodcut-style jump-pillaring).
            BlockPos placeAt = bot.getBlockPos();
            if (!world.getBlockState(placeAt).isAir()) {
                placeAt = placeAt.up();
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
        BlockPos doorLower = center.offset(doorSide, radius).up(1);
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
                for (int y = floorY + 1; y <= floorY + 3; y++) {
                    BlockPos target = new BlockPos(pos.getX(), y, pos.getZ());
                    BlockState state = world.getBlockState(target);
                    if (state.isAir()) continue;
                    if (state.isIn(BlockTags.LEAVES) || state.isReplaceable() || state.isOf(Blocks.SNOW) || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) {
                        mineSoft(bot, target);
                    }
                }
                BlockPos below = pos.down();
                if (world.getBlockState(pos).isAir() && world.getBlockState(below).isAir()) {
                    placeBlock(bot, pos);
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
                BlockPos floor = new BlockPos(column.getX(), floorY, column.getZ());
                if (world.getBlockState(floor).isAir() || world.getBlockState(floor).isReplaceable()) {
                    placeBlock(bot, floor, counters);
                }
                // Walls
                if (perimeter) {
                    for (int y = floorY + 1; y <= roofY; y++) {
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
                for (int y = floorY + 1; y <= roofY; y++) {
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
        if (state.isIn(BlockTags.LEAVES) || state.isReplaceable() || state.isOf(Blocks.SNOW)) {
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
        BlockPos doorLower = center.offset(doorSide, radius).up(1);
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
        Vec3d center = Vec3d.ofCenter(target);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double maxReachSq = 20.25D; // ~4.5 blocks (survival reach)
        if (botPos.squaredDistanceTo(center) <= maxReachSq) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        List<BlockPos> candidates = findStandableCandidatesNear(world, target, 5, 3, maxReachSq, bot.getBlockPos(), 8);
        if (candidates.isEmpty()) {
            LOGGER.warn("Shelter: no standable spot near {} for placement", target.toShortString());
            return false;
        }

        for (BlockPos stand : candidates) {
            if (stand == null) {
                continue;
            }
            var planOpt = net.shasankp000.GameAI.services.MovementService.planLootApproach(bot, stand, net.shasankp000.GameAI.services.MovementService.MovementOptions.skillLoot());
            if (planOpt.isEmpty()) {
                continue;
            }
            var res = net.shasankp000.GameAI.services.MovementService.execute(bot.getCommandSource(), bot, planOpt.get(), false, true, false, false);
            if (!res.success()) {
                continue;
            }
            Vec3d newPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
            if (newPos.squaredDistanceTo(center) <= maxReachSq) {
                net.shasankp000.Entity.LookController.faceBlock(bot, target);
                return true;
            }
        }
        LOGGER.warn("Shelter: could not approach within reach of {} after trying {} stand positions", target.toShortString(), candidates.size());
        return false;
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
