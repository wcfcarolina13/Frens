package net.wcfcarolina13.GameAI.services;

/**
 * The chat reply for {@code /bot leash release}. No Minecraft types, so the wording is tested
 * (player-facing text: no em dashes, no semicolons). Says "leads", not "animals": a leashed boat
 * counts too.
 */
public final class LeashReleasePolicy {

    private LeashReleasePolicy() {
    }

    /**
     * @param botName           the bot's display name
     * @param released          how many leads the bot let go of
     * @param dropped           how many of the returned leads did not fit in its inventory and were
     *                          dropped at its feet (clamped to {@code 0..released})
     * @param forgotOtherTarget the bot also forgot a tracked animal it was not holding right now
     */
    public static String reply(String botName, int released, int dropped, boolean forgotOtherTarget) {
        String name = botName == null || botName.isBlank() ? "The bot" : botName;
        String forgot = " It will stop going back for the animal it was leading.";
        if (released <= 0) {
            return forgotOtherTarget
                    ? name + " wasn't holding a lead, but it will stop going back for the animal it was leading."
                    : name + " isn't holding any leads.";
        }
        int onGround = Math.max(0, Math.min(dropped, released));
        int kept = released - onGround;
        String head = name + " let go of " + released + (released == 1 ? " lead." : " leads.");
        String body;
        if (onGround == 0) {
            body = released == 1 ? " It is in its inventory." : " They are in its inventory.";
        } else if (kept == 0) {
            body = " Its inventory is full, so " + (released == 1 ? "it is" : "they are") + " on the ground at its feet.";
        } else {
            body = " " + kept + " in its inventory and " + onGround + " on the ground at its feet.";
        }
        return head + body + (forgotOtherTarget ? forgot : "");
    }
}
