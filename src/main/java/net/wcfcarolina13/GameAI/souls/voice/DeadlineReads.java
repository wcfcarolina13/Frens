package net.wcfcarolina13.GameAI.souls.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Deadline-bounded reads for the voice engines. A child's stdout pipe and a UNIX socket both
 * offer only blocking reads that ignore interrupts, so a partial line or a server that accepts
 * and never answers would wedge the single engine thread past every timeout. These helpers
 * poll instead: {@code available()} on a pipe, a caller-supplied non-blocking attempt on a
 * channel, and give up at the deadline with a {@link TimeoutException}.
 *
 * <p>Pure apart from the stream handed in: the clock and the sleeper are injectable so tests
 * drive them with a fake stream and a fake clock. Production callers use the overloads that
 * take a timeout and wall-clock time.
 */
final class DeadlineReads {

    /** Poll interval between attempts, matching the old engine loops. */
    static final long POLL_MS = 20L;

    /** Sleeps for the given milliseconds (injectable for tests). */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** One non-blocking read attempt: positive for progress, 0 for nothing yet, negative for EOF. */
    interface ReadAttempt {
        int read() throws IOException;
    }

    private static final Sleeper REAL_SLEEPER = Thread::sleep;
    private static final LongSupplier REAL_CLOCK = System::currentTimeMillis;

    private DeadlineReads() {
    }

    /**
     * Reads one non-blank line of UTF-8 text from {@code in} within {@code timeoutMs}, byte by
     * byte through {@code available()} so a half-written line can never block past the deadline.
     * A trailing carriage return is dropped. Blank lines are skipped.
     *
     * @param producerAlive false once the writing process has exited, so an exited child with an
     *                      empty pipe fails fast instead of waiting out the deadline
     * @throws IOException      when the stream ends or the producer exits before a line arrives
     * @throws TimeoutException when the deadline passes first
     */
    static String readLine(InputStream in, long timeoutMs, BooleanSupplier producerAlive)
            throws IOException, InterruptedException, TimeoutException {
        return readLine(in, REAL_CLOCK.getAsLong() + timeoutMs, producerAlive, REAL_CLOCK, REAL_SLEEPER);
    }

    static String readLine(InputStream in, long deadlineMs, BooleanSupplier producerAlive,
                           LongSupplier clock, Sleeper sleeper)
            throws IOException, InterruptedException, TimeoutException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            if (in.available() > 0) {
                int c = in.read();
                if (c == -1) {
                    throw new IOException("stream closed before a line arrived");
                }
                if (c == '\n') {
                    String text = line.toString(StandardCharsets.UTF_8);
                    if (text.endsWith("\r")) {
                        text = text.substring(0, text.length() - 1);
                    }
                    if (!text.isBlank()) {
                        return text;
                    }
                    line.reset();
                } else {
                    line.write(c);
                }
                continue;
            }
            if (!producerAlive.getAsBoolean() && in.available() == 0) {
                throw new IOException("process exited before a line arrived");
            }
            if (clock.getAsLong() >= deadlineMs) {
                throw new TimeoutException("no complete line within the deadline");
            }
            sleeper.sleep(POLL_MS);
        }
    }

    /**
     * Repeats a non-blocking {@code attempt} until it reports progress or EOF (any non-zero
     * result, which is returned) or the deadline passes.
     *
     * @throws TimeoutException when every attempt before the deadline returned 0
     */
    static int awaitProgress(ReadAttempt attempt, long deadlineMs)
            throws IOException, InterruptedException, TimeoutException {
        return awaitProgress(attempt, deadlineMs, REAL_CLOCK, REAL_SLEEPER);
    }

    static int awaitProgress(ReadAttempt attempt, long deadlineMs, LongSupplier clock, Sleeper sleeper)
            throws IOException, InterruptedException, TimeoutException {
        while (true) {
            int n = attempt.read();
            if (n != 0) {
                return n;
            }
            if (clock.getAsLong() >= deadlineMs) {
                throw new TimeoutException("no reply within the deadline");
            }
            sleeper.sleep(POLL_MS);
        }
    }
}
