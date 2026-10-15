package net.wcfcarolina13.GameAI.skills.support;

/**
 * Which failure gets the woodcut run's final chat line when nothing was felled.
 *
 * <p>1.1.240 field: Bob's targets failed on paths, then a protected target was refused at
 * selection, and that refusal overwrote the run's reason, so a pure path failure ended with
 * "protection checks blocked the trunk". A selection-time protection refusal now sets the reason
 * only while no other failure has set one. A refusal by the miner itself (the harvest's own
 * PROTECTED_AT_MINING) still overwrites, as before.
 *
 * <p>No Minecraft types: WoodcutSkill passes its private reason enum's {@code name()}.
 */
public final class WoodcutFinalReasonPolicy {

    /** The reason name a run starts with (no failure recorded yet). */
    public static final String NO_TARGET = "NO_TARGET";

    private WoodcutFinalReasonPolicy() {
    }

    /** Whether a selection-time protection refusal may set the run's final reason. */
    public static boolean selectionRefusalSetsReason(String currentReasonName) {
        return currentReasonName == null || NO_TARGET.equals(currentReasonName);
    }
}
