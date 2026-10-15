package net.wcfcarolina13.PathFinding.unified;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Per-thread search buffers, cleared (not reallocated) per search (Baritone #5145). A search
 * never escapes its thread, so one instance per worker is enough.
 */
public final class SearchBuffers {
    private static final ThreadLocal<SearchBuffers> LOCAL = ThreadLocal.withInitial(SearchBuffers::new);

    final BinaryHeapOpenSet open = new BinaryHeapOpenSet();
    final Long2ObjectOpenHashMap<SearchNode> nodes = new Long2ObjectOpenHashMap<>(4096);
    final MoveCosts.Refusals refusals = new MoveCosts.Refusals();
    final List<PlannedMove> successors = new ArrayList<>(32);
    boolean inUse;

    public static SearchBuffers get() {
        return LOCAL.get();
    }

    void clear() {
        open.clear();
        nodes.clear();
        refusals.reset();
        successors.clear();
    }
}
