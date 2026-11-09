package net.wcfcarolina13.GameAI.services;

import java.util.function.BooleanSupplier;

/**
 * When a hungry bot's only food is raw meat, whether to hold it for the furnace instead of eating
 * it raw. Cooked meat gives roughly twice the food, and auto-cook only starts at food 10, so a bot
 * that eats raw beef at 14 never lets auto-cook run.
 *
 * <p>Holds only while food is above 6 (sprinting still works there), health is at least half,
 * and a cook could actually start soon (the caller's check: auto-cook may run, raw food and fuel
 * on hand, a furnace or smoker nearby). Below that the bot eats raw meat as before. Two limits:
 * <ul>
 *   <li>A hurt bot above food 10 eats: auto-cook can't start there, and an idle bot loses food so
 *       slowly that waiting would leave it without regeneration for a long time.</li>
 *   <li>Once food is at auto-cook's level, the hold lasts at most {@link #MAX_HOLD_AT_COOK_LEVEL_MS}.
 *       Auto-cook can keep launching a cook that then loads nothing (the furnace holds someone
 *       else's ore, the output is full, the furnace can't be reached), and nothing else lowers an
 *       idle bot's food, so without this cap the bot would hold forever.</li>
 * </ul>
 */
public final class RawMeatHoldPolicy {
    /** Food level the hold needs: 7 and up. At 6 the bot can no longer sprint, so it eats. */
    public static final int MIN_FOOD_TO_HOLD = 7;
    /** A bot below half health eats what it has rather than wait for the furnace. */
    public static final float MIN_HEALTH_FRACTION_TO_HOLD = 0.5F;
    /** Auto-cook's start level ({@code BotAutoCookingService.COOK_HUNGER_THRESHOLD}). */
    public static final int COOK_FOOD_LEVEL = 10;
    /** About a dozen auto-cook attempts (one every 14-17 s) before the bot gives up and eats. */
    public static final long MAX_HOLD_AT_COOK_LEVEL_MS = 3L * 60L * 1000L;

    private RawMeatHoldPolicy() {
    }

    /**
     * @param foodLevel      the bot's hunger bar, 0-20
     * @param healthFraction health over max health
     * @param heldAtCookLevelMs how long the current hold has lasted at food {@link #COOK_FOOD_LEVEL}
     *                       or below, 0 when it hasn't reached that level
     * @param cookSoon       evaluated last, and only when the cheap gates pass
     */
    public static boolean shouldHold(int foodLevel, float healthFraction, long heldAtCookLevelMs,
                                     BooleanSupplier cookSoon) {
        if (foodLevel < MIN_FOOD_TO_HOLD) {
            return false;
        }
        if (!(healthFraction >= MIN_HEALTH_FRACTION_TO_HOLD)) {
            return false;
        }
        if (foodLevel > COOK_FOOD_LEVEL && healthFraction < 1.0F) {
            return false;
        }
        if (foodLevel <= COOK_FOOD_LEVEL && heldAtCookLevelMs > MAX_HOLD_AT_COOK_LEVEL_MS) {
            return false;
        }
        return cookSoon != null && cookSoon.getAsBoolean();
    }
}
