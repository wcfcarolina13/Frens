# Frens Soul Group Chat — Design

**Date:** 2026-08-25
**Status:** Approved design (interview + section-by-section approval this session)
**Parent spec:** `2026-08-23-frens-soul-communication-design.md` — its "Group chat" extension boundary (§Future extension boundaries) is the contract this design fulfills. Three rulings from that spec are inherited unchanged: group conversation has its **own channel history** never merged into private DM histories; a **fresh authoritative roster** is captured per turn; generation is **one capped orchestration call** returning a structured speaker sequence (one-call-per-bot is rejected).
**Baseline:** frens 1.1.175 deployed; DM soul pilot (Jake) field-tested; suite 417/417.

## 1. Summary

Group chat adds the PARTY channel: a player addresses several of their own companions at once ("bots, what should we do tonight" or "Jake and Sara, thoughts?") and the eligible nearby soul bots respond as one orchestrated, paced scene — distinct voices, distinct lines, one shared transcript. Each scene is a single self-contained turn; there is no persistent session object. The existing DIRECT DM pipeline is not modified: group chat is an additive parallel path that shares the scheduler slot, profile registry, reachability policy, and voice plumbing.

## 2. Decisions made in the design interview

| # | Decision | Choice |
|---|---|---|
| D1 | Lifecycle | **Broadcast-addressed one-turn scene.** No persistent session, no roster churn rules, no end conditions. Continuity comes from the shared PARTY transcript. |
| D2 | Roster & authorization | **Speaker's own bots, LOCAL only.** Soul-enabled bots owned by (or operator-authorized to) the speaking player, same world, ≤32 blocks. Other players' bots never join. Same trust boundary as DM. |
| D3 | Audience | **All players in earshot.** Text lines and positional voice go to every player within 32 blocks of the speaking bot at delivery time. |
| D4 | Generation | **One capped orchestration call** (inherited parent-spec ruling). |
| D5 | Presentation | **Paced playback, text synced to voice.** Lines deliver one at a time in speaker order; each text line appears when that bot's audio dispatches, or after a length-scaled beat when voice is off. |
| D6 | Text-master visibility | Player-initiated scene replies follow the DM ruling (soul replies exempt from the Text Chat master — Bradley 2026-08-25). System-initiated banter (future) gets its own gating. |
| D7 | Architecture | **Additive parallel path.** New group types/service/store beside the DM pipeline; DIRECT types, store paths, and delivery untouched. |

## 3. Addressing & routing

### 3.1 ChatAddressing multi-name resolution

`ChatAddressing.resolve` (currently first-match-wins, single winner — `ChatAddressing.java:75–82`) grows a leading multi-name scan:

