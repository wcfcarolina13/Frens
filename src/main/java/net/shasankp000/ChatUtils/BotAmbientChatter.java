package net.shasankp000.ChatUtils;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.world.World;
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides ambient "talking to self" chatter for bots when idle.
 * 
 * <p>This creates an immersive atmosphere where bots occasionally vocalize
 * idle thoughts, similar to how Minecraft villagers hum to themselves.
 * 
 * <p>Chatter is infrequent (every 2-5 minutes when idle) and only occurs when:
 * <ul>
 *   <li>Voiced dialogue is enabled for the bot</li>
 *   <li>Bot is truly idle (not following, guarding, patrolling, or running a task)</li>
 *   <li>Bot is in the overworld during daytime</li>
 * </ul>
 */
public final class BotAmbientChatter {

    private static final Logger LOGGER = LoggerFactory.getLogger("ambient-chatter");
    private static final Random RNG = new Random();

    // Chatter frequency: minimum and maximum delay in ticks between chatter attempts
    // 2400 ticks = 2 minutes, 6000 ticks = 5 minutes
    private static final long MIN_DELAY_TICKS = 2400L;
    private static final long MAX_DELAY_TICKS = 6000L;

    // Don't chatter at night (after sunset) - it would be weird
    private static final int DONT_CHATTER_AFTER_TOD = 12_500;
    private static final int DONT_CHATTER_BEFORE_TOD = 500;

    // Per-bot next chatter tick
    private static final Map<UUID, Long> NEXT_CHATTER_TICK = new ConcurrentHashMap<>();

    // Idle chatter sounds - things the bot might say when standing around
    private static final SoundEvent[] IDLE_CHATTER = {
            BotDialogueSounds.LINE_IDLE_ALL_QUIET,
            BotDialogueSounds.LINE_IDLE_STILL_STANDING,
            BotDialogueSounds.LINE_IDLE_TAKING_IT_EASY,
            BotDialogueSounds.LINE_IDLE_HERE_IF_NEEDED,
            BotDialogueSounds.LINE_IDLE_ENJOYING_CALM,
    };

    // Context-aware chatter - things the bot might muse about
    private static final SoundEvent[] CONTEXT_CHATTER = {
            BotDialogueSounds.LINE_CONTEXT_BREATHER_SOMETIMES,
            BotDialogueSounds.LINE_CONTEXT_CAMPFIRE_WONDERS,
            BotDialogueSounds.LINE_CONTEXT_LISTENING,
    };

    private BotAmbientChatter() {
    }

    /**
     * Clears all scheduler state. Should be called when the server world changes.
     */
    public static void resetSession() {
        NEXT_CHATTER_TICK.clear();
    }

    /**
     * Called every server tick to potentially trigger ambient chatter.
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long nowTick = server.getTicks();

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }

            // Check if voiced dialogue is enabled
            String botName = bot.getName().getString();
            ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getEffectiveBotControl(botName);
            if (settings == null || !settings.isVoicedDialogue()) {
                continue;
            }

            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }

            // Only in overworld
            if (world.getRegistryKey() != World.OVERWORLD) {
                continue;
            }

            // Don't chatter while sleeping
            if (bot.isSleeping()) {
                continue;
            }

            // Only chatter when truly idle
            if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.IDLE) {
                continue;
            }

            // Don't compete with active tasks
            if (TaskService.hasActiveTask(bot.getUuid())) {
                continue;
            }

            // Check time of day - no chatter at night
            int tod = (int) (world.getTimeOfDay() % 24_000L);
            if (tod >= DONT_CHATTER_AFTER_TOD || tod < DONT_CHATTER_BEFORE_TOD) {
                continue;
            }

            UUID botUuid = bot.getUuid();
            long nextChatter = NEXT_CHATTER_TICK.getOrDefault(botUuid, 0L);

            // Handle server restart: if next tick is way in the future, reset it
            if (nowTick < 4_000L && nextChatter - nowTick > MAX_DELAY_TICKS * 2) {
                nextChatter = nowTick + randomDelay();
                NEXT_CHATTER_TICK.put(botUuid, nextChatter);
            }

            if (nowTick < nextChatter) {
                continue;
            }

            // Time to maybe chatter!
            // Pick a random sound from idle or context chatter
            SoundEvent sound = pickChatterSound();
            
            if (sound != null) {
                // Play the sound (respects voiced dialogue setting internally)
                if (BotDialoguePlayer.playSoundForBot(bot, sound)) {
                    LOGGER.debug("Ambient chatter for {}: {}", botName, sound.id().getPath());
                }
            }

            // Schedule next chatter attempt
            NEXT_CHATTER_TICK.put(botUuid, nowTick + randomDelay());
        }
    }

    private static long randomDelay() {
        return MIN_DELAY_TICKS + RNG.nextLong(MAX_DELAY_TICKS - MIN_DELAY_TICKS);
    }

    private static SoundEvent pickChatterSound() {
        // 70% chance for idle chatter, 30% for context chatter
        if (RNG.nextFloat() < 0.7f) {
            return IDLE_CHATTER[RNG.nextInt(IDLE_CHATTER.length)];
        } else {
            return CONTEXT_CHATTER[RNG.nextInt(CONTEXT_CHATTER.length)];
        }
    }

    /**
     * Manually trigger a chatter sound for testing purposes.
     * 
     * @param bot The bot to make chatter
     * @return true if a sound was played
     */
    public static boolean triggerChatter(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        SoundEvent sound = pickChatterSound();
        return BotDialoguePlayer.playSoundForBot(bot, sound);
    }
}
