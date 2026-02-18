package net.shasankp000.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.DolphinEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.biome.Biome;
import net.shasankp000.ChatUtils.BotDialoguePlayer;
import net.shasankp000.ChatUtils.BotDialogueSounds;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotEventHandler;

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
    }

    private static final ConcurrentHashMap<UUID, TriggerState> STATE = new ConcurrentHashMap<>();

    private static final WeightedLine[] AMBIENT_LINES = new WeightedLine[] {
            new WeightedLine("ambient_bad_feeling", "I have a bad feeling about this.", BotDialogueSounds.LINE_AMBIENT_BAD_FEELING, WEIGHT_RARE),
            new WeightedLine("ambient_my_job", "I can't believe this is my job.", BotDialogueSounds.LINE_AMBIENT_MY_JOB, WEIGHT_COMMON),
            new WeightedLine("ambient_blame_terrain", "If we die, I'm blaming the terrain.", BotDialogueSounds.LINE_AMBIENT_BLAME_TERRAIN, WEIGHT_UNCOMMON),
            new WeightedLine("ambient_thinking", "I'm thinking. Don't rush me.", BotDialogueSounds.LINE_AMBIENT_THINKING, WEIGHT_COMMON)
    };

    private static final WeightedLine[] HIGH_THREAT_LINES = new WeightedLine[] {
            new WeightedLine("creepy_head_swivel", "Keep your head on a swivel.", BotDialogueSounds.LINE_CREEPY_HEAD_SWIVEL, WEIGHT_UNCOMMON),
            new WeightedLine("creepy_bad_ideas_mature", "This is where bad ideas go to mature.", BotDialogueSounds.LINE_CREEPY_BAD_IDEAS_MATURE, WEIGHT_UNCOMMON),
            new WeightedLine("creepy_complaint_reality", "I'd like to file a complaint with reality.", BotDialogueSounds.LINE_CREEPY_COMPLAINT_REALITY, WEIGHT_RARE)
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
            new WeightedLine("meta_human_laugh", "That was a human laugh. Totally normal.", BotDialogueSounds.LINE_META_HUMAN_LAUGH, WEIGHT_RARE),
            new WeightedLine("meta_stop_looking", "Stop looking at me like that. I'm trying.", BotDialogueSounds.LINE_META_STOP_LOOKING, WEIGHT_RARE)
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
            new WeightedLine("shelter_some_problems", "This will keep out... some of the problems.", BotDialogueSounds.LINE_SHELTER_SOME_PROBLEMS, WEIGHT_UNCOMMON)
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
        }

        STATE.keySet().retainAll(live);
    }

    public static boolean playShelterCompletion(ServerPlayerEntity bot, String forcedLineId) {
        return tryTrigger(bot, "shelter_completion", SHELTER_LINES, forcedLineId, false);
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
            default -> false;
        };
    }

    private static boolean tryFreefall(ServerPlayerEntity bot, TriggerState state) {
        boolean airborne = !bot.isOnGround() && !bot.isTouchingWater() && !bot.isSubmergedInWater();
        boolean fastDrop = airborne && bot.getVelocity().y < -0.85D && bot.fallDistance > 6.0F;
        boolean wearingElytra = bot.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        boolean elytraLikeFlight = airborne
                && wearingElytra
                && bot.fallDistance > 1.5F
                && bot.getVelocity().horizontalLengthSquared() > 0.06D;
        boolean freefall = fastDrop || elytraLikeFlight;
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

        if (!inCombat && RNG.nextDouble() < 0.003D
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
                bot.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
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
}
