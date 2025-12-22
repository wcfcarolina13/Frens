package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
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
import net.shasankp000.EntityUtil;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.GameAI.services.BlockInteractionService;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.impl.CollectDirtSkill;
import net.shasankp000.GameAI.skills.impl.WoodcutSkill;
import net.shasankp000.GameAI.skills.support.TreeDetector;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.PlayerUtils.MiningTool;
import net.shasankp000.Entity.AutoFaceEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Survival-style hovel builder that walks the exterior ring to place walls and
 * then bridges a serpentine path across the roof.
 */
public final class HovelPerimeterBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-shelter");
    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks
    private static final int PLACEMENT_THROTTLE_EVERY = 8;
    private static final long PLACEMENT_THROTTLE_MS = 18L;
    private static final long PILLAR_STEP_DELAY_MS = 160L;

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
    private static final List<Item> PILLAR_BLOCKS = buildPillarBlocks();

    private record HovelPlan(BlockPos center, Direction doorSide) {}

    private static final class BuildCounters {
        int attemptedPlacements;
        int placedBlocks;
        int reachFailures;
        int noMaterials;
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
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(origin, "origin");

        HovelPlan plan = resolvePlan(world, bot, origin, radius, preferredDoorSide, context.sharedState(), resumeRequested);
        BlockPos center = plan.center();
        Direction doorSide = plan.doorSide();

        if (TreeDetector.isNearHumanBlocks(world, center, radius + 2)) {
            return SkillExecutionResult.failure("Build site is too close to human-built blocks.");
        }
        if (hasNearbyFluidOrFarmland(world, center, radius + 1)) {
            return SkillExecutionResult.failure("Build site is too wet or near farmland.");
        }

        if (!moveToBuildSite(source, bot, center)) {
            return SkillExecutionResult.failure("I couldn't reach a safe spot to build a hovel.");
        }

        int neededBlocks = estimatePlacementNeed(world, center, radius, wallHeight, doorSide);
        int available = countBuildBlocks(bot);
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

        BuildCounters counters = new BuildCounters();
        ChatUtils.sendSystemMessage(source, "Hovel: clearing and leveling the build site...");
        levelBuildSite(world, bot, center, radius, counters);
        ensureClearBuildSiteWithWoodcut(source, bot, center, radius);
        levelBuildSite(world, bot, center, radius, counters);

        if (shortage > 0 && allowAutoGather) {
            ensureBuildStock(source, bot, neededBlocks, center);
        }

        ensureDoorwayOpen(world, bot, center, radius, doorSide);
        if (!buildWallsFromRing(world, source, bot, center, radius, wallHeight, doorSide, counters)) {
            LOGGER.warn("Shelter: wall build stalled; continuing to roof attempt anyway.");
        }
        if (!buildRoofByWalk(world, source, bot, center, radius, wallHeight, counters)) {
            LOGGER.warn("Shelter: roof build stalled.");
        }
        ensureDoorwayOpen(world, bot, center, radius, doorSide);

        int remaining = countTargetsRemaining(world, center, radius, wallHeight, doorSide);
        sweepDrops(source, Math.max(10.0, radius + 7.0), 6.0, 60, 12_000L);
        if (remaining <= 0) {
            ChatUtils.sendSystemMessage(source, "Hovel complete.");
            return SkillExecutionResult.success("Shelter (hovel) built.");
        }
        ChatUtils.sendSystemMessage(source, "Hovel incomplete (" + remaining + " missing).");
        return SkillExecutionResult.failure("Shelter (hovel) incomplete.");
    }

    private HovelPlan resolvePlan(ServerWorld world,
                                  ServerPlayerEntity bot,
                                  BlockPos origin,
                                  int radius,
                                  Direction preferredDoorSide,
                                  Map<String, Object> sharedState,
                                  boolean resumeRequested) {
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
        double bestScore = Double.NEGATIVE_INFINITY;
        int scanRadius = 7;
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, topY, z);
                if (!isStandable(world, candidate)) {
                    continue;
                }
                if (TreeDetector.isNearHumanBlocks(world, candidate, radius + 2)) {
                    continue;
                }
                if (hasNearbyFluidOrFarmland(world, candidate, radius + 1)) {
                    continue;
                }
                double flatness = evaluateFlatness(world, candidate, radius);
                if (flatness > 1.5) {
                    continue;
                }
                double distSq = origin.getSquaredDistance(candidate);
                double score = -distSq - flatness * 6.0;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }

        Direction door = fallbackDoor;
        SharedStateUtils.setValue(sharedState, prefix + "center.x", best.getX());
        SharedStateUtils.setValue(sharedState, prefix + "center.y", best.getY());
        SharedStateUtils.setValue(sharedState, prefix + "center.z", best.getZ());
        SharedStateUtils.setValue(sharedState, prefix + "door", door.name());
        return new HovelPlan(best, door);
    }

    private boolean buildWallsFromRing(ServerWorld world,
                                       ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       BlockPos center,
                                       int radius,
                                       int wallHeight,
                                       Direction doorSide,
                                       BuildCounters counters) {
        int floorY = center.getY();
        int roofY = floorY + wallHeight;
        int ringRadius = radius + 1;
        List<BlockPos> ring = orderedRing(center, ringRadius, floorY);
        if (ring.isEmpty()) {
            return false;
        }
        BlockPos start = pickClosest(bot, ring);
        if (start == null) {
            return false;
        }
        int startIndex = ring.indexOf(start);
        if (startIndex < 0) {
            startIndex = 0;
        }
        int startAttempts = counters != null ? counters.attemptedPlacements : 0;
        int startReach = counters != null ? counters.reachFailures : 0;
        int startNoMat = counters != null ? counters.noMaterials : 0;
        int placedTotal = 0;
        int steps = ring.size();
        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }
            BlockPos ringPos = ring.get((startIndex + i) % steps);
            if (!isStandable(world, ringPos)) {
                LOGGER.debug("Shelter: skipping unstandable ring tile {}", ringPos);
                continue;
            }
            ensureRingStandable(world, bot, ringPos);
            if (!moveToRingPos(source, bot, ringPos)) {
                LOGGER.warn("Shelter: could not move to ring tile {} (pos={})", ringPos, bot.getBlockPos());
                continue;
            }
            BlockPos interior = offsetTowardCenter(center, ringPos);
            if (interior == null) {
                continue;
            }
            for (int y = floorY; y <= roofY - 1; y++) {
                BlockPos target = new BlockPos(interior.getX(), y, interior.getZ());
                if (isDoorGap(target, center, radius, doorSide, floorY)) {
                    continue;
                }
                if (placeBlockDirectIfWithinReach(bot, target, counters)) {
                    placedTotal++;
                }
                else {
                    LOGGER.debug("Shelter: wall placement rejected at {}", target);
                }
            }
        }
        int attempted = counters != null ? counters.attemptedPlacements - startAttempts : -1;
        int reachFails = counters != null ? counters.reachFailures - startReach : -1;
        int noMat = counters != null ? counters.noMaterials - startNoMat : -1;
        LOGGER.info("Shelter: wall ring pass placed={} attempted={} reachFail={} noMat={}",
                placedTotal, attempted, reachFails, noMat);
        return placedTotal > 0;
    }

    private boolean moveToRingPos(ServerCommandSource source, ServerPlayerEntity bot, BlockPos ringPos) {
        if (bot.getBlockPos().getSquaredDistance(ringPos) <= 0.25D) {
            return true;
        }
        MovementService.MovementPlan direct = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT, ringPos, ringPos, null, null, Direction.UP);
        MovementService.MovementResult res = MovementService.execute(source, bot, direct, false, true, true, false);
        boolean ok = res.success() && bot.getBlockPos().getSquaredDistance(ringPos) <= 0.25D;
        if (!ok) {
            LOGGER.warn("Shelter: failed to reach ring tile {} (distSq={}, res={})", ringPos, bot.getBlockPos().getSquaredDistance(ringPos), res.detail());
        }
        return ok;
    }

    private boolean buildRoofByWalk(ServerWorld world,
                                    ServerCommandSource source,
                                    ServerPlayerEntity bot,
                                    BlockPos center,
                                    int radius,
                                    int wallHeight,
                                    BuildCounters counters) {
        int roofY = center.getY() + wallHeight;
        List<BlockPos> roofPath = buildRoofPath(center, radius, roofY);
        if (roofPath.isEmpty()) {
            return true;
        }
        BlockPos startRoof = roofPath.get(0);
        LOGGER.debug("Shelter: roof path length={} start={}", roofPath.size(), startRoof);
        BlockPos pillarBase = pickRoofPillarBase(world, center, radius, startRoof);
        if (pillarBase == null) {
            LOGGER.warn("Shelter: no viable roof pillar base found.");
            return false;
        }
        if (!moveToBuildSite(source, bot, pillarBase)) {
            return false;
        }

        List<BlockPos> placedPillar = new ArrayList<>();
        int climb = Math.max(0, roofY - bot.getBlockY());
        if (!pillarUp(bot, climb, placedPillar)) {
            teardownScaffolding(world, bot, center, radius, wallHeight, placedPillar);
            return false;
        }

        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        try {
            if (!placeBlockDirectIfWithinReach(bot, startRoof, counters)) {
                LOGGER.warn("Shelter: could not place starting roof block at {}", startRoof.toShortString());
                LOGGER.debug("Shelter: roof start block state {}", world.getBlockState(startRoof));
                return false;
            }
            if (!stepToAdjacent(source, bot, startRoof)) {
                LOGGER.warn("Shelter: could not step onto starting roof block {}", startRoof.toShortString());
                return false;
            }
            for (int i = 1; i < roofPath.size(); i++) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    return false;
                }
                BlockPos next = roofPath.get(i);
                BlockState state = world.getBlockState(next);
                if (state.isAir() || state.isReplaceable()) {
                    placeBlockDirectIfWithinReach(bot, next, counters);
                }
                if (!stepToAdjacent(source, bot, next)) {
                    LOGGER.warn("Shelter: stalled stepping to roof {}", next.toShortString());
                    return false;
                }
            }
            for (int i = roofPath.size() - 2; i >= 0; i--) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    return false;
                }
                BlockPos back = roofPath.get(i);
                if (!stepToAdjacent(source, bot, back)) {
                    break;
                }
            }
            BlockPos pillarTop = new BlockPos(pillarBase.getX(), roofY, pillarBase.getZ());
            stepToAdjacent(source, bot, pillarTop);
        } finally {
            bot.setSneaking(wasSneaking);
            teardownScaffolding(world, bot, center, radius, wallHeight, placedPillar);
        }
        return true;
    }

    private BlockPos pickRoofPillarBase(ServerWorld world, BlockPos center, int radius, BlockPos startRoof) {
        int floorY = center.getY();
        List<BlockPos> candidates = List.of(
                new BlockPos(startRoof.getX() - 1, floorY, startRoof.getZ()),
                new BlockPos(startRoof.getX() + 1, floorY, startRoof.getZ()),
                new BlockPos(startRoof.getX(), floorY, startRoof.getZ() - 1),
                new BlockPos(startRoof.getX(), floorY, startRoof.getZ() + 1)
        );
        BlockPos fallback = null;
        for (BlockPos base : candidates) {
            if (!isStandable(world, base)) {
                continue;
            }
            int cheby = Math.max(Math.abs(base.getX() - center.getX()), Math.abs(base.getZ() - center.getZ()));
            if (cheby > radius) {
                return base.toImmutable();
            }
            if (fallback == null) {
                fallback = base.toImmutable();
            }
        }
        return fallback;
    }

    private List<BlockPos> buildRoofPath(BlockPos center, int radius, int roofY) {
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;
        List<BlockPos> path = new ArrayList<>();
        boolean reverse = false;
        for (int z = maxZ; z >= minZ; z--) {
            if (!reverse) {
                for (int x = minX; x <= maxX; x++) {
                    path.add(new BlockPos(x, roofY, z));
                }
            } else {
                for (int x = maxX; x >= minX; x--) {
                    path.add(new BlockPos(x, roofY, z));
                }
            }
            reverse = !reverse;
        }
        return path;
    }

    private boolean stepToAdjacent(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        if (bot.getBlockPos().equals(target)) {
            return true;
        }
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT, target, target, null, null, Direction.UP);
        MovementService.MovementResult result = MovementService.execute(source, bot, plan, false, true, true, false);
        return result.success();
    }

    private void teardownScaffolding(ServerWorld world,
                                     ServerPlayerEntity bot,
                                     BlockPos center,
                                     int radius,
                                     int wallHeight,
                                     List<BlockPos> placed) {
        if (placed == null || placed.isEmpty()) {
            return;
        }
        Collections.reverse(placed);
        for (BlockPos pos : placed) {
            if (pos == null) {
                continue;
            }
            if (isStructureCell(center, radius, wallHeight, pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            boolean scaffoldish = state.isReplaceable()
                    || state.isIn(BlockTags.LEAVES)
                    || state.isOf(Blocks.SNOW)
                    || state.isOf(Blocks.SNOW_BLOCK);
            if (!scaffoldish) {
                for (Item item : PILLAR_BLOCKS) {
                    if (item instanceof net.minecraft.item.BlockItem bi && state.isOf(bi.getBlock())) {
                        scaffoldish = true;
                        break;
                    }
                }
            }
            if (!scaffoldish) {
                continue;
            }
            net.shasankp000.Entity.LookController.faceBlock(bot, pos);
            LOGGER.debug("Shelter: tearing down scaffold at {}", pos);
            mineSoft(bot, pos);
            sleepQuiet(60L);
        }
    }

    private boolean pillarUp(ServerPlayerEntity bot, int steps, List<BlockPos> placedPillar) {
        if (steps <= 0) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!hasAnyPillarBlock(bot)) {
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
            sleepQuiet(PILLAR_STEP_DELAY_MS);
            if (!world.getBlockState(candidate).isAir()) {
                candidate = candidate.up();
            }
            if (!isPlaceableTarget(world, candidate)) {
                mineSoft(bot, candidate);
            }
            BlockPos placed = tryPlaceScaffold(bot, candidate, placedPillar);
            if (placed == null) {
                LOGGER.debug("Shelter: scaffold pillar placement failed at {}", candidate);
                bot.setSneaking(wasSneaking);
                return false;
            }
            waitForYIncrease(bot, candidate.getY(), 1_000L);
            sleepQuiet(PILLAR_STEP_DELAY_MS);
        }
        bot.setSneaking(wasSneaking);
        return true;
    }

    private BlockPos tryPlaceScaffold(ServerPlayerEntity bot, BlockPos target, List<BlockPos> placed) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        if (!hasAnyPillarBlock(bot)) {
            return null;
        }
        if (!isPlaceableTarget(world, target)) {
            mineSoft(bot, target);
        }
        BlockPos supportPlaced = ensureSupportBlockPlaced(bot, target);
        if (supportPlaced != null && placed != null) {
            placed.add(supportPlaced.toImmutable());
        }
        if (BotActions.placeBlockAt(bot, target, Direction.UP, PILLAR_BLOCKS)) {
            if (placed != null) {
                placed.add(target.toImmutable());
            }
            return target;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos alt = target.offset(dir);
            if (!isPlaceableTarget(world, alt)) {
                mineSoft(bot, alt);
            }
            BlockPos altSupport = ensureSupportBlockPlaced(bot, alt);
            if (altSupport != null && placed != null) {
                placed.add(altSupport.toImmutable());
            }
            if (BotActions.placeBlockAt(bot, alt, Direction.UP, PILLAR_BLOCKS)) {
                if (placed != null) {
                    placed.add(alt.toImmutable());
                }
                return alt;
            }
        }
        return null;
    }

    private BlockPos ensureSupportBlockPlaced(ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        BlockPos below = target.down();
        BlockState belowState = world.getBlockState(below);
        if (!belowState.getCollisionShape(world, below).isEmpty()) {
            return null;
        }
        if (!isPlaceableTarget(world, below)) {
            mineSoft(bot, below);
        }
        if (!hasAnyPillarBlock(bot)) {
            return null;
        }
        boolean placed = BotActions.placeBlockAt(bot, below, Direction.UP, PILLAR_BLOCKS);
        return placed ? below : null;
    }

    private boolean isPlaceableTarget(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW);
    }

    private boolean waitForYIncrease(ServerPlayerEntity bot, int fromY, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(50L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (bot.getBlockY() > fromY) {
                return true;
            }
            sleepQuiet(50L);
        }
        return bot.getBlockY() > fromY;
    }

    private boolean hasAnyPillarBlock(ServerPlayerEntity bot) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (PILLAR_BLOCKS.contains(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    private void ensureRingStandable(ServerWorld world, ServerPlayerEntity bot, BlockPos ringPos) {
        if (ringPos == null) {
            return;
        }
        BlockPos below = ringPos.down();
        BlockState belowState = world.getBlockState(below);
        if (belowState.getCollisionShape(world, below).isEmpty()) {
            LOGGER.debug("Shelter: placing temporary support at {}", below);
            placeBlock(bot, below, null);
        }
        BlockState footState = world.getBlockState(ringPos);
        if (!footState.getCollisionShape(world, ringPos).isEmpty()) {
            LOGGER.debug("Shelter: clearing blocking block at {}", ringPos);
            mineSoft(bot, ringPos);
        }
        BlockPos head = ringPos.up();
        BlockState headState = world.getBlockState(head);
        if (!headState.getCollisionShape(world, head).isEmpty()) {
            LOGGER.debug("Shelter: carving headroom at {}", head);
            mineSoft(bot, head);
        }
    }

    private BlockPos offsetTowardCenter(BlockPos center, BlockPos ringPos) {
        int dx = Integer.compare(center.getX(), ringPos.getX());
        int dz = Integer.compare(center.getZ(), ringPos.getZ());
        if (dx == 0 && dz == 0) {
            return null;
        }
        return ringPos.add(dx, 0, dz);
    }

    private List<BlockPos> orderedRing(BlockPos center, int ringRadius, int y) {
        List<BlockPos> ring = new ArrayList<>();
        int minX = center.getX() - ringRadius;
        int maxX = center.getX() + ringRadius;
        int minZ = center.getZ() - ringRadius;
        int maxZ = center.getZ() + ringRadius;
        for (int x = minX; x <= maxX; x++) {
            ring.add(new BlockPos(x, y, minZ));
        }
        for (int z = minZ + 1; z <= maxZ; z++) {
            ring.add(new BlockPos(maxX, y, z));
        }
        for (int x = maxX - 1; x >= minX; x--) {
            ring.add(new BlockPos(x, y, maxZ));
        }
        for (int z = maxZ - 1; z > minZ; z--) {
            ring.add(new BlockPos(minX, y, z));
        }
        return ring;
    }

    private BlockPos pickClosest(ServerPlayerEntity bot, List<BlockPos> candidates) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : candidates) {
            double sq = origin.getSquaredDistance(pos);
            if (sq < bestSq) {
                bestSq = sq;
                best = pos;
            }
        }
        return best;
    }

    private boolean moveToBuildSite(ServerCommandSource source, ServerPlayerEntity bot, BlockPos center) {
        if (bot.getBlockPos().getSquaredDistance(center) <= 2.25D) {
            return true;
        }
        var planOpt = MovementService.planLootApproach(bot, center, MovementService.MovementOptions.skillLoot());
        if (planOpt.isEmpty()) {
            return false;
        }
        var res = MovementService.execute(source, bot, planOpt.get(), false, true, true, false);
        if (!res.success()) {
            LOGGER.warn("Shelter moveToBuildSite: MovementService failed ({}) endedAt={} target={}",
                    res.detail(), bot.getBlockPos().toShortString(), center.toShortString());
        }
        return res.success();
    }

    private int estimatePlacementNeed(ServerWorld world, BlockPos center, int radius, int wallHeight, Direction doorSide) {
        int floorY = center.getY();
        int roofY = floorY + wallHeight;
        int needed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos support = new BlockPos(center.getX() + dx, floorY - 1, center.getZ() + dz);
                BlockState supportState = world.getBlockState(support);
                if (supportState.isAir() || supportState.isReplaceable() || supportState.isIn(BlockTags.LEAVES) || supportState.isIn(BlockTags.LOGS)) {
                    needed++;
                }
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                if (perimeter) {
                    for (int y = floorY; y <= roofY - 1; y++) {
                        BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                        if (isDoorGap(pos, center, radius, doorSide, floorY)) {
                            continue;
                        }
                        BlockState state = world.getBlockState(pos);
                        if (state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) {
                            needed++;
                        }
                    }
                }
                BlockPos roof = new BlockPos(center.getX() + dx, roofY, center.getZ() + dz);
                BlockState roofState = world.getBlockState(roof);
                if (roofState.isAir() || roofState.isReplaceable() || roofState.isIn(BlockTags.LEAVES) || roofState.isIn(BlockTags.LOGS)) {
                    needed++;
                }
            }
        }
        return needed + 10;
    }

    private void announceResources(ServerCommandSource source, int needed, int available, boolean willAutoGather) {
        int shortage = Math.max(0, needed - available);
        String msg = "Shelter needs " + needed + " cheap blocks (have " + available + ", short " + shortage + "). "
                + (shortage == 0 ? "Enough materials on hand."
                : willAutoGather ? "Proceeding to collect the shortfall." : "Say 'proceed' in the command to let me gather.");
        LOGGER.info("Shelter preflight: {}", msg);
        ChatUtils.sendSystemMessage(source, msg);
    }

    private void ensureBuildStock(ServerCommandSource source, ServerPlayerEntity bot, int needed, BlockPos returnPos) {
        int available = countBuildBlocks(bot);
        int toGather = Math.max(0, needed - available);
        if (toGather <= 0) {
            return;
        }
        ChatUtils.sendSystemMessage(source,
                "Gathering shelter materials nearby (staying near the build site)."
        );
        gatherBuildBlocksViaDescent(source, bot, toGather, returnPos);
        if (countBuildBlocks(bot) < needed) {
            gatherBuildBlocksNearby(bot, returnPos, Math.min(needed, toGather));
        }
    }

    private void gatherBuildBlocksViaDescent(ServerCommandSource source,
                                             ServerPlayerEntity bot,
                                             int toGather,
                                             BlockPos anchor) {
        if (toGather <= 0) {
            return;
        }
        Direction facing = bot.getHorizontalFacing();
        Map<String, Object> shared = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        params.put("count", Math.max(8, Math.min(96, toGather)));
        params.put("descentBlocks", 10);
        params.put("issuerFacing", facing.asString());
        params.put("lockDirection", true);
        SkillContext ctx = new SkillContext(source, shared, params);
        SkillExecutionResult res = new CollectDirtSkill().execute(ctx);
        if (!res.success()) {
            LOGGER.warn("Shelter gather: descent shortfall: {}", res.message());
        }
        if (anchor != null) {
            moveToBuildSite(source, bot, anchor);
        }
    }

    private void gatherBuildBlocksNearby(ServerPlayerEntity bot, BlockPos anchor, int target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        int gathered = 0;
        int radius = 6;
        BlockPos origin = anchor != null ? anchor : bot.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -1, -radius), origin.add(radius, 2, radius))) {
            if (gathered >= target) {
                break;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) {
                continue;
            }
            if (TreeDetector.isNearHumanBlocks(world, pos, 2)) {
                continue;
            }
            mineSoft(bot, pos);
            gathered++;
        }
    }

    private void levelBuildSite(ServerWorld world,
                                ServerPlayerEntity bot,
                                BlockPos center,
                                int radius,
                                BuildCounters counters) {
        int floorY = center.getY();
        int maxCutY = floorY + 3;
        int minCutY = floorY + 1;
        for (int dx = -radius - 1; dx <= radius + 1; dx++) {
            for (int dz = -radius - 1; dz <= radius + 1; dz++) {
                for (int y = maxCutY; y >= minCutY; y--) {
                    BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir() || state.isReplaceable()) {
                        continue;
                    }
                    if (TreeDetector.isNearHumanBlocks(world, pos, 2)) {
                        continue;
                    }
                    mineSoft(bot, pos);
                    if (counters != null) {
                        counters.placedBlocks++;
                    }
                }
            }
        }

        int minY = floorY - 3;
        int maxY = floorY - 1;
        for (int dx = -radius - 1; dx <= radius + 1; dx++) {
            for (int dz = -radius - 1; dz <= radius + 1; dz++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos support = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (!world.getFluidState(support).isEmpty()) {
                        continue;
                    }
                    BlockState state = world.getBlockState(support);
                    if (state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) {
                        placeBlock(bot, support, counters);
                    }
                }
            }
        }

        for (int dx = -radius - 1; dx <= radius + 1; dx++) {
            for (int dz = -radius - 1; dz <= radius + 1; dz++) {
                BlockPos foot = new BlockPos(center.getX() + dx, floorY, center.getZ() + dz);
                BlockState state = world.getBlockState(foot);
                if (!state.isAir() && !state.isReplaceable()) {
                    if (!TreeDetector.isNearHumanBlocks(world, foot, 2)) {
                        mineSoft(bot, foot);
                    }
                }
            }
        }
    }

    private void ensureClearBuildSiteWithWoodcut(ServerCommandSource source,
                                                 ServerPlayerEntity bot,
                                                 BlockPos center,
                                                 int radius) {
        if (TreeDetector.findNearestAnyLog(bot, radius + 4, 6, null).isEmpty()) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("count", 6);
        params.put("searchRadius", radius + 4);
        params.put("verticalRange", 8);
        SkillContext ctx = new SkillContext(source, new HashMap<>(), params);
        SkillExecutionResult res = new WoodcutSkill().execute(ctx);
        if (!res.success()) {
            LOGGER.warn("Shelter: woodcut during hovel prep failed: {}", res.message());
        }
        moveToBuildSite(source, bot, center);
    }

    private int countTargetsRemaining(ServerWorld world, BlockPos center, int radius, int wallHeight, Direction doorSide) {
        int floorY = center.getY();
        int roofY = floorY + wallHeight;
        int remaining = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                if (perimeter) {
                    for (int y = floorY; y <= roofY - 1; y++) {
                        BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                        if (isDoorGap(pos, center, radius, doorSide, floorY)) {
                            continue;
                        }
                        BlockState state = world.getBlockState(pos);
                        if (state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS) || state.isOf(Blocks.SNOW)) {
                            remaining++;
                        }
                    }
                }
                BlockPos roof = new BlockPos(center.getX() + dx, roofY, center.getZ() + dz);
                BlockState roofState = world.getBlockState(roof);
                if (roofState.isAir() || roofState.isReplaceable() || roofState.isIn(BlockTags.LEAVES) || roofState.isIn(BlockTags.LOGS) || roofState.isOf(Blocks.SNOW)) {
                    remaining++;
                }
            }
        }
        return remaining;
    }

    private boolean placeBlockDirectIfWithinReach(ServerPlayerEntity bot, BlockPos pos, BuildCounters counters) {
        if (bot == null || pos == null) {
            return false;
        }
        String failureReason = null;
        if (!canPlaceWithSight(bot, pos)) {
            if (!isAdjacentPlacement(bot, pos)) {
                if (counters != null) {
                    counters.reachFailures++;
                }
                failureReason = "LOS";
                LOGGER.debug("Shelter: LOS reject at {}", pos);
                return false;
            }
            failureReason = "adjacentLOS";
        }
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        if (botPos.squaredDistanceTo(center) > REACH_DISTANCE_SQ) {
            if (counters != null) {
                counters.reachFailures++;
            }
            failureReason = "reach-distance";
            return false;
        }
        Item blockItem = selectBuildItem(bot);
        if (blockItem == null) {
            if (counters != null) {
                counters.noMaterials++;
            }
            failureReason = "noMaterials";
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
        LOGGER.debug("Shelter: attempting placement at {} (reason={})", pos, failureReason == null ? "placing" : failureReason);
        net.shasankp000.Entity.LookController.faceBlock(bot, pos);
        boolean placed = BotActions.placeBlockAt(bot, pos, Direction.UP, List.of(blockItem));
        if (counters != null && counters.attemptedPlacements % PLACEMENT_THROTTLE_EVERY == 0) {
            sleepQuiet(PLACEMENT_THROTTLE_MS);
        }
        if (placed && counters != null) {
            counters.placedBlocks++;
        }
        if (!placed) {
            LOGGER.debug("Shelter: BotActions placement failed at {} (reason={})", pos, failureReason == null ? "unknown" : failureReason);
        }
        return placed;
    }

    private boolean isAdjacentPlacement(ServerPlayerEntity bot, BlockPos pos) {
        BlockPos bp = bot.getBlockPos();
        return Math.abs(bp.getX()-pos.getX())<=1 && Math.abs(bp.getY()-pos.getY())<=1 && Math.abs(bp.getZ()-pos.getZ())<=1;
    }
    private boolean canPlaceWithSight(ServerPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (BlockInteractionService.canInteract(bot, pos, REACH_DISTANCE_SQ)) {
            return true;
        }
        BlockPos below = pos.down();
        if (world.getBlockState(below).isSolidBlock(world, below)
                && BlockInteractionService.canInteract(bot, below, REACH_DISTANCE_SQ)) {
            return true;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = pos.offset(dir);
            if (world.getBlockState(neighbor).isSolidBlock(world, neighbor)
                    && BlockInteractionService.canInteract(bot, neighbor, REACH_DISTANCE_SQ)) {
                return true;
            }
        }
        return false;
    }

    private boolean placeBlock(ServerPlayerEntity bot, BlockPos pos, BuildCounters counters) {
        if (bot == null || pos == null) {
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
        if (!BlockInteractionService.canInteract(bot, pos, REACH_DISTANCE_SQ)) {
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

    private Item selectBuildItem(ServerPlayerEntity bot) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (BUILD_BLOCKS.contains(stack.getItem())) {
                return stack.getItem();
            }
        }
        return null;
    }

    private int countBuildBlocks(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (BUILD_BLOCKS.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean hasOption(SkillContext context, String... names) {
        Object options = context.parameters().get("options");
        if (options instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                String opt = o.toString().toLowerCase(Locale.ROOT);
                for (String name : names) {
                    if (opt.equals(name.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isStructureCell(BlockPos center, int radius, int wallHeight, BlockPos pos) {
        int floorY = center.getY();
        int roofY = floorY + wallHeight;
        int dx = pos.getX() - center.getX();
        int dz = pos.getZ() - center.getZ();
        boolean within = Math.max(Math.abs(dx), Math.abs(dz)) <= radius;
        if (!within) {
            return false;
        }
        if (pos.getY() == roofY) {
            return true;
        }
        if (pos.getY() < floorY || pos.getY() > roofY - 1) {
            return false;
        }
        return Math.abs(dx) == radius || Math.abs(dz) == radius;
    }

    private boolean isDoorGap(BlockPos pos, BlockPos center, int radius, Direction doorSide, int floorY) {
        if (doorSide == null || pos == null) {
            return false;
        }
        if (pos.getY() != floorY + 1 && pos.getY() != floorY + 2) {
            return false;
        }
        BlockPos lower = doorwayLower(center, radius, doorSide, floorY + 1);
        return pos.equals(lower) || pos.equals(lower.up());
    }

    private BlockPos doorwayLower(BlockPos center, int radius, Direction doorSide, int y) {
        return new BlockPos(
                center.getX() + doorSide.getOffsetX() * radius,
                y,
                center.getZ() + doorSide.getOffsetZ() * radius
        );
    }

    private void ensureDoorwayOpen(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius, Direction doorSide) {
        if (doorSide == null) {
            return;
        }
        int floorY = center.getY();
        BlockPos lower = doorwayLower(center, radius, doorSide, floorY + 1);
        BlockPos upper = lower.up();
        clearDoorSoft(world, bot, lower);
        clearDoorSoft(world, bot, upper);
    }

    private void clearDoorSoft(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable()) {
            return;
        }
        if (state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapdoorBlock
                || state.getBlock() instanceof FenceGateBlock) {
            return;
        }
        if (!TreeDetector.isNearHumanBlocks(world, pos, 2)) {
            mineSoft(bot, pos);
        }
    }

    private boolean hasNearbyFluidOrFarmland(ServerWorld world, BlockPos center, int radius) {
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -2, -radius), center.add(radius, 2, radius))) {
            if (!world.getFluidState(pos).isEmpty()) {
                return true;
            }
            BlockState state = world.getBlockState(pos);
            if (isFarmBlock(state)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFarmBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.isOf(Blocks.FARMLAND) || state.isIn(BlockTags.CROPS);
    }

    private boolean isStandable(ServerWorld world, BlockPos foot) {
        if (world == null || foot == null) {
            return false;
        }
        if (!world.getFluidState(foot).isEmpty() || !world.getFluidState(foot.up()).isEmpty()) {
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

    private double evaluateFlatness(ServerWorld world, BlockPos center, int radius) {
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
            return 0.0;
        }
        return maxY - minY;
    }

    private void mineSoft(ServerPlayerEntity bot, BlockPos pos) {
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (!BlockInteractionService.canInteract(bot, pos, REACH_DISTANCE_SQ)) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (isFarmBlock(state)) {
            return;
        }
        BlockState below = world.getBlockState(pos.down());
        if (below.isOf(Blocks.FARMLAND)) {
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

    private boolean yieldToImmediateThreats(ServerPlayerEntity bot, long maxWaitMs) {
        if (bot == null) {
            return false;
        }
        List<net.minecraft.entity.Entity> hostiles = AutoFaceEntity.detectNearbyEntities(bot, 10.0D)
                .stream()
                .filter(EntityUtil::isHostile)
                .toList();
        if (hostiles.isEmpty()) {
            return false;
        }
        BotEventHandler.engageImmediateThreats(bot);
        long deadline = System.currentTimeMillis() + Math.max(250L, maxWaitMs);
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
        }
        return true;
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void sweepDrops(ServerCommandSource source, double radius, double vRange, int maxTargets, long durationMs) {
        try {
            DropSweeper.sweep(source.withSilent().withMaxLevel(4), radius, vRange, maxTargets, durationMs);
            DropSweeper.sweep(source.withSilent().withMaxLevel(4), radius + 2.0, vRange + 1.0, Math.max(maxTargets, 40), durationMs + 3000);
        } catch (Exception e) {
            LOGGER.warn("Shelter drop-sweep failed: {}", e.getMessage());
        }
    }

    private static List<Item> buildPillarBlocks() {
        List<Item> items = new ArrayList<>(SCAFFOLD_BLOCKS);
        for (Item item : BUILD_BLOCKS) {
            if (!items.contains(item)) {
                items.add(item);
            }
        }
        return Collections.unmodifiableList(items);
    }
}
