package net.shasankp000.ChatUtils;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.BatEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.world.LightType;
import net.minecraft.util.math.BlockPos;
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.BotHomeService;
import net.shasankp000.GameAI.services.BotIdleHobbiesService;
import net.shasankp000.GameAI.services.CompanionOverheadDialogueService;
import net.shasankp000.GameAI.services.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides ambient "talking to self" chatter for bots when idle.
 * 
 * <p>This creates an immersive atmosphere where bots occasionally vocalize
 * idle thoughts, similar to how Minecraft villagers hum to themselves.
 * 
 * <p>Uses the {@link BotMoodManager} emotional state system to select
 * appropriate dialogue based on the bot's current mood:
 * <ul>
 *   <li>STRESSED - Alert/tense sounds after combat</li>
 *   <li>INJURED - Pain/fatigue sounds when hurt</li>
 *   <li>HUNGRY - Food-related comments when low on food</li>
 *   <li>CONTENT - Relaxed/happy sounds when all is well</li>
 *   <li>NEUTRAL - General idle chatter</li>
 * </ul>
 * 
 * <p>Chatter is infrequent (every 2-5 minutes when idle) and only occurs when:
 * <ul>
 *   <li>Voiced dialogue is enabled for the bot</li>
 *   <li>Bot is truly idle (not following, guarding, patrolling, or running a task)</li>
 *   <li>Bot is in a supported dimension (Overworld/Nether/End)</li>
 * </ul>
 */
public final class BotAmbientChatter {

    private static final Logger LOGGER = LoggerFactory.getLogger("ambient-chatter");
    private static final Random RNG = new Random();

    // Chatter frequency: minimum and maximum delay in ticks between chatter attempts
    // 2400 ticks = 2 minutes, 6000 ticks = 5 minutes
    private static final long MIN_DELAY_TICKS = 2400L;
    private static final long MAX_DELAY_TICKS = 6000L;

    // Don't chatter at night (after sunset) - it would be weird
    private static final int DONT_CHATTER_AFTER_TOD = 12_500;
    private static final int DONT_CHATTER_BEFORE_TOD = 500;

    // Per-bot next chatter tick
    private static final Map<UUID, Long> NEXT_CHATTER_TICK = new ConcurrentHashMap<>();

    /**
     * Session gate: do not allow the first ambient line to fire until a non-bot player ("commander")
     * has joined and we've waited a short grace period for resources/UI to settle.
     */
    private static volatile long FIRST_COMMANDER_JOIN_TICK = -1L;

    // A small extra delay after commander join before *any* ambient chatter can play.
    // 20 ticks = 1 second.
    private static final long COMMANDER_JOIN_GRACE_TICKS = 200L; // ~10 seconds

    // Event-based triggers should never spam; enforce a small minimum gap between any ambient lines.
    private static final long MIN_GAP_BETWEEN_AMBIENT_TICKS = 100L; // ~5 seconds

    // Cooldowns for specific environment "event" triggers.
    private static final long EVENT_COOLDOWN_MINOR_TICKS = 1200L;   // ~60 seconds
    private static final long EVENT_COOLDOWN_SPECIAL_TICKS = 2400L; // ~2 minutes

    // Overworld-only weather and time reminders should feel occasional.
    private static final long EVENT_COOLDOWN_WEATHER_TICKS = 2400L;      // ~2 minutes
    private static final long EVENT_COOLDOWN_SUNSET_SOON_TICKS = 4800L;  // ~4 minutes

    // "Not near a recent bed" window for adventuring checks.
    private static final long RECENT_SLEEP_WINDOW_MS = 20L * 60L * 1000L; // 20 minutes

    // Biome flavor should be rare; biome borders can be crossed frequently while exploring.
    private static final long EVENT_COOLDOWN_BIOME_TICKS = 1800L; // ~90 seconds

    // How often we do heavier environment scanning for event triggers.
    private static final long ENV_SCAN_INTERVAL_TICKS = 80L; // ~4 seconds

    // Darkness should require both low block light and low sky light for a sustained period.
    // This avoids false positives under daytime tree canopies.
    private static final int DARK_BLOCK_LIGHT_MAX = 2;
    private static final int DARK_SKY_LIGHT_MAX = 3;
    private static final long DARK_SUSTAIN_TICKS = 160L; // ~8 seconds

    private static final Map<UUID, BotAmbientState> BOT_STATE = new ConcurrentHashMap<>();

    // Idle chatter sounds - things the bot might say when standing around (NEUTRAL mood)
    private static final SoundEvent[] IDLE_CHATTER = {
            // Heavier weight on less repetitive lines.
            BotDialogueSounds.LINE_IDLE_TAKING_IT_EASY,
            BotDialogueSounds.LINE_IDLE_TAKING_IT_EASY,
            BotDialogueSounds.LINE_IDLE_ENJOYING_CALM,
            BotDialogueSounds.LINE_IDLE_ENJOYING_CALM,
            BotDialogueSounds.LINE_IDLE_HERE_IF_NEEDED,
            BotDialogueSounds.LINE_IDLE_HERE_IF_NEEDED,
            // Mix in a couple of "context" lines to widen variety.
            BotDialogueSounds.LINE_CONTEXT_LISTENING,
            BotDialogueSounds.LINE_CONTEXT_BREATHER_SOMETIMES,
            BotDialogueSounds.LINE_CONTEXT_CAMPFIRE_WONDERS,
            // Keep these, but at lower effective weight.
            BotDialogueSounds.LINE_IDLE_ALL_QUIET,
            BotDialogueSounds.LINE_IDLE_STILL_STANDING,
    };

        // Ambient / cave chatter (used when underground or in cave-like conditions)
        private static final SoundEvent[] AMBIENT_CAVE_CHATTER = {
            BotDialogueSounds.LINE_AMBIENT_HEARD_SOMETHING,
            BotDialogueSounds.LINE_AMBIENT_DID_YOU_HEAR,
            BotDialogueSounds.LINE_AMBIENT_SOMETHING_MOVED,
            BotDialogueSounds.LINE_AMBIENT_NOT_ALONE,
            BotDialogueSounds.LINE_AMBIENT_SMELLS_TERRIBLE,
            BotDialogueSounds.LINE_AMBIENT_DONT_LIKE_THIS,
            BotDialogueSounds.LINE_AMBIENT_CREEPY,
            BotDialogueSounds.LINE_AMBIENT_CAVE_DEEP,
        };

    // Context-aware chatter - things the bot might muse about (NEUTRAL mood variant)
    private static final SoundEvent[] CONTEXT_CHATTER = {
            BotDialogueSounds.LINE_CONTEXT_BREATHER_SOMETIMES,
            BotDialogueSounds.LINE_CONTEXT_CAMPFIRE_WONDERS,
            BotDialogueSounds.LINE_CONTEXT_LISTENING,
    };

    // Fishing-related context sounds (used when bot recently fished)
    private static final SoundEvent[] FISHING_CONTEXT_CHATTER = {
            BotDialogueSounds.LINE_CONTEXT_FISH_EARLIER,
            BotDialogueSounds.LINE_CONTEXT_SMELLS_FISH,
            BotDialogueSounds.LINE_CONTEXT_FISH_COOPERATING,
    };

    // Campfire/hangout-related context sounds (used when bot recently hung out)
    private static final SoundEvent[] HANGOUT_CONTEXT_CHATTER = {
            BotDialogueSounds.LINE_CONTEXT_WARMING_EARLIER,
            BotDialogueSounds.LINE_CONTEXT_BREATHER_SOMETIMES,
            BotDialogueSounds.LINE_CONTEXT_CAMPFIRE_WONDERS,
    };

    // Health-related sounds when bot is injured (INJURED mood)
    private static final SoundEvent[] INJURED_CHATTER = {
            BotDialogueSounds.LINE_STATUS_NEED_BREATHER,
            BotDialogueSounds.LINE_STATUS_NOT_BEST,
            BotDialogueSounds.LINE_STATUS_TOO_MANY_HITS,
            BotDialogueSounds.LINE_WARNING_BANGED_UP,
            BotDialogueSounds.LINE_WARNING_NOT_FULL_STRENGTH,
    };

    // Hunger-related sounds when bot is hungry (HUNGRY mood)
    private static final SoundEvent[] HUNGRY_CHATTER = {
            BotDialogueSounds.LINE_STATUS_HUNGRY,
            BotDialogueSounds.LINE_STATUS_FIND_FOOD,
            BotDialogueSounds.LINE_STATUS_SNACK_TIME,
    };

    // Alert/tense sounds after recent combat (STRESSED mood)
    private static final SoundEvent[] STRESSED_CHATTER = {
            BotDialogueSounds.LINE_COMBAT_STANDING_DOWN,
            BotDialogueSounds.LINE_STATUS_NEED_BREATHER,
            BotDialogueSounds.LINE_STATUS_TOO_MANY_HITS,
            BotDialogueSounds.LINE_WARNING_BANGED_UP,
    };

        // Companion banter (rare, intended for FOLLOW mode)
        private static final SoundEvent[] BANTER_CHATTER = {
            BotDialogueSounds.LINE_BANTER_GOT_YOUR_BACK,
            BotDialogueSounds.LINE_BANTER_STAY_SHARP,
            BotDialogueSounds.LINE_BANTER_IF_YELL_RUN,
            BotDialogueSounds.LINE_BANTER_CREEPER_KIDDING,
        };

        // Darkness-related chatter (used when light is low)
        private static final SoundEvent[] DARK_CHATTER = {
            BotDialogueSounds.LINE_DARK_CANT_SEE,
            BotDialogueSounds.LINE_DARK_WHERE_ARE_YOU,
            BotDialogueSounds.LINE_DARK_NEED_LIGHT,
            BotDialogueSounds.LINE_DARK_TOO_DARK,
            BotDialogueSounds.LINE_DARK_TORCH_PLEASE,
        };

        // Amethyst geode chatter (used when near amethyst blocks underground)
        private static final SoundEvent[] AMETHYST_CHATTER = {
            BotDialogueSounds.LINE_AMBIENT_AMETHYST_BEAUTIFUL,
            BotDialogueSounds.LINE_AMBIENT_AMETHYST_SPARKLY,
            BotDialogueSounds.LINE_AMBIENT_AMETHYST_GEODE,
        };

        // Bat encounter chatter (used when bats nearby in dark/underground areas)
        private static final SoundEvent[] BAT_CHATTER = {
            BotDialogueSounds.LINE_AMBIENT_BAT_STARTLED,
            BotDialogueSounds.LINE_AMBIENT_BAT_WINGS,
            BotDialogueSounds.LINE_AMBIENT_BAT_CREEPY,
        };

        // Dripstone chatter (used near dripstone in caves)
        private static final SoundEvent[] DRIPSTONE_CHATTER = {
            BotDialogueSounds.LINE_AMBIENT_DRIPSTONE_CAREFUL,
            BotDialogueSounds.LINE_AMBIENT_DRIPSTONE_SHARP,
            BotDialogueSounds.LINE_AMBIENT_DRIPSTONE_DRIPPING,
        };

        // Deepslate chatter (used in deep dark areas with lots of deepslate)
        private static final SoundEvent[] DEEPSLATE_CHATTER = {
            BotDialogueSounds.LINE_AMBIENT_DEEPSLATE_COLD,
            BotDialogueSounds.LINE_AMBIENT_DEEPSLATE_DEEP,
            BotDialogueSounds.LINE_AMBIENT_DEEPSLATE_ANCIENT,
        };

        // Wildlife chatter (used on surface/daytime)
        private static final SoundEvent[] WILDLIFE_CHATTER = {
            BotDialogueSounds.LINE_WILDLIFE_HEARD_BIRD,
            BotDialogueSounds.LINE_WILDLIFE_SAW_COW,
            BotDialogueSounds.LINE_WILDLIFE_PIG_NEARBY,
            BotDialogueSounds.LINE_WILDLIFE_SHEEP_AROUND,
            BotDialogueSounds.LINE_WILDLIFE_CHICKEN,
            BotDialogueSounds.LINE_WILDLIFE_NICE_DAY,
            BotDialogueSounds.LINE_WILDLIFE_HEARD_WOLF,
        };

        // Nether ambience (used when in the Nether)
        private static final SoundEvent[] NETHER_CHATTER = {
            BotDialogueSounds.LINE_NETHER_HOT,
            BotDialogueSounds.LINE_NETHER_LAVA,
            BotDialogueSounds.LINE_NETHER_GHASTS,
            BotDialogueSounds.LINE_NETHER_PIGLINS,
            BotDialogueSounds.LINE_NETHER_SOUL_SAND,
            BotDialogueSounds.LINE_NETHER_FORTRESS,
            BotDialogueSounds.LINE_NETHER_BASTION,
        };

        // Nether entry callouts (used once in a while when entering the Nether)
        private static final SoundEvent[] NETHER_ENTRY_CHATTER = {
            BotDialogueSounds.LINE_NETHER_ENTER_1,
            BotDialogueSounds.LINE_NETHER_ENTER_2,
        };

        // The End ambience (used when in The End)
        private static final SoundEvent[] END_CHATTER = {
            BotDialogueSounds.LINE_END_EERIE,
            BotDialogueSounds.LINE_END_VOID,
            BotDialogueSounds.LINE_END_ISLANDS,
            BotDialogueSounds.LINE_END_CHORUS,
            BotDialogueSounds.LINE_END_ENDERMEN,
            BotDialogueSounds.LINE_END_KEEP_EYES_UP,
            BotDialogueSounds.LINE_END_CITY,
            BotDialogueSounds.LINE_END_GATEWAY,
        };

        // The End entry callouts (used once in a while when entering The End)
        private static final SoundEvent[] END_ENTRY_CHATTER = {
            BotDialogueSounds.LINE_END_ENTER_1,
            BotDialogueSounds.LINE_END_ENTER_2,
        };

        // Portal proximity (Overworld)
        private static final SoundEvent[] NETHER_PORTAL_OW_CHATTER = {
            BotDialogueSounds.LINE_PORTAL_NETHER_OVERWORLD_1,
            BotDialogueSounds.LINE_PORTAL_NETHER_OVERWORLD_2,
        };
        private static final SoundEvent[] END_PORTAL_OW_CHATTER = {
            BotDialogueSounds.LINE_PORTAL_END_OVERWORLD_1,
            BotDialogueSounds.LINE_PORTAL_END_OVERWORLD_2,
        };

        // Weird portals (wrong dimension)
        private static final SoundEvent[] WEIRD_END_IN_NETHER_CHATTER = {
            BotDialogueSounds.LINE_WEIRD_PORTAL_END_IN_NETHER_1,
            BotDialogueSounds.LINE_WEIRD_PORTAL_END_IN_NETHER_2,
        };
        private static final SoundEvent[] WEIRD_NETHER_IN_END_CHATTER = {
            BotDialogueSounds.LINE_WEIRD_PORTAL_NETHER_IN_END_1,
            BotDialogueSounds.LINE_WEIRD_PORTAL_NETHER_IN_END_2,
        };

    // Relaxed/happy sounds when all is well (CONTENT mood)
    private static final SoundEvent[] CONTENT_CHATTER = {
            BotDialogueSounds.LINE_IDLE_ENJOYING_CALM,
            BotDialogueSounds.LINE_IDLE_TAKING_IT_EASY,
            BotDialogueSounds.LINE_CONTEXT_BREATHER_SOMETIMES,
            BotDialogueSounds.LINE_CONTEXT_CAMPFIRE_WONDERS,
            BotDialogueSounds.LINE_CONTEXT_LISTENING,
            BotDialogueSounds.LINE_IDLE_HERE_IF_NEEDED,
            BotDialogueSounds.LINE_IDLE_ALL_QUIET,
    };

    private BotAmbientChatter() {
    }

    private static final class BotAmbientState {
        boolean initialized;

        // Avoid immediate repeats in scheduled chatter.
        String lastScheduledSoundId;

        // Dimension tracking
        String lastWorldId;
        long lastEnterNetherTick;
        long lastEnterEndTick;

        // Biome tracking (Nether/End flavor)
        String lastBiomePath;
        long lastBiomeFlavorTick;

        // Cheap environment flags (can be updated each tick)
        boolean wasUnderground;
        boolean wasDark;

        // Overworld weather (sky-exposed only)
        int lastWeatherKind = -1; // 0 clear, 1 rain, 2 snow, 3 thunder
        long lastWeatherEventTick;

        // Overworld time reminder
        long lastSunsetSoonDay = -1;
        long lastSunsetSoonEventTick;

        // Heavier environment flags (only updated on scan interval)
        boolean hadAmethyst;
        boolean hadBats;
        boolean hadDripstone;
        boolean hadDeepslate;

        // Nether/End environment flags (scan interval)
        boolean hadNetherLava;
        boolean hadNetherSoulSand;
        boolean hadNetherNetherBricks;
        boolean hadNetherBlackstone;
        boolean hadNetherGhast;
        boolean hadNetherPiglins;

        boolean hadEndChorus;
        boolean hadEndGateway;
        boolean hadEndCity;
        boolean hadEndermen;

        // Portal proximity flags (Overworld)
        boolean hadNetherPortalOW;
        long lastNetherPortalOWEventTick;
        boolean hadEndPortalOW;
        long lastEndPortalOWEventTick;

        // Weird portal flags (wrong dimension)
        boolean hadEndPortalNether;
        long lastEndPortalNetherEventTick;
        boolean hadNetherPortalEnd;
        long lastNetherPortalEndEventTick;

        long lastEnvScanTick;

        // Cooldowns
        long lastAnyAmbientTick;
        long lastEnterCaveTick;
        long lastEnterDarkTick;
        long darkSinceTick = -1L;
        long lastAmethystEventTick;
        long lastBatEventTick;
        long lastDripstoneEventTick;
        long lastDeepslateEventTick;

        long lastNetherLavaEventTick;
        long lastNetherSoulSandEventTick;
        long lastNetherFortressEventTick;
        long lastNetherBlackstoneEventTick;
        long lastNetherGhastEventTick;
        long lastNetherPiglinsEventTick;

        long lastEndChorusEventTick;
        long lastEndGatewayEventTick;
        long lastEndCityEventTick;
        long lastEndermenEventTick;
    }

