package net.shasankp000.GameAI.services;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages persistent work direction and pause positions for bots across stripmine and other directional skills.
 * Direction is captured from the command issuer when a job starts and persists until reset.
 * Pause positions are stored when directional jobs (stripmine, stairs) pause, so they can resume from the same location.
 */
public final class WorkDirectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger("work-direction");
    private static final Map<UUID, Direction> STORED_DIRECTIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> PAUSE_POSITIONS = new ConcurrentHashMap<>();

    private WorkDirectionService() {
    }

    /**
     * Stores the work direction for a bot.
     * @param botUuid UUID of the bot
     * @param direction The horizontal direction to store
     */
    public static void setDirection(UUID botUuid, Direction direction) {
        if (botUuid == null || direction == null) {
            return;
        }
        if (!direction.getAxis().isHorizontal()) {
            LOGGER.warn("Attempted to set non-horizontal direction {} for bot {}", direction, botUuid);
            return;
        }
        STORED_DIRECTIONS.put(botUuid, direction);
        LOGGER.info("Set work direction {} for bot {}", direction.asString(), botUuid);
    }

    /**
     * Gets the stored work direction for a bot, if any.
     * @param botUuid UUID of the bot
     * @return The stored direction, or empty if none is set
     */
    public static Optional<Direction> getDirection(UUID botUuid) {
        if (botUuid == null) {
            return Optional.empty();
        }
        Direction dir = STORED_DIRECTIONS.get(botUuid);
        return Optional.ofNullable(dir);
    }

    /**
     * Clears the stored work direction for a bot.
     * @param botUuid UUID of the bot
     * @return true if a direction was cleared, false if none was stored
     */
    public static boolean resetDirection(UUID botUuid) {
        if (botUuid == null) {
            return false;
        }
        Direction removed = STORED_DIRECTIONS.remove(botUuid);
        if (removed != null) {
            LOGGER.info("Reset work direction for bot {} (was {})", botUuid, removed.asString());
            return true;
        }
        return false;
    }

    /**
     * Gets the work direction for a bot, falling back to the provided default if none is stored.
     * @param botUuid UUID of the bot
     * @param defaultDirection Direction to use if none is stored
     * @return The stored direction or the default
     */
    public static Direction getOrDefault(UUID botUuid, Direction defaultDirection) {
        return getDirection(botUuid).orElse(defaultDirection);
    }

    /**
     * Stores the pause position for a directional job (stripmine, stairs).
     * When the bot resumes, it should return to this position first.
     * @param botUuid UUID of the bot
     * @param position The position where the job was paused
     */
    public static void setPausePosition(UUID botUuid, BlockPos position) {
        if (botUuid == null || position == null) {
            return;
        }
        PAUSE_POSITIONS.put(botUuid, position.toImmutable());
        LOGGER.info("Set pause position {} for bot {}", position.toShortString(), botUuid);
    }

    /**
     * Gets the stored pause position for a bot, if any.
     * @param botUuid UUID of the bot
     * @return The stored pause position, or empty if none is set
     */
    public static Optional<BlockPos> getPausePosition(UUID botUuid) {
        if (botUuid == null) {
            return Optional.empty();
        }
        BlockPos pos = PAUSE_POSITIONS.get(botUuid);
        return Optional.ofNullable(pos);
    }

    /**
     * Clears the stored pause position for a bot after it has been used.
     * @param botUuid UUID of the bot
     */
    public static void clearPausePosition(UUID botUuid) {
        if (botUuid == null) {
            return;
        }
        BlockPos removed = PAUSE_POSITIONS.remove(botUuid);
        if (removed != null) {
            LOGGER.info("Cleared pause position {} for bot {}", removed.toShortString(), botUuid);
        }
    }

    /**
     * Clears all stored directions and positions. Useful for cleanup or testing.
     */
    public static void clearAll() {
        int dirCount = STORED_DIRECTIONS.size();
        int posCount = PAUSE_POSITIONS.size();
        STORED_DIRECTIONS.clear();
        PAUSE_POSITIONS.clear();
        LOGGER.info("Cleared all stored work data (directions: {}, pause positions: {})", dirCount, posCount);
    }
}
