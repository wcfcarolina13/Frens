package net.wcfcarolina13.GameAI.services;

/**
 * Per-thread flag for "an inventory offload is placing a chest". Placing the chest can craft one,
 * the craft can need room, and making room offloads again: without this, a bot with a full pack,
 * no chest and no crafting table recursed until StackOverflowError (seen headless 2026-11-07, on
 * main too). Only the outermost offload may place a chest.
 */
final class OffloadChestGuard {
    private static final ThreadLocal<Boolean> PLACING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private OffloadChestGuard() {
    }

    /** True if this thread may place an offload chest now; the caller must then call {@link #exit()}. */
    static boolean tryEnter() {
        if (PLACING.get()) {
            return false;
        }
        PLACING.set(Boolean.TRUE);
        return true;
    }

    static void exit() {
        PLACING.remove();
    }

    static boolean active() {
        return PLACING.get();
    }
}
