# Frens Soul Banter — Design

**Date:** 2026-08-26
**Status:** Approved design (interview + section-by-section approval this session)
**Parent specs:** `2026-08-23-frens-soul-communication-design.md` (§Autonomous banter: scheduled by deterministic Frens logic, never the model; eligibility = explicit enablement + nearby qualified participants + suitable player presence + quiet period + per-bot cooldowns + provider-budget availability + no danger/urgent transitions; scene cancelled if facts go stale before delivery) and `2026-08-25-frens-soul-group-chat-design.md` (the PARTY scene machinery this rides, shipped in 1.1.176).
> **REVISED 2026-08-29 (1.1.182):** D6's delivery gating no longer uses the ambient text/voice
> CATEGORY masks — field sessions showed that coupling meant muting scripted one-liners also
> silenced every soul scene. BANTER (and LOCAL) delivery now gates on the Text master, the
> Voice master + Soul Voice, and the feature chips; the Adv category mutes govern prebaked
> lines only. The PLAYER-scene exemption and everything else in D6 stands.

**Baseline:** frens 1.1.176 deployed; suite 463/463; group chat field-test pending (banter ships behind a default-off toggle, so the two can be field-tested together).

## 1. Summary

Banter is autonomous companion small talk: when a player is nearby and at ease, their soul-bound bots occasionally strike up a short scene among themselves — reacting to what actually happened ("that skeleton nearly had you") — generated through the same one-call PARTY pipeline as group chat and played back with the same paced multi-voice machinery. A deterministic director decides *when*; the model only ever writes *lines*. Unlike player-initiated soul replies, banter is ambient-like and respects the ambient text/voice category masks — the explicit carve-out from the soul-DM Text-master exemption.

## 2. Decisions made in the design interview

