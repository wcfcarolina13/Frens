package net.shasankp000.GameAI.services;

import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages persistent work direction for bots across stripmine and other directional skills.
 * Direction is captured from the command issuer when a job starts and persists until reset.
 */
public final class WorkDirectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger("work-direction");
    private static final Map<UUID, Direction> STORED_DIRECTIONS = new ConcurrentHashMap<>();

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
     * Clears all stored directions. Useful for cleanup or testing.
     */
    public static void clearAll() {
        int count = STORED_DIRECTIONS.size();
        STORED_DIRECTIONS.clear();
        LOGGER.info("Cleared all stored work directions (count: {})", count);
    }
}
