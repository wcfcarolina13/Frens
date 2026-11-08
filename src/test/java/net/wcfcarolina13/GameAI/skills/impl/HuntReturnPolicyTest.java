package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuntReturnPolicyTest {

    @Test
    void nearBaseWalksAndFarBaseStaysUnlessTeleportIsOn() {
        assertEquals(HuntReturnPolicy.Decision.WALK, HuntReturnPolicy.decide(40, false));
        assertEquals(HuntReturnPolicy.Decision.WALK, HuntReturnPolicy.decide(96, false));
        assertEquals(HuntReturnPolicy.Decision.TOO_FAR, HuntReturnPolicy.decide(97, false));
        assertEquals(HuntReturnPolicy.Decision.TOO_FAR, HuntReturnPolicy.decide(1340, false));
        assertEquals(HuntReturnPolicy.Decision.TELEPORT, HuntReturnPolicy.decide(1340, true));
        assertEquals(HuntReturnPolicy.Decision.TELEPORT, HuntReturnPolicy.decide(40, true));
    }

    @Test
    void theLinesSayWhyAndCarryNoDashesOrSemicolons() {
        String far = HuntReturnPolicy.tooFarLine(1340);
        assertTrue(far.contains("1340 blocks away"));
        String lost = HuntReturnPolicy.couldNotReturnLine(200);
        for (String line : new String[] { far, lost }) {
            assertFalse(line.contains("\u2014") || line.contains(";"), line);
        }
    }
}
