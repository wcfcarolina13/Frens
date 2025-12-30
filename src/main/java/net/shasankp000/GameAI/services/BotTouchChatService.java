package net.shasankp000.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.shasankp000.ChatUtils.BotMoodManager;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.ChatUtils.EmotionalState;
import net.shasankp000.GameAI.BotEventHandler;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Touch" interaction flavor text for bots.
 *
 * <p>Intended to be triggered by an explicit interaction (e.g., right-click / punch) to avoid chat spam.
 * 
 * <p>Uses {@link BotMoodManager} to add mood-aware responses.
 */
public final class BotTouchChatService {

    private static final long COOLDOWN_MS = 4_000L;
    private static final ConcurrentHashMap<String, Long> LAST_TOUCH_MS = new ConcurrentHashMap<>();

    private BotTouchChatService() {
    }

    public static boolean trySendTouchLine(ServerPlayerEntity toucher, ServerPlayerEntity bot) {
        if (toucher == null || bot == null || bot.isRemoved()) {
            return false;
        }
        if (!BotEventHandler.isRegisteredBot(bot)) {
            return false;
        }

        String key = toucher.getUuid() + ":" + bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_TOUCH_MS.getOrDefault(key, 0L);
        if (now - last < COOLDOWN_MS) {
            return false;
        }
        LAST_TOUCH_MS.put(key, now);

        String msg = pickLine(toucher, bot);
        if (msg == null || msg.isBlank()) {
            return false;
        }

        ChatUtils.sendChatMessages(
                bot.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                msg,
                true
        );
        return true;
    }

    private static String pickLine(ServerPlayerEntity toucher, ServerPlayerEntity bot) {
        String name = toucher != null ? toucher.getName().getString() : "there";

        TaskService.ActiveTaskInfo info = TaskService.getActiveTaskInfo(bot.getUuid()).orElse(null);
        String task = info != null && info.name() != null ? info.name().toLowerCase(Locale.ROOT) : "";

        if (task.startsWith("skill:fish")) {
            return choose(
                    "Shh… I’m watching the bobber, " + name + ".",
                    "If the fish are biting today, I’m not complaining.",
                    "Got a good feeling about this spot."
            );
        }
        if (task.startsWith("skill:woodcut")) {
            return choose(
                    "Careful—falling logs. I’ve got this.",
                    "Just tidying up the tree line.",
                    "A few more swings and this one’s down."
            );
        }
        if (task.startsWith("skill:hangout")) {
            return choose(
                    "Just warming up by the fire.",
                    "Nice and quiet out here.",
                    "Taking a breather before the next job."
            );
        }
        if (task.startsWith("skill:sleep")) {
            return choose(
                    "I’m trying to get some sleep.",
                    "Give me a second—finding a bed.",
                    "Long day. Time to sleep."
            );
        }

        BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);

        // No active skill: mix in occasional contextual chatter.
        String modeLine = switch (mode) {
            case FOLLOW -> choose(
                    "I’m right behind you, " + name + ".",
                    "Lead the way.",
                    "Keeping up." 
            );
            case GUARD -> choose(
                    "All clear. I’m keeping watch.",
                    "Guard duty. Nothing gets past me.",
                    "Quiet so far."
            );
            case PATROL -> choose(
                    "Doing my rounds.",
                    "Patrolling the area.",
                    "Just checking the perimeter."
            );
            case RETURNING_BASE -> choose(
                    "Heading home before it gets too dark.",
                    "On my way back to base.",
                    "Returning to base." 
            );
            case STAY -> choose(
                    "Holding position.",
                    "I’ll stay right here.",
                    "Standing by." 
            );
            case IDLE -> choose(
                    "Just taking it easy.",
                    "Nothing urgent—I'm here if you need me.",
                    "Enjoying the calm."
            );
        };

        // Get mood-based greetings for distressed states
        String moodLine = getMoodLine(bot, name);

        String simple = choose(
            "Hmm?",
            "Yeah?",
            "What do you need?",
            "Need something, " + name + "?"
        );

        String context = buildContextLine(toucher, bot);
        double r = Math.random();
        
        // High priority: if mood is distressed, use mood line 40% of the time
        if (moodLine != null && r < 0.40) {
            return moodLine;
        }
        
