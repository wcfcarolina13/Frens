package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.dialogue.DialoguePacing;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.wcfcarolina13.ChatUtils.BotDialoguePlayer;
import net.wcfcarolina13.ChatUtils.DialogueTextMapper;
import net.wcfcarolina13.ChatUtils.TextLineVisibilityService;
import net.wcfcarolina13.ChatUtils.VoiceLineCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows short-lived, in-world overhead dialogue above a companion/bot (not in chat).
 *
 * Intended for quick, high-signal “state” moments (e.g., foliage obstruction) where chat spam would be annoying.
 */
public final class CompanionOverheadDialogueService {

    private static final Logger LOGGER = LoggerFactory.getLogger("companion-overhead");

    private static final long COOLDOWN_MS = 8_000L;
    private static final int DURATION_MS = 2_800;
    private static final double RANGE = 32.0;

    private static final ConcurrentHashMap<UUID, Long> LAST_LEAF_STUCK_MS = new ConcurrentHashMap<>();

    // Separate cooldowns for berry bush reactions so they don't suppress foliage stuck UX.
    private static final long BERRY_STING_COOLDOWN_MS = 6_000L;
    private static final long BERRY_EDIBLE_COOLDOWN_MS = 18_000L;
    private static final ConcurrentHashMap<UUID, Long> LAST_BERRY_STING_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_BERRY_EDIBLE_MS = new ConcurrentHashMap<>();

    // Global overhead-display timestamp for cross-system suppression (e.g., portal chatter overlap).
    private static final ConcurrentHashMap<UUID, Long> LAST_ANY_OVERHEAD_MS = new ConcurrentHashMap<>();
    private static final long OVERHEAD_GLOBAL_SUPPRESSION_MS = 4_000L;

    private static final ConcurrentHashMap<UUID, Long> LAST_GEAR_PRESERVE_SWAP_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_GEAR_COMBAT_EDGE_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_GEAR_NO_REPLACEMENT_MS = new ConcurrentHashMap<>();

    private static final long GEAR_DIALOGUE_COOLDOWN_MS = 30_000L; // 30s per-bot, per-category

    private static final String[] LEAF_STUCK_LINES = new String[] {
            "These branches are thick!",
            "Hold on — stuck in some branches.",
            "Can't get through these leaves.",
            "Just a sec… foliage's got me.",
            "Ugh. Leaves in the way."
    };

    private static final String[] BERRY_STING_LINES = new String[] {
            "Ouch!",
            "These are thorny!",
            "Yowch!"
    };

    private static final String[] BERRY_EDIBLE_LINES = new String[] {
            "These are edible...I think."
    };

    private static final String[] GEAR_PRESERVE_SWAP_LINES = new String[] {
            "Been using this one a while. Gonna hang it up.",
            "Not risking the good stuff on this.",
            "This blade's getting thin — grabbing something else.",
            "Yeah no, too nice to wreck out here.",
            "Saving the shiny for when it matters.",
            "I'll spare this one. Held up well.",
            "Don't want to snap it doing grunt work.",
            "Shelving the fancy kit. Back to basics.",
            "This one's earned a break.",
            "Swapping — rather not push my luck."
    };

    private static final String[] GEAR_COMBAT_EDGE_LINES = new String[] {
            "One more hit and she's gone!",
            "Careful — gear's on the edge!",
            "This thing's about to snap!",
            "Running on fumes over here!",
            "Almost spent — pull back!",
            "Hang on, I'm out of good stuff!"
    };

    private static final String[] GEAR_NO_REPLACEMENT_LINES = new String[] {
            "Got nothing else. Give me a minute.",
            "Can't find a spare anywhere.",
            "Need to restock — this was my last one.",
            "I'm out. Someone grab me a new one?",
            "Tried the chests, no luck.",
            "No replacements around. I'm stuck.",
            "Looked everywhere. Nothing.",
            "Hands are empty. I'll wait."
    };

    private static final java.util.Random RNG = new java.util.Random();

    private CompanionOverheadDialogueService() {
    }

    /** Returns true if an overhead line was shown for this bot within the suppression window. */
    public static boolean isRecentlyShown(UUID botId) {
        if (botId == null) return false;
        long last = LAST_ANY_OVERHEAD_MS.getOrDefault(botId, 0L);
        return (System.currentTimeMillis() - last) < OVERHEAD_GLOBAL_SUPPRESSION_MS;
    }

    /**
     * Best-effort: show a short foliage-stuck line above the bot to nearby players.
     * Rate-limited per bot.
     */
    public static void tryShowLeafStuck(ServerPlayerEntity bot, String reason) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        UUID id = bot.getUuid();
        if (id == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = LAST_LEAF_STUCK_MS.getOrDefault(id, 0L);
        if (now - last < DialoguePacing.scaledCooldown(DialoguePacing.Stream.SCRIPTED, COOLDOWN_MS)) {
            return;
        }
        LAST_LEAF_STUCK_MS.put(id, now);

        String line = LEAF_STUCK_LINES[RNG.nextInt(LEAF_STUCK_LINES.length)];
        int durationMs = DURATION_MS;

        if (TextLineVisibilityService.isTextAllowed(VoiceLineCategory.fromTag("leaf-stuck"))) {
            CompanionOverheadHologramService.show(bot, line, durationMs);
            LAST_ANY_OVERHEAD_MS.put(id, System.currentTimeMillis());
        }

        // If we have an audio mapping for this overhead line, play it.
        tryPlayVoicedOverheadLine(bot, line, "leaf-stuck");

        if (reason != null && !reason.isBlank()) {
            LOGGER.debug("Overhead line (leaf-stuck) bot={} reason={} line={}", bot.getName().getString(), reason, line);
        } else {
            LOGGER.debug("Overhead line (leaf-stuck) bot={} line={}", bot.getName().getString(), line);
        }
    }

