package net.wcfcarolina13.GameAI.services;

/**
 * Keeps a following bot in its bed. Sleep (co-sleep and zzz) starts its own "skill:sleep"
 * ticket and never suspends follow, so the follow tick kept moving the body out of the bed
 * without a vanilla wake-up, and wolf-teleported a bot that was walking to a bed back to its
 * owner. The follow tick is skipped while the bot sleeps or walks to a bed for the sleep task;
 * the mode stays FOLLOW, so follow resumes by itself once vanilla wakes the bot at daybreak.
 * An explicit owner call (follow, stop, the follow toggle) wakes the bot first.
 */
public final class SleepFollowPolicy {

    /** Ticket name TaskService gives the sleep task ({@code beginSkill("sleep")}). */
    public static final String SLEEP_TICKET = "skill:sleep";

    private SleepFollowPolicy() {
    }

    /** True when the follow tick must leave the bot where it is this tick. */
    public static boolean suppressFollowTick(boolean sleeping, String activeTaskName) {
        return suppressFollowTick(sleeping, activeTaskName, false);
    }

    /**
     * Same, but a bot that is still awake on its way to a bed lets follow defend it when
     * hostiles are present (vanilla refuses the bed with monsters near anyway). A bot that is
     * asleep is always left alone: damage wakes it, and follow takes over on the next tick.
     */
    public static boolean suppressFollowTick(boolean sleeping, String activeTaskName, boolean hostilesPresent) {
        if (sleeping) {
            return true;
        }
        return !hostilesPresent && isSleepTask(activeTaskName);
    }

    /** True for the sleep task's ticket name ("skill:sleep", or a bare "sleep"), any case. */
    public static boolean isSleepTask(String activeTaskName) {
        if (activeTaskName == null) {
            return false;
        }
        String name = activeTaskName.trim();
        return SLEEP_TICKET.equalsIgnoreCase(name) || "sleep".equalsIgnoreCase(name);
    }

    /**
     * The follow toggle on a bot that already follows this player normally turns follow off.
     * When that bot is asleep or on its way to a bed, the press is a call: wake it (and drop
     * the sleep task) and keep following instead.
     */
    public static boolean toggleCallsBack(boolean alreadyFollowing, boolean sleeping, String activeTaskName) {
        return alreadyFollowing && (sleeping || isSleepTask(activeTaskName));
    }
}
