# Frens — Soul conversation ontology, Phase 1: change-driven cues + speech-act rotation

Date: 2026-08-29 · Status: approved direction (Bradley: "proceed with your recommendation") · Baseline: 1.1.195

## Problem

Every ambient scene starts from a flat bag of present-tense facts. Topic rotation (1.1.194) stops
the same *noun* recurring, but nothing tells the model **what changed** or **what kind of thing to
say**, so scenes stay observational and gravitate to the loudest instinct.

## Phase 1 scope (pure + unit-tested, no schema changes)

### 1. `SoulSceneDiff` — what changed since the last scene for this audience

Input: the previous and current `GroundingSnapshot` of the roster's first bot (plus its optional
`PlayerSnapshot`), and a per-audience **seen-registry** (`Set<String>` of keys such as
`animal:wolves`, `facility:campfire`, `biome:taiga`, `held:bow`). Output: `SoulBanterSeed.Anchor`s
with weight 5 (above any static grounding anchor, below a HIGH event at 6):

| change | phrase (examples) | topic key |
|---|---|---|
| weather changed | "the rain just stopped" / "it just started raining" | the weather |
| time phase changed | "night has fallen" / "dawn is breaking" | the hour |
| biome changed | "we have crossed into taiga" | the land |
| first-seen animal / facility / biome | "the first wolves we've seen" | animals / facilities / the land |
| hostiles appeared | "hostiles just showed up" | danger |
| bot health dropped ≥ 6 | "Jake just took a hit" | health |
| bot hunger crossed below 8 | "Jake is getting hungry" | food |
| bot held item changed | "Jake switched to a bow" | gear |
| player held item changed | "Roti is holding a bow now" | the player |
| entered / left home base | "we are back at Riverside" / "we have left Riverside behind" | home |
| mount gained / lost | "Jake is riding a horse now" | the mount |
| death count rose | "Jake died since we last talked" | dying |

First call for an audience (no previous snapshot) yields only first-seen anchors, and seeds the
registry with everything currently visible so the *next* scene reports genuine novelty.
The registry is session-scoped (in-memory per runtime); persistence is Phase 2.

### 2. `SoulSpeechAct` — what kind of thing to say

`OBSERVE, ASK, TEASE, PLAN, RECALL, WORRY, JOKE`, each with a directive template for group and
solo rosters (e.g. ASK group: "one of you asks the other something real about", solo: "ask Roti
something about"). Eligibility: RECALL needs an event anchor; WORRY needs a danger/health/food
anchor; the rest are always eligible. Weighted pick (OBSERVE 3, ASK 3, others 2) that skips the
last 4 acts used for this audience while any other eligible act exists. Pure, randomness injected.

### 3. Seed integration

`SoulBanterSeed.buildSeed(..., recentTopics, changeAnchors, act)`: change anchors join the pool;
the primary pick is weighted as before (so a change usually wins); the seed text starts with the
act's directive instead of a fixed "talk about". `Seed` gains `act`. Old overloads unchanged.

### 4. Director

`SoulBanterDirector` keeps `Map<UUID, AudienceMemory>` (last grounding, seen keys, recent acts,
recent topics — the latter moved in from 1.1.194). On fire: diff → act → seed → remember. Fired
log line gains `act=`.

### 5. Tests

`SoulSceneDiffTest` (identical → no change anchors; weather/biome/first-seen/health/home cases;
registry seeded on first call), `SoulSpeechActTest` (rotation, eligibility), seed tests for the
act prefix and change-anchor precedence.

Out of scope (Phase 2/3): open threads + stance, day-memory consolidation/callbacks, typed
relation facts, structured output + novelty rejection.
