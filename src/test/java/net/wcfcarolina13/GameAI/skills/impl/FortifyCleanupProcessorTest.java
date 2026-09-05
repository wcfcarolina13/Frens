package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FortifyCleanupProcessorTest {

    @Test
    void nullOrBlankReasonIsOther() {
        assertEquals(ReplaceFailureKind.OTHER, FortifyCleanupProcessor.classifyReplaceFailureKind(null));
        assertEquals(ReplaceFailureKind.OTHER, FortifyCleanupProcessor.classifyReplaceFailureKind(""));
        assertEquals(ReplaceFailureKind.OTHER, FortifyCleanupProcessor.classifyReplaceFailureKind("   "));
    }

    @Test
    void knownPrefixesMapToKinds() {
        assertEquals(ReplaceFailureKind.BOT_OCCUPIES,
                FortifyCleanupProcessor.classifyReplaceFailureKind("bot-intersects-target:1,2,3"));
        assertEquals(ReplaceFailureKind.OUT_OF_REACH,
                FortifyCleanupProcessor.classifyReplaceFailureKind("out-of-reach"));
        assertEquals(ReplaceFailureKind.LOS_BLOCKED,
                FortifyCleanupProcessor.classifyReplaceFailureKind("no-line-of-sight"));
        assertEquals(ReplaceFailureKind.NO_MATERIAL,
                FortifyCleanupProcessor.classifyReplaceFailureKind("no-block-item-available"));
    }

    @Test
    void unknownReasonIsOther() {
        assertEquals(ReplaceFailureKind.OTHER, FortifyCleanupProcessor.classifyReplaceFailureKind("something-else"));
        // prefix match only: a suffix occurrence does not classify
        assertEquals(ReplaceFailureKind.OTHER, FortifyCleanupProcessor.classifyReplaceFailureKind("x out-of-reach"));
    }
}
