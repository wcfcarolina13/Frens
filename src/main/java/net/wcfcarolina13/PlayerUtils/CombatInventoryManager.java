package net.wcfcarolina13.PlayerUtils;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.BotCombatCalloutService;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.ElytraFlightService;
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;

import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.List;

import net.minecraft.util.Hand;

/**
 * Helper utilities that prepare the bot's inventory for combat situations.
 *
 * <p>The routines here are intentionally conservative: they avoid reshuffling
 * stacks unless a clear upgrade is available, and they operate entirely on the
 * server thread by being invoked from AI loops that already run there.</p>
 */
public final class CombatInventoryManager {

    private static final double MIN_COMBAT_ATTACK_SPEED = 6.0D;
    private static final int HOTBAR_SIZE = 9;
    private static final int FOOD_HOTBAR_SLOT = 7;
    private static final int HUNGER_THRESHOLD = 14;
    private static final float CRITICAL_HEALTH_THRESHOLD = 12.0F;
    private static final double HOSTILE_ALERT_DISTANCE_SQ = 36.0D; // 6 blocks

    /** Minimum server ticks between bundle-reach attempts, per bot (this runs on the server tick). */
    private static final long BUNDLE_REACH_COOLDOWN_TICKS = 100L;
    private static final java.util.Map<java.util.UUID, Long> LAST_BUNDLE_REACH_TICK =
            new java.util.concurrent.ConcurrentHashMap<>();

    private CombatInventoryManager() {
    }

    /**
     * Rate-limit gate for the bundle-reach paths below. {@code ensureCombatLoadout} is called from
     * per-tick server loops (AutoFaceEntity, idle hobbies), so an unbounded reach attempt would scan
     * bundles every tick. Consumes the cooldown when it returns false (i.e. when a reach may proceed).
     */
    private static boolean bundleReachRateLimited(ServerPlayerEntity bot) {
        if (bot == null || bot.getEntityWorld() == null) {
            return true;
        }
        long now = bot.getEntityWorld().getTime();
        java.util.UUID id = bot.getUuid();
        Long last = LAST_BUNDLE_REACH_TICK.get(id);
        if (last != null && now - last < BUNDLE_REACH_COOLDOWN_TICKS && now >= last) {
            return true;
        }
        LAST_BUNDLE_REACH_TICK.put(id, now);
        return false;
    }

    /**
     * Ensures that the bot has sensible armor, weapon, shield, and food ready
     * whenever combat is imminent.
     */
    public static void ensureCombatLoadout(ServerPlayerEntity bot) {
        if (bot == null || bot.isDead()) {
            return;
        }

        autoEquipMissingArmor(bot);
        ensureOffhandShield(bot);
        ensureBestWeaponAccessible(bot);
        ensureFoodAccessible(bot);
        boostAttackSpeed(bot);
    }

    public static boolean tryConsumeIfNeeded(ServerPlayerEntity bot, List<Entity> hostileEntities) {
        if (bot == null || bot.isDead()) {
            return false;
        }

        if (bot.isUsingItem()) {
            return false;
        }

        HungerManager hungerManager = bot.getHungerManager();
        int foodLevel = hungerManager.getFoodLevel();
        boolean hungerLow = foodLevel <= HUNGER_THRESHOLD;
        boolean healthLow = bot.getHealth() <= CRITICAL_HEALTH_THRESHOLD && foodLevel < 20;
        boolean shouldEat = hungerLow || healthLow;

        if (!shouldEat || !isSafeToEat(bot, hostileEntities)) {
            return false;
        }

        PlayerInventory inventory = bot.getInventory();
        OptionalInt foodSlot = findCheapestFoodSlot(inventory);
        if (foodSlot.isEmpty()) {
            return false;
        }

        int slot = foodSlot.getAsInt();
        if (slot >= HOTBAR_SIZE) {
            swapStacks(inventory, slot, FOOD_HOTBAR_SLOT);
            slot = FOOD_HOTBAR_SLOT;
        }

        BotActions.selectHotbarSlot(bot, slot);
        BotActions.useSelectedItem(bot);
        inventory.markDirty();
        return true;
    }

