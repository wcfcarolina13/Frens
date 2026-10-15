package net.wcfcarolina13.GameAI.skills.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One woodcut run's memory of blocks the protection check or the miner refused, so a refused
 * block is logged once and skipped afterwards. Keyed by the block AND the tree it was mined for:
 * a leaf's protection depends on its target tree's base, so a leaf refused while clearing toward
 * a Spawn tree is tried again for a tree outside spawn. Pure: positions are packed longs
 * ({@code BlockPos.asLong()}); a missing target tree is its own key.
 */
public final class WoodcutRefusedTargets {

    private record Key(long pos, boolean hasTarget, long target) {
    }

    private final Map<Key, String> refused = new ConcurrentHashMap<>();

    private static Key key(long pos, Long targetBase) {
        return targetBase == null ? new Key(pos, false, 0L) : new Key(pos, true, targetBase);
    }

    /** The remembered refusal reason, or null when this block was not refused for this target tree. */
    public String get(long pos, Long targetBase) {
        return refused.get(key(pos, targetBase));
    }

    /** Remembers the first refusal for this block and target tree; a later reason never overwrites it. */
    public void remember(long pos, Long targetBase, String reason) {
        refused.putIfAbsent(key(pos, targetBase), reason == null ? "protected" : reason);
    }

    public int size() {
        return refused.size();
    }
}
