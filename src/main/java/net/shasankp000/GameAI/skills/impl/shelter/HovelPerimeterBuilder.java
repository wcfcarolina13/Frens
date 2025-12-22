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

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Robust hovel builder that uses an iterative goal-based approach.
 * Strategy:
 * 1. Level site.
 * 2. Build base walls from outside.
 * 3. Move inside and pillar up to complete upper walls and roof.
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

    private record HovelPlan(BlockPos center, Direction doorSide) {}

    private static final class BuildCounters {
        int placedBlocks = 0;
        int reachFailures = 0;
        int noMaterials = 0;
        int attemptedPlacements = 0;
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
        levelBuildSite(world, bot, center, radius, counters);
        
        int needed = estimatePlacementNeed(world, center, radius, wallHeight, doorSide);
        if (countBuildBlocks(bot) < needed) {
            ensureBuildStock(source, bot, needed, center);
        }

        List<BlockPos> walls = generateWallBlueprint(center, radius, wallHeight, doorSide);
        List<BlockPos> roof = generateRoofBlueprint(center, radius, wallHeight);

        ChatUtils.sendSystemMessage(source, "Hovel: building walls...");
        buildFromOutside(world, source, bot, center, radius, walls, counters);

        ChatUtils.sendSystemMessage(source, "Hovel: completing roof...");
        buildFromInside(world, source, bot, center, radius, wallHeight, walls, roof, counters);

        ensureDoorwayOpen(world, bot, center, radius, doorSide);
        placeInsideTorches(world, bot, center, radius);
        sweepDrops(source, radius + 5, 6.0, 60, 5000L);

        int missing = countMissing(world, walls) + countMissing(world, roof);
        if (missing == 0) {
            ChatUtils.sendSystemMessage(source, "Hovel complete!");
            return SkillExecutionResult.success("Hovel built.");
        } else {
            ChatUtils.sendSystemMessage(source, "Hovel mostly complete (" + missing + " blocks missing).");
            return SkillExecutionResult.failure("Hovel incomplete.");
        }
    }

    private List<BlockPos> generateWallBlueprint(BlockPos center, int radius, int height, Direction doorSide) {
        List<BlockPos> blueprint = new ArrayList<>();
        int floorY = center.getY();
        for (int y = floorY; y < floorY + height; y++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius || Math.abs(dz) == radius) {
                        BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                        if (!isDoorGap(pos, center, radius, doorSide, floorY)) {
                            blueprint.add(pos);
                        }
                    }
                }
            }
        }
        return blueprint;
    }

    private List<BlockPos> generateRoofBlueprint(BlockPos center, int radius, int height) {
        List<BlockPos> blueprint = new ArrayList<>();
        int roofY = center.getY() + height;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                blueprint.add(new BlockPos(center.getX() + dx, roofY, center.getZ() + dz));
            }
        }
        return blueprint;
    }

    private void buildFromOutside(ServerWorld world, ServerCommandSource source, ServerPlayerEntity bot, BlockPos center, int radius, List<BlockPos> walls, BuildCounters counters) {
        List<BlockPos> ring = orderedRing(center, radius + 1, center.getY());
        for (BlockPos ringPos : ring) {
            if (SkillManager.shouldAbortSkill(bot)) return;
            ensureRingStandable(world, bot, ringPos);
            if (!moveToRingPos(source, bot, ringPos)) continue;

            walls.stream()
                .filter(p -> p.getY() <= center.getY() + 2)
                .filter(p -> isMissing(world, p))
                .filter(p -> bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) <= REACH_DISTANCE_SQ)
                .forEach(p -> placeBlockDirectIfWithinReach(bot, p, counters));
        }
    }

    private void buildFromInside(ServerWorld world, ServerCommandSource source, ServerPlayerEntity bot, BlockPos center, int radius, int height, List<BlockPos> walls, List<BlockPos> roof, BuildCounters counters) {
        if (!moveToRingPos(source, bot, center)) {
            moveToBuildSite(source, bot, center);
        }

        int pillarHeight = Math.min(3, height - 1);
        List<BlockPos> pillar = new ArrayList<>();
        if (pillarUp(bot, pillarHeight, pillar)) {
            List<BlockPos> allGoal = new ArrayList<>(walls);
            allGoal.addAll(roof);
            
            boolean madeProgress = true;
            int stuckCycles = 0;
            while (madeProgress && stuckCycles < 10) {
                if (SkillManager.shouldAbortSkill(bot)) break;
                int startPlaced = counters.placedBlocks;
                
                allGoal.stream()
                    .filter(p -> isMissing(world, p))
                    .filter(p -> bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) <= REACH_DISTANCE_SQ)
                    .sorted(Comparator.comparingInt(BlockPos::getY))
                    .forEach(p -> placeBlockDirectIfWithinReach(bot, p, counters));
                
                if (counters.placedBlocks > startPlaced) {
                    madeProgress = true;
                    stuckCycles = 0;
                } else {
                    madeProgress = false;
                    stuckCycles++;
                }
            }
            
            Collections.reverse(pillar);
            for (BlockPos p : pillar) {
                mineSoft(bot, p);
                sleepQuiet(100L);
            }
        }
    }

    private boolean isMissing(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES);
    }

    private int countMissing(ServerWorld world, List<BlockPos> blueprint) {
        return (int) blueprint.stream().filter(p -> isMissing(world, p)).count();
    }

    private boolean placeBlockDirectIfWithinReach(ServerPlayerEntity bot, BlockPos pos, BuildCounters counters) {
        if (!isMissing((ServerWorld)bot.getEntityWorld(), pos)) return true;
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
        net.shasankp000.Entity.LookController.faceBlock(bot, pos);
        boolean ok = BotActions.placeBlockAt(bot, pos, Direction.UP, List.of(item));
        if (ok && counters != null) counters.placedBlocks++;
        sleepQuiet(PLACEMENT_DELAY_MS);
        return ok;
    }

    private boolean pillarUp(ServerPlayerEntity bot, int steps, List<BlockPos> placed) {
        if (steps <= 0) return true;
        Item item = selectBuildItem(bot);
        if (item == null) return false;
        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) return false;
            BlockPos current = bot.getBlockPos();
            BotActions.jump(bot);
            sleepQuiet(150L);
            if (BotActions.placeBlockAt(bot, current, Direction.UP, List.of(item))) {
                placed.add(current.toImmutable());
            } else { return false; }
            sleepQuiet(150L);
        }
        return true;
    }

    private void placeInsideTorches(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius) {
        if (radius < 2) return;
        List<BlockPos> targets = List.of(center.add(radius-1, 0, radius-1), center.add(-(radius-1), 0, -(radius-1)));
        for (BlockPos p : targets) {
            if (isMissing(world, p) && world.getBlockState(p.down()).isSolidBlock(world, p.down())) {
                BotActions.placeBlockAt(bot, p, Direction.UP, List.of(Items.TORCH));
            }
        }
    }

    private HovelPlan resolvePlan(ServerWorld world, ServerPlayerEntity bot, BlockPos origin, int radius, Direction preferredDoorSide, Map<String, Object> sharedState, boolean resumeRequested) {
        Direction door = preferredDoorSide != null ? preferredDoorSide : bot.getHorizontalFacing();
        return new HovelPlan(origin, door);
    }

    private boolean moveToRingPos(ServerCommandSource source, ServerPlayerEntity bot, BlockPos ringPos) {
        if (bot.getBlockPos().equals(ringPos)) return true;
        MovementService.MovementPlan plan = new MovementService.MovementPlan(MovementService.Mode.DIRECT, ringPos, ringPos, null, null, Direction.UP);
        MovementService.execute(source, bot, plan, false, true, true, false);
        return bot.getBlockPos().getSquaredDistance(ringPos) <= 2.25D;
    }

    private List<BlockPos> orderedRing(BlockPos center, int r, int y) {
        List<BlockPos> ring = new ArrayList<>();
        for (int x = center.getX() - r; x <= center.getX() + r; x++) ring.add(new BlockPos(x, y, center.getZ() - r));
        for (int z = center.getZ() - r + 1; z <= center.getZ() + r; z++) ring.add(new BlockPos(center.getX() + r, y, z));
        for (int x = center.getX() + r - 1; x >= center.getX() - r; x--) ring.add(new BlockPos(x, y, center.getZ() + r));
        for (int z = Math.max(center.getZ() + r - 1, center.getZ() - r + 1); z > center.getZ() - r; z--) ring.add(new BlockPos(center.getX() - r, y, z));
        return ring;
    }

    private boolean moveToBuildSite(ServerCommandSource source, ServerPlayerEntity bot, BlockPos center) {
        if (bot.getBlockPos().getSquaredDistance(center) <= 4.0D) return true;
        var planOpt = MovementService.planLootApproach(bot, center, MovementService.MovementOptions.skillLoot());
        if (planOpt.isEmpty()) return false;
        return MovementService.execute(source, bot, planOpt.get(), false, true, true, false).success();
    }

    private void levelBuildSite(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius, BuildCounters counters) {
        int floorY = center.getY();
        for (int dx = -radius - 1; dx <= radius + 1; dx++) {
            for (int dz = -radius - 1; dz <= radius + 1; dz++) {
                for (int y = floorY + 3; y >= floorY; y--) {
                    BlockPos p = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (!isMissing(world, p)) mineSoft(bot, p);
                }
                BlockPos support = new BlockPos(center.getX() + dx, floorY - 1, center.getZ() + dz);
                if (isMissing(world, support)) placeBlockDirectIfWithinReach(bot, support, counters);
            }
        }
    }

    private void mineSoft(ServerPlayerEntity bot, BlockPos pos) {
        if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > REACH_DISTANCE_SQ) return;
        try { MiningTool.mineBlock(bot, pos).get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private int countBuildBlocks(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && BUILD_BLOCKS.contains(stack.getItem())) total += stack.getCount();
        }
        return total;
    }

    private Item selectBuildItem(ServerPlayerEntity bot) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && BUILD_BLOCKS.contains(stack.getItem())) return stack.getItem();
        }
        return null;
    }

    private int estimatePlacementNeed(ServerWorld world, BlockPos center, int radius, int height, Direction doorSide) {
        return (radius * 2 + 1) * (radius * 2 + 1) + (radius * 8 * height);
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

    private boolean isDoorGap(BlockPos pos, BlockPos center, int radius, Direction doorSide, int floorY) {
        if (doorSide == null) return false;
        if (pos.getY() != floorY && pos.getY() != floorY + 1) return false;
        BlockPos doorPos = center.offset(doorSide, radius);
        return pos.getX() == doorPos.getX() && pos.getZ() == doorPos.getZ();
    }

    private void ensureDoorwayOpen(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius, Direction doorSide) {
        if (doorSide == null) return;
        BlockPos doorPos = center.offset(doorSide, radius);
        mineSoft(bot, doorPos);
        mineSoft(bot, doorPos.up());
    }

    private void ensureRingStandable(ServerWorld world, ServerPlayerEntity bot, BlockPos ringPos) {
        BlockPos below = ringPos.down();
        if (isMissing(world, below)) placeBlockDirectIfWithinReach(bot, below, null);
        if (!isMissing(world, ringPos)) mineSoft(bot, ringPos);
        if (!isMissing(world, ringPos.up())) mineSoft(bot, ringPos.up());
    }

    private void sweepDrops(ServerCommandSource source, double radius, double vRange, int maxTargets, long durationMs) {
        try { DropSweeper.sweep(source.withSilent(), radius, vRange, maxTargets, durationMs); } catch (Exception ignored) {}
    }
}
