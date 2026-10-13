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
        ALL_PRESENT
    }

    private RosterScreenCopy() {
    }

    public static State state(int restorableCount, boolean allSavedPresent) {
        if (restorableCount > 0) {
            return State.HAS_RESTORABLE;
        }
        return allSavedPresent ? State.ALL_PRESENT : State.NONE_SAVED;
    }

    public static String title(State state) {
        return switch (state) {
            case HAS_RESTORABLE -> "Choose Companions to Restore";
            case NONE_SAVED -> "Welcome. No saved companions yet.";
            case ALL_PRESENT -> "All your companions are already here";
        };
    }

    public static String subtitle(State state) {
        return switch (state) {
            case HAS_RESTORABLE -> "Tick one or more saved bots, then click Spawn Selected.";
            case NONE_SAVED -> "Pick a name below to spawn your first bot.";
            case ALL_PRESENT -> "To add another, type a new name below and click Create.";
        };
    }

    /** Placeholder shown where the list would be. Empty for {@link State#HAS_RESTORABLE}. */
    public static String emptyListLine(State state) {
        return switch (state) {
            case HAS_RESTORABLE -> "";
            case NONE_SAVED -> "(No saved bots yet. Create one below.)";
            case ALL_PRESENT -> "(Everyone you saved is already in this world.)";
        };
    }
}
