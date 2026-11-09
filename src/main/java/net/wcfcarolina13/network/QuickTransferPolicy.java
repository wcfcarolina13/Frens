package net.wcfcarolina13.network;

/**
 * Pure rules for the Quick Store / Quick Fetch worker in {@code ChestRegistryNetworkManager}
 * (no Minecraft types).
 *
 * <p>Since the worker attaches its thread to the task ticket, {@code /bot stop} really interrupts
 * it. A stop during the combat wait used to be swallowed ({@code break} / {@code {}}) and the walk
 * started anyway; and a stop during the walk made the transfer return 0, which was reported as
 * "could not deposit any items. Chest may be full or bot inventory empty."
 */
public final class QuickTransferPolicy {

    private QuickTransferPolicy() {
    }

    /** Whether the worker should end before (re)starting the walk: the task was stopped or interrupted. */
    public static boolean stopBeforeWalk(boolean abortRequested, boolean interrupted) {
        return abortRequested || interrupted;
    }

    /**
     * The chat line reporting the transfer, or null for none. A stopped transfer that moved nothing
     * says nothing: {@code /bot stop} already answers, and "chest may be full" would be wrong.
     */
    public static String resultMessage(String botName, int moved, boolean deposit, boolean keepGear,
                                       boolean aborted) {
        if (moved > 0) {
            String verb = deposit ? "deposited" : "fetched";
            String suffix = (deposit && keepGear) ? " (kept its gear)." : ".";
            return "§a" + botName + " " + verb + " " + moved + " items" + suffix + "§r";
        }
        if (aborted) {
            return null;
        }
        String action = deposit ? "deposit" : "fetch";
        return "§e" + botName + " could not " + action + " any items. "
                + (deposit ? "Chest may be full or bot inventory empty." : "Chest may be empty or bot inventory full.")
                + "§r";
    }
}
