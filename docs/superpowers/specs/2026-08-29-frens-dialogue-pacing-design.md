# Frens — Dialogue pacing sliders + active banter (design)

Date: 2026-08-29 · Status: approved approach A (Bradley), spec for implementation · Baseline: 1.1.187

## 1. Goal

Give the player one frequency slider per dialogue stream and add a fourth stream, **active banter**:
LLM companion chatter that fires while bots are *working* (skill running, or actively following the
player) instead of only in quiet, idle moments.

Streams:

| Stream | Today | Slider name |
|---|---|---|
| Scripted lines (pet/weather/gear/inventory/wake-up/enchanting/animal-defence) | per-service cooldown + chance constants, no central knob | **Scripted lines** |
| Idle LLM banter (`SoulBanterDirector`) | first 60–150 s, then 8–15 min; at-ease + 90 s quiet gates | **Idle banter** |
| Active LLM banter | does not exist | **Active banter** |
| Local chime-ins (`SoulLocalDirector`) | first 0–2 min, then 6–12 min | **Local chime-ins** |

Non-goals: per-bot rates, changing what lines say (except the new active directive), per-voice
work (separate follow-up), touching the cross-bot line dedup or the ambient category masks.

## 2. Semantics

- Each slider is an int **0–100**, default **50 = today's cadence**. It never means "off" — the
  existing toggles remain the kill switches (Banter, Local, Text/Voice masters; new **Active** toggle).
- Multiplier `m(rate) = 4^((50 − rate) / 50)`: rate 0 → cooldowns ×4 (rarer), 50 → ×1, 100 → ×0.25
  (chattier). Exponential so both halves of the slider feel symmetric.
- Cooldowns scale by `m`; per-tick/one-shot chances scale by `1/m`, clamped to [0, 1].
- First-scene delays (`initialDelayMs`) are NOT scaled — a fresh session should feel the same
  regardless of the slider; steady-state spacing is what the slider tunes.

## 3. Components

### 3.1 `DialoguePacing` (new, `GameAI/services/dialogue/`, pure + tested)

```java
enum Stream { SCRIPTED, BANTER_IDLE, BANTER_ACTIVE, LOCAL }
static double multiplier(int rate)                       // pure
static long   scaledCooldown(int rate, long baseMs)      // pure: round(base * m)
static double scaledChance(int rate, double base)        // pure: clamp(base / m, 0, 1)
static String describe(int rate, long minMs, long maxMs) // "every ~4–8 min" for the screen
static long   scaledCooldown(Stream s, long baseMs)      // convenience: rate from Frens.CONFIG (lazy)
static double scaledChance(Stream s, double base)
```
The `Stream` overloads read `Frens.CONFIG` lazily at call time (the same lazy-read ruling the LLM
settings follow). The souls package never references `Frens`; directors receive `IntSupplier`s.

### 3.2 Config (`ManualConfig`, `settings.json5`)

`dialogueScriptedRate`, `soulBanterIdleRate`, `soulBanterActiveRate`, `soulLocalRate` — ints,
clamped 0–100 in setters, default 50. `soulBanterActiveEnabled` — boolean, default false (matches
the Banter default; the player turns it on to test).

### 3.3 Scripted services (mechanical, one call per site)

`now - last < X_MS` → `now - last < DialoguePacing.scaledCooldown(SCRIPTED, X_MS)`;
`rng > CHANCE` → `rng > DialoguePacing.scaledChance(SCRIPTED, CHANCE)`.

Sites: `PetProximityReactionService.playLine` (single helper covers all 10 cooldowns),
`CompanionOverheadDialogueService` (COOLDOWN_MS, berry ×2 via `tryShowGeneric`, gear ×3),
`BotInventoryFullDialogueService` (2 cooldowns + 1 chance), `EnchantingAmbientDialogueService`
(cooldown + chance), `BotWakeUpDialogueService` (chance), `BotAnimalDefenseService` (warn cooldown).
Constants stay as the *base* values; nothing else in those services changes.

### 3.4 Directors

**`SoulBanterDirector`** gains `IntSupplier idleRate, IntSupplier activeRate, BooleanSupplier
activeEnabled` (injected from `SoulRuntime` next to the existing suppliers).

