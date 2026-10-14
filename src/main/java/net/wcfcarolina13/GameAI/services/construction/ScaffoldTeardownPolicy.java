package net.wcfcarolina13.GameAI.services.construction;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure rules for what a scaffold teardown may forget (no Minecraft types).
 *
 * <p>A survival-speed break takes seconds per block, so a stop mid-teardown is common. The memory
 * used to be cleared after every teardown, so a {@code /bot stop} during Fortify cleanup of a
 * 12-block pillar removed one block and forgot the other eleven for good. Now only positions known
 * to be gone leave the memory.
 */
public final class ScaffoldTeardownPolicy {

    /** What the server-thread pre-check found at one tracked position. */
    public enum Precheck {
        /** Already air. */
        GONE,
        /** Something that is not a scaffold block stands there now (built over, replaced). */
        NOT_SCAFFOLD,
        /** Still scaffold, but out of reach or refused by protection this time. */
        SKIP,
        /** Still scaffold and allowed: break it. */
        BREAK
    }

    private ScaffoldTeardownPolicy() {
    }

    /** Whether the position leaves the memory without a break. */
    public static boolean isGoneWithoutBreaking(Precheck precheck) {
        return precheck == Precheck.GONE || precheck == Precheck.NOT_SCAFFOLD;
    }

    /**
     * The tracked positions to forget after a teardown: those now gone, plus those kept because
     * they became part of the structure (a later full teardown must never break them). Everything
     * else stays tracked.
     */
    public static <T> Set<T> toForget(Collection<T> tracked, Collection<T> gone, Collection<T> keptAsStructure) {
        Set<T> out = new HashSet<>();
        if (tracked == null) {
            return out;
        }
        for (T pos : tracked) {
            if ((gone != null && gone.contains(pos)) || (keptAsStructure != null && keptAsStructure.contains(pos))) {
                out.add(pos);
            }
        }
        return out;
    }
}
