# Dialogue Pacing Sliders + Active Banter — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Four per-stream frequency sliders (scripted lines, idle banter, active banter, local chime-ins) and a new "active banter" LLM scene that fires while companions are working.

**Architecture:** One pure `DialoguePacing` helper turns a 0–100 rate into a cooldown/chance multiplier; scripted services call it at their cooldown sites, the two soul directors receive `IntSupplier` rates by injection. Active banter is a second lane inside `SoulBanterDirector` producing a new `SceneKind.WORK` whose prompt directive describes what each bot is doing. A small vanilla-widget screen edits the four rates and autosaves.

**Tech Stack:** Java 21, Fabric 0.18.4 / Minecraft 1.21.11 (yarn), JUnit 5 (`./gradlew test --tests '<pattern>'`), Jackson-mapped `settings.json5` via `ManualConfig`.

**Spec:** `docs/superpowers/specs/2026-08-29-frens-dialogue-pacing-design.md`

## Global Constraints

- Souls package (`GameAI/souls/**`) never references `Frens` — enablement/rates arrive as injected suppliers.
- Rates are ints 0–100, default 50 = today's cadence; `m(rate) = 4^((50 − rate)/50)`; cooldowns × m, chances ÷ m clamped to [0,1]; first-scene delays are never scaled.
- Sliders never mean "off"; toggles stay the kill switches.
- Every code change: `./gradlew build -x test` clean; relevant `--tests` green; one commit per task; `changelog.md` entry in the final task.
- Deploy only after the pre-deploy check in `CLAUDE.md` (`pgrep -f "net.minecraft.client.main.Main"` must find nothing); bump `mod_version` first.
- Yarn API facts verified from `mappings.tiny`: `SliderWidget(int x, int y, int w, int h, Text, double value)`, `protected double value`, `protected abstract void updateMessage()`, `protected abstract void applyValue()`.

---

