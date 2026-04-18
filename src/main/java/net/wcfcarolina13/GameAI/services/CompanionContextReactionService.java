package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.DolphinEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import net.wcfcarolina13.ChatUtils.BotDialoguePlayer;
import net.wcfcarolina13.ChatUtils.BotDialogueSounds;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotEventHandler;

import net.minecraft.entity.player.HungerManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Batch 3 Phase 1 non-topic context reactions.
 */
public final class CompanionContextReactionService {

    private static final Random RNG = new Random();

    // Rarity weights: COMMON=10, UNCOMMON=5, RARE=2, VERY_RARE=1.
    private static final int WEIGHT_COMMON = 10;
    private static final int WEIGHT_UNCOMMON = 5;
    private static final int WEIGHT_RARE = 2;
    private static final int WEIGHT_VERY_RARE = 1;

    private static final long COOLDOWN_90S_MS = 90_000L;
    private static final long COOLDOWN_180S_MS = 180_000L;
    private static final long COOLDOWN_COMBATISH_MS = 25_000L;
    private static final long COOLDOWN_FALL_MS = 20_000L;
    private static final long COOLDOWN_META_MS = 12L * 60L * 1000L;
    private static final long COOLDOWN_MEME_MS = 30L * 60L * 1000L;

    private static final Map<String, Long> TRIGGER_COOLDOWN_MS = new HashMap<>();

    private static final class WeightedLine {
        final String id;
        final String text;
        final net.minecraft.sound.SoundEvent sound;
        final int weight;

        WeightedLine(String id, String text, net.minecraft.sound.SoundEvent sound, int weight) {
            this.id = id;
            this.text = text;
            this.sound = sound;
            this.weight = Math.max(1, weight);
        }
    }

    private static final class TriggerState {
        final Map<String, Long> lastTriggerMs = new HashMap<>();
        final Map<String, String> lastLineByTrigger = new HashMap<>();
        boolean wasInBoat = false;
        boolean wasLowHealth = false;
        boolean wasCommanderLowHealth = false;
        boolean wasCommanderHungry = false;
        int lastWeatherKind = -1;
        /** Tick at which commander last was NOT looking at the bot. Used to compute
         *  how long the commander has been staring. -1 = not currently staring. */
        long commanderStareStartTick = -1L;
    }

    private static final ConcurrentHashMap<UUID, TriggerState> STATE = new ConcurrentHashMap<>();

    /** Tick at which each bot was first seen by this service. Used for spawn-grace silence. */
    private static final ConcurrentHashMap<UUID, Long> FIRST_SEEN_TICK = new ConcurrentHashMap<>();
    /** Number of ticks after spawn before the bot is allowed to trigger context reactions (~10 s). */
    private static final long SPAWN_GRACE_TICKS = 200L;

    private static final WeightedLine[] AMBIENT_LINES = new WeightedLine[] {
            // ambient_bad_feeling moved to HIGH_THREAT_LINES per triage retune — was
            // firing too often in safe ambient settings.
            new WeightedLine("ambient_my_job", "I can't believe this is my job.", BotDialogueSounds.LINE_AMBIENT_MY_JOB, WEIGHT_VERY_RARE),
            new WeightedLine("ambient_blame_terrain", "If we die, I'm blaming the terrain.", BotDialogueSounds.LINE_AMBIENT_BLAME_TERRAIN, WEIGHT_RARE),
            new WeightedLine("ambient_thinking", "I'm thinking. Don't rush me.", BotDialogueSounds.LINE_AMBIENT_THINKING, WEIGHT_COMMON),
            new WeightedLine("ambient_saw_bird", "I think I saw a bird.", BotDialogueSounds.LINE_AMBIENT_SAW_BIRD, WEIGHT_COMMON),
            new WeightedLine("ambient_had_plan", "What was I doing? I had a plan. I definitely had a plan.", BotDialogueSounds.LINE_AMBIENT_HAD_PLAN, WEIGHT_UNCOMMON),
            new WeightedLine("ambient_giant_statue", "I should probably build a giant statue of myself. Or a farm. Probably a farm.", BotDialogueSounds.LINE_AMBIENT_GIANT_STATUE, WEIGHT_RARE),
            new WeightedLine("ambient_forgot_something", "I'm 100% sure I forgot something, but I can't remember what it is.", BotDialogueSounds.LINE_AMBIENT_FORGOT_SOMETHING, WEIGHT_UNCOMMON),
            new WeightedLine("ambient_same_tree", "I swear I've walked past this exact tree three times now. I am definitely lost.", BotDialogueSounds.LINE_AMBIENT_SAME_TREE, WEIGHT_RARE),
            new WeightedLine("ambient_teeth_itch", "My teeth itch!", BotDialogueSounds.LINE_AMBIENT_TEETH_ITCH, WEIGHT_RARE)
    };

    private static final WeightedLine[] PIG_STARING_LINES = new WeightedLine[] {
            new WeightedLine("pig_staring", "That pig has been looking at me for a long time. It's getting weird.", BotDialogueSounds.LINE_PIG_STARING, WEIGHT_COMMON)
    };

    private static final WeightedLine[] UNDERGROUND_LINES = new WeightedLine[] {
            new WeightedLine("underground_yearn_mines", "I yearn for the mines.", BotDialogueSounds.LINE_UNDERGROUND_YEARN_MINES, WEIGHT_COMMON)
    };

    private static final WeightedLine[] ENDERMAN_SPOTTED_LINES = new WeightedLine[] {
            new WeightedLine("enderman_spotted_dont_look", "Don't look at it.", BotDialogueSounds.LINE_ENDERMAN_SPOTTED_DONT_LOOK, WEIGHT_COMMON)
    };

    private static final WeightedLine[] DIG_DOWN_LINES = new WeightedLine[] {
            new WeightedLine("dig_down_warning", "Never dig straight down! Are you new here?", BotDialogueSounds.LINE_DIG_DOWN_WARNING, WEIGHT_COMMON)
    };

    private static final WeightedLine[] TREE_PUNCH_LINES = new WeightedLine[] {
            new WeightedLine("tree_punch_time", "Time to punch some trees.", BotDialogueSounds.LINE_TREE_PUNCH_TIME, WEIGHT_COMMON),
            new WeightedLine("tree_punch_owes_money", "This tree owes me money.", BotDialogueSounds.LINE_TREE_PUNCH_OWES_MONEY, WEIGHT_COMMON),
            new WeightedLine("tree_punch_ora", "Ora ora ora ora ora ora ora ora ora ora!", BotDialogueSounds.LINE_TREE_PUNCH_ORA, WEIGHT_RARE)
    };

