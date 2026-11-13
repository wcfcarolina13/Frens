package net.wcfcarolina13.GameAI.services;

/**
 * Pure decisions behind {@link BotAutoCookingService#canCookRawMeatSoon} while auto-cook's own
 * leaf-litter fuel run is in flight, and the reason logged when the cook does not start after
 * that run. No Minecraft types, so JUnit can test it.
 *
 * <p>The fuel run takes the bot's task slot (and may move it out of IDLE), so auto-cook's
 * eligibility check reports "task running" or "mode ..." for exactly as long as the cook is
 * fetching its fuel. Raw-meat holding must not switch off for that, or the bot eats the meat it
 * is gathering fuel to cook.
 */
public final class CookSoonEligibilityPolicy {

    /** Reason {@code BotAutoCookingService.ineligibleReason} gives while a task holds the slot. */
    public static final String TASK_RUNNING = "task running";
    /** Prefix of the reason it gives when the bot is not in IDLE mode. */
    public static final String MODE_PREFIX = "mode ";

    private CookSoonEligibilityPolicy() {
    }

    /**
     * Whether the cook-soon check may go on past auto-cook's eligibility gate.
     *
     * @param ineligibleReason null when auto-cook may run for the bot now
     * @param fuelRunInFlight  the bot's own leaf-litter fuel run is in flight
     */
    public static boolean allows(String ineligibleReason, boolean fuelRunInFlight) {
        if (ineligibleReason == null) {
            return true;
        }
        if (!fuelRunInFlight) {
            return false;
        }
        // Only the busy signals the fuel run itself causes are forgiven. Gone, sleeping, another
        // dimension, fleeing or sheltering still end the hold.
        return ineligibleReason.equals(TASK_RUNNING) || ineligibleReason.startsWith(MODE_PREFIX);
    }

    /**
     * Why the cook did not start after the leaf-litter run, for the log. Checked in the order the
     * continuation checks them.
     */
    public static String noCookAfterRunReason(boolean mayContinue,
                                              boolean hasFuel,
                                              String ineligibleReason,
                                              int foodLevel,
                                              int cookFoodLevel,
                                              boolean hasCookableFood) {
        if (!mayContinue) {
            return "the run was stopped";
        }
        if (!hasFuel) {
            return "still no fuel";
        }
        if (ineligibleReason != null) {
            return "not eligible (" + ineligibleReason + ")";
        }
        if (foodLevel > cookFoodLevel) {
            return "food " + foodLevel + " is above the cook level " + cookFoodLevel;
        }
        if (!hasCookableFood) {
            return "no cookable food left";
        }
        return "the furnace cook did not start";
    }
}
