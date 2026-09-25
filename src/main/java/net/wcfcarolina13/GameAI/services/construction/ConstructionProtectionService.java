package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks task-scoped construction positions that movement recovery must not mine through.
 *
 * <p>Intended for active schematic/construction tasks where the bot may otherwise treat
 * newly placed structure or temporary scaffolds as generic local obstructions.</p>
 */
public final class ConstructionProtectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger("construction-protection");

    private static final ConcurrentHashMap<UUID, ProtectionContext> ACTIVE_PROTECTIONS = new ConcurrentHashMap<>();
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();

    private ConstructionProtectionService() {}

    private record ProtectionContext(
            String taskId,
            long sessionId,
            Set<BlockPos> plannedPositions,
            Set<BlockPos> stationPositions,
            ProtectionBounds footprintBounds,
            InteriorEgressPolicy.Layout egressLayout
    ) {}

    /**
     * Read-only egress view of the active construction: task id for logs, a session id that
     * changes on every {@link #activate} (the same schematic rebuilt gets a fresh one), and the
     * planned layout for footprint/doorway queries at a given feet Y.
     */
    public record EgressView(String taskId, long sessionId, InteriorEgressPolicy.Layout layout) {}

    private record ProtectionBounds(
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ
    ) {
        boolean contains(BlockPos pos) {
            if (pos == null) {
                return false;
            }
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        String summary() {
            return minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ;
        }
    }

    /**
     * @param doorPositions planned walk-through door cells (subset of {@code plannedPositions});
     *                      they stay open during the build, so egress treats their columns as
     *                      walkable doorways
     */
    public static void activate(UUID botId,
                                String taskId,
                                Set<BlockPos> plannedPositions,
                                Set<BlockPos> stationPositions,
                                Set<BlockPos> doorPositions) {
        if (botId == null) {
            return;
        }
        Set<BlockPos> plannedCopy = immutableCopy(plannedPositions);
        Set<BlockPos> stationCopy = immutableCopy(stationPositions);
        Set<BlockPos> doorCopy = immutableCopy(doorPositions);
        ProtectionBounds bounds = computeBounds(plannedCopy);
        String resolvedTaskId = taskId == null ? "construction" : taskId;
        ACTIVE_PROTECTIONS.put(botId, new ProtectionContext(
                resolvedTaskId,
                SESSION_SEQUENCE.incrementAndGet(),
                plannedCopy,
                stationCopy,
                bounds,
                buildEgressLayout(plannedCopy, doorCopy)
        ));
        LOGGER.info("construction protection active: bot={} task={} planned={} stations={} doors={} bounds={}",
                botId,
                resolvedTaskId,
                plannedCopy.size(),
                stationCopy.size(),
                doorCopy.size(),
                bounds == null ? "none" : bounds.summary());
    }

    /** Footprint/doorway query surface for interior egress; empty when no construction is active. */
    public static Optional<EgressView> egressView(UUID botId) {
        if (botId == null) {
            return Optional.empty();
        }
        ProtectionContext context = ACTIVE_PROTECTIONS.get(botId);
        if (context == null || context.egressLayout().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EgressView(context.taskId(), context.sessionId(), context.egressLayout()));
    }

    private static InteriorEgressPolicy.Layout buildEgressLayout(Set<BlockPos> planned, Set<BlockPos> doors) {
        InteriorEgressPolicy.Layout.Builder builder = InteriorEgressPolicy.Layout.builder();
        for (BlockPos pos : planned) {
            if (doors.contains(pos)) {
                builder.door(pos.getX(), pos.getY(), pos.getZ());
            } else {
                builder.solid(pos.getX(), pos.getY(), pos.getZ());
            }
        }
        for (BlockPos pos : doors) {
            if (!planned.contains(pos)) {
                builder.door(pos.getX(), pos.getY(), pos.getZ());
            }
        }
        return builder.build();
    }

    public static void clear(UUID botId) {
        if (botId == null) {
            return;
        }
        ProtectionContext removed = ACTIVE_PROTECTIONS.remove(botId);
        if (removed != null) {
            LOGGER.info("construction protection cleared: bot={} task={}", botId, removed.taskId());
        }
    }

    public static boolean hasActiveProtection(UUID botId) {
        return botId != null && ACTIVE_PROTECTIONS.containsKey(botId);
    }

    public static boolean isProtected(UUID botId, BlockPos pos) {
        return protectionReason(botId, pos) != null;
    }

    public static String protectionReason(UUID botId, BlockPos pos) {
        if (botId == null || pos == null) {
            return null;
        }
        ProtectionContext context = ACTIVE_PROTECTIONS.get(botId);
        if (context == null) {
            return null;
        }
        BlockPos key = pos.toImmutable();
        if (context.plannedPositions.contains(key)) {
            return "planned-block";
        }
        if (context.stationPositions.contains(key)) {
            return "build-station";
        }
        if (ScaffoldService.isTrackedScaffold(botId, key)) {
            return "tracked-scaffold";
        }
        if (context.footprintBounds != null && context.footprintBounds.contains(key)) {
            return "footprint-volume";
        }
        return null;
    }

    private static ProtectionBounds computeBounds(Set<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : positions) {
            if (pos == null) {
                continue;
            }
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        if (minX == Integer.MAX_VALUE) {
            return null;
        }
        return new ProtectionBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static Set<BlockPos> immutableCopy(Set<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return Set.of();
        }
        Set<BlockPos> copy = new HashSet<>(positions.size());
        for (BlockPos pos : positions) {
            if (pos != null) {
                copy.add(pos.toImmutable());
            }
        }
        return Set.copyOf(copy);
    }
}