package net.shasankp000.ChatUtils;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
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
import net.shasankp000.GameAI.services.BotIdleHobbiesService;
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
 *   <li>Bot is in the overworld during daytime</li>
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

    // Idle chatter sounds - things the bot might say when standing around (NEUTRAL mood)
    private static final SoundEvent[] IDLE_CHATTER = {
            BotDialogueSounds.LINE_IDLE_ALL_QUIET,
            BotDialogueSounds.LINE_IDLE_STILL_STANDING,
            BotDialogueSounds.LINE_IDLE_TAKING_IT_EASY,
            BotDialogueSounds.LINE_IDLE_HERE_IF_NEEDED,
            BotDialogueSounds.LINE_IDLE_ENJOYING_CALM,
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
        };

    // Relaxed/happy sounds when all is well (CONTENT mood)
    private static final SoundEvent[] CONTENT_CHATTER = {
            BotDialogueSounds.LINE_IDLE_ENJOYING_CALM,
            BotDialogueSounds.LINE_IDLE_TAKING_IT_EASY,
            BotDialogueSounds.LINE_CONTEXT_BREATHER_SOMETIMES,
            BotDialogueSounds.LINE_IDLE_ALL_QUIET,
    };

    private BotAmbientChatter() {
    }

    /**
     * Clears all scheduler state. Should be called when the server world changes.
     */
    public static void resetSession() {
        NEXT_CHATTER_TICK.clear();
    }

    /**
     * Called every server tick to potentially trigger ambient chatter.
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long nowTick = server.getTicks();

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

            // Only in overworld
            if (world.getRegistryKey() != World.OVERWORLD) {
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
            
            // Time of day check - only for surface/idle chatter, not for cave/dark sounds
            int tod = (int) (world.getTimeOfDay() % 24_000L);
            boolean isDaytime = tod < DONT_CHATTER_AFTER_TOD && tod >= DONT_CHATTER_BEFORE_TOD;
            
            // Skip surface/idle chatter at night, but always allow environment-aware sounds
            if (!isDaytime && !isUnderground && !isDark) {
                continue;
            }

            UUID botUuid = bot.getUuid();
            long nextChatter = NEXT_CHATTER_TICK.getOrDefault(botUuid, 0L);

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
            SoundEvent sound = pickChatterSound(bot);
            
            if (sound != null) {
                // Play the sound (respects voiced dialogue setting internally)
                if (BotDialoguePlayer.playSoundForBot(bot, sound)) {
                    LOGGER.debug("Ambient chatter for {}: {}", botName, sound.id().getPath());
                }
            }

            // Schedule next chatter attempt
            NEXT_CHATTER_TICK.put(botUuid, nowTick + randomDelay());
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
    private static SoundEvent pickChatterSound(ServerPlayerEntity bot) {
        // Environment-aware chatter first (cave/dark/wildlife)
        SoundEvent env = pickEnvironmentSound(bot);
        if (env != null) return env;

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
    private static SoundEvent pickEnvironmentSound(ServerPlayerEntity bot) {
        if (bot == null || bot.getEntityWorld() == null) return null;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return null;

        BlockPos pos = bot.getBlockPos();
        int y = pos.getY();
        
        int blockLight = 15;
        try {
            blockLight = world.getLightLevel(LightType.BLOCK, pos);
        } catch (Exception ignored) {}
        
        boolean isUnderground = y < 60;
        boolean isDark = blockLight <= 4;
        boolean isDeepUnderground = y < 0; // Below Y=0 (deepslate layer)

        // Check for special environment blocks nearby (higher priority, lower chance)
        
        // Amethyst geode detection - beautiful crystals deep underground
        if (isUnderground && RNG.nextFloat() < 0.25f && hasNearbyAmethyst(world, pos)) {
            return AMETHYST_CHATTER[RNG.nextInt(AMETHYST_CHATTER.length)];
        }
        
        // Bat detection - creepy flapping in caves
        if ((isUnderground || isDark) && RNG.nextFloat() < 0.30f && hasNearbyBats(world, pos)) {
            return BAT_CHATTER[RNG.nextInt(BAT_CHATTER.length)];
        }
        
        // Dripstone detection - pointy stalactites/stalagmites
        if (isUnderground && isDark && RNG.nextFloat() < 0.25f && hasNearbyDripstone(world, pos)) {
            return DRIPSTONE_CHATTER[RNG.nextInt(DRIPSTONE_CHATTER.length)];
        }
        
        // Deepslate detection - ancient cold stone in the depths
        if (isDeepUnderground && isDark && RNG.nextFloat() < 0.30f && hasNearbyDeepslate(world, pos)) {
            return DEEPSLATE_CHATTER[RNG.nextInt(DEEPSLATE_CHATTER.length)];
        }

        // Generic cave/ambient chatter when underground
        if (isUnderground && RNG.nextFloat() < 0.40f) {
            return AMBIENT_CAVE_CHATTER[RNG.nextInt(AMBIENT_CAVE_CHATTER.length)];
        }

        // Dark-room chatter when light is low
        if (isDark && RNG.nextFloat() < 0.35f) {
            return DARK_CHATTER[RNG.nextInt(DARK_CHATTER.length)];
        }

        // Wildlife chatter during daytime on surface
        long tod = Math.floorMod(world.getTimeOfDay(), 24_000L);
        if (!isUnderground && tod < 12_000 && RNG.nextFloat() < 0.20f) {
            return WILDLIFE_CHATTER[RNG.nextInt(WILDLIFE_CHATTER.length)];
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
        SoundEvent sound = pickChatterSound(bot);
        return BotDialoguePlayer.playSoundForBot(bot, sound);
    }
}
