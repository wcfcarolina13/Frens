package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.AbstractNautilusEntity;
import net.minecraft.entity.passive.CamelEntity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.NautilusEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.wcfcarolina13.ChatUtils.BotDialoguePlayer;
import net.wcfcarolina13.ChatUtils.VoiceLineCategory;
import net.wcfcarolina13.ChatUtils.BotDialogueSounds;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotEventHandler;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pet and tamed-animal proximity reactions (Batch 3 Phase 1).
 */
public final class PetProximityReactionService {

    private static final double PET_RADIUS = 10.0D;
    private static final double NAUTILUS_RADIUS = 12.0D;

    private static final long WOLF_NEARBY_COOLDOWN_MS = 6L * 60_000L;
    private static final long WOLF_HURT_COOLDOWN_MS = 8_000L;
    private static final long ANIMAL_WELL_BEHAVED_COOLDOWN_MS = 90_000L;
    private static final long MOUNT_QUALITY_COOLDOWN_MS = 5L * 60_000L;
    private static final long NAUTILUS_COOLDOWN_MS = 10L * 60_000L;
    private static final long CAT_COOLDOWN_MS = 5L * 60_000L;
    private static final long PARROT_COOLDOWN_MS = 5L * 60_000L;
    private static final long CAMEL_COOLDOWN_MS = 5L * 60_000L;
    private static final long HORSE_COOLDOWN_MS = 5L * 60_000L;
    private static final long WOLF_OBSERVATION_COOLDOWN_MS = 5L * 60_000L;

    // Rarity weights: COMMON=10, UNCOMMON=5
    private static final int WEIGHT_COMMON = 10;
    private static final int WEIGHT_UNCOMMON = 5;

    private static final Random RNG = new Random();

    private static final ConcurrentHashMap<UUID, Long> LAST_WOLF_NEARBY_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_WOLF_HURT_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_ANIMAL_WELL_BEHAVED_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_MOUNT_QUALITY_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_NAUTILUS_UNTAMED_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_NAUTILUS_TAMED_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_CAT_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_PARROT_NICE_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_CAMEL_NICE_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_HORSE_NICE_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_WOLF_OBSERVATION_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Float> WOLF_LAST_HEALTH = new ConcurrentHashMap<>();

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

    private static final WeightedLine[] WOLF_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("wolf_guard_duty", "Guard dog on duty.", BotDialogueSounds.LINE_WOLF_GUARD_DUTY, WEIGHT_UNCOMMON),
            new WeightedLine("wolf_menace", "Who's a menace? You're a menace.", BotDialogueSounds.LINE_WOLF_MENACE, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] WOLF_HURT_LINES = new WeightedLine[] {
            new WeightedLine("wolf_leave_alone", "Hey - leave the dog alone.", BotDialogueSounds.LINE_WOLF_LEAVE_ALONE, WEIGHT_COMMON)
    };

    // Broad tamed-non-wolf-non-nautilus pool — fires on any tamed cat/parrot/horse/etc.
    private static final WeightedLine[] ANIMAL_WELL_BEHAVED_LINES = new WeightedLine[] {
            new WeightedLine("animal_well_behaved", "I respect a well-behaved animal.", BotDialogueSounds.LINE_ANIMAL_WELL_BEHAVED, WEIGHT_UNCOMMON)
    };

    // Mount-only pool — fires on tamed horse/donkey/mule/llama/trader-llama or any camel.
    // Long cooldown so it stays a remark, not background chatter.
    private static final WeightedLine[] MOUNT_QUALITY_LINES = new WeightedLine[] {
            new WeightedLine("animal_quality", "That's a quality animal.", BotDialogueSounds.LINE_ANIMAL_QUALITY, WEIGHT_UNCOMMON)
    };

    // Wild nautilus — neutral mob that retaliates and dashes at pufferfish.
    private static final WeightedLine[] NAUTILUS_UNTAMED_LINES = new WeightedLine[] {
            new WeightedLine("nautilus_ocean_never", "Never going near the ocean again.", BotDialogueSounds.LINE_NAUTILUS_OCEAN_NEVER, WEIGHT_UNCOMMON)
    };

    // Tamed nautilus — pufferfish-tamed, rideable when saddled per the wiki.
    private static final WeightedLine[] NAUTILUS_TAMED_LINES = new WeightedLine[] {
            new WeightedLine("nautilus_ride", "You can actually ride one of these?", BotDialogueSounds.LINE_NAUTILUS_RIDE, WEIGHT_UNCOMMON)
    };

