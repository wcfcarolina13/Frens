package net.wcfcarolina13.ChatUtils;

import net.minecraft.sound.SoundEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Maps chat text content to the appropriate dialogue sound event.
 * 
 * <p>This class handles the text-to-sound lookup including:
 * <ul>
 *   <li>Exact text matching for static lines</li>
 *   <li>Pattern matching for lines with dynamic player names</li>
 *   <li>Text normalization (ellipsis, em-dash, etc.)</li>
 * </ul>
 * 
 * <p>Lines containing dynamic data (coordinates, biome, time, weather) do not
 * have pre-generated audio and will return null.
 */
public final class DialogueTextMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("dialogue-mapper");

    // Use LinkedHashMap to maintain insertion order for pattern matching priority
    private static final Map<Pattern, SoundEvent> PATTERN_MAP = new LinkedHashMap<>();
    private static final Map<String, SoundEvent> EXACT_MAP = new LinkedHashMap<>();
    /** Reverse map: SoundEvent identifier → first matching display text. */
    private static final Map<String, String> SOUND_TO_TEXT = new java.util.HashMap<>();

    static {
        initializeMappings();
    }

    private DialogueTextMapper() {
    }

    /**
     * Look up the sound event for a given chat message.
     * 
     * @param text The chat message text
     * @return The matching SoundEvent, or null if no match found
     */
    public static SoundEvent lookup(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String normalized = normalize(text);

        // Try exact match first
        SoundEvent exact = EXACT_MAP.get(normalized);
        if (exact != null) {
            LOGGER.debug("Exact match for '{}' -> {}", normalized, exact.id());
            return exact;
        }

        // Try pattern matching
        for (Map.Entry<Pattern, SoundEvent> entry : PATTERN_MAP.entrySet()) {
            if (entry.getKey().matcher(normalized).matches()) {
                LOGGER.debug("Pattern match for '{}' -> {}", normalized, entry.getValue().id());
                return entry.getValue();
            }
        }

        LOGGER.debug("No sound match for: '{}'", normalized);
        return null;
    }

    /**
     * Normalize text for matching:
     * - Convert ellipsis character (…) to three dots (...)
     * - Convert em-dash (—) to hyphen (-)
     * - Convert curly quotes to straight quotes
     * - Trim whitespace
     * - Normalize multiple spaces to single space
     */
    private static String normalize(String text) {
        return text
                .replace("…", "...")
                .replace("—", "-")
                .replace("\u2019", "'")  // Right single quotation mark
                .replace("\u2018", "'")  // Left single quotation mark
                .replace("\u201C", "\"") // Left double quotation mark
                .replace("\u201D", "\"") // Right double quotation mark
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Create a pattern that matches text with any player name in place of {name}.
     */
    private static Pattern patternWithName(String template) {
        // First, replace {name} with a placeholder to protect it during escaping
        String placeholder = "___PLAYER_NAME___";
        String withPlaceholder = template.replace("{name}", placeholder);
        
        // Normalize special characters first
        withPlaceholder = withPlaceholder.replace("…", "...").replace("—", "-");
        
        // Escape regex special characters
        String escaped = withPlaceholder
                .replace(".", "\\.")
                .replace("?", "\\?")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("'", "'");  // Normalize apostrophes
        
        // Replace placeholder with a pattern that matches any word characters (player names)
        String pattern = escaped.replace(placeholder, "[\\w]+");
        
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
    }

    private static void initializeMappings() {
        // ============ TOUCH RESPONSES ============
        // Simple lines (exact match)
        EXACT_MAP.put("Hmm?", BotDialogueSounds.LINE_TOUCH_HMM);
        EXACT_MAP.put("Yeah?", BotDialogueSounds.LINE_TOUCH_YEAH);
        EXACT_MAP.put("What do you need?", BotDialogueSounds.LINE_TOUCH_WHAT_NEED);
        // Pattern with name
        PATTERN_MAP.put(patternWithName("Need something, {name}?"), BotDialogueSounds.LINE_TOUCH_NEED_SOMETHING);

        // ============ SKILL: FISHING ============
        PATTERN_MAP.put(patternWithName("Shh... I'm watching the bobber, {name}."), BotDialogueSounds.LINE_SKILL_FISHING_WATCHING);
        EXACT_MAP.put("If the fish are biting today, I'm not complaining.", BotDialogueSounds.LINE_SKILL_FISHING_BITING);
        EXACT_MAP.put("Got a good feeling about this spot.", BotDialogueSounds.LINE_SKILL_FISHING_GOOD_SPOT);
        EXACT_MAP.put("Fishing session finished.", BotDialogueSounds.LINE_SKILL_FISH_SESSION_DONE);
        EXACT_MAP.put("Sun has set. Stopping fishing.", BotDialogueSounds.LINE_SKILL_FISH_SUNSET);

        // ============ SKILL: WOODCUTTING ============
        EXACT_MAP.put("Careful-falling logs. I've got this.", BotDialogueSounds.LINE_SKILL_WOODCUT_CAREFUL);
        EXACT_MAP.put("Just tidying up the tree line.", BotDialogueSounds.LINE_SKILL_WOODCUT_TIDYING);
        EXACT_MAP.put("A few more swings and this one's down.", BotDialogueSounds.LINE_SKILL_WOODCUT_FEW_MORE);

        // ============ SKILL: HANGOUT ============
        EXACT_MAP.put("Just warming up by the fire.", BotDialogueSounds.LINE_SKILL_HANGOUT_FIRE);
        EXACT_MAP.put("Nice and quiet out here.", BotDialogueSounds.LINE_SKILL_HANGOUT_NICE_QUIET);
        EXACT_MAP.put("Taking a breather before the next job.", BotDialogueSounds.LINE_SKILL_HANGOUT_BREATHER);
        EXACT_MAP.put("I'll hang out by the fire for a bit.", BotDialogueSounds.LINE_SKILL_HANGOUT_FIRE_BIT);
        EXACT_MAP.put("I'll hang out for a bit.", BotDialogueSounds.LINE_SKILL_HANGOUT_BIT);
        EXACT_MAP.put("Taking a short breather.", BotDialogueSounds.LINE_SKILL_HANGOUT_SHORT_BREATHER);

        // ============ SKILL: SLEEP ============
        EXACT_MAP.put("I'm trying to get some sleep.", BotDialogueSounds.LINE_SKILL_SLEEP_TRYING);
        EXACT_MAP.put("Give me a second-finding a bed.", BotDialogueSounds.LINE_SKILL_SLEEP_FINDING_BED);
        EXACT_MAP.put("Long day. Time to sleep.", BotDialogueSounds.LINE_SKILL_SLEEP_LONG_DAY);

        // ============ WAKE-UP (after sleeping near player) ============
        EXACT_MAP.put("You know you snore like a piglin?", BotDialogueSounds.LINE_WAKE_SNORE_PIGLIN);
        EXACT_MAP.put("I had the strangest dream that I was an NPC in a video game.", BotDialogueSounds.LINE_WAKE_DREAM_NPC);
        EXACT_MAP.put("A good night's rest.", BotDialogueSounds.LINE_WAKE_GOOD_REST);
        EXACT_MAP.put("Seize the day!", BotDialogueSounds.LINE_WAKE_SEIZE_DAY);

        // ============ COOKING REACTION (food taken from furnace/smoker/campfire) ============
        EXACT_MAP.put("Something smells good.", BotDialogueSounds.LINE_COOK_SMELLS_GOOD);
        EXACT_MAP.put("Is that dinner?", BotDialogueSounds.LINE_COOK_DINNER);
        EXACT_MAP.put("Now I'm getting hungry.", BotDialogueSounds.LINE_COOK_GETTING_HUNGRY);
        EXACT_MAP.put("Smells like home.", BotDialogueSounds.LINE_COOK_LIKE_HOME);
        EXACT_MAP.put("Nothing beats a hot meal.", BotDialogueSounds.LINE_COOK_HOT_MEAL);
        EXACT_MAP.put("Save me some, will you?", BotDialogueSounds.LINE_COOK_SAVE_SOME);
        EXACT_MAP.put("That smells amazing.", BotDialogueSounds.LINE_COOK_AMAZING);
        EXACT_MAP.put("I could eat.", BotDialogueSounds.LINE_COOK_COULD_EAT);

        // ============ REACTION: PLAYER EATS ROTTEN FLESH ============
        EXACT_MAP.put("Did you really just eat that?", BotDialogueSounds.LINE_REACT_ROTTEN_REALLY);
        EXACT_MAP.put("That's... rotten. You know that, right?", BotDialogueSounds.LINE_REACT_ROTTEN_KNOW_THAT);
        EXACT_MAP.put("I can smell it from here.", BotDialogueSounds.LINE_REACT_ROTTEN_SMELL);
        EXACT_MAP.put("Your stomach is braver than mine.", BotDialogueSounds.LINE_REACT_ROTTEN_BRAVE);

        // ============ REACTION: PLAYER EATS SUSPICIOUS STEW ============
        EXACT_MAP.put("That stew looks... suspicious.", BotDialogueSounds.LINE_REACT_STEW_SUSPICIOUS);
        EXACT_MAP.put("You sure about that stew?", BotDialogueSounds.LINE_REACT_STEW_SURE);
        EXACT_MAP.put("I wouldn't trust that if I were you.", BotDialogueSounds.LINE_REACT_STEW_WOULDNT_TRUST);
        EXACT_MAP.put("Bold choice with the mystery stew.", BotDialogueSounds.LINE_REACT_STEW_BOLD);

        // ============ FOOD GIVING: ACCEPT ============
        EXACT_MAP.put("Thanks, I needed that.", BotDialogueSounds.LINE_FOOD_ACCEPT_NEEDED);
        EXACT_MAP.put("Don't mind if I do.", BotDialogueSounds.LINE_FOOD_ACCEPT_DONT_MIND);
        EXACT_MAP.put("Much appreciated.", BotDialogueSounds.LINE_FOOD_ACCEPT_APPRECIATED);
        EXACT_MAP.put("Perfect timing.", BotDialogueSounds.LINE_FOOD_ACCEPT_TIMING);

        // ============ FOOD GIVING: REFUSE ============
        EXACT_MAP.put("I'm good, thanks.", BotDialogueSounds.LINE_FOOD_REFUSE_GOOD);
        EXACT_MAP.put("Maybe later.", BotDialogueSounds.LINE_FOOD_REFUSE_LATER);
        EXACT_MAP.put("Not hungry right now.", BotDialogueSounds.LINE_FOOD_REFUSE_NOT_HUNGRY);
        EXACT_MAP.put("I'll pass for now.", BotDialogueSounds.LINE_FOOD_REFUSE_PASS);

        // ============ MODE: FOLLOW ============
        PATTERN_MAP.put(patternWithName("I'm right behind you, {name}."), BotDialogueSounds.LINE_MODE_FOLLOW_BEHIND);
        PATTERN_MAP.put(patternWithName("Now following {name}."), BotDialogueSounds.LINE_MODE_FOLLOW_KEEPING_UP);
        PATTERN_MAP.put(patternWithName("Now following {name} at a distance."), BotDialogueSounds.LINE_MODE_FOLLOW_KEEPING_UP);
        EXACT_MAP.put("Lead the way.", BotDialogueSounds.LINE_MODE_FOLLOW_LEAD);
        EXACT_MAP.put("Keeping up.", BotDialogueSounds.LINE_MODE_FOLLOW_KEEPING_UP);

        // ============ MODE: GUARD ============
        EXACT_MAP.put("All clear. I'm keeping watch.", BotDialogueSounds.LINE_MODE_GUARD_CLEAR);
        EXACT_MAP.put("Guard duty. Nothing gets past me.", BotDialogueSounds.LINE_MODE_GUARD_NOTHING_PAST);
        EXACT_MAP.put("Quiet so far.", BotDialogueSounds.LINE_MODE_GUARD_QUIET);
        // Dynamic guard radius messages use guard clear sound
        PATTERN_MAP.put(Pattern.compile("Guarding this area \\(radius [\\d.]+ blocks\\)\\."), BotDialogueSounds.LINE_MODE_GUARD_CLEAR);

        // ============ MODE: PATROL ============
        EXACT_MAP.put("Doing my rounds.", BotDialogueSounds.LINE_MODE_PATROL_ROUNDS);
        EXACT_MAP.put("Patrolling the area.", BotDialogueSounds.LINE_MODE_PATROL_AREA);
        EXACT_MAP.put("Just checking the perimeter.", BotDialogueSounds.LINE_MODE_PATROL_PERIMETER);
        // Dynamic patrol radius messages use patrol area sound
        PATTERN_MAP.put(Pattern.compile("Patrolling this area \\(radius [\\d.]+ blocks\\)\\."), BotDialogueSounds.LINE_MODE_PATROL_AREA);

        // ============ MODE: RETURNING BASE ============
        EXACT_MAP.put("Heading home before it gets too dark.", BotDialogueSounds.LINE_MODE_RETURN_HEADING);
        EXACT_MAP.put("On my way back to base.", BotDialogueSounds.LINE_MODE_RETURN_ON_WAY);
        EXACT_MAP.put("Returning to base.", BotDialogueSounds.LINE_MODE_RETURN_RETURNING);

        // ============ MODE: STAY ============
        EXACT_MAP.put("Holding position.", BotDialogueSounds.LINE_MODE_STAY_HOLDING);
        EXACT_MAP.put("I'll stay right here.", BotDialogueSounds.LINE_MODE_STAY_RIGHT_HERE);
        EXACT_MAP.put("Standing by.", BotDialogueSounds.LINE_MODE_STAY_STANDING_BY);

        // ============ MODE: IDLE ============
        EXACT_MAP.put("Just taking it easy.", BotDialogueSounds.LINE_IDLE_TAKING_IT_EASY);
        EXACT_MAP.put("Nothing urgent-I'm here if you need me.", BotDialogueSounds.LINE_IDLE_HERE_IF_NEEDED);
        EXACT_MAP.put("I'm here if you need me.", BotDialogueSounds.LINE_IDLE_HERE_IF_NEEDED);
        EXACT_MAP.put("Enjoying the calm.", BotDialogueSounds.LINE_IDLE_ENJOYING_CALM);

        // ============ GREETINGS ============
        PATTERN_MAP.put(patternWithName("Hey, {name}."), BotDialogueSounds.LINE_GREETING_HEY);
        PATTERN_MAP.put(patternWithName("Oh-{name}, there you are."), BotDialogueSounds.LINE_GREETING_THERE_YOU_ARE);
        PATTERN_MAP.put(patternWithName("Good to see you again, {name}."), BotDialogueSounds.LINE_GREETING_GOOD_TO_SEE);
        PATTERN_MAP.put(patternWithName("Welcome back, {name}."), BotDialogueSounds.LINE_GREETING_WELCOME_BACK);

        // ============ STATUS: HUNGER ============
        EXACT_MAP.put("I'm getting hungry...", BotDialogueSounds.LINE_STATUS_HUNGRY);
        EXACT_MAP.put("We should find food soon.", BotDialogueSounds.LINE_STATUS_FIND_FOOD);
        EXACT_MAP.put("My stomach says it's snack o'clock.", BotDialogueSounds.LINE_STATUS_SNACK_TIME);

        // ============ STATUS: HEALTH ============
        EXACT_MAP.put("I could use a breather.", BotDialogueSounds.LINE_STATUS_NEED_BREATHER);
        EXACT_MAP.put("Not feeling my best right now.", BotDialogueSounds.LINE_STATUS_NOT_BEST);
        EXACT_MAP.put("I've taken a few too many hits today.", BotDialogueSounds.LINE_STATUS_TOO_MANY_HITS);

        // ============ IDLE REMARKS ============
        PATTERN_MAP.put(patternWithName("All quiet around here, {name}."), BotDialogueSounds.LINE_IDLE_ALL_QUIET);
        EXACT_MAP.put("Still standing. Still ready.", BotDialogueSounds.LINE_IDLE_STILL_STANDING);

        // ============ CONTEXT LINES ============
        // Hunger context
        EXACT_MAP.put("I could really use a snack soon.", BotDialogueSounds.LINE_STATUS_HUNGRY);
        EXACT_MAP.put("Getting hungry... might want to restock food.", BotDialogueSounds.LINE_STATUS_FIND_FOOD);
        EXACT_MAP.put("My stomach's complaining louder than the mobs.", BotDialogueSounds.LINE_STATUS_SNACK_TIME);

        // Health context
        EXACT_MAP.put("I'm a bit banged up. Maybe we should take it easy.", BotDialogueSounds.LINE_WARNING_BANGED_UP);
        EXACT_MAP.put("I'm not at full strength right now.", BotDialogueSounds.LINE_WARNING_NOT_FULL_STRENGTH);
        EXACT_MAP.put("If something jumps us, I'd prefer it be a chicken.", BotDialogueSounds.LINE_WARNING_PREFER_CHICKEN);

        // Fish hobby context
        EXACT_MAP.put("I was fishing a bit ago. Not a bad haul.", BotDialogueSounds.LINE_CONTEXT_FISH_EARLIER);
        EXACT_MAP.put("Still smells like fish. Could be worse.", BotDialogueSounds.LINE_CONTEXT_SMELLS_FISH);
        EXACT_MAP.put("The fish were cooperating earlier.", BotDialogueSounds.LINE_CONTEXT_FISH_COOPERATING);

        // Hangout hobby context
        EXACT_MAP.put("I was just warming up by the fire earlier.", BotDialogueSounds.LINE_CONTEXT_WARMING_EARLIER);
        EXACT_MAP.put("Nice to take a breather sometimes.", BotDialogueSounds.LINE_CONTEXT_BREATHER_SOMETIMES);
        EXACT_MAP.put("A little campfire time does wonders.", BotDialogueSounds.LINE_CONTEXT_CAMPFIRE_WONDERS);

        // General context
        EXACT_MAP.put("If you need me, I'm listening.", BotDialogueSounds.LINE_CONTEXT_LISTENING);
        // Dynamic touch/social weather-time lines (accept ellipsis/comma/plain variants).
        PATTERN_MAP.put(Pattern.compile("It's (early morning|morning|daytime|sunset|night|late night)(\\.\\.\\.|,)?\\s*(and the )?sky( looks)? clear\\.", Pattern.CASE_INSENSITIVE), BotDialogueSounds.LINE_WEATHER_SUNNY);
        PATTERN_MAP.put(Pattern.compile("It's (early morning|morning|daytime|sunset|night|late night)(\\.\\.\\.|,)?\\s*(and the )?sky( looks)? raining\\.", Pattern.CASE_INSENSITIVE), BotDialogueSounds.LINE_WEATHER_RAIN);
        PATTERN_MAP.put(Pattern.compile("It's (early morning|morning|daytime|sunset|night|late night)(\\.\\.\\.|,)?\\s*(and the )?sky( looks)? thundering\\.", Pattern.CASE_INSENSITIVE), BotDialogueSounds.LINE_WEATHER_THUNDER);
        PATTERN_MAP.put(Pattern.compile("It's (early morning|morning|daytime|sunset|night|late night)(\\.\\.\\.|,)?\\s*sky's clear\\.", Pattern.CASE_INSENSITIVE), BotDialogueSounds.LINE_WEATHER_SUNNY);
        PATTERN_MAP.put(Pattern.compile("It's (early morning|morning|daytime|sunset|night|late night)(\\.\\.\\.|,)?\\s*sky's raining\\.", Pattern.CASE_INSENSITIVE), BotDialogueSounds.LINE_WEATHER_RAIN);
        PATTERN_MAP.put(Pattern.compile("It's (early morning|morning|daytime|sunset|night|late night)(\\.\\.\\.|,)?\\s*sky's thundering\\.", Pattern.CASE_INSENSITIVE), BotDialogueSounds.LINE_WEATHER_THUNDER);

        // ============ CONFIRM ============
        EXACT_MAP.put("On it.", BotDialogueSounds.LINE_CONFIRM_ON_IT);
        // Pattern for "On it — <summary>." messages
        PATTERN_MAP.put(Pattern.compile("On it [-—] .+\\."), BotDialogueSounds.LINE_CONFIRM_ON_IT);
        EXACT_MAP.put("Understood. I'll hold off for now.", BotDialogueSounds.LINE_CONFIRM_HOLD_OFF);
        EXACT_MAP.put("Give me a quick yes or no so I know whether to proceed.", BotDialogueSounds.LINE_CONFIRM_ASK_YESNO);

        // ============ DISCOVER ============
        EXACT_MAP.put("I found diamonds!", BotDialogueSounds.LINE_DISCOVER_DIAMONDS);
        EXACT_MAP.put("I found ancient debris!", BotDialogueSounds.LINE_DISCOVER_ANCIENT_DEBRIS);
        EXACT_MAP.put("I found emeralds!", BotDialogueSounds.LINE_DISCOVER_EMERALDS);
        EXACT_MAP.put("I found gold!", BotDialogueSounds.LINE_DISCOVER_GOLD);
        EXACT_MAP.put("I found iron!", BotDialogueSounds.LINE_DISCOVER_IRON);
        EXACT_MAP.put("I found coal!", BotDialogueSounds.LINE_DISCOVER_COAL);
        EXACT_MAP.put("I found redstone!", BotDialogueSounds.LINE_DISCOVER_REDSTONE);
        EXACT_MAP.put("I found lapis!", BotDialogueSounds.LINE_DISCOVER_LAPIS);
        EXACT_MAP.put("I found quartz!", BotDialogueSounds.LINE_DISCOVER_QUARTZ);
        EXACT_MAP.put("I found a chest!", BotDialogueSounds.LINE_DISCOVER_CHEST);
        EXACT_MAP.put("I found an amethyst geode!", BotDialogueSounds.LINE_DISCOVER_GEODE);
        EXACT_MAP.put("I found a structure.", BotDialogueSounds.LINE_DISCOVER_STRUCTURE);
        EXACT_MAP.put("I found a mineshaft!", BotDialogueSounds.LINE_DISCOVER_MINESHAFT);
        EXACT_MAP.put("I found a mob spawner!", BotDialogueSounds.LINE_DISCOVER_SPAWNER);

        // ============ HAZARD ============
        EXACT_MAP.put("Careful, there's lava ahead.", BotDialogueSounds.LINE_HAZARD_LAVA);
        // Common variants (some skills historically used these)
        EXACT_MAP.put("There's lava ahead.", BotDialogueSounds.LINE_HAZARD_LAVA);
        EXACT_MAP.put("Water detected ahead.", BotDialogueSounds.LINE_HAZARD_WATER);
        EXACT_MAP.put("There's water ahead.", BotDialogueSounds.LINE_HAZARD_WATER);
        EXACT_MAP.put("Ran out of torches!", BotDialogueSounds.LINE_HAZARD_NO_TORCHES);

        // ============ COMBAT ============
        EXACT_MAP.put("Engaging threats against allies.", BotDialogueSounds.LINE_COMBAT_ENGAGING);
        EXACT_MAP.put("Standing down unless attacked.", BotDialogueSounds.LINE_COMBAT_STANDING_DOWN);
        EXACT_MAP.put("I'll defend nearby bots when they are attacked.", BotDialogueSounds.LINE_COMBAT_DEFEND_BOTS);
        EXACT_MAP.put("I'll focus on my own fights.", BotDialogueSounds.LINE_COMBAT_FOCUS_SELF);
        EXACT_MAP.put("Combat stance set to aggressive.", BotDialogueSounds.LINE_COMBAT_AGGRESSIVE);
        EXACT_MAP.put("Combat stance set to evasive.", BotDialogueSounds.LINE_COMBAT_EVASIVE);

        // Combat: ranged misses (randomized variants)
        EXACT_MAP.put("I missed!", BotDialogueSounds.LINE_COMBAT_MISSED_1);
        EXACT_MAP.put("Dang, missed!", BotDialogueSounds.LINE_COMBAT_MISSED_2);
        EXACT_MAP.put("Ugh. Missed.", BotDialogueSounds.LINE_COMBAT_MISSED_3);
        EXACT_MAP.put("That one went wide!", BotDialogueSounds.LINE_COMBAT_MISSED_4);

        // ============ HUNGER ============
        EXACT_MAP.put("I'll die if I don't eat!", BotDialogueSounds.LINE_HUNGER_DYING);
        EXACT_MAP.put("I'm starving!", BotDialogueSounds.LINE_HUNGER_STARVING);
        EXACT_MAP.put("I'm getting hungry.", BotDialogueSounds.LINE_HUNGER_WARNING);
        EXACT_MAP.put("I'm hungry!", BotDialogueSounds.LINE_HUNGER_WARNING); // Actual code uses this variant

        // ============ EATING ============
        EXACT_MAP.put("I don't have any safe food to eat!", BotDialogueSounds.LINE_EATING_NO_FOOD);
        EXACT_MAP.put("I ate some food, but I'm still hungry.", BotDialogueSounds.LINE_EATING_STILL_HUNGRY);
        EXACT_MAP.put("I ate some food so far.", BotDialogueSounds.LINE_EATING_PROGRESS);
        EXACT_MAP.put("I ate some food. I feel better now!", BotDialogueSounds.LINE_EATING_DONE);
        // Pattern matches for dynamic food counts: "I ate 2 food item(s), but I'm still hungry."
        PATTERN_MAP.put(Pattern.compile("I ate \\d+ food item\\(s\\), but I'm still hungry\\."), BotDialogueSounds.LINE_EATING_STILL_HUNGRY);
        PATTERN_MAP.put(Pattern.compile("I ate \\d+ food item\\(s\\) so far\\."), BotDialogueSounds.LINE_EATING_PROGRESS);
        PATTERN_MAP.put(Pattern.compile("I ate \\d+ food item\\(s\\)\\. I feel better now!"), BotDialogueSounds.LINE_EATING_DONE);

        // ============ DEATH ============
        EXACT_MAP.put("I died. Should I continue with the last job?", BotDialogueSounds.LINE_DEATH_RESUME_ASK);

        // ============ MOVE ============
        EXACT_MAP.put("I couldn't reach that spot.", BotDialogueSounds.LINE_MOVE_CANT_REACH);
        EXACT_MAP.put("I couldn't clear a block.", BotDialogueSounds.LINE_MOVE_BLOCKED);
        EXACT_MAP.put("Walking to you.", BotDialogueSounds.LINE_MOVE_WALKING_TO_YOU);
        EXACT_MAP.put("Follow target lost. Returning to idle.", BotDialogueSounds.LINE_MOVE_TARGET_LOST);
        EXACT_MAP.put("Back to idling.", BotDialogueSounds.LINE_MOVE_BACK_TO_IDLE);

        // ============ INVENTORY ============
        EXACT_MAP.put("I'm out of inventory space.", BotDialogueSounds.LINE_INVENTORY_FULL);
        EXACT_MAP.put("I'm out of inventory space, so I'll leave the remaining drops where they fell.", BotDialogueSounds.LINE_INVENTORY_FULL);
        EXACT_MAP.put("I don't have that.", BotDialogueSounds.LINE_INVENTORY_DONT_HAVE);
        EXACT_MAP.put("I don't have that", BotDialogueSounds.LINE_INVENTORY_DONT_HAVE); // Without period
        EXACT_MAP.put("Here, take this.", BotDialogueSounds.LINE_INVENTORY_GIVE_ITEM);

        // ============ FISH ============
        EXACT_MAP.put("I can't find any water nearby.", BotDialogueSounds.LINE_FISH_NO_WATER);

        // ============ SLEEP ============
        EXACT_MAP.put("I couldn't sleep right now.", BotDialogueSounds.LINE_SLEEP_CANT_NOW);
        EXACT_MAP.put("I couldn't craft a bed.", BotDialogueSounds.LINE_SLEEP_NO_BED);
        EXACT_MAP.put("I couldn't find a safe spot.", BotDialogueSounds.LINE_SLEEP_NO_SPOT);
        EXACT_MAP.put("I couldn't get into the bed.", BotDialogueSounds.LINE_SLEEP_BED_BLOCKED);

        // ============ CRAFT ============
        EXACT_MAP.put("I need a crafting table placed nearby.", BotDialogueSounds.LINE_CRAFT_NEED_TABLE);
        EXACT_MAP.put("I don't know how to craft that yet.", BotDialogueSounds.LINE_CRAFT_UNKNOWN);
        EXACT_MAP.put("I couldn't place a crafting table here.", BotDialogueSounds.LINE_CRAFT_CANT_PLACE);

        // Recipe unlock reactions (triggered when a new craft-history entry is created)
        EXACT_MAP.put("New recipe unlocked.", BotDialogueSounds.LINE_CRAFT_RECIPE_UNLOCKED_1);
        EXACT_MAP.put("Nice. New recipe.", BotDialogueSounds.LINE_CRAFT_RECIPE_UNLOCKED_2);
        EXACT_MAP.put("That's a new one.", BotDialogueSounds.LINE_CRAFT_RECIPE_UNLOCKED_3);
        EXACT_MAP.put("Good. More options now.", BotDialogueSounds.LINE_CRAFT_RECIPE_UNLOCKED_4);
        EXACT_MAP.put("Another recipe for the book.", BotDialogueSounds.LINE_CRAFT_RECIPE_UNLOCKED_5);
        EXACT_MAP.put("That might come in handy.", BotDialogueSounds.LINE_CRAFT_RECIPE_UNLOCKED_6);

        // ============ SMELT ============
        EXACT_MAP.put("I need a furnace placed nearby.", BotDialogueSounds.LINE_SMELT_NEED_FURNACE);
        EXACT_MAP.put("I have nothing cookable.", BotDialogueSounds.LINE_SMELT_NOTHING);

        // ============ FARM ============
        EXACT_MAP.put("I need seeds before I can farm.", BotDialogueSounds.LINE_FARM_NEED_SEEDS);
        EXACT_MAP.put("I need a hoe to till the soil.", BotDialogueSounds.LINE_FARM_NEED_HOE);

        // ============ SHELTER ============
        EXACT_MAP.put("I can't build a shelter here.", BotDialogueSounds.LINE_SHELTER_CANT_BUILD);

        // ============ WARNINGS ============
        EXACT_MAP.put("I'm suffocating!", BotDialogueSounds.LINE_WARNING_SUFFOCATING);
        EXACT_MAP.put("There's a drop ahead.", BotDialogueSounds.LINE_WARNING_DROP_AHEAD);
        EXACT_MAP.put("That's a big drop.", BotDialogueSounds.LINE_WARNING_DROP_AHEAD);
        EXACT_MAP.put("I'm stuck down here - attempting a ladder or stair escape...", BotDialogueSounds.LINE_WARNING_STUCK);
        // Additional warning variations
        EXACT_MAP.put("I'm stuck!", BotDialogueSounds.LINE_WARNING_STUCK);

        // ============ OTHER STATUS ============
        EXACT_MAP.put("It's getting late; heading home.", BotDialogueSounds.LINE_STATUS_LATE_HEADING_HOME);
        EXACT_MAP.put("It's getting late-heading home.", BotDialogueSounds.LINE_STATUS_LATE_HEADING_HOME);
        // Return/base arrival messages - reuse mode_return sounds
        EXACT_MAP.put("Arrived at base.", BotDialogueSounds.LINE_MODE_RETURN_RETURNING);
        EXACT_MAP.put("Arrived at base. Holding position.", BotDialogueSounds.LINE_MODE_STAY_HOLDING);
        // Busy messages - reuse confirm_hold_off sound
        EXACT_MAP.put("Busy with another task right now.", BotDialogueSounds.LINE_CONFIRM_HOLD_OFF);

        // ============ ADDITIONAL HANGOUT LINES ============
        // From HangoutSkill.java
        EXACT_MAP.put("I'll hang out by the fire for a bit.", BotDialogueSounds.LINE_SKILL_HANGOUT_FIRE_BIT);
        EXACT_MAP.put("I'll hang out for a bit.", BotDialogueSounds.LINE_SKILL_HANGOUT_BIT);
        EXACT_MAP.put("Taking a short breather.", BotDialogueSounds.LINE_SKILL_HANGOUT_SHORT_BREATHER);

        // ============ ADDITIONAL MODE CONFIRMATIONS ============
        // Return mode variations
        EXACT_MAP.put("Returning to base.", BotDialogueSounds.LINE_MODE_RETURN_RETURNING);
        EXACT_MAP.put("On my way back to base.", BotDialogueSounds.LINE_MODE_RETURN_ON_WAY);
        EXACT_MAP.put("Heading home before it gets too dark.", BotDialogueSounds.LINE_MODE_RETURN_HEADING);
        
        // Follow mode variations
        EXACT_MAP.put("Lead the way.", BotDialogueSounds.LINE_MODE_FOLLOW_LEAD);
        EXACT_MAP.put("Keeping up.", BotDialogueSounds.LINE_MODE_FOLLOW_KEEPING_UP);
        PATTERN_MAP.put(patternWithName("I'm right behind you, {name}."), BotDialogueSounds.LINE_MODE_FOLLOW_BEHIND);
        
        // Stay mode variations  
        EXACT_MAP.put("Holding position.", BotDialogueSounds.LINE_MODE_STAY_HOLDING);
        EXACT_MAP.put("I'll stay right here.", BotDialogueSounds.LINE_MODE_STAY_RIGHT_HERE);
        EXACT_MAP.put("Standing by.", BotDialogueSounds.LINE_MODE_STAY_STANDING_BY);
        EXACT_MAP.put("Staying put here.", BotDialogueSounds.LINE_MODE_STAY_HOLDING);
        
        // Guard mode variations
        EXACT_MAP.put("All clear. I'm keeping watch.", BotDialogueSounds.LINE_MODE_GUARD_CLEAR);
        EXACT_MAP.put("Guard duty. Nothing gets past me.", BotDialogueSounds.LINE_MODE_GUARD_NOTHING_PAST);
        EXACT_MAP.put("Quiet so far.", BotDialogueSounds.LINE_MODE_GUARD_QUIET);
        
        // Patrol mode variations
        EXACT_MAP.put("Doing my rounds.", BotDialogueSounds.LINE_MODE_PATROL_ROUNDS);
        EXACT_MAP.put("Patrolling the area.", BotDialogueSounds.LINE_MODE_PATROL_AREA);
        EXACT_MAP.put("Just checking the perimeter.", BotDialogueSounds.LINE_MODE_PATROL_PERIMETER);

        // ============ NEW CHATTERBOX LINES: AMBIENT / DARK / WILDLIFE / LOST / FOUND ============
        // Ambient / cave
        EXACT_MAP.put("I heard something.", BotDialogueSounds.LINE_AMBIENT_HEARD_SOMETHING);
        EXACT_MAP.put("Did you hear that?", BotDialogueSounds.LINE_AMBIENT_DID_YOU_HEAR);
        EXACT_MAP.put("Something moved.", BotDialogueSounds.LINE_AMBIENT_SOMETHING_MOVED);
        EXACT_MAP.put("I'm not alone.", BotDialogueSounds.LINE_AMBIENT_NOT_ALONE);
        EXACT_MAP.put("Smells terrible.", BotDialogueSounds.LINE_AMBIENT_SMELLS_TERRIBLE);
        EXACT_MAP.put("I don't like this.", BotDialogueSounds.LINE_AMBIENT_DONT_LIKE_THIS);
        EXACT_MAP.put("This is creepy.", BotDialogueSounds.LINE_AMBIENT_CREEPY);
        EXACT_MAP.put("It's deep in the cave.", BotDialogueSounds.LINE_AMBIENT_CAVE_DEEP);

        // Special environment - amethyst
        EXACT_MAP.put("These crystals are beautiful.", BotDialogueSounds.LINE_AMBIENT_AMETHYST_BEAUTIFUL);
        EXACT_MAP.put("So sparkly down here.", BotDialogueSounds.LINE_AMBIENT_AMETHYST_SPARKLY);
        EXACT_MAP.put("We found a geode!", BotDialogueSounds.LINE_AMBIENT_AMETHYST_GEODE);

        // Special environment - bats
        EXACT_MAP.put("Ah! Bats!", BotDialogueSounds.LINE_AMBIENT_BAT_STARTLED);
        EXACT_MAP.put("I hear wings flapping.", BotDialogueSounds.LINE_AMBIENT_BAT_WINGS);
        EXACT_MAP.put("Bats give me the creeps.", BotDialogueSounds.LINE_AMBIENT_BAT_CREEPY);

        // Special environment - dripstone
        EXACT_MAP.put("Careful, those are sharp.", BotDialogueSounds.LINE_AMBIENT_DRIPSTONE_CAREFUL);
        EXACT_MAP.put("Pointy stalactites everywhere.", BotDialogueSounds.LINE_AMBIENT_DRIPSTONE_SHARP);
        EXACT_MAP.put("I can hear dripping.", BotDialogueSounds.LINE_AMBIENT_DRIPSTONE_DRIPPING);

        // Special environment - deepslate
        EXACT_MAP.put("It's cold down here.", BotDialogueSounds.LINE_AMBIENT_DEEPSLATE_COLD);
        EXACT_MAP.put("We're really deep now.", BotDialogueSounds.LINE_AMBIENT_DEEPSLATE_DEEP);
        EXACT_MAP.put("This stone feels ancient.", BotDialogueSounds.LINE_AMBIENT_DEEPSLATE_ANCIENT);

        // Darkness-related
        EXACT_MAP.put("I can't see a thing.", BotDialogueSounds.LINE_DARK_CANT_SEE);
        EXACT_MAP.put("Where are you?", BotDialogueSounds.LINE_DARK_WHERE_ARE_YOU);
        EXACT_MAP.put("I need light.", BotDialogueSounds.LINE_DARK_NEED_LIGHT);
        EXACT_MAP.put("It's too dark.", BotDialogueSounds.LINE_DARK_TOO_DARK);
        EXACT_MAP.put("Torch please.", BotDialogueSounds.LINE_DARK_TORCH_PLEASE);
        EXACT_MAP.put("Torch, please.", BotDialogueSounds.LINE_DARK_TORCH_PLEASE);

        // Wildlife / surface
        EXACT_MAP.put("I heard a bird.", BotDialogueSounds.LINE_WILDLIFE_HEARD_BIRD);
        EXACT_MAP.put("Saw a cow.", BotDialogueSounds.LINE_WILDLIFE_SAW_COW);
        EXACT_MAP.put("Pig nearby.", BotDialogueSounds.LINE_WILDLIFE_PIG_NEARBY);
        EXACT_MAP.put("Sheep around.", BotDialogueSounds.LINE_WILDLIFE_SHEEP_AROUND);
        EXACT_MAP.put("Chicken nearby.", BotDialogueSounds.LINE_WILDLIFE_CHICKEN);
        EXACT_MAP.put("Nice day.", BotDialogueSounds.LINE_WILDLIFE_NICE_DAY);
        EXACT_MAP.put("I heard a wolf.", BotDialogueSounds.LINE_WILDLIFE_HEARD_WOLF);

        // Lost / shouts
        EXACT_MAP.put("Over here!", BotDialogueSounds.LINE_LOST_OVER_HERE);
        EXACT_MAP.put("Can anyone hear me?", BotDialogueSounds.LINE_LOST_CAN_ANYONE_HEAR);
        EXACT_MAP.put("Hello?", BotDialogueSounds.LINE_LOST_HELLO);
        EXACT_MAP.put("Help!", BotDialogueSounds.LINE_LOST_HELP);
        EXACT_MAP.put("I'm stuck here.", BotDialogueSounds.LINE_LOST_STUCK_HERE);
        EXACT_MAP.put("Where is everyone?", BotDialogueSounds.LINE_LOST_WHERE_IS_EVERYONE);

        // Found / rescued responses
        EXACT_MAP.put("Finally! Someone found me.", BotDialogueSounds.LINE_FOUND_FINALLY);
        EXACT_MAP.put("Thank goodness.", BotDialogueSounds.LINE_FOUND_THANK_GOODNESS);
        EXACT_MAP.put("I thought I'd be stuck.", BotDialogueSounds.LINE_FOUND_THOUGHT_ID_BE_STUCK);
        EXACT_MAP.put("So glad.", BotDialogueSounds.LINE_FOUND_SO_GLAD);
        EXACT_MAP.put("I've been calling.", BotDialogueSounds.LINE_FOUND_BEEN_CALLING);
        EXACT_MAP.put("Let's get out of here.", BotDialogueSounds.LINE_FOUND_LETS_GET_OUT);

        // ============ MOUNT / LEAD HANDLING (January 2026) ============
        // RideSyncService: low health / feeding
        EXACT_MAP.put("This horse looks hurt.", BotDialogueSounds.LINE_MOUNT_HORSE_HURT);
        EXACT_MAP.put("I don't have any apples to heal it.", BotDialogueSounds.LINE_MOUNT_NO_APPLES);
        EXACT_MAP.put("I don't have any suitable food to heal it.", BotDialogueSounds.LINE_MOUNT_NO_SUITABLE_FOOD);

        // RideSyncService: securing/leashing issues
        EXACT_MAP.put("I don't have a lead to secure this horse.", BotDialogueSounds.LINE_MOUNT_NO_LEAD_SECURE);
        EXACT_MAP.put("I can't grab a lead to secure this horse.", BotDialogueSounds.LINE_MOUNT_CANT_GRAB_LEAD);
        EXACT_MAP.put("I don't have a fence to tie this horse to yet. I'll keep it on a lead.", BotDialogueSounds.LINE_MOUNT_NO_FENCE_KEEP_LEAD);
        EXACT_MAP.put("I lost track of the horse I was holding.", BotDialogueSounds.LINE_MOUNT_LOST_TRACK_HOLDING);
        EXACT_MAP.put("The horse I was holding is gone.", BotDialogueSounds.LINE_MOUNT_HORSE_GONE);
        EXACT_MAP.put("The lead snapped after a sudden drop.", BotDialogueSounds.LINE_MOUNT_LEAD_SNAPPED_DROP);
        EXACT_MAP.put("I don't have a lead to reattach.", BotDialogueSounds.LINE_MOUNT_NO_LEAD_REATTACH);

        // Best-effort mapping (no exact recorded line in the pack)
        EXACT_MAP.put("I couldn't secure the lead on the horse.", BotDialogueSounds.LINE_MOUNT_CANT_SECURE);

        // Alternate phrasings — recorded but previously unused
        // Horse hurt alternates
        EXACT_MAP.put("Mount's looking banged up.", BotDialogueSounds.LINE_MOUNT_BANGED_UP);
        EXACT_MAP.put("My horse is hurt.", BotDialogueSounds.LINE_MOUNT_MY_HORSE_HURT);
        // No apples alternates
        EXACT_MAP.put("I'm out of apples for the horse.", BotDialogueSounds.LINE_MOUNT_OUT_OF_APPLES);
        EXACT_MAP.put("No apples on me for this horse.", BotDialogueSounds.LINE_MOUNT_NO_APPLES_ON_ME);
        // No food alternates
        EXACT_MAP.put("I can't find food for it.", BotDialogueSounds.LINE_MOUNT_CANT_FIND_FOOD);
        EXACT_MAP.put("I don't have feed for this horse.", BotDialogueSounds.LINE_MOUNT_NO_FEED);
        // No lead (ensure) alternate
        EXACT_MAP.put("I'm missing a lead for the horse.", BotDialogueSounds.LINE_MOUNT_MISSING_LEAD);
        // No lead (grab) alternates
        EXACT_MAP.put("I couldn't get the lead.", BotDialogueSounds.LINE_MOUNT_COULDNT_GET_LEAD);
        EXACT_MAP.put("My lead isn't accessible right now.", BotDialogueSounds.LINE_MOUNT_LEAD_NOT_ACCESSIBLE);
        // No fence alternates
        EXACT_MAP.put("No fence nearby to tie it off.", BotDialogueSounds.LINE_MOUNT_NO_FENCE_NEARBY);
        EXACT_MAP.put("I'll hold the lead until I can tie it off.", BotDialogueSounds.LINE_MOUNT_HOLD_LEAD_UNTIL_TIE);
        // Lost horse alternates
        EXACT_MAP.put("I lost the horse I was leading.", BotDialogueSounds.LINE_MOUNT_LOST_HORSE_LEAD);
        EXACT_MAP.put("I can't find the horse.", BotDialogueSounds.LINE_MOUNT_CANT_FIND_HORSE);
        EXACT_MAP.put("My mount is gone.", BotDialogueSounds.LINE_MOUNT_MOUNT_GONE);
        EXACT_MAP.put("I lost the animal I was leading.", BotDialogueSounds.LINE_MOUNT_LOST_LEADING);
        // Lead snapped alternates
        EXACT_MAP.put("The lead broke after that fall.", BotDialogueSounds.LINE_MOUNT_LEAD_BROKE_FALL);
        EXACT_MAP.put("The lead snapped on that drop.", BotDialogueSounds.LINE_MOUNT_LEAD_SNAPPED_ON_DROP);
        // No lead to reattach alternates
        EXACT_MAP.put("I'm out of leads to reattach.", BotDialogueSounds.LINE_MOUNT_OUT_OF_LEADS);
        EXACT_MAP.put("No spare leads to reattach.", BotDialogueSounds.LINE_MOUNT_NO_SPARE_LEADS);
        // ============ JANUARY 2026 BATCH 3 (MANIFEST-GENERATED) ============
        // Generated from tools/audio manifests
        EXACT_MAP.put("I have a bad feeling about this.", BotDialogueSounds.LINE_AMBIENT_BAD_FEELING);
        EXACT_MAP.put("If we die, I'm blaming the terrain.", BotDialogueSounds.LINE_AMBIENT_BLAME_TERRAIN);
        EXACT_MAP.put("I can't believe this is my job.", BotDialogueSounds.LINE_AMBIENT_MY_JOB);
        EXACT_MAP.put("I'm thinking. Don't rush me.", BotDialogueSounds.LINE_AMBIENT_THINKING);
        EXACT_MAP.put("That's a quality animal.", BotDialogueSounds.LINE_ANIMAL_QUALITY);
        EXACT_MAP.put("I respect a well-behaved animal.", BotDialogueSounds.LINE_ANIMAL_WELL_BEHAVED);
        EXACT_MAP.put("Beautiful day to be on the water.", BotDialogueSounds.LINE_BOAT_BEAUTIFUL_DAY);
        EXACT_MAP.put("That water looks deep.", BotDialogueSounds.LINE_BOAT_DEEP_WATER);
        EXACT_MAP.put("We've got an escort.", BotDialogueSounds.LINE_BOAT_DOLPHIN_ESCORT);
        EXACT_MAP.put("Did you see the size of that fish?", BotDialogueSounds.LINE_BOAT_FISH_SIZE);
        EXACT_MAP.put("Probably some good fishing in these parts.", BotDialogueSounds.LINE_BOAT_GOOD_FISHING);
        EXACT_MAP.put("I hope you know how to swim.", BotDialogueSounds.LINE_BOAT_KNOW_SWIM);
        EXACT_MAP.put("Look out, kraken! Just kidding.", BotDialogueSounds.LINE_BOAT_KRAKEN);
        EXACT_MAP.put("Shipwreck speedrun.", BotDialogueSounds.LINE_BOAT_SHIPWRECK_SPEEDRUN);
        EXACT_MAP.put("This is excessive.", BotDialogueSounds.LINE_COMBAT_MULTI_EXCESSIVE);
        EXACT_MAP.put("Not a fair fight.", BotDialogueSounds.LINE_COMBAT_MULTI_NOT_FAIR);
        EXACT_MAP.put("Alright, everybody relax.", BotDialogueSounds.LINE_COMBAT_MULTI_RELAX);
        EXACT_MAP.put("This is where bad ideas go to mature.", BotDialogueSounds.LINE_CREEPY_BAD_IDEAS_MATURE);
        EXACT_MAP.put("I'd like to file a complaint with reality.", BotDialogueSounds.LINE_CREEPY_COMPLAINT_REALITY);
        EXACT_MAP.put("Keep your head on a swivel.", BotDialogueSounds.LINE_CREEPY_HEAD_SWIVEL);
        EXACT_MAP.put("I didn't mean to do that!", BotDialogueSounds.LINE_FF_DEALT_DIDNT_MEAN);
        EXACT_MAP.put("I panicked!", BotDialogueSounds.LINE_FF_DEALT_PANICKED);
        EXACT_MAP.put("I'm on your team!", BotDialogueSounds.LINE_FF_RECEIVED_ON_YOUR_TEAM);
        EXACT_MAP.put("Ow. That was you.", BotDialogueSounds.LINE_FF_RECEIVED_OW_THAT_WAS_YOU);
        EXACT_MAP.put("Aaahaha!", BotDialogueSounds.LINE_FREEFALL_AAAHAHA);
        EXACT_MAP.put("Exhilarating!!!", BotDialogueSounds.LINE_FREEFALL_EXHILARATING);
        EXACT_MAP.put("Falling with style!", BotDialogueSounds.LINE_FREEFALL_FALLING_STYLE);
        EXACT_MAP.put("I'm a bird!", BotDialogueSounds.LINE_FREEFALL_IM_A_BIRD);
        EXACT_MAP.put("Someone tell my inventory I loved it.", BotDialogueSounds.LINE_FREEFALL_INVENTORY);
        EXACT_MAP.put("I immediately regret this!", BotDialogueSounds.LINE_FREEFALL_REGRET);
        EXACT_MAP.put("WOOOOOHOOOOO!!!", BotDialogueSounds.LINE_FREEFALL_WOOHOO);
        EXACT_MAP.put("Yolo!", BotDialogueSounds.LINE_FREEFALL_YOLO);
        EXACT_MAP.put("That's illegal. That's against nature.", BotDialogueSounds.LINE_MEME_CHICKEN_ILLEGAL);
        EXACT_MAP.put("Chicken jockey!", BotDialogueSounds.LINE_MEME_CHICKEN_JOCKEY);
        EXACT_MAP.put("Nope. Absolutely not.", BotDialogueSounds.LINE_MEME_CHICKEN_NOPE);
        EXACT_MAP.put("Creeper... aw man.", BotDialogueSounds.LINE_MEME_CREEPER_AW_MAN);
        EXACT_MAP.put("Back up. Back up. Back up.", BotDialogueSounds.LINE_MEME_CREEPER_BACK_UP);
        EXACT_MAP.put("I hate that sound.", BotDialogueSounds.LINE_MEME_CREEPER_HATE_SOUND);
        EXACT_MAP.put("If you say 'Herobrine,' I'm leaving.", BotDialogueSounds.LINE_MEME_HEROBRINE_LEAVING);
        EXACT_MAP.put("I saw nothing. And I'm keeping it that way.", BotDialogueSounds.LINE_MEME_HEROBRINE_SAW_NOTHING);
        EXACT_MAP.put("I am Steve.", BotDialogueSounds.LINE_MEME_I_AM_STEVE);
        EXACT_MAP.put("I'm... Steve-adjacent.", BotDialogueSounds.LINE_MEME_STEVE_ADJACENT);
        EXACT_MAP.put("Technoblade never dies.", BotDialogueSounds.LINE_MEME_TECHNOBLADE);
        EXACT_MAP.put("That was a human laugh. Totally normal.", BotDialogueSounds.LINE_META_HUMAN_LAUGH);
        EXACT_MAP.put("I'm not a robot. You're a robot.", BotDialogueSounds.LINE_META_NOT_ROBOT);
        EXACT_MAP.put("Stop looking at me like that. I'm trying.", BotDialogueSounds.LINE_META_STOP_LOOKING);
        EXACT_MAP.put("That was... adequate.", BotDialogueSounds.LINE_POST_COMBAT_ADEQUATE);
        EXACT_MAP.put("I need a minute.", BotDialogueSounds.LINE_POST_COMBAT_MULTI_NEED_MINUTE);
        EXACT_MAP.put("That wasn't easy.", BotDialogueSounds.LINE_POST_COMBAT_MULTI_NOT_EASY);
        EXACT_MAP.put("That was easy.", BotDialogueSounds.LINE_POST_COMBAT_SINGLE_EASY);
        EXACT_MAP.put("I almost feel bad for it. Almost.", BotDialogueSounds.LINE_POST_COMBAT_SINGLE_FEEL_BAD);
        EXACT_MAP.put("Barely an inconvenience.", BotDialogueSounds.LINE_POST_COMBAT_SINGLE_INCONVENIENCE);
        EXACT_MAP.put("That must have hurt.", BotDialogueSounds.LINE_POST_COMBAT_SINGLE_MUST_HURT);
        EXACT_MAP.put("We're still alive. Good.", BotDialogueSounds.LINE_POST_COMBAT_STILL_ALIVE);
        EXACT_MAP.put("I felt that in my bones.", BotDialogueSounds.LINE_POST_EXPLOSION_BONES);
        EXACT_MAP.put("Next time, less 'boom,' yeah?", BotDialogueSounds.LINE_POST_EXPLOSION_LESS_BOOM);
        EXACT_MAP.put("Let's back up. Slowly.", BotDialogueSounds.LINE_PRECIPICE_BACK_UP);
        EXACT_MAP.put("That's a big drop.", BotDialogueSounds.LINE_PRECIPICE_BIG_DROP);
        EXACT_MAP.put("Are we gonna jump?", BotDialogueSounds.LINE_PRECIPICE_GONNA_JUMP);
        EXACT_MAP.put("Nope.", BotDialogueSounds.LINE_PRECIPICE_NOPE);
        EXACT_MAP.put("I'm not a fan of gravity right now.", BotDialogueSounds.LINE_PRECIPICE_NOT_FAN_GRAVITY);
        EXACT_MAP.put("You first.", BotDialogueSounds.LINE_PRECIPICE_YOU_FIRST);
        EXACT_MAP.put("I hate that sound.", BotDialogueSounds.LINE_SCARY_HATE_SOUND);
        EXACT_MAP.put("Nope. Not acknowledging that.", BotDialogueSounds.LINE_SCARY_NOT_ACKNOWLEDGING);
        EXACT_MAP.put("It's not pretty, but it's ours.", BotDialogueSounds.LINE_SHELTER_NOT_PRETTY);
        EXACT_MAP.put("We have a roof. Luxury.", BotDialogueSounds.LINE_SHELTER_ROOF_LUXURY);
        EXACT_MAP.put("This will keep out... some of the problems.", BotDialogueSounds.LINE_SHELTER_SOME_PROBLEMS);
        EXACT_MAP.put("Is that all you can say?", BotDialogueSounds.LINE_VILLAGER_ALL_YOU_SAY);
        EXACT_MAP.put("I can't tell them apart.", BotDialogueSounds.LINE_VILLAGER_CANT_TELL_APART);
        EXACT_MAP.put("He's really committed to that one line.", BotDialogueSounds.LINE_VILLAGER_COMMITTED_ONE_LINE);
        EXACT_MAP.put("A man of few words.", BotDialogueSounds.LINE_VILLAGER_FEW_WORDS);
        EXACT_MAP.put("The local tongue is so foreign to me.", BotDialogueSounds.LINE_VILLAGER_FOREIGN_TONGUE);
        EXACT_MAP.put("Time to negotiate with the locals.", BotDialogueSounds.LINE_VILLAGER_NEGOTIATE);
        EXACT_MAP.put("I have no idea what that guy just said.", BotDialogueSounds.LINE_VILLAGER_NO_IDEA);
        EXACT_MAP.put("We should nod like we understand.", BotDialogueSounds.LINE_VILLAGER_NOD_UNDERSTAND);
        EXACT_MAP.put("That was rude.", BotDialogueSounds.LINE_VILLAGER_RUDE);
        EXACT_MAP.put("Amazing view.", BotDialogueSounds.LINE_VISTA_AMAZING_VIEW);
        EXACT_MAP.put("That's beautiful.", BotDialogueSounds.LINE_VISTA_BEAUTIFUL);
        EXACT_MAP.put("We should have built the base here.", BotDialogueSounds.LINE_VISTA_BUILT_BASE_HERE);
        EXACT_MAP.put("I could live here.", BotDialogueSounds.LINE_VISTA_COULD_LIVE_HERE);
        EXACT_MAP.put("That's gorgeous.", BotDialogueSounds.LINE_VISTA_GORGEOUS);
        EXACT_MAP.put("Okay, that's worth the walk.", BotDialogueSounds.LINE_VISTA_WORTH_WALK);
        EXACT_MAP.put("Wow, would you look at that.", BotDialogueSounds.LINE_VISTA_WOULD_YOU_LOOK);
        EXACT_MAP.put("Wow.", BotDialogueSounds.LINE_VISTA_WOW);
        EXACT_MAP.put("Guard dog on duty.", BotDialogueSounds.LINE_WOLF_GUARD_DUTY);
        EXACT_MAP.put("Hey - leave the dog alone.", BotDialogueSounds.LINE_WOLF_LEAVE_ALONE);
        EXACT_MAP.put("Who's a menace? You're a menace.", BotDialogueSounds.LINE_WOLF_MENACE);
        EXACT_MAP.put("We are somewhere we should not be.", BotDialogueSounds.LINE_TOPIC_DEEP_DARK_FIRST);
        EXACT_MAP.put("This place punishes curiosity. Stay light on your feet.", BotDialogueSounds.LINE_TOPIC_DEEP_DARK_ASK_1);
        EXACT_MAP.put("If you hear a heartbeat, stop moving.", BotDialogueSounds.LINE_TOPIC_DEEP_DARK_ASK_2);
        EXACT_MAP.put("I'm voting we don't explore this.", BotDialogueSounds.LINE_TOPIC_DEEP_DARK_ASK_3);
        EXACT_MAP.put("The Deep Dark still feels like a mistake we escaped.", BotDialogueSounds.LINE_TOPIC_DEEP_DARK_MEMORY);
        EXACT_MAP.put("This is either paradise or a prank.", BotDialogueSounds.LINE_TOPIC_MUSHROOM_FIRST);
        EXACT_MAP.put("No mobs. No screaming. Suspiciously peaceful.", BotDialogueSounds.LINE_TOPIC_MUSHROOM_ASK_1);
        EXACT_MAP.put("We could retire here. Quietly. With soup.", BotDialogueSounds.LINE_TOPIC_MUSHROOM_ASK_2);
        EXACT_MAP.put("We found the one biome that doesn't hate us.", BotDialogueSounds.LINE_TOPIC_MUSHROOM_MEMORY);
        EXACT_MAP.put("Everything here is alive and judging us.", BotDialogueSounds.LINE_TOPIC_JUNGLE_FIRST);
        EXACT_MAP.put("This is where you get lost three blocks from safety.", BotDialogueSounds.LINE_TOPIC_JUNGLE_ASK_1);
        EXACT_MAP.put("If you see vines, assume there's a trap attached.", BotDialogueSounds.LINE_TOPIC_JUNGLE_ASK_2);
        EXACT_MAP.put("Jungle navigation is just arguing with leaves.", BotDialogueSounds.LINE_TOPIC_JUNGLE_MEMORY);
        EXACT_MAP.put("So much sand. So little patience.", BotDialogueSounds.LINE_TOPIC_DESERT_FIRST);
        EXACT_MAP.put("Desert looks empty until it isn't.", BotDialogueSounds.LINE_TOPIC_DESERT_ASK_1);
        EXACT_MAP.put("We're one hole away from a bad day.", BotDialogueSounds.LINE_TOPIC_DESERT_ASK_2);
        EXACT_MAP.put("We crossed that desert like it owed us money.", BotDialogueSounds.LINE_TOPIC_DESERT_MEMORY);
        EXACT_MAP.put("Air's thin. Mistakes get expensive.", BotDialogueSounds.LINE_TOPIC_SNOW_FIRST);
        EXACT_MAP.put("Good view. Bad fall.", BotDialogueSounds.LINE_TOPIC_SNOW_ASK_1);
        EXACT_MAP.put("If we slip, we're bouncing the whole way down.", BotDialogueSounds.LINE_TOPIC_SNOW_ASK_2);
        EXACT_MAP.put("Mountains taught us humility.", BotDialogueSounds.LINE_TOPIC_SNOW_MEMORY);
        EXACT_MAP.put("Civilization. And a suspicious amount of staring.", BotDialogueSounds.LINE_TOPIC_VILLAGE_FIRST);
        EXACT_MAP.put("Villagers don't talk, but they communicate disappointment.", BotDialogueSounds.LINE_TOPIC_VILLAGE_ASK_1);
        EXACT_MAP.put("We should leave them better off than we found them. Ideally.", BotDialogueSounds.LINE_TOPIC_VILLAGE_ASK_2);
        EXACT_MAP.put("That last village felt like returning to the world.", BotDialogueSounds.LINE_TOPIC_VILLAGE_MEMORY);
        EXACT_MAP.put("This is the most words I've seen all week.", BotDialogueSounds.LINE_TOPIC_LIBRARY_FIRST);
        EXACT_MAP.put("Books make me feel... upgraded.", BotDialogueSounds.LINE_TOPIC_LIBRARY_ASK_1);
        EXACT_MAP.put("If we're learning, we should do it before the next disaster.", BotDialogueSounds.LINE_TOPIC_LIBRARY_ASK_2);
        EXACT_MAP.put("I like quiet places. Don't tell anyone.", BotDialogueSounds.LINE_TOPIC_LIBRARY_ASK_3);
        EXACT_MAP.put("I'd like to spend more time among those bookshelves.", BotDialogueSounds.LINE_TOPIC_LIBRARY_MEMORY);
        EXACT_MAP.put("Time for magic gambling.", BotDialogueSounds.LINE_TOPIC_ENCHANTING_FIRST);
        EXACT_MAP.put("I love enchantments. I usually hate enchantment outcomes.", BotDialogueSounds.LINE_TOPIC_ENCHANTING_ASK_1);
        EXACT_MAP.put("Pray for a good enchantment.", BotDialogueSounds.LINE_TOPIC_ENCHANTING_ASK_2);
        EXACT_MAP.put("If it comes out cursed-looking, we pretend it's fine.", BotDialogueSounds.LINE_TOPIC_ENCHANTING_ASK_3);
        EXACT_MAP.put("Remember when we enchanted something and instantly got arrogant?", BotDialogueSounds.LINE_TOPIC_ENCHANTING_MEMORY);
        EXACT_MAP.put("Someone tried to leave. It didn't go great.", BotDialogueSounds.LINE_TOPIC_RUINED_PORTAL_FIRST);
        EXACT_MAP.put("Ruins always feel fresh. Like the world remembers.", BotDialogueSounds.LINE_TOPIC_RUINED_PORTAL_ASK_1);
        EXACT_MAP.put("We should take what we can and not ask questions.", BotDialogueSounds.LINE_TOPIC_RUINED_PORTAL_ASK_2);
        EXACT_MAP.put("That portal was a warning sign dressed as loot.", BotDialogueSounds.LINE_TOPIC_RUINED_PORTAL_MEMORY);
        EXACT_MAP.put("Fortress found. This is where things get serious.", BotDialogueSounds.LINE_TOPIC_NETHER_FORTRESS_FIRST);
        EXACT_MAP.put("Long halls, blind corners, and fire. Great.", BotDialogueSounds.LINE_TOPIC_NETHER_FORTRESS_ASK_1);
        EXACT_MAP.put("If we get rods, we get out. Simple plan.", BotDialogueSounds.LINE_TOPIC_NETHER_FORTRESS_ASK_2);
        EXACT_MAP.put("That fortress run was stressful.", BotDialogueSounds.LINE_TOPIC_NETHER_FORTRESS_MEMORY);
        EXACT_MAP.put("We're guests. The hosts bite.", BotDialogueSounds.LINE_TOPIC_BASTION_FIRST);
        EXACT_MAP.put("Gold makes them friendly. Temporarily.", BotDialogueSounds.LINE_TOPIC_BASTION_ASK_1);
        EXACT_MAP.put("Every chest here has a price.", BotDialogueSounds.LINE_TOPIC_BASTION_ASK_2);
        EXACT_MAP.put("Bastions taught us to respect piglins.", BotDialogueSounds.LINE_TOPIC_BASTION_MEMORY);
        EXACT_MAP.put("We found it. The place you only reach by obsession.", BotDialogueSounds.LINE_TOPIC_STRONGHOLD_FIRST);
        EXACT_MAP.put("Every hallway says final exam.", BotDialogueSounds.LINE_TOPIC_STRONGHOLD_ASK_1);
        EXACT_MAP.put("This is the doorway to consequences.", BotDialogueSounds.LINE_TOPIC_STRONGHOLD_ASK_2);
        EXACT_MAP.put("Stronghold stone still feels cold.", BotDialogueSounds.LINE_TOPIC_STRONGHOLD_MEMORY);
        EXACT_MAP.put("This is a cathedral for bad ideas.", BotDialogueSounds.LINE_TOPIC_ANCIENT_CITY_FIRST);
        EXACT_MAP.put("Don't be loud.", BotDialogueSounds.LINE_TOPIC_ANCIENT_CITY_ASK_1);
        EXACT_MAP.put("We're here to take loot, not to prove anything.", BotDialogueSounds.LINE_TOPIC_ANCIENT_CITY_ASK_2);
        EXACT_MAP.put("Ancient City... I'd rather not do that again.", BotDialogueSounds.LINE_TOPIC_ANCIENT_CITY_MEMORY);
        EXACT_MAP.put("A dungeon designed by someone who hates safety.", BotDialogueSounds.LINE_TOPIC_TRIAL_CHAMBERS_FIRST);
        EXACT_MAP.put("This place rewards aggression. Carefully.", BotDialogueSounds.LINE_TOPIC_TRIAL_CHAMBERS_ASK_1);
        EXACT_MAP.put("We can leave any time. That's the strategy.", BotDialogueSounds.LINE_TOPIC_TRIAL_CHAMBERS_ASK_2);
        EXACT_MAP.put("Trials were fun. In the way pain is fun.", BotDialogueSounds.LINE_TOPIC_TRIAL_CHAMBERS_MEMORY);
        EXACT_MAP.put("New dimension. Same problems, louder.", BotDialogueSounds.LINE_TOPIC_NETHER_FIRST);
        EXACT_MAP.put("Rule one: don't trust the floor.", BotDialogueSounds.LINE_TOPIC_NETHER_ASK_1);
        EXACT_MAP.put("Rule two: don't trust the ceiling either.", BotDialogueSounds.LINE_TOPIC_NETHER_ASK_2);
        EXACT_MAP.put("The Nether changed the tone of the whole adventure.", BotDialogueSounds.LINE_TOPIC_NETHER_MEMORY);
        EXACT_MAP.put("The End. Keep your eyes to yourself.", BotDialogueSounds.LINE_TOPIC_END_FIRST);
        EXACT_MAP.put("It's quiet here. That's never a good sign.", BotDialogueSounds.LINE_TOPIC_END_ASK_1);
        EXACT_MAP.put("We came this far. No sloppy mistakes.", BotDialogueSounds.LINE_TOPIC_END_ASK_2);
        EXACT_MAP.put("The End felt like stepping outside the world.", BotDialogueSounds.LINE_TOPIC_END_MEMORY);
        EXACT_MAP.put("A salesman approaches. Stay strong.", BotDialogueSounds.LINE_TOPIC_TRADER_FIRST_1);
        EXACT_MAP.put("That's either commerce... or a scam with llamas.", BotDialogueSounds.LINE_TOPIC_TRADER_FIRST_2);
        EXACT_MAP.put("He sells junk with confidence. I respect it.", BotDialogueSounds.LINE_TOPIC_TRADER_ASK_1);
        EXACT_MAP.put("He travels the world to offer... two ferns and a bucket. Iconic.", BotDialogueSounds.LINE_TOPIC_TRADER_ASK_2);
        EXACT_MAP.put("The llamas are the real security detail.", BotDialogueSounds.LINE_TOPIC_TRADER_ASK_3);
        EXACT_MAP.put("Remember the wandering trader? He had the vibe of a side quest.", BotDialogueSounds.LINE_TOPIC_TRADER_MEMORY_1);
        EXACT_MAP.put("I still think about those llamas. Professional posture.", BotDialogueSounds.LINE_TOPIC_TRADER_MEMORY_2);
        EXACT_MAP.put("Those llamas look like they've seen things.", BotDialogueSounds.LINE_TOPIC_LLAMA_FIRST);
        EXACT_MAP.put("They're not pets. They're coworkers.", BotDialogueSounds.LINE_TOPIC_LLAMA_ASK_1);
        EXACT_MAP.put("If one spits at me, I'm taking it personally.", BotDialogueSounds.LINE_TOPIC_LLAMA_ASK_2);
        EXACT_MAP.put("Respect the llama. Fear the llama.", BotDialogueSounds.LINE_TOPIC_LLAMA_ASK_3);
        EXACT_MAP.put("We met a trader's llamas once. I'm still not over it.", BotDialogueSounds.LINE_TOPIC_LLAMA_MEMORY);
        EXACT_MAP.put("Horse acquired. Dignity restored.", BotDialogueSounds.LINE_TOPIC_HORSE_FIRST_1);
        EXACT_MAP.put("We're doing this the classy way now.", BotDialogueSounds.LINE_TOPIC_HORSE_FIRST_2);
        EXACT_MAP.put("A horse is just faster walking with extra responsibility.", BotDialogueSounds.LINE_TOPIC_HORSE_ASK_1);
        EXACT_MAP.put("If it jumps well, we keep it. That's the law.", BotDialogueSounds.LINE_TOPIC_HORSE_ASK_2);
        EXACT_MAP.put("Remember our first horse? That was freedom.", BotDialogueSounds.LINE_TOPIC_HORSE_MEMORY);
        EXACT_MAP.put("Cargo secured.", BotDialogueSounds.LINE_TOPIC_DONKEY_FIRST);
        EXACT_MAP.put("They're not fast, but they're reliable.", BotDialogueSounds.LINE_TOPIC_DONKEY_ASK_1);
        EXACT_MAP.put("That donkey carried half our life on its back.", BotDialogueSounds.LINE_TOPIC_DONKEY_MEMORY);
        EXACT_MAP.put("We're riding a camel. That's real life now.", BotDialogueSounds.LINE_TOPIC_CAMEL_FIRST_1);
        EXACT_MAP.put("Two seats. Bad ideas made easier.", BotDialogueSounds.LINE_TOPIC_CAMEL_FIRST_2);
        EXACT_MAP.put("This is a desert taxi with attitude.", BotDialogueSounds.LINE_TOPIC_CAMEL_ASK_1);
        EXACT_MAP.put("If we get ambushed, we're leaving with style.", BotDialogueSounds.LINE_TOPIC_CAMEL_ASK_2);
        EXACT_MAP.put("That camel ride felt like a victory lap.", BotDialogueSounds.LINE_TOPIC_CAMEL_MEMORY);
        EXACT_MAP.put("We are walking on lava. Normal day.", BotDialogueSounds.LINE_TOPIC_STRIDER_FIRST_1);
        EXACT_MAP.put("Strider online. Nether commute unlocked.", BotDialogueSounds.LINE_TOPIC_STRIDER_FIRST_2);
        EXACT_MAP.put("If it stops moving, we're history.", BotDialogueSounds.LINE_TOPIC_STRIDER_ASK_1);
        EXACT_MAP.put("Warped fungus on a stick is the weirdest steering wheel.", BotDialogueSounds.LINE_TOPIC_STRIDER_ASK_2);
        EXACT_MAP.put("That first lava ride felt illegal.", BotDialogueSounds.LINE_TOPIC_STRIDER_MEMORY);
        EXACT_MAP.put("Nice, a minecart.", BotDialogueSounds.LINE_TOPIC_MINECART_FIRST);
        EXACT_MAP.put("Nothing says progress like building rails through a mountain.", BotDialogueSounds.LINE_TOPIC_MINECART_ASK_1);
        EXACT_MAP.put("If we derail, we pretend it's a feature.", BotDialogueSounds.LINE_TOPIC_MINECART_ASK_2);
        EXACT_MAP.put("We built infrastructure. Like adults.", BotDialogueSounds.LINE_TOPIC_MINECART_MEMORY);
        EXACT_MAP.put("We can fly. This will not improve our judgment.", BotDialogueSounds.LINE_TOPIC_ELYTRA_FIRST);
        EXACT_MAP.put("Fireworks are just controlled panic.", BotDialogueSounds.LINE_TOPIC_ELYTRA_ASK_1);
        EXACT_MAP.put("Don't aim at trees. Trees always win.", BotDialogueSounds.LINE_TOPIC_ELYTRA_ASK_2);
        EXACT_MAP.put("First flight was pure confidence.", BotDialogueSounds.LINE_TOPIC_ELYTRA_MEMORY);

        // ============ CARE: PLAYER HURT ============
        EXACT_MAP.put("You're looking rough - need a breather?", BotDialogueSounds.LINE_CARE_PLAYER_HURT_1);
        EXACT_MAP.put("That's a lot of damage. Take it easy.", BotDialogueSounds.LINE_CARE_PLAYER_HURT_2);
        EXACT_MAP.put("Hang in there. I've got food if you need it.", BotDialogueSounds.LINE_CARE_PLAYER_HURT_3);
        // ============ CARE: PLAYER HUNGRY ============
        EXACT_MAP.put("Your stomach's growling - eat something.", BotDialogueSounds.LINE_CARE_PLAYER_HUNGRY_1);
        EXACT_MAP.put("You should eat. I think I've got something.", BotDialogueSounds.LINE_CARE_PLAYER_HUNGRY_2);
        EXACT_MAP.put("Low on food? Don't wait too long.", BotDialogueSounds.LINE_CARE_PLAYER_HUNGRY_3);

        // Cross-dimension follow dialogue (overhead → sound lookup)
        EXACT_MAP.put("I'll follow you to the end...is that corny?", BotDialogueSounds.LINE_FOLLOW_DIM_END);
        EXACT_MAP.put("The End. Sure. Why not.", BotDialogueSounds.LINE_FOLLOW_DIM_END_2);
        EXACT_MAP.put("Into the fire with you, then.", BotDialogueSounds.LINE_FOLLOW_DIM_NETHER);
        EXACT_MAP.put("Nether it is. Stay close.", BotDialogueSounds.LINE_FOLLOW_DIM_NETHER_2);
        EXACT_MAP.put("Fresh air. Finally.", BotDialogueSounds.LINE_FOLLOW_DIM_OVERWORLD);
        EXACT_MAP.put("Back to the surface. Good.", BotDialogueSounds.LINE_FOLLOW_DIM_OVERWORLD_2);

        // ============ APRIL 2026 HANDOFF (v1.1.30+) ============
        // Berry-bush reactions (fired via CompanionOverheadDialogueService.tryShowSweetBerryBush*)
        EXACT_MAP.put("Ouch!", BotDialogueSounds.LINE_BERRY_BUSH_OUCH);
        EXACT_MAP.put("Yowch!", BotDialogueSounds.LINE_BERRY_BUSH_YOWCH);
        EXACT_MAP.put("These are thorny!", BotDialogueSounds.LINE_BERRY_BUSH_THORNY);
        EXACT_MAP.put("These are edible...I think.", BotDialogueSounds.LINE_BERRY_BUSH_EDIBLE_I_THINK);
        EXACT_MAP.put("These are edible... I think.", BotDialogueSounds.LINE_BERRY_BUSH_EDIBLE_I_THINK);

        // Foliage-stuck reactions (via CompanionOverheadDialogueService LEAF_STUCK_LINES)
        EXACT_MAP.put("These branches are thick!", BotDialogueSounds.LINE_FOLIAGE_STUCK_BRANCHES_THICK);
        EXACT_MAP.put("Hold on - stuck in some branches.", BotDialogueSounds.LINE_FOLIAGE_STUCK_IN_BRANCHES);
        EXACT_MAP.put("Can't get through these leaves.", BotDialogueSounds.LINE_FOLIAGE_STUCK_CANT_GET_THROUGH);
        EXACT_MAP.put("Just a sec... foliage's got me.", BotDialogueSounds.LINE_FOLIAGE_STUCK_GOT_ME);

        LOGGER.info("Initialized {} exact mappings and {} pattern mappings", EXACT_MAP.size(), PATTERN_MAP.size());

        // Build reverse map (sound → first matching text) for overhead display.
        for (Map.Entry<String, SoundEvent> entry : EXACT_MAP.entrySet()) {
            String soundId = entry.getValue().id().toString();
            SOUND_TO_TEXT.putIfAbsent(soundId, entry.getKey());
        }
    }

    /**
     * Reverse lookup: get the display text for a sound event.
     * Returns null if no text is mapped.
     */
    public static String textForSound(SoundEvent sound) {
        if (sound == null || sound.id() == null) {
            return null;
        }
        return SOUND_TO_TEXT.get(sound.id().toString());
    }

    /**
     * Check if a sound mapping exists for the given text.
     * @param text The chat message text
     * @return true if a sound mapping exists
     */
    public static boolean hasMapping(String text) {
        return lookup(text) != null;
    }
}
