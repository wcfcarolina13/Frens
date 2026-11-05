package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives {@link DreamsleeveVoiceEngine#sendRequestLine} against a real UNIX socket on this
 * machine: a server that accepts and never acks must time out at the deadline instead of parking
 * the caller for good, and a server that acks must let the request through unchanged. Skipped where
 * the JDK has no UNIX domain socket support.
 */
class DreamsleeveAckDeadlineTest {

    private static Path shortSocketPath() {
        // AF_UNIX paths are short on macOS, so bind under /tmp like the engine does.
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(Path.of("/tmp")),
                "no /tmp (Windows): skipped");
        return Path.of("/tmp", "frens-acktest-" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL) + ".sock");
    }

    private static ServerSocketChannel listen(Path sock) throws IOException {
        ServerSocketChannel server;
        try {
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        } catch (UnsupportedOperationException ex) {
            assumeTrue(false, "no UNIX domain sockets here");
            throw ex;
        }
        server.bind(UnixDomainSocketAddress.of(sock));
        return server;
    }

    @Test
    void serverThatAcceptsButNeverAcksTimesOutAtTheDeadline() throws Exception {
        Path sock = shortSocketPath();
        try (ServerSocketChannel server = listen(sock)) {
            Thread acceptor = new Thread(() -> {
                try (SocketChannel peer = server.accept()) {
                    ByteBuffer sink = ByteBuffer.allocate(1024);
                    peer.read(sink);
                    Thread.sleep(2_000); // hold the connection open, never answer
                } catch (Exception ignored) {
                    // test ends first
                }
            }, "acktest-silent-server");
            acceptor.setDaemon(true);
            acceptor.start();

            long start = System.currentTimeMillis();
            assertThrows(TimeoutException.class, () -> DreamsleeveVoiceEngine.sendRequestLine(
                    sock, "{\"op\":\"speak\"}\n", System.currentTimeMillis() + 300));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed >= 250, "returned before the deadline: " + elapsed + " ms");
            assertTrue(elapsed < 1_500, "ran far past the deadline: " + elapsed + " ms");
        } finally {
            Files.deleteIfExists(sock);
        }
    }

    @Test
    void ackedRequestArrivesIntactAndReturnsPromptly() throws Exception {
        Path sock = shortSocketPath();
        StringBuilder received = new StringBuilder();
        try (ServerSocketChannel server = listen(sock)) {
            Thread acceptor = new Thread(() -> {
                try (SocketChannel peer = server.accept()) {
                    ByteBuffer buf = ByteBuffer.allocate(1024);
                    while (received.indexOf("\n") < 0 && peer.read(buf) > 0) {
                        buf.flip();
                        synchronized (received) {
                            received.append(StandardCharsets.UTF_8.decode(buf));
                        }
                        buf.clear();
                    }
                    peer.write(ByteBuffer.wrap("{\"ok\":true}\n".getBytes(StandardCharsets.UTF_8)));
                } catch (IOException ignored) {
                    // test ends first
                }
            }, "acktest-acking-server");
            acceptor.setDaemon(true);
            acceptor.start();

            long start = System.currentTimeMillis();
            DreamsleeveVoiceEngine.sendRequestLine(sock, "{\"op\":\"speak\",\"text\":\"hi\"}\n",
                    System.currentTimeMillis() + 5_000);
            assertTrue(System.currentTimeMillis() - start < 2_000);
            acceptor.join(2_000);
            synchronized (received) {
                assertEquals("{\"op\":\"speak\",\"text\":\"hi\"}\n", received.toString());
            }
        } finally {
            Files.deleteIfExists(sock);
        }
    }

    @Test
    void missingSocketFailsAsAnIoErrorNotATimeout() {
        Path sock = shortSocketPath();
        assertThrows(IOException.class, () -> DreamsleeveVoiceEngine.sendRequestLine(
                sock, "{}\n", System.currentTimeMillis() + 300));
    }
}
