package net.wcfcarolina13.PlayerUtils;

/**
 * Pure decision helper for "extract-first" consumption paths.
 *
 * <p>Read-side scanners are bundle-aware ({@link InventoryIterator#count}), but every mutation path
 * (decrement, hotbar swap, furnace insert) only understands direct inventory slots. Before consuming
 * {@code needed} items, a caller must first pull enough copies out of bundles so the direct count
 * covers the requirement — otherwise a bundle-aware count over-promises and the consume silently
 * short-changes the recipe.
 *
 * <p>Deliberately free of {@code net.minecraft.*} types so it is unit-testable under the project's
 * test policy.
 */
public final class BundleReachPolicy {

    private BundleReachPolicy() {}

    /**
     * How many {@code BundleService.extractFirst} calls to attempt before consuming.
     *
     * @param needed  items the caller is about to consume (non-positive means nothing to do)
     * @param direct  items already sitting in direct inventory slots
     * @param bundled items visible inside bundles (i.e. {@code count - countDirect})
     * @return {@code 0} when the direct supply already covers {@code needed}; otherwise the shortfall
     *         capped by what the bundles actually hold. Never negative.
     */
    public static int extractionsNeeded(int needed, int direct, int bundled) {
        if (needed <= 0) {
            return 0;
        }
        int safeDirect = Math.max(0, direct);
        int safeBundled = Math.max(0, bundled);
        if (safeDirect >= needed) {
            return 0;
        }
        return Math.min(needed - safeDirect, safeBundled);
    }

    /**
     * Whether a "reach into a bundle" extraction is worth doing for a <em>quality</em> upgrade
     * (best tool / best armor piece / best weapon), as opposed to the quantity rule above.
     *
     * <p>Selection code compares candidates by score. A bundled candidate is only worth extracting
     * when it is strictly better than the best candidate already sitting in a direct slot —
     * extracting an equal-or-worse item just churns the inventory. Callers additionally pass a
     * rate-limit flag so per-tick paths (combat loadout) cannot hammer the extraction path.
     *
     * @param bestDirectScore  score of the best direct-slot candidate, or {@code -1} when there is none
     * @param bestBundledScore score of the best bundled candidate, or {@code -1} when there is none
     * @param rateLimited      true when the caller's cooldown has not yet elapsed
     * @return true iff the bundled candidate is strictly better and the caller is not rate-limited
     */
    public static boolean shouldReachForBetter(int bestDirectScore, int bestBundledScore, boolean rateLimited) {
        if (rateLimited) {
            return false;
        }
        return bestBundledScore > bestDirectScore;
    }

    /** Bare-hands mining speed: a bundled item that does not beat it is never worth extracting. */
    public static final float HAND_SPEED = 1.0f;

    /**
     * Tool-selection upgrade rule (finding 6): pull a hand-bundled tool out of its bundle when it
     * mines the block strictly faster than the best direct-slot tool, even when that direct tool
     * already works. Same rule as armor and weapons since 1.1.205.
     *
     * <p>{@code bestBundledSpeed} must already exclude stacks the durability policy refuses
     * ({@code DurabilityPolicyService.shouldAvoid}), exactly as the direct-slot scan does, so a
     * worn preserved pickaxe in a bundle is never the reason for a reach.
     *
     * @param bestDirectSpeed  fastest direct-slot (hotbar + main inventory) speed, {@code 0} when none
     * @param bestBundledSpeed fastest usable bundled speed, {@code 0} when none
     * @param rateLimited      true while this bot's tool-reach cooldown has not elapsed
     * @return true iff not rate-limited and the bundled tool beats both bare hands and the best
     *         direct tool
     */
    public static boolean shouldUpgradeFromBundle(float bestDirectSpeed, float bestBundledSpeed, boolean rateLimited) {
        if (rateLimited || Float.isNaN(bestDirectSpeed) || Float.isNaN(bestBundledSpeed)) {
            return false;
        }
        return bestBundledSpeed > HAND_SPEED && bestBundledSpeed > bestDirectSpeed;
    }

    /**
     * Per-bot cooldown check shared by the tick-driven bundle reaches.
     *
     * @param now           current world time in ticks
     * @param lastReachTick world time of this bot's last reach attempt, or {@code null} when never
     * @param cooldownTicks minimum ticks between attempts
     * @return true while the cooldown is still running; a clock that went backwards (world change)
     *         never blocks
     */
    public static boolean isCoolingDown(long now, Long lastReachTick, long cooldownTicks) {
        if (lastReachTick == null) {
            return false;
        }
        return now >= lastReachTick && now - lastReachTick < cooldownTicks;
    }
}
