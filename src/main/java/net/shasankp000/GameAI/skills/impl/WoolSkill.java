package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.CraftingHelper;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.BlockInteractionService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Peaceful wool collection: shears adult sheep, collects drops, and deposits blocks if inventory is tight.
 */
public class WoolSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-wool");
    private static final int MIN_FREE_SLOTS = 5;
    private static final int PEN_SEARCH_RADIUS = 14;
    private static final int WILD_SEARCH_RADIUS = 48;
    private static final int SHEEP_VERTICAL_RANGE = 18;
    private static final int CHEST_SEARCH_RADIUS = 10;
    private static final int SUNSET_TIME_OF_DAY = 12000; // day phase; stop when sun starts going down
    private static final long MAX_JOB_MILLIS = 30 * 60_000L; // hard cap (day is ~20 minutes)
    private static final int DROP_SWEEP_RADIUS = 18;
    private static final int DROP_SWEEP_PASSES = 4;
    private static final int DEFAULT_MIN_WOOL = 16;
    private static final long POST_SHEAR_SWEEP_BUDGET_MS = 9000L;
    private static final List<Item> DEPOSIT_PREFERRED = List.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE,
            Items.DIRT, Items.GRASS_BLOCK, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.SAND, Items.RED_SAND,
            Items.GRAVEL, Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG, Items.ACACIA_LOG,
            Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG, Items.CRIMSON_STEM, Items.WARPED_STEM,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS,
            Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
    );
    private static final Map<UUID, BlockPos> LAST_SEEN_SHEEP = new HashMap<>();

    @Override
    public String name() {
        return "wool";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No active bot.");
        }
        ServerWorld world = source.getWorld();

        if (!ensureShears(bot, source)) {
            return SkillExecutionResult.failure("Missing shears and cannot craft.");
        }

        int radius = detectFenceNearby(world, bot.getBlockPos()) ? PEN_SEARCH_RADIUS : WILD_SEARCH_RADIUS;
        BlockPos startPos = bot.getBlockPos();
        int minWoolToCollect = parseCount(context.parameters());
        if (minWoolToCollect <= 0) {
            minWoolToCollect = DEFAULT_MIN_WOOL;
        }
        int woolAtStart = countWoolItems(bot.getInventory());
        long startedAt = System.currentTimeMillis();
        int timeOfDay = (int) (world.getTimeOfDay() % 24000L);
        if (timeOfDay >= SUNSET_TIME_OF_DAY) {
            ChatUtils.sendSystemMessage(source, "It's getting late; I'll collect wool tomorrow.");
            return SkillExecutionResult.failure("Too late to start wool run.");
        }

        ensureInventorySpace(bot, world, source);
        ChatUtils.sendSystemMessage(source, "Collecting at least " + minWoolToCollect + " wool before sunset.");
        ChatUtils.sendSystemMessage(source, "Looking for sheep within " + radius + " blocks (line-of-sight only).");

        int sheared = 0;
        Set<UUID> failedSheepIds = new HashSet<>(); // Track sheep we couldn't reach to avoid oscillating

        while (System.currentTimeMillis() - startedAt < MAX_JOB_MILLIS) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return SkillExecutionResult.failure("Wool job stopped.");
            }
            int now = (int) (world.getTimeOfDay() % 24000L);
            if (now >= SUNSET_TIME_OF_DAY) {
                ChatUtils.sendSystemMessage(source, "It's getting late; I'm returning to base.");
                moveTo(bot, source, startPos, false);
                break;
            }

            if (!ensureWoolCapacityOrDeposit(bot, world, source)) {
                break;
            }

            List<SheepEntity> candidates = visibleSheep(bot, world, radius);
            // Filter out sheep we already failed to reach (avoid oscillation)
            candidates.removeIf(s -> failedSheepIds.contains(s.getUuid()));
            
            if (candidates.isEmpty()) {
                // Clear failed set when exploring - give them another chance after moving
                failedSheepIds.clear();
                if (!exploreForSheep(bot, world, source, radius)) {
                    break;
                }
                continue;
            }

            // IMPORTANT: Pick ONE sheep and commit to it, don't iterate through all
            SheepEntity target = candidates.get(0); // Already sorted by distance
            LAST_SEEN_SHEEP.put(bot.getUuid(), target.getBlockPos());
            
            if (!moveNextTo(bot, source, target.getBlockPos())) {
                // Mark this sheep as failed and try a different one next iteration
                failedSheepIds.add(target.getUuid());
                LOGGER.debug("Failed to reach sheep {}, marking as unreachable", target.getUuid());
                continue;
            }
            
            // Re-check if sheep is still shearable after we moved to it
            if (!target.isAlive() || !target.isShearable() || target.isBaby()) {
                continue;
            }
            
            // CRITICAL: Ensure shears are equipped before each shearing attempt
            if (!ensureShearsEquipped(bot)) {
                LOGGER.warn("Lost shears, attempting to re-equip or craft");
                if (!ensureShears(bot, source)) {
                    return SkillExecutionResult.failure("Lost shears and cannot replace.");
                }
            }
            
            BotActions.interactEntity(bot, target, Hand.MAIN_HAND);
            sheared++;
            bot.swingHand(Hand.MAIN_HAND, true);
            
            // Give the drops a tick to spawn, then immediately collect THIS sheep's drops
            sleep(250);
            
            // Collect drops BEFORE moving to next sheep - use shorter budget, focused area
            collectImmediateDrops(bot, world, target.getBlockPos());
            
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return SkillExecutionResult.failure("Wool job stopped.");
            }

            int collected = countWoolItems(bot.getInventory()) - woolAtStart;
            if (collected >= minWoolToCollect) {
                ChatUtils.sendSystemMessage(source, "Collected at least " + minWoolToCollect + " wool; heading back.");
                dropSweepWool(bot, world, source, bot.getBlockPos(), 7000L);
                moveTo(bot, source, startPos, false);
                return SkillExecutionResult.success("Collected " + collected + " wool and sheared " + sheared + " sheep.");
            }
        }

        dropSweepWool(bot, world, source, bot.getBlockPos(), 9000L);

        ensureInventorySpace(bot, world, source); // final deposit pass

        if (sheared == 0) {
            return SkillExecutionResult.failure("No shearable sheep found nearby.");
        }
        return SkillExecutionResult.success("Sheared " + sheared + " sheep and gathered wool.");
    }

    private int parseCount(Map<String, Object> params) {
        if (params == null) {
            return 0;
        }
        Object value = params.get("count");
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String s) {
            try {
                return Math.max(0, Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private int countWoolItems(Inventory inv) {
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem().getTranslationKey().contains("wool")) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean ensureShears(ServerPlayerEntity bot, ServerCommandSource source) {
        int slot = findShearsSlot(bot);
        if (slot == -1) {
            int crafted = CraftingHelper.craftGeneric(source, bot, source.getPlayer(), "shears", 1, null);
            if (crafted > 0) {
                slot = findShearsSlot(bot);
            }
        }
        if (slot == -1) {
            ChatUtils.sendSystemMessage(source, "I need shears to collect wool.");
            return false;
        }
        if (slot >= 9) {
            int empty = findEmptyHotbar(bot);
            if (empty == -1) {
                empty = bot.getInventory().getSelectedSlot();
            }
            // manual swap
            ItemStack from = bot.getInventory().getStack(slot);
            ItemStack to = bot.getInventory().getStack(empty);
            bot.getInventory().setStack(slot, to);
            bot.getInventory().setStack(empty, from);
            slot = empty;
        }
        BotActions.selectHotbarSlot(bot, slot);
        return true;
    }

    /**
     * Quick check if shears are currently in hand. If not, try to re-select them.
     * Returns true if shears are now equipped, false otherwise.
     */
    private boolean ensureShearsEquipped(ServerPlayerEntity bot) {
        ItemStack hand = bot.getMainHandStack();
        if (hand.isOf(Items.SHEARS)) {
            return true;
        }
        // Shears not in hand - find and re-select
        int slot = findShearsSlot(bot);
        if (slot == -1) {
            return false;
        }
        if (slot >= 9) {
            // Need to move to hotbar first
            int empty = findEmptyHotbar(bot);
            if (empty == -1) {
                empty = bot.getInventory().getSelectedSlot();
            }
            ItemStack from = bot.getInventory().getStack(slot);
            ItemStack to = bot.getInventory().getStack(empty);
            bot.getInventory().setStack(slot, to);
            bot.getInventory().setStack(empty, from);
            slot = empty;
        }
        BotActions.selectHotbarSlot(bot, slot);
        return bot.getMainHandStack().isOf(Items.SHEARS);
    }

    /**
     * Immediately collect wool drops in a small radius around the sheared sheep.
     * This is a quick, focused collection before moving to the next target.
     */
    private void collectImmediateDrops(ServerPlayerEntity bot, ServerWorld world, BlockPos sheepPos) {
        // Small radius, quick collection - don't wander far
        Box box = Box.from(Vec3d.of(sheepPos)).expand(5, 3, 5);
        List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class, box,
                e -> e.getStack().getItem().getTranslationKey().contains("wool"));
        
        if (drops.isEmpty()) {
            return;
        }
        
        drops.sort((a, b) -> Double.compare(bot.squaredDistanceTo(a), bot.squaredDistanceTo(b)));
        
        long deadline = System.currentTimeMillis() + 3000L; // Max 3 seconds for immediate pickup
        for (ItemEntity drop : drops) {
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            BlockPos dropPos = drop.getBlockPos();
            // Only pick up drops that are reasonably close and at similar Y level
            if (Math.abs(dropPos.getY() - bot.getBlockY()) > 2) {
                continue;
            }
            double distSq = bot.squaredDistanceTo(drop);
            if (distSq > 49.0) { // Don't chase drops more than 7 blocks away
                continue;
            }
            
            // Quick nudge toward drop
            MovementService.nudgeTowardUntilClose(bot, dropPos, 1.5, 1200L, 0.2, "wool-pickup");
            sleep(80);
        }
    }

    private List<SheepEntity> visibleSheep(ServerPlayerEntity bot, ServerWorld world, int radius) {
        Box box = Box.from(Vec3d.of(bot.getBlockPos())).expand(radius, SHEEP_VERTICAL_RANGE, radius);
        List<SheepEntity> visible = world.getEntitiesByClass(
                SheepEntity.class,
                box,
                s -> !s.isBaby() && s.isShearable() && (bot.canSee(s) || bot.squaredDistanceTo(s) <= 64.0)
        );
        if (visible.isEmpty()) {
            return List.of();
        }
        visible.sort((a, b) -> Double.compare(bot.squaredDistanceTo(a), bot.squaredDistanceTo(b)));
        return visible;
    }

    private boolean exploreForSheep(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, int radius) {
        BlockPos last = LAST_SEEN_SHEEP.get(bot.getUuid());
        BlockPos anchor = last != null ? last : bot.getBlockPos();
        // Small spiral exploration around the last seen region (or current position).
        int[] rings = {0, 4, 8, 12, 16};
        for (int r : rings) {
            for (int dx : new int[]{r, -r, 0}) {
                for (int dz : new int[]{0, r, -r}) {
                    if (SkillManager.shouldAbortSkill(bot)) {
                        BotActions.stop(bot);
                        return false;
                    }
                    BlockPos probe = anchor.add(dx, 0, dz);
                    probe = probe.withY(anchor.getY());
                    if (moveTo(bot, source, probe, true)) {
                        if (!visibleSheep(bot, world, radius).isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        }
        // Even if we didn't find sheep, we still explored.
        return true;
    }

    private void collectNearbyWool(ServerPlayerEntity bot, ServerWorld world) {
        Box box = Box.from(Vec3d.of(bot.getBlockPos())).expand(6, 3, 6);
        List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class, box, e -> e.getStack().isOf(Items.WHITE_WOOL) || e.getStack().getItem().getTranslationKey().contains("wool"));
        for (ItemEntity drop : drops) {
            moveNextTo(bot, bot.getCommandSource(), drop.getBlockPos());
            sleep(40);
        }
    }

    private void dropSweepWool(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, BlockPos center, long budgetMs) {
        long deadline = System.currentTimeMillis() + Math.max(1500L, budgetMs);
        Map<BlockPos, Integer> attempts = new HashMap<>();
        int emptyScans = 0;

        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return;
            }
            Box box = Box.from(Vec3d.of(center)).expand(DROP_SWEEP_RADIUS, 6, DROP_SWEEP_RADIUS);
            List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class, box,
                    e -> e.getStack().getItem().getTranslationKey().contains("wool"));
            drops.removeIf(d -> attempts.getOrDefault(d.getBlockPos(), 0) >= 2);
            drops.sort((a, b) -> Double.compare(bot.squaredDistanceTo(a), bot.squaredDistanceTo(b)));

            if (drops.isEmpty()) {
                emptyScans++;
                if (emptyScans >= 2) {
                    return;
                }
                sleep(160);
                continue;
            }
            emptyScans = 0;

            // Sweep what we can reach quickly; don't ping-pong between distant goals.
            for (ItemEntity drop : drops) {
                if (System.currentTimeMillis() >= deadline) {
                    break;
                }
                if (SkillManager.shouldAbortSkill(bot)) {
                    BotActions.stop(bot);
                    return;
                }
                BlockPos pos = drop.getBlockPos();
                if (Math.abs(pos.getY() - bot.getBlockY()) > 3) {
                    continue;
                }
                attempts.put(pos, attempts.getOrDefault(pos, 0) + 1);
                moveNextTo(bot, source, pos);
                sleep(80);
            }
            sleep(160);
        }
    }

    private boolean detectFenceNearby(ServerWorld world, BlockPos origin) {
        int scan = 12;
        for (BlockPos pos : BlockPos.iterate(origin.add(-scan, -1, -scan), origin.add(scan, 2, scan))) {
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.FENCES)) {
                return true;
            }
        }
        return false;
    }

    private void ensureInventorySpace(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source) {
        int free = countFreeSlots(bot.getInventory());
        if (free >= MIN_FREE_SLOTS) {
            return;
        }
        BlockPos chestPos = findNearbyChest(world, bot.getBlockPos(), CHEST_SEARCH_RADIUS);
        if (chestPos == null) {
            ChatUtils.sendSystemMessage(source, "Inventory is tight and no chest nearby; proceeding anyway.");
            return;
        }
        boolean reached = moveNextTo(bot, source, chestPos);
        if (!reached || !BlockInteractionService.canInteract(bot, chestPos)) {
            ChatUtils.sendSystemMessage(source, "I found a chest, but I can't reach it from here.");
            return;
        }
        ChestBlockEntity chest = world.getBlockEntity(chestPos) instanceof ChestBlockEntity c ? c : null;
        if (chest == null) {
            return;
        }
        int moved = depositPreferred(bot.getInventory(), chest);
        ChatUtils.sendSystemMessage(source, moved > 0 ? "Stored " + moved + " items to free space." : "Chest is full; continuing.");
    }

    private int depositPreferred(Inventory from, Inventory to) {
        int moved = 0;
        for (int i = 0; i < from.size(); i++) {
            ItemStack stack = from.getStack(i);
            if (stack.isEmpty()) continue;
            if (!DEPOSIT_PREFERRED.contains(stack.getItem())) continue;
            ItemStack copy = stack.copy();
            ItemStack leftover = insertInto(to, copy);
            int deposited = copy.getCount() - leftover.getCount();
            if (deposited > 0) {
                stack.decrement(deposited);
                moved += deposited;
            }
            if (countFreeSlots(from) >= MIN_FREE_SLOTS) {
                break;
            }
        }
        return moved;
    }

    private ItemStack insertInto(Inventory inv, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack slot = inv.getStack(i);
            if (slot.isEmpty()) {
                inv.setStack(i, remaining);
                return ItemStack.EMPTY;
            }
            if (ItemStack.areItemsEqual(slot, remaining) && ItemStack.areEqual(slot, remaining) && slot.getCount() < slot.getMaxCount()) {
                int canAdd = Math.min(slot.getMaxCount() - slot.getCount(), remaining.getCount());
                slot.increment(canAdd);
                remaining.decrement(canAdd);
                if (remaining.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        return remaining;
    }

    private BlockPos findNearbyChest(ServerWorld world, BlockPos origin, int radius) {
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -1, -radius), origin.add(radius, 2, radius))) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(net.minecraft.block.Blocks.CHEST) || state.isOf(net.minecraft.block.Blocks.TRAPPED_CHEST)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private boolean moveNextTo(ServerPlayerEntity bot, ServerCommandSource source, BlockPos target) {
        List<BlockPos> stands = findStandCandidates(bot, source.getWorld(), target);
        for (BlockPos stand : stands) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return false;
            }
            if (moveTo(bot, source, stand, true)) {
                return true;
            }
        }
        return false;
    }

    private List<BlockPos> findStandCandidates(ServerPlayerEntity bot, ServerWorld world, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos foot = target.offset(dir);
            BlockPos below = foot.down();
            if (world.getBlockState(below).isSolidBlock(world, below) && world.isAir(foot)) {
                candidates.add(foot);
            }
        }
        candidates.sort((a, b) -> Double.compare(bot.squaredDistanceTo(Vec3d.ofCenter(a)), bot.squaredDistanceTo(Vec3d.ofCenter(b))));
        return candidates;
    }

    private boolean moveTo(ServerPlayerEntity bot, ServerCommandSource source, BlockPos target, boolean fastReplan) {
        if (SkillManager.shouldAbortSkill(bot)) {
            BotActions.stop(bot);
            return false;
        }
        
        // Early Y-level sanity check: don't pursue sheep that wandered into caves
        int dy = Math.abs(target.getY() - bot.getBlockY());
        if (dy > 12) {
            LOGGER.debug("Target {} too far vertically (dy={}), skipping", target.toShortString(), dy);
            return false;
        }
        
        double distSq = bot.getBlockPos().getSquaredDistance(target);
        final double ARRIVAL_THRESHOLD_SQ = 9.0D;
        
        // Short-range nudge for nearby targets
        if (distSq <= 196.0D && dy <= 2) {
            boolean close = MovementService.nudgeTowardUntilClose(
                    bot,
                    target,
                    ARRIVAL_THRESHOLD_SQ,
                    fastReplan ? 1800L : 2800L,
                    0.16,
                    "wool-short-move");
            if (close || bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
                return true;
            }
            // If short nudge failed, check for a blocking door
            BlockPos blockingDoor = BlockInteractionService.findBlockingDoor(bot, target, 12.0D);
            if (blockingDoor != null) {
                MovementService.tryOpenDoorAt(bot, blockingDoor);
                MovementService.tryTraverseOpenableToward(bot, blockingDoor, target, "wool-door-nearby");
                MovementService.nudgeTowardUntilClose(bot, target, ARRIVAL_THRESHOLD_SQ, 2000L, 0.18, "wool-door-cross");
                if (bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
                    return true;
                }
            }
        }
        
        // Standard pathfinding for longer distances
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                target,
                target,
                null,
                null,
                bot.getHorizontalFacing()
        );
        MovementService.MovementResult res = MovementService.execute(source, bot, plan, false, fastReplan, true, false);
        if (res.success() || bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
            return true;
        }
        
        // Check for blocking door on the direct line
        BlockPos directBlockingDoor = BlockInteractionService.findBlockingDoor(bot, target, 64.0D);
        if (directBlockingDoor != null) {
            MovementService.tryOpenDoorAt(bot, directBlockingDoor);
            MovementService.tryTraverseOpenableToward(bot, directBlockingDoor, target, "wool-doorway");
            res = MovementService.execute(source, bot, plan, false, true, false, false);
            if (res.success() || bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
                return true;
            }
        }
        
        // Door escape assist: if we appear to be in an enclosure, try "approach -> open -> step through"
        try {
            MovementService.DoorSubgoalPlan doorPlan = directBlockingDoor == null
                    ? MovementService.findDoorEscapePlan(bot, target, null)
                    : null;
            if (doorPlan != null) {
                LOGGER.info("wool door-escape: doorBase={} approach={} step={}",
                        doorPlan.doorBase().toShortString(),
                        doorPlan.approachPos().toShortString(),
                        doorPlan.stepThroughPos().toShortString());
                boolean approached = MovementService.nudgeTowardUntilClose(
                        bot, doorPlan.approachPos(), 2.25D, 2200L, 0.18, "wool-door-approach");
                if (approached) {
                    MovementService.tryOpenDoorAt(bot, doorPlan.doorBase());
                    MovementService.nudgeTowardUntilClose(
                            bot, doorPlan.stepThroughPos(), 4.0D, 2600L, 0.22, "wool-door-step");
                    // Retry the main move after stepping through
                    res = MovementService.execute(source, bot, plan, false, true, false, false);
                    if (res.success() || bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Door escape helper threw: {}", t.getMessage());
        }
        
        // Final fallback: safe nudge for very close targets
        if (bot.getBlockPos().getSquaredDistance(target) <= 16.0) {
            boolean nudged = MovementService.nudgeTowardUntilClose(bot, target, ARRIVAL_THRESHOLD_SQ, 1500L, 0.2, "wool-final-nudge");
            if (nudged || bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
                return true;
            }
        }
        
        return false;
    }

    private boolean ensureWoolCapacityOrDeposit(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source) {
        int free = countFreeSlots(bot.getInventory());
        if (free > 0) {
            return true;
        }
        // If inventory is full, try deposit. If still full, stop with an explanation.
        ensureInventorySpace(bot, world, source);
        free = countFreeSlots(bot.getInventory());
        if (free > 0) {
            return true;
        }
        ChatUtils.sendSystemMessage(source, "I don't have space for more wool. I need a nearby chest to store items.");
        return false;
    }

    private int findShearsSlot(ServerPlayerEntity bot) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).isOf(Items.SHEARS)) {
                return i;
            }
        }
        return -1;
    }

    private int findEmptyHotbar(ServerPlayerEntity bot) {
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private int countFreeSlots(Inventory inv) {
        int free = 0;
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) free++;
        }
        return free;
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