    // Tamed cat — separate pool from the broad-tamed-animal line per spec ("Meow." isn't a quality assessment).
    private static final WeightedLine[] CAT_NEARBY_LINES = new WeightedLine[] {
            new WeightedLine("cat_meow", "Meow.", BotDialogueSounds.LINE_CAT_MEOW, WEIGHT_UNCOMMON)
    };

    // Species-specific "I see one right now" lines. Gated on proximity + LoS so the bot
    // doesn't shout "nice camel" when there's no camel anywhere near or one is behind a wall.
    // Audio was authored in the April 2026 handoff but originally wired into the blind
    // WILDLIFE_CHATTER pool, which fired randomly on surface daytime regardless of fauna.
    private static final WeightedLine[] PARROT_NICE_LINES = new WeightedLine[] {
            new WeightedLine("parrot_nice_bird", "Nice bird!", BotDialogueSounds.LINE_PARROT_NEARBY_NICE_BIRD, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] CAMEL_NICE_LINES = new WeightedLine[] {
            new WeightedLine("camel_nice_camel", "Nice camel.", BotDialogueSounds.LINE_CAMEL_NEARBY_NICE_CAMEL, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] HORSE_NICE_LINES = new WeightedLine[] {
            new WeightedLine("horse_nice_horse", "Nice horse.", BotDialogueSounds.LINE_HORSE_NEARBY_NICE_HORSE, WEIGHT_UNCOMMON)
    };

    // Independent from WOLF_NEARBY_LINES (which is tamed-only "guard dog on duty" /
    // "who's a menace"). These three fire on any visible wolf, wild or tame.
    private static final WeightedLine[] WOLF_OBSERVATION_LINES = new WeightedLine[] {
            new WeightedLine("wolf_good_dog", "Good dog!", BotDialogueSounds.LINE_WOLF_NEARBY_GOOD_DOG, WEIGHT_UNCOMMON),
            new WeightedLine("wolf_love_dogs", "I love dogs.", BotDialogueSounds.LINE_WOLF_NEARBY_LOVE_DOGS, WEIGHT_UNCOMMON),
            new WeightedLine("wolf_skinwalker", "Wait — that's not a dog.", BotDialogueSounds.LINE_WOLF_NEARBY_SKINWALKER, WEIGHT_UNCOMMON)
    };

    private PetProximityReactionService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || server.getTicks() % 20 != 0) {
            return;
        }

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }

            List<WolfEntity> wolves = nearbyTamedWolves(world, bot);
            if (!wolves.isEmpty()) {
                maybeWolfNearby(bot);
                maybeWolfHurt(bot, wolves);
            }

            if (hasNearbyTamedNonWolfAnimal(world, bot)) {
                maybeAnimalWellBehaved(bot);
            }

            if (hasNearbyMountAnimal(world, bot)) {
                maybeMountQuality(bot);
            }

            if (hasNearbyTamedCat(world, bot)) {
                maybeCatNearby(bot);
            }

            // Species-specific "I see one right now" scans — proximity + LoS gated.
            if (hasNearbyVisibleParrot(world, bot)) {
                maybeParrotNice(bot);
            }
            if (hasNearbyVisibleCamel(world, bot)) {
                maybeCamelNice(bot);
            }
            if (hasNearbyVisibleHorseLike(world, bot)) {
                maybeHorseNice(bot);
            }
            if (hasNearbyVisibleWolf(world, bot)) {
                maybeWolfObservation(bot);
            }

