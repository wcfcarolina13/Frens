package net.wcfcarolina13.PathFinding.unified;

import java.util.Arrays;

/**
 * Array binary min-heap over {@link SearchNode#f} with decrease-key through
 * {@link SearchNode#heapIndex} (no {@code PriorityQueue.remove} re-heaps). 1-indexed.
 * {@code BinaryHeapOpenSetTest} fuzzes it against a {@code PriorityQueue} oracle (mineflayer #369).
 */
public final class BinaryHeapOpenSet {
    public static final int INITIAL_CAPACITY = 1024;

    private SearchNode[] heap = new SearchNode[INITIAL_CAPACITY];
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        for (int i = 1; i <= size; i++) {
            heap[i].heapIndex = -1;
            heap[i] = null;
        }
        size = 0;
    }

    public void insert(SearchNode node) {
        if (node.heapIndex >= 0) {
            throw new IllegalStateException("node already in heap");
        }
        if (++size >= heap.length) {
            heap = Arrays.copyOf(heap, heap.length << 1);
        }
        heap[size] = node;
        node.heapIndex = size;
        siftUp(size);
    }

    /** Call after lowering {@code node.f}. */
    public void decreaseKey(SearchNode node) {
        if (node.heapIndex < 1 || node.heapIndex > size || heap[node.heapIndex] != node) {
            throw new IllegalStateException("node not in heap");
        }
        siftUp(node.heapIndex);
    }

    public SearchNode peekLowest() {
        return size == 0 ? null : heap[1];
    }

    public SearchNode removeLowest() {
        if (size == 0) {
            throw new IllegalStateException("empty heap");
        }
        SearchNode result = heap[1];
        SearchNode last = heap[size];
        heap[size] = null;
        size--;
        result.heapIndex = -1;
        if (size > 0) {
            heap[1] = last;
            last.heapIndex = 1;
            siftDown(1);
        }
        return result;
    }

    private void siftUp(int index) {
        SearchNode node = heap[index];
        double f = node.f;
        while (index > 1) {
            int parent = index >>> 1;
            SearchNode p = heap[parent];
            if (p.f <= f) {
                break;
            }
            heap[index] = p;
            p.heapIndex = index;
            index = parent;
        }
        heap[index] = node;
        node.heapIndex = index;
    }

    private void siftDown(int index) {
        SearchNode node = heap[index];
        double f = node.f;
        while (true) {
            int child = index << 1;
            if (child > size) {
                break;
            }
            if (child < size && heap[child + 1].f < heap[child].f) {
                child++;
            }
            SearchNode c = heap[child];
            if (c.f >= f) {
                break;
            }
            heap[index] = c;
            c.heapIndex = index;
            index = child;
        }
        heap[index] = node;
        node.heapIndex = index;
    }
}
