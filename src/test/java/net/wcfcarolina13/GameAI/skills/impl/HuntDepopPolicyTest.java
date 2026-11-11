package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuntDepopPolicyTest {

    @Test
    void multiKindHuntShortOfItsTargetMovesOn() {
        assertTrue(HuntDepopPolicy.mayLookForAnotherKind(false, 2, 6));
    }

    @Test
    void namedHuntEnds() {
        assertFalse(HuntDepopPolicy.mayLookForAnotherKind(true, 2, 6));
    }

    @Test
    void huntAtItsTargetEnds() {
        assertFalse(HuntDepopPolicy.mayLookForAnotherKind(false, 6, 6));
        assertFalse(HuntDepopPolicy.mayLookForAnotherKind(false, 7, 6));
    }
}
