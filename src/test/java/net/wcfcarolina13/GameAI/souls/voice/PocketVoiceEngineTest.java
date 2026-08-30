package net.wcfcarolina13.GameAI.souls.voice;

import com.sun.net.httpserver.HttpServer;
import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PocketVoiceEngineTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private static final byte[] WAV = "RIFF    WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/health", ex -> {
            ex.sendResponseHeaders(200, 2);
            try (OutputStream o = ex.getResponseBody()) { o.write("ok".getBytes()); }
        });
        server.createContext("/tts", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.getResponseHeaders().add("Content-Type", "audio/wav");
            ex.sendResponseHeaders(200, WAV.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(WAV); }
        });
        server.start();
    }

    @AfterEach
    void stopStub() { server.stop(0); }

    @Test
    void commandServesFromTheManagedVenvOnLoopback() {
        List<String> cmd = PocketVoiceEngine.command("/cfg/pocket-tts", 8123);
        assertEquals(Path.of("/cfg/pocket-tts/venv/bin/pocket-tts").toString(), cmd.get(0));
        assertEquals(List.of("serve", "--host", "127.0.0.1", "--port", "8123"), cmd.subList(1, 6));
    }

    @Test
    void formBodyEncodesTextAndVoice() {
        assertEquals("text=Hello+%26+goodbye%3F&voice_url=charles",
                PocketVoiceEngine.formBody("Hello & goodbye?", "charles"));
    }

    @Test
    void synthesizePostsTheResolvedVoiceAndReturnsWavBytes() throws Exception {
        PocketVoiceEngine engine = new PocketVoiceEngine("/nowhere", "charles", 2000L,
                key -> "Bob".equals(key.botName()) ? new SoulTypes.VoiceSpec("paul", -1, "", "") : SoulTypes.VoiceSpec.EMPTY,
                port, false);
        byte[] out = engine.synthesize("Line one.\nLine two.", new SoulTypes.VoiceKey("Bob", "frens:bob"))
                .get(5, TimeUnit.SECONDS);
        assertArrayEquals(WAV, out);
        String decoded = URLDecoder.decode(lastBody.get(), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("voice_url=paul"), decoded);
        assertTrue(decoded.contains("text=Line one. Line two."), "newlines flattened: " + decoded);
        byte[] dflt = engine.synthesize("Hi.", new SoulTypes.VoiceKey("Jake", "")).get(5, TimeUnit.SECONDS);
        assertArrayEquals(WAV, dflt);
        assertTrue(URLDecoder.decode(lastBody.get(), StandardCharsets.UTF_8).contains("voice_url=charles"));
        engine.close();
        assertFalse(engine.alive());
    }

    @Test
    void nonWavResponseFailsTheLine() throws Exception {
        server.removeContext("/tts");
        server.createContext("/tts", ex -> {
            ex.sendResponseHeaders(500, 3);
            try (OutputStream o = ex.getResponseBody()) { o.write("bad".getBytes()); }
        });
        PocketVoiceEngine engine = new PocketVoiceEngine("/nowhere", "charles", 2000L,
                key -> SoulTypes.VoiceSpec.EMPTY, port, false);
        var f = engine.synthesize("Hi.", new SoulTypes.VoiceKey("", ""));
        assertThrows(Exception.class, () -> f.get(5, TimeUnit.SECONDS));
        engine.close();
    }
}
