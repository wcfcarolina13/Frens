# Pocket TTS Engine + In-Game Installer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Kyutai Pocket TTS as a third soul voice engine (CPU, warm HTTP server, 21 English preset voices) with a transparent in-game installer, an engine-chooser row, and engine-aware `/bot soul voice` commands.

**Architecture:** `PocketVoiceEngine` implements the existing `SoulVoiceEngine` seam by owning a `pocket-tts serve` subprocess and POSTing `/tts`. `PocketInstaller` mirrors `PiperInstaller` (static `InstallJob` with compare-and-set, `detect() -> Plan`, `installAsync`), provisioning a venv via `uv` (preferred) or a python >= 3.10. `VoiceSpec.piperModel/piperSpeaker` become engine-neutral `voice/speaker` with read-aliases.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5 (`./gradlew test --tests 'net.wcfcarolina13.GameAI.souls.*' -q`), `com.sun.net.httpserver.HttpServer` for stubs, Gson (ManualConfig), Jackson (profiles). Build: `./gradlew build -x test -q`.

**Spec:** `docs/superpowers/specs/2026-08-29-frens-soul-pocket-tts-engine-design.md`

## Global Constraints
- Package base `src/main/java/net/wcfcarolina13/`; tests under `src/test/java/net/wcfcarolina13/`.
- Pinned package: `pocket-tts==3.0.2`. Server: `pocket-tts serve --host 127.0.0.1 --port <port>`; `GET /health` -> 200; `POST /tts` form fields `text`, `voice_url` -> `audio/wav` body, 24 kHz mono 16-bit.
- Engine id string: `"pocket"`. Default voice: `"charles"`. English preset names (21): alba anna azelma bill_boerst caro_davy charles cosette eponine eve fantine george jane javert jean marius mary michael paul peter_yearsley stuart_bell vera. Non-English presets (giovanni lola juergen rafael estelle) are NOT offered.
- Never block the server/render thread; process I/O on the engine thread only; `close()` non-blocking (copy `DreamsleeveVoiceEngine`'s discipline).
- Every task ends with `./gradlew build -x test -q` clean and the souls test suite green (465 + new), then a commit prefixed `souls:` or `ui:` ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Do not touch `gradle.properties` or `changelog.md` (the integrator does both once at the end).
- Run `find .git -maxdepth 1 -name '*.lock' -delete` before `git add` if a lock exists. Stage only the files you touched (never `git add -A`).

---

### Task A1: VoiceSpec rename (`voice`/`speaker`) with read aliases

**Files:**
- Modify: `GameAI/souls/SoulTypes.java:153-185` (VoiceSpec record)
- Modify: `FilingSystem/ManualConfig.java:492-522` (SoulVoiceAssignment)
- Modify: `GameAI/souls/SoulRuntime.java:782-784` (`toSpec`)
- Modify: `GameAI/souls/SoulProfileRegistry.java:136-144` (profile voice parse)
- Modify: `GameAI/souls/voice/PiperVoiceEngine.java:209-216`
- Modify: `Commands/BotSoulCommands.java` (every `piperModel()`, `piperSpeaker()`, `parsePiper(` use; grep)
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulVoiceSpecTest.java`

**Interfaces:**
- Produces: `SoulTypes.VoiceSpec(String voice, int speaker, String refAudio, String refText)`, `VoiceSpec.EMPTY`, `static VoiceSpec parse(String)` (renamed from `parsePiper`), `isEmpty()`.
- Produces: `ManualConfig.SoulVoiceAssignment(String voice, int speaker, String refAudio, String refText)` with `getVoice()/setVoice()/getSpeaker()/setSpeaker()`; Gson field annotations `@SerializedName(value = "voice", alternate = {"piperModel"})` and `@SerializedName(value = "speaker", alternate = {"piperSpeaker"})`.

- [ ] **Step 1: Update the test** — in `SoulVoiceSpecTest` replace every `piperModel()` with `voice()`, `piperSpeaker()` with `speaker()`, `parsePiper(` with `parse(`. Add (import `net.wcfcarolina13.FilingSystem.ManualConfig`):
```java
    @Test
    void assignmentReadsLegacyPiperFieldNames() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        ManualConfig.SoulVoiceAssignment legacy = gson.fromJson(
                "{\"piperModel\":\"en_US-ryan-medium\",\"piperSpeaker\":2,\"refAudio\":\"\",\"refText\":\"\"}",
                ManualConfig.SoulVoiceAssignment.class);
        assertEquals("en_US-ryan-medium", legacy.getVoice());
        assertEquals(2, legacy.getSpeaker());
        assertTrue(gson.toJson(legacy).contains("\"voice\""), "writes the new key");
    }
```
- [ ] **Step 2: Run** `./gradlew test --tests 'net.wcfcarolina13.GameAI.souls.SoulVoiceSpecTest' -q` -> FAIL (compile: no `voice()`).
- [ ] **Step 3: Implement** — rename record components (compact constructor normalises `voice` trim, `speaker < -1 -> -1`); `parsePiper` -> `parse` (same body, `new VoiceSpec(s, -1, "", "")`). `SoulVoiceAssignment`: fields `voice`, `speaker` with the `@SerializedName` annotations (import `com.google.gson.annotations.SerializedName`), 4-arg ctor params `voice, speaker`, accessors `getVoice/setVoice/getSpeaker/setSpeaker`, `isEmpty()` uses `getVoice()`. `SoulRuntime.toSpec`: `new SoulTypes.VoiceSpec(a.getVoice(), a.getSpeaker(), a.getRefAudio(), a.getRefText())`. `SoulProfileRegistry`:
```java
                String voiceName = voiceNode.hasNonNull("voice") ? voiceNode.path("voice").asText("")
                        : voiceNode.path("piperModel").asText("");
                int speaker = voiceNode.hasNonNull("speaker") ? voiceNode.path("speaker").asInt(-1)
                        : voiceNode.path("piperSpeaker").asInt(-1);
                voice = new SoulTypes.VoiceSpec(voiceName, speaker,
                        voiceNode.path("refAudio").asText(""), voiceNode.path("refText").asText(""));
```
`PiperVoiceEngine`: `spec.voice()`, `spec.speaker()`. `BotSoulCommands`: `describeSpec` label becomes `"voice " + spec.voice()`; `executeVoiceAssign` uses `VoiceSpec.parse`, `spec.voice()`, `new ManualConfig.SoulVoiceAssignment(spec.voice(), spec.speaker(), "", "")`; `executeVoiceAssignClone` uses `existing.getVoice()/getSpeaker()`. Record Javadoc: "engine-interpreted voice name (Piper: onnx model name or path; Pocket: preset name)".
- [ ] **Step 4: Run** souls tests -> PASS; `./gradlew build -x test -q` clean.
- [ ] **Step 5: Commit** `souls: VoiceSpec voice/speaker are engine-neutral (piperModel/piperSpeaker read as aliases)`.

### Task A2: Settings + config keys for the pocket engine

**Files:**
- Modify: `GameAI/souls/voice/SoulVoiceSettings.java`
- Modify: `FilingSystem/ManualConfig.java:139-152` and `:978-1008`
- Modify: `GameAI/souls/voice/SoulVoiceService.java:47-48` (the `DISABLED` literal)
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceSettingsTest.java`

**Interfaces:**
- Produces: `SoulVoiceSettings(boolean enabled, boolean valid, String validationError, String engine, String piperBinary, String voiceModel, String dreamsleeveDir, String refAudio, String refText, String pocketDir, String pocketVoice, int maxChars, long synthTimeoutMs, float radioGain)`; `ENGINE_POCKET = "pocket"`; `static SoulVoiceSettings disabled(String reason)`; `validationErrorFor(engine, piperBinary, voiceModel, dreamsleeveDir, refAudio, pocketDir)`.
- Produces: `ManualConfig.getSoulVoicePocketDir()/setSoulVoicePocketDir(String)` (default `""`), `getSoulVoicePocketVoice()/setSoulVoicePocketVoice(String)` (default `"charles"`; blank reads as `"charles"`).

- [ ] **Step 1: Tests** — add to `SoulVoiceSettingsTest`:
```java
    @Test
    void pocketEngineNeedsAnInstallDir() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulVoiceEnabled(true);
        config.setSoulVoiceEngine("pocket");
        assertFalse(SoulVoiceSettings.from(config).valid());
        config.setSoulVoicePocketDir("/tmp/frens/pocket-tts");
        SoulVoiceSettings s = SoulVoiceSettings.from(config);
        assertTrue(s.valid());
        assertEquals("charles", s.pocketVoice(), "blank voice falls back to the default preset");
    }

    @Test
    void disabledFactoryIsInvalidWithTheGivenReason() {
        SoulVoiceSettings s = SoulVoiceSettings.disabled("off");
        assertFalse(s.enabled());
        assertFalse(s.valid());
        assertEquals("off", s.validationError());
    }
```
- [ ] **Step 2: Run** the test class -> FAIL (no `setSoulVoicePocketDir`).
- [ ] **Step 3: Implement** — ManualConfig fields `private String soulVoicePocketDir = "";` and `private String soulVoicePocketVoice = "charles";` next to the other soulVoice fields, accessors next to the other soulVoice accessors (trim, null -> ""; voice blank -> "charles"). SoulVoiceSettings: add the two components after `refText`; `from()` reads them; `disabled(reason)` = `new SoulVoiceSettings(false, false, reason, ENGINE_PIPER, "", "", "", "", "", "", "charles", 400, 8000L, 0.6f)`; `from(null)` returns `disabled("Frens configuration is unavailable.")`; `validationErrorFor` gains `case ENGINE_POCKET -> pocketDir.isBlank() ? "Install Pocket TTS first (Soul Voice -> Eng...)." : ""`. `SoulVoiceService.DISABLED` -> `SoulVoiceSettings.disabled(<its current reason string>)`. Class Javadoc: three engines.
- [ ] **Step 4: Run** souls tests -> PASS; build clean.
- [ ] **Step 5: Commit** `souls: pocket engine settings (soulVoicePocketDir/Voice), SoulVoiceSettings.disabled()`.

### Task A3: PocketVoiceEngine

**Files:**
- Create: `GameAI/souls/voice/PocketVoiceEngine.java`
- Modify: `GameAI/souls/SoulRuntime.java:807-813` (engine switch)
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/voice/PocketVoiceEngineTest.java`

**Interfaces:**
- Produces: `public PocketVoiceEngine(String installDir, String defaultVoice, long synthTimeoutMs, Function<SoulTypes.VoiceKey, SoulTypes.VoiceSpec> voiceResolver) throws IOException`; package-private test ctor `PocketVoiceEngine(String installDir, String defaultVoice, long synthTimeoutMs, Function<...> resolver, int fixedPort, boolean spawnServer)`; `static Path binaryPath(String installDir)` = `<installDir>/venv/bin/pocket-tts`; `static List<String> command(String installDir, int port)`; `static String formBody(String text, String voice)`; `static boolean looksLikeWav(byte[] bytes)`.

- [ ] **Step 1: Test**
```java
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
```
- [ ] **Step 2: Run** -> FAIL (class missing).
- [ ] **Step 3: Implement** `PocketVoiceEngine` — structure copied from `DreamsleeveVoiceEngine`: single-thread executor `frens-soul-voice-engine` (daemon), `volatile boolean closed`, `Process process`, `drainStream`, `killProcess`, non-blocking `close()` with daemon closer thread, `alive()` = `!closed`, `resolveQuietly`. Fields: `installDir`, `defaultVoice`, `synthTimeoutMs`, `voiceResolver`, `int port` (fixed, or `pickFreePort()` = `try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }`), `boolean spawnServer`, `HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()`. Public ctor delegates to the test ctor with `pickFreePort(), true`.
  - `synthesize(text, VoiceKey)` on the engine thread: `ensureServer()`; `spec = resolveQuietly(key)`; `voice = spec.voice().isEmpty() ? defaultVoice : spec.voice()`; `POST http://127.0.0.1:<port>/tts`, header `Content-Type: application/x-www-form-urlencoded`, body `formBody(text.replace('\n', ' '), voice)`, `.timeout(Duration.ofMillis(synthTimeoutMs))`, `BodyHandlers.ofByteArray()`; status != 200 or `!looksLikeWav(body)` -> `throw new IOException("pocket tts returned " + status)`; complete. Any exception -> if `spawnServer` then `killProcess()`; `completeExceptionally`.
  - `ensureServer()`: if `!spawnServer` return; if process alive and `healthy()` return; `ProcessBuilder(command(installDir, port))` with `environment().put("OMP_NUM_THREADS", "1")`, `redirectErrorStream(false)`, start, drain both streams, `LOGGER.info("[souls] tts engine started pid={} (pocket-tts serve :{})", ...)`; poll `healthy()` every 250 ms up to 20 s (`IOException("pocket tts server exited during startup")` if the process died; `TimeoutException` if never healthy); then fire-and-forget warm-up POST `"Ready."` with the default voice (ignore result).
  - `healthy()`: `GET /health`, 1 s timeout, status 200.
  - `formBody`: `"text=" + URLEncoder.encode(text, UTF_8) + "&voice_url=" + URLEncoder.encode(voice, UTF_8)`.
  - `looksLikeWav`: length >= 12 and bytes 0-3 == "RIFF" and 8-11 == "WAVE".
  - `command(installDir, port)`: `List.of(binaryPath(installDir).toString(), "serve", "--host", "127.0.0.1", "--port", Integer.toString(port))`.
  Then in `SoulRuntime.buildVoiceService`:
```java
            SoulVoiceEngine engine = switch (voiceSettings.engine()) {
                case SoulVoiceSettings.ENGINE_DREAMSLEEVE -> new DreamsleeveVoiceEngine(
                        voiceSettings.dreamsleeveDir(), voiceSettings.refAudio(),
                        voiceSettings.refText(), voiceSettings.synthTimeoutMs(), voiceResolver);
                case SoulVoiceSettings.ENGINE_POCKET -> new PocketVoiceEngine(
                        voiceSettings.pocketDir(), voiceSettings.pocketVoice(),
                        voiceSettings.synthTimeoutMs(), voiceResolver);
                case SoulVoiceSettings.ENGINE_PIPER -> new PiperVoiceEngine(voiceSettings.piperBinary(),
                        voiceSettings.voiceModel(), voiceSettings.synthTimeoutMs(), voiceResolver);
                default -> throw new IllegalStateException("unknown soul voice engine: " + voiceSettings.engine());
            };
```
  (the existing `catch (Exception ex)` logs and returns `disabled()`).
- [ ] **Step 4: Run** souls tests -> PASS; build clean.
- [ ] **Step 5: Commit** `souls: PocketVoiceEngine — warm pocket-tts serve on loopback, per-bot preset voices`.

### Task B1: PocketInstaller (runtime detection, venv, pip, smoke test, config)

**Files:**
- Create: `GameAI/souls/voice/PocketInstaller.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/voice/PocketInstallerTest.java`

**Interfaces:**
- Produces: `public record Runtime(Path executable, String kind, String version)` (`kind` in `"uv"`, `"python"`); `public record Plan(Path installDir, Path venvDir, Path binary, boolean alreadyInstalled, Runtime runtime, long freeDiskBytes, boolean hfTokenPresent)` (`runtime` may be null); `public static Plan detect()`; `static Optional<Runtime> findRuntime(List<Path> uvCandidates, List<Path> pythonCandidates, Predicate<Path> isExecutable, Function<List<String>, String> runForOutput)`; `static boolean pythonVersionOk(String versionLine)`; `static List<String> venvCommand(Runtime rt, Path venvDir)`; `static List<String> pipInstallCommand(Runtime rt, Path venvDir)`; `public static InstallJob activeJob()`, `public static void clearFinishedJob()`, `public static boolean installAsync(Plan)`; `public static final String PACKAGE_SPEC = "pocket-tts==3.0.2"`; `public static final long NEED_FREE_BYTES = 2L * 1024 * 1024 * 1024`; `public static Path defaultInstallDir()` = `<gameDir>/config/frens/pocket-tts`.

- [ ] **Step 1: Test**
```java
package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class PocketInstallerTest {

    @Test
    void pythonVersionGate() {
        assertTrue(PocketInstaller.pythonVersionOk("Python 3.12.0"));
        assertTrue(PocketInstaller.pythonVersionOk("Python 3.10.14"));
        assertFalse(PocketInstaller.pythonVersionOk("Python 3.9.6"));
        assertFalse(PocketInstaller.pythonVersionOk("Python 2.7.18"));
        assertFalse(PocketInstaller.pythonVersionOk(""));
    }

    @Test
    void uvWinsWhenPresentOtherwiseFirstModernPython() {
        Path uv = Path.of("/opt/homebrew/bin/uv");
        Path oldPy = Path.of("/usr/bin/python3");
        Path newPy = Path.of("/opt/homebrew/bin/python3.11");
        Optional<PocketInstaller.Runtime> withUv = PocketInstaller.findRuntime(
                List.of(uv), List.of(oldPy, newPy), p -> true,
                cmd -> cmd.get(0).endsWith("uv") ? "uv 0.8.0"
                        : cmd.get(0).equals(oldPy.toString()) ? "Python 3.9.6" : "Python 3.11.9");
        assertEquals("uv", withUv.orElseThrow().kind());
        Optional<PocketInstaller.Runtime> noUv = PocketInstaller.findRuntime(
                List.of(uv), List.of(oldPy, newPy), p -> !p.equals(uv),
                cmd -> cmd.get(0).equals(oldPy.toString()) ? "Python 3.9.6" : "Python 3.11.9");
        assertEquals("python", noUv.orElseThrow().kind());
        assertEquals(newPy, noUv.orElseThrow().executable());
        assertTrue(PocketInstaller.findRuntime(List.of(uv), List.of(oldPy), p -> !p.equals(uv),
                cmd -> "Python 3.9.6").isEmpty());
    }

    @Test
    void commandsPerRuntimeKind() {
        Path venv = Path.of("/cfg/pocket-tts/venv");
        PocketInstaller.Runtime uv = new PocketInstaller.Runtime(Path.of("/opt/homebrew/bin/uv"), "uv", "uv 0.8.0");
        assertEquals(List.of("/opt/homebrew/bin/uv", "venv", "--python", "3.12", venv.toString()),
                PocketInstaller.venvCommand(uv, venv));
        assertEquals(List.of("/opt/homebrew/bin/uv", "pip", "install", "--python",
                        venv.resolve("bin/python").toString(), PocketInstaller.PACKAGE_SPEC),
                PocketInstaller.pipInstallCommand(uv, venv));
        PocketInstaller.Runtime py = new PocketInstaller.Runtime(Path.of("/opt/homebrew/bin/python3.11"), "python", "Python 3.11.9");
        assertEquals(List.of("/opt/homebrew/bin/python3.11", "-m", "venv", venv.toString()),
                PocketInstaller.venvCommand(py, venv));
        assertEquals(List.of(venv.resolve("bin/pip").toString(), "install", PocketInstaller.PACKAGE_SPEC),
                PocketInstaller.pipInstallCommand(py, venv));
    }
}
```
- [ ] **Step 2: Run** -> FAIL.
- [ ] **Step 3: Implement** `PocketInstaller` (final class, private ctor, `LOGGER = LoggerFactory.getLogger("frens.souls")`):
  - `pythonVersionOk`: regex `Python (\d+)\.(\d+)` -> major == 3 && minor >= 10.
  - `findRuntime`: for each uv candidate that `isExecutable` -> `runForOutput([uv, --version])`; non-blank output -> `Runtime(uv, "uv", output.trim())`. Else for each python candidate that is executable -> `runForOutput([py, --version])`; first `pythonVersionOk` wins -> `Runtime(py, "python", version.trim())`. Else `Optional.empty()`.
  - `detect()`: uv candidates `/opt/homebrew/bin/uv`, `~/.local/bin/uv`, `/usr/local/bin/uv`, then `uv` in each PATH dir; python candidates `/opt/homebrew/bin/python3.13`, `.12`, `.11`, `.10`, `/opt/homebrew/bin/python3`, `/Library/Frameworks/Python.framework/Versions/3.13/bin/python3` down to `3.10`, `/usr/local/bin/python3`, then `python3` in each PATH dir. `runForOutput` = `ProcessBuilder(cmd).redirectErrorStream(true)`, read all, `waitFor(10, SECONDS)`; any exception -> `""`. `installDir` = configured `getSoulVoicePocketDir()` when non-blank else `defaultInstallDir()`; `venvDir = installDir.resolve("venv")`; `binary = PocketVoiceEngine.binaryPath(installDir.toString())`; `alreadyInstalled = Files.isExecutable(binary)`; free space like Piper (`Files.getFileStore(installDir).getUsableSpace()` after `createDirectories`, -1 on failure); `hfTokenPresent = Files.isRegularFile(Path.of(home, ".cache", "huggingface", "token"))`.
  - `ACTIVE_JOB` / `activeJob()` / `clearFinishedJob()` / `installAsync(Plan)` — copy Piper's block verbatim; thread name `frens-pocket-install`; job description `"Pocket TTS install"`.
  - `static void install(Plan plan, PiperInstaller.Progress progress) throws Exception`:
    1. `if (plan.runtime() == null) throw new IOException("No Python 3.10+ or uv found. Install uv (docs.astral.sh/uv) or python.org 3.12, then retry.");`
    2. If `!plan.alreadyInstalled()`: `progress.update("Creating environment...", 0, 0); runLogged(venvCommand(rt, venvDir), progress); progress.update("Installing pocket-tts (about 850 MB, a few minutes)...", 0, 0); runLogged(pipInstallCommand(rt, venvDir), progress);` — `runLogged` streams merged stdout lines and publishes the last non-blank line trimmed to 70 chars as `progress.update("pip: " + line, 0, 0)`; non-zero exit -> `IOException(cmd.get(0) + " failed (exit " + code + "): " + lastLine)`; `waitFor` capped at 30 minutes.
    3. `if (!Files.isExecutable(plan.binary())) throw new IOException("pocket-tts binary missing after install: " + plan.binary());`
    4. `progress.update("Testing synthesis (first run downloads the 228 MB model)...", 0, 0);` then `PocketVoiceEngine eng = new PocketVoiceEngine(installDir, "charles", 120_000L, k -> SoulTypes.VoiceSpec.EMPTY); try { byte[] wav = eng.synthesize("Ready.", new SoulTypes.VoiceKey("", "")).get(150, TimeUnit.SECONDS); if (!PocketVoiceEngine.looksLikeWav(wav)) throw new IOException("smoke test produced no WAV"); } finally { eng.close(); }`
    5. `ManualConfig cfg = Frens.CONFIG` (null -> `IllegalStateException("Config not loaded")`); `cfg.setSoulVoiceEngine(SoulVoiceSettings.ENGINE_POCKET); cfg.setSoulVoicePocketDir(installDir.toAbsolutePath().toString()); cfg.setSoulVoiceEnabled(true); cfg.save();` then `SoulRuntime.current().ifPresent(rt -> rt.reloadSettings(cfg).exceptionally(...log...))`; `progress.update("Done", 0, 0)`; `LOGGER.info("[souls] pocket-tts installed: {}", installDir)`.
- [ ] **Step 4: Run** souls tests -> PASS; build clean.
- [ ] **Step 5: Commit** `souls: PocketInstaller — uv/python runtime detection, managed venv, pinned pip install, smoke test`.

### Task B2: PocketInstallerScreen + engine chooser as a list

**Files:**
- Create: `GraphicalUserInterface/PocketInstallerScreen.java`
- Modify: `GraphicalUserInterface/SoulVoiceEngineScreen.java` (rewrite body)

**Interfaces:**
- Consumes: `PocketInstaller.detect()/Plan/activeJob()/clearFinishedJob()/installAsync(Plan)/NEED_FREE_BYTES/defaultInstallDir()`, `PocketVoiceEngine.binaryPath`.
- Produces: `new PocketInstallerScreen(Screen parent)`; private `record EngineRow(String id, String title, String blurb, boolean available, Runnable installer)` inside `SoulVoiceEngineScreen`.

- [ ] **Step 1:** Write `PocketInstallerScreen` as a copy of `PiperInstallerScreen` with: title `"§bPocket TTS Installer"`, `POPUP_HEIGHT = 230`, one action button (label `"Install"` when `!plan.alreadyInstalled()`, else `"Use Installed"` — both call `PocketInstaller.installAsync(plan)`; the installer skips venv/pip when already installed and just smoke-tests + writes config) plus `Close`; `drawPlan` rows: runtime (`"Runtime: " + rt.version() + " (" + rt.executable() + ")"` with a check, or a cross + `"No Python 3.10+ or uv found — install uv from docs.astral.sh/uv"`), `"Disk space: need ~2 GB free — <n> available"`, `"Download: ~850 MB of Python packages (pypi.org) + 228 MB model (huggingface.co) on first run"`, `"Engine: pocket-tts 3.0.2 · CPU only · 21 English voices"` (dim), `"Install to: <dir>"` (dim, elided), and `"Voice cloning: needs a Hugging Face login (accept terms at huggingface.co/kyutai/pocket-tts, then hf auth login)"` or `"Voice cloning: Hugging Face token found"` (dim, wrapped). Install button active only when `phase == READY && plan.runtime() != null && diskOk && no job running`. Success line `"§aInstalled. Pocket TTS is now the soul voice engine."`.
- [ ] **Step 2:** Rewrite `SoulVoiceEngineScreen`: in `init()` build `List<EngineRow>`:
  - Dreamsleeve: title `"Dreamsleeve — cloned bot voice"`, blurb `"§7Best quality. Uses the GPU. " + (avail ? "Available." : "Not set up on this machine.")`, installer `null`.
  - Pocket: title `"Pocket TTS — natural CPU voices"`, blurb `"§7Kyutai, 100M params, 21 presets. " + (avail ? "Installed." : "One-time ~1 GB install.")`, available = `Files.isExecutable(PocketVoiceEngine.binaryPath(dir))` where `dir` = configured pocket dir if non-blank else `PocketInstaller.defaultInstallDir().toString()`, installer `() -> this.client.setScreen(new PocketInstallerScreen(this))`.
  - Piper: existing title/blurb, installer `() -> this.client.setScreen(new PiperInstallerScreen(this))`.
  Layout: `ROW_H = 50`; `POPUP_HEIGHT = 40 + rows.size() * ROW_H + 36`; row i at `y = cy + 40 + i * ROW_H`: title at `y + 2`, blurb at `y + 14`, button (width 130, right-aligned at `cx + POPUP_WIDTH - PAD - 130`, `y + 4`) labelled `current ? "§a" + shortName + " ✔" : available ? "Use " + shortName : "Install " + shortName + "..."`, `active = !(available && current) && (available || installer != null)`; click -> `available ? selectEngine(id) : installer.run()`. Store rows in a field so `render` draws titles/blurbs from the same list. Keep `selectEngine`, `close`, `keyPressed`, `shouldPause` unchanged.
- [ ] **Step 3:** `./gradlew build -x test -q` clean (screens have no unit tests — say so in the commit body).
- [ ] **Step 4: Commit** `ui: Pocket TTS installer screen; soul voice engine chooser is a three-row list`.

### Task C1: VoiceCatalog + engine-aware voice commands

**Files:**
- Create: `GameAI/souls/voice/VoiceCatalog.java`
- Modify: `Commands/BotSoulCommands.java` (`executeVoiceList`, `executeVoiceInstall`, `executeVoiceAssign`, `validateVoiceConfig` and its callers/tests — grep `validateVoiceConfig(`)
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/voice/VoiceCatalogTest.java`

**Interfaces:**
- Produces: `public record Entry(String name, String description)`; `public static final List<String> POCKET_VOICES` (21 English names, sorted); `public static List<Entry> forEngine(String engine)` (`"pocket"` -> 21 entries; `"piper"` -> mapped from `PiperInstaller.VOICE_CATALOG` name/description; else empty); `public static boolean isKnown(String engine, String name)`; `public static boolean needsDownload(String engine)` (true only for `"piper"`).
  Pocket descriptions: alba "warm Scottish woman", anna "bright young woman", azelma "soft-spoken woman", bill_boerst "older American man", caro_davy "calm woman", charles "steady English man", cosette "hesitant young woman", eponine "quick young woman", eve "low even woman", fantine "gentle woman", george "gruff older man", jane "clear woman", javert "deep stern man", jean "dry older man", marius "light young man", mary "matter-of-fact woman", michael "easy-going man", paul "plain-spoken man", peter_yearsley "British man", stuart_bell "brisk man", vera "measured woman".

- [ ] **Step 1: Test**
```java
package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoiceCatalogTest {
    @Test
    void pocketCatalogHasTheEnglishPresets() {
        assertEquals(21, VoiceCatalog.POCKET_VOICES.size());
        assertTrue(VoiceCatalog.isKnown("pocket", "charles"));
        assertFalse(VoiceCatalog.isKnown("pocket", "giovanni"), "non-English presets are not offered");
        assertFalse(VoiceCatalog.needsDownload("pocket"));
        assertTrue(VoiceCatalog.needsDownload("piper"));
        assertEquals(VoiceCatalog.POCKET_VOICES.size(), VoiceCatalog.forEngine("pocket").size());
        assertFalse(VoiceCatalog.forEngine("piper").isEmpty());
        assertTrue(VoiceCatalog.forEngine("dreamsleeve").isEmpty());
    }
}
```
- [ ] **Step 2: Run** -> FAIL.
- [ ] **Step 3: Implement** `VoiceCatalog`, then `BotSoulCommands`:
  - `executeVoiceList`: `engine = cfg.getSoulVoiceEngine()`; `piper` -> today's output unchanged; `pocket` -> `"Pocket TTS voices (all available, no download needed):"` then `"  name — description"` per entry; `dreamsleeve` -> `"Dreamsleeve clones a reference clip: /bot soul voice assign <bot> clone <ref.wav>"`; then the per-bot picks block unchanged.
  - `executeVoiceInstall`: if `!VoiceCatalog.needsDownload(engine)` -> `ChatUtils.sendSystemMessage(source, "No download needed — " + engine + " voices are ready. /bot soul voice assign <bot> <name>.")` and return 1; else the existing Piper path.
  - `executeVoiceAssign`: after `VoiceSpec.parse`: if engine is `pocket` and `!VoiceCatalog.isKnown("pocket", spec.voice())` -> error `"Unknown Pocket voice '<v>'. /bot soul voice list."`, return 0; run the existing Piper resolver check only when engine is `piper`. Success message `profileId + " → " + describeSpec(spec) + ". Takes effect on the next spoken line."`.
  - `validateVoiceConfig`: add a `String pocketDir` parameter; `if ("pocket".equals(engine)) return isFile.test(PocketVoiceEngine.binaryPath(pocketDir).toString()) ? Optional.empty() : Optional.of("Pocket TTS is not installed (Soul Voice → Eng… → Install).");` Update callers and any test that calls it.
- [ ] **Step 4: Run** souls tests -> PASS; build clean.
- [ ] **Step 5: Commit** `souls: VoiceCatalog; /bot soul voice list|install|assign are engine-aware (pocket presets need no download)`.