    private static void autoEquipMissingArmor(ServerPlayerEntity bot) {
        if (ElytraFlightService.isInFlight(bot.getUuid())) {
            return;
        }
        boolean missingPiece = Arrays.stream(new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
                .anyMatch(slot -> !armorUtils.isValidArmorForSlot(bot.getEquippedStack(slot), slot));

        if (missingPiece) {
            armorUtils.autoEquipArmor(bot);
        }
    }

    private static void ensureOffhandShield(ServerPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        ItemStack offhand = bot.getOffHandStack();

        // Check if current offhand is a preserved-below-threshold shield and request fallback
        if (!offhand.isEmpty()
                && isShieldStack(offhand)
                && DurabilityPolicyService.shouldAvoid(bot, offhand)) {
            if (BotCombatCalloutService.isInCombat(bot.getUuid())) {
                CompanionOverheadDialogueService.tryShowGearCombatEdge(bot);
            }
            DurabilityFallbackService.requestRefresh(
                    bot, DurabilityFallbackService.GearCategory.SHIELD);
            return;
        }

        if (isShieldStack(offhand)) {
            return; // Already holding a shield
        }

        java.util.function.Predicate<ItemStack> usableShield = stack ->
                isShieldStack(stack) && !DurabilityPolicyService.shouldAvoid(bot, stack);
        OptionalInt shieldSlot = findItemSlot(inventory, usableShield);
        if (shieldSlot.isEmpty()) {
            // Nothing in a direct slot — a shield may still be inside a bundle. Only worth the hop
            // when the bundle-aware count exceeds the direct count, and only once per cooldown.
            if (InventoryIterator.countDirect(bot, usableShield) == 0
                    && InventoryIterator.count(bot, usableShield) > 0
                    && BundleReachPolicy.shouldReachForBetter(-1, 0, bundleReachRateLimited(bot))
                    && net.wcfcarolina13.GameAI.services.BundleService.reachFirst(bot, usableShield)) {
                shieldSlot = findItemSlot(inventory, usableShield);
            }
            if (shieldSlot.isEmpty()) {
                return;
            }
        }

        ItemStack shieldStack = inventory.getStack(shieldSlot.getAsInt());
        inventory.setStack(shieldSlot.getAsInt(), offhand);
        bot.setStackInHand(Hand.OFF_HAND, shieldStack);
        inventory.markDirty();
    }

    private static void ensureBestWeaponAccessible(ServerPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        ItemStack priorHeld = bot.getMainHandStack();
        boolean priorWasFiltered = !priorHeld.isEmpty()
                && DurabilityPolicyService.shouldAvoid(bot, priorHeld);

        OptionalInt bestWeaponSlot = findBestWeaponSlot(bot, inventory);
        // Bundle-aware: a strictly better weapon may be sitting inside a bundle. Extract it and
        // rescan direct slots (bundled stacks are read-only views and are never selected directly).
        if (reachBetterBundledWeapon(bot, inventory, bestWeaponSlot)) {
            bestWeaponSlot = findBestWeaponSlot(bot, inventory);
        }
        if (bestWeaponSlot.isEmpty()) {
            // If the currently-held weapon is preserved-below-threshold, request fallback for SWORD category.
            if (!priorHeld.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, priorHeld)) {
                if (BotCombatCalloutService.isInCombat(bot.getUuid())) {
                    CompanionOverheadDialogueService.tryShowGearCombatEdge(bot);
                }
                DurabilityFallbackService.requestRefresh(
                        bot, DurabilityFallbackService.GearCategory.SWORD);
            }
            return;
        }

        int slot = bestWeaponSlot.getAsInt();
        int hotbarTarget = 0;

        if (slot >= HOTBAR_SIZE) {
            swapStacks(inventory, slot, hotbarTarget);
        }

        inventory.setSelectedSlot(hotbarTarget);
        inventory.markDirty();

        if (priorWasFiltered) {
            CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
        }
    }

    private static void ensureFoodAccessible(ServerPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();

        boolean foodInHotbar = false;
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (isFoodStack(inventory.getStack(i))) {
                foodInHotbar = true;
                break;
            }
        }

        if (foodInHotbar) {
            return;
        }

