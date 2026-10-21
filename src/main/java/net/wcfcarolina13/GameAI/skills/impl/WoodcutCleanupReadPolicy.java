package net.wcfcarolina13.GameAI.skills.impl;

final class WoodcutCleanupReadPolicy {
    private WoodcutCleanupReadPolicy() {}

    static boolean readsKnown(long missingBefore, long missingAfter) {
        return missingBefore == missingAfter;
    }

    static boolean shouldForgetRemembered(boolean cellKnown, boolean isLog) {
        return cellKnown && !isLog;
    }
}
