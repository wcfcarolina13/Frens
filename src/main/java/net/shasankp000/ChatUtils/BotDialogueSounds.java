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

    // === NEW CATEGORY SOUND EVENTS ===
    public static final SoundEvent BOT_COMBAT = register("bot.combat");
    public static final SoundEvent BOT_CONFIRM = register("bot.confirm");
    public static final SoundEvent BOT_CRAFT = register("bot.craft");
    public static final SoundEvent BOT_DEATH = register("bot.death");
    public static final SoundEvent BOT_DISCOVER = register("bot.discover");
    public static final SoundEvent BOT_EATING = register("bot.eating");
    public static final SoundEvent BOT_FARM = register("bot.farm");
    public static final SoundEvent BOT_FISH = register("bot.fish");
    public static final SoundEvent BOT_HAZARD = register("bot.hazard");
    public static final SoundEvent BOT_HUNGER = register("bot.hunger");
    public static final SoundEvent BOT_INVENTORY = register("bot.inventory");
    public static final SoundEvent BOT_MOVE = register("bot.move");
    public static final SoundEvent BOT_SHELTER = register("bot.shelter");
    public static final SoundEvent BOT_SLEEP = register("bot.sleep");
    public static final SoundEvent BOT_SMELT = register("bot.smelt");
    // New categories from chatterbox handoff
    public static final SoundEvent BOT_AMBIENT = register("bot.ambient");
    public static final SoundEvent BOT_DARK = register("bot.dark");
    public static final SoundEvent BOT_WILDLIFE = register("bot.wildlife");
    public static final SoundEvent BOT_LOST = register("bot.lost");
    public static final SoundEvent BOT_FOUND = register("bot.found");

    // Line-specific sound events (combat)
    public static final SoundEvent LINE_COMBAT_ENGAGING = register("bot.line.combat_engaging");
    public static final SoundEvent LINE_COMBAT_STANDING_DOWN = register("bot.line.combat_standing_down");
    public static final SoundEvent LINE_COMBAT_DEFEND_BOTS = register("bot.line.combat_defend_bots");
    public static final SoundEvent LINE_COMBAT_FOCUS_SELF = register("bot.line.combat_focus_self");
    public static final SoundEvent LINE_COMBAT_AGGRESSIVE = register("bot.line.combat_aggressive");
    public static final SoundEvent LINE_COMBAT_EVASIVE = register("bot.line.combat_evasive");

    // Line-specific sound events (confirm)
    public static final SoundEvent LINE_CONFIRM_ON_IT = register("bot.line.confirm_on_it");
    public static final SoundEvent LINE_CONFIRM_HOLD_OFF = register("bot.line.confirm_hold_off");
    public static final SoundEvent LINE_CONFIRM_ASK_YESNO = register("bot.line.confirm_ask_yesno");

    // Line-specific sound events (craft)
    public static final SoundEvent LINE_CRAFT_NEED_TABLE = register("bot.line.craft_need_table");
    public static final SoundEvent LINE_CRAFT_UNKNOWN = register("bot.line.craft_unknown");
    public static final SoundEvent LINE_CRAFT_CANT_PLACE = register("bot.line.craft_cant_place");

    // Line-specific sound events (death)
    public static final SoundEvent LINE_DEATH_RESUME_ASK = register("bot.line.death_resume_ask");

    // Line-specific sound events (discover)
    public static final SoundEvent LINE_DISCOVER_DIAMONDS = register("bot.line.discover_diamonds");
    public static final SoundEvent LINE_DISCOVER_ANCIENT_DEBRIS = register("bot.line.discover_ancient_debris");
    public static final SoundEvent LINE_DISCOVER_EMERALDS = register("bot.line.discover_emeralds");
    public static final SoundEvent LINE_DISCOVER_GOLD = register("bot.line.discover_gold");
    public static final SoundEvent LINE_DISCOVER_IRON = register("bot.line.discover_iron");
    public static final SoundEvent LINE_DISCOVER_COAL = register("bot.line.discover_coal");
    public static final SoundEvent LINE_DISCOVER_REDSTONE = register("bot.line.discover_redstone");
    public static final SoundEvent LINE_DISCOVER_LAPIS = register("bot.line.discover_lapis");
    public static final SoundEvent LINE_DISCOVER_QUARTZ = register("bot.line.discover_quartz");
    public static final SoundEvent LINE_DISCOVER_CHEST = register("bot.line.discover_chest");
    public static final SoundEvent LINE_DISCOVER_GEODE = register("bot.line.discover_geode");
    public static final SoundEvent LINE_DISCOVER_STRUCTURE = register("bot.line.discover_structure");

    // Line-specific sound events (eating)
    public static final SoundEvent LINE_EATING_NO_FOOD = register("bot.line.eating_no_food");
    public static final SoundEvent LINE_EATING_STILL_HUNGRY = register("bot.line.eating_still_hungry");
    public static final SoundEvent LINE_EATING_PROGRESS = register("bot.line.eating_progress");
    public static final SoundEvent LINE_EATING_DONE = register("bot.line.eating_done");

    // Line-specific sound events (farm)
    public static final SoundEvent LINE_FARM_NEED_SEEDS = register("bot.line.farm_need_seeds");
    public static final SoundEvent LINE_FARM_NEED_HOE = register("bot.line.farm_need_hoe");

    // Line-specific sound events (fish)
    public static final SoundEvent LINE_FISH_NO_WATER = register("bot.line.fish_no_water");

    // Line-specific sound events (hazard)
    public static final SoundEvent LINE_HAZARD_LAVA = register("bot.line.hazard_lava");
    public static final SoundEvent LINE_HAZARD_WATER = register("bot.line.hazard_water");
    public static final SoundEvent LINE_HAZARD_NO_TORCHES = register("bot.line.hazard_no_torches");

    // Line-specific sound events (hunger)
    public static final SoundEvent LINE_HUNGER_DYING = register("bot.line.hunger_dying");
    public static final SoundEvent LINE_HUNGER_STARVING = register("bot.line.hunger_starving");
    public static final SoundEvent LINE_HUNGER_WARNING = register("bot.line.hunger_warning");

    // Line-specific sound events (inventory)
    public static final SoundEvent LINE_INVENTORY_FULL = register("bot.line.inventory_full");
    public static final SoundEvent LINE_INVENTORY_DONT_HAVE = register("bot.line.inventory_dont_have");
    public static final SoundEvent LINE_INVENTORY_GIVE_ITEM = register("bot.line.inventory_give_item");

    // Line-specific sound events (move)
    public static final SoundEvent LINE_MOVE_CANT_REACH = register("bot.line.move_cant_reach");
    public static final SoundEvent LINE_MOVE_BLOCKED = register("bot.line.move_blocked");
    public static final SoundEvent LINE_MOVE_WALKING_TO_YOU = register("bot.line.move_walking_to_you");
    public static final SoundEvent LINE_MOVE_TARGET_LOST = register("bot.line.move_target_lost");
    public static final SoundEvent LINE_MOVE_BACK_TO_IDLE = register("bot.line.move_back_to_idle");

    // Line-specific sound events (shelter)
    public static final SoundEvent LINE_SHELTER_CANT_BUILD = register("bot.line.shelter_cant_build");

    // Line-specific sound events (sleep)
    public static final SoundEvent LINE_SLEEP_CANT_NOW = register("bot.line.sleep_cant_now");
    public static final SoundEvent LINE_SLEEP_NO_BED = register("bot.line.sleep_no_bed");
    public static final SoundEvent LINE_SLEEP_NO_SPOT = register("bot.line.sleep_no_spot");
    public static final SoundEvent LINE_SLEEP_BED_BLOCKED = register("bot.line.sleep_bed_blocked");

    // Line-specific sound events (smelt)
    public static final SoundEvent LINE_SMELT_NEED_FURNACE = register("bot.line.smelt_need_furnace");
    public static final SoundEvent LINE_SMELT_NOTHING = register("bot.line.smelt_nothing");

    // Line-specific sound events (ambient / cave)
    public static final SoundEvent LINE_AMBIENT_HEARD_SOMETHING = register("bot.line.ambient_heard_something");
    public static final SoundEvent LINE_AMBIENT_DID_YOU_HEAR = register("bot.line.ambient_did_you_hear");
    public static final SoundEvent LINE_AMBIENT_SOMETHING_MOVED = register("bot.line.ambient_something_moved");
    public static final SoundEvent LINE_AMBIENT_NOT_ALONE = register("bot.line.ambient_not_alone");
    public static final SoundEvent LINE_AMBIENT_SMELLS_TERRIBLE = register("bot.line.ambient_smells_terrible");
    public static final SoundEvent LINE_AMBIENT_DONT_LIKE_THIS = register("bot.line.ambient_dont_like_this");
    public static final SoundEvent LINE_AMBIENT_CREEPY = register("bot.line.ambient_creepy");
    public static final SoundEvent LINE_AMBIENT_CAVE_DEEP = register("bot.line.ambient_cave_deep");

    // Line-specific sound events (darkness)
    public static final SoundEvent LINE_DARK_CANT_SEE = register("bot.line.dark_cant_see");
    public static final SoundEvent LINE_DARK_WHERE_ARE_YOU = register("bot.line.dark_where_are_you");
    public static final SoundEvent LINE_DARK_NEED_LIGHT = register("bot.line.dark_need_light");
    public static final SoundEvent LINE_DARK_TOO_DARK = register("bot.line.dark_too_dark");
    public static final SoundEvent LINE_DARK_TORCH_PLEASE = register("bot.line.dark_torch_please");

    // Line-specific sound events (wildlife)
    public static final SoundEvent LINE_WILDLIFE_HEARD_BIRD = register("bot.line.wildlife_heard_bird");
    public static final SoundEvent LINE_WILDLIFE_SAW_COW = register("bot.line.wildlife_saw_cow");
    public static final SoundEvent LINE_WILDLIFE_PIG_NEARBY = register("bot.line.wildlife_pig_nearby");
    public static final SoundEvent LINE_WILDLIFE_HEARD_WOLF = register("bot.line.wildlife_heard_wolf");
    public static final SoundEvent LINE_WILDLIFE_SHEEP_AROUND = register("bot.line.wildlife_sheep_around");
    public static final SoundEvent LINE_WILDLIFE_CHICKEN = register("bot.line.wildlife_chicken");
    public static final SoundEvent LINE_WILDLIFE_NICE_DAY = register("bot.line.wildlife_nice_day");

    // Line-specific sound events (lost / shouts)
    public static final SoundEvent LINE_LOST_OVER_HERE = register("bot.line.lost_over_here");
    public static final SoundEvent LINE_LOST_CAN_ANYONE_HEAR = register("bot.line.lost_can_anyone_hear");
    public static final SoundEvent LINE_LOST_HELLO = register("bot.line.lost_hello");
    public static final SoundEvent LINE_LOST_HELP = register("bot.line.lost_help");
    public static final SoundEvent LINE_LOST_STUCK_HERE = register("bot.line.lost_stuck_here");
    public static final SoundEvent LINE_LOST_WHERE_IS_EVERYONE = register("bot.line.lost_where_is_everyone");

    // Line-specific sound events (found / rescued)
    public static final SoundEvent LINE_FOUND_FINALLY = register("bot.line.found_finally");
    public static final SoundEvent LINE_FOUND_THANK_GOODNESS = register("bot.line.found_thank_goodness");
    public static final SoundEvent LINE_FOUND_THOUGHT_ID_BE_STUCK = register("bot.line.found_thought_id_be_stuck");
    public static final SoundEvent LINE_FOUND_SO_GLAD = register("bot.line.found_so_glad");
    public static final SoundEvent LINE_FOUND_BEEN_CALLING = register("bot.line.found_been_calling");
    public static final SoundEvent LINE_FOUND_LETS_GET_OUT = register("bot.line.found_lets_get_out");

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
