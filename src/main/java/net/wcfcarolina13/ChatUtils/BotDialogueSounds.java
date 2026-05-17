package net.wcfcarolina13.ChatUtils;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.wcfcarolina13.Frens;

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

    // Line-specific sound events (weather)
    public static final SoundEvent LINE_WEATHER_RAIN = register("bot.line.weather_rain");
    public static final SoundEvent LINE_WEATHER_SNOW = register("bot.line.weather_snow");
    public static final SoundEvent LINE_WEATHER_THUNDER = register("bot.line.weather_thunder");
    public static final SoundEvent LINE_WEATHER_SUNNY = register("bot.line.weather_sunny");

    // Line-specific sound events (time)
    public static final SoundEvent LINE_TIME_SUNSET_SOON = register("bot.line.time_sunset_soon");

    // Nonverbal / foley
    public static final SoundEvent FX_HURT_GRUNT = register("bot.fx.hurt_grunt");

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

    // Line-specific sound events (wake-up after sleeping near player)
    public static final SoundEvent LINE_WAKE_SNORE_PIGLIN = register("bot.line.wake_snore_piglin");
    public static final SoundEvent LINE_WAKE_DREAM_NPC = register("bot.line.wake_dream_npc");
    public static final SoundEvent LINE_WAKE_GOOD_REST = register("bot.line.wake_good_rest");
    public static final SoundEvent LINE_WAKE_SEIZE_DAY = register("bot.line.wake_seize_day");

    // Line-specific sound events (cooking reaction — food taken from furnace/smoker/campfire)
    public static final SoundEvent LINE_COOK_SMELLS_GOOD = register("bot.line.cook_smells_good");
    public static final SoundEvent LINE_COOK_DINNER = register("bot.line.cook_dinner");
    public static final SoundEvent LINE_COOK_GETTING_HUNGRY = register("bot.line.cook_getting_hungry");
    public static final SoundEvent LINE_COOK_LIKE_HOME = register("bot.line.cook_like_home");
    public static final SoundEvent LINE_COOK_HOT_MEAL = register("bot.line.cook_hot_meal");
    public static final SoundEvent LINE_COOK_SAVE_SOME = register("bot.line.cook_save_some");
    public static final SoundEvent LINE_COOK_AMAZING = register("bot.line.cook_amazing");
    public static final SoundEvent LINE_COOK_COULD_EAT = register("bot.line.cook_could_eat");

    // Line-specific sound events (player eating rotten flesh)
    public static final SoundEvent LINE_REACT_ROTTEN_REALLY = register("bot.line.react_rotten_really");
    public static final SoundEvent LINE_REACT_ROTTEN_KNOW_THAT = register("bot.line.react_rotten_know_that");
    public static final SoundEvent LINE_REACT_ROTTEN_SMELL = register("bot.line.react_rotten_smell");
    public static final SoundEvent LINE_REACT_ROTTEN_BRAVE = register("bot.line.react_rotten_brave");

    // Line-specific sound events (player eating suspicious stew)
    public static final SoundEvent LINE_REACT_STEW_SUSPICIOUS = register("bot.line.react_stew_suspicious");
    public static final SoundEvent LINE_REACT_STEW_SURE = register("bot.line.react_stew_sure");
    public static final SoundEvent LINE_REACT_STEW_WOULDNT_TRUST = register("bot.line.react_stew_wouldnt_trust");
    public static final SoundEvent LINE_REACT_STEW_BOLD = register("bot.line.react_stew_bold");

    // Line-specific sound events (food giving: accept)
    public static final SoundEvent LINE_FOOD_ACCEPT_NEEDED = register("bot.line.food_accept_needed");
    public static final SoundEvent LINE_FOOD_ACCEPT_DONT_MIND = register("bot.line.food_accept_dont_mind");
    public static final SoundEvent LINE_FOOD_ACCEPT_APPRECIATED = register("bot.line.food_accept_appreciated");
    public static final SoundEvent LINE_FOOD_ACCEPT_TIMING = register("bot.line.food_accept_timing");

    // Line-specific sound events (food giving: refuse)
    public static final SoundEvent LINE_FOOD_REFUSE_GOOD = register("bot.line.food_refuse_good");
    public static final SoundEvent LINE_FOOD_REFUSE_LATER = register("bot.line.food_refuse_later");
    public static final SoundEvent LINE_FOOD_REFUSE_NOT_HUNGRY = register("bot.line.food_refuse_not_hungry");
    public static final SoundEvent LINE_FOOD_REFUSE_PASS = register("bot.line.food_refuse_pass");

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

    // Line-specific sound events (combat mode toggles)
    public static final SoundEvent LINE_COMBAT_ENGAGING = register("bot.line.combat_engaging");
    public static final SoundEvent LINE_COMBAT_STANDING_DOWN = register("bot.line.combat_standing_down");
    public static final SoundEvent LINE_COMBAT_DEFEND_BOTS = register("bot.line.combat_defend_bots");
    public static final SoundEvent LINE_COMBAT_FOCUS_SELF = register("bot.line.combat_focus_self");
    public static final SoundEvent LINE_COMBAT_AGGRESSIVE = register("bot.line.combat_aggressive");
    public static final SoundEvent LINE_COMBAT_EVASIVE = register("bot.line.combat_evasive");

    // Line-specific sound events (combat callouts - during fights)
    public static final SoundEvent LINE_COMBAT_THREAT_DETECTED = register("bot.line.combat_threat_detected");
    public static final SoundEvent LINE_COMBAT_ATTACKING = register("bot.line.combat_attacking");
    public static final SoundEvent LINE_COMBAT_KILL = register("bot.line.combat_kill");
    public static final SoundEvent LINE_COMBAT_KILL_GOT_IT = register("bot.line.combat_kill_got_it");
    public static final SoundEvent LINE_COMBAT_KILL_TARGET_DOWN = register("bot.line.combat_kill_target_down");
    public static final SoundEvent LINE_COMBAT_KILL_ONE_LESS = register("bot.line.combat_kill_one_less");

    // Creature-specific kill confirmations (combat)
    public static final SoundEvent LINE_COMBAT_KILL_CREEPER_1 = register("bot.line.combat_kill_creeper_1");
    public static final SoundEvent LINE_COMBAT_KILL_CREEPER_2 = register("bot.line.combat_kill_creeper_2");
    public static final SoundEvent LINE_COMBAT_KILL_SKELETON_1 = register("bot.line.combat_kill_skeleton_1");
    public static final SoundEvent LINE_COMBAT_KILL_SKELETON_2 = register("bot.line.combat_kill_skeleton_2");
    public static final SoundEvent LINE_COMBAT_KILL_ZOMBIE_1 = register("bot.line.combat_kill_zombie_1");
    public static final SoundEvent LINE_COMBAT_KILL_ZOMBIE_2 = register("bot.line.combat_kill_zombie_2");
    public static final SoundEvent LINE_COMBAT_KILL_SPIDER_1 = register("bot.line.combat_kill_spider_1");
    public static final SoundEvent LINE_COMBAT_KILL_SPIDER_2 = register("bot.line.combat_kill_spider_2");
    public static final SoundEvent LINE_COMBAT_KILL_ENDERMAN_1 = register("bot.line.combat_kill_enderman_1");
    public static final SoundEvent LINE_COMBAT_KILL_ENDERMAN_2 = register("bot.line.combat_kill_enderman_2");
    public static final SoundEvent LINE_COMBAT_KILL_WITCH_1 = register("bot.line.combat_kill_witch_1");
    public static final SoundEvent LINE_COMBAT_KILL_WITCH_2 = register("bot.line.combat_kill_witch_2");
    public static final SoundEvent LINE_COMBAT_KILL_SLIME_1 = register("bot.line.combat_kill_slime_1");
    public static final SoundEvent LINE_COMBAT_KILL_SLIME_2 = register("bot.line.combat_kill_slime_2");
    public static final SoundEvent LINE_COMBAT_KILL_PILLAGER_1 = register("bot.line.combat_kill_pillager_1");
    public static final SoundEvent LINE_COMBAT_KILL_PILLAGER_2 = register("bot.line.combat_kill_pillager_2");
    public static final SoundEvent LINE_COMBAT_KILL_VINDICATOR_1 = register("bot.line.combat_kill_vindicator_1");
    public static final SoundEvent LINE_COMBAT_KILL_VINDICATOR_2 = register("bot.line.combat_kill_vindicator_2");
    public static final SoundEvent LINE_COMBAT_KILL_EVOKER_1 = register("bot.line.combat_kill_evoker_1");
    public static final SoundEvent LINE_COMBAT_KILL_EVOKER_2 = register("bot.line.combat_kill_evoker_2");
    public static final SoundEvent LINE_COMBAT_KILL_MAGMA_CUBE_1 = register("bot.line.combat_kill_magma_cube_1");
    public static final SoundEvent LINE_COMBAT_KILL_MAGMA_CUBE_2 = register("bot.line.combat_kill_magma_cube_2");
    public static final SoundEvent LINE_COMBAT_KILL_RAVAGER_1 = register("bot.line.combat_kill_ravager_1");
    public static final SoundEvent LINE_COMBAT_KILL_RAVAGER_2 = register("bot.line.combat_kill_ravager_2");
    public static final SoundEvent LINE_COMBAT_KILL_VEX_1 = register("bot.line.combat_kill_vex_1");
    public static final SoundEvent LINE_COMBAT_KILL_VEX_2 = register("bot.line.combat_kill_vex_2");
    public static final SoundEvent LINE_COMBAT_CLEAR = register("bot.line.combat_clear");
    public static final SoundEvent LINE_COMBAT_PLAYER_HIT = register("bot.line.combat_player_hit");

    // Line-specific sound events (combat: ranged misses)
    public static final SoundEvent LINE_COMBAT_MISSED_1 = register("bot.line.combat_missed_1");
    public static final SoundEvent LINE_COMBAT_MISSED_2 = register("bot.line.combat_missed_2");
    public static final SoundEvent LINE_COMBAT_MISSED_3 = register("bot.line.combat_missed_3");
    public static final SoundEvent LINE_COMBAT_MISSED_4 = register("bot.line.combat_missed_4");

    // Line-specific sound events (confirm)
    public static final SoundEvent LINE_CONFIRM_ON_IT = register("bot.line.confirm_on_it");
    public static final SoundEvent LINE_CONFIRM_HOLD_OFF = register("bot.line.confirm_hold_off");
    public static final SoundEvent LINE_CONFIRM_ASK_YESNO = register("bot.line.confirm_ask_yesno");

    // Line-specific sound events (craft)
    public static final SoundEvent LINE_CRAFT_NEED_TABLE = register("bot.line.craft_need_table");
    public static final SoundEvent LINE_CRAFT_UNKNOWN = register("bot.line.craft_unknown");
    public static final SoundEvent LINE_CRAFT_CANT_PLACE = register("bot.line.craft_cant_place");
    public static final SoundEvent LINE_CRAFT_RECIPE_UNLOCKED_1 = register("bot.line.craft_recipe_unlocked_1");
    public static final SoundEvent LINE_CRAFT_RECIPE_UNLOCKED_2 = register("bot.line.craft_recipe_unlocked_2");
    public static final SoundEvent LINE_CRAFT_RECIPE_UNLOCKED_3 = register("bot.line.craft_recipe_unlocked_3");
    public static final SoundEvent LINE_CRAFT_RECIPE_UNLOCKED_4 = register("bot.line.craft_recipe_unlocked_4");
    public static final SoundEvent LINE_CRAFT_RECIPE_UNLOCKED_5 = register("bot.line.craft_recipe_unlocked_5");
    public static final SoundEvent LINE_CRAFT_RECIPE_UNLOCKED_6 = register("bot.line.craft_recipe_unlocked_6");

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
    public static final SoundEvent LINE_DISCOVER_MINESHAFT = register("bot.line.discover_mineshaft");
    public static final SoundEvent LINE_DISCOVER_SPAWNER = register("bot.line.discover_spawner");

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

    // Line-specific sound events (special environment - amethyst)
    public static final SoundEvent LINE_AMBIENT_AMETHYST_BEAUTIFUL = register("bot.line.ambient_amethyst_beautiful");
    public static final SoundEvent LINE_AMBIENT_AMETHYST_SPARKLY = register("bot.line.ambient_amethyst_sparkly");
    public static final SoundEvent LINE_AMBIENT_AMETHYST_GEODE = register("bot.line.ambient_amethyst_geode");

    // Line-specific sound events (special environment - bats)
    public static final SoundEvent LINE_AMBIENT_BAT_STARTLED = register("bot.line.ambient_bat_startled");
    public static final SoundEvent LINE_AMBIENT_BAT_WINGS = register("bot.line.ambient_bat_wings");
    public static final SoundEvent LINE_AMBIENT_BAT_CREEPY = register("bot.line.ambient_bat_creepy");

    // Line-specific sound events (special environment - dripstone)
    public static final SoundEvent LINE_AMBIENT_DRIPSTONE_CAREFUL = register("bot.line.ambient_dripstone_careful");
    public static final SoundEvent LINE_AMBIENT_DRIPSTONE_SHARP = register("bot.line.ambient_dripstone_sharp");
    public static final SoundEvent LINE_AMBIENT_DRIPSTONE_DRIPPING = register("bot.line.ambient_dripstone_dripping");

    // Line-specific sound events (special environment - deepslate)
    public static final SoundEvent LINE_AMBIENT_DEEPSLATE_COLD = register("bot.line.ambient_deepslate_cold");
    public static final SoundEvent LINE_AMBIENT_DEEPSLATE_DEEP = register("bot.line.ambient_deepslate_deep");
    public static final SoundEvent LINE_AMBIENT_DEEPSLATE_ANCIENT = register("bot.line.ambient_deepslate_ancient");

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

    // Line-specific sound events (dimension ambience: Nether)
    public static final SoundEvent LINE_NETHER_ENTER_1 = register("bot.line.nether_enter_1");
    public static final SoundEvent LINE_NETHER_ENTER_2 = register("bot.line.nether_enter_2");
    public static final SoundEvent LINE_NETHER_HOT = register("bot.line.nether_hot");
    public static final SoundEvent LINE_NETHER_LAVA = register("bot.line.nether_lava");
    public static final SoundEvent LINE_NETHER_GHASTS = register("bot.line.nether_ghasts");
    public static final SoundEvent LINE_NETHER_PIGLINS = register("bot.line.nether_piglins");
    public static final SoundEvent LINE_NETHER_SOUL_SAND = register("bot.line.nether_soul_sand");
    public static final SoundEvent LINE_NETHER_FORTRESS = register("bot.line.nether_fortress");
    public static final SoundEvent LINE_NETHER_BASTION = register("bot.line.nether_bastion");

    // Line-specific sound events (biome flavor: Nether)
    public static final SoundEvent LINE_NETHER_BIOME_CRIMSON_FOREST = register("bot.line.nether_biome_crimson_forest");
    public static final SoundEvent LINE_NETHER_BIOME_WARPED_FOREST = register("bot.line.nether_biome_warped_forest");
    public static final SoundEvent LINE_NETHER_BIOME_SOUL_SAND_VALLEY = register("bot.line.nether_biome_soul_sand_valley");
    public static final SoundEvent LINE_NETHER_BIOME_BASALT_DELTAS = register("bot.line.nether_biome_basalt_deltas");
    public static final SoundEvent LINE_NETHER_BIOME_WASTES = register("bot.line.nether_biome_wastes");

    // Line-specific sound events (dimension ambience: The End)
    public static final SoundEvent LINE_END_ENTER_1 = register("bot.line.end_enter_1");
    public static final SoundEvent LINE_END_ENTER_2 = register("bot.line.end_enter_2");
    public static final SoundEvent LINE_END_EERIE = register("bot.line.end_eerie");
    public static final SoundEvent LINE_END_VOID = register("bot.line.end_void");
    public static final SoundEvent LINE_END_ISLANDS = register("bot.line.end_islands");
    public static final SoundEvent LINE_END_CHORUS = register("bot.line.end_chorus");
    public static final SoundEvent LINE_END_ENDERMEN = register("bot.line.end_endermen");
    public static final SoundEvent LINE_END_KEEP_EYES_UP = register("bot.line.end_keep_eyes_up");
    public static final SoundEvent LINE_END_CITY = register("bot.line.end_city");
    public static final SoundEvent LINE_END_GATEWAY = register("bot.line.end_gateway");

    // Line-specific sound events (biome flavor: The End)
    public static final SoundEvent LINE_END_BIOME_MAIN_ISLAND = register("bot.line.end_biome_main_island");
    public static final SoundEvent LINE_END_BIOME_HIGHLANDS = register("bot.line.end_biome_highlands");
    public static final SoundEvent LINE_END_BIOME_MIDLANDS = register("bot.line.end_biome_midlands");
    public static final SoundEvent LINE_END_BIOME_BARRENS = register("bot.line.end_biome_barrens");
    public static final SoundEvent LINE_END_BIOME_SMALL_ISLANDS = register("bot.line.end_biome_small_islands");

    // Line-specific sound events (banter)
    public static final SoundEvent LINE_BANTER_CREEPER_KIDDING = register("bot.line.banter_creeper_kidding");
    public static final SoundEvent LINE_BANTER_IF_YELL_RUN = register("bot.line.banter_if_yell_run");
    public static final SoundEvent LINE_BANTER_GOT_YOUR_BACK = register("bot.line.banter_got_your_back");
    public static final SoundEvent LINE_BANTER_STAY_SHARP = register("bot.line.banter_stay_sharp");

    // Line-specific sound events (mount / lead handling)
    public static final SoundEvent LINE_MOUNT_HORSE_HURT = register("bot.line.mount_horse_hurt");
    public static final SoundEvent LINE_MOUNT_BANGED_UP = register("bot.line.mount_banged_up");
    public static final SoundEvent LINE_MOUNT_MY_HORSE_HURT = register("bot.line.mount_my_horse_hurt");
    public static final SoundEvent LINE_MOUNT_NO_APPLES = register("bot.line.mount_no_apples");
    public static final SoundEvent LINE_MOUNT_OUT_OF_APPLES = register("bot.line.mount_out_of_apples");
    public static final SoundEvent LINE_MOUNT_NO_APPLES_ON_ME = register("bot.line.mount_no_apples_on_me");
    public static final SoundEvent LINE_MOUNT_NO_SUITABLE_FOOD = register("bot.line.mount_no_suitable_food");
    public static final SoundEvent LINE_MOUNT_CANT_FIND_FOOD = register("bot.line.mount_cant_find_food");
    public static final SoundEvent LINE_MOUNT_NO_FEED = register("bot.line.mount_no_feed");
    public static final SoundEvent LINE_MOUNT_NO_LEAD_SECURE = register("bot.line.mount_no_lead_secure");
    public static final SoundEvent LINE_MOUNT_MISSING_LEAD = register("bot.line.mount_missing_lead");
    public static final SoundEvent LINE_MOUNT_CANT_SECURE = register("bot.line.mount_cant_secure");
    public static final SoundEvent LINE_MOUNT_CANT_GRAB_LEAD = register("bot.line.mount_cant_grab_lead");
    public static final SoundEvent LINE_MOUNT_COULDNT_GET_LEAD = register("bot.line.mount_couldnt_get_lead");
    public static final SoundEvent LINE_MOUNT_LEAD_NOT_ACCESSIBLE = register("bot.line.mount_lead_not_accessible");
    public static final SoundEvent LINE_MOUNT_NO_FENCE_KEEP_LEAD = register("bot.line.mount_no_fence_keep_lead");
    public static final SoundEvent LINE_MOUNT_NO_FENCE_NEARBY = register("bot.line.mount_no_fence_nearby");
    public static final SoundEvent LINE_MOUNT_HOLD_LEAD_UNTIL_TIE = register("bot.line.mount_hold_lead_until_tie");
    public static final SoundEvent LINE_MOUNT_LOST_TRACK_HOLDING = register("bot.line.mount_lost_track_holding");
    public static final SoundEvent LINE_MOUNT_LOST_HORSE_LEAD = register("bot.line.mount_lost_horse_lead");
    public static final SoundEvent LINE_MOUNT_CANT_FIND_HORSE = register("bot.line.mount_cant_find_horse");
    public static final SoundEvent LINE_MOUNT_HORSE_GONE = register("bot.line.mount_horse_gone");
    public static final SoundEvent LINE_MOUNT_MOUNT_GONE = register("bot.line.mount_mount_gone");
    public static final SoundEvent LINE_MOUNT_LOST_LEADING = register("bot.line.mount_lost_leading");
    public static final SoundEvent LINE_MOUNT_LEAD_SNAPPED_DROP = register("bot.line.mount_lead_snapped_drop");
    public static final SoundEvent LINE_MOUNT_LEAD_BROKE_FALL = register("bot.line.mount_lead_broke_fall");
    public static final SoundEvent LINE_MOUNT_LEAD_SNAPPED_ON_DROP = register("bot.line.mount_lead_snapped_on_drop");
    public static final SoundEvent LINE_MOUNT_NO_LEAD_REATTACH = register("bot.line.mount_no_lead_reattach");
    public static final SoundEvent LINE_MOUNT_OUT_OF_LEADS = register("bot.line.mount_out_of_leads");
    public static final SoundEvent LINE_MOUNT_NO_SPARE_LEADS = register("bot.line.mount_no_spare_leads");

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
// ============ JANUARY 2026 BATCH 3 (MANIFEST-GENERATED) ============
// Generated from tools/audio/batch3_phase1_manifest.tsv + tools/audio/batch3_topic_manifest.tsv
    public static final SoundEvent LINE_AMBIENT_BAD_FEELING = register("bot.line.ambient_bad_feeling");
    public static final SoundEvent LINE_AMBIENT_BLAME_TERRAIN = register("bot.line.ambient_blame_terrain");
    public static final SoundEvent LINE_AMBIENT_MY_JOB = register("bot.line.ambient_my_job");
    public static final SoundEvent LINE_AMBIENT_THINKING = register("bot.line.ambient_thinking");
    public static final SoundEvent LINE_ANIMAL_QUALITY = register("bot.line.animal_quality");
    public static final SoundEvent LINE_ANIMAL_WELL_BEHAVED = register("bot.line.animal_well_behaved");
    public static final SoundEvent LINE_BOAT_BEAUTIFUL_DAY = register("bot.line.boat_beautiful_day");
    public static final SoundEvent LINE_BOAT_DEEP_WATER = register("bot.line.boat_deep_water");
    public static final SoundEvent LINE_BOAT_DOLPHIN_ESCORT = register("bot.line.boat_dolphin_escort");
    public static final SoundEvent LINE_BOAT_FISH_SIZE = register("bot.line.boat_fish_size");
    public static final SoundEvent LINE_BOAT_GOOD_FISHING = register("bot.line.boat_good_fishing");
    public static final SoundEvent LINE_BOAT_KNOW_SWIM = register("bot.line.boat_know_swim");
    public static final SoundEvent LINE_BOAT_KRAKEN = register("bot.line.boat_kraken");
    public static final SoundEvent LINE_BOAT_SHIPWRECK_SPEEDRUN = register("bot.line.boat_shipwreck_speedrun");
    public static final SoundEvent LINE_COMBAT_MULTI_EXCESSIVE = register("bot.line.combat_multi_excessive");
    public static final SoundEvent LINE_COMBAT_MULTI_NOT_FAIR = register("bot.line.combat_multi_not_fair");
    public static final SoundEvent LINE_COMBAT_MULTI_RELAX = register("bot.line.combat_multi_relax");
    public static final SoundEvent LINE_CREEPY_BAD_IDEAS_MATURE = register("bot.line.creepy_bad_ideas_mature");
    public static final SoundEvent LINE_CREEPY_COMPLAINT_REALITY = register("bot.line.creepy_complaint_reality");
    public static final SoundEvent LINE_CREEPY_HEAD_SWIVEL = register("bot.line.creepy_head_swivel");
    public static final SoundEvent LINE_FF_DEALT_DIDNT_MEAN = register("bot.line.ff_dealt_didnt_mean");
    public static final SoundEvent LINE_FF_DEALT_PANICKED = register("bot.line.ff_dealt_panicked");
    public static final SoundEvent LINE_FF_RECEIVED_ON_YOUR_TEAM = register("bot.line.ff_received_on_your_team");
    public static final SoundEvent LINE_FF_RECEIVED_OW_THAT_WAS_YOU = register("bot.line.ff_received_ow_that_was_you");
    public static final SoundEvent LINE_FREEFALL_AAAHAHA = register("bot.line.freefall_aaahaha");
    public static final SoundEvent LINE_FREEFALL_EXHILARATING = register("bot.line.freefall_exhilarating");
    public static final SoundEvent LINE_FREEFALL_FALLING_STYLE = register("bot.line.freefall_falling_style");
    public static final SoundEvent LINE_FREEFALL_IM_A_BIRD = register("bot.line.freefall_im_a_bird");
    public static final SoundEvent LINE_FREEFALL_INVENTORY = register("bot.line.freefall_inventory");
    public static final SoundEvent LINE_FREEFALL_REGRET = register("bot.line.freefall_regret");
    public static final SoundEvent LINE_FREEFALL_WOOHOO = register("bot.line.freefall_woohoo");
    public static final SoundEvent LINE_FREEFALL_YOLO = register("bot.line.freefall_yolo");
    public static final SoundEvent LINE_MEME_CHICKEN_ILLEGAL = register("bot.line.meme_chicken_illegal");
    public static final SoundEvent LINE_MEME_CHICKEN_JOCKEY = register("bot.line.meme_chicken_jockey");
    public static final SoundEvent LINE_MEME_CHICKEN_NOPE = register("bot.line.meme_chicken_nope");
    public static final SoundEvent LINE_MEME_CREEPER_AW_MAN = register("bot.line.meme_creeper_aw_man");
    public static final SoundEvent LINE_MEME_CREEPER_BACK_UP = register("bot.line.meme_creeper_back_up");
    public static final SoundEvent LINE_MEME_CREEPER_HATE_SOUND = register("bot.line.meme_creeper_hate_sound");
    public static final SoundEvent LINE_MEME_HEROBRINE_LEAVING = register("bot.line.meme_herobrine_leaving");
    public static final SoundEvent LINE_MEME_HEROBRINE_SAW_NOTHING = register("bot.line.meme_herobrine_saw_nothing");
    public static final SoundEvent LINE_MEME_I_AM_STEVE = register("bot.line.meme_i_am_steve");
    public static final SoundEvent LINE_MEME_STEVE_ADJACENT = register("bot.line.meme_steve_adjacent");
    public static final SoundEvent LINE_MEME_TECHNOBLADE = register("bot.line.meme_technoblade");
    public static final SoundEvent LINE_META_HUMAN_LAUGH = register("bot.line.meta_human_laugh");
    public static final SoundEvent LINE_META_NOT_ROBOT = register("bot.line.meta_not_robot");
    public static final SoundEvent LINE_META_STOP_LOOKING = register("bot.line.meta_stop_looking");
    public static final SoundEvent LINE_POST_COMBAT_ADEQUATE = register("bot.line.post_combat_adequate");
    public static final SoundEvent LINE_POST_COMBAT_MULTI_NEED_MINUTE = register("bot.line.post_combat_multi_need_minute");
    public static final SoundEvent LINE_POST_COMBAT_MULTI_NOT_EASY = register("bot.line.post_combat_multi_not_easy");
    public static final SoundEvent LINE_POST_COMBAT_SINGLE_EASY = register("bot.line.post_combat_single_easy");
    public static final SoundEvent LINE_POST_COMBAT_SINGLE_FEEL_BAD = register("bot.line.post_combat_single_feel_bad");
    public static final SoundEvent LINE_POST_COMBAT_SINGLE_INCONVENIENCE = register("bot.line.post_combat_single_inconvenience");
    public static final SoundEvent LINE_POST_COMBAT_SINGLE_MUST_HURT = register("bot.line.post_combat_single_must_hurt");
    public static final SoundEvent LINE_POST_COMBAT_STILL_ALIVE = register("bot.line.post_combat_still_alive");
    public static final SoundEvent LINE_POST_EXPLOSION_BONES = register("bot.line.post_explosion_bones");
    public static final SoundEvent LINE_POST_EXPLOSION_LESS_BOOM = register("bot.line.post_explosion_less_boom");
    public static final SoundEvent LINE_PRECIPICE_BACK_UP = register("bot.line.precipice_back_up");
    public static final SoundEvent LINE_PRECIPICE_BIG_DROP = register("bot.line.precipice_big_drop");
    public static final SoundEvent LINE_PRECIPICE_GONNA_JUMP = register("bot.line.precipice_gonna_jump");
    public static final SoundEvent LINE_PRECIPICE_NOPE = register("bot.line.precipice_nope");
    public static final SoundEvent LINE_PRECIPICE_NOT_FAN_GRAVITY = register("bot.line.precipice_not_fan_gravity");
    public static final SoundEvent LINE_PRECIPICE_YOU_FIRST = register("bot.line.precipice_you_first");
    public static final SoundEvent LINE_SCARY_HATE_SOUND = register("bot.line.scary_hate_sound");
    public static final SoundEvent LINE_SCARY_NOT_ACKNOWLEDGING = register("bot.line.scary_not_acknowledging");
    public static final SoundEvent LINE_SHELTER_NOT_PRETTY = register("bot.line.shelter_not_pretty");
    public static final SoundEvent LINE_SHELTER_ROOF_LUXURY = register("bot.line.shelter_roof_luxury");
    public static final SoundEvent LINE_SHELTER_SOME_PROBLEMS = register("bot.line.shelter_some_problems");
    public static final SoundEvent LINE_VILLAGER_ALL_YOU_SAY = register("bot.line.villager_all_you_say");
    public static final SoundEvent LINE_VILLAGER_CANT_TELL_APART = register("bot.line.villager_cant_tell_apart");
    public static final SoundEvent LINE_VILLAGER_COMMITTED_ONE_LINE = register("bot.line.villager_committed_one_line");
    public static final SoundEvent LINE_VILLAGER_FEW_WORDS = register("bot.line.villager_few_words");
    public static final SoundEvent LINE_VILLAGER_FOREIGN_TONGUE = register("bot.line.villager_foreign_tongue");
    public static final SoundEvent LINE_VILLAGER_NEGOTIATE = register("bot.line.villager_negotiate");
    public static final SoundEvent LINE_VILLAGER_NO_IDEA = register("bot.line.villager_no_idea");
    public static final SoundEvent LINE_VILLAGER_NOD_UNDERSTAND = register("bot.line.villager_nod_understand");
    public static final SoundEvent LINE_VILLAGER_RUDE = register("bot.line.villager_rude");
    public static final SoundEvent LINE_VISTA_AMAZING_VIEW = register("bot.line.vista_amazing_view");
    public static final SoundEvent LINE_VISTA_BEAUTIFUL = register("bot.line.vista_beautiful");
    public static final SoundEvent LINE_VISTA_BUILT_BASE_HERE = register("bot.line.vista_built_base_here");
    public static final SoundEvent LINE_VISTA_COULD_LIVE_HERE = register("bot.line.vista_could_live_here");
    public static final SoundEvent LINE_VISTA_GORGEOUS = register("bot.line.vista_gorgeous");
    public static final SoundEvent LINE_VISTA_WORTH_WALK = register("bot.line.vista_worth_walk");
    public static final SoundEvent LINE_VISTA_WOULD_YOU_LOOK = register("bot.line.vista_would_you_look");
    public static final SoundEvent LINE_VISTA_WOW = register("bot.line.vista_wow");
    public static final SoundEvent LINE_WOLF_GUARD_DUTY = register("bot.line.wolf_guard_duty");
    public static final SoundEvent LINE_WOLF_LEAVE_ALONE = register("bot.line.wolf_leave_alone");
    public static final SoundEvent LINE_WOLF_MENACE = register("bot.line.wolf_menace");
    public static final SoundEvent LINE_TOPIC_DEEP_DARK_FIRST = register("bot.line.topic_deep_dark_first");
    public static final SoundEvent LINE_TOPIC_DEEP_DARK_ASK_1 = register("bot.line.topic_deep_dark_ask_1");
    public static final SoundEvent LINE_TOPIC_DEEP_DARK_ASK_2 = register("bot.line.topic_deep_dark_ask_2");
    public static final SoundEvent LINE_TOPIC_DEEP_DARK_ASK_3 = register("bot.line.topic_deep_dark_ask_3");
    public static final SoundEvent LINE_TOPIC_DEEP_DARK_MEMORY = register("bot.line.topic_deep_dark_memory");
    public static final SoundEvent LINE_TOPIC_MUSHROOM_FIRST = register("bot.line.topic_mushroom_first");
    public static final SoundEvent LINE_TOPIC_MUSHROOM_ASK_1 = register("bot.line.topic_mushroom_ask_1");
    public static final SoundEvent LINE_TOPIC_MUSHROOM_ASK_2 = register("bot.line.topic_mushroom_ask_2");
    public static final SoundEvent LINE_TOPIC_MUSHROOM_MEMORY = register("bot.line.topic_mushroom_memory");
    public static final SoundEvent LINE_TOPIC_JUNGLE_FIRST = register("bot.line.topic_jungle_first");
    public static final SoundEvent LINE_TOPIC_JUNGLE_ASK_1 = register("bot.line.topic_jungle_ask_1");
    public static final SoundEvent LINE_TOPIC_JUNGLE_ASK_2 = register("bot.line.topic_jungle_ask_2");
    public static final SoundEvent LINE_TOPIC_JUNGLE_MEMORY = register("bot.line.topic_jungle_memory");
    public static final SoundEvent LINE_TOPIC_DESERT_FIRST = register("bot.line.topic_desert_first");
    public static final SoundEvent LINE_TOPIC_DESERT_ASK_1 = register("bot.line.topic_desert_ask_1");
    public static final SoundEvent LINE_TOPIC_DESERT_ASK_2 = register("bot.line.topic_desert_ask_2");
    public static final SoundEvent LINE_TOPIC_DESERT_MEMORY = register("bot.line.topic_desert_memory");
    public static final SoundEvent LINE_TOPIC_SNOW_FIRST = register("bot.line.topic_snow_first");
    public static final SoundEvent LINE_TOPIC_SNOW_ASK_1 = register("bot.line.topic_snow_ask_1");
    public static final SoundEvent LINE_TOPIC_SNOW_ASK_2 = register("bot.line.topic_snow_ask_2");
    public static final SoundEvent LINE_TOPIC_SNOW_MEMORY = register("bot.line.topic_snow_memory");
    public static final SoundEvent LINE_TOPIC_VILLAGE_FIRST = register("bot.line.topic_village_first");
    public static final SoundEvent LINE_TOPIC_VILLAGE_ASK_1 = register("bot.line.topic_village_ask_1");
    public static final SoundEvent LINE_TOPIC_VILLAGE_ASK_2 = register("bot.line.topic_village_ask_2");
    public static final SoundEvent LINE_TOPIC_VILLAGE_MEMORY = register("bot.line.topic_village_memory");
    public static final SoundEvent LINE_TOPIC_LIBRARY_FIRST = register("bot.line.topic_library_first");
    public static final SoundEvent LINE_TOPIC_LIBRARY_ASK_1 = register("bot.line.topic_library_ask_1");
    public static final SoundEvent LINE_TOPIC_LIBRARY_ASK_2 = register("bot.line.topic_library_ask_2");
    public static final SoundEvent LINE_TOPIC_LIBRARY_ASK_3 = register("bot.line.topic_library_ask_3");
    public static final SoundEvent LINE_TOPIC_LIBRARY_MEMORY = register("bot.line.topic_library_memory");
    public static final SoundEvent LINE_TOPIC_ENCHANTING_FIRST = register("bot.line.topic_enchanting_first");
    public static final SoundEvent LINE_TOPIC_ENCHANTING_ASK_1 = register("bot.line.topic_enchanting_ask_1");
    public static final SoundEvent LINE_TOPIC_ENCHANTING_ASK_2 = register("bot.line.topic_enchanting_ask_2");
    public static final SoundEvent LINE_TOPIC_ENCHANTING_ASK_3 = register("bot.line.topic_enchanting_ask_3");
    public static final SoundEvent LINE_TOPIC_ENCHANTING_MEMORY = register("bot.line.topic_enchanting_memory");
    public static final SoundEvent LINE_TOPIC_RUINED_PORTAL_FIRST = register("bot.line.topic_ruined_portal_first");
    public static final SoundEvent LINE_TOPIC_RUINED_PORTAL_ASK_1 = register("bot.line.topic_ruined_portal_ask_1");
    public static final SoundEvent LINE_TOPIC_RUINED_PORTAL_ASK_2 = register("bot.line.topic_ruined_portal_ask_2");
    public static final SoundEvent LINE_TOPIC_RUINED_PORTAL_MEMORY = register("bot.line.topic_ruined_portal_memory");
    public static final SoundEvent LINE_TOPIC_NETHER_FORTRESS_FIRST = register("bot.line.topic_nether_fortress_first");
    public static final SoundEvent LINE_TOPIC_NETHER_FORTRESS_ASK_1 = register("bot.line.topic_nether_fortress_ask_1");
    public static final SoundEvent LINE_TOPIC_NETHER_FORTRESS_ASK_2 = register("bot.line.topic_nether_fortress_ask_2");
    public static final SoundEvent LINE_TOPIC_NETHER_FORTRESS_MEMORY = register("bot.line.topic_nether_fortress_memory");
    public static final SoundEvent LINE_TOPIC_BASTION_FIRST = register("bot.line.topic_bastion_first");
    public static final SoundEvent LINE_TOPIC_BASTION_ASK_1 = register("bot.line.topic_bastion_ask_1");
    public static final SoundEvent LINE_TOPIC_BASTION_ASK_2 = register("bot.line.topic_bastion_ask_2");
    public static final SoundEvent LINE_TOPIC_BASTION_MEMORY = register("bot.line.topic_bastion_memory");
    public static final SoundEvent LINE_TOPIC_STRONGHOLD_FIRST = register("bot.line.topic_stronghold_first");
    public static final SoundEvent LINE_TOPIC_STRONGHOLD_ASK_1 = register("bot.line.topic_stronghold_ask_1");
    public static final SoundEvent LINE_TOPIC_STRONGHOLD_ASK_2 = register("bot.line.topic_stronghold_ask_2");
    public static final SoundEvent LINE_TOPIC_STRONGHOLD_MEMORY = register("bot.line.topic_stronghold_memory");
    public static final SoundEvent LINE_TOPIC_ANCIENT_CITY_FIRST = register("bot.line.topic_ancient_city_first");
    public static final SoundEvent LINE_TOPIC_ANCIENT_CITY_ASK_1 = register("bot.line.topic_ancient_city_ask_1");
    public static final SoundEvent LINE_TOPIC_ANCIENT_CITY_ASK_2 = register("bot.line.topic_ancient_city_ask_2");
    public static final SoundEvent LINE_TOPIC_ANCIENT_CITY_MEMORY = register("bot.line.topic_ancient_city_memory");
    public static final SoundEvent LINE_TOPIC_TRIAL_CHAMBERS_FIRST = register("bot.line.topic_trial_chambers_first");
    public static final SoundEvent LINE_TOPIC_TRIAL_CHAMBERS_ASK_1 = register("bot.line.topic_trial_chambers_ask_1");
    public static final SoundEvent LINE_TOPIC_TRIAL_CHAMBERS_ASK_2 = register("bot.line.topic_trial_chambers_ask_2");
    public static final SoundEvent LINE_TOPIC_TRIAL_CHAMBERS_MEMORY = register("bot.line.topic_trial_chambers_memory");
    public static final SoundEvent LINE_TOPIC_NETHER_FIRST = register("bot.line.topic_nether_first");
    public static final SoundEvent LINE_TOPIC_NETHER_ASK_1 = register("bot.line.topic_nether_ask_1");
    public static final SoundEvent LINE_TOPIC_NETHER_ASK_2 = register("bot.line.topic_nether_ask_2");
    public static final SoundEvent LINE_TOPIC_NETHER_MEMORY = register("bot.line.topic_nether_memory");
    public static final SoundEvent LINE_TOPIC_END_FIRST = register("bot.line.topic_end_first");
    public static final SoundEvent LINE_TOPIC_END_ASK_1 = register("bot.line.topic_end_ask_1");
    public static final SoundEvent LINE_TOPIC_END_ASK_2 = register("bot.line.topic_end_ask_2");
    public static final SoundEvent LINE_TOPIC_END_MEMORY = register("bot.line.topic_end_memory");
    public static final SoundEvent LINE_TOPIC_TRADER_FIRST_1 = register("bot.line.topic_trader_first_1");
    public static final SoundEvent LINE_TOPIC_TRADER_FIRST_2 = register("bot.line.topic_trader_first_2");
    public static final SoundEvent LINE_TOPIC_TRADER_ASK_1 = register("bot.line.topic_trader_ask_1");
    public static final SoundEvent LINE_TOPIC_TRADER_ASK_2 = register("bot.line.topic_trader_ask_2");
    public static final SoundEvent LINE_TOPIC_TRADER_ASK_3 = register("bot.line.topic_trader_ask_3");
    public static final SoundEvent LINE_TOPIC_TRADER_MEMORY_1 = register("bot.line.topic_trader_memory_1");
    public static final SoundEvent LINE_TOPIC_TRADER_MEMORY_2 = register("bot.line.topic_trader_memory_2");
    public static final SoundEvent LINE_TOPIC_LLAMA_FIRST = register("bot.line.topic_llama_first");
    public static final SoundEvent LINE_TOPIC_LLAMA_ASK_1 = register("bot.line.topic_llama_ask_1");
    public static final SoundEvent LINE_TOPIC_LLAMA_ASK_2 = register("bot.line.topic_llama_ask_2");
    public static final SoundEvent LINE_TOPIC_LLAMA_ASK_3 = register("bot.line.topic_llama_ask_3");
    public static final SoundEvent LINE_TOPIC_LLAMA_MEMORY = register("bot.line.topic_llama_memory");
    public static final SoundEvent LINE_TOPIC_HORSE_FIRST_1 = register("bot.line.topic_horse_first_1");
    public static final SoundEvent LINE_TOPIC_HORSE_FIRST_2 = register("bot.line.topic_horse_first_2");
    public static final SoundEvent LINE_TOPIC_HORSE_ASK_1 = register("bot.line.topic_horse_ask_1");
    public static final SoundEvent LINE_TOPIC_HORSE_ASK_2 = register("bot.line.topic_horse_ask_2");
    public static final SoundEvent LINE_TOPIC_HORSE_MEMORY = register("bot.line.topic_horse_memory");
    public static final SoundEvent LINE_TOPIC_DONKEY_FIRST = register("bot.line.topic_donkey_first");
    public static final SoundEvent LINE_TOPIC_DONKEY_ASK_1 = register("bot.line.topic_donkey_ask_1");
    public static final SoundEvent LINE_TOPIC_DONKEY_MEMORY = register("bot.line.topic_donkey_memory");
    public static final SoundEvent LINE_TOPIC_CAMEL_FIRST_1 = register("bot.line.topic_camel_first_1");
    public static final SoundEvent LINE_TOPIC_CAMEL_FIRST_2 = register("bot.line.topic_camel_first_2");
    public static final SoundEvent LINE_TOPIC_CAMEL_ASK_1 = register("bot.line.topic_camel_ask_1");
    public static final SoundEvent LINE_TOPIC_CAMEL_ASK_2 = register("bot.line.topic_camel_ask_2");
    public static final SoundEvent LINE_TOPIC_CAMEL_MEMORY = register("bot.line.topic_camel_memory");
    public static final SoundEvent LINE_TOPIC_STRIDER_FIRST_1 = register("bot.line.topic_strider_first_1");
    public static final SoundEvent LINE_TOPIC_STRIDER_FIRST_2 = register("bot.line.topic_strider_first_2");
    public static final SoundEvent LINE_TOPIC_STRIDER_ASK_1 = register("bot.line.topic_strider_ask_1");
    public static final SoundEvent LINE_TOPIC_STRIDER_ASK_2 = register("bot.line.topic_strider_ask_2");
    public static final SoundEvent LINE_TOPIC_STRIDER_MEMORY = register("bot.line.topic_strider_memory");
    public static final SoundEvent LINE_TOPIC_MINECART_FIRST = register("bot.line.topic_minecart_first");
    public static final SoundEvent LINE_TOPIC_MINECART_ASK_1 = register("bot.line.topic_minecart_ask_1");
    public static final SoundEvent LINE_TOPIC_MINECART_ASK_2 = register("bot.line.topic_minecart_ask_2");
    public static final SoundEvent LINE_TOPIC_MINECART_MEMORY = register("bot.line.topic_minecart_memory");
    public static final SoundEvent LINE_TOPIC_ELYTRA_FIRST = register("bot.line.topic_elytra_first");
    public static final SoundEvent LINE_TOPIC_ELYTRA_ASK_1 = register("bot.line.topic_elytra_ask_1");
    public static final SoundEvent LINE_TOPIC_ELYTRA_ASK_2 = register("bot.line.topic_elytra_ask_2");
    public static final SoundEvent LINE_TOPIC_ELYTRA_MEMORY = register("bot.line.topic_elytra_memory");

    // Cross-dimension follow dialogue
    public static final SoundEvent LINE_FOLLOW_DIM_END = register("bot.line.follow_dim_end");
    public static final SoundEvent LINE_FOLLOW_DIM_END_2 = register("bot.line.follow_dim_end_2");
    public static final SoundEvent LINE_FOLLOW_DIM_NETHER = register("bot.line.follow_dim_nether");
    public static final SoundEvent LINE_FOLLOW_DIM_NETHER_2 = register("bot.line.follow_dim_nether_2");
    public static final SoundEvent LINE_FOLLOW_DIM_OVERWORLD = register("bot.line.follow_dim_overworld");
    public static final SoundEvent LINE_FOLLOW_DIM_OVERWORLD_2 = register("bot.line.follow_dim_overworld_2");

    // Portal proximity dialogue (Overworld)
    public static final SoundEvent LINE_PORTAL_NETHER_OVERWORLD_1 = register("bot.line.portal_nether_overworld_1");
    public static final SoundEvent LINE_PORTAL_NETHER_OVERWORLD_2 = register("bot.line.portal_nether_overworld_2");
    public static final SoundEvent LINE_PORTAL_END_OVERWORLD_1 = register("bot.line.portal_end_overworld_1");
    public static final SoundEvent LINE_PORTAL_END_OVERWORLD_2 = register("bot.line.portal_end_overworld_2");

    // Player care - hurt
    public static final SoundEvent LINE_CARE_PLAYER_HURT_1 = register("bot.line.care_player_hurt_1");
    public static final SoundEvent LINE_CARE_PLAYER_HURT_2 = register("bot.line.care_player_hurt_2");
    public static final SoundEvent LINE_CARE_PLAYER_HURT_3 = register("bot.line.care_player_hurt_3");
    // Player care - hungry
    public static final SoundEvent LINE_CARE_PLAYER_HUNGRY_1 = register("bot.line.care_player_hungry_1");
    public static final SoundEvent LINE_CARE_PLAYER_HUNGRY_2 = register("bot.line.care_player_hungry_2");
    public static final SoundEvent LINE_CARE_PLAYER_HUNGRY_3 = register("bot.line.care_player_hungry_3");

    // Weird portal lines (wrong dimension)
    public static final SoundEvent LINE_WEIRD_PORTAL_END_IN_NETHER_1 = register("bot.line.weird_portal_end_in_nether_1");
    public static final SoundEvent LINE_WEIRD_PORTAL_END_IN_NETHER_2 = register("bot.line.weird_portal_end_in_nether_2");
    public static final SoundEvent LINE_WEIRD_PORTAL_NETHER_IN_END_1 = register("bot.line.weird_portal_nether_in_end_1");
    public static final SoundEvent LINE_WEIRD_PORTAL_NETHER_IN_END_2 = register("bot.line.weird_portal_nether_in_end_2");

    // === APRIL 2026 BACKLOG ===

    // Dig-straight-down warning
    public static final SoundEvent LINE_DIG_DOWN_WARNING = register("bot.line.dig_down_warning");

    // First tree punch (3 variants)
    public static final SoundEvent LINE_TREE_PUNCH_TIME = register("bot.line.tree_punch_time");
    public static final SoundEvent LINE_TREE_PUNCH_OWES_MONEY = register("bot.line.tree_punch_owes_money");
    public static final SoundEvent LINE_TREE_PUNCH_ORA = register("bot.line.tree_punch_ora");

    // Pig staring
    public static final SoundEvent LINE_PIG_STARING = register("bot.line.pig_staring");

    // Underground / mines
    public static final SoundEvent LINE_UNDERGROUND_YEARN_MINES = register("bot.line.underground_yearn_mines");

    // End ship sequence (captain-now bit)
    public static final SoundEvent LINE_END_SHIP_LOOK_AT_ME = register("bot.line.end_ship_look_at_me");
    public static final SoundEvent LINE_END_SHIP_CAPTAIN = register("bot.line.end_ship_captain");
    public static final SoundEvent LINE_END_SHIP_RUINED_JOKE = register("bot.line.end_ship_ruined_joke");

    // Near primed TNT
    public static final SoundEvent LINE_TNT_WOULDNT = register("bot.line.tnt_wouldnt");
    public static final SoundEvent LINE_TNT_WONT = register("bot.line.tnt_wont");
    public static final SoundEvent LINE_TNT_TALK_OVER = register("bot.line.tnt_talk_over");
    public static final SoundEvent LINE_TNT_TERMINATOR = register("bot.line.tnt_terminator");

    // Dirt digging
    public static final SoundEvent LINE_DIRT_DIGGY_HOLE = register("bot.line.dirt_diggy_hole");

    // Ambient chatter additions
    public static final SoundEvent LINE_AMBIENT_SAW_BIRD = register("bot.line.ambient_saw_bird");
    public static final SoundEvent LINE_AMBIENT_HAD_PLAN = register("bot.line.ambient_had_plan");
    public static final SoundEvent LINE_AMBIENT_GIANT_STATUE = register("bot.line.ambient_giant_statue");
    public static final SoundEvent LINE_AMBIENT_FORGOT_SOMETHING = register("bot.line.ambient_forgot_something");
    public static final SoundEvent LINE_AMBIENT_SAME_TREE = register("bot.line.ambient_same_tree");

    // Follow command acks
    public static final SoundEvent LINE_MODE_FOLLOW_YOU_LEAD = register("bot.line.mode_follow_you_lead");
    public static final SoundEvent LINE_MODE_FOLLOW_LETS_GO = register("bot.line.mode_follow_lets_go");
    public static final SoundEvent LINE_MODE_FOLLOW_MOVING = register("bot.line.mode_follow_moving");

    // Stop-follow command acks ("standing by" already exists as LINE_MODE_STAY_STANDING_BY)
    public static final SoundEvent LINE_STOP_ILL_STAY_HERE = register("bot.line.stop_ill_stay_here");
    public static final SoundEvent LINE_STOP_DONT_BE_LONG = register("bot.line.stop_dont_be_long");
    public static final SoundEvent LINE_STOP_SEE_YA = register("bot.line.stop_see_ya");
    public static final SoundEvent LINE_STOP_ADIOS = register("bot.line.stop_adios");
    public static final SoundEvent LINE_STOP_ILL_WAIT_HERE = register("bot.line.stop_ill_wait_here");

    // === APRIL 2026 HANDOFF (v1.1.30) — audio delivered, Java wiring in progress ===

    // Ambient chatter additions
    public static final SoundEvent LINE_AMBIENT_TEETH_ITCH = register("bot.line.ambient_teeth_itch");

    // Animal proximity reactions
    public static final SoundEvent LINE_ANIMAL_NEARBY_LOVE_ANIMALS = register("bot.line.animal_nearby_love_animals");
    public static final SoundEvent LINE_PARROT_NEARBY_NICE_BIRD = register("bot.line.parrot_nearby_nice_bird");
    public static final SoundEvent LINE_HORSE_NEARBY_NICE_HORSE = register("bot.line.horse_nearby_nice_horse");
    public static final SoundEvent LINE_CAMEL_NEARBY_NICE_CAMEL = register("bot.line.camel_nearby_nice_camel");
    public static final SoundEvent LINE_WOLF_NEARBY_GOOD_DOG = register("bot.line.wolf_nearby_good_dog");
    public static final SoundEvent LINE_WOLF_NEARBY_LOVE_DOGS = register("bot.line.wolf_nearby_love_dogs");
    public static final SoundEvent LINE_WOLF_NEARBY_SKINWALKER = register("bot.line.wolf_nearby_skinwalker");

    // Berry-bush skill callbacks
    public static final SoundEvent LINE_BERRY_BUSH_OUCH = register("bot.line.berry_bush_ouch");
    public static final SoundEvent LINE_BERRY_BUSH_YOWCH = register("bot.line.berry_bush_yowch");
    public static final SoundEvent LINE_BERRY_BUSH_EDIBLE_I_THINK = register("bot.line.berry_bush_these_are_edible_i_think");
    public static final SoundEvent LINE_BERRY_BUSH_THORNY = register("bot.line.berry_bush_these_are_thorny");

    // "Creepy place" — high-threat/unnerving-location flavor
    public static final SoundEvent LINE_CREEPY_PLACE_HEARD_THAT = register("bot.line.creepy_place_heard_that");
    public static final SoundEvent LINE_CREEPY_PLACE_IM_FINE_PROBABLY = register("bot.line.creepy_place_im_fine_probably");
    public static final SoundEvent LINE_CREEPY_PLACE_PRETEND_DIDNT_SEE = register("bot.line.creepy_place_pretend_didnt_see");
    public static final SoundEvent LINE_CREEPY_PLACE_SAW_NOTHING = register("bot.line.creepy_place_saw_nothing");
    public static final SoundEvent LINE_CREEPY_PLACE_SHOULDNT_BE_HERE = register("bot.line.creepy_place_shouldnt_be_here");
    public static final SoundEvent LINE_CREEPY_PLACE_WE_CAN_RECOVER = register("bot.line.creepy_place_we_can_recover");
    public static final SoundEvent LINE_CREEPY_PLACE_WIN_OR_LEAVE = register("bot.line.creepy_place_win_or_leave");

    // Enderman sighted
    public static final SoundEvent LINE_ENDERMAN_SPOTTED_DONT_LOOK = register("bot.line.enderman_spotted_dont_look");

    // Foliage/branch stuck (navigation)
    public static final SoundEvent LINE_FOLIAGE_STUCK_CANT_GET_THROUGH = register("bot.line.foliage_stuck_cant_get_through_these_leaves");
    public static final SoundEvent LINE_FOLIAGE_STUCK_GOT_ME = register("bot.line.foliage_stuck_foliage_got_me");
    public static final SoundEvent LINE_FOLIAGE_STUCK_IN_BRANCHES = register("bot.line.foliage_stuck_hold_on_stuck_in_branches");
    public static final SoundEvent LINE_FOLIAGE_STUCK_BRANCHES_THICK = register("bot.line.foliage_stuck_these_branches_are_thick");

    // Bot hit player (apology)
    public static final SoundEvent LINE_I_HIT_PLAYER_ACCIDENT = register("bot.line.i_hit_player_accident");
    public static final SoundEvent LINE_I_HIT_PLAYER_MUST_HAVE_HURT = register("bot.line.i_hit_player_must_have_hurt");
    public static final SoundEvent LINE_I_HIT_PLAYER_MY_BAD = register("bot.line.i_hit_player_my_bad");
    public static final SoundEvent LINE_I_HIT_PLAYER_NOT_ON_PURPOSE = register("bot.line.i_hit_player_not_on_purpose");
    public static final SoundEvent LINE_I_HIT_PLAYER_OOPS_SORRY = register("bot.line.i_hit_player_oops_sorry");
    public static final SoundEvent LINE_I_HIT_PLAYER_SORRY = register("bot.line.i_hit_player_sorry");

    // Intense combat
    public static final SoundEvent LINE_INTENSE_COMBAT_POINT_ME_AT_PROBLEM = register("bot.line.intense_combat_point_me_at_problem");
    public static final SoundEvent LINE_INTENSE_COMBAT_THRIVING_IN_TERRIBLE = register("bot.line.intense_combat_thriving_in_terrible");
    public static final SoundEvent LINE_INTENSE_COMBAT_TRYING_TO_KILL_US = register("bot.line.intense_combat_trying_to_kill_us");

    // Player hit bot (protest)
    public static final SoundEvent LINE_PLAYER_HIT_ME_FRIENDLY_FIRE = register("bot.line.player_hit_me_friendly_fire");
    public static final SoundEvent LINE_PLAYER_HIT_ME_LOOK_OUT = register("bot.line.player_hit_me_look_out");
    public static final SoundEvent LINE_PLAYER_HIT_ME_QUIT_IT = register("bot.line.player_hit_me_quit_it");
    public static final SoundEvent LINE_PLAYER_HIT_ME_STOP_THAT = register("bot.line.player_hit_me_stop_that");
    public static final SoundEvent LINE_PLAYER_HIT_ME_THAT_HURT = register("bot.line.player_hit_me_that_hurt");
    public static final SoundEvent LINE_PLAYER_HIT_ME_WATCH_WHERE = register("bot.line.player_hit_me_watch_where");

    // Post-combat / post-explosion extras
    public static final SoundEvent LINE_POST_COMBAT_WASNT_AWESOME = register("bot.line.post_combat_wasnt_awesome");
    public static final SoundEvent LINE_POST_EXPLOSION_EXACTLY_HOW_IT_WENT = register("bot.line.post_explosion_exactly_how_it_went");

    // Shelter completion
    public static final SoundEvent LINE_SHELTER_BUILT_ALMOST_SOUND = register("bot.line.shelter_built_almost_sound");
    public static final SoundEvent LINE_SHELTER_BUILT_INTENTIONAL = register("bot.line.shelter_built_intentional");
    public static final SoundEvent LINE_SHELTER_BUILT_RECTANGLE = register("bot.line.shelter_built_rectangle");
    public static final SoundEvent LINE_SHELTER_BUILT_RESOURCES_LIMITED = register("bot.line.shelter_built_resources_limited");

    // Sunset variants (extend existing LINE_TIME_SUNSET_SOON)
    public static final SoundEvent LINE_TIME_SUNSET_SOON_01 = register("bot.line.time_sunset_soon_01");
    public static final SoundEvent LINE_TIME_SUNSET_SOON_02 = register("bot.line.time_sunset_soon_02");
    public static final SoundEvent LINE_TIME_SUNSET_SOON_03 = register("bot.line.time_sunset_soon_03");
    public static final SoundEvent LINE_TIME_SUNSET_SOON_04 = register("bot.line.time_sunset_soon_04");
    public static final SoundEvent LINE_TIME_SUNSET_SOON_05 = register("bot.line.time_sunset_soon_05");
    public static final SoundEvent LINE_TIME_SUNSET_SOON_06 = register("bot.line.time_sunset_soon_06");

    // Weather variants (extend WEATHER_*_LINES pools)
    public static final SoundEvent LINE_WEATHER_RAIN_01 = register("bot.line.weather_rain_01");
    public static final SoundEvent LINE_WEATHER_RAIN_02 = register("bot.line.weather_rain_02");
    public static final SoundEvent LINE_WEATHER_RAIN_03 = register("bot.line.weather_rain_03");
    public static final SoundEvent LINE_WEATHER_RAIN_04 = register("bot.line.weather_rain_04");
    public static final SoundEvent LINE_WEATHER_RAIN_05 = register("bot.line.weather_rain_05");
    public static final SoundEvent LINE_WEATHER_RAIN_06 = register("bot.line.weather_rain_06");
    public static final SoundEvent LINE_WEATHER_SNOW_01 = register("bot.line.weather_snow_01");
    public static final SoundEvent LINE_WEATHER_SNOW_02 = register("bot.line.weather_snow_02");
    public static final SoundEvent LINE_WEATHER_SNOW_03 = register("bot.line.weather_snow_03");
    public static final SoundEvent LINE_WEATHER_SNOW_04 = register("bot.line.weather_snow_04");
    public static final SoundEvent LINE_WEATHER_SNOW_05 = register("bot.line.weather_snow_05");
    public static final SoundEvent LINE_WEATHER_SNOW_06 = register("bot.line.weather_snow_06");
    public static final SoundEvent LINE_WEATHER_SUNNY_01 = register("bot.line.weather_sunny_01");
    public static final SoundEvent LINE_WEATHER_SUNNY_02 = register("bot.line.weather_sunny_02");
    public static final SoundEvent LINE_WEATHER_SUNNY_03 = register("bot.line.weather_sunny_03");
    public static final SoundEvent LINE_WEATHER_SUNNY_04 = register("bot.line.weather_sunny_04");
    public static final SoundEvent LINE_WEATHER_SUNNY_05 = register("bot.line.weather_sunny_05");
    public static final SoundEvent LINE_WEATHER_SUNNY_06 = register("bot.line.weather_sunny_06");
    public static final SoundEvent LINE_WEATHER_THUNDER_01 = register("bot.line.weather_thunder_01");
    public static final SoundEvent LINE_WEATHER_THUNDER_02 = register("bot.line.weather_thunder_02");
    public static final SoundEvent LINE_WEATHER_THUNDER_03 = register("bot.line.weather_thunder_03");
    public static final SoundEvent LINE_WEATHER_THUNDER_04 = register("bot.line.weather_thunder_04");
    public static final SoundEvent LINE_WEATHER_THUNDER_05 = register("bot.line.weather_thunder_05");
    public static final SoundEvent LINE_WEATHER_THUNDER_06 = register("bot.line.weather_thunder_06");

    // === MAY 2026 BACKLOG ===

    // Nautilus mob (1.21.11): wild ocean encounter + tamed-rideable encounter.
    // Per the wiki MCP: NautilusEntity extends TameableEntity; tame via pufferfish, ride via saddle.
    public static final SoundEvent LINE_NAUTILUS_OCEAN_NEVER = register("bot.line.nautilus_ocean_never");
    public static final SoundEvent LINE_NAUTILUS_RIDE = register("bot.line.nautilus_ride");

    // Tamed cat sighting (separate from "quality animal" — cats get their own line).
    public static final SoundEvent LINE_CAT_MEOW = register("bot.line.cat_meow");

    // Sniffer sighting — rare cute mob.
    public static final SoundEvent LINE_SNIFFER_DINOSAUR = register("bot.line.sniffer_dinosaur");
    public static final SoundEvent LINE_SNIFFER_CUTEST_THING = register("bot.line.sniffer_cutest_thing");

    // Nether-mob proximity reactions.
    public static final SoundEvent LINE_ZOMBIFIED_PIGLIN_PORKCHOP = register("bot.line.zombified_piglin_porkchop");
    public static final SoundEvent LINE_HOGLIN_BACON_SPREE = register("bot.line.hoglin_bacon_spree");
    public static final SoundEvent LINE_PIGLIN_BRUTE_BIGGER = register("bot.line.piglin_brute_bigger");

    // Aquatic + raid mob proximity reactions.
    public static final SoundEvent LINE_SQUID_JUST_A = register("bot.line.squid_just_a");
    public static final SoundEvent LINE_GLOW_SQUID_PRETTY = register("bot.line.glow_squid_pretty");
    public static final SoundEvent LINE_DOLPHIN_DID_YOU_SEE = register("bot.line.dolphin_did_you_see");
    public static final SoundEvent LINE_VEX_GOBLINS_WINGS = register("bot.line.vex_goblins_wings");

    // Fox / ocelot near chickens — shared line for either predator type.
    public static final SoundEvent LINE_FOX_OCELOT_NEAR_CHICKENS = register("bot.line.fox_ocelot_near_chickens");

    // Panda variants (PandaEntity.getMainGene() = WORRIED / LAZY / BROWN / AGGRESSIVE).
    public static final SoundEvent LINE_PANDA_WORRIED = register("bot.line.panda_worried");
    public static final SoundEvent LINE_PANDA_LAZY = register("bot.line.panda_lazy");
    public static final SoundEvent LINE_PANDA_BROWN = register("bot.line.panda_brown");
    public static final SoundEvent LINE_PANDA_AGGRESSIVE = register("bot.line.panda_aggressive");
    public static final SoundEvent LINE_PANDA_PLAYFUL = register("bot.line.panda_playful");
    public static final SoundEvent LINE_PANDA_WEAK = register("bot.line.panda_weak");

    // Cute-animal "can we keep it?" pool — fires near various untamed cute mobs.
    public static final SoundEvent LINE_CUTE_ANIMAL_KEEP_IT = register("bot.line.cute_animal_keep_it");
    public static final SoundEvent LINE_CUTE_ANIMAL_LOOK_AT_IT = register("bot.line.cute_animal_look_at_it");
    public static final SoundEvent LINE_CUTE_ANIMAL_WANT_ONE = register("bot.line.cute_animal_want_one");
    public static final SoundEvent LINE_CUTE_ANIMAL_SO_CUTE = register("bot.line.cute_animal_so_cute");

    // Guardian (ocean monument) — proximity + laser-charging states.
    public static final SoundEvent LINE_GUARDIAN_STARING_RIGHT = register("bot.line.guardian_staring_right");
    public static final SoundEvent LINE_GUARDIAN_DONT_LIKE = register("bot.line.guardian_dont_like");
    public static final SoundEvent LINE_GUARDIAN_GLOWING = register("bot.line.guardian_glowing");
    public static final SoundEvent LINE_GUARDIAN_BEAM_HURT = register("bot.line.guardian_beam_hurt");

    // Elder guardian — rarer/more-emphatic pool.
    public static final SoundEvent LINE_ELDER_GUARDIAN_BOSS = register("bot.line.elder_guardian_boss");
    public static final SoundEvent LINE_ELDER_GUARDIAN_FATIGUE = register("bot.line.elder_guardian_fatigue");

    // Mob-crusher detection (≥6 same-type passives stuffed in one cell).
    public static final SoundEvent LINE_MOB_CRUSHER_HUMANE = register("bot.line.mob_crusher_humane");
    public static final SoundEvent LINE_MOB_CRUSHER_CRUELTY_FREE = register("bot.line.mob_crusher_cruelty_free");
    public static final SoundEvent LINE_MOB_CRUSHER_NETHER_PLACE = register("bot.line.mob_crusher_nether_place");

    // Redstone-machine proximity (≥4 components AND ≥2 distinct types in a 5×5×5).
    public static final SoundEvent LINE_REDSTONE_MACHINE_TECH = register("bot.line.redstone_machine_tech");
    public static final SoundEvent LINE_REDSTONE_MACHINE_HELL_AND_BACK = register("bot.line.redstone_machine_hell_and_back");

    // Walking-dogs hobby — fires when the bot stands a sitting wolf up to start a walk.
    public static final SoundEvent LINE_WALK_DOGS_GOOD_DOG = register("bot.line.walk_dogs_good_dog");
    public static final SoundEvent LINE_WALK_DOGS_WALKIES = register("bot.line.walk_dogs_walkies");

    // Snow golem nearby — proximity dialogue for vanilla snow golems.
    public static final SoundEvent LINE_SNOW_GOLEM_FRIEND = register("bot.line.snow_golem_friend");
    public static final SoundEvent LINE_SNOW_GOLEM_BUDDY = register("bot.line.snow_golem_buddy");
    public static final SoundEvent LINE_SNOW_GOLEM_AMMO = register("bot.line.snow_golem_ammo");
    public static final SoundEvent LINE_SNOW_GOLEM_HUG = register("bot.line.snow_golem_hug");

    // Iron golem with daisy — fires when the bot offers a poppy/daisy to a nearby iron golem.
    public static final SoundEvent LINE_IRON_GOLEM_DAISY_HERE = register("bot.line.iron_golem_daisy_here");
    public static final SoundEvent LINE_IRON_GOLEM_DAISY_EARNED = register("bot.line.iron_golem_daisy_earned");
    public static final SoundEvent LINE_IRON_GOLEM_DAISY_CUTE = register("bot.line.iron_golem_daisy_cute");

    // Warden proximity — avoidance/fear dialogue. Long cooldown (warden encounters are rare & special).
    public static final SoundEvent LINE_WARDEN_LEAVE_NOW = register("bot.line.warden_leave_now");
    public static final SoundEvent LINE_WARDEN_NOT_A_SOUND = register("bot.line.warden_not_a_sound");
    public static final SoundEvent LINE_WARDEN_DONT_PEEP = register("bot.line.warden_dont_peep");
    public static final SoundEvent LINE_WARDEN_NOT_WHAT_THINK = register("bot.line.warden_not_what_think");
    public static final SoundEvent LINE_WARDEN_SNEAK = register("bot.line.warden_sneak");

    // Snowball-fight idle hobby (1.1.81). Lines fired by BotSnowballFightService via the
    // CompanionOverheadDialogueService.showOverheadLine path; audio is resolved by exact-text
    // lookup in DialogueTextMapper.EXACT_MAP. Three IDs (probe_incoming, escalate_on_now,
    // escalate_in_for_it) have no audio yet — TTS regen pending.
    public static final SoundEvent LINE_SNOWBALL_PROBE_CATCH_THIS = register("bot.line.snowball_probe_catch_this");
    public static final SoundEvent LINE_SNOWBALL_PROBE_HEADS_UP = register("bot.line.snowball_probe_heads_up");
    public static final SoundEvent LINE_SNOWBALL_PROBE_INCOMING = register("bot.line.snowball_probe_incoming");
    public static final SoundEvent LINE_SNOWBALL_PROBE_FIGHT = register("bot.line.snowball_probe_fight");
    public static final SoundEvent LINE_SNOWBALL_PROBE_BET = register("bot.line.snowball_probe_bet");
    public static final SoundEvent LINE_SNOWBALL_PROBE_FIGHT_Q = register("bot.line.snowball_probe_fight_q");
    public static final SoundEvent LINE_SNOWBALL_ESCALATE_ON_NOW = register("bot.line.snowball_escalate_on_now");
    public static final SoundEvent LINE_SNOWBALL_ESCALATE_IN_FOR_IT = register("bot.line.snowball_escalate_in_for_it");
    public static final SoundEvent LINE_SNOWBALL_ESCALATE_BRING_IT = register("bot.line.snowball_escalate_bring_it");
    public static final SoundEvent LINE_SNOWBALL_ESCALATE_PLAYING = register("bot.line.snowball_escalate_playing");
    public static final SoundEvent LINE_SNOWBALL_ESCALATE_EAT_SNOW = register("bot.line.snowball_escalate_eat_snow");
    public static final SoundEvent LINE_SNOWBALL_TAUNT_GOT_YOU = register("bot.line.snowball_taunt_got_you");
    public static final SoundEvent LINE_SNOWBALL_TAUNT_BULLSEYE = register("bot.line.snowball_taunt_bullseye");
    public static final SoundEvent LINE_SNOWBALL_TAUNT_TAKE_THAT = register("bot.line.snowball_taunt_take_that");
    public static final SoundEvent LINE_SNOWBALL_TAUNT_CANT_CATCH = register("bot.line.snowball_taunt_cant_catch");
    public static final SoundEvent LINE_SNOWBALL_TAUNT_ALL_YOU_GOT = register("bot.line.snowball_taunt_all_you_got");
    public static final SoundEvent LINE_SNOWBALL_TAUNT_HOLD_STILL = register("bot.line.snowball_taunt_hold_still");
    public static final SoundEvent LINE_SNOWBALL_TAUNT_ALMOST = register("bot.line.snowball_taunt_almost");
    public static final SoundEvent LINE_SNOWBALL_TAUNT_GOTCHA = register("bot.line.snowball_taunt_gotcha");
    public static final SoundEvent LINE_SNOWBALL_DODGE_CLOSE_ONE = register("bot.line.snowball_dodge_close_one");
    public static final SoundEvent LINE_SNOWBALL_DODGE_MISSED = register("bot.line.snowball_dodge_missed");
    public static final SoundEvent LINE_SNOWBALL_DODGE_NICE_TRY = register("bot.line.snowball_dodge_nice_try");
    public static final SoundEvent LINE_SNOWBALL_DODGE_DODGED = register("bot.line.snowball_dodge_dodged");
    public static final SoundEvent LINE_SNOWBALL_TIMEOUT_TOUGH_CROWD = register("bot.line.snowball_timeout_tough_crowd");
    public static final SoundEvent LINE_SNOWBALL_TIMEOUT_DONT_PLAY = register("bot.line.snowball_timeout_dont_play");
    public static final SoundEvent LINE_SNOWBALL_TIMEOUT_SUIT_YOURSELF = register("bot.line.snowball_timeout_suit_yourself");
    public static final SoundEvent LINE_SNOWBALL_TIMEOUT_NO_FUN = register("bot.line.snowball_timeout_no_fun");
    public static final SoundEvent LINE_SNOWBALL_YIELD_OUT_OF_AMMO = register("bot.line.snowball_yield_out_of_ammo");
    public static final SoundEvent LINE_SNOWBALL_YIELD_IM_OUT = register("bot.line.snowball_yield_im_out");
    public static final SoundEvent LINE_SNOWBALL_YIELD_YOU_WIN = register("bot.line.snowball_yield_you_win");
    public static final SoundEvent LINE_SNOWBALL_YIELD_MERCY = register("bot.line.snowball_yield_mercy");
    public static final SoundEvent LINE_SNOWBALL_YIELD_GOT_ME = register("bot.line.snowball_yield_got_me");

    private BotDialogueSounds() {
    }

    /**
     * Register a sound event with the given path.
     * @param path The sound event path (e.g., "bot.greeting" or "bot.line.greeting_hey")
     * @return The registered SoundEvent
     */
    private static SoundEvent register(String path) {
        Identifier id = Identifier.of(Frens.MOD_ID, path);
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

    // Spell sound helpers — vanilla SoundEvents, not registered via sounds.json
    public static final net.minecraft.sound.SoundEvent SPELL_GUIDANCE_PROMPT = net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
    public static final net.minecraft.sound.SoundEvent SPELL_GUIDANCE_ACCEPT = net.minecraft.sound.SoundEvents.ENTITY_ENDER_EYE_LAUNCH;
    public static final net.minecraft.sound.SoundEvent SPELL_GUIDANCE_CONSUME = net.minecraft.sound.SoundEvents.ENTITY_ENDER_PEARL_THROW;
    public static final net.minecraft.sound.SoundEvent SPELL_CHORUS_RECALL = net.minecraft.sound.SoundEvents.ENTITY_ENDERMAN_TELEPORT;

    /**
     * Initialize and register all sound events with the game registry.
     * Call this from the mod initializer.
     */
    public static void registerAll() {
        for (Map.Entry<String, SoundEvent> entry : SOUND_EVENTS.entrySet()) {
            Registry.register(Registries.SOUND_EVENT, entry.getValue().id(), entry.getValue());
        }
        Frens.LOGGER.info("Registered {} bot dialogue sound events", SOUND_EVENTS.size());
    }
}
