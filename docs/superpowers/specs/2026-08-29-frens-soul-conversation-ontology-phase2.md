# Frens soul conversation ontology — Phase 2 design (2026-08-29)

Continuity. Phase 1 (1.1.196) gave scenes *what changed* and *what kind of thing to say*.
Field transcript after the 1.1.197 fixes still has no memory across scenes: nobody follows
up a question, nobody holds a grudge, yesterday never happened. Phase 2 adds three
deterministic (no-LLM) state machines and feeds them into the prompt and the seed.

## 1. Per-bot `mind.json` (new store file, `knowledge.json` precedent)

`SoulTypes.SoulMind(schemaVersion, Stance playerStance, List<OpenThread> threads,
List<DayMemory> memories, Set<String> seen, long lastConsolidatedTick, int lastDay)`.
Loaded/saved by `SoulStore` on the writer thread with tmp+atomic replace, cached in
`SoulRuntime` like `cachedState`. `soul.json` is untouched (no migration risk).

- **`Stance(trust, exasperation, curiosity)`** — ints 0..6, baselines 3/0/3, toward the
  audience player only (bot↔bot stance waits for Phase 3 typed relations). Moves by rule:
  open thread answered → trust+1, curiosity−1 · thread expired unanswered → exasperation+1 ·
  player gives a task (TASK_STARTED, category ≠ hobby, once per day) → trust+1 ·
  OWNER_DAMAGE witnessed → curiosity+1. Decays one step toward baseline per Minecraft day
  at consolidation. Rendered as one clause in `stateBlock` via a word ladder
  ("wary of Roti" / "" / "would follow Roti anywhere"; "fed up with being ignored" at ≥2,
  "sulking" at ≥4; "full of questions for Roti" at ≥5). ≤60 chars.
- **`OpenThread(question, askedAtMs, sceneId, answered)`** — a bot's line that ended a
  player-addressed scene (or contained the owner's name) and ends with `?`, ≤120 chars,
  max 3 per bot (oldest evicted). Closed as *answered* by any addressed chat/DM from the
  player to that bot within 10 min real time; otherwise *expired* → exasperation+1 and it
  becomes a seed anchor: `Bob never got an answer about "<question>"` (weight 5, topic
  "unanswered question", fits ASK/TEASE). Recalled once, then dropped.
- **`DayMemory(day, topic, phrase, place, participants, salience, lastRecalledDay)`** —
  produced by consolidation (§2). Cap 30 per bot; salience −1 per day, evicted at 0;
  recalling one bumps `lastRecalledDay` and halves its pick weight for 3 days.
- **`seen`** — the Phase 1 first-sighting registry, moved from the director's per-audience
  in-memory map to the bot (cap 400 keys). `SoulSceneDiff.diff` takes the union of the
  roster's sets and writes new keys back to every roster bot, so "the first wolf any of you
  have seen" is literally that and survives restarts.

## 2. Day consolidation (deterministic)

Trigger: `SoulEventObserver`'s 1 Hz sampler notices the Minecraft day number changed for a
bot (`world.getTimeOfDay()/24000`) — for a sleeping bot that is the moment they wake; bots
that never sleep still get one. No LLM call. Steps, on the store thread:
1. `SoulStore.eventsSince(botId, lastConsolidatedTick)` — new ranged reader (today
   `recentEvents` parses the whole file every call).
2. Group by `SoulBanterSeed.topicOf(event)`; score = Σ salience (HIGH 6 / NORMAL 3 / LOW 1)
   + min(count,3); drop SLEEP/WAKE and sleep tasks; drop topics already held as a memory
   from the same day.
3. Top 3 → `DayMemory` with the humanized phrase (`phraseFor`, ≤80 chars), place = base
   name if at a base else biome, participants = owner / other bot when in `participants`.
4. Apply stance decay, expire threads, save `mind.json`, then trim `events.jsonl` to the
   last 200 records (atomic rewrite) so the journal stops growing forever.

## 3. Prompt + seed

- `SoulGroupPromptAssembler.stateBlock`: append the stance clause per bot.
  New `OPEN THREADS` message between state and history, only when non-empty:
  `Bob still wants to know: "Did you find the iron?"` (≤200 chars total).
- `SoulBanterSeed.buildSeed` gains `mindsPerBot`: memory anchors at weight 4 (between
  grounding 3 and change 5), phrase `remember when <phrase>` with `on day <n>` when older
  than a day; thread anchors at weight 5. `SoulSpeechAct` RECALL eligibility widens from
  "has an event anchor" to "has an event **or memory** anchor"; RECALL re-picks the primary
  among those (existing mechanism).
- `SoulRuntime.sceneDelivered` receives the delivered `SceneLine`s (widen
  `GroupScenePlayback.LineCommitter.sceneDelivered`) and runs thread extraction;
  `SoulLocalDirector.noteAddressedChat` / the chat router's addressed path mark threads
  answered.

## Phases (≤5 files each, approval between)
- **2a types + store + ops**: `SoulTypes` (4 records), `SoulStore` (mind load/save,
  `eventsSince`, `trimEvents`), new pure `SoulMindOps` (stance rules, thread open/close/
  expire, consolidate, decay, seen merge), tests `SoulMindOpsTest`, `SoulStoreTest` (+3).
- **2b hooks**: `SoulEventObserver` (day rollover → `runtime.onNewDay(botId)`),
  `SoulRuntime` (cached minds, consolidation, thread extraction), `GroupScenePlayback`
  (committer signature), `SoulLocalDirector` (answered), `SoulChatRouter` (addressed → answered).
- **2c prompt + seed + director**: `SoulGroupPromptAssembler`, `SoulBanterSeed`,
  `SoulSpeechAct`, `SoulSceneDiff` (seen from minds), `SoulBanterDirector` (wiring;
  `AudienceMemory` keeps only lastGrounding/topics/acts and evicts audiences idle >1 h).

Out of scope (Phase 3): bot↔bot stance, typed relation facts, structured `{act, topic,
lines}` output, phrase-novelty rejection, LLM-written memories.

## Testing
Pure ops fully unit-tested (rules, caps, decay, consolidation scoring, thread lifecycle).
Store round-trip incl. missing `mind.json` and journal trim. Assembler: stance clause and
OPEN THREADS block present/absent. Seed: memory anchor weight and RECALL eligibility.
Field: play across a Minecraft day boundary; ask a bot nothing after it asks you something
and listen for the follow-up; check `mind.json` after the first consolidation.
