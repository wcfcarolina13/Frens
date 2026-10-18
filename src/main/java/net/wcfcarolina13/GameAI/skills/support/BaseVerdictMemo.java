package net.wcfcarolina13.GameAI.skills.support;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One scan's cache of a per-trunk-base verdict, keyed on the packed base position, with hit and
 * miss counts for the scan's log line.
 *
 * <p>1.1.268 field (finding 4): the woodcut detect scan rebuilt the same tree (neighbour-trunk box,
 * canopy flood fill) for every log of one trunk, each block read a server round trip. A verdict
 * that is a function of the trunk base alone is computed once per base per scan.
 *
 * <p>A plain {@link HashMap}, unlike {@code TreeDetector.CleanupScanMemo}'s concurrent maps: one
 * memo lives for one scan on one skill thread and is never shared or kept across scans. A cached
 * {@code null} or empty value is a real answer and counts as a hit.
 *
 * <p>No Minecraft types.
 */
public final class BaseVerdictMemo<V> {

    private final Map<Long, V> verdicts = new HashMap<>();
    private int hits;
    private int misses;

    public V computeIfAbsent(long baseKey, Supplier<V> compute) {
        if (verdicts.containsKey(baseKey)) {
            hits++;
            return verdicts.get(baseKey);
        }
        misses++;
        V value = compute.get();
        verdicts.put(baseKey, value);
        return value;
    }

    public int hits() {
        return hits;
    }

    /** Verdicts computed, one per distinct base. */
    public int misses() {
        return misses;
    }

    public int size() {
        return verdicts.size();
    }
}
