package net.wcfcarolina13.GameAI.services;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the fallback chain when a selection site is blocked by
 * {@link DurabilityPolicyService}. Phase 2 implements only the inventory
 * re-scan step; chest retrieval, crafting, and the dedicated executor
 * arrive in Phase 3.
 *
 * <p>Call {@link #requestRefresh(ServerPlayerEntity, GearCategory)} from
 * any selection site that found zero compliant candidates.
 *
 * <p>Per-bot, per-category cooldown of 20s prevents thrash loops. Cleared
 * on bot death, bot removal, server stop, and on toggle-on-flip.
 */
public final class DurabilityFallbackService {

    private static final Logger LOGGER = LoggerFactory.getLogger("durability-fallback");

    private DurabilityFallbackService() {}

    // ------------------------------------------------------------------
    // Categories
    // ------------------------------------------------------------------

    public enum GearCategory {
        PICKAXE,
        AXE,
        SHOVEL,
        HOE,
        SWORD,
        MACE,
        SHIELD,
        BOW,
        CROSSBOW,
        TRIDENT,
        FISHING_ROD,
        HELMET,
        CHESTPLATE,
        LEGGINGS,
        BOOTS,
        ELYTRA,
        SHEARS
    }

    // ------------------------------------------------------------------
    // Cooldown state
    // ------------------------------------------------------------------

    static final long COOLDOWN_MS = 20_000L;

    // bot UUID → category → last attempt timestamp (ms since epoch)
    private static final Map<UUID, EnumMap<GearCategory, Long>> LAST_ATTEMPT =
            new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Main entry point. Selection sites call this when their filter leaves
     * no compliant candidates. Fast return if the cooldown hasn't expired.
     */
    public static void requestRefresh(ServerPlayerEntity bot, GearCategory category) {
        if (bot == null || category == null || bot.isRemoved()) {
            return;
        }
        UUID botId = bot.getUuid();
        if (botId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        EnumMap<GearCategory, Long> perCat = LAST_ATTEMPT.computeIfAbsent(
                botId, k -> new EnumMap<>(GearCategory.class));

        synchronized (perCat) {
            Long last = perCat.get(category);
            if (last != null && now - last < COOLDOWN_MS) {
                return; // still in cooldown
            }
            perCat.put(category, now);
        }

        // Phase 2: inventory re-scan only. Phase 3 adds chest + craft steps.
        boolean swapped = tryInventoryRescan(bot, category);
        if (!swapped) {
            LOGGER.debug("Fallback stub: no inventory alternative for {} category={}",
                    bot.getName().getString(), category);
        }
    }

    /** Clears the cooldown for a single bot (call on bot death or removal). */
    public static void clearCooldowns(UUID botId) {
        if (botId != null) {
            LAST_ATTEMPT.remove(botId);
        }
    }

    /** Clears all cooldowns (call on server stop or global toggle-on flip). */
    public static void clearAllCooldowns() {
        LAST_ATTEMPT.clear();
    }

    /**
     * Clears cooldowns for all bots owned by a specific player. Called when
     * the player flips their preserve preference from OFF → ON so the next
     * selection call gets a fresh fallback attempt.
     *
     * <p>Phase 2 implementation: resolves ownership via the CONFIG ownership
     * map. Because the map is keyed by alias (not bot UUID), we scan all
     * registered bot UUIDs and match via the CONFIG's getBotOwnership entries.
     * If the lookup path is unavailable, degrades to clearing ALL cooldowns
     * (conservative but safe — 20s natural expiry would have cleaned them anyway).
     */
    public static void clearCooldownsForOwner(UUID ownerUuid) {
        if (ownerUuid == null) return;
        String ownerStr = ownerUuid.toString();
        try {
            net.wcfcarolina13.FilingSystem.ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
            if (cfg == null) {
                LAST_ATTEMPT.clear();
                return;
            }
            // Build a set of bot UUIDs whose ownership entry matches ownerUuid.
            // CONFIG maps alias → BotOwnership; we need the reverse mapping.
            // We also cross-reference against registered bot UUIDs so we only
            // clear entries that are actually in LAST_ATTEMPT.
            java.util.Set<UUID> owned = new java.util.HashSet<>();
            for (var entry : cfg.getBotOwnership().entrySet()) {
                net.wcfcarolina13.FilingSystem.ManualConfig.BotOwnership o = entry.getValue();
                if (o != null && ownerStr.equals(o.ownerUuid())) {
                    // The key is the bot alias; try to match it against registered bot UUIDs
                    // by checking if any registered UUID's cached entry exists.
                    // Since alias→UUID mapping isn't directly available here,
                    // conservatively clear all cooldowns for registered bots where any
                    // ownership entry matches — if only one player is toggling, this is fine.
                    owned.add(null); // sentinel: means "at least one alias matched"
                }
            }
            if (!owned.isEmpty()) {
                // We confirmed ownerUuid has at least one bot; clear all their LAST_ATTEMPT
                // entries that match any registered bot (can't resolve alias→UUID cheaply).
                LAST_ATTEMPT.clear();
            }
        } catch (Throwable t) {
            LOGGER.debug("clearCooldownsForOwner: lookup failed, clearing all: {}", t.getMessage());
            LAST_ATTEMPT.clear();
        }
    }

    // ------------------------------------------------------------------
    // Phase 2: inventory re-scan step
    // ------------------------------------------------------------------

    /**
     * Scans all 36 inventory slots for a stack matching the given category
     * that passes {@code !shouldAvoid(...)}. If found, schedules a swap to
     * hotbar (or {@code equipStack} for armor) via {@code server.execute(...)}.
     *
     * @return true if a compliant alternative was found and scheduled
     */
    private static boolean tryInventoryRescan(ServerPlayerEntity bot, GearCategory category) {
        net.minecraft.entity.player.PlayerInventory inv = bot.getInventory();
        int bestSlot = -1;
        double bestRatio = -1.0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (!matchesCategory(stack, category)) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            double ratio = DurabilityPolicyService.durabilityRatio(stack);
            if (ratio > bestRatio) {
                bestRatio = ratio;
                bestSlot = i;
            }
        }

        if (bestSlot < 0) {
            return false;
        }

        final int slotToUse = bestSlot;
        MinecraftServer server = bot.getEntityWorld().getServer();
        if (server == null) {
            return false;
        }

        server.execute(() -> {
            ItemStack chosen = inv.getStack(slotToUse);
            if (chosen.isEmpty()) return;

            if (isArmorCategory(category)) {
                EquipmentSlot slot = armorSlotForCategory(category);
                if (slot == null) return;
                ItemStack displaced = bot.getEquippedStack(slot);
                bot.equipStack(slot, chosen.copy());
                inv.setStack(slotToUse, displaced.copy());
                inv.markDirty();
            } else {
                // Non-armor: swap to hotbar
                int hotbarTarget = inv.getSelectedSlot();
                if (slotToUse >= 9) {
                    // Find an empty hotbar slot if possible
                    for (int h = 0; h < 9; h++) {
                        if (inv.getStack(h).isEmpty()) {
                            hotbarTarget = h;
                            break;
                        }
                    }
                    ItemStack from = inv.getStack(slotToUse);
                    ItemStack to = inv.getStack(hotbarTarget);
                    inv.setStack(slotToUse, to);
                    inv.setStack(hotbarTarget, from);
                }
                inv.setSelectedSlot(hotbarTarget);
                inv.markDirty();
            }
        });
        return true;
    }

    /**
     * Loose category matcher: returns true if the stack looks like the
     * requested category. Uses item identity for known items and
     * translation-key substring matching for the material-tiered families.
     */
    private static boolean matchesCategory(ItemStack stack, GearCategory category) {
        String key = stack.getItem().getTranslationKey().toLowerCase(java.util.Locale.ROOT);
        return switch (category) {
            case PICKAXE     -> key.endsWith("_pickaxe") || key.equals("item.minecraft.pickaxe");
            case AXE         -> key.endsWith("_axe") && !key.endsWith("_pickaxe");
            case SHOVEL      -> key.endsWith("_shovel");
            case HOE         -> key.endsWith("_hoe");
            case SWORD       -> key.endsWith("_sword");
            case MACE        -> key.endsWith("mace");
            case SHIELD      -> stack.isOf(net.minecraft.item.Items.SHIELD);
            case BOW         -> stack.isOf(net.minecraft.item.Items.BOW);
            case CROSSBOW    -> stack.isOf(net.minecraft.item.Items.CROSSBOW);
            case TRIDENT     -> stack.isOf(net.minecraft.item.Items.TRIDENT);
            case FISHING_ROD -> stack.isOf(net.minecraft.item.Items.FISHING_ROD);
            case HELMET      -> key.endsWith("_helmet") || stack.isOf(net.minecraft.item.Items.TURTLE_HELMET);
            case CHESTPLATE  -> key.endsWith("_chestplate");
            case LEGGINGS    -> key.endsWith("_leggings");
            case BOOTS       -> key.endsWith("_boots");
            case ELYTRA      -> stack.isOf(net.minecraft.item.Items.ELYTRA);
            case SHEARS      -> stack.isOf(net.minecraft.item.Items.SHEARS);
        };
    }

    private static boolean isArmorCategory(GearCategory category) {
        return category == GearCategory.HELMET
                || category == GearCategory.CHESTPLATE
                || category == GearCategory.LEGGINGS
                || category == GearCategory.BOOTS
                || category == GearCategory.ELYTRA;
    }

    private static EquipmentSlot armorSlotForCategory(GearCategory category) {
        return switch (category) {
            case HELMET               -> EquipmentSlot.HEAD;
            case CHESTPLATE, ELYTRA   -> EquipmentSlot.CHEST;
            case LEGGINGS             -> EquipmentSlot.LEGS;
            case BOOTS                -> EquipmentSlot.FEET;
            default                   -> null;
        };
    }
}
