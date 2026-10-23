package net.wcfcarolina13.GameAI.services;

/**
 * Pure gates for the idle tool fallbacks in {@link BotIdleHobbiesService}.
 *
 * <p>The idle dispatcher {@code continue}s past every later fallback and the hobby picker when a
 * fallback returns true, so a fallback may only claim the tick when it did something. Before
 * 1.1.274 the wooden fallback returned true while cooling down or backed off, which starved the
 * leather-armor and cobblestone-tools fallbacks for any bot without an axe.
 */
final class IdleFallbackGatePolicy {

    private IdleFallbackGatePolicy() {
    }

    /**
     * True when a wooden-fallback pass should claim the tick: it started a woodcut, pulled supplies
     * from a chest, or crafted a tool. A pass that only armed a cooldown or a backoff reports false.
     */
    static boolean reportsWork(boolean startedWoodcut, boolean moved, boolean crafted) {
        return startedWoodcut || moved || crafted;
    }

    static boolean allowsHobby(boolean enabled, long nowTick, long backoffUntilTick) {
        return enabled && nowTick >= backoffUntilTick;
    }

    /**
     * Every resource woodcut respects the hobby failure backoff. A held axe bypasses only the
     * flat no-axe fallback cooldown; it does not make failed woodcutting viable again.
     */
    static boolean blocksResourcesWoodcut(boolean hasUsableAxe,
                                          long nowTick,
                                          long woodcutBackoffUntilTick,
                                          long woodenFallbackNextTick) {
        return nowTick < woodcutBackoffUntilTick
                || (!hasUsableAxe && nowTick < woodenFallbackNextTick);
    }
}
