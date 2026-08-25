package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.ChatUtils.BotDialoguePlayer;
import net.wcfcarolina13.ChatUtils.VoiceLineCategory;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.ChatUtils.BotDialogueSounds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Batch 3 topic dialogue selector (Phase 2A).
 *
 * <p>Policy:
 * - First request per topic family uses a first_unlocked line.
 * - Subsequent requests: 80% ask_about, 20% memory_callback.
 * - Selection state is persisted in ManualConfig.BotQuestMemory.tags.
 */
public final class Batch3TopicDialogueService {

    private static final Random RNG = new Random();

    public static final String KEY_BIOMES = "batch3_biomes";
    public static final String KEY_STRUCTURES = "batch3_structures";
    public static final String KEY_DIMENSIONS = "batch3_dimensions";
    public static final String KEY_TRADERS_MOUNTS = "batch3_traders_mounts";
    public static final String KEY_TRAVEL = "batch3_travel";

    private static final class TopicLine {
        final String id;
        final String text;
        final SoundEvent sound;

        TopicLine(String id, String text, SoundEvent sound) {
            this.id = id;
            this.text = text;
            this.sound = sound;
        }
    }

    private static final class TopicFamily {
        final String id;
        final List<TopicLine> first;
        final List<TopicLine> ask;
        final List<TopicLine> memory;

        TopicFamily(String id, List<TopicLine> first, List<TopicLine> ask, List<TopicLine> memory) {
            this.id = id;
            this.first = first;
            this.ask = ask;
            this.memory = memory;
        }
    }

    private static final class TopicFamilyBuilder {
        final List<TopicLine> first = new ArrayList<>();
        final List<TopicLine> ask = new ArrayList<>();
        final List<TopicLine> memory = new ArrayList<>();
    }

    private static final Map<String, TopicFamilyBuilder> FAMILY_BUILDERS = new LinkedHashMap<>();
    private static final Map<String, TopicFamily> FAMILIES = new LinkedHashMap<>();
    private static final Map<String, List<String>> GROUP_FAMILIES = new LinkedHashMap<>();

