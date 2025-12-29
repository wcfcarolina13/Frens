package net.shasankp000.ChatUtils;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles playing dialogue sounds for bot chat messages.
 * 
 * <p>This class provides the connection between chat messages and audio playback,
 * looking up the appropriate sound for each message and playing it at the bot's location.
 */
public final class BotDialoguePlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-dialogue");

    // Audio settings - volume affects the audible range (lower = quieter and shorter range)
    private static final float VOLUME = 0.8f;
    private static final float PITCH = 1.0f;

    private BotDialoguePlayer() {
    }

    /**
     * Attempt to play the dialogue sound for a chat message.
     * 
     * <p>This method:
     * <ul>
     *   <li>Checks if voiced dialogue is enabled in config</li>
     *   <li>Looks up the sound for the message text</li>
     *   <li>Plays the sound at the bot's location if found</li>
     * </ul>
     * 
     * @param source The command source (should be the bot)
     * @param message The chat message text
     * @return true if a sound was played, false otherwise
     */
    public static boolean tryPlayDialogue(ServerCommandSource source, String message) {
        if (source == null || message == null || message.isBlank()) {
            LOGGER.info("[VoicedDialogue] Skipping: null/blank source or message");
            return false;
        }

        // Get the bot entity from the source
        ServerPlayerEntity bot = null;
        try {
            // Try to get the player entity from the source
            if (source.getEntity() instanceof ServerPlayerEntity player) {
                bot = player;
            }
        } catch (Exception e) {
            LOGGER.info("[VoicedDialogue] Could not get player entity from source: {}", e.getMessage());
            return false;
        }

        if (bot == null) {
            LOGGER.info("[VoicedDialogue] No bot entity found in source");
            return false;
        }

        // Check if this is actually a registered bot
        if (!BotEventHandler.isRegisteredBot(bot)) {
            LOGGER.info("[VoicedDialogue] Entity is not a registered bot: {}", bot.getName().getString());
            return false;
        }

        // Check if voiced dialogue is enabled for this bot
        String botName = bot.getName().getString();
        ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getEffectiveBotControl(botName);
        if (settings == null || !settings.isVoicedDialogue()) {
            LOGGER.info("[VoicedDialogue] Disabled for bot: {}", botName);
            return false;
        }

        // Look up the sound for this message
        LOGGER.info("[VoicedDialogue] Looking up sound for message: '{}'", message);
        SoundEvent sound = DialogueTextMapper.lookup(message);
        if (sound == null) {
            LOGGER.info("[VoicedDialogue] No sound mapping for message: '{}'", message);
            return false;
        }

        LOGGER.info("[VoicedDialogue] Found sound: {} for message", sound.id());
        // Play the sound at the bot's location
        return playSound(bot, sound);
    }

    /**
     * Play a sound event at the bot's location with proper 3D positional audio.
     * 
     * @param bot The bot entity
     * @param sound The sound event to play
     * @return true if the sound was played successfully
     */
    private static boolean playSound(ServerPlayerEntity bot, SoundEvent sound) {
        if (bot == null || sound == null) {
            return false;
        }

        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            LOGGER.debug("Bot is not in a server world");
            return false;
        }

        try {
            double x = bot.getX();
            double y = bot.getY();
            double z = bot.getZ();
            
            // Use playSoundFromEntity for proper 3D positional audio that follows the entity
            // This creates realistic distance-based attenuation
            world.playSoundFromEntity(
                    null,           // Player to exclude (null = play for everyone)
                    bot,            // The entity the sound emanates from
                    sound,
                    SoundCategory.VOICE,
                    VOLUME,
                    PITCH
            );

            LOGGER.info("[VoicedDialogue] Played sound {} at ({}, {}, {})", 
                    sound.id(), x, y, z);
            return true;

        } catch (Exception e) {
            LOGGER.warn("[VoicedDialogue] Failed to play sound: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Convenience method to play a specific sound event for a bot.
     * Useful for programmatic sound playback outside of chat.
     * 
     * @param bot The bot entity
     * @param sound The sound event to play
     * @return true if the sound was played
     */
    public static boolean playSoundForBot(ServerPlayerEntity bot, SoundEvent sound) {
        if (bot == null || sound == null) {
            return false;
        }

        // Check if voiced dialogue is enabled for this bot
        String botName = bot.getName().getString();
        ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getEffectiveBotControl(botName);
        if (settings == null || !settings.isVoicedDialogue()) {
            return false;
        }

        return playSound(bot, sound);
    }

    /**
     * Force-play a sound for a bot, bypassing the voiced dialogue check.
     * Used for testing and debugging (e.g., /bot sound_test command).
     * 
     * @param bot The bot entity
     * @param sound The sound event to play
     * @return true if the sound was played
     */
    public static boolean forcePlaySound(ServerPlayerEntity bot, SoundEvent sound) {
        if (bot == null || sound == null) {
            return false;
        }
        return playSound(bot, sound);
    }
}
