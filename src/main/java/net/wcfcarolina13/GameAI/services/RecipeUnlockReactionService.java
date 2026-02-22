package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small reactive chatter: when a (non-bot) player crafts something for the first time
 * (treated as a "new recipe unlocked" moment), a nearby bot may comment.
 *
 * <p>Intentionally conservative to avoid spam. This is not tied to the recipe book toast
 * event; it triggers when we detect a new entry in {@link CraftingHistoryService}.</p>
 */
public final class RecipeUnlockReactionService {

    private static final Logger LOGGER = LoggerFactory.getLogger("recipe-unlock-react");

    private static final double REACT_RADIUS = 10.0D;
    private static final double REACT_RADIUS_SQ = REACT_RADIUS * REACT_RADIUS;

    private static final long PLAYER_COOLDOWN_TICKS = 20L * 10L; // 10 seconds

    private static final ConcurrentHashMap<UUID, Long> LAST_REACT_TICK_BY_PLAYER = new ConcurrentHashMap<>();

    private static final String[] LINES = new String[] {
            "New recipe unlocked.",
            "Nice. New recipe.",
            "That's a new one.",
            "Good. More options now.",
            "Another recipe for the book.",
            "That might come in handy."
    };

    private RecipeUnlockReactionService() {
    }

    public static void onNewCraftedRecipe(ServerPlayerEntity player, Identifier recipeId) {
        if (player == null || recipeId == null) {
            return;
        }
        if (BotEventHandler.isRegisteredBot(player)) {
            return;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }

        long nowTick = server.getTicks();
        long last = LAST_REACT_TICK_BY_PLAYER.getOrDefault(player.getUuid(), 0L);
        if (last > 0L && (nowTick - last) < PLAYER_COOLDOWN_TICKS) {
            return;
        }

        ServerPlayerEntity bot = pickNearbyBot(server, player);
        if (bot == null) {
            return;
        }

        // Avoid competing with active tasks that already talk.
        if (TaskService.hasActiveTask(bot.getUuid())) {
            return;
        }

        String line = choose(LINES);
        if (line == null || line.isBlank()) {
            return;
        }

        LAST_REACT_TICK_BY_PLAYER.put(player.getUuid(), nowTick);

        try {
            ChatUtils.sendChatMessages(
                    bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                    line,
                    true
            );
        } catch (Throwable t) {
            LOGGER.debug("Failed to send recipe-unlock reaction: {}", t.getMessage());
        }
    }

    private static ServerPlayerEntity pickNearbyBot(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null) {
            return null;
        }

        List<ServerPlayerEntity> bots = BotEventHandler.getRegisteredBots(server);
        if (bots == null || bots.isEmpty()) {
            return null;
        }

        return bots.stream()
                .filter(b -> b != null && !b.isRemoved())
                .filter(b -> b.getEntityWorld() == player.getEntityWorld())
                .filter(b -> !b.isSleeping())
                .filter(b -> player.squaredDistanceTo(b) <= REACT_RADIUS_SQ)
                .min(Comparator.comparingDouble(player::squaredDistanceTo))
                .orElse(null);
    }

    private static String choose(String... options) {
        if (options == null || options.length == 0) {
            return null;
        }
        int idx = (int) (Math.random() * options.length);
        idx = Math.max(0, Math.min(options.length - 1, idx));
        return options[idx];
    }
}
