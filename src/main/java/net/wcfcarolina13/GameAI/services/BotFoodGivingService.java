package net.wcfcarolina13.GameAI.services;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.wcfcarolina13.GameAI.BotActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles the direct food-giving mechanic: player right-clicks bot with food in hand.
 * <p>
 * For non-precious food, accepted whenever hunger is below full (foodLevel &lt; 20).
 * For precious food (golden apple etc.), a clickable chat prompt asks the player to confirm
 * before consuming.
 * <p>
 * Overhead dialogue lines are shown for accept and refuse, rate-limited.
 */
public final class BotFoodGivingService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-food-giving");

    /** Per-bot cooldown for accept/refuse dialogue. */
    private static final long DIALOGUE_COOLDOWN_MS = 60_000L; // 1 minute

    private static final int DISPLAY_DURATION_MS = 3_000;
    private static final double OVERHEAD_RANGE = 32.0;

    /** Precious-food confirmation prompt lifespan. */
    private static final long PENDING_FEED_TTL_MS = 15_000L;

    private static final String[] ACCEPT_LINES = {
            "Thanks, I needed that.",
            "Don't mind if I do.",
            "Much appreciated.",
            "Perfect timing."
    };

    private static final String[] REFUSE_LINES = {
            "I'm good, thanks.",
            "Maybe later.",
            "Not hungry right now.",
            "I'll pass for now."
    };

    private record PendingFeed(UUID playerUuid, UUID botUuid, Item expectedItem, long expiryMillis) {
        boolean isExpired(long nowMillis) {
            return nowMillis >= expiryMillis;
        }
    }

    private static final Map<UUID, Long> LAST_DIALOGUE_MS = new ConcurrentHashMap<>();

    /** Key = botUuid. A single pending prompt per bot at a time — a second interaction replaces it. */
    private static final Map<UUID, PendingFeed> PENDING_FEEDS = new ConcurrentHashMap<>();

    private BotFoodGivingService() {
    }

    /**
     * Attempts the food-giving interaction. Called from the UseEntityCallback before inventory opens.
     *
     * @param player the player right-clicking the bot
     * @param bot    the bot being interacted with
     * @return true if the interaction was consumed (food given, refused with dialogue, or a
     *         confirmation prompt was shown), false if the player isn't holding food (let normal
     *         interaction proceed)
     */
    public static boolean tryGiveFood(ServerPlayerEntity player, ServerPlayerEntity bot) {
        if (player == null || bot == null || player.isRemoved() || bot.isRemoved()) {
            return false;
        }

        ItemStack heldStack = player.getStackInHand(Hand.MAIN_HAND);
        if (heldStack.isEmpty()) {
            return false;
        }

        FoodComponent food = heldStack.getComponents().get(DataComponentTypes.FOOD);
        if (food == null) {
            return false; // Not food — let normal interaction proceed.
        }

        if (HealingService.isForbidden(heldStack)) {
            showDialogue(bot, false);
            return true;
        }

        HungerManager hunger = bot.getHungerManager();
        if (hunger.getFoodLevel() >= 20) {
            showDialogue(bot, false);
            return true;
        }

        if (HealingService.isPrecious(heldStack)) {
            if (net.wcfcarolina13.Frens.CONFIG.getAutoAcceptPreciousFoods(player.getUuid())) {
                // Player has opted in to auto-accept — skip the prompt and consume directly.
                doFeed(player, bot, heldStack);
            } else {
                sendConfirmationPrompt(player, bot, heldStack);
            }
            return true;
        }

        doFeed(player, bot, heldStack);
        return true;
    }

    /**
     * Invoked when the player clicks [Yes] on a precious-food confirmation prompt.
     * Validates the pending entry and completes the feed if still valid.
     */
    public static void confirmPending(ServerPlayerEntity player, UUID botUuid) {
        if (player == null || botUuid == null) return;

        PendingFeed pending = PENDING_FEEDS.get(botUuid);
        long now = System.currentTimeMillis();
        if (pending == null || pending.isExpired(now) || !pending.playerUuid.equals(player.getUuid())) {
            PENDING_FEEDS.remove(botUuid);
            player.sendMessage(Text.literal("That food offer has expired.").formatted(Formatting.GRAY), false);
            return;
        }

        ItemStack held = player.getStackInHand(Hand.MAIN_HAND);
        if (held.isEmpty() || held.getItem() != pending.expectedItem) {
            PENDING_FEEDS.remove(botUuid);
            player.sendMessage(Text.literal("You're no longer holding that item.").formatted(Formatting.GRAY), false);
            return;
        }

        MinecraftServer server = player.getCommandSource() != null ? player.getCommandSource().getServer() : null;
        ServerPlayerEntity bot = server != null ? server.getPlayerManager().getPlayer(botUuid) : null;
        if (bot == null || bot.isRemoved()) {
            PENDING_FEEDS.remove(botUuid);
            player.sendMessage(Text.literal("That bot is no longer available.").formatted(Formatting.GRAY), false);
            return;
        }

        PENDING_FEEDS.remove(botUuid);
        doFeed(player, bot, held);
    }

    /** Invoked when the player clicks [No] on a precious-food confirmation prompt. */
    public static void cancelPending(ServerPlayerEntity player, UUID botUuid) {
        if (player == null || botUuid == null) return;
        PendingFeed pending = PENDING_FEEDS.remove(botUuid);
        if (pending != null && pending.playerUuid.equals(player.getUuid())) {
            player.sendMessage(Text.literal("Kept for later.").formatted(Formatting.GRAY), false);
        }
    }

    /** Periodic sweep to purge expired pending prompts. Registered as a server tick handler. */
    public static void onServerTick(MinecraftServer server) {
        if (PENDING_FEEDS.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PendingFeed>> it = PENDING_FEEDS.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired(now)) {
                it.remove();
            }
        }
    }

    private static void sendConfirmationPrompt(ServerPlayerEntity player, ServerPlayerEntity bot, ItemStack held) {
        Item item = held.getItem();
        UUID botUuid = bot.getUuid();
        long expiry = System.currentTimeMillis() + PENDING_FEED_TTL_MS;
        PENDING_FEEDS.put(botUuid, new PendingFeed(player.getUuid(), botUuid, item, expiry));

        String itemName = held.getName().getString();
        String botName = bot.getName().getString();

        MutableText yes = Text.literal("[Yes]").styled(s -> s
                .withColor(Formatting.GREEN)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/bot feedconfirm " + botUuid)));
        MutableText no = Text.literal("[No]").styled(s -> s
                .withColor(Formatting.RED)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/bot feedcancel " + botUuid)));

        MutableText prompt = Text.literal("")
                .append(Text.literal(botName + ": ").formatted(Formatting.AQUA))
                .append(Text.literal("That " + itemName + " is precious. Eat it anyway? "))
                .append(yes)
                .append(Text.literal(" "))
                .append(no);

        player.sendMessage(prompt, false);
        LOGGER.info("Precious-food prompt shown: player={} bot={} item={}",
                player.getName().getString(), botName, item.getTranslationKey());
    }

    private static void doFeed(ServerPlayerEntity player, ServerPlayerEntity bot, ItemStack heldStack) {
        ItemStack taken = heldStack.split(1);
        if (taken.isEmpty()) {
            return;
        }

        int emptySlot = findEmptyHotbarSlot(bot);
        if (emptySlot < 0) {
            emptySlot = findEmptyInventorySlot(bot);
        }
        if (emptySlot < 0) {
            heldStack.increment(1);
            showDialogue(bot, false);
            return;
        }

        bot.getInventory().setStack(emptySlot, taken);
        bot.getInventory().markDirty();

        int hotbarSlot = emptySlot;
        if (emptySlot >= 9) {
            int hbSlot = findEmptyHotbarSlot(bot);
            if (hbSlot < 0) hbSlot = 8;
            ItemStack temp = bot.getInventory().getStack(hbSlot);
            bot.getInventory().setStack(hbSlot, bot.getInventory().getStack(emptySlot));
            bot.getInventory().setStack(emptySlot, temp);
            hotbarSlot = hbSlot;
        }

        BotActions.selectHotbarSlot(bot, hotbarSlot);
        BotActions.useSelectedItem(bot);
        bot.getInventory().markDirty();

        LOGGER.info("Bot {} accepted food {} from player {}",
                bot.getName().getString(), taken.getItem().getTranslationKey(), player.getName().getString());

        showDialogue(bot, true);
    }

    private static void showDialogue(ServerPlayerEntity bot, boolean accepted) {
        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_DIALOGUE_MS.getOrDefault(botId, 0L);
        if (now - last < DIALOGUE_COOLDOWN_MS) {
            return;
        }

        if (CompanionOverheadDialogueService.isRecentlyShown(botId)) {
            return;
        }

        double roll = ThreadLocalRandom.current().nextDouble();
        if (accepted && roll > 0.70) return;
        if (!accepted && roll > 0.50) return;

        String[] lines = accepted ? ACCEPT_LINES : REFUSE_LINES;
        String line = lines[ThreadLocalRandom.current().nextInt(lines.length)];

        LAST_DIALOGUE_MS.put(botId, now);
        CompanionOverheadDialogueService.showOverheadLine(
                bot, line, DISPLAY_DURATION_MS, OVERHEAD_RANGE,
                accepted ? "food-accept" : "food-refuse", null);

        LOGGER.info("Food-giving dialogue bot={} accepted={} line=\"{}\"",
                bot.getName().getString(), accepted, line);
    }

    private static int findEmptyHotbarSlot(ServerPlayerEntity bot) {
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static int findEmptyInventorySlot(ServerPlayerEntity bot) {
        for (int i = 9; i < 36; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** Clear pending feed prompts tied to a specific bot (e.g., when the bot is removed). */
    public static void clearPendingForBot(UUID botUuid) {
        if (botUuid != null) PENDING_FEEDS.remove(botUuid);
    }

    /** Clear pending feed prompts authored by a specific player (e.g., on disconnect). */
    public static void clearPendingForPlayer(UUID playerUuid) {
        if (playerUuid == null) return;
        PENDING_FEEDS.values().removeIf(p -> p.playerUuid.equals(playerUuid));
    }

    /** Call on server stop. */
    public static void clear() {
        LAST_DIALOGUE_MS.clear();
        PENDING_FEEDS.clear();
    }
}
