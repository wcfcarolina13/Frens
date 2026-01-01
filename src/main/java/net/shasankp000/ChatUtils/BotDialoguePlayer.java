package net.shasankp000.ChatUtils;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles playing dialogue sounds for bot chat messages.
 * 
 * <p>This class provides the connection between chat messages and audio playback,
 * looking up the appropriate sound for each message and playing it at the bot's location.
 * 
 * <p>Also supports subtitle display via action bar for accessibility and immersion.
 */
public final class BotDialoguePlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-dialogue");

    // Audio settings - volume affects the audible range (lower = quieter and shorter range)
    private static final float VOLUME = 0.8f;
    private static final float PITCH = 1.0f;
    
    // Subtitle range - players within this range see subtitles
    private static final double SUBTITLE_RANGE = 16.0;

    // Map sound events to subtitle text for ambient chatter
    private static final Map<SoundEvent, String> SUBTITLE_MAP = new HashMap<>();
    
    static {
        initializeSubtitles();
    }
    
    private static void initializeSubtitles() {
        // Idle chatter
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_IDLE_ALL_QUIET, "All quiet around here...");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_IDLE_STILL_STANDING, "Still standing. Still ready.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_IDLE_TAKING_IT_EASY, "Just taking it easy.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_IDLE_HERE_IF_NEEDED, "I'm here if you need me.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_IDLE_ENJOYING_CALM, "Enjoying the calm.");
        
        // Context chatter
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_CONTEXT_BREATHER_SOMETIMES, "Nice to take a breather sometimes.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_CONTEXT_CAMPFIRE_WONDERS, "A little campfire time does wonders.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_CONTEXT_LISTENING, "If you need me, I'm listening.");
        
        // Fishing context chatter
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_CONTEXT_FISH_EARLIER, "I was fishing a bit ago. Not a bad haul.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_CONTEXT_SMELLS_FISH, "Still smells like fish. Could be worse.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_CONTEXT_FISH_COOPERATING, "The fish were cooperating earlier.");
        
        // Hangout context chatter
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_CONTEXT_WARMING_EARLIER, "I was just warming up by the fire earlier.");
        
        // Combat context
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_STANDING_DOWN, "Standing down unless attacked.");
        
        // Status - injured
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_STATUS_NEED_BREATHER, "I could use a breather.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_STATUS_NOT_BEST, "Not feeling my best right now.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_STATUS_TOO_MANY_HITS, "I've taken too many hits today.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WARNING_BANGED_UP, "I'm a bit banged up.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WARNING_NOT_FULL_STRENGTH, "I'm not at full strength.");
        
        // Status - hungry
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_STATUS_HUNGRY, "I'm getting hungry...");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_STATUS_FIND_FOOD, "We should find food soon.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_STATUS_SNACK_TIME, "Snack time, maybe?");

        // ============ NEW CHATTERBOX SUBTITLES ============
        // Ambient / cave
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_HEARD_SOMETHING, "I heard something.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_DID_YOU_HEAR, "Did you hear that?");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_SOMETHING_MOVED, "Something moved.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_NOT_ALONE, "Not alone...");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_SMELLS_TERRIBLE, "Smells terrible.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_DONT_LIKE_THIS, "I don't like this.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_CREEPY, "This is creepy.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_CAVE_DEEP, "Deep in the cave.");

        // Darkness
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_DARK_CANT_SEE, "I can't see a thing.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_DARK_WHERE_ARE_YOU, "Where are you?");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_DARK_NEED_LIGHT, "I need light.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_DARK_TOO_DARK, "It's too dark.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_DARK_TORCH_PLEASE, "Torch please.");

        // Wildlife
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WILDLIFE_HEARD_BIRD, "I heard a bird.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WILDLIFE_SAW_COW, "Saw a cow.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WILDLIFE_PIG_NEARBY, "Pig nearby.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WILDLIFE_SHEEP_AROUND, "Sheep around.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WILDLIFE_CHICKEN, "Chicken nearby.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WILDLIFE_NICE_DAY, "Nice day.");

        // Lost / found
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_LOST_OVER_HERE, "Over here!");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_LOST_CAN_ANYONE_HEAR, "Can anyone hear me?");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_LOST_HELLO, "Hello?");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_LOST_HELP, "Help!");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_LOST_STUCK_HERE, "I'm stuck here.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_LOST_WHERE_IS_EVERYONE, "Where is everyone?");

        SUBTITLE_MAP.put(BotDialogueSounds.LINE_FOUND_FINALLY, "Finally! Someone found me.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_FOUND_THANK_GOODNESS, "Thank goodness.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_FOUND_THOUGHT_ID_BE_STUCK, "Thought I'd be stuck.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_FOUND_SO_GLAD, "So glad.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_FOUND_BEEN_CALLING, "I've been calling.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_FOUND_LETS_GET_OUT, "Let's get out.");
    }

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
     * Useful for programmatic sound playback outside of chat (e.g., ambient chatter).
     * Shows subtitle to nearby players if available.
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

        boolean played = playSound(bot, sound);
        
        // Show subtitle to nearby players for ambient chatter
        if (played) {
            showSubtitle(bot, sound);
        }
        
        return played;
    }
    
    /**
     * Show a subtitle to nearby players via action bar.
     * Only shows subtitles for sounds that have mapped text (like ambient chatter).
     * 
     * @param bot The bot speaking
     * @param sound The sound being played
     */
    private static void showSubtitle(ServerPlayerEntity bot, SoundEvent sound) {
        String subtitleText = SUBTITLE_MAP.get(sound);
        if (subtitleText == null) {
            return; // No subtitle for this sound
        }
        
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        
        String botName = bot.getName().getString();
        Text subtitle = Text.literal("[" + botName + "] ")
                .formatted(Formatting.GRAY, Formatting.ITALIC)
                .append(Text.literal(subtitleText).formatted(Formatting.WHITE, Formatting.ITALIC));
        
        // Send to all players within range
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player == bot) continue; // Don't send to the bot itself
            
            double distance = player.squaredDistanceTo(bot);
            if (distance <= SUBTITLE_RANGE * SUBTITLE_RANGE) {
                player.sendMessage(subtitle, true); // true = action bar
            }
        }
        
        LOGGER.debug("[Subtitle] Showed '{}' from {} to nearby players", subtitleText, botName);
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
