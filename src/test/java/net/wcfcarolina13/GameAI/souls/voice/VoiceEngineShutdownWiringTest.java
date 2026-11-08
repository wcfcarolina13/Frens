package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-reading guard for the 2026-11-05 outbound-I/O fixes: every engine's closer thread kills
 * its processes directly after the executor wait (a queued kill is dropped by {@code shutdownNow}
 * when a synth is in flight), the Dreamsleeve ack is never a bare blocking read, Piper's stdout is
 * read under a deadline, tar's output is discarded, and the Ollama installer cannot throw out of
 * {@code detect()}.
 */
class VoiceEngineShutdownWiringTest {

    private static final Path VOICE = Path.of("src/main/java/net/wcfcarolina13/GameAI/souls/voice");
    private static final Path SOULS = Path.of("src/main/java/net/wcfcarolina13/GameAI/souls");

    @Test
    void piperCloserKillsEveryWarmProcessDirectly() throws IOException {
        String close = block(flatten(VOICE.resolve("PiperVoiceEngine.java")), "public void close()");
        String closer = block(close, "Thread closer = new Thread(() ->");
        assertTrue(closer.contains("engineThread.awaitTermination(1, TimeUnit.SECONDS)"));
        int wait = closer.indexOf("engineThread.shutdownNow()");
        int kill = closer.indexOf("killAll();");
        assertTrue(wait >= 0 && kill > wait, "killAll must run on the closer after the executor wait");
        assertTrue(kill < closer.indexOf("deleteOutputDirQuietly()"));
        String source = flatten(VOICE.resolve("PiperVoiceEngine.java"));
        assertTrue(source.contains("new ConcurrentHashMap<>()"), "closer walks the map, so it must be concurrent");
        assertTrue(source.contains("volatile Process process"));
        assertTrue(block(source, "private void killAll()").contains("List.copyOf(processes.values())"));
    }

    @Test
    void dreamsleeveAndPocketClosersKillTheServerDirectly() throws IOException {
        for (String engine : new String[] {"DreamsleeveVoiceEngine.java", "PocketVoiceEngine.java"}) {
            String source = flatten(VOICE.resolve(engine));
            String closer = block(block(source, "public void close()"), "Thread closer = new Thread(() ->");
            int wait = closer.indexOf("engineThread.shutdownNow()");
            int kill = closer.indexOf("killProcess();");
            assertTrue(wait >= 0 && kill > wait, engine + ": killProcess must run on the closer after the wait");
            assertTrue(source.contains("private volatile Process process"), engine);
            // The kill reads the handle once so a concurrent null from the engine thread cannot NPE it.
            assertTrue(block(source, "private void killProcess()").contains("Process current = process;"), engine);
        }
    }

    @Test
    void dreamsleeveAckIsAwaitedOnANonBlockingChannelUnderTheSynthDeadline() throws IOException {
        String source = flatten(VOICE.resolve("DreamsleeveVoiceEngine.java"));
        String send = block(source, "static void sendRequestLine(Path sockPath, String requestLine, long deadlineMs)");
        assertTrue(send.contains("channel.configureBlocking(false)"));
        assertTrue(send.contains("DeadlineReads.awaitProgress(() -> channel.read(ack), deadlineMs)"));
        assertFalse(source.contains("channel.read(ack);"), "no bare blocking ack read anywhere in the engine");
        // One deadline for ack plus render: a line costs at most synthTimeoutMs.
        String synth = block(source, "public CompletableFuture<byte[]> synthesize(String text, net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceKey key)");
        assertTrue(synth.contains("long deadline = System.currentTimeMillis() + synthTimeoutMs;"));
        assertTrue(synth.contains("outFile.toString()), deadline);"));
        assertTrue(synth.contains("awaitRenderedFile(outFile, deadline)"));
        // The warm-up ack wait is short and its timeout is swallowed like its I/O errors.
        String ensure = block(source, "private void ensureServer()");
        assertTrue(ensure.contains("System.currentTimeMillis() + WARMUP_ACK_TIMEOUT_MS"));
        assertTrue(ensure.contains("catch (IOException | TimeoutException ignored)"));
    }

    @Test
    void piperStdoutIsReadByteWiseUnderTheDeadlineNotThroughABufferedReader() throws IOException {
        String source = flatten(VOICE.resolve("PiperVoiceEngine.java"));
        assertFalse(source.contains("BufferedReader"), "a BufferedReader readLine can block past the deadline");
        assertFalse(source.contains(".ready()"));
        String read = block(source, "private String readLineWithDeadline(PiperProcess proc)");
        assertTrue(read.contains("DeadlineReads.readLine(proc.stdout, synthTimeoutMs,"));
        assertTrue(read.contains("child != null && child.isAlive()"));
        assertTrue(source.contains("proc.stdout = proc.process.getInputStream();"));
    }

    @Test
    void tarOutputIsDiscardedSoALargeListingCannotBlockExtraction() throws IOException {
        String extract = block(flatten(VOICE.resolve("PiperInstaller.java")),
                "private static void extract(Path archive, Path destDir)");
        String tar = extract.substring(extract.indexOf("new ProcessBuilder(\"tar\""));
        assertTrue(tar.contains(".redirectOutput(ProcessBuilder.Redirect.DISCARD)"));
        assertTrue(tar.indexOf(".redirectErrorStream(true)") < tar.indexOf(".redirectOutput("));
    }

    @Test
    void ollamaDetectValidatesTheSchemeFirstAndCatchesRuntimeFailures() throws IOException {
        String source = flatten(SOULS.resolve("OllamaModelInstaller.java"));
        String detect = block(source, "public static Status detect()");
        int check = detect.indexOf("if (!OllamaEndpointPolicy.isHttpUrl(base))");
        int http = detect.indexOf("getJson(OllamaEndpointPolicy.endpoint(base, \"/api/version\")");
        assertTrue(check >= 0 && check < http, "scheme check before the first connection");
        assertTrue(detect.contains("catch (IOException | RuntimeException e)"));
        assertFalse(source.contains("base + \"/api/"), "no string concatenation of the base URL");
        assertFalse(source.contains("baseUrl() + \"/api/"), "no string concatenation of the base URL");
        assertTrue(block(source, "public static void pull(String tag, Progress progress)")
                .contains("OllamaEndpointPolicy.endpoint(baseUrl(), \"/api/pull\")"));
    }

    @Test
    void soulProviderAndSettingsShareTheEndpointPolicy() throws IOException {
        String provider = flatten(SOULS.resolve("OllamaSoulProvider.java"));
        assertFalse(provider.contains("baseUri.resolve("), "resolve drops a reverse-proxy path prefix");
        assertTrue(provider.contains("OllamaEndpointPolicy.endpoint(baseUri, \"/api/chat\")"));
        assertTrue(provider.contains("OllamaEndpointPolicy.endpoint(baseUri, \"/api/tags\")"));
        String settings = flatten(SOULS.resolve("SoulSettings.java"));
        assertTrue(settings.contains("OllamaEndpointPolicy.isSupportedScheme(baseUri.getScheme())"));
        assertTrue(settings.contains("OllamaEndpointPolicy.SCHEME_ERROR"));
    }

    private static String flatten(Path path) throws IOException {
        return Files.readString(path)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "")
                .replaceAll("\\s+", " ");
    }

    private static String block(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "Missing scope: " + marker);
        int start = source.indexOf('{', at);
        int depth = 1;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, i);
        }
        throw new AssertionError("Unbalanced block after: " + marker);
    }
}
