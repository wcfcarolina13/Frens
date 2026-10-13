package net.wcfcarolina13.GraphicalUserInterface;

/**
 * Scale-to-fit maths for the bot inventory screen (364x230 content). When the scaled window is
 * narrower or shorter than the content, the screen lays out in a larger "content" coordinate space
 * and draws it through a uniform scale. Mouse input arrives in screen coordinates and is mapped
 * back with {@link #toContent}. Picture-in-picture elements (the entity previews) ignore the
 * matrix, so their boxes go the other way with {@link #toScreen}.
 *
 * <p>At scale 1 every mapping is the identity, so screens that already fit behave exactly as before.
 * No Minecraft types here.
 */
public final class InventoryScalePolicy {

    /**
     * Gap kept between the content and the screen edge. Must stay at most 5, or the default
     * 854x480 window (427x240 scaled) would scale the 230-tall inventory for no reason.
     */
    public static final int MARGIN = 4;

    private InventoryScalePolicy() {
    }

    /** Scale for {@code content} on {@code screen}: exactly 1 when it fits with {@link #MARGIN}. */
    public static float scaleFor(int contentW, int contentH, int screenW, int screenH) {
        return UiFitPolicy.fitScale(contentW, contentH, screenW, screenH, MARGIN);
    }

    public static boolean isScaled(float scale) {
        return scale < 1.0f;
    }

    /**
     * Size of the content coordinate space along one axis. Rounded up so that, drawn at
     * {@code scale}, it covers the whole screen (no undarkened strip at the edge).
     */
    public static int virtualExtent(int screenExtent, float scale) {
        if (!isScaled(scale) || scale <= 0.0f) {
            return screenExtent;
        }
        return (int) Math.ceil(screenExtent / (double) scale - 1.0e-6);
    }

    /** Screen (mouse) coordinate to content coordinate. */
    public static double toContent(double screenCoord, float scale) {
        if (!isScaled(scale) || scale <= 0.0f) {
            return screenCoord;
        }
        return screenCoord / scale;
    }

    /** Integer screen coordinate to integer content coordinate, for render's int mouse position. */
    public static int toContent(int screenCoord, float scale) {
        if (!isScaled(scale) || scale <= 0.0f) {
            return screenCoord;
        }
        return (int) Math.floor(screenCoord / (double) scale);
    }

    /** Content coordinate to screen coordinate, for elements drawn outside the scale matrix. */
    public static int toScreen(int contentCoord, float scale) {
        if (!isScaled(scale)) {
            return contentCoord;
        }
        return Math.round(contentCoord * scale);
    }
}