    /**
     * Quick reaction when a bot walks through a Sweet Berry Bush.
     */
    public static void tryShowSweetBerryBushSting(ServerPlayerEntity bot, String reason) {
        tryShowGeneric(bot, LAST_BERRY_STING_MS, BERRY_STING_COOLDOWN_MS, BERRY_STING_LINES, "berry-sting", reason);
    }

    /**
     * Rare, ambient observation when adjacent to Sweet Berry Bushes.
     */
    public static void tryShowSweetBerryBushEdible(ServerPlayerEntity bot, String reason) {
        tryShowGeneric(bot, LAST_BERRY_EDIBLE_MS, BERRY_EDIBLE_COOLDOWN_MS, BERRY_EDIBLE_LINES, "berry-edible", reason);
    }

    /**
     * Show an arbitrary overhead line (no built-in cooldown). Intended for callers that already
     * have their own throttling and want to avoid chat spam.
     */
    public static void showOverheadLine(ServerPlayerEntity bot, String line, int durationMs, double range, String tag, String reason) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        if (line == null || line.isBlank()) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        UUID id = bot.getUuid();
        if (id == null) {
            return;
        }

        int dur = durationMs > 0 ? durationMs : DURATION_MS;

        // Text toggle gates the hologram only; voice is gated by its own toggles below
        // (previously this early-returned, silencing voice when Text Chat was off —
        // inconsistent with tryShowGeneric and with ChatUtils' voice-only mode).
        if (TextLineVisibilityService.isTextAllowed(VoiceLineCategory.fromTag(tag))) {
            CompanionOverheadHologramService.show(bot, line, dur);
            LAST_ANY_OVERHEAD_MS.put(id, System.currentTimeMillis());
        }

        // If the line has a DialogueTextMapper entry, play the matching sound.
        // This was missing from the public overload (only tryShowGeneric had it),
        // which caused greeting/skill_sleep/topic_*/many other lines to show
        // overhead text but never play audio. Callers that also manually
        // BotDialoguePlayer.playSoundForBotDetailed(...) are protected from
        // double-play by BotDialoguePlayer's MIN_GAP_ANY_VOICE_MS mutex.
        tryPlayVoicedOverheadLine(bot, line, tag);

        if (tag != null && !tag.isBlank()) {
            if (reason != null && !reason.isBlank()) {
                LOGGER.debug("Overhead line ({}) bot={} reason={} line={}", tag, bot.getName().getString(), reason, line);
            } else {
                LOGGER.debug("Overhead line ({}) bot={} line={}", tag, bot.getName().getString(), line);
            }
        }
    }

    private static void tryShowGeneric(
            ServerPlayerEntity bot,
            ConcurrentHashMap<UUID, Long> lastMap,
            long cooldownMs,
            String[] lines,
            String tag,
            String reason
    ) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        UUID id = bot.getUuid();
        if (id == null) {
            return;
        }
        if (lines == null || lines.length == 0) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastMap.getOrDefault(id, 0L);
        if (now - last < DialoguePacing.scaledCooldown(DialoguePacing.Stream.SCRIPTED, Math.max(0L, cooldownMs))) {
            return;
        }
        lastMap.put(id, now);

        String line = lines[RNG.nextInt(lines.length)];
        int durationMs = DURATION_MS;

        if (TextLineVisibilityService.isTextAllowed(VoiceLineCategory.fromTag(tag))) {
            CompanionOverheadHologramService.show(bot, line, durationMs);
            LAST_ANY_OVERHEAD_MS.put(id, System.currentTimeMillis());
        }

        // If we have an audio mapping for this overhead line, play it.
        tryPlayVoicedOverheadLine(bot, line, tag);

        if (reason != null && !reason.isBlank()) {
            LOGGER.debug("Overhead line ({}) bot={} reason={} line={}", tag, bot.getName().getString(), reason, line);
        } else {
            LOGGER.debug("Overhead line ({}) bot={} line={}", tag, bot.getName().getString(), line);
        }
    }

    private static void tryPlayVoicedOverheadLine(ServerPlayerEntity bot, String line, String tag) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            SoundEvent sound = DialogueTextMapper.lookup(line);
            if (sound == null) {
                return;
            }
            // Respect per-bot voiced dialogue config, category mutes + global anti-spam.
            BotDialoguePlayer.playSoundForBotDetailed(bot, sound, VoiceLineCategory.fromTag(tag));
        } catch (Throwable ignored) {
            // Best-effort only.
        }
    }

    public static boolean tryShowGearPreserveSwap(ServerPlayerEntity bot) {
        tryShowGeneric(
                bot,
                LAST_GEAR_PRESERVE_SWAP_MS,
                GEAR_DIALOGUE_COOLDOWN_MS,
                GEAR_PRESERVE_SWAP_LINES,
                "gear-preserve-swap",
                null);
        return true;
    }

    public static boolean tryShowGearCombatEdge(ServerPlayerEntity bot) {
        tryShowGeneric(
                bot,
                LAST_GEAR_COMBAT_EDGE_MS,
                GEAR_DIALOGUE_COOLDOWN_MS,
                GEAR_COMBAT_EDGE_LINES,
                "gear-combat-edge",
                null);
        return true;
    }

    public static boolean tryShowGearNoReplacement(ServerPlayerEntity bot) {
        tryShowGeneric(
                bot,
                LAST_GEAR_NO_REPLACEMENT_MS,
                GEAR_DIALOGUE_COOLDOWN_MS,
                GEAR_NO_REPLACEMENT_LINES,
                "gear-no-replacement",
                null);
        return true;
    }
}
