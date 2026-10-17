package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.PostArrivalPersistPolicy.Restore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostArrivalPersistPolicyTest {

    @Test
    void retrieveActionsArePersisted() {
        assertTrue(PostArrivalPersistPolicy.persistable("retrieve:sender"));
        assertTrue(PostArrivalPersistPolicy.persistable("retrieve:stay"));
        assertFalse(PostArrivalPersistPolicy.persistable("retrieve:home"));
        assertFalse(PostArrivalPersistPolicy.persistable("retrieve:"));
    }

    @Test
    void withdrawActionsArePersisted() {
        assertTrue(PostArrivalPersistPolicy.persistable("withdraw:stay"));
        assertTrue(PostArrivalPersistPolicy.persistable("withdraw:player"));
        assertTrue(PostArrivalPersistPolicy.persistable("withdraw:home"));
        assertFalse(PostArrivalPersistPolicy.persistable("withdraw:"));
        assertFalse(PostArrivalPersistPolicy.persistable("withdraw:somewhere"));
        assertFalse(PostArrivalPersistPolicy.persistable("withdraw"));
    }

    @Test
    void skillResumeAndJunkAreNotPersisted() {
        assertFalse(PostArrivalPersistPolicy.persistable("skill_resume:bot skill woodcut Smokey"));
        assertFalse(PostArrivalPersistPolicy.persistable(null));
        assertFalse(PostArrivalPersistPolicy.persistable(""));
    }

    @Test
    void otherWorldIsDroppedBeforeAnythingElse() {
        assertEquals(Restore.DROP_WORLD, PostArrivalPersistPolicy.restore("A#1", "B#2", true, true));
        assertEquals(Restore.DROP_WORLD, PostArrivalPersistPolicy.restore("A#1", "B#2", false, false));
    }

    @Test
    void legacyOrUnknownWorldKeepsOldBehaviour() {
        assertTrue(PostArrivalPersistPolicy.sameWorld(null, "B#2"));
        assertTrue(PostArrivalPersistPolicy.sameWorld("A#1", null));
        assertTrue(PostArrivalPersistPolicy.sameWorld("A#1", "A#1"));
        assertFalse(PostArrivalPersistPolicy.sameWorld("A#1", "A#2"));
        assertEquals(Restore.KEEP, PostArrivalPersistPolicy.restore(null, "B#2", true, true));
    }

    @Test
    void orphanAndWrongTripAreDropped() {
        assertEquals(Restore.DROP_ORPHAN, PostArrivalPersistPolicy.restore("A#1", "A#1", false, true));
        assertEquals(Restore.DROP_WRONG_TRIP, PostArrivalPersistPolicy.restore("A#1", "A#1", true, false));
        assertEquals(Restore.KEEP, PostArrivalPersistPolicy.restore("A#1", "A#1", true, true));
    }

    @Test
    void intTripleNeedsAllThree() {
        assertArrayEquals(new int[] {1, -64, 3}, PostArrivalPersistPolicy.intTriple(1, -64, 3));
        assertNull(PostArrivalPersistPolicy.intTriple(null, 2, 3));
        assertNull(PostArrivalPersistPolicy.intTriple(1, null, 3));
        assertNull(PostArrivalPersistPolicy.intTriple(1, 2, null));
    }

    @Test
    void doubleTripleNeedsAllThreeFinite() {
        assertArrayEquals(new double[] {1.5, 64.0, -3.25}, PostArrivalPersistPolicy.doubleTriple(1.5, 64.0, -3.25));
        assertNull(PostArrivalPersistPolicy.doubleTriple(null, 2.0, 3.0));
        assertNull(PostArrivalPersistPolicy.doubleTriple(1.0, Double.NaN, 3.0));
        assertNull(PostArrivalPersistPolicy.doubleTriple(1.0, 2.0, Double.POSITIVE_INFINITY));
    }

    @Test
    void targetMustEqualDestinationExactly() {
        assertTrue(PostArrivalPersistPolicy.targetEqualsDest(new int[] {10, 64, -5}, 10, 64, -5));
        assertFalse(PostArrivalPersistPolicy.targetEqualsDest(new int[] {10, 65, -5}, 10, 64, -5));
        assertFalse(PostArrivalPersistPolicy.targetEqualsDest(null, 10, 64, -5));
    }
}
