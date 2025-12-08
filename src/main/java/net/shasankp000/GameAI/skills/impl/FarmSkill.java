package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.Entity.LookController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class FarmSkill implements Skill {
    private static final Logger LOGGER = LoggerFactory.getLogger("farm-skill");
    private static final List<Item> DIRT_BLOCK_PREFERENCE = List.of(Items.DIRT, Items.COARSE_DIRT, Items.PODZOL, Items.GRASS_BLOCK);
    private static final Set<Block> TILLABLE_SURFACES = Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.DIRT_PATH);
    private static final int HYDRATION_RADIUS = 4; // Minecraft farmland hydration radius
    private static final int PATCH_SIZE = HYDRATION_RADIUS * 2 + 1;
    private static final int SEARCH_RADIUS_PATCHES = 2;
    private static final int PATCH_AREA = PATCH_SIZE * PATCH_SIZE;
    private static final double MAX_CENTER_DISTANCE_SQ = 16.0; // Relaxed to 4 blocks
    private static final int MAX_SURFACE_CLEAR_HEIGHT = 3;
    private static final Set<Block> SURFACE_COVER_BLOCKS = Set.of(
            Blocks.SNOW,
            Blocks.SNOW_BLOCK,
            Blocks.TALL_GRASS,
            Blocks.LARGE_FERN,
            Blocks.FERN,
            Blocks.DEAD_BUSH,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.LILY_PAD
    );

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
        UUID uuid = bot.getUuid();

        BlockPos center = selectFarmCenter(bot, world);
        LOGGER.info("Bot {} initiating farming workflow at {} (radius {})", bot.getName().getString(), center, HYDRATION_RADIUS);

        // Target the block ABOVE the center (where we stand)
        BlockPos standTarget = center.up();

        // Ensure we don't jump around (use DIRECT movement only)
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                standTarget, 
                standTarget,
                null,
                null,
                bot.getHorizontalFacing()
        );
        MovementService.MovementResult moveResult = MovementService.execute(source, bot, plan, false);
        if (!moveResult.success()) {
            LOGGER.warn("Unable to reach farm center at {}: {}", center, moveResult.detail());
            ChatUtils.sendSystemMessage(source, "Unable to reach the intended farm area.");
            return SkillExecutionResult.failure("Failed to reach farm center.");
        }
        
        // Check distance to center (ignoring Y for "close enough" if we are above/on it)
        double h_dx = bot.getX() - (center.getX() + 0.5);
        double h_dz = bot.getZ() - (center.getZ() + 0.5);
        double horizontalDistSq = h_dx * h_dx + h_dz * h_dz;

        if (horizontalDistSq > MAX_CENTER_DISTANCE_SQ) {
            LOGGER.warn("Bot {} ended {:.2f} blocks away from center {} (needed <= {:.1f})",
                    bot.getName().getString(), Math.sqrt(horizontalDistSq), center, Math.sqrt(MAX_CENTER_DISTANCE_SQ));
            ChatUtils.sendSystemMessage(source, "Could not get close enough to the farm center.");
            return SkillExecutionResult.failure("Failed to reach farm center closely enough.");
        }

        BlockPos waterPos = center;
        PlotStats stats = preparePlot(source, bot, world, waterPos);
        if (stats == null) {
            ChatUtils.sendSystemMessage(source, "Failed to prepare the farm plot.");
            return SkillExecutionResult.failure("Unable to prepare farm plot.");
        }

        if (!digWaterHole(world, bot, source, waterPos)) {
            return SkillExecutionResult.failure("Unable to place irrigation water.");
        }

        if (!positionForWatering(source, bot, waterPos)) {
            return SkillExecutionResult.failure("Unable to position for irrigation.");
        }

        // Place water if bot has a water bucket
        int waterBucketSlot = ensureHotbarItem(bot, Items.WATER_BUCKET);
        if (waterBucketSlot >= 0) {
            LOGGER.debug("Water bucket available in slot {}", waterBucketSlot);
            selectHotbarSlot(bot, waterBucketSlot);
            ItemStack stack = bot.getInventory().getStack(waterBucketSlot);
            
            boolean waterPlaced = false;
            List<Direction> facesToTry = new ArrayList<>();
            facesToTry.add(Direction.UP); // Floor of the hole
            for (Direction h : Direction.Type.HORIZONTAL) {
                facesToTry.add(h); // Walls of the hole
            }
            
            for (Direction clickFace : facesToTry) {
                BlockPos targetBlock;
                Direction side;
                
                if (clickFace == Direction.UP) {
                    targetBlock = waterPos.down(); // The floor block
                    side = Direction.UP; // Click the top of the floor
                } else {
                    targetBlock = waterPos.offset(clickFace); // The wall block
                    side = clickFace.getOpposite(); // The face of that block pointing to waterPos
                }
                
                if (world.getBlockState(targetBlock).isSolidBlock(world, targetBlock)) {
                    LOGGER.debug("Trying to place water on {} face of {}", side, targetBlock);
                    LookController.faceBlock(bot, targetBlock);
                    try { Thread.sleep(50); } catch (InterruptedException e) {}
                    
                    ActionResult result = stack.useOnBlock(new net.minecraft.item.ItemUsageContext(bot, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(targetBlock), side, targetBlock, false)));
                            
                    if (result.isAccepted()) {
                        bot.swingHand(Hand.MAIN_HAND, true);
                        LOGGER.info("Placed irrigation water at {} using {} face of {}", waterPos, side, targetBlock);
                        waterPlaced = true;
                        break;
                    }
                }
            }

            if (!waterPlaced) {
                LOGGER.warn("Water placement at {} failed completely.", waterPos);
                ChatUtils.sendSystemMessage(source, "Water placement failed at " + waterPos + "; is the bucket full and reachable?");
                return SkillExecutionResult.failure("Unable to place irrigation water.");
            }
        } else {
            ChatUtils.sendSystemMessage(source, "I don't have water. Please give me a water bucket; pausing farm.");
            SkillManager.requestSkillPause(bot, "Missing water bucket for farming.");
            return SkillExecutionResult.failure("Paused: missing water bucket.");
        }

        int seedSlot = ensureHotbarItem(bot, Items.WHEAT_SEEDS);
        if (seedSlot < 0) {
            LOGGER.warn("No wheat seeds detected for farm skill near {}", center);
            ChatUtils.sendSystemMessage(source, "No wheat seeds found.");
            return SkillExecutionResult.failure("Missing seeds.");
        }
        LOGGER.debug("Wheat seeds ready in slot {}", seedSlot);

        if (!ensureHoeInHotbar(bot)) {
            ChatUtils.sendSystemMessage(source, "I need a hoe to till this area; please place one in my inventory.");
            return SkillExecutionResult.failure("Missing hoe.");
        }

        selectHotbarSlot(bot, seedSlot);
        int plantedCount = 0;
        for (int dx = -HYDRATION_RADIUS; dx <= HYDRATION_RADIUS; dx++) {
            for (int dz = -HYDRATION_RADIUS; dz <= HYDRATION_RADIUS; dz++) {
                BlockPos pos = waterPos.add(dx, 0, dz);
                if (pos.equals(waterPos)) continue;
                BlockState bs = world.getBlockState(pos);
                if (bs.isOf(Blocks.FARMLAND) && world.isAir(pos.up())) {
                    ItemStack stack = bot.getMainHandStack();
                    if (stack.isEmpty() || !stack.isOf(Items.WHEAT_SEEDS)) {
                        int reseedSlot = ensureHotbarItem(bot, Items.WHEAT_SEEDS);
                        if (reseedSlot == -1) {
                            LOGGER.info("Seeds depleted after planting {} plots", plantedCount);
                            return SkillExecutionResult.failure("Missing seeds.");
                        }
                        selectHotbarSlot(bot, reseedSlot);
                        stack = bot.getMainHandStack();
                    }
                    if (stack.isEmpty()) {
                        LOGGER.info("Seeds empty before planting at {}", pos);
                        return SkillExecutionResult.failure("Missing seeds.");
                    }
                    LookController.faceBlock(bot, pos);
                    ActionResult plantResult = stack.useOnBlock(new net.minecraft.item.ItemUsageContext(bot, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false)));
                    if (plantResult.isAccepted()) {
                        bot.swingHand(Hand.MAIN_HAND, true);
                        plantedCount++;
                    } else {
                        LOGGER.debug("Planting attempt at {} returned {}", pos, plantResult);
                    }
                }
            }
        }
        LOGGER.info("Farm completed: hoed {} plots, filled {} gaps, planted {} seeds", stats.tilled(), stats.dirtFilled(), plantedCount);

        return SkillExecutionResult.success("Farmed a hydrated area and planted wheat.");
    }

    private static PlotStats preparePlot(ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         ServerWorld world,
                                         BlockPos waterPos) {
        int tilled = 0;
        int dirtFilled = 0;
        BlockPos waterBase = waterPos.down();
        
        for (int dx = -HYDRATION_RADIUS; dx <= HYDRATION_RADIUS; dx++) {
            for (int dz = -HYDRATION_RADIUS; dz <= HYDRATION_RADIUS; dz++) {
                BlockPos pos = waterPos.add(dx, 0, dz);
                if (pos.equals(waterPos)) continue;
                BlockState bs = world.getBlockState(pos);
                if (bs.isOf(Blocks.FARMLAND) && world.isAir(pos.up())) {
                    continue;
                }
                
                clearSurfaceObstacles(world, bot, pos);
                
                if (botSquaredDistance(bot, pos) > 36.0) {
                    MovementService.MovementPlan step = new MovementService.MovementPlan(
                            MovementService.Mode.DIRECT, pos.up(), pos.up(), null, null, bot.getHorizontalFacing());
                    MovementService.execute(source, bot, step, false);
                }
                
                BlockState above = world.getBlockState(pos.up());
                if (!above.isAir() && above.isReplaceable()) {
                    mineBlock(bot, pos.up(), world);
                    LOGGER.debug("Cleared {} while preparing the plot (was {})", pos.up(), above.getBlock());
                }
                
                BlockState base = world.getBlockState(pos);
                if (base.isAir()) {
                    LookController.faceBlock(bot, pos);
                    if (fillWithDirt(bot, world, pos)) {
                        dirtFilled++;
                    } else {
                        LOGGER.debug("Unable to fill {} with dirt; skipping tilling", pos);
                        continue;
                    }
                    base = world.getBlockState(pos);
                }
                
                Block baseBlock = base.getBlock();
                if (!TILLABLE_SURFACES.contains(baseBlock) && baseBlock != Blocks.FARMLAND) {
                    LOGGER.debug("Skipping {} because base {} cannot be hoed", pos, baseBlock);
                    continue;
                }
                if (baseBlock != Blocks.FARMLAND) {
                    LookController.faceBlock(bot, pos);
                    boolean hoed = BotActions.useHoe(bot, pos);
                    if (hoed) {
                        tilled++;
                        LOGGER.debug("Hoed {} into farmland", pos);
                    } else {
                        LOGGER.warn("Hoe failed to till {}", pos);
                    }
                } else {
                    LOGGER.debug("Plot {} already farmland", pos);
                }
            }
        }
        return new PlotStats(tilled, dirtFilled);
    }

    private static void clearSurfaceObstacles(ServerWorld world, ServerPlayerEntity bot, BlockPos groundPos) {
        // groundPos is the dirt layer. We check blocks ABOVE it.
        for (int offsetY = 1; offsetY <= MAX_SURFACE_CLEAR_HEIGHT; offsetY++) {
            BlockPos checkPos = groundPos.up(offsetY);
            BlockState state = world.getBlockState(checkPos);
            if (state.isAir()) {
                continue;
            }
            if (state.isReplaceable() || shouldRemoveCover(state)) {
                mineBlock(bot, checkPos, world);
                LOGGER.debug("Cleared {} at {} while preparing farm plot", state.getBlock(), checkPos);
                continue;
            }
            if (offsetY > 1) {
                LOGGER.debug("Left solid block {} at {} while prepping plot", state.getBlock(), checkPos);
            }
        }
    }

    private static boolean shouldRemoveCover(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.isIn(BlockTags.LOGS)
                || state.isIn(BlockTags.LEAVES)
                || state.isIn(BlockTags.SAPLINGS)
                || state.isIn(BlockTags.FLOWERS)
                || SURFACE_COVER_BLOCKS.contains(state.getBlock());
    }

    private static boolean digWaterHole(ServerWorld world, ServerPlayerEntity bot, ServerCommandSource source, BlockPos waterPos) {
        // waterPos is the block where water source goes.
        // waterBase is the block UNDER it (must be solid).
        
        BlockPos waterBase = waterPos.down();
        BlockState centerState = world.getBlockState(waterPos);
        BlockState baseState = world.getBlockState(waterBase);
        
        LOGGER.debug("Starting water hole prep at {} (center={}, base={})", waterPos, centerState.getBlock(), baseState.getBlock());
        
        // 1. Clear the center (Y)
        if (!centerState.isAir()) {
            mineBlock(bot, waterPos, world);
        }
        
        // 2. Ensure base (Y-1) is solid. DO NOT MINE IT if it's already solid!
        if (baseState.isAir()) {
            LookController.faceBlock(bot, waterBase);
            if (!fillWithDirt(bot, world, waterBase)) {
                LOGGER.warn("Unable to fill water hole base at {}", waterBase);
                ChatUtils.sendSystemMessage(source, "I need more dirt to shape the water hole.");
                return false;
            }
        }
        
        LOGGER.debug("Dug water hole at {}", waterPos);
        return true;
    }

    private record PlotStats(int tilled, int dirtFilled) {}

    private static boolean positionForWatering(ServerCommandSource source,
                                               ServerPlayerEntity bot,
                                               BlockPos waterPos) {
        // Custom logic: Find a solid neighbor at waterPos level (e.g. Farmland/Dirt)
        // We want to stand ON it (Y+1).
        // waterPos is the hole (Air). waterPos.neighbors should be Dirt/Farmland.
        
        ServerWorld world = source.getWorld();
        BlockPos bestStand = null;
        double minDist = Double.MAX_VALUE;

        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = waterPos.offset(dir);
            
            // Valid standing spot:
            // neighbor (Y) is solid.
            // neighbor.up() (Y+1) is air.
            // neighbor.up(2) (Y+2) is air.
            
            if (world.getBlockState(neighbor).isSolidBlock(world, neighbor) &&
                world.getBlockState(neighbor.up()).isAir() &&
                world.getBlockState(neighbor.up(2)).isAir()) {
                
                BlockPos standPos = neighbor.up();
                double d = bot.squaredDistanceTo(Vec3d.ofCenter(standPos));
                if (d < minDist) {
                    minDist = d;
                    bestStand = standPos;
                }
            }
        }

        BlockPos target = (bestStand != null) ? bestStand : waterPos.up(); // Fallback: try standing above hole?
        
        LOGGER.info("positionForWatering selected target {}", target);
        
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                target,
                target,
                null,
                null,
                bot.getHorizontalFacing()
        );
        
        return MovementService.execute(source, bot, plan, false).success();
    }

    private static boolean fillWithDirt(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isAir()) {
            return true;
        }
        if (!hasDirtSupply(bot)) {
            LOGGER.warn("No dirt available to flatten {} -- supply exhausted", pos);
            return false;
        }
        if (BotActions.placeBlockAt(bot, pos, Direction.UP, DIRT_BLOCK_PREFERENCE)) {
            LOGGER.debug("Filled {} with dirt to create a flat surface", pos);
            return true;
        }
        LOGGER.debug("Unable to fill {} with dirt (missing supply or no support)", pos);
        return false;
    }

    private static boolean hasDirtSupply(ServerPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        for (Item dirtType : DIRT_BLOCK_PREFERENCE) {
            if (findInventoryItemSlot(inventory, dirtType) != -1) {
                return true;
            }
        }
        return false;
    }

    private static boolean ensureHoeInHotbar(ServerPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        int hoeSlot = findHoeSlotInInventory(inventory);
        if (hoeSlot == -1) {
            LOGGER.warn("No hoe found for farm skill near {}", bot.getBlockPos());
            return false;
        }
        if (hoeSlot < 9) {
            return true;
        }
        int hotbarTarget = findEmptyHotbarSlot(inventory);
        if (hotbarTarget == -1) {
            hotbarTarget = inventory.getSelectedSlot();
        }
        swapStacks(inventory, hoeSlot, hotbarTarget);
        LOGGER.debug("Moved hoe from slot {} to hotbar slot {}", hoeSlot, hotbarTarget);
        return true;
    }

    private static int findHoeSlotInInventory(PlayerInventory inventory) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof HoeItem) {
                return i;
            }
        }
        return -1;
    }

    private static BlockPos selectFarmCenter(ServerPlayerEntity bot, ServerWorld world) {
        BlockPos feetPos = bot.getBlockPos();
        BlockPos base = alignToPatchGrid(feetPos);
        
        // Improved Vertical Scan:
        // We want to find a SOLID block to be the farm center.
        // Scan down from feet level (and slightly above) to find solid ground.
        
        BlockPos bestY = null;
        // Scan from Eye level down to 5 blocks below feet
        for (int y = feetPos.getY() + 1; y >= feetPos.getY() - 5; y--) {
            BlockPos check = new BlockPos(base.getX(), y, base.getZ());
            if (!world.getBlockState(check).isAir() && !shouldRemoveCover(world.getBlockState(check))) {
                // Found a solid block that isn't just grass/flowers
                bestY = check;
                break;
            }
        }
        
        if (bestY != null) {
            base = bestY;
        } else {
            // Fallback if floating or in air: just use feet - 1 if air
            if (world.getBlockState(base).isAir()) {
                base = base.down();
            }
        }
        
        BlockPos best = base;
        int bestScore = patchFarmlandCoverage(world, base);
        if (bestScore == 0) {
            LOGGER.debug("Starting new patch at {}", base);
            return base;
        }
        for (int radius = 1; radius <= SEARCH_RADIUS_PATCHES; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    BlockPos candidate = base.add(dx * PATCH_SIZE, 0, dz * PATCH_SIZE);
                    
                    // Re-adjust candidate Y to match terrain
                    BlockPos candidateSurface = null;
                    for (int y = candidate.getY() + 2; y >= candidate.getY() - 3; y--) {
                         BlockPos check = new BlockPos(candidate.getX(), y, candidate.getZ());
                         if (!world.getBlockState(check).isAir() && !shouldRemoveCover(world.getBlockState(check))) {
                             candidateSurface = check;
                             break;
                         }
                    }
                    if (candidateSurface != null) {
                        candidate = candidateSurface;
                    }
                    
                    int score = patchFarmlandCoverage(world, candidate);
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                        if (score == 0) {
                            LOGGER.debug("Found empty patch at {}", candidate);
                            return candidate;
                        }
                    }
                }
            }
        }
        if (!best.equals(base)) {
            LOGGER.debug("Advancing to farm patch {} ({} farmland blocks)", best, bestScore);
        } else {
            LOGGER.debug("Continuing at patch {} ({} farmland blocks)", base, bestScore);
        }
        return best;
    }

    private static BlockPos alignToPatchGrid(BlockPos pos) {
        int x = Math.round(pos.getX() / (float) PATCH_SIZE) * PATCH_SIZE;
        int z = Math.round(pos.getZ() / (float) PATCH_SIZE) * PATCH_SIZE;
        return new BlockPos(x, pos.getY(), z);
    }

    private static int patchFarmlandCoverage(ServerWorld world, BlockPos center) {
        int farmlandCount = 0;
        for (int dx = -HYDRATION_RADIUS; dx <= HYDRATION_RADIUS; dx++) {
            for (int dz = -HYDRATION_RADIUS; dz <= HYDRATION_RADIUS; dz++) {
                BlockPos pos = center.add(dx, 0, dz);
                if (world.getBlockState(pos).isOf(Blocks.FARMLAND)) {
                    farmlandCount++;
                }
            }
        }
        return farmlandCount;
    }

    private static double botSquaredDistance(ServerPlayerEntity bot, BlockPos pos) {
        BlockPos b = bot.getBlockPos();
        double dx = b.getX() - pos.getX();
        double dz = b.getZ() - pos.getZ();
        return dx*dx + dz*dz;
    }

    private static int ensureHotbarItem(ServerPlayerEntity bot, Item item) {
        PlayerInventory inventory = bot.getInventory();
        int hotbarSlot = findHotbarItemSlot(inventory, item);
        if (hotbarSlot != -1) {
            return hotbarSlot;
        }
        int stackSlot = findInventoryItemSlot(inventory, item);
        if (stackSlot == -1) {
            return -1;
        }
        int target = findEmptyHotbarSlot(inventory);
        if (target == -1) {
            target = inventory.getSelectedSlot();
        }
        swapStacks(inventory, stackSlot, target);
        LOGGER.debug("Moved {} from slot {} to hotbar slot {}", item.getTranslationKey(), stackSlot, target);
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
        if (slot >= 0 && slot < 9) {
            bot.getInventory().setSelectedSlot(slot);
        }
    }
    
    private static void mineBlock(ServerPlayerEntity bot, BlockPos pos, ServerWorld world) {
        LookController.faceBlock(bot, pos);
        boolean broken = world.breakBlock(pos, true, bot);
        if (broken) {
            bot.swingHand(Hand.MAIN_HAND, true);
        }
    }
}
