package net.wcfcarolina13.GraphicalUserInterface;

/**
 * Where the Guide's topics list and detail card go inside the body area. Pure (no Minecraft
 * types) so the narrow-screen rules are unit tested.
 *
 * <ul>
 *   <li>Wide (body at least {@link #NARROW_BODY_W}): the 1.1.227 split, unchanged. Topics take
 *       about a third of the width (220..320 px) but leave the detail card 200 px, never going
 *       under {@link #WIDE_TOPICS_MIN_W}. The detail card gets the rest (at least 180 here).</li>
 *   <li>Narrow with height to spare: the topics list sits above the detail card, both at full
 *       width, so the card text wraps to a readable width.</li>
 *   <li>Narrow and short (for example 320x240): side by side, split so the two columns always
 *       fit the body exactly.</li>
 * </ul>
 */
public final class GuideLayoutPolicy {

    /**
     * Below this body width the old wide layout no longer fits (screen width about 392): at 368
     * the topics floor 180 plus the gap leaves the detail card exactly 180.
     */
    public static final int NARROW_BODY_W = 368;
    /** Stack only when the body is at least this tall, so both stacked panels stay usable. */
    public static final int STACK_MIN_BODY_H = 210;
    /** The old topics floor: {@code max(180, body - 200)}. */
    public static final int WIDE_TOPICS_MIN_W = 180;
    /** The old split leaves the detail card this much before the topics floor kicks in. */
    public static final int WIDE_DETAIL_SHARE = 200;
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
            // Exactly the 1.1.227 formula, so 392 wide and up look as they did.
            int topicsW = Math.min(desired, Math.max(WIDE_TOPICS_MIN_W, w - WIDE_DETAIL_SHARE));
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
