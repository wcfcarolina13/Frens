package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FollowLeafAnchorPolicyTest {

    @Test
    void firstTickAnchorsAtBotWithZeroTicks() {
        FollowLeafAnchorPolicy.Anchor a = FollowLeafAnchorPolicy.update(null, 10.5D, 20.5D);
        assertEquals(10.5D, a.x());
        assertEquals(20.5D, a.z());
        assertEquals(0, a.ticks());
    }

    @Test
    void stationaryBotCounts() {
        FollowLeafAnchorPolicy.Anchor a = FollowLeafAnchorPolicy.update(null, 10.5D, 20.5D);
        for (int i = 0; i < 5; i++) {
            a = FollowLeafAnchorPolicy.update(a, 10.5D, 20.5D);
        }
        assertEquals(5, a.ticks());
    }

    @Test
    void rockingAcrossABlockBoundaryKeepsCounting() {
        // Anchored at x=10.9; the nudge rocks the bot to 11.2 (next block) and back to 10.7.
        FollowLeafAnchorPolicy.Anchor a = FollowLeafAnchorPolicy.update(null, 10.9D, 20.5D);
        double[] xs = {11.2D, 10.7D, 11.3D, 10.8D, 11.1D};
        for (double x : xs) {
            a = FollowLeafAnchorPolicy.update(a, x, 20.5D);
        }
        assertEquals(5, a.ticks());
        assertEquals(10.9D, a.x());
    }

    @Test
    void realMovementResetsTheAnchor() {
        FollowLeafAnchorPolicy.Anchor a = FollowLeafAnchorPolicy.update(null, 10.5D, 20.5D);
        a = FollowLeafAnchorPolicy.update(a, 10.5D, 20.5D);
        a = FollowLeafAnchorPolicy.update(a, 11.5D, 20.5D);
        assertEquals(0, a.ticks());
        assertEquals(11.5D, a.x());
    }

    @Test
    void radiusBoundaryIsInclusive() {
        FollowLeafAnchorPolicy.Anchor a = FollowLeafAnchorPolicy.update(null, 0.0D, 0.0D);
        assertEquals(1, FollowLeafAnchorPolicy.update(a, 0.75D, 0.0D).ticks());
        assertEquals(0, FollowLeafAnchorPolicy.update(a, 0.76D, 0.0D).ticks());
    }
}