| # | Decision | Choice |
|---|---|---|
| D1 | Enablement | **Opt-in, default OFF.** `soulBanterEnabled` config toggle; Banter chip in Companion Settings (autosave) + `/bot soul banter on / off / status`. Only meaningful while the souls master is on. |
| D2 | Frequency | **Occasional:** a scene per ~8–15 minutes of eligible play, randomized within the band; per-player cooldown; player-initiated scenes also push it back. |
| D3 | Topics | **Recent events + situation.** Seed built deterministically from the roster bots' witnessed event journals plus the live situation; party-history callbacks come free via normal history replay, never as an explicit seed. |
| D4 | Audience | **Nearby & at ease.** A player within ~24 blocks of the bots, alive, not sleeping, not in combat, no hostiles near either party, and ~90 s since the last player chat or soul turn. |
| D5 | Architecture | **Thin director over the shipped PARTY path.** No separate BANTER-channel pipeline (loses continuity, duplicates plumbing); no LLM bolted onto the legacy `BotAmbientSocialChatService` (violates the parent spec). The scripted ambient service is untouched — it stays player-directed one-liners. |
| D6 | Visibility | **Banter does NOT inherit the soul-DM Text-master exemption.** BANTER scenes are gated by the ambient text and voice category masks; PLAYER scenes keep the exemption (Bradley's 2026-08-25 ruling). |

## 3. Director & eligibility

New `SoulBanterDirector` (souls package; no `Frens` references — live config reads injected as suppliers, same lazy-lambda pattern as the voice master). Ticked from the existing `SoulRuntime.tickScenes` END_SERVER_TICK facade; evaluates at most once per 100 ticks (5 s).

Per online player, ALL must hold — checked cheapest-first so the common path is a few field reads:

1. **Enablement:** banter toggle on (injected supplier) ∧ `pipelineAvailable()`.
2. **Cooldown:** per-player `nextBanterAtMs`, re-randomized into **[8, 15] min** after every fired banter scene; initialized to now + **[4, 8] min** on world start / toggle-on. Any player-initiated scene for that player also re-arms the full cooldown.
3. **Budget:** `SoulRuntime.activeGenerations() == 0` and no active scene for that player — banter never queues behind DM turns, scenes, or TTS renders.
4. **Delivery surface open:** at least one of the ambient text / ambient voice gates (see §5) currently allows output. Fully muted ambient = no generation at all — never spend a call whose output nobody can receive.
5. **Audience at ease (cheap live checks):** player alive, not sleeping, not in combat (recent-hurt/attacker), and no chat activity within the last **90 s**. Recency comes from one signal: the existing `SoulPlayerActivity` static facade gains a chat timestamp fed from the Frens public-chat callback — which sees every typed line including soul-addressed ones, so ordinary chat, DM turns, and group-scene triggers all reset the quiet window through the same note. (Whispered `/msg` DMs bypass that callback; acceptable slack for v1.)
6. **Roster:** the group-chat eligibility filter reused verbatim (`SoulGroupRouter.eligibleRoster`: soul-profile-bound ∧ owner/operator-authorized ∧ LOCAL), requiring **≥2 bots within ~24 blocks of the player and within ~12 blocks of each other** (they are talking to each other, not shouting across a field).
7. **Post-capture danger veto:** per-bot grounding is captured exactly as `SoulGroupRouter.tryRoute` does (server thread, `SoulSnapshotBuilder.capture`, fresh roster per turn). If any snapshot reports hostiles nearby, in-combat, or breaking-free/surface-recovery states, the attempt aborts silently and retries in ~2 min (cooldown not fully consumed).

Every veto logs throttled (`[souls] banter outcome=vetoed:<reason>`, one line per reason change per player) so "why is there no banter?" is a single grep during field tests. A fired scene logs the same shape with `outcome=fired`.

## 4. Turn shape, seed, and prompt

**`SceneKind` on the turn:** `SoulGroupTypes.GroupSceneTurn` gains `SceneKind kind` (`PLAYER`, `BANTER`) with a compatibility constructor defaulting to `PLAYER` so 1.1.176 call sites are source-stable. For banter: `ownerId`/`ownerDisplayName` = the audience player (their PARTY transcript, their cooldowns, their reset), `playerMessage` carries the **seed text**, roster/grounding/routingId exactly as scenes already work.

**Seed builder (`SoulBanterSeed`, pure):** up to 3 salient recent events drawn from the roster bots' event journals (`SoulStore.recentEvents` per bot) — HIGH salience first, newest within a tier; kills, task completions/failures, deaths/respawns, dimension changes, sleep — plus a one-line situation summary from the captured groundings (time phase, weather, biome, and the audience player's apparent activity). Deterministic selection logic with mild randomness in event choice; bounded total length (~400 chars).

**Prompt (banter mode of `SoulGroupPromptAssembler`):** identical scene contract / CAST / CURRENT STATE / party-history blocks; the final USER message is a bracketed **narrator directive**, never attributed to the player:
`[A quiet moment. The companions chat briefly among themselves. Recent happenings: <seed>. A few short lines only.]`
Banter scenes are validated with a **4-line scene cap** (tighter than the player-scene 6; per-bot cap of 2 and 300-char line cap unchanged).

**Persistence marker:** the banter turn's HEARD record persists as `[banter] <seed>`. History replay in the group assembler **skips `[banter]`-prefixed HEARD records** (a stale seed must never replay as a player utterance), while banter SPOKEN lines replay normally — so group chat and banter remember each other for free.

## 5. Delivery gating & playback

`GroupScenePlayback` learns scene kinds via two injected suppliers, wired in `SoulRuntime.start` with the established lazy-lambda pattern:

- **BANTER text:** requires `TextLineVisibilityService.isTextAllowed(AMBIENT_CHATTER)` (Text master ON and ambient not muted).
- **BANTER voice:** requires the ambient voice category to be unmuted (on top of the Voice master already enforced inside `SoulVoiceService.synthesizeLine`).
- **PLAYER scenes: untouched** — they keep the soul-DM exemption.

Gates are **re-read per line**, so muting ambient mid-scene silences the remainder immediately. If text is gated but voice is open (or vice versa), the open surface still delivers — same independent-surfaces rule as everywhere else. A line whose both surfaces are closed is skipped without committing.

