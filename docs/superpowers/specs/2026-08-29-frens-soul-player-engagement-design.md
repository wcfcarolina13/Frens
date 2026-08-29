# Frens Soul Player Engagement — Design

**Date:** 2026-08-29
**Status:** Approved interview (3 decisions this session); spec pending Bradley's review
**Parent specs:** `2026-08-26-frens-soul-banter-design.md` (the director and PARTY machinery this extends), `2026-08-26-frens-soul-local-chat-design.md` (the reply window and continuation this reuses).
**Baseline:** frens 1.1.180 deployed; suite 539 green. Motivating field session 2026-08-28: with one bot, no bot-initiated conversation can exist at all — banter needs a roster of 2 and local chat needs the player to speak substantively first, so a quiet solo player gets total silence.

## 1. Summary

One new capability with two entry points: **a companion may start a conversation with the player.**

- **Solo remarks** (roster of one): a lone soul-bound companion, when things are calm, occasionally says one short thing TO the player — a remark, observation, or question seeded from real recent events, exactly as banter scenes are seeded.
- **Player-addressed banter** (roster of two+): an ordinary banter scene may be marked, deterministically at fire time, as allowed to end with a line addressed to the player.

Both open the existing local-chat **reply window** on delivery, so the player's next unaddressed line answers the bot through the shipped one-continuation machinery. The model still never schedules anything: the banter director decides *when* and *whether the player may be addressed*; the model only writes lines inside an accepted scene.

## 2. Decisions from the interview

| # | Decision | Choice |
|---|---|---|
| E1 | Solo gap | **Solo remarks: the bot speaks TO the player** (not muse-aloud, not leave-quiet). |
| E2 | Multi-bot depth | **Player pulled into banter** — scenes may address the player. (Wider seeds, second personality, longer scenes: explicitly not chosen; second personality noted as the highest-leverage follow-up.) |
| E3 | Enablement | **Rides the existing toggles.** Solo remarks fire under `soulBanterEnabled`; player-addressed lines occur only in scenes banter already allows. No new chip, no new command, frequency inherits banter's bands. Chip/help text updated to say banter also works with a single companion. |

## 3. Director changes (`SoulBanterDirector`)

- **Roster gate relaxes from ≥2 to ≥1.** The `roster` veto reason now means "no eligible bot"; `bots-apart` is vacuously satisfied for a roster of one (a lone bot is trivially together). All other gates — cooldown bands, budget, ambient surfaces open, player at ease, 90 s quiet window, post-capture danger veto — apply unchanged to both modes.
- **Fire-time engagement decision (deterministic):** when the roster is 1, the scene is always player-directed (that is its entire point). When the roster is ≥2, the director marks the scene `addressPlayer` with probability **1/3** from its injected `RandomGenerator` — most banter stays bot-to-bot ambience.
- Verdict logging unchanged; a fired solo scene logs `roster=1` on the existing line.

## 4. Turn shape and prompt

- `SoulGroupTypes.GroupSceneTurn` gains `boolean addressPlayer` with a compatibility constructor defaulting to `false` (same pattern as `SceneKind` in 1.1.177) — `PLAYER` and `LOCAL` turns never set it.
- `SoulGroupPromptAssembler`, BANTER branch, three variants of the narrator directive:
  - roster ≥2, `addressPlayer=false` — unchanged 1.1.177 directive.
  - roster ≥2, `addressPlayer=true` — appends: `One of you may end by saying one short thing to <Owner> — a question or a remark addressed to them.`
  - roster ==1 — replaces the "chat among themselves" framing: `[A quiet moment. <Bot> may say one short thing to <Owner> — a remark, an observation, or a question about recent happenings: <seed>. One or two short lines only.]`
- **Caps:** no new constants. A solo scene is naturally bounded by `MAX_LINES_PER_BOT = 2`; the `BANTER_MAX_SCENE_LINES = 4` cap is unchanged for rosters of 2+.
- Validation, delivery gating (`isAmbient()` → ambient masks per line), combat abort, silent failures, PARTY-transcript persistence, `[banter] `-prefixed HEARD seed: all unchanged.

## 5. The reply window handoff

- `GroupScenePlayback.LineCommitter.sceneDelivered` (the seam built in 1.1.178) forwards **BANTER scenes too**, and gains a third argument: the **participant index of the last delivered line's speaker** (−1 when nothing delivered). The playback's `SceneState` tracks it in `deliver()`. Changing the default method's signature is safe: it has exactly one caller (`finish()`) and one meaningful override (`SoulRuntime`'s committer); `SoulGroupConversationService`'s incidental inheritance of the no-op default is unaffected. `SoulRuntime`'s committer opens the reply window when: kind is `LOCAL` (existing behavior, unchanged), **or** kind is `BANTER` ∧ `turn.addressPlayer()` (solo scenes always set it) ∧ `deliveredLines > 0`. The window's bot is that last speaker — for a solo scene the bot itself; for a group scene, whoever spoke last is whom the player would answer.
- **No textual detection.** A `addressPlayer` scene whose lines happened not to address the player still opens a window; an unused window expires in 30 s, harmlessly. Determinism beats parsing.
- **Ordering note (verified against shipped code):** `submitGroupTurn` calls `SoulLocalDirector.notePlayerScene` for BANTER kind, which clears any open window at *submit* time; the new window opens at *delivery* time. Clear-then-open is the correct sequence and needs no change.
- **Documented limitation:** the reply window and its continuation live in `SoulLocalDirector`, whose entry point returns immediately while `soulLocalChatEnabled` is off. With banter ON and local chat OFF, a solo remark is one-way — the bot speaks, but answering it does nothing special. The banter status/toggle warning added in 1.1.180 is extended to mention this combination.

## 6. Out of scope

- A second soul profile (highest-leverage richness follow-up; zero machinery).
- Wider topic seeds, longer scenes, multi-beat chains.
- Any change to seed building, the DM pipeline, storage, or LoadGoverner (the probe signature is untouched; solo scenes count like any scene).

## 7. Testing

- Director: roster gate at 1 (veto only at 0); `bots-apart` vacuous for one bot; `addressPlayer` always true for roster 1, ~1/3 over many samples for roster 2 with a seeded random.
- Types: compat constructor defaults `addressPlayer=false`; `PLAYER`/`LOCAL` turns never set it.
- Assembler: three directive variants; solo directive names bot and owner and carries the seed; group `addressPlayer` directive appends rather than replaces.
- Window handoff (pure seams): BANTER+`addressPlayer`+delivered>0 opens; BANTER without flag does not; zero-delivery does not; last-speaker selection.
- Field test: solo bot + calm → a remark addressed to you within the banter bands; answering it inside 30 s gets exactly one continuation; two-bot banter occasionally ends addressed to you; ambient mutes still silence everything.
