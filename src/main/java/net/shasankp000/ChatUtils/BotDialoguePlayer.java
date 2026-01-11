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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Anti-spam: limit how often a bot can play voiced dialogue, especially for combat/status.
     * This is a second line of defense on top of per-feature cooldowns.
     */
    private static final long MIN_GAP_ANY_VOICE_MS = 2_500L;
    private static final long MIN_GAP_COMBAT_STATUS_VOICE_MS = 10_000L;

    private static final ConcurrentHashMap<UUID, Long> LAST_ANY_VOICE_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_COMBAT_STATUS_VOICE_MS = new ConcurrentHashMap<>();

    public enum PlayResult {
        PLAYED,
        THROTTLED,
        DISABLED,
        FAILED
    }

    private enum DialogueCategory {
        COMBAT,
        STATUS,
        OTHER
    }

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

        // Weather
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WEATHER_RAIN, "Rain's coming down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WEATHER_SNOW, "Snow's coming down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WEATHER_THUNDER, "Thunderstorm.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WEATHER_SUNNY, "Nice clear day.");

        // Time-of-day reminders
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_TIME_SUNSET_SOON, "Sun will go down soon.");

        // Nonverbal / foley
        SUBTITLE_MAP.put(BotDialogueSounds.FX_HURT_GRUNT, "(hurt grunt)");
        
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
        
        // Combat mode context
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_STANDING_DOWN, "Standing down unless attacked.");
        
        // Combat callouts (during fights)
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_THREAT_DETECTED, "Hostile spotted!");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_ATTACKING, "Engaging!");
        // Legacy kill event plays a pool of different kill clips; keep subtitle generic.
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL, "Enemy down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_GOT_IT, "Got it!");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_TARGET_DOWN, "Target down!");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_ONE_LESS, "One less to worry about.");

        // Creature-specific kill confirmations
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_CREEPER_1, "Creeper down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_CREEPER_2, "No boom today.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_SKELETON_1, "Skeleton down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_SKELETON_2, "Bones scattered.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_ZOMBIE_1, "Zombie down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_ZOMBIE_2, "Back in the ground.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_SPIDER_1, "Spider down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_SPIDER_2, "Webs won't save you.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_ENDERMAN_1, "Enderman down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_ENDERMAN_2, "Glad I didn't blink.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_WITCH_1, "Witch down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_WITCH_2, "Potion problem solved.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_SLIME_1, "Slime down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_SLIME_2, "Back to goo.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_PILLAGER_1, "Pillager down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_PILLAGER_2, "One less raider.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_VINDICATOR_1, "Vindicator down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_VINDICATOR_2, "Axe is out.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_EVOKER_1, "Evoker down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_EVOKER_2, "No more tricks.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_MAGMA_CUBE_1, "Magma cube down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_MAGMA_CUBE_2, "Back to magma.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_RAVAGER_1, "Ravager down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_RAVAGER_2, "Beast is down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_VEX_1, "Vex down.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_KILL_VEX_2, "No more flying blades.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_CLEAR, "All clear.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_PLAYER_HIT, "Hey! Watch it!");

        // Combat: ranged misses
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_MISSED_1, "I missed!");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_MISSED_2, "Dang, missed!");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_MISSED_3, "Ugh. Missed.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_COMBAT_MISSED_4, "That one went wide!");
        
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

        // Mining / discovery callouts
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_DISCOVER_MINESHAFT, "I found a mineshaft!");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_DISCOVER_SPAWNER, "I found a mob spawner!");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_HAZARD_LAVA, "Careful, there's lava ahead.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_HAZARD_WATER, "Water detected ahead.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WARNING_DROP_AHEAD, "There's a drop ahead.");

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

        // Special environment - amethyst
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_AMETHYST_BEAUTIFUL, "These crystals are beautiful.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_AMETHYST_SPARKLY, "So sparkly down here.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_AMETHYST_GEODE, "We found a geode!");

        // Special environment - bats
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_BAT_STARTLED, "Ah! Bats!");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_BAT_WINGS, "I hear wings flapping.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_BAT_CREEPY, "Bats give me the creeps.");

        // Special environment - dripstone
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_DRIPSTONE_CAREFUL, "Careful, those are sharp.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_DRIPSTONE_SHARP, "Pointy stalactites everywhere.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_DRIPSTONE_DRIPPING, "I can hear dripping.");

        // Special environment - deepslate
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_DEEPSLATE_COLD, "It's cold down here.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_DEEPSLATE_DEEP, "We're really deep now.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_AMBIENT_DEEPSLATE_ANCIENT, "This stone feels ancient.");

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
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_WILDLIFE_HEARD_WOLF, "I heard a wolf.");

        // Nether ambience
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_ENTER_1, "Nether... stay close.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_ENTER_2, "We're in the Nether. Watch your step.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_HOT, "It's hot. Real hot.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_LAVA, "Lava everywhere. Careful.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_GHASTS, "Keep an eye out for ghasts.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_PIGLINS, "Piglins are watching us.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_SOUL_SAND, "This soul sand... I hate it.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_FORTRESS, "That looks like a fortress.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_BASTION, "Lots of blackstone... could be a bastion nearby.");

        // Nether biome flavor
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_BIOME_CRIMSON_FOREST, "Crimson forest... red as far as I can see.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_BIOME_WARPED_FOREST, "Warped forest... keep your eyes open.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_BIOME_SOUL_SAND_VALLEY, "Soul Sand Valley... I don't like the sound of it.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_BIOME_BASALT_DELTAS, "Basalt deltas. Lots of sharp stone.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_NETHER_BIOME_WASTES, "Nether wastes... heat and ash.");

        // The End ambience
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_ENTER_1, "This is The End...");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_ENTER_2, "Don't look down. Just... don't.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_EERIE, "This place feels wrong.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_VOID, "One step and it's over.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_ISLANDS, "Islands floating in the void...");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_CHORUS, "Chorus fruit nearby.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_ENDERMEN, "Endermen everywhere...");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_KEEP_EYES_UP, "Keep your eyes up. Stay sharp.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_CITY, "End City nearby.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_GATEWAY, "End gateway spotted.");

        // The End biome flavor
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_BIOME_MAIN_ISLAND, "Main island. Stay focused.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_BIOME_HIGHLANDS, "End highlands ahead.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_BIOME_MIDLANDS, "End midlands... still feels like the void.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_BIOME_BARRENS, "End barrens... nothing out here.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_END_BIOME_SMALL_ISLANDS, "Small islands... careful crossing.");

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

        // ============ JANUARY 2026: BANTER ==========
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_BANTER_CREEPER_KIDDING, "Creeper... kidding.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_BANTER_IF_YELL_RUN, "If I yell, run.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_BANTER_GOT_YOUR_BACK, "I've got your back.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_BANTER_STAY_SHARP, "Stay sharp out here.");

        // ============ JANUARY 2026: MOUNT / LEAD HANDLING ==========
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_HORSE_HURT, "This horse looks hurt.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_BANGED_UP, "Mount's looking banged up.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_MY_HORSE_HURT, "My horse is hurt.");

        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_NO_APPLES, "I don't have any apples to heal it.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_OUT_OF_APPLES, "I'm out of apples for the horse.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_NO_APPLES_ON_ME, "No apples on me for this horse.");

        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_NO_SUITABLE_FOOD, "I don't have any suitable food to heal it.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_CANT_FIND_FOOD, "I can't find food for it.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_NO_FEED, "I don't have feed for this horse.");

        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_NO_LEAD_SECURE, "I don't have a lead to secure this horse.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_MISSING_LEAD, "I'm missing a lead for the horse.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_CANT_SECURE, "I can't secure it without a lead.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_CANT_GRAB_LEAD, "I can't grab a lead to secure this horse.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_COULDNT_GET_LEAD, "I couldn't get the lead.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_LEAD_NOT_ACCESSIBLE, "My lead isn't accessible right now.");

        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_NO_FENCE_KEEP_LEAD, "I don't have a fence to tie this horse to yet. I'll keep it on a lead.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_NO_FENCE_NEARBY, "No fence nearby to tie it off.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_HOLD_LEAD_UNTIL_TIE, "I'll hold the lead until I can tie it off.");

        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_LOST_TRACK_HOLDING, "I lost track of the horse I was holding.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_LOST_HORSE_LEAD, "I lost the horse I was leading.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_CANT_FIND_HORSE, "I can't find the horse.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_HORSE_GONE, "The horse I was holding is gone.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_MOUNT_GONE, "My mount is gone.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_LOST_LEADING, "I lost the animal I was leading.");

        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_LEAD_SNAPPED_DROP, "The lead snapped after a sudden drop.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_LEAD_BROKE_FALL, "The lead broke after that fall.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_LEAD_SNAPPED_ON_DROP, "The lead snapped on that drop.");

        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_NO_LEAD_REATTACH, "I don't have a lead to reattach.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_OUT_OF_LEADS, "I'm out of leads to reattach.");
        SUBTITLE_MAP.put(BotDialogueSounds.LINE_MOUNT_NO_SPARE_LEADS, "No spare leads to reattach.");
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

        // Play the sound at the bot's location (throttled to avoid spam)
        PlayResult res = playSoundInternal(bot, sound, false);
        return res == PlayResult.PLAYED;
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

    private static DialogueCategory categoryFor(SoundEvent sound) {
        if (sound == null || sound.id() == null) {
            return DialogueCategory.OTHER;
        }
        String path = sound.id().getPath();
        if (path == null) {
            return DialogueCategory.OTHER;
        }
        if (path.startsWith("bot.line.combat_")) {
            return DialogueCategory.COMBAT;
        }
        if (path.startsWith("bot.line.status_") || path.startsWith("bot.line.warning_")) {
            return DialogueCategory.STATUS;
        }
        return DialogueCategory.OTHER;
    }

    private static boolean shouldThrottle(UUID botId, DialogueCategory category, long nowMs) {
        if (botId == null) {
            return false;
        }
        long lastAny = LAST_ANY_VOICE_MS.getOrDefault(botId, 0L);
        if (nowMs - lastAny < MIN_GAP_ANY_VOICE_MS) {
            return true;
        }

        if (category == DialogueCategory.COMBAT || category == DialogueCategory.STATUS) {
            long lastCombatStatus = LAST_COMBAT_STATUS_VOICE_MS.getOrDefault(botId, 0L);
            if (nowMs - lastCombatStatus < MIN_GAP_COMBAT_STATUS_VOICE_MS) {
                return true;
            }
        }

        return false;
    }

    private static void markPlayed(UUID botId, DialogueCategory category, long nowMs) {
        if (botId == null) {
            return;
        }
        LAST_ANY_VOICE_MS.put(botId, nowMs);
        if (category == DialogueCategory.COMBAT || category == DialogueCategory.STATUS) {
            LAST_COMBAT_STATUS_VOICE_MS.put(botId, nowMs);
        }
    }

    private static PlayResult playSoundInternal(ServerPlayerEntity bot, SoundEvent sound, boolean showSubtitle) {
        if (bot == null || sound == null) {
            return PlayResult.FAILED;
        }

        long now = System.currentTimeMillis();
        UUID botId = bot.getUuid();
        DialogueCategory category = categoryFor(sound);

        if (shouldThrottle(botId, category, now)) {
            LOGGER.debug("[VoicedDialogue] Throttled sound {} for bot {}", sound.id(), bot.getName().getString());
            return PlayResult.THROTTLED;
        }

        boolean played = playSound(bot, sound);
        if (!played) {
            return PlayResult.FAILED;
        }

        markPlayed(botId, category, now);

        if (showSubtitle) {
            showSubtitle(bot, sound);
        }

        return PlayResult.PLAYED;
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
        return playSoundForBotDetailed(bot, sound) == PlayResult.PLAYED;
    }

    /**
     * Like {@link #playSoundForBot(ServerPlayerEntity, SoundEvent)} but returns a detailed outcome.
     * Callers can distinguish "throttled" from "disabled" and avoid chat fallback spam.
     */
    public static PlayResult playSoundForBotDetailed(ServerPlayerEntity bot, SoundEvent sound) {
        if (bot == null || sound == null) {
            return PlayResult.FAILED;
        }

        String botName = bot.getName().getString();
        ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getEffectiveBotControl(botName);
        if (settings == null || !settings.isVoicedDialogue()) {
            return PlayResult.DISABLED;
        }

        return playSoundInternal(bot, sound, true);
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
