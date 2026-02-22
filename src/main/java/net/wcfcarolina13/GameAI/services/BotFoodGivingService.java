package net.wcfcarolina13.GameAI.services;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.wcfcarolina13.GameAI.BotActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles the direct food-giving mechanic: player right-clicks bot with food in hand.
 * <p>
 * If the bot is hungry (same rules as {@link HealingService#autoEat}), the bot takes one
 * food item from the player's hand, places it in its own inventory, and eats it.
 * If the bot is not hungry, it refuses and nothing happens.
 * <p>
 * Overhead dialogue lines are shown for both accept and refuse, rate-limited.
 */
public final class BotFoodGivingService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-food-giving");

    /** Hunger thresholds (mirrors HealingService). */
    private static final int HUNGER_COMFORTABLE = 15;
    private static final int REGEN_READY_FOOD_LEVEL = 18;

    /** Per-bot cooldown for accept/refuse dialogue. */
    private static final long DIALOGUE_COOLDOWN_MS = 60_000L; // 1 minute

    private static final int DISPLAY_DURATION_MS = 3_000;
    private static final double OVERHEAD_RANGE = 32.0;

    /** Foods the bot will never eat (same as HealingService.FORBIDDEN_FOODS). */
    private static final Set<String> FORBIDDEN_FOODS = Set.of(
            "rotten_flesh", "poisonous_potato", "spider_eye", "pufferfish", "suspicious_stew"
    );

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

    private static final Map<UUID, Long> LAST_DIALOGUE_MS = new ConcurrentHashMap<>();

    private BotFoodGivingService() {
    }

    /**
     * Attempts the food-giving interaction. Called from the UseEntityCallback before inventory opens.
     *
     * @param player the player right-clicking the bot
     * @param bot    the bot being interacted with
     * @return true if the interaction was consumed (food given or refused with dialogue),
     *         false if the player isn't holding food (let normal interaction proceed)
     */
    public static boolean tryGiveFood(ServerPlayerEntity player, ServerPlayerEntity bot) {
        if (player == null || bot == null || player.isRemoved() || bot.isRemoved()) {
            return false;
        }

        ItemStack heldStack = player.getStackInHand(Hand.MAIN_HAND);
        if (heldStack.isEmpty()) {
            return false;
        }

        // Check if held item is food.
        FoodComponent food = heldStack.getComponents().get(DataComponentTypes.FOOD);
        if (food == null) {
            return false; // Not food — let normal interaction proceed.
        }

        // Check if it's a forbidden food the bot won't eat.
        String itemId = heldStack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        boolean forbidden = FORBIDDEN_FOODS.stream().anyMatch(itemId::contains);
        if (forbidden) {
            showDialogue(bot, false);
            return true; // Consumed the interaction, but refused the food.
        }

        // Check hunger conditions (mirrors HealingService.autoEat logic).
        HungerManager hunger = bot.getHungerManager();
        int foodLevel = hunger.getFoodLevel();
        float saturation = hunger.getSaturationLevel();
        float health = bot.getHealth();
        float maxHealth = bot.getMaxHealth();

        boolean isHungry = foodLevel < 20;
        boolean missingHealth = health + 0.001F < maxHealth;
        boolean needsComfortFood = foodLevel < HUNGER_COMFORTABLE;
        boolean needsRegenFuel = isHungry && missingHealth && (foodLevel < REGEN_READY_FOOD_LEVEL || saturation <= 0.0F);

        if (!needsComfortFood && !needsRegenFuel) {
            // Bot is not hungry enough — refuse.
            showDialogue(bot, false);
            return true;
        }

        // Bot is hungry — take one food item from the player and eat it.
        ItemStack taken = heldStack.split(1);
        if (taken.isEmpty()) {
            return false;
        }

        // Place food in bot's inventory.
        int emptySlot = findEmptyHotbarSlot(bot);
        if (emptySlot < 0) {
            emptySlot = findEmptyInventorySlot(bot);
        }
        if (emptySlot < 0) {
            // Bot has no room. Give item back.
            heldStack.increment(1);
            showDialogue(bot, false);
            return true;
        }

        bot.getInventory().setStack(emptySlot, taken);
        bot.getInventory().markDirty();

        // Move to hotbar and consume.
        int hotbarSlot = emptySlot;
        if (emptySlot >= 9) {
            int hbSlot = findEmptyHotbarSlot(bot);
            if (hbSlot < 0) hbSlot = 8;
            // Swap to hotbar.
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
        return true;
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

        // 70% chance to show dialogue on accept, 50% on refuse.
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

    /** Call on server stop. */
    public static void clear() {
        LAST_DIALOGUE_MS.clear();
    }
}
