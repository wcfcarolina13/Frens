# Frens Soul Ambient / Local Chat — Design

**Date:** 2026-08-26
**Status:** Approved design (interview + section approval this session)
**Parent specs:** `2026-08-23-frens-soul-communication-design.md` (the pilot's channel model and its standing invariants: the model never schedules, explicit enablement, stale facts cancel a scene, provider budget respected), `2026-08-25-frens-soul-group-chat-design.md` (the PARTY scene machinery this rides, shipped in 1.1.176), and `2026-08-26-frens-soul-banter-design.md` (the deterministic-director pattern and the ambient-mask gating rule this reuses, shipped in 1.1.177).
**Baseline:** frens 1.1.177; suite 488 green; group-chat and banter field tests both still pending. Local chat ships behind its own default-off toggle, so it can be validated independently of either.

## 1. Summary

Ambient (local) chat is the pilot's fourth conversational surface: a soul-bound companion standing near you **overhears what you say to nobody in particular**, and very occasionally chimes in with one short line — spoken out loud to everyone in earshot, not whispered back.

It splits into two units with very different cost and risk:

- **`SoulLocalMemory`** — an always-on, in-memory recorder of recent unaddressed chat near soul bots. No LLM calls, no output, no disk.
- **`SoulLocalDirector`** — a deterministic, default-OFF reaction director that turns a rare high-salience overheard line into a one-bot `LOCAL` scene through the shipped PARTY pipeline.

The governing constraint is that unaddressed chat is *every line the player types*. So the gate carries the whole design: a pure salience scorer plus banter's veto chain decides, before any provider call, that a line is worth answering. The model only ever writes one line inside a scene the director already accepted.

## 2. Decisions made in the design interview

| # | Decision | Choice |
|---|---|---|
| D1 | Feature shape | **Memory layer + rare reaction on top**, as two separable units. The recorder is useful and free; the speaking half is the risky half and stays opt-in. |
| D2 | Reaction gate | **Deterministic salience score + hard cooldown.** Pure Frens-side scoring before any LLM call — preserves the parent spec's "model never schedules" invariant, and makes "why didn't it react" a single grep, exactly like banter's veto chain. |
| D3 | Reply surface | **One bot, spoken locally to everyone in earshot.** Positional voice + text via the existing scene playback fan-out, gated by the ambient category masks. Not a private reply — an overheard remark in a shared space should be audible to the people standing in it. |
| D4 | Memory home | **Session ring buffer; the reaction commits to the existing PARTY transcript** as an ordinary record. No new store, no new epochs, no new reset command. |
| D5 | Enablement | **Recording unconditional; every consumer gated by `soulLocalChatEnabled` (default false).** With the toggle off the feature is a provable no-op: a buffer nobody reads. |
| D6 | Follow-up | **30 s reply window, one continuation**, landing as another one-bot `LOCAL` scene so the exchange stays in one transcript. |
| D7 | Architecture | **One-bot PARTY scene ("a scene of one").** The group pipeline has no roster-≥2 assumption; only `SoulGroupRouter.tryRoute` does, and a director never calls it. Rejected: a `Channel.LOCAL` DM-pipeline variant (would rebuild earshot fan-out and per-line ambient gating that the scene path already has) and a standalone path (a third copy of plumbing that exists twice). |

## 3. The never-consume invariant

**The ambient path never consumes a chat line.** `SoulChatRouter` and `SoulGroupRouter` are exclusive gates — they return `CONSUMED` and legacy routing never sees the message. The local path is the opposite: it observes and returns, always.

Every existing behavior on unaddressed chat is therefore untouched, reaction or no reaction — `tryHandleNearbyQuestAsk`, `BotRespawnPromptService.handleChat`, `SkillResumeService.handleChat`, `FunctionCallerV2.tryHandleConfirmation`, and the terminal `handleLegacyInlineActionFromRaw`. This holds for the reply-window continuation too: a windowed follow-up submits a scene *in addition to* whatever the legacy parser does with the line, never instead of it.

This is what makes the feature strictly additive, and it is the single most important thing for a reviewer to check.

## 4. `SoulLocalMemory` — the recorder

A static facade in the souls package, in the shape of the existing `SoulPlayerActivity` (server-thread writes, bounded state, `clear()` on stop).

- **Keyed by player UUID.** Each entry: the line text, an epoch timestamp, and the **set of soul-bot UUIDs in earshot when it was spoken**. A bot reads only lines it actually witnessed — "overheard" must mean overheard.
- **Bounded:** last **8** entries per player, entries older than **10 min** treated as absent. In-memory only; never written to disk.
- **Cleared** on player disconnect, on `SoulRuntime` shutdown, and by the existing test-visible `clear()` convention.
- **Written from** the `Frens` public-chat callback, on the unaddressed branch only (`target.bots().isEmpty()`), skipping bot senders and blank lines. Skipped entirely while the souls master is off — nothing above it could read the buffer.
- **Witness computation is deliberately cheap:** same world and within `EARSHOT_BLOCKS` of the speaker, over the registered-bot list (typically a handful), plus the already-cached soul-profile-bound check. **No `CompanionCommunicationPolicy` call at record time** — authorization is a reaction-time concern and is enforced by the director's `roster` gate. This keeps the unconditional recorder to a bounded distance scan per chat line, which is what makes the "provable no-op when off" claim in D5 actually true.

**Consumers (all three gated by `soulLocalChatEnabled`):**

1. The reaction prompt — the last few witnessed lines as immediate context.
2. `SoulBanterSeed` — one optional "overheard" fragment, so banter can pick up a thread you dropped.
3. `SoulPromptAssembler` (DM) — a short bounded "recently overheard" block (≤ ~200 chars, witnessed lines inside the TTL only), appended to the existing state block and omitted entirely when empty.

Consumer 3 is the only touch to the DM pipeline in this spec. Gating it behind the toggle is deliberate (D5): with Local off, DM prompts are byte-identical to 1.1.177, so the 8B-tuned DM behavior can be A/B'd against the change rather than silently replaced.

## 5. `SoulLocalDirector` — the reaction gate

Same construction rules as `SoulBanterDirector`: souls package, no `Frens` references, live config and ambient-surface reads arrive as injected `BooleanSupplier`s, the bot list through a `botsProvider` function, clock and `RandomGenerator` injected for testability.

Unlike banter it is **edge-triggered**: the chat callback notifies it of one unaddressed line, on the server thread. It still holds a `tick()` hook from `SoulRuntime.tickScenes` for cheap expiry of reply windows and cooldown bookkeeping.

### 5.1 Salience scoring (pure, unit-tested)

The **hard rejects** run before anything else, including the veto chain — they are the cheapest possible reject and need no bot context (no score computed): blank, fewer than 3 words, shorter than ~12 chars, byte-identical to the player's previous line, sender is a registered bot.

Then an additive score against the candidate bot:

| Signal | Weight |
|---|---|
| Names a nearby soul bot **not** in leading position (a leading name is an address and was already routed away) | +3 |
| Stated intent or plan (`I'm going to`, `let's`, `we should`, `I need to`) | +2 |
| Keyword overlap with that bot's active task name or most recent journal event | +2 |
| Ends in a question mark (an open question to the room) | +2 |
| Six or more words | +1 |
| Mostly digits/coordinates | −2 |

Fires only at **≥ 4**. The threshold and every weight are constants in one place, tunable from field-test logs without touching logic.

Scoring is deliberately per-bot, so the reacting bot is the highest-scoring eligible bot in earshot, ties broken by proximity — the companion the line was most *about* answers, not merely the closest one.

### 5.2 Veto chain

Reused from banter in the same cheapest-first, first-failure-wins shape (`firstVeto` returning the failed gate's name or `null`):

1. `disabled` — `soulLocalChatEnabled` off.
2. `pipeline` — `runtime.pipelineAvailable()` false.
3. `cooldown` — per-player `nextLocalAtMs` not elapsed. (Bypassed for a windowed continuation.)
4. `busy` — `runtime.isSceneBudgetFree(playerId)` false; local chat never queues behind a DM turn, a scene, or a TTS render.
5. `muted` — neither ambient text nor ambient voice is open; never spend a call whose output nobody can receive.
6. `player-not-at-ease` — dead, sleeping, recently hurt, or has an attacker.
7. `roster` — no eligible bot in earshot (soul-profile-bound ∧ owner/operator-authorized ∧ `LOCAL` reachability ∧ within `EARSHOT_BLOCKS` = **16** — tighter than banter's 24, because this is conversation, not a scene you are watching).
8. `salience` — no eligible bot scored at or above threshold. Ordered *after* `roster` because scoring is per-bot and needs the roster to score against. (Bypassed for a windowed continuation.)
9. `danger` — post-capture veto on the reacting bot's fresh grounding: hostiles nearby, in combat, breaking free, or surface recovery active. Aborts silently and retries in ~2 min without consuming the full cooldown.

Capture follows the established two-phase pattern: the cheap gates run on the triggering server-thread call, grounding is captured on the server thread via `SoulSnapshotBuilder.capture`, and the cheap gates are re-checked after any asynchronous hop before submission.

### 5.3 Cooldowns and interplay with banter

Per-player `nextLocalAtMs` re-randomized into **[6, 12] min** after a fired reaction; a danger/capture veto pushes ~2 min only. A fired local reaction re-arms banter's cooldown through the existing `SoulBanterDirector.notePlayerScene`, and a fired banter scene re-arms local chat's — the two ambient surfaces take turns rather than stacking.

They cannot overlap regardless: both submit under `partyKey(ownerId)`, whose single-flight is enforced by the scheduler, and both require `isSceneBudgetFree`.

The quiet-window overlap noted in the handoff resolves cleanly and needs no new signal. `SoulRuntime.notePlayerChat` already fires on **every** typed line before routing, so any chat — including a line that triggers a local reaction — resets banter's 90 s quiet window. Banter is by construction the *silence* surface and local chat the *speech* surface; one line cannot satisfy both.

## 6. Turn shape, prompt, validation

`SoulGroupTypes.SceneKind` gains **`LOCAL`**, plus `isAmbient()` returning `kind != PLAYER`.

Three of the four existing `== BANTER` branches are really asking "is this ambient?" and become `isAmbient()`:

- `GroupScenePlayback` text gate,
- `GroupScenePlayback` voice gate,
- `SoulGroupConversationService.statusUnlessBanter` (renamed `statusUnlessAmbient`) — local failures are silent to the player for the same reason banter's are.

The two where content genuinely differs stay explicit per-kind branches:

- **Final USER message.** `PLAYER` uses `Owner: message`; `BANTER` uses the bracketed narrator directive. `LOCAL` uses a bracketed context note followed by the real speaker-tagged utterance — e.g. `[<Owner> is talking nearby, not to you. You may chime in with one short line, or say nothing worth saying.]` then `<Owner>: <line>` — so the model knows it is chiming in rather than answering an address.
- **Scene cap.** New `LOCAL_MAX_SCENE_LINES = 1`, threaded through the existing `SoulGroupResponseValidator.parse(raw, rosterDisplayNames, maxSceneLines)` overload. The per-bot cap of 2 and the 300-char line cap are unchanged and moot at a scene cap of 1.

**Persistence:** `LOCAL` HEARD records use the ordinary speaker-tagged form (`Owner: message`), **not** a skip marker. This is the deliberate difference from banter: a banter seed is a synthetic narrator directive that must never replay as a player utterance, whereas an overheard line genuinely *was* said by the player in earshot. Replaying it normally is correct, and it gives the windowed continuation its context for free. There is **no marker in the record text at all** — a `LOCAL` record is byte-identical in shape to a `PLAYER` one. The scene kind lives in telemetry and in the in-flight turn, never in the transcript.

`GroupSceneTurn` is otherwise unchanged: roster of one, `ownerId` = the speaking player, `routingId` minted at the director and adopted end to end.

## 7. Delivery and the reply window

Delivery is `GroupScenePlayback` with no structural change: positional voice from the reacting bot to everyone in earshot, text alongside, both re-read per line against the ambient masks, mid-scene staleness abort, and commit-only-on-delivery. A one-line scene exercises the same path a six-line scene does.

On a delivered reaction the director opens a **30 s reply window** keyed by (player, bot). Inside it, one unaddressed line from that player submits a second one-bot `LOCAL` scene, **bypassing the cooldown and the salience threshold** — the answer to a companion's remark is relevant by definition — while the hard rejects, the `busy`/`muted`/`player-not-at-ease` gates, and the danger veto all still apply.

The window closes on the first of: elapse; one continuation used; the player explicitly addressing any bot — the addressed and unaddressed branches diverge before the local hook, so the chat callback notifies the director on **both**: an addressed line closes open windows and records nothing, an unaddressed line records and evaluates; the player leaving earshot or changing world; a new reaction firing.

A bot that comments on your plans and then goes deaf when you answer is the intrusive failure mode this exists to prevent.

## 8. Config, commands, UI, telemetry

- **Config:** `soulLocalChatEnabled` (default **false**) in `ManualConfig`'s soul block, with accessors.
- **Command:** `/bot soul local on|off|status`. `status` prints enablement, the most recent veto reason, the last computed salience score, time-to-next-eligible, and whether a reply window is open — the primary field-test tool, matching `/bot soul banter status`.
- **UI:** a "Local" global chip beside Soul Chat / Soul Voice / Banter in Companion Settings, autosaving on flip. All three `GLOBAL_TOGGLES` wiring points (constant list, init loads, `saveSettings` writes) move together, per the known pattern.
- **Telemetry:** `[souls] local player=… bot=… outcome=fired|vetoed:<reason> score=N`, throttled to one line per reason change per player. Fired scenes then reuse the existing `[souls] scene` / `scene-playback` lines, joined on the `routingId`.
- **Privacy:** message content is never logged — only the numeric score and the veto reason. The ring buffer holds content in memory only; the sole persisted content is the scene's own PARTY transcript record, which is exactly the material group chat and banter already persist.

## 9. Invariants audit

| Constraint | Treatment |
|---|---|
| Model never schedules (parent spec) | The director is pure deterministic Frens logic — salience scoring plus a veto chain. The model writes one line inside an already-accepted scene. |
| Explicit enablement | `soulLocalChatEnabled` default off; the souls master still gates everything above it. Recording runs unconditionally but has no reader while the toggle is off. |
| Provider budget | `isSceneBudgetFree` required; `partyKey` single-flight shared with group chat and banter; LoadGoverner probe signature untouched (local scenes count like any scene). |
| Stale facts cancel the scene | Post-capture danger veto before submission; per-line staleness abort during playback; store epoch checks on commit. |
| Ambient ≠ soul-DM visibility exemption | `LOCAL` scenes are gated by the ambient text/voice category masks via `isAmbient()`; `PLAYER` scenes keep the exemption, unchanged. |
| Existing chat behavior preserved | The never-consume invariant (§3): the local path observes and returns; no existing unaddressed-chat handler loses a line. |
| DM pipeline discipline | One deliberate touch — the bounded "recently overheard" block in `SoulPromptAssembler` — gated by the toggle, so DM prompts are byte-identical to 1.1.177 while Local is off. `SoulChatRouter`, `SoulConversationService`, and `SoulMessageDelivery` are untouched. |
| Party history never merges into DM memory | Unchanged. Local scenes write only to the PARTY transcript; the DM block reads the in-memory ring, never the party store. |
| One in-flight generation per key | Guaranteed by `partyKey(ownerId)` single-flight, shared with player scenes and banter. |

## 10. Testing

Pure-unit coverage in the established style (baseline 488 green):

- **Salience scorer:** every hard reject; each weighted signal in isolation; the leading-name case scoring 0 (it is an address, not an overheard mention); threshold boundary at 3 vs 4; per-bot scoring picking the referenced bot over the merely nearer one.
- **Veto chain:** truth table over `firstVeto`, asserting cheapest-first ordering by the reason returned; continuation bypass covering cooldown and salience but *not* `busy`, `muted`, `player-not-at-ease`, or the hard rejects.
- **`SoulLocalMemory`:** ring bound at 8, TTL expiry, witness filtering (a non-witness bot reads nothing), clear-on-disconnect, bot senders skipped.
- **Cooldown math:** post-fire band, danger-veto partial retry, mutual re-arm with banter in both directions.
- **Reply window:** opens on delivery only (never on a vetoed or failed attempt); each of the five closing conditions; exactly one continuation per window.
- **Kind handling:** `isAmbient()` true for `BANTER` and `LOCAL`, false for `PLAYER`; `LOCAL` final message shape (bracketed context note + speaker-tagged utterance, no skip marker); `LOCAL` HEARD record replays as a player utterance; scene cap of 1 threaded to the validator; `LOCAL` failures silent.
- **Never-consume:** the chat-hook seam returns without consuming in every branch — fired, vetoed, disabled, and thrown-exception.
- **Manual field-test checklist** (the plan will enumerate): toggle off = zero director activity and byte-identical DM prompts; `status` verdicts and scores while typing deliberately boring and deliberately salient lines; a fired reaction end to end with voice, heard by a bystander; ambient text muted → voice-only; ambient fully muted → no generation at all (grep the `muted` veto); the reply window answered, and the window expiring unanswered; explicit address mid-window closing it; combat veto; cooldown spacing over a session; banter and local taking turns rather than stacking; `/bot soul reset party` archiving local-scene records.

## 11. Out of scope

- **Multi-turn local conversation.** One reaction plus one windowed continuation, then back to normal. Chained exchanges are a deliberate follow-up.
- **Multi-bot reactions.** One bot answers. Two bots reacting to an overheard line is banter's job, seeded through the ring (§4 consumer 2).
- **Whispered and command chat.** `/msg` bypasses the public-chat callback (the same slack banter accepts); commands never reach the hook.
- **Persistent local memory.** The ring is session-scoped by design; durable cross-surface memory is the consolidation roadmap item.
- **Per-bot salience tuning or personality-driven thresholds** beyond the existing profiles.
- **Multiplayer per-player category masks** — existing known limitation; global masks apply.
- **Any change to the legacy scripted `BotAmbientSocialChatService`** — it stays player-directed one-liners.
