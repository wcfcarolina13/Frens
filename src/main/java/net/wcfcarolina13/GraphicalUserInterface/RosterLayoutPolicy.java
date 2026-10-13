package net.wcfcarolina13.GraphicalUserInterface;

/**
 * Vertical fit for the companion roster ({@link BotRestoreScreen}). Pure so it is unit tested.
 * The roster keeps its 320x290 look when the screen has room, and otherwise drops to a compact
 * layout whose bot list shows as many rows as fit and scrolls through the rest.
 */
public final class RosterLayoutPolicy {

    public static final int ROW_H = 18;
    public static final int MAX_ROWS = 6;
    public static final int SUBTITLE_LINE_H = 10;
    /** Everything except the bot rows, with one subtitle line, in the roomy layout. */
    public static final int ROOMY_FIXED_H = 182;
    /** Everything except the bot rows, with one subtitle line, in the compact layout (4 px bottom margin). */
    public static final int COMPACT_FIXED_H = 134;

    private RosterLayoutPolicy() {
    }

    /**
     * @param compact true when the roomy layout doesn't fit the screen height
     * @param rows    bot rows shown at once (the rest scroll)
     * @param panelH  total panel height
     */
    public record Fit(boolean compact, int rows, int panelH) {
    }

    /**
     * @param screenH       scaled screen height
     * @param margin        space kept free above and below the panel
     * @param subtitleLines lines the subtitle wraps to (at least 1)
     */
    public static Fit fit(int screenH, int margin, int subtitleLines) {
        int extra = Math.max(0, subtitleLines - 1) * SUBTITLE_LINE_H;
        int available = screenH - 2 * Math.max(0, margin);
        int roomyH = ROOMY_FIXED_H + extra + MAX_ROWS * ROW_H;
        if (roomyH <= available) {
            return new Fit(false, MAX_ROWS, roomyH);
        }
        int fixed = COMPACT_FIXED_H + extra;
        int rows = visibleRows(available, fixed, ROW_H, MAX_ROWS);
        return new Fit(true, rows, fixed + rows * ROW_H);
    }

    /** How many rows fit in {@code availableH} after {@code fixedH}, between 1 and {@code maxRows}. */
    public static int visibleRows(int availableH, int fixedH, int rowH, int maxRows) {
        int max = Math.max(1, maxRows);
        if (rowH <= 0) {
            return max;
        }
        int rows = (availableH - fixedH) / rowH;
        return Math.max(1, Math.min(max, rows));
    }

    /** Keeps the first shown row inside the list so the last page is never half empty. */
    public static int clampFirstRow(int firstRow, int total, int visible) {
        int maxFirst = Math.max(0, total - Math.max(1, visible));
        return Math.max(0, Math.min(maxFirst, firstRow));
    }
}
