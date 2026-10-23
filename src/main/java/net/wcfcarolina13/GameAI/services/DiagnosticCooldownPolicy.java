package net.wcfcarolina13.GameAI.services;

/** Missing timestamps allow the first diagnostic without sentinel subtraction overflow. */
public final class DiagnosticCooldownPolicy {
    private DiagnosticCooldownPolicy() { }

    public static boolean canLog(Long lastTick, long nowTick, long cooldownTicks) {
        return lastTick == null || nowTick < lastTick
                || nowTick - lastTick >= Math.max(0L, cooldownTicks);
    }
}
