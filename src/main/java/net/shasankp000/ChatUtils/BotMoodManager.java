package net.shasankp000.ChatUtils;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages emotional state for each bot based on their current situation.
 * 
 * <p>Mood is computed from:
 * <ul>
 *   <li>Recent combat (damage taken in last 10 seconds = STRESSED)</li>
 *   <li>Health level (below 50% = INJURED)</li>
 *   <li>Hunger level (below 8 food points = HUNGRY)</li>
 *   <li>Idle time with good stats (30+ seconds = CONTENT)</li>
 * </ul>
 * 
 * <p>States have priority: STRESSED > INJURED > HUNGRY > CONTENT > NEUTRAL
 */
public final class BotMoodManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-mood");

    // Thresholds for state determination
    private static final float INJURED_HEALTH_PERCENT = 0.5f; // Below 50% health
    private static final int HUNGRY_FOOD_LEVEL = 8; // Below 8 food points (out of 20)
    private static final float HEALTHY_HEALTH_PERCENT = 0.8f; // Above 80% health for content
    private static final int WELL_FED_FOOD_LEVEL = 16; // Above 16 food for content
    
    // Timing thresholds (in ticks, 20 ticks = 1 second)
    private static final long COMBAT_STRESS_DURATION_TICKS = 200L; // 10 seconds
    private static final long CONTENT_IDLE_DURATION_TICKS = 600L; // 30 seconds

    // Track last combat/damage time per bot
    private static final Map<UUID, Long> LAST_DAMAGE_TICK = new ConcurrentHashMap<>();
    
    // Track when bot became idle with good stats
    private static final Map<UUID, Long> CONTENT_START_TICK = new ConcurrentHashMap<>();

    private BotMoodManager() {
    }

    /**
     * Clears all mood tracking state. Call on server/world change.
     */
    public static void resetSession() {
        LAST_DAMAGE_TICK.clear();
        CONTENT_START_TICK.clear();
        LOGGER.debug("Mood manager session reset");
    }

    /**
     * Notify that a bot took damage. This triggers the STRESSED state.
     * Should be called from damage event handlers.
     * 
     * @param bot The bot that took damage
     */
    public static void noteDamage(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return;
        }
        long nowTick = server.getTicks();
        LAST_DAMAGE_TICK.put(bot.getUuid(), nowTick);
        // Reset content timer when damaged
        CONTENT_START_TICK.remove(bot.getUuid());
        LOGGER.debug("Bot {} took damage, mood set to stressed", bot.getName().getString());
    }

    /**
     * Check if bot was recently in combat (took damage within the stress window).
     */
    public static boolean wasRecentlyInCombat(ServerPlayerEntity bot) {
        if (bot == null || bot.getCommandSource().getServer() == null) {
            return false;
        }
        long nowTick = bot.getCommandSource().getServer().getTicks();
        long lastDamage = LAST_DAMAGE_TICK.getOrDefault(bot.getUuid(), -1L);
        if (lastDamage < 0) {
            return false;
        }
        return (nowTick - lastDamage) < COMBAT_STRESS_DURATION_TICKS;
    }

    /**
     * Get the current emotional state for a bot.
     * 
     * <p>Evaluates conditions in priority order and returns the highest-priority
     * matching state.
     * 
     * @param bot The bot to evaluate
     * @return The current EmotionalState
     */
    public static EmotionalState getMood(ServerPlayerEntity bot) {
        if (bot == null || bot.getCommandSource().getServer() == null) {
            return EmotionalState.NEUTRAL;
        }

        UUID botId = bot.getUuid();
        MinecraftServer server = bot.getCommandSource().getServer();
        long nowTick = server.getTicks();

        // Priority 1: STRESSED - Recent combat/damage
        if (wasRecentlyInCombat(bot)) {
            CONTENT_START_TICK.remove(botId); // Reset content timer
            return EmotionalState.STRESSED;
        }

        // Get current stats
        float healthPercent = bot.getHealth() / bot.getMaxHealth();
        HungerManager hunger = bot.getHungerManager();
        int foodLevel = hunger.getFoodLevel();

        // Priority 2: INJURED - Low health
        if (healthPercent < INJURED_HEALTH_PERCENT) {
            CONTENT_START_TICK.remove(botId);
            return EmotionalState.INJURED;
        }

        // Priority 3: HUNGRY - Low food
        if (foodLevel < HUNGRY_FOOD_LEVEL) {
            CONTENT_START_TICK.remove(botId);
            return EmotionalState.HUNGRY;
        }

        // Check for CONTENT conditions: healthy, well-fed, idle
        boolean isHealthy = healthPercent >= HEALTHY_HEALTH_PERCENT;
        boolean isWellFed = foodLevel >= WELL_FED_FOOD_LEVEL;
        boolean isIdle = BotEventHandler.getCurrentMode(bot) == BotEventHandler.Mode.IDLE;

        if (isHealthy && isWellFed && isIdle) {
            // Start or check content timer
            long contentStart = CONTENT_START_TICK.getOrDefault(botId, -1L);
            if (contentStart < 0) {
                // Start the timer
                CONTENT_START_TICK.put(botId, nowTick);
            } else if ((nowTick - contentStart) >= CONTENT_IDLE_DURATION_TICKS) {
                // Been content long enough
                return EmotionalState.CONTENT;
            }
        } else {
            // Not meeting content conditions, reset timer
            CONTENT_START_TICK.remove(botId);
        }

        return EmotionalState.NEUTRAL;
    }

    /**
     * Get a human-readable description of the bot's current mood.
     * Useful for debugging or status displays.
     */
    public static String getMoodDescription(ServerPlayerEntity bot) {
        EmotionalState mood = getMood(bot);
        return switch (mood) {
            case STRESSED -> "stressed from recent combat";
            case INJURED -> "hurt and in pain";
            case HUNGRY -> "hungry and needs food";
            case CONTENT -> "relaxed and content";
            case NEUTRAL -> "doing fine";
        };
    }

    /**
     * Called each tick to potentially update mood-related state.
     * Currently just ensures cleanup of stale entries.
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        
        // Cleanup stale entries for removed bots (every ~5 seconds)
        if (server.getTicks() % 100 == 0) {
            LAST_DAMAGE_TICK.keySet().removeIf(uuid -> {
                ServerPlayerEntity bot = server.getPlayerManager().getPlayer(uuid);
                return bot == null || bot.isRemoved();
            });
            CONTENT_START_TICK.keySet().removeIf(uuid -> {
                ServerPlayerEntity bot = server.getPlayerManager().getPlayer(uuid);
                return bot == null || bot.isRemoved();
            });
        }
    }
}
