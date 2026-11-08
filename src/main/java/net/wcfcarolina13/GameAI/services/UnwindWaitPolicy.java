package net.wcfcarolina13.GameAI.services;

/**
 * When a bot's new task waits for its stopped task's worker to exit (ruling 2026-11-08: "New task
 * waits, up to 2 s"). The new task waits until the stopped task's ticket completes, not until its
 * thread exits, because skill workers are pooled threads that outlive their task. The wait happens on the new worker's own thread, never on the server thread,
 * so /bot stop stays instant and the tick loop never blocks. No Minecraft imports.
 */
public final class UnwindWaitPolicy {

    /** The most a new task waits for the old worker. */
    public static final long WAIT_MS = 2000L;

    private UnwindWaitPolicy() {
    }

    /**
     * @param previousIsCurrent the old ticket ran on this same thread (a pooled worker reused for its next task)
     * @param previousAlive     the old ticket has not completed yet
     * @param onServerThread    the caller is the server thread
     */
    public static boolean shouldWait(boolean previousIsCurrent, boolean previousAlive, boolean onServerThread) {
        return !previousIsCurrent && previousAlive && !onServerThread;
    }
}
