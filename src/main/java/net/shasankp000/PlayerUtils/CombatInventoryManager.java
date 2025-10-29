package net.shasankp000.PlayerUtils;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * Helper utilities that prepare the bot's inventory for combat situations.
 *
 * <p>The routines here are intentionally conservative: they avoid reshuffling
 * stacks unless a clear upgrade is available, and they operate entirely on the
 * server thread by being invoked from AI loops that already run there.</p>
 */
public final class CombatInventoryManager {

    private static final double MIN_COMBAT_ATTACK_SPEED = 12.0D;
    private static final int HOTBAR_SIZE = 9;

    private CombatInventoryManager() {
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

    private static void autoEquipMissingArmor(ServerPlayerEntity bot) {
        boolean missingPiece = Arrays.stream(new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
                .anyMatch(slot -> bot.getEquippedStack(slot).isEmpty());

        if (missingPiece) {
            armorUtils.autoEquipArmor(bot);
        }
    }

    private static void ensureOffhandShield(ServerPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        ItemStack offhand = bot.getOffHandStack();

        if (isShieldStack(offhand)) {
            return; // Already holding a shield
        }

        OptionalInt shieldSlot = findItemSlot(inventory, CombatInventoryManager::isShieldStack);
        if (shieldSlot.isEmpty()) {
            return;
        }

        ItemStack shieldStack = inventory.getStack(shieldSlot.getAsInt());
        inventory.setStack(shieldSlot.getAsInt(), offhand);
        bot.setStackInHand(Hand.OFF_HAND, shieldStack);
        inventory.markDirty();
    }

    private static void ensureBestWeaponAccessible(ServerPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        OptionalInt bestWeaponSlot = findBestWeaponSlot(inventory);
        if (bestWeaponSlot.isEmpty()) {
            return;
        }

        int slot = bestWeaponSlot.getAsInt();
        int hotbarTarget = 0;

        if (slot >= HOTBAR_SIZE) {
            swapStacks(inventory, slot, hotbarTarget);
        }

        inventory.setSelectedSlot(hotbarTarget);
        inventory.markDirty();
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

    private static OptionalInt findBestWeaponSlot(PlayerInventory inventory) {
        int bestIndex = -1;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            double score = evaluateWeapon(stack);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }

        return bestIndex >= 0 ? OptionalInt.of(bestIndex) : OptionalInt.empty();
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
        return !stack.isEmpty() && stack.getComponents().contains(DataComponentTypes.FOOD);
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
}
