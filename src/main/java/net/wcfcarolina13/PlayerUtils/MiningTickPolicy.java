package net.wcfcarolina13.PlayerUtils;

/**
 * Pure decision for one {@code MiningTool} tick: is the job done, refused, or still running?
 *
 * <p>A block that is already gone (air) is a finished job, whatever the claim check would say.
 * The claim check reads the live block to apply the Spawn-base tree exemption, and air is not a
 * tree, so checking the claim first turned every successful log break inside a claim into
 * "protected claim" (field bug E, 2026-10-17).</p>
 *
 * <p>Free of {@code net.minecraft.*} types so it is unit-testable.</p>
 */
public final class MiningTickPolicy {

    public enum Verdict { COMPLETE, REFUSE_CLAIM, CONTINUE }

    private MiningTickPolicy() {}

    /** Gone wins over the claim; otherwise a refused claim stops the job. */
    public static Verdict verdict(boolean blockGone, boolean claimAllowed) {
        if (blockGone) {
            return Verdict.COMPLETE;
        }
        return claimAllowed ? Verdict.CONTINUE : Verdict.REFUSE_CLAIM;
    }
}
