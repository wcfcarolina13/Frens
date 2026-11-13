package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningFaceVisibilityPolicyTest {
    @Test void samplesStartAtTopThenSidesFacingEye() {
        var points = MiningFaceVisibilityPolicy.samples(10, 117, 20, 9.5, 121.62, 19.5);
        assertEquals(new MiningFaceVisibilityPolicy.Point(10.5, 117.999, 20.5), points.get(0));
        assertTrue(points.contains(new MiningFaceVisibilityPolicy.Point(10.001, 117.5, 20.5)));
        assertTrue(points.contains(new MiningFaceVisibilityPolicy.Point(10.5, 117.5, 20.001)));
        assertFalse(points.contains(new MiningFaceVisibilityPolicy.Point(10.999, 117.5, 20.5)));
    }
    @Test void topEdgeCanBeSeenOverBankWhenCentreRayIsBlocked() {
        // Eye on bank x=9.5, top of bank y=120, ditch x=10. Bottom cell y=117.
        var points = MiningFaceVisibilityPolicy.samples(10, 117, 20, 9.5, 121.62, 20.5);
        double centreBoundaryY = 121.62 + (117.5 - 121.62) * .5;
        assertTrue(centreBoundaryY < 120);
        assertTrue(points.stream().anyMatch(p -> {
            double t = .5 / (p.x() - 9.5);
            return p.y() > 117.99 && 121.62 + (p.y() - 121.62) * t > 120;
        }));
    }
    @Test void samplesStayInsideTargetAndContainCentreFallback() {
        var points = MiningFaceVisibilityPolicy.samples(-2, 4, 7, 0, 2, 9);
        assertTrue(points.stream().allMatch(p -> p.x() > -2 && p.x() < -1
                && p.y() > 4 && p.y() < 5 && p.z() > 7 && p.z() < 8));
        assertTrue(points.contains(new MiningFaceVisibilityPolicy.Point(-1.5, 4.5, 7.5)));
    }
}
