package net.shasankp000.ChatUtils;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.shasankp000.AIPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for all bot dialogue sound events.
 * 
 * <p>Sound events are defined in sounds.json and registered here for use in code.
 * The sounds.json defines both category events (bot.greeting, bot.touch, etc.)
 * and individual line events (bot.line.greeting_hey, bot.line.touch_hmm, etc.).
 */
public final class BotDialogueSounds {

    private static final Map<String, SoundEvent> SOUND_EVENTS = new HashMap<>();

    // Category sound events (play random sound from category)
    public static final SoundEvent BOT_GREETING = register("bot.greeting");
    public static final SoundEvent BOT_TOUCH = register("bot.touch");
    public static final SoundEvent BOT_STATUS = register("bot.status");
    public static final SoundEvent BOT_WARNING = register("bot.warning");
    public static final SoundEvent BOT_IDLE = register("bot.idle");
    public static final SoundEvent BOT_SKILL = register("bot.skill");
    public static final SoundEvent BOT_MODE = register("bot.mode");

    // Line-specific sound events (greeting)
    public static final SoundEvent LINE_GREETING_HEY = register("bot.line.greeting_hey");
    public static final SoundEvent LINE_GREETING_GOOD_TO_SEE = register("bot.line.greeting_good_to_see");
    public static final SoundEvent LINE_GREETING_WELCOME_BACK = register("bot.line.greeting_welcome_back");
    public static final SoundEvent LINE_GREETING_THERE_YOU_ARE = register("bot.line.greeting_there_you_are");

    // Line-specific sound events (touch)
    public static final SoundEvent LINE_TOUCH_HMM = register("bot.line.touch_hmm");
    public static final SoundEvent LINE_TOUCH_YEAH = register("bot.line.touch_yeah");
    public static final SoundEvent LINE_TOUCH_WHAT_NEED = register("bot.line.touch_what_need");
    public static final SoundEvent LINE_TOUCH_NEED_SOMETHING = register("bot.line.touch_need_something");

    // Line-specific sound events (status)
    public static final SoundEvent LINE_STATUS_HUNGRY = register("bot.line.status_hungry");
    public static final SoundEvent LINE_STATUS_FIND_FOOD = register("bot.line.status_find_food");
    public static final SoundEvent LINE_STATUS_SNACK_TIME = register("bot.line.status_snack_time");
    public static final SoundEvent LINE_STATUS_NEED_BREATHER = register("bot.line.status_need_breather");
    public static final SoundEvent LINE_STATUS_NOT_BEST = register("bot.line.status_not_best");
    public static final SoundEvent LINE_STATUS_TOO_MANY_HITS = register("bot.line.status_too_many_hits");
    public static final SoundEvent LINE_STATUS_LATE_HEADING_HOME = register("bot.line.status_late_heading_home");

    // Line-specific sound events (idle)
    public static final SoundEvent LINE_IDLE_ALL_QUIET = register("bot.line.idle_all_quiet");
    public static final SoundEvent LINE_IDLE_STILL_STANDING = register("bot.line.idle_still_standing");
    public static final SoundEvent LINE_IDLE_TAKING_IT_EASY = register("bot.line.idle_taking_it_easy");
    public static final SoundEvent LINE_IDLE_HERE_IF_NEEDED = register("bot.line.idle_here_if_needed");
    public static final SoundEvent LINE_IDLE_ENJOYING_CALM = register("bot.line.idle_enjoying_calm");

    // Line-specific sound events (skill: fishing)
    public static final SoundEvent LINE_SKILL_FISHING_WATCHING = register("bot.line.skill_fishing_watching");
    public static final SoundEvent LINE_SKILL_FISHING_BITING = register("bot.line.skill_fishing_biting");
    public static final SoundEvent LINE_SKILL_FISHING_GOOD_SPOT = register("bot.line.skill_fishing_good_spot");
    public static final SoundEvent LINE_SKILL_FISH_SESSION_DONE = register("bot.line.skill_fish_session_done");
    public static final SoundEvent LINE_SKILL_FISH_SUNSET = register("bot.line.skill_fish_sunset");

    // Line-specific sound events (skill: woodcut)
    public static final SoundEvent LINE_SKILL_WOODCUT_CAREFUL = register("bot.line.skill_woodcut_careful");
    public static final SoundEvent LINE_SKILL_WOODCUT_TIDYING = register("bot.line.skill_woodcut_tidying");
    public static final SoundEvent LINE_SKILL_WOODCUT_FEW_MORE = register("bot.line.skill_woodcut_few_more");

    // Line-specific sound events (skill: hangout)
    public static final SoundEvent LINE_SKILL_HANGOUT_FIRE = register("bot.line.skill_hangout_fire");
    public static final SoundEvent LINE_SKILL_HANGOUT_NICE_QUIET = register("bot.line.skill_hangout_nice_quiet");
    public static final SoundEvent LINE_SKILL_HANGOUT_BREATHER = register("bot.line.skill_hangout_breather");
    public static final SoundEvent LINE_SKILL_HANGOUT_FIRE_BIT = register("bot.line.skill_hangout_fire_bit");
    public static final SoundEvent LINE_SKILL_HANGOUT_BIT = register("bot.line.skill_hangout_bit");
    public static final SoundEvent LINE_SKILL_HANGOUT_SHORT_BREATHER = register("bot.line.skill_hangout_short_breather");

