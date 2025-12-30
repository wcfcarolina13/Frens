package net.shasankp000.ChatUtils;

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

        // ============ MODE: FOLLOW ============
        PATTERN_MAP.put(patternWithName("I'm right behind you, {name}."), BotDialogueSounds.LINE_MODE_FOLLOW_BEHIND);
        EXACT_MAP.put("Lead the way.", BotDialogueSounds.LINE_MODE_FOLLOW_LEAD);
        EXACT_MAP.put("Keeping up.", BotDialogueSounds.LINE_MODE_FOLLOW_KEEPING_UP);

        // ============ MODE: GUARD ============
        EXACT_MAP.put("All clear. I'm keeping watch.", BotDialogueSounds.LINE_MODE_GUARD_CLEAR);
        EXACT_MAP.put("Guard duty. Nothing gets past me.", BotDialogueSounds.LINE_MODE_GUARD_NOTHING_PAST);
        EXACT_MAP.put("Quiet so far.", BotDialogueSounds.LINE_MODE_GUARD_QUIET);

        // ============ MODE: PATROL ============
        EXACT_MAP.put("Doing my rounds.", BotDialogueSounds.LINE_MODE_PATROL_ROUNDS);
        EXACT_MAP.put("Patrolling the area.", BotDialogueSounds.LINE_MODE_PATROL_AREA);
        EXACT_MAP.put("Just checking the perimeter.", BotDialogueSounds.LINE_MODE_PATROL_PERIMETER);

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

        // ============ HAZARD ============
        EXACT_MAP.put("Careful, there's lava ahead.", BotDialogueSounds.LINE_HAZARD_LAVA);
        EXACT_MAP.put("Water detected ahead.", BotDialogueSounds.LINE_HAZARD_WATER);
        EXACT_MAP.put("Ran out of torches!", BotDialogueSounds.LINE_HAZARD_NO_TORCHES);

        // ============ COMBAT ============
        EXACT_MAP.put("Engaging threats against allies.", BotDialogueSounds.LINE_COMBAT_ENGAGING);
        EXACT_MAP.put("Standing down unless attacked.", BotDialogueSounds.LINE_COMBAT_STANDING_DOWN);
        EXACT_MAP.put("I'll defend nearby bots when they are attacked.", BotDialogueSounds.LINE_COMBAT_DEFEND_BOTS);
        EXACT_MAP.put("I'll focus on my own fights.", BotDialogueSounds.LINE_COMBAT_FOCUS_SELF);
        EXACT_MAP.put("Combat stance set to aggressive.", BotDialogueSounds.LINE_COMBAT_AGGRESSIVE);
        EXACT_MAP.put("Combat stance set to evasive.", BotDialogueSounds.LINE_COMBAT_EVASIVE);

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
        EXACT_MAP.put("I'm stuck down here - attempting a ladder or stair escape...", BotDialogueSounds.LINE_WARNING_STUCK);
        // Additional warning variations
        EXACT_MAP.put("I'm stuck!", BotDialogueSounds.LINE_WARNING_STUCK);

        // ============ OTHER STATUS ============
        EXACT_MAP.put("It's getting late; heading home.", BotDialogueSounds.LINE_STATUS_LATE_HEADING_HOME);
        EXACT_MAP.put("It's getting late-heading home.", BotDialogueSounds.LINE_STATUS_LATE_HEADING_HOME);

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

        LOGGER.info("Initialized {} exact mappings and {} pattern mappings", EXACT_MAP.size(), PATTERN_MAP.size());
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