### Task 1: Config fields + `DialoguePacing` (pure) + tests

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java` (fields near line 119; getters near line 862)
- Create: `src/main/java/net/wcfcarolina13/GameAI/services/dialogue/DialoguePacing.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/services/dialogue/DialoguePacingTest.java`

**Interfaces:**
- Produces: `DialoguePacing.Stream {SCRIPTED, BANTER_IDLE, BANTER_ACTIVE, LOCAL}`, `static double multiplier(int rate)`, `static long scaledCooldown(int rate, long baseMs)`, `static double scaledChance(int rate, double base)`, `static String describe(int rate, long minMs, long maxMs)`, `static long scaledCooldown(Stream, long)`, `static double scaledChance(Stream, double)`, `static int rate(Stream)`.
- Produces on `ManualConfig`: `getDialogueScriptedRate/set…`, `getSoulBanterIdleRate/set…`, `getSoulBanterActiveRate/set…`, `getSoulLocalRate/set…` (int, clamped 0–100), `isSoulBanterActiveEnabled/setSoulBanterActiveEnabled`.

- [ ] **Step 1: Write the failing test**

```java
package net.wcfcarolina13.GameAI.services.dialogue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialoguePacingTest {

    @Test
    void multiplierEndpointsAndMidpoint() {
        assertEquals(4.0, DialoguePacing.multiplier(0), 1e-9);
        assertEquals(1.0, DialoguePacing.multiplier(50), 1e-9);
        assertEquals(0.25, DialoguePacing.multiplier(100), 1e-9);
        assertEquals(4.0, DialoguePacing.multiplier(-20), 1e-9, "clamped below");
        assertEquals(0.25, DialoguePacing.multiplier(140), 1e-9, "clamped above");
    }

    @Test
    void multiplierIsMonotonicallyDecreasing() {
        double previous = Double.MAX_VALUE;
        for (int rate = 0; rate <= 100; rate++) {
            double m = DialoguePacing.multiplier(rate);
            assertTrue(m < previous, "rate " + rate);
            previous = m;
        }
    }

    @Test
    void cooldownScalesAndChanceInverselyScalesWithClamp() {
        assertEquals(240_000L, DialoguePacing.scaledCooldown(0, 60_000L));
        assertEquals(60_000L, DialoguePacing.scaledCooldown(50, 60_000L));
        assertEquals(15_000L, DialoguePacing.scaledCooldown(100, 60_000L));
        assertEquals(0.15, DialoguePacing.scaledChance(0, 0.6), 1e-9);
        assertEquals(0.6, DialoguePacing.scaledChance(50, 0.6), 1e-9);
        assertEquals(1.0, DialoguePacing.scaledChance(100, 0.6), 1e-9, "clamped to 1");
        assertEquals(0.0, DialoguePacing.scaledChance(100, 0.0), 1e-9);
    }

    @Test
    void describeReadsAsAHumanBand() {
        assertEquals("every ~8–15 min", DialoguePacing.describe(50, 8 * 60_000L, 15 * 60_000L));
        assertEquals("every ~2–4 min", DialoguePacing.describe(100, 8 * 60_000L, 15 * 60_000L));
        assertEquals("every ~32–60 min", DialoguePacing.describe(0, 8 * 60_000L, 15 * 60_000L));
        assertEquals("every ~15–30 s", DialoguePacing.describe(100, 60_000L, 120_000L));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*DialoguePacingTest*' -q`
Expected: compilation failure — `DialoguePacing` does not exist.

- [ ] **Step 3: Implement config fields and the helper**

`ManualConfig.java`, after the `soulLocalChatEnabled` field (line ~123):

```java
    // Active banter: companions chat while WORKING (skill running / actively following) —
    // a second lane in SoulBanterDirector with its own cadence. Default-OFF like banter.
    private boolean soulBanterActiveEnabled = false;
    // Dialogue pacing sliders (0–100, 50 = shipped cadence). Never "off": the toggles above
    // and the Text/Voice masters are the kill switches. See DialoguePacing for the math.
    private int dialogueScriptedRate = 50;
    private int soulBanterIdleRate = 50;
    private int soulBanterActiveRate = 50;
    private int soulLocalRate = 50;
```

After `setSoulLocalChatEnabled` (line ~866):

```java
    public boolean isSoulBanterActiveEnabled() { return soulBanterActiveEnabled; }
    public void setSoulBanterActiveEnabled(boolean v) { this.soulBanterActiveEnabled = v; }

    private static int clampRate(int rate) { return Math.max(0, Math.min(100, rate)); }
    public int getDialogueScriptedRate() { return clampRate(dialogueScriptedRate); }
    public void setDialogueScriptedRate(int v) { this.dialogueScriptedRate = clampRate(v); }
    public int getSoulBanterIdleRate() { return clampRate(soulBanterIdleRate); }
    public void setSoulBanterIdleRate(int v) { this.soulBanterIdleRate = clampRate(v); }
    public int getSoulBanterActiveRate() { return clampRate(soulBanterActiveRate); }
    public void setSoulBanterActiveRate(int v) { this.soulBanterActiveRate = clampRate(v); }
    public int getSoulLocalRate() { return clampRate(soulLocalRate); }
    public void setSoulLocalRate(int v) { this.soulLocalRate = clampRate(v); }
```

`DialoguePacing.java`:

```java
package net.wcfcarolina13.GameAI.services.dialogue;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;

import java.util.Locale;

/**
 * One knob per dialogue stream. A rate of 0–100 (50 = the shipped cadence) becomes a cooldown
 * multiplier {@code 4^((50 - rate) / 50)}: 0 → ×4 (rarer), 100 → ×0.25 (chattier). Cooldowns
 * multiply by it, per-tick chances divide by it. Pure functions take the rate explicitly; the
 * {@link Stream} overloads read the live {@link ManualConfig} lazily at call time so a slider
 * change applies to the next line without any reload. The souls package must NOT call the
 * Stream overloads (it never references Frens) — directors receive an IntSupplier instead.
 */
public final class DialoguePacing {

    public enum Stream { SCRIPTED, BANTER_IDLE, BANTER_ACTIVE, LOCAL }

    public static final int DEFAULT_RATE = 50;

    private DialoguePacing() {
    }

    public static double multiplier(int rate) {
        int r = Math.max(0, Math.min(100, rate));
        return Math.pow(4.0, (50 - r) / 50.0);
    }

    public static long scaledCooldown(int rate, long baseMs) {
        return Math.round(baseMs * multiplier(rate));
    }

    public static double scaledChance(int rate, double base) {
        return Math.max(0.0, Math.min(1.0, base / multiplier(rate)));
    }

    /** "every ~8–15 min" / "every ~15–30 s" for the settings screen captions. */
    public static String describe(int rate, long minMs, long maxMs) {
        double m = multiplier(rate);
        long lo = Math.round(minMs * m);
        long hi = Math.round(maxMs * m);
        if (hi < 120_000L) {
            return String.format(Locale.ROOT, "every ~%d–%d s", Math.round(lo / 1000.0), Math.round(hi / 1000.0));
        }
        return String.format(Locale.ROOT, "every ~%d–%d min", Math.round(lo / 60_000.0), Math.round(hi / 60_000.0));
    }

    public static int rate(Stream stream) {
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            return DEFAULT_RATE;
        }
        return switch (stream) {
            case SCRIPTED -> cfg.getDialogueScriptedRate();
            case BANTER_IDLE -> cfg.getSoulBanterIdleRate();
            case BANTER_ACTIVE -> cfg.getSoulBanterActiveRate();
            case LOCAL -> cfg.getSoulLocalRate();
        };
    }

    public static long scaledCooldown(Stream stream, long baseMs) {
        return scaledCooldown(rate(stream), baseMs);
    }

    public static double scaledChance(Stream stream, double base) {
        return scaledChance(rate(stream), base);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*DialoguePacingTest*' -q`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java src/main/java/net/wcfcarolina13/GameAI/services/dialogue/DialoguePacing.java src/test/java/net/wcfcarolina13/GameAI/services/dialogue/DialoguePacingTest.java
git commit -m "pacing: DialoguePacing rate→multiplier helper + four rate fields and active-banter toggle in config"
```

---

### Task 2: Scripted services use the SCRIPTED rate

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java:451`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadDialogueService.java:128,235`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotInventoryFullDialogueService.java:58,84,87`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/EnchantingAmbientDialogueService.java:82,92`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotWakeUpDialogueService.java:134`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java:324`

**Interfaces:**
- Consumes: `DialoguePacing.scaledCooldown(Stream.SCRIPTED, long)`, `DialoguePacing.scaledChance(Stream.SCRIPTED, double)`.

No unit test harness reaches these (they need a server); verification is compile + field check. Each edit is one expression; constants stay as base values.

- [ ] **Step 1: Add the import to each of the six files**

```java
import net.wcfcarolina13.GameAI.services.dialogue.DialoguePacing;
```

- [ ] **Step 2: Replace the cooldown/chance expressions**

`PetProximityReactionService.playLine` (line 451):
```java
        if (forcedLineId == null && cooldownMs > 0L
                && now - last < DialoguePacing.scaledCooldown(DialoguePacing.Stream.SCRIPTED, cooldownMs)) {
```
`CompanionOverheadDialogueService` line 128:
```java
        if (now - last < DialoguePacing.scaledCooldown(DialoguePacing.Stream.SCRIPTED, COOLDOWN_MS)) {
```
and `tryShowGeneric` line 235:
```java
        if (now - last < DialoguePacing.scaledCooldown(DialoguePacing.Stream.SCRIPTED, Math.max(0L, cooldownMs))) {
```
`BotInventoryFullDialogueService` line 58 / 84 / 87:
```java
            if (now - last < DialoguePacing.scaledCooldown(DialoguePacing.Stream.SCRIPTED, FULL_INVENTORY_COOLDOWN_MS)) {
```
```java
        if (now - last < DialoguePacing.scaledCooldown(DialoguePacing.Stream.SCRIPTED, CHEST_RELIEF_COOLDOWN_MS)) {
```
```java
        if (RNG.nextDouble() > DialoguePacing.scaledChance(DialoguePacing.Stream.SCRIPTED, CHEST_RELIEF_CHANCE)) {
```
`EnchantingAmbientDialogueService` line 82 / 92:
```java
            if (now - last < DialoguePacing.scaledCooldown(DialoguePacing.Stream.SCRIPTED, COOLDOWN_MS)) {
```
```java
            if (ThreadLocalRandom.current().nextDouble() > DialoguePacing.scaledChance(DialoguePacing.Stream.SCRIPTED, SPEAK_CHANCE)) {
```
`BotWakeUpDialogueService` line 134:
```java
            if (ThreadLocalRandom.current().nextDouble() >= DialoguePacing.scaledChance(DialoguePacing.Stream.SCRIPTED, SPEAK_CHANCE)) {
```
`BotAnimalDefenseService` line 324:
```java
        if (lastWarnedAt != null && now - lastWarnedAt < DialoguePacing.scaledCooldown(DialoguePacing.Stream.SCRIPTED, OVERHEAD_WARN_COOLDOWN_MS)) {
```

- [ ] **Step 3: Build**

Run: `./gradlew build -x test -q`
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java src/main/java/net/wcfcarolina13/GameAI/services/CompanionOverheadDialogueService.java src/main/java/net/wcfcarolina13/GameAI/services/BotInventoryFullDialogueService.java src/main/java/net/wcfcarolina13/GameAI/services/EnchantingAmbientDialogueService.java src/main/java/net/wcfcarolina13/GameAI/services/BotWakeUpDialogueService.java src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java
git commit -m "pacing: scripted line cooldowns and chances follow the Scripted rate"
```

---

### Task 3: `SceneKind.WORK` + work directive in the prompt assembler

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupTypes.java:38-46`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupConversationService.java:175-179`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupPromptAssembler.java:58-90`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java:480-486` (WORK re-arms local like BANTER)
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupPromptAssemblerTest.java`

**Interfaces:**
- Produces: `SoulGroupTypes.SceneKind.WORK` (ambient, line cap `BANTER_MAX_SCENE_LINES`), `static String SoulGroupPromptAssembler.humanizeTask(String)`, `static String SoulGroupPromptAssembler.workLabel(SceneParticipant, String ownerDisplayName)`.

- [ ] **Step 1: Write the failing tests** (append to `SoulGroupPromptAssemblerTest`, next to `banterTurn`)

```java
    private SoulGroupTypes.GroupSceneTurn workTurn(int rosterSize, boolean addressPlayer) {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        List<SoulGroupTypes.SceneParticipant> roster = new ArrayList<>();
        roster.add(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake")));
        if (rosterSize > 1) {
            UUID sara = UUID.randomUUID();
            roster.add(new SoulGroupTypes.SceneParticipant(sara, "frens:jake", "Sara", grounding(sara, "Sara")));
        }
        return new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.WORK, owner, "Bradley",
                roster, "seed-text", Instant.EPOCH, UUID.randomUUID(), addressPlayer);
    }

    @Test
    void humanizeTaskStripsThePrefixAndMapsKnownSkills() {
        assertEquals("woodcutting", SoulGroupPromptAssembler.humanizeTask("skill:woodcut"));
        assertEquals("mining", SoulGroupPromptAssembler.humanizeTask("skill:mining"));
        assertEquals("collect dirt", SoulGroupPromptAssembler.humanizeTask("skill:collect_dirt"));
        assertEquals("", SoulGroupPromptAssembler.humanizeTask(""));
        assertEquals("", SoulGroupPromptAssembler.humanizeTask(null));
    }

    @Test
    void groupWorkDirectiveNamesWhatEachBotIsDoing() {
        String last = lastMessage(assembleBanter(workTurn(2, false)));
        assertTrue(last.contains("Jake is woodcutting"), last);
        assertTrue(last.contains("Sara is woodcutting"), last);
        assertTrue(last.contains("without stopping"));
        assertTrue(last.contains("seed-text"));
        assertFalse(last.contains("to Bradley"), "unflagged work scenes never address the player");
    }

    @Test
    void groupWorkDirectiveWithFlagAppendsThePlayerAddressedOption() {
        String last = lastMessage(assembleBanter(workTurn(2, true)));
        assertTrue(last.contains("may end by saying one short thing to Bradley"));
    }

    @Test
    void soloWorkDirectiveSpeaksToThePlayerAndForbidsAnAnswer() {
        String last = lastMessage(assembleBanter(workTurn(1, true)));
        assertTrue(last.contains("Jake is woodcutting and may say one short thing to Bradley"), last);
        assertTrue(last.contains("Bradley does not answer in this scene"));
        assertFalse(last.contains("among themselves"));
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*SoulGroupPromptAssemblerTest*' -q`
Expected: compilation failure — `SceneKind.WORK` / `humanizeTask` missing.

- [ ] **Step 3: Implement**

`SoulGroupTypes.SceneKind`:
```java
    public enum SceneKind {
        PLAYER, BANTER, LOCAL,
        /** Banter's second lane: companions chatting WHILE working (skill running / following). */
        WORK;
```

`SoulGroupConversationService` line 175:
```java
        int maxSceneLines = switch (turn.kind()) {
            case BANTER, WORK -> SoulGroupTypes.BANTER_MAX_SCENE_LINES;
            case LOCAL -> SoulGroupTypes.LOCAL_MAX_SCENE_LINES;
            case PLAYER -> SoulGroupTypes.MAX_SCENE_LINES;
        };
```

`SoulRuntime` line ~480: change `else if (turn.kind() == SoulGroupTypes.SceneKind.BANTER) {` to
```java
        } else if (turn.kind() == SoulGroupTypes.SceneKind.BANTER
                || turn.kind() == SoulGroupTypes.SceneKind.WORK) {
```

`SoulGroupPromptAssembler`: add a `case WORK -> new SoulTypes.Message(SoulTypes.Role.USER, workDirective(turn));` arm before `case LOCAL`, and these members:

```java
    /** "skill:woodcut" → "woodcutting"; unknown ids just lose the prefix and underscores. */
    static String humanizeTask(String activeTask) {
        String t = activeTask == null ? "" : activeTask.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("skill:")) {
            t = t.substring("skill:".length());
        }
        return switch (t) {
            case "woodcut", "woodcutting" -> "woodcutting";
            case "mine", "mining" -> "mining";
            case "fish", "fishing" -> "fishing";
            case "farm", "farming" -> "farming";
            case "shelter" -> "building a shelter";
            case "fortify", "fortify_village", "fortifyvillage" -> "fortifying the village";
            case "hunt", "hunting" -> "hunting";
            default -> t.replace('_', ' ');
        };
    }

    /** "Jake is woodcutting" / "Bob is walking with Roti" / "Bob is busy". */
    static String workLabel(SoulGroupTypes.SceneParticipant participant, String ownerDisplayName) {
        String task = humanizeTask(participant.grounding().bot().activeTask());
        if (!task.isEmpty()) {
            return participant.displayName() + " is " + task;
        }
        if (participant.grounding().situation().following()) {
            return participant.displayName() + " is walking with " + ownerDisplayName;
        }
        return participant.displayName() + " is busy";
    }

    private static String workDirective(SoulGroupTypes.GroupSceneTurn turn) {
        String owner = turn.ownerDisplayName();
        if (turn.roster().size() == 1) {
            SoulGroupTypes.SceneParticipant only = turn.roster().get(0);
            return "[" + workLabel(only, owner) + " and may say one short thing to " + owner
                    + " about it — a remark, a grumble, or a question. Recent happenings: "
                    + turn.playerMessage() + ". One short line, at most two, all spoken by "
                    + only.displayName() + "; " + owner + " does not answer in this scene.]";
        }
        StringBuilder who = new StringBuilder();
        for (int i = 0; i < turn.roster().size(); i++) {
            if (i > 0) {
                who.append(i == turn.roster().size() - 1 ? " and " : ", ");
            }
            who.append(workLabel(turn.roster().get(i), owner));
        }
        return "[The companions are busy — " + who + ". They trade a short word or two about"
                + " the work without stopping. Recent happenings: " + turn.playerMessage()
                + ". A few short lines only."
                + (turn.addressPlayer()
                        ? " One of you may end by saying one short thing to " + owner
                                + " — a question or a remark addressed to them."
                        : "")
                + "]";
    }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*SoulGroupPromptAssemblerTest*' --tests '*SoulGroupConversationServiceTest*' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupTypes.java src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupConversationService.java src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupPromptAssembler.java src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupPromptAssemblerTest.java
git commit -m "souls: SceneKind.WORK with a 'while working' directive built from each bot's active task"
```

---

### Task 4: Directors — scaled idle/local cadence, active lane, wiring, status

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulBanterDirector.java`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulLocalDirector.java:198,295,396,525-531`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java:267-283`
- Modify: `src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java:648-656`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulBanterDirectorTest.java`, `src/test/java/net/wcfcarolina13/GameAI/souls/SoulLocalDirectorTest.java`

**Interfaces:**
- Consumes: `SceneKind.WORK` (Task 3), `ManualConfig` rate getters (Task 1).
- Produces: `SoulBanterDirector(runtime, server, banterEnabled, activeEnabled, ambientTextOpen, ambientVoiceOpen, botsProvider, workingProbe, idleRate, activeRate, clock, random)`; statics `nextDelayMs(RandomGenerator, double multiplier)`, `nextActiveDelayMs(RandomGenerator, double multiplier)`, `firstActiveVeto(...)`; `SoulLocalDirector(... , localRate, clock, random)` and `nextDelayMs(RandomGenerator, double)`.

- [ ] **Step 1: Write the failing tests**

Append to `SoulBanterDirectorTest`:
```java
    @Test
    void scaledCooldownBandsFollowTheMultiplier() {
        Random random = new Random(3);
        for (int i = 0; i < 500; i++) {
            long slow = SoulBanterDirector.nextDelayMs(random, 4.0);
            assertTrue(slow >= 32 * 60_000L && slow <= 60 * 60_000L, "slow=" + slow);
            long fast = SoulBanterDirector.nextDelayMs(random, 0.25);
            assertTrue(fast >= 2 * 60_000L && fast <= 15 * 60_000L / 4, "fast=" + fast);
            long active = SoulBanterDirector.nextActiveDelayMs(random, 1.0);
            assertTrue(active >= 4 * 60_000L && active <= 8 * 60_000L, "active=" + active);
        }
        assertEquals(SoulBanterDirector.nextDelayMs(new Random(9)), SoulBanterDirector.nextDelayMs(new Random(9), 1.0));
    }

    @Test
    void activeVetoOrderAddsNobodyWorkingAndRelaxesAtEase() {
        assertNull(SoulBanterDirector.firstActiveVeto(true, true, true, true, true, true, true, 2, 1, true));
        assertEquals("disabled", SoulBanterDirector.firstActiveVeto(false, true, true, true, true, true, true, 2, 1, true));
        assertEquals("pipeline", SoulBanterDirector.firstActiveVeto(true, false, true, true, true, true, true, 2, 1, true));
        assertEquals("cooldown", SoulBanterDirector.firstActiveVeto(true, true, false, true, true, true, true, 2, 1, true));
        assertEquals("busy", SoulBanterDirector.firstActiveVeto(true, true, true, false, true, true, true, 2, 1, true));
        assertEquals("muted", SoulBanterDirector.firstActiveVeto(true, true, true, true, false, true, true, 2, 1, true));
        assertEquals("player-not-ready", SoulBanterDirector.firstActiveVeto(true, true, true, true, true, false, true, 2, 1, true));
        assertEquals("not-quiet", SoulBanterDirector.firstActiveVeto(true, true, true, true, true, true, false, 2, 1, true));
        assertEquals("roster", SoulBanterDirector.firstActiveVeto(true, true, true, true, true, true, true, 0, 0, true));
        assertEquals("nobody-working", SoulBanterDirector.firstActiveVeto(true, true, true, true, true, true, true, 2, 0, true));
        assertEquals("bots-apart", SoulBanterDirector.firstActiveVeto(true, true, true, true, true, true, true, 2, 1, false));
        assertEquals(30_000L, SoulBanterDirector.ACTIVE_QUIET_WINDOW_MS);
    }
```
(`assertNull` import: `import static org.junit.jupiter.api.Assertions.assertNull;` is already present — line 24 uses it.)

Append to `SoulLocalDirectorTest`:
```java
    @Test
    void scaledCooldownBandFollowsTheMultiplier() {
        Random random = new Random(5);
        for (int i = 0; i < 500; i++) {
            long slow = SoulLocalDirector.nextDelayMs(random, 4.0);
            assertTrue(slow >= 24 * 60_000L && slow <= 48 * 60_000L, "slow=" + slow);
        }
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests '*SoulBanterDirectorTest*' --tests '*SoulLocalDirectorTest*' -q`
Expected: compilation failure (new overloads missing).

- [ ] **Step 3: Implement `SoulLocalDirector`**

Replace the static cadence helper and its two call sites:
```java
    static long nextDelayMs(RandomGenerator random) {
        return nextDelayMs(random, 1.0);
    }

    /** 6–12 min × the Local rate multiplier (DialoguePacing, injected as {@code localRate}). */
    static long nextDelayMs(RandomGenerator random, double multiplier) {
        return Math.round((6 * 60_000L + random.nextDouble() * 6 * 60_000L) * multiplier);
    }

    private double cadenceMultiplier() {
        int r = Math.max(0, Math.min(100, localRate.getAsInt()));
        return Math.pow(4.0, (50 - r) / 50.0);
    }
```
Constructor: add `IntSupplier localRate` parameter directly before `LongSupplier clock`, store as `private final IntSupplier localRate;` (`import java.util.function.IntSupplier;`). Lines 295 and 396: `nextDelayMs(random)` → `nextDelayMs(random, cadenceMultiplier())`. Line 198 (`initialDelayMs`) unchanged.

- [ ] **Step 4: Implement `SoulBanterDirector`**

Fields/constructor (replace the existing constructor):
```java
    static final long ACTIVE_QUIET_WINDOW_MS = 30_000L;

    private final BooleanSupplier activeEnabled;
    private final Predicate<ServerPlayerEntity> workingProbe;
    private final IntSupplier idleRate;
    private final IntSupplier activeRate;
    private final Map<UUID, Long> nextActiveAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastActiveVerdict = new ConcurrentHashMap<>();

    /** Which of the two banter lanes a scene belongs to; each has its own cooldown + verdict. */
    private enum Lane { IDLE, ACTIVE }

    public SoulBanterDirector(SoulRuntime runtime, MinecraftServer server,
                               BooleanSupplier banterEnabled, BooleanSupplier activeEnabled,
                               BooleanSupplier ambientTextOpen, BooleanSupplier ambientVoiceOpen,
                               Function<MinecraftServer, List<ServerPlayerEntity>> botsProvider,
                               Predicate<ServerPlayerEntity> workingProbe,
                               IntSupplier idleRate, IntSupplier activeRate,
                               LongSupplier clock, RandomGenerator random) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.server = Objects.requireNonNull(server, "server");
        this.banterEnabled = Objects.requireNonNull(banterEnabled, "banterEnabled");
        this.activeEnabled = Objects.requireNonNull(activeEnabled, "activeEnabled");
        this.ambientTextOpen = Objects.requireNonNull(ambientTextOpen, "ambientTextOpen");
        this.ambientVoiceOpen = Objects.requireNonNull(ambientVoiceOpen, "ambientVoiceOpen");
        this.botsProvider = Objects.requireNonNull(botsProvider, "botsProvider");
        this.workingProbe = Objects.requireNonNull(workingProbe, "workingProbe");
        this.idleRate = Objects.requireNonNull(idleRate, "idleRate");
        this.activeRate = Objects.requireNonNull(activeRate, "activeRate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }
```
Imports: `java.util.function.IntSupplier`, `java.util.function.Predicate`.

`tick()`: the enabled early-return becomes `if (!banterEnabled.getAsBoolean() && !activeEnabled.getAsBoolean()) return;` and the loop body becomes:
```java
            if (banterEnabled.getAsBoolean()) {
                evaluate(player, bots, now);
            }
            if (activeEnabled.getAsBoolean() && !pendingAttempts.contains(player.getUuid())) {
                evaluateActive(player, bots, now);
            }
```

`evaluate(...)`: after the veto block, replace the Phase-A tail (from `pendingAttempts.add(playerId);` to the end of the method) with `beginScene(playerId, rosterBots, Lane.IDLE);`. Then add:
```java
    private void evaluateActive(ServerPlayerEntity player, List<ServerPlayerEntity> bots, long now) {
        UUID playerId = player.getUuid();
        long nextAt = nextActiveAtMs.computeIfAbsent(playerId, id -> now + initialDelayMs(random));
        List<ServerPlayerEntity> rosterBots = eligibleRosterBots(player, bots);
        int working = 0;
        for (ServerPlayerEntity bot : rosterBots) {
            if (workingProbe.test(bot)) {
                working++;
            }
        }
        String veto = firstActiveVeto(
                activeEnabled.getAsBoolean(),
                runtime.pipelineAvailable(),
                now >= nextAt,
                runtime.isSceneBudgetFree(playerId),
                ambientTextOpen.getAsBoolean() || ambientVoiceOpen.getAsBoolean(),
                playerReady(player),
                now - SoulPlayerActivity.lastChatAt(playerId) >= ACTIVE_QUIET_WINDOW_MS,
                rosterBots.size(),
                working,
                botsCloseTogether(rosterBots));
        if (veto != null) {
            recordVerdict(playerId, Lane.ACTIVE, "vetoed:" + veto);
            return;
        }
        beginScene(playerId, rosterBots, Lane.ACTIVE);
    }

    /** Phase A tail shared by both lanes: fetch recent events off-thread, hop back, fire. */
    private void beginScene(UUID playerId, List<ServerPlayerEntity> rosterBots, Lane lane) {
        pendingAttempts.add(playerId);
        List<UUID> rosterIds = rosterBots.stream().map(ServerPlayerEntity::getUuid).toList();
        List<CompletableFuture<List<SoulTypes.SoulEvent>>> fetches = new ArrayList<>();
        for (UUID botId : rosterIds) {
            fetches.add(runtime.recentEventsForBanter(botId, EVENT_FETCH_WINDOW)
                    .exceptionally(ex -> List.of()));
        }
        CompletableFuture.allOf(fetches.toArray(CompletableFuture[]::new))
                .whenComplete((v, err) -> server.execute(() -> {
                    try {
                        List<List<SoulTypes.SoulEvent>> eventsPerBot = new ArrayList<>();
                        for (CompletableFuture<List<SoulTypes.SoulEvent>> fetch : fetches) {
                            eventsPerBot.add(fetch.getNow(List.of()));
                        }
                        fireScene(playerId, rosterIds, eventsPerBot, lane);
                    } finally {
                        pendingAttempts.remove(playerId);
                    }
                }));
    }
```
`fireScene` gains a `Lane lane` parameter. Its re-check becomes:
```java
        boolean laneEnabled = lane == Lane.IDLE ? banterEnabled.getAsBoolean() : activeEnabled.getAsBoolean();
        long quietWindow = lane == Lane.IDLE ? QUIET_WINDOW_MS : ACTIVE_QUIET_WINDOW_MS;
        boolean ready = lane == Lane.IDLE ? playerAtEase(player) : playerReady(player);
        if (player == null || !laneEnabled || !runtime.pipelineAvailable()
                || !runtime.isSceneBudgetFree(playerId) || !ready
                || now - SoulPlayerActivity.lastChatAt(playerId) < quietWindow) {
            recordVerdict(playerId, lane, "vetoed:changed-before-capture");
            return;
        }
```
Every `recordVerdict(playerId, "...")` inside `fireScene` becomes `recordVerdict(playerId, lane, "...")`; every `nextEligibleAtMs.put(playerId, now + RETRY_AFTER_VETO_MS)` becomes `cooldowns(lane).put(playerId, now + RETRY_AFTER_VETO_MS)`. The turn is built with `kind(lane)`; `armedUntilMs = now + nextDelay(lane)`; `cooldowns(lane).put(playerId, armedUntilMs)`; the fired log line gains `lane={}`; the failure refund uses `cooldowns(lane).replace(...)`. Add helpers:
```java
    private Map<UUID, Long> cooldowns(Lane lane) {
        return lane == Lane.IDLE ? nextEligibleAtMs : nextActiveAtMs;
    }

    private SoulGroupTypes.SceneKind kind(Lane lane) {
        return lane == Lane.IDLE ? SoulGroupTypes.SceneKind.BANTER : SoulGroupTypes.SceneKind.WORK;
    }

    private long nextDelay(Lane lane) {
        return lane == Lane.IDLE
                ? nextDelayMs(random, multiplier(idleRate.getAsInt()))
                : nextActiveDelayMs(random, multiplier(activeRate.getAsInt()));
    }

    private void recordVerdict(UUID playerId, Lane lane, String verdict) {
        Map<UUID, String> verdicts = lane == Lane.IDLE ? lastVerdict : lastActiveVerdict;
        String previous = verdicts.put(playerId, verdict);
        if (!verdict.equals(previous)) {
            LOGGER.info("[souls] banter lane={} player={} outcome={}", lane, playerId, verdict);
        }
    }

    /** Active lane is lenient: work goes on under light danger; only dead/asleep blocks it. */
    private static boolean playerReady(ServerPlayerEntity player) {
        return player.isAlive() && !player.isSleeping();
    }

    static double multiplier(int rate) {
        int r = Math.max(0, Math.min(100, rate));
        return Math.pow(4.0, (50 - r) / 50.0);
    }
```
The existing two-arg `recordVerdict(UUID, String)` is deleted; `evaluate` uses `recordVerdict(playerId, Lane.IDLE, ...)`. `primeNow` puts `0L` into both maps; `notePlayerScene` re-arms both with their own `nextDelay`; `statusFor` becomes:
```java
    public String statusFor(UUID playerId) {
        long now = clock.getAsLong();
        return "idle — " + laneStatus(playerId, now, lastVerdict, nextEligibleAtMs)
                + "; active — " + laneStatus(playerId, now, lastActiveVerdict, nextActiveAtMs);
    }

    private static String laneStatus(UUID playerId, long now, Map<UUID, String> verdicts, Map<UUID, Long> cooldowns) {
        long nextAt = cooldowns.getOrDefault(playerId, 0L);
        String verdict = verdicts.getOrDefault(playerId, "no evaluation yet");
        long remainingS = Math.max(0L, (nextAt - now) / 1000L);
        return "last verdict: " + verdict + "; cooldown: "
                + (remainingS == 0 ? "elapsed" : remainingS + "s remaining");
    }
```
Static cadence rules:
```java
    static long nextDelayMs(RandomGenerator random) {
        return nextDelayMs(random, 1.0);
    }

    /** 8–15 min × the Idle rate multiplier. */
    static long nextDelayMs(RandomGenerator random, double multiplier) {
        return Math.round((8 * 60_000L + random.nextDouble() * 7 * 60_000L) * multiplier);
    }

    /** 4–8 min × the Active rate multiplier — working chatter is denser than idle chatter. */
    static long nextActiveDelayMs(RandomGenerator random, double multiplier) {
        return Math.round((4 * 60_000L + random.nextDouble() * 4 * 60_000L) * multiplier);
    }

    /** Active lane gate chain; "player-not-ready" replaces at-ease, "nobody-working" is new. */
    static String firstActiveVeto(boolean enabled, boolean pipelineAvailable, boolean cooldownElapsed,
                                   boolean budgetFree, boolean surfaceOpen, boolean playerReady,
                                   boolean quiet, int eligibleRosterSize, int workingCount,
                                   boolean botsCloseTogether) {
        if (!enabled) return "disabled";
        if (!pipelineAvailable) return "pipeline";
        if (!cooldownElapsed) return "cooldown";
        if (!budgetFree) return "busy";
        if (!surfaceOpen) return "muted";
        if (!playerReady) return "player-not-ready";
        if (!quiet) return "not-quiet";
        if (eligibleRosterSize < 1) return "roster";
        if (workingCount < 1) return "nobody-working";
        if (!botsCloseTogether) return "bots-apart";
        return null;
    }
```

- [ ] **Step 5: Wire in `SoulRuntime` (line ~267)**

```java
            runtime.banterDirector = new SoulBanterDirector(runtime, server,
                    () -> {
                        ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                        return cfg != null && cfg.isSoulBanterEnabled();
                    },
                    () -> {
                        ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                        return cfg != null && cfg.isSoulBanterActiveEnabled();
                    },
                    ambientTextOpen, ambientVoiceOpen,
                    srv -> net.wcfcarolina13.GameAI.BotEventHandler.getRegisteredBots(srv),
                    bot -> net.wcfcarolina13.GameAI.services.TaskService.hasActiveTask(bot.getUuid())
                            || net.wcfcarolina13.GameAI.BotEventHandler.isFollowingPlayer(bot),
                    () -> {
                        ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                        return cfg == null ? 50 : cfg.getSoulBanterIdleRate();
                    },
                    () -> {
                        ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                        return cfg == null ? 50 : cfg.getSoulBanterActiveRate();
                    },
                    System::currentTimeMillis, new java.util.Random());
            runtime.localDirector = new SoulLocalDirector(runtime, server,
                    () -> {
                        ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                        return cfg != null && cfg.isSoulLocalChatEnabled();
                    },
                    ambientTextOpen, ambientVoiceOpen,
                    srv -> net.wcfcarolina13.GameAI.BotEventHandler.getRegisteredBots(srv),
                    () -> {
                        ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                        return cfg == null ? 50 : cfg.getSoulLocalRate();
                    },
                    System::currentTimeMillis, new java.util.Random());
```
(The lazy `Frens.CONFIG` reads inside lambdas are the established pattern here; the souls package still has no static reference.)

- [ ] **Step 6: `BotSoulCommands.executeBanterStatus`** — report both lanes:
```java
        boolean enabled = config != null && config.isSoulBanterEnabled();
        boolean active = config != null && config.isSoulBanterActiveEnabled();
        ...
                "Banter is " + (enabled ? "ON" : "OFF") + ", active banter is " + (active ? "ON" : "OFF")
                        + ". " + verdict + ambientSurfaceWarning()
                        + ((enabled || active) ? replyRoutingNote() : ""));
```

- [ ] **Step 7: Run tests + build**

Run: `./gradlew test --tests '*SoulBanterDirectorTest*' --tests '*SoulLocalDirectorTest*' --tests '*SoulRuntimeTest*' -q && ./gradlew build -x test -q`
Expected: PASS, clean build.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulBanterDirector.java src/main/java/net/wcfcarolina13/GameAI/souls/SoulLocalDirector.java src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulBanterDirectorTest.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulLocalDirectorTest.java
git commit -m "souls: active banter lane (WORK scenes while bots work) + rate-scaled idle/local cadence"
```

---

### Task 5: `DialogueSettingsScreen` + entry points in `BotControlScreen`

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/DialogueSettingsScreen.java`
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java` (GLOBAL_TOGGLES list line 36–47; index constants 50–55; field decls ~147; reset ~705; chip layout ~782–793; click handling ~1366–1376; init 224–234; saveSettings 552–554)

**Interfaces:**
- Consumes: `DialoguePacing.describe/multiplier`, `ManualConfig` rate getters/setters, `configNetworkManager.sendSaveConfigPacket(ConfigJsonUtil.configToJson())`.

No unit harness for screens; verify by opening the screen in-game.

- [ ] **Step 1: Create the screen**

```java
package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.services.dialogue.DialoguePacing;
import net.wcfcarolina13.network.ConfigJsonUtil;
import net.wcfcarolina13.network.configNetworkManager;

import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.IntFunction;

/**
 * Four dialogue-frequency sliders (Scripted lines, Idle banter, Active banter, Local
 * chime-ins), opened from the "Rates…" chip on the Banter row of {@link BotControlScreen}.
 * 0–100, 50 = shipped cadence; the caption under each shows the resulting band. Writes
 * settings.json5 on every change (autosave ruling), so closing any way never loses a value.
 */
public class DialogueSettingsScreen extends Screen {

    private static final int POPUP_WIDTH = 360;
    private static final int POPUP_HEIGHT = 232;
    private static final int PAD = 8;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 16;

    private final Screen parent;

    public DialogueSettingsScreen(Screen parent) {
        super(Text.literal("§bDialogue Frequency"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        int w = POPUP_WIDTH - PAD * 2;
        int y = cy + 40;
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                    .dimensions(cx + PAD, cy + POPUP_HEIGHT - 28, w, 20).build());
            return;
        }
        addRow(cx + PAD, y, w, "Scripted lines", cfg::getDialogueScriptedRate, cfg::setDialogueScriptedRate,
                rate -> String.format(Locale.ROOT, "cooldowns ×%.2g", DialoguePacing.multiplier(rate)),
                "Pet, weather, gear, inventory, wake-up and enchanting remarks. Left = rarer, right = chattier.");
        y += ROW_H + ROW_GAP;
        addRow(cx + PAD, y, w, "Idle banter", cfg::getSoulBanterIdleRate, cfg::setSoulBanterIdleRate,
                rate -> DialoguePacing.describe(rate, 8 * 60_000L, 15 * 60_000L),
                "LLM banter when things are calm (Banter toggle). First scene of a session is always 60–150 s.");
        y += ROW_H + ROW_GAP;
        addRow(cx + PAD, y, w, "Active banter", cfg::getSoulBanterActiveRate, cfg::setSoulBanterActiveRate,
                rate -> DialoguePacing.describe(rate, 4 * 60_000L, 8 * 60_000L),
                "LLM banter while a companion is working or following you (Active toggle).");
        y += ROW_H + ROW_GAP;
        addRow(cx + PAD, y, w, "Local chime-ins", cfg::getSoulLocalRate, cfg::setSoulLocalRate,
                rate -> DialoguePacing.describe(rate, 6 * 60_000L, 12 * 60_000L),
                "Reactions to chat you type near them that wasn't addressed to anyone (Local toggle).");

        int btnY = cy + POPUP_HEIGHT - 28;
        int btnW = (w - PAD) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset defaults"), b -> resetDefaults())
                .dimensions(cx + PAD, btnY, btnW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(cx + PAD + btnW + PAD, btnY, btnW, 20).build());
    }

    private void addRow(int x, int y, int w, String label, IntSupplier get, IntConsumer set,
                        IntFunction<String> caption, String tooltip) {
        RateSlider slider = new RateSlider(x, y, w, ROW_H, label, get.getAsInt(), set, caption);
        slider.setTooltip(Tooltip.of(Text.literal(tooltip)));
        addDrawableChild(slider);
    }

    private void resetDefaults() {
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            return;
        }
        cfg.setDialogueScriptedRate(DialoguePacing.DEFAULT_RATE);
        cfg.setSoulBanterIdleRate(DialoguePacing.DEFAULT_RATE);
        cfg.setSoulBanterActiveRate(DialoguePacing.DEFAULT_RATE);
        cfg.setSoulLocalRate(DialoguePacing.DEFAULT_RATE);
        persist();
        this.clearChildren();
        this.init();
    }

    private static void persist() {
        if (Frens.CONFIG == null) {
            return;
        }
        Frens.CONFIG.save();
        configNetworkManager.sendSaveConfigPacket(ConfigJsonUtil.configToJson());
    }

    /** One slider: label + rate on the knob, resulting cadence as a grey caption. */
    private static final class RateSlider extends SliderWidget {
        private final String label;
        private final IntConsumer set;
        private final IntFunction<String> caption;
        private int lastApplied;

        RateSlider(int x, int y, int w, int h, String label, int initial, IntConsumer set,
                   IntFunction<String> caption) {
            super(x, y, w, h, Text.literal(""), initial / 100.0);
            this.label = label;
            this.set = set;
            this.caption = caption;
            this.lastApplied = initial;
            updateMessage();
        }

        private int rate() {
            return (int) Math.round(this.value * 100.0);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(label + ": " + rate() + "   §7" + caption.apply(rate())));
        }

        @Override
        protected void applyValue() {
            int rate = rate();
            if (rate == lastApplied) {
                return;
            }
            lastApplied = rate;
            set.accept(rate);
            persist();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + POPUP_HEIGHT + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + POPUP_HEIGHT, 0xCC222222);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 10, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§750 = shipped cadence. Toggles stay the on/off switches."),
                this.width / 2, cy + 24, 0xFFFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        } else {
            super.close();
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input != null ? input.key() : -1;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
```

- [ ] **Step 2: `BotControlScreen` — Active toggle + Rates chip**

1. Insert after the "Banter" `GlobalToggleDef` (line 45):
```java
            new GlobalToggleDef("Active", "Companions also chat while WORKING — a skill running or actively following you (needs Soul Chat on). Own cadence, tuned by the Rates… chip on the Banter row. /bot soul banter status shows the active lane."),
```
2. Index constants: `ACTIVE_TOGGLE_INDEX = 9;` and `LOCAL_TOGGLE_INDEX = 10;`.
3. Field next to `soulChatModelRect`: `private Rect dialogueRatesRect;`; reset it to `null` next to line 705.
4. Chip layout (line ~782): extend the condition with `|| i == BANTER_TOGGLE_INDEX` and the assignment chain with
```java
                } else if (i == BANTER_TOGGLE_INDEX) {
                    dialogueRatesRect = advRect;
```
5. Wherever the chip text is drawn for `soulVoiceEngineRect`/`soulChatModelRect` (search `"Engine"`/`"Model"` chip labels in the draw loop), draw `"Rates…"` for the Banter row the same way.
6. Click handling (line ~1372): add
```java
            if (dialogueRatesRect != null && dialogueRatesRect.contains(mx, my)) {
                if (this.client != null) {
                    this.client.setScreen(new DialogueSettingsScreen(this));
                }
                return true;
            }
```
7. `init()` (line 233): `globalValues[ACTIVE_TOGGLE_INDEX] = Frens.CONFIG.isSoulBanterActiveEnabled();`
8. `saveSettings()` (line 552): `config.setSoulBanterActiveEnabled(globalValues[ACTIVE_TOGGLE_INDEX]);`

- [ ] **Step 3: Build**

Run: `./gradlew build -x test -q`
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/DialogueSettingsScreen.java src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java
git commit -m "ui: Dialogue Frequency screen (four rate sliders) + Active banter toggle and Rates… chip"
```

---

### Task 6: Changelog, version bump, full suite, deploy

**Files:**
- Modify: `changelog.md` (new top entry), `gradle.properties` (`mod_version` → `1.1.188-release+1.21.11`)

- [ ] **Step 1: Full suite + build**

Run: `./gradlew build -q` — Expected: all tests green (≥ 553 + new), clean.

- [ ] **Step 2: Changelog entry** under the file header, newest-first, titled `## Dialogue pacing sliders + active banter; 1.1.188 (2026-08-29)`, listing: the four streams and the multiplier rule, the WORK scene and its veto chain, the screen and where it opens, the field checklist from spec §4.

- [ ] **Step 3: Commit + deploy**

```bash
git add changelog.md gradle.properties
git commit -m "dialogue pacing: changelog; bump 1.1.188"
pgrep -f "net.minecraft.client.main.Main" >/dev/null && { echo "ABORT: Minecraft is running"; exit 1; }
./gradlew build -x test -q
JAR="build/libs/frens-$(grep '^mod_version=' gradle.properties | cut -d= -f2).jar"
for DIR in "/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.11/minecraft/mods" "/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10/minecraft/mods" "/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10 TEST/minecraft/mods"; do find "$DIR" -maxdepth 1 -name 'frens-*.jar' -delete; cp "$JAR" "$DIR/"; done
```
Verify with `javap` that the deployed jar's `SoulGroupTypes$SceneKind` lists `WORK`.

- [ ] **Step 4: Field checklist for Bradley** (from spec §4): sliders persist across restart; Scripted 100 → denser pet remarks, 0 → near-silent; Active ON + a bot woodcutting → a WORK scene within ~2.5 min mentioning the work; `/bot soul banter status` shows both lanes.