            // Nautilus scans run independent of the broad pet pool above; the broad pool
            // explicitly excludes AbstractNautilusEntity so the more specific lines win.
            List<NautilusEntity> nautiluses = nearbyNautiluses(world, bot);
            if (!nautiluses.isEmpty()) {
                boolean anyWild = false;
                boolean anyTamed = false;
                for (NautilusEntity n : nautiluses) {
                    if (n.isTamed()) {
                        anyTamed = true;
                    } else {
                        anyWild = true;
                    }
                }
                if (anyTamed) {
                    maybeNautilusTamed(bot);
                } else if (anyWild) {
                    maybeNautilusUntamed(bot);
                }
            }
        }
    }

    public static boolean debugTrigger(ServerPlayerEntity bot, String triggerKey, String lineId) {
        if (bot == null || triggerKey == null) {
            return false;
        }
        String key = triggerKey.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "tamed_wolf_nearby", "wolf_nearby" -> playLine(bot, WOLF_NEARBY_LINES, lineId, LAST_WOLF_NEARBY_MS, 0L);
            case "wolf_takes_damage", "wolf_hurt" -> playLine(bot, WOLF_HURT_LINES, lineId, LAST_WOLF_HURT_MS, 0L);
            case "tamed_animal_nearby", "animal_nearby", "animal_well_behaved" ->
                    playLine(bot, ANIMAL_WELL_BEHAVED_LINES, lineId, LAST_ANIMAL_WELL_BEHAVED_MS, 0L);
            case "mount_quality", "animal_quality" -> playLine(bot, MOUNT_QUALITY_LINES, lineId, LAST_MOUNT_QUALITY_MS, 0L);
            case "nautilus_untamed", "nautilus_wild" -> playLine(bot, NAUTILUS_UNTAMED_LINES, lineId, LAST_NAUTILUS_UNTAMED_MS, 0L);
            case "nautilus_tamed" -> playLine(bot, NAUTILUS_TAMED_LINES, lineId, LAST_NAUTILUS_TAMED_MS, 0L);
            case "cat_nearby", "cat_meow" -> playLine(bot, CAT_NEARBY_LINES, lineId, LAST_CAT_MS, 0L);
            case "parrot_nice", "parrot_nearby" -> playLine(bot, PARROT_NICE_LINES, lineId, LAST_PARROT_NICE_MS, 0L);
            case "camel_nice", "camel_nearby" -> playLine(bot, CAMEL_NICE_LINES, lineId, LAST_CAMEL_NICE_MS, 0L);
            case "horse_nice", "horse_nearby" -> playLine(bot, HORSE_NICE_LINES, lineId, LAST_HORSE_NICE_MS, 0L);
            case "wolf_observation", "wolf_nearby_observation" ->
                    playLine(bot, WOLF_OBSERVATION_LINES, lineId, LAST_WOLF_OBSERVATION_MS, 0L);
            default -> false;
        };
    }

    private static List<WolfEntity> nearbyTamedWolves(ServerWorld world, ServerPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(PET_RADIUS, 6.0D, PET_RADIUS);
        return world.getEntitiesByClass(
                WolfEntity.class,
                box,
                wolf -> wolf != null && wolf.isAlive() && wolf.isTamed()
        );
    }

    private static boolean hasNearbyTamedNonWolfAnimal(ServerWorld world, ServerPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(PET_RADIUS, 6.0D, PET_RADIUS);

        // Exclude wolves, cats, and nautiluses — each has its own dedicated pool.
        boolean tameables = !world.getEntitiesByClass(
                TameableEntity.class,
                box,
                tameable -> tameable != null
                        && tameable.isAlive()
                        && tameable.isTamed()
                        && !(tameable instanceof WolfEntity)
                        && !(tameable instanceof CatEntity)
                        && !(tameable instanceof AbstractNautilusEntity)
        ).isEmpty();

        if (tameables) {
            return true;
        }

        return !world.getEntitiesByClass(
                AbstractHorseEntity.class,
                box,
                horse -> horse != null && horse.isAlive() && horse.isTame()
        ).isEmpty();
    }

    private static boolean hasNearbyMountAnimal(ServerWorld world, ServerPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(PET_RADIUS, 6.0D, PET_RADIUS);

        boolean tamedHorseLike = !world.getEntitiesByClass(
                AbstractHorseEntity.class,
                box,
                horse -> horse != null && horse.isAlive() && horse.isTame()
        ).isEmpty();
        if (tamedHorseLike) {
            return true;
        }

        // Camels aren't part of AbstractHorseEntity in 1.21.11 and have no tame system in vanilla,
        // so any living camel in range counts as a "quality animal" sighting.
        return !world.getEntitiesByClass(
                CamelEntity.class,
                box,
                camel -> camel != null && camel.isAlive()
        ).isEmpty();
    }

    private static List<NautilusEntity> nearbyNautiluses(ServerWorld world, ServerPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(NAUTILUS_RADIUS, 6.0D, NAUTILUS_RADIUS);
        return world.getEntitiesByClass(
                NautilusEntity.class,
                box,
                n -> n != null && n.isAlive()
        );
    }

    private static boolean hasNearbyTamedCat(ServerWorld world, ServerPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(PET_RADIUS, 6.0D, PET_RADIUS);
        return !world.getEntitiesByClass(
                CatEntity.class,
                box,
                cat -> cat != null && cat.isAlive() && cat.isTamed()
        ).isEmpty();
    }

    private static boolean hasNearbyVisibleParrot(ServerWorld world, ServerPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(PET_RADIUS, 6.0D, PET_RADIUS);
        return !world.getEntitiesByClass(
                ParrotEntity.class,
                box,
                p -> p != null && p.isAlive() && EntityVisibilityUtil.canSee(bot, p)
        ).isEmpty();
    }

    private static boolean hasNearbyVisibleCamel(ServerWorld world, ServerPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(PET_RADIUS, 6.0D, PET_RADIUS);
        return !world.getEntitiesByClass(
                CamelEntity.class,
                box,
                c -> c != null && c.isAlive() && EntityVisibilityUtil.canSee(bot, c)
        ).isEmpty();
    }

    private static boolean hasNearbyVisibleHorseLike(ServerWorld world, ServerPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(PET_RADIUS, 6.0D, PET_RADIUS);
        return !world.getEntitiesByClass(
                AbstractHorseEntity.class,
                box,
                h -> h != null && h.isAlive() && EntityVisibilityUtil.canSee(bot, h)
        ).isEmpty();
    }

    private static boolean hasNearbyVisibleWolf(ServerWorld world, ServerPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(PET_RADIUS, 6.0D, PET_RADIUS);
        return !world.getEntitiesByClass(
                WolfEntity.class,
                box,
                w -> w != null && w.isAlive() && EntityVisibilityUtil.canSee(bot, w)
        ).isEmpty();
    }

    private static void maybeWolfNearby(ServerPlayerEntity bot) {
        if (RNG.nextDouble() > 0.20D) {
            return;
        }
        playLine(bot, WOLF_NEARBY_LINES, null, LAST_WOLF_NEARBY_MS, WOLF_NEARBY_COOLDOWN_MS);
    }

    private static void maybeWolfHurt(ServerPlayerEntity bot, List<WolfEntity> wolves) {
        boolean anyHurt = false;
        for (WolfEntity wolf : wolves) {
            UUID id = wolf.getUuid();
            float health = wolf.getHealth();
            Float prev = WOLF_LAST_HEALTH.put(id, health);
            if (prev != null && health + 0.05f < prev) {
                anyHurt = true;
            }
        }
        if (!anyHurt) {
            return;
        }
        playLine(bot, WOLF_HURT_LINES, null, LAST_WOLF_HURT_MS, WOLF_HURT_COOLDOWN_MS);
    }

    private static void maybeAnimalWellBehaved(ServerPlayerEntity bot) {
        if (RNG.nextDouble() > 0.20D) {
            return;
        }
        playLine(bot, ANIMAL_WELL_BEHAVED_LINES, null, LAST_ANIMAL_WELL_BEHAVED_MS, ANIMAL_WELL_BEHAVED_COOLDOWN_MS);
    }

    private static void maybeMountQuality(ServerPlayerEntity bot) {
        if (RNG.nextDouble() > 0.20D) {
            return;
        }
        playLine(bot, MOUNT_QUALITY_LINES, null, LAST_MOUNT_QUALITY_MS, MOUNT_QUALITY_COOLDOWN_MS);
    }

    private static void maybeNautilusUntamed(ServerPlayerEntity bot) {
        if (RNG.nextDouble() > 0.20D) {
            return;
        }
        playLine(bot, NAUTILUS_UNTAMED_LINES, null, LAST_NAUTILUS_UNTAMED_MS, NAUTILUS_COOLDOWN_MS);
    }

    private static void maybeNautilusTamed(ServerPlayerEntity bot) {
        if (RNG.nextDouble() > 0.20D) {
            return;
        }
        playLine(bot, NAUTILUS_TAMED_LINES, null, LAST_NAUTILUS_TAMED_MS, NAUTILUS_COOLDOWN_MS);
    }

    private static void maybeCatNearby(ServerPlayerEntity bot) {
        if (RNG.nextDouble() > 0.20D) {
            return;
        }
        playLine(bot, CAT_NEARBY_LINES, null, LAST_CAT_MS, CAT_COOLDOWN_MS);
    }

    private static void maybeParrotNice(ServerPlayerEntity bot) {
        if (RNG.nextDouble() > 0.20D) {
            return;
        }
        playLine(bot, PARROT_NICE_LINES, null, LAST_PARROT_NICE_MS, PARROT_COOLDOWN_MS);
    }

    private static void maybeCamelNice(ServerPlayerEntity bot) {
        if (RNG.nextDouble() > 0.20D) {
            return;
        }
        playLine(bot, CAMEL_NICE_LINES, null, LAST_CAMEL_NICE_MS, CAMEL_COOLDOWN_MS);
    }

    private static void maybeHorseNice(ServerPlayerEntity bot) {
        if (RNG.nextDouble() > 0.20D) {
            return;
        }
        playLine(bot, HORSE_NICE_LINES, null, LAST_HORSE_NICE_MS, HORSE_COOLDOWN_MS);
    }

    private static void maybeWolfObservation(ServerPlayerEntity bot) {
        if (RNG.nextDouble() > 0.20D) {
            return;
        }
        playLine(bot, WOLF_OBSERVATION_LINES, null, LAST_WOLF_OBSERVATION_MS, WOLF_OBSERVATION_COOLDOWN_MS);
    }

    /** Cross-bot recency per line id: with two companions near the same animal, each bot's
     *  per-bot cooldown allowed both to fire the IDENTICAL line seconds apart ("Nice bird!"
     *  from Jake at :22 and Bob at :24 in the 2026-08-29 field log). Any line spoken by ANY
     *  bot is off the menu for every bot for this window; a second bot may still react with a
     *  different line from the pool. */
    private static final long GLOBAL_LINE_DEDUP_MS = 120_000L;
    private static final ConcurrentHashMap<String, Long> GLOBAL_LAST_LINE_MS = new ConcurrentHashMap<>();

    private static boolean playLine(ServerPlayerEntity bot,
                                    WeightedLine[] pool,
                                    String forcedLineId,
                                    ConcurrentHashMap<UUID, Long> cooldownMap,
                                    long cooldownMs) {
        if (bot == null || pool == null || pool.length == 0) {
            return false;
        }
        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = cooldownMap.getOrDefault(botId, 0L);
        if (forcedLineId == null && cooldownMs > 0L && now - last < cooldownMs) {
            return false;
        }

        WeightedLine[] eligible = pool;
        if (forcedLineId == null) {
            java.util.List<WeightedLine> fresh = new java.util.ArrayList<>(pool.length);
            for (WeightedLine candidate : pool) {
                if (now - GLOBAL_LAST_LINE_MS.getOrDefault(candidate.id, 0L) >= GLOBAL_LINE_DEDUP_MS) {
                    fresh.add(candidate);
                }
            }
            if (fresh.isEmpty()) {
                return false; // every line in this pool was recently spoken by some bot
            }
            eligible = fresh.toArray(new WeightedLine[0]);
        }

        WeightedLine line = pickWeightedLine(eligible, forcedLineId);
        if (line == null) {
            return false;
        }

        GLOBAL_LAST_LINE_MS.put(line.id, now);
        cooldownMap.put(botId, now);
        CompanionOverheadDialogueService.showOverheadLine(bot, line.text, 3_000, 48.0, "pet", line.id);
        BotDialoguePlayer.PlayResult result = BotDialoguePlayer.playSoundForBotDetailed(bot, line.sound, VoiceLineCategory.AMBIENT_CHATTER);
        if (result == BotDialoguePlayer.PlayResult.PLAYED || result == BotDialoguePlayer.PlayResult.THROTTLED) {
            return true;
        }

        ChatUtils.sendChatMessages(
                bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                line.text,
                true,
                VoiceLineCategory.AMBIENT_CHATTER
        );
        return true;
    }

    private static WeightedLine pickWeightedLine(WeightedLine[] pool, String forcedLineId) {
        if (forcedLineId != null && !forcedLineId.isBlank()) {
            for (WeightedLine line : pool) {
                if (line != null && forcedLineId.equalsIgnoreCase(line.id)) {
                    return line;
                }
            }
            return null;
        }

        int total = 0;
        for (WeightedLine line : pool) {
            if (line != null) {
                total += line.weight;
            }
        }
        if (total <= 0) {
            return null;
        }

        int r = RNG.nextInt(total);
        int run = 0;
        for (WeightedLine line : pool) {
            if (line == null) {
                continue;
            }
            run += line.weight;
            if (r < run) {
                return line;
            }
        }
        return pool[pool.length - 1];
    }
}
