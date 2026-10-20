package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TreeSamplingPolicyTest {

    @Test
    void ringSamplesUseTheFloorOnSmallRings() {
        assertEquals(24, TreeSamplingPolicy.samplesForRing(8, 24));
        assertEquals(16, TreeSamplingPolicy.samplesForRing(6, 16));
    }

    @Test
    void ringSamplesGrowWithRadiusUpToTheCap() {
        assertEquals(32, TreeSamplingPolicy.samplesForRing(16, 24));
        assertEquals(48, TreeSamplingPolicy.samplesForRing(24, 24));
        assertEquals(48, TreeSamplingPolicy.samplesForRing(120, 24));
    }

    @Test
    void scanTopIsTwoAboveTheWorldTopWhenTheTerrainIsLow() {
        // heightmap sample 70 -> world top 71 -> scan from 73
        assertEquals(73, TreeSamplingPolicy.scanMaxY(70, 64, 24));
    }

    @Test
    void scanTopIsCappedByTheVerticalRange() {
        assertEquals(88, TreeSamplingPolicy.scanMaxY(150, 64, 24));
        assertEquals(65, TreeSamplingPolicy.scanMaxY(150, 64, 0));
    }
}
