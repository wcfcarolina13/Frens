package net.wcfcarolina13.GraphicalUserInterface;

/**
 * Where the Guide's topics list and detail card go inside the body area. Pure (no Minecraft
 * types) so the narrow-screen rules are unit tested.
 *
 * <ul>
 *   <li>Wide (body at least {@link #NARROW_BODY_W}): topics take about a third of the width
 *       (220..320 px) and the detail card keeps at least {@link #DETAIL_MIN_W}.</li>
 *   <li>Narrow with height to spare: the topics list sits above the detail card, both at full
 *       width, so the card text wraps to a readable width.</li>
 *   <li>Narrow and short (for example 320x240): side by side, split so the two columns always
 *       fit the body exactly.</li>
 * </ul>
 */
public final class GuideLayoutPolicy {

    /** Below this body width the wide layout no longer fits (screen width about 392). */
    public static final int NARROW_BODY_W = 368;
    /** Stack only when the body is at least this tall, so both stacked panels stay usable. */
    public static final int STACK_MIN_BODY_H = 210;
    public static final int DETAIL_MIN_W = 200;
    /** Panel chrome (header 24 + bottom 6) plus four topic rows of 18. */
    public static final int STACKED_TOPICS_MIN_H = 102;
    public static final int STACKED_DETAIL_MIN_H = 100;
    public static final int NARROW_TOPICS_MIN_W = 110;
    public static final int NARROW_DETAIL_MIN_W = 160;

    private GuideLayoutPolicy() {
    }

    public record Box(int x, int y, int w, int h) {
    }

    public record Layout(boolean stacked, Box topics, Box detail) {
    }

    public static Layout layout(int bodyX, int bodyY, int bodyW, int bodyH, int gap) {
        int w = Math.max(0, bodyW);
        int h = Math.max(0, bodyH);
        int g = Math.max(0, gap);

        if (w >= NARROW_BODY_W) {
            int desired = Math.max(220, Math.min(320, (w * 34) / 100));
            int topicsW = Math.min(desired, w - g - DETAIL_MIN_W);
            int detailX = bodyX + topicsW + g;
            return new Layout(false,
                    new Box(bodyX, bodyY, topicsW, h),
                    new Box(detailX, bodyY, bodyX + w - detailX, h));
        }

        if (h >= STACK_MIN_BODY_H) {
            int topicsH = Math.max(STACKED_TOPICS_MIN_H, (h * 40) / 100);
            topicsH = Math.min(topicsH, h - g - STACKED_DETAIL_MIN_H);
            int detailY = bodyY + topicsH + g;
            return new Layout(true,
                    new Box(bodyX, bodyY, w, topicsH),
                    new Box(bodyX, detailY, w, bodyY + h - detailY));
        }

        int[] cols = UiFitPolicy.splitColumns(w, 0.40, NARROW_TOPICS_MIN_W, NARROW_DETAIL_MIN_W, g);
        int detailX = bodyX + cols[0] + g;
        return new Layout(false,
                new Box(bodyX, bodyY, cols[0], h),
                new Box(detailX, bodyY, cols[1], h));
    }
}
