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

    /**
     * True when the resources woodcut must not start: the bot has no usable axe and the wooden
     * fallback is still waiting, either inside its per-hobby failure backoff or its flat cooldown.
     * A woodcut without an axe exits at once with "I have no axe", so starting one here would
     * bypass the backoff that stops the cd4120ca restart storm.
     */
    static boolean blocksResourcesWoodcut(boolean hasUsableAxe,
                                          long nowTick,
                                          long woodcutBackoffUntilTick,
                                          long woodenFallbackNextTick) {
        if (hasUsableAxe) {
            return false;
        }
        return nowTick < woodcutBackoffUntilTick || nowTick < woodenFallbackNextTick;
    }
}
