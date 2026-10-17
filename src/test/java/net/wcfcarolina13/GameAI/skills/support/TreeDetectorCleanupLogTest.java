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
    // ── review I2 (d): lazy signals and the component memo give the same disposition ─────────

    private static final int[] SIZES = {1, 3, 4, 5, 40, 65};

    private static boolean b(int bits, int bit) {
        return ((bits >> bit) & 1) == 1;
    }

    @Test
    void aSkippedComponentProfileNeverChangesTheDisposition() {
        for (int sig = 0; sig < 16; sig++) {
            boolean human = b(sig, 0), prot = b(sig, 1), full = b(sig, 2), lone = b(sig, 3);
            if (TreeCleanupClassifier.needsComponentProfile(human, prot, full, lone)) {
                continue;
            }
            TreeCleanupClassifier.CleanupLogDisposition unread = TreeCleanupClassifier.classifyFromSignals(
                    false, prot, "protected-x", human, full, lone, 0, false, false, false, false);
            for (int size : SIZES) {
                for (int prof = 0; prof < 16; prof++) {
                    TreeCleanupClassifier.CleanupLogDisposition real = TreeCleanupClassifier.classifyFromSignals(
                            false, prot, "protected-x", human, full, lone,
                            size, b(prof, 0), b(prof, 1), b(prof, 2), b(prof, 3));
                    assertEquals(unread, real, "signals " + sig + " profile " + size + "/" + prof);
                }
            }
        }
    }

    @Test
    void aSkippedFullTreeCheckNeverChangesTheDisposition() {
        for (int sig = 0; sig < 4; sig++) {
            boolean human = b(sig, 0), prot = b(sig, 1);
            if (TreeCleanupClassifier.needsFullTreeCheck(human, prot)) {
                continue;
            }
            TreeCleanupClassifier.CleanupLogDisposition unread = TreeCleanupClassifier.classifyFromSignals(
                    false, prot, "protected-x", human, false, false, 0, false, false, false, false);
            for (int rest = 0; rest < 4; rest++) {
                for (int size : SIZES) {
                    for (int prof = 0; prof < 16; prof++) {
                        TreeCleanupClassifier.CleanupLogDisposition real = TreeCleanupClassifier.classifyFromSignals(
                                false, prot, "protected-x", human, b(rest, 0), b(rest, 1),
                                size, b(prof, 0), b(prof, 1), b(prof, 2), b(prof, 3));
                        assertEquals(unread, real);
                    }
                }
            }
        }
    }

    @Test
    void profileRejectsIsTheVerdictOfEveryLogThatReachesTheComponentStep() {
        for (int size : SIZES) {
            for (int prof = 0; prof < 16; prof++) {
                boolean rejects = TreeCleanupClassifier.profileRejects(size, b(prof, 0), b(prof, 1), b(prof, 2), b(prof, 3));
                TreeCleanupClassifier.CleanupLogDisposition d = TreeCleanupClassifier.classifyFromSignals(
                        false, false, "protected-x", false, false, false,
                        size, b(prof, 0), b(prof, 1), b(prof, 2), b(prof, 3));
                assertEquals(rejects, !d.actionable(), size + "/" + prof);
                if (rejects) {
                    assertEquals(TreeCleanupClassifier.CleanupLogDispositionKind.REJECTED_GROUNDED_OR_NON_FLOATER, d.kind());
                }
            }
        }
    }
}
