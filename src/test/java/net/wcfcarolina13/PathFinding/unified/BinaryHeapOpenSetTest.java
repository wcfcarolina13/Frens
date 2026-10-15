package net.wcfcarolina13.PathFinding.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** heap_fuzz: 10k random insert / decrease-key / pop against a PriorityQueue oracle (mineflayer #369). */
class BinaryHeapOpenSetTest {

    @Test
    void matchesPriorityQueueOracleOnRandomOps() {
        for (long seed = 1; seed <= 5; seed++) {
            fuzz(seed, 10_000);
        }
    }

    private static void fuzz(long seed, int ops) {
        Random rnd = new Random(seed);
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        PriorityQueue<SearchNode> oracle = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        List<SearchNode> open = new ArrayList<>();
        int id = 0;
        for (int i = 0; i < ops; i++) {
            int op = rnd.nextInt(10);
            if (op < 5 || open.isEmpty()) {
                SearchNode n = new SearchNode(id, 0, 0, 0, id);
                id++;
                n.f = rnd.nextInt(500) + rnd.nextDouble();
                heap.insert(n);
                oracle.add(n);
                open.add(n);
            } else if (op < 8) {
                SearchNode n = open.get(rnd.nextInt(open.size()));
                oracle.remove(n);
                n.f -= rnd.nextInt(50) + rnd.nextDouble();
                heap.decreaseKey(n);
                oracle.add(n);
            } else {
                SearchNode popped = heap.removeLowest();
                // Ties may pop a different node than the oracle; the priority must match.
                assertEquals(oracle.peek().f, popped.f, 0.0, "seed " + seed + " op " + i);
                assertTrue(oracle.remove(popped));
                assertTrue(open.remove(popped));
                assertEquals(-1, popped.heapIndex);
            }
            assertEquals(oracle.size(), heap.size());
        }
        while (!heap.isEmpty()) {
            SearchNode popped = heap.removeLowest();
            assertEquals(oracle.peek().f, popped.f, 0.0);
            assertTrue(oracle.remove(popped));
        }
        assertTrue(oracle.isEmpty());
    }

    @Test
    void decreaseKeyMovesNodeToFront() {
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        SearchNode a = node(10);
        SearchNode b = node(20);
        SearchNode c = node(30);
        heap.insert(a);
        heap.insert(b);
        heap.insert(c);
        c.f = 1;
        heap.decreaseKey(c);
        assertSame(c, heap.removeLowest());
        assertSame(a, heap.removeLowest());
        assertSame(b, heap.removeLowest());
        assertTrue(heap.isEmpty());
    }

    @Test
    void growsPastInitialCapacityAndClears() {
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        List<SearchNode> nodes = new ArrayList<>();
        for (int i = 0; i < BinaryHeapOpenSet.INITIAL_CAPACITY * 3; i++) {
            SearchNode n = node(BinaryHeapOpenSet.INITIAL_CAPACITY * 3 - i);
            nodes.add(n);
            heap.insert(n);
        }
        assertEquals(1.0, heap.removeLowest().f);
        heap.clear();
        assertTrue(heap.isEmpty());
        for (SearchNode n : nodes) {
            assertEquals(-1, n.heapIndex);
        }
    }

    @Test
    void misuseFailsLoudly() {
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        SearchNode a = node(1);
        assertThrows(IllegalStateException.class, () -> heap.decreaseKey(a));
        assertThrows(IllegalStateException.class, heap::removeLowest);
        heap.insert(a);
        assertThrows(IllegalStateException.class, () -> heap.insert(a));
    }

    private static SearchNode node(double f) {
        SearchNode n = new SearchNode(0, 0, 0, 0, 0);
        n.f = f;
        return n;
    }
}