- `nextDelayMs(random, multiplier)` for idle: `(8 + 7·r) min × m`. `initialDelayMs` unchanged.
- Active banter is a second evaluation inside the same `tick()` with its own per-player state
  (`nextActiveAtMs`, `activeVerdict`, shared `pendingAttempts` so idle/active never race one player):
  - **Working roster** = eligible roster bots (same profile/authorization/LOCAL/24-block rules)
    of which **≥ 1 is working**: `TaskService.hasActiveTask(botId)` or
    `BotEventHandler.isFollowingPlayer(bot)`. Pure rule `workingRoster(List<Boolean> working)` tested.
  - Veto order (own `firstActiveVeto`, tested): disabled → pipeline → cooldown → busy → muted →
    player-not-alive-or-sleeping → not-quiet (30 s, not 90) → roster → nobody-working → bots-apart.
    `hurtTime`/`attacker` are deliberately NOT gates (work continues under light danger); the
    post-capture `groundingDangerous` veto (hostiles/combat/breaking-free/surface-recovery) still applies.
  - Cadence: base `(4 + 4·r) min × m(activeRate)`; initial 60–150 s; failure refund identical to idle.
  - Fires `SceneKind.WORK` turns; `decideAddressPlayer` coin reused.
- `primeNow` primes both; `statusFor` reports both verdicts/cooldowns.

**`SoulLocalDirector`** gains `IntSupplier localRate`; `nextDelayMs(random, multiplier)`.

### 3.5 Scene kind + prompt

`SoulGroupTypes.SceneKind.WORK` (ambient = true, line cap = `BANTER_MAX_SCENE_LINES`). Every
exhaustive `switch` on `SceneKind` gains a `WORK` arm (compiler-enforced).

`SoulGroupPromptAssembler` WORK directive — built from each participant's `bot.activeTask()` /
`situation.following()`:

- group: `[The companions are busy — Jake is woodcutting, Bob is walking with Roti. They trade a
  short word or two about the work without stopping. Recent happenings: <seed>. A few short lines
  only.<optional address-player tail>]`
- solo: `[Jake is busy woodcutting and may say one short thing to Roti about it — a remark, a
  grumble, or a question. One short line, at most two, all spoken by Jake; Roti does not answer in
  this scene.]`

Task labels: `skill:woodcut` → "woodcutting" via a small `humanizeTask` map (fallback: strip the
`skill:` prefix). Seed reuses `SoulBanterSeed.build`.

### 3.6 Screen

`DialogueSettingsScreen extends Screen` (GraphicalUserInterface, vanilla widgets like
`ConfigureVoiceCategoriesScreen`): four `RateSlider extends SliderWidget` rows, each with a caption
readout beneath — Scripted: "×0.5 cooldowns", Idle/Active/Local: `DialoguePacing.describe(...)` e.g.
"every ~4–8 min". Buttons: **Reset defaults**, **Done**. Autosave on slider release
(`Frens.CONFIG.set…; save()`), same sync path `BotControlScreen.saveSettings` uses.

Entry points in `BotControlScreen`: an **Active** toggle (index 10, wired at all three sites the
comment names) and a **Rates…** chip on the Banter row opening the screen.

### 3.7 Commands

`/bot soul banter status` includes the active verdict/cooldown; `/bot soul banter now` primes both.

## 4. Testing

- `DialoguePacingTest`: multiplier endpoints (0→4, 50→1, 100→0.25), monotonic, chance clamp, describe.
- `SoulBanterDirectorTest`: `firstActiveVeto` order, `workingRoster` rule, scaled cadence bands.
- `SoulLocalDirectorTest`: scaled band.
- `SoulGroupPromptAssemblerTest`: WORK group + solo directives, `humanizeTask`.
- Field checklist (deployed build): sliders persist across restart; Scripted at 100 makes pet
  remarks noticeably denser, at 0 near-silent; Active on + a bot woodcutting → a WORK scene within
  ~2.5 min mentioning the work; Idle unchanged at 50; `/bot soul banter status` shows both.

## 5. Phases (one commit per phase, ≤ 5 files each)

1. Config + `DialoguePacing` + test + scripted-service call sites.
2. `SceneKind.WORK` + prompt assembler + switch arms + tests.
3. Directors (banter idle/active, local) + `SoulRuntime` wiring + commands + tests.
4. Screen + `BotControlScreen` entry points; changelog; version bump; deploy.