    static {
        addLine("deep_dark", "first", new TopicLine("topic_deep_dark_first", "We are somewhere we should not be.", BotDialogueSounds.LINE_TOPIC_DEEP_DARK_FIRST));
        addLine("deep_dark", "ask", new TopicLine("topic_deep_dark_ask_1", "This place punishes curiosity. Stay light on your feet.", BotDialogueSounds.LINE_TOPIC_DEEP_DARK_ASK_1));
        addLine("deep_dark", "ask", new TopicLine("topic_deep_dark_ask_2", "If you hear a heartbeat, stop moving.", BotDialogueSounds.LINE_TOPIC_DEEP_DARK_ASK_2));
        addLine("deep_dark", "ask", new TopicLine("topic_deep_dark_ask_3", "I'm voting we don't explore this.", BotDialogueSounds.LINE_TOPIC_DEEP_DARK_ASK_3));
        addLine("deep_dark", "memory", new TopicLine("topic_deep_dark_memory", "The Deep Dark still feels like a mistake we escaped.", BotDialogueSounds.LINE_TOPIC_DEEP_DARK_MEMORY));
        addLine("mushroom", "first", new TopicLine("topic_mushroom_first", "This is either paradise or a prank.", BotDialogueSounds.LINE_TOPIC_MUSHROOM_FIRST));
        addLine("mushroom", "ask", new TopicLine("topic_mushroom_ask_1", "No mobs. No screaming. Suspiciously peaceful.", BotDialogueSounds.LINE_TOPIC_MUSHROOM_ASK_1));
        addLine("mushroom", "ask", new TopicLine("topic_mushroom_ask_2", "We could retire here. Quietly. With soup.", BotDialogueSounds.LINE_TOPIC_MUSHROOM_ASK_2));
        addLine("mushroom", "memory", new TopicLine("topic_mushroom_memory", "We found the one biome that doesn't hate us.", BotDialogueSounds.LINE_TOPIC_MUSHROOM_MEMORY));
        addLine("jungle", "first", new TopicLine("topic_jungle_first", "Everything here is alive and judging us.", BotDialogueSounds.LINE_TOPIC_JUNGLE_FIRST));
        addLine("jungle", "ask", new TopicLine("topic_jungle_ask_1", "This is where you get lost three blocks from safety.", BotDialogueSounds.LINE_TOPIC_JUNGLE_ASK_1));
        addLine("jungle", "ask", new TopicLine("topic_jungle_ask_2", "If you see vines, assume there's a trap attached.", BotDialogueSounds.LINE_TOPIC_JUNGLE_ASK_2));
        addLine("jungle", "memory", new TopicLine("topic_jungle_memory", "Jungle navigation is just arguing with leaves.", BotDialogueSounds.LINE_TOPIC_JUNGLE_MEMORY));
        addLine("desert", "first", new TopicLine("topic_desert_first", "So much sand. So little patience.", BotDialogueSounds.LINE_TOPIC_DESERT_FIRST));
        addLine("desert", "ask", new TopicLine("topic_desert_ask_1", "Desert looks empty until it isn't.", BotDialogueSounds.LINE_TOPIC_DESERT_ASK_1));
        addLine("desert", "ask", new TopicLine("topic_desert_ask_2", "We're one hole away from a bad day.", BotDialogueSounds.LINE_TOPIC_DESERT_ASK_2));
        addLine("desert", "memory", new TopicLine("topic_desert_memory", "We crossed that desert like it owed us money.", BotDialogueSounds.LINE_TOPIC_DESERT_MEMORY));
        addLine("snow", "first", new TopicLine("topic_snow_first", "Air's thin. Mistakes get expensive.", BotDialogueSounds.LINE_TOPIC_SNOW_FIRST));
        addLine("snow", "ask", new TopicLine("topic_snow_ask_1", "Good view. Bad fall.", BotDialogueSounds.LINE_TOPIC_SNOW_ASK_1));
        addLine("snow", "ask", new TopicLine("topic_snow_ask_2", "If we slip, we're bouncing the whole way down.", BotDialogueSounds.LINE_TOPIC_SNOW_ASK_2));
        addLine("snow", "memory", new TopicLine("topic_snow_memory", "Mountains taught us humility.", BotDialogueSounds.LINE_TOPIC_SNOW_MEMORY));
        addLine("village", "first", new TopicLine("topic_village_first", "Civilization. And a suspicious amount of staring.", BotDialogueSounds.LINE_TOPIC_VILLAGE_FIRST));
        addLine("village", "ask", new TopicLine("topic_village_ask_1", "Villagers don't talk, but they communicate disappointment.", BotDialogueSounds.LINE_TOPIC_VILLAGE_ASK_1));
        addLine("village", "ask", new TopicLine("topic_village_ask_2", "We should leave them better off than we found them. Ideally.", BotDialogueSounds.LINE_TOPIC_VILLAGE_ASK_2));
        addLine("village", "memory", new TopicLine("topic_village_memory", "That last village felt like returning to the world.", BotDialogueSounds.LINE_TOPIC_VILLAGE_MEMORY));
        addLine("library", "first", new TopicLine("topic_library_first", "This is the most words I've seen all week.", BotDialogueSounds.LINE_TOPIC_LIBRARY_FIRST));
        addLine("library", "ask", new TopicLine("topic_library_ask_1", "Books make me feel... upgraded.", BotDialogueSounds.LINE_TOPIC_LIBRARY_ASK_1));
        addLine("library", "ask", new TopicLine("topic_library_ask_2", "If we're learning, we should do it before the next disaster.", BotDialogueSounds.LINE_TOPIC_LIBRARY_ASK_2));
        addLine("library", "ask", new TopicLine("topic_library_ask_3", "I like quiet places. Don't tell anyone.", BotDialogueSounds.LINE_TOPIC_LIBRARY_ASK_3));
        addLine("library", "memory", new TopicLine("topic_library_memory", "I'd like to spend more time among those bookshelves.", BotDialogueSounds.LINE_TOPIC_LIBRARY_MEMORY));
        addLine("enchanting", "first", new TopicLine("topic_enchanting_first", "Time for magic gambling.", BotDialogueSounds.LINE_TOPIC_ENCHANTING_FIRST));
        addLine("enchanting", "ask", new TopicLine("topic_enchanting_ask_1", "I love enchantments. I usually hate enchantment outcomes.", BotDialogueSounds.LINE_TOPIC_ENCHANTING_ASK_1));
        addLine("enchanting", "ask", new TopicLine("topic_enchanting_ask_2", "Pray for a good enchantment.", BotDialogueSounds.LINE_TOPIC_ENCHANTING_ASK_2));
        addLine("enchanting", "ask", new TopicLine("topic_enchanting_ask_3", "If it comes out cursed-looking, we pretend it's fine.", BotDialogueSounds.LINE_TOPIC_ENCHANTING_ASK_3));
        addLine("enchanting", "memory", new TopicLine("topic_enchanting_memory", "Remember when we enchanted something and instantly got arrogant?", BotDialogueSounds.LINE_TOPIC_ENCHANTING_MEMORY));
        addLine("ruined_portal", "first", new TopicLine("topic_ruined_portal_first", "Someone tried to leave. It didn't go great.", BotDialogueSounds.LINE_TOPIC_RUINED_PORTAL_FIRST));
        addLine("ruined_portal", "ask", new TopicLine("topic_ruined_portal_ask_1", "Ruins always feel fresh. Like the world remembers.", BotDialogueSounds.LINE_TOPIC_RUINED_PORTAL_ASK_1));
        addLine("ruined_portal", "ask", new TopicLine("topic_ruined_portal_ask_2", "We should take what we can and not ask questions.", BotDialogueSounds.LINE_TOPIC_RUINED_PORTAL_ASK_2));
        addLine("ruined_portal", "memory", new TopicLine("topic_ruined_portal_memory", "That portal was a warning sign dressed as loot.", BotDialogueSounds.LINE_TOPIC_RUINED_PORTAL_MEMORY));
        addLine("nether_fortress", "first", new TopicLine("topic_nether_fortress_first", "Fortress found. This is where things get serious.", BotDialogueSounds.LINE_TOPIC_NETHER_FORTRESS_FIRST));
        addLine("nether_fortress", "ask", new TopicLine("topic_nether_fortress_ask_1", "Long halls, blind corners, and fire. Great.", BotDialogueSounds.LINE_TOPIC_NETHER_FORTRESS_ASK_1));
        addLine("nether_fortress", "ask", new TopicLine("topic_nether_fortress_ask_2", "If we get rods, we get out. Simple plan.", BotDialogueSounds.LINE_TOPIC_NETHER_FORTRESS_ASK_2));
        addLine("nether_fortress", "memory", new TopicLine("topic_nether_fortress_memory", "That fortress run was stressful.", BotDialogueSounds.LINE_TOPIC_NETHER_FORTRESS_MEMORY));
        addLine("bastion", "first", new TopicLine("topic_bastion_first", "We're guests. The hosts bite.", BotDialogueSounds.LINE_TOPIC_BASTION_FIRST));
        addLine("bastion", "ask", new TopicLine("topic_bastion_ask_1", "Gold makes them friendly. Temporarily.", BotDialogueSounds.LINE_TOPIC_BASTION_ASK_1));
        addLine("bastion", "ask", new TopicLine("topic_bastion_ask_2", "Every chest here has a price.", BotDialogueSounds.LINE_TOPIC_BASTION_ASK_2));
        addLine("bastion", "memory", new TopicLine("topic_bastion_memory", "Bastions taught us to respect piglins.", BotDialogueSounds.LINE_TOPIC_BASTION_MEMORY));
        addLine("stronghold", "first", new TopicLine("topic_stronghold_first", "We found it. The place you only reach by obsession.", BotDialogueSounds.LINE_TOPIC_STRONGHOLD_FIRST));
        addLine("stronghold", "ask", new TopicLine("topic_stronghold_ask_1", "Every hallway says final exam.", BotDialogueSounds.LINE_TOPIC_STRONGHOLD_ASK_1));
        addLine("stronghold", "ask", new TopicLine("topic_stronghold_ask_2", "This is the doorway to consequences.", BotDialogueSounds.LINE_TOPIC_STRONGHOLD_ASK_2));
        addLine("stronghold", "memory", new TopicLine("topic_stronghold_memory", "Stronghold stone still feels cold.", BotDialogueSounds.LINE_TOPIC_STRONGHOLD_MEMORY));
        addLine("ancient_city", "first", new TopicLine("topic_ancient_city_first", "This is a cathedral for bad ideas.", BotDialogueSounds.LINE_TOPIC_ANCIENT_CITY_FIRST));
        addLine("ancient_city", "ask", new TopicLine("topic_ancient_city_ask_1", "Don't be loud.", BotDialogueSounds.LINE_TOPIC_ANCIENT_CITY_ASK_1));
        addLine("ancient_city", "ask", new TopicLine("topic_ancient_city_ask_2", "We're here to take loot, not to prove anything.", BotDialogueSounds.LINE_TOPIC_ANCIENT_CITY_ASK_2));
        addLine("ancient_city", "memory", new TopicLine("topic_ancient_city_memory", "Ancient City... I'd rather not do that again.", BotDialogueSounds.LINE_TOPIC_ANCIENT_CITY_MEMORY));
        addLine("trial_chambers", "first", new TopicLine("topic_trial_chambers_first", "A dungeon designed by someone who hates safety.", BotDialogueSounds.LINE_TOPIC_TRIAL_CHAMBERS_FIRST));
        addLine("trial_chambers", "ask", new TopicLine("topic_trial_chambers_ask_1", "This place rewards aggression. Carefully.", BotDialogueSounds.LINE_TOPIC_TRIAL_CHAMBERS_ASK_1));
        addLine("trial_chambers", "ask", new TopicLine("topic_trial_chambers_ask_2", "We can leave any time. That's the strategy.", BotDialogueSounds.LINE_TOPIC_TRIAL_CHAMBERS_ASK_2));
        addLine("trial_chambers", "memory", new TopicLine("topic_trial_chambers_memory", "Trials were fun. In the way pain is fun.", BotDialogueSounds.LINE_TOPIC_TRIAL_CHAMBERS_MEMORY));
        addLine("nether", "first", new TopicLine("topic_nether_first", "New dimension. Same problems, louder.", BotDialogueSounds.LINE_TOPIC_NETHER_FIRST));
        addLine("nether", "ask", new TopicLine("topic_nether_ask_1", "Rule one: don't trust the floor.", BotDialogueSounds.LINE_TOPIC_NETHER_ASK_1));
        addLine("nether", "ask", new TopicLine("topic_nether_ask_2", "Rule two: don't trust the ceiling either.", BotDialogueSounds.LINE_TOPIC_NETHER_ASK_2));
        addLine("nether", "memory", new TopicLine("topic_nether_memory", "The Nether changed the tone of the whole adventure.", BotDialogueSounds.LINE_TOPIC_NETHER_MEMORY));
        addLine("end", "first", new TopicLine("topic_end_first", "The End. Keep your eyes to yourself.", BotDialogueSounds.LINE_TOPIC_END_FIRST));
        addLine("end", "ask", new TopicLine("topic_end_ask_1", "It's quiet here. That's never a good sign.", BotDialogueSounds.LINE_TOPIC_END_ASK_1));
        addLine("end", "ask", new TopicLine("topic_end_ask_2", "We came this far. No sloppy mistakes.", BotDialogueSounds.LINE_TOPIC_END_ASK_2));
        addLine("end", "memory", new TopicLine("topic_end_memory", "The End felt like stepping outside the world.", BotDialogueSounds.LINE_TOPIC_END_MEMORY));
        addLine("trader", "first", new TopicLine("topic_trader_first_1", "A salesman approaches. Stay strong.", BotDialogueSounds.LINE_TOPIC_TRADER_FIRST_1));
        addLine("trader", "first", new TopicLine("topic_trader_first_2", "That's either commerce... or a scam with llamas.", BotDialogueSounds.LINE_TOPIC_TRADER_FIRST_2));
        addLine("trader", "ask", new TopicLine("topic_trader_ask_1", "He sells junk with confidence. I respect it.", BotDialogueSounds.LINE_TOPIC_TRADER_ASK_1));
        addLine("trader", "ask", new TopicLine("topic_trader_ask_2", "He travels the world to offer... two ferns and a bucket. Iconic.", BotDialogueSounds.LINE_TOPIC_TRADER_ASK_2));
        addLine("trader", "ask", new TopicLine("topic_trader_ask_3", "The llamas are the real security detail.", BotDialogueSounds.LINE_TOPIC_TRADER_ASK_3));
        addLine("trader", "memory", new TopicLine("topic_trader_memory_1", "Remember the wandering trader? He had the vibe of a side quest.", BotDialogueSounds.LINE_TOPIC_TRADER_MEMORY_1));
        addLine("trader", "memory", new TopicLine("topic_trader_memory_2", "I still think about those llamas. Professional posture.", BotDialogueSounds.LINE_TOPIC_TRADER_MEMORY_2));
        addLine("llama", "first", new TopicLine("topic_llama_first", "Those llamas look like they've seen things.", BotDialogueSounds.LINE_TOPIC_LLAMA_FIRST));
        addLine("llama", "ask", new TopicLine("topic_llama_ask_1", "They're not pets. They're coworkers.", BotDialogueSounds.LINE_TOPIC_LLAMA_ASK_1));
        addLine("llama", "ask", new TopicLine("topic_llama_ask_2", "If one spits at me, I'm taking it personally.", BotDialogueSounds.LINE_TOPIC_LLAMA_ASK_2));
        addLine("llama", "ask", new TopicLine("topic_llama_ask_3", "Respect the llama. Fear the llama.", BotDialogueSounds.LINE_TOPIC_LLAMA_ASK_3));
        addLine("llama", "memory", new TopicLine("topic_llama_memory", "We met a trader's llamas once. I'm still not over it.", BotDialogueSounds.LINE_TOPIC_LLAMA_MEMORY));
        addLine("horse", "first", new TopicLine("topic_horse_first_1", "Horse acquired. Dignity restored.", BotDialogueSounds.LINE_TOPIC_HORSE_FIRST_1));
        addLine("horse", "first", new TopicLine("topic_horse_first_2", "We're doing this the classy way now.", BotDialogueSounds.LINE_TOPIC_HORSE_FIRST_2));
        addLine("horse", "ask", new TopicLine("topic_horse_ask_1", "A horse is just faster walking with extra responsibility.", BotDialogueSounds.LINE_TOPIC_HORSE_ASK_1));
        addLine("horse", "ask", new TopicLine("topic_horse_ask_2", "If it jumps well, we keep it. That's the law.", BotDialogueSounds.LINE_TOPIC_HORSE_ASK_2));
        addLine("horse", "memory", new TopicLine("topic_horse_memory", "Remember our first horse? That was freedom.", BotDialogueSounds.LINE_TOPIC_HORSE_MEMORY));
        addLine("donkey", "first", new TopicLine("topic_donkey_first", "Cargo secured.", BotDialogueSounds.LINE_TOPIC_DONKEY_FIRST));
        addLine("donkey", "ask", new TopicLine("topic_donkey_ask_1", "They're not fast, but they're reliable.", BotDialogueSounds.LINE_TOPIC_DONKEY_ASK_1));
        addLine("donkey", "memory", new TopicLine("topic_donkey_memory", "That donkey carried half our life on its back.", BotDialogueSounds.LINE_TOPIC_DONKEY_MEMORY));
        addLine("camel", "first", new TopicLine("topic_camel_first_1", "We're riding a camel. That's real life now.", BotDialogueSounds.LINE_TOPIC_CAMEL_FIRST_1));
        addLine("camel", "first", new TopicLine("topic_camel_first_2", "Two seats. Bad ideas made easier.", BotDialogueSounds.LINE_TOPIC_CAMEL_FIRST_2));
        addLine("camel", "ask", new TopicLine("topic_camel_ask_1", "This is a desert taxi with attitude.", BotDialogueSounds.LINE_TOPIC_CAMEL_ASK_1));
        addLine("camel", "ask", new TopicLine("topic_camel_ask_2", "If we get ambushed, we're leaving with style.", BotDialogueSounds.LINE_TOPIC_CAMEL_ASK_2));
        addLine("camel", "memory", new TopicLine("topic_camel_memory", "That camel ride felt like a victory lap.", BotDialogueSounds.LINE_TOPIC_CAMEL_MEMORY));
        addLine("strider", "first", new TopicLine("topic_strider_first_1", "We are walking on lava. Normal day.", BotDialogueSounds.LINE_TOPIC_STRIDER_FIRST_1));
        addLine("strider", "first", new TopicLine("topic_strider_first_2", "Strider online. Nether commute unlocked.", BotDialogueSounds.LINE_TOPIC_STRIDER_FIRST_2));
        addLine("strider", "ask", new TopicLine("topic_strider_ask_1", "If it stops moving, we're history.", BotDialogueSounds.LINE_TOPIC_STRIDER_ASK_1));
        addLine("strider", "ask", new TopicLine("topic_strider_ask_2", "Warped fungus on a stick is the weirdest steering wheel.", BotDialogueSounds.LINE_TOPIC_STRIDER_ASK_2));
        addLine("strider", "memory", new TopicLine("topic_strider_memory", "That first lava ride felt illegal.", BotDialogueSounds.LINE_TOPIC_STRIDER_MEMORY));
        addLine("minecart", "first", new TopicLine("topic_minecart_first", "Nice, a minecart.", BotDialogueSounds.LINE_TOPIC_MINECART_FIRST));
        addLine("minecart", "ask", new TopicLine("topic_minecart_ask_1", "Nothing says progress like building rails through a mountain.", BotDialogueSounds.LINE_TOPIC_MINECART_ASK_1));
        addLine("minecart", "ask", new TopicLine("topic_minecart_ask_2", "If we derail, we pretend it's a feature.", BotDialogueSounds.LINE_TOPIC_MINECART_ASK_2));
        addLine("minecart", "memory", new TopicLine("topic_minecart_memory", "We built infrastructure. Like adults.", BotDialogueSounds.LINE_TOPIC_MINECART_MEMORY));
        addLine("elytra", "first", new TopicLine("topic_elytra_first", "We can fly. This will not improve our judgment.", BotDialogueSounds.LINE_TOPIC_ELYTRA_FIRST));
        addLine("elytra", "ask", new TopicLine("topic_elytra_ask_1", "Fireworks are just controlled panic.", BotDialogueSounds.LINE_TOPIC_ELYTRA_ASK_1));
        addLine("elytra", "ask", new TopicLine("topic_elytra_ask_2", "Don't aim at trees. Trees always win.", BotDialogueSounds.LINE_TOPIC_ELYTRA_ASK_2));
        addLine("elytra", "memory", new TopicLine("topic_elytra_memory", "First flight was pure confidence.", BotDialogueSounds.LINE_TOPIC_ELYTRA_MEMORY));

        finalizeFamilies();

        GROUP_FAMILIES.put(KEY_BIOMES, List.of(
                "deep_dark", "mushroom", "jungle", "desert", "snow", "village"
        ));
        GROUP_FAMILIES.put(KEY_STRUCTURES, List.of(
                "library", "enchanting", "ruined_portal", "nether_fortress", "bastion", "stronghold", "ancient_city", "trial_chambers"
        ));
        GROUP_FAMILIES.put(KEY_DIMENSIONS, List.of(
                "nether", "end"
        ));
        GROUP_FAMILIES.put(KEY_TRADERS_MOUNTS, List.of(
                "trader", "llama", "horse", "donkey", "camel", "strider"
        ));
        GROUP_FAMILIES.put(KEY_TRAVEL, List.of(
                "minecart", "elytra"
        ));
    }

