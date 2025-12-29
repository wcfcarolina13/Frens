package net.shasankp000.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Low-frequency, proximity-based "social" chatter.
 *
 * <p>Goals:
 * <ul>
 *   <li>Greet a player if the bot hasn't seen them in a while</li>
 *   <li>Occasionally remark when a player stands near the bot for some time</li>
 * </ul>
 *
 * <p>This is deliberately conservative to avoid chat spam.
 */
public final class BotAmbientSocialChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-social");

    private static final double NEAR_RADIUS = 6.0D;
    private static final double NEAR_RADIUS_SQ = NEAR_RADIUS * NEAR_RADIUS;

    private static final long NOT_SEEN_FOR_TICKS = 20L * 60L * 5L;   // 5 minutes
    private static final long GREET_COOLDOWN_TICKS = 20L * 45L;      // 45 seconds

    private static final long NEAR_CHAT_AFTER_TICKS = 20L * 25L;     // 25 seconds
    private static final long NEAR_CHAT_COOLDOWN_TICKS = 20L * 90L;  // 90 seconds

    private static final ConcurrentHashMap<String, Long> LAST_SEEN_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> NEAR_SINCE_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> LAST_CHAT_TICK = new ConcurrentHashMap<>();

    private BotAmbientSocialChatService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long nowTick = server.getTicks();

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            if (world.getRegistryKey() != World.OVERWORLD) {
                continue;
            }
            if (bot.isSleeping()) {
                continue;
            }

            // Avoid competing with skills/tasks that already speak.
            if (TaskService.hasActiveTask(bot.getUuid())) {
                continue;
            }

            List<ServerPlayerEntity> players = world.getPlayers();
            for (ServerPlayerEntity player : players) {
                if (player == null || player.isRemoved()) {
                    continue;
                }
                if (player == bot) {
                    continue;
                }
                if (BotEventHandler.isRegisteredBot(player)) {
                    continue;
                }

                double distSq = player.squaredDistanceTo(bot);
                UUID playerUuid = player.getUuid();
                UUID botUuid = bot.getUuid();
                String key = playerUuid + ":" + botUuid;

                if (distSq <= NEAR_RADIUS_SQ) {
                    long lastSeen = LAST_SEEN_TICK.getOrDefault(key, 0L);
                    LAST_SEEN_TICK.put(key, nowTick);

                    long nearSince = NEAR_SINCE_TICK.getOrDefault(key, 0L);
                    if (nearSince == 0L) {
                        NEAR_SINCE_TICK.put(key, nowTick);
                        nearSince = nowTick;
                    }

                    long lastChat = LAST_CHAT_TICK.getOrDefault(key, 0L);

                    boolean longTimeNoSee = lastSeen > 0L && (nowTick - lastSeen) > NOT_SEEN_FOR_TICKS;
                    boolean firstSeen = lastSeen == 0L;

                    if ((firstSeen || longTimeNoSee) && (nowTick - lastChat) > GREET_COOLDOWN_TICKS) {
                        String msg = pickGreetingLine(player.getName().getString());
                        say(bot, msg);
                        LAST_CHAT_TICK.put(key, nowTick);
                        continue;
                    }

                    // If they've been nearby a while, very occasionally say something.
                    if ((nowTick - nearSince) > NEAR_CHAT_AFTER_TICKS && (nowTick - lastChat) > NEAR_CHAT_COOLDOWN_TICKS) {
                        // ~0.08% chance per tick when eligible => roughly once every ~60-90s while hovering.
                        if (Math.random() < 0.0008) {
                            String msg = pickNearbyRemarkLine(player, bot);
                            if (msg != null && !msg.isBlank()) {
                                say(bot, msg);
                                LAST_CHAT_TICK.put(key, nowTick);
                            }
                        }
                    }
                } else {
                    NEAR_SINCE_TICK.remove(key);
                }
            }
        }
    }

    private static void say(ServerPlayerEntity bot, String msg) {
        if (bot == null || msg == null || msg.isBlank()) {
            return;
        }
        try {
            ChatUtils.sendChatMessages(
                    bot.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                    msg,
                    true
            );
        } catch (Throwable t) {
            LOGGER.debug("Failed to send social chat line: {}", t.getMessage());
        }
    }

    private static String pickGreetingLine(String playerName) {
        String name = playerName != null && !playerName.isBlank() ? playerName : "there";
        return choose(
                "Hey, " + name + ".",
                "Oh—" + name + ", there you are.",
                "Good to see you again, " + name + ".",
                "Welcome back, " + name + "."
        );
    }

    private static String pickNearbyRemarkLine(ServerPlayerEntity player, ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        long tod = Math.floorMod(world.getTimeOfDay(), 24_000L);
        String time = describeTimePhase(tod);
        String weather = world.isThundering() ? "thundering" : (world.isRaining() ? "raining" : "clear");
        BlockPos pos = bot.getBlockPos();

        String name = player != null ? player.getName().getString() : "there";

        // Keep it short and not too LLM-ish.
        if (bot.getHungerManager() != null && bot.getHungerManager().getFoodLevel() <= 6) {
            return choose(
                    "I’m getting hungry…",
                    "We should find food soon.",
                    "My stomach says it’s snack o’clock."
            );
        }
        if (bot.getHealth() <= Math.max(4.0f, bot.getMaxHealth() * 0.35f)) {
            return choose(
                    "I could use a breather.",
                    "Not feeling my best right now.",
                    "I’ve taken a few too many hits today."
            );
        }

        return choose(
                "It’s " + time + "… sky’s " + weather + ".",
                "All quiet around here, " + name + ".",
                "Still standing. Still ready.",
                "We’re at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "."
        );
    }

    private static String describeTimePhase(long tod) {
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
