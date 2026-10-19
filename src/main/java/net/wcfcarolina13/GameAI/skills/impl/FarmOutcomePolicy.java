package net.wcfcarolina13.GameAI.skills.impl;

/**
 * Pure outcome rule for the farm skill's final report (finding 34).
 *
 * <p>Before this policy a farm run always reported success, so a run whose hoe had been pushed
 * out of the hotbar said "planted 0 seeds (tilled 0)" as if it had worked. {@link FarmSkill}
 * now asks {@link #failureReason} at the end of a run. The counts include the repair pass
 * (re-tilled and replanted plots), and {@code standingCrops} counts plots that already carry a
 * crop, so a run on a farm that was already fully planted is still a success.</p>
 *
 * <p>Deliberately free of {@code net.minecraft.*} types so it can be unit-tested.</p>
 */
public final class FarmOutcomePolicy {

    /** Player line when nothing was tilled or planted and the hoe could not be reached. */
    public static final String NO_HOE_LINE = "I don't have a hoe in reach, so nothing got planted.";
    /** Player line when nothing was tilled or planted although a hoe was in reach. */
    public static final String NOTHING_TILLED_LINE = "I couldn't till any ground, so nothing got planted.";

    private FarmOutcomePolicy() {
    }

    /**
     * @param tilled        plots tilled this run (main pass, second pass and repair re-tills)
     * @param planted       seeds planted this run (main pass, second pass and repair replants)
     * @param standingCrops farm plots that hold a crop at the end of the run
     * @param hoeAvailable  whether a hoe could still be brought into the hotbar
     * @return {@code null} when the run counts as a success, otherwise the player-facing reason
     */
    public static String failureReason(int tilled, int planted, int standingCrops, boolean hoeAvailable) {
        if (tilled > 0 || planted > 0 || standingCrops > 0) {
            return null;
        }
        return hoeAvailable ? NOTHING_TILLED_LINE : NO_HOE_LINE;
    }
}
