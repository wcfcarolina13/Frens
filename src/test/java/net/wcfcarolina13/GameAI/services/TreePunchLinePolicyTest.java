package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreePunchLinePolicyTest {

    @Test
    void aBotWithNoAxeBreakingALogMayNarrate() {
        assertTrue(TreePunchLinePolicy.shouldNarrate(true, false));
    }

    @Test
    void aBotWithAnAxeNeverSaysPunchSomeTrees() {
        assertFalse(TreePunchLinePolicy.shouldNarrate(true, true));
    }

    @Test
    void nonLogBlocksNeverNarrate() {
        assertFalse(TreePunchLinePolicy.shouldNarrate(false, false));
        assertFalse(TreePunchLinePolicy.shouldNarrate(false, true));
    }
}