    private static final WeightedLine[] DIRT_DIG_LINES = new WeightedLine[] {
            new WeightedLine("dirt_diggy_hole", "Diggy diggy hole.", BotDialogueSounds.LINE_DIRT_DIGGY_HOLE, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] FOLLOW_ACK_LINES = new WeightedLine[] {
            new WeightedLine("mode_follow_you_lead", "You lead.", BotDialogueSounds.LINE_MODE_FOLLOW_YOU_LEAD, WEIGHT_COMMON),
            new WeightedLine("mode_follow_lets_go", "Let's go.", BotDialogueSounds.LINE_MODE_FOLLOW_LETS_GO, WEIGHT_COMMON),
            new WeightedLine("mode_follow_moving", "Moving.", BotDialogueSounds.LINE_MODE_FOLLOW_MOVING, WEIGHT_COMMON)
    };

    private static final WeightedLine[] STOP_ACK_LINES = new WeightedLine[] {
            new WeightedLine("stop_ill_stay_here", "I'll stay here.", BotDialogueSounds.LINE_STOP_ILL_STAY_HERE, WEIGHT_COMMON),
            new WeightedLine("stop_dont_be_long", "Don't be too long.", BotDialogueSounds.LINE_STOP_DONT_BE_LONG, WEIGHT_COMMON),
            new WeightedLine("stop_see_ya", "See ya.", BotDialogueSounds.LINE_STOP_SEE_YA, WEIGHT_COMMON),
            new WeightedLine("stop_adios", "Adiós.", BotDialogueSounds.LINE_STOP_ADIOS, WEIGHT_COMMON),
            new WeightedLine("stop_ill_wait_here", "I'll wait here.", BotDialogueSounds.LINE_STOP_ILL_WAIT_HERE, WEIGHT_COMMON),
            new WeightedLine("mode_stay_standing_by", "Standing by.", BotDialogueSounds.LINE_MODE_STAY_STANDING_BY, WEIGHT_COMMON)
    };

    private static final WeightedLine[] HIGH_THREAT_LINES = new WeightedLine[] {
            new WeightedLine("creepy_head_swivel", "Keep your head on a swivel.", BotDialogueSounds.LINE_CREEPY_HEAD_SWIVEL, WEIGHT_UNCOMMON),
            new WeightedLine("creepy_bad_ideas_mature", "This is where bad ideas go to mature.", BotDialogueSounds.LINE_CREEPY_BAD_IDEAS_MATURE, WEIGHT_UNCOMMON),
            new WeightedLine("creepy_complaint_reality", "I'd like to file a complaint with reality.", BotDialogueSounds.LINE_CREEPY_COMPLAINT_REALITY, WEIGHT_RARE),
            new WeightedLine("ambient_bad_feeling", "I have a bad feeling about this.", BotDialogueSounds.LINE_AMBIENT_BAD_FEELING, WEIGHT_UNCOMMON),
            new WeightedLine("creepy_place_heard_that", "I heard that. I hate that I heard that.", BotDialogueSounds.LINE_CREEPY_PLACE_HEARD_THAT, WEIGHT_UNCOMMON),
            new WeightedLine("creepy_place_im_fine_probably", "I'm fine. Probably.", BotDialogueSounds.LINE_CREEPY_PLACE_IM_FINE_PROBABLY, WEIGHT_UNCOMMON),
            new WeightedLine("creepy_place_pretend_didnt_see", "I'll pretend I didn't see that.", BotDialogueSounds.LINE_CREEPY_PLACE_PRETEND_DIDNT_SEE, WEIGHT_UNCOMMON),
            new WeightedLine("creepy_place_saw_nothing", "I saw nothing.", BotDialogueSounds.LINE_CREEPY_PLACE_SAW_NOTHING, WEIGHT_COMMON),
            new WeightedLine("creepy_place_shouldnt_be_here", "This place has strong 'we shouldn't be here' energy.", BotDialogueSounds.LINE_CREEPY_PLACE_SHOULDNT_BE_HERE, WEIGHT_RARE),
            new WeightedLine("creepy_place_we_can_recover", "We can recover.", BotDialogueSounds.LINE_CREEPY_PLACE_WE_CAN_RECOVER, WEIGHT_UNCOMMON),
            new WeightedLine("creepy_place_win_or_leave", "We can win this. Or we can leave. I'm flexible.", BotDialogueSounds.LINE_CREEPY_PLACE_WIN_OR_LEAVE, WEIGHT_RARE)
    };

    private static final WeightedLine[] SCARY_LINES = new WeightedLine[] {
            new WeightedLine("scary_not_acknowledging", "Nope. Not acknowledging that.", BotDialogueSounds.LINE_SCARY_NOT_ACKNOWLEDGING, WEIGHT_COMMON),
            new WeightedLine("scary_hate_sound", "I hate that sound.", BotDialogueSounds.LINE_SCARY_HATE_SOUND, WEIGHT_COMMON)
    };

    private static final WeightedLine[] BOAT_NOT_COMBAT_LINES = new WeightedLine[] {
            new WeightedLine("boat_fish_size", "Did you see the size of that fish?", BotDialogueSounds.LINE_BOAT_FISH_SIZE, WEIGHT_COMMON),
            new WeightedLine("boat_kraken", "Look out, kraken! Just kidding.", BotDialogueSounds.LINE_BOAT_KRAKEN, WEIGHT_COMMON),
            new WeightedLine("boat_beautiful_day", "Beautiful day to be on the water.", BotDialogueSounds.LINE_BOAT_BEAUTIFUL_DAY, WEIGHT_COMMON),
            new WeightedLine("boat_good_fishing", "Probably some good fishing in these parts.", BotDialogueSounds.LINE_BOAT_GOOD_FISHING, WEIGHT_COMMON),
            new WeightedLine("boat_know_swim", "I hope you know how to swim.", BotDialogueSounds.LINE_BOAT_KNOW_SWIM, WEIGHT_COMMON)
    };

    private static final WeightedLine[] BOAT_DEEP_WATER_LINES = new WeightedLine[] {
            new WeightedLine("boat_deep_water", "That water looks deep.", BotDialogueSounds.LINE_BOAT_DEEP_WATER, WEIGHT_COMMON)
    };

    private static final WeightedLine[] BOAT_DOLPHIN_LINES = new WeightedLine[] {
            new WeightedLine("boat_dolphin_escort", "We've got an escort.", BotDialogueSounds.LINE_BOAT_DOLPHIN_ESCORT, WEIGHT_COMMON)
    };

    private static final WeightedLine[] BOAT_BREAK_LINES = new WeightedLine[] {
            new WeightedLine("boat_shipwreck_speedrun", "Shipwreck speedrun.", BotDialogueSounds.LINE_BOAT_SHIPWRECK_SPEEDRUN, WEIGHT_RARE)
    };

    private static final WeightedLine[] PRECIPICE_LINES = new WeightedLine[] {
            new WeightedLine("precipice_you_first", "You first.", BotDialogueSounds.LINE_PRECIPICE_YOU_FIRST, WEIGHT_COMMON),
            new WeightedLine("precipice_gonna_jump", "Are we gonna jump?", BotDialogueSounds.LINE_PRECIPICE_GONNA_JUMP, WEIGHT_COMMON),
            new WeightedLine("precipice_big_drop", "That's a big drop.", BotDialogueSounds.LINE_PRECIPICE_BIG_DROP, WEIGHT_COMMON),
            new WeightedLine("precipice_nope", "Nope.", BotDialogueSounds.LINE_PRECIPICE_NOPE, WEIGHT_COMMON),
            new WeightedLine("precipice_not_fan_gravity", "I'm not a fan of gravity right now.", BotDialogueSounds.LINE_PRECIPICE_NOT_FAN_GRAVITY, WEIGHT_UNCOMMON),
            new WeightedLine("precipice_back_up", "Let's back up. Slowly.", BotDialogueSounds.LINE_PRECIPICE_BACK_UP, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] VISTA_LINES = new WeightedLine[] {
            new WeightedLine("vista_wow", "Wow.", BotDialogueSounds.LINE_VISTA_WOW, WEIGHT_COMMON),
            new WeightedLine("vista_would_you_look", "Wow, would you look at that.", BotDialogueSounds.LINE_VISTA_WOULD_YOU_LOOK, WEIGHT_COMMON),
            new WeightedLine("vista_built_base_here", "We should have built the base here.", BotDialogueSounds.LINE_VISTA_BUILT_BASE_HERE, WEIGHT_COMMON),
            new WeightedLine("vista_beautiful", "That's beautiful.", BotDialogueSounds.LINE_VISTA_BEAUTIFUL, WEIGHT_COMMON),
            new WeightedLine("vista_gorgeous", "That's gorgeous.", BotDialogueSounds.LINE_VISTA_GORGEOUS, WEIGHT_COMMON),
            new WeightedLine("vista_amazing_view", "Amazing view.", BotDialogueSounds.LINE_VISTA_AMAZING_VIEW, WEIGHT_COMMON),
            new WeightedLine("vista_worth_walk", "Okay, that's worth the walk.", BotDialogueSounds.LINE_VISTA_WORTH_WALK, WEIGHT_UNCOMMON),
            new WeightedLine("vista_could_live_here", "I could live here.", BotDialogueSounds.LINE_VISTA_COULD_LIVE_HERE, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] FREEFALL_LINES = new WeightedLine[] {
            new WeightedLine("freefall_exhilarating", "Exhilarating!!!", BotDialogueSounds.LINE_FREEFALL_EXHILARATING, WEIGHT_COMMON),
            new WeightedLine("freefall_im_a_bird", "I'm a bird!", BotDialogueSounds.LINE_FREEFALL_IM_A_BIRD, WEIGHT_COMMON),
            new WeightedLine("freefall_falling_style", "Falling with style!", BotDialogueSounds.LINE_FREEFALL_FALLING_STYLE, WEIGHT_COMMON),
            new WeightedLine("freefall_yolo", "Yolo!", BotDialogueSounds.LINE_FREEFALL_YOLO, WEIGHT_COMMON),
            new WeightedLine("freefall_aaahaha", "Aaahaha!", BotDialogueSounds.LINE_FREEFALL_AAAHAHA, WEIGHT_COMMON),
            new WeightedLine("freefall_woohoo", "WOOOOOHOOOOO!!!", BotDialogueSounds.LINE_FREEFALL_WOOHOO, WEIGHT_COMMON),
            new WeightedLine("freefall_regret", "I immediately regret this!", BotDialogueSounds.LINE_FREEFALL_REGRET, WEIGHT_UNCOMMON),
            new WeightedLine("freefall_inventory", "Someone tell my inventory I loved it.", BotDialogueSounds.LINE_FREEFALL_INVENTORY, WEIGHT_RARE)
    };

    private static final WeightedLine[] META_LINES = new WeightedLine[] {
            new WeightedLine("meta_not_robot", "I'm not a robot. You're a robot.", BotDialogueSounds.LINE_META_NOT_ROBOT, WEIGHT_RARE),
            new WeightedLine("meta_human_laugh", "That was a human laugh. Totally normal.", BotDialogueSounds.LINE_META_HUMAN_LAUGH, WEIGHT_RARE)
            // meta_stop_looking moved to COMMANDER_STARING_LINES per triage retune —
            // only fires after ≥5 s of commander looking at the bot.
    };

    private static final WeightedLine[] COMMANDER_STARING_LINES = new WeightedLine[] {
            new WeightedLine("meta_stop_looking", "Stop looking at me like that. I'm trying.", BotDialogueSounds.LINE_META_STOP_LOOKING, WEIGHT_COMMON)
    };

    private static final WeightedLine[] MEME_CHICKEN_LINES = new WeightedLine[] {
            new WeightedLine("meme_chicken_jockey", "Chicken jockey!", BotDialogueSounds.LINE_MEME_CHICKEN_JOCKEY, WEIGHT_VERY_RARE),
            new WeightedLine("meme_chicken_nope", "Nope. Absolutely not.", BotDialogueSounds.LINE_MEME_CHICKEN_NOPE, WEIGHT_VERY_RARE),
            new WeightedLine("meme_chicken_illegal", "That's illegal. That's against nature.", BotDialogueSounds.LINE_MEME_CHICKEN_ILLEGAL, WEIGHT_VERY_RARE)
    };

    private static final WeightedLine[] MEME_CREEPER_LINES = new WeightedLine[] {
            new WeightedLine("meme_creeper_aw_man", "Creeper... aw man.", BotDialogueSounds.LINE_MEME_CREEPER_AW_MAN, WEIGHT_VERY_RARE),
            new WeightedLine("meme_creeper_back_up", "Back up. Back up. Back up.", BotDialogueSounds.LINE_MEME_CREEPER_BACK_UP, WEIGHT_COMMON),
            new WeightedLine("meme_creeper_hate_sound", "I hate that sound.", BotDialogueSounds.LINE_MEME_CREEPER_HATE_SOUND, WEIGHT_COMMON)
    };

    private static final WeightedLine[] MEME_STEVE_LINES = new WeightedLine[] {
            new WeightedLine("meme_i_am_steve", "I am Steve.", BotDialogueSounds.LINE_MEME_I_AM_STEVE, WEIGHT_VERY_RARE),
            new WeightedLine("meme_steve_adjacent", "I'm... Steve-adjacent.", BotDialogueSounds.LINE_MEME_STEVE_ADJACENT, WEIGHT_VERY_RARE)
    };

    private static final WeightedLine[] MEME_TECHNOBLADE_LINES = new WeightedLine[] {
            new WeightedLine("meme_technoblade", "Technoblade never dies.", BotDialogueSounds.LINE_MEME_TECHNOBLADE, WEIGHT_VERY_RARE)
    };

    private static final WeightedLine[] MEME_HEROBRINE_LINES = new WeightedLine[] {
            new WeightedLine("meme_herobrine_leaving", "If you say 'Herobrine,' I'm leaving.", BotDialogueSounds.LINE_MEME_HEROBRINE_LEAVING, WEIGHT_VERY_RARE),
            new WeightedLine("meme_herobrine_saw_nothing", "I saw nothing. And I'm keeping it that way.", BotDialogueSounds.LINE_MEME_HEROBRINE_SAW_NOTHING, WEIGHT_VERY_RARE)
    };

    private static final WeightedLine[] PLAYER_HURT_LINES = new WeightedLine[] {
            new WeightedLine("care_player_hurt_1", "You're looking rough - need a breather?", BotDialogueSounds.LINE_CARE_PLAYER_HURT_1, WEIGHT_COMMON),
            new WeightedLine("care_player_hurt_2", "That's a lot of damage. Take it easy.", BotDialogueSounds.LINE_CARE_PLAYER_HURT_2, WEIGHT_COMMON),
            new WeightedLine("care_player_hurt_3", "Hang in there. I've got food if you need it.", BotDialogueSounds.LINE_CARE_PLAYER_HURT_3, WEIGHT_COMMON)
    };

    private static final WeightedLine[] PLAYER_HUNGRY_LINES = new WeightedLine[] {
            new WeightedLine("care_player_hungry_1", "Your stomach's growling - eat something.", BotDialogueSounds.LINE_CARE_PLAYER_HUNGRY_1, WEIGHT_COMMON),
            new WeightedLine("care_player_hungry_2", "You should eat. I think I've got something.", BotDialogueSounds.LINE_CARE_PLAYER_HUNGRY_2, WEIGHT_COMMON),
            new WeightedLine("care_player_hungry_3", "Low on food? Don't wait too long.", BotDialogueSounds.LINE_CARE_PLAYER_HUNGRY_3, WEIGHT_COMMON)
    };

    private static final WeightedLine[] SHELTER_LINES = new WeightedLine[] {
            new WeightedLine("shelter_roof_luxury", "We have a roof. Luxury.", BotDialogueSounds.LINE_SHELTER_ROOF_LUXURY, WEIGHT_UNCOMMON),
            new WeightedLine("shelter_not_pretty", "It's not pretty, but it's ours.", BotDialogueSounds.LINE_SHELTER_NOT_PRETTY, WEIGHT_UNCOMMON),
            new WeightedLine("shelter_some_problems", "This will keep out... some of the problems.", BotDialogueSounds.LINE_SHELTER_SOME_PROBLEMS, WEIGHT_UNCOMMON),
            new WeightedLine("shelter_built_almost_sound", "I'm proud of us. This is almost structurally sound.", BotDialogueSounds.LINE_SHELTER_BUILT_ALMOST_SOUND, WEIGHT_UNCOMMON),
            new WeightedLine("shelter_built_intentional", "If anyone asks, this was intentional.", BotDialogueSounds.LINE_SHELTER_BUILT_INTENTIONAL, WEIGHT_UNCOMMON),
            new WeightedLine("shelter_built_rectangle", "Aesthetic update. We live in a rectangle.", BotDialogueSounds.LINE_SHELTER_BUILT_RECTANGLE, WEIGHT_UNCOMMON),
            new WeightedLine("shelter_built_resources_limited", "We call this style. Resources were limited.", BotDialogueSounds.LINE_SHELTER_BUILT_RESOURCES_LIMITED, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] WEATHER_RAIN_LINES = new WeightedLine[] {
            new WeightedLine("weather_rain", "Rain's coming down.", BotDialogueSounds.LINE_WEATHER_RAIN, WEIGHT_COMMON),
            new WeightedLine("weather_rain_01", "It's starting to rain.", BotDialogueSounds.LINE_WEATHER_RAIN_01, WEIGHT_COMMON),
            new WeightedLine("weather_rain_02", "Rain's coming down.", BotDialogueSounds.LINE_WEATHER_RAIN_02, WEIGHT_COMMON),
            new WeightedLine("weather_rain_03", "Getting wet out here.", BotDialogueSounds.LINE_WEATHER_RAIN_03, WEIGHT_COMMON),
            new WeightedLine("weather_rain_04", "Hope this clears up soon.", BotDialogueSounds.LINE_WEATHER_RAIN_04, WEIGHT_COMMON),
            new WeightedLine("weather_rain_05", "At least it's not snow.", BotDialogueSounds.LINE_WEATHER_RAIN_05, WEIGHT_UNCOMMON),
            new WeightedLine("weather_rain_06", "The rain feels nice, actually.", BotDialogueSounds.LINE_WEATHER_RAIN_06, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] WEATHER_SNOW_LINES = new WeightedLine[] {
            new WeightedLine("weather_snow", "Snow's coming down.", BotDialogueSounds.LINE_WEATHER_SNOW, WEIGHT_COMMON),
            new WeightedLine("weather_snow_01", "Snow's falling.", BotDialogueSounds.LINE_WEATHER_SNOW_01, WEIGHT_COMMON),
            new WeightedLine("weather_snow_02", "It's snowing out here.", BotDialogueSounds.LINE_WEATHER_SNOW_02, WEIGHT_COMMON),
            new WeightedLine("weather_snow_03", "Cold. Very cold.", BotDialogueSounds.LINE_WEATHER_SNOW_03, WEIGHT_COMMON),
            new WeightedLine("weather_snow_04", "Bundle up. It's snowing.", BotDialogueSounds.LINE_WEATHER_SNOW_04, WEIGHT_COMMON),
            new WeightedLine("weather_snow_05", "Snow everywhere. Beautiful, but cold.", BotDialogueSounds.LINE_WEATHER_SNOW_05, WEIGHT_UNCOMMON),
            new WeightedLine("weather_snow_06", "I can barely see through this snow.", BotDialogueSounds.LINE_WEATHER_SNOW_06, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] WEATHER_THUNDER_LINES = new WeightedLine[] {
            new WeightedLine("weather_thunder", "Thunderstorm.", BotDialogueSounds.LINE_WEATHER_THUNDER, WEIGHT_COMMON),
            new WeightedLine("weather_thunder_01", "Thunder! Find cover!", BotDialogueSounds.LINE_WEATHER_THUNDER_01, WEIGHT_COMMON),
            new WeightedLine("weather_thunder_02", "Storm's rolling in.", BotDialogueSounds.LINE_WEATHER_THUNDER_02, WEIGHT_COMMON),
            new WeightedLine("weather_thunder_03", "Thunderstorm. Stay low.", BotDialogueSounds.LINE_WEATHER_THUNDER_03, WEIGHT_COMMON),
            new WeightedLine("weather_thunder_04", "That lightning is close!", BotDialogueSounds.LINE_WEATHER_THUNDER_04, WEIGHT_COMMON),
            new WeightedLine("weather_thunder_05", "I don't like the sound of that thunder.", BotDialogueSounds.LINE_WEATHER_THUNDER_05, WEIGHT_UNCOMMON),
            new WeightedLine("weather_thunder_06", "Bad time to be outside.", BotDialogueSounds.LINE_WEATHER_THUNDER_06, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] WEATHER_SUNNY_LINES = new WeightedLine[] {
            new WeightedLine("weather_sunny", "Nice clear day.", BotDialogueSounds.LINE_WEATHER_SUNNY, WEIGHT_COMMON),
            new WeightedLine("weather_sunny_01", "Sun's out again.", BotDialogueSounds.LINE_WEATHER_SUNNY_01, WEIGHT_COMMON),
            new WeightedLine("weather_sunny_02", "Clear skies. Nice.", BotDialogueSounds.LINE_WEATHER_SUNNY_02, WEIGHT_COMMON),
            new WeightedLine("weather_sunny_03", "Finally, some sunshine.", BotDialogueSounds.LINE_WEATHER_SUNNY_03, WEIGHT_COMMON),
            new WeightedLine("weather_sunny_04", "Weather cleared up.", BotDialogueSounds.LINE_WEATHER_SUNNY_04, WEIGHT_COMMON),
            new WeightedLine("weather_sunny_05", "Good day for exploring.", BotDialogueSounds.LINE_WEATHER_SUNNY_05, WEIGHT_UNCOMMON),
            new WeightedLine("weather_sunny_06", "Much better without the rain.", BotDialogueSounds.LINE_WEATHER_SUNNY_06, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] WAKE_LINES = new WeightedLine[] {
            new WeightedLine("wake_snore_piglin", "You know you snore like a piglin?", BotDialogueSounds.LINE_WAKE_SNORE_PIGLIN, WEIGHT_COMMON),
            new WeightedLine("wake_dream_npc", "I had the strangest dream...", BotDialogueSounds.LINE_WAKE_DREAM_NPC, WEIGHT_COMMON),
            new WeightedLine("wake_good_rest", "A good night's rest.", BotDialogueSounds.LINE_WAKE_GOOD_REST, WEIGHT_COMMON),
            new WeightedLine("wake_seize_day", "Seize the day!", BotDialogueSounds.LINE_WAKE_SEIZE_DAY, WEIGHT_COMMON)
    };

    private static final WeightedLine[] COOK_LINES = new WeightedLine[] {
            new WeightedLine("cook_smells_good", "Something smells good.", BotDialogueSounds.LINE_COOK_SMELLS_GOOD, WEIGHT_COMMON),
            new WeightedLine("cook_dinner", "Is that dinner?", BotDialogueSounds.LINE_COOK_DINNER, WEIGHT_COMMON),
            new WeightedLine("cook_getting_hungry", "Now I'm getting hungry.", BotDialogueSounds.LINE_COOK_GETTING_HUNGRY, WEIGHT_COMMON),
            new WeightedLine("cook_like_home", "Smells like home.", BotDialogueSounds.LINE_COOK_LIKE_HOME, WEIGHT_UNCOMMON),
            new WeightedLine("cook_hot_meal", "Nothing beats a hot meal.", BotDialogueSounds.LINE_COOK_HOT_MEAL, WEIGHT_UNCOMMON),
            new WeightedLine("cook_save_some", "Save me some, will you?", BotDialogueSounds.LINE_COOK_SAVE_SOME, WEIGHT_UNCOMMON),
            new WeightedLine("cook_amazing", "That smells amazing.", BotDialogueSounds.LINE_COOK_AMAZING, WEIGHT_COMMON),
            new WeightedLine("cook_could_eat", "I could eat.", BotDialogueSounds.LINE_COOK_COULD_EAT, WEIGHT_COMMON)
    };

    static {
        TRIGGER_COOLDOWN_MS.put("random_idle_not_combat", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("in_high_threat_location", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("scary_sound_nearby", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("in_boat_not_combat", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("in_boat_deep_water", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("in_boat_dolphin_nearby", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("boat_breaks", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("standing_on_edge", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("safe_vista", COOLDOWN_180S_MS);
        TRIGGER_COOLDOWN_MS.put("falling_or_elytra", COOLDOWN_FALL_MS);
        TRIGGER_COOLDOWN_MS.put("random_ambient", COOLDOWN_META_MS);
        TRIGGER_COOLDOWN_MS.put("baby_zombie_on_chicken", COOLDOWN_MEME_MS);
        TRIGGER_COOLDOWN_MS.put("creeper_hiss", COOLDOWN_MEME_MS);
        TRIGGER_COOLDOWN_MS.put("world_start_or_milestone", COOLDOWN_MEME_MS);
        TRIGGER_COOLDOWN_MS.put("survive_near_death_or_totem", COOLDOWN_MEME_MS);
        TRIGGER_COOLDOWN_MS.put("lightning_at_night", COOLDOWN_MEME_MS);
        TRIGGER_COOLDOWN_MS.put("shelter_completion", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("combat_phase_hint", COOLDOWN_COMBATISH_MS);
        TRIGGER_COOLDOWN_MS.put("player_hurt", 60_000L);
        TRIGGER_COOLDOWN_MS.put("player_hungry", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("weather_change", 5L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("wake_up", COOLDOWN_MEME_MS);
        TRIGGER_COOLDOWN_MS.put("cooking_nearby", COOLDOWN_180S_MS);
        TRIGGER_COOLDOWN_MS.put("pig_staring", COOLDOWN_180S_MS);
        TRIGGER_COOLDOWN_MS.put("underground_mines", COOLDOWN_180S_MS);
        TRIGGER_COOLDOWN_MS.put("dig_straight_down", 60_000L);
        TRIGGER_COOLDOWN_MS.put("tree_punch_first", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("dirt_dig", COOLDOWN_90S_MS);
        TRIGGER_COOLDOWN_MS.put("follow_ack", 30_000L);
        TRIGGER_COOLDOWN_MS.put("stop_ack", 30_000L);
        TRIGGER_COOLDOWN_MS.put("commander_staring", COOLDOWN_META_MS);
        TRIGGER_COOLDOWN_MS.put("enderman_spotted", COOLDOWN_180S_MS);
    }

    private CompanionContextReactionService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || server.getTicks() % 20 != 0) {
            return;
        }

        Set<UUID> live = new HashSet<>();

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }

            UUID botId = bot.getUuid();
            live.add(botId);
            TriggerState state = STATE.computeIfAbsent(botId, ignored -> new TriggerState());

            // Spawn-grace: stay silent for the first few seconds so audio doesn't cut off while
            // the world is still loading in.
            long nowTick = server.getTicks();
            long firstSeen = FIRST_SEEN_TICK.computeIfAbsent(botId, ignored -> nowTick);
            if (nowTick - firstSeen < SPAWN_GRACE_TICKS) {
                continue;
            }

            List<Entity> hostiles = world.getOtherEntities(bot, bot.getBoundingBox().expand(14.0D), e -> e instanceof HostileEntity && e.isAlive());
            boolean inCombat = !hostiles.isEmpty();

            if (tryFreefall(bot, state)) {
                continue;
            }
            if (tryBoat(bot, world, state, inCombat)) {
                continue;
            }
            if (tryPrecipice(bot, world, state)) {
                continue;
            }
            if (tryVista(bot, world, state, inCombat)) {
                continue;
            }
            if (tryHighThreat(bot, world, state, hostiles)) {
                continue;
            }
            if (tryScary(bot, world, state)) {
                continue;
            }
            if (tryWeather(bot, world, state)) {
                continue;
            }
            if (tryCookingNearby(bot, world, state, inCombat)) {
                continue;
            }
            if (tryAmbient(bot, state, inCombat)) {
                continue;
            }
            if (tryMetaAndMemes(bot, world, state, inCombat, hostiles)) {
                continue;
            }
            if (tryPlayerHurt(bot, world, state)) {
                continue;
            }
            if (tryPlayerHungry(bot, world, state)) {
                continue;
            }
            if (tryPigStaring(bot, world, state)) {
                continue;
            }
            if (tryUnderground(bot, world, state)) {
                continue;
            }
            if (tryCommanderStaring(bot, world, state, nowTick)) {
                continue;
            }
            if (tryEndermanSpotted(bot, world, state)) {
                continue;
            }
        }

        STATE.keySet().retainAll(live);
        FIRST_SEEN_TICK.keySet().retainAll(live);
    }

    public static boolean playShelterCompletion(ServerPlayerEntity bot, String forcedLineId) {
        return tryTrigger(bot, "shelter_completion", SHELTER_LINES, forcedLineId, false);
    }

    /** Call from sleep/wake-up code when the bot wakes up from a bed. */
    public static boolean playWakeUp(ServerPlayerEntity bot) {
        return tryTrigger(bot, "wake_up", WAKE_LINES, null, false);
    }

    public static boolean debugTrigger(ServerPlayerEntity bot, String triggerKey, String lineId) {
        if (bot == null || triggerKey == null || triggerKey.isBlank()) {
            return false;
        }
        String key = triggerKey.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "random_idle_not_combat", "ambient" -> tryTrigger(bot, "random_idle_not_combat", AMBIENT_LINES, lineId, true);
            case "in_high_threat_location", "high_threat" -> tryTrigger(bot, "in_high_threat_location", HIGH_THREAT_LINES, lineId, true);
            case "scary_sound_nearby", "scary" -> tryTrigger(bot, "scary_sound_nearby", SCARY_LINES, lineId, true);
            case "in_boat_not_combat", "boat" -> tryTrigger(bot, "in_boat_not_combat", BOAT_NOT_COMBAT_LINES, lineId, true);
            case "in_boat_deep_water", "boat_deep" -> tryTrigger(bot, "in_boat_deep_water", BOAT_DEEP_WATER_LINES, lineId, true);
            case "in_boat_dolphin_nearby", "boat_dolphin" -> tryTrigger(bot, "in_boat_dolphin_nearby", BOAT_DOLPHIN_LINES, lineId, true);
            case "boat_breaks", "boat_break" -> tryTrigger(bot, "boat_breaks", BOAT_BREAK_LINES, lineId, true);
            case "standing_on_edge", "precipice" -> tryTrigger(bot, "standing_on_edge", PRECIPICE_LINES, lineId, true);
            case "safe_vista", "vista" -> tryTrigger(bot, "safe_vista", VISTA_LINES, lineId, true);
            case "falling_or_elytra", "freefall" -> tryTrigger(bot, "falling_or_elytra", FREEFALL_LINES, lineId, true);
            case "random_ambient", "meta" -> tryTrigger(bot, "random_ambient", META_LINES, lineId, true);
            case "baby_zombie_on_chicken", "meme_chicken" -> tryTrigger(bot, "baby_zombie_on_chicken", MEME_CHICKEN_LINES, lineId, true);
            case "creeper_hiss", "meme_creeper" -> tryTrigger(bot, "creeper_hiss", MEME_CREEPER_LINES, lineId, true);
            case "world_start_or_milestone", "meme_steve" -> tryTrigger(bot, "world_start_or_milestone", MEME_STEVE_LINES, lineId, true);
            case "survive_near_death_or_totem", "meme_technoblade" -> tryTrigger(bot, "survive_near_death_or_totem", MEME_TECHNOBLADE_LINES, lineId, true);
            case "lightning_at_night", "meme_herobrine" -> tryTrigger(bot, "lightning_at_night", MEME_HEROBRINE_LINES, lineId, true);
            case "shelter_completion", "shelter" -> playShelterCompletion(bot, lineId);
            case "player_hurt", "care_hurt" -> tryTrigger(bot, "player_hurt", PLAYER_HURT_LINES, lineId, true);
            case "player_hungry", "care_hungry" -> tryTrigger(bot, "player_hungry", PLAYER_HUNGRY_LINES, lineId, true);
            case "weather_change", "weather" -> tryTrigger(bot, "weather_change", WEATHER_RAIN_LINES, lineId, true);
            case "weather_rain" -> tryTrigger(bot, "weather_change", WEATHER_RAIN_LINES, lineId, true);
            case "weather_snow" -> tryTrigger(bot, "weather_change", WEATHER_SNOW_LINES, lineId, true);
            case "weather_thunder" -> tryTrigger(bot, "weather_change", WEATHER_THUNDER_LINES, lineId, true);
            case "weather_sunny" -> tryTrigger(bot, "weather_change", WEATHER_SUNNY_LINES, lineId, true);
            case "wake_up", "wake" -> tryTrigger(bot, "wake_up", WAKE_LINES, lineId, true);
            case "cooking_nearby", "cook" -> tryTrigger(bot, "cooking_nearby", COOK_LINES, lineId, true);
            default -> false;
        };
    }

    private static boolean tryFreefall(ServerPlayerEntity bot, TriggerState state) {
        boolean airborne = !bot.isOnGround() && !bot.isTouchingWater() && !bot.isSubmergedInWater();
        boolean fastDrop = airborne && bot.getVelocity().y < -0.85D && bot.fallDistance > 6.0F;
        // bot.isGliding() is the definitive elytra-flight check — fallDistance stays
        // near zero during level flight so the old heuristic never triggered.
        boolean elytraGliding = bot.isGliding()
                && bot.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        boolean freefall = fastDrop || elytraGliding;
        if (!freefall) {
            return false;
        }
        // Avoid yelling every second while continuously falling.
        return tryTrigger(bot, "falling_or_elytra", FREEFALL_LINES, null, false);
    }

    private static boolean tryBoat(ServerPlayerEntity bot, ServerWorld world, TriggerState state, boolean inCombat) {
        boolean inBoat = bot.getVehicle() instanceof BoatEntity;
        boolean triggered = false;

        if (inBoat && !inCombat) {
            if (RNG.nextDouble() < 0.12D && tryTrigger(bot, "in_boat_not_combat", BOAT_NOT_COMBAT_LINES, null, false)) {
                triggered = true;
            }
            if (!triggered && bot.getY() < 40.0D && RNG.nextDouble() < 0.22D
                    && tryTrigger(bot, "in_boat_deep_water", BOAT_DEEP_WATER_LINES, null, false)) {
                triggered = true;
            }
            if (!triggered) {
                Box dolphinBox = bot.getBoundingBox().expand(20.0D, 8.0D, 20.0D);
                boolean hasDolphin = !world.getEntitiesByClass(DolphinEntity.class, dolphinBox, d -> d != null && d.isAlive()).isEmpty();
                if (hasDolphin && RNG.nextDouble() < 0.28D
                        && tryTrigger(bot, "in_boat_dolphin_nearby", BOAT_DOLPHIN_LINES, null, false)) {
                    triggered = true;
                }
            }
        }

        if (!inBoat && state.wasInBoat) {
            boolean wetExit = bot.isTouchingWater() || bot.isSubmergedInWater();
            if (wetExit || RNG.nextDouble() < 0.50D) {
                tryTrigger(bot, "boat_breaks", BOAT_BREAK_LINES, null, false);
            }
        }
        state.wasInBoat = inBoat;
        return triggered;
    }

    private static boolean tryPrecipice(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (!bot.isOnGround() || bot.hasVehicle()) {
            return false;
        }
        UUID followTarget = BotEventHandler.getFollowTargetUuid(bot);
        if (followTarget != null) {
            ServerPlayerEntity commander = world.getServer().getPlayerManager().getPlayer(followTarget);
            if (commander != null && commander.isAlive() && !commander.isRemoved()) {
                double yGap = Math.abs(commander.getY() - bot.getY());
                if (yGap >= 4.0D) {
                    return false;
                }
            }
        }
        if (!hasBigDropAhead(bot, world, 8)) {
            return false;
        }
        if (RNG.nextDouble() > 0.35D) {
            return false;
        }
        return tryTrigger(bot, "standing_on_edge", PRECIPICE_LINES, null, false);
    }

    private static boolean tryVista(ServerPlayerEntity bot, ServerWorld world, TriggerState state, boolean inCombat) {
        if (inCombat || bot.hasVehicle()) {
            return false;
        }
        if (!world.isSkyVisible(bot.getBlockPos().up())) {
            return false;
        }
        if (world.isRaining() || world.isThundering()) {
            return false;
        }
        if (bot.getY() < 95.0D) {
            return false;
        }
        if (RNG.nextDouble() > 0.14D) {
            return false;
        }
        return tryTrigger(bot, "safe_vista", VISTA_LINES, null, false);
    }

    private static boolean tryHighThreat(ServerPlayerEntity bot, ServerWorld world, TriggerState state, List<Entity> hostiles) {
        if (!isHighThreatLocation(bot, world, hostiles)) {
            return false;
        }
        // Suppress if a dimension-handoff overhead line was already shown recently.
        if (CompanionOverheadDialogueService.isRecentlyShown(bot.getUuid())) {
            return false;
        }
        if (RNG.nextDouble() > 0.16D) {
            return false;
        }
        return tryTrigger(bot, "in_high_threat_location", HIGH_THREAT_LINES, null, false);
    }

    private static boolean tryScary(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (!isScaryNearby(bot, world)) {
            return false;
        }
        if (RNG.nextDouble() > 0.18D) {
            return false;
        }
        return tryTrigger(bot, "scary_sound_nearby", SCARY_LINES, null, false);
    }

    private static boolean tryAmbient(ServerPlayerEntity bot, TriggerState state, boolean inCombat) {
        if (inCombat || bot.hasVehicle()) {
            return false;
        }
        if (RNG.nextDouble() > 0.04D) {
            return false;
        }
        return tryTrigger(bot, "random_idle_not_combat", AMBIENT_LINES, null, false);
    }

    private static boolean tryWeather(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (!world.getDimension().hasSkyLight()) {
            return false;
        }
        if (!world.isSkyVisible(bot.getBlockPos().up())) {
            return false;
        }

        int kindNow = computeWeatherKind(world, bot.getBlockPos());
        int kindPrev = state.lastWeatherKind;

        if (kindPrev == -1) {
            state.lastWeatherKind = kindNow;
            return false;
        }
        if (kindNow == kindPrev) {
            return false;
        }
        state.lastWeatherKind = kindNow;

        WeightedLine[] pool = switch (kindNow) {
            case 3 -> WEATHER_THUNDER_LINES;
            case 2 -> WEATHER_SNOW_LINES;
            case 1 -> WEATHER_RAIN_LINES;
            default -> (kindPrev != 0 && RNG.nextFloat() < 0.70f) ? WEATHER_SUNNY_LINES : null;
        };
        if (pool == null) {
            return false;
        }
        return tryTrigger(bot, "weather_change", pool, null, false);
    }

    private static int computeWeatherKind(ServerWorld world, BlockPos pos) {
        if (world.isThundering()) {
            return 3;
        }
        if (!world.isRaining()) {
            return 0;
        }
        try {
            if (pos != null && world.getBiome(pos).value().getTemperature() < 0.15f) {
                return 2;
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    private static boolean tryCookingNearby(ServerPlayerEntity bot, ServerWorld world, TriggerState state, boolean inCombat) {
        if (inCombat) {
            return false;
        }
        BlockPos botPos = bot.getBlockPos();
        boolean nearCooking = false;
        for (int dx = -4; dx <= 4 && !nearCooking; dx++) {
            for (int dy = -2; dy <= 2 && !nearCooking; dy++) {
                for (int dz = -4; dz <= 4 && !nearCooking; dz++) {
                    BlockPos check = botPos.add(dx, dy, dz);
                    var blockState = world.getBlockState(check);
                    var block = blockState.getBlock();
                    if (block instanceof net.minecraft.block.CampfireBlock
                            && blockState.get(net.minecraft.block.CampfireBlock.LIT)) {
                        nearCooking = true;
                    } else if (block instanceof net.minecraft.block.AbstractFurnaceBlock
                            && blockState.get(net.minecraft.block.AbstractFurnaceBlock.LIT)) {
                        nearCooking = true;
                    }
                }
            }
        }
        if (!nearCooking) {
            return false;
        }
        if (RNG.nextDouble() > 0.10D) {
            return false;
        }
        return tryTrigger(bot, "cooking_nearby", COOK_LINES, null, false);
    }

    private static boolean tryMetaAndMemes(ServerPlayerEntity bot,
                                           ServerWorld world,
                                           TriggerState state,
                                           boolean inCombat,
                                           List<Entity> hostiles) {
        boolean lowHealth = bot.getHealth() <= 4.0F;

        // Very rare meme lines first (contextual).
        if (hasChickenJockeyNearby(bot, world) && tryTrigger(bot, "baby_zombie_on_chicken", MEME_CHICKEN_LINES, null, false)) {
            state.wasLowHealth = lowHealth;
            return true;
        }

        if (hasHissingCreeperNearby(bot, hostiles) && tryTrigger(bot, "creeper_hiss", MEME_CREEPER_LINES, null, false)) {
            state.wasLowHealth = lowHealth;
            return true;
        }

        if (lowHealth && !state.wasLowHealth && tryTrigger(bot, "survive_near_death_or_totem", MEME_TECHNOBLADE_LINES, null, false)) {
            state.wasLowHealth = true;
            return true;
        }

        if (isNightLightningWeather(world) && RNG.nextDouble() < 0.05D
                && tryTrigger(bot, "lightning_at_night", MEME_HEROBRINE_LINES, null, false)) {
            state.wasLowHealth = lowHealth;
            return true;
        }

        if (!inCombat && RNG.nextDouble() < 0.00075D
                && tryTrigger(bot, "world_start_or_milestone", MEME_STEVE_LINES, null, false)) {
            state.wasLowHealth = lowHealth;
            return true;
        }

        if (!inCombat && RNG.nextDouble() < 0.008D
                && tryTrigger(bot, "random_ambient", META_LINES, null, false)) {
            state.wasLowHealth = lowHealth;
            return true;
        }

        state.wasLowHealth = lowHealth;
        return false;
    }

    private static boolean tryTrigger(ServerPlayerEntity bot,
                                      String triggerKey,
                                      WeightedLine[] pool,
                                      String forcedLineId,
                                      boolean debugPath) {
        if (bot == null || triggerKey == null || pool == null || pool.length == 0) {
            return false;
        }
        UUID botId = bot.getUuid();

        // Don't overwrite a line recently shown by another system (cooking, food-giving, etc.).
        if (CompanionOverheadDialogueService.isRecentlyShown(botId)) {
            return false;
        }

        TriggerState state = STATE.computeIfAbsent(botId, ignored -> new TriggerState());

        long now = System.currentTimeMillis();
        long cooldown = TRIGGER_COOLDOWN_MS.getOrDefault(triggerKey, COOLDOWN_90S_MS);
        long last = state.lastTriggerMs.getOrDefault(triggerKey, 0L);
        if (forcedLineId == null && now - last < cooldown) {
            return false;
        }

        String lastLineId = state.lastLineByTrigger.get(triggerKey);
        WeightedLine line = pickWeightedLine(pool, forcedLineId, lastLineId);
        if (line == null) {
            return false;
        }

        state.lastTriggerMs.put(triggerKey, now);
        state.lastLineByTrigger.put(triggerKey, line.id);
        CompanionOverheadDialogueService.showOverheadLine(bot, line.text, 3_000, 48.0, "context", triggerKey);

        BotDialoguePlayer.PlayResult result = BotDialoguePlayer.playSoundForBotDetailed(bot, line.sound);
        if (result == BotDialoguePlayer.PlayResult.PLAYED || result == BotDialoguePlayer.PlayResult.THROTTLED) {
            return true;
        }

        ChatUtils.sendChatMessages(
                bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                line.text,
                true
        );
        return true;
    }

    private static WeightedLine pickWeightedLine(WeightedLine[] pool, String forcedLineId, String lastLineId) {
        if (pool == null || pool.length == 0) {
            return null;
        }
        if (forcedLineId != null && !forcedLineId.isBlank()) {
            for (WeightedLine line : pool) {
                if (line != null && forcedLineId.equalsIgnoreCase(line.id)) {
                    return line;
                }
            }
            return null;
        }

        List<WeightedLine> candidates = new ArrayList<>();
        for (WeightedLine line : pool) {
            if (line == null) {
                continue;
            }
            if (lastLineId != null && !lastLineId.isBlank() && lastLineId.equalsIgnoreCase(line.id)) {
                continue;
            }
            candidates.add(line);
        }
        if (candidates.isEmpty()) {
            for (WeightedLine line : pool) {
                if (line != null) {
                    candidates.add(line);
                }
            }
        }

        int total = 0;
        for (WeightedLine line : candidates) {
            total += line.weight;
        }
        if (total <= 0) {
            return null;
        }

        int r = RNG.nextInt(total);
        int run = 0;
        for (WeightedLine line : candidates) {
            run += line.weight;
            if (r < run) {
                return line;
            }
        }
        return candidates.getLast();
    }

    private static boolean isHighThreatLocation(ServerPlayerEntity bot, ServerWorld world, List<Entity> hostiles) {
        if (world == null || bot == null) {
            return false;
        }

        String dim = world.getRegistryKey().getValue().toString();
        if (dim.contains("the_nether") || dim.contains("the_end")) {
            return true;
        }

        if (hostiles != null && hostiles.size() >= 3) {
            return true;
        }

        RegistryEntry<Biome> biomeEntry = world.getBiome(bot.getBlockPos());
        if (biomeEntry != null) {
            String biomeKey = biomeEntry.getKey().map(k -> k.getValue().toString()).orElse("").toLowerCase(Locale.ROOT);
            if (biomeKey.contains("deep_dark") || biomeKey.contains("ancient_city") || biomeKey.contains("soul_sand_valley")) {
                return true;
            }
        }

        return false;
    }

    private static boolean isScaryNearby(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }

        Box close = bot.getBoundingBox().expand(16.0D, 8.0D, 16.0D);
        if (!world.getEntitiesByClass(WardenEntity.class, close, w -> w != null && w.isAlive()).isEmpty()) {
            return true;
        }

        for (Entity entity : world.getOtherEntities(bot, close, e -> e instanceof CreeperEntity && e.isAlive())) {
            if (entity instanceof CreeperEntity creeper && creeper.isIgnited()) {
                return true;
            }
        }

        if (!world.isSkyVisible(bot.getBlockPos().up()) && world.getLightLevel(bot.getBlockPos()) < 5) {
            return true;
        }

        return false;
    }

    private static boolean hasBigDropAhead(ServerPlayerEntity bot, ServerWorld world, int minDrop) {
        float yaw = bot.getYaw();
        double yawRad = Math.toRadians(yaw);
        int dx = (int) Math.round(-Math.sin(yawRad) * 2.0D);
        int dz = (int) Math.round(Math.cos(yawRad) * 2.0D);
        BlockPos ahead = bot.getBlockPos().add(dx, 0, dz);
        if (!world.isChunkLoaded(ahead)) {
            return false;
        }

        int airDepth = 0;
        for (int y = ahead.getY() - 1; y >= ahead.getY() - 24; y--) {
            BlockPos p = new BlockPos(ahead.getX(), y, ahead.getZ());
            if (!world.isChunkLoaded(p)) {
                break;
            }
            if (world.getBlockState(p).isAir()) {
                airDepth++;
            } else {
                break;
            }
        }
        return airDepth >= minDrop;
    }

    private static boolean hasChickenJockeyNearby(ServerPlayerEntity bot, ServerWorld world) {
        Box box = bot.getBoundingBox().expand(24.0D, 12.0D, 24.0D);
        List<Entity> zombies = world.getOtherEntities(bot, box, e -> e != null && e.getType() == EntityType.ZOMBIE && e.isAlive());
        for (Entity zombie : zombies) {
            if (!(zombie instanceof net.minecraft.entity.mob.ZombieEntity z) || !z.isBaby()) {
                continue;
            }
            if (z.getVehicle() instanceof ChickenEntity) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHissingCreeperNearby(ServerPlayerEntity bot, List<Entity> hostiles) {
        if (hostiles == null) {
            return false;
        }
        for (Entity e : hostiles) {
            if (e instanceof CreeperEntity creeper && creeper.isIgnited()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNightLightningWeather(ServerWorld world) {
        if (world == null || !world.isThundering()) {
            return false;
        }
        long time = Math.floorMod(world.getTimeOfDay(), 24_000L);
        return time >= 13_000L && time <= 23_000L;
    }

    // ---- Player-care triggers ----

    private static boolean tryPlayerHurt(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        ServerPlayerEntity commander = findNearbyCommander(bot, world, 24.0);
        if (commander == null) return false;
        boolean lowHealth = commander.getHealth() <= Math.max(6.0f, commander.getMaxHealth() * 0.30f);
        if (!lowHealth) {
            state.wasCommanderLowHealth = false;
            return false;
        }
        if (state.wasCommanderLowHealth) return false; // already reacted this bout
        state.wasCommanderLowHealth = true;
        if (RNG.nextDouble() > 0.70D) return false;
        return tryTrigger(bot, "player_hurt", PLAYER_HURT_LINES, null, false);
    }

    private static boolean tryPlayerHungry(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        ServerPlayerEntity commander = findNearbyCommander(bot, world, 24.0);
        if (commander == null) return false;
        HungerManager hunger = commander.getHungerManager();
        boolean isHungry = hunger.getFoodLevel() <= 6;
        if (!isHungry) {
            state.wasCommanderHungry = false;
            return false;
        }
        if (state.wasCommanderHungry) return false; // already reacted this bout
        state.wasCommanderHungry = true;
        if (RNG.nextDouble() > 0.65D) return false;
        return tryTrigger(bot, "player_hungry", PLAYER_HUNGRY_LINES, null, false);
    }

    private static ServerPlayerEntity findNearbyCommander(ServerPlayerEntity bot, ServerWorld world, double maxRange) {
        if (bot == null || world == null) return null;
        BotCommandStateService.State st = BotCommandStateService.stateFor(bot.getUuid());
        UUID commanderUuid = st != null ? st.followTargetUuid : null;
        if (commanderUuid == null) return null;
        ServerPlayerEntity commander = world.getServer().getPlayerManager().getPlayer(commanderUuid);
        if (commander == null || commander.isRemoved() || !commander.isAlive()) return null;
        if (commander.squaredDistanceTo(bot) > maxRange * maxRange) return null;
        return commander;
    }

    // ---- April 2026 backlog triggers ----

    private static boolean tryPigStaring(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        Box box = bot.getBoundingBox().expand(6.0D, 3.0D, 6.0D);
        List<Entity> pigs = world.getOtherEntities(bot, box,
                e -> e != null && e.getType() == EntityType.PIG && e.isAlive());
        if (pigs.isEmpty()) return false;
        for (Entity pig : pigs) {
            if (isEntityFacing(pig, bot)) {
                if (RNG.nextDouble() < 0.015D) {
                    return tryTrigger(bot, "pig_staring", PIG_STARING_LINES, null, false);
                }
                break;
            }
        }
        return false;
    }

    private static boolean tryUnderground(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        if (!world.getDimension().hasSkyLight()) return false;
        if (bot.getY() >= 40.0D) return false;
        if (world.isSkyVisible(bot.getBlockPos().up())) return false;
        if (RNG.nextDouble() > 0.012D) return false;
        return tryTrigger(bot, "underground_mines", UNDERGROUND_LINES, null, false);
    }

    /** Fires enderman_spotted_dont_look when a live enderman is within 16 blocks
     *  and roughly in the bot's forward cone. Cooldown-throttled to 3 min so the
     *  bot doesn't spam the line while near a nest. */
    private static boolean tryEndermanSpotted(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        Box box = bot.getBoundingBox().expand(16.0D, 8.0D, 16.0D);
        List<EndermanEntity> endermen = world.getEntitiesByClass(
                EndermanEntity.class, box, e -> e != null && e.isAlive());
        if (endermen.isEmpty()) return false;
        boolean anyInForwardCone = false;
        for (EndermanEntity end : endermen) {
            if (isEntityFacing(bot, end)) {
                anyInForwardCone = true;
                break;
            }
        }
        if (!anyInForwardCone) return false;
        if (RNG.nextDouble() > 0.06D) return false;
        return tryTrigger(bot, "enderman_spotted", ENDERMAN_SPOTTED_LINES, null, false);
    }

    /** Fires meta_stop_looking only after the commander has been staring at the
     *  bot continuously for ≥ 5 seconds (≥ 100 server ticks). */
    private static boolean tryCommanderStaring(ServerPlayerEntity bot, ServerWorld world, TriggerState state, long nowTick) {
        ServerPlayerEntity commander = findNearbyCommander(bot, world, 12.0);
        if (commander == null || !isEntityFacing(commander, bot)) {
            state.commanderStareStartTick = -1L;
            return false;
        }
        if (state.commanderStareStartTick < 0L) {
            state.commanderStareStartTick = nowTick;
            return false;
        }
        long staredTicks = nowTick - state.commanderStareStartTick;
        if (staredTicks < 100L) return false;  // 5 s at 20 TPS
        if (RNG.nextDouble() > 0.15D) return false;
        return tryTrigger(bot, "commander_staring", COMMANDER_STARING_LINES, null, false);
    }

    private static boolean isEntityFacing(Entity source, Entity target) {
        if (source == null || target == null) return false;
        Vec3d toTarget = target.getEntityPos().subtract(source.getEntityPos());
        double len = toTarget.length();
        if (len < 0.0001) return false;
        toTarget = toTarget.multiply(1.0 / len);
        double yawRad = Math.toRadians(source.getYaw());
        Vec3d forward = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
        return toTarget.dotProduct(forward) > 0.85D;
    }

    /**
     * Called from the server-wide block-break hook. Fans out to dig-down, tree-punch,
     * and dirt-dig reactions when the broken block matches.
     */
    public static void onBotBlockBreak(ServerPlayerEntity bot, ServerWorld world, BlockPos pos, BlockState state) {
        if (bot == null || world == null || pos == null || state == null) return;
        if (!BotEventHandler.isRegisteredBot(bot)) return;

        BlockPos feet = bot.getBlockPos();
        if (pos.getX() == feet.getX() && pos.getZ() == feet.getZ() && pos.getY() == feet.getY() - 1) {
            if (RNG.nextDouble() < 0.35D) {
                tryTrigger(bot, "dig_straight_down", DIG_DOWN_LINES, null, false);
            }
            return;
        }

        if (state.isIn(BlockTags.LOGS)) {
            if (RNG.nextDouble() < 0.30D) {
                tryTrigger(bot, "tree_punch_first", TREE_PUNCH_LINES, null, false);
            }
            return;
        }

        if (state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.ROOTED_DIRT)
                || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.PODZOL)
                || state.isOf(Blocks.MYCELIUM)) {
            if (RNG.nextDouble() < 0.08D) {
                tryTrigger(bot, "dirt_dig", DIRT_DIG_LINES, null, false);
            }
        }
    }

    /** Call from /bot follow to emit a voice ack. Cooldown-throttled; safe to call often. */
    public static boolean playFollowAck(ServerPlayerEntity bot) {
        if (bot == null) return false;
        return tryTrigger(bot, "follow_ack", FOLLOW_ACK_LINES, null, false);
    }

    /** Call from /bot follow stop and /bot stay to emit a voice ack. Cooldown-throttled. */
    public static boolean playStopAck(ServerPlayerEntity bot) {
        if (bot == null) return false;
        return tryTrigger(bot, "stop_ack", STOP_ACK_LINES, null, false);
    }
}
