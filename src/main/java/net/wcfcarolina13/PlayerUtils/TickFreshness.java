package net.wcfcarolina13.PlayerUtils;

/**
 * Pure staleness arithmetic for "remembered event" maps keyed by a server tick.
 *
 * <p>Lives outside the service classes on purpose: services touch {@code net.minecraft.*} statics
 * that can't be class-initialized under the JUnit suite, so the predicate is tested here instead.
 */
public final class TickFreshness {

    private TickFreshness() {
    }

    /**
     * @return true iff {@code recordedTick} is a real recording (&gt; 0) and lies within
     *         {@code windowTicks} at or before {@code nowTick}. A tick recorded after
     *         {@code nowTick} (world reload / tick counter reset) is treated as stale.
     */
    public static boolean isFresh(long recordedTick, long nowTick, long windowTicks) {
        if (recordedTick <= 0L) {
            return false;
        }
        long age = nowTick - recordedTick;
        return age >= 0L && age <= Math.max(1L, windowTicks);
    }
}
