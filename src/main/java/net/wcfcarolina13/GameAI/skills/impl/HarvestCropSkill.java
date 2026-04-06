package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.DropSweeper;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Scans a radius around the bot for fully mature crops on farmland
 * and carefully harvests them, picking up drops without trampling the soil.
 */
public class HarvestCropSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("harvest-crop-skill");

    private static final int SCAN_RADIUS = 16;
    private static final int SCAN_VERTICAL = 4;
    private static final int ACTION_DELAY_MS = 150;
    private static final int MIN_FREE_SLOTS_BEFORE_OFFLOAD = 3;
    private static final int CHEST_SEARCH_RADIUS = 12;
    private static final int CHEST_SEARCH_YSPAN = 6;
    private static final double REMEMBERED_CHEST_MAX_DIST_SQ = 48.0D * 48.0D;
    private static final double FINAL_SWEEP_RADIUS = 12.0D;
    private static final double FINAL_SWEEP_VERTICAL = 4.0D;
    private static final int FINAL_SWEEP_MAX_TARGETS = 12;
    private static final long FINAL_SWEEP_DURATION_MS = 12_000L;

    /** Crop blocks that grow on farmland and can be checked for maturity via CropBlock. */
    private static final Set<Block> FARMLAND_CROPS = Set.of(
            Blocks.WHEAT,
            Blocks.BEETROOTS,
            Blocks.CARROTS,
            Blocks.POTATOES
    );

    private static final Set<Item> PLANTABLE_SEEDS = Set.of(
            Items.WHEAT_SEEDS,
            Items.BEETROOT_SEEDS,
            Items.CARROT,
            Items.POTATO
    );

    private record ReplantTarget(BlockPos cropPos, Item seedItem) {}

    @Override
    public String name() {
        return "harvest";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No bot player available.");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("Not in a server world.");
        }

        List<BlockPos> matureCrops = findMatureCrops(world, bot.getBlockPos());
        List<BlockPos> initialEmptyPlots = findEmptyFarmland(world, bot.getBlockPos());
        if (matureCrops.isEmpty() && initialEmptyPlots.isEmpty()) {
            return SkillExecutionResult.failure("No mature crops or empty farmland nearby.");
        }

        // Sort nearest first to minimize walking
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        matureCrops.sort(Comparator.comparingDouble(p -> botPos.squaredDistanceTo(Vec3d.ofCenter(p))));

        int harvested = 0;
        int replanted = 0;
        int offloads = 0;
        int chestRestocks = 0;
        List<ReplantTarget> replantTargets = new ArrayList<>();
        Map<Item, Integer> pendingSeedReserve = new HashMap<>();
        try {
            if (matureCrops.isEmpty() && !initialEmptyPlots.isEmpty()) {
                LOGGER.info("Harvest fallback: no mature crops near {}, planting {} empty farmland plot(s) instead",
                        bot.getBlockPos().toShortString(), initialEmptyPlots.size());
            }
            if (countEmptySlots(bot) < MIN_FREE_SLOTS_BEFORE_OFFLOAD
                    && attemptInventoryOffload(bot, source, pendingSeedReserve, "pre-harvest")) {
                offloads++;
            }

            for (BlockPos cropPos : matureCrops) {
                abortIfRequested(bot);

                // Re-verify crop is still mature (may have been harvested by another entity)
                BlockState state = world.getBlockState(cropPos);
                if (!isMatureFarmlandCrop(state)) continue;

                // Territory check on the crop block
                if (!isMutationAuthorized(bot, world, cropPos)) continue;

                Item seedItem = seedItemForCrop(state);
                if (seedItem == null) continue;

                // The farmland is one block below the crop
                BlockPos farmlandPos = cropPos.down();

                // Find a non-farmland standing spot; if none, sneak on the farmland
                BlockPos stand = findStandingSpot(world, farmlandPos);
                boolean sneak = false;
                if (stand == null) {
                    stand = farmlandPos;
                    sneak = true;
                }

                BotActions.sneak(bot, sneak);
                moveTo(source, bot, stand.up());
                abortIfRequested(bot);

                LookController.faceBlock(bot, cropPos);
                sleep(ACTION_DELAY_MS);

                // Break the crop block using MiningTool (preserves held item)
                try {
                    String result = MiningTool.mineBlock(bot, cropPos, true).get(8, TimeUnit.SECONDS);
                    if (result != null && result.toLowerCase(Locale.ROOT).contains("complete")) {
                        harvested++;
                        replantTargets.add(new ReplantTarget(cropPos.toImmutable(), seedItem));
                        pendingSeedReserve.merge(seedItem, 1, Integer::sum);
                        sleep(120);
                        if (countEmptySlots(bot) < MIN_FREE_SLOTS_BEFORE_OFFLOAD
                                && attemptInventoryOffload(bot, source, pendingSeedReserve, "mid-harvest")) {
                            offloads++;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to harvest crop at {}: {}", cropPos.toShortString(), e.getMessage());
                }
            }

            if (!replantTargets.isEmpty()) {
                int[] restockCounter = new int[1];
                replanted = replantCrops(bot, world, source, replantTargets, pendingSeedReserve, restockCounter);
                chestRestocks = restockCounter[0];
            }
            List<BlockPos> emptyPlots = findEmptyFarmland(world, bot.getBlockPos());
            if (!emptyPlots.isEmpty()) {
                emptyPlots.sort(Comparator.comparingDouble(p -> botPos.squaredDistanceTo(Vec3d.ofCenter(p))));
                int[] restockCounter = new int[1];
                replanted += plantEmptyFarmland(bot, world, source, emptyPlots, restockCounter);
                chestRestocks += restockCounter[0];
            }
        } catch (SkillAbortException e) {
            BotActions.sneak(bot, false);
            return SkillExecutionResult.failure("Harvesting interrupted after " + harvested + " crop"
                    + (harvested != 1 ? "s" : "") + ".");
        }

        BotActions.sneak(bot, false);

        if (harvested > 0) {
            if (countEmptySlots(bot) < MIN_FREE_SLOTS_BEFORE_OFFLOAD
                    && attemptInventoryOffload(bot, source, pendingSeedReserve, "pre-sweep")) {
                offloads++;
            }
            sleep(300);
            boolean clearedForSweep = countEmptySlots(bot) > 0;
            if (!clearedForSweep && attemptInventoryOffload(bot, source, pendingSeedReserve, "pre-sweep-full")) {
                offloads++;
                clearedForSweep = true;
            }
            if (clearedForSweep) {
                try {
                    DropSweeper.sweep(source.withSilent(), FINAL_SWEEP_RADIUS, FINAL_SWEEP_VERTICAL, FINAL_SWEEP_MAX_TARGETS, FINAL_SWEEP_DURATION_MS);
                } catch (Exception e) {
                    LOGGER.warn("Harvest drop sweep failed: {}", e.getMessage());
                }
            }
            if (countEmptySlots(bot) < MIN_FREE_SLOTS_BEFORE_OFFLOAD
                    && attemptInventoryOffload(bot, source, pendingSeedReserve, "post-sweep")) {
                offloads++;
            }
        }

        if (harvested == 0) {
            if (replanted > 0) {
                String summary = "No ripe crops were ready, so I planted " + replanted + " empty plot"
                        + (replanted == 1 ? "" : "s") + ".";
                if (chestRestocks > 0) {
                    summary += " Pulled extra seeds from nearby chests " + chestRestocks + " time" + (chestRestocks == 1 ? "" : "s") + ".";
                }
                return SkillExecutionResult.success(summary);
            }
            return SkillExecutionResult.failure("Could not harvest or plant any crops.");
        }
        String summary = "Harvested " + harvested + " crop" + (harvested != 1 ? "s" : "")
                + " and replanted " + replanted + ".";
        if (offloads > 0) {
            summary += " Offloaded to chests " + offloads + " time" + (offloads == 1 ? "" : "s") + ".";
        }
        if (chestRestocks > 0) {
            summary += " Pulled extra seeds from nearby chests " + chestRestocks + " time" + (chestRestocks == 1 ? "" : "s") + ".";
        }
        return SkillExecutionResult.success(summary);
    }

    // ── Crop detection ───────────────────────────────────────────────────

    private static List<BlockPos> findMatureCrops(ServerWorld world, BlockPos center) {
        List<BlockPos> crops = new ArrayList<>();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -SCAN_VERTICAL; dy <= SCAN_VERTICAL; dy++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (isMatureFarmlandCrop(state)) {
                        // Verify it's actually on farmland
                        if (world.getBlockState(pos.down()).isOf(Blocks.FARMLAND)) {
                            crops.add(pos);
                        }
                    }
                }
            }
        }
        return crops;
    }

    private static List<BlockPos> findEmptyFarmland(ServerWorld world, BlockPos center) {
        List<BlockPos> plots = new ArrayList<>();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -SCAN_VERTICAL; dy <= SCAN_VERTICAL; dy++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (world.getBlockState(pos).isOf(Blocks.FARMLAND) && world.isAir(pos.up())) {
                        plots.add(pos);
                    }
                }
            }
        }
        return plots;
    }

    private static boolean isMatureFarmlandCrop(BlockState state) {
        if (state == null) return false;
        Block block = state.getBlock();
        if (!FARMLAND_CROPS.contains(block)) return false;
        // CropBlock.isMature() checks age == maxAge for all vanilla crop types
        if (block instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        return false;
    }

    private static Item seedItemForCrop(BlockState state) {
        if (state == null) {
            return null;
        }
        Block block = state.getBlock();
        if (block == Blocks.WHEAT) {
            return Items.WHEAT_SEEDS;
        }
        if (block == Blocks.BEETROOTS) {
            return Items.BEETROOT_SEEDS;
        }
        if (block == Blocks.CARROTS) {
            return Items.CARROT;
        }
        if (block == Blocks.POTATOES) {
            return Items.POTATO;
        }
        return null;
    }

    // ── Movement helpers (same as PlantSeedsSkill) ───────────────────────

    private static void moveTo(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        abortIfRequested(bot);
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT, target, target, null, null, null);
        MovementService.execute(source, bot, plan);
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
                && isPassable(world, ground.up());
    }

    private static boolean isPassable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        if (state.isAir() || state.isReplaceable()) return true;
        return state.getCollisionShape(world, pos).isEmpty();
    }

    // ── Utility ──────────────────────────────────────────────────────────

    private static boolean isMutationAuthorized(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (bot == null || world == null || pos == null) return false;
        var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, pos);
        return auth.allowed();
    }

    private static int replantCrops(ServerPlayerEntity bot,
                                    ServerWorld world,
                                    ServerCommandSource source,
                                    List<ReplantTarget> targets,
                                    Map<Item, Integer> pendingSeedReserve,
                                    int[] chestRestocks) {
        if (bot == null || world == null || source == null || targets == null || targets.isEmpty()) {
            return 0;
        }
        int replanted = 0;
        for (ReplantTarget target : targets) {
            abortIfRequested(bot);
            if (target == null || target.cropPos() == null || target.seedItem() == null) {
                continue;
            }
            BlockPos cropPos = target.cropPos();
            BlockPos farmlandPos = cropPos.down();
            if (!world.getBlockState(farmlandPos).isOf(Blocks.FARMLAND) || !world.isAir(cropPos)) {
                pendingSeedReserve.merge(target.seedItem(), -1, Integer::sum);
                continue;
            }
            if (!isMutationAuthorized(bot, world, cropPos)) {
                pendingSeedReserve.merge(target.seedItem(), -1, Integer::sum);
                continue;
            }
            int restocked = ensureSeedAvailability(bot, source, target.seedItem(), pendingSeedReserve.getOrDefault(target.seedItem(), 1));
            if (restocked > 0 && chestRestocks != null && chestRestocks.length > 0) {
                chestRestocks[0]++;
            }
            int seedSlot = ensureSeedHotbar(bot, target.seedItem());
            if (seedSlot < 0) {
                LOGGER.info("Harvest replant skipped at {}: no {} available", cropPos.toShortString(), target.seedItem());
                pendingSeedReserve.merge(target.seedItem(), -1, Integer::sum);
                continue;
            }

            BlockPos stand = findStandingSpot(world, farmlandPos);
            boolean sneak = false;
            if (stand == null) {
                stand = farmlandPos;
                sneak = true;
            }

            BotActions.sneak(bot, sneak);
            moveTo(source, bot, stand.up());
            abortIfRequested(bot);
            selectHotbarSlot(bot, seedSlot);
            LookController.faceBlock(bot, farmlandPos);
            sleep(ACTION_DELAY_MS);

            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(farmlandPos).add(0, 0.5, 0),
                    Direction.UP,
                    farmlandPos,
                    false
            );
            ActionResult result = bot.interactionManager.interactBlock(
                    bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
            if (result.isAccepted()) {
                bot.swingHand(Hand.MAIN_HAND, true);
                replanted++;
                sleep(120);
            } else {
                LOGGER.debug("Harvest replant failed at {} with seed {}", cropPos.toShortString(), target.seedItem());
            }
            pendingSeedReserve.merge(target.seedItem(), -1, Integer::sum);
        }
        BotActions.sneak(bot, false);
        return replanted;
    }

    private static int plantEmptyFarmland(ServerPlayerEntity bot,
                                          ServerWorld world,
                                          ServerCommandSource source,
                                          List<BlockPos> emptyPlots,
                                          int[] chestRestocks) {
        if (bot == null || world == null || source == null || emptyPlots == null || emptyPlots.isEmpty()) {
            return 0;
        }
        int planted = 0;
        for (BlockPos plot : emptyPlots) {
            abortIfRequested(bot);
            if (!world.getBlockState(plot).isOf(Blocks.FARMLAND) || !world.isAir(plot.up())) {
                continue;
            }
            if (!isMutationAuthorized(bot, world, plot.up())) {
                continue;
            }
            int seedSlot = ensureAnySeedHotbarOrRestock(bot, source, chestRestocks);
            if (seedSlot < 0) {
                LOGGER.info("Harvest fallback planting stopped: no seeds available for remaining empty farmland");
                break;
            }

            BlockPos stand = findStandingSpot(world, plot);
            boolean sneak = false;
            if (stand == null) {
                stand = plot;
                sneak = true;
            }

            BotActions.sneak(bot, sneak);
            moveTo(source, bot, stand.up());
            abortIfRequested(bot);
            selectHotbarSlot(bot, seedSlot);
            LookController.faceBlock(bot, plot);
            sleep(ACTION_DELAY_MS);

            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(plot).add(0, 0.5, 0),
                    Direction.UP,
                    plot,
                    false
            );
            ActionResult result = bot.interactionManager.interactBlock(
                    bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
            if (result.isAccepted()) {
                bot.swingHand(Hand.MAIN_HAND, true);
                planted++;
                sleep(120);
            }
        }
        BotActions.sneak(bot, false);
        return planted;
    }

    private static int ensureSeedAvailability(ServerPlayerEntity bot,
                                              ServerCommandSource source,
                                              Item seedItem,
                                              int neededCount) {
        if (bot == null || source == null || seedItem == null) {
            return 0;
        }
        if (countItem(bot.getInventory(), seedItem) >= Math.max(1, neededCount)) {
            return 0;
        }
        if (countEmptySlots(bot) == 0 && !attemptInventoryOffload(bot, source, Map.of(seedItem, Math.max(1, neededCount)), "seed-restock")) {
            return 0;
        }
        int missing = Math.max(1, neededCount) - countItem(bot.getInventory(), seedItem);
        if (missing <= 0) {
            return 0;
        }
        for (ChestStoreService.StorageChestCandidate candidate : findNearbyChestCandidates(source, bot)) {
            int moved = ChestStoreService.withdrawMatchingWalkOnly(
                    source,
                    bot,
                    candidate.pos(),
                    missing,
                    stack -> stack != null && !stack.isEmpty() && stack.isOf(seedItem));
            if (moved > 0) {
                LOGGER.info("Harvest restocked {}x {} from chest {}", moved, seedItem, candidate.pos().toShortString());
                return moved;
            }
        }
        return 0;
    }

    private static boolean attemptInventoryOffload(ServerPlayerEntity bot,
                                                   ServerCommandSource source,
                                                   Map<Item, Integer> pendingSeedReserve,
                                                   String phase) {
        if (bot == null || source == null) {
            return false;
        }
        Predicate<ItemStack> matcher = stack -> isHarvestOffloadCandidate(bot, stack, pendingSeedReserve);
        for (ChestStoreService.StorageChestCandidate candidate : findNearbyChestCandidates(source, bot)) {
            ChestStoreService.DepositProbeResult probe =
                    ChestStoreService.probeDepositMatchingObstacleAware(source, bot, candidate.pos(), matcher);
            LOGGER.info("Harvest offload probe: phase={} chest={} source={} moved={} reached={} interacted={}",
                    phase,
                    candidate.pos().toShortString(),
                    candidate.source(),
                    probe.moved(),
                    probe.reachedStand(),
                    probe.interacted());
            if (probe.moved() > 0) {
                return true;
            }
        }
        BlockPos placed = ChestStoreService.placeChestNearBot(source, bot, false);
        if (placed != null) {
            return ChestStoreService.depositMatchingWalkOnly(source, bot, placed, matcher) > 0;
        }
        return false;
    }

    private static List<ChestStoreService.StorageChestCandidate> findNearbyChestCandidates(ServerCommandSource source,
                                                                                           ServerPlayerEntity bot) {
        return ChestStoreService.listDepositChestCandidates(
                source,
                bot,
                null,
                CHEST_SEARCH_RADIUS,
                CHEST_SEARCH_YSPAN,
                REMEMBERED_CHEST_MAX_DIST_SQ);
    }

    private static boolean isHarvestOffloadCandidate(ServerPlayerEntity bot,
                                                     ItemStack stack,
                                                     Map<Item, Integer> pendingSeedReserve) {
        if (bot == null || stack == null || stack.isEmpty()) {
            return false;
        }
        if (ChestStoreService.isOffloadProtected(stack)) {
            return false;
        }
        Item item = stack.getItem();
        if (item == Items.CHEST || item == Items.CRAFTING_TABLE) {
            return false;
        }
        if (PLANTABLE_SEEDS.contains(item)) {
            return false;
        }
        return stack.getMaxCount() > 1;
    }

    private static int ensureSeedHotbar(ServerPlayerEntity bot, Item targetSeed) {
        if (bot == null || targetSeed == null) {
            return -1;
        }
        PlayerInventory inv = bot.getInventory();
        for (int i = 0; i < 9; i++) {
            if (!inv.getStack(i).isEmpty() && inv.getStack(i).isOf(targetSeed)) {
                return i;
            }
        }
        for (int i = 9; i < inv.size(); i++) {
            if (!inv.getStack(i).isEmpty() && inv.getStack(i).isOf(targetSeed)) {
                int target = findEmptyHotbarSlot(inv);
                if (target == -1) target = inv.getSelectedSlot();
                swapStacks(inv, i, target);
                return target;
            }
        }
        return -1;
    }

    private static int ensureAnySeedHotbarOrRestock(ServerPlayerEntity bot,
                                                    ServerCommandSource source,
                                                    int[] chestRestocks) {
        int slot = findAnySeedHotbarSlot(bot);
        if (slot >= 0) {
            return slot;
        }
        int moved = restockAnyPlantableSeed(bot, source);
        if (moved > 0 && chestRestocks != null && chestRestocks.length > 0) {
            chestRestocks[0]++;
        }
        return findAnySeedHotbarSlot(bot);
    }

    private static int findAnySeedHotbarSlot(ServerPlayerEntity bot) {
        if (bot == null) {
            return -1;
        }
        PlayerInventory inv = bot.getInventory();
        for (Item seed : PLANTABLE_SEEDS) {
            for (int i = 0; i < 9; i++) {
                if (!inv.getStack(i).isEmpty() && inv.getStack(i).isOf(seed)) {
                    return i;
                }
            }
        }
        for (Item seed : PLANTABLE_SEEDS) {
            for (int i = 9; i < inv.size(); i++) {
                if (!inv.getStack(i).isEmpty() && inv.getStack(i).isOf(seed)) {
                    int target = findEmptyHotbarSlot(inv);
                    if (target == -1) target = inv.getSelectedSlot();
                    swapStacks(inv, i, target);
                    return target;
                }
            }
        }
        return -1;
    }

    private static int restockAnyPlantableSeed(ServerPlayerEntity bot, ServerCommandSource source) {
        if (bot == null || source == null) {
            return 0;
        }
        if (countEmptySlots(bot) == 0 && !attemptInventoryOffload(bot, source, Map.of(), "generic-seed-restock")) {
            return 0;
        }
        for (Item seed : PLANTABLE_SEEDS) {
            for (ChestStoreService.StorageChestCandidate candidate : findNearbyChestCandidates(source, bot)) {
                int moved = ChestStoreService.withdrawMatchingWalkOnly(
                        source,
                        bot,
                        candidate.pos(),
                        1,
                        stack -> stack != null && !stack.isEmpty() && stack.isOf(seed));
                if (moved > 0) {
                    LOGGER.info("Harvest restocked {}x {} from chest {}", moved, seed, candidate.pos().toShortString());
                    return moved;
                }
            }
        }
        return 0;
    }

    private static int findEmptyHotbarSlot(PlayerInventory inventory) {
        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private static void swapStacks(PlayerInventory inventory, int a, int b) {
        if (inventory == null || a == b) return;
        ItemStack first = inventory.getStack(a);
        ItemStack second = inventory.getStack(b);
        inventory.setStack(a, second);
        inventory.setStack(b, first);
        inventory.markDirty();
    }

    private static void selectHotbarSlot(ServerPlayerEntity bot, int slot) {
        if (bot == null || slot < 0 || slot >= 9) return;
        PlayerInventory inventory = bot.getInventory();
        inventory.setSelectedSlot(slot);
        bot.setStackInHand(Hand.MAIN_HAND, inventory.getStack(slot));
        inventory.markDirty();
        bot.playerScreenHandler.syncState();
    }

    private static int countItem(PlayerInventory inventory, Item item) {
        if (inventory == null || item == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int countEmptySlots(ServerPlayerEntity bot) {
        if (bot == null) {
            return 0;
        }
        int empty = 0;
        Inventory inventory = bot.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private static void sleep(int ms) {
        if (Thread.currentThread().isInterrupted()) throw new SkillAbortException();
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

    private static final class SkillAbortException extends RuntimeException {
        private SkillAbortException() {
            super("harvest-crop-skill-abort");
        }
    }
}