- The leading-address segment may be a run of bot names joined by `and` / commas: `"Jake and Sara, what do you think"` → addressed = {Jake, Sara}, prompt = `"what do you think"`.
- Connector tokens (`and`, `,`, `&`) between leading names are consumed; the scan stops at the first token that is neither a known bot name nor a connector.
- Broadcast keywords (`bots`, `allbots`, `all bots`) are unchanged and still checked first.
- `Resolution` carries a **list** of matched name indices (today's `matchedNameIndex` becomes derived/compatible). Single-name resolution behavior is byte-identical to today, including the mid-sentence-match rule.
- Stays pure and unit-testable.

### 3.2 Route selection

At the existing soul seam (`Frens.java:1297`): if souls are conversation-enabled and the resolution is **broadcast** or names **≥2 bots**, `SoulGroupRouter.tryRoute` runs (new class, sibling of `SoulChatRouter`). Gates, in order:

1. Souls master + pipeline available (same checks as DM routing).
2. `souls.party.enabled` toggle (see §7).
3. Roster construction: candidate set = addressed bots (or all registered bots for broadcast) filtered to:
   - has a soul profile (registered in `SoulProfileRegistry`),
   - speaker is owner or operator (`CompanionCommunicationPolicy.isPrivateSoulAuthorized` projection — the DM gate, reused per bot),
   - LOCAL reachability: same world, ≤32 blocks (`classifySoulReachability` == LOCAL; REMOTE does **not** qualify for scenes).
4. Outcome by roster size:
   - **≥2** → mint `routingId`, capture group grounding, submit a `GroupSceneTurn`.
   - **=1** → downgrade: route as an ordinary DIRECT DM turn to that bot (DM has the relationship history; a scene of one is just a DM).
   - **=0** → deterministic status to the speaker ("None of your companions are close enough to chat."), message consumed.
5. If souls are disabled/unavailable or the party toggle is off, the message falls through to the legacy `LLMOrchestrator.handleChat` broadcast loop exactly as today.

**Consumption rule:** when a soul scene (or the 1-bot downgrade) accepts the message, the legacy per-bot loop does **not** also run. Non-soul bots do not participate in soul scenes and do not double-reply. This is a deliberate behavior change for mixed soul/non-soul rosters.

## 4. Types, scheduling, orchestration

### 4.1 New types (additive) — as implemented

- `SoulGroupTypes` namespace class: scene caps (`MAX_SCENE_BOTS=4`, `MAX_LINES_PER_BOT=2`, `MAX_SCENE_LINES=6`, `MAX_LINE_CHARS=300`), `GroupSceneTurn(ownerId, ownerDisplayName, roster, playerMessage, acceptedAt, routingId)` with `roster` = ordered list of `SceneParticipant(botId, profileId, displayName, grounding)`, and `SceneLine(participantIndex, text)`.
- **Refinement (no `PartyKey`, no `GroupGroundingSnapshot`, no `GroupSnapshotBuilder`):** the party key is `SoulGroupTypes.partyKey(ownerId)` = `ConversationKey(botId=ownerId, playerId=ownerId, PARTY)` — safe because the party store is a separately-rooted `SoulStore` instance, so the botId path segment resolves to the owner's own directory there. Per-bot grounding is captured by calling the existing `SoulSnapshotBuilder.capture(server, bot, sender, LOCAL)` once per roster member at accept time (fresh roster per turn, server thread); a capture that throws drops that bot, and fewer than two survivors downgrade to DM or a deterministic notice.

`ConversationKey`, `AcceptedTurn`, `GroundingSnapshot`, and `SoulGenerationScheduler` are not modified.

### 4.2 Scheduler — unchanged (refinement)

Because the party key IS a `ConversationKey`, the scheduler needs **zero changes**: `maxConcurrent = 1`, per-key single-flight, FIFO queue, `queueCapacity` → `OVERLOADED`, epoch invalidation, and `cancelForPlayer(playerId)` (the party key's playerId is the owner, so owner disconnect cancels pending scenes) all apply as-is. A scene is **one job in the same global slot** as DM turns.

**Frozen contract:** `SoulRuntime.activeGenerations()` (reflected by LoadGoverner's `FrensSoulProbe`) keeps its exact static signature; its sum gains the active-scene count (`scheduler.inFlightCount() + voice.activeSyntheses() + scenePlayback.activeSceneCount()`) so the governor floor holds through the scene's whole playback window, including the gaps between per-line renders.

### 4.3 Orchestration call

New `SoulGroupPromptAssembler`, same assembly discipline as `SoulPromptAssembler` (fixed order, hard char budgets, no interpolation in the system contract):

1. Scene system contract: "you are narrating one exchange among these companions; output only lines in the form `Name: text`; speakers must come from the roster; stay brief."
2. Bounded identity block per roster profile (from `SoulProfileRegistry`; per-profile budget smaller than the DM identity budget — plan sets exact numbers).
3. Bounded PARTY history from the party transcript (own budget, smaller than DM's 20-turn/12k).
4. Authoritative roster state from `GroupGroundingSnapshot` + SITUATION.
5. The player's message, exactly once, last.

Model = the configured soul model; one request; `MAX_OUTPUT_TOKENS` sized for ≤6 short lines.

### 4.4 Validation

New `SoulGroupResponseValidator` (pure; sibling of `SoulResponseValidator`) parses **plain speaker-tagged lines** — chosen over JSON because 3B/8B local models emit tagged lines far more reliably:

- A valid line is `<RosterDisplayName>: <text>` (name match case-insensitive, normalized like `ChatAddressing`).
- Lines with unknown or non-roster speakers are dropped, not repaired.
- Caps: ≤2 lines per bot, ≤6 lines per scene, ≤300 chars per line (truncate at sentence boundary where possible).
- Zero valid lines after filtering → `MALFORMED`, deterministic status to the trigger player, nothing committed.

## 5. Delivery & scene playback

New `GroupSceneDelivery` + a tick-driven playback state machine (registered on the server tick loop; no sleeps, no blocking — Threading Rules apply):

- The validated scene is an ordered list of `(botId, line)`. Playback advances one line at a time.
- **Per line, on the server thread:**
  1. Liveness re-check: bot still present/alive, scene epoch still current, party toggle + souls master still on, speaker within LOCAL range of the owner (grace: the same 32-block rule, evaluated at line time). Stale → skip the line (and if the whole scene is stale — owner gone, epoch bumped — abort remaining lines).
  2. Text fan-out: `Text.literal(displayName + ": " + line)` to **every player within 32 blocks of the speaking bot** at that moment.
  3. Voice: synthesize via `SoulVoiceService.synthesizeLine(profileId, text)` on the shared voice worker; each line gets its **own derived groupId** (`GroupScenePlayback.lineGroupId(routingId, i)`) so each speaker plays on its own client audio source positioned at that bot — cross-line ordering is enforced server-side by the pacer, not the client queue (refinement: the originally spec'd shared per-scene groupId would have pinned all speakers to one source position). Voice fan-out sends the positional payload to each in-earshot player (loop over the existing per-player payload path).
- **Pacing:** text for line N appears when line N's audio dispatches. When voice is off/unavailable (muted master, engine down, synthesis failure), the machine falls back to a beat delay of ~1.5–2.5 s scaled by line length. Line N+1 starts after line N's dispatch + estimated audio duration (from PCM length) or its beat. Voice synthesis remains sequential on the existing 1-thread voice executor.
- **Commit rule (parent-spec ruling):** each line appends to the party transcript **only after successful delivery**. Skipped/stale/undelivered lines never enter the transcript. The player's HEARD record commits at acceptance, as in DM.
- Voice failure never blocks text or the scene; text failure for one line fails that line only.

## 6. Storage

**Implementation refinement:** no `SoulPartyStore` class — the party channel is a second `SoulStore` **instance** opened via the new `SoulStore.openAt(exactRoot)` factory at `<world>/frens/party/v1`, keyed by `partyKey(ownerId)`. This reuses the store's epoch, crash-reconciliation, corrupt-tail-quarantine, and bounded-history machinery wholesale (DM store code untouched apart from the additive factory):

- Path: `<world>/frens/party/v1/<ownerUuid>/conversations/<ownerUuid>/active.jsonl` + `archive/epoch-N-<ts>.jsonl`; the cursor lives in `<world>/frens/party/v1/<ownerUuid>/soul.json` under key `PARTY:<ownerUuid>`.
- Records are uniformly speaker-tagged: HEARD content is stored as `"Owner: message"`, SPOKEN as `"Bot: line"`, so party history replays into group prompts verbatim.
- Records: `HEARD` (player message, roster snapshot ids), `SPOKEN` per delivered line (speaker botId + text), `FAILURE` (category), all joined by `routingId`.
- PARTY history feeds **only** group prompts. It is never merged into any bot's DM history, and DM history is never fed into scenes (bots' knowledge of the player still comes from per-bot knowledge/events, which remain per-bot and are shared infrastructure).
- Reset: `/bot soul reset party` archives the active epoch and invalidates queued/in-flight scene jobs via the same scheduler `invalidate` path.

## 7. Config, failure, telemetry

- **Config:** rides the souls master (`souls.enabled`, model, timeout, queue capacity). One new toggle `souls.party.enabled` (default **on** when souls are on) as a kill switch. Scene caps are code constants, not config.
- **Failure codes:** reused (`OVERLOADED` → the existing "tied up" status; `TIMEOUT`, `MALFORMED`, `CANCELLED`, `STALE_EPOCH` behave as in DM). No new codes anticipated; if playback needs one, plan adds it.
- **Telemetry:** `[souls] scene` log lines joining on `routingId` for routing → roster → queue → provider → validation → per-line delivery → tts, with durations and counts; no prompt/content in normal logs (same redaction rules as DM).

## 8. Invariants audit

The DM pipeline's invariants and how this design treats each:

| Invariant (current code) | Treatment |
|---|---|
| `ConversationKey` = 1 bot + 1 player | Untouched; scenes use `partyKey(ownerId)` (owner in both slots, PARTY channel) + `GroupSceneTurn`. |
| Transcript path `<botId>/conversations/<playerId>/` | Untouched; party transcripts live under `<world>/party/<ownerUuid>/`. |
| One in-flight generation per key; global slot = 1 | Preserved; the party key is one key, scene = one job. |
| One turn → one reply → one recipient | DM path unchanged; group delivery is a new class with fan-out. |
| `SoulResponseValidator` single-speaker parsing | Untouched; scenes use `SoulGroupResponseValidator`. |
| Voice targets `key.playerId()` | DM path unchanged; scene voice loops the same payload path per in-earshot player. |
| `isPrivateSoulAuthorized` owner/operator-only | Reused per roster bot — group inherits the DM trust boundary (D2). |
| `GroundingSnapshot` single-player, no roster | Untouched; scenes use `GroupGroundingSnapshot`. |
| Broadcast hard-excluded from souls (`Frens.java:1291`) | This seam is exactly where group routing plugs in; legacy loop remains the souls-off fallback. |
| `ChatAddressing` single addressee | Extended (multi-name), single-name behavior byte-identical. |
| Epoch/profile recheck per key | Scenes get their own epoch via `SoulPartyStore`; per-line liveness re-check at playback. |
| `activeGenerations()` frozen LoadGoverner contract | No signature or semantics change. |
| Soul replies bypass Text master | Inherited for player-initiated scenes (D6); banter will use the `textEnabled` seam later. |
| Events per-bot, history per-pair | Unchanged; party transcript is a third, parallel shape as the parent spec requires. |

## 9. Testing

Pure-unit coverage in the existing souls test style (no Minecraft server in tests); suite must stay green (417/417 at baseline):

- `ChatAddressing`: multi-name leading scans (connectors, ordering, mid-sentence names, broadcast precedence, single-name regression cases byte-identical).
- Roster filter projection: ownership/operator/no-profile/REMOTE/other-world/other-owner exclusions; 1-bot downgrade; 0-bot status.
- `SoulGroupResponseValidator`: tag parsing, unknown-speaker drops, per-bot/per-scene/per-line caps, zero-valid → MALFORMED.
- Playback staleness combinator (pure): epoch bump mid-scene, bot removed mid-scene, owner left range, toggle flipped.
- Party-key semantics: `partyKey` shape and cursor key, `SoulStore.openAt` root isolation, party epochs/reset.
- Manual in-game checklist (plan will enumerate): 2-bot and 3-bot scenes, mixed soul/non-soul broadcast, bystander earshot, voice-off pacing, mid-scene walk-away, `/bot soul reset party`, LoadGoverner floor through a scene.

## 10. Out of scope

- Autonomous banter (system-initiated scenes) — next roadmap item; reuses this PARTY path but needs its own design (eligibility, quiet periods, cooldowns, its own text-gating).
- Ambient/local channel (unaddressed chat), consolidation, action requests — later roadmap items.
- Other players' bots in scenes (shared-bot opt-in) — possible later extension flagged in the interview.
- REMOTE participation in scenes (Ender Eye / Goat Horn / Tome) — LOCAL only for v1.
- Persistent group sessions with roster churn.
- New config surface beyond `souls.party.enabled`.
