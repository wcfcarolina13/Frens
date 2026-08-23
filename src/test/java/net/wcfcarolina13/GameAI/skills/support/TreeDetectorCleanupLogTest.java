package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeDetectorCleanupLogTest {

    @Test
    void loneUnprotectedLeaflessLogIsActionable() {
        TreeCleanupClassifier.CleanupLogDisposition disposition = TreeCleanupClassifier.classifyFromSignals(
                false,
                false,
                "protected-generic",
                false,
                false,
                true,
                1,
                false,
                false,
                false,
                false
        );

        assertTrue(disposition.actionable());
        assertEquals(TreeCleanupClassifier.CleanupLogDispositionKind.LONE_FLOATING, disposition.kind());
    }

    @Test
    void humanAdjacentLogIsBlocked() {
        TreeCleanupClassifier.CleanupLogDisposition disposition = TreeCleanupClassifier.classifyFromSignals(
                false,
                false,
                "protected-generic",
                true,
                false,
                false,
                1,
                false,
                false,
                false,
                false
        );

        assertEquals(TreeCleanupClassifier.CleanupLogDispositionKind.BLOCKED_HUMAN_ADJACENT, disposition.kind());
    }

    @Test
    void protectedLogIsBlocked() {
        TreeCleanupClassifier.CleanupLogDisposition disposition = TreeCleanupClassifier.classifyFromSignals(
                false,
                true,
                "protected-generic",
                false,
                false,
                false,
                1,
                false,
                false,
                false,
                false
        );

        assertEquals(TreeCleanupClassifier.CleanupLogDispositionKind.BLOCKED_PROTECTED, disposition.kind());
    }

    @Test
    void intactTreeIsRejectedAsFullTree() {
        TreeCleanupClassifier.CleanupLogDisposition disposition = TreeCleanupClassifier.classifyFromSignals(
                false,
                false,
                "protected-generic",
                false,
                true,
                false,
                6,
                true,
                true,
                false,
                false
        );

        assertEquals(TreeCleanupClassifier.CleanupLogDispositionKind.REJECTED_FULL_TREE, disposition.kind());
    }

    @Test
    void suspendedBranchClusterWithLeavesIsActionable() {
        TreeCleanupClassifier.CleanupLogDisposition disposition = TreeCleanupClassifier.classifyFromSignals(
                false,
                false,
                "protected-generic",
                false,
                false,
                false,
                2,
                false,
                true,
                true,
                false
        );

        assertTrue(disposition.actionable());
        assertEquals(TreeCleanupClassifier.CleanupLogDispositionKind.SUSPENDED_FRAGMENT, disposition.kind());
    }

    @Test
    void smallOrphanClusterWithoutGroundSupportIsActionable() {
        TreeCleanupClassifier.CleanupLogDisposition disposition = TreeCleanupClassifier.classifyFromSignals(
                false,
                false,
                "protected-generic",
                false,
                false,
                false,
                2,
                false,
                false,
                false,
                false
        );

        assertTrue(disposition.actionable());
        assertEquals(TreeCleanupClassifier.CleanupLogDispositionKind.ORPHAN_CLUSTER, disposition.kind());
    }

    @Test
    void groundedMultiLogClusterWithoutLeavesIsRejected() {
        TreeCleanupClassifier.CleanupLogDisposition disposition = TreeCleanupClassifier.classifyFromSignals(
                false,
                false,
                "protected-generic",
                false,
                false,
                false,
                2,
                true,
                false,
                false,
                false
        );

        assertEquals(TreeCleanupClassifier.CleanupLogDispositionKind.REJECTED_GROUNDED_OR_NON_FLOATER, disposition.kind());
    }

    @Test
    void rememberedLeftoverRemainsActionableWhenPresent() {
        TreeCleanupClassifier.CleanupLogDisposition disposition = TreeCleanupClassifier.classifyFromSignals(
                true,
                false,
                "protected-woodcut:none",
                false,
                false,
                false,
                0,
                false,
                false,
                false,
                false
        );

        assertTrue(disposition.actionable());
        assertEquals(TreeCleanupClassifier.CleanupLogDispositionKind.REMEMBERED_LEFTOVER, disposition.kind());
    }
}
