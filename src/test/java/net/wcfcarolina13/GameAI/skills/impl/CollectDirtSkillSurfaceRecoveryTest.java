package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectDirtSkillSurfaceRecoveryTest {

    @Test
    void stalledAscentAbortsOnlyWhenThresholdReached() {
        assertFalse(CollectDirtSkill.shouldAbortStalledAscent(2, 3));
        assertTrue(CollectDirtSkill.shouldAbortStalledAscent(3, 3));
    }

    @Test
    void nullThresholdDoesNotAbortStalledAscent() {
        assertFalse(CollectDirtSkill.shouldAbortStalledAscent(99, null));
    }
}