    private static String biomePath(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return "";
        }
        try {
            return world.getBiome(pos)
                    .getKey()
                    .map(k -> k.getValue().getPath())
                    .orElse("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static SoundEvent biomeFlavorSound(boolean isNether, boolean isEnd, String biomePath) {
        if (biomePath == null || biomePath.isBlank()) {
            return null;
        }

        if (isNether) {
            return switch (biomePath) {
                case "crimson_forest" -> BotDialogueSounds.LINE_NETHER_BIOME_CRIMSON_FOREST;
                case "warped_forest" -> BotDialogueSounds.LINE_NETHER_BIOME_WARPED_FOREST;
                case "soul_sand_valley" -> BotDialogueSounds.LINE_NETHER_BIOME_SOUL_SAND_VALLEY;
                case "basalt_deltas" -> BotDialogueSounds.LINE_NETHER_BIOME_BASALT_DELTAS;
                case "nether_wastes" -> BotDialogueSounds.LINE_NETHER_BIOME_WASTES;
                default -> null;
            };
        }

        if (isEnd) {
            return switch (biomePath) {
                case "the_end" -> BotDialogueSounds.LINE_END_BIOME_MAIN_ISLAND;
                case "end_highlands" -> BotDialogueSounds.LINE_END_BIOME_HIGHLANDS;
                case "end_midlands" -> BotDialogueSounds.LINE_END_BIOME_MIDLANDS;
                case "end_barrens" -> BotDialogueSounds.LINE_END_BIOME_BARRENS;
                case "small_end_islands" -> BotDialogueSounds.LINE_END_BIOME_SMALL_ISLANDS;
                default -> null;
            };
        }

        return null;
    }

    /**
     * Clears all scheduler state. Should be called when the server world changes.
     */
    public static void resetSession() {
        NEXT_CHATTER_TICK.clear();
        BOT_STATE.clear();
        FIRST_COMMANDER_JOIN_TICK = -1L;
    }

    /**
     * Called every server tick to potentially trigger ambient chatter.
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long nowTick = server.getTicks();

        // Establish the first commander join tick (first non-bot player seen this session).
        if (FIRST_COMMANDER_JOIN_TICK < 0L) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (p != null && !(p instanceof net.shasankp000.Entity.createFakePlayer)) {
                    FIRST_COMMANDER_JOIN_TICK = nowTick;
                    break;
                }
            }
        }

        // If no commander has joined yet, do not allow ambient chatter at all.
        if (FIRST_COMMANDER_JOIN_TICK < 0L) {
            return;
        }

        long minAllowedAmbientTick = FIRST_COMMANDER_JOIN_TICK + COMMANDER_JOIN_GRACE_TICKS;

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }

            // Check if voiced dialogue is enabled
            String botName = bot.getName().getString();
            ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getEffectiveBotControl(botName);
            if (settings == null || !settings.isVoicedDialogue()) {
                continue;
            }

            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }

            // Only in supported dimensions
            var worldKey = world.getRegistryKey();
            if (worldKey != World.OVERWORLD && worldKey != World.NETHER && worldKey != World.END) {
                continue;
            }

            // Don't chatter while sleeping
            if (bot.isSleeping()) {
                continue;
            }

            // Allow chatter when IDLE or FOLLOW mode (not during GUARD/PATROL/combat)
            BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);
            if (mode != BotEventHandler.Mode.IDLE && mode != BotEventHandler.Mode.FOLLOW) {
                continue;
            }

            // Don't compete with active tasks
            if (TaskService.hasActiveTask(bot.getUuid())) {
                continue;
            }

            // Check if underground or in dark area - allow environment chatter anytime
            BlockPos pos = bot.getBlockPos();
            int y = pos.getY();
            int blockLight = 15;
            try {
                blockLight = world.getLightLevel(LightType.BLOCK, pos);
            } catch (Exception ignored) {}
            
            boolean isUnderground = y < 60;
            boolean isDark = blockLight <= 4;
            
            // Time-of-day check is only relevant in the Overworld.
            if (worldKey == World.OVERWORLD) {
                int tod = (int) (world.getTimeOfDay() % 24_000L);
                boolean isDaytime = tod < DONT_CHATTER_AFTER_TOD && tod >= DONT_CHATTER_BEFORE_TOD;

                // Skip surface/idle chatter at night, but always allow environment-aware sounds
                if (!isDaytime && !isUnderground && !isDark) {
                    continue;
                }
            }

            UUID botUuid = bot.getUuid();
            BotAmbientState state = BOT_STATE.computeIfAbsent(botUuid, id -> new BotAmbientState());

            // Gate: never allow the very first ambient line to fire until after commander join + grace.
            if (nowTick < minAllowedAmbientTick) {
                // Also ensure we don't have "nextChatter = 0" causing an immediate play right after grace.
                NEXT_CHATTER_TICK.putIfAbsent(botUuid, minAllowedAmbientTick + randomDelay());
                continue;
            }

            // Event-based triggers (state transitions / new discoveries) run alongside the long-delay scheduler.
            boolean eventPlayed = maybeTriggerEnvironmentEvents(bot, world, nowTick, state);

            // Ensure scheduled chatter does not default to immediate playback.
            NEXT_CHATTER_TICK.putIfAbsent(botUuid, nowTick + randomDelay());

            long nextChatter = NEXT_CHATTER_TICK.getOrDefault(botUuid, nowTick + randomDelay());

            // Handle server restart: if next tick is way in the future, reset it
            if (nowTick < 4_000L && nextChatter - nowTick > MAX_DELAY_TICKS * 2) {
                nextChatter = nowTick + randomDelay();
                NEXT_CHATTER_TICK.put(botUuid, nextChatter);
            }

            if (nowTick < nextChatter) {
                continue;
            }

            // Time to maybe chatter!
            // Pick a context-aware sound based on bot's current state
            // Avoid back-to-back ambient lines if we just spoke due to an event trigger.
            if (eventPlayed || (nowTick - state.lastAnyAmbientTick) < MIN_GAP_BETWEEN_AMBIENT_TICKS) {
                NEXT_CHATTER_TICK.put(botUuid, nowTick + randomDelay());
                continue;
            }

            SoundEvent sound = pickChatterSound(bot, state, nowTick);

            // Avoid repeating the exact same scheduled clip back-to-back.
            if (sound != null && state.lastScheduledSoundId != null) {
                String soundId = sound.id().toString();
                if (soundId.equals(state.lastScheduledSoundId)) {
                    // Re-roll a few times to find a different line.
                    for (int i = 0; i < 4; i++) {
                        SoundEvent reroll = pickChatterSound(bot, state, nowTick);
                        if (reroll != null && !reroll.id().toString().equals(state.lastScheduledSoundId)) {
                            sound = reroll;
                            break;
                        }
                    }
                }
            }
            
            if (sound != null) {
                // Play the sound (respects voiced dialogue setting internally)
                if (BotDialoguePlayer.playSoundForBot(bot, sound)) {
                    state.lastAnyAmbientTick = nowTick;
                    state.lastScheduledSoundId = sound.id().toString();
                    LOGGER.debug("Ambient chatter for {}: {}", botName, sound.id().getPath());
                    // Show overhead text for the played sound.
                    String text = DialogueTextMapper.textForSound(sound);
                    if (text != null) {
                        CompanionOverheadDialogueService.showOverheadLine(bot, text, 3_000, 48.0, "ambient", null);
                    }
                }
            }

            // Schedule next chatter attempt
            NEXT_CHATTER_TICK.put(botUuid, nowTick + randomDelay());
        }
    }

    /**
     * Fire a small set of event-based ambient triggers (entering cave, entering darkness,
     * newly detecting special cave features). These are separate from the 2–5 minute scheduler.
     */
    private static boolean maybeTriggerEnvironmentEvents(ServerPlayerEntity bot, ServerWorld world, long nowTick, BotAmbientState state) {
        if (bot == null || world == null || state == null) {
            return false;
        }

        // Global anti-spam: don't speak again too quickly.
        if (state.initialized && (nowTick - state.lastAnyAmbientTick) < MIN_GAP_BETWEEN_AMBIENT_TICKS) {
            return false;
        }

        BlockPos pos = bot.getBlockPos();
        int y = pos.getY();

        String currentBiomePath = biomePath(world, pos);

        int blockLight = safeLightLevel(world, LightType.BLOCK, pos, 15);
        int skyLight = safeLightLevel(world, LightType.SKY, pos, 15);

        boolean isUnderground = y < 60;
        boolean isLowLight = blockLight <= 4;
        boolean isDarkNow = blockLight <= DARK_BLOCK_LIGHT_MAX && skyLight <= DARK_SKY_LIGHT_MAX;
        boolean darkForEvents = updateAndCheckSustainedDarkness(state, nowTick, isDarkNow);
        boolean isDeepUnderground = y < 0;

        var worldKey = world.getRegistryKey();
        boolean isOverworld = worldKey == World.OVERWORLD;
        boolean isNether = worldKey == World.NETHER;
        boolean isEnd = worldKey == World.END;

        // Dimension transitions can cause false "enter cave" / "enter dark" triggers.
        // Detect a dimension change and reset the baseline, optionally firing a themed entry line.
        String worldId = world.getRegistryKey().getValue().toString();
        if (state.lastWorldId != null && !state.lastWorldId.equals(worldId)) {
            state.lastWorldId = worldId;

            // Reset biome baseline so we don't treat a dimension swap as a "biome change".
            state.lastBiomePath = currentBiomePath;

            // Reset baseline so the next tick doesn't immediately think we "entered" darkness/caves.
            state.wasUnderground = isUnderground;
            state.wasDark = darkForEvents;
            state.hadAmethyst = false;
            state.hadBats = false;
            state.hadDripstone = false;
            state.hadDeepslate = false;

            state.hadNetherLava = false;
            state.hadNetherSoulSand = false;
            state.hadNetherNetherBricks = false;
            state.hadNetherBlackstone = false;
            state.hadNetherGhast = false;
            state.hadNetherPiglins = false;

            state.hadEndChorus = false;
            state.hadEndGateway = false;
            state.hadEndCity = false;
            state.hadEndermen = false;

            state.hadNetherPortalOW = false;
            state.hadEndPortalOW = false;
            state.hadEndPortalNether = false;
            state.hadNetherPortalEnd = false;

            // Reset overworld-only baselines.
            state.lastWeatherKind = -1;
            state.lastSunsetSoonDay = -1;

            // Try a rare, themed entry callout (special cooldown).
            // Suppress if a dimension-handoff overhead line was already shown recently.
            if (!CompanionOverheadDialogueService.isRecentlyShown(bot.getUuid())) {
                if (isNether && (nowTick - state.lastEnterNetherTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.80f) {
                    if (playEvent(bot, NETHER_ENTRY_CHATTER[RNG.nextInt(NETHER_ENTRY_CHATTER.length)])) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastEnterNetherTick = nowTick;
                        return true;
                    }
                }
                if (isEnd && (nowTick - state.lastEnterEndTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.85f) {
                    if (playEvent(bot, END_ENTRY_CHATTER[RNG.nextInt(END_ENTRY_CHATTER.length)])) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastEnterEndTick = nowTick;
                        return true;
                    }
                }
            }

            return false;
        }

        // Heavier scans (blocks/entities) only every few seconds.
        boolean doScan = !state.initialized || (nowTick - state.lastEnvScanTick) >= ENV_SCAN_INTERVAL_TICKS;
        boolean hasAmethyst = state.hadAmethyst;
        boolean hasBats = state.hadBats;
        boolean hasDripstone = state.hadDripstone;
        boolean hasDeepslate = state.hadDeepslate;

        boolean hasNetherLava = state.hadNetherLava;
        boolean hasNetherSoulSand = state.hadNetherSoulSand;
        boolean hasNetherNetherBricks = state.hadNetherNetherBricks;
        boolean hasNetherBlackstone = state.hadNetherBlackstone;
        boolean hasNetherGhast = state.hadNetherGhast;
        boolean hasNetherPiglins = state.hadNetherPiglins;

        boolean hasEndChorus = state.hadEndChorus;
        boolean hasEndGateway = state.hadEndGateway;
        boolean hasEndCity = state.hadEndCity;
        boolean hasEndermen = state.hadEndermen;

        boolean hasNetherPortalOW = state.hadNetherPortalOW;
        boolean hasEndPortalOW = state.hadEndPortalOW;
        boolean hasEndPortalNether = state.hadEndPortalNether;
        boolean hasNetherPortalEnd = state.hadNetherPortalEnd;

        if (doScan) {
            state.lastEnvScanTick = nowTick;

            // Overworld-only cave features
            if (isOverworld) {
            hasAmethyst = isUnderground && hasNearbyAmethyst(world, pos);
            hasBats = (isUnderground || isLowLight) && hasNearbyBats(world, pos);
            hasDripstone = isUnderground && isLowLight && hasNearbyDripstone(world, pos);
            hasDeepslate = isDeepUnderground && isLowLight && hasNearbyDeepslate(world, pos);
            // Portal proximity (Overworld)
            hasNetherPortalOW = hasNearbyBlock(world, pos, 10, Blocks.NETHER_PORTAL);
            hasEndPortalOW = hasNearbyBlock(world, pos, 12,
                Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL);
            } else {
            hasAmethyst = false;
            hasBats = false;
            hasDripstone = false;
            hasDeepslate = false;
            hasNetherPortalOW = false;
            hasEndPortalOW = false;
            }

            // Nether features
            if (isNether) {
            hasNetherLava = hasNearbyBlock(world, pos, 7,
                Blocks.LAVA
            );
            hasNetherSoulSand = hasNearbyBlock(world, pos, 7,
                Blocks.SOUL_SAND,
                Blocks.SOUL_SOIL
            );
            hasNetherNetherBricks = hasNearbyBlock(world, pos, 8,
                Blocks.NETHER_BRICKS,
                Blocks.NETHER_BRICK_FENCE,
                Blocks.NETHER_BRICK_STAIRS,
                Blocks.NETHER_BRICK_SLAB,
                Blocks.RED_NETHER_BRICKS
            );
            hasNetherBlackstone = hasNearbyBlock(world, pos, 8,
                Blocks.BLACKSTONE,
                Blocks.POLISHED_BLACKSTONE,
                Blocks.POLISHED_BLACKSTONE_BRICKS,
                Blocks.CHISELED_POLISHED_BLACKSTONE,
                Blocks.GILDED_BLACKSTONE
            );

            hasNetherGhast = hasNearbyEntityType(world, pos, 28,
                EntityType.GHAST
            );
            hasNetherPiglins = hasNearbyEntityType(world, pos, 18,
                EntityType.PIGLIN,
                EntityType.PIGLIN_BRUTE
            );
            // Weird: End portal in the Nether
            hasEndPortalNether = hasNearbyBlock(world, pos, 12,
                Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL);
            } else {
            hasNetherLava = false;
            hasNetherSoulSand = false;
            hasNetherNetherBricks = false;
            hasNetherBlackstone = false;
            hasNetherGhast = false;
            hasNetherPiglins = false;
            hasEndPortalNether = false;
            }

            // End features
            if (isEnd) {
            hasEndChorus = hasNearbyBlock(world, pos, 10,
                Blocks.CHORUS_PLANT,
                Blocks.CHORUS_FLOWER
            );
            hasEndGateway = hasNearbyBlock(world, pos, 14,
                Blocks.END_GATEWAY
            );
            hasEndCity = hasNearbyBlock(world, pos, 16,
                Blocks.PURPUR_BLOCK,
                Blocks.PURPUR_PILLAR,
                Blocks.END_ROD
            );
            hasEndermen = hasNearbyEntityType(world, pos, 18,
                EntityType.ENDERMAN
            );
            // Weird: Nether portal in The End
            hasNetherPortalEnd = hasNearbyBlock(world, pos, 10, Blocks.NETHER_PORTAL);
            } else {
            hasEndChorus = false;
            hasEndGateway = false;
            hasEndCity = false;
            hasEndermen = false;
            hasNetherPortalEnd = false;
            }
        }

        // First time seeing this bot in this session: initialize baseline, do not trigger.
        if (!state.initialized) {
            state.initialized = true;
            state.lastWorldId = world.getRegistryKey().getValue().toString();
            state.lastBiomePath = currentBiomePath;
            state.wasUnderground = isUnderground;
            state.wasDark = darkForEvents;
            state.hadAmethyst = hasAmethyst;
            state.hadBats = hasBats;
            state.hadDripstone = hasDripstone;
            state.hadDeepslate = hasDeepslate;

            state.hadNetherLava = hasNetherLava;
            state.hadNetherSoulSand = hasNetherSoulSand;
            state.hadNetherNetherBricks = hasNetherNetherBricks;
            state.hadNetherBlackstone = hasNetherBlackstone;
            state.hadNetherGhast = hasNetherGhast;
            state.hadNetherPiglins = hasNetherPiglins;

            state.hadEndChorus = hasEndChorus;
            state.hadEndGateway = hasEndGateway;
            state.hadEndCity = hasEndCity;
            state.hadEndermen = hasEndermen;

            state.hadNetherPortalOW = hasNetherPortalOW;
            state.hadEndPortalOW = hasEndPortalOW;
            state.hadEndPortalNether = hasEndPortalNether;
            state.hadNetherPortalEnd = hasNetherPortalEnd;

            // Overworld-only baselines
            state.lastWeatherKind = computeWeatherKind(world, pos);
            state.lastSunsetSoonDay = Math.floorDiv(world.getTimeOfDay(), 24_000L);

            state.lastAnyAmbientTick = nowTick;
            return false;
        }

        // ================== OVERWORLD: weather + time reminders ==================
        // Only comment on weather/time when above ground and sky-exposed.
        boolean skyExposed = isOverworld && !isUnderground && !isLowLight && isSkyVisible(world, pos);

        if (isOverworld && skyExposed) {
            // --- Weather transitions ---
            int kindNow = computeWeatherKind(world, pos);
            int kindPrev = state.lastWeatherKind;

            if (kindPrev == -1) {
                state.lastWeatherKind = kindNow;
            } else if (kindNow != kindPrev
                    && (nowTick - state.lastWeatherEventTick) >= EVENT_COOLDOWN_WEATHER_TICKS
                    && RNG.nextFloat() < 0.85f) {
                SoundEvent weatherLine = switch (kindNow) {
                    case 3 -> BotDialogueSounds.LINE_WEATHER_THUNDER;
                    case 2 -> BotDialogueSounds.LINE_WEATHER_SNOW;
                    case 1 -> BotDialogueSounds.LINE_WEATHER_RAIN;
                    default -> {
                        // If the weather cleared, prefer a "sunny" line, but keep it occasional.
                        if (kindPrev != 0 && RNG.nextFloat() < 0.70f) {
                            yield BotDialogueSounds.LINE_WEATHER_SUNNY;
                        }
                        yield null;
                    }
                };

                // Update baseline regardless; even if we don't play, we don't want to keep treating it as a "new" transition.
                state.lastWeatherKind = kindNow;

                if (weatherLine != null && playEvent(bot, weatherLine)) {
                    state.lastAnyAmbientTick = nowTick;
                    state.lastWeatherEventTick = nowTick;
                    state.wasUnderground = isUnderground;
                    state.wasDark = darkForEvents;
                    return true;
                }
            } else {
                state.lastWeatherKind = kindNow;
            }

            // --- "Sun will go down soon" reminder (after noon, only when adventuring) ---
            long day = Math.floorDiv(world.getTimeOfDay(), 24_000L);
            long tod = Math.floorMod(world.getTimeOfDay(), 24_000L);

            // After noon-ish, before late-afternoon. (Avoid overlapping with existing "late" lines nearer sunset.)
            boolean afterNoon = tod >= 7_000L && tod <= 9_500L;

            if (afterNoon
                    && state.lastSunsetSoonDay != day
                    && (nowTick - state.lastSunsetSoonEventTick) >= EVENT_COOLDOWN_SUNSET_SOON_TICKS
                    && RNG.nextFloat() < 0.55f) {
                boolean nearBase = BotHomeService.isNearAnyBase(bot, 64.0D);
                boolean nearRecentBed = BotHomeService.isNearRecentSleep(bot, 48.0D, RECENT_SLEEP_WINDOW_MS);

                if (!nearBase && !nearRecentBed) {
                    if (playEvent(bot, BotDialogueSounds.LINE_TIME_SUNSET_SOON)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastSunsetSoonEventTick = nowTick;
                        state.lastSunsetSoonDay = day;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }
            }
        }

        // Biome-flavor transitions (Nether/End). Run on scan interval to keep it cheap.
        if (doScan && (isNether || isEnd)) {
            String prev = state.lastBiomePath;
            if (prev != null && !prev.isBlank() && currentBiomePath != null && !currentBiomePath.isBlank() && !prev.equals(currentBiomePath)) {
                SoundEvent biomeLine = biomeFlavorSound(isNether, isEnd, currentBiomePath);
                if (biomeLine != null
                        && (nowTick - state.lastBiomeFlavorTick) >= EVENT_COOLDOWN_BIOME_TICKS
                        && RNG.nextFloat() < 0.80f) {
                    if (playEvent(bot, biomeLine)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastBiomeFlavorTick = nowTick;
                        state.lastBiomePath = currentBiomePath;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        // Keep other baselines updated via the normal end-of-method baseline update.
                        return true;
                    }
                }
            }

            // Always update the tracked biome so we don't repeatedly consider the same transition.
            if (currentBiomePath != null && !currentBiomePath.isBlank()) {
                state.lastBiomePath = currentBiomePath;
            }
        }

        // Special-feature "discovery" triggers first (higher priority).
        if (doScan) {
            // Portal proximity triggers (Overworld)
            if (isOverworld) {
                if (hasNetherPortalOW && !state.hadNetherPortalOW && (nowTick - state.lastNetherPortalOWEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.70f) {
                    if (playEvent(bot, NETHER_PORTAL_OW_CHATTER[RNG.nextInt(NETHER_PORTAL_OW_CHATTER.length)])) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastNetherPortalOWEventTick = nowTick;
                        state.hadNetherPortalOW = hasNetherPortalOW;
                        state.hadEndPortalOW = hasEndPortalOW;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }

                if (hasEndPortalOW && !state.hadEndPortalOW && (nowTick - state.lastEndPortalOWEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.80f) {
                    if (playEvent(bot, END_PORTAL_OW_CHATTER[RNG.nextInt(END_PORTAL_OW_CHATTER.length)])) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastEndPortalOWEventTick = nowTick;
                        state.hadEndPortalOW = hasEndPortalOW;
                        state.hadNetherPortalOW = hasNetherPortalOW;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }
            }

            // Weird portal triggers (wrong dimension — rare, high roll chance)
            if (isNether) {
                if (hasEndPortalNether && !state.hadEndPortalNether && (nowTick - state.lastEndPortalNetherEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.90f) {
                    if (playEvent(bot, WEIRD_END_IN_NETHER_CHATTER[RNG.nextInt(WEIRD_END_IN_NETHER_CHATTER.length)])) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastEndPortalNetherEventTick = nowTick;
                        state.hadEndPortalNether = hasEndPortalNether;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }
            }
            if (isEnd) {
                if (hasNetherPortalEnd && !state.hadNetherPortalEnd && (nowTick - state.lastNetherPortalEndEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.90f) {
                    if (playEvent(bot, WEIRD_NETHER_IN_END_CHATTER[RNG.nextInt(WEIRD_NETHER_IN_END_CHATTER.length)])) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastNetherPortalEndEventTick = nowTick;
                        state.hadNetherPortalEnd = hasNetherPortalEnd;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }
            }

            // Nether/End discovery triggers first so we don't waste the opportunity on overworld cave flavor.
            if (isNether) {
                if (hasNetherNetherBricks && !state.hadNetherNetherBricks && (nowTick - state.lastNetherFortressEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.80f) {
                    if (playEvent(bot, BotDialogueSounds.LINE_NETHER_FORTRESS)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastNetherFortressEventTick = nowTick;
                        state.hadNetherNetherBricks = hasNetherNetherBricks;
                        state.hadNetherLava = hasNetherLava;
                        state.hadNetherSoulSand = hasNetherSoulSand;
                        state.hadNetherBlackstone = hasNetherBlackstone;
                        state.hadNetherGhast = hasNetherGhast;
                        state.hadNetherPiglins = hasNetherPiglins;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }

                if (hasNetherBlackstone && !state.hadNetherBlackstone && (nowTick - state.lastNetherBlackstoneEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.60f) {
                    if (playEvent(bot, BotDialogueSounds.LINE_NETHER_BASTION)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastNetherBlackstoneEventTick = nowTick;
                        state.hadNetherBlackstone = hasNetherBlackstone;
                        state.hadNetherNetherBricks = hasNetherNetherBricks;
                        state.hadNetherLava = hasNetherLava;
                        state.hadNetherSoulSand = hasNetherSoulSand;
                        state.hadNetherGhast = hasNetherGhast;
                        state.hadNetherPiglins = hasNetherPiglins;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }

                if (hasNetherSoulSand && !state.hadNetherSoulSand && (nowTick - state.lastNetherSoulSandEventTick) >= EVENT_COOLDOWN_MINOR_TICKS && RNG.nextFloat() < 0.65f) {
                    if (playEvent(bot, BotDialogueSounds.LINE_NETHER_SOUL_SAND)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastNetherSoulSandEventTick = nowTick;
                        state.hadNetherSoulSand = hasNetherSoulSand;
                        state.hadNetherLava = hasNetherLava;
                        state.hadNetherNetherBricks = hasNetherNetherBricks;
                        state.hadNetherBlackstone = hasNetherBlackstone;
                        state.hadNetherGhast = hasNetherGhast;
                        state.hadNetherPiglins = hasNetherPiglins;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }

                if (hasNetherLava && !state.hadNetherLava && (nowTick - state.lastNetherLavaEventTick) >= EVENT_COOLDOWN_MINOR_TICKS && RNG.nextFloat() < 0.60f) {
                    if (playEvent(bot, BotDialogueSounds.LINE_NETHER_LAVA)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastNetherLavaEventTick = nowTick;
                        state.hadNetherLava = hasNetherLava;
                        state.hadNetherSoulSand = hasNetherSoulSand;
                        state.hadNetherNetherBricks = hasNetherNetherBricks;
                        state.hadNetherBlackstone = hasNetherBlackstone;
                        state.hadNetherGhast = hasNetherGhast;
                        state.hadNetherPiglins = hasNetherPiglins;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }

                if (hasNetherGhast && !state.hadNetherGhast && (nowTick - state.lastNetherGhastEventTick) >= EVENT_COOLDOWN_MINOR_TICKS && RNG.nextFloat() < 0.75f) {
                    if (playEvent(bot, BotDialogueSounds.LINE_NETHER_GHASTS)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastNetherGhastEventTick = nowTick;
                        state.hadNetherGhast = hasNetherGhast;
                        state.hadNetherPiglins = hasNetherPiglins;
                        state.hadNetherLava = hasNetherLava;
                        state.hadNetherSoulSand = hasNetherSoulSand;
                        state.hadNetherNetherBricks = hasNetherNetherBricks;
                        state.hadNetherBlackstone = hasNetherBlackstone;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }

                if (hasNetherPiglins && !state.hadNetherPiglins && (nowTick - state.lastNetherPiglinsEventTick) >= EVENT_COOLDOWN_MINOR_TICKS && RNG.nextFloat() < 0.70f) {
                    if (playEvent(bot, BotDialogueSounds.LINE_NETHER_PIGLINS)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastNetherPiglinsEventTick = nowTick;
                        state.hadNetherPiglins = hasNetherPiglins;
                        state.hadNetherGhast = hasNetherGhast;
                        state.hadNetherLava = hasNetherLava;
                        state.hadNetherSoulSand = hasNetherSoulSand;
                        state.hadNetherNetherBricks = hasNetherNetherBricks;
                        state.hadNetherBlackstone = hasNetherBlackstone;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }
            }

            if (isEnd) {
                if (hasEndGateway && !state.hadEndGateway && (nowTick - state.lastEndGatewayEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.85f) {
                    if (playEvent(bot, BotDialogueSounds.LINE_END_GATEWAY)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastEndGatewayEventTick = nowTick;
                        state.hadEndGateway = hasEndGateway;
                        state.hadEndCity = hasEndCity;
                        state.hadEndChorus = hasEndChorus;
                        state.hadEndermen = hasEndermen;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }

                if (hasEndCity && !state.hadEndCity && (nowTick - state.lastEndCityEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.80f) {
                    if (playEvent(bot, BotDialogueSounds.LINE_END_CITY)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastEndCityEventTick = nowTick;
                        state.hadEndCity = hasEndCity;
                        state.hadEndGateway = hasEndGateway;
                        state.hadEndChorus = hasEndChorus;
                        state.hadEndermen = hasEndermen;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }

                if (hasEndChorus && !state.hadEndChorus && (nowTick - state.lastEndChorusEventTick) >= EVENT_COOLDOWN_MINOR_TICKS && RNG.nextFloat() < 0.70f) {
                    if (playEvent(bot, BotDialogueSounds.LINE_END_CHORUS)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastEndChorusEventTick = nowTick;
                        state.hadEndChorus = hasEndChorus;
                        state.hadEndCity = hasEndCity;
                        state.hadEndGateway = hasEndGateway;
                        state.hadEndermen = hasEndermen;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }

                if (hasEndermen && !state.hadEndermen && (nowTick - state.lastEndermenEventTick) >= EVENT_COOLDOWN_MINOR_TICKS && RNG.nextFloat() < 0.65f) {
                    if (playEvent(bot, BotDialogueSounds.LINE_END_ENDERMEN)) {
                        state.lastAnyAmbientTick = nowTick;
                        state.lastEndermenEventTick = nowTick;
                        state.hadEndermen = hasEndermen;
                        state.hadEndCity = hasEndCity;
                        state.hadEndGateway = hasEndGateway;
                        state.hadEndChorus = hasEndChorus;
                        state.wasUnderground = isUnderground;
                        state.wasDark = darkForEvents;
                        return true;
                    }
                }
            }

            if (hasAmethyst && !state.hadAmethyst && (nowTick - state.lastAmethystEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.70f) {
                if (playEvent(bot, AMETHYST_CHATTER[RNG.nextInt(AMETHYST_CHATTER.length)])) {
                    state.lastAnyAmbientTick = nowTick;
                    state.lastAmethystEventTick = nowTick;
                    state.hadAmethyst = hasAmethyst;
                    state.hadBats = hasBats;
                    state.hadDripstone = hasDripstone;
                    state.hadDeepslate = hasDeepslate;
                    state.wasUnderground = isUnderground;
                    state.wasDark = darkForEvents;
                    return true;
                }
            }

            if (hasBats && !state.hadBats && (nowTick - state.lastBatEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.65f) {
                if (playEvent(bot, BAT_CHATTER[RNG.nextInt(BAT_CHATTER.length)])) {
                    state.lastAnyAmbientTick = nowTick;
                    state.lastBatEventTick = nowTick;
                    state.hadAmethyst = hasAmethyst;
                    state.hadBats = hasBats;
                    state.hadDripstone = hasDripstone;
                    state.hadDeepslate = hasDeepslate;
                    state.wasUnderground = isUnderground;
                    state.wasDark = darkForEvents;
                    return true;
                }
            }

            if (hasDripstone && !state.hadDripstone && (nowTick - state.lastDripstoneEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.70f) {
                if (playEvent(bot, DRIPSTONE_CHATTER[RNG.nextInt(DRIPSTONE_CHATTER.length)])) {
                    state.lastAnyAmbientTick = nowTick;
                    state.lastDripstoneEventTick = nowTick;
                    state.hadAmethyst = hasAmethyst;
                    state.hadBats = hasBats;
                    state.hadDripstone = hasDripstone;
                    state.hadDeepslate = hasDeepslate;
                    state.wasUnderground = isUnderground;
                    state.wasDark = darkForEvents;
                    return true;
                }
            }

            if (hasDeepslate && !state.hadDeepslate && (nowTick - state.lastDeepslateEventTick) >= EVENT_COOLDOWN_SPECIAL_TICKS && RNG.nextFloat() < 0.60f) {
                if (playEvent(bot, DEEPSLATE_CHATTER[RNG.nextInt(DEEPSLATE_CHATTER.length)])) {
                    state.lastAnyAmbientTick = nowTick;
                    state.lastDeepslateEventTick = nowTick;
                    state.hadAmethyst = hasAmethyst;
                    state.hadBats = hasBats;
                    state.hadDripstone = hasDripstone;
                    state.hadDeepslate = hasDeepslate;
                    state.wasUnderground = isUnderground;
                    state.wasDark = darkForEvents;
                    return true;
                }
            }
        }

        // Cheaper state transitions.
        if (isOverworld && !state.wasUnderground && isUnderground && (nowTick - state.lastEnterCaveTick) >= EVENT_COOLDOWN_MINOR_TICKS && RNG.nextFloat() < 0.55f) {
            if (playEvent(bot, AMBIENT_CAVE_CHATTER[RNG.nextInt(AMBIENT_CAVE_CHATTER.length)])) {
                state.lastAnyAmbientTick = nowTick;
                state.lastEnterCaveTick = nowTick;
                state.wasUnderground = isUnderground;
                state.wasDark = darkForEvents;
                if (doScan) {
                    state.hadAmethyst = hasAmethyst;
                    state.hadBats = hasBats;
                    state.hadDripstone = hasDripstone;
                    state.hadDeepslate = hasDeepslate;
                }
                return true;
            }
        }

        if (isOverworld
                && !state.wasDark
                && darkForEvents
                && (nowTick - state.lastEnterDarkTick) >= EVENT_COOLDOWN_MINOR_TICKS
                && RNG.nextFloat() < 0.35f) {
            if (playEvent(bot, DARK_CHATTER[RNG.nextInt(DARK_CHATTER.length)])) {
                state.lastAnyAmbientTick = nowTick;
                state.lastEnterDarkTick = nowTick;
                state.wasUnderground = isUnderground;
                state.wasDark = darkForEvents;
                if (doScan) {
                    state.hadAmethyst = hasAmethyst;
                    state.hadBats = hasBats;
                    state.hadDripstone = hasDripstone;
                    state.hadDeepslate = hasDeepslate;
                }
                return true;
            }
        }

        // Update baseline.
        state.wasUnderground = isUnderground;
        state.wasDark = darkForEvents;
        if (doScan) {
            state.hadAmethyst = hasAmethyst;
            state.hadBats = hasBats;
            state.hadDripstone = hasDripstone;
            state.hadDeepslate = hasDeepslate;

            state.hadNetherLava = hasNetherLava;
            state.hadNetherSoulSand = hasNetherSoulSand;
            state.hadNetherNetherBricks = hasNetherNetherBricks;
            state.hadNetherBlackstone = hasNetherBlackstone;
            state.hadNetherGhast = hasNetherGhast;
            state.hadNetherPiglins = hasNetherPiglins;

            state.hadEndChorus = hasEndChorus;
            state.hadEndGateway = hasEndGateway;
            state.hadEndCity = hasEndCity;
            state.hadEndermen = hasEndermen;

            state.hadNetherPortalOW = hasNetherPortalOW;
            state.hadEndPortalOW = hasEndPortalOW;
            state.hadEndPortalNether = hasEndPortalNether;
            state.hadNetherPortalEnd = hasNetherPortalEnd;
        }

        return false;
    }

    private static boolean playEvent(ServerPlayerEntity bot, SoundEvent sound) {
        if (bot == null || sound == null) {
            return false;
        }
        boolean played = BotDialoguePlayer.playSoundForBot(bot, sound);
        if (played) {
            String text = DialogueTextMapper.textForSound(sound);
            if (text != null) {
                CompanionOverheadDialogueService.showOverheadLine(bot, text, 3_000, 48.0, "ambient", null);
            }
        }
        return played;
    }

    /**
     * 0 = clear, 1 = rain, 2 = snow, 3 = thunder.
     *
     * <p>Note: uses biome temperature as a lightweight proxy for rain-vs-snow at the bot's position.
     */
    private static int computeWeatherKind(ServerWorld world, BlockPos pos) {
        if (world == null) {
            return 0;
        }
        if (world.isThundering()) {
            return 3;
        }
        if (!world.isRaining()) {
            return 0;
        }

        // Raining: in cold biomes this is effectively snow.
        try {
            if (pos != null && world.getBiome(pos).value().getTemperature() < 0.15f) {
                return 2;
            }
        } catch (Exception ignored) {
            // If biome lookup fails, fall back to rain.
        }
        return 1;
    }

    private static boolean isSkyVisible(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        try {
            return world.isSkyVisible(pos.up());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long randomDelay() {
        return MIN_DELAY_TICKS + RNG.nextLong(MAX_DELAY_TICKS - MIN_DELAY_TICKS);
    }

    // How long a hobby context is considered "recent" (10 minutes)
    private static final long HOBBY_CONTEXT_WINDOW_MS = 10 * 60 * 1000L;

    /**
     * Pick a mood-aware and context-aware chatter sound based on the bot's state.
     * 
     * <p>Uses {@link BotMoodManager} to determine the bot's current mood
     * and considers recent activities for context:
     * <ul>
     *   <li>STRESSED - Alert/tense sounds (100% chance)</li>
     *   <li>INJURED - Pain/fatigue sounds (100% chance)</li>
     *   <li>HUNGRY - Food-related sounds (100% chance)</li>
     *   <li>CONTENT - Relaxed sounds, with hobby context (40% hobby, 60% content)</li>
     *   <li>NEUTRAL - Hobby context (30%), idle (50%), general context (20%)</li>
     * </ul>
     * 
     * @param bot The bot to pick a sound for
     * @return The selected SoundEvent
     */
    private static SoundEvent pickChatterSound(ServerPlayerEntity bot, BotAmbientState state, long nowTick) {
        // Environment-aware chatter first (cave/dark/wildlife)
        SoundEvent env = pickEnvironmentSound(bot, state, nowTick);
        if (env != null) return env;

        // Rare banter while following a player (kept separate from mood-based chatter)
        if (BotEventHandler.getCurrentMode(bot) == BotEventHandler.Mode.FOLLOW && RNG.nextFloat() < 0.12f) {
            return BANTER_CHATTER[RNG.nextInt(BANTER_CHATTER.length)];
        }

        // Get the bot's current emotional state from the mood manager
        EmotionalState mood = BotMoodManager.getMood(bot);
        UUID botId = bot.getUuid();
        
        LOGGER.debug("Bot {} mood: {}", bot.getName().getString(), mood.getId());

        // Check for recent hobby context
        String lastHobby = BotIdleHobbiesService.getLastHobbyName(botId);
        long lastHobbyEndMs = BotIdleHobbiesService.getLastHobbyEndMs(botId);
        boolean hasRecentHobby = lastHobby != null && !lastHobby.isBlank() 
                && lastHobbyEndMs > 0 
                && (System.currentTimeMillis() - lastHobbyEndMs) < HOBBY_CONTEXT_WINDOW_MS;

        // Select sound based on mood
        return switch (mood) {
            case STRESSED -> STRESSED_CHATTER[RNG.nextInt(STRESSED_CHATTER.length)];
            case INJURED -> INJURED_CHATTER[RNG.nextInt(INJURED_CHATTER.length)];
            case HUNGRY -> HUNGRY_CHATTER[RNG.nextInt(HUNGRY_CHATTER.length)];
            case CONTENT -> {
                // Content mood: 40% hobby context (if available), 60% content sounds
                if (hasRecentHobby && RNG.nextFloat() < 0.4f) {
                    yield pickHobbyContextSound(lastHobby);
                } else {
                    yield CONTENT_CHATTER[RNG.nextInt(CONTENT_CHATTER.length)];
                }
            }
            case NEUTRAL -> {
                float roll = RNG.nextFloat();
                // Neutral mood: 30% hobby context (if available), 50% idle, 20% general context
                if (hasRecentHobby && roll < 0.3f) {
                    yield pickHobbyContextSound(lastHobby);
                } else if (roll < 0.8f) {
                    yield IDLE_CHATTER[RNG.nextInt(IDLE_CHATTER.length)];
                } else {
                    yield CONTEXT_CHATTER[RNG.nextInt(CONTEXT_CHATTER.length)];
                }
            }
        };
    }

    /**
     * Pick an environment-specific chatter sound (cave ambient, darkness, wildlife, special blocks).
     * Returns null if no environment-specific sound should be played.
     */
    private static SoundEvent pickEnvironmentSound(ServerPlayerEntity bot, BotAmbientState state, long nowTick) {
        if (bot == null || bot.getEntityWorld() == null) return null;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return null;

        // Dimension-specific ambience should take precedence over generic cave/dark lines.
        if (world.getRegistryKey() == World.NETHER) {
            if (RNG.nextFloat() < 0.65f) {
                return NETHER_CHATTER[RNG.nextInt(NETHER_CHATTER.length)];
            }
        } else if (world.getRegistryKey() == World.END) {
            if (RNG.nextFloat() < 0.75f) {
                return END_CHATTER[RNG.nextInt(END_CHATTER.length)];
            }
        }

        BlockPos pos = bot.getBlockPos();
        int y = pos.getY();
        
        int blockLight = safeLightLevel(world, LightType.BLOCK, pos, 15);
        int skyLight = safeLightLevel(world, LightType.SKY, pos, 15);

        boolean isUnderground = y < 60;
        boolean isLowLight = blockLight <= 4;
        boolean isDarkNow = blockLight <= DARK_BLOCK_LIGHT_MAX && skyLight <= DARK_SKY_LIGHT_MAX;
        boolean darkLongEnough = updateAndCheckSustainedDarkness(state, nowTick, isDarkNow);
        boolean isDeepUnderground = y < 0; // Below Y=0 (deepslate layer)

        // Check for special environment blocks nearby (higher priority, lower chance)
        
        // Amethyst geode detection - beautiful crystals deep underground
        if (isUnderground && RNG.nextFloat() < 0.25f && hasNearbyAmethyst(world, pos)) {
            return AMETHYST_CHATTER[RNG.nextInt(AMETHYST_CHATTER.length)];
        }
        
        // Bat detection - creepy flapping in caves
        if ((isUnderground || isLowLight) && RNG.nextFloat() < 0.30f && hasNearbyBats(world, pos)) {
            return BAT_CHATTER[RNG.nextInt(BAT_CHATTER.length)];
        }
        
        // Dripstone detection - pointy stalactites/stalagmites
        if (isUnderground && isLowLight && RNG.nextFloat() < 0.25f && hasNearbyDripstone(world, pos)) {
            return DRIPSTONE_CHATTER[RNG.nextInt(DRIPSTONE_CHATTER.length)];
        }
        
        // Deepslate detection - ancient cold stone in the depths
        if (isDeepUnderground && isLowLight && RNG.nextFloat() < 0.30f && hasNearbyDeepslate(world, pos)) {
            return DEEPSLATE_CHATTER[RNG.nextInt(DEEPSLATE_CHATTER.length)];
        }

        // Generic cave/ambient chatter when underground
        if (isUnderground && RNG.nextFloat() < 0.40f) {
            return AMBIENT_CAVE_CHATTER[RNG.nextInt(AMBIENT_CAVE_CHATTER.length)];
        }

        // Dark-room chatter when light is low
        if (darkLongEnough && RNG.nextFloat() < 0.18f) {
            return DARK_CHATTER[RNG.nextInt(DARK_CHATTER.length)];
        }

        // Wildlife chatter during daytime on surface (Overworld only)
        if (world.getRegistryKey() == World.OVERWORLD) {
            long tod = Math.floorMod(world.getTimeOfDay(), 24_000L);
            if (!isUnderground && tod < 12_000 && RNG.nextFloat() < 0.20f) {
                return WILDLIFE_CHATTER[RNG.nextInt(WILDLIFE_CHATTER.length)];
            }
        }

        return null;
    }
    
    /**
     * Check if there are amethyst blocks nearby (geode detection).
     */
    private static boolean hasNearbyAmethyst(ServerWorld world, BlockPos center) {
        int radius = 8;
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dy = -radius; dy <= radius; dy += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    BlockPos checkPos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(checkPos);
                    Block block = state.getBlock();
                    if (block == Blocks.AMETHYST_BLOCK || block == Blocks.BUDDING_AMETHYST ||
                        block == Blocks.AMETHYST_CLUSTER || block == Blocks.LARGE_AMETHYST_BUD ||
                        block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.SMALL_AMETHYST_BUD) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Check if there are bats nearby.
     */
    private static boolean hasNearbyBats(ServerWorld world, BlockPos center) {
        Box searchBox = new Box(center).expand(12);
        List<Entity> entities = world.getOtherEntities(null, searchBox, e -> e instanceof BatEntity);
        return !entities.isEmpty();
    }

    private static boolean hasNearbyEntityType(ServerWorld world, BlockPos center, double radius, EntityType<?>... types) {
        if (world == null || center == null || types == null || types.length == 0) {
            return false;
        }
        Box searchBox = new Box(center).expand(radius);
        List<Entity> entities = world.getOtherEntities(null, searchBox, e -> {
            if (e == null) return false;
            EntityType<?> t = e.getType();
            for (EntityType<?> want : types) {
                if (t == want) return true;
            }
            return false;
        });
        return !entities.isEmpty();
    }

    private static boolean hasNearbyBlock(ServerWorld world, BlockPos center, int radius, Block... blocks) {
        if (world == null || center == null || blocks == null || blocks.length == 0) {
            return false;
        }
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dy = -radius; dy <= radius; dy += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    BlockPos checkPos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(checkPos);
                    Block b = state.getBlock();
                    for (Block want : blocks) {
                        if (b == want) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Check if there is dripstone nearby.
     */
    private static boolean hasNearbyDripstone(ServerWorld world, BlockPos center) {
        int radius = 6;
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dy = -radius; dy <= radius; dy += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    BlockPos checkPos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(checkPos);
                    Block block = state.getBlock();
                    if (block == Blocks.POINTED_DRIPSTONE || block == Blocks.DRIPSTONE_BLOCK) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Check if there is significant deepslate nearby (at least 5 blocks).
     */
    private static boolean hasNearbyDeepslate(ServerWorld world, BlockPos center) {
        int radius = 5;
        int count = 0;
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dy = -radius; dy <= radius; dy += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    BlockPos checkPos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(checkPos);
                    Block block = state.getBlock();
                    if (block == Blocks.DEEPSLATE || block == Blocks.COBBLED_DEEPSLATE ||
                        block == Blocks.DEEPSLATE_BRICKS || block == Blocks.DEEPSLATE_TILES ||
                        block == Blocks.POLISHED_DEEPSLATE || block == Blocks.CRACKED_DEEPSLATE_BRICKS ||
                        block == Blocks.CRACKED_DEEPSLATE_TILES || block == Blocks.CHISELED_DEEPSLATE) {
                        count++;
                        if (count >= 5) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Pick a context sound based on the bot's recent hobby.
     * 
     * @param hobby The hobby name (e.g., "fish", "hangout")
     * @return A hobby-appropriate SoundEvent
     */
    private static SoundEvent pickHobbyContextSound(String hobby) {
        if (hobby == null) {
            return CONTEXT_CHATTER[RNG.nextInt(CONTEXT_CHATTER.length)];
        }
        
        String lowerHobby = hobby.toLowerCase();
        if (lowerHobby.contains("fish")) {
            return FISHING_CONTEXT_CHATTER[RNG.nextInt(FISHING_CONTEXT_CHATTER.length)];
        } else if (lowerHobby.contains("hangout") || lowerHobby.contains("campfire")) {
            return HANGOUT_CONTEXT_CHATTER[RNG.nextInt(HANGOUT_CONTEXT_CHATTER.length)];
        }
        
        // Fallback to general context
        return CONTEXT_CHATTER[RNG.nextInt(CONTEXT_CHATTER.length)];
    }

    /**
     * Manually trigger a chatter sound for testing purposes.
     * 
     * @param bot The bot to make chatter
     * @return true if a sound was played
     */
    public static boolean triggerChatter(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        BotAmbientState state = BOT_STATE.computeIfAbsent(bot.getUuid(), id -> new BotAmbientState());
        long nowTick = bot.getCommandSource().getServer() != null
                ? bot.getCommandSource().getServer().getTicks()
                : 0L;
        SoundEvent sound = pickChatterSound(bot, state, nowTick);
        boolean played = BotDialoguePlayer.playSoundForBot(bot, sound);
        if (played && sound != null) {
            String text = DialogueTextMapper.textForSound(sound);
            if (text != null) {
                CompanionOverheadDialogueService.showOverheadLine(bot, text, 3_000, 48.0, "ambient", null);
            }
        }
        return played;
    }

    private static int safeLightLevel(ServerWorld world, LightType type, BlockPos pos, int fallback) {
        if (world == null || type == null || pos == null) {
            return fallback;
        }
        try {
            return world.getLightLevel(type, pos);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean updateAndCheckSustainedDarkness(BotAmbientState state, long nowTick, boolean darkNow) {
        if (state == null) {
            return darkNow;
        }
        if (!darkNow) {
            state.darkSinceTick = -1L;
            return false;
        }
        if (state.darkSinceTick < 0L) {
            state.darkSinceTick = nowTick;
            return false;
        }
        return (nowTick - state.darkSinceTick) >= DARK_SUSTAIN_TICKS;
    }
}
