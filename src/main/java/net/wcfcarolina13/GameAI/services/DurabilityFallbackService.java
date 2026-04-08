package net.wcfcarolina13.GameAI.services;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import net.wcfcarolina13.GameAI.services.BotChestRegistryService.ItemSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the fallback chain when a selection site is blocked by
 * {@link DurabilityPolicyService}. Runs on a dedicated single-thread executor.
 *
 * <p>Fallback chain (in order):
 * <ol>
 *   <li>Inventory re-scan — swap a compliant stack from the bot's own inventory</li>
 *   <li>Chest retrieval — walk to a registered chest and withdraw a matching tool</li>
 *   <li>Crafting fallback — craft a replacement (tool categories only)</li>
 *   <li>Stand down — log and show overhead dialogue</li>
 * </ol>
 *
 * <p>Per-bot, per-category cooldown of 20s prevents thrash loops. Cleared
 * on bot death, bot removal, server stop, and on toggle-on-flip.
 */
public final class DurabilityFallbackService {

    private static final Logger LOGGER = LoggerFactory.getLogger("durability-fallback");

    private DurabilityFallbackService() {}

    // ------------------------------------------------------------------
    // Dedicated executor
    // ------------------------------------------------------------------

    private static final ExecutorService fallbackExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "frens-durability-fallback");
                t.setDaemon(true);
                return t;
            });

    public static void shutdownExecutors() {
        try {
            fallbackExecutor.shutdown();
            if (!fallbackExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                fallbackExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            fallbackExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        clearAllCooldowns();
    }

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
     * Dispatches the full fallback chain to the dedicated executor.
     */
    public static void requestRefresh(ServerPlayerEntity bot,
                                      GearCategory category,
                                      ServerCommandSource source) {
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
                return;
            }
            perCat.put(category, now);
        }

        fallbackExecutor.submit(() -> runFallbackChain(bot, category, source));
    }

    /**
     * Convenience overload for callers that don't have a {@link ServerCommandSource} handy.
     * Forwards to the 3-param version with null; derivation happens on the executor thread.
     */
    public static void requestRefresh(ServerPlayerEntity bot, GearCategory category) {
        requestRefresh(bot, category, null);
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
     * <p>Resolves ownership by scanning the CONFIG ownership map (alias → BotOwnership).
     * Since alias→UUID mapping is not cheaply available, clears the entire LAST_ATTEMPT
     * map when any alias matches the owner — acceptable because cooldowns are short (20s)
     * and this fires at most once per toggle.
     */
    public static void clearCooldownsForOwner(UUID ownerUuid) {
        if (ownerUuid == null) return;
        net.wcfcarolina13.FilingSystem.ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
        if (cfg == null) return;
        String ownerStr = ownerUuid.toString();
        boolean anyOwned = cfg.getBotOwnership().values().stream()
                .anyMatch(o -> o != null && ownerStr.equals(o.ownerUuid()));
        if (anyOwned) {
            LAST_ATTEMPT.clear();
        }
    }

    // ------------------------------------------------------------------
    // Fallback chain orchestration
    // ------------------------------------------------------------------

    private static void runFallbackChain(ServerPlayerEntity bot,
                                         GearCategory category,
                                         ServerCommandSource source) {
        if (bot == null || bot.isRemoved()) return;

        // Derive source on the executor thread if the caller didn't provide one.
        // This is the single source of truth for source derivation — both the
        // 2-param and 3-param entry points funnel through this point.
        ServerCommandSource effectiveSource = source;
        if (effectiveSource == null) {
            try {
                effectiveSource = bot.getCommandSource().withSilent();
            } catch (Throwable t) {
                LOGGER.debug("runFallbackChain: could not derive ServerCommandSource for {}: {}",
                        bot.getName().getString(), t.getMessage());
            }
        }

        // Step 1: inventory re-scan
        if (tryInventoryRescan(bot, category)) {
            LOGGER.debug("Fallback: swapped from inventory for {} category={}",
                    bot.getName().getString(), category);
            return;
        }

        // Step 2: chest retrieval
        if (tryChestRetrieval(bot, category, effectiveSource)) {
            LOGGER.debug("Fallback: retrieved from chest for {} category={}",
                    bot.getName().getString(), category);
            return;
        }

        // Step 3: crafting fallback (tool categories only)
        if (tryCraftingFallback(bot, category, effectiveSource)) {
            LOGGER.debug("Fallback: crafted replacement for {} category={}",
                    bot.getName().getString(), category);
            return;
        }

        // Step 4: stand down
        LOGGER.info("Fallback: no replacement found for {} category={} — standing down",
                bot.getName().getString(), category);
        CompanionOverheadDialogueService.tryShowGearNoReplacement(bot);
    }

    private static boolean tryChestRetrieval(ServerPlayerEntity bot,
                                              GearCategory category,
                                              ServerCommandSource source) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (source == null) {
            return false;
        }

        Predicate<ItemSnapshot> snapshotFilter = snap -> {
            // Loose pre-filter on snapshot item ID: accept any snap whose id contains
            // a category-relevant keyword. The real gate is the stackPredicate below.
            if (snap == null || snap.itemId == null) return false;
            String id = snap.itemId.toLowerCase(java.util.Locale.ROOT);
            return switch (category) {
                case PICKAXE     -> id.contains("pickaxe");
                case AXE         -> id.contains("_axe") && !id.contains("pickaxe");
                case SHOVEL      -> id.contains("shovel");
                case HOE         -> id.contains("_hoe");
                case SWORD       -> id.contains("sword");
                case MACE        -> id.contains("mace");
                case SHIELD      -> id.contains("shield");
                case BOW         -> id.endsWith("_bow") || id.equals("minecraft:bow");
                case CROSSBOW    -> id.contains("crossbow");
                case TRIDENT     -> id.contains("trident");
                case FISHING_ROD -> id.contains("fishing_rod");
                case HELMET      -> id.contains("helmet");
                case CHESTPLATE  -> id.contains("chestplate");
                case LEGGINGS    -> id.contains("leggings");
                case BOOTS       -> id.contains("boots");
                case ELYTRA      -> id.contains("elytra");
                case SHEARS      -> id.contains("shears");
            };
        };

        Predicate<ItemStack> stackPredicate = stack -> {
            if (!matchesCategory(stack, category)) return false;
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) return false;
            return DurabilityPolicyService.durabilityRatio(stack) >= 0.25;
        };

        // Neutral comparator: no tier preference, let distance sort handle it
        Comparator<ItemSnapshot> comparator = (a, b) -> 0;

        try {
            return ToolProvisionService.retrieveToolFromChests(
                    bot, world, source, snapshotFilter, stackPredicate, comparator, 48);
        } catch (Throwable t) {
            LOGGER.debug("tryChestRetrieval failed: {}", t.getMessage());
            return false;
        }
    }

    private static boolean tryCraftingFallback(ServerPlayerEntity bot,
                                                GearCategory category,
                                                ServerCommandSource source) {
        if (source == null) return false;
        try {
            return switch (category) {
                case PICKAXE -> ToolProvisionService.ensurePickaxe(bot, source, null, true);
                case AXE     -> ToolProvisionService.ensureAxe(bot, source, null, true);
                case SHOVEL  -> ToolProvisionService.ensureShovel(bot, source, null, true);
                case SWORD   -> ToolProvisionService.ensureSword(bot, source, null, true);
                default      -> false;
            };
        } catch (Throwable t) {
            LOGGER.debug("tryCraftingFallback failed for {}: {}", category, t.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Fallback chain step 1: inventory re-scan
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
