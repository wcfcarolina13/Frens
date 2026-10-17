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
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShearsItem;
import net.wcfcarolina13.PlayerUtils.ToolSelector;

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

        int broken = 0;
        boolean toolChosen = false;
        for (BlockPos leaf : mountedLeafCandidates(bot.getBlockPos(), toward)) {
            BlockState leafState = world.getBlockState(leaf);
            if (!leafState.isIn(BlockTags.LEAVES)) {
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
            if (!toolChosen) {
                toolChosen = true;
                if (!holdLeafTool(bot, leafState)) {
                    return;
                }
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

    /**
     * Holds the item {@link ToolSelector#selectBestToolForBlock} picks for leaves (never shears:
     * they drop the leaf block instead of saplings and sticks). Selects that hotbar slot only when
     * it is not already held. Server tick only: the leaf branch of the selector reads the hotbar and
     * never swaps, reaches into bundles or hops threads. Returns false when the main hand still holds
     * shears, so the caller skips this pass.
     */
    private static boolean holdLeafTool(ServerPlayerEntity bot, BlockState leafState) {
        ItemStack tool = ToolSelector.selectBestToolForBlock(bot, leafState);
        if (!tool.isEmpty() && tool != bot.getMainHandStack()) {
            for (int i = 0; i < 9; i++) {
                if (bot.getInventory().getStack(i) == tool) {
                    bot.getInventory().setSelectedSlot(i);
                    break;
                }
            }
        }
        return !(bot.getMainHandStack().getItem() instanceof ShearsItem);
    }
}
