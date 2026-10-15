package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the woodcut stand skip from an unmoved cell (1.1.240). No net.minecraft types. */
class WoodcutStandRetryPolicyTest {

    @Test
    void pathFailureDetailsCount() {
        assertTrue(WoodcutStandRetryPolicy.isPathFailure("approach-direct: no walkable path"));
        assertTrue(WoodcutStandRetryPolicy.isPathFailure("move-to-stand: walk blocked near 21,66,127"));
        assertTrue(WoodcutStandRetryPolicy.isPathFailure("approach-plan: walk blocked after replans"));
        assertTrue(WoodcutStandRetryPolicy.isPathFailure("A* timeout after 10000 ms"));
        assertTrue(WoodcutStandRetryPolicy.isPathFailure("NO WALKABLE PATH"));
    }

    @Test
    void otherFailuresDoNotCount() {
        assertFalse(WoodcutStandRetryPolicy.isPathFailure(null));
        assertFalse(WoodcutStandRetryPolicy.isPathFailure(""));
        assertFalse(WoodcutStandRetryPolicy.isPathFailure("approach-direct: aborted"), "/bot stop");
        assertFalse(WoodcutStandRetryPolicy.isPathFailure("requested-bounds"));
        assertFalse(WoodcutStandRetryPolicy.isPathFailure("null-plan"));
        assertFalse(WoodcutStandRetryPolicy.isPathFailure("Invalid movement request."));
        assertFalse(WoodcutStandRetryPolicy.isPathFailure("move-to-stand: throttled (recent path attempt) – last: x"));
    }

    @Test
    void skipsOnlyFromAnUnmovedCell() {
        String detail = "approach-direct: no walkable path";
        assertTrue(WoodcutStandRetryPolicy.skipRemainingStands(detail, 0.0D));
        assertTrue(WoodcutStandRetryPolicy.skipRemainingStands(detail, 0.99D));
        assertFalse(WoodcutStandRetryPolicy.skipRemainingStands(detail, 1.0D), "moved a block: keep retrying");
        assertFalse(WoodcutStandRetryPolicy.skipRemainingStands(detail, 6.0D));
        assertFalse(WoodcutStandRetryPolicy.skipRemainingStands(detail, Double.MAX_VALUE), "unknown origin");
        assertFalse(WoodcutStandRetryPolicy.skipRemainingStands("approach-direct: aborted", 0.0D));
    }

    @Test
    void movementIsHorizontalSoAJumpBobIsNotMovement() {
        assertEquals(0.0D, WoodcutStandRetryPolicy.horizontalMoved(22.5D, 126.5D, 22.5D, 126.5D), 1e-9);
        assertEquals(5.0D, WoodcutStandRetryPolicy.horizontalMoved(0.0D, 0.0D, 3.0D, 4.0D), 1e-9);
    }

    @Test
    void skipLineIsExact() {
        assertEquals("Woodcut approach: skipping remaining stands for -12, 68, 152 (path failed from an unmoved cell: approach-direct: no walkable path)",
                WoodcutStandRetryPolicy.skipLine("-12, 68, 152", "approach-direct: no walkable path"));
    }
}
