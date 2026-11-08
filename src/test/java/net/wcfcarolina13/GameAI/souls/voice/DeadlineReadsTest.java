package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link DeadlineReads} with fake streams and a fake clock: every sleep advances the clock
 * by the poll interval, so a deadline of N ms allows exactly N / POLL_MS polls.
 */
class DeadlineReadsTest {

    /** A pipe whose bytes arrive in scripted chunks, one chunk per poll (like a slow child). */
    private static final class ScriptedPipe extends InputStream {
        private final List<byte[]> chunks = new ArrayList<>();
        private byte[] current = new byte[0];
        private int pos;
        private boolean closed;

        ScriptedPipe chunk(String s) {
            chunks.add(s.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        /** Called by the fake sleeper: the next chunk becomes readable. */
        void tick() {
            if (pos >= current.length && !chunks.isEmpty()) {
                current = chunks.remove(0);
                pos = 0;
            }
        }

        void closeNow() {
            closed = true;
        }

        @Override
        public int available() {
            return closed && pos >= current.length ? 1 : current.length - pos;
        }

        @Override
        public int read() {
            if (pos < current.length) {
                return current[pos++] & 0xFF;
            }
            return closed ? -1 : 0;
        }
    }

    private static final class FakeClock {
        final AtomicLong now = new AtomicLong();
        final AtomicInteger sleeps = new AtomicInteger();

        DeadlineReads.Sleeper sleeper(Runnable onSleep) {
            return ms -> {
                sleeps.incrementAndGet();
                now.addAndGet(ms);
                onSleep.run();
            };
        }
    }

    @Test
    void completeLineIsReturnedWithoutCarriageReturnAndBlankLinesAreSkipped() throws Exception {
        InputStream in = new ByteArrayInputStream("\n   \n/tmp/out/1.wav\r\nnext\n".getBytes(StandardCharsets.UTF_8));
        FakeClock clock = new FakeClock();
        String line = DeadlineReads.readLine(in, 1_000, () -> true, clock.now::get, clock.sleeper(() -> { }));
        assertEquals("/tmp/out/1.wav", line);
        assertEquals(0, clock.sleeps.get(), "a buffered line needs no polling");
    }

    @Test
    void partialLineThatNeverCompletesTimesOutInsteadOfBlocking() {
        ScriptedPipe pipe = new ScriptedPipe().chunk("/tmp/half-writ");
        FakeClock clock = new FakeClock();
        TimeoutException ex = assertThrows(TimeoutException.class, () -> DeadlineReads.readLine(
                pipe, 100, () -> true, clock.now::get, clock.sleeper(pipe::tick)));
        assertTrue(ex.getMessage().contains("deadline"));
        // 100 ms deadline at a 20 ms poll: the clock crossed the deadline after five sleeps.
        assertEquals(5, clock.sleeps.get());
    }

    @Test
    void lineArrivingInPiecesAcrossPollsIsAssembled() throws Exception {
        ScriptedPipe pipe = new ScriptedPipe().chunk("/tmp/").chunk("piece").chunk("d.wav\n");
        FakeClock clock = new FakeClock();
        String line = DeadlineReads.readLine(pipe, 1_000, () -> true, clock.now::get, clock.sleeper(pipe::tick));
        assertEquals("/tmp/pieced.wav", line);
        assertTrue(clock.sleeps.get() >= 2);
    }

    @Test
    void exitedProducerWithAnEmptyPipeFailsFastAsAnIoError() {
        ScriptedPipe pipe = new ScriptedPipe();
        FakeClock clock = new FakeClock();
        IOException ex = assertThrows(IOException.class, () -> DeadlineReads.readLine(
                pipe, 10_000, () -> false, clock.now::get, clock.sleeper(pipe::tick)));
        assertTrue(ex.getMessage().contains("exited"));
        assertEquals(0, clock.sleeps.get(), "no waiting out the deadline for a dead child");
    }

    @Test
    void streamEndIsAnIoErrorNotATimeout() {
        ScriptedPipe pipe = new ScriptedPipe().chunk("abc");
        pipe.closeNow();
        FakeClock clock = new FakeClock();
        assertThrows(IOException.class, () -> DeadlineReads.readLine(
                pipe, 10_000, () -> true, clock.now::get, clock.sleeper(pipe::tick)));
    }

    @Test
    void utf8PathsSurviveByteWiseAssembly() throws Exception {
        InputStream in = new ByteArrayInputStream("/Users/Jörg/Übung.wav\n".getBytes(StandardCharsets.UTF_8));
        FakeClock clock = new FakeClock();
        assertEquals("/Users/Jörg/Übung.wav",
                DeadlineReads.readLine(in, 100, () -> true, clock.now::get, clock.sleeper(() -> { })));
    }

    @Test
    void awaitProgressReturnsTheFirstNonZeroResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FakeClock clock = new FakeClock();
        int n = DeadlineReads.awaitProgress(() -> calls.incrementAndGet() >= 3 ? 12 : 0,
                clock.now.get() + 1_000, clock.now::get, clock.sleeper(() -> { }));
        assertEquals(12, n);
        assertEquals(3, calls.get());
        assertEquals(2, clock.sleeps.get());
    }

    @Test
    void awaitProgressTreatsEofAsProgressAndTimesOutOnSilence() throws Exception {
        FakeClock clock = new FakeClock();
        assertEquals(-1, DeadlineReads.awaitProgress(() -> -1, 1_000, clock.now::get, clock.sleeper(() -> { })));
        assertThrows(TimeoutException.class, () -> DeadlineReads.awaitProgress(
                () -> 0, clock.now.get() + 60, clock.now::get, clock.sleeper(() -> { })));
        assertEquals(3, clock.sleeps.get(), "60 ms at a 20 ms poll is three sleeps");
    }

    @Test
    void awaitProgressPropagatesIoErrorsFromTheAttempt() {
        FakeClock clock = new FakeClock();
        assertThrows(IOException.class, () -> DeadlineReads.awaitProgress(() -> {
            throw new IOException("reset");
        }, 1_000, clock.now::get, clock.sleeper(() -> { })));
    }
}
