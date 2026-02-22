package net.wcfcarolina13.GameAI.services;

import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

public final class MountedLeafClearingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("mount-leaf");
    private static final long CLEAR_INTERVAL_TICKS = 2L;
    private static final double LEAF_REACH_SQ = 4.5 * 4.5;
    private static final int MAX_BREAKS_PER_PASS = 3;
    private static final Map<UUID, Long> LAST_CLEAR_TICK = new ConcurrentHashMap<>();

    private MountedLeafClearingService() {}

    public static void maybeClear(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null || commander == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (!bot.hasVehicle()) {
            return;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return;
        }
        long now = server.getTicks();
        long last = LAST_CLEAR_TICK.getOrDefault(bot.getUuid(), 0L);
        if (now - last < CLEAR_INTERVAL_TICKS) {
            return;
        }
        LAST_CLEAR_TICK.put(bot.getUuid(), now);

        Vec3d delta = new Vec3d(commander.getX() - bot.getX(), 0.0D, commander.getZ() - bot.getZ());
        if (delta.lengthSquared() < 1.0E-3) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        Direction toward = Direction.fromHorizontalDegrees(yaw);

        ItemStack tool = selectLeafTool(bot);
        if (!tool.isEmpty()) {
            equipTool(bot, tool);
        }

        int broken = 0;
        for (BlockPos leaf : mountedLeafCandidates(bot.getBlockPos(), toward)) {
            if (!world.getBlockState(leaf).isIn(BlockTags.LEAVES)) {
                continue;
            }
            if (ProtectedZoneService.isProtected(leaf, world, null)) {
                continue;
            }
            if (TreeDetector.isNearHumanBlocks(world, leaf, 3)) {
                continue;
            }
            if (bot.squaredDistanceTo(Vec3d.ofCenter(leaf)) > LEAF_REACH_SQ) {
                continue;
            }
            try {
                bot.swingHand(Hand.MAIN_HAND);
                if (bot.interactionManager.tryBreakBlock(leaf)) {
                    broken++;
                }
            } catch (Exception ignored) {
            }
            if (broken >= MAX_BREAKS_PER_PASS) {
                break;
            }
        }
        if (broken > 0) {
            LOGGER.debug("Leaf clear: bot={} broke={} toward={}", bot.getName().getString(), broken, toward);
        }
    }

    private static List<BlockPos> mountedLeafCandidates(BlockPos start, Direction toward) {
        if (start == null || toward == null) {
            return List.of();
        }
        BlockPos front = start.offset(toward);
        BlockPos head = start.up(2);
        BlockPos ceiling = start.up(3);
        return List.of(
                head, head.up(), ceiling,
                head.offset(toward), head.offset(toward).up(), head.offset(toward).up(2),
                front.up(), front.up(2), front.up(3), front,
                head.offset(toward.rotateYClockwise()), head.offset(toward.rotateYClockwise()).up(),
                head.offset(toward.rotateYCounterclockwise()), head.offset(toward.rotateYCounterclockwise()).up()
        );
    }

    private static ItemStack selectLeafTool(ServerPlayerEntity bot) {
        if (bot == null) {
            return ItemStack.EMPTY;
        }
        if (!bot.getInventory().contains(new ItemStack(Items.SHEARS))) {
            return ItemStack.EMPTY;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isOf(Items.SHEARS)) {
                return stack;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isOf(Items.SHEARS)) {
                int hotbarSlot = swapToHotbar(bot, slot);
                if (hotbarSlot >= 0) {
                    return bot.getInventory().getStack(hotbarSlot);
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static void equipTool(ServerPlayerEntity bot, ItemStack tool) {
        if (bot == null || tool == null || tool.isEmpty()) {
            return;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (ItemStack.areItemsEqual(stack, tool)) {
                bot.getInventory().setSelectedSlot(i);
                return;
            }
        }
    }

    private static int swapToHotbar(ServerPlayerEntity bot, int fromSlot) {
        int targetHotbarSlot = bot.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                targetHotbarSlot = i;
                break;
            }
        }
        bot.currentScreenHandler.onSlotClick(fromSlot, targetHotbarSlot, SlotActionType.SWAP, bot);
        return targetHotbarSlot;
    }
}
