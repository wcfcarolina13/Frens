package net.wcfcarolina13.GameAI.skills.impl;

import java.util.List;

/**
 * Which animals a hunt may pick from. The Hunting screen saves the ticked animals; only the hunt
 * its Hunt button starts (marked with {@link #TICKED_OPTION}) uses them, together with every
 * ticked animal, nearest first. A typed hunt without a target, Auto Hunt and the hunt hobby hunt
 * anything edible, so ticking one animal can't starve a hungry fren. A resumed hunt keeps the
 * selection it saved at sunset.
 */
public final class HuntSelectionPolicy {

    public static final String TICKED_OPTION = "ticked";

    private HuntSelectionPolicy() {}

    /**
     * @param useTicks       the hunt was started by the Hunting screen's Hunt button
     * @param tickedTargets  the animals ticked on the Hunting screen
     * @param resumedTargets the selection a resumed hunt saved, or null when not resuming
     * @return the animal ids to hunt; empty means anything edible
     */
    public static List<String> effectiveSelection(boolean useTicks, List<String> tickedTargets,
                                                  List<String> resumedTargets) {
        if (resumedTargets != null) {
            return List.copyOf(resumedTargets);
        }
        if (!useTicks || tickedTargets == null) {
            return List.of();
        }
        return List.copyOf(tickedTargets);
    }
}
