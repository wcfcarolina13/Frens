package net.wcfcarolina13.GameAI.services;

/**
 * The chat reply for {@code /bot leash release}. No Minecraft types, so the wording is tested
 * (player-facing text: no em dashes, no semicolons).
 */
public final class LeashReleasePolicy {

    private LeashReleasePolicy() {
    }

    /**
     * @param botName  the bot's display name
     * @param released how many animals the bot let go of
     * @param dropped  how many of the returned leads did not fit in its inventory and were dropped
     *                 at its feet (clamped to {@code 0..released})
     */
    public static String reply(String botName, int released, int dropped) {
        String name = botName == null || botName.isBlank() ? "The bot" : botName;
        if (released <= 0) {
            return name + " isn't holding any animals on a lead.";
        }
        int onGround = Math.max(0, Math.min(dropped, released));
        int kept = released - onGround;
        String head = name + " let go of " + released + (released == 1 ? " animal." : " animals.");
        if (onGround == 0) {
            return head + (released == 1 ? " The lead is" : " The leads are") + " in its inventory.";
        }
        if (kept == 0) {
            return head + " Its inventory is full, so " + (released == 1 ? "the lead is" : "the leads are")
                    + " on the ground at its feet.";
        }
        return head + " " + kept + (kept == 1 ? " lead is" : " leads are") + " in its inventory and "
                + onGround + " on the ground at its feet.";
    }
}
