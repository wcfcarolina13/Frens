package net.wcfcarolina13.GameAI.services;

import java.util.function.BooleanSupplier;

/**
 * When a hungry bot's only food is raw meat, whether to hold it for the furnace instead of eating
 * it raw. Cooked meat gives roughly twice the food, and auto-cook only starts at food 10, so a bot
 * that eats raw beef at 14 never lets auto-cook run.
 *
 * <p>Holds only while food is above 6 (sprinting still works there, and natural regeneration
 * needs 18 either way), health is at least half, and a cook could actually start soon (the
 * caller's check: auto-cook may run, raw food and fuel on hand, a furnace or smoker nearby).
 * Below that the bot eats raw meat as before.
 */
public final class RawMeatHoldPolicy {
    /** Food level the hold needs: 7 and up. At 6 the bot can no longer sprint, so it eats. */
    public static final int MIN_FOOD_TO_HOLD = 7;
    /** A bot below half health eats what it has rather than wait for the furnace. */
    public static final float MIN_HEALTH_FRACTION_TO_HOLD = 0.5F;

    private RawMeatHoldPolicy() {
    }

    /**
     * @param foodLevel      the bot's hunger bar, 0-20
     * @param healthFraction health over max health
     * @param cookSoon       evaluated last, and only when the cheap gates pass
     */
    public static boolean shouldHold(int foodLevel, float healthFraction, BooleanSupplier cookSoon) {
        if (foodLevel < MIN_FOOD_TO_HOLD) {
            return false;
        }
        if (!(healthFraction >= MIN_HEALTH_FRACTION_TO_HOLD)) {
            return false;
        }
        return cookSoon != null && cookSoon.getAsBoolean();
    }
}
