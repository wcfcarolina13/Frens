package net.wcfcarolina13.GameAI.services;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Soft-dependency bridge into the Emotecraft mod (kosmx, MIT). When Emotecraft
 * is present at runtime, lets bots play character-animation emotes alongside
 * voice-line reactions. When Emotecraft is absent, every method is a silent
 * no-op so Frens runs unchanged.
 *
 * <p><b>Why reflection over compile-only:</b> avoids pulling Emotecraft +
 * playeranimcore + NoteBlockLib into the Frens build. The API surface used
 * here is two methods, both static; reflection cost is negligible (a single
 * lookup at server start, then cached {@link Method} handles).
 *
 * <p><b>Cooldown:</b> per-bot per-emote, default 30 seconds. Voice-line
 * triggers already have their own cooldowns (most ≥ 60 s); the emote cooldown
 * is a safety net so multiple events firing in quick succession don't stack
 * emotes.
 */
public final class EmotecraftBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("emotecraft-bridge");
    private static final long DEFAULT_COOLDOWN_MS = 30_000L;

    /** All 11 emotes shipped with Emotecraft 3.2.0 (UUIDs from each emote's JSON). */
    public enum EmoteId {
        WAVING(UUID.fromString("33b912f8-0aa0-45e6-a2d4-9b5677e6f35c"), "waving"),
        POINT(UUID.fromString("931b2bda-a25e-4e55-957a-88c46f5e9a7b"), "point"),
        PALM(UUID.fromString("0de63fbe-2f20-44e0-8969-479b76ceeb6b"), "palm"),
        CLAP(UUID.fromString("96506a5e-a69c-4a18-9add-d43dfd272fa6"), "clap"),
        CRYING(UUID.fromString("a2969c42-520f-4c5c-a024-a7d0a2b7b4d1"), "crying"),
        HERE(UUID.fromString("3045b335-12ca-4ddb-aca5-0aef450a5e4c"), "here"),
        KAZOTSKY_KICK(UUID.fromString("01a53a42-2fd2-418c-8e46-e4ee1ff9ee6a"), "kazotsky_kick"),
        BACKFLIP(UUID.fromString("ebfb1e69-330a-4970-8bca-f5625c90681a"), "backflip"),
        TWERK(UUID.fromString("87e8adf4-0000-0000-0000-000000000000"), "twerk"),
        CLUB_PENGUIN_DANCE(UUID.fromString("ffdcfa49-0000-0000-0000-000000000000"), "club_penguin_dance"),
        ROBLOX_POTION_DANCE(UUID.fromString("f9f6669d-f1b3-4170-8bc5-475a9a600439"), "roblox_potion_dance");

        public final UUID uuid;
        public final String slug;

        EmoteId(UUID uuid, String slug) {
            this.uuid = uuid;
            this.slug = slug;
        }

        public static EmoteId bySlug(String slug) {
            if (slug == null) return null;
            String normalized = slug.toLowerCase().replace('-', '_').replace(' ', '_');
            for (EmoteId id : values()) {
                if (id.slug.equals(normalized)) return id;
            }
            return null;
        }
    }

    private static volatile boolean AVAILABLE = false;
    private static volatile Method GET_EMOTE_METHOD;     // UniversalEmoteSerializer.getEmote(UUID) -> Animation
    private static volatile Method PLAY_EMOTE_METHOD;    // ServerEmoteAPI.playEmote(UUID, Animation, boolean) -> void
    private static volatile Method STOP_EMOTE_METHOD;    // ServerEmoteAPI.stopEmote(UUID) -> void (optional)

    private static final ConcurrentHashMap<UUID, Map<EmoteId, Long>> LAST_PLAYED_MS = new ConcurrentHashMap<>();

    private EmotecraftBridge() {}

    /**
     * Probes for Emotecraft and caches the reflected handles. Idempotent.
     * Call once on {@code SERVER_STARTED}.
     */
    public static void initialize() {
        if (AVAILABLE) return;
        if (!FabricLoader.getInstance().isModLoaded("emotecraft")) {
            LOGGER.info("Emotecraft not detected; bot emote bridge disabled");
            return;
        }
        try {
            Class<?> serializer = Class.forName(
                    "io.github.kosmx.emotes.server.serializer.UniversalEmoteSerializer");
            Method getEmote = serializer.getMethod("getEmote", UUID.class);

            Class<?> api = Class.forName("io.github.kosmx.emotes.api.events.server.ServerEmoteAPI");
            Class<?> animationClass = Class.forName("com.zigythebird.playeranimcore.animation.Animation");
            Method playEmote = api.getMethod("playEmote", UUID.class, animationClass, boolean.class);
            // stopEmote is optional — if the API surface ever changes name we still
            // ship without it (looping dances just won't stop early).
            Method stopEmote = null;
            try {
                stopEmote = api.getMethod("stopEmote", UUID.class);
            } catch (NoSuchMethodException ignored) {
                LOGGER.warn("Emotecraft.stopEmote(UUID) not found; long-running emotes will not be cancelable");
            }

            GET_EMOTE_METHOD = getEmote;
            PLAY_EMOTE_METHOD = playEmote;
            STOP_EMOTE_METHOD = stopEmote;
            AVAILABLE = true;
            LOGGER.info("Emotecraft detected; bot emote bridge enabled ({} emotes registered, stopEmote={})",
                    EmoteId.values().length, stopEmote != null ? "yes" : "no");
        } catch (Throwable t) {
            LOGGER.warn("Emotecraft detected but API failed to load: {}", t.toString());
        }
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Default cooldown-respecting variant. {@code force=false}. */
    public static boolean playEmote(ServerPlayerEntity bot, EmoteId emote) {
        return playEmote(bot, emote, false);
    }

    /**
     * Plays an emote on the bot. Returns {@code true} if dispatched,
     * {@code false} if Emotecraft is unavailable, the emote couldn't load,
     * or the cooldown is still active. Errors are logged at debug level.
     */
    public static boolean playEmote(ServerPlayerEntity bot, EmoteId emote, boolean force) {
        if (!AVAILABLE || bot == null || emote == null) return false;
        if (!force && !checkAndUpdateCooldown(bot.getUuid(), emote)) return false;
        try {
            Object animation = GET_EMOTE_METHOD.invoke(null, emote.uuid);
            if (animation == null) {
                LOGGER.debug("Emote {} not loaded server-side", emote.slug);
                return false;
            }
            PLAY_EMOTE_METHOD.invoke(null, bot.getUuid(), animation, force);
            return true;
        } catch (Throwable t) {
            LOGGER.debug("Emote play failed bot={} emote={}: {}",
                    bot.getName().getString(), emote.slug, t.toString());
            return false;
        }
    }

    /**
     * Cancels any active emote on the bot. Returns {@code true} if the stop
     * call was dispatched, {@code false} if Emotecraft is unavailable, the
     * stopEmote API isn't present, or the call threw. Looping dances
     * (backflip, twerk, club_penguin_dance, roblox_potion_dance) animate
     * indefinitely once started — callers must invoke this to stop them.
     */
    public static boolean stopEmote(ServerPlayerEntity bot) {
        if (!AVAILABLE || bot == null || STOP_EMOTE_METHOD == null) return false;
        try {
            STOP_EMOTE_METHOD.invoke(null, bot.getUuid());
            return true;
        } catch (Throwable t) {
            LOGGER.debug("Emote stop failed bot={}: {}",
                    bot.getName().getString(), t.toString());
            return false;
        }
    }

    private static boolean checkAndUpdateCooldown(UUID botId, EmoteId emote) {
        long now = System.currentTimeMillis();
        Map<EmoteId, Long> bucket = LAST_PLAYED_MS.computeIfAbsent(botId,
                k -> new ConcurrentHashMap<>());
        Long last = bucket.get(emote);
        if (last != null && now - last < DEFAULT_COOLDOWN_MS) return false;
        bucket.put(emote, now);
        return true;
    }

    public static void reset() {
        LAST_PLAYED_MS.clear();
    }
}