    // Line-specific sound events (skill: sleep)
    public static final SoundEvent LINE_SKILL_SLEEP_TRYING = register("bot.line.skill_sleep_trying");
    public static final SoundEvent LINE_SKILL_SLEEP_FINDING_BED = register("bot.line.skill_sleep_finding_bed");
    public static final SoundEvent LINE_SKILL_SLEEP_LONG_DAY = register("bot.line.skill_sleep_long_day");

    // Line-specific sound events (mode: follow)
    public static final SoundEvent LINE_MODE_FOLLOW_BEHIND = register("bot.line.mode_follow_behind");
    public static final SoundEvent LINE_MODE_FOLLOW_LEAD = register("bot.line.mode_follow_lead");
    public static final SoundEvent LINE_MODE_FOLLOW_KEEPING_UP = register("bot.line.mode_follow_keeping_up");

    // Line-specific sound events (mode: guard)
    public static final SoundEvent LINE_MODE_GUARD_CLEAR = register("bot.line.mode_guard_clear");
    public static final SoundEvent LINE_MODE_GUARD_NOTHING_PAST = register("bot.line.mode_guard_nothing_past");
    public static final SoundEvent LINE_MODE_GUARD_QUIET = register("bot.line.mode_guard_quiet");

    // Line-specific sound events (mode: patrol)
    public static final SoundEvent LINE_MODE_PATROL_ROUNDS = register("bot.line.mode_patrol_rounds");
    public static final SoundEvent LINE_MODE_PATROL_AREA = register("bot.line.mode_patrol_area");
    public static final SoundEvent LINE_MODE_PATROL_PERIMETER = register("bot.line.mode_patrol_perimeter");

    // Line-specific sound events (mode: return)
    public static final SoundEvent LINE_MODE_RETURN_HEADING = register("bot.line.mode_return_heading");
    public static final SoundEvent LINE_MODE_RETURN_ON_WAY = register("bot.line.mode_return_on_way");
    public static final SoundEvent LINE_MODE_RETURN_RETURNING = register("bot.line.mode_return_returning");

    // Line-specific sound events (mode: stay)
    public static final SoundEvent LINE_MODE_STAY_HOLDING = register("bot.line.mode_stay_holding");
    public static final SoundEvent LINE_MODE_STAY_RIGHT_HERE = register("bot.line.mode_stay_right_here");
    public static final SoundEvent LINE_MODE_STAY_STANDING_BY = register("bot.line.mode_stay_standing_by");

    // Line-specific sound events (warning)
    public static final SoundEvent LINE_WARNING_SUFFOCATING = register("bot.line.warning_suffocating");
    public static final SoundEvent LINE_WARNING_DROP_AHEAD = register("bot.line.warning_drop_ahead");
    public static final SoundEvent LINE_WARNING_STUCK = register("bot.line.warning_stuck");
    public static final SoundEvent LINE_WARNING_BANGED_UP = register("bot.line.warning_banged_up");
    public static final SoundEvent LINE_WARNING_NOT_FULL_STRENGTH = register("bot.line.warning_not_full_strength");
    public static final SoundEvent LINE_WARNING_PREFER_CHICKEN = register("bot.line.warning_prefer_chicken");

    // Line-specific sound events (context)
    public static final SoundEvent LINE_CONTEXT_FISH_EARLIER = register("bot.line.context_fish_earlier");
    public static final SoundEvent LINE_CONTEXT_SMELLS_FISH = register("bot.line.context_smells_fish");
    public static final SoundEvent LINE_CONTEXT_FISH_COOPERATING = register("bot.line.context_fish_cooperating");
    public static final SoundEvent LINE_CONTEXT_WARMING_EARLIER = register("bot.line.context_warming_earlier");
    public static final SoundEvent LINE_CONTEXT_BREATHER_SOMETIMES = register("bot.line.context_breather_sometimes");
    public static final SoundEvent LINE_CONTEXT_CAMPFIRE_WONDERS = register("bot.line.context_campfire_wonders");
    public static final SoundEvent LINE_CONTEXT_LISTENING = register("bot.line.context_listening");

    private BotDialogueSounds() {
    }

    /**
     * Register a sound event with the given path.
     * @param path The sound event path (e.g., "bot.greeting" or "bot.line.greeting_hey")
     * @return The registered SoundEvent
     */
    private static SoundEvent register(String path) {
        Identifier id = Identifier.of(AIPlayer.MOD_ID, path);
        SoundEvent event = SoundEvent.of(id);
        SOUND_EVENTS.put(path, event);
        return event;
    }

    /**
     * Get a sound event by its path.
     * @param path The sound event path
     * @return The SoundEvent, or null if not found
     */
    public static SoundEvent getByPath(String path) {
        return SOUND_EVENTS.get(path);
    }

    /**
     * Initialize and register all sound events with the game registry.
     * Call this from the mod initializer.
     */
    public static void registerAll() {
        for (Map.Entry<String, SoundEvent> entry : SOUND_EVENTS.entrySet()) {
            Registry.register(Registries.SOUND_EVENT, entry.getValue().id(), entry.getValue());
        }
        AIPlayer.LOGGER.info("Registered {} bot dialogue sound events", SOUND_EVENTS.size());
    }
}
