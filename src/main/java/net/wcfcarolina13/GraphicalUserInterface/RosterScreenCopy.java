package net.wcfcarolina13.GraphicalUserInterface;

/**
 * Header and empty-list text for {@link BotRestoreScreen}. The roster hides companions that are
 * already in the world, so an empty list means one of two different things: nothing has been
 * saved yet, or everything saved is already here. Pure so the choice can be tested.
 */
public final class RosterScreenCopy {

    public enum State {
        /** At least one saved companion can be restored. */
        HAS_RESTORABLE,
        /** No saved companions at all. */
        NONE_SAVED,
        /** Companions are saved, and every one of them is already in the world. */
        ALL_PRESENT,
        /** None saved in this world, but companions from other worlds can be brought in. */
        NONE_HERE
    }

    private RosterScreenCopy() {
    }

    public static State state(int restorableCount, boolean allSavedPresent) {
        return state(restorableCount, allSavedPresent, 0);
    }

    /**
     * @param otherWorldsCount companions saved in other worlds that the roster can bring in
     */
    public static State state(int restorableCount, boolean allSavedPresent, int otherWorldsCount) {
        if (restorableCount > 0) {
            return State.HAS_RESTORABLE;
        }
        if (allSavedPresent) {
            return State.ALL_PRESENT;
        }
        return otherWorldsCount > 0 ? State.NONE_HERE : State.NONE_SAVED;
    }

    public static String title(State state) {
        return switch (state) {
            case HAS_RESTORABLE -> "Choose Companions to Restore";
            case NONE_SAVED -> "Welcome. No saved companions yet.";
            case ALL_PRESENT -> "All your companions are already here";
            case NONE_HERE -> "No companions in this world yet.";
        };
    }

    public static String subtitle(State state) {
        return switch (state) {
            case HAS_RESTORABLE -> "Tick one or more saved bots, then click Spawn Selected.";
            case NONE_SAVED -> "Pick a name below to spawn your first bot.";
            case ALL_PRESENT -> "To add another, type a new name below and click Create.";
            case NONE_HERE -> "Create one below, or open Other worlds to bring one in.";
        };
    }

    /** Placeholder shown where the list would be. Empty for {@link State#HAS_RESTORABLE}. */
    public static String emptyListLine(State state) {
        return switch (state) {
            case HAS_RESTORABLE -> "";
            case NONE_SAVED -> "(No saved bots yet. Create one below.)";
            case ALL_PRESENT -> "(Everyone you saved is already in this world.)";
            case NONE_HERE -> "(No companions in this world yet.)";
        };
    }

    /** Title while the Other worlds list is open. */
    public static String otherWorldsTitle() {
        return "Companions from other worlds";
    }

    /** Subtitle while the Other worlds list is open: what bringing one in does. */
    public static String otherWorldsSubtitle() {
        return "Spawning one here starts it fresh in this world. Its inventory stays in its old world.";
    }

    /** Label of the button that opens or closes the Other worlds list. */
    public static String otherWorldsToggle(int otherWorldsCount, boolean open) {
        return open ? "Back to this world" : "Other worlds (" + Math.max(0, otherWorldsCount) + ")";
    }

    /** Why Create is greyed when the typed name already belongs to a companion in another world. */
    public static String createNameInOtherWorldHint() {
        return "That name belongs to a companion in another world. Bring them in from Other worlds.";
    }
}
