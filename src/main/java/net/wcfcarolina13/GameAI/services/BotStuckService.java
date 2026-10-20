package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Local enclosure heuristics and the last safe position used by respawn/regroup recovery. */
public final class BotStuckService {

    private static final Map<UUID, Vec3d> LAST_SAFE_POSITION = new ConcurrentHashMap<>();

    private BotStuckService() {}

    public record EnvironmentSnapshot(boolean enclosed, int solidNeighborCount, boolean hasHeadroom,
                                          boolean hasEscapeRoute, boolean horizontallyEnclosed) {}

    public static void resetAll() {
        LAST_SAFE_POSITION.clear();
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
            return new EnvironmentSnapshot(false, 0, true, true, false);
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
        // Check if all 4 horizontal directions are blocked at feet+head level
        boolean horizEnclosed = !escapeRoute;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos fwd = pos.offset(direction);
            if (!isSolid(world, fwd) || !isSolid(world, fwd.up())) {
                // At least one horizontal direction has a gap at feet or head
                horizEnclosed = false;
                break;
            }
        }
        return new EnvironmentSnapshot(enclosed, solidNeighbors, headroom, escapeRoute, horizEnclosed);
    }

    private static boolean isSolid(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(world, pos).isEmpty();
    }
}
