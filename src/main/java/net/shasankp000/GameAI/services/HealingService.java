package net.shasankp000.GameAI.services;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized service for bot healing and hunger management.
 * Handles both automatic eating and manual heal commands.
 */
public final class HealingService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(HealingService.class);
    
    // Hunger thresholds
    private static final int HUNGER_COMFORTABLE = 15;  // Eat when below this
    private static final int HUNGER_WARNING = 10;      // "I'm hungry"
    private static final int HUNGER_CRITICAL = 5;      // "I'm starving"
    private static final int HUNGER_EMERGENCY = 2;     // "I'll die if I don't eat!"
    
    // Health threshold for eating to regen health
    private static final float HEALTH_EAT_THRESHOLD = 15.0F; // 75% of 20
    
    // Track when we last complained about hunger to avoid spam
    private static final Map<UUID, Long> LAST_HUNGER_WARNING = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_WARNED_LEVEL = new ConcurrentHashMap<>();
    private static final long WARNING_COOLDOWN_MS = 30000; // 30 seconds
    
    // Foods with negative effects to avoid
    private static final Set<String> FORBIDDEN_FOODS = Set.of(
        "rotten_flesh",
        "poisonous_potato",
        "spider_eye",
        "pufferfish",
        "suspicious_stew" // Can have random effects
    );
    
    private HealingService() {
    }
    
    /**
     * Automatic hunger/health monitoring. Call from tick loops.
     * Eats when hungry or health is low.
     */
    public static boolean autoEat(ServerPlayerEntity bot) {
        if (bot == null || bot.isDead() || bot.isUsingItem()) {
            return false;
        }
        
        HungerManager hunger = bot.getHungerManager();
        int foodLevel = hunger.getFoodLevel();
        float health = bot.getHealth();
        float maxHealth = bot.getMaxHealth();
        
        boolean needsFood = foodLevel < HUNGER_COMFORTABLE;
        boolean needsHealthRegen = (health / maxHealth) <= 0.75F && foodLevel < 20;
        
        if (!needsFood && !needsHealthRegen) {
            return false;
        }
        
        // Check warnings
        checkHungerWarnings(bot, foodLevel);
        
        // Try to eat
        PlayerInventory inv = bot.getInventory();
        OptionalInt foodSlot = findCheapestSafeFood(inv);
        
        if (foodSlot.isEmpty()) {
            return false;
        }
        
        return consumeFood(bot, foodSlot.getAsInt());
    }
    
    /**
     * Manual heal command - eat until fully satiated
     */
    public static boolean healBot(ServerPlayerEntity bot) {
        if (bot == null || bot.isDead()) {
            return false;
        }
        
        HungerManager hunger = bot.getHungerManager();
        PlayerInventory inv = bot.getInventory();
        
        int consumed = 0;
        
        // Eat until fully satiated
        while (hunger.getFoodLevel() < 20) {
            OptionalInt foodSlot = findCheapestSafeFood(inv);
            
            if (foodSlot.isEmpty()) {
                if (consumed == 0) {
                    ChatUtils.sendChatMessages(bot.getCommandSource(), "I don't have any safe food to eat!");
                    return false;
                } else {
                    ChatUtils.sendChatMessages(bot.getCommandSource(), "I ate " + consumed + " food item(s), but I'm still hungry.");
                    return true;
                }
            }
            
            if (!consumeFood(bot, foodSlot.getAsInt())) {
                // Failed to consume - might be using an item
                if (consumed > 0) {
                    ChatUtils.sendChatMessages(bot.getCommandSource(), "I ate " + consumed + " food item(s) so far.");
                    return true;
                }
                return false;
            }
            
            consumed++;
            
            // Safety: don't loop forever
            if (consumed > 20) {
                LOGGER.warn("Heal loop exceeded 20 iterations for bot {}", bot.getName().getString());
                break;
            }
        }
        
        if (consumed > 0) {
            ChatUtils.sendChatMessages(bot.getCommandSource(), "I ate " + consumed + " food item(s). I feel better now!");
            return true;
        }
        
        return false;
    }
    
    /**
     * Check hunger levels and send warnings if needed
     */
    private static void checkHungerWarnings(ServerPlayerEntity bot, int foodLevel) {
        UUID uuid = bot.getUuid();
        long now = System.currentTimeMillis();
        
        Long lastWarning = LAST_HUNGER_WARNING.get(uuid);
        Integer lastLevel = LAST_WARNED_LEVEL.get(uuid);
        
        // Cooldown check
        if (lastWarning != null && (now - lastWarning) < WARNING_COOLDOWN_MS) {
            return;
        }
        
        // Don't repeat same warning level
        if (lastLevel != null && lastLevel == foodLevel) {
            return;
        }
        
        String message = null;
        
        if (foodLevel <= HUNGER_EMERGENCY) {
            message = "I'll die if I don't eat!";
        } else if (foodLevel <= HUNGER_CRITICAL) {
            message = "I'm starving!";
        } else if (foodLevel <= HUNGER_WARNING) {
            message = "I'm hungry!";
        }
        
        if (message != null) {
            PlayerInventory inv = bot.getInventory();
            OptionalInt foodSlot = findCheapestSafeFood(inv);
            
            if (foodSlot.isEmpty()) {
                // No food available - complain
                ChatUtils.sendChatMessages(bot.getCommandSource(), message);
                LAST_HUNGER_WARNING.put(uuid, now);
                LAST_WARNED_LEVEL.put(uuid, foodLevel);
            }
        }
    }
    
    /**
     * Find the cheapest safe food in inventory
     */
    private static OptionalInt findCheapestSafeFood(PlayerInventory inventory) {
        int bestSlot = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            
            FoodComponent food = getFoodComponent(stack);
            if (food == null) {
                continue;
            }
            
            // Skip forbidden foods (items with known negative effects)
            String itemId = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            boolean forbidden = FORBIDDEN_FOODS.stream().anyMatch(itemId::contains);
            if (forbidden) {
                continue;
            }
            
            // Calculate nutrition value (lower = cheaper/less valuable)
            double score = food.nutrition() + (food.saturation() * 2.0);
            
            if (score < bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        
        return bestSlot >= 0 ? OptionalInt.of(bestSlot) : OptionalInt.empty();
    }
    
    /**
     * Consume food from the given slot
     */
    private static boolean consumeFood(ServerPlayerEntity bot, int slot) {
        if (bot.isUsingItem()) {
            return false;
        }
        
        PlayerInventory inv = bot.getInventory();
        ItemStack foodStack = inv.getStack(slot);
        
        if (foodStack.isEmpty() || getFoodComponent(foodStack) == null) {
            return false;
        }
        
        // Move to hotbar if needed
        int targetSlot = slot;
        if (slot >= 9) {
            // Find empty hotbar slot or use slot 8
            int hotbarSlot = findEmptyHotbarSlot(inv).orElse(8);
            swapStacks(inv, slot, hotbarSlot);
            targetSlot = hotbarSlot;
        }
        
        // Select and use
        BotActions.selectHotbarSlot(bot, targetSlot);
        BotActions.useSelectedItem(bot);
        inv.markDirty();
        
        LOGGER.debug("Bot {} consuming food from slot {}", bot.getName().getString(), targetSlot);
        return true;
    }
    
    private static FoodComponent getFoodComponent(ItemStack stack) {
        return stack != null && !stack.isEmpty() 
            ? stack.getComponents().get(DataComponentTypes.FOOD) 
            : null;
    }
    
    private static OptionalInt findEmptyHotbarSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }
    
    private static void swapStacks(PlayerInventory inventory, int from, int to) {
        if (from == to) {
            return;
        }
        ItemStack fromStack = inventory.getStack(from);
        ItemStack toStack = inventory.getStack(to);
        inventory.setStack(to, fromStack);
        inventory.setStack(from, toStack);
        inventory.markDirty();
    }
    
    /**
     * Clear warning state for a bot (e.g., when they get food)
     */
    public static void clearWarnings(UUID botUuid) {
        LAST_HUNGER_WARNING.remove(botUuid);
        LAST_WARNED_LEVEL.remove(botUuid);
    }
}
