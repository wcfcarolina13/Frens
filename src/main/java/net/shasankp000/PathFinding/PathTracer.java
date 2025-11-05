package net.shasankp000.PathFinding;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.BlockState;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simplified path executor that walks a queue of {@link Segment}s on the server
 * thread without spawning extra worker threads or issuing Carpet commands.
 * Segments are executed sequentially; for each segment we orient the bot,
 * move it to the block centre, and optionally apply a sprint flag.
 */
public final class PathTracer {

    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");

    private static volatile boolean shouldSprint;
    private static final AtomicReference<BotSegmentManager> ACTIVE_MANAGER = new AtomicReference<>();

    private PathTracer() {
    }

    public static class BotSegmentManager {

        private final MinecraftServer server;
        private final ServerCommandSource botSource;
        private final String botName;
        private final Queue<Segment> jobQueue = new ArrayDeque<>();
        private BlockPos finalTarget = null;

        private static final AtomicReference<String> FINAL_RESULT = new AtomicReference<>("");
        private static volatile boolean moving = false;
        private static CompletableFuture<String> completionFuture = null;

        BotSegmentManager(MinecraftServer server, ServerCommandSource botSource, String botName) {
            this.server = server;
            this.botSource = botSource;
            this.botName = botName;
        }

        void addSegmentJob(Segment segment) {
            jobQueue.add(segment);
            finalTarget = segment.end();
        }

        void begin() {
            server.execute(this::processQueue);
        }

        private void processQueue() {
            ServerPlayerEntity player = botSource.getPlayer();
            if (player == null) {
                LOGGER.error("Cannot start path processing: bot player missing.");
                complete("Player not found");
                return;
            }

            moving = true;
            while (!jobQueue.isEmpty()) {
                Segment segment = jobQueue.poll();
                if (!executeSegment(player, segment)) {
                    moving = false;
                    return;
                }
            }
            ensureFinalPosition(player);
            moving = false;
            complete(tracePathOutput(botSource));
        }

        private boolean executeSegment(ServerPlayerEntity player, Segment segment) {
            if (!player.isAlive()) {
                LOGGER.warn("Skipping segment: player entity is not alive.");
                complete("Player not found");
                return false;
            }

            orientPlayer(player, segment);
            movePlayer(player, segment);
            return true;
        }

        private void ensureFinalPosition(ServerPlayerEntity player) {
            if (finalTarget == null) {
                return;
            }
            ServerWorld world = (ServerWorld) player.getEntityWorld();
            BlockPos current = player.getBlockPos();
            if (current.getSquaredDistance(finalTarget) <= 4.0D) {
                return;
            }
            BlockPos safe = findSafeLanding(world, finalTarget);
            Vec3d destination = centerOf(safe);
            player.refreshPositionAndAngles(destination.x, destination.y, destination.z, player.getYaw(), player.getPitch());
            player.setVelocity(Vec3d.ZERO);
            player.velocityDirty = true;
            player.setOnGround(true);
            LOGGER.info("{} repositioned to safe target {} from {}", botName, safe, current);
        }

        private void orientPlayer(ServerPlayerEntity player, Segment segment) {
            BlockPos start = segment.start();
            BlockPos end = segment.end();

            double dx = end.getX() - start.getX();
            double dz = end.getZ() - start.getZ();

            if (dx == 0 && dz == 0) {
                return;
            }

            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            player.setYaw(yaw);
            player.setHeadYaw(yaw);
            player.setBodyYaw(yaw);

            LOGGER.debug("{} is now facing yaw {}", botName, yaw);
        }

