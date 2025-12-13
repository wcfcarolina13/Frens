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
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.CraftingHelper;
import net.shasankp000.GameAI.services.MovementService;
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

        while (System.currentTimeMillis() - startedAt < MAX_JOB_MILLIS) {
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
            if (candidates.isEmpty()) {
                if (!exploreForSheep(bot, world, source, radius)) {
                    break;
                }
                continue;
            }

            boolean progressed = false;
            for (SheepEntity target : candidates) {
                LAST_SEEN_SHEEP.put(bot.getUuid(), target.getBlockPos());
                if (!moveNextTo(bot, source, target.getBlockPos())) {
                    continue;
                }
                if (!target.isShearable() || target.isBaby()) {
                    continue;
                }
                LookController.faceEntity(bot, target);
                bot.interact(target, Hand.MAIN_HAND);
                sheared++;
                progressed = true;
                // Give the drops a tick to spawn, then sweep aggressively.
                sleep(220);
                dropSweepWool(bot, world, source, target.getBlockPos(), POST_SHEAR_SWEEP_BUDGET_MS);

                int collected = countWoolItems(bot.getInventory()) - woolAtStart;
                if (collected >= minWoolToCollect) {
                    ChatUtils.sendSystemMessage(source, "Collected at least " + minWoolToCollect + " wool; heading back.");
                    dropSweepWool(bot, world, source, bot.getBlockPos(), 7000L);
                    moveTo(bot, source, startPos, false);
                    return SkillExecutionResult.success("Collected " + collected + " wool and sheared " + sheared + " sheep.");
                }
            }
            if (!progressed) {
                exploreForSheep(bot, world, source, radius);
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
        moveNextTo(bot, source, chestPos);
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
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                target,
                target,
                null,
                null,
                bot.getHorizontalFacing()
        );
        MovementService.MovementResult res = MovementService.execute(source, bot, plan, false, fastReplan, true, false);
        return res.success() || bot.getBlockPos().getSquaredDistance(target) <= 9.0;
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
