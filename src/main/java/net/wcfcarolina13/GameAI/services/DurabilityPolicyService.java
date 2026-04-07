package net.wcfcarolina13.GameAI.services;

import java.util.Set;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import net.wcfcarolina13.Frens;

/**
 * Durability preservation policy for bot tool/armor/weapon selection.
 *
 * <p>When an owner toggles "Preserve Expensive Gear" on, bots owned by that
 * player refuse to use items that are both <em>preserved</em> (expensive
 * material or enchanted) and below the durability threshold (11% normally,
 * 3% in combat).
 *
 * <p>This class is pure-static and stateless. Selection sites call
 * {@link #shouldAvoid(ServerPlayerEntity, ItemStack)} as a one-line filter.
 * See spec at
 * {@code docs/superpowers/specs/2026-04-07-durability-preservation-toggle-design.md}.
 */
public final class DurabilityPolicyService {

    private DurabilityPolicyService() {}

    // ------------------------------------------------------------------
    // Thresholds
    // ------------------------------------------------------------------

    /** Normal (out-of-combat) durability threshold: 11%. */
    public static final double NORMAL_THRESHOLD = 0.11;

    /** Combat durability threshold: 3%. */
    public static final double COMBAT_THRESHOLD = 0.03;

    // ------------------------------------------------------------------
    // Preserved item set (28 items: gold/diamond/netherite tools + armor
    // + turtle helmet)
    // ------------------------------------------------------------------

    private static final Set<Item> PRESERVED_ITEMS = Set.of(
            // Gold tier (9)
            Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL,
            Items.GOLDEN_HOE, Items.GOLDEN_SWORD,
            Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE,
            Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS,
            // Diamond tier (9)
            Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL,
            Items.DIAMOND_HOE, Items.DIAMOND_SWORD,
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
            Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
            // Netherite tier (9)
            Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL,
            Items.NETHERITE_HOE, Items.NETHERITE_SWORD,
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
            Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
            // Turtle shell (1)
            Items.TURTLE_HELMET);

    // ------------------------------------------------------------------
    // Predicates
    // ------------------------------------------------------------------

    public static boolean isPreservedMaterial(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return PRESERVED_ITEMS.contains(stack.getItem());
    }

    /**
     * Returns true if the stack has any enchantment. Uses the canonical
     * {@code stack.hasEnchantments()} form already used by
     * {@code ToolProvisionService} and {@code BotMutualAidService}.
     */
    public static boolean isEnchanted(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.hasEnchantments();
    }

    public static boolean isPreserved(ItemStack stack) {
        return isPreservedMaterial(stack) || isEnchanted(stack);
    }

    /** Returns ratio in [0.0, 1.0]. Non-damageable stacks return 1.0. */
    public static double durabilityRatio(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageable()) {
            return 1.0;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) return 1.0;
        int remaining = max - stack.getDamage();
        if (remaining < 0) remaining = 0;
        return (double) remaining / (double) max;
    }

    /**
     * Returns the current threshold for this bot: 3% if in combat,
     * 11% otherwise.
     */
    public static double currentThreshold(ServerPlayerEntity bot) {
        if (bot == null) return NORMAL_THRESHOLD;
        UUID botId = bot.getUuid();
        if (botId != null && BotCombatCalloutService.isInCombat(botId)) {
            return COMBAT_THRESHOLD;
        }
        return NORMAL_THRESHOLD;
    }

    /**
     * Returns true if the owner of this bot has the preservation toggle
     * enabled. Null owner (unowned bot) → policy disabled.
     */
    public static boolean isPolicyEnabled(ServerPlayerEntity bot) {
        if (bot == null) return false;
        if (Frens.CONFIG == null) return false;
        UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
        if (ownerUuid == null) return false;
        return Frens.CONFIG.getPreserveExpensiveGear(ownerUuid);
    }

    /**
     * Main predicate. Returns true iff the bot should refuse to use this
     * stack right now because the policy is enabled, the stack is
     * preserved, and its durability is below the current threshold.
     */
    public static boolean shouldAvoid(ServerPlayerEntity bot, ItemStack stack) {
        if (bot == null || stack == null || stack.isEmpty()) return false;
        if (!stack.isDamageable()) return false;
        if (!isPolicyEnabled(bot)) return false;
        if (!isPreserved(stack)) return false;
        return durabilityRatio(stack) < currentThreshold(bot);
    }
}
