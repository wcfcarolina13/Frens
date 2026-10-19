package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.LodestoneValidityPolicy.Validity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodestoneValidityPolicyTest {

    @Test
    void loadedChunkWithLodestoneIsPresent() {
        Validity v = LodestoneValidityPolicy.decide(true, null, true);
        assertEquals(Validity.PRESENT, v);
        assertTrue(LodestoneValidityPolicy.allowsTravel(v));
    }

    @Test
    void loadedChunkWithoutLodestoneIsAbsent() {
        Validity v = LodestoneValidityPolicy.decide(false, null, true);
        assertEquals(Validity.ABSENT, v);
        assertFalse(LodestoneValidityPolicy.allowsTravel(v));
    }

    @Test
    void unloadedChunkWithPoiLodestoneIsPresent() {
        Validity v = LodestoneValidityPolicy.decide(null, true, true);
        assertEquals(Validity.PRESENT, v);
        assertTrue(LodestoneValidityPolicy.allowsTravel(v));
    }

    @Test
    void unloadedChunkWithoutPoiLodestoneIsAbsent() {
        Validity v = LodestoneValidityPolicy.decide(null, false, true);
        assertEquals(Validity.ABSENT, v);
        assertFalse(LodestoneValidityPolicy.allowsTravel(v));
    }

    @Test
    void offThreadUnloadedChunkIsUnknownAndAllowed() {
        Validity v = LodestoneValidityPolicy.decide(null, null, false);
        assertEquals(Validity.UNKNOWN, v);
        assertTrue(LodestoneValidityPolicy.allowsTravel(v));
    }
}
