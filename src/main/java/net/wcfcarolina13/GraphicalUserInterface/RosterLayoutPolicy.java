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
    /** Height of the "Other worlds" toggle row above the bot rows, when there are other worlds. */
    public static final int TOGGLE_ROW_H = 22;

    /**
     * The roster's vertical offsets, top to bottom. {@link BotRestoreScreen} places everything
     * from these, and the fit maths sums them, so the two can't drift apart.
     *
     * @param titleTop   panel top to title
     * @param subtitleGap title to the first subtitle line
     * @param rowsGap    last subtitle line's bottom to the first bot row
     * @param navGap     last bot row's bottom to Prev/Next
     * @param actionsGap Prev/Next to Spawn Selected / Cancel
     * @param createGap  Spawn Selected to the create-bot row
     * @param linkGap    create-bot row to the bottom link
     * @param bottom     bottom link to the panel bottom (link height plus margin)
     */
    public record Spacing(int titleTop, int subtitleGap, int rowsGap, int navGap, int actionsGap,
                          int createGap, int linkGap, int bottom) {
        /** Everything except the bot rows, with one subtitle line. */
        public int fixedH() {
            return titleTop + subtitleGap + SUBTITLE_LINE_H + rowsGap + navGap + actionsGap + createGap
                    + linkGap + bottom;
        }
    }

    /** The 1.1.227 320x290 look: rows at panel + 52, link at panel + 150 + rows. */
    public static final Spacing ROOMY = new Spacing(14, 16, 12, 8, 24, 36, 30, 32);
    /** Tighter gaps for short screens, 4 px bottom margin under the 10 px link. */
    public static final Spacing COMPACT = new Spacing(6, 16, 6, 4, 22, 30, 26, 14);

    /** Everything except the bot rows, with one subtitle line, in the roomy layout (182). */
    public static final int ROOMY_FIXED_H = ROOMY.fixedH();
    /** Everything except the bot rows, with one subtitle line, in the compact layout (134). */
    public static final int COMPACT_FIXED_H = COMPACT.fixedH();

    public static Spacing spacing(boolean compact) {
        return compact ? COMPACT : ROOMY;
    }

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
        return fit(screenH, margin, subtitleLines, 0);
    }

    /**
     * @param extraH fixed height added above the bot rows (the Other worlds toggle row,
     *               {@link #TOGGLE_ROW_H}, or 0)
     */
    public static Fit fit(int screenH, int margin, int subtitleLines, int extraH) {
        int extra = Math.max(0, subtitleLines - 1) * SUBTITLE_LINE_H + Math.max(0, extraH);
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
