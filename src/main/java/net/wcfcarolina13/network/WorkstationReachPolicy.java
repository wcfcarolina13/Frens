package net.wcfcarolina13.network;

/**
 * Why a remote Enchant or Anvil spell did not open. Before this, the server returned without a
 * word when the bot was in another world, more than 64 blocks away, or no workstation stood
 * within 4 blocks of the caster, so the button looked broken.
 */
public final class WorkstationReachPolicy {
    /** Same reach the screen handler rechecks every tick ({@code squaredDistanceTo(bot) <= 64*64}). */
    public static final double MAX_BOT_DISTANCE = 64.0;
    /** Radius the server searches around the caster for the table or anvil. */
    public static final int WORKSTATION_RADIUS = 4;

    private WorkstationReachPolicy() {
    }

    /**
     * @param workstation "an Enchanting Table" or "an anvil"
     * @return the message to show, or null when the screen may open
     */
    public static String refusal(String botName, boolean sameWorld, double distanceSq,
                                 boolean workstationNearby, String workstation) {
        String name = botName == null || botName.isBlank() ? "Your companion" : botName;
        if (!sameWorld) {
            return name + " is in another dimension.";
        }
        if (distanceSq > MAX_BOT_DISTANCE * MAX_BOT_DISTANCE) {
            return name + " is too far away (" + (int) Math.round(Math.sqrt(distanceSq))
                    + " blocks). It has to be within " + (int) MAX_BOT_DISTANCE + " blocks.";
        }
        if (!workstationNearby) {
            return "Stand within " + WORKSTATION_RADIUS + " blocks of " + workstation + ".";
        }
        return null;
    }
}
