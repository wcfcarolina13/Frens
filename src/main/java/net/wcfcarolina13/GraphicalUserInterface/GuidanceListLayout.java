package net.wcfcarolina13.GraphicalUserInterface;

/**
 * Layout of the Remote Guidance destination list. With many saved bases the rows used to run
 * down over the Confirm button, so a click on Confirm hit a list row. The list now shows only the
 * rows that fit above the warning line and scrolls. Pure so it can be tested without Minecraft.
 */
final class GuidanceListLayout {

    private GuidanceListLayout() {}

    /** How many rows of {@code rowStep} fit from {@code topY} down to {@code limitY}, at least 1. */
    static int visibleRows(int topY, int limitY, int rowStep) {
        if (rowStep <= 0) {
            return 1;
        }
        return Math.max(1, (limitY - topY) / rowStep);
    }

    /** Clamps the first shown row so the window stays in range and shows the selected row. */
    static int clampOffset(int offset, int selected, int total, int visible) {
        int max = Math.max(0, total - visible);
        int o = Math.max(0, Math.min(max, offset));
        if (selected >= 0 && selected < total) {
            if (selected < o) {
                o = selected;
            } else if (selected >= o + visible) {
                o = selected - visible + 1;
            }
        }
        return Math.max(0, Math.min(max, o));
    }
}
