package net.wcfcarolina13.GameAI.skills.support;

final class TreeCleanupClassifier {

    enum CleanupLogDispositionKind {
        REMEMBERED_LEFTOVER,
        LONE_FLOATING,
        SUSPENDED_FRAGMENT,
        ORPHAN_CLUSTER,
        BLOCKED_PROTECTED,
        BLOCKED_HUMAN_ADJACENT,
        REJECTED_FULL_TREE,
        REJECTED_GROUNDED_OR_NON_FLOATER,
        REJECTED_NON_LOG
    }

    record CleanupLogDisposition(CleanupLogDispositionKind kind, boolean actionable, String reason) {
    }

    private TreeCleanupClassifier() {
    }

    static CleanupLogDisposition classifyFromSignals(boolean rememberedLeftover,
                                                     boolean blockedProtected,
                                                     String protectedReason,
                                                     boolean humanAdjacent,
                                                     boolean fullTree,
                                                     boolean loneFloating,
                                                     int componentSize,
                                                     boolean componentGrounded,
                                                     boolean componentLeavesNearby,
                                                     boolean componentUnsupported,
                                                     boolean componentTooLarge) {
        if (rememberedLeftover) {
            if (blockedProtected) {
                return blocked(CleanupLogDispositionKind.BLOCKED_PROTECTED, protectedReason);
            }
            return actionable(CleanupLogDispositionKind.REMEMBERED_LEFTOVER, "remembered-leftover");
        }
        if (humanAdjacent) {
            return blocked(CleanupLogDispositionKind.BLOCKED_HUMAN_ADJACENT, "human-adjacent");
        }
        if (blockedProtected) {
            return blocked(CleanupLogDispositionKind.BLOCKED_PROTECTED, protectedReason);
        }
        if (fullTree) {
            return rejected(CleanupLogDispositionKind.REJECTED_FULL_TREE, "full-tree");
        }
        if (loneFloating) {
            return actionable(CleanupLogDispositionKind.LONE_FLOATING, "lone-floating-log");
        }
        if (!componentGrounded && componentUnsupported && componentLeavesNearby) {
            return actionable(CleanupLogDispositionKind.SUSPENDED_FRAGMENT, "suspended-fragment");
        }
        if (!componentGrounded && !componentTooLarge && (componentLeavesNearby || componentSize <= 4)) {
            return actionable(CleanupLogDispositionKind.ORPHAN_CLUSTER, "orphan-cluster");
        }
        return rejected(CleanupLogDispositionKind.REJECTED_GROUNDED_OR_NON_FLOATER, "grounded-or-non-floater");
    }

    private static CleanupLogDisposition actionable(CleanupLogDispositionKind kind, String reason) {
        return new CleanupLogDisposition(kind, true, reason);
    }

    private static CleanupLogDisposition blocked(CleanupLogDispositionKind kind, String reason) {
        return new CleanupLogDisposition(kind, false, reason);
    }

    private static CleanupLogDisposition rejected(CleanupLogDispositionKind kind, String reason) {
        return new CleanupLogDisposition(kind, false, reason);
    }
}