    private Batch3TopicDialogueService() {
    }

    private static void addLine(String familyId, String category, TopicLine line) {
        if (familyId == null || familyId.isBlank() || category == null || category.isBlank() || line == null) {
            return;
        }
        TopicFamilyBuilder builder = FAMILY_BUILDERS.computeIfAbsent(familyId, ignored -> new TopicFamilyBuilder());
        switch (category) {
            case "first" -> builder.first.add(line);
            case "memory" -> builder.memory.add(line);
            default -> builder.ask.add(line);
        }
    }

    private static void finalizeFamilies() {
        for (Map.Entry<String, TopicFamilyBuilder> entry : FAMILY_BUILDERS.entrySet()) {
            TopicFamilyBuilder b = entry.getValue();
            FAMILIES.put(entry.getKey(), new TopicFamily(
                    entry.getKey(),
                    List.copyOf(b.first),
                    List.copyOf(b.ask),
                    List.copyOf(b.memory)
            ));
        }
    }

    private static String canonicalGroupKey(String triggerKey) {
        if (triggerKey == null || triggerKey.isBlank()) {
            return "";
        }
        String k = triggerKey.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case KEY_BIOMES, "topic_biomes" -> KEY_BIOMES;
            case KEY_STRUCTURES, "topic_structures" -> KEY_STRUCTURES;
            case KEY_DIMENSIONS, "topic_dimensions" -> KEY_DIMENSIONS;
            case KEY_TRADERS_MOUNTS, "topic_mounts" -> KEY_TRADERS_MOUNTS;
            case KEY_TRAVEL, "topic_travel" -> KEY_TRAVEL;
            default -> "";
        };
    }

    public static String pickLineForTopic(String botAlias, String triggerKey) {
        return pickLineForTopic(botAlias, triggerKey, null);
    }

    public static String pickLineForTopic(String botAlias, String triggerKey, String forcedLineId) {
        TopicLine line = pickTopicLineForTopic(botAlias, triggerKey, forcedLineId, true);
        return line == null ? null : line.text;
    }

    private static TopicLine pickTopicLineForTopic(String botAlias,
                                                   String triggerKey,
                                                   String forcedLineId,
                                                   boolean persistSelection) {
        String groupKey = canonicalGroupKey(triggerKey);
        if (groupKey.isBlank()) {
            return null;
        }

        List<String> familyIds = GROUP_FAMILIES.get(groupKey);
        if (familyIds == null || familyIds.isEmpty()) {
            return null;
        }

        TopicLine forced = null;
        if (forcedLineId != null && !forcedLineId.isBlank()) {
            String normalizedForced = forcedLineId.trim().toLowerCase(Locale.ROOT);
            forced = findForcedLine(familyIds, normalizedForced);
            if (forced == null) {
                return null;
            }
        }

        String alias = sanitizeAlias(botAlias);
        ManualConfig.BotQuestMemory memory = getQuestMemory(alias);

        TopicFamily selectedFamily;
        TopicLine selectedLine;

        if (forced != null) {
            selectedFamily = familyForLine(familyIds, forced.id);
            selectedLine = forced;
        } else {
            selectedFamily = pickLeastSeenFamily(memory, familyIds);
            if (selectedFamily == null) {
                return null;
            }
            selectedLine = pickLineByPolicy(memory, selectedFamily);
        }

        if (selectedFamily == null || selectedLine == null) {
            return null;
        }

        if (persistSelection) {
            recordSelection(memory, selectedFamily.id, selectedFamily.ask.contains(selectedLine));
        }
        return selectedLine;
    }

    public static boolean debugTrigger(ServerPlayerEntity bot, String triggerKey, String lineId) {
        if (bot == null || bot.isRemoved()) {
            return false;
        }
        TopicLine line = pickTopicLineForTopic(bot.getName().getString(), triggerKey, lineId, false);
        if (line == null || line.text == null || line.text.isBlank()) {
            return false;
        }
        CompanionOverheadDialogueService.showOverheadLine(bot, line.text, 3000, 48.0, "topic", triggerKey);

        BotDialoguePlayer.PlayResult result = BotDialoguePlayer.playSoundForBotDetailed(bot, line.sound, VoiceLineCategory.TOPICS_QUESTS);
        if (result == BotDialoguePlayer.PlayResult.PLAYED || result == BotDialoguePlayer.PlayResult.THROTTLED) {
            return true;
        }

        ChatUtils.sendChatMessages(
                bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                line.text,
                true,
                VoiceLineCategory.TOPICS_QUESTS
        );
        return true;
    }

    private static TopicLine findForcedLine(List<String> familyIds, String forcedLineId) {
        for (String familyId : familyIds) {
            TopicFamily f = FAMILIES.get(familyId);
            if (f == null) {
                continue;
            }
            TopicLine line = findById(f.first, forcedLineId);
            if (line != null) {
                return line;
            }
            line = findById(f.ask, forcedLineId);
            if (line != null) {
                return line;
            }
            line = findById(f.memory, forcedLineId);
            if (line != null) {
                return line;
            }
        }
        return null;
    }

    private static TopicFamily familyForLine(List<String> familyIds, String lineId) {
        if (lineId == null || lineId.isBlank()) {
            return null;
        }
        String target = lineId.trim().toLowerCase(Locale.ROOT);
        for (String familyId : familyIds) {
            TopicFamily f = FAMILIES.get(familyId);
            if (f == null) {
                continue;
            }
            if (findById(f.first, target) != null || findById(f.ask, target) != null || findById(f.memory, target) != null) {
                return f;
            }
        }
        return null;
    }

    private static TopicLine findById(List<TopicLine> lines, String id) {
        if (lines == null || lines.isEmpty() || id == null || id.isBlank()) {
            return null;
        }
        for (TopicLine line : lines) {
            if (line != null && id.equalsIgnoreCase(line.id)) {
                return line;
            }
        }
        return null;
    }

    private static TopicFamily pickLeastSeenFamily(ManualConfig.BotQuestMemory memory,
                                                   List<String> familyIds) {
        int minSeen = Integer.MAX_VALUE;
        List<TopicFamily> candidates = new ArrayList<>();

        for (String familyId : familyIds) {
            TopicFamily family = FAMILIES.get(familyId);
            if (family == null) {
                continue;
            }
            int seen = getTag(memory, seenTag(family.id));
            if (seen < minSeen) {
                minSeen = seen;
                candidates.clear();
                candidates.add(family);
            } else if (seen == minSeen) {
                candidates.add(family);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(RNG.nextInt(candidates.size()));
    }

    private static TopicLine pickLineByPolicy(ManualConfig.BotQuestMemory memory,
                                              TopicFamily family) {
        if (family == null) {
            return null;
        }

        int seen = getTag(memory, seenTag(family.id));
        if (seen <= 0 && !family.first.isEmpty()) {
            return family.first.get(RNG.nextInt(family.first.size()));
        }

        boolean hasAsk = !family.ask.isEmpty();
        boolean hasMemory = !family.memory.isEmpty();

        if (hasAsk && hasMemory) {
            if (RNG.nextDouble() < 0.80D) {
                return family.ask.get(RNG.nextInt(family.ask.size()));
            }
            return family.memory.get(RNG.nextInt(family.memory.size()));
        }
        if (hasAsk) {
            return family.ask.get(RNG.nextInt(family.ask.size()));
        }
        if (hasMemory) {
            return family.memory.get(RNG.nextInt(family.memory.size()));
        }
        if (!family.first.isEmpty()) {
            return family.first.get(RNG.nextInt(family.first.size()));
        }
        return null;
    }

    private static void recordSelection(ManualConfig.BotQuestMemory memory, String familyId, boolean askLine) {
        if (memory == null || familyId == null || familyId.isBlank()) {
            return;
        }

        incrementTag(memory, seenTag(familyId));
        if (askLine) {
            incrementTag(memory, askTag(familyId));
        }

        try {
            if (Frens.CONFIG != null) {
                Frens.CONFIG.save();
            }
        } catch (Exception ignored) {
        }
    }

    private static String sanitizeAlias(String botAlias) {
        if (botAlias == null || botAlias.isBlank()) {
            return "";
        }
        return botAlias.trim();
    }

    private static ManualConfig.BotQuestMemory getQuestMemory(String botAlias) {
        if (botAlias == null || botAlias.isBlank() || Frens.CONFIG == null) {
            return null;
        }
        try {
            return Frens.CONFIG.getOrCreateBotQuestMemory(botAlias);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int getTag(ManualConfig.BotQuestMemory memory, String key) {
        if (memory == null || key == null || key.isBlank()) {
            return 0;
        }
        return memory.getTags().getOrDefault(key, 0);
    }

    private static void incrementTag(ManualConfig.BotQuestMemory memory, String key) {
        if (memory == null || key == null || key.isBlank()) {
            return;
        }
        Map<String, Integer> tags = memory.getTags();
        tags.put(key, tags.getOrDefault(key, 0) + 1);
    }

    private static String seenTag(String familyId) {
        return "batch3.topic." + familyId + ".seen";
    }

    private static String askTag(String familyId) {
        return "batch3.topic." + familyId + ".ask";
    }
}
