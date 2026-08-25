# Soul Generated Voice (TTS) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jake's committed soul replies get a locally synthesized spoken voice — positional near him, "radio" over distance — without ever touching the text path.

**Architecture:** A `SpokenListener` seam in `SoulConversationService.commitSpoken` feeds a new `SoulVoiceService` (worker thread) that sanitizes the line, synthesizes WAV via one mod-owned long-lived Piper subprocess, strips it to raw PCM server-side, and ships ≤32 KB chunks to the one conversation player as a new S2C `SoulVoicePayload`. The client reassembles and plays through its **own** OpenAL device/context on a dedicated thread (never fighting Minecraft's sound engine), pinning a 3D source to Jake for LOCAL turns and a flat quieter source for REMOTE.

**Tech Stack:** Java 21, Minecraft 1.21.11 (yarn), Fabric API networking (`PayloadTypeRegistry` / `ServerPlayNetworking` / `ClientPlayNetworking`), LWJGL OpenAL (`org.lwjgl.openal.ALC10`/`AL10`, already on the client classpath), Piper TTS (external binary, CPU).

**Spec:** `docs/superpowers/specs/2026-08-24-soul-generated-voice-design.md`

## Global Constraints

- Voice is OFF by default (`soulVoiceEnabled=false`); disabled behavior must be byte-identical to today.
- Any voice-path failure drops audio only; text delivery, memory, and gameplay are never affected.
- No server-thread blocking anywhere; synthesis and file I/O on the voice worker only.
- All voice log lines use the shared `frens.souls` logger, prefix `[souls] tts`, and carry the turn's routingId; never message content.
- Unit tests are pure: no subprocess, no Minecraft server, no OpenAL. (`./gradlew test` must stay green; suite currently 384 tests.)
- Piper CLI flags used by `PiperVoiceEngine` MUST be verified against the installed binary (`piper --help`) during Task 5 — never assumed.
- Repo conventions: commit per task, changelog entry at the end, `mod_version` bump before deploy, pre-deploy `pgrep` check.

---

### Task 1: Voice config fields + validated settings snapshot

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java` (after the soul accessors around line 799)
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceSettings.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceSettingsTest.java`

**Interfaces:**
- Consumes: `ManualConfig` (existing soul accessor style, lines 762–799).
- Produces: `SoulVoiceSettings(boolean enabled, boolean valid, String validationError, String piperBinary, String voiceModel, int maxChars, long synthTimeoutMs, float radioGain)` record with `public static SoulVoiceSettings from(ManualConfig config)`; ManualConfig getters/setters `isSoulVoiceEnabled/setSoulVoiceEnabled`, `getSoulVoicePiperBinary/set…`, `getSoulVoiceModel/set…`, `getSoulVoiceMaxChars/set…`, `getSoulVoiceSynthTimeoutMs/set…`, `getSoulVoiceRadioGain/set…`.

- [ ] **Step 1: Write the failing test**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulVoiceSettingsTest {

    @Test
    void defaultsAreDisabledAndValidStructurally() {
        SoulVoiceSettings s = SoulVoiceSettings.from(new ManualConfig());
        assertFalse(s.enabled());
        assertEquals(400, s.maxChars());
        assertEquals(8000L, s.synthTimeoutMs());
        assertEquals(0.6f, s.radioGain(), 0.0001f);
    }

    @Test
    void enabledWithBlankPathsIsInvalidWithAConcreteError() {
        ManualConfig config = new ManualConfig();
        config.setSoulVoiceEnabled(true);
        SoulVoiceSettings s = SoulVoiceSettings.from(config);
        assertTrue(s.enabled());
        assertFalse(s.valid());
        assertFalse(s.validationError().isBlank());
    }

    @Test
    void enabledWithBothPathsIsValid() {
        ManualConfig config = new ManualConfig();
        config.setSoulVoiceEnabled(true);
        config.setSoulVoicePiperBinary("/opt/homebrew/bin/piper");
        config.setSoulVoiceModel("/Users/roti/voices/jake.onnx");
        SoulVoiceSettings s = SoulVoiceSettings.from(config);
        assertTrue(s.valid());
    }

    @Test
    void nullConfigYieldsDisabledInvalid() {
        SoulVoiceSettings s = SoulVoiceSettings.from(null);
        assertFalse(s.enabled());
        assertFalse(s.valid());
    }

    @Test
    void outOfRangeNumbersClampToSaneBounds() {
        ManualConfig config = new ManualConfig();
        config.setSoulVoiceMaxChars(99999);
        config.setSoulVoiceSynthTimeoutMs(50);
        config.setSoulVoiceRadioGain(9.0f);
        SoulVoiceSettings s = SoulVoiceSettings.from(config);
        assertEquals(1000, s.maxChars());
        assertEquals(1000L, s.synthTimeoutMs());
        assertEquals(1.0f, s.radioGain(), 0.0001f);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.SoulVoiceSettingsTest" -q`
Expected: compile failure — `SoulVoiceSettings` does not exist.

- [ ] **Step 3: Add ManualConfig fields + accessors**

In `ManualConfig.java`, next to the existing soul fields, add the private fields:

```java
    // Soul generated-voice (TTS). Default-off; local Piper only. See
    // docs/superpowers/specs/2026-08-24-soul-generated-voice-design.md.
    private boolean soulVoiceEnabled = false;
    private String soulVoicePiperBinary = "";
    private String soulVoiceModel = "";
    private int soulVoiceMaxChars = 400;
    private long soulVoiceSynthTimeoutMs = 8000L;
    private float soulVoiceRadioGain = 0.6f;
```

And after the soul accessors (line ~799), following the existing clamp style:

```java
    // === Soul generated-voice accessors ===

    public boolean isSoulVoiceEnabled() { return soulVoiceEnabled; }
    public void setSoulVoiceEnabled(boolean v) { this.soulVoiceEnabled = v; }

    public String getSoulVoicePiperBinary() { return soulVoicePiperBinary == null ? "" : soulVoicePiperBinary; }
    public void setSoulVoicePiperBinary(String v) { this.soulVoicePiperBinary = v == null ? "" : v.trim(); }

    public String getSoulVoiceModel() { return soulVoiceModel == null ? "" : soulVoiceModel; }
    public void setSoulVoiceModel(String v) { this.soulVoiceModel = v == null ? "" : v.trim(); }

    public int getSoulVoiceMaxChars() { return Math.max(40, Math.min(1000, soulVoiceMaxChars)); }
    public void setSoulVoiceMaxChars(int v) { this.soulVoiceMaxChars = v; }

    public long getSoulVoiceSynthTimeoutMs() { return Math.max(1000L, Math.min(30_000L, soulVoiceSynthTimeoutMs)); }
    public void setSoulVoiceSynthTimeoutMs(long v) { this.soulVoiceSynthTimeoutMs = v; }

    public float getSoulVoiceRadioGain() { return Math.max(0.0f, Math.min(1.0f, soulVoiceRadioGain)); }
    public void setSoulVoiceRadioGain(float v) { this.soulVoiceRadioGain = v; }
```

- [ ] **Step 4: Write SoulVoiceSettings**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.FilingSystem.ManualConfig;

/**
 * Validated, immutable snapshot of the soul generated-voice settings derived from
 * {@link ManualConfig} — mirrors {@code SoulSettings.from}. Structural validation only
 * (non-blank paths); file existence is checked by the enable command and at engine start,
 * never here (this must stay pure).
 */
public record SoulVoiceSettings(boolean enabled, boolean valid, String validationError,
                                 String piperBinary, String voiceModel, int maxChars,
                                 long synthTimeoutMs, float radioGain) {

    public static SoulVoiceSettings from(ManualConfig config) {
        if (config == null) {
            return new SoulVoiceSettings(false, false, "Frens configuration is unavailable.",
                    "", "", 400, 8000L, 0.6f);
        }
        boolean enabled = config.isSoulVoiceEnabled();
        String binary = config.getSoulVoicePiperBinary();
        String model = config.getSoulVoiceModel();
        int maxChars = config.getSoulVoiceMaxChars();
        long timeoutMs = config.getSoulVoiceSynthTimeoutMs();
        float radioGain = config.getSoulVoiceRadioGain();
        if (binary.isBlank()) {
            return new SoulVoiceSettings(enabled, false, "Configure the piper binary path first.",
                    binary, model, maxChars, timeoutMs, radioGain);
        }
        if (model.isBlank()) {
            return new SoulVoiceSettings(enabled, false, "Configure a piper voice model first.",
                    binary, model, maxChars, timeoutMs, radioGain);
        }
        return new SoulVoiceSettings(enabled, true, "", binary, model, maxChars, timeoutMs, radioGain);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.SoulVoiceSettingsTest" -q`
Expected: PASS. (If `new ManualConfig()` fails to construct in the harness, replace the config-based tests with a small package-private `SoulVoiceSettings.of(...)` factory taking plain values and test `from(null)` plus `of`; note it in the commit message.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceSettings.java src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceSettingsTest.java
git commit -m "Soul voice: config fields + validated SoulVoiceSettings snapshot"
```

---

### Task 2: Text sanitizer (pure)

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceSanitizer.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceSanitizerTest.java`

**Interfaces:**
- Produces: `public static Optional<String> sanitize(String text, int maxChars)` — formatting stripped, whitespace collapsed, sentence-boundary truncation, empty → `Optional.empty()`.

- [ ] **Step 1: Write the failing test**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulVoiceSanitizerTest {

    @Test
    void stripsFormattingCodesAndMarkupAndCollapsesWhitespace() {
        assertEquals(Optional.of("Hello there, friend."),
                SoulVoiceSanitizer.sanitize("§7Hello  *there*,\n_friend_.§r", 400));
    }

    @Test
    void truncatesAtASentenceBoundaryPastMaxChars() {
        String text = "First sentence here. Second sentence is much longer and keeps going.";
        // maxChars lands inside the second sentence -> cut back to the end of the first.
        assertEquals(Optional.of("First sentence here."), SoulVoiceSanitizer.sanitize(text, 25));
    }

    @Test
    void hardTruncatesWhenNoSentenceBoundaryExists() {
        String text = "a".repeat(500);
        Optional<String> out = SoulVoiceSanitizer.sanitize(text, 100);
        assertTrue(out.isPresent());
        assertEquals(100, out.get().length());
    }

    @Test
    void emptyBlankAndNullYieldEmpty() {
        assertTrue(SoulVoiceSanitizer.sanitize(null, 400).isEmpty());
        assertTrue(SoulVoiceSanitizer.sanitize("", 400).isEmpty());
        assertTrue(SoulVoiceSanitizer.sanitize("§7§r * _ ", 400).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.SoulVoiceSanitizerTest" -q`
Expected: compile failure — class missing.

- [ ] **Step 3: Implement**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import java.util.Optional;

/** Pure text preparation for synthesis: strip markup, collapse whitespace, bound length. */
public final class SoulVoiceSanitizer {

    private SoulVoiceSanitizer() {
    }

    public static Optional<String> sanitize(String text, int maxChars) {
        if (text == null) {
            return Optional.empty();
        }
        String cleaned = text
                .replaceAll("§.", "")            // Minecraft formatting codes
                .replaceAll("[*_`~#>\\[\\]]", "") // markdown-ish markup
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        if (cleaned.length() <= maxChars) {
            return Optional.of(cleaned);
        }
        String head = cleaned.substring(0, maxChars);
        int lastBoundary = Math.max(head.lastIndexOf(". "),
                Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
        if (cleaned.charAt(maxChars - 1) == '.' || cleaned.charAt(maxChars - 1) == '!'
                || cleaned.charAt(maxChars - 1) == '?') {
            return Optional.of(head);
        }
        if (lastBoundary > 0) {
            return Optional.of(head.substring(0, lastBoundary + 1));
        }
        return Optional.of(head);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.SoulVoiceSanitizerTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceSanitizer.java src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceSanitizerTest.java
git commit -m "Soul voice: pure sanitizer (markup strip, sentence-boundary truncation)"
```

---

### Task 3: WAV→PCM parser + chunker + reassembly buffer (pure)

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoicePcm.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoicePcmTest.java`

**Interfaces:**
- Produces:
  - `public record PcmAudio(int sampleRate, byte[] data)` (16-bit mono PCM only).
  - `public static Optional<PcmAudio> parseWav(byte[] wavBytes)` — canonical RIFF/WAVE with a `fmt ` chunk (PCM format 1, 1 channel, 16 bits) and a `data` chunk; anything else → empty.
  - `public static List<byte[]> chunk(byte[] pcm, int maxChunkBytes)`.
  - `public static final class Reassembly` with `Optional<byte[]> accept(UUID correlationId, int chunkIndex, int chunkCount, byte[] data, long nowMs)` (returns joined PCM when the last chunk of a set arrives) and `void expireStale(long nowMs)` dropping sets older than 10_000 ms.

- [ ] **Step 1: Write the failing test**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulVoicePcmTest {

    private static byte[] wav(int sampleRate, short channels, short bits, byte[] pcm) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()); header.putInt(36 + pcm.length); header.put("WAVE".getBytes());
        header.put("fmt ".getBytes()); header.putInt(16); header.putShort((short) 1);
        header.putShort(channels); header.putInt(sampleRate);
        header.putInt(sampleRate * channels * bits / 8);
        header.putShort((short) (channels * bits / 8)); header.putShort(bits);
        header.put("data".getBytes()); header.putInt(pcm.length);
        out.write(header.array()); out.write(pcm);
        return out.toByteArray();
    }

    @Test
    void parsesCanonicalMono16WavToPcm() throws Exception {
        byte[] pcm = {1, 2, 3, 4, 5, 6};
        Optional<SoulVoicePcm.PcmAudio> parsed = SoulVoicePcm.parseWav(wav(22050, (short) 1, (short) 16, pcm));
        assertTrue(parsed.isPresent());
        assertEquals(22050, parsed.get().sampleRate());
        assertArrayEquals(pcm, parsed.get().data());
    }

    @Test
    void rejectsStereoNon16BitAndGarbage() throws Exception {
        assertTrue(SoulVoicePcm.parseWav(wav(22050, (short) 2, (short) 16, new byte[4])).isEmpty());
        assertTrue(SoulVoicePcm.parseWav(wav(22050, (short) 1, (short) 8, new byte[4])).isEmpty());
        assertTrue(SoulVoicePcm.parseWav(new byte[] {1, 2, 3}).isEmpty());
        assertTrue(SoulVoicePcm.parseWav(null).isEmpty());
    }

    @Test
    void chunksSplitAndRoundTripThroughReassembly() {
        byte[] pcm = new byte[100_000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        List<byte[]> chunks = SoulVoicePcm.chunk(pcm, 32_768);
        assertEquals(4, chunks.size());

        SoulVoicePcm.Reassembly reassembly = new SoulVoicePcm.Reassembly();
        UUID id = UUID.randomUUID();
        Optional<byte[]> joined = Optional.empty();
        for (int i = 0; i < chunks.size(); i++) {
            joined = reassembly.accept(id, i, chunks.size(), chunks.get(i), 0L);
        }
        assertTrue(joined.isPresent());
        assertArrayEquals(pcm, joined.get());
    }

    @Test
    void staleIncompleteSetsAreExpired() {
        SoulVoicePcm.Reassembly reassembly = new SoulVoicePcm.Reassembly();
        UUID id = UUID.randomUUID();
        reassembly.accept(id, 0, 2, new byte[] {1}, 0L);
        reassembly.expireStale(10_001L);
        // Completing after expiry must NOT produce audio from the dropped first chunk.
        assertTrue(reassembly.accept(id, 1, 2, new byte[] {2}, 10_002L).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.SoulVoicePcmTest" -q`
Expected: compile failure.

- [ ] **Step 3: Implement `SoulVoicePcm`**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Pure PCM plumbing: canonical-WAV parsing, chunking, and client-side chunk reassembly. */
public final class SoulVoicePcm {

    private static final long STALE_SET_MS = 10_000L;

    public record PcmAudio(int sampleRate, byte[] data) {
    }

    private SoulVoicePcm() {
    }

    public static Optional<PcmAudio> parseWav(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 44) {
            return Optional.empty();
        }
        ByteBuffer buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] tag = new byte[4];
        buf.get(tag);
        if (!"RIFF".equals(new String(tag))) return Optional.empty();
        buf.getInt(); // riff size
        buf.get(tag);
        if (!"WAVE".equals(new String(tag))) return Optional.empty();

        int sampleRate = -1;
        boolean formatOk = false;
        while (buf.remaining() >= 8) {
            buf.get(tag);
            int size = buf.getInt();
            String chunkId = new String(tag);
            if (size < 0 || size > buf.remaining()) return Optional.empty();
            if ("fmt ".equals(chunkId)) {
                if (size < 16) return Optional.empty();
                int start = buf.position();
                short format = buf.getShort();
                short channels = buf.getShort();
                sampleRate = buf.getInt();
                buf.getInt();   // byte rate
                buf.getShort(); // block align
                short bits = buf.getShort();
                formatOk = format == 1 && channels == 1 && bits == 16;
                buf.position(start + size);
            } else if ("data".equals(chunkId)) {
                if (!formatOk || sampleRate <= 0) return Optional.empty();
                byte[] pcm = new byte[size];
                buf.get(pcm);
                return Optional.of(new PcmAudio(sampleRate, pcm));
            } else {
                buf.position(buf.position() + size);
            }
        }
        return Optional.empty();
    }

    public static List<byte[]> chunk(byte[] pcm, int maxChunkBytes) {
        List<byte[]> chunks = new ArrayList<>();
        if (pcm == null || pcm.length == 0 || maxChunkBytes <= 0) {
            return chunks;
        }
        for (int offset = 0; offset < pcm.length; offset += maxChunkBytes) {
            int len = Math.min(maxChunkBytes, pcm.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(pcm, offset, chunk, 0, len);
            chunks.add(chunk);
        }
        return chunks;
    }

    /** Client-side chunk collector. Not thread-safe; confine to one thread. */
    public static final class Reassembly {
        private record PendingSet(byte[][] chunks, long firstSeenMs) {
        }

        private final Map<UUID, PendingSet> pending = new HashMap<>();

        public Optional<byte[]> accept(UUID correlationId, int chunkIndex, int chunkCount,
                                        byte[] data, long nowMs) {
            if (correlationId == null || data == null || chunkCount <= 0
                    || chunkIndex < 0 || chunkIndex >= chunkCount) {
                return Optional.empty();
            }
            PendingSet set = pending.computeIfAbsent(correlationId,
                    id -> new PendingSet(new byte[chunkCount][], nowMs));
            if (set.chunks().length != chunkCount) {
                pending.remove(correlationId);
                return Optional.empty();
            }
            set.chunks()[chunkIndex] = data;
            int total = 0;
            for (byte[] c : set.chunks()) {
                if (c == null) return Optional.empty();
                total += c.length;
            }
            pending.remove(correlationId);
            byte[] joined = new byte[total];
            int offset = 0;
            for (byte[] c : set.chunks()) {
                System.arraycopy(c, 0, joined, offset, c.length);
                offset += c.length;
            }
            return Optional.of(joined);
        }

        public void expireStale(long nowMs) {
            pending.values().removeIf(set -> nowMs - set.firstSeenMs() > STALE_SET_MS);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.SoulVoicePcmTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoicePcm.java src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoicePcmTest.java
git commit -m "Soul voice: pure WAV parse, chunking, and chunk reassembly"
```

---

### Task 4: Gate decision table + restart backoff policy (pure)

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceGate.java`
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/voice/VoiceBackoffPolicy.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceGateTest.java`

**Interfaces:**
- Produces:
  - `public enum SoulVoiceGate.Mode { POSITIONAL, RADIO }`
  - `public static Optional<Mode> decide(boolean voiceEnabled, boolean settingsValid, boolean engineAlive, SoulTypes.Reachability reachability)` — LOCAL→POSITIONAL, REMOTE→RADIO, anything else / any false → empty.
  - `VoiceBackoffPolicy` (instance, plain values only): `long onFailure(long nowMs)` returns the restart delay in ms or `-1` for self-disable; `void onSuccess()` resets. Rules: delays 1s → 5s → 15s; a 4th failure inside a 5-minute window returns `-1`; a success or a failure after the window resets the ladder.

- [ ] **Step 1: Write the failing test**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulVoiceGateTest {

    @Test
    void localVoicesPositionalRemoteVoicesRadio() {
        assertEquals(SoulVoiceGate.Mode.POSITIONAL,
                SoulVoiceGate.decide(true, true, true, SoulTypes.Reachability.LOCAL).orElseThrow());
        assertEquals(SoulVoiceGate.Mode.RADIO,
                SoulVoiceGate.decide(true, true, true, SoulTypes.Reachability.REMOTE).orElseThrow());
    }

    @Test
    void anyGateFactFalseOrUnreachableSkipsVoice() {
        assertTrue(SoulVoiceGate.decide(false, true, true, SoulTypes.Reachability.LOCAL).isEmpty());
        assertTrue(SoulVoiceGate.decide(true, false, true, SoulTypes.Reachability.LOCAL).isEmpty());
        assertTrue(SoulVoiceGate.decide(true, true, false, SoulTypes.Reachability.LOCAL).isEmpty());
        assertTrue(SoulVoiceGate.decide(true, true, true, SoulTypes.Reachability.UNREACHABLE).isEmpty());
        assertTrue(SoulVoiceGate.decide(true, true, true, null).isEmpty());
    }

    @Test
    void backoffLadderEscalatesThenSelfDisablesInsideTheWindow() {
        VoiceBackoffPolicy policy = new VoiceBackoffPolicy();
        assertEquals(1_000L, policy.onFailure(0L));
        assertEquals(5_000L, policy.onFailure(10_000L));
        assertEquals(15_000L, policy.onFailure(20_000L));
        assertEquals(-1L, policy.onFailure(30_000L));
    }

    @Test
    void successAndWindowExpiryResetTheLadder() {
        VoiceBackoffPolicy policy = new VoiceBackoffPolicy();
        policy.onFailure(0L);
        policy.onSuccess();
        assertEquals(1_000L, policy.onFailure(1_000L));
        // A failure past the 5-minute window starts fresh.
        assertEquals(1_000L, policy.onFailure(1_000L + 300_001L));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.SoulVoiceGateTest" -q`
Expected: compile failure.

- [ ] **Step 3: Implement both classes**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulTypes;

import java.util.Optional;

/** Pure decision table: does this committed reply get voiced, and in which playback mode. */
public final class SoulVoiceGate {

    public enum Mode { POSITIONAL, RADIO }

    private SoulVoiceGate() {
    }

    public static Optional<Mode> decide(boolean voiceEnabled, boolean settingsValid,
                                         boolean engineAlive, SoulTypes.Reachability reachability) {
        if (!voiceEnabled || !settingsValid || !engineAlive || reachability == null) {
            return Optional.empty();
        }
        return switch (reachability) {
            case LOCAL -> Optional.of(Mode.POSITIONAL);
            case REMOTE -> Optional.of(Mode.RADIO);
            case UNREACHABLE -> Optional.empty();
        };
    }
}
```

```java
package net.wcfcarolina13.GameAI.souls.voice;

/**
 * Engine restart ladder: 1s -> 5s -> 15s, then self-disable (-1) on the 4th failure inside a
 * 5-minute window. Success, or a failure after the window, resets the ladder. Plain values
 * only; the caller supplies time.
 */
public final class VoiceBackoffPolicy {

    private static final long WINDOW_MS = 300_000L;
    private static final long[] DELAYS_MS = {1_000L, 5_000L, 15_000L};

    private int failuresInWindow;
    private long windowStartMs;

    public synchronized long onFailure(long nowMs) {
        if (failuresInWindow == 0 || nowMs - windowStartMs > WINDOW_MS) {
            failuresInWindow = 0;
            windowStartMs = nowMs;
        }
        failuresInWindow++;
        if (failuresInWindow > DELAYS_MS.length) {
            return -1L;
        }
        return DELAYS_MS[failuresInWindow - 1];
    }

    public synchronized void onSuccess() {
        failuresInWindow = 0;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.SoulVoiceGateTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceGate.java src/main/java/net/wcfcarolina13/GameAI/souls/voice/VoiceBackoffPolicy.java src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceGateTest.java
git commit -m "Soul voice: pure gate decision table + engine restart backoff policy"
```

---

### Task 5: Piper engine (subprocess wrapper)

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceEngine.java`
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/voice/PiperVoiceEngine.java`
- Test: extend `src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceGateTest.java` is NOT the home — create `src/test/java/net/wcfcarolina13/GameAI/souls/voice/PiperCommandTest.java`

**Interfaces:**
- Produces:
  - `public interface SoulVoiceEngine extends AutoCloseable { CompletableFuture<byte[]> synthesize(String text, String voiceId); boolean alive(); void close(); }` (future completes with WAV bytes or exceptionally).
  - `PiperVoiceEngine implements SoulVoiceEngine` — one long-lived subprocess; `public static List<String> command(String binary, String modelPath, String outputDir)` is the pure, tested argv builder.
- **MANDATORY:** before finalizing, run the installed piper binary with `--help` (`"$(command -v piper)" --help` or the configured path) and confirm: the flag names for model and output-directory mode, that piper reads one line of text per utterance from stdin, and that it prints each output WAV path to stdout. Adjust `command(...)` and the stdout-reading loop to the real flags. If the binary is not installed on this machine yet, keep the documented flags (`--model <path> --output_dir <dir>`) and note in the commit message that live verification moves to the runbook's first voice-on run.

- [ ] **Step 1: Write the failing argv test**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiperCommandTest {

    @Test
    void commandCarriesBinaryModelAndOutputDirInOrder() {
        List<String> cmd = PiperVoiceEngine.command("/opt/homebrew/bin/piper",
                "/voices/jake.onnx", "/tmp/frens-voice");
        assertEquals(List.of("/opt/homebrew/bin/piper",
                "--model", "/voices/jake.onnx",
                "--output_dir", "/tmp/frens-voice"), cmd);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.PiperCommandTest" -q`
Expected: compile failure.

- [ ] **Step 3: Implement the interface and engine**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import java.util.concurrent.CompletableFuture;

/** One synthesis backend. Implementations own their process/resource lifecycle. */
public interface SoulVoiceEngine extends AutoCloseable {
    /**
     * Synthesizes one utterance to WAV bytes. {@code voiceId} is the per-profile voice seam —
     * v1 implementations may ignore it (single configured voice).
     */
    CompletableFuture<byte[]> synthesize(String text, String voiceId);

    boolean alive();

    @Override
    void close();
}
```

`PiperVoiceEngine` core (single-threaded engine executor owns ALL process interaction; the
public methods only submit to it):

```java
package net.wcfcarolina13.GameAI.souls.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One long-lived Piper subprocess. Framing: write one sanitized line to stdin, Piper writes a
 * WAV file into {@code outputDir} and prints its path to stdout; we read that line, load the
 * bytes, and delete the file. A synthesis timeout kills and restarts the process (a hung
 * engine is not trusted). All process I/O happens on the single engine thread.
 */
public final class PiperVoiceEngine implements SoulVoiceEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    private final String binary;
    private final String modelPath;
    private final long synthTimeoutMs;
    private final Path outputDir;
    private final ExecutorService engineThread =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "frens-soul-voice-engine");
                t.setDaemon(true);
                return t;
            });

    private Process process;
    private Writer stdin;
    private BufferedReader stdout;
    private volatile boolean closed;
    private volatile boolean lastStartFailed;

    public PiperVoiceEngine(String binary, String modelPath, long synthTimeoutMs) throws IOException {
        this.binary = binary;
        this.modelPath = modelPath;
        this.synthTimeoutMs = synthTimeoutMs;
        this.outputDir = Files.createTempDirectory("frens-soul-voice");
    }

    public static List<String> command(String binary, String modelPath, String outputDir) {
        return List.of(binary, "--model", modelPath, "--output_dir", outputDir);
    }

    @Override
    public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        engineThread.submit(() -> {
            try {
                ensureProcess();
                stdin.write(text.replace('\n', ' '));
                stdin.write('\n');
                stdin.flush();
                String wavPath = readLineWithDeadline();
                Path file = Path.of(wavPath.trim());
                byte[] bytes = Files.readAllBytes(file);
                Files.deleteIfExists(file);
                lastStartFailed = false;
                result.complete(bytes);
            } catch (Exception ex) {
                killProcess();
                result.completeExceptionally(ex);
            }
        });
        return result;
    }

    /** Blocking stdout read bounded by the synth deadline, running ON the engine thread. */
    private String readLineWithDeadline() throws Exception {
        long deadline = System.currentTimeMillis() + synthTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (stdout.ready()) {
                String line = stdout.readLine();
                if (line == null) {
                    throw new IOException("piper stdout closed");
                }
                if (!line.isBlank()) {
                    return line;
                }
            } else {
                Thread.sleep(20);
            }
        }
        throw new TimeoutException("piper synthesis exceeded " + synthTimeoutMs + "ms");
    }

    private void ensureProcess() throws IOException {
        if (closed) {
            throw new IOException("engine closed");
        }
        if (process != null && process.isAlive()) {
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(command(binary, modelPath, outputDir.toString()));
        builder.redirectErrorStream(false);
        process = builder.start();
        stdin = new java.io.OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        LOGGER.info("[souls] tts engine started pid={}", process.pid());
    }

    private void killProcess() {
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
    }

    @Override
    public boolean alive() {
        return !closed && !lastStartFailed;
    }

    @Override
    public void close() {
        closed = true;
        engineThread.submit(this::killProcess);
        engineThread.shutdown();
        try {
            if (!engineThread.awaitTermination(1, TimeUnit.SECONDS)) {
                engineThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            engineThread.shutdownNow();
        }
    }
}
```

- [ ] **Step 4: Verify the real piper CLI contract**

Run: `command -v piper && piper --help 2>&1 | head -40` (or the configured binary path).
Confirm/adjust: model flag, output-dir flag, stdin line protocol, stdout path echo. Update
`command(...)` + `PiperCommandTest` expectations if the real flags differ. If piper is not yet
installed, leave as documented and note it in the commit message.

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.PiperCommandTest" -q`
Expected: PASS. Then `./gradlew build -x test -q` to confirm compilation.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceEngine.java src/main/java/net/wcfcarolina13/GameAI/souls/voice/PiperVoiceEngine.java src/test/java/net/wcfcarolina13/GameAI/souls/voice/PiperCommandTest.java
git commit -m "Soul voice: SoulVoiceEngine interface + long-lived Piper subprocess wrapper"
```

---

### Task 6: SpokenListener seam in SoulConversationService

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulConversationService.java` (constructor ~line 64; `commitSpoken` ~line 266)
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulConversationServiceTest.java`

**Interfaces:**
- Produces: `public interface SoulConversationService.SpokenListener { void onSpoken(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token, String text); }`; new 8-arg constructor `SoulConversationService(store, prompts, scheduler, provider, validator, delivery, settings, spokenListener)`; the existing 7-arg constructor delegates with a no-op listener. Listener fires exactly once per delivered turn, from `commitSpoken`, wrapped so a throwing listener can never affect the turn.

- [ ] **Step 1: Write the failing test** (add to `SoulConversationServiceTest`, using its existing fixtures — `turn`, `delivery`, `store`, `scheduler`, `provider`; construct a listener-armed service locally)

```java
    // === Additional coverage: SpokenListener seam (voice subscription point) ===

    @Test
    void spokenListenerFiresExactlyOncePerDeliveredTurnWithTheValidatedText() throws Exception {
        List<String> spoken = new ArrayList<>();
        SoulConversationService listening = new SoulConversationService(store,
                new SoulPromptAssembler(), scheduler, provider, new SoulResponseValidator(),
                delivery, new SoulSettings(true, true, "", "ollama", "test-model",
                        URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8),
                (t, token, text) -> spoken.add(text));
        delivery.completeNext(true);

        listening.submit(turn).get(2, SECONDS);

        assertEquals(List.of("We're steady."), spoken);
    }

    @Test
    void spokenListenerNeverFiresOnFailedDelivery() throws Exception {
        List<String> spoken = new ArrayList<>();
        SoulConversationService listening = new SoulConversationService(store,
                new SoulPromptAssembler(), scheduler, provider, new SoulResponseValidator(),
                delivery, new SoulSettings(true, true, "", "ollama", "test-model",
                        URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8),
                (t, token, text) -> spoken.add(text));
        delivery.completeNext(false);

        listening.submit(turn).get(2, SECONDS);

        assertEquals(List.of(), spoken);
    }
```

(Add imports `java.util.ArrayList` / `java.net.URI` / `java.time.Duration` if not present; the fixture already imports most.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.SoulConversationServiceTest" -q`
Expected: compile failure — no 8-arg constructor.

- [ ] **Step 3: Implement the seam**

In `SoulConversationService`:

```java
    /** Voice subscription point: fired once per turn, only after text is committed as spoken. */
    public interface SpokenListener {
        void onSpoken(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token, String text);
    }

    private static final SpokenListener NO_OP_SPOKEN_LISTENER = (turn, token, text) -> {
    };

    private final SpokenListener spokenListener;
```

Existing 7-arg constructor delegates: `this(store, prompts, scheduler, provider, validator,
delivery, settings, NO_OP_SPOKEN_LISTENER);` — the new 8-arg constructor stores
`this.spokenListener = Objects.requireNonNull(spokenListener, "spokenListener")`.

At the TOP of `commitSpoken` (line ~269, before the store append — text delivery has already
succeeded, which is the spec's subscription point):

```java
        try {
            spokenListener.onSpoken(turn, token, text);
        } catch (RuntimeException ex) {
            LOGGER.warn("[souls] spoken listener threw; voice skipped: {}", ex.toString());
        }
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test -q`
Expected: PASS (386 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulConversationService.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulConversationServiceTest.java
git commit -m "Soul voice: SpokenListener seam fires once per committed spoken turn"
```

---

### Task 7: SoulVoiceService (server orchestration)

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceService.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceServiceTest.java`

**Interfaces:**
- Consumes: `SoulVoiceSettings` (Task 1), `SoulVoiceSanitizer` (Task 2), `SoulVoicePcm` (Task 3), `SoulVoiceGate`/`VoiceBackoffPolicy` (Task 4), `SoulVoiceEngine` (Task 5), `SoulConversationService.SpokenListener` (Task 6).
- Produces:
  - `public interface VoiceDelivery { void send(UUID playerId, UUID correlationId, UUID botId, SoulVoiceGate.Mode mode, int sampleRate, List<byte[]> pcmChunks); }` — Minecraft-free so the service is unit-testable; the production impl is Task 8.
  - `public final class SoulVoiceService implements SoulConversationService.SpokenListener, AutoCloseable` with constructor `SoulVoiceService(SoulVoiceSettings settings, SoulVoiceEngine engine, VoiceDelivery delivery)`, `public static SoulVoiceService disabled()`, `public boolean engineAlive()`, `void onSpoken(...)`, `void close()`.
  - Worker queue cap 4 (drops with a log line); chunk size 32_768; `[souls] tts correlationId= outcome= synthMs= bytes= chunks=` INFO line per attempt.

- [ ] **Step 1: Write the failing test**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulVoiceServiceTest {

    private record Sent(UUID playerId, UUID correlationId, UUID botId,
                         SoulVoiceGate.Mode mode, int sampleRate, List<byte[]> chunks) {
    }

    private static byte[] tinyWav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(22050).putInt(44100).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }

    private static SoulTypes.AcceptedTurn turn(SoulTypes.Reachability reachability, UUID routingId) {
        UUID botId = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                botId, UUID.randomUUID(), SoulTypes.Channel.DIRECT);
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(botId, "Jake", "minecraft:overworld",
                "plains", 0, 64, 0, true, "day", "clear", 20.0f, 20.0f, 20, 0, "bare hands",
                0, 36, List.of(), "content", "idle", "", "", "", "", false, 0, false, Optional.empty());
        SoulTypes.GroundingSnapshot grounding = new SoulTypes.GroundingSnapshot(
                reachability, bot, Optional.empty(), Instant.now());
        return new SoulTypes.AcceptedTurn(key, "Jake", "Player", "hi", "frens:jake",
                grounding, Instant.now(), routingId);
    }

    private static SoulTypes.TurnToken tokenFor(SoulTypes.AcceptedTurn turn) {
        return new SoulTypes.TurnToken(turn.key(), turn.routingId(), 0L, 1L);
    }

    private static SoulVoiceSettings enabledSettings() {
        return new SoulVoiceSettings(true, true, "", "/bin/piper", "/voices/jake.onnx",
                400, 8000L, 0.6f);
    }

    @Test
    void deliveredLocalTurnIsSynthesizedChunkedAndSentPositional() throws Exception {
        CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        byte[] pcm = new byte[40_000];
        SoulVoiceEngine engine = new SoulVoiceEngine() {
            @Override public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
                return CompletableFuture.completedFuture(tinyWav(pcm));
            }
            @Override public boolean alive() { return true; }
            @Override public void close() { }
        };
        SoulVoiceService service = new SoulVoiceService(enabledSettings(), engine,
                (playerId, correlationId, botId, mode, sampleRate, chunks) -> {
                    sent.add(new Sent(playerId, correlationId, botId, mode, sampleRate, chunks));
                    done.countDown();
                });

        UUID routingId = UUID.randomUUID();
        SoulTypes.AcceptedTurn accepted = turn(SoulTypes.Reachability.LOCAL, routingId);
        service.onSpoken(accepted, tokenFor(accepted), "Hello there.");

        assertTrue(done.await(2, TimeUnit.SECONDS));
        Sent one = sent.get(0);
        assertEquals(routingId, one.correlationId());
        assertEquals(SoulVoiceGate.Mode.POSITIONAL, one.mode());
        assertEquals(22050, one.sampleRate());
        assertEquals(2, one.chunks().size()); // 40_000 bytes at 32_768/chunk
        service.close();
    }

    @Test
    void disabledServiceAndUnreachableTurnsSendNothing() throws Exception {
        CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();
        SoulVoiceService disabled = SoulVoiceService.disabled();
        SoulTypes.AcceptedTurn accepted = turn(SoulTypes.Reachability.LOCAL, UUID.randomUUID());
        disabled.onSpoken(accepted, tokenFor(accepted), "Hello.");

        SoulVoiceService enabled = new SoulVoiceService(enabledSettings(),
                new SoulVoiceEngine() {
                    @Override public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
                        return CompletableFuture.completedFuture(tinyWav(new byte[2]));
                    }
                    @Override public boolean alive() { return true; }
                    @Override public void close() { }
                },
                (playerId, correlationId, botId, mode, sampleRate, chunks) ->
                        sent.add(new Sent(playerId, correlationId, botId, mode, sampleRate, chunks)));
        SoulTypes.AcceptedTurn unreachable = turn(SoulTypes.Reachability.UNREACHABLE, UUID.randomUUID());
        enabled.onSpoken(unreachable, tokenFor(unreachable), "Hello.");
        Thread.sleep(200);
        assertEquals(0, sent.size());
        disabled.close();
        enabled.close();
    }

    @Test
    void engineFailureDropsAudioWithoutThrowing() throws Exception {
        SoulVoiceService service = new SoulVoiceService(enabledSettings(),
                new SoulVoiceEngine() {
                    @Override public CompletableFuture<byte[]> synthesize(String text, String voiceId) {
                        return CompletableFuture.failedFuture(new RuntimeException("boom"));
                    }
                    @Override public boolean alive() { return true; }
                    @Override public void close() { }
                },
                (playerId, correlationId, botId, mode, sampleRate, chunks) -> {
                    throw new AssertionError("must not deliver on engine failure");
                });
        SoulTypes.AcceptedTurn accepted = turn(SoulTypes.Reachability.LOCAL, UUID.randomUUID());
        service.onSpoken(accepted, tokenFor(accepted), "Hello.");
        Thread.sleep(200);
        service.close();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.SoulVoiceServiceTest" -q`
Expected: compile failure.

- [ ] **Step 3: Implement `SoulVoiceService`**

```java
package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulConversationService;
import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The voice subscriber: sanitizes a committed reply, synthesizes it, and hands PCM chunks to a
 * {@link VoiceDelivery}. Every failure drops audio only — nothing here can affect the turn
 * that already delivered its text.
 */
public final class SoulVoiceService implements SoulConversationService.SpokenListener, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");
    private static final int CHUNK_BYTES = 32_768;
    private static final int QUEUE_CAP = 4;

    /** Minecraft-free delivery boundary; production impl sends SoulVoicePayload chunks. */
    public interface VoiceDelivery {
        void send(UUID playerId, UUID correlationId, UUID botId, SoulVoiceGate.Mode mode,
                  int sampleRate, List<byte[]> pcmChunks);
    }

    private static final SoulVoiceService DISABLED =
            new SoulVoiceService(new SoulVoiceSettings(false, false, "disabled", "", "", 400, 8000L, 0.6f),
                    null, (playerId, correlationId, botId, mode, sampleRate, chunks) -> { });

    private final SoulVoiceSettings settings;
    private final SoulVoiceEngine engine; // null only for the disabled instance
    private final VoiceDelivery delivery;
    private final ExecutorService worker;

    public SoulVoiceService(SoulVoiceSettings settings, SoulVoiceEngine engine, VoiceDelivery delivery) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.engine = engine;
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAP),
                r -> {
                    Thread t = new Thread(r, "frens-soul-voice");
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> LOGGER.info("[souls] tts queue full; dropping a line"));
    }

    public static SoulVoiceService disabled() {
        return DISABLED;
    }

    public boolean engineAlive() {
        return engine != null && engine.alive();
    }

    @Override
    public void onSpoken(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token, String text) {
        Optional<SoulVoiceGate.Mode> mode = SoulVoiceGate.decide(settings.enabled(),
                settings.valid(), engineAlive(), turn.grounding().reachability());
        if (mode.isEmpty()) {
            return;
        }
        Optional<String> sanitized = SoulVoiceSanitizer.sanitize(text, settings.maxChars());
        if (sanitized.isEmpty()) {
            logTts(turn.routingId(), "skipped-empty", 0L, 0, 0);
            return;
        }
        worker.execute(() -> runSynthesis(turn, mode.get(), sanitized.get()));
    }

    private void runSynthesis(SoulTypes.AcceptedTurn turn, SoulVoiceGate.Mode mode, String text) {
        long startNanos = System.nanoTime();
        try {
            byte[] wav = engine.synthesize(text, turn.profileId())
                    .get(settings.synthTimeoutMs() + 500L, TimeUnit.MILLISECONDS);
            long synthMs = (System.nanoTime() - startNanos) / 1_000_000L;
            Optional<SoulVoicePcm.PcmAudio> pcm = SoulVoicePcm.parseWav(wav);
            if (pcm.isEmpty()) {
                logTts(turn.routingId(), "failed-badwav", synthMs, wav == null ? 0 : wav.length, 0);
                return;
            }
            List<byte[]> chunks = SoulVoicePcm.chunk(pcm.get().data(), CHUNK_BYTES);
            delivery.send(turn.key().playerId(), turn.routingId(), turn.key().botId(),
                    mode, pcm.get().sampleRate(), chunks);
            logTts(turn.routingId(), "spoken", synthMs, pcm.get().data().length, chunks.size());
        } catch (Exception ex) {
            long synthMs = (System.nanoTime() - startNanos) / 1_000_000L;
            logTts(turn.routingId(), "failed-" + ex.getClass().getSimpleName(), synthMs, 0, 0);
        }
    }

    private static void logTts(UUID correlationId, String outcome, long synthMs, int bytes, int chunks) {
        LOGGER.info("[souls] tts correlationId={} outcome={} synthMs={} bytes={} chunks={}",
                correlationId, outcome, synthMs, bytes, chunks);
    }

    @Override
    public void close() {
        if (this == DISABLED) {
            return;
        }
        worker.shutdownNow();
        if (engine != null) {
            engine.close();
        }
    }
}
```

(Restart/backoff via `VoiceBackoffPolicy` is wired inside `PiperVoiceEngine` restart handling in
Task 5's `ensureProcess` path if desired later; v1 keeps the policy consumed here: on a failed
synthesis call `policy.onFailure(System.currentTimeMillis())`; if it returns `-1`, flip an
internal `selfDisabled` flag consulted by `engineAlive()`. Implement exactly that: add
`private final VoiceBackoffPolicy backoff = new VoiceBackoffPolicy(); private volatile boolean selfDisabled;`
— set `selfDisabled = true` + one WARN when `onFailure` returns `-1`, call `backoff.onSuccess()`
after a successful synthesis, and include `&& !selfDisabled` in `engineAlive()`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "net.wcfcarolina13.GameAI.souls.voice.SoulVoiceServiceTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceService.java src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceServiceTest.java
git commit -m "Soul voice: SoulVoiceService orchestration (gate, sanitize, synth, chunk, deliver)"
```

---

### Task 8: Payload + runtime wiring + production delivery

**Files:**
- Create: `src/main/java/net/wcfcarolina13/network/SoulVoicePayload.java`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java` (payload registration block, ~line 504)
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java` (`start` ~131, `reloadSettings` ~334, `buildPipeline` ~347, `closePipeline` ~391, `Pipeline` record, 5-arg test constructor ~104, `status` ~443)

**Interfaces:**
- Consumes: `SoulVoiceService` + `VoiceDelivery` (Task 7), `SoulVoiceSettings` (Task 1), `PiperVoiceEngine` (Task 5).
- Produces:
  - `SoulVoicePayload(UUID correlationId, UUID botId, byte mode, int sampleRate, int chunkIndex, int chunkCount, byte[] data)` implementing `CustomPayload`, `ID = Identifier.of("frens", "soul_voice")`, `CODEC` via manual `PacketCodec.of(SoulVoicePayload::write, SoulVoicePayload::read)`; mode byte `0=POSITIONAL, 1=RADIO`.
  - `SoulRuntime.Pipeline` gains a 5th component `SoulVoiceService voice`; the 5-arg test constructor supplies `SoulVoiceService.disabled()`; `buildPipeline(SoulSettings, SoulVoiceSettings)`; `closePipeline` closes `pipeline.voice()`; new accessor `public boolean voiceEngineAlive()` and `public SoulVoiceSettings voiceSettings()` for the command task.

- [ ] **Step 1: Write the payload**

```java
package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Server -> Client: one chunk of a synthesized soul-voice line (16-bit mono PCM). */
public record SoulVoicePayload(UUID correlationId, UUID botId, byte mode, int sampleRate,
                                int chunkIndex, int chunkCount, byte[] data)
        implements CustomPayload {

    public static final byte MODE_POSITIONAL = 0;
    public static final byte MODE_RADIO = 1;

    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "soul_voice");
    public static final CustomPayload.Id<SoulVoicePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, SoulVoicePayload> CODEC =
            PacketCodec.of(SoulVoicePayload::write, SoulVoicePayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeUuid(correlationId);
        buf.writeUuid(botId);
        buf.writeByte(mode);
        buf.writeVarInt(sampleRate);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(chunkCount);
        buf.writeByteArray(data);
    }

    private static SoulVoicePayload read(PacketByteBuf buf) {
        return new SoulVoicePayload(buf.readUuid(), buf.readUuid(), buf.readByte(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
```

Note: `PacketCodec.of(BiConsumer<V, B>, Function<B, V>)`'s exact parameter order in 1.21.11 —
check an existing manual codec in the repo or the yarn signature (`javap -cp <named-jar>
net.minecraft.network.codec.PacketCodec | grep " of("`) and match; if only tuple codecs exist in
the repo, compose with `PacketCodec.tuple` limits in mind (7 fields exceeds tuple arity 6 —
manual `of` is the correct form here).

- [ ] **Step 2: Register the payload**

In `Frens.java` next to the existing registrations (line ~504):

```java
        PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.SoulVoicePayload.ID, net.wcfcarolina13.network.SoulVoicePayload.CODEC);
```

- [ ] **Step 3: Wire the runtime**

In `SoulRuntime`:
1. `Pipeline` record: add component `SoulVoiceService voice` (import `net.wcfcarolina13.GameAI.souls.voice.*`).
2. Add field `private volatile SoulVoiceSettings voiceSettings = SoulVoiceSettings.from(null);`
3. `start(server, config)`: before constructing the runtime, keep as-is; inside the private
   production constructor path, thread `SoulVoiceSettings.from(config)` through — change the
   private constructor to `SoulRuntime(SoulSettings, SoulVoiceSettings, SoulStore, Delivery, VoiceDelivery)`
   and `start` to build the production `VoiceDelivery`:

```java
            SoulVoiceService.VoiceDelivery voiceDelivery = (playerId, correlationId, botId, mode, sampleRate, chunks) ->
                    server.execute(() -> {
                        net.minecraft.server.network.ServerPlayerEntity player =
                                server.getPlayerManager().getPlayer(playerId);
                        if (player == null) {
                            return;
                        }
                        byte modeByte = mode == SoulVoiceGate.Mode.POSITIONAL
                                ? SoulVoicePayload.MODE_POSITIONAL : SoulVoicePayload.MODE_RADIO;
                        for (int i = 0; i < chunks.size(); i++) {
                            ServerPlayNetworking.send(player, new SoulVoicePayload(
                                    correlationId, botId, modeByte, sampleRate, i, chunks.size(), chunks.get(i)));
                        }
                    });
```

4. `buildPipeline(SoulSettings settings)` becomes `buildPipeline(SoulSettings settings, SoulVoiceSettings voiceSettings)`:

```java
        SoulVoiceService voice = buildVoiceService(voiceSettings);
        SoulConversationService conversationService = new SoulConversationService(
                store, promptAssembler, scheduler, provider, validator, delivery, settings, voice);
        return new Pipeline(settings, provider, scheduler, conversationService, voice);
```

   with:

```java
    private SoulVoiceService buildVoiceService(SoulVoiceSettings voiceSettings) {
        if (!voiceSettings.enabled() || !voiceSettings.valid()) {
            return SoulVoiceService.disabled();
        }
        try {
            SoulVoiceEngine engine = new PiperVoiceEngine(voiceSettings.piperBinary(),
                    voiceSettings.voiceModel(), voiceSettings.synthTimeoutMs());
            return new SoulVoiceService(voiceSettings, engine, voiceDelivery);
        } catch (Exception ex) {
            LOGGER.warn("[souls] tts engine unavailable, voice disabled: {}", ex.toString());
            return SoulVoiceService.disabled();
        }
    }
```

   (`voiceDelivery` stored as a final field set by the constructors; the 5-arg test constructor
   sets a no-op and uses `SoulVoiceService.disabled()` in its Pipeline — zero test churn.)
5. `reloadSettings(config)`: also refresh `voiceSettings = SoulVoiceSettings.from(config)` and
   pass it to `buildPipeline`.
6. `closePipeline`: add `try { pipeline.voice().close(); } catch (RuntimeException ex) { LOGGER.warn("[souls] voice close failed: {}", ex.toString()); }`.
7. New accessors: `public boolean voiceEngineAlive() { return pipelineRef.get().voice().engineAlive(); }` and `public SoulVoiceSettings voiceSettings() { return voiceSettings; }`.

- [ ] **Step 4: Build + full suite**

Run: `./gradlew build -q`
Expected: compiles; all tests pass (the 5-arg test constructor path still compiles because the
Pipeline construction inside it supplies `SoulVoiceService.disabled()`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/network/SoulVoicePayload.java src/main/java/net/wcfcarolina13/Frens.java src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java
git commit -m "Soul voice: payload, production delivery, and runtime/pipeline wiring"
```

---

### Task 9: /bot soul voice command

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java` (subcommand tree at `literal("soul")`, line ~66; follow the existing `model` subcommand's persist-then-`reloadSettings` pattern at line ~189)
- Test: `src/test/java/net/wcfcarolina13/Commands/BotSoulCommandsTest.java`

**Interfaces:**
- Consumes: `SoulRuntime.reloadSettings(ManualConfig)`, `SoulRuntime.voiceSettings()`, `SoulRuntime.voiceEngineAlive()`, `Frens.CONFIG` setters from Task 1.
- Produces: `/bot soul voice on|off|status`; pure helper `static Optional<String> validateVoicePaths(String binary, String model, Predicate<String> isFile)` returning a human error or empty when ok.

- [ ] **Step 1: Write the failing test** (add to `BotSoulCommandsTest`, matching its existing pure-helper test style)

```java
    @Test
    void voicePathValidationReportsTheFirstConcreteProblem() {
        java.util.function.Predicate<String> exists = path -> path.equals("/ok/piper") || path.equals("/ok/jake.onnx");
        assertTrue(BotSoulCommands.validateVoicePaths("/ok/piper", "/ok/jake.onnx", exists).isEmpty());
        assertEquals("Piper binary not found: /missing/piper",
                BotSoulCommands.validateVoicePaths("/missing/piper", "/ok/jake.onnx", exists).orElseThrow());
        assertEquals("Voice model not found: /missing/jake.onnx",
                BotSoulCommands.validateVoicePaths("/ok/piper", "/missing/jake.onnx", exists).orElseThrow());
        assertEquals("Configure the piper binary path first.",
                BotSoulCommands.validateVoicePaths("", "/ok/jake.onnx", exists).orElseThrow());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "net.wcfcarolina13.Commands.BotSoulCommandsTest" -q`
Expected: compile failure — helper missing.

- [ ] **Step 3: Implement helper + subcommand**

```java
    static java.util.Optional<String> validateVoicePaths(String binary, String model,
                                                          java.util.function.Predicate<String> isFile) {
        if (binary == null || binary.isBlank()) {
            return java.util.Optional.of("Configure the piper binary path first.");
        }
        if (model == null || model.isBlank()) {
            return java.util.Optional.of("Configure a piper voice model first.");
        }
        if (!isFile.test(binary)) {
            return java.util.Optional.of("Piper binary not found: " + binary);
        }
        if (!isFile.test(model)) {
            return java.util.Optional.of("Voice model not found: " + model);
        }
        return java.util.Optional.empty();
    }
```

Subcommand tree appended under `literal("soul")`, mirroring the existing style:

```java
                .then(CommandManager.literal("voice")
                        .then(CommandManager.literal("on").executes(ctx -> {
                            var problem = validateVoicePaths(
                                    Frens.CONFIG.getSoulVoicePiperBinary(),
                                    Frens.CONFIG.getSoulVoiceModel(),
                                    path -> java.nio.file.Files.isRegularFile(java.nio.file.Path.of(path)));
                            if (problem.isPresent()) {
                                ctx.getSource().sendError(Text.literal(problem.get()));
                                return 0;
                            }
                            Frens.CONFIG.setSoulVoiceEnabled(true);
                            Frens.CONFIG.save();
                            return reloadAndReport(ctx.getSource(), "Soul voice enabled.");
                        }))
                        .then(CommandManager.literal("off").executes(ctx -> {
                            Frens.CONFIG.setSoulVoiceEnabled(false);
                            Frens.CONFIG.save();
                            return reloadAndReport(ctx.getSource(), "Soul voice disabled.");
                        }))
                        .then(CommandManager.literal("status").executes(ctx -> {
                            var maybeRuntime = SoulRuntime.current();
                            if (maybeRuntime.isEmpty()) {
                                ctx.getSource().sendFeedback(() -> Text.literal("Soul system: runtime not currently running."), false);
                                return 1;
                            }
                            var vs = maybeRuntime.get().voiceSettings();
                            boolean alive = maybeRuntime.get().voiceEngineAlive();
                            String line = "Soul voice: " + (vs.enabled() ? "on" : "off")
                                    + " valid=" + vs.valid()
                                    + (vs.valid() ? "" : " (" + vs.validationError() + ")")
                                    + " engineAlive=" + alive;
                            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
                            return 1;
                        })))
```

`reloadAndReport` = whatever the existing config-change subcommands use at line ~189 (persist
already done, then `runtime.reloadSettings(Frens.CONFIG)` and report on the server thread) —
reuse that exact private helper; if it has a different name, call it identically to how
`model` does. Follow the file's existing use of `Frens.CONFIG.save()` — if the file persists
config differently (e.g. a `persistConfig()` helper), copy that pattern instead.

- [ ] **Step 4: Run the full suite + build**

Run: `./gradlew build -q`
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java src/test/java/net/wcfcarolina13/Commands/BotSoulCommandsTest.java
git commit -m "Soul voice: /bot soul voice on|off|status with up-front path validation"
```

---

### Task 10: Client playback (own OpenAL context) + receiver

**Files:**
- Create: `src/client/java/net/wcfcarolina13/GraphicalUserInterface/SoulVoiceClientPlayer.java` — **check first** where client-only classes live in this repo (`FrensClient.java`'s package and source set; if there is no split `src/client` source set, put it in `src/main/java/net/wcfcarolina13/ui/SoulVoiceClientPlayer.java` beside other client-side classes and guard with the same client-only usage pattern the repo uses for screens).
- Modify: `src/main/java/net/wcfcarolina13/FrensClient.java` (receiver registration beside `BasesListPayload.ID` at ~768; client tick hook registration)

**Interfaces:**
- Consumes: `SoulVoicePayload` (Task 8), `SoulVoicePcm.Reassembly` (Task 3).
- Produces: `SoulVoiceClientPlayer` with static `void onPayload(SoulVoicePayload payload)` (any thread → enqueues), `void onClientTick(MinecraftClient client)` (updates listener + source positions, expires stale chunk sets), `void stopAll()` (disconnect), `void shutdown()`.

- [ ] **Step 1: Implement the audio output**

Core structure (all AL calls on one dedicated daemon thread whose loop drains a task queue —
LWJGL AL contexts are current per-thread, so owning our own device+context on our own thread
never touches Minecraft's sound engine):

```java
public final class SoulVoiceClientPlayer {

    private record ActiveVoice(UUID botId, int source, int buffer, byte mode) {
    }

    private static final java.util.concurrent.BlockingQueue<Runnable> AL_TASKS = new java.util.concurrent.LinkedBlockingQueue<>();
    private static final SoulVoicePcm.Reassembly REASSEMBLY = new SoulVoicePcm.Reassembly();
    private static final java.util.Map<java.util.UUID, ActiveVoice> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long device;
    private static volatile long context;
    private static volatile boolean started;

    public static void onPayload(net.wcfcarolina13.network.SoulVoicePayload payload) {
        long now = System.currentTimeMillis();
        java.util.Optional<byte[]> pcm;
        synchronized (REASSEMBLY) {
            pcm = REASSEMBLY.accept(payload.correlationId(), payload.chunkIndex(),
                    payload.chunkCount(), payload.data(), now);
        }
        pcm.ifPresent(bytes -> alTask(() -> play(payload.botId(), payload.mode(), payload.sampleRate(), bytes)));
    }

    private static void play(java.util.UUID botId, byte mode, int sampleRate, byte[] pcm) {
        ensureContext();
        stopForOnAlThread(botId);
        int buffer = org.lwjgl.openal.AL10.alGenBuffers();
        java.nio.ByteBuffer data = org.lwjgl.BufferUtils.createByteBuffer(pcm.length).put(pcm).flip();
        org.lwjgl.openal.AL10.alBufferData(buffer, org.lwjgl.openal.AL10.AL_FORMAT_MONO16, data, sampleRate);
        int source = org.lwjgl.openal.AL10.alGenSources();
        org.lwjgl.openal.AL10.alSourcei(source, org.lwjgl.openal.AL10.AL_BUFFER, buffer);
        if (mode == net.wcfcarolina13.network.SoulVoicePayload.MODE_RADIO) {
            org.lwjgl.openal.AL10.alSourcei(source, org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE, org.lwjgl.openal.AL10.AL_TRUE);
            org.lwjgl.openal.AL10.alSource3f(source, org.lwjgl.openal.AL10.AL_POSITION, 0f, 0f, 0f);
        }
        org.lwjgl.openal.AL10.alSourcef(source, org.lwjgl.openal.AL10.AL_GAIN, currentGain(mode));
        org.lwjgl.openal.AL10.alSourcePlay(source);
        ACTIVE.put(botId, new ActiveVoice(botId, source, buffer, mode));
    }
```

Plus, with the same AL-thread confinement:
- `ensureContext()`: `device = ALC10.alcOpenDevice((ByteBuffer) null); context = ALC10.alcCreateContext(device, (IntBuffer) null); ALC10.alcMakeContextCurrent(context); AL.createCapabilities(ALC.createCapabilities(device));` — once, lazily, on the AL thread.
- `alTask(Runnable)`: enqueue; the AL thread loop (started lazily, daemon, name `frens-soul-voice-al`) drains `AL_TASKS` with `take()`.
- `onClientTick(MinecraftClient client)`: every tick enqueue an update task capturing: camera position/orientation vectors, each active bot's entity position (`client.world.getPlayerByUuid(botId)`, null → leave last position), and the volume factor `client.options.getSoundVolume(SoundCategory.MASTER) * client.options.getSoundVolume(SoundCategory.PLAYERS)`. The AL task sets `AL10.alListener3f(AL_POSITION, …)` + `alListenerfv(AL_ORIENTATION, …)` (only when any positional voice is active), updates each positional source's `AL_POSITION`, refreshes gains, and deletes stopped sources/buffers (`AL_SOURCE_STATE != AL_PLAYING` → `alDeleteSources`/`alDeleteBuffers`, remove from `ACTIVE`). Also `REASSEMBLY.expireStale(now)` under its lock. Radio gain multiplies `voiceRadioGain`... the client does not know the server's configured gain — bake the radio attenuation server-side? No: mode-based constant `0.6f` client-side is acceptable for v1; note it.
- `stopAll()` / `shutdown()` enqueue stop/delete of everything; `shutdown` also destroys context+device.

- [ ] **Step 2: Register receiver + tick hook in FrensClient**

Beside the existing receivers (~line 768):

```java
        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.SoulVoicePayload.ID, (payload, context) ->
                net.wcfcarolina13.ui.SoulVoiceClientPlayer.onPayload(payload));
```

And with the client tick events the file already registers (find its existing
`ClientTickEvents.END_CLIENT_TICK.register(...)` and add):

```java
        ClientTickEvents.END_CLIENT_TICK.register(client ->
                net.wcfcarolina13.ui.SoulVoiceClientPlayer.onClientTick(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                net.wcfcarolina13.ui.SoulVoiceClientPlayer.stopAll());
```

(Adjust the package in these snippets to wherever the class landed per this task's Files note.)

- [ ] **Step 3: Build**

Run: `./gradlew build -q`
Expected: green — no unit tests for AL code; correctness is the runbook's job.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/ui/SoulVoiceClientPlayer.java src/main/java/net/wcfcarolina13/FrensClient.java
git commit -m "Soul voice: client OpenAL playback (own device/context) + payload receiver"
```

---

### Task 11: Runbook, changelog, version bump, deploy readiness

**Files:**
- Modify: `docs/testing/SOUL_COMMUNICATION_PILOT.md` (manual section)
- Modify: `changelog.md` (new top entry)
- Modify: `gradle.properties` (`mod_version` → next patch)

**Interfaces:** none — documentation and release mechanics.

- [ ] **Step 1: Add manual runbook cases**

Append to the manual section of `SOUL_COMMUNICATION_PILOT.md`:

```markdown
### Generated voice (v1, off by default)

Setup: install piper + one voice model locally; set `soulVoicePiperBinary` and
`soulVoiceModel` in the config; `/bot soul voice on` (expect up-front path validation).

- [ ] **Voice disabled baseline** — with `soulVoiceEnabled=false`, DM Jake: behavior identical
  to pre-voice builds; no `[souls] tts` lines.
- [ ] **Local positional voice** — stand near Jake, DM him: text reply first, then his voice
  from his position; walk while he speaks — audio follows him. Log shows
  `[souls] tts correlationId=<same routingId as the turn> outcome=spoken`.
- [ ] **Remote radio voice** — DM from far away (REMOTE reachability): flat, quieter voice.
- [ ] **Engine kill mid-session** — `pkill -f piper` then DM: text still arrives, one WARN,
  voice recovers (restart) or self-disables after repeated failures.
- [ ] **Timeout** — set `soulVoiceSynthTimeoutMs=1000` with a long reply: that line's audio is
  dropped (`outcome=failed-TimeoutException`), text unaffected.
- [ ] **Volume** — master/players volume sliders scale the voice.
```

- [ ] **Step 2: Changelog entry (top of `changelog.md`)**

```markdown
## Soul generated voice v1 (2026-08-XX)

Per `docs/superpowers/specs/2026-08-24-soul-generated-voice-design.md`: Jake's committed soul
replies are synthesized locally with Piper (CPU, mod-owned long-lived subprocess) and played
client-side through a dedicated OpenAL context — positional from his body when LOCAL, flat
quieter "radio" when REMOTE. Text-first always: voice subscribes at the commit-spoken point via
the new `SoulConversationService.SpokenListener` seam and any failure drops audio only.
Off by default (`/bot soul voice on|off|status`, up-front path validation). `[souls] tts`
lines join the turn's routingId chain with synth time kept separate from LLM/delivery time.
```

- [ ] **Step 3: Bump version, full build, deploy check**

```bash
# bump mod_version patch in gradle.properties first
./gradlew build -q
pgrep -f "net.minecraft.client.main.Main" >/dev/null && echo "ABORT: game running" || echo "safe to deploy"
```

Follow the repo's standard deploy block from CLAUDE.md only if the game is closed.

- [ ] **Step 4: Commit**

```bash
git add docs/testing/SOUL_COMMUNICATION_PILOT.md changelog.md gradle.properties
git commit -m "Soul voice: runbook cases, changelog, version bump"
```

---

## Self-Review Notes

- **Spec coverage:** settings/gating (T1, T4, T9), sanitizer (T2), PCM/chunk/reassembly (T3),
  engine + subprocess lifecycle + backoff (T4, T5, T7), commit-spoken subscription (T6),
  orchestration + queue cap + tts logging (T7), payload/transport/runtime wiring +
  SERVER_STOPPING-equivalent close via `closePipeline`+`stop()` (T8), command (T9), client
  playback + entity-fallback-to-radio via mode handling and missing-entity position hold (T10),
  runbook/acceptance (T11). Voice-id seam: `SoulVoiceEngine.synthesize(text, voiceId)` carries
  `turn.profileId()` (T5/T7).
- **Known deviations to confirm at execution time:** `PacketCodec.of` parameter order (T8 step
  1 note); piper CLI flags (T5 step 4, mandatory); client class location/source set (T10);
  radio gain applied client-side as a constant rather than the server's configured
  `voiceRadioGain` — if that bothers testing, add the gain as a payload float field in T8.
- **Type consistency:** `SoulVoiceGate.Mode` used by T7 service, T8 delivery + payload byte
  mapping, T10 client; `SoulVoicePcm.Reassembly.accept(...)` signature identical in T3 and T10;
  `SoulVoiceSettings` accessors identical in T1, T7, T8, T9.
