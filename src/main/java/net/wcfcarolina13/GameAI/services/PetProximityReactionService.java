package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.dialogue.DialoguePacing;
import net.wcfcarolina13.GameAI.services.dialogue.ScriptedDelivery;
import net.wcfcarolina13.GameAI.services.dialogue.SpeechFloorPolicy;
import net.wcfcarolina13.GameAI.services.dialogue.SpeechFloorService;
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
import net.wcfcarolina13.ChatUtils.TextLineVisibilityService;
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

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(PetProximityReactionService.class);

    private static final double PET_RADIUS = 10.0D;
    private static final double NAUTILUS_RADIUS = 12.0D;

    private static final long WOLF_NEARBY_COOLDOWN_MS = 6L * 60_000L;
    private static final long WOLF_HURT_COOLDOWN_MS = 8_000L;
    /** 5min, raised from 90s (1.1.216): its sibling animal/mount pools all run at 5min, and the
     *  90s value let "I respect a well-behaved animal." repeat from the SAME bot 91 seconds after
     *  itself in the 2026-09-06 field log. Matching the siblings also puts it at or above the
     *  cross-bot dedup window, so neither a same-bot nor a cross-bot repeat can beat it. */
    private static final long ANIMAL_WELL_BEHAVED_COOLDOWN_MS = 5L * 60_000L;
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
     *  from Jake at :22 and Bob at :24 in the 2026-08-29 field log). A line recently spoken by
     *  a DIFFERENT bot is off the menu; the SAME bot repeating is still governed only by its
     *  own per-pool cooldown — 9 of the 11 pools hold exactly one line, so a bot-agnostic
     *  window would have silently stretched every such pool's designed cadence (review #2).
     *
     *  <p>The window is derived PER POOL from that pool's own cooldown (1.1.216 review finding 4).
     *  It started as one flat 120s constant, which expired before the 5-minute mount/animal pool
     *  cooldowns it was meant to cover — "Nice horse." and "That's a quality animal." still crossed
     *  between bots at 122–123s in the 2026-09-06 field log. Raising the flat constant to 300s
     *  fixed those pools and broke {@code WOLF_HURT_LINES}: a single-line pool on an 8s cooldown,
     *  where a flat five-minute dedup means the second bot cannot answer a hurt wolf for five
     *  minutes while the first answers every eight seconds. A pool's cooldown already states how
     *  often that line is meant to be heard, so it is exactly the right cross-bot window too. */
    private static final long MIN_LINE_DEDUP_MS = 8_000L;

    /** Ceiling on the derived window: no pool suppresses a cross-bot echo for more than 10 minutes. */
    private static final long MAX_LINE_DEDUP_MS = 10L * 60_000L;

    /** Cross-bot dedup window for a pool whose (pacing-scaled) cooldown is {@code cooldownMs}. */
    private static long lineDedupWindowMs(long cooldownMs) {
        return Math.min(MAX_LINE_DEDUP_MS, Math.max(MIN_LINE_DEDUP_MS, cooldownMs));
    }

    private record LineEcho(UUID botId, long atMs) {
    }

    private static final ConcurrentHashMap<String, LineEcho> GLOBAL_LAST_LINE = new ConcurrentHashMap<>();

    private static boolean playLine(ServerPlayerEntity bot,
                                    WeightedLine[] pool,
                                    String forcedLineId,
                                    ConcurrentHashMap<UUID, Long> cooldownMap,
                                    long cooldownMs) {
        if (bot == null || pool == null || pool.length == 0) {
            return false;
        }
        if (forcedLineId != null && forcedLineId.isBlank()) {
            // A blank forced id is no forced id. Normalised FIRST so every gate below — pool
            // cooldown, speech floor, cross-bot dedup — sees the same value; when this sat below
            // the floor check, a blank forced id skipped the floor and was then treated as organic
            // by the dedup (1.1.216 review finding 6).
            forcedLineId = null;
        }
        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        long effectiveCooldownMs = cooldownMs > 0L
                ? DialoguePacing.scaledCooldown(DialoguePacing.Stream.SCRIPTED, cooldownMs)
                : 0L;
        long last = cooldownMap.getOrDefault(botId, 0L);
        if (forcedLineId == null && cooldownMs > 0L && now - last < effectiveCooldownMs) {
            return false;
        }

        // Cross-lane speech floor: the pool cooldown above is per (bot, pool) only, so it cannot
        // see a line another bot — or a soul scene — just delivered to the same player. An organic
        // line arriving while the floor is closed is DROPPED, not deferred (deferring would only
        // move the pile-up), and the pool cooldown is deliberately NOT consumed so the same
        // trigger may speak once the floor reopens. Forced/debug fires (cooldownMs == 0) bypass
        // the floor for the same reason they bypass the dedup: /bot dialogue test must always play.
        UUID audience = resolveAudience(bot);
        if (forcedLineId == null && cooldownMs > 0L
                && !SpeechFloorService.isFloorOpen(audience, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT)) {
            LOGGER.debug("[dialogue] pet-line dropped bot={} floorRemainingMs={}",
                    bot.getName().getString(), SpeechFloorService.remainingMs(audience));
            return false;
        }

        WeightedLine[] eligible = pool;
        // Dedup applies only to organic fires (cooldownMs > 0): the debug command
        // (/bot dialogue test <trigger>) passes cooldownMs == 0 and must always play.
        if (forcedLineId == null && cooldownMs > 0L) {
            long dedupWindowMs = lineDedupWindowMs(effectiveCooldownMs);
            java.util.List<WeightedLine> fresh = new java.util.ArrayList<>(pool.length);
            for (WeightedLine candidate : pool) {
                LineEcho echo = GLOBAL_LAST_LINE.get(candidate.id);
                boolean recentlyByAnotherBot = echo != null && !botId.equals(echo.botId())
                        && now - echo.atMs() < dedupWindowMs;
                if (!recentlyByAnotherBot) {
                    fresh.add(candidate);
                }
            }
            if (fresh.isEmpty()) {
                return false; // every line in this pool was just spoken by a different bot
            }
            eligible = fresh.toArray(new WeightedLine[0]);
        }

        WeightedLine line = pickWeightedLine(eligible, forcedLineId);
        if (line == null) {
            return false;
        }

        GLOBAL_LAST_LINE.put(line.id, new LineEcho(botId, now));
        cooldownMap.put(botId, now);

        // The floor is armed only if a surface actually DELIVERED something (1.1.216 review
        // finding 2). Every surface below carries its own mask — the overhead hologram consults
        // TextLineVisibilityService, the voice consults the voice masks — so arming before the
        // attempt meant a fully muted scripted lane still silenced the soul lane for four seconds
        // per line. That is a lane gated on another lane's TOGGLE, which the project's dialogue
        // lane-separation rule forbids: a lane may only be gated on live speaking state.
        //
        // showOverheadLine already voices lines that have a DialogueTextMapper entry and reports
        // what surfaced. Playing line.sound again for such a line hit the 2.5 s per-bot voice
        // mutex, came back THROTTLED, and — with text off — left the floor unarmed although the
        // player had just heard the line (1.1.217). So the explicit play runs only when the
        // overhead made no voice attempt ("pet" and AMBIENT_CHATTER are the same mask category).
        ScriptedDelivery.Surface overhead =
                CompanionOverheadDialogueService.showOverheadLine(bot, line.text, 3_000, 48.0, "pet", line.id);
        ScriptedDelivery.VoiceOutcome explicitVoice = ScriptedDelivery.needsExplicitVoice(overhead.voice())
                ? ScriptedDelivery.from(BotDialoguePlayer.playSoundForBotDetailed(
                        bot, line.sound, VoiceLineCategory.AMBIENT_CHATTER))
                : ScriptedDelivery.VoiceOutcome.NONE;
        if (ScriptedDelivery.voiceHandled(overhead.voice(), explicitVoice)) {
            // No chat fallback when the voice lane took the line. A THROTTLED here now means a
            // DIFFERENT line held the voice mutex, so on its own it is not a delivery; a PLAYED
            // from either attempt is.
            noteSpeechIfDelivered(audience,
                    ScriptedDelivery.delivered(overhead.textShown(), overhead.voice(), explicitVoice, false));
            return true;
        }

        ChatUtils.sendChatMessages(
                bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                line.text,
                true,
                VoiceLineCategory.AMBIENT_CHATTER
        );
        noteSpeechIfDelivered(audience, ScriptedDelivery.delivered(overhead.textShown(), overhead.voice(), explicitVoice,
                TextLineVisibilityService.isTextAllowed(VoiceLineCategory.AMBIENT_CHATTER)));
        return true;
    }

    /**
     * The player this bot's ambient flavour is aimed at, or {@code null} when there is nobody to
     * aim it at (unowned bot, owner offline) — in which case the floor is skipped entirely rather
     * than keyed onto a shared sentinel (1.1.216 review finding 3).
     *
     * <p>Uses {@code resolveController}, not {@code resolveOwnerUuid}: the latter reads only the
     * config {@code botOwnership} map, which is written solely by survival recruitment and
     * {@code /bot setowner}, so for an ordinary spawned bot it returns null. The controller
     * resolver carries the survival-recruitment fallback and only returns a player who is online.
     */
    private static UUID resolveAudience(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        ServerPlayerEntity controller = CompanionCommunicationPolicy.resolveController(world.getServer(), bot);
        return controller == null ? null : controller.getUuid();
    }

    /** Arms the audience's floor only when at least one surface actually showed or sent the line. */
    private static void noteSpeechIfDelivered(UUID audience, boolean delivered) {
        if (delivered) {
            SpeechFloorService.noteSpeech(audience, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        }
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
