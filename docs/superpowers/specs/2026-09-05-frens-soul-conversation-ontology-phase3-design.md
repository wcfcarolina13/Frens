# Frens soul conversation ontology — Phase 3 design (2026-09-05)

**Status:** design only, awaiting Bradley's review. No code, no implementation plan.
**Baseline:** frens 1.1.207; suite ~624 green; nothing soul-side field-tested since 1.1.184
(`docs/testing/FIELD_SESSION_1.1.202.md` is the pending session).
**Parent specs:** `2026-08-29-frens-soul-conversation-ontology-phase1.md` (change anchors,
speech acts), `…-phase2.md` (`mind.json`, stance, open threads, day memories, persisted seen —
its "Out of scope (Phase 3)" line is exactly this document's brief),
`2026-09-04-frens-soul-memory-digest-design.md` (playerMemories, digestCursors, the clerk-prompt
pattern this reuses), `2026-08-29-frens-soul-player-engagement-design.md` (the "second
personality" follow-up, addressed in §0).

## Problem

Phase 2 gave a bot a memory of *the world* and a feeling about *one* person: the owner. The
`Stance` record is documented as "toward the audience player only" (`SoulTypes.java:286-297`),
and Phase 2's own note says bot↔bot stance waits for typed relations. Three consequences show up
in every group scene:

1. **Jake and Bob have no history with each other.** Two bots who have argued for a week greet
   each other the same way on day 1 and day 8. All relational state is bot→owner.
2. **Everything the mind knows is free text.** `DayMemory.phrase`, `PlayerMemory.fact`, and
   `OpenThread.question` are strings that can only be pasted into a prompt. Nothing can be
   queried ("what does Jake think Bob is bad at?"), deduped structurally, or contradicted.
3. **The model's only channel is prose.** `SoulGroupResponseValidator.parse`
   (`SoulGroupResponseValidator.java:95`) turns output into `Name: line` and that is all a scene
   can ever produce. Stance moves only by hard-coded rules in `SoulMindOps`
   (`noteTaskGiven:129`, `noteOwnerHurt:139`, `markAnswered:88`) — never by what actually
   happened in the conversation.

And a fourth, cheapest to fix and most visible in the field transcripts: **the bots repeat
themselves.** Rotation today is topic-level only — `AudienceMemory.topics`
(`SoulBanterDirector.java:89-94`, cap `RECENT_TOPIC_MEMORY = 6` at :97) blocks a repeated *topic
key*, not a repeated *sentence*. A bot can say "Torch is unhappy down here" under `danger`, then
again under `the hour`, and nothing notices.

## Goals / Non-goals

**Goals**
- A bot holds a persistent, decaying opinion of every *other bot* it shares scenes with, moved by
  what happens in those scenes.
- Mind content that is worth querying is stored as typed triples, not sentences, without throwing
  away the free-text rendering path prompts already use.
- The model may, when it can, return structured side-effects (stance nudges, new facts, thread
  ops) alongside its lines — and a malformed response must degrade to exactly today's behaviour,
  not to a failed scene.
- No bot says something it (or the scene) said recently, judged without embeddings.
- Every new store field loads as empty from an existing `mind.json` — no migration step, no
  schema-version bump, same contract Phase 2 and the digest already honour
  (`SoulTypes.java:363-368`, `SoulStore.loadMind:737`).

**Non-goals**
- Semantic retrieval, embeddings, or a vector index (`Database/` is not involved).
- Bot↔bot *conversation* outside the existing group scene (no DMs between bots).
- LLM-written day memories (still deterministic; the digest already covers LLM-written *player*
  memories).
- Any change to the voice/TTS lanes, LoadGoverner probe, or delivery masks.
- Personality *content*. See §0.

## §0. The "second scripted-text personality" — separate spec

**Recommendation: separate spec, and it should ship before or beside this one.**
`silas.json` (`src/main/resources/data/frens/souls/silas.json`, registered at
`SoulProfileRegistry.java:32` and loaded at :55) is a *soul* profile — LLM identity, values,
boundaries, examples. The engagement spec's "second personality" follow-up is about the **scripted
dialogue packs** (`GameAI/services/dialogue/`, the Voiced/Text lane), which is authored content
with zero ontology machinery: no store fields, no prompt blocks, no model contract. Bundling it
here would mix a content deliverable with a state-model deliverable and violate the lane rule
recorded in `feedback_dialogue_lane_separation.md` (scripted and soul lanes are independent).
It is out of scope for this document, and is *not* blocked by it.

---

## (a) Bot↔bot stance

### Approaches

1. **Reuse `Stance` per peer.** `Map<UUID peerBotId, Stance>` beside `playerStance`. Same 0..6
   fields, same decay, same `stanceClause` ladder with the peer's name.
   *Trade-off:* trivial, symmetric with what exists, tested rules reused. But trust/exasperation/
   curiosity are owner-shaped ("would follow Roti anywhere") and read oddly bot-to-bot.
2. **A new `PeerStance(warmth, friction, respect)` record.** Vocabulary chosen for peers.
   *Trade-off:* better prose, but a second decay path, a second ladder, a second test surface, and
   a second thing to keep in sync when Phase 4 wants a fourth axis.
3. **Derive peer stance from typed relations (b)** — no dedicated field; `(Jake, annoyed_by, Bob,
   0.7)` facts are rendered on demand.
   *Trade-off:* one storage mechanism instead of two, but confidence-weighted triples decay and
   aggregate badly, and every scene would pay a scan.

**Recommendation: (1), reuse `Stance` per peer, with a peer-flavoured word ladder.** Keep the
record, keep the clamp and the decay (`SoulMindOps.stepToward:356`), add a second rendering
function so the *words* fit a peer while the *numbers* stay one tested model. Approach 3 is the
tempting one and it is wrong: stance is a slowly-moving scalar, relations are discrete claims.

### Data

```json
{
  "schemaVersion": 1,
  "playerStance": { "trust": 4, "exasperation": 1, "curiosity": 3 },
  "peerStances": {
    "1f0c...-bob": { "trust": 2, "exasperation": 3, "curiosity": 4 }
  }
}
```
`SoulMind` gains `Map<UUID, Stance> peerStances` (default `Map.of()` via the existing compat
constructor pattern at `SoulTypes.java:363`). Cap **6 peers**, evicting the peer whose stance is
closest to `Stance.BASELINE` (a bot you feel nothing about is the one to forget).

### Update rules (deterministic, pure, in `SoulMindOps`)

Applied to every roster bot at `sceneDelivered`, keyed by the *other* participants:

| trigger (all observable from the delivered `SceneLine`s + the turn) | effect on speaker→peer |
|---|---|
| peer's line names this bot and ends with `?` (asked something) | curiosity +1 |
| this bot's line names the peer and ends with `?` while the peer never speaks again in the scene | exasperation +1 |
| both bots speak in the scene at all | trust +1, **once per Minecraft day per pair** (guarded like `lastTaskTrustDay`, `SoulTypes.java:349` / `SoulMindOps.noteTaskGiven:129`) |
| a TEASE-act scene (`SoulSpeechAct`) delivered with ≥2 lines | exasperation +1 *and* trust +1 (banter is affectionate friction) |
| peer present at an `OWNER_DAMAGE` event day (from the journal at consolidation) | trust +1 |

Decay: one step toward baseline per peer per consolidation, inside the existing
`SoulMindOps.consolidate` (`SoulMindOps.java:204`) — the same place `playerStance` and
`playerMemories` already decay.

### Hooks

- Read/aggregate: `SoulRuntime.noteSceneDeliveredForMind` (`SoulRuntime.java:839`, invoked from the committer at `:263`) already
  receives the turn, `lastSpeakerIndex`, and the delivered lines, and already calls
  `updateMind` per bot. Peer-stance updates are a second pure call in that method — no new seam.
- Render: `SoulGroupPromptAssembler.stateBlock` (`SoulGroupPromptAssembler.java:218`), where the
  owner stance clause is appended at :248-252. One extra clause per bot, only for peers **present
  in this roster**, ≤50 chars, bounded by the existing `MAX_STATE_CHARS_PER_BOT = 400` (:36).

Example addition to `CURRENT STATE`:
```
Jake: health 18/20, hunger 14/20, holding an iron pickaxe, would follow Roti anywhere, short with Bob
Bob: health 20/20, hunger 17/20, mood cheerful, curious about Jake
```

---

## (b) Typed relation facts

### Approaches

1. **Full triple store in `mind.json`** — `RelationFact(subject, relation, object, confidence,
   source, day, salience)` with an enum of relations, replacing free-text where it can.
   *Trade-off:* queryable, dedupable by `(subject, relation, object)`, contradiction-detectable.
   Costs a new render path and a bigger file.
2. **Free-text with tags** — keep `DayMemory`/`PlayerMemory` strings, add a `relation` tag field.
   *Trade-off:* nearly free, but tags on prose give you grouping and nothing else; you still
   cannot say "we already believe the opposite".
3. **Typed facts as a strictly additive sidecar** — a new `relations` list that *coexists* with
   the existing memories rather than replacing them; only the (c) structured-output path and a
   small deterministic extractor write to it.
   *Trade-off:* two stores of overlapping truth for a while, but zero regression risk to shipped
   recall, and it can be deleted if it does not earn its place.

**Recommendation: (3) — additive sidecar, with (1)'s record shape.** Replacing `PlayerMemory`
would put the day-old, unfield-tested memory digest through a rewrite before it has been proven in
game once. Ship the typed shape next to it; if relations prove better in the field, Phase 4 can
migrate the digest to emit relations directly and retire the free-text list.

### Data

```json
{
  "relations": [
    { "subject": "Roti",  "relation": "DISLIKES",   "object": "the Nether",
      "confidence": 0.8, "source": "SAID",     "day": 12, "salience": 8 },
    { "subject": "Bob",   "relation": "GOOD_AT",    "object": "finding caves",
      "confidence": 0.5, "source": "INFERRED", "day": 14, "salience": 6 },
    { "subject": "Roti",  "relation": "PROMISED",   "object": "to build a bridge at the ravine",
      "confidence": 0.9, "source": "SAID",     "day": 14, "salience": 10 }
  ]
}
```
- `relation` — a **closed enum**: `LIKES, DISLIKES, FEARS, GOOD_AT, BAD_AT, PROMISED, WANTS,
  CALLS` (the last for player-coined names: `(Roti, CALLS, the north camp "the Rookery")`).
  A value outside the enum is dropped at parse, never stored — an open string field is how a
  3B model turns a knowledge base into noise.
- `subject` / `object` — display strings, ≤60 chars each, `subject` restricted to a name present
  in the scene (owner or a roster bot) or the literal `"the world"`.
- `confidence` — 0.0–1.0, quantised to one decimal on write.
- `source` — `SAID` (player/bot stated it), `SEEN` (derived from the event journal), `INFERRED`
  (model asserted it in structured output).
- Cap **20 per bot**; eviction lowest `salience` then oldest `day`. Salience −1 per consolidation,
  +3 on recall — identical to `PlayerMemory` (`memory digest spec §3`), so one decay rule covers both.
- **Contradiction rule:** a new fact with the same `(subject, relation)` and a *different* object
  where the relation is single-valued (`GOOD_AT`, `BAD_AT`, `CALLS`) replaces the old one when its
  confidence is ≥ the old one's, else is dropped. `LIKES`/`DISLIKES` of the same object are direct
  opposites: writing one removes the other. Everything else accumulates.

### Hooks

- Write: only two producers. (i) `SoulRuntime.noteSceneDeliveredForMind:832` for the structured
  path from (c); (ii) `SoulMindOps.consolidate:204` for `SEEN` facts derived from the journal
  (e.g. repeated `TASK_STARTED` of the same category by the same bot → `(Bob, GOOD_AT, mining)`
  at confidence 0.4). No other caller.
- Render: a `BELIEFS` block in `SoulGroupPromptAssembler`, built exactly like `aboutBlock`
  (`SoulGroupPromptAssembler.java:301-322`) and inserted immediately after it in `assemble`
  (:74) — so `CURRENT STATE` (present truth) still precedes both remembered-speech blocks.
  Cap **4 lines, ≤240 chars**.
- Seed: `SoulBanterSeed.Anchor` at weight **4** (same tier as day memories,
  `SoulMindOps.MEMORY_ANCHOR_WEIGHT:35`), topic `relation:<RELATION>`, phrase
  `Roti once said he can't stand the Nether`. RECALL-eligible like memory anchors. Produced by a
  new pure `anchors(...)` mirroring `SoulMemoryDigestOps.anchors` (`SoulMemoryDigestOps.java:422`)
  and appended in the director's existing `mindAnchors` loop (`SoulBanterDirector.java:288-294`).

Example block:
```
BELIEFS (what Jake has come to think; claims, not world truth)
- Roti dislikes the Nether
- Roti promised to build a bridge at the ravine
- Bob is good at finding caves
```

---

## (c) Structured LLM output

### Approaches

1. **Whole response is JSON** — `{"lines":[{"speaker":"Jake","text":"…"}],"stance":[…],"facts":[…]}`.
   *Trade-off:* one parse, everything available. But if the JSON is bad the whole *scene* is lost;
   today's transcripts already show `outcome=failed:MALFORMED` from the 3B model on a plain
   `Name: line` grammar (`SoulGroupResponseValidator.java:113-119` documents exactly that fix).
   Raising grammar difficulty on a model that already fails the easy grammar is the wrong bet.
2. **Prose first, JSON appended after a sentinel** — the model writes normal lines, then
   optionally a final line beginning with a sentinel token containing a compact JSON object.
   *Trade-off:* the scene survives an unparseable tail unconditionally; the sentinel is trivially
   stripped before delivery; the model can simply omit it. Costs a strict "sentinel must be the
   last non-blank line" rule.
3. **A second generation** — deliver the scene, then submit a separate clerk call that reads the
   delivered lines and emits the side-effects, exactly like `SoulMemoryDigestService`.
   *Trade-off:* highest quality parse (a clerk prompt is a much easier task than dual-format
   generation) and zero risk to the scene, but doubles generation count per scene, which the
   LoadGoverner floor and the pacing bands were not sized for.

**Recommendation: (2) sentinel-suffixed JSON, with (3) as the documented fallback if the field
session shows the 3B model can't produce a usable tail.** (2) keeps the one-generation budget and
is fail-safe by construction. Specifically:

- Sentinel: a line whose first non-space characters are `##FRENS` followed by a single JSON object.
- The validator's line loop (`SoulGroupResponseValidator.java:120-130`) treats a `##FRENS` line as
  **end-of-scene**, exactly as `endAtOwnerAddress` already ends a scene at :92-97 — everything after
  it is dropped, and the line itself never becomes a `SceneLine`.
- `SceneParse` (`:36`) gains `Optional<String> sideChannelRaw` (default empty; existing callers
  source-stable). Parsing the JSON is **not** the validator's job — it hands the raw string on.
- A new pure `SoulSideChannelOps.parse(raw, rosterNames, ownerName)` → `SideEffects(stanceDeltas,
  facts, threadOps)` where every element is individually validated and individually droppable.

Schema the prompt asks for (kept deliberately tiny):
```
##FRENS {"stance":{"Bob":{"warmth":-1}},"facts":[["Roti","DISLIKES","the Nether",0.8]],"threads":{"closed":["Did you find the iron?"]}}
```
- `stance` — peer display name → one of `warmth` / `friction` / `curiosity`, value in `-1|0|+1`
  **only**. Mapped onto `Stance.trust` / `.exasperation` / `.curiosity`. Any other key or magnitude
  is dropped. **A model-asserted delta is capped at ±1 per scene per axis per peer**, and never
  applies to `playerStance` — owner stance stays rule-driven so the model cannot flatter itself
  into the owner's good graces.
- `facts` — array of `[subject, relation, object, confidence]` 4-tuples, ≤3 per scene, written at
  `source: "INFERRED"`, confidence clamped to ≤0.6 regardless of what the model claims.
- `threads.closed` — question strings the model believes were answered in-scene; matched against
  the bot's own `OpenThread.question` by the (d) normaliser at ≥0.7 overlap, then routed through
  the existing `SoulMindOps.markAnswered:88`. No thread *opening* — that stays deterministic in
  `extractQuestion` (`SoulMindOps.java:174`).

### Failure and fallback (the important part)

| failure | behaviour |
|---|---|
| no `##FRENS` line at all | scene delivers normally; zero side-effects. **This is the expected common case and must never be logged as an error.** |
| `##FRENS` present, JSON unparseable | line dropped from the scene, side-effects empty, one INFO `[souls] side-channel unparsed correlationId=… chars=N` |
| JSON parses, unknown keys | unknown keys ignored, known ones applied |
| a relation outside the enum, a stance magnitude >1, a subject not in the scene | that element dropped, the rest applied, counts logged |
| `##FRENS` appears mid-scene (model put it first) | scene ends there; if **zero** lines were parsed, the parse is rejected as today (`reject("no roster-tagged dialogue lines")`, `SoulGroupResponseValidator.java:188`) and the existing cooldown-refund path (`SoulBanterDirector.java:341+`) applies unchanged |
| model emits `##FRENS` but the scene was ambient-muted | side-effects still apply — they are state, not speech |

Prompt cost is one added paragraph in `sceneContract()` (the SYSTEM message added at
`SoulGroupPromptAssembler.java:70`), and it must be phrased as **optional**:

```
You may end your reply with one extra line starting with ##FRENS followed by a JSON object
recording what changed: {"stance":{"<name>":{"warmth":1}},"facts":[["<who>","LIKES","<what>",0.7]]}.
Use only the relations LIKES, DISLIKES, FEARS, GOOD_AT, BAD_AT, PROMISED, WANTS, CALLS. Values
for warmth, friction and curiosity are only -1, 0 or 1. If nothing changed, omit the line
entirely. Never let this line be spoken by a character.
```

`MAX_OUTPUT_TOKENS` for group scenes is **320** (`SoulGroupPromptAssembler.java:38`). A sentinel
line is ~40–60 tokens, so it should rise to **380** *only when the feature is enabled*; otherwise
a long scene plus a tail gets truncated and the tail is what survives least gracefully.

---

## (d) Novelty rejection

### Approaches

1. **Per-bot ring of the last N delivered line texts, normalised, rejected on n-gram overlap.**
   *Trade-off:* catches literal and near-literal repeats, cheap, pure, no store growth if the ring
   is in-memory. Misses paraphrase.
2. **Keyword/content-word set overlap (Jaccard), same ring.**
   *Trade-off:* catches paraphrase better; more false positives on short lines ("Aye." vs "Aye,
   right."), which is fatal for a laconic persona like Silas.
3. **Both, gated by length** — trigram overlap for lines ≥8 words, exact-normalised-match only for
   shorter ones.
   *Trade-off:* one more branch; correct on both ends.

**Recommendation: (3).** Reuse the digest's already-tested normaliser (`SoulMemoryDigestOps`
merge does lowercase + punctuation strip + stop-word removal for its Jaccard ≥0.6 dedupe —
memory-digest spec §6), so the vocabulary work is not written twice.

- **Where:** a rejection pass over `SceneParse.lines` after `SoulGroupResponseValidator.parse`
  returns and before delivery — i.e. in the runtime's committer path, not inside the validator
  (the validator is grammar; novelty is history). A rejected line is **dropped, not the scene**;
  if every line is dropped, the scene is treated as zero-delivery, which the existing
  `sceneDelivered` contract already handles (`SoulRuntime.java:265-272` explicitly documents the
  zero-delivery case).
- **State:** `Map<UUID botId, ArrayDeque<String>>` of the last **12** normalised delivered lines,
  in the same in-memory tier as `AudienceMemory` (`SoulBanterDirector.java:89`), plus a per-scene
  set so two bots cannot say the same thing inside one scene. **Not persisted** — a restart
  legitimately resets what "recently" means, and this keeps `mind.json` from growing per line.
- **Thresholds:** ≥8 content words → reject at trigram overlap ≥0.6 against any remembered line;
  <8 → reject only on exact normalised match. Every rejection logs
  `[souls] novelty dropped bot=… reason=trigram|exact`.

---

## Interactions with the memory digest and day consolidation

- **Decay lives in one place.** `SoulMindOps.consolidate` (`SoulMindOps.java:204`) already decays
  `playerStance`, expires threads, decays `DayMemory`, and (per the digest spec §6) decays
  `playerMemories`. `peerStances` and `relations` join that same pass. Nothing new is scheduled;
  `SoulRuntime.onNewDay:773` is untouched except that the mind it saves has more fields.
- **The digest runs after consolidation and does not see relations.** Deliberate: the digest's
  clerk prompt (memory-digest spec §6) produces free-text `PlayerMemory` facts. A Phase 4 could
  swap its output format to relation tuples; this spec does not, because that would change an
  unfield-tested feature. Until then a player statement can produce *both* a `PlayerMemory` (via
  digest) and a `RelationFact` (via the (c) side channel). **De-dup rule:** when building the
  `BELIEFS` block, drop any relation whose rendered phrase has Jaccard ≥0.6 against a fact already
  in the `ABOUT` block — the same comparator (d) and the digest merge use. `ABOUT` wins because it
  is the higher-confidence, clerk-validated path.
- **Anchor competition.** The seed pool already carries change anchors (5), thread anchors (5),
  memory anchors (4), player-memory anchors (4), grounding (3)
  (`SoulBanterSeed.java:104-137`, `SoulMindOps.java:35-38`). Relation anchors at 4 with
  `MAX_RELATION_ANCHORS = 2` keep the pool from being swamped; without a cap, 20 relations would
  outvote everything else by sheer count.
- **Journal trim is unaffected.** `store.trimEvents(botId, 200)` (`SoulRuntime.java:801`) stays;
  relations derived at consolidation are exactly why trimming is safe — the durable claim outlives
  the raw event.

## Cost budget

Per group scene, worst case, on top of today's prompt:

| feature | prompt cost | output cost | when it is paid |
|---|---|---|---|
| (a) peer stance | ~12 tokens/bot in `CURRENT STATE` (≤50 chars/clause) | 0 | every scene with ≥2 bots, only for non-baseline peers |
| (b) `BELIEFS` block | ≤240 chars ≈ 70 tokens | 0 | only when a bot has relations |
| (c) side channel | ~90 tokens of contract, one-off per request | up to ~60 tokens | every scene while enabled |
| (d) novelty | 0 | 0 | pure post-processing |

Ceiling ≈ **+180 prompt / +60 output tokens** per group scene. Against a current group request of
roughly 3–5k characters (`MAX_HISTORY_CHARS = 4000`, `MAX_IDENTITY_CHARS_PER_BOT = 600`,
`MAX_STATE_CHARS_PER_BOT = 400`, `MAX_SITUATION_CHARS = 800` —
`SoulGroupPromptAssembler.java:33-40`) that is a single-digit percentage. The real cost is
**latency on a 3B local model**, which is why (c) is the one to gate.

**Gating:** none of these get a new frequency slider. Scene *frequency* is already governed by
`DialoguePacing` (`services/dialogue/DialoguePacing.java:17-40`, mirrored Frens-free in
`SoulBanterDirector.java:489-499` and `SoulLocalDirector.java:61`), and these features add cost
*per scene*, not scenes. What they get is **one `ManualConfig` boolean each for (b) and (c)** —
`soulRelationsEnabled`, `soulStructuredOutputEnabled`, both default **false** on the build that
introduces them and flipped to true after the field session — matching how
`soulMemoryDigestEnabled` shipped (digest spec §8). (a) and (d) need no toggle: (a) is a clause
that is empty at baseline, (d) only ever removes output.

## Testing

**Pure and unit-testable (no game):**
- (a) `SoulMindOpsTest` +: each peer trigger, once-per-day trust guard, 6-peer cap and
  closest-to-baseline eviction, decay toward baseline, peer word ladder rendering (including the
  empty string at baseline).
- (b) new `SoulRelationOpsTest`: enum rejection, subject-in-scene rejection, confidence
  quantisation/clamp, single-valued contradiction replace, LIKES/DISLIKES opposition, cap
  eviction order, salience decay/recall bump, anchor weight and cap.
- (c) new `SoulSideChannelOpsTest`: absent sentinel → empty, unparseable JSON → empty + scene
  intact, unknown keys ignored, stance magnitude >1 dropped, ≥4 facts truncated to 3, confidence
  clamped to 0.6, `##FRENS` first line → zero scene lines → reject path.
  `SoulGroupResponseValidatorTest` +: sentinel ends the scene, sentinel line never becomes a
  `SceneLine`, `sideChannelRaw` carried through, existing parses unchanged when no sentinel.
- (d) new `SoulNoveltyOpsTest`: exact repeat rejected, trigram-0.6 paraphrase rejected, short-line
  paraphrase *kept*, ring eviction at 12, same-line-twice-in-one-scene rejected, all-lines-dropped
  → zero delivery.
- Assemblers: `BELIEFS` present/absent/truncated; peer clause present/absent; `CURRENT STATE`
  still under `MAX_STATE_CHARS_PER_BOT` with the peer clause appended.
- Store: `mind.json` written before this build loads with empty `peerStances`/`relations`.

**Needs the guided field session** (format per `docs/testing/FIELD_SESSION_1.1.202.md`):

- [ ] **Peer clause appears after a shared scene**
  - Bradley does: with Jake and Bob together and quiet, let two banter scenes fire, then read
    `mind.json` for both bots.
  - Claude watches for: `peerStances` containing the other bot's UUID after the second scene.
  - Pass when: both files hold one peer entry, values within 0..6, neither equal to all-baseline.
- [ ] **Peer stance reaches the prompt**
  - Bradley does: nothing; third scene.
  - Claude watches for: the request's `CURRENT STATE` block (diagnostic logging only) carrying a
    peer clause for at least one bot.
  - Pass when: the clause names the other bot and is ≤50 chars.
- [ ] **Trust bump is once per day**
  - Bradley does: force three scenes in one Minecraft day (`/bot soul banter now` ×3).
  - Claude watches for: `peerStances` trust unchanged between scene 2 and scene 3.
  - Pass when: trust moved at most once that day.
- [ ] **Side channel is emitted at all**
  - Bradley does: `/bot soul structured on`, then tell Jake something opinionated ("I hate the
    Nether") and let a scene fire.
  - Claude watches for: `[souls] side-channel` INFO with a nonzero applied count, or the absence
    line — either is data, both are recorded.
  - Pass when: over 5 scenes, at least one usable `##FRENS` tail appears **or** the outcome is
    recorded as "3B cannot do it" and fallback (3) is scheduled.
- [ ] **A malformed tail never costs a scene**
  - Bradley does: nothing; watch across the same 5 scenes.
  - Claude watches for: any `side-channel unparsed` line, and the scene's delivery outcome on the
    same `correlationId`.
  - Pass when: every scene with an unparsed tail still delivered its lines; zero
    `outcome=failed:MALFORMED` attributable to a sentinel.
- [ ] **`##FRENS` is never spoken**
  - Bradley does: read chat during those scenes.
  - Claude watches for: the literal string `##FRENS` in a delivered line or in TTS text.
  - Pass when: it never appears in chat or audio.
- [ ] **A belief reaches a later scene**
  - Bradley does: after a relation appears in `mind.json`, sleep through a night, then let banter
    fire twice.
  - Claude watches for: seed `topic="relation:…"` on a fired line.
  - Pass when: a scene is seeded from a relation and the delivered line references it without
    stating it as world fact.
- [ ] **Novelty rejection fires and does not gag the bots**
  - Bradley does: sit through ~6 scenes in one sitting.
  - Claude watches for: `[souls] novelty dropped` lines and the delivered-line count per scene.
  - Pass when: ≥1 drop occurs across the session **and** no scene delivers zero lines because of
    novelty alone.
- [ ] **Silas stays laconic**
  - Bradley does: run at least two scenes with Silas in the roster.
  - Claude watches for: `novelty dropped reason=exact` on Silas's short lines.
  - Pass when: Silas's short repeated idioms ("Aye.") are **not** dropped as paraphrase — only
    exact repeats are.
- [ ] **Old minds load clean**
  - Bradley does: launch with the pre-Phase-3 `mind.json` files in place (no reset).
  - Claude watches for: `[souls] mind consolidated` at the first day rollover, no Jackson
    `UnrecognizedProperty`/`InvalidDefinition` warnings.
  - Pass when: consolidation succeeds and the rewritten file gains the new fields as empties.

## Open questions for Bradley

1. **Should model-asserted stance deltas touch the owner stance too, or peers only?**
   *Recommendation: peers only.* Owner stance drives the visible ladder and the seed; letting the
   model move it invites drift and flattery with no counterweight.
2. **Do relations replace `PlayerMemory` eventually, or coexist permanently?**
   *Recommendation: coexist for now, decide after the field session.* Retiring the digest's output
   format before it has been played once is premature; §"Interactions" defines the de-dup so
   coexistence is not visible in the prompt.
3. **Ship (c) enabled-by-default or off-by-default?**
   *Recommendation: off by default on its introducing build*, flipped after the field session
   confirms the 3B model produces a usable tail — same treatment `soulMemoryDigestEnabled` got.
4. **Is a `/bot soul beliefs <Bot>` command wanted in this phase?**
   *Recommendation: yes, read-only*, mirroring `/bot soul memory <Bot>` (digest spec §8). It is
   ~30 lines and it is the only way to see (b) working without opening a JSON file mid-session.
5. **Should novelty state persist across restarts?**
   *Recommendation: no.* Persisting it grows `mind.json` per line for a window that a restart
   legitimately resets, and the failure mode of forgetting is one repeated line.

## Sequencing

Four builds, in this order. Each is independently field-checkable and independently revertible.

1. **(d) novelty rejection — first, alone.** Pure, no schema change, no prompt change, no toggle,
   and it improves *every* existing scene including the ones Phase 1/2 already ship. It is also
   the prerequisite for (c)'s thread-close matching, which needs the normaliser. Smallest possible
   thing that makes the current field session's transcripts better.
2. **(a) bot↔bot stance — second.** One new map, rules that reuse tested primitives, one prompt
   clause, no model contract. Ships the visible "they have history" payoff without touching the
   generation path.
3. **(b) typed relations — third, with only the deterministic `SEEN` producer wired.** The store
   shape, caps, contradiction rules, `BELIEFS` block, and seed anchors all land and get proven by
   journal-derived facts before any model output is trusted.
4. **(c) structured output — last, and only once (b)'s consumer exists.** (c) is the only change
   that touches the generation contract on a model that already fails the simpler grammar; it must
   land on a build where everything downstream of it is already field-proven, so a bad field
   result is diagnosed as "the model can't" rather than "something in the chain is broken".

Not combined: (a) and (b) both write `mind.json` fields, and shipping them together would make a
bad field result ambiguous between two new state machines.
