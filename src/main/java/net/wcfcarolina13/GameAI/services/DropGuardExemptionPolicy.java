package net.wcfcarolina13.GameAI.services;

/**
 * When the follow drop guard lets a bot step into a 3+ deep drop in front of it. Normally a grounded
 * target at most 4.5 blocks below counts as a normal descent (a cave staircase, the commander just
 * below a ledge). For guard and patrol walking back to their point ({@code stayOutOfHoles}) that
 * exemption walked the bot into the open pit beside its point; there the drop is allowed only when
 * it lands on the target's own column. No Minecraft imports.
 */
public final class DropGuardExemptionPolicy {

    private DropGuardExemptionPolicy() {
    }

    public static boolean exempt(boolean stayOutOfHoles, double stepDown, boolean targetGrounded,
                                 int frontX, int frontZ, int targetX, int targetZ) {
        if (!(stepDown >= -0.5D && stepDown <= 4.5D && targetGrounded)) {
            return false;
        }
        if (!stayOutOfHoles) {
            return true;
        }
        return frontX == targetX && frontZ == targetZ;
    }
}
