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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Peaceful wool collection: shears adult sheep, collects drops, and deposits blocks if inventory is tight.
 */
public class WoolSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-wool");
    private static final int MIN_FREE_SLOTS = 5;
    private static final int PEN_SEARCH_RADIUS = 14;
    private static final int WILD_SEARCH_RADIUS = 32;
    private static final int CHEST_SEARCH_RADIUS = 10;
    private static final List<Item> DEPOSIT_PREFERRED = List.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE,
            Items.DIRT, Items.GRASS_BLOCK, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.SAND, Items.RED_SAND,
            Items.GRAVEL, Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG, Items.ACACIA_LOG,
            Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG, Items.CRIMSON_STEM, Items.WARPED_STEM,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS,
            Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
    );

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

        ensureInventorySpace(bot, world, source);

        int radius = detectFenceNearby(world, bot.getBlockPos()) ? PEN_SEARCH_RADIUS : WILD_SEARCH_RADIUS;
        ChatUtils.sendSystemMessage(source, "Looking for sheep within " + radius + " blocks.");

        int sheared = shearNearbySheep(bot, world, source, radius);
        collectNearbyWool(bot, world);

        ensureInventorySpace(bot, world, source); // final deposit pass

        if (sheared == 0) {
            return SkillExecutionResult.failure("No shearable sheep found nearby.");
        }
        return SkillExecutionResult.success("Sheared " + sheared + " sheep and gathered wool.");
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

    private int shearNearbySheep(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, int radius) {
        Box box = Box.from(Vec3d.of(bot.getBlockPos())).expand(radius, 6, radius);
        List<SheepEntity> sheep = world.getEntitiesByClass(SheepEntity.class, box, s -> !s.isBaby() && s.isShearable());
        if (sheep.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (SheepEntity s : sheep) {
            if (!moveNextTo(bot, source, s.getBlockPos())) {
                continue;
            }
            if (!s.isShearable()) {
                continue;
            }
            LookController.faceEntity(bot, s);
            bot.interact(s, Hand.MAIN_HAND);
            count++;
            sleep(120);
            collectNearbyWool(bot, world);
        }
        return count;
    }

    private void collectNearbyWool(ServerPlayerEntity bot, ServerWorld world) {
        Box box = Box.from(Vec3d.of(bot.getBlockPos())).expand(6, 3, 6);
        List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class, box, e -> e.getStack().isOf(Items.WHITE_WOOL) || e.getStack().getItem().getTranslationKey().contains("wool"));
        for (ItemEntity drop : drops) {
            moveNextTo(bot, bot.getCommandSource(), drop.getBlockPos());
            sleep(40);
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
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                target,
                target,
                null,
                null,
                bot.getHorizontalFacing()
        );
        MovementService.execute(source, bot, plan, false);
        double dist = bot.getBlockPos().getSquaredDistance(target);
        return dist <= 9.0;
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
