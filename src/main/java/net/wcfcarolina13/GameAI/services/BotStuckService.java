package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.PlayerUtils.MiningTool;
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
    /** Tracks position from N ticks ago for bounded-movement detection (oscillation). */
    private static final Map<UUID, Vec3d> BOUNDED_CHECK_ORIGIN = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> BOUNDED_CHECK_TICKS = new ConcurrentHashMap<>();
    /** If bot stays within 2 blocks of origin for 60 ticks, it's stuck (even if oscillating). */
    private static final int BOUNDED_STUCK_THRESHOLD = 60;
    private static final double BOUNDED_STUCK_RADIUS_SQ = 4.0; // 2 blocks
    /** Cooldown to prevent rapid mine-escape attempts (value = tick of last attempt). */
    private static final Map<UUID, Long> MINE_ESCAPE_COOLDOWN = new ConcurrentHashMap<>();
    private static final long MINE_ESCAPE_COOLDOWN_TICKS = 60; // 3 seconds

    private BotStuckService() {}

    public record EnvironmentSnapshot(boolean enclosed, int solidNeighborCount, boolean hasHeadroom,
                                          boolean hasEscapeRoute, boolean horizontallyEnclosed) {}

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
        BOUNDED_CHECK_ORIGIN.remove(botId);
        BOUNDED_CHECK_TICKS.remove(botId);
        MINE_ESCAPE_COOLDOWN.remove(botId);
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

    private static boolean isStandable(ServerWorld world, BlockPos stand) {
        if (world == null || stand == null) {
            return false;
        }
        BlockPos foot = stand.down();
        return !world.getBlockState(foot).getCollisionShape(world, foot).isEmpty()
                && world.getBlockState(stand).getCollisionShape(world, stand).isEmpty()
                && world.getBlockState(stand.up()).getCollisionShape(world, stand.up()).isEmpty();
    }

    private static int countSolidNeighbors(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return 0;
        }
        int solid = 0;
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (isSolid(world, pos.offset(d))) {
                solid++;
            }
        }
        if (isSolid(world, pos.down())) {
            solid++;
        }
        if (isSolid(world, pos.up())) {
            solid++;
        }
        return solid;
    }

    /**
     * Corner/pit escape: try to move to a nearby standable tile (prefer 1-block-up exits and open space).
     * This avoids getting wedged against a wall where "forward" motion can't resolve collisions.
     */
    private static boolean tryEscapeHop(ServerPlayerEntity bot, String label) {
        if (bot == null || bot.isRemoved()) {
            return false;
        }
        ServerWorld world = bot.getCommandSource().getWorld();
        BlockPos start = bot.getBlockPos();

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    if (Math.abs(dx) + Math.abs(dz) > 2) {
                        continue;
                    }
                    BlockPos cand = start.add(dx, dy, dz);
                    if (!isStandable(world, cand)) {
                        continue;
                    }
                    int elevation = cand.getY() - start.getY();
                    int solids = countSolidNeighbors(world, cand);
                    double distSq = cand.getSquaredDistance(start);
                    // Prefer stepping up and moving into open space.
                    double score = distSq * 0.6D + solids * 1.6D - elevation * 5.0D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = cand.toImmutable();
                    }
                }
            }
        }

        if (best == null) {
            return false;
        }

        LOGGER.debug("stuck-escape hop [{}]: from={} to={} score={}",
                label,
                start.toShortString(),
                best.toShortString(),
                String.format(java.util.Locale.ROOT, "%.2f", bestScore));

        return MovementService.nudgeTowardUntilClose(bot, best, 2.25D, 1900L, 0.22D, "stuck-escape-" + label);
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

        // Bounded-movement detection: bot oscillating within a small area (e.g. walking into wall)
        Vec3d boundedOrigin = BOUNDED_CHECK_ORIGIN.get(botId);
        int boundedTicks = BOUNDED_CHECK_TICKS.getOrDefault(botId, 0);
        if (boundedOrigin == null) {
            BOUNDED_CHECK_ORIGIN.put(botId, currentPos);
            BOUNDED_CHECK_TICKS.put(botId, 0);
        } else if (currentPos.squaredDistanceTo(boundedOrigin) < BOUNDED_STUCK_RADIUS_SQ) {
            boundedTicks++;
            BOUNDED_CHECK_TICKS.put(botId, boundedTicks);
        } else {
            // Moved far enough — reset origin
            BOUNDED_CHECK_ORIGIN.put(botId, currentPos);
            BOUNDED_CHECK_TICKS.put(botId, 0);
            boundedTicks = 0;
        }

        // If oscillating in a small area for 3 seconds, try mine-escape
        if (boundedTicks >= BOUNDED_STUCK_THRESHOLD && environmentSnapshot != null) {
            ServerWorld bWorld = bot.getCommandSource().getWorld();
            boolean noSky = bWorld != null && !bWorld.isSkyVisible(bot.getBlockPos().up());
            if (noSky) {
                long nowTick = bWorld.getServer() != null ? bWorld.getServer().getTicks() : 0;
                long lastMine = MINE_ESCAPE_COOLDOWN.getOrDefault(botId, 0L);
                if (nowTick - lastMine >= MINE_ESCAPE_COOLDOWN_TICKS) {
                    MINE_ESCAPE_COOLDOWN.put(botId, nowTick);
                    LOGGER.info("Bot {} bounded-stuck for {}t (oscillating in {}block radius, no sky) — mining escape",
                            bot.getName().getString(), boundedTicks,
                            String.format("%.1f", Math.sqrt(BOUNDED_STUCK_RADIUS_SQ)));
                    tryMineEscape(bot, bWorld);
                }
                BOUNDED_CHECK_ORIGIN.put(botId, currentPos);
                BOUNDED_CHECK_TICKS.put(botId, 0);
            }
        }

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

            // First try to escape by moving to a nearby open/standable spot (handles corner wedging).
            boolean escaped = tryEscapeHop(bot, "primary") || tryEscapeHop(bot, "fallback");
            if (!escaped) {
                BotActions.escapeStairs(bot);
            }

            // If horizontally enclosed (all 4 walls blocked) and hop/stairs didn't help, mine out
            boolean horizEnclosed = environmentSnapshot != null && environmentSnapshot.horizontallyEnclosed();
            if (!escaped && horizEnclosed) {
                ServerWorld world = bot.getCommandSource().getWorld();
                long nowTick = world.getServer() != null ? world.getServer().getTicks() : 0;
                long lastMine = MINE_ESCAPE_COOLDOWN.getOrDefault(botId, 0L);
                if (nowTick - lastMine >= MINE_ESCAPE_COOLDOWN_TICKS) {
                    MINE_ESCAPE_COOLDOWN.put(botId, nowTick);
                    tryMineEscape(bot, world);
                }
            }

            STATIONARY_TICKS.put(botId, 0);
            LAST_KNOWN_POSITION.put(botId, new Vec3d(bot.getX(), bot.getY(), bot.getZ()));
        }
    }

    /**
     * Mine escape for horizontally enclosed bots. Finds the nearest direction with air
     * beyond the wall (up to 3 blocks), mines the wall in that direction.
     * Falls back to mining north if all directions are solid.
     */
    private static void tryMineEscape(ServerPlayerEntity bot, ServerWorld world) {
        BlockPos pos = bot.getBlockPos();

        // Find nearest direction with air beyond the wall
        Direction bestDir = null;
        int bestDist = Integer.MAX_VALUE;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            for (int dist = 1; dist <= 3; dist++) {
                BlockPos check = pos.offset(dir, dist);
                if (world.getBlockState(check).isAir() && world.getBlockState(check.up()).isAir()) {
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestDir = dir;
                    }
                    break;
                }
            }
        }

        Direction mineDir = bestDir != null ? bestDir : Direction.NORTH;
        BlockPos wallFeet = pos.offset(mineDir);
        BlockPos wallHead = wallFeet.up();

        LOGGER.info("Mine-escape for {} toward {} (airDist={})",
                bot.getName().getString(), mineDir.asString(),
                bestDir != null ? bestDist : "none");

        // Face the wall before mining (required for LoS check in MiningTool)
        LookController.faceBlock(bot, wallFeet);

        if (world.getBlockState(wallFeet).isSolidBlock(world, wallFeet)) {
            MiningTool.mineBlock(bot, wallFeet, false);
        }
        if (world.getBlockState(wallHead).isSolidBlock(world, wallHead)) {
            MiningTool.mineBlock(bot, wallHead, false);
        }
    }
}