        // Otherwise: 25% context, 30% mode, rest simple
        if (context != null && !context.isBlank() && r < 0.55) {
            return context;
        }
        if (r < 0.70) {
            return modeLine;
        }
        return simple;
    }

    /**
     * Get a mood-specific response line based on the bot's emotional state.
     * 
     * @return A mood-appropriate line, or null for NEUTRAL/CONTENT states
     */
    private static String getMoodLine(ServerPlayerEntity bot, String name) {
        EmotionalState mood = BotMoodManager.getMood(bot);
        
        return switch (mood) {
            case STRESSED -> choose(
                    "Still a bit on edge after that fight, " + name + ".",
                    "Give me a second—still catching my breath.",
                    "That was close. I'm alright though.",
                    "Heart's still pounding from that scuffle."
            );
            case INJURED -> choose(
                    "I'm a bit banged up, " + name + ".",
                    "Could use a moment to recover.",
                    "Not at my best right now.",
                    "I've had better days, health-wise."
            );
            case HUNGRY -> choose(
                    "My stomach's been grumbling.",
                    "Could really use a snack, " + name + ".",
                    "Getting hungry over here.",
                    "Food would be nice about now."
            );
            case CONTENT -> choose(
                    "Doing great, " + name + "!",
                    "Life's good right now.",
                    "No complaints here.",
                    "Feeling pretty good, actually."
            );
            case NEUTRAL -> null; // Use default responses
        };
    }

        private static String buildContextLine(ServerPlayerEntity toucher, ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) {
            return null;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }

        String name = toucher != null ? toucher.getName().getString() : "there";
        BlockPos pos = bot.getBlockPos();

        String biome = world.getBiome(pos)
            .getKey()
            .map(k -> k.getValue().getPath())
            .orElse("somewhere");

        long tod = Math.floorMod(world.getTimeOfDay(), 24_000L);
        String timePhase = describeTimePhase(tod);
        String weather = world.isThundering() ? "thundering" : (world.isRaining() ? "raining" : "clear");

        int food = bot.getHungerManager() != null ? bot.getHungerManager().getFoodLevel() : 20;
        float hp = bot.getHealth();
        float maxHp = bot.getMaxHealth();

        String lastHobby = BotIdleHobbiesService.getLastHobbyName(bot.getUuid());
        long lastHobbyEndMs = BotIdleHobbiesService.getLastHobbyEndMs(bot.getUuid());
        boolean hobbyRecent = lastHobby != null && !lastHobby.isBlank() && lastHobbyEndMs > 0
            && (System.currentTimeMillis() - lastHobbyEndMs) < 10 * 60_000L;

        // Keep these short; avoid lore-dumps.
        if (food <= 6) {
            return choose(
                "I could really use a snack soon.",
                "Getting hungry… might want to restock food.",
                "My stomach’s complaining louder than the mobs."
            );
        }
        if (hp <= Math.max(4.0f, maxHp * 0.35f)) {
            return choose(
                "I’m a bit banged up. Maybe we should take it easy.",
                "I’m not at full strength right now.",
                "If something jumps us, I’d prefer it be a chicken."
            );
        }
        if (hobbyRecent && "fish".equalsIgnoreCase(lastHobby)) {
            return choose(
                "I was fishing a bit ago. Not a bad haul.",
                "Still smells like fish. Could be worse.",
                "The fish were cooperating earlier."
            );
        }
        if (hobbyRecent && "hangout".equalsIgnoreCase(lastHobby)) {
            return choose(
                "I was just warming up by the fire earlier.",
                "Nice to take a breather sometimes.",
                "A little campfire time does wonders."
            );
        }

        return choose(
            "It’s " + timePhase + "… and the sky looks " + weather + ".",
            "This " + biome + " has a vibe to it.",
            "We’re at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".",
            "All quiet around here, " + name + ".",
            "If you need me, I’m listening."
        );
        }

        private static String describeTimePhase(long tod) {
        // Rough vanilla phases.
        if (tod < 1_000L) return "early morning";
        if (tod < 6_000L) return "morning";
        if (tod < 11_000L) return "daytime";
        if (tod < 13_000L) return "sunset";
        if (tod < 23_000L) return "night";
        return "late night";
        }

    private static String choose(String... options) {
        if (options == null || options.length == 0) {
            return "";
        }
        int idx = (int) (Math.random() * options.length);
        idx = Math.max(0, Math.min(idx, options.length - 1));
        return options[idx];
    }
}
