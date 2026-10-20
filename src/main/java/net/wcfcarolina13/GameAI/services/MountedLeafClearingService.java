package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShearsItem;
import net.wcfcarolina13.PlayerUtils.ToolSelector;

public final class MountedLeafClearingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("mount-leaf");
    private static final long CLEAR_INTERVAL_TICKS = 2L;
    private static final double LEAF_REACH_SQ = 4.5 * 4.5;
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
        if (!server.isOnThread()) {
            server.execute(() -> maybeClear(bot, commander));
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

        for (BlockPos leaf : mountedLeafCandidates(bot.getBlockPos(), toward)) {
            if (!LeafClearService.isBreakableLeaf(world, leaf)) {
                continue;
            }
            if (ProtectedZoneService.isProtected(leaf, world, BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot))) {
                continue;
            }
            if (TreeDetector.isNearHumanBlocks(world, leaf, 3)) {
                continue;
            }
            var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, leaf);
            if (!auth.allowed()) {
                continue;
            }
            if (bot.squaredDistanceTo(Vec3d.ofCenter(leaf)) > LEAF_REACH_SQ) {
                continue;
            }
            if (!holdLeafTool(bot, world.getBlockState(leaf))) {
                return;
            }
            if (LeafClearService.startLeafMining(bot, leaf, "mounted")) {
                LOGGER.debug("Leaf clear: bot={} mining={} toward={}",
                        bot.getName().getString(), leaf, toward);
                return;
            }
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

    /**
     * Holds the item {@link ToolSelector#selectBestToolForBlock} picks for leaves (never shears:
     * they drop the leaf block instead of saplings and sticks). An empty pick means the bare hand,
     * so a held weapon or shears is put away by selecting the first empty hotbar slot. Selects a
     * slot only when the pick is not already held. Server tick only: the leaf branch of the
     * selector reads the hotbar and never swaps, reaches into bundles or hops threads. Returns false
     * (the caller skips this pass) when the pick cannot be held: no empty hotbar slot for a bare
     * hand, a pick not found in the hotbar, shears still in the main hand, or a hotbar locked by
     * {@link HotbarLockService} (the slot is left alone, as {@code BotActions.selectHotbarSlot} does).
     */
    private static boolean holdLeafTool(ServerPlayerEntity bot, BlockState leafState) {
        if (HotbarLockService.isLocked(bot)) {
            HotbarLockService.maybeLogBlocked(bot, "mount-leaf");
            return false;
        }
        ItemStack tool = ToolSelector.selectBestToolForBlock(bot, leafState);
        ItemStack held = bot.getMainHandStack();
        if (tool.isEmpty()) {
            if (!held.isEmpty()) {
                int empty = hotbarSlotOf(bot, ItemStack.EMPTY);
                if (empty < 0) {
                    return false;
                }
                bot.getInventory().setSelectedSlot(empty);
            }
        } else if (tool != held) {
            int slot = hotbarSlotOf(bot, tool);
            if (slot < 0) {
                return false;
            }
            bot.getInventory().setSelectedSlot(slot);
        }
        return !(bot.getMainHandStack().getItem() instanceof ShearsItem);
    }

    /**
     * Hotbar slot holding {@code wanted}: an empty slot for {@link ItemStack#EMPTY}, otherwise the
     * same live stack the selector returned, falling back to an equal stack so a selector that
     * ever returns a copy still selects a slot. -1 when none.
     */
    private static int hotbarSlotOf(ServerPlayerEntity bot, ItemStack wanted) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (wanted.isEmpty() ? stack.isEmpty() : stack == wanted) {
                return i;
            }
        }
        if (!wanted.isEmpty()) {
            for (int i = 0; i < 9; i++) {
                if (ItemStack.areEqual(bot.getInventory().getStack(i), wanted)) {
                    return i;
                }
            }
        }
        return -1;
    }
}
