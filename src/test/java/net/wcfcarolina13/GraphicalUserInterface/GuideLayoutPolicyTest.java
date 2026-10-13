package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideLayoutPolicyTest {

    private static final int GAP = 8;

    /** Mirrors BotGuideScreen.bodyRect(): x 12, y 44, w width-24, h height-74. */
    private static GuideLayoutPolicy.Layout forScreen(int width, int height) {
        return GuideLayoutPolicy.layout(12, 44, Math.max(200, width - 24), Math.max(100, height - 74), GAP);
    }

    private static void assertOnScreen(GuideLayoutPolicy.Layout l, int width, int height) {
        for (GuideLayoutPolicy.Box b : new GuideLayoutPolicy.Box[] {l.topics(), l.detail()}) {
            assertTrue(b.x() >= 0, "x on screen: " + b);
            assertTrue(b.x() + b.w() <= width - 12, "right edge inside the body: " + b);
            assertTrue(b.y() + b.h() <= height, "bottom on screen: " + b);
            assertTrue(b.w() > 0 && b.h() > 0, "non-empty: " + b);
        }
    }

    @Test
    void wideScreenKeepsTheOriginalSplit() {
        // 854x480 at scale 1: body 830 wide, topics clamp(34%) = 282, detail gets the rest.
        GuideLayoutPolicy.Layout l = forScreen(854, 480);
        assertFalse(l.stacked());
        assertEquals(282, l.topics().w());
        assertEquals(12 + 282 + GAP, l.detail().x());
        assertEquals(830 - 282 - GAP, l.detail().w());
    }

    @Test
    void at427x240BothColumnsFitSideBySide() {
        GuideLayoutPolicy.Layout l = forScreen(427, 240);
        assertFalse(l.stacked());
        assertEquals(403, l.topics().w() + GAP + l.detail().w());
        assertTrue(l.detail().w() >= GuideLayoutPolicy.DETAIL_MIN_W);
        assertOnScreen(l, 427, 240);
    }

    @Test
    void at342x328TheTopicsStackAboveTheDetailCard() {
        GuideLayoutPolicy.Layout l = forScreen(342, 328);
        assertTrue(l.stacked());
        assertEquals(318, l.topics().w());
        assertEquals(318, l.detail().w());
        assertEquals(l.topics().y() + l.topics().h() + GAP, l.detail().y());
        assertEquals(44 + 254, l.detail().y() + l.detail().h());
        assertTrue(l.topics().h() >= GuideLayoutPolicy.STACKED_TOPICS_MIN_H);
        assertTrue(l.detail().h() >= GuideLayoutPolicy.STACKED_DETAIL_MIN_H);
        assertOnScreen(l, 342, 328);
    }

    @Test
    void at320x240ItSplitsSideBySideBecauseStackingWouldBeTooShort() {
        GuideLayoutPolicy.Layout l = forScreen(320, 240);
        assertFalse(l.stacked());
        assertEquals(296, l.topics().w() + GAP + l.detail().w());
        assertTrue(l.topics().w() >= GuideLayoutPolicy.NARROW_TOPICS_MIN_W);
        assertTrue(l.detail().w() >= GuideLayoutPolicy.NARROW_DETAIL_MIN_W);
        assertOnScreen(l, 320, 240);
    }

    @Test
    void justBelowTheWideThresholdStillFitsExactly() {
        GuideLayoutPolicy.Layout l = GuideLayoutPolicy.layout(12, 44, GuideLayoutPolicy.NARROW_BODY_W - 1, 150, GAP);
        assertFalse(l.stacked());
        assertEquals(GuideLayoutPolicy.NARROW_BODY_W - 1, l.topics().w() + GAP + l.detail().w());
    }

    @Test
    void atTheWideThresholdTheDetailCardKeepsItsMinimum() {
        GuideLayoutPolicy.Layout l = GuideLayoutPolicy.layout(12, 44, GuideLayoutPolicy.NARROW_BODY_W, 150, GAP);
        assertFalse(l.stacked());
        assertEquals(GuideLayoutPolicy.DETAIL_MIN_W, l.detail().w());
        assertEquals(GuideLayoutPolicy.NARROW_BODY_W, l.topics().w() + GAP + l.detail().w());
    }

    @Test
    void stackingNeedsTheMinimumBodyHeight() {
        assertFalse(GuideLayoutPolicy.layout(0, 0, 300, GuideLayoutPolicy.STACK_MIN_BODY_H - 1, GAP).stacked());
        assertTrue(GuideLayoutPolicy.layout(0, 0, 300, GuideLayoutPolicy.STACK_MIN_BODY_H, GAP).stacked());
    }
}
