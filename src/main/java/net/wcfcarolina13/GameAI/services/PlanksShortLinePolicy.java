package net.wcfcarolina13.GameAI.services;

/**
 * The line a bot says when it cannot get the planks a craft needs. With logs in the pack and no
 * free slot, the logs could not be turned into planks because the planks had nowhere to go, so
 * the line names the full pack instead of asking for planks it effectively has.
 */
public final class PlanksShortLinePolicy {
    private PlanksShortLinePolicy() {
    }

    public static String line(int planksPerItem, boolean hasLogs, boolean packFull) {
        if (hasLogs && packFull) {
            return "My pack is full, so I can't turn my logs into planks. Make some room and ask again.";
        }
        return "I need " + planksPerItem + " planks per craft.";
    }
}