        OptionalInt foodSlot = findItemSlot(inventory, CombatInventoryManager::isFoodStack);
        foodSlot.ifPresent(slot -> {
            int target = 1; // Keep food near weapon
            if (slot != target) {
                swapStacks(inventory, slot, target);
            }
        });
    }

    private static OptionalInt findBestWeaponSlot(ServerPlayerEntity bot, PlayerInventory inventory) {
        // First pass: find the best compliant weapon (swords, axes, tridents, etc.)
        int bestIndex = -1;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            double score = evaluateWeapon(stack);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }

        if (bestIndex >= 0) {
            return OptionalInt.of(bestIndex);
        }

        // Second pass: no compliant "real" weapon found. This happens when the only
        // sword/axe is filtered by the durability preservation policy. Fall back to
        // any compliant mining tool (pickaxe/shovel/hoe) that can still deal melee
        // damage. Axes are already scored by evaluateWeapon, so only need to cover
        // item types it doesn't recognize.
        int fallbackIndex = -1;
        double fallbackScore = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;

            double score = evaluateWeapon(stack);
            if (score == Double.NEGATIVE_INFINITY) {
                // evaluateWeapon doesn't recognize this item — check if it's a
                // mining tool we can use as a fallback melee option.
                String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
                if (key.endsWith("_pickaxe")) {
                    score = 55 + materialWeight(key); // iron pickaxe ~58, diamond ~59
                } else if (key.endsWith("_shovel") || key.endsWith("_hoe")) {
                    score = 40 + materialWeight(key);
                } else {
                    continue;
                }
            }

            if (score > fallbackScore) {
                fallbackScore = score;
                fallbackIndex = i;
            }
        }

        return fallbackIndex >= 0 ? OptionalInt.of(fallbackIndex) : OptionalInt.empty();
    }

    /**
     * Pulls a bundled weapon into a direct slot when it scores strictly better than the best direct
     * candidate. Server thread (see the class javadoc); {@code reachFirst} takes its on-thread fast
     * path here. Gated by {@link #bundleReachRateLimited} because callers tick every server tick.
     *
     * @return true when a weapon was moved out of a bundle
     */
    private static boolean reachBetterBundledWeapon(ServerPlayerEntity bot,
                                                    PlayerInventory inventory,
                                                    OptionalInt bestDirectSlot) {
        if (bot == null || inventory == null) {
            return false;
        }
        double bestDirectScore = bestDirectSlot.isPresent()
                ? evaluateWeapon(inventory.getStack(bestDirectSlot.getAsInt()))
                : Double.NEGATIVE_INFINITY;

        ItemStack bestBundled = ItemStack.EMPTY;
        double bestBundledScore = Double.NEGATIVE_INFINITY;
        for (var ref : InventoryIterator.stream(bot).toList()) {
            if (ref.isDirect()) {
                continue;
            }
            ItemStack stack = ref.stack();
            if (stack.isEmpty() || DurabilityPolicyService.shouldAvoid(bot, stack)) {
                continue;
            }
            double score = evaluateWeapon(stack);
            if (score > bestBundledScore) {
                bestBundledScore = score;
                bestBundled = stack;
            }
        }
        if (bestBundled.isEmpty() || bestBundledScore == Double.NEGATIVE_INFINITY) {
            return false;
        }
        int directScore = bestDirectScore == Double.NEGATIVE_INFINITY ? -1 : (int) Math.round(bestDirectScore);
        int bundledScore = (int) Math.round(bestBundledScore);
        if (!BundleReachPolicy.shouldReachForBetter(directScore, bundledScore, bundleReachRateLimited(bot))) {
            return false;
        }
        final ItemStack wanted = bestBundled;
        return net.wcfcarolina13.GameAI.services.BundleService.reachFirst(
                bot, stack -> ItemStack.areItemsAndComponentsEqual(stack, wanted));
    }

    private static double evaluateWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);

        if (key.contains("sword")) {
            return 120 + materialWeight(key);
        }
        if (key.contains("axe")) {
            return 105 + materialWeight(key);
        }
        if (key.contains("trident")) {
            return 95;
        }
        if (key.contains("spear")) {
            return 93 + materialWeight(key);
        }
        if (key.contains("mace")) {
            return 92;
        }
        if (key.contains("bow") || key.contains("crossbow")) {
            return 80 + materialWeight(key);
        }
        return Double.NEGATIVE_INFINITY;
    }

    private static OptionalInt findItemSlot(PlayerInventory inventory, java.util.function.Predicate<ItemStack> predicate) {
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
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

    private static void boostAttackSpeed(ServerPlayerEntity bot) {
        EntityAttributeInstance attackSpeed = bot.getAttributeInstance(EntityAttributes.ATTACK_SPEED);
        if (attackSpeed != null && attackSpeed.getBaseValue() < MIN_COMBAT_ATTACK_SPEED) {
            attackSpeed.setBaseValue(MIN_COMBAT_ATTACK_SPEED);
        }
    }

    private static boolean isShieldStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT).contains("shield");
    }

    private static boolean isFoodStack(ItemStack stack) {
        return getFoodComponent(stack) != null;
    }

    private static double materialWeight(String key) {
        if (key.contains("netherite")) {
            return 5.0;
        }
        if (key.contains("diamond")) {
            return 4.0;
        }
        if (key.contains("iron")) {
            return 3.0;
        }
        if (key.contains("gold")) {
            return 2.5;
        }
        if (key.contains("stone")) {
            return 1.5;
        }
        if (key.contains("wood") || key.contains("wooden")) {
            return 0.8;
        }
        return 1.0;
    }

    private static OptionalInt findCheapestFoodSlot(PlayerInventory inventory) {
        int bestSlot = -1;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            FoodComponent food = getFoodComponent(stack);
            if (food != null) {
                double score = food.nutrition() + food.saturation() * 0.25D;
                if (score < bestScore || (score == bestScore && (bestSlot < 0 || stack.getCount() < inventory.getStack(bestSlot).getCount()))) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
        }

        return bestSlot >= 0 ? OptionalInt.of(bestSlot) : OptionalInt.empty();
    }

    private static boolean isSafeToEat(ServerPlayerEntity bot, List<Entity> hostiles) {
        if (hostiles == null || hostiles.isEmpty()) {
            return true;
        }
        for (Entity hostile : hostiles) {
            if (hostile.squaredDistanceTo(bot) <= HOSTILE_ALERT_DISTANCE_SQ && bot.canSee(hostile)) {
                return false;
            }
        }
        return true;
    }

    private static FoodComponent getFoodComponent(ItemStack stack) {
        return stack != null && !stack.isEmpty() ? stack.getComponents().get(DataComponentTypes.FOOD) : null;
    }
}
