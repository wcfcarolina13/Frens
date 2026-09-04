# Frens soul memory digest (consolidation, phase 1) — design (2026-09-04)

**Status:** approved in interview 2026-09-03/04 (six decisions below); Bradley pre-approved the
recommendations and asked for the spec, plan, and implementation to run through.
**Baseline:** frens 1.1.200; suite 624 green.
**Parent specs:** `2026-08-23-frens-soul-communication-design.md` §Consolidation (the contract this
fulfils: versioned, replaceable, source-linked, non-authoritative, runs at safe lifecycle points,
can be disabled or rebuilt without losing raw history); `2026-08-29-frens-soul-conversation-ontology-phase2.md`
(the `mind.json` store, day rollover hook, DayMemory caps/decay, seed anchor weights this reuses).

## 1. Goal

A player should notice that **a bot remembers what they said**. Days later Jake references plans,
preferences, promises, names for things, and feelings the player expressed in chat — on every
surface where the player is present. Today nothing is derived from what the player *says*: Phase 2
memories come only from the world-event journal, and prompts see only a bounded recent tail of
the one transcript for the surface in play.

Non-goals (later phases): relationship summaries as identity, bot↔bot memories, per-item forget,
LLM-written day memories, semantic retrieval, structured JSON output from the model.

## 2. Decisions from the interview

| # | Decision | Choice |
|---|---|---|
| C1 | Outcome | **Conversational recall** — facts the player stated, not a personality summary. |
| C2 | Extraction | **LLM summarizer, offline.** The local soul model reads new transcript material and emits ≤5 one-line facts. |
| C3 | Privacy | **Anywhere the player is present.** A DM-learned memory may surface in group, banter, or local scenes when that player is in the audience. One memory list per bot–player pair. |
| C4 | Trigger | **Piggyback the Phase 2 day rollover** (`SoulRuntime.onNewDay`), after event consolidation. |
| C5 | Ownership | **Per bot, with witnessed sharing.** A memory forms for every bot present in the scene (DM: that bot; party/banter/local: the scene roster). |
| C6 | Controls | **View + forget-all via the existing reset.** `/bot soul memory <Bot>` lists; `/bot soul reset <Bot>` archives memories with the transcript. |

## 3. Data model (`SoulTypes`, `mind.json`)

```
PlayerMemory(UUID playerId, int day, String fact, int salience, int lastRecalledDay,
             List<UUID> sourceCorrelationIds)
```
- `fact`: one line, third person, contains the player's display name at formation time
  ("Roti hates the Nether and avoids it"), ≤100 chars.
- `salience`: starts **10**; −1 per Minecraft day at consolidation; +3 when a scene recalls it
  (cap 10); evicted at 0. An unrefreshed memory lasts ~10 days; a recalled one indefinitely.
- Cap **24 per bot–player pair**, lowest salience then oldest evicted first.

`SoulMind` gains:
- `List<PlayerMemory> playerMemories` (all players, filtered by `playerId` at read time),
- `List<PlayerMemory> archivedPlayerMemories` (cap 100, newest kept; written by reset),
- `Map<String, ConversationCursor> digestCursors` keyed by the store's existing `cursorKey`
  string for a transcript (`<player-uuid>` for DIRECT under the bot's own store; the party key
  is prefixed `party:<owner-uuid>`) → last (epoch, sequence) digested.

Schema version stays 1; all three fields default to empty when absent (compat constructor, same
as `GroundingSnapshot.overheard`). `soul.json` untouched.

## 4. Party records carry the roster

`ConversationRecord` gains `List<UUID> participants` (default empty; compat constructor keeps
every existing call site source-stable). `SoulStore.beginHeardTurn` gains an overload taking
participants; `SoulGroupConversationService` passes the roster's bot ids for every scene kind
(PLAYER, BANTER, LOCAL, WORK). DM records leave it empty (the bot is implied by the file).

Presence rule for gathering: a bot was present in a party scene if its id is in the HEARD
record's `participants`, **or** (records written before this version) it has a SPOKEN line under
the same `correlationId`.

## 5. Gathering (`SoulStore` readers + pure `SoulMemoryDigestOps`)