        private void movePlayer(ServerPlayerEntity player, Segment segment) {
            ServerWorld world = (ServerWorld) player.getEntityWorld();
            BlockPos safeLanding = findSafeLanding(world, segment.end());
            Vec3d destination = centerOf(safeLanding);

            double destX = destination.x;
            double destZ = destination.z;
            double destY = destination.y;

            if (segment.jump() && destination.y - player.getY() > 0.1) {
                destY += 0.05;
            }

            player.setSprinting(segment.sprint() && shouldSprint);
            player.refreshPositionAndAngles(destX, destY, destZ, player.getYaw(), player.getPitch());
            player.setVelocity(Vec3d.ZERO);
            player.velocityDirty = true;
            player.setOnGround(true);

            if (player.isInsideWall()) {
                BlockPos emergency = findSafeLanding(world, safeLanding.up());
                Vec3d emergencyCenter = centerOf(emergency);
                player.refreshPositionAndAngles(emergencyCenter.x, emergencyCenter.y, emergencyCenter.z, player.getYaw(), player.getPitch());
                player.setVelocity(Vec3d.ZERO);
                player.velocityDirty = true;
                player.setOnGround(true);
            }

            LOGGER.debug("Moved {} to {} via {} (jump={}, sprint={})", botName, safeLanding, segment.end(), segment.jump(), segment.sprint());
        }

        private void complete(String result) {
            FINAL_RESULT.set(result);
            CompletableFuture<String> future = getPathCompletionFuture();
            if (!future.isDone()) {
                future.complete(result);
            }
        }

        void cancel() {
            jobQueue.clear();
        }

        public static boolean getBotMovementStatus() {
            return moving;
        }

        public static CompletableFuture<String> getPathCompletionFuture() {
            if (completionFuture == null || completionFuture.isDone()) {
                completionFuture = new CompletableFuture<>();
            }
            return completionFuture;
        }

        public static void clearJobs() {
            BotSegmentManager active = ACTIVE_MANAGER.getAndSet(null);
            if (active != null) {
                active.jobQueue.clear();
            }
            moving = false;
            if (completionFuture != null && !completionFuture.isDone()) {
                completionFuture.complete("Path cleared");
            }
            completionFuture = null;
            FINAL_RESULT.set("");
        }

        public static String tracePathOutput(ServerCommandSource botSource) {
            if (botSource == null || botSource.getPlayer() == null) {
                return "Bot not found";
            }

            ServerPlayerEntity bot = botSource.getPlayer();
            BlockPos currentPos = bot.getBlockPos();
            return String.format("Bot moved to position - x: %d y: %d z: %d",
                    currentPos.getX(), currentPos.getY(), currentPos.getZ());
        }
    }

    public static CompletableFuture<String> tracePath(MinecraftServer server,
                                                      ServerCommandSource botSource,
                                                      String botName,
                                                      Queue<Segment> segments,
                                                      boolean sprint) {
        shouldSprint = sprint;
        BotSegmentManager.clearJobs();

        BotSegmentManager manager = new BotSegmentManager(server, botSource, botName);
        ACTIVE_MANAGER.set(manager);

        CompletableFuture<String> completionFuture = BotSegmentManager.getPathCompletionFuture();
        segments.forEach(manager::addSegmentJob);
        manager.begin();
        return completionFuture;
    }

    public static void flushAllMovementTasks() {
        BotSegmentManager.clearJobs();
        LOGGER.info("All movement tasks flushed");
    }

    private static Vec3d centerOf(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static BlockPos findSafeLanding(ServerWorld world, BlockPos target) {
        if (isStandable(world, target)) {
            return target;
        }

        BlockPos cursor = target;
        for (int i = 0; i < 4; i++) {
            cursor = cursor.up();
            if (isStandable(world, cursor)) {
                return cursor;
            }
        }

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        HashSet<BlockPos> visited = new HashSet<>();
        queue.add(target);
        visited.add(target);

        final int maxDistance = 4;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (isStandable(world, pos)) {
                return pos;
            }
            if (target.getManhattanDistance(pos) >= maxDistance) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.offset(direction);
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }

        BlockPos fallback = target.up(6);
        return isStandable(world, fallback) ? fallback : target.up();
    }

    private static boolean isStandable(World world, BlockPos pos) {
        BlockState body = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        BlockState foot = world.getBlockState(pos.down());
        return body.getCollisionShape(world, pos).isEmpty()
                && head.getCollisionShape(world, pos.up()).isEmpty()
                && !foot.getCollisionShape(world, pos.down()).isEmpty();
    }
}
