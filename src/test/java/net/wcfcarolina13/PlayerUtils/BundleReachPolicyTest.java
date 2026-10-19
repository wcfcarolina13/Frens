package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleReachPolicyTest {

    @Test
    @DisplayName("direct supply already covers the requirement -> no extractions")
    void directCoversNeed() {
        assertEquals(0, BundleReachPolicy.extractionsNeeded(4, 4, 10));
        assertEquals(0, BundleReachPolicy.extractionsNeeded(4, 9, 10));
    }

    @Test
    @DisplayName("shortfall is pulled from bundles when they hold enough")
    void shortfallCoveredByBundles() {
        assertEquals(3, BundleReachPolicy.extractionsNeeded(5, 2, 64));
        assertEquals(5, BundleReachPolicy.extractionsNeeded(5, 0, 5));
    }

    @Test
    @DisplayName("bundles cap the number of extractions")
    void bundlesCapExtractions() {
        assertEquals(2, BundleReachPolicy.extractionsNeeded(10, 0, 2));
        assertEquals(1, BundleReachPolicy.extractionsNeeded(10, 8, 1));
    }

    @Test
    @DisplayName("nothing bundled means nothing to extract")
    void nothingBundled() {
        assertEquals(0, BundleReachPolicy.extractionsNeeded(6, 1, 0));
    }

    @Test
    @DisplayName("non-positive need is a no-op")
    void nonPositiveNeed() {
        assertEquals(0, BundleReachPolicy.extractionsNeeded(0, 0, 10));
        assertEquals(0, BundleReachPolicy.extractionsNeeded(-3, 0, 10));
    }

    @Test
    @DisplayName("negative counts are clamped, never producing a negative result")
    void negativeCountsClamped() {
        assertEquals(0, BundleReachPolicy.extractionsNeeded(5, -2, -7));
        assertEquals(5, BundleReachPolicy.extractionsNeeded(5, -2, 9));
        assertEquals(0, BundleReachPolicy.extractionsNeeded(5, 9, -1));
    }

    @Test
    @DisplayName("strictly better bundled candidate is worth extracting")
    void bundledStrictlyBetter() {
        assertTrue(BundleReachPolicy.shouldReachForBetter(10, 20, false));
    }

    @Test
    @DisplayName("bundled candidate that only ties the direct best is not extracted")
    void bundledTieNotExtracted() {
        assertFalse(BundleReachPolicy.shouldReachForBetter(20, 20, false));
        assertFalse(BundleReachPolicy.shouldReachForBetter(30, 20, false));
    }

    @Test
    @DisplayName("no direct candidate (-1) means any bundled candidate wins")
    void noDirectCandidate() {
        assertTrue(BundleReachPolicy.shouldReachForBetter(-1, 0, false));
        assertFalse(BundleReachPolicy.shouldReachForBetter(-1, -1, false));
    }

    @Test
    @DisplayName("rate limiting suppresses the reach even when the bundled item is better")
    void rateLimitedSuppresses() {
        assertFalse(BundleReachPolicy.shouldReachForBetter(10, 99, true));
        assertFalse(BundleReachPolicy.shouldReachForBetter(-1, 99, true));
    }

    @Test
    @DisplayName("finding 6: bundled diamond pickaxe beats a working loose stone pickaxe -> reach")
    void upgradeEvenWhenDirectToolWorks() {
        // stone pickaxe 4.0 on stone, diamond 8.0
        assertTrue(BundleReachPolicy.shouldUpgradeFromBundle(4.0f, 8.0f, false));
    }

    @Test
    @DisplayName("old behaviour kept: nothing direct beats hands, bundled tool does -> reach")
    void upgradeWhenNoDirectTool() {
        assertTrue(BundleReachPolicy.shouldUpgradeFromBundle(0.0f, 4.0f, false));
        assertTrue(BundleReachPolicy.shouldUpgradeFromBundle(1.0f, 2.0f, false));
    }

    @Test
    @DisplayName("equal or slower bundled tool never churns the inventory")
    void noUpgradeWhenNotStrictlyBetter() {
        assertFalse(BundleReachPolicy.shouldUpgradeFromBundle(8.0f, 8.0f, false));
        assertFalse(BundleReachPolicy.shouldUpgradeFromBundle(8.0f, 4.0f, false));
    }

    @Test
    @DisplayName("a bundled item no faster than bare hands is never reached")
    void noUpgradeAtHandSpeed() {
        assertFalse(BundleReachPolicy.shouldUpgradeFromBundle(0.0f, 1.0f, false));
        assertFalse(BundleReachPolicy.shouldUpgradeFromBundle(0.0f, 0.0f, false));
    }

    @Test
    @DisplayName("rate-limited bot does not reach even for a strictly better tool")
    void noUpgradeWhenRateLimited() {
        assertFalse(BundleReachPolicy.shouldUpgradeFromBundle(4.0f, 8.0f, true));
        assertFalse(BundleReachPolicy.shouldUpgradeFromBundle(0.0f, 8.0f, true));
    }

    @Test
    @DisplayName("NaN speeds never trigger a reach")
    void noUpgradeOnNaN() {
        assertFalse(BundleReachPolicy.shouldUpgradeFromBundle(Float.NaN, 8.0f, false));
        assertFalse(BundleReachPolicy.shouldUpgradeFromBundle(4.0f, Float.NaN, false));
    }

    @Test
    @DisplayName("cooldown: never reached, inside window, at boundary, clock went backwards")
    void cooldownWindow() {
        assertFalse(BundleReachPolicy.isCoolingDown(500L, null, 100L));
        assertTrue(BundleReachPolicy.isCoolingDown(500L, 500L, 100L));
        assertTrue(BundleReachPolicy.isCoolingDown(599L, 500L, 100L));
        assertFalse(BundleReachPolicy.isCoolingDown(600L, 500L, 100L));
        assertFalse(BundleReachPolicy.isCoolingDown(10L, 500L, 100L));
    }
}
