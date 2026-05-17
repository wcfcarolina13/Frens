package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.GuardianEntity;
import net.minecraft.entity.mob.HoglinEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.entity.passive.AxolotlEntity;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.DolphinEntity;
import net.minecraft.entity.passive.FoxEntity;
import net.minecraft.entity.passive.GlowSquidEntity;
import net.minecraft.entity.passive.OcelotEntity;
import net.minecraft.entity.passive.PandaEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.entity.passive.SnowGolemEntity;
import net.minecraft.entity.passive.SquidEntity;
import net.minecraft.entity.passive.TurtleEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.wcfcarolina13.Entity.LookController;
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
        /** End-ship "captain now" sequence state. -1 = idle; otherwise tick when
         *  the "hey, hey, look at me" solicitation line fired. */
        long endShipSolicitedAtTick = -1L;
        /** Accumulated ticks of continuous commander-looking-at-bot since solicitation.
         *  Reaches 60 (3 s) → fires "I'm the captain now." */
        int endShipLookingTicks = 0;
        /** Continuous-non-look streak. Used to allow brief glances away (≤1 s)
         *  without nuking the look-streak; a real look-away (≥20 ticks) resets. */
        int endShipLookAwayStreak = 0;
        /** TNT-proximity plea sequence. -1 = idle; 0..3 = next line index to fire. */
        int tntSequenceIndex = -1;
        /** Tick when the last TNT plea line was fired; used to space lines ~1 s apart. */
        long tntLastLineTick = 0L;
        /** Iron golems already gifted a flower by this bot — one-shot per golem. */
        final Set<UUID> giftedGolems = new HashSet<>();
    }

    private static final ConcurrentHashMap<UUID, TriggerState> STATE = new ConcurrentHashMap<>();

    /** Tick at which each bot was first seen by this service. Used for spawn-grace silence. */
    private static final ConcurrentHashMap<UUID, Long> FIRST_SEEN_TICK = new ConcurrentHashMap<>();
    /** Number of ticks after spawn before the bot is allowed to trigger context reactions (~10 s). */
    private static final long SPAWN_GRACE_TICKS = 200L;

    private static final WeightedLine[] AMBIENT_LINES = new WeightedLine[] {
            // ambient_bad_feeling moved to HIGH_THREAT_LINES per triage retune — was
            // firing too often in safe ambient settings.
            // ambient_saw_bird + ambient_same_tree moved to OUTDOOR_AMBIENT_LINES —
            // they were firing underground where neither line makes sense, and saw_bird
            // was also overused at WEIGHT_COMMON.
            new WeightedLine("ambient_my_job", "I can't believe this is my job.", BotDialogueSounds.LINE_AMBIENT_MY_JOB, WEIGHT_VERY_RARE),
            new WeightedLine("ambient_blame_terrain", "If we die, I'm blaming the terrain.", BotDialogueSounds.LINE_AMBIENT_BLAME_TERRAIN, WEIGHT_RARE),
            new WeightedLine("ambient_thinking", "I'm thinking. Don't rush me.", BotDialogueSounds.LINE_AMBIENT_THINKING, WEIGHT_COMMON),
            new WeightedLine("ambient_had_plan", "What was I doing? I had a plan. I definitely had a plan.", BotDialogueSounds.LINE_AMBIENT_HAD_PLAN, WEIGHT_UNCOMMON),
            new WeightedLine("ambient_giant_statue", "I should probably build a giant statue of myself. Or a farm. Probably a farm.", BotDialogueSounds.LINE_AMBIENT_GIANT_STATUE, WEIGHT_RARE),
            new WeightedLine("ambient_forgot_something", "I'm 100% sure I forgot something, but I can't remember what it is.", BotDialogueSounds.LINE_AMBIENT_FORGOT_SOMETHING, WEIGHT_UNCOMMON),
            new WeightedLine("ambient_teeth_itch", "My teeth itch!", BotDialogueSounds.LINE_AMBIENT_TEETH_ITCH, WEIGHT_RARE)
    };

    // Lines that only make sense with the sky overhead (surface overworld / nether open
    // roof / end island top). The "same tree" line additionally requires an actual log
    // nearby — it was firing in deserts/plains/beaches where the bot has never seen a
    // tree, making the line nonsensical.
    private static final WeightedLine[] OUTDOOR_AMBIENT_LINES = new WeightedLine[] {
            new WeightedLine("ambient_saw_bird", "I think I saw a bird.", BotDialogueSounds.LINE_AMBIENT_SAW_BIRD, WEIGHT_VERY_RARE),
            new WeightedLine("ambient_same_tree", "I swear I've walked past this exact tree three times now. I am definitely lost.", BotDialogueSounds.LINE_AMBIENT_SAME_TREE, WEIGHT_RARE)
    };

    // Sky-only subset for use when no log/tree is nearby — drops the "same tree" line
    // so the bot doesn't claim to recognise a tree in a treeless biome.
    private static final WeightedLine[] OUTDOOR_AMBIENT_SKY_ONLY_LINES = new WeightedLine[] {
            new WeightedLine("ambient_saw_bird", "I think I saw a bird.", BotDialogueSounds.LINE_AMBIENT_SAW_BIRD, WEIGHT_VERY_RARE)
    };

    private static final WeightedLine[] PIG_STARING_LINES = new WeightedLine[] {
            new WeightedLine("pig_staring", "That pig has been looking at me for a long time. It's getting weird.", BotDialogueSounds.LINE_PIG_STARING, WEIGHT_COMMON)
    };

    // Snow golem nearby — fires near a vanilla snow golem (pumpkin-stack-on-snow construction).
    // Pool kept light and friendly; one of the rarer pools so it doesn't drown out other reactions
    // when the player has built a snow-golem army.
    private static final WeightedLine[] SNOW_GOLEM_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("snow_golem_friend", "Hey, snow buddy.", BotDialogueSounds.LINE_SNOW_GOLEM_FRIEND, WEIGHT_COMMON),
            new WeightedLine("snow_golem_buddy", "Look at this little guy.", BotDialogueSounds.LINE_SNOW_GOLEM_BUDDY, WEIGHT_COMMON),
            new WeightedLine("snow_golem_ammo", "He makes the ammo, I do the throwing.", BotDialogueSounds.LINE_SNOW_GOLEM_AMMO, WEIGHT_UNCOMMON),
            new WeightedLine("snow_golem_hug", "He looks like he could use a hug. He probably can't hug back.", BotDialogueSounds.LINE_SNOW_GOLEM_HUG, WEIGHT_RARE)
    };

    // Iron golem with daisy — fires when the bot is near an iron golem and has a poppy or oxeye
    // daisy in its inventory. The bot drops the flower at the golem's feet (modeled on villager
    // children offering poppies in vanilla). One-shot per golem UUID so the bot doesn't keep
    // dumping its entire flower inventory on the same golem.
    private static final WeightedLine[] IRON_GOLEM_DAISY_LINES = new WeightedLine[] {
            new WeightedLine("iron_golem_daisy_here", "Hold on big guy, I've got something for you.", BotDialogueSounds.LINE_IRON_GOLEM_DAISY_HERE, WEIGHT_COMMON),
            new WeightedLine("iron_golem_daisy_earned", "Here, you've earned this.", BotDialogueSounds.LINE_IRON_GOLEM_DAISY_EARNED, WEIGHT_COMMON),
            new WeightedLine("iron_golem_daisy_cute", "Iron golem with a flower. Cute, right?", BotDialogueSounds.LINE_IRON_GOLEM_DAISY_CUTE, WEIGHT_UNCOMMON)
    };

    // "Smells terrible." — moved from the generic AMBIENT_CAVE_CHATTER pool 2026-05-07. Now
    // only fires when there's a contextually-coherent smelly source nearby (rotting mobs,
    // dungeon spawners, lush-cave moss/mushrooms, mud/clay, mycelium). Reuses the existing
    // LINE_AMBIENT_SMELLS_TERRIBLE audio asset.
    private static final WeightedLine[] SMELLS_TERRIBLE_LINES = new WeightedLine[] {
            new WeightedLine("ambient_smells_terrible", "Smells terrible.", BotDialogueSounds.LINE_AMBIENT_SMELLS_TERRIBLE, WEIGHT_COMMON)
    };

    // Warden proximity — fear/avoidance dialogue. Wardens are rare/special so this gets a
    // long cooldown. Existing isScaryNearby() detection covers warden too via SCARY_LINES,
    // but those are generic ("I hate that sound."); these are warden-specific.
    private static final WeightedLine[] WARDEN_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("warden_leave_now", "We need to leave. Now.", BotDialogueSounds.LINE_WARDEN_LEAVE_NOW, WEIGHT_COMMON),
            new WeightedLine("warden_not_a_sound", "Not a sound. Not a single sound.", BotDialogueSounds.LINE_WARDEN_NOT_A_SOUND, WEIGHT_COMMON),
            new WeightedLine("warden_dont_peep", "Don't make a peep. I'm serious.", BotDialogueSounds.LINE_WARDEN_DONT_PEEP, WEIGHT_COMMON),
            new WeightedLine("warden_not_what_think", "Please tell me that's not what I think it is.", BotDialogueSounds.LINE_WARDEN_NOT_WHAT_THINK, WEIGHT_UNCOMMON),
            new WeightedLine("warden_sneak", "Sneak. Don't sneak loudly. Just sneak.", BotDialogueSounds.LINE_WARDEN_SNEAK, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] UNDERGROUND_LINES = new WeightedLine[] {
            new WeightedLine("underground_yearn_mines", "I yearn for the mines.", BotDialogueSounds.LINE_UNDERGROUND_YEARN_MINES, WEIGHT_COMMON)
    };

    private static final WeightedLine[] ENDERMAN_SPOTTED_LINES = new WeightedLine[] {
            new WeightedLine("enderman_spotted_dont_look", "Don't look at it.", BotDialogueSounds.LINE_ENDERMAN_SPOTTED_DONT_LOOK, WEIGHT_COMMON)
    };

    // Sniffer — rare cute mob, "Dinosaur." reaction + alternate "cutest thing" line.
    private static final WeightedLine[] SNIFFER_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("sniffer_dinosaur", "Dinosaur.", BotDialogueSounds.LINE_SNIFFER_DINOSAUR, WEIGHT_COMMON),
            new WeightedLine("sniffer_cutest_thing", "That's the cutest thing I've ever seen.", BotDialogueSounds.LINE_SNIFFER_CUTEST_THING, WEIGHT_COMMON)
    };

    // Nether neighbours — three separate pools, each its own cooldown so the bot
    // can react to a piglin brute even if it just commented on a hoglin.
    private static final WeightedLine[] ZOMBIFIED_PIGLIN_LINES = new WeightedLine[] {
            new WeightedLine("zombified_piglin_porkchop", "What's up, porkchop?", BotDialogueSounds.LINE_ZOMBIFIED_PIGLIN_PORKCHOP, WEIGHT_COMMON)
    };

    private static final WeightedLine[] HOGLIN_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("hoglin_bacon_spree", "If they give us gravel again I'm going on a bacon spree.", BotDialogueSounds.LINE_HOGLIN_BACON_SPREE, WEIGHT_COMMON)
    };

    private static final WeightedLine[] PIGLIN_BRUTE_LINES = new WeightedLine[] {
            new WeightedLine("piglin_brute_bigger", "That one's bigger than the others!", BotDialogueSounds.LINE_PIGLIN_BRUTE_BIGGER, WEIGHT_COMMON)
    };

    // Aquatic ambient — squid is mundane, glow squid is the rare standout.
    private static final WeightedLine[] SQUID_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("squid_just_a", "Just a squid.", BotDialogueSounds.LINE_SQUID_JUST_A, WEIGHT_COMMON)
    };

    private static final WeightedLine[] GLOW_SQUID_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("glow_squid_pretty", "Pretty.", BotDialogueSounds.LINE_GLOW_SQUID_PRETTY, WEIGHT_COMMON)
    };

    // Dolphin sighting — only fires when bot is NOT in a boat, since the existing
    // in_boat_dolphin_nearby trigger covers the escort scenario with its own line.
    private static final WeightedLine[] DOLPHIN_SIGHTED_LINES = new WeightedLine[] {
            new WeightedLine("dolphin_did_you_see", "Did you see that dolphin?", BotDialogueSounds.LINE_DOLPHIN_DID_YOU_SEE, WEIGHT_COMMON)
    };

    // Vex — raid / woodland mansion mob. Combat-relevant so faster trigger.
    private static final WeightedLine[] VEX_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("vex_goblins_wings", "Goblins with wings! Duck and cover!", BotDialogueSounds.LINE_VEX_GOBLINS_WINGS, WEIGHT_COMMON)
    };

    // Fox or ocelot near chickens — combined-condition pool.
    private static final WeightedLine[] FOX_OCELOT_CHICKEN_LINES = new WeightedLine[] {
            new WeightedLine("fox_ocelot_near_chickens", "Don't let it near the chickens.", BotDialogueSounds.LINE_FOX_OCELOT_NEAR_CHICKENS, WEIGHT_COMMON)
    };

    // Panda variant-specific pools — keyed off PandaEntity.getMainGene().
    private static final WeightedLine[] PANDA_WORRIED_LINES = new WeightedLine[] {
            new WeightedLine("panda_worried", "That panda looks stressed.", BotDialogueSounds.LINE_PANDA_WORRIED, WEIGHT_COMMON)
    };

    private static final WeightedLine[] PANDA_LAZY_LINES = new WeightedLine[] {
            new WeightedLine("panda_lazy", "Lying down on the job, eh?", BotDialogueSounds.LINE_PANDA_LAZY, WEIGHT_COMMON)
    };

    private static final WeightedLine[] PANDA_BROWN_LINES = new WeightedLine[] {
            new WeightedLine("panda_brown", "A brown panda. That's special.", BotDialogueSounds.LINE_PANDA_BROWN, WEIGHT_COMMON)
    };

    private static final WeightedLine[] PANDA_AGGRESSIVE_LINES = new WeightedLine[] {
            new WeightedLine("panda_aggressive", "That one looks angry. Give it space.", BotDialogueSounds.LINE_PANDA_AGGRESSIVE, WEIGHT_COMMON)
    };

    private static final WeightedLine[] PANDA_PLAYFUL_LINES = new WeightedLine[] {
            new WeightedLine("panda_playful", "Look at it go!", BotDialogueSounds.LINE_PANDA_PLAYFUL, WEIGHT_COMMON)
    };

    private static final WeightedLine[] PANDA_WEAK_LINES = new WeightedLine[] {
            new WeightedLine("panda_weak", "Aw, that little one looks fragile.", BotDialogueSounds.LINE_PANDA_WEAK, WEIGHT_COMMON)
    };

    // Guardian (regular) — proximity vs laser-charging are split into two pools.
    private static final WeightedLine[] GUARDIAN_PROXIMITY_LINES = new WeightedLine[] {
            new WeightedLine("guardian_staring_right", "It's staring right at me.", BotDialogueSounds.LINE_GUARDIAN_STARING_RIGHT, WEIGHT_COMMON),
            new WeightedLine("guardian_dont_like", "I don't like the way it's looking at us.", BotDialogueSounds.LINE_GUARDIAN_DONT_LIKE, WEIGHT_COMMON)
    };

    private static final WeightedLine[] GUARDIAN_CHARGING_LINES = new WeightedLine[] {
            new WeightedLine("guardian_glowing", "Why is it glowing at me?!", BotDialogueSounds.LINE_GUARDIAN_GLOWING, WEIGHT_COMMON),
            new WeightedLine("guardian_beam_hurt", "That beam is gonna hurt — move!", BotDialogueSounds.LINE_GUARDIAN_BEAM_HURT, WEIGHT_COMMON)
    };

    // Elder guardian — rare, more emphatic. Includes the iconic Mining Fatigue gag.
    private static final WeightedLine[] ELDER_GUARDIAN_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("elder_guardian_boss", "That one's the boss. We should leave.", BotDialogueSounds.LINE_ELDER_GUARDIAN_BOSS, WEIGHT_COMMON),
            new WeightedLine("elder_guardian_fatigue", "Mining Fatigue incoming, I just know it.", BotDialogueSounds.LINE_ELDER_GUARDIAN_FATIGUE, WEIGHT_COMMON)
    };

    // Mob-crusher anti-cruelty pool — editorial line, fires when ≥6 same-type
    // passives are crammed into one block-cell within an 8-block scan.
    private static final WeightedLine[] MOB_CRUSHER_LINES = new WeightedLine[] {
            new WeightedLine("mob_crusher_humane", "Totally humane.", BotDialogueSounds.LINE_MOB_CRUSHER_HUMANE, WEIGHT_COMMON),
            new WeightedLine("mob_crusher_cruelty_free", "100% cruelty free.", BotDialogueSounds.LINE_MOB_CRUSHER_CRUELTY_FREE, WEIGHT_COMMON),
            new WeightedLine("mob_crusher_nether_place", "There's a special place in the Nether for whoever built this.", BotDialogueSounds.LINE_MOB_CRUSHER_NETHER_PLACE, WEIGHT_RARE)
    };

    // Redstone-machine proximity — fires near complex contraptions outside bases.
    private static final WeightedLine[] REDSTONE_MACHINE_LINES = new WeightedLine[] {
            new WeightedLine("redstone_machine_tech", "Tech-o-no-lo-hee-ah", BotDialogueSounds.LINE_REDSTONE_MACHINE_TECH, WEIGHT_COMMON),
            new WeightedLine("redstone_machine_hell_and_back", "We literally went to hell and back to build this.", BotDialogueSounds.LINE_REDSTONE_MACHINE_HELL_AND_BACK, WEIGHT_UNCOMMON)
    };

    // Cute-animal pool — fires near various untamed cute mobs. Dispatched AFTER the
    // more-specific triggers above (fox+chicken, panda variants) so those win first.
    private static final WeightedLine[] CUTE_ANIMAL_LINES = new WeightedLine[] {
            new WeightedLine("cute_animal_keep_it", "Can we keep it?", BotDialogueSounds.LINE_CUTE_ANIMAL_KEEP_IT, WEIGHT_UNCOMMON),
            new WeightedLine("cute_animal_look_at_it", "Look at it!", BotDialogueSounds.LINE_CUTE_ANIMAL_LOOK_AT_IT, WEIGHT_COMMON),
            new WeightedLine("cute_animal_want_one", "I want one of those.", BotDialogueSounds.LINE_CUTE_ANIMAL_WANT_ONE, WEIGHT_UNCOMMON),
            new WeightedLine("cute_animal_so_cute", "It's so cute.", BotDialogueSounds.LINE_CUTE_ANIMAL_SO_CUTE, WEIGHT_COMMON)
    };

    // Wandering-trader proximity — flat pool of all trader topic lines. The
    // questing-mode "tell me about traders" path still goes through
    // SurvivalCompanionQuestService; this is the ambient gate so the lines
    // also fire in admin worlds when the bot actually sees a trader.
    private static final WeightedLine[] TRADER_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("topic_trader_first_1", "A salesman approaches. Stay strong.", BotDialogueSounds.LINE_TOPIC_TRADER_FIRST_1, WEIGHT_UNCOMMON),
            new WeightedLine("topic_trader_first_2", "That's either commerce... or a scam with llamas.", BotDialogueSounds.LINE_TOPIC_TRADER_FIRST_2, WEIGHT_UNCOMMON),
            new WeightedLine("topic_trader_ask_1", "He sells junk with confidence. I respect it.", BotDialogueSounds.LINE_TOPIC_TRADER_ASK_1, WEIGHT_COMMON),
            new WeightedLine("topic_trader_ask_2", "He travels the world to offer... two ferns and a bucket. Iconic.", BotDialogueSounds.LINE_TOPIC_TRADER_ASK_2, WEIGHT_COMMON),
            new WeightedLine("topic_trader_ask_3", "The llamas are the real security detail.", BotDialogueSounds.LINE_TOPIC_TRADER_ASK_3, WEIGHT_COMMON),
            new WeightedLine("topic_trader_memory_1", "Remember the wandering trader? He had the vibe of a side quest.", BotDialogueSounds.LINE_TOPIC_TRADER_MEMORY_1, WEIGHT_RARE),
            new WeightedLine("topic_trader_memory_2", "I still think about those llamas. Professional posture.", BotDialogueSounds.LINE_TOPIC_TRADER_MEMORY_2, WEIGHT_RARE)
    };

    private static final WeightedLine[] LLAMA_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("topic_llama_first", "Those llamas look like they've seen things.", BotDialogueSounds.LINE_TOPIC_LLAMA_FIRST, WEIGHT_UNCOMMON),
            new WeightedLine("topic_llama_ask_1", "They're not pets. They're coworkers.", BotDialogueSounds.LINE_TOPIC_LLAMA_ASK_1, WEIGHT_COMMON),
            new WeightedLine("topic_llama_ask_2", "If one spits at me, I'm taking it personally.", BotDialogueSounds.LINE_TOPIC_LLAMA_ASK_2, WEIGHT_COMMON),
            new WeightedLine("topic_llama_ask_3", "Respect the llama. Fear the llama.", BotDialogueSounds.LINE_TOPIC_LLAMA_ASK_3, WEIGHT_COMMON),
            new WeightedLine("topic_llama_memory", "We met a trader's llamas once. I'm still not over it.", BotDialogueSounds.LINE_TOPIC_LLAMA_MEMORY, WEIGHT_RARE)
    };

    // TNT-proximity sequence — 4 escalating pleas, fired in order ~1 s apart
    // while the bot is near primed TNT and the commander is watching.
    private static final WeightedLine[] TNT_SEQUENCE_LINES = new WeightedLine[] {
            new WeightedLine("tnt_wouldnt", "You wouldn't.", BotDialogueSounds.LINE_TNT_WOULDNT, WEIGHT_COMMON),
            new WeightedLine("tnt_wont", "You won't.", BotDialogueSounds.LINE_TNT_WONT, WEIGHT_COMMON),
            new WeightedLine("tnt_talk_over", "Come on, let's talk this over.", BotDialogueSounds.LINE_TNT_TALK_OVER, WEIGHT_COMMON),
            new WeightedLine("tnt_terminator", "I promise I won't make any more Terminator jokes.", BotDialogueSounds.LINE_TNT_TERMINATOR, WEIGHT_COMMON)
    };

    // End-ship "captain now" sequence — 3-stage conditional gag. Initial
    // solicitation → branches on whether the commander looks at the bot.
    private static final WeightedLine[] END_SHIP_LOOK_AT_ME_LINES = new WeightedLine[] {
            new WeightedLine("end_ship_look_at_me", "Hey, hey, look at me.", BotDialogueSounds.LINE_END_SHIP_LOOK_AT_ME, WEIGHT_COMMON)
    };

    private static final WeightedLine[] END_SHIP_CAPTAIN_LINES = new WeightedLine[] {
            new WeightedLine("end_ship_captain", "I'm the captain now.", BotDialogueSounds.LINE_END_SHIP_CAPTAIN, WEIGHT_COMMON)
    };

    private static final WeightedLine[] END_SHIP_RUINED_JOKE_LINES = new WeightedLine[] {
            new WeightedLine("end_ship_ruined_joke", "That ruined the joke.", BotDialogueSounds.LINE_END_SHIP_RUINED_JOKE, WEIGHT_COMMON)
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
        // Wake-up is not a meme — the 10-minute service-level cooldown in
        // BotWakeUpDialogueService is the real gate. Keep this short so successive
        // sleep cycles within a play session each get a chance to fire.
        TRIGGER_COOLDOWN_MS.put("wake_up", 30_000L);
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
        TRIGGER_COOLDOWN_MS.put("trader_nearby", COOLDOWN_180S_MS);
        TRIGGER_COOLDOWN_MS.put("llama_nearby", COOLDOWN_180S_MS);
        // End-ship gag: very long cooldown — it's a one-shot meme per session.
        TRIGGER_COOLDOWN_MS.put("end_ship_look_at_me", 30L * 60L * 1000L);
        // The follow-up lines use their own short cooldowns because they're
        // gated by the state machine anyway.
        TRIGGER_COOLDOWN_MS.put("end_ship_captain", 5_000L);
        TRIGGER_COOLDOWN_MS.put("end_ship_ruined_joke", 5_000L);
        // TNT plea cooldowns: the sequence itself is long-cooldowned; individual
        // line cooldowns are short because the sequence paces them.
        TRIGGER_COOLDOWN_MS.put("tnt_sequence_start", 5L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("tnt_sequence_step", 500L);
        TRIGGER_COOLDOWN_MS.put("outdoor_ambient", COOLDOWN_META_MS);
        // May 2026 — wild mob proximity reactions
        TRIGGER_COOLDOWN_MS.put("sniffer_nearby", 5L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("zombified_piglin_nearby", COOLDOWN_180S_MS);
        TRIGGER_COOLDOWN_MS.put("hoglin_nearby", COOLDOWN_180S_MS);
        TRIGGER_COOLDOWN_MS.put("piglin_brute_nearby", COOLDOWN_180S_MS);
        TRIGGER_COOLDOWN_MS.put("squid_nearby", 5L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("glow_squid_nearby", 5L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("dolphin_sighted", COOLDOWN_180S_MS);
        TRIGGER_COOLDOWN_MS.put("vex_nearby", COOLDOWN_180S_MS);
        TRIGGER_COOLDOWN_MS.put("fox_ocelot_near_chickens", 5L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("panda_worried", 5L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("panda_lazy", 5L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("panda_brown", 10L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("panda_aggressive", COOLDOWN_180S_MS);
        TRIGGER_COOLDOWN_MS.put("cute_animal_nearby", 8L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("guardian_proximity", 5L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("guardian_charging", 60_000L);
        TRIGGER_COOLDOWN_MS.put("elder_guardian_nearby", 8L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("mob_crusher", 10L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("redstone_machine_nearby", COOLDOWN_90S_MS);
        // May 2026 — golem proximity reactions
        TRIGGER_COOLDOWN_MS.put("snow_golem_nearby", 5L * 60L * 1000L);
        TRIGGER_COOLDOWN_MS.put("iron_golem_daisy", 30L * 60L * 1000L);
        // May 2026 — gated smells-terrible (moved out of generic cave chatter pool)
        TRIGGER_COOLDOWN_MS.put("smells_terrible", 5L * 60L * 1000L);
        // May 2026 — warden-specific avoidance dialogue (warden encounters are rare/special)
        TRIGGER_COOLDOWN_MS.put("warden_nearby", 3L * 60L * 1000L);
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
            if (tryOutdoorAmbient(bot, world, state, inCombat)) {
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
            if (tryTraderOrLlamaNearby(bot, world, state)) {
                continue;
            }
            if (trySnifferNearby(bot, world, state)) {
                continue;
            }
            if (tryNetherNeighbourNearby(bot, world, state)) {
                continue;
            }
            if (tryAquaticAmbient(bot, world, state)) {
                continue;
            }
            if (tryVexNearby(bot, world, state)) {
                continue;
            }
            if (tryFoxOcelotNearChickens(bot, world, state)) {
                continue;
            }
            if (tryPandaProximity(bot, world, state)) {
                continue;
            }
            if (tryCuteAnimalNearby(bot, world, state)) {
                continue;
            }
            if (tryGuardianFamily(bot, world, state)) {
                continue;
            }
            if (tryMobCrusher(bot, world, state)) {
                continue;
            }
            if (tryRedstoneMachineNearby(bot, world, state)) {
                continue;
            }
            if (trySnowGolemNearby(bot, world, state)) {
                continue;
            }
            if (tryIronGolemDaisy(bot, world, state)) {
                continue;
            }
            // Warden first — it's the highest-priority bail-out line.
            if (tryWardenNearby(bot, world, state)) {
                continue;
            }
            if (trySmellsTerrible(bot, world, state)) {
                continue;
            }
            if (tryEndShipSequence(bot, world, state, nowTick)) {
                continue;
            }
            if (tryTntSequence(bot, world, state, nowTick)) {
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

    /** Wake-up variant that bypasses the global {@code isRecentlyShown} suppression.
     *  Used by {@link BotWakeUpDialogueService} after its 40-tick scheduling delay —
     *  the schedule was set on the wake edge specifically, so it should win over any
     *  unrelated ambient line that happened to fire during the sleep-screen fade. */
    public static boolean playWakeUpForced(ServerPlayerEntity bot) {
        return tryTrigger(bot, "wake_up", WAKE_LINES, null, true);
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
            case "trader_nearby", "trader" -> tryTrigger(bot, "trader_nearby", TRADER_NEARBY_LINES, lineId, true);
            case "llama_nearby", "llama" -> tryTrigger(bot, "llama_nearby", LLAMA_NEARBY_LINES, lineId, true);
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
                boolean hasDolphin = world.getEntitiesByClass(
                        DolphinEntity.class, dolphinBox, d -> d != null && d.isAlive())
                        .stream().anyMatch(d -> EntityVisibilityUtil.canSee(bot, d));
                if (hasDolphin && RNG.nextDouble() < 0.28D
                        && tryTrigger(bot, "in_boat_dolphin_nearby", BOAT_DOLPHIN_LINES, null, false)) {
                    EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
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

    /** Outdoor-only ambient pool: lines like "I saw a bird" and "walked past this tree"
     *  only make sense with the sky overhead. Gated on sky-visible, hasSkyLight (so it
     *  doesn't fire in nether/end), and rarer than AMBIENT_LINES to curb overuse.
     *  The "walked past this tree" line additionally requires a log within 12 blocks —
     *  it was firing in deserts/plains/beaches where the bot had never seen a tree. */
    private static boolean tryOutdoorAmbient(ServerPlayerEntity bot, ServerWorld world, TriggerState state, boolean inCombat) {
        if (inCombat || bot.hasVehicle()) return false;
        if (!world.getDimension().hasSkyLight()) return false;
        if (!world.isSkyVisible(bot.getBlockPos().up())) return false;
        if (RNG.nextDouble() > 0.008D) return false;
        WeightedLine[] pool = hasNearbyLog(world, bot.getBlockPos(), 12)
                ? OUTDOOR_AMBIENT_LINES
                : OUTDOOR_AMBIENT_SKY_ONLY_LINES;
        return tryTrigger(bot, "outdoor_ambient", pool, null, false);
    }

    /** Early-exit scan for any block in {@link BlockTags#LOGS} within {@code radius}
     *  of the bot. Cheap enough to call on the rare outdoor-ambient roll path. */
    private static boolean hasNearbyLog(ServerWorld world, BlockPos center, int radius) {
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dy = -2; dy <= 6; dy++) {
            for (int dx = -radius; dx <= radius; dx += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (world.getBlockState(cursor).isIn(BlockTags.LOGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        // Edible-food gate: only fire "smells good"-style lines when the nearby lit furnace/
        // smoker/blast-furnace actually has a food item in its input or output, or the lit
        // campfire has food on it. Smelting cobblestone or sand should not trigger food lines.
        if (!CookingReactionService.isNearActivelyCookingFood(world, bot.getBlockPos())) {
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
                                      boolean bypassSuppression) {
        if (bot == null || triggerKey == null || pool == null || pool.length == 0) {
            return false;
        }
        UUID botId = bot.getUuid();

        // Don't overwrite a line recently shown by another system (cooking, food-giving, etc.).
        // Bypass for debug-triggered lines and for scheduled wake-up lines (where the schedule
        // was set on the wake edge specifically and should win over any unrelated ambient line
        // that happened to fire during the sleep-screen fade).
        if (!bypassSuppression && CompanionOverheadDialogueService.isRecentlyShown(botId)) {
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
            if (isEntityFacing(pig, bot) && EntityVisibilityUtil.canSee(bot, pig)) {
                if (RNG.nextDouble() < 0.015D) {
                    return tryTrigger(bot, "pig_staring", PIG_STARING_LINES, null, false);
                }
                break;
            }
        }
        return false;
    }

    /** Snow golem proximity — fires occasionally when a vanilla snow golem is within 12 blocks
     *  with line of sight. Cooldown 5 min so a snow-golem army doesn't drown out other reactions. */
    private static boolean trySnowGolemNearby(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        Box box = bot.getBoundingBox().expand(12.0D, 4.0D, 12.0D);
        List<Entity> snowmen = world.getOtherEntities(bot, box,
                e -> e instanceof SnowGolemEntity && e.isAlive());
        if (snowmen.isEmpty()) return false;
        for (Entity sm : snowmen) {
            if (EntityVisibilityUtil.canSee(bot, sm)) {
                if (RNG.nextDouble() < 0.020D) {
                    return tryTrigger(bot, "snow_golem_nearby", SNOW_GOLEM_NEARBY_LINES, null, false);
                }
                break;
            }
        }
        return false;
    }

    /** Iron golem with daisy — bot offers a poppy or oxeye daisy if it has one. Models the
     *  vanilla villager-children-give-poppies-to-iron-golems behavior: bot turns toward the
     *  golem, removes one flower from inventory, spawns it as an item entity at the golem's
     *  feet, and fires a dialogue line. One-shot per golem UUID. Skipped on angry golems —
     *  approaching one with a flower right now is poor judgment.
     *
     *  We don't make the golem visually "hold" the flower — that's hardcoded vanilla AI tied
     *  to villager goals and isn't reachable without mixins. The dropped flower at the
     *  golem's feet is the visual payoff. */
    private static boolean tryIronGolemDaisy(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle() || bot.isSneaking()) return false;
        Box box = bot.getBoundingBox().expand(5.0D, 3.0D, 5.0D);
        List<Entity> golems = world.getOtherEntities(bot, box,
                e -> e instanceof IronGolemEntity g && g.isAlive() && !g.hasAngerTime());
        if (golems.isEmpty()) return false;

        // Pick the closest un-gifted golem
        IronGolemEntity target = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : golems) {
            if (state.giftedGolems.contains(e.getUuid())) continue;
            double sq = e.squaredDistanceTo(bot);
            if (sq < bestSq) {
                bestSq = sq;
                target = (IronGolemEntity) e;
            }
        }
        if (target == null) return false;

        // Find a poppy or oxeye daisy in inventory
        int slot = -1;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack s = bot.getInventory().getStack(i);
            if (!s.isEmpty() && (s.isOf(Items.POPPY) || s.isOf(Items.OXEYE_DAISY))) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return false;

        // Low per-tick chance — this is a special, memorable interaction; don't make it routine.
        if (RNG.nextDouble() > 0.04D) return false;

        LookController.faceEntity(bot, target);

        ItemStack stack = bot.getInventory().getStack(slot);
        ItemStack offering = stack.split(1);
        Vec3d goPos = target.getEntityPos();
        ItemEntity itemEntity = new ItemEntity(world,
                goPos.x, goPos.y + 0.4, goPos.z, offering);
        itemEntity.setVelocity(0.0, 0.15, 0.0);
        itemEntity.setPickupDelay(40); // small delay so the moment reads as "given"
        world.spawnEntity(itemEntity);

        state.giftedGolems.add(target.getUuid());
        return tryTrigger(bot, "iron_golem_daisy", IRON_GOLEM_DAISY_LINES, null, false);
    }

    /** Warden proximity — fires fear/avoidance lines when a warden is within 32 blocks.
     *  Warden encounters are rare and tense, so this gets a long 3-min cooldown and a low
     *  per-tick probability — the line should land at most a couple times per encounter. */
    private static boolean tryWardenNearby(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        Box box = bot.getBoundingBox().expand(32.0D, 16.0D, 32.0D);
        boolean hasWarden = !world.getEntitiesByClass(WardenEntity.class, box,
                w -> w != null && w.isAlive()).isEmpty();
        if (!hasWarden) return false;
        if (RNG.nextDouble() > 0.04D) return false;
        return tryTrigger(bot, "warden_nearby", WARDEN_NEARBY_LINES, null, false);
    }

    /** Gated "smells terrible" — fires only when there's a contextually-coherent source of
     *  the smell nearby. Was previously firing as part of the generic cave-chatter pool any
     *  time the bot transitioned underground. New constraints (any one of):
     *  <ul>
     *    <li>zombies / slimes / witches / zombie villagers within 12 blocks</li>
     *    <li>mob spawner block within 8 blocks (dungeon vibes)</li>
     *    <li>lush_caves biome at the bot's position (musty moss smell)</li>
     *    <li>mushroom block (small or large), rooted dirt, moss block, moss carpet, clay,
     *        coarse dirt, mud, mycelium within 6 blocks</li>
     *  </ul>
     *  RNG-first to keep block scans cheap on the 99.5% bail path. */
    private static boolean trySmellsTerrible(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        // Cheap RNG bail before the expensive scans (tick rate ~1 Hz per bot here).
        if (RNG.nextDouble() > 0.005D) return false;
        if (!hasSmellyContextNearby(bot, world)) return false;
        return tryTrigger(bot, "smells_terrible", SMELLS_TERRIBLE_LINES, null, false);
    }

    /** Returns true if at least one smelly source is in scanning range of the bot.
     *  Scans are layered cheapest→most expensive: biome lookup, then 12-block mob scan,
     *  then 8-block spawner scan, then 6-block block-type scan. Bails on first hit. */
    private static boolean hasSmellyContextNearby(ServerPlayerEntity bot, ServerWorld world) {
        BlockPos origin = bot.getBlockPos();

        // Biome check (cheapest)
        try {
            RegistryEntry<Biome> biomeEntry = world.getBiome(origin);
            if (biomeEntry != null) {
                String biomeKey = biomeEntry.getKey().map(k -> k.getValue().toString()).orElse("").toLowerCase(Locale.ROOT);
                if (biomeKey.contains("lush_caves")) return true;
            }
        } catch (Exception ignored) {
        }

        // Mob scan — 12 blocks. Zombies, slimes, witches, zombie villagers all rotting/foul.
        Box mobBox = bot.getBoundingBox().expand(12.0D, 4.0D, 12.0D);
        if (!world.getOtherEntities(bot, mobBox, e ->
                e.isAlive() && (
                    e instanceof net.minecraft.entity.mob.ZombieEntity         // includes ZombieVillagerEntity, HuskEntity, DrownedEntity
                    || e instanceof net.minecraft.entity.mob.SlimeEntity       // includes MagmaCubeEntity
                    || e instanceof net.minecraft.entity.mob.WitchEntity)).isEmpty()) {
            return true;
        }

        // Spawner scan — 8 blocks (dungeon-y dank smell).
        for (BlockPos pos : BlockPos.iterate(
                origin.add(-8, -4, -8),
                origin.add(8, 4, 8))) {
            if (!world.isChunkLoaded(pos)) continue;
            if (world.getBlockState(pos).isOf(Blocks.SPAWNER)) return true;
        }

        // Block-type scan — 6 blocks. Mushrooms (small + large), rooted dirt, moss, clay,
        // coarse dirt, mud, mycelium. Mushroom_stem too since players harvest huge mushrooms.
        for (BlockPos pos : BlockPos.iterate(
                origin.add(-6, -3, -6),
                origin.add(6, 3, 6))) {
            if (!world.isChunkLoaded(pos)) continue;
            BlockState s = world.getBlockState(pos);
            if (s.isOf(Blocks.RED_MUSHROOM) || s.isOf(Blocks.BROWN_MUSHROOM)
                    || s.isOf(Blocks.RED_MUSHROOM_BLOCK) || s.isOf(Blocks.BROWN_MUSHROOM_BLOCK)
                    || s.isOf(Blocks.MUSHROOM_STEM)
                    || s.isOf(Blocks.ROOTED_DIRT)
                    || s.isOf(Blocks.MOSS_BLOCK) || s.isOf(Blocks.MOSS_CARPET)
                    || s.isOf(Blocks.CLAY)
                    || s.isOf(Blocks.COARSE_DIRT)
                    || s.isOf(Blocks.MUD)
                    || s.isOf(Blocks.MYCELIUM)) {
                return true;
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

    /**
     * TNT-proximity plea sequence. When the bot is stationary near primed
     * TNT and the commander is watching (facing the TNT or the bot), the
     * bot fires four escalating lines paced ~1 s apart:
     *
     * <ol>
     *   <li>"You wouldn't."</li>
     *   <li>"You won't."</li>
     *   <li>"Come on, let's talk this over."</li>
     *   <li>"I promise I won't make any more Terminator jokes."</li>
     * </ol>
     *
     * If the TNT despawns (explodes) mid-sequence the rest of the lines are
     * cancelled. The whole sequence is 5 min-cooldown to avoid spam on
     * TNT-heavy builds.
     */
    private static boolean tryTntSequence(ServerPlayerEntity bot, ServerWorld world, TriggerState state, long nowTick) {
        if (state.tntSequenceIndex >= 0) {
            // Sequence in flight — pace subsequent lines ~1 s apart.
            if (nowTick - state.tntLastLineTick < 20L) return true;
            if (state.tntSequenceIndex >= TNT_SEQUENCE_LINES.length) {
                state.tntSequenceIndex = -1;
                return false;
            }
            if (!hasPrimedTntNear(bot, world, 10.0)) {
                // TNT gone (exploded / removed) — bail.
                state.tntSequenceIndex = -1;
                return false;
            }
            String lineId = TNT_SEQUENCE_LINES[state.tntSequenceIndex].id;
            if (tryTrigger(bot, "tnt_sequence_step", TNT_SEQUENCE_LINES, lineId, false)) {
                state.tntSequenceIndex++;
                state.tntLastLineTick = nowTick;
            }
            return true;
        }

        // Entry: bot stationary, primed TNT nearby, commander aware.
        if (bot.hasVehicle()) return false;
        if (bot.getVelocity().lengthSquared() > 0.04D) return false;
        if (!hasPrimedTntNear(bot, world, 10.0)) return false;
        ServerPlayerEntity commander = findNearbyCommander(bot, world, 24.0);
        if (commander == null) return false;
        if (!isCommanderWatchingTnt(commander, bot, world)) return false;

        // Fire first line with the 5-minute start cooldown.
        WeightedLine[] firstOnly = new WeightedLine[] { TNT_SEQUENCE_LINES[0] };
        if (!tryTrigger(bot, "tnt_sequence_start", firstOnly, null, false)) {
            return false;
        }
        state.tntSequenceIndex = 1;
        state.tntLastLineTick = nowTick;
        return true;
    }

    private static boolean hasPrimedTntNear(ServerPlayerEntity bot, ServerWorld world, double range) {
        Box box = bot.getBoundingBox().expand(range, range, range);
        return !world.getEntitiesByClass(TntEntity.class, box, t -> t != null && t.isAlive()).isEmpty();
    }

    private static boolean isCommanderWatchingTnt(ServerPlayerEntity commander, ServerPlayerEntity bot, ServerWorld world) {
        Box box = bot.getBoundingBox().expand(10.0D, 6.0D, 10.0D);
        for (TntEntity tnt : world.getEntitiesByClass(TntEntity.class, box, t -> t != null && t.isAlive())) {
            if (isEntityFacing(commander, tnt)) return true;
        }
        // Fall back to "commander facing the bot" — they're watching either way.
        return isEntityFacing(commander, bot);
    }

    /**
     * End-ship "captain now" gag. Three-stage conditional sequence:
     *
     * <ol>
     *   <li>Solicitation: bot says "Hey, hey, look at me." when a nearby commander
     *       is NOT currently looking at the bot and the bot is perched / riding
     *       something / in The End.</li>
     *   <li>Resolution — captain: if the commander then faces the bot for ≥ 3 s
     *       (60 ticks) within a 14 s window, bot says "I'm the captain now."</li>
     *   <li>Resolution — ruined: if 14 s elapse without the commander ever looking
     *       long enough, bot says "That ruined the joke."</li>
     * </ol>
     */
    private static boolean tryEndShipSequence(ServerPlayerEntity bot, ServerWorld world, TriggerState state, long nowTick) {
        // Stage 2/3: sequence already in flight — advance or resolve.
        if (state.endShipSolicitedAtTick >= 0L) {
            long elapsed = nowTick - state.endShipSolicitedAtTick;
            ServerPlayerEntity commander = findNearbyCommander(bot, world, 32.0);
            boolean commanderLookingNow = commander != null && isEntityFacing(commander, bot);
            if (commanderLookingNow) {
                // Looking now — accumulate. Also clear the grace counter so a
                // brief look-away doesn't keep counting down past a re-look.
                state.endShipLookingTicks++;
                state.endShipLookAwayStreak = 0;
                if (state.endShipLookingTicks >= 60) {  // 3 s at 20 TPS
                    state.endShipSolicitedAtTick = -1L;
                    state.endShipLookingTicks = 0;
                    state.endShipLookAwayStreak = 0;
                    return tryTrigger(bot, "end_ship_captain", END_SHIP_CAPTAIN_LINES, null, false);
                }
            } else {
                // Look-away grace: a single-tick blink/glance (or the look-direction
                // jitter that comes from natural mouse movement) shouldn't nuke the
                // streak. Only reset after ~1 s of continuous non-looking. Without
                // this, the captain line could only fire if the commander stared
                // perfectly still for 3 s, which the user almost never does.
                state.endShipLookAwayStreak++;
                if (state.endShipLookAwayStreak >= 20) {
                    state.endShipLookingTicks = 0;
                    state.endShipLookAwayStreak = 0;
                }
            }
            if (elapsed >= 280L) {  // 14 s at 20 TPS
                state.endShipSolicitedAtTick = -1L;
                state.endShipLookingTicks = 0;
                state.endShipLookAwayStreak = 0;
                return tryTrigger(bot, "end_ship_ruined_joke", END_SHIP_RUINED_JOKE_LINES, null, false);
            }
            return true;  // still in flight; don't let other triggers steal the turn
        }

        // Stage 1: entry conditions.
        ServerPlayerEntity commander = findNearbyCommander(bot, world, 24.0);
        if (commander == null) return false;
        if (isEntityFacing(commander, bot)) return false;  // joke needs the look-away setup

        // Bot should be "perched" — riding something, OR in The End, OR genuinely
        // elevated above the commander. The previous "elevated = Y>=70 + sky visible"
        // was true everywhere outdoors above sea level, which is why the line was
        // firing at random while the user walked through the overworld. The real
        // semantic is "bot is dramatically above the commander" — require ≥3 blocks
        // of vertical lead AND sky access AND the bot stationary (no follow chase).
        boolean ridingSomething = bot.hasVehicle();
        String dim = world.getRegistryKey().getValue().toString();
        boolean inEnd = dim.contains("the_end");
        boolean elevated = !ridingSomething
                && bot.getY() >= commander.getY() + 3.0
                && world.isSkyVisible(bot.getBlockPos().up())
                && !net.wcfcarolina13.GameAI.BotEventHandler.isFollowingPlayer(bot);
        if (!ridingSomething && !inEnd && !elevated) return false;

        if (RNG.nextDouble() > 0.0008D) return false;  // very rare

        if (!tryTrigger(bot, "end_ship_look_at_me", END_SHIP_LOOK_AT_ME_LINES, null, false)) {
            return false;
        }
        EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.HERE);
        state.endShipSolicitedAtTick = nowTick;
        state.endShipLookingTicks = 0;
        return true;
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
        boolean anySpotted = false;
        for (EndermanEntity end : endermen) {
            if (isEntityFacing(bot, end) && EntityVisibilityUtil.canSee(bot, end)) {
                anySpotted = true;
                break;
            }
        }
        if (!anySpotted) return false;
        if (RNG.nextDouble() > 0.06D) return false;
        if (tryTrigger(bot, "enderman_spotted", ENDERMAN_SPOTTED_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        return false;
    }

    /** Fires sniffer_dinosaur when a live sniffer is within 12 blocks. Sniffers
     *  are rare (lush caves / sniffer egg hatches) so 5-min cooldown plus 8% roll
     *  keeps the line a remark, not background chatter. */
    private static boolean trySnifferNearby(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        Box box = bot.getBoundingBox().expand(12.0D, 6.0D, 12.0D);
        boolean any = world.getEntitiesByClass(
                SnifferEntity.class, box, s -> s != null && s.isAlive())
                .stream().anyMatch(s -> EntityVisibilityUtil.canSee(bot, s));
        if (!any) return false;
        if (RNG.nextDouble() > 0.08D) return false;
        if (tryTrigger(bot, "sniffer_nearby", SNIFFER_NEARBY_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        return false;
    }

    /** Fires the appropriate Nether-neighbour line when a piglin brute / hoglin /
     *  zombified piglin is within 12 blocks. Each mob family has its own pool +
     *  cooldown, so seeing all three in quick succession can fire all three lines.
     *  Priority: piglin brute (rarer + more remarkable) > hoglin > zombified piglin. */
    private static boolean tryNetherNeighbourNearby(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        Box box = bot.getBoundingBox().expand(12.0D, 6.0D, 12.0D);

        boolean hasBrute = world.getEntitiesByClass(
                PiglinBruteEntity.class, box, p -> p != null && p.isAlive())
                .stream().anyMatch(p -> EntityVisibilityUtil.canSee(bot, p));
        if (hasBrute && RNG.nextDouble() <= 0.08D
                && tryTrigger(bot, "piglin_brute_nearby", PIGLIN_BRUTE_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }

        boolean hasHoglin = world.getEntitiesByClass(
                HoglinEntity.class, box, h -> h != null && h.isAlive())
                .stream().anyMatch(h -> EntityVisibilityUtil.canSee(bot, h));
        if (hasHoglin && RNG.nextDouble() <= 0.06D
                && tryTrigger(bot, "hoglin_nearby", HOGLIN_NEARBY_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }

        boolean hasZpiglin = world.getEntitiesByClass(
                ZombifiedPiglinEntity.class, box, z -> z != null && z.isAlive())
                .stream().anyMatch(z -> EntityVisibilityUtil.canSee(bot, z));
        if (hasZpiglin && RNG.nextDouble() <= 0.06D
                && tryTrigger(bot, "zombified_piglin_nearby", ZOMBIFIED_PIGLIN_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        return false;
    }

    /** Aquatic ambient — squid is the mundane "Just a squid." remark, glow squid
     *  gets the rarer "Pretty." line. Glow squid takes priority when both are in
     *  range since the standout reaction beats the mundane one. Suppressed when
     *  the bot is in a boat (existing in_boat_dolphin_nearby etc. already cover
     *  on-the-water flavor). */
    private static boolean tryAquaticAmbient(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        Box box = bot.getBoundingBox().expand(12.0D, 6.0D, 12.0D);

        boolean hasGlow = world.getEntitiesByClass(
                GlowSquidEntity.class, box, g -> g != null && g.isAlive())
                .stream().anyMatch(g -> EntityVisibilityUtil.canSee(bot, g));
        if (hasGlow && RNG.nextDouble() <= 0.08D
                && tryTrigger(bot, "glow_squid_nearby", GLOW_SQUID_NEARBY_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }

        // Squid scan filters out the GlowSquidEntity subclass so the priority
        // above isn't double-counted as a "regular squid" sighting.
        boolean hasSquid = world.getEntitiesByClass(
                SquidEntity.class, box,
                s -> s != null && s.isAlive() && !(s instanceof GlowSquidEntity))
                .stream().anyMatch(s -> EntityVisibilityUtil.canSee(bot, s));
        if (hasSquid && RNG.nextDouble() <= 0.05D
                && tryTrigger(bot, "squid_nearby", SQUID_NEARBY_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }

        // Dolphin sighting — uses the existing forward-cone helper + a strict LOS
        // raycast so it fires only when the bot is actually facing AND can see a
        // dolphin (matches the "Did you SEE that dolphin?" framing).
        List<DolphinEntity> dolphins = world.getEntitiesByClass(
                DolphinEntity.class, box.expand(4.0D, 0.0D, 4.0D),
                d -> d != null && d.isAlive());
        if (!dolphins.isEmpty()) {
            boolean anySpotted = false;
            for (DolphinEntity d : dolphins) {
                if (isEntityFacing(bot, d) && EntityVisibilityUtil.canSee(bot, d)) {
                    anySpotted = true;
                    break;
                }
            }
            if (anySpotted && RNG.nextDouble() <= 0.08D
                    && tryTrigger(bot, "dolphin_sighted", DOLPHIN_SIGHTED_LINES, null, false)) {
                EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
                return true;
            }
        }
        return false;
    }

    /** Fires fox_ocelot_near_chickens when a fox or ocelot AND a chicken are
     *  both within 12 blocks of the bot. Single shared pool — both predators
     *  trigger the same line. 5-min cooldown so the bot doesn't chatter while
     *  the predator/prey pair is loitering near a coop. */
    private static boolean tryFoxOcelotNearChickens(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        Box box = bot.getBoundingBox().expand(12.0D, 6.0D, 12.0D);

        boolean hasFoxOrOcelot = world.getEntitiesByClass(
                FoxEntity.class, box, f -> f != null && f.isAlive())
                .stream().anyMatch(f -> EntityVisibilityUtil.canSee(bot, f));
        if (!hasFoxOrOcelot) {
            hasFoxOrOcelot = world.getEntitiesByClass(
                    OcelotEntity.class, box, o -> o != null && o.isAlive())
                    .stream().anyMatch(o -> EntityVisibilityUtil.canSee(bot, o));
        }
        if (!hasFoxOrOcelot) return false;

        boolean hasChicken = world.getEntitiesByClass(
                ChickenEntity.class, box, c -> c != null && c.isAlive())
                .stream().anyMatch(c -> EntityVisibilityUtil.canSee(bot, c));
        if (!hasChicken) return false;

        if (RNG.nextDouble() > 0.10D) return false;
        if (tryTrigger(bot, "fox_ocelot_near_chickens", FOX_OCELOT_CHICKEN_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        return false;
    }

    /** Redstone-machine proximity detection. Counts redstone-component blocks in
     *  a 5×5×5 box around the bot. Trigger when ≥4 components AND ≥2 distinct
     *  block types — that's the threshold above which we're confident it's a
     *  contraption rather than a single redstone door or pressure-plate. Skipped
     *  when the bot is inside a registered base, since the user's own base will
     *  almost certainly hit this threshold and we don't want chatter at home. */
    private static boolean tryRedstoneMachineNearby(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        // Skip when bot is inside a registered base — the user's own base will trigger constantly.
        if (net.wcfcarolina13.GameAI.services.BotHomeService.findBaseNearPosition(
                world.getServer(), world, bot.getBlockPos()).isPresent()) {
            return false;
        }

        BlockPos.Mutable cur = new BlockPos.Mutable();
        BlockPos center = bot.getBlockPos();
        int componentCount = 0;
        java.util.HashSet<net.minecraft.block.Block> distinctTypes = new java.util.HashSet<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    cur.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState s = world.getBlockState(cur);
                    net.minecraft.block.Block b = s.getBlock();
                    if (b == Blocks.REPEATER || b == Blocks.COMPARATOR || b == Blocks.OBSERVER
                            || b == Blocks.PISTON || b == Blocks.STICKY_PISTON
                            || b == Blocks.DISPENSER || b == Blocks.DROPPER || b == Blocks.HOPPER) {
                        componentCount++;
                        distinctTypes.add(b);
                    }
                }
            }
        }
        if (componentCount < 4 || distinctTypes.size() < 2) return false;
        if (RNG.nextDouble() > 0.20D) return false;
        return tryTrigger(bot, "redstone_machine_nearby", REDSTONE_MACHINE_LINES, null, false);
    }

    /** Mob-crusher anti-cruelty detection. Scans an 8-block box for passives of
     *  the curated set (cow / sheep / pig / chicken / villager — explicitly NOT
     *  hostiles, so skeleton/zombie grinders don't fire this), groups by entity
     *  type + integer block-cell, and fires when any single (type, cell) bucket
     *  has ≥ 6 entities — i.e., they're stuffed in a 1×1 column. 20% roll, 10-
     *  min cooldown since the line is editorial, not scan-frequent. */
    private static boolean tryMobCrusher(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        Box box = bot.getBoundingBox().expand(8.0D, 4.0D, 8.0D);
        List<Entity> passives = world.getOtherEntities(bot, box, e -> {
            if (e == null || !e.isAlive()) return false;
            return e instanceof CowEntity
                    || e instanceof SheepEntity
                    || e instanceof PigEntity
                    || e instanceof ChickenEntity
                    || e instanceof VillagerEntity;
        });
        if (passives.size() < 6) return false;

        java.util.HashMap<String, Integer> stuffingCounts = new java.util.HashMap<>();
        for (Entity e : passives) {
            BlockPos cell = e.getBlockPos();
            String key = e.getType().toString() + ":" + cell.getX() + "," + cell.getY() + "," + cell.getZ();
            stuffingCounts.merge(key, 1, Integer::sum);
        }
        int maxStuffed = 0;
        for (int n : stuffingCounts.values()) {
            if (n > maxStuffed) maxStuffed = n;
        }
        if (maxStuffed < 6) return false;
        if (RNG.nextDouble() > 0.20D) return false;
        return tryTrigger(bot, "mob_crusher", MOB_CRUSHER_LINES, null, false);
    }

    /** Guardian + elder guardian proximity reactions. Single 16-block scan, three
     *  state branches:
     *  1. Elder guardian present → wins outright (rarer, more emphatic).
     *  2. Regular guardian targeting bot or commander (hasBeamTarget && target == us)
     *     → laser-charging pool, short 60s cooldown so the bot can re-react if hit again.
     *  3. Regular guardian present but not charging at us → proximity pool, 5min cd.
     *  ElderGuardianEntity extends GuardianEntity so the regular-guardian scan
     *  filters out elder instances. */
    private static boolean tryGuardianFamily(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        Box box = bot.getBoundingBox().expand(16.0D, 8.0D, 16.0D);

        // Elder guardian wins first if present.
        boolean hasElder = world.getEntitiesByClass(
                ElderGuardianEntity.class, box, e -> e != null && e.isAlive())
                .stream().anyMatch(e -> EntityVisibilityUtil.canSee(bot, e));
        if (hasElder && RNG.nextDouble() <= 0.20D
                && tryTrigger(bot, "elder_guardian_nearby", ELDER_GUARDIAN_NEARBY_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }

        List<GuardianEntity> guardians = world.getEntitiesByClass(
                GuardianEntity.class, box,
                g -> g != null && g.isAlive() && !(g instanceof ElderGuardianEntity))
                .stream().filter(g -> EntityVisibilityUtil.canSee(bot, g)).toList();
        if (guardians.isEmpty()) return false;

        // Charging branch — fires when a guardian's beam target is the bot or commander.
        ServerPlayerEntity commander = findNearbyCommander(bot, world, 24.0);
        boolean chargingAtUs = false;
        for (GuardianEntity g : guardians) {
            if (!g.hasBeamTarget()) continue;
            net.minecraft.entity.LivingEntity target = g.getBeamTarget();
            if (target == null) continue;
            if (target == bot || (commander != null && target == commander)) {
                chargingAtUs = true;
                break;
            }
        }
        if (chargingAtUs && RNG.nextDouble() <= 0.30D
                && tryTrigger(bot, "guardian_charging", GUARDIAN_CHARGING_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }

        // Plain proximity branch.
        if (RNG.nextDouble() <= 0.10D
                && tryTrigger(bot, "guardian_proximity", GUARDIAN_PROXIMITY_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        return false;
    }

    /** Cute-animal "can we keep it?" pool — fires near untamed cute mobs. Runs
     *  AFTER the fox+chicken and panda-variant triggers above so those win when
     *  applicable. Pandas with NORMAL/PLAYFUL/WEAK genes (no variant line) fall
     *  through to this pool, as do tamed parrots? No — only UNTAMED parrots
     *  count, since tamed parrots already trigger LINE_PARROT_NEARBY_NICE_BIRD.
     *  Long cooldown (8 min) per spec — this is a flavor remark, not chatter. */
    private static boolean tryCuteAnimalNearby(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        Box box = bot.getBoundingBox().expand(12.0D, 6.0D, 12.0D);
        boolean any = world.getOtherEntities(bot, box, e -> {
            if (e == null || !e.isAlive()) return false;
            if (e instanceof FoxEntity) return true;
            if (e instanceof OcelotEntity) return true;
            if (e instanceof AxolotlEntity) return true;
            if (e instanceof BeeEntity) return true;
            if (e instanceof RabbitEntity) return true;
            if (e instanceof TurtleEntity) return true;
            if (e instanceof PandaEntity) return true;
            if (e instanceof ParrotEntity p) return !p.isTamed();
            return false;
        }).stream().anyMatch(e -> EntityVisibilityUtil.canSee(bot, e));
        if (!any) return false;
        if (RNG.nextDouble() > 0.05D) return false;
        if (tryTrigger(bot, "cute_animal_nearby", CUTE_ANIMAL_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        return false;
    }

    /** Variant-keyed panda proximity reactions. Iterates all pandas in a 12-block
     *  scan, picks the highest-priority gene, and fires the matching line.
     *  Priority: BROWN (rarest IRL) > AGGRESSIVE (combat-relevant) > WORRIED >
     *  LAZY. NORMAL / PLAYFUL / WEAK don't have lines and are skipped. Each
     *  variant has its own cooldown so seeing a brown panda and then a worried
     *  panda later can fire both lines. */
    private static boolean tryPandaProximity(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        Box box = bot.getBoundingBox().expand(12.0D, 6.0D, 12.0D);
        List<PandaEntity> pandas = world.getEntitiesByClass(
                PandaEntity.class, box, p -> p != null && p.isAlive())
                .stream().filter(p -> EntityVisibilityUtil.canSee(bot, p)).toList();
        if (pandas.isEmpty()) return false;

        // Walk the list once to find the highest-priority gene present.
        boolean hasBrown = false;
        boolean hasAggressive = false;
        boolean hasWorried = false;
        boolean hasLazy = false;
        boolean hasPlayful = false;
        boolean hasWeak = false;
        for (PandaEntity p : pandas) {
            switch (p.getMainGene()) {
                case BROWN -> hasBrown = true;
                case AGGRESSIVE -> hasAggressive = true;
                case WORRIED -> hasWorried = true;
                case LAZY -> hasLazy = true;
                case PLAYFUL -> hasPlayful = true;
                case WEAK -> hasWeak = true;
                default -> { /* NORMAL gene is unremarkable */ }
            }
        }

        if (hasBrown && RNG.nextDouble() <= 0.20D
                && tryTrigger(bot, "panda_brown", PANDA_BROWN_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        if (hasAggressive && RNG.nextDouble() <= 0.10D
                && tryTrigger(bot, "panda_aggressive", PANDA_AGGRESSIVE_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        if (hasWeak && RNG.nextDouble() <= 0.10D
                && tryTrigger(bot, "panda_weak", PANDA_WEAK_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        if (hasWorried && RNG.nextDouble() <= 0.08D
                && tryTrigger(bot, "panda_worried", PANDA_WORRIED_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        if (hasPlayful && RNG.nextDouble() <= 0.07D
                && tryTrigger(bot, "panda_playful", PANDA_PLAYFUL_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        if (hasLazy && RNG.nextDouble() <= 0.06D
                && tryTrigger(bot, "panda_lazy", PANDA_LAZY_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        return false;
    }

    /** Fires vex_goblins_wings when a live vex is within 12 blocks. Combat-
     *  relevant (raid / woodland mansion) so the roll is faster (12%) than the
     *  ambient pools — bot should react promptly when one shows up. */
    private static boolean tryVexNearby(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        Box box = bot.getBoundingBox().expand(12.0D, 6.0D, 12.0D);
        boolean any = world.getEntitiesByClass(
                VexEntity.class, box, v -> v != null && v.isAlive())
                .stream().anyMatch(v -> EntityVisibilityUtil.canSee(bot, v));
        if (!any) return false;
        if (RNG.nextDouble() > 0.12D) return false;
        if (tryTrigger(bot, "vex_nearby", VEX_NEARBY_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        return false;
    }

    /** Fires topic_trader_* or topic_llama_* when a wandering trader or llama is
     *  within 14 blocks. Trader takes priority when both are present (llama is
     *  usually leashed to the trader anyway). Cooldown-throttled to 3 min per
     *  family so the bot doesn't chatter while traveling alongside a caravan. */
    private static boolean tryTraderOrLlamaNearby(ServerPlayerEntity bot, ServerWorld world, TriggerState state) {
        if (bot.hasVehicle()) return false;
        Box box = bot.getBoundingBox().expand(14.0D, 6.0D, 14.0D);
        List<Entity> nearby = world.getOtherEntities(bot, box, e -> {
            if (e == null || !e.isAlive()) return false;
            EntityType<?> t = e.getType();
            return t == EntityType.WANDERING_TRADER
                    || t == EntityType.TRADER_LLAMA
                    || t == EntityType.LLAMA;
        }).stream().filter(e -> EntityVisibilityUtil.canSee(bot, e)).toList();
        if (nearby.isEmpty()) return false;

        boolean hasTrader = false;
        boolean hasLlama = false;
        for (Entity e : nearby) {
            EntityType<?> t = e.getType();
            if (t == EntityType.WANDERING_TRADER) hasTrader = true;
            else if (t == EntityType.TRADER_LLAMA || t == EntityType.LLAMA) hasLlama = true;
            if (hasTrader && hasLlama) break;
        }

        if (RNG.nextDouble() > 0.015D) return false;
        if (hasTrader) {
            if (tryTrigger(bot, "trader_nearby", TRADER_NEARBY_LINES, null, false)) {
                EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
                return true;
            }
            return false;
        }
        if (tryTrigger(bot, "llama_nearby", LLAMA_NEARBY_LINES, null, false)) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.POINT);
            return true;
        }
        return false;
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
     * Called from the server-wide block-break hook for any player. Fans out to:
     *
     * <ul>
     *   <li><b>Bot breaker:</b> tree-punch / dirt-dig self-narration ("Time to punch some
     *       trees" / "Diggy diggy hole"). Skips the dig-down warning — bots routinely
     *       mine the block beneath their feet during woodcut column descent and stump
     *       clearing, and the line text ("Never dig straight down! Are you new here?")
     *       is admonitory, not self-mockery.</li>
     *   <li><b>Real-player breaker:</b> when the broken block was directly under the
     *       player's feet, a nearby registered bot with line-of-sight reacts with the
     *       dig-down warning. The bot has to be able to see the player — no warnings
     *       through walls.</li>
     * </ul>
     */
    public static void onBotBlockBreak(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState state) {
        if (player == null || world == null || pos == null || state == null) return;

        BlockPos feet = player.getBlockPos();
        boolean directlyBelowFeet = pos.getX() == feet.getX()
                && pos.getZ() == feet.getZ()
                && pos.getY() == feet.getY() - 1;

        boolean isBot = BotEventHandler.isRegisteredBot(player);
        if (isBot) {
            // Bot self-narration. Dig-down line is excluded — see method docstring.
            if (directlyBelowFeet) return;

            if (state.isIn(BlockTags.LOGS)) {
                if (RNG.nextDouble() < 0.30D) {
                    tryTrigger(player, "tree_punch_first", TREE_PUNCH_LINES, null, false);
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
                    tryTrigger(player, "dirt_dig", DIRT_DIG_LINES, null, false);
                }
            }
            return;
        }

        // Real player breaker — companion bots react.
        // High-value ore: nearby bot claps. Pure emote, no voice line. 60s per-bot
        // cooldown via the bridge so a vein of diamonds doesn't trigger a clapping
        // frenzy.
        boolean isHighValueOre = state.isOf(Blocks.DIAMOND_ORE)
                || state.isOf(Blocks.DEEPSLATE_DIAMOND_ORE)
                || state.isOf(Blocks.EMERALD_ORE)
                || state.isOf(Blocks.DEEPSLATE_EMERALD_ORE)
                || state.isOf(Blocks.ANCIENT_DEBRIS);
        if (isHighValueOre) {
            ServerPlayerEntity clapper = findNearestVisibleBot(player, world, 16.0D);
            if (clapper != null) {
                EmotecraftBridge.playEmote(clapper, EmotecraftBridge.EmoteId.CLAP);
            }
        }

        if (!directlyBelowFeet) return;
        if (RNG.nextDouble() >= 0.35D) return;

        ServerPlayerEntity reactingBot = findNearestVisibleBot(player, world, 16.0D);
        if (reactingBot == null) return;
        if (tryTrigger(reactingBot, "dig_straight_down", DIG_DOWN_LINES, null, false)) {
            EmotecraftBridge.playEmote(reactingBot, EmotecraftBridge.EmoteId.PALM);
        }
    }

    /**
     * Returns the nearest registered bot in the same world that can see {@code source}
     * via {@link EntityVisibilityUtil#canSee}, within {@code maxDistance}, or null.
     */
    private static ServerPlayerEntity findNearestVisibleBot(ServerPlayerEntity source,
                                                            ServerWorld world,
                                                            double maxDistance) {
        if (source == null || world == null) return null;
        ServerPlayerEntity nearest = null;
        double bestDistSq = maxDistance * maxDistance;
        for (ServerPlayerEntity candidate : net.wcfcarolina13.GameAI.services.BotRegistry
                .getPlayers(world.getServer())) {
            if (candidate == source) continue;
            if (candidate.getEntityWorld() != world) continue;
            double d = candidate.squaredDistanceTo(source);
            if (d > bestDistSq) continue;
            if (!EntityVisibilityUtil.canSee(candidate, source)) continue;
            nearest = candidate;
            bestDistSq = d;
        }
        return nearest;
    }

    /** Call from /bot follow to emit a voice ack. Cooldown-throttled; safe to call often. */
    public static boolean playFollowAck(ServerPlayerEntity bot) {
        if (bot == null) return false;
        boolean fired = tryTrigger(bot, "follow_ack", FOLLOW_ACK_LINES, null, false);
        if (fired) {
            EmotecraftBridge.playEmote(bot, EmotecraftBridge.EmoteId.WAVING);
        }
        return fired;
    }

    /** Call from /bot follow stop and /bot stay to emit a voice ack. Cooldown-throttled. */
    public static boolean playStopAck(ServerPlayerEntity bot) {
        if (bot == null) return false;
        return tryTrigger(bot, "stop_ack", STOP_ACK_LINES, null, false);
    }
}