**Stale-facts abort (parent-spec rule):** the per-line staleness combinator gains a kind-aware check — a BANTER scene whose current speaker is in combat (or whose audience player has entered combat) aborts its remaining lines. Only delivered lines commit to the transcript, exactly as shipped.

## 6. Storage

No new storage. Banter turns live in the audience player's existing PARTY transcript (`<world>/frens/party/v1/<playerUuid>/…`), sharing its epochs; `/bot soul reset party` archives banter history along with group-chat history, and the store's epoch checks give banter the same mid-reset cancellation semantics scenes already have.

## 7. Config, commands, UI, telemetry

- **Config:** `soulBanterEnabled` (default **false**) in `ManualConfig`'s soul block + accessors.
- **Command:** `/bot soul banter on|off|status`. `status` prints enablement plus the actor's current eligibility verdict (the most recent veto reason and time-to-next-eligible) — the primary field-test debugging tool.
- **UI:** a "Banter" global chip beside Soul Chat / Soul Voice in Companion Settings (`BotControlScreen`), autosaving on flip per the standing ruling. All three GLOBAL_TOGGLES wiring points (constant list, init loads, saveSettings writes) move together, per the known pattern.
- **Telemetry:** `[souls] banter` director lines (fired/vetoed, per-reason throttled); fired scenes then reuse the existing `[souls] scene` / `scene-playback` lines joined on the routingId.

## 8. Invariants audit

| Constraint | Treatment |
|---|---|
| Model never schedules banter (parent spec) | The director is pure deterministic Frens logic; the model only writes lines inside one already-accepted scene. |
| Explicit enablement | `soulBanterEnabled` default off; souls master still gates everything above it. |
| Provider budget | `activeGenerations() == 0` required; one scene at a time per player; LoadGoverner probe signature untouched (banter scenes count like any scene). |
| Stale facts cancel the scene | Post-capture veto before submission; per-line kind-aware combat abort during playback; store epoch checks on commit. |
| Banter ≠ soul-DM visibility exemption | BANTER scenes gated by ambient text/voice category masks; PLAYER scenes exempt, unchanged. |
| DM pipeline untouched | All changes are in the group/scene classes shipped yesterday plus new director/seed files; `SoulChatRouter`/`SoulConversationService`/`SoulMessageDelivery` untouched. |
| One in-flight generation per key | Banter submits under the same `partyKey(ownerId)` — single-flight with player scenes by construction. |
| Party history never merges into DM memory | Unchanged; banter writes only to the PARTY transcript. |

## 9. Testing

Pure-unit coverage in the established style (baseline 463 green):

- Eligibility combinator truth table (each veto reason, cheapest-first ordering observable via reason returned).
- Cooldown math: initial grace band, post-fire re-randomization bounds, player-scene re-arm, danger-veto partial retry (~2 min).
- `SoulBanterSeed`: salience-first pick, tier ordering, bounded length, situation line presence, determinism given a fixed random source.
- Assembler banter mode: directive final message (USER role, bracketed, no player attribution), `[banter]` HEARD skipped in replay, SPOKEN replay unchanged, 4-line cap threaded to the validator.
- Playback kind gating: BANTER lines respect injected text/voice suppliers re-read per line; PLAYER lines ignore them; both-surfaces-closed skips without commit; mid-scene combat abort for BANTER only.
- Manual field-test checklist (plan will enumerate): toggle off = zero director activity; status command verdicts; a fired scene end-to-end with voice; ambient text muted → voice-only banter; ambient fully muted → no generation (grep the veto); combat interrupt mid-scene; cooldown spacing over a session; `/bot soul reset party` mid-banter.

## 10. Out of scope

- Ambient/local channel (unaddressed chat overheard by bots), consolidation, action requests — later roadmap items.
- Bot-initiated banter *toward the player* (addressing them directly) — banter is bot-to-bot; the player is audience.
- Per-bot banter personalities/frequency tuning beyond the existing profiles.
- Multiplayer per-player category masks (existing known limitation; global masks apply).
- Any change to the legacy scripted `BotAmbientSocialChatService`.
