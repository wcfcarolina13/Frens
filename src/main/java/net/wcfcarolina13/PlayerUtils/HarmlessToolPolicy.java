package net.wcfcarolina13.PlayerUtils;

import java.util.Locale;

/**
 * Pure decisions for "what may the bot hold while breaking a block it has no proper tool for".
 *
 * <p>Rule: use the effective tool for the block if one is carried; otherwise bare hands or an
 * item without durability. Never spend durability on a block the tool is not effective on
 * (a copper axe on dirt breaks at hand speed and only loses durability).</p>
 *
 * <p>Deliberately free of {@code net.minecraft.*} types so it is unit-testable; callers pass
 * {@code ItemStack.isEmpty()}, {@code isDamageable()}, {@code getMiningSpeedMultiplier(state)}
 * and {@code BotActions.isCombatClassItem(stack)}.</p>
 */
public final class HarmlessToolPolicy {

    /** Vanilla bare-hand (and any non-tool item) mining speed multiplier. */
    public static final float HAND_SPEED = 1.0f;

    private HarmlessToolPolicy() {}

    /** A tool is effective on a block when it mines it faster than a bare hand. */
    public static boolean isEffective(float speedOnBlock) {
        return speedOnBlock > HAND_SPEED;
    }

    /**
     * Whether an item is a safe stand-in for bare hands: an empty hand, or a non-weapon item
     * without durability that is no faster than a hand. Damageable tools (axe, shovel, hoe,
     * pickaxe, shears) are never harmless, because using them still costs durability.
     */
    public static boolean isHarmlessFallback(boolean empty, boolean weaponOnly, boolean damageable,
                                             float speedOnBlock) {
        if (empty) {
            return true;
        }
        return !weaponOnly && !damageable && speedOnBlock <= HAND_SPEED;
    }

    /**
     * When nothing carried beats hand speed on the block, may the currently held item stay in
     * hand? Yes for an empty hand, an item effective on the block, or a non-durable non-weapon
     * item. No for weapons and for damageable items that are not effective on the block.
     */
    public static boolean keepHeldWhenNoEffectiveTool(boolean heldEmpty, boolean heldDamageable,
                                                      boolean heldEffectiveOnBlock, boolean heldWeapon) {
        return keepHeldWhenNoEffectiveTool(heldEmpty, heldDamageable, heldEffectiveOnBlock, heldWeapon, false);
    }

    /**
     * As {@link #keepHeldWhenNoEffectiveTool(boolean, boolean, boolean, boolean)}, plus: an item
     * that would waste the block's drop (see {@link #shearsWasteLeaves}) is never kept, even
     * though it is effective on the block.
     */
    public static boolean keepHeldWhenNoEffectiveTool(boolean heldEmpty, boolean heldDamageable,
                                                      boolean heldEffectiveOnBlock, boolean heldWeapon,
                                                      boolean heldWastesDrop) {
        if (heldEmpty) {
            return true;
        }
        if (heldWeapon || heldWastesDrop) {
            return false;
        }
        if (heldEffectiveOnBlock) {
            return true;
        }
        return !heldDamageable;
    }

    /**
     * Shears on leaves drop the leaf block itself instead of saplings, sticks and apples, which
     * is what the bot wants from a tree. So shears are never used to mine leaves, however fast.
     */
    public static boolean shearsWasteLeaves(boolean targetIsLeaves, boolean itemIsShears) {
        return targetIsLeaves && itemIsShears;
    }

    /**
     * The held item is not worth keeping, but is there anything better to switch to? When no
     * harmless hotbar item exists and the whole inventory has no empty slot, a true empty hand
     * cannot be made, so the held item stays (switching would only trade one wear for another
     * and make the mining re-poll thrash). Never for a weapon: a sword loses 2 durability per
     * block, so the caller gets EMPTY and {@code MiningTool}'s fallback picks a non-weapon.
     */
    public static boolean keepHeldAsLastResort(boolean harmlessHotbarItemFound, boolean anyEmptyInventorySlot,
                                               boolean heldWeapon) {
        return !heldWeapon && !harmlessHotbarItemFound && !anyEmptyInventorySlot;
    }

    /**
     * Whether an item found in the main inventory is worth swapping into the hotbar as the
     * mining tool. Only a tool that actually beats hand speed is; otherwise the swap would just
     * put a non-effective (possibly damageable) item into the hand.
     */
    public static boolean shouldSwapInFromMainInventory(float bestSpeed) {
        return isEffective(bestSpeed);
    }

    /**
     * Keyword match against an item translation key that keeps "axe" from matching pickaxes
     * ({@code item.minecraft.stone_pickaxe} contains "axe"). Case-insensitive.
     */
    public static boolean matchesToolKeyword(String translationKey, String keyword) {
        if (translationKey == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        String key = translationKey.toLowerCase(Locale.ROOT);
        String kw = keyword.toLowerCase(Locale.ROOT);
        if (!key.contains(kw)) {
            return false;
        }
        if (kw.equals("axe") && key.contains("pickaxe")) {
            // Only a match if "axe" also appears outside the "pickaxe" token.
            return key.replace("pickaxe", "").contains("axe");
        }
        return true;
    }

    /**
     * Whether {@code BotActions.selectBestTool} should skip an item because its translation key
     * contains the avoid keyword. A null or blank avoid keyword avoids nothing: {@code "x".contains("")}
     * is always true, so a blank avoid used to skip every stack and turn the call into a no-op.
     */
    public static boolean isAvoided(String translationKey, String avoidKeyword) {
        if (translationKey == null || avoidKeyword == null || avoidKeyword.isBlank()) {
            return false;
        }
        return translationKey.toLowerCase(Locale.ROOT).contains(avoidKeyword.toLowerCase(Locale.ROOT));
    }
}
