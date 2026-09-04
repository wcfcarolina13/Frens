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
}
