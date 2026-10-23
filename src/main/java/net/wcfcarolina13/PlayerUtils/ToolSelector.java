package net.wcfcarolina13.PlayerUtils;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ToolSelector {

    private static final Logger LOGGER = LoggerFactory.getLogger("tool-selector");

    public static ItemStack selectBestToolForBlock(ServerPlayerEntity bot, BlockState blockState) {
        return selectBestToolForBlock(bot, blockState, true, true);
    }

    /** Minimum world ticks between tool bundle-reach attempts, per bot (MiningTool re-polls every 20). */
    private static final long BUNDLE_REACH_COOLDOWN_TICKS = 100L;
    private static final java.util.Map<java.util.UUID, Long> LAST_BUNDLE_REACH_TICK =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Drops the bundle-reach cooldown stamp for a bot that is no longer tracked. */
    public static void forgetBot(java.util.UUID botUuid) {
        if (botUuid != null) {
            LAST_BUNDLE_REACH_TICK.remove(botUuid);
        }
    }

    /** Clears world-tick cooldowns when the server session ends. */
    public static void resetSession() {
        LAST_BUNDLE_REACH_TICK.clear();
    }

    /**
     * @param allowBundleReach when true and a tool sitting inside a bundle mines the block strictly
     *                         faster than every direct-slot tool (and beats bare hands), it is pulled
     *                         into a direct slot and the scan is retried exactly once, all in one
     *                         server-thread unit. This fires even when a direct tool already works
     *                         (finding 6: a loose stone pickaxe no longer hides a bundled diamond one),
     *                         matching the armor and weapon upgrades since 1.1.205. Bundled stacks the
     *                         durability policy refuses are skipped like loose ones, and attempts are
     *                         rate-limited to one per {@value #BUNDLE_REACH_COOLDOWN_TICKS} ticks per
     *                         bot so a failing reach cannot spin. Mining callers run on
     *                         {@code MiningTool}'s worker executor.
     * @param allowRevalidationRetry when true and the on-thread re-validation of the scanned slot
     *                         fails (another actor moved the stack between scan and swap), the scan
     *                         is re-run exactly once inside the same server-thread unit rather than
     *                         discarding an otherwise usable tool.
     */
    private static ItemStack selectBestToolForBlock(ServerPlayerEntity bot, BlockState blockState,
                                                    boolean allowBundleReach, boolean allowRevalidationRetry) {
        List<ItemStack> hotbarItems = hotBarUtils.getHotbarItems(bot);
        ItemStack bestTool = ItemStack.EMPTY;
        float highestSpeed = 0.0f;
        ItemStack priorHeld = bot.getMainHandStack();
        boolean priorWasFiltered = !priorHeld.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, priorHeld);

        if (blockState != null && blockState.isIn(BlockTags.LEAVES)) {
            // Never shears: they drop the leaf block instead of saplings and sticks
            // (HarmlessToolPolicy.shearsWasteLeaves). Keep a held hoe (leaves are #mineable/hoe in
            // 1.21.11), but never an axe or other damageable item that only loses durability on leaves.
            return heldOrHarmlessFallback(bot, blockState);
        }

        // ── SCAN (read-only; safe off the server thread) ──────────────────
        // First, search hotbar (slots 0-8)
        for (int i = 0; i < hotbarItems.size(); i++) {
            ItemStack item = hotbarItems.get(i);
            if (item.isEmpty() || isWeaponOnlyItem(item)) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, item)) continue;

            float speed = item.getMiningSpeedMultiplier(blockState);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                bestTool = item;
            }
        }

        // Then search main inventory (slots 9-35) for better tools
        int bestMainSlot = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack item = bot.getInventory().getStack(i);
            if (item.isEmpty() || isWeaponOnlyItem(item)) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, item)) continue;

            float speed = item.getMiningSpeedMultiplier(blockState);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                bestTool = item;
                bestMainSlot = i;
            }
        }

        // Bundled tools are invisible to the direct-slot scans above. Score them here (read-only) so
        // a strictly faster bundled tool is reached even when a direct one already works.
        if (allowBundleReach) {
            float bestBundledSpeed = bestBundledSpeed(bot, blockState);
            // Score check first: the rate-limit gate stamps the cooldown, and burning it on a
            // candidate the score check rejects would block a genuinely better tool for 100 ticks.
            if (BundleReachPolicy.shouldUpgradeFromBundle(highestSpeed, bestBundledSpeed, false)
                    && !bundleReachRateLimited(bot)) {
                final float directSpeed = highestSpeed;
                // The extraction and the rescan (which may itself swap into the hotbar) have to be
                // one atomic server-thread unit, or another actor can move the reached stack between them.
                ItemStack reached = callOnServer(bot, () -> {
                    if (reachBetterBundledTool(bot, blockState, directSpeed).isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                    return selectBestToolForBlock(bot, blockState, false, false);
                }, ItemStack.EMPTY);
                if (!reached.isEmpty()) {
                    return reached;
                }
                // Reach failed or the inventory changed: carry on with the direct-slot result.
            }
        }

        // ── MUTATION (one atomic server-thread unit) ─────────────────────
        // If best tool is in main inventory, swap it to an empty hotbar slot or current slot.
        // Only a tool that actually beats hand speed is worth pulling out of the main inventory;
        // with an empty/weapon-only hotbar any item scores 1.0 and would otherwise be swapped in.
        if (bestMainSlot != -1 && HarmlessToolPolicy.shouldSwapInFromMainInventory(highestSpeed)) {
            final int sourceSlot = bestMainSlot;
            final ItemStack expected = bestTool;
            final boolean announce = priorWasFiltered;
            ItemStack swapped = callOnServer(bot, () -> {
                ItemStack live = bot.getInventory().getStack(sourceSlot);
                if (!slotStillMatches(expected, live)) {
                    if (allowRevalidationRetry) {
                        LOGGER.debug("Tool select: slot {} no longer holds {} for {} — re-running scan once",
                                sourceSlot, expected.getItem(), bot.getName().getString());
                        // Already on the server thread: the rescan (and any swap it performs) stays
                        // inside this same atomic unit.
                        return selectBestToolForBlock(bot, blockState, false, false);
                    }
                    LOGGER.debug("Tool select: slot {} no longer holds {} — aborting swap for {}",
                            sourceSlot, expected.getItem(), bot.getName().getString());
                    return ItemStack.EMPTY;
                }
                int targetHotbarSlot = bot.getInventory().getSelectedSlot();
                for (int i = 0; i < 9; i++) {
                    if (bot.getInventory().getStack(i).isEmpty()) {
                        targetHotbarSlot = i;
                        break;
                    }
                }
                LOGGER.info("Swapping best tool from main inventory slot {} to hotbar slot {}",
                        sourceSlot, targetHotbarSlot);
                bot.currentScreenHandler.onSlotClick(sourceSlot, targetHotbarSlot, SlotActionType.SWAP, bot);
                if (announce) {
                    CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
                }
                return bot.getInventory().getStack(targetHotbarSlot);
            }, ItemStack.EMPTY);

            if (!swapped.isEmpty()) {
                return swapped;
            }
            // The chosen stack moved between scan and swap — fall through to the
            // "nothing better than hands" tail below rather than returning a stale stack.
            highestSpeed = 0.0f;
            bestTool = ItemStack.EMPTY;
        }

        // If no tool with speed > 1.0 was found, use current selection
        if (highestSpeed <= 1.0f) {
            // (A bundled tool that beats hands was already reached above, before the swap.)
            // If the reason nothing was found is that a preserved tool is below threshold,
            // request fallback refresh for the appropriate category.
            ItemStack held = hotBarUtils.getSelectedHotbarItemStack(bot);
            if (!held.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, held)) {
                DurabilityFallbackService.GearCategory cat = heldCategoryGuess(held);
                if (cat != null) {
                    DurabilityFallbackService.requestRefresh(bot, cat);
                }
            }

            return heldOrHarmlessFallback(bot, blockState);
        }

        return bestTool;
    }

    /**
     * Pure re-validation predicate: does the slot we picked during the off-thread scan still hold
     * the same item (and components) we scored? Empty {@code expected} never matches.
     */
    static boolean slotStillMatches(ItemStack expected, ItemStack actual) {
        if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()) {
            return false;
        }
        return ItemStack.areItemsAndComponentsEqual(expected, actual);
    }

    /**
     * Runs {@code task} on the server thread (fast path when already there) and blocks for the result.
     * Uses the shared cancellable server hop; a timed-out queued mutation cannot start later.
     */
    private static <T> T callOnServer(ServerPlayerEntity bot, java.util.function.Supplier<T> task, T fallback) {
        if (bot == null || task == null) {
            return fallback;
        }
        return BotActions.callOnServerThread(bot, () -> {
            try {
                T result = task.get();
                return result == null ? fallback : result;
            } catch (Throwable t) {
                LOGGER.debug("Tool select step failed: {}", t.toString());
                return fallback;
            }
        }, 2_000L, fallback);
    }

    /**
     * Read-only: the fastest bundled (non-direct) tool speed for {@code blockState}, skipping weapons
     * and stacks {@link DurabilityPolicyService#shouldAvoid} refuses, same filters as the direct scan.
     * Bundle contents are immutable components, so this is as safe off the server thread as the
     * direct-slot scan. {@code 0} when nothing usable is bundled.
     */
    private static float bestBundledSpeed(ServerPlayerEntity bot, BlockState blockState) {
        if (bot == null || blockState == null) {
            return 0.0f;
        }
        float best = 0.0f;
        for (var ref : InventoryIterator.stream(bot).toList()) {
            if (ref.isDirect()) {
                continue;
            }
            ItemStack stack = ref.stack();
            if (stack.isEmpty() || isWeaponOnlyItem(stack)) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            best = Math.max(best, stack.getMiningSpeedMultiplier(blockState));
        }
        return best;
    }

    /**
     * Per-bot cooldown gate for the tool bundle reach. Consumes the cooldown when it returns false
     * (a reach may proceed). Same shape as {@code CombatInventoryManager.bundleReachRateLimited}.
     */
    private static boolean bundleReachRateLimited(ServerPlayerEntity bot) {
        if (bot == null || bot.getEntityWorld() == null) {
            return true;
        }
        long now = bot.getEntityWorld().getTime();
        java.util.UUID id = bot.getUuid();
        if (BundleReachPolicy.isCoolingDown(now, LAST_BUNDLE_REACH_TICK.get(id), BUNDLE_REACH_COOLDOWN_TICKS)) {
            return true;
        }
        LAST_BUNDLE_REACH_TICK.put(id, now);
        return false;
    }

    /**
     * Looks for a bundled tool that mines {@code blockState} strictly faster than {@code bestDirectSpeed}
     * and, if found, pulls it into a direct inventory slot. Bundled stacks yielded by
     * {@link InventoryIterator} are read-only views — they are never selected or equipped directly.
     *
     * @return the (non-empty) bundled candidate that was extracted, or {@link ItemStack#EMPTY}
     */
    private static ItemStack reachBetterBundledTool(ServerPlayerEntity bot, BlockState blockState, float bestDirectSpeed) {
        if (bot == null || blockState == null) {
            return ItemStack.EMPTY;
        }
        ItemStack bestBundled = ItemStack.EMPTY;
        float bestBundledSpeed = 0.0f;
        for (var ref : InventoryIterator.stream(bot).toList()) {
            if (ref.isDirect()) {
                continue;
            }
            ItemStack stack = ref.stack();
            if (stack.isEmpty() || isWeaponOnlyItem(stack)) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            float speed = stack.getMiningSpeedMultiplier(blockState);
            if (speed > bestBundledSpeed) {
                bestBundledSpeed = speed;
                bestBundled = stack;
            }
        }
        if (bestBundled.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // On-thread re-check of the live bundle contents. Not rate-limited here: the caller already
        // passed (and stamped) bundleReachRateLimited for this attempt.
        if (!BundleReachPolicy.shouldUpgradeFromBundle(bestDirectSpeed, bestBundledSpeed, false)) {
            return ItemStack.EMPTY;
        }
        final ItemStack wanted = bestBundled;
        boolean reached = net.wcfcarolina13.GameAI.services.BundleService.reachFirst(
                bot, stack -> ItemStack.areItemsAndComponentsEqual(stack, wanted));
        if (!reached) {
            return ItemStack.EMPTY;
        }
        LOGGER.info("Tool select: reached {} out of a bundle (speed {} > {})",
                wanted.getItem(), bestBundledSpeed, bestDirectSpeed);
        return wanted;
    }

    private static boolean isWeaponOnlyItem(ItemStack stack) {
        return BotActions.isCombatClassItem(stack);
    }

    /**
     * Nothing carried beats hand speed on {@code blockState}: keep the held item only when that
     * costs nothing (empty hand, non-durable item) or the item is effective on the block; never a
     * weapon or a damageable tool that would wear for no gain. Otherwise pick a harmless hotbar
     * item, or {@link ItemStack#EMPTY} so {@code MiningTool} falls back to a true empty hand.
     * Read-only: the caller performs any slot change on the server thread.
     */
    private static ItemStack heldOrHarmlessFallback(ServerPlayerEntity bot, BlockState blockState) {
        ItemStack selected = hotBarUtils.getSelectedHotbarItemStack(bot);
        if (selected.isEmpty()) {
            return ItemStack.EMPTY;
        }
        boolean wastesDrop = HarmlessToolPolicy.shearsWasteLeaves(
                blockState != null && blockState.isIn(BlockTags.LEAVES), selected.isOf(Items.SHEARS));
        boolean keep = HarmlessToolPolicy.keepHeldWhenNoEffectiveTool(
                false,
                selected.isDamageable(),
                blockState != null && HarmlessToolPolicy.isEffective(selected.getMiningSpeedMultiplier(blockState)),
                isWeaponOnlyItem(selected),
                wastesDrop);
        if (keep) {
            return selected;
        }
        ItemStack harmless = findHarmlessHotbarFallback(bot, blockState);
        // Shears on leaves are refused like a weapon: EMPTY sends MiningTool to its fallback,
        // whose last tier skips shears on leaves, so the re-poll settles on another item.
        if (HarmlessToolPolicy.keepHeldAsLastResort(!harmless.isEmpty(), hasAnyEmptyMainSlot(bot),
                isWeaponOnlyItem(selected) || wastesDrop)) {
            // Packed inventory with nothing harmless: MiningTool could not free a hand either, and
            // returning EMPTY here would make its 20-tick re-poll swap and reset progress forever.
            // Never a weapon: EMPTY sends MiningTool to its fallback, whose last tier takes any
            // non-weapon item, which this branch then keeps on the next re-poll.
            return selected;
        }
        return harmless;
    }

    private static boolean hasAnyEmptyMainSlot(ServerPlayerEntity bot) {
        for (int i = 0; i < 36; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack findHarmlessHotbarFallback(ServerPlayerEntity bot, BlockState blockState) {
        if (bot == null) {
            return ItemStack.EMPTY;
        }
        BlockState reference = blockState != null ? blockState : Blocks.STONE.getDefaultState();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            if (HarmlessToolPolicy.isHarmlessFallback(false, isWeaponOnlyItem(stack), stack.isDamageable(),
                    stack.getMiningSpeedMultiplier(reference))) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static DurabilityFallbackService.GearCategory heldCategoryGuess(ItemStack stack) {
        String key = stack.getItem().getTranslationKey().toLowerCase(java.util.Locale.ROOT);
        if (key.endsWith("_pickaxe")) return DurabilityFallbackService.GearCategory.PICKAXE;
        if (key.endsWith("_shovel"))  return DurabilityFallbackService.GearCategory.SHOVEL;
        if (key.endsWith("_hoe"))     return DurabilityFallbackService.GearCategory.HOE;
        if (key.endsWith("_axe"))     return DurabilityFallbackService.GearCategory.AXE;
        return null;
    }
}
