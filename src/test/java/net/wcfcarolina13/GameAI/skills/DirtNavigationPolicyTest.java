package net.wcfcarolina13.GameAI.skills;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirtNavigationPolicyTest {

    @AfterEach
    void reset() {
        DirtNavigationPolicy.clearForTests();
    }

    @Test
    void unknownOffsetScoresHalf() {
        assertEquals(0.5, DirtNavigationPolicy.scoreOffset(3, 0, -2), 1e-9);
    }

    @Test
    void laplaceSmoothedSuccessRate() {
        DirtNavigationPolicy.recordOffset(1, 0, 0, true);
        DirtNavigationPolicy.recordOffset(1, 0, 0, true);
        DirtNavigationPolicy.recordOffset(1, 0, 0, false);
        // (2 + 1) / (3 + 2)
        assertEquals(0.6, DirtNavigationPolicy.scoreOffset(1, 0, 0), 1e-9);
        assertEquals(0.5, DirtNavigationPolicy.scoreOffset(-1, 0, 0), 1e-9, "offsets are separate");
    }

    @Test
    void concurrentRecordsAreNotLost() throws InterruptedException {
        int threads = 8;
        int perThread = 5_000;
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread w = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    DirtNavigationPolicy.recordOffset(0, 1, 0, false);
                }
            });
            workers.add(w);
            w.start();
        }
        for (Thread w : workers) {
            w.join();
        }
        int failures = threads * perThread;
        // All failures counted: (0 + 1) / (failures + 2).
        assertEquals(1.0 / (failures + 2.0), DirtNavigationPolicy.scoreOffset(0, 1, 0), 1e-12);
    }
}
