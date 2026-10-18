package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the per-scan base verdict memo. No net.minecraft types. */
class BaseVerdictMemoTest {

    @Test
    void computesOncePerKey() {
        BaseVerdictMemo<String> memo = new BaseVerdictMemo<>();
        AtomicInteger calls = new AtomicInteger();
        assertEquals("tree", memo.computeIfAbsent(42L, () -> { calls.incrementAndGet(); return "tree"; }));
        assertEquals("tree", memo.computeIfAbsent(42L, () -> { calls.incrementAndGet(); return "other"; }));
        assertEquals(1, calls.get());
        assertEquals(1, memo.misses());
        assertEquals(1, memo.hits());
    }

    @Test
    void keysAreIndependent() {
        BaseVerdictMemo<Integer> memo = new BaseVerdictMemo<>();
        assertEquals(1, memo.computeIfAbsent(1L, () -> 1));
        assertEquals(2, memo.computeIfAbsent(2L, () -> 2));
        assertEquals(1, memo.computeIfAbsent(1L, () -> 99));
        assertEquals(2, memo.size());
        assertEquals(2, memo.misses());
        assertEquals(1, memo.hits());
    }

    @Test
    void emptyOptionalIsCached() {
        BaseVerdictMemo<Optional<String>> memo = new BaseVerdictMemo<>();
        AtomicInteger calls = new AtomicInteger();
        assertTrue(memo.computeIfAbsent(7L, () -> { calls.incrementAndGet(); return Optional.empty(); }).isEmpty());
        assertTrue(memo.computeIfAbsent(7L, () -> { calls.incrementAndGet(); return Optional.of("x"); }).isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void fiftyEightLookupsOverTenBases() {
        BaseVerdictMemo<Boolean> memo = new BaseVerdictMemo<>();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 58; i++) {
            long base = i % 10;
            memo.computeIfAbsent(base, () -> { calls.incrementAndGet(); return base % 2 == 0; });
        }
        assertEquals(10, calls.get());
        assertEquals(10, memo.misses());
        assertEquals(48, memo.hits());
    }
}
