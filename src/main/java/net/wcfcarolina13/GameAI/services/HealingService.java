package net.wcfcarolina13.GameAI.services;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.PlayerUtils.BundleReachPolicy;
import net.wcfcarolina13.PlayerUtils.InventoryIterator;
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

    /**
     * Natural regeneration requires a sufficiently high hunger bar. In vanilla this is effectively
     * "9+ shanks" ($\ge 18$ food level). We aim to reach that threshold when the bot is safe.
     */
    private static final int REGEN_READY_FOOD_LEVEL = 18;

    // Safety window for eating (avoid interrupting combat reactions).
    private static final double HOSTILE_ALERT_DISTANCE_SQ = 36.0D; // 6 blocks
    
    // Health threshold for eating to regen health (legacy; kept for backwards-compat behaviour)
    @SuppressWarnings("unused")
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

    // Valuable foods the bot should preserve — only eaten at starvation emergency.
    private static final Set<String> PRECIOUS_FOODS = Set.of(
        "golden_apple",
        "enchanted_golden_apple",
        "golden_carrot"
    );

    // Raw meats that have a smelted/cooked counterpart. The bot should prefer the
    // cooked variant when both are present — vanilla cooking is a flat upgrade
    // (more nutrition + more saturation, no negative effects). Raw chicken
    // additionally has a 30% food-poisoning chance, so it's also penalised here.
    // Tropical fish has no cooked variant in vanilla (and isn't really meant to
    // be eaten), and pufferfish is already in FORBIDDEN_FOODS.
    private static final Set<Item> RAW_MEATS = Set.of(
        Items.BEEF,
        Items.PORKCHOP,
        Items.MUTTON,
        Items.CHICKEN,
        Items.RABBIT,
        Items.COD,
        Items.SALMON
    );

    public static boolean isRawMeat(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return RAW_MEATS.contains(stack.getItem());
    }
    
    /**
     * Returns true if the item is edible and suitable for fast-travel provisioning
     * (i.e., not toxic and not too precious to consume casually).
     */
    public static boolean isTravelUsableFood(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        FoodComponent food = getFoodComponent(stack);
        if (food == null) return false;
        if (isForbidden(stack)) return false;
        if (isPrecious(stack)) return false;
        return true;
    }

    public static boolean isPrecious(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String itemId = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        return PRECIOUS_FOODS.stream().anyMatch(itemId::contains);
    }

    public static boolean isForbidden(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String itemId = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        return FORBIDDEN_FOODS.stream().anyMatch(itemId::contains);
    }

    /**
     * Returns true if the bot has at least one full stack (64+) of any single non-precious,
     * non-forbidden edible item. Used to lower the auto-eat stinginess threshold.
     */
    public static boolean hasAbundantFood(ServerPlayerEntity bot) {
        if (bot == null) return false;
        // Bundle-aware: a stack tucked into a bundle still counts toward "plenty to eat".
        return InventoryIterator.stream(bot)
                .map(InventoryIterator.SlotRef::stack)
                .anyMatch(stack -> stack.getCount() >= 64
                        && !isPrecious(stack)
                        && !isForbidden(stack)
                        && getFoodComponent(stack) != null
                        && getFoodComponent(stack).nutrition() > 0);
    }

    /** Foods {@link #findCheapestSafeFood} would consider eligible (ignoring the raw/cooked split). */
    private static boolean isSafeEdible(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        FoodComponent food = getFoodComponent(stack);
        if (food == null || food.nutrition() <= 0) return false;
        return !isForbidden(stack) && !isPrecious(stack);
    }

    /**
     * "No food in a direct slot, but there is food inside a bundle" recovery.
     *
     * <p>Pulls one safe edible out of a bundle so the slot-indexed eat path can reach it.
     * {@code BundleService.extract} is <b>server-thread only</b>; {@link #stabilizeEat} runs on a
     * worker thread, so the extraction is scheduled with the same
     * {@code bot.getCommandSource().getServer().execute(...)} hop that path already uses for
     * {@link #consumeFood}, then briefly waited on before the caller rescans.
     *
     * @return true when an extraction was performed (caller should rescan the inventory)
     */
    private static boolean tryReachBundledFood(ServerPlayerEntity bot) {
        if (bot == null) return false;
        int direct = InventoryIterator.countDirect(bot, HealingService::isSafeEdible);
        int total = InventoryIterator.count(bot, HealingService::isSafeEdible);
        // One bite is enough to unblock the eat path; the caller loops if it needs more.
        if (BundleReachPolicy.extractionsNeeded(1, direct, total - direct) <= 0) {
            return false;
        }
        // Shared thread hop (server-thread fast path, else future) lives in BundleService.
        return BundleService.reachFirst(bot, HealingService::isSafeEdible);
    }

    private HealingService() {
    }

    public static boolean isHungry(ServerPlayerEntity bot) {
        return bot != null && bot.getHungerManager().getFoodLevel() <= HUNGER_WARNING;
    }

    public static boolean isStarving(ServerPlayerEntity bot) {
        return bot != null && bot.getHungerManager().getFoodLevel() <= HUNGER_CRITICAL;
    }

    public static boolean isEmergencyHungry(ServerPlayerEntity bot) {
        return bot != null && bot.getHungerManager().getFoodLevel() <= HUNGER_EMERGENCY;
    }

    /**
     * Iteration-boundary helper for long-running skills. Returns true iff the bot is
     * starving AND a single autoEat pass couldn't fix it (no food in inventory or only
     * forbidden food). When true, the skill should bail out (and typically flag for
     * manual resume) so the bot doesn't grind itself to death on a stripmine/farm/etc.
     * Skills that already do their own hunger handling (HuntSkill, FishingSkill,
     * GrassSeedSkill) keep their existing logic; this helper is for the rest.
     */
    public static boolean shouldPauseForStarvation(ServerPlayerEntity bot) {
        if (!isStarving(bot)) return false;
        if (autoEat(bot)) return false;
        return true;
    }

    /**
     * Automatic hunger/health monitoring. Call from tick loops.
     * Eats when hungry or health is low.
     */
    public static boolean autoEat(ServerPlayerEntity bot) {
        return autoEat(bot, null);
    }

    /**
     * Variant that accepts hostile context (when already computed by the caller).
     *
     * <p>Behavior requested: when the bot is safe, not under attack, hungry, and not at full health,
     * it should eat its cheapest safe food until its hunger bar is high enough to naturally regenerate.</p>
     */
    public static boolean autoEat(ServerPlayerEntity bot, List<Entity> hostileEntities) {
        if (bot == null || bot.isDead() || bot.isUsingItem()) {
            return false;
        }

        HungerManager hunger = bot.getHungerManager();
        int foodLevel = hunger.getFoodLevel();
        float saturation = hunger.getSaturationLevel();
        float health = bot.getHealth();
        float maxHealth = bot.getMaxHealth();

        boolean hungry = foodLevel < 20;
        boolean missingHealth = health + 0.001F < maxHealth;

        // Keep legacy behaviour: don't casually dip into food while at full hunger.
        // Raise the threshold when the bot has a full stack of non-precious food — if there's
        // plenty to eat, no reason to be stingy.
        int effectiveComfort = hasAbundantFood(bot) ? 18 : HUNGER_COMFORTABLE;
        boolean needsComfortFood = foodLevel < effectiveComfort;

        // New behaviour: if we're hurt and our hunger/saturation isn't high enough to naturally regen hearts, top up.
        // In practice, hearts won't regen reliably unless the hunger bar is high (>=18) and there's some satiation.
        boolean needsRegenFuel = hungry && missingHealth && (foodLevel < REGEN_READY_FOOD_LEVEL || saturation <= 0.0F);

        if (!needsComfortFood && !needsRegenFuel) {
            return false;
        }

        // If we're in danger or actively being attacked, only eat when it's truly urgent.
        boolean emergency = foodLevel <= HUNGER_CRITICAL;
        if (!emergency && !isSafeToEat(bot, hostileEntities)) {
            return false;
        }

        // Check warnings (only tied to hunger levels).
        checkHungerWarnings(bot, foodLevel);

        PlayerInventory inv = bot.getInventory();
        OptionalInt foodSlot = findCheapestSafeFood(inv);
        if (foodSlot.isEmpty() && tryReachBundledFood(bot)) {
            foodSlot = findCheapestSafeFood(inv);
        }
        if (foodSlot.isEmpty()) {
            // Last resort: eat rotten flesh if starving or hurt with no other food.
            // Hunger debuff is annoying but better than starving to death.
            if (emergency || missingHealth) {
                foodSlot = findDesperateFood(inv);
            }
            if (foodSlot.isEmpty()) {
                return false;
            }
        }

        return consumeFood(bot, foodSlot.getAsInt());
    }

    /**
     * Eat up to {@code maxBites} food items, blocking between each bite.
     * Designed for worker threads (pre-shelter stabilization, in-shelter eating).
     * Stops early if hunger is high enough for natural regen.
     * Rotten flesh is only tried as the very first bite (don't waste time on more).
     *
     * @return number of items consumed
     */
    public static int stabilizeEat(ServerPlayerEntity bot, int maxBites) {
        int eaten = 0;
        for (int i = 0; i < maxBites; i++) {
            if (bot == null || bot.isDead() || bot.isRemoved()) break;
            HungerManager hunger = bot.getHungerManager();
            if (hunger.getFoodLevel() >= REGEN_READY_FOOD_LEVEL && hunger.getSaturationLevel() > 0)
                break; // hunger high enough for natural regen

            PlayerInventory inv = bot.getInventory();
            OptionalInt slot = findCheapestSafeFood(inv);
            if (slot.isEmpty() && tryReachBundledFood(bot)) {
                slot = findCheapestSafeFood(inv);
            }
            if (slot.isEmpty()) {
                // Desperate: try rotten flesh but only as the first bite
                if (eaten == 0) slot = findDesperateFood(inv);
                if (slot.isEmpty()) break;
            }

            final int foodSlot = slot.getAsInt();
            try {
                bot.getCommandSource().getServer().execute(() -> consumeFood(bot, foodSlot));
                Thread.sleep(1700); // vanilla eat time ~1.6s
                eaten++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return eaten;
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
            if (foodSlot.isEmpty() && tryReachBundledFood(bot)) {
                foodSlot = findCheapestSafeFood(inv);
            }

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
            CompanionOverheadDialogueService.showOverheadLine(bot, message, 3000, 32.0, "hunger", null);
            LAST_HUNGER_WARNING.put(uuid, now);
            LAST_WARNED_LEVEL.put(uuid, foodLevel);
        }
    }
    
    /**
     * Find the cheapest safe food in inventory.
     *
     * <p>Two-pass: prefer non-raw foods (cooked meats, breads, fruits, vegetables,
     * stews), then fall back to raw meats only if nothing cooked is available.
     * Without this preference the cheapest-by-score logic always picks raw meat
     * over its cooked counterpart (raw beef = 6.6, cooked beef = 33.6) — the
     * bot would happily eat raw chicken next to a stack of cooked chicken,
     * eating more of it for the same hunger refill and risking food poisoning.
     */
    private static OptionalInt findCheapestSafeFood(PlayerInventory inventory) {
        OptionalInt cooked = findCheapestSafeFood(inventory, false);
        if (cooked.isPresent()) return cooked;
        return findCheapestSafeFood(inventory, true);
    }

    /**
     * @param allowRaw if true, raw meats (beef, porkchop, mutton, chicken, rabbit,
     *                 cod, salmon) are eligible. If false, they are skipped.
     */
    private static OptionalInt findCheapestSafeFood(PlayerInventory inventory, boolean allowRaw) {
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

            // Skip precious foods (too valuable to eat casually)
            if (PRECIOUS_FOODS.stream().anyMatch(itemId::contains)) {
                continue;
            }

            if (!allowRaw && isRawMeat(stack)) {
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
     * Last-resort food search: first tries rotten flesh (hunger debuff), then
     * precious foods (golden apple, golden carrot — valuable but edible).
     * Still excludes truly dangerous items (spider eye, pufferfish, poisonous potato).
     */
    private static OptionalInt findDesperateFood(PlayerInventory inventory) {
        // First pass: rotten flesh (cheap, acceptable in desperation)
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            FoodComponent food = getFoodComponent(stack);
            if (food == null) continue;
            String itemId = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (itemId.contains("rotten_flesh")) {
                LOGGER.info("Desperate food: eating rotten flesh (slot {})", i);
                return OptionalInt.of(i);
            }
        }
        // Second pass: precious foods (golden apple, golden carrot — last resort)
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            FoodComponent food = getFoodComponent(stack);
            if (food == null) continue;
            String itemId = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (PRECIOUS_FOODS.stream().anyMatch(itemId::contains)) {
                LOGGER.info("Desperate food: eating precious food {} (slot {})", itemId, i);
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    private static boolean isSafeToEat(ServerPlayerEntity bot, List<Entity> hostiles) {
        if (bot == null) {
            return false;
        }

        // "Not under attack": if something is actively set as our attacker, play it safe.
        if (bot.getAttacker() != null) {
            return false;
        }

        if (hostiles == null || hostiles.isEmpty()) {
            return true;
        }

        for (Entity hostile : hostiles) {
            if (hostile == null || hostile.isRemoved() || !hostile.isAlive()) {
                continue;
            }
            if (hostile.squaredDistanceTo(bot) <= HOSTILE_ALERT_DISTANCE_SQ && bot.canSee(hostile)) {
                return false;
            }
        }

        return true;
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