New store readers (store thread, read-only):
- `conversationPlayers(botId)` → player UUIDs with a `conversations/<player>/active.jsonl`.
- `recordsSince(key, ConversationCursor cursor)` → records of the **current** epoch with
  `sequence > cursor.sequence` when `cursor.epoch == currentEpoch`, else the whole current epoch
  (a reset happened; the digested material was archived with it).

Sources per bot at rollover: every DIRECT key `(botId, player)` from `conversationPlayers`, plus
the party key of every owner whose party transcript exists (`partyStore` root scan) — filtered by
the presence rule per scene.

Filter (pure): keep HEARD and SPOKEN; drop FAILURE; drop HEARD records whose content starts with
the banter narrator prefix (`SoulGroupTypes`/seed's `BANTER_HEARD_PREFIX`) — synthetic directives,
not speech; keep LOCAL overheard lines (genuine player speech). Material per (bot, player) is
the newest **40 records / ~2,000 chars**, rendered `Name: line` in order. Minimum to run:
**≥4 player lines** (HEARD, not narrator) since the cursor; below that nothing runs and the
cursor stays.

## 6. Digest generation (`SoulMemoryDigestService`, new)

One `SoulGenerationScheduler.submit` per (bot, player) with key
`ConversationKey(botId, playerId, Channel.SYSTEM)` and the bot's DIRECT epoch — so it queues
FIFO behind live scenes, is governor-floored like any soul generation, and can never collide
with an active DM turn for the same key. Provider and model: whatever `SoulRuntime` resolves for
that bot's DM today (same `SoulModelProvider`).

Prompt (clerk contract, not the persona; assembled by the service, ≤ ~2.5k chars):
```
SYSTEM: You are a memory clerk for <Bot>, a Minecraft companion. Read the transcript and list
the things about <Player> that <Bot> should remember later: plans, preferences, promises,
names <Player> uses for places or things, and how <Player> feels. Rules: at most 5 lines; each
line starts with "- " and is under 100 characters; write in third person using the name
<Player>; only what <Player> actually said or clearly implied; no world facts, no advice, no
quotes. If nothing is worth remembering, reply with exactly "- none".
USER: <material>
```
Validation (pure, per line, `SoulMemoryDigestOps.validate`): strip via
`SoulResponseValidator.sanitizeBase`; drop blank lines; whole output rejected if more than 8 `- `
lines (runaway; prose lines are ignored); each kept line must start with `- `, be ≤100 chars after the dash, contain no
`§` or control chars, and contain the player's name (case-insensitive) or the word "they"; `-
none` alone → empty result. Everything else per line is dropped silently (count logged).

Merge (pure, `SoulMemoryDigestOps.merge`): for each accepted fact, if an existing memory for the
same player has word-set Jaccard ≥ 0.6 (lowercased, punctuation stripped, stop-words removed),
bump that memory's salience +2 (cap 10) and append the source ids; otherwise add a new memory at
salience 10 with `day` = the consolidation day. Then enforce the cap.

Cursor: advanced to the newest gathered record on **every** outcome — success, `- none`, provider
failure, OVERLOADED, or all lines rejected. Re-digesting identical lines tomorrow would not change
the answer; a persistent provider outage must not accumulate an ever-growing backlog.

Decay/eviction (pure, in the existing `SoulMindOps.consolidate` path): −1 salience per
consolidation day for all `playerMemories`; evict at 0.

## 7. Injection

- **Group/banter prompt** (`SoulGroupPromptAssembler`): new `ABOUT <Player>` message after the
  state block, only when non-empty: up to **5** facts for the scene's owner, ordered by salience
  desc then day desc, **≤300 chars** total, each line `- <fact>`. Per roster bot (Jake and Bob may
  differ). Rendered under the existing recalled-content framing (untrusted conversational content,
  not world truth).
- **DM prompt** (`SoulPromptAssembler`): same `ABOUT` block via a new `List<String>` parameter
  with a compat overload, placed after the authoritative state. Stance and open threads stay
  group-only (separate decision).
- **Banter seed** (`SoulBanterSeed`): player-memory anchors at **weight 4**, topic
  `memory:said`, phrase `<Player> once said: <fact>`; count as RECALL material like day memories;
  same 3-day recall cooldown via `lastRecalledDay`. Recall bump: when a scene the seed built from a
  player-memory anchor is **delivered**, `SoulRuntime.sceneDelivered` calls a new
  `SoulMindOps.notePlayerMemoryRecalled(mind, playerId, fact, day)` (+3, cap 10) — the same hook
  Phase 2 uses for thread extraction; the anchor carries the fact text so no index is needed.

## 8. Commands, reset, settings

- `/bot soul memory <Bot>` — owner-only (same authorization as the other soul subcommands):
  lists that bot's `playerMemories` for the caller, newest day first, `day N · salience S · fact`,
  or `"<Bot> doesn't remember anything about you yet."`. Reads the cached mind; no store round-trip
  on the server thread.
- `/bot soul reset <Bot>` (existing) — in addition to archiving the transcript and bumping the
  epoch: moves the caller's `playerMemories` into `archivedPlayerMemories` and removes the
  caller's `digestCursors` entries. Party reset (`/bot soul reset party`) does the same for the
  party cursor only (memories formed from party scenes stay — they belong to the bot–player pair,
  not the channel).
- `soulMemoryDigestEnabled` in `ManualConfig` (default **true**), `/bot soul digest on|off|status`.
  Off → `onNewDay` skips gathering entirely (provable no-op); existing memories still decay and
  still render (so turning it off does not amnesia the bot; reset does).

## 9. Failure and safety

- Nothing in this feature runs on the server thread except reading the cached mind for the
  command and the `onNewDay` entry that captures player names.
- Provider failure / OVERLOADED / validator-rejected-all → one INFO line
  (`[souls] memory digest bot=… player=… outcome=… kept=N dropped=M`), cursor advanced.
- Facts are claims. Prompts frame them as what the player *said*; they are never merged into
  grounding or `knowledge.json`.
- Transcript material and the model's raw output are logged only under the existing diagnostic
  mode; routine logs carry counts and outcomes.
- `LoadGoverner`: the digest is an ordinary scheduled generation, so `activeGenerations()`
  already covers it. No probe change.

## 10. Testing

- **Pure ops** (`SoulMemoryDigestOpsTest`): filter (FAILURE dropped, narrator prefix dropped,
  LOCAL kept), presence rule (participants field vs SPOKEN fallback), min-lines gate, material
  cap, validate (prefix, length, name/they requirement, `- none`, >8-line runaway), merge (Jaccard
  dedupe with bump, new insert, cap eviction order), decay/evict, recall bump, cursor advance on
  every outcome.
- **Store** (`SoulStoreTest` +): `mind.json` without the new fields loads with empties;
  `recordsSince` across an epoch change; `conversationPlayers`; party `participants` round-trip and
  old records without it.
- **Assemblers**: `ABOUT` block present / absent / truncated at 300 chars, DM and group.
- **Seed**: player-memory anchor weight and RECALL eligibility; recall cooldown.
- **Service** (`SoulMemoryDigestServiceTest`): fake provider returning a fixed digest → memories
  written, cursor advanced; provider failure → cursor advanced, no memories; disabled toggle → no
  submit.
- **Field** (next session): tell Jake three things, sleep through a night, inspect
  `mind.json`, listen for a recall in banter, run `/bot soul memory Jake`, then `/bot soul reset
  Jake` and confirm the list is empty and `archivedPlayerMemories` holds them.

## 11. Implementation phases (≤5 files each; build + tests between)

- **A — types, store, ops**: `SoulTypes` (PlayerMemory, SoulMind fields, ConversationRecord
  participants), `SoulStore` (mind fields, `conversationPlayers`, `recordsSince`, `beginHeardTurn`
  overload), new `SoulMemoryDigestOps` + `SoulMemoryDigestOpsTest`, `SoulStoreTest` additions.
- **B — service + runtime**: new `SoulMemoryDigestService` + test, `SoulRuntime.onNewDay`
  wiring and reset hook, `SoulGroupConversationService` passes the roster, `SoulMindOps` decay.
- **C — injection**: `SoulPromptAssembler`, `SoulGroupPromptAssembler`, `SoulBanterSeed`,
  `SoulRuntime.sceneDelivered` recall bump, assembler/seed tests.
- **D — controls**: `ManualConfig` toggle, `BotSoulCommands` (`memory`, `digest`), changelog,
  version bump.
