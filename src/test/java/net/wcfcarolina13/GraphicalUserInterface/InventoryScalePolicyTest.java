package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryScalePolicyTest {

    private static final int INV_W = 364;
    private static final int INV_H = 230;

    @Test
    void defaultWindowDoesNotScale() {
        // 854x480 at auto scale = 427x240: height is tight but 230 + 2*4 still fits.
        float s = InventoryScalePolicy.scaleFor(INV_W, INV_H, 427, 240);
        assertEquals(1.0f, s);
        assertFalse(InventoryScalePolicy.isScaled(s));
    }

    @Test
    void wideWindowDoesNotScale() {
        assertEquals(1.0f, InventoryScalePolicy.scaleFor(INV_W, INV_H, 640, 360));
    }

    @Test
    void exactFitWithMarginDoesNotScale() {
        assertEquals(1.0f, InventoryScalePolicy.scaleFor(INV_W, INV_H, INV_W + 8, INV_H + 8));
    }

    @Test
    void bradleysWindowScalesByWidth() {
        // 1708x1642 at GUI scale 5 = about 342x328.
        float s = InventoryScalePolicy.scaleFor(INV_W, INV_H, 342, 328);
        assertEquals(334f / 364f, s, 1e-6);
        assertTrue(InventoryScalePolicy.isScaled(s));
    }

    @Test
    void vanillaFloorScalesByWidth() {
        float s = InventoryScalePolicy.scaleFor(INV_W, INV_H, 320, 240);
        assertEquals(312f / 364f, s, 1e-6);
    }

    @Test
    void scaledContentFitsInsideScreenWithMargin() {
        int[][] sizes = {{342, 328}, {320, 240}, {300, 200}};
        for (int[] size : sizes) {
            float s = InventoryScalePolicy.scaleFor(INV_W, INV_H, size[0], size[1]);
            assertTrue(INV_W * s <= size[0] - 2 * InventoryScalePolicy.MARGIN + 1e-3, "width at " + size[0]);
            assertTrue(INV_H * s <= size[1] - 2 * InventoryScalePolicy.MARGIN + 1e-3, "height at " + size[1]);
        }
    }

    @Test
    void virtualExtentIsIdentityAtScaleOne() {
        assertEquals(427, InventoryScalePolicy.virtualExtent(427, 1.0f));
        assertEquals(240, InventoryScalePolicy.virtualExtent(240, 1.0f));
    }

    @Test
    void virtualExtentCoversScreenWhenScaled() {
        float s = InventoryScalePolicy.scaleFor(INV_W, INV_H, 320, 240);
        int vw = InventoryScalePolicy.virtualExtent(320, s);
        int vh = InventoryScalePolicy.virtualExtent(240, s);
        assertTrue(vw * s >= 320 - 1e-3, "virtual width covers the screen");
        assertTrue(vh * s >= 240 - 1e-3, "virtual height covers the screen");
        assertTrue((vw - 1) * s < 320, "no more than one extra content pixel");
        assertTrue(vw >= INV_W + 8, "content plus margin fits the virtual width");
    }

    @Test
    void virtualExtentExactDivisionHasNoExtraPixel() {
        assertEquals(400, InventoryScalePolicy.virtualExtent(200, 0.5f));
    }

    @Test
    void toContentIsIdentityAtScaleOne() {
        assertEquals(123.75, InventoryScalePolicy.toContent(123.75, 1.0f));
        assertEquals(123, InventoryScalePolicy.toContent(123, 1.0f));
    }

    @Test
    void toContentDividesByScale() {
        assertEquals(200.0, InventoryScalePolicy.toContent(100.0, 0.5f), 1e-9);
        assertEquals(186, InventoryScalePolicy.toContent(160, 312f / 364f));
    }

    @Test
    void toContentIntFloors() {
        // 101 / 0.5 = 202 exactly, 3 / 0.8 = 3.75 -> 3
        assertEquals(202, InventoryScalePolicy.toContent(101, 0.5f));
        assertEquals(3, InventoryScalePolicy.toContent(3, 0.8f));
    }

    @Test
    void toScreenInvertsToContentForSlotCorners() {
        float s = InventoryScalePolicy.scaleFor(INV_W, INV_H, 342, 328);
        int originX = (InventoryScalePolicy.virtualExtent(342, s) - INV_W) / 2;
        // A slot corner in content space maps to a screen point that maps back within one pixel.
        int contentX = originX + 8 + 18 * 4;
        int screenX = InventoryScalePolicy.toScreen(contentX, s);
        assertEquals(contentX, InventoryScalePolicy.toContent((double) screenX, s), 1.0);
    }

    @Test
    void toScreenIsIdentityAtScaleOne() {
        assertEquals(57, InventoryScalePolicy.toScreen(57, 1.0f));
    }

    @Test
    void mouseOnRightEdgeOfScaledInventoryHitsItsLastColumn() {
        float s = InventoryScalePolicy.scaleFor(INV_W, INV_H, 320, 240);
        int vw = InventoryScalePolicy.virtualExtent(320, s);
        int originX = (vw - INV_W) / 2;
        int rightEdgeContent = originX + INV_W - 1;
        double screenX = rightEdgeContent * s;
        assertTrue(screenX < 320, "inventory right edge is on screen");
        assertEquals(rightEdgeContent, InventoryScalePolicy.toContent(screenX, s), 1e-3);
    }
}
