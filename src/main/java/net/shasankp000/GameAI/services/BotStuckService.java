package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.GameAI.BotActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage-2 refactor: extract "stuck" tracking and local enclosure heuristics out of BotEventHandler.
 *
 * <p>Tracks per-bot position deltas for simple stuck detection and stores a per-bot "last safe position"
 * used as a fallback respawn/regroup anchor.</p>
 */
public final class BotStuckService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-stuck");

    private static final int STUCK_TICK_THRESHOLD = 12;

    private static final Map<UUID, Vec3d> LAST_KNOWN_POSITION = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> STATIONARY_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Vec3d> LAST_SAFE_POSITION = new ConcurrentHashMap<>();

    private BotStuckService() {}

    public record EnvironmentSnapshot(boolean enclosed, int solidNeighborCount, boolean hasHeadroom, boolean hasEscapeRoute) {}

    public static void resetAll() {
        LAST_KNOWN_POSITION.clear();
        STATIONARY_TICKS.clear();
        LAST_SAFE_POSITION.clear();
    }

    public static void resetBot(UUID botId) {
        if (botId == null) {
            return;
        }
        LAST_KNOWN_POSITION.remove(botId);
        STATIONARY_TICKS.remove(botId);
    }

    public static Vec3d getLastSafePosition(UUID botId) {
        if (botId == null) {
            return null;
        }
        return LAST_SAFE_POSITION.get(botId);
    }

    public static void setLastSafePosition(UUID botId, Vec3d pos) {
        if (botId == null) {
            return;
        }
        if (pos == null) {
            LAST_SAFE_POSITION.remove(botId);
        } else {
            LAST_SAFE_POSITION.put(botId, pos);
        }
    }

    public static EnvironmentSnapshot analyzeEnvironment(ServerPlayerEntity bot) {
        if (bot == null) {
            return new EnvironmentSnapshot(false, 0, true, true);
        }
        ServerWorld world = bot.getCommandSource().getWorld();
        BlockPos pos = bot.getBlockPos();

        int solidNeighbors = 0;
        for (Direction direction : Direction.values()) {
            if (direction == Direction.DOWN || direction == Direction.UP || direction.getAxis().isHorizontal()) {
                BlockPos checkPos = pos.offset(direction);
                if (isSolid(world, checkPos)) {
                    solidNeighbors++;
                }
            }
        }

        boolean headroom = world.getBlockState(pos.up()).isAir();
        boolean escapeRoute = false;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos forward = pos.offset(direction);
            if (world.getBlockState(forward).isAir() && world.getBlockState(forward.up()).isAir()) {
                escapeRoute = true;
                break;
            }
        }

        boolean enclosed = solidNeighbors >= 5;
        return new EnvironmentSnapshot(enclosed, solidNeighbors, headroom, escapeRoute);
    }

    private static boolean isSolid(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(world, pos).isEmpty();
    }

    public static void updateStuckTracker(ServerPlayerEntity bot, EnvironmentSnapshot environmentSnapshot) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        UUID botId = bot.getUuid();

        Vec3d currentPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d lastPos = LAST_KNOWN_POSITION.get(botId);
        if (lastPos == null) {
            LAST_KNOWN_POSITION.put(botId, currentPos);
            STATIONARY_TICKS.put(botId, 0);
            return;
        }

        int ticks = STATIONARY_TICKS.getOrDefault(botId, 0);
        double distanceSq = currentPos.squaredDistanceTo(lastPos);
        if (distanceSq < 0.01) {
            ticks++;
        } else {
            ticks = 0;
            LAST_KNOWN_POSITION.put(botId, currentPos);
        }
        STATIONARY_TICKS.put(botId, ticks);

        BlockState feetState = bot.getCommandSource().getWorld().getBlockState(bot.getBlockPos());
        if (feetState.isOf(Blocks.FARMLAND) && ticks > 5) {
            BotActions.jump(bot);
        }

        boolean enclosedNoEscape = environmentSnapshot != null
                && environmentSnapshot.enclosed()
                && !environmentSnapshot.hasEscapeRoute();

        if (ticks >= STUCK_TICK_THRESHOLD || enclosedNoEscape) {
            LOGGER.info("Escape routine triggered (stationaryTicks={}, enclosed={}, hasEscapeRoute={})",
                    ticks,
                    environmentSnapshot != null && environmentSnapshot.enclosed(),
                    environmentSnapshot == null || environmentSnapshot.hasEscapeRoute());
            BotActions.escapeStairs(bot);
            STATIONARY_TICKS.put(botId, 0);
            LAST_KNOWN_POSITION.put(botId, new Vec3d(bot.getX(), bot.getY(), bot.getZ()));
        }
    }
}

