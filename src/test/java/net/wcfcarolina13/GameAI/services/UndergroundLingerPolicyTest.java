package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.UndergroundLingerPolicy.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndergroundLingerPolicyTest {

    // decide(openSky, feetY, wideSurfaceFeetY, isDay, thundering, scaffoldCount, food, inShelter)

    @Test
    void jakeShallowOpenSkyShaftByDayClimbsOut() {
        // 1.1.235 sandbox: feet 62, wide surface 65, open sky, day, no pickaxe, 6 dirt, food 20.
        assertEquals(Decision.ALLOW_RECOVERY,
                UndergroundLingerPolicy.decide(true, 62, 65, true, false, 6, 20, false));
    }

    @Test
    void sameHoleAtNightStaysPut() {
        assertEquals(Decision.SUPPRESS_NIGHT,
                UndergroundLingerPolicy.decide(true, 62, 65, false, false, 6, 20, false));
    }

    @Test
    void thunderCountsAsNight() {
        assertEquals(Decision.SUPPRESS_NIGHT,
                UndergroundLingerPolicy.decide(true, 62, 65, true, true, 6, 20, false));
    }

    @Test
    void nightRuleAppliesEvenInANonFunctionalCoveredPit() {
        // Covered and deep: the tree's #0 bypass used to skip the night rule here.
        assertEquals(Decision.SUPPRESS_NIGHT,
                UndergroundLingerPolicy.decide(false, 40, 65, false, false, 0, 20, false));
    }

    @Test
    void ownShelterByDayStaysPut() {
        assertEquals(Decision.SUPPRESS_SHELTERED,
                UndergroundLingerPolicy.decide(true, 62, 65, true, false, 6, 20, true));
    }

    @Test
    void criticallyHungryFallsThroughToTheTreesHungerRule() {
        assertEquals(Decision.FALL_THROUGH,
                UndergroundLingerPolicy.decide(true, 62, 65, false, false, 6, 5, false));
        assertEquals(Decision.FALL_THROUGH,
                UndergroundLingerPolicy.decide(true, 62, 65, true, false, 6, 6, true));
    }

    @Test
    void noScaffoldFallsThrough() {
        assertEquals(Decision.FALL_THROUGH,
                UndergroundLingerPolicy.decide(true, 62, 65, true, false, 0, 20, false));
        assertEquals(Decision.FALL_THROUGH,
                UndergroundLingerPolicy.decide(true, 62, 65, true, false, 1, 20, false));
        assertEquals(Decision.ALLOW_RECOVERY,
                UndergroundLingerPolicy.decide(true, 62, 65, true, false, 2, 20, false));
    }

    @Test
    void deepHoleFallsThrough() {
        assertEquals(Decision.FALL_THROUGH,
                UndergroundLingerPolicy.decide(true, 55, 65, true, false, 6, 20, false));
    }

    @Test
    void depthBoundaries() {
        assertEquals(Decision.FALL_THROUGH,
                UndergroundLingerPolicy.decide(true, 65, 65, true, false, 6, 20, false)); // depth 0
        assertEquals(Decision.ALLOW_RECOVERY,
                UndergroundLingerPolicy.decide(true, 64, 65, true, false, 6, 20, false)); // depth 1
        assertEquals(Decision.ALLOW_RECOVERY,
                UndergroundLingerPolicy.decide(true, 59, 65, true, false, 6, 20, false)); // depth 6
        assertEquals(Decision.FALL_THROUGH,
                UndergroundLingerPolicy.decide(true, 58, 65, true, false, 6, 20, false)); // depth 7
    }

    @Test
    void coveredHoleFallsThrough() {
        assertEquals(Decision.FALL_THROUGH,
                UndergroundLingerPolicy.decide(false, 62, 65, true, false, 6, 20, false));
    }

    @Test
    void depthIsWideSurfaceMinusFeet() {
        assertEquals(3, UndergroundLingerPolicy.depthBelowSurface(62, 65));
        assertEquals(-2, UndergroundLingerPolicy.depthBelowSurface(67, 65));
    }

    @Test
    void capCoversOnlyDirectlyAboveTheHead() {
        assertTrue(UndergroundLingerPolicy.bunkerCapCovers(62, 64));
        assertFalse(UndergroundLingerPolicy.bunkerCapCovers(62, 65)); // old first candidate: digPos
        assertFalse(UndergroundLingerPolicy.bunkerCapCovers(62, 63)); // head cell
    }
}
