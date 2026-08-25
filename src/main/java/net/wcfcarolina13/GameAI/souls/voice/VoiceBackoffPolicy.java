package net.wcfcarolina13.GameAI.souls.voice;

/**
 * Engine restart ladder: 1s -> 5s -> 15s, then self-disable (-1) on the 4th failure inside a
 * 5-minute window. Success, or a failure after the window, resets the ladder. Plain values
 * only; the caller supplies time.
 */
public final class VoiceBackoffPolicy {

    private static final long WINDOW_MS = 300_000L;
    private static final long[] DELAYS_MS = {1_000L, 5_000L, 15_000L};

    private int failuresInWindow;
    private long windowStartMs;

    public synchronized long onFailure(long nowMs) {
        if (failuresInWindow == 0 || nowMs - windowStartMs > WINDOW_MS) {
            failuresInWindow = 0;
            windowStartMs = nowMs;
        }
        failuresInWindow++;
        if (failuresInWindow > DELAYS_MS.length) {
            return -1L;
        }
        return DELAYS_MS[failuresInWindow - 1];
    }

    public synchronized void onSuccess() {
        failuresInWindow = 0;
    }
}
