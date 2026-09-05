---
task: "Backlog lineup 2026-09-03. DONE: 1.1.200, 1.1.201 (memory digest), 1.1.202 (torch-hold diagnostics, CreeperEvasionPolicy, creeper isFusing root-cause fix). NEXT: guided field session on 1.1.202 — ONE merged checklist: docs/testing/FIELD_SESSION_1.1.202.md (protocol: GUIDED_SESSION_PROTOCOL.md); then doorway rework discussion; then ACTION REQUESTS interview."
test_command: "./gradlew build -x test"
---

## Session Handoff 2026-09-05 (late night) — next session starts here

**State:** main = origin/main @ 1.1.210 (pushed, deployed to all three Prism instances). Suite 812 green.

**Shipped in 1.1.210 (FortifyVillageSkill Phase 3, zero behaviour change):** `FortifyCarveContext extends
FortifySharedContext` (3 methods) on `FortifySkillOps`; `FortifyCarveHelper` (983 lines: 4-arg
`tryBreakThroughObstacle`, `attemptFinalizeCarveTransaction`, `canOverrideVillageAdjacentForCarve`, nine
carve-only helpers, four carve constants) moved verbatim, reviewer-regenerated diff clean; three cleanup
delegates deleted (processor injected directly). Skill 7,106 → 6,185 lines. Only observable change: log
category `skill-fortify-carve`. Details + rulings + field checks in `changelog.md`.

**Fortify extraction is DONE — stop.** The residual skill is navigation glue that reads every field.
Tidy-ups only, for a later pass: inline four single-caller delegates
(`tryBreakThroughObstacle` ×2, `attemptFinalizeCarveTransaction`, `isCarveEligibleForBreakAttempt`);
`isInsideCurrentFortificationHull(BlockPos)`/`isLayoutExteriorReachable` are public now (interface
constraint, same as the tower precedent). Minor: `LeafClearAction`/`LeafClearResult` still on MovementService.

**Needs Bradley next:** review the Phase 3 ontology spec (unchanged — 5 open questions with recommended
answers). Then the guided field session: Phases 6b–6i in `docs/testing/FIELD_SESSION_1.1.202.md`
(now 137 items; 6i = carve regression).

**Remaining autonomous candidates:** ontology Phase 3 (d) novelty rejection ONLY once the spec is approved.
Nothing else in the backlog is both autonomous and scoped; the next session should start with Bradley's
spec verdict or pick a Lane 2 gameplay bug that has a log.

**Deferred with reasons (unchanged):** `FarmSkill.pillarEscape`; `WoodcutSkill.pillarUp`,
`HovelPerimeterBuilder.pillarUp`, `WoodcutSkill.clearBlockingLeaves`; doorway rework.

**Needs Bradley:** Phase 3 spec review; guided field session; woodcut fallback restart loop repro; doorway
rework decision; ACTION REQUESTS interview; Bob's TTS reference sample.

---

## Session Handoff 2026-09-05 (night) — superseded

**State:** main = origin/main @ 1.1.209 (pushed, deployed to all three Prism instances). Suite 812 green.

**Shipped in 1.1.209 (FortifyVillageSkill Phase 2, zero behaviour change):** `FortifySharedContext` +
`FortifyTowerContext` callback interfaces on `FortifySkillOps`; `FortifyCleanupProcessor` (deferred-cleanup
processing + carve-repair replace path) and `FortifyTowerHelper` (all 44 tower methods, 2,209 lines) moved
verbatim; 12 pre-existing dead privates deleted; shared constants hoisted. Skill 9,560 → 7,093 lines.
Reviewer regenerated every moved-body diff independently. Only observable change: log categories
`skill-fortify-cleanup` / `skill-fortify-tower`. Details + field checks in `changelog.md`.

**Rulings made autonomously this session:** picked Fortify Phase 2 over ontology (d) because the Phase 3
spec is still unreviewed; interface is a sibling of `FortifyNavOps` (not a sub-interface) so existing
helpers keep their narrow contract; the "~15 methods" target became 14 + 11 because the tower section's
true dependency count is 24 — split rather than leave dependencies behind; dead-code deletion and
constant hoisting shipped in the same release as separate commits (reviewer-verified zero callers).

**Needs Bradley next:** review the Phase 3 spec (unchanged — 5 open questions with recommended answers).
Then the guided field session: Phases 6b–6h in `docs/testing/FIELD_SESSION_1.1.202.md` (now 132 items;
6h = fortify regression).

**Remaining autonomous candidates:** ontology Phase 3 (d) novelty rejection ONLY once the spec is approved;
Fortify Phase 3 could extract the carve/break-through section (`tryBreakThroughObstacle`, ~560 lines,
`attemptFinalizeCarveTransaction`) behind the same `FortifySharedContext` — but stop there: the remaining
skill is navigation glue that reads every field. Minor: `LeafClearAction`/`LeafClearResult` still declared
on MovementService.

**Deferred with reasons (unchanged):** `FarmSkill.pillarEscape`; `WoodcutSkill.pillarUp`,
`HovelPerimeterBuilder.pillarUp`, `WoodcutSkill.clearBlockingLeaves`.

**Needs Bradley:** Phase 3 spec review; guided field session; woodcut fallback restart loop repro; doorway
rework decision; ACTION REQUESTS interview; Bob's TTS reference sample.

---

## Session Handoff 2026-09-05 (later) — superseded

**State:** main = origin/main @ 1.1.208 (pushed, deployed to all three Prism instances). Suite 809 green.

**Shipped in 1.1.208:** `DebouncedWriter` + debounced `BotHomeService` writes (500 ms / 5 s, write-through
after shutdown); ladder helpers → ScaffoldService; new `LeafClearService` (moved navigation leaf clearing +
shared `clearLineOfSight` for WoodcutCleanup/BridgeScaffold) — all verified zero-behaviour-change;
**soul ontology Phase 3 spec** written, NOT built (`docs/superpowers/specs/2026-09-05-…phase3-design.md`).

**Needs Bradley next:** review the Phase 3 spec (5 open questions with recommended answers; proposed order
novelty → peer stance → relations → structured output, one build each). Then the guided field session
(Phases 6b–6f + the 1.1.208 checks in the changelog).

**Deferred with reasons:** `FarmSkill.pillarEscape` (timing/placement differ from ScaffoldService — a
behaviour change, judge in the field first); `WoodcutSkill.pillarUp`, `HovelPerimeterBuilder.pillarUp`,
`WoodcutSkill.clearBlockingLeaves` (need 2–3 options each, fragile skills); FortifyVillageSkill Phase 2
(own build). Minor: `LeafClearAction`/`LeafClearResult` still declared on MovementService.

**Remaining autonomous candidates:** FortifyVillageSkill Phase 2 (`FortifySharedContext`, own build);
ontology Phase 3 (d) novelty rejection once the spec is approved.

**Needs Bradley:** Phase 3 spec review; guided field session; woodcut fallback restart loop repro; doorway
rework decision; ACTION REQUESTS interview; Bob's TTS reference sample.

---

## Session Handoff 2026-09-05 — superseded

**State:** main = origin/main @ 1.1.207 (pushed, deployed to all three Prism instances). Suite 795 green.

**Shipped in 1.1.207:** command pruning review (both commands kept with evidence; README name fixed;
Actions-tab Regroup duplicate removed); crafting refusals report exactly what is missing
(`CraftingRequirementsPolicy`) and unknown names are no longer silent; `WaterSpotMemory` per world
(shared by fishing + farming, per-kind cap 16) and fishing water search 12→28 with a column pre-filter.
Details + field checks in `changelog.md`.

**Field checks pending:** `docs/testing/FIELD_SESSION_1.1.202.md` Phases 6b/6c/6d plus the 1.1.205–1.1.207
lists in the changelog (add Phases 6e/6f when convenient).

**Remaining autonomous candidates:** ScaffoldService/LeafClearService extraction; FortifyVillageSkill
Phase 2 (`FortifySharedContext`); ontology Phase 3 / second scripted-text personality (spec first);
`BotHomeService.flush()` debounce (sync whole-file write on every mutator).

**Needs Bradley:** guided field session; woodcut fallback restart loop repro; doorway rework decision;
ACTION REQUESTS interview; Bob's TTS reference sample.

---

## Session Handoff 2026-09-04 (latest) — superseded

**State:** main = origin/main @ 1.1.206 (pushed, deployed to all three Prism instances). Suite 773 green.

**Shipped in 1.1.206:** the four code follow-ups from the 1.1.205 review — ToolSelector/MiningTool swaps
now happen on the server thread (scan off-thread, atomic re-validated swap, one retry); FarmSkill and
BotActions hotbar targeting prefers the selected slot again; travel-wait offload latch is a 2-failure
counter that ignores aborts; `getRecentSurfaceRecoveryFailureReason` never null. Phase 6d (1.1.205
checks) added to the field checklist (108 items).

**Field checks pending:** `docs/testing/FIELD_SESSION_1.1.202.md` Phases 6b/6c/6d; for 1.1.206 watch
mining for a one-tick hitch before the first swing and any `slot moved` DEBUG lines.

**Remaining autonomous candidates:** command pruning review (`look_player`, `direction reset`,
Actions-tab Regroup duplicate); water location memory + fishing reach; crafting helper "report missing
inputs"; ScaffoldService/LeafClearService extraction; FortifyVillageSkill Phase 2; ontology Phase 3 /
second scripted-text personality (spec first).

**Needs Bradley:** guided field session; woodcut fallback restart loop repro; doorway rework decision;
ACTION REQUESTS interview; Bob's TTS reference sample.

---

## Session Handoff 2026-09-04 (late) — superseded

**State:** main = origin/main @ 1.1.205 (pushed, deployed to all three Prism instances). Suite 767 green.

**Shipped in 1.1.205 (loose ends from the backlog run, items 1–5):** FarmSkill/RideSyncService hotbar
access honours the hotbar lock; escape pillar pulls scaffold out of bundles (`BundleService.reachFirst`
shared thread hop); bundle-aware tool/armor/weapon selection (strictly-better rule, 100-tick combat rate
limit); travel-wait offload is real via `TaskService.runAmbient` (60 s interval + failure latch, follow
suspend/resume mirrored from SkillManager); furnace fuel offload fallback (`FuelOffloadPolicy`,
`FurnaceOffloadService`) when no chest can be found or placed. Details + field checks in `changelog.md`.

**Field checks pending:** `docs/testing/FIELD_SESSION_1.1.202.md` Phases 6b/6c plus the 1.1.205 list in
the changelog (add a Phase 6d when convenient).

**Code follow-ups:** `ToolSelector.onSlotClick` mutates inventory off the server thread (pre-existing);
`FarmSkill.ensureHotbarAccess` unlocked-with-full-hotbar targets slot 0 now; travel-wait offload latch
fires on any false (incl. `/bot stop` abort) and stamps the interval even when the slot was busy;
`getRecentSurfaceRecoveryFailureReason` null-vs-"" contract.

**Remaining autonomous candidates (from the 2026-09-04 list):** command pruning review (`look_player`,
`direction reset`, Actions-tab Regroup duplicate); water location memory + fishing reach; crafting
helper "report missing inputs"; ScaffoldService/LeafClearService extraction; FortifyVillageSkill Phase 2;
ontology Phase 3 / second scripted-text personality (spec first).

**Needs Bradley:** guided field session; woodcut fallback restart loop repro; doorway rework decision;
ACTION REQUESTS interview; Bob's TTS reference sample.

---

## Session Handoff 2026-09-04 (night) — superseded

**State:** main = origin/main @ 1.1.204 (pushed, deployed to all three Prism instances). Suite 750 green.

**Shipped in 1.1.204 (backlog run, six items):** bundle-aware inventory (`InventoryIterator`,
`BundleService.extract`, extract-first consumes); Spells key → inventory Spells tab (legacy
`CompanionSpellsScreen` deleted; non-op >8 blocks now gets "Out of range"); third persona Silas;
pillar-failure `reason=` + guard/patrol escape cooldown when still below surface + hotbar-lock guard;
`TravelWaitPolicy`/`TravelWaitService` (hobbies while a fast-travel cooldown runs, auto-travel after;
offload-to-existing-chest decided but deferred — no tick-safe worker launch); housekeeping verified.
Details + field checks in `changelog.md` (1.1.204 entry).

**Field checks now pending for 1.1.203 + 1.1.204:** `docs/testing/FIELD_SESSION_1.1.202.md` Phase 6b
(config sync / mute masks) plus the 1.1.204 list in the changelog entry (Spells key, `/bot come` on
cooldown, wedged-bot `reason=`, Silas banter, bundled-plank crafting).

**Code follow-ups named in comments:** bundled scaffold extraction in the escape path
(`BotFleeService.countScaffoldBlocks`); `FarmSkill`/`RideSyncService` private `ensureHotbarAccess`
copies under hotbar lock; MiningTool/armor/combat tool selection direct-only; travel-wait
OFFLOAD_EXISTING worker launch; `getRecentSurfaceRecoveryFailureReason` null-vs-"" contract.

**Needs Bradley:** the guided field session; woodcut fallback restart loop repro; doorway rework
architecture decision; ACTION REQUESTS design interview; Bob's own TTS reference sample.

---

## Session Handoff 2026-09-04 (evening) — superseded

**State:** main = origin/main @ 1.1.203 merge (see git log). Deployed JAR on all three Prism instances:
1.1.203 (if the deploy line in changelog says so; otherwise 1.1.202). Suite 704 green.

**Shipped in 1.1.203 (one build, four items):** real config sync (S2C on join/change, operator-or-host
gated C2S save, `SharedConfig` allowlist, dedicated-server `remoteAuthoritative` guard), per-player
voice mute masks (per-recipient sound send + `VoiceMuteMaskPayload`), model-manager RAM shortfall on
the Download button, Dreamsleeve greyed off macOS. Spec/plan under `docs/superpowers/`.

**Field checks for 1.1.203 (add to the guided session):** LAN/dedicated — Voice toggle and dialogue
rates reach bots; muting a category in one client leaves another client hearing it; opening a
single-player world after leaving a server keeps local settings; single-player saves still work with
cheats off.

**Do next (separate builds):** `InventoryIterator` refactor; second soul persona JSON draft; delete
legacy `CompanionSpellsScreen`. Deferred minors from the 1.1.203 branch: mask receiver unthrottled;
wire cap 64 vs 9 ids; while on a remote server no client-side `save()` persists.

---

## Session Handoff 2026-09-04 (morning) — superseded

**State:** main = origin/main @ a82aad2 (pushed). Deployed JAR on all three Prism instances:
1.1.202. Suite 679 green. Read `.ralph/guardrails.md` before touching code; `CLAUDE.md` here is
gitignored but present locally.

**Shipped today:** 1.1.200 (ollama4j shared-state fix + woodcut-loop diagnostic), 1.1.201 (soul
memory digest = CONSOLIDATION phase 1: spec/plan under `docs/superpowers/`), 1.1.202 (torch-hold
gate diagnostics, `CreeperEvasionPolicy`, the `BotCreeperDefenseService.isFusing` root-cause fix),
Windows support for the Pocket TTS installer (unreleased, on main), README rewrite, merged field
checklist `docs/testing/FIELD_SESSION_1.1.202.md` + `GUIDED_SESSION_PROTOCOL.md`.

**Do next, as one build (1.1.203), same loop as today — implement → review → merge → push:**
1. **Config sync stub fix.** `configNetworkManager` / `ConfigJsonUtil.configToJson/applyConfigJson`
   are compile-time no-op stubs, so ALL global config (incl. the Voice toggle) is single-player
   only. Make the sync real (S2C on join + on change), with unit tests for the JSON round-trip.
   Detail in the "Multiplayer Voice Muting" section below.
2. **Per-player voice mute masks** on top of it: replace the broadcast in
   `BotDialoguePlayer.playSound` with a per-recipient loop, C2S payload for each client's mask,
   consult it in `VoiceLineMuteService.isMuted(category, viewer)` (the `viewer` seam exists).
3. **Model manager RAM warning** — read `OperatingSystemMXBean.getTotalMemorySize()`, warn when
   a model's `recommendedRamGb` exceeds it (`OllamaModelInstaller.KnownModel`).
4. **Grey out Dreamsleeve off macOS** in the voice engine chooser (`EngineRow` list).

Then (separate builds): `InventoryIterator` refactor (bundle-aware slots for HungerService /
MiningTool / CraftingHelper / ChestStoreService); second soul persona JSON draft; delete legacy
`CompanionSpellsScreen` (still referenced from `FrensClient.java:38, 1578`).

**Needs Bradley, not a session alone:** the guided field session on 1.1.202 (one merged checklist,
he plays, Claude directs from `latest.log`); the doorway rework architecture decision; the
ACTION REQUESTS design interview.

**Rules that bit today:** never `git add -A` (untracked `logs/`, `voices/ab-test/`); the vault
git-guard hook blocks any `git add/commit` text while the shell cwd is inside `~/pontus/vault`
— `cd` out in a separate call; bump `mod_version` only when deploying; deploy only after the
`pgrep … natives` check says the game is closed.

## Backlog Lineup 2026-09-03

**Where things stand:** main at 1.1.199, deployed to all three Prism instances, 240 commits
unpushed to origin (Bradley must say push). Everything 1.1.185→1.1.199 shipped 2026-08-29/30 and
has only had a four-minute smoke run (1.21.11 `latest.log`, 01:13–01:17 on 08-30), so the soul
track is validation-bound before anything new starts. Suite 621 green.

### Lane 1 — validate what shipped (blocks the soul track)
- [ ] **Guided field session on 1.1.202** — use the ONE merged checklist `docs/testing/FIELD_SESSION_1.1.202.md` (82 items, 11 phases; protocol in `docs/testing/GUIDED_SESSION_PROTOCOL.md`). It supersedes the per-version lists below, which it was built from: group
      chat (1.1.176), banter (1.1.177), local chat (1.1.178), solo remarks + player-addressed banter
      (1.1.181), pacing sliders (1.1.188), per-bot voices (1.1.190–1.1.192), ontology Phase 1+2 seeds
      / open threads / day memories (1.1.196–1.1.198), Pocket TTS install + voice quality (1.1.198).
      Still-open 1.1.175 items too: Piper install retry on macOS, llama3.2:3b speed vs 8B, streaming
      first-word latency.
- [ ] **Memory digest field checklist** (1.1.201, spec §10): tell Jake three things, sleep through a
      night, inspect `mind.json` for the new `playerMemories` / `digestCursors`, listen for a recall
      in banter, run `/bot soul memory Jake`, then `/bot soul reset Jake` and confirm the list is
      empty and `archivedPlayerMemories` holds them. Also exercise `/bot soul digest on|off|status`.
      - Confirm a party-scene fact is attributed to the right player.
      - Reset while a rollover digest is running → memories stay empty.
- [ ] **Post-session autopsy** in the established pattern; fixes as one build.

### Lane 2 — bugs already on the table
- [x] **ollama4j `NoClassDefFoundError` in idle hobbies** ✅ 2026-09-03, commit `7123d83` (1.1.200).
      Root cause: the shared skill-state map was a static on `FunctionCallerV2`, whose static init
      constructs `OllamaAPI`; ollama4j is excluded from non-AI JARs, so every non-LLM caller (idle
      hobbies, auto-hunt, come-recovery, `/bot` skill commands) got a throwaway empty map.
      `SharedStateService` now owns the map; regression test guards the dependency.
- [ ] **Woodcut fallback restart loop** (NEW, 1.1.199 log 01:14:01–01:14:04): the idle wooden
      fallback restarted a doomed one-tree woodcut 34× in 3 s (bot has no axe; `WoodcutSkill.
      prepareWoodcutTooling` refuses, `maybeHandleIdleWoodenFallback` fires again next tick). The
      12 s cooldown (`NEXT_WOODEN_FALLBACK_TICK`) is wiped whenever `computeAccessibleIdleFallback
      Signature` changes between ticks — nothing in the log shows what changes. 1.1.200 adds an INFO
      line at that reset (`signature changed A -> B with N ticks of cooldown left`). Field session:
      reproduce with an axeless bot near trees, read the line, then fix at the source. Product
      question underneath: should the axeless fallback punch one tree instead of refusing?
- [x] **Soul follow-ups deferred in 1.1.178/1.1.179** — all three were already closed and the
      handoff above was stale: word-boundary bot-name match (`SoulLocalSalience.
      mentionsBotNotLeading`, regex `\b`), `vetoed:roster-lost` pushes `nextEligibleAtMs`
      (`SoulLocalDirector` ~L262), `SoulPlayerActivity.clear()` on runtime shutdown +
      `forget(playerId)` on disconnect (`SoulRuntime` L434 / L697). Verified in code 2026-09-03.
- [ ] **Bob's own TTS reference sample** — still shares Jake's Dreamsleeve clone (open since 1.1.184).
- [ ] **P1 gameplay** (detail in the P1 section below): creeper back-away, BotTorchHoldService not
      firing (diagnostic-first), doorway/pressure-plate stalls (multi-day rework — discuss the
      door-plan architecture with Bradley first). Slot after the field session; they need in-game time.

### Lane 3 — next features, in queue order
- [x] **CONSOLIDATION** — durable cross-surface memory; fold DM / PARTY / banter / local into one
      whole. Design interview + spec first (`docs/superpowers/specs/`), then plan, then implement.
      ✅ 2026-09-04 (1.1.201) — phase 1 shipped as the memory digest; field-test pending.
- [ ] **Action requests** — follows consolidation.
- [x] Smaller candidates named in specs but not built: second soul personality (engagement spec calls
      it the highest-leverage follow-up), ontology Phase 3 (bot↔bot stance, typed relation facts,
      structured output, novelty rejection), Pocket voice cloning (HF-gated), per-player voice mute
      masks (gated on the stubbed config sync — see Multiplayer Voice Muting below).

### Housekeeping (one commit)
- [x] Decide on pushing the 240 commits. ✅ pushed 2026-09-04 (main = origin/main from 1.1.203 on).
- [x] `AGENTS.md` pgrep check — already synced to the natives pattern on 2026-09-03 (verified 2026-09-04).
- [x] `CLAUDE.md` + `AGENTS.md` "No automated tests" wording — gone as of 2026-09-03 (verified 2026-09-04); CI still `build -x test`.
- [x] `TODO.md` retired 2026-09-03; changelog header already points at RALPH_TASK.md (verified 2026-09-04).
- [x] Vault: `Frens.md` now says main 1.1.203 pushed (vault commit e8f9d83, 2026-09-04); the March backlog note was already updated 2026-09-03.

## Session Handoff 2026-08-26 (later) — ambient/local chat shipped (1.1.178)

Ambient/local chat implemented end-to-end this session: spec
(`docs/superpowers/specs/2026-08-26-frens-soul-local-chat-design.md`), plan
(`docs/superpowers/plans/2026-08-26-soul-local-chat.md`), 10 implementation commits
across 7 tasks (incl. two fix rounds on the director and one on the runtime wiring),
suite 488 → 525 green. Full reasoning in `changelog.md` (1.1.178 entry). A soul-bound
companion standing near you now overhears unaddressed chat and, rarely, chimes in out
loud to everyone in earshot via the same PARTY scene pipeline group chat and banter use.
Split into an always-on recorder (`SoulLocalMemory`, gated only by the toggle + hard
rejects) and a default-OFF deterministic reaction director (`SoulLocalDirector`, gated by
a pure salience score plus a banter-style veto chain); the reply window opens on scene
*delivery* (not submission, a mid-implementation correction) and grants exactly one
continuation, enforced by a fail-closed `ContinuationTracker`. Never consumes a chat
line — every pre-existing unaddressed-chat handler still runs untouched, reaction or no
reaction. Default OFF behind `soulLocalChatEnabled` (Local chip + `/bot soul local
on|off|status`).

**Field-test:** run the 1.1.176 group-chat checklist, the 1.1.177 banter checklist, AND
the 1.1.178 local-chat checklist (all three in `changelog.md`) — **none of the three
soul-track surfaces has been field-tested yet**, so this is a three-feature validation
pass, not just one. Local chat needs its own toggle ON (`/bot soul local on`) plus a
second player nearby to confirm the reaction is actually audible to a bystander, not just
the addressee.

**Follow-ups surfaced during the local-chat plan, not yet actioned** (full detail in the
changelog's "Follow-ups" and "Pre-existing bug" sections): a word-boundary fix needed in
the salience scorer's bot-name match (short names like "Al"/"Sam" can substring-match);
`vetoed:roster-lost` doesn't push the cooldown so repeated capture failures retry every
line; one stale/duplicate test name in `GroupScenePlaybackTest`; and a **pre-existing bug
in 1.1.177's banter feature, not introduced by this plan** — `SoulPlayerActivity.clear()`
has no production call site and `SoulPlayerActivity` has no per-player eviction on
disconnect, so its static maps grow across a session and stale activity strings can
survive a player rejoin. Worth its own small follow-up session.

**Next soul-track item after all three validate: CONSOLIDATION.** The roadmap's fifth
item — durable cross-surface memory (the local ring is deliberately session-scoped;
consolidation is where that becomes persistent), and folding the now-four conversational
surfaces (DM, group/PARTY, banter, local) into a coherent whole before the sixth item,
action requests, gets its own spec. Needs its own interview and spec first, same as every
prior soul-track item.

## Session Handoff 2026-08-26 — banter shipped (1.1.177)

Banter implemented end-to-end: spec
(`docs/superpowers/specs/2026-08-26-frens-soul-banter-design.md`), plan
(`docs/superpowers/plans/2026-08-26-soul-banter.md`), 7 implementation commits, suite
463 → 488 green. Full reasoning in `changelog.md` (1.1.177 entry). Deterministic
`SoulBanterDirector` (5 s eval, named-veto chain, two-phase capture+submit) fires
BANTER-kind scenes through the PARTY machinery; seeds from witnessed events + situation;
4-line cap; ambient text/voice category masks gate delivery per line (the deliberate
opposite of the soul-DM exemption); failures silent; default OFF behind
`soulBanterEnabled` (Banter chip + `/bot soul banter on|off|status` — status prints the
live veto, the main field-test tool).

**Field-test:** run the 1.1.176 group-chat checklist AND the 1.1.177 banter checklist
(both in changelog.md). Banter needs the toggle ON + 2 soul-bound bots
(`/bot soul enable <SecondBot>` reuses Jake's profile) + 4–8 min of calm.

**Next soul-track item after validation: AMBIENT/LOCAL CHAT** — bots overhearing and
occasionally reacting to unaddressed player chat near them. Spec first. Known tension to
resolve in the interview: every chat line becomes a potential LLM trigger (cost/noise),
overlap with banter's quiet-window signal (`SoulPlayerActivity.lastChatAt`), and
addressing rules (when does an overheard reaction feel natural vs intrusive).

## Session Handoff 2026-08-25 (later) — group chat shipped (1.1.176)

Group chat implemented end-to-end this session: spec
(`docs/superpowers/specs/2026-08-25-frens-soul-group-chat-design.md`), plan
(`docs/superpowers/plans/2026-08-25-soul-group-chat.md`), 8 implementation commits, suite
417 → 463 green. Full reasoning in `changelog.md` (1.1.176 entry). "bots, ..." or
"Jake and Sara, ..." → roster of the speaker's own soul-bound LOCAL bots → one
orchestration call → speaker-tagged validated lines → tick-paced playback with
per-speaker positional voice to everyone in earshot. Party transcripts at
`<world>/frens/party/v1/<owner>/` (own epochs; `/bot soul reset party`). Kill switch
`soulPartyEnabled` (default on). DM pipeline untouched; LoadGoverner probe signature
unchanged (sum now includes active scenes).

**Field-test checklist for next play session (1.1.176):** see the changelog entry —
2-bot scene needs `/bot soul enable <SecondBot>` (reuses Jake's profile; authoring a
second soul profile is the natural companion task), mixed soul/non-soul broadcast,
bystander earshot, voice-off beat pacing, walk-away mid-scene, party reset mid-scene,
governor floor during scenes, party toggle off → legacy loop. Piper/3B items from the
1.1.175 checklist below are still open too.

**Next soul-track item after group chat validates: BANTER** — system-initiated scenes
riding this same PARTY path. Needs its own spec first (eligibility: quiet period,
cooldowns, player presence; and its own text-category gating — banter is ambient-like, so
it should NOT inherit the soul-DM Text-master exemption; the `textEnabled` seam in
SoulMessageDelivery and the scene playback fan-out are the hooks).

## Session Handoff 2026-08-25 — dialogue controls + TTS/LLM installers shipped (1.1.166→1.1.175)

**Where we are:** frens-1.1.175 deployed to all three Prism instances; suite 417/417; main
~120 commits ahead of origin (NOT pushed — Bradley must say push). Everything below is in
`changelog.md` (2026-08-25 entries, newest-first) with full reasoning.

**Shipped today (one line each):** voiced-line category muting (9 categories, Voice row
Adv…); AdminWorldSettingsScreen double-blur crash fix + mod-wide alpha-0 invisible-text
fix; LLM/voice/text control consolidation (lazy config-backed LLM enablement — push maps
deleted; Voice/Text masters now gate the soul pipeline; soul toggles in the GUI); Text Adv
unified with Voice semantics (master = kill switch, checked = active);
sentence-streaming TTS + LoadGoverner floor held through the synth window; Piper voice
engine installer (pinned/sha256, macOS upstream-dylib fix, smoke test) + engine chooser
(Eng… chip); Ollama soul-model manager (LLM… chip; llama3.2:3b lined up for a speed
test); Companion Settings autosave (Save button removed); background installs survive
menu close (service-owned InstallJob, double-start impossible); category-aware chat
fixes the "Nice bird!" leak (voice-muted lines fell back to untagged chat).

**Bradley's standing rulings from today:** settings autosave unless destructive; one Adv
semantics everywhere (master kill switch + per-category checkboxes); soul replies always
visible regardless of Text Chat; users get engine/model CHOICE via transparent installers
(show size, source, destination, system check, pre-install detection).

**Field-test items still open on 1.1.175:** Piper Download & Install retry on macOS (the
dylib fix is deployed but untested in-game); llama3.2:3b pull + speed/quality vs 8B
(prompts were tuned on 8B — judge reply grounding too); streaming first-word latency +
whether the governor fix stops the laptop bogging during synth; category muting round-trip
(ambient muted in both menus = fully gone).

**NEXT SOUL-TRACK ITEM — group chat.** Queue from the pilot roadmap: group chat → banter →
ambient/local chat → consolidation → action requests. Today's session did NOT spec it.
Known constraints to bring into the brainstorm: soul routing is currently exclusive
private DM (leading-name routing via ChatAddressing; CompanionCommunicationPolicy.isPrivateSoulAuthorized;
one AcceptedTurn per player-bot conversation, cursor epochs per ConversationKey);
SoulMessageDelivery sends privately to ONE player; voice delivery is per-player payloads.
Group chat likely touches: routing (who is addressed when several bots/players hear),
delivery fan-out, turn scheduling (SoulGenerationScheduler is 1-slot), reachability
policy, and how LoadGoverner handles multiple queued generations. Spec first
(docs/superpowers/specs/), then plan, then implement.

## Session Notes 2026-05-06 — Named-hostile pacifism shipped (verified in 1.1.55)

The "Next session's task" handed off on 2026-04-20 was **fully implemented and shipped in commit `aeef62a` (1.1.55)** along with the per-hobby toggle menu + llama mount filter. All hook points, the toggle, the flee hook, and the Admin UI are wired:

| Spec item | Location |
|---|---|
| `BotCombatPolicyService.shouldBotAttack` | [services/BotCombatPolicyService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotCombatPolicyService.java) |
| Per-bot toggle storage + getter/setter/toggle | [services/BotHomeService.java:489](src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java#L489) (`isAttackNamedMobs` / `setAttackNamedMobs` / `toggleAttackNamedMobs`) |
| `engageHostiles` filter (covers all 8 call sites) | [BotEventHandler.java:3657](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3657) |
| `BotAnimalDefenseService` Step 1 + Step 2 (scan still runs, only engagement gated) | [services/BotAnimalDefenseService.java:193, 234](src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java#L193) |
| `BotMutualAidService` ally-threat | [services/BotMutualAidService.java:562](src/main/java/net/wcfcarolina13/GameAI/services/BotMutualAidService.java#L562) |
| `BotRLActionService` candidate set | [services/BotRLActionService.java:92](src/main/java/net/wcfcarolina13/GameAI/services/BotRLActionService.java#L92) |
| Damage-intake flee hook | [Frens.java:932-944](src/main/java/net/wcfcarolina13/Frens.java#L932-L944) |
| `BotFleeService.fleeFromEntity` | [services/BotFleeService.java:650](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java#L650) |
| `/bot attack_named_mobs on/off/toggle` chat command | [Commands/BotHomeCommands.java:159](src/main/java/net/wcfcarolina13/Commands/BotHomeCommands.java#L159) |
| Admin UI row "Attack Named Mobs" | [BotPlayerInventoryScreen.java:622](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java#L622) |

`BotCombatCalloutService` was on the spec's hook list but inspection confirmed it's voice-line-only and has no attack-target selection — no filter needed. Behavior matches the spec intent: hostile scans still see name-tagged mobs (so flee can trigger and overhead warnings still fire), only the engagement boundary rejects them.

The 2026-04-20 backlog item line at the bottom of P2 Commands/UX was kept for now as a "test in-game and confirm" reminder; can be checked off after manual verification of the spec's 6-item test plan.

### Other backlog items still open from 2026-04-20

- **Base Manager UX polish** — sort rows / section headers / hover tooltip / Set Home chat echo.
- **Delete legacy `CompanionSpellsScreen` (post-1.1.40 cutover)** — the unified tab is validated; safe to remove the dead screen and its references.
- **Actions-tab Regroup duplicate** — decide whether to keep `Regroup` / `Return Home` in both Actions and Spells or consolidate.

These are all independent follow-ups for whichever session picks them up next.

## Session Notes 2026-05-06 — Backlog audit pass

Swept the Backlog section against the current codebase to catch items that had quietly shipped without being checked off. Results:

**Flipped to [x] with implementation pointers:**

- Creeper evasion (sprint away when unarmed) — `BotEventHandler.java:3794`
- Protected build zones (no-grief areas) — `ProtectedZoneService` (AABB zones, persisted per-world)
- Till soil, plant seeds, harvest, replant — `PlantSeedsSkill` + `HarvestCropSkill` + `FarmSkill` cover the loop, auto-replant validated 2026-04-08
- Tree chopping (safe climbing, late drop collection) — `WoodcutSkill` (registered hobby `woodcut`)
- Strip mining with safety offset — `StripMineSkill`

**Annotated with current-state notes (still [ ] but partial):**

- Bundle-aware inventory scanning — lodestone / navigation / honey already migrated; HungerService / MiningTool / ChestStoreService still raw-slot
- Boat support — `TravelMountHandler` covers mount/dismount/leashed-rejoin sync; free-form bot-driven boat navigation still open
- Farm irrigation leak patching — detection shipped (`irrigationLeakReason`), patch path open
- Animal husbandry — shears used in `WoolSkill` / `HoneyCollectSkill`; breed/pen still open
- Create infinite water source — `FarmSkill` *uses* an existing 2×2 basin but doesn't *create* one
- Fall-clutch / ride-sync verification items — code shipped (`BotFallSafetyService`, `RideSyncService`); just unverified in-game

**Genuinely still open (large set — left as-is):** drop-sweep cobblestone loop, idle during fast-travel cooldown, furnace offload fallback, craft chest from wood, hunger-aware task interruption, smoker preference, HealingService cooked-food preference, fuel-acquisition fallback, farm underground recovery, farm proactive chest workflow, cave/structure detection, water encounter handling, shelves+containers no-break list, water location memory, fight with teammates, craft common items / armor / walls / 2-person house, recipe awareness, hunt camp shelter, multi-bot UX, advanced combat, command queuing, voiced banter for follow-adventure, quick-action buttons, shift-click inventory UI, ShelterSkill refactor, construction parity, FortifyVillageSkill Phase 2, command pruning eval, Base Manager UX polish, legacy `CompanionSpellsScreen` deletion (file still exists + still referenced by `FrensClient.java:38, 1578`), Actions-tab Regroup duplicate, Elder Scrolls dialogue/journal, LLM Phase 1+.

No code changes in this audit pass — RALPH-only documentation cleanup.

---

## Session Notes 2026-04-16 — Door passage series (1.1.5 → 1.1.16)

**Current deployed version: 1.1.16** (all three Prism instances). This session ran a long iterative series on bot door passage; documenting where each fix landed and what's still open so the next session can pick up cleanly.

### What shipped and is known-good (do not revert)

- **1.1.5** `BotActions.hasMovementClearance` — replaced `blocksMovement()` feet/head check with stateful `isPassableForMovement`. Open doors/gates/trapdoors with `Properties.OPEN == true` are now passable. Same bug-pattern fix pattern as commit 29a5de8 (ReturnBaseStuckService, 2026-04-08).
- **1.1.6** `FollowMovementService.hasTwoHighClearance` — same fix applied to the follow-movement passability helper used by narrow-passage align, chokepoint, dropoff guard, local-obstacle nudge.
- **1.1.7** Door-plan refactor in [BotEventHandler.java](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java): (a) deleted the `door-recovery` retreat (was retreating bot 2-3 blocks away from door when stuck → primary oscillation source); (b) gated `door-corner` plan rebuild with wrong-side check; (c) `isDirectRouteBlocked` now skips hits on open openables. This change intentionally breaks the old shelter-escape "trapped in corner, needs door as exit" behavior — documented as acceptable trade-off at the time, re-address later.
- **1.1.8** Extracted `isDoorPlanWrongSide(approach, step, goal)` helper and applied it at **all four** door-plan creation sites (`door-corner`, `door-adjacent`, `door-escape`, `door-ray`). Previously only `door-corner` had the check.
- **1.1.9** In `tickFollowDoorPlan`: when `plan.stepping() && doorOpen && onGround`, force `BotActions.jump(bot)` each tick instead of deferring to `autoJumpIfNeeded` (which explicitly skips door cells).
- **1.1.10** Extracted `isBoxClearIgnoringWalkablePartials` helper in `BotActions`. When `world.isSpaceEmpty` rejects due to a walkable partial's thin collision overlapping the bot's bounding box at the Y boundary (strict-less-than `Box#intersects`), second pass skips cells whose state passes `isPassableForMovement`. Covered plates, carpets, rails, thin partials, open door strips.
- **1.1.11** **Diagnostic-only build** — added `door-step-diag` (tickFollowDoorPlan) and `applyMovementInput-reject` (BotActions) log channels. Evidence caught that pressure plates failed `hasClearance=false`.
- **1.1.12** **THE FIX** — `isPassableForMovement` now consults [WalkablePartialBlocks.isPathable](src/main/java/net/wcfcarolina13/GameAI/services/WalkablePartialBlocks.java) as a third gate. In 1.21.11 `state.blocksMovement()` **returns `true` for pressure plates** (contrary to my assumption), so the previous two gates rejected. `WalkablePartialBlocks.isPathable` correctly handles plates via `AbstractPressurePlateBlock`, plus carpets, rails, tripwire, lily pad, collision max Y ≤ 0.125 fallback. Signature changed to `(state, world, pos)`. Applied symmetrically in `FollowMovementService`.
- **1.1.13** Tick-persistent door-open retry (was only firing in `!plan.stepping()` phase) + stepping-flip only commits when `doorOpen` is confirmed from world state (not trusting `tryOpenDoorAt`'s return).
- **1.1.14** Stuck-near-doorway auto-jump — generalized 1.1.9. When door plan stuck counter ≥ 8 ticks and bot on ground, force jump regardless of stepping phase. Generalizes the user's "jump a couple times to unstick" reflex.
- **1.1.16** = 1.1.14 (1.1.15 reverted, see below).

### What was reverted (1.1.15, deployed as 1.1.16)

- 1.1.15 added an auto-step escape hatch in `canOccupyPosition`: if `hasMovementClearance(feet)` failed but feet cell was `WalkablePartialBlocks.isStandable` and cells above passed, allow the impulse anyway, trust vanilla `Entity.move()` auto-step. **Too permissive.** Bot pushed into stair back-top-halves and other partials where vanilla auto-step couldn't actually resolve the step-up, accumulated stuck time, triggered rescue-mining behavior. Reverted in 1.1.16.

### Still open — priority order for next session

1. **Bot gets stuck approaching the northern tower door** (residual from 1.1.16 testing). Specific pattern from latest log at `08:39:44 → 08:39:55`: bot at `(888, 64, 1397)`, no door plan active, `commander-route clear` firing every ~2s (removing waypoints), outer direct-pursuit tries to apply impulse but something rejects it. Bot sits for 11 seconds until a wolf-teleport or user-jump rescues it. `canOccupyPosition` is rejecting movement from `z=1397` to `z=1396` even though `z=1396` should just be a pressure plate (1.1.12 fix handles plates). Something in the approach path — likely the head cell at `(888, 65, 1396)` — is not passable. Need to re-enable the 1.1.11-style diagnostic to see exactly what block rejects. Don't guess again.

2. **Shelter-breakfree misfires on indoor spawn.** Triggered by `Bot Jake trapped on join — hasn't moved (0.0 blocks) and no sky. Launching break-free.` in log. When bot legitimately respawns inside an enclosed structure (tower, house, base), the detection flags it as trapped and auto-mines upward, destroying stairs and other structural blocks. Need a smarter indoor-detection heuristic — e.g., check if there's a door within N blocks before assuming the bot must mine out. Or make the break-free path prefer walking toward known doors/bases before mining.

3. **Bot standing at end of staircase not trying to walk around.** New symptom reported in this session — bot on opposite end of the spiral staircase from commander, just stares, doesn't even attempt pathfinding around the central column. Likely the pathfinder ([BaritoneStylePathFinder](src/main/java/net/wcfcarolina13/PathFinding/BaritoneStylePathFinder.java) or classic [PathFinder](src/main/java/net/wcfcarolina13/PathFinding/PathFinder.java)) can't build a multi-level path through interior stairs around a column. Separate, larger issue from door passage — may need to expand the pathfinder's node-type classification for interior stair traversal. Out of scope for a quick door-fix session; worth its own investigation.

### Architectural concern to raise with user at start of next session

3+ fix attempts on door passage in this session triggered the systematic-debugging `3+ fixes failed → question architecture` rule. The current door-plan state machine (approach/step/stepping + four separate creation sites + stuck counter + wrong-side check + forced jump + tick-persistent retry) is VERY complex compared to vanilla villager door handling (~30 line `InteractWithDoorGoal`). Possible simplification: drop the door-plan entirely and have the pathfinder emit door tiles as regular waypoints, with an `InteractWithDoorGoal`-style observer that opens doors opportunistically when the bot's next waypoint is a closed door. Large change but may eliminate several bug classes at once. Discuss before attempting.

### Test procedure for next session

When testing door passage:
1. Spawn bot **OUTSIDE** the tower (sky visible) to avoid triggering shelter-breakfree. This isolates the door-passage issue.
2. Have bot follow; walk through each door in both directions; observe whether stall occurs and at which cell.
3. If stall happens and cause isn't obvious from log, **re-enable the 1.1.11-style diagnostic first** rather than guessing. The diagnostic caught the pressure plate root cause in one test run.

## Session Notes 2026-04-11 — Tamed-animal defense (Feature A)

- **New service:** `BotAnimalDefenseService` consolidates owned-animal defense with hostile-forward scan + small reverse watch list. See `docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md` (rev 3) and `docs/superpowers/plans/2026-04-11-tamed-animal-defense.md` for full design + plan.
- **Three integration hooks** in existing code: `BotEventHandler.scoreThreat` (additive defense boost), top of `BotEventHandler.engageHostiles` (augmentHostilesWithDefenseTargets call), `Frens.java` END_SERVER_TICK + SERVER_STOPPING. Iron-golem accidental-hit + direct-aggro special rules added inline in `engageHostiles`.
- **PvE only.** Player attackers get an overhead warning (existing "Engaging threats against allies" voiced line) instead of engagement. Alliances feature is the planned PvP gate, not yet built.
- **JAR built but not deployed** — user will deploy when ready. Manual verification checklist (15 items) is in the spec under "Manual Verification Checklist".

## Session Notes 2026-04-10 — Walkable-partial stuck fix + rescue teleport

- **Fixed:** Bot permanently stuck on walkable partial blocks (carpets, pressure plates, slabs, stairs, snow layers, rails, tripwire, lily pad) when near doorways. Root cause was `BotRescueService.rescueFromBurial` / `isBotCurrentlyStuck` computing `feetBlocked = !getCollisionShape().isEmpty()` — every walkable partial has a non-empty thin shape, so a bot standing normally on one had its feet blockpos == the partial block and was classified `stuckInBlocks=true`. That kicked `attemptEscapeMovement` every ~1.2s, yanking the bot off its planned door-traversal path and producing doorway wedge loops visible in `latest.log` 17:22–17:39 (`feetState=White Carpet` → repeated `door-close wait: bot too close` + `door-corner: stagnant`). Fix: added `isThinWalkablePartialBlock` class-based whitelist (mirrors `FollowPathService`) plus a ≤0.125 max-Y fallback for floor candles/skulls/etc. Called in both feetBlocked sites.
- **New feature:** Rescue teleport keybind (`key.frens.rescue_teleport`, unbound by default). Player-pressed un-stick hotkey. Server finds closest follower within 5 blocks horizontal / ≤3 above / ≤1 below / line of sight / actually following the player, then teleports it to the player's exact block with zeroed velocity. Tight constraints so it can't yank a bot across the map or phase through walls — purely for wedge-geometry escapes when wolf-teleport can't fire.
- **Also fixed (same session):** `ReturnBaseStuckService.isPassable()` had the same false-positive bug — rejected carpeted/plated path cells as non-passable during return-to-base stuck escape, because its comment falsely claimed pressure plates/carpets have empty collision shapes. Promoted `BotRescueService.isThinWalkablePartialBlock` to package-private and had `isPassable()` delegate both the feet-cell and head-cell checks to it. Rewrote the misleading comment. Left `isPassableForMining` (falling-block predicate) and `isPassableForStanding` (step-up target detection) alone — different semantics.
- Deployed JAR to all three Prism instances (1.21.11, 1.21.10, 1.21.10 TEST) after each change, after confirming game was not running. Verified deployed classes via `javap` (both `isThinWalkablePartialBlock` method in BotRescueService and the cross-class invocation from ReturnBaseStuckService.isPassable).

## Next Session: Farm Pipeline Validation & Follow-Ups

**Status:** Farm tree-clear + irrigation pipeline fixed end-to-end (2026-04-08). In-game validation confirmed all four user-reported failures are fixed. Ready for deeper farm playtesting + known follow-ups below.

### What was fixed this session (2026-04-08, commits 6458e9d..085d2a5)

User reported four distinct failures during `/bot skill farm ... manual=true` in a forested area. Each traced from Prism log evidence in `1.21.10/minecraft/logs/latest.log`.

- **WoodcutSkill bounded-mode envelope check** (`6458e9d`) — `isTreeWorkEnvelopeWithinBounds` required the whole leaf envelope expanded by `WOODCUT_LOG_SCAN_EXPANSION=4` to fit inside caller bounds. For a 13×10×13 farm AABB it rejected every tree (16/16). Relaxed to require only the trunk (`base` → `top`). Added per-tree `activeTreeWorkEnvelopeMin/Max` overlay so `mineBlockDetailed` can prune canopy that overhangs caller bounds for the currently-felling tree only. Also skipped the outer-finally full-region cleanup in bounded mode (saves ~30s per pass).
- **FarmSkill work buffer + brute-clear demotion** (`f5cee16`) — Split "is in the way" query buffer from woodcut's work buffer. Added `FARM_WOODCUT_WORK_BUFFER=6` / `FARM_WOODCUT_WORK_VERTICAL_RANGE=20` (used only by `runWoodcutInline`). `clearBlockingTreeBlocksLocally` no longer runs in parallel with woodcut — only as last-resort fallback when woodcut failed to reduce the blocker count. Eliminates floaters.
- **Farm phantom precipice** (`4a73129`) — `assessFarmSite` used `Heightmap.Type.WORLD_SURFACE` which counts logs/leaves, returning canopy tops (y=78–85) instead of walkable ground (y=65–68) in a forest. Median landed mid-canopy; `hasSimplePrecipice` sampled at y=72 and saw air below → phantom rejection. Replaced with `SafePositionService.getWalkableGroundY` in `assessFarmSite`'s column loop + `estimateFarmAreaMedianSurfaceY`. Removed two redundant hard-reject `hasSimplePrecipice` call sites.
- **WoodcutSkill UOE + unconditional bounded cleanup skip** (`14f1fa0`) — After the envelope check relaxation, `collectRemainingEnvelopeLogs` crashed with `UnsupportedOperationException`: `Stream.toList()` is unmodifiable, next line called `.sort()`. Latent bug that never fired before. Fix: `Collectors.toCollection(ArrayList::new)`. The UOE then burned 42 seconds in the outer finally's cleanup (user-visible stillness) because `felled == 0` on the exception path. Broadened the bounded-mode cleanup skip to unconditional.
- **Irrigation 2x2 infinite source** (`085d2a5`) — `placeWaterOnServerThread` used `world.getBlockState(waterPos).isOf(Blocks.WATER)` as a success check. After placing NW, flowing water spreads into SE within ticks. On the SE placement: `useOnBlock` returned `Pass` (bucket NOT consumed), but the world check saw pre-existing flowing water and falsely reported success. Result: only 1 real source, 3 flowing cells. Fix: use **bucket consumption** (`WATER_BUCKET` → `BUCKET`) as proof of placement. Removed the `still >= 1 && water >= 4` fallback in `isAcceptableIrrigation` (it was labeled "snow/cold-biome" but only ever fired in exactly this bug case).

All five commits pushed to `origin/main`. User in-game validation: **"Good, that fixed it"**.

### Known issues to watch next session

1. **The `ensureAtSurface` / `nudgeToward failed` loop at session start** (~74 seconds in the 12:23 log). Not fixed this session — secondary symptom. Triggered when bot starts with `leavesTrap=true` under canopy. Worth investigating if it recurs.
2. **WoodcutSkill still reports `soilFail=29` out of 31 logs** in dense forest — most logs fail `hazardous terrain on all sides` before even reaching the envelope check. The one tree that does get through is felled correctly now, but most of the forest is invisible to detection. May need to revisit the soil/hazard heuristic for bounded-mode farm clearing.
3. **Farm manual placement still has multiple `Heightmap.Type.WORLD_SURFACE` / `findSurfaceY` call sites** outside `assessFarmSite`. They weren't fixed in this session because they receive upstream-chosen positions. If they misbehave in forests, apply the same walkable-ground-Y treatment.

### Previously outstanding from last session (2026-04-03/04)

**Lodestone compass fast-travel system:**
- `LodestoneCompassService` — inventory scanning (including bundles), validation, selection, home designation
- `/bot compass list|home|travel` commands
- Lodestones in Bases menu, smoke signal navigation beacons
- Sunrise skill resume loop (sunset save → sleep → sunrise fast-travel back → resume skill)
- Protected lodestones from ALL mining paths (BotStuckService, ReturnBaseStuckService, MovementService)
- Fast-travel spawn offset away from solid blocks

**Woodcut scaffold descent:**
- `descendScaffoldColumn()` — Y-level-grouped descent with bridge-first-then-drop
- Hazard avoidance — `WoodcutHazardScanner` scans for ravines/water, filters scaffold directions
- Bridge retraction fix — bot walks toward perch before mining bridge blocks behind it
- Adjacent column pillar — bot can pillar from 1 block off trunk when entry is blocked
- No-walk elevated sweeps during descent (bot stays on column)

**Woodcut "Until sunset" GUI:**
- Actions menu defaults to "Until sunset" like fishing
- `SkillManager.isOpenEnded()` extended for woodcut

### Legacy follow-ups (not addressed this session)

**Lodestone compass fast-travel system:**
- `LodestoneCompassService` — inventory scanning (including bundles), validation, selection, home designation
- `/bot compass list|home|travel` commands
- Lodestones in Bases menu, smoke signal navigation beacons
- Sunrise skill resume loop (sunset save → sleep → sunrise fast-travel back → resume skill)
- Protected lodestones from ALL mining paths (BotStuckService, ReturnBaseStuckService, MovementService)
- Fast-travel spawn offset away from solid blocks

**Woodcut scaffold descent:**
- `descendScaffoldColumn()` — Y-level-grouped descent with bridge-first-then-drop
- Hazard avoidance — `WoodcutHazardScanner` scans for ravines/water, filters scaffold directions
- Bridge retraction fix — bot walks toward perch before mining bridge blocks behind it
- Adjacent column pillar — bot can pillar from 1 block off trunk when entry is blocked
- No-walk elevated sweeps during descent (bot stays on column)

**Woodcut "Until sunset" GUI:**
- Actions menu defaults to "Until sunset" like fishing
- `SkillManager.isOpenEnded()` extended for woodcut

### Known issues to validate/fix next

1. **Woodcut success rate still ~10-15%.** Most trees end PATH_OR_REACH_FAILURE. The bot mines 3-5 ground logs per tree but often can't reach upper trunk. The adjacent-column fix helps but the bridge fallback still has LoS failures. Needs in-game observation to identify remaining blockers.

2. **Bot gets stuck for 11k+ ticks** between tree canopies during woodcut. ReturnBaseStuck fires but can't effectively escape. May need a "give up on this area" threshold.

3. **Follow mode stuck at 1-block Y differences.** Bot can see commander but `directBlocked=true` when 1 block above. Escalates to stagnant-80+ with no resolution.

4. **Duplicate "Returning to base" messages** on each sunset return.

5. **Suffocation during scaffold descent** — bot embeds in terrain after dropping through scaffold gaps.

### Backlog items added this session

- **P1:** Axe retrieval from nearby chests (wooden/stone/copper only, no enchanted)
- **P1:** Bundle-aware inventory scanning (systemic fix for all inv methods)
- **P1:** Idle during fast-travel cooldown (hobby/offload while waiting)

### Key files changed

| File | Changes |
|---|---|
| `LodestoneCompassService.java` | NEW — compass scanning, validation, bundle support |
| `NavigationArtifactService.java` | Tier/multiplier, spawn offset, smoke signal, skipArtifactGate |
| `BotAutoReturnSunsetService.java` | LODESTONE_COMPASS anchor, sunrise resume, lodestone shortcut |
| `SkillResumeService.java` | SunriseResumeRecord, getLastRawArgs |
| `BotHomeService.java` | homeCompassNameByBot, findBaseNearPosition |
| `WoodcutSkill.java` | descendScaffoldColumn, hazard filtering, adjacent column, no-walk sweeps |
| `WoodcutHazardScanner.java` | NEW — ravine/water terrain assessment |
| `BridgeScaffoldService.java` | Retraction walks toward perch first |
| `ProtectedStructureBlockHelper.java` | isNeverBreakBlock (lodestone, beacon, etc.) |
| `MovementService.java` | isNeverBreakBlock guards on obstruction mining |
| `BotStuckService.java` | isNeverBreakBlock guard on mine-escape |
| `ReturnBaseStuckService.java` | isNeverBreakBlock guard on tryMineBlock |
| `modCommandRegistry.java` | /bot compass commands, orphaned compass warnings |
| `SkillManager.java` | Woodcut open-ended when no count |
| `BotPlayerInventoryScreen.java` | Woodcut "Until sunset" GUI |
| `BaseNetworkManager.java` | Lodestone entries in bases menu |
| `BaseManagerScreen.java` | isLodestone, Go To/Set Home for lodestones |

# Task: (No active task)

No active Ralph criteria. Pick from the backlog below when starting a new iteration.

## Recent Session Notes (2026-03-31)

- Implemented the critical shallow-hole navigation fix: the main pathfinder now supports 8-direction local movement (including diagonal step-ups/step-downs with anti-corner-cutting), the movement follower preserves nearby diagonal/vertical lead-in hops instead of collapsing them into the same bad wall-hump, and `ReturnBaseStuckService` now promotes discovered natural step-ups into a temporary local escape target instead of just nudging once and immediately losing control back to the old blocked destination.
- In-game validation now confirms the shallow-hole work is materially better: recent Prism logs show multi-hop `movement local escape ... route=...` selections and successful terrain progress instead of the original repeated wall-jump failure loop. The remaining navigation issue shifted from "cannot escape" to "movement still feels too micro-step / exact-hop heavy."
- Follow-up pass landed on top of that validated route escape:
  - `MovementService` now keeps a short-lived commitment to a successful local route so ordinary path following stays fluid instead of re-triggering precision local escape on every next segment.
  - same-level raw lead-in segments are merged into longer runs when the bot is no longer in trap-like terrain.
  - woodcut trunk-entry carving no longer digs the support block under the stance by default, reducing the 1-block pits left around harvested trees.
  - per-tree maintenance now includes a narrow terrain-restore hook for tracked entry repairs.
  - sapling replanting returns to planting range after drop sweep/cleanup and idle woodcut no longer disables replanting.
- Implemented woodcut utility-placement recovery: when the bot needs to place a crafting table or chest while stuck in a cramped hole, it now tries to clear a minimal safe pocket from soft-natural blocks or bot scaffold, and can do a short local relocation before giving up.
- Tightened woodcut exact-stand occupancy: trunk-entry movement now requires real block occupancy, runs bounded recovery on false-positive `"already at destination"` / near-stand movement results, and performs short post-failure stance recovery so the bot does not resume from the same bad hole stance.
- Follow-up tuning pass landed:
  - faster escalation out of repeated shallow-hole / exact-stand stalls
  - more frequent and broader woodcut drop sweeping
  - reduced log spam for utility-placement rejection scans, drop-sweep tactical traces, and expected woodcut/crafting pursuit misses
- Verified this session with:
  - `./gradlew compileJava`
  - `./gradlew remapJar`
  - `./gradlew test --tests 'net.wcfcarolina13.PathFinding.BaritoneStylePathFinderTest' --tests 'net.wcfcarolina13.PathFinding.PathFinderSegmentTest' --tests 'net.wcfcarolina13.GameAI.services.MovementServiceLocalEscapeHeuristicsTest'`
- Jar deployment status:
  - the latest navigation build with diagonal/local shallow-hole escape fixes and the movement-smoothing / replant / terrain-preservation follow-up is copied to the Prism `1.21.11` and `1.21.10` instances
  - `1.21.10 TEST` still has the older jar and was intentionally not touched during the latest deploy
- Best next validation target:
  - in-game regression run focused on:
    - shallow pits with diagonal one-block exits, verifying fewer repeated micro-hop local-escape restarts
    - woodcut trunk-entry cases that previously carved `grass_block` / `dirt` support under the stand
    - standalone and idle woodcut runs, verifying `Planted sapling at ...` or explicit replant skip summaries after per-tree cleanup

## Ralph Instructions

1. Work on the next incomplete criterion (marked [ ])
2. Check off completed criteria (change [ ] to [x])
3. Run build after code changes
4. Commit your changes frequently
5. Update .ralph/progress.md with what you accomplished
6. When ALL criteria are [x], say: "RALPH COMPLETE"
7. If stuck 3+ times on same issue, say: "RALPH GUTTER"

**For any vanilla-game knowledge** (mob behavior, entity classes, item names, block properties, drops, recipes, biome rules), use the **Minecraft Wiki MCP** (`MinecraftWiki_searchWiki`, `MinecraftWiki_getPageSummary`, `MinecraftWiki_getPageSection`) **before relying on training data**. 1.21.11 ships with content that postdates training (e.g. the rideable Nautilus mob, Mounts of Mayhem); training-data assertions about new mobs/items will be wrong.

**For any modding-API question** (Fabric APIs, mixins, registries, networking, screen handlers, server lifecycle hooks, Yarn / Parchment mappings, obfuscated→deobfuscated class lookups), use the **MCModding MCP** (`get_class_details`, `lookup_obfuscated`, `get_method_signature`, etc.) **before guessing or falling back to docs.fabricmc.net or the loom-decompiled jars**. The Parchment-mappings DB covers 1.21.11 down to 1.16.5 with documented class / method / field signatures. See CLAUDE.md "Game / API Knowledge" + "MCP integrations for this work" for the full guidance on both MCPs.

---

# Backlog

Future work items, organized by priority. Not active Ralph criteria — these are candidates for future RALPH_TASK.md iterations.

## P1 — High

### User-reported 2026-05-09 (top criticality)

User-flagged batch from in-game observation against deployed 1.1.93 (latest.log: `~/Library/Application Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log`). Listed in working ROI order; subsequent sessions should pick top-down.

- [x] **Stand-down hotkey + stop→drop-sweep cooldown (60s)** — ✅ shipped 1.1.95. New per-bot drop-sweep suppression layer in [DropSweepService](src/main/java/net/wcfcarolina13/GameAI/services/DropSweepService.java) (`suppressFor`/`isSuppressedFor`). `/bot stop` now sets a 60s suppression. New service [BotStandDownService](src/main/java/net/wcfcarolina13/GameAI/services/BotStandDownService.java) snapshots follow target, stops following, suppresses drop-sweep for 60s, then re-issues follow on tick expiry ("Back in formation."). Companion overlay slot 1 repurposed from duplicate "Stop" to `🪖 Stand Down (60s)`; new `bot standdown` brigadier command. Tap `\` still does plain stop. Final implementation differs from the original spec: timers live in `DropSweepService` (drop-sweep) and `BotStandDownService` (follow snapshot), not `BotHomeService` — closer to the existing per-service ownership pattern.
- [ ] **Creeper self-protection / back-away** — 🔧 1.1.202: root cause found and fixed (`BotCreeperDefenseService` filtered on `isIgnited()`, never set by proximity swelling; now `isFusing()`), decision extracted to tested `CreeperEvasionPolicy`, armed bots back away inside 4.5 blocks; **verify in the field session Phase 8**, then check off. Original report: Existing creeper evasion (sprint away when unarmed, [BotEventHandler.java:3794](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3794)) reportedly insufficient — bot still doesn't reliably back off when creepers are near. Audit fuse-distance / armed-vs-unarmed branches and tighten. Likely also needs a dedicated `BotCreeperSafetyService` or expansion of `BotHazardService`.
- [x] **Dangerous-pursuit gate** — ✅ shipped 1.1.97. New [DangerousPursuitGate](src/main/java/net/wcfcarolina13/GameAI/services/DangerousPursuitGate.java) composes 4 rules: target >4 blocks below bot → reject; combined light ≤0 at target → reject; 2+ hostiles within 5 blocks of target → reject; non-aggroed mob → require ranged weapon. Wired into [DropSweepService.collectNearbyDrops](src/main/java/net/wcfcarolina13/GameAI/services/DropSweepService.java) per-drop filter + [BotEventHandler.engageHostiles](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) hostile-filter loop. Self-defense (mob already targeting bot) always passes. Out of scope: route-based fall/light analysis (currently checks target only, not the path); charged-creeper weighting in cluster threshold.
- [ ] **BotTorchHoldService not visibly firing** — 🔧 1.1.202 ships the state-change `[torch-hold]` gate diagnostics; field session Phase 8 names the rejecting gate, then fix. Original: — added in 1.1.72-1.1.91 batch but user reports torch never appears in bot's hand in dim follow situations. Service IS deployed (in 1.1.93+ JAR) and registered ([Frens.java:1131](src/main/java/net/wcfcarolina13/Frens.java#L1131)). Likely caused by: (a) overly-strict 8-block audible-hostile suppression — in caves/at night there's almost always *some* hostile in 8 blocks → torch hold rejected; (b) foreign-swap detection cycling against other systems that mutate selected slot every tick (combat loadout, AutoFaceEntity), so torch gets re-overridden between 5-tick eval intervals; (c) only logs at `LOGGER.debug` so we can't tell which gate is firing. Diagnostic-first: bump key transitions to `LOGGER.info`, ship a build, see what the gate actually rejects. Possible fix after diagnosis: drop the 8-block audible gate, keep only the 16-block visible-LOS gate. Don't touch until diagnostic confirms the root cause.
- [x] **Locked-gate enclosure respect (drop-sweep + pursuit)** — ✅ shipped 1.1.98. New ray-cast helper [DangerousPursuitGate.crossesLockedGate](src/main/java/net/wcfcarolina13/GameAI/services/DangerousPursuitGate.java) samples ~1 cell per block along the bot→target line and returns true if any sample is a tracked locked door / fence gate / trapdoor. Wired into both `isLocationSafeForPursuit` (drop-sweep) and `engageHostiles` combat-target filter. Caveat: over-rejects when a wall has BOTH a locked gate AND an unlocked gap (line crosses the locked cell while a legitimate path goes around). Under-rejection was the actual user complaint, so the trade-off is acceptable. Reuses existing LockableBlockService — no new wall semantics.
- [x] **Bed selection bugs (two related)** — ✅ shipped 1.1.100, user-verified in-game 2026-05-17 (bot now places and uses its own bed from inventory when no nearby bed is available). (a) [SleepService.findNearbyBedFeet](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java) now filters beds with `BedBlock.OCCUPIED == true` and sorts the bot's previously-claimed bed (via `BotHomeService.getLastSleep`) first. Defensive late-occupancy guard added to `tryUseBed`. (b) When all nearby beds are filtered (only-occupied case), the existing placement branch fires with an explicit "Nearby bed is taken. Setting up my own." handoff message. Out of scope: bed reservation across mid-night user step-outs; multi-bot claim contention.
- [~] **Pathfinding cache learning isn't measurably improving** — diagnostic surfacing shipped 1.1.101. [NavHazardCache](src/main/java/net/wcfcarolina13/GameAI/services/navigation/NavHazardCache.java) is the actual learning system (per-cell rejection scoring; pathfinders consult via `penaltyFor`). Wiring is correct — recording fires per `applyMovementInput-reject` (903 today's session), pathfinders read the penalty in both [PathFinder.java:152,187](src/main/java/net/wcfcarolina13/PathFinding/PathFinder.java#L152) and [BaritoneStylePathFinder.java:380](src/main/java/net/wcfcarolina13/PathFinding/BaritoneStylePathFinder.java#L380). Penalty hits now log at INFO (throttled 1/s, ≥1.0 penalty); periodic summary every 5 min lists top scoring cells. Architectural caveat: cache helps when alternative routes exist; tight bottleneck doorways (the user's actual stuck cases) have no alternates, so cache doesn't help there even when working perfectly. Out of scope: chat command for on-demand cache dump (`/bot debug nav-hazard`); promotion-event INFO log; tuning `STREAK_PROMOTION_THRESHOLD` after we have real data.
- [ ] **Doorway / pressure plate stuck (still recurring)**: User reports persistent stalls at doorways and pressure plates despite the 1.1.5 → 1.1.16 fix series. Re-read the 2026-04-16 session notes at the top of this file before touching this — the Architectural Concern at line 92 (drop the door-plan state machine entirely; emit door tiles as regular pathfinder waypoints with an `InteractWithDoorGoal`-style observer) is the recommended next move. Discuss with user before attempting; this is a multi-day rework.

### Pre-existing P1 items

- [ ] **Elder Scrolls-style dialogue menu**: Conversation topics, commands, quests
- [ ] **Elder Scrolls-style Journal**: Conversation topics, quests, important information with simple filter search
- [x] **Drop-sweep cobblestone loop**: ✅ shipped. Two-layer fix: (1) [DropSweeper.ensureSpaceForDropSweep:284-286](src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java#L284-L286) only drops items when `chestStoreSucceeded` is true — no offload target → no drop. (2) per-bot TTL self-drop suppression in 1.1.70 (commit `247005e`) — [DropSweeper.java:216-217](src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java#L216-L217) calls `CraftingHelper.isRecentlySelfDropped` to reject pickup of items the bot itself dropped within the last 5 min, killing the inter-sweep reacquisition loop even if guard #1 partially fails.
- [x] **Idle during fast-travel cooldown** ✅ 1.1.204 — `TravelWaitService` (hobbies + auto-travel; offload-existing deferred). Was: When a bot wants to fast-travel but has an active cooldown, it should do useful things while waiting (idle hobbies if enabled, chest offloading to nearby existing chests if disabled), then fast-travel when cooldown expires. Currently the bot just sits idle. For sunset→home specifically, don't build new chests — only use existing ones.
- [x] **Axe retrieval from nearby chests**: When the bot runs out of axes during woodcut, check nearby registered chests (via BotChestRegistryService) for wooden/stone/copper axes — nothing better than copper, nothing enchanted. Take one and continue. Currently the bot just stops or mines with bare hands/wrong tool.
- [x] **Bundle-aware inventory scanning** ✅ 1.1.204 (`InventoryIterator` + extract-first; MiningTool/armor/combat selection still direct-only). Was: Partially shipped. Audited 2026-05-06: lodestone compass ([LodestoneCompassService](src/main/java/net/wcfcarolina13/GameAI/services/LodestoneCompassService.java)), navigation artifacts ([NavigationArtifactService:312](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java#L312)), [HoneyCollectSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HoneyCollectSkill.java), and [BundleService](src/main/java/net/wcfcarolina13/GameAI/services/BundleService.java) + [ArtifactScanner](src/main/java/net/wcfcarolina13/GameAI/services/ArtifactScanner.java) all read `BUNDLE_CONTENTS`. Still raw-slot in food detection (HungerService, isFoodItem, cookAllFoodSync), tool selection (MiningTool, armorUtils, CombatInventoryManager), crafting material checks (CraftingHelper), chest offloading (ChestStoreService). Consider a shared `InventoryIterator` utility that yields both direct slots and bundle contents, so every caller gets bundle support automatically.
- [x] **Escape-with-full-inventory** ✅ 1.1.204 — inventory hypothesis disproved; shipped pillar `reason=` plumbing + escape cooldown when still below surface + hotbar-lock guard; verify in field. Was: Guard/patrol stuck escape (pillar via `ensureAtSurfaceForHobby`) fails when inventory has no room for scaffold blocks — `"pillar recovery placed no blocks"` repeated every ~12s. Bot stuck in 1-block hole with full cobblestone inventory. Consider: temporarily drop a non-essential stack, pillar out, pick it back up. Or: use cobblestone directly as scaffold material.

## P2 — Medium

### Inventory & Storage

- [x] **Furnace offload fallback** ✅ 1.1.205 (`FurnaceOffloadService` + `FuelOffloadPolicy`; verify in field). Was: When no chest is available but furnaces are nearby, dump fuel-eligible items (leaves, sticks, planks) into the fuel slot and smeltable items into the input slot. Especially useful during patrol when bot accumulates items with no chest infrastructure.
- [x] **Craft chest from wood** — ✅ already done. [ToolProvisionService.ensureChest](src/main/java/net/wcfcarolina13/GameAI/services/ToolProvisionService.java) crafts an 8-plank chest when planks/logs are available. Wired into [ChestStoreService.java:588](src/main/java/net/wcfcarolina13/GameAI/services/ChestStoreService.java#L588) (offload path), HuntSkill (camp), FishingSkill.
- [ ] Shift-click, double-click, drag support in inventory UI
- [ ] Quick-action buttons (Sort, Equip Best, Take All, Give All)
- [ ] Bundle packing verification: drop_sweep crafts/uses bundles when inventory is truly full
- [ ] Chest management overhaul: locking/access policy, categorization rules, organization modes

### Follow / Come

- [ ] **Guard/patrol verification**: In-game tests for radius handling, stuck escape in various terrain, interaction with combat and drop sweeps (partially done 2026-03-28: UI radius controls, stuck escape, HUD mode display)
- [ ] **Come tool crafting (verification)**: Verify torches/shovels/pickaxes are provisioned in-world when recipes/materials permit
- [ ] **Follow stability (verification)**: Core planner/backoff/waypoint recovery runtime verification across dimensions/terrain
- [ ] **Deterministic follow/come assertions (verification)**: Run `FOLLOW_COME_ASSERT_RUNBOOK.md` and record pass/fail outcomes
- [ ] **Follow vertical recovery (verification)**: Bot should attempt a nearby projected anchor reroute first, then prompt regroup if still blocked
  - [ ] In-game check: have commander drop into a shaft with a nearby reachable staircase and verify follow reroutes to descend
  - [ ] In-game check: while following, place a 1x1 deep shaft in the movement lane; verify bot sidesteps/stops

### Shelter (Redo Needed)

- [ ] **ShelterSkill refactor**: Split `ShelterSkill.java` into smaller hovel/burrow builder classes
- [x] **ScaffoldService extraction** ✅ 1.1.208 (ladders moved; Woodcut/Hovel pillar variants + FarmSkill.pillarEscape deferred — see handoff)
- [x] **LeafClearService extraction** ✅ 1.1.208 (navigation clearing moved + shared clearLineOfSight; WoodcutSkill's LOS variant deferred)
- [ ] **Shelter resource acquisition flow**: Auto-collect/craft materials by default; allow `ask|wait|manual` to pause
- [ ] **Shelter options parameter**: Investigate what `options` currently controls for hovel/burrow
- [ ] **Shelter chest workflow**: Withdraw/deposit resources and place chests inside planned interior
- [ ] **Burrow "descend-stripmine-descend"**: Restore intended method

### Construction (Blocked — formerly active task)

#### Carried over from the retired `TODO.md` (written 2026-02-25, never revisited)
- [x] **FortifyVillageSkill Phase 2** — 1.1.209: `FortifySharedContext`/`FortifyTowerContext`, `FortifyCleanupProcessor`, `FortifyTowerHelper`; skill 9,560 → 7,093 lines, zero behaviour change (field check Phase 6h).
- [ ] Playtest fortify tower reliability pass (top-block repair verification, scaffold summit step/return, deferred cleanup backoff) on live server and tune retry/backoff values from `latest.log`.
- [ ] Playtest Learning Mode v1 on a live fortify/scaffold scenario (`pillaring_to_roof`) and confirm trace quality + overhead at `detail=balanced`; tune snapshot radius/tick rates if needed.
- [ ] Capture at least one intentional failed pillar/summit demo (`/bot learn stop fail`) to tune generalized scaffold retry/recenter thresholds from trace timing.
- [ ] Playtest fortify tower cavity-ignore flow (`report_cavities` particles + user-guided repair) on live server and adjust thresholds if needed.
- [ ] Generate and map audio for fortify cavity callouts (5 variants) listed in `AUDIO_NEEDED.md`.


- [ ] **Construction parity baseline**: Establish measurable parity for generic schematic builds, shelter/hovel/burrow, fortify wall/patch/moat, and other block-placement paths
- [ ] **Shared construction reach/scaffold**: Standardize feet-based reach, LOS-aware recovery, scaffold stance rules in the generic service layer
- [ ] **Generic schematic bottlenecks**: Remove remaining bottlenecks in `BuildSchematicSkill` and `ConstructionRecoveryService`
- [ ] **Shelter onto shared semantics**: Move shelter/hovel/burrow onto shared reach/scaffold without regressing geometry-specific behavior
- [x] **FortifyVillageSkill Phase 2 refactoring** ✅ 1.1.209 (cleanup/tower) + 1.1.210 (carve, Phase 3 — extraction complete): `FortifySharedContext`/`FortifyTowerContext`/`FortifyCarveContext`, `FortifyCleanupProcessor`, `FortifyTowerHelper`, `FortifyCarveHelper`; skill 9,560 → 6,185. Phase 1 complete (extracted EntombmentHelper, SkillTypes, CleanupHelper, LayoutHelper, EscapeHelper — reduced by ~740 lines)

### Commands / UX

- [x] **Command pruning review** ✅ 1.1.207 — both KEPT (only yaw-setter; only WorkDirection reset); README name fixed
- [ ] In-game check: verify guide/search usability and that actions launched from adjusted counts run with the expected arguments
- [ ] **Base Manager UX polish (carry-forward from 2026-04-20)**: the menu mixes registered bases (yellow `[Base]`) with lodestone compasses (white rows) in one flat list, and `[Home]` means two different things depending on row color. Even the dev got confused. A minimal inline legend landed in 1.1.39 and a "Home & Bases Explained" guide topic was added, but the full fix is: (a) sort rows so registered bases come first, lodestones second; (b) insert section headers (`Registered Bases`, `Lodestone Compasses`); (c) on row hover, show a one-line tooltip describing what clicking `Set Home` will do for that row type; (d) when the user clicks `Set Home` on a row, echo the stored label back in chat (`Jake will treat 'home' as home.`) — so they can immediately verify it took.
- [x] **Named-hostile-mob pacifism (from 2026-04-20 backlog)** — implemented in `aeef62a` (1.1.55). `BotCombatPolicyService.shouldBotAttack` gates engagement; named hostiles still appear in scans so flee fires on damage; per-bot `attackNamedMobs` opt-in toggle reachable via `/bot attack_named_mobs <on|off|toggle> [target]` and the Admin tab "Attack Named Mobs" row. **Manual in-game verification still pending** — see the 6-item test plan in the 2026-05-06 session notes at the top of this file.
- [x] **`CompanionSpellsScreen` cleanup — partial**: Audited 2026-05-06. The legacy screen is **not** fully decommissioned — it's still reached via the dedicated `KEY_OPEN_SPELLS` keybind path in [FrensClient.java:660 → 1578](src/main/java/net/wcfcarolina13/FrensClient.java#L660), the recruit-contact key fall-through after recruitment, and the temporary `-` go-to-spells override (when holding a spell trigger item). `isEyeSpellOnCooldown` / `armEyeSpellCooldown` are also still consumed inside `FrensClient` itself, not just the legacy screen. So the original "delete the file" plan would break the keybind UX. What WAS removable: the dead `openSpellsMenu` method in `BotPlayerInventoryScreen` — defined but never called. Removed in 1.1.68. Open follow-up: decide whether to migrate the `KEY_OPEN_SPELLS` keybind path to also use the unified Spells tab (would let us actually delete the legacy screen) or accept the dual UX as intentional.
- [ ] **Actions-tab Regroup duplicate**: `Regroup` lives in both the Actions tab (`Orders & Travel`) and the Spells tab (Movement). Users may hit the Actions-tab version first; it runs `/bot companion come` which the server then rejects if artifacts are missing. Decide: keep both with a visible "gated" indicator on the Actions copy, or remove Regroup from Actions and leave it in Spells only. Same question for `Return Home` (Actions) vs `Home` (Spells) — distinct actions (`RETURN_HOME` vs `COMPANION_HOME`) so this may just need clearer labels.

### Navigation & Movement

- [x] **Lodestone fast-travel: pre-check mount placement before bot teleport** — ✅ shipped 1.1.124 via a richer fix than originally planned. `TravelMountHandler.evaluateTravel` (the pre-discard pre-flight already used by both lodestone fast-travel and chorus-recall) now attempts `tryTetherAtSourceForSameDim` when the destination is too tight for the mount, instead of immediately refusing. If the mount can be tethered to a nearby fence (or one can be placed), travel proceeds with bot alone and the mount waits at source — same pattern as the existing cross-dim tether. Otherwise refuses with a clearer message ("no fence within reach, no lead, or no spot to place a fence"). Also renamed `TETHERED_CROSS_DIM` → `TETHERED_AT_SOURCE` (semantics now cover both) and documented the load-bearing dismount invariant in `evaluateTravel`'s Javadoc. The fallback `mountPos = safeSpot != null ? safeSpot : ps.dest()` at [NavigationArtifactService.java:1457](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java#L1457) is now unreachable in practice (pre-flight `hasAnimalRoom` ensures `findSafeAnimalSpot` succeeds at recreation time) but kept as a defensive last-resort.
- [ ] Swimming parity (surface and underwater, verify behavior matches survival movement)
- [ ] Boat support (enter, exit, navigate) — partial: [TravelMountHandler](src/main/java/net/wcfcarolina13/GameAI/services/TravelMountHandler.java) handles boat mount/dismount/leashed-rejoin sync (incl. ChestBoatEntity); free-form bot-driven boat *navigation* (steer, paddle to a point) is not implemented.
- [ ] Test fishing from a boat
- [ ] Portal following (Nether, End)
- [ ] Cross-realm teleport command
- [ ] Water-aware pickup (wade/bridge)
- [ ] Edge/hole pickup (hop down safely)
- [x] Add shelves and containers to no-break list — ✅ already done. [ProtectedStructureBlockHelper.isProtectedContainer](src/main/java/net/wcfcarolina13/GameAI/services/ProtectedStructureBlockHelper.java) covers bookshelves (incl. chiseled), all chest variants, barrels, hoppers, dispensers, droppers, decorated pots, crafters, brewing stands, furnaces, blast furnaces, smokers, and all 17 shulker box variants. Wired through `isNeverBreakBlock` and consulted from BotStuckService + MovementService.

### Fishing

- [ ] Verify leaf-block clearing when navigating far from shoreline
- [ ] Verify fishing from higher vertical positions (cliffs/piers)
- [ ] In-game check: trigger `/bot fish` while bot is swimming/submerged and verify it relocates to dry shore before first cast
- [x] **Fishing reach** ✅ 1.1.207 — 12→28, ±3, column pre-filter
- [x] **Water location memory** ✅ 1.1.207 — `WaterSpotMemory` in BotHomeService.WorldData, shared with farming

### Combat & Safety

- [x] Creeper evasion (sprint away when unarmed) — implemented in [BotEventHandler.java:3794](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3794): default flee sprints away within 6 blocks, raises shield within 4.5, with extra `isIgnited()` boost in scoreThreat (line 3603).
- [x] Protected build zones (no-grief areas) — implemented as [ProtectedZoneService](src/main/java/net/wcfcarolina13/GameAI/services/ProtectedZoneService.java) with AABB zones, persisted per-world; consulted by FeedAnimalsSkill, MiningHazardDetector, and others.
- [ ] Fight with teammates
- [ ] In-game check: stand near passive endermen and confirm bot does not face/aggro them; then provoke one and confirm bot can still target it once hostile
- [ ] In-game check: drop bot from lethal height with/without a water bucket (Overworld), verify clutch attempts near impact and no attempts in ultrawarm dimensions — **code shipped** in [BotFallSafetyService](src/main/java/net/wcfcarolina13/GameAI/services/BotFallSafetyService.java) ("Attempts a last-second water-bucket clutch when a lethal fall is detected"); just unverified in-game.
- [ ] Ride sync verification: mount/dismount mirroring across entities — **code shipped** in [RideSyncService](src/main/java/net/wcfcarolina13/GameAI/services/RideSyncService.java); just unverified in-game.
- [ ] Ride sync leashed persistence: tethered after disconnect/rejoin — **code shipped** in [Frens.java:803-809](src/main/java/net/wcfcarolina13/Frens.java#L803-L809) (`trySecureMountBeforeDismount` + `secureLeashedMountOnDisconnect`); just unverified in-game.

### Crafting & Building

- [ ] Craft common items (armor, torches, etc.)
- [x] Crafting helper: report missing items ✅ 1.1.207 (`CraftingRequirementsPolicy`; torches/bed/door/fence + unknown names)
- [ ] Crafting table craft: craft when inputs exist; announce success or missing items in chat
- [ ] Placement: place crafted table/furnace/chest near commander safely
- [ ] Build walls (specified materials, dimensions)
- [ ] Simple 2-person house
- [ ] Block placement primitives
- [ ] Recipe awareness: refuse and explain if commander lacks recipe

### Farming & Survival

- [x] **Hunger-aware task interruption** — ✅ shipped 1.1.102. New helper `HealingService.shouldPauseForStarvation(bot)` returns true iff starving + autoEat fails. Wired into [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java), [CollectDirtSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/CollectDirtSkill.java) (covers MiningSkill), [WoodcutSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java) main loop. Skill bails with `flagManualResume`; user feeds bot then `/bot resume`. HuntSkill / FishingSkill / GrassSeedSkill keep their existing in-skill checks. FarmSkill / Bridge / Shelter / Fortify not yet — main-loop boundaries non-obvious; add when symptom hits.
- [x] Till soil, plant seeds, harvest, replant — implemented across [PlantSeedsSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/PlantSeedsSkill.java), [HarvestCropSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HarvestCropSkill.java), and [FarmSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java) (full pipeline: assess site → till → irrigate → plant → harvest → replant; auto-replanting validated 2026-04-08).
- [ ] Create infinite water source — [FarmSkill.java:332+](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java#L332) detects + uses an existing 2×2 still-water basin, but does NOT yet *create* one when none exists. The "create from scratch" path is still open.
- [ ] Animal husbandry (shear, collect meat, pen animals) — partial: shears used for [WoolSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoolSkill.java) (sheep) and [HoneyCollectSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HoneyCollectSkill.java); auto-hunt collects meat. Breed/pen behavior not done.
- [ ] **Farm underground recovery**: Escape when underground with overhead dirt
- [ ] **Farm chest workflow**: Proactive chest placement/use during farming
- [ ] **Farm irrigation leak patching**: Detection partially shipped — [FarmSkill.java:741](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java#L741) (`irrigationLeakReason`) flags leaks; the *patch* path (replace flowing-water cells with source / plug missing edges) is still open.
- [ ] Hobby verification: flower picking, feed-animals, hobby hunt behavior
- [x] **HealingService cooked food preference**: ✅ Auto-eat now prefers cooked over raw via two-pass search in [HealingService.findCheapestSafeFood](src/main/java/net/wcfcarolina13/GameAI/services/HealingService.java). Raw meats (`BEEF`, `PORKCHOP`, `MUTTON`, `CHICKEN`, `RABBIT`, `COD`, `SALMON`) are skipped on the first pass and admitted on a second pass only if no cooked food is available. Closes the raw-chicken food-poisoning hole and stops the bot from gnawing raw beef next to a stack of cooked beef. Same `cheapest-within-tier` ordering preserved otherwise. See changelog 2026-05-08.
- [x] **Smoker preference for food cooking** — ✅ shipped 1.1.102. New `FurnacePreference { FOOD, ORE, ANY }` enum threaded through `resolveFurnaceTarget`. FOOD prefers SMOKER (rejects BLAST_FURNACE), ORE prefers BLAST_FURNACE (rejects SMOKER), ANY accepts all. Two-pass selection at every step (commander look-at, shared registry, nearest scan, inventory placement). `cookAllFoodSync` always passes FOOD; `startBatchCookInternal` passes FOOD when `foodOnly`. Smoker crafting (logs + cobblestone non-grid recipe) still out of scope — generic furnace remains the crafting fallback.
- [ ] **Fuel acquisition fallback**: If no fuel in inventory, attempt mini leaf-litter collection before giving up on cooking

### Hunting — Multi-Day Self-Sufficiency (Future Phase)

- [ ] **Hunt camp shelter**: Bot builds a small hut with a bed and door at hunting grounds for multi-day hunts
- [ ] **Hunt self-sufficient resource gathering**: Bot gathers wood/dirt/cobblestone for camp building and chest crafting

### Hobbies (new ideas)

- [x] **Walking dogs** — implemented in 1.1.65 as [BotDogWalkingHobbyService](src/main/java/net/wcfcarolina13/GameAI/services/BotDogWalkingHobbyService.java). v1 design choice: opportunistic-only (no detour to find a wolf — fires when bot is within 3 blocks of an eligible sitting wolf during idle time). Sessions 3–10 min, 50% sit-at-home roll, external cancellation via per-tick `wolf.isSitting()` re-read. See changelog 1.1.65 for full notes. Open follow-ups deferred:
  - ✅ Voiced "Going for walkies" / "Who's a good dog?" line on session start — shipped via [BotDogWalkingHobbyService.playSessionStartLine():176-188](src/main/java/net/wcfcarolina13/GameAI/services/BotDogWalkingHobbyService.java#L176-L188), 50/50 between `LINE_WALK_DOGS_GOOD_DOG` and `LINE_WALK_DOGS_WALKIES` ([BotDialogueSounds:908-909](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java#L908-L909)).
  - Multi-dog sessions (currently 1 wolf per bot).
  - Bot-side detour-to-wolf if user wants the bot to actively seek out sitters (currently fully opportunistic per spec).

### Mining & Resource Gathering

- [x] Tree chopping (safe climbing, late drop collection) — implemented as [WoodcutSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java) (also a registered hobby `woodcut`). Mine-from-outside trunk, scaffold ascent, late drop sweep, hazard scanning, sapling replant — all shipped through 2026-04-03.
- [x] Strip mining with safety offset (sand, gravel, lava) — implemented as [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java); torch placement + falling-block guards in place.
- [ ] **Cubic-area clearing skill (or stripmine extension)**: User wants the bot to clear large rectangular volumes near a base — e.g. excavate a 10×4×10 area for a future build. [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java) is line/branch-shaped only and isn't a fit. Could be a new skill `excavate <w> <h> <d>` that takes a corner anchor + dimensions and runs a layered sweep from top-down (avoids drops landing in unmined cells). Reuse [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java)'s safety primitives (torch placement, falling-block guards, hazard detection) where possible. User flagged 2026-05-09 as a follow-up to the protected-zone-override work.
- [ ] Cave/structure detection and reporting
- [ ] Water encounter handling

## P3 — Low

### Multi-Bot Features

- [ ] Per-bot chat behaviors/personas (beyond routing)
- [ ] Broadcast command UX polish (feedback per bot)
- [ ] Shared job coordination (queue fan-out, conflict handling)
- [ ] Resume prompts respect group commands

### Advanced Combat

- [ ] PVP sparring mode
- [ ] Army formations (line, grid)
- [ ] Archer positioning
- [ ] Horse flank maneuvers

### Quality of Life

- [ ] Command queuing (multi-step instructions)
- [ ] Voiced banter variants for follow-adventure lines

### Dialogue / Voiced Lines (backlog 2026-05-04)

New mob-proximity and context-triggered ambient lines. All would extend the existing dialogue pipeline (`PetProximityReactionService` / `CompanionOverheadDialogueService` / `BotDialoguePlayer` / `BotDialogueSounds` + subtitle map in `BotDialoguePlayer.SUBTITLE_MAP` + `DialogueTextMapper.EXACT_MAP`). New OGGs need to be staged + wired through `BotDialogueSounds` constants.

**Cute-animal "can we keep it?" pool** — fires near untamed cute mobs. Candidates: foxes, ocelots, axolotls, pandas, bees, rabbits, parrots, sniffers (already have own line below), turtles. Long cooldown (~5–10 min/bot).

**Pandas** — variant-specific lines keyed off `PandaEntity.getMainGene()` (NORMAL / LAZY / WORRIED / PLAYFUL / WEAK / BROWN / AGGRESSIVE). At minimum:

- Worried → "That panda looks stressed."
- Lazy → reference to flopping over.
- Brown → rare-variant callout.
- Aggressive → wariness line.

**Foxes & ocelots** — "Don't let it near the chickens." Single shared pool, fires when a fox or ocelot is within ~12 blocks AND a chicken is within ~12 blocks of the bot.

**Cats** — literal "Meow." line near tamed cats. Probably a separate pool from the existing tamed-animal-nearby pool (this one isn't a quality assessment).

**Zombified piglins** — "What's up, porkchop?" near `ZombifiedPiglinEntity` (current name; was "zombie pigman" pre-1.16).

**Hoglins** — "If they give us gravel again I'm going on a bacon spree." near `HoglinEntity`. (Reference to piglins bartering gravel.)

**Piglin brutes** — "That one's bigger than the others!" near `PiglinBruteEntity`. (User originally said "hoglin brute" — there's no such mob; this is the piglin brute.)

**Sniffers** — "Dinosaur." or "That's the cutest thing I've ever seen." near `SnifferEntity`.

**Vexes** — "Goblins with wings! Duck and cover!" near `VexEntity`.

**Guardians** — fires near `GuardianEntity` (ocean monuments). Wiki notes the guardian's eye follows the player and the laser charges over 4 seconds, going purple → yellow. Two trigger states:

- Proximity (mere sighting, ~16 blocks): "It's staring right at me." / "I don't like the way it's looking at us."
- Laser-charging (when guardian has begun beam wind-up on the bot or commander): "Why is it glowing at me?!" / "That beam is gonna hurt — move!"

**Elder guardians** — fires near `ElderGuardianEntity`. Rarer/more-emphatic pool: "That one's the boss. We should leave." / "Mining Fatigue incoming, I just know it." (References the iconic Mining Fatigue debuff Elder Guardians inflict.)

**Squids** — fires near `SquidEntity` underwater. Benign tone:

- "Just a squid." (mundane)
- When ink cloud spawns nearby (squid was hit): "...Ew."

**Glow squids** — fires near `GlowSquidEntity` (deep dark water, Y < 30). Curious tone:

- "Pretty." / "It's glowing." (single-word delivery preferred, since it's an "aqua luminescent" rare passive)
- "Grab the ink — that color's rare." (only the glow variant drops glow ink sacs)

**Dolphins** — "Did you see that dolphin?" Fires when a `DolphinEntity` enters the bot's view cone within ~16 blocks.

**Nautilus, untamed** — "Never going near the ocean again." Fires near a wild `NautilusEntity` (or whatever the entity class is named in 1.21.11; per [minecraft.wiki/w/Nautilus](https://minecraft.wiki/w/Nautilus) they're neutral mobs that spawn 1–3 per group in all ocean biomes between Y 38–58, retaliate when attacked, and dash at pufferfish — so the fearful-traveler tone fits).

**Nautilus, tamed** — "You can actually ride one of these?" Fires near a tamed nautilus (pufferfish-tamed, saddle-equipped). Per the wiki, ridden nautiluses grant "Breath of the Nautilus" (oxygen-pause underwater) and have a dash ability — a second optional line could reference the dash: "It just *jumped* through the water!" Confirm the entity class and tamed-flag accessor at implementation time.

**Redstone machines (proximity)** — fires near complex redstone setups. Lines: "Tech-o-no-lo-hee-ah" / "We literally went to hell and back to build this."

- Lowest-impact detection proposal: on the existing 20-tick idle scan, count powered redstone components (repeaters, comparators, observers, pistons, dispensers) with `block.hasComparatorOutput()` or `state.get(Properties.POWERED) == true` in a 5×5×5 box around the bot. Threshold ≥ 4 components AND ≥ 2 distinct block types triggers the pool. 90s cooldown. Skips if bot is already in a pre-classified base structure.

**Mob-crusher detection (anti-cruelty line)** — "Totally humane." / "100% cruelty free." / "There's a special place in the Nether for whoever built this."

- Lowest-impact detection proposal: scan within ~8 blocks for any `BlockPos` containing ≥ 6 living entities of the same passive type (cows, sheep, pigs, chickens, villagers — explicitly **excludes** hostile mobs per user spec, so skeleton/zombie grinders don't fire). Use `world.getEntitiesByClass(LivingEntity.class, smallBox, …)` filtered to passives, grouped by type. Long cooldown (~10 min/bot) since the line is editorial, not a scan-frequent reaction.

**"That's a quality animal."** — ✅ scope down + slow down shipped.

- Pool split landed: [PetProximityReactionService.java:79-87](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L79-L87) defines `ANIMAL_WELL_BEHAVED_LINES` (broad tamed-non-wolf trigger, 90s cooldown) and `MOUNT_QUALITY_LINES` ("That's a quality animal", mount-only trigger via `hasNearbyMountAnimal` at [lines 213-232](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L213-L232) covering tamed `AbstractHorseEntity` / any `CamelEntity` / `LlamaEntity` & `TraderLlamaEntity` as `AbstractHorseEntity` subclasses).
- Cooldown bumped: [line 36](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L36) — `MOUNT_QUALITY_COOLDOWN_MS = 5L * 60_000L`.

## Installer portability (Backlogged 2026-09-04 — only ever tested on the M2 Pro Mac)

Bradley asked whether the installers cater to the user's machine. They detect **platform**, not specs:
- [x] **Pocket TTS installer has no Windows support** ✅ 2026-09-04 (`efd13c7`, `7944410`) — Windows uv/py-launcher/python discovery, `Scripts\` venv layout, WindowsApps stub exclusion, py-launcher false-positive fix. **Still to verify on a real Windows box**: `pocket-tts.exe` console-script name, no-shell process start, `Files.isExecutable` on `.exe`. Original: — `PocketInstaller.uvCandidates/pythonCandidates` search Homebrew, python.org framework, `/usr/local` and `PATH` for `uv`/`python3`; no `uv.exe`/`python.exe`/`py` launcher, no `Scripts/` venv layout. A Windows user with Python installed gets "No Python 3.10+ or uv found". Add Windows candidates + venv `Scripts\python.exe` path, and test.
- [ ] **Piper Windows x64 / Linux x64 paths are pinned (sha256) but untested** — zip extraction on Windows and the `.exe` name, `.so` completeness on Linux. ARM Windows/Linux are explicitly unsupported (screen says so). Needs one run each.
- [ ] **No RAM/cores/GPU detection anywhere** — the Ollama model manager shows a recommended-RAM guide per model but does not read the machine's RAM; Pocket pins `OMP_NUM_THREADS=1`; Piper spawns one process per voice. A cheap win: read `Runtime.maxMemory` / `OperatingSystemMXBean.getTotalMemorySize()` and warn when a model's recommended RAM exceeds it.
- [ ] **Dreamsleeve is Mac-Metal only by construction** — fine, but the engine chooser should grey it out off-macOS instead of letting the user configure a path that can never work.
- [x] README now states all of the above honestly (2026-09-04).

## TTS Latency / System Load (Backlogged 2026-08-25)

Bradley (field round on 1.1.167/168): soul TTS response "quite slow, and seems to bug the
laptop even at low graphics/shader settings" — wants a lighter path tested if quality holds.
Audit: only live engine is Dreamsleeve (Qwen3-TTS 12Hz 1.7B 8-bit, MLX/Metal warm server);
`PiperVoiceEngine` exists in code but no piper binary is installed; `soulVoicePiperBinary`
is empty in live config.

- [x] **Sentence-streaming synthesis** — DONE 2026-08-25 (commit `aefc5d3`): per-sentence
      segments share a groupId; client queues them on one OpenAL source. Field-verify the
      first-word latency drop in-game.
- [x] **Verify LoadGoverner covers synthesis windows** — CONFIRMED BROKEN and fixed same
      commit: scheduler slot freed before the TTS render, so `activeGenerations()` was 0
      during the heaviest Metal window; now includes `SoulVoiceService.activeSyntheses`.
- [x] **Offline engine A/B** — run 2026-08-25 (no game running, so contention-free
      numbers). Piper (en_US-lessac-medium, CPU): ~0.97s/line after a 3s first-load.
      Dreamsleeve warm (Qwen3 1.7B, Metal): ~2.6s/line for ~3.7s of audio (~1.5x
      realtime) — the in-game 8-10s was multi-sentence replies + GPU contention, both now
      addressed (streaming + governor floor). Piper is ~2.6x faster and zero-GPU but a
      generic voice. Samples: `voices/ab-test/{piper,dreamsleeve}-line{1..3}.wav`.
      In-game Piper trial needs NO code: settings.json5 `soulVoiceEngine: "piper"`,
      `soulVoicePiperBinary: "~/.local/bin/piper"` (expanded), `soulVoiceModel:
      voices/ab-test/piper-voice/en_US-lessac-medium.onnx`.
- [ ] Hosted-API offload remains separately backlogged behind the credential security review.

## Multiplayer Voice Muting (Backlogged 2026-08-25)

Voiced-line category muting shipped global-only (settings.json5 mask, `ConfigureVoiceCategoriesScreen`
via the Voice row "Adv…" chip in Bot Control). Deferred multiplayer half:

- [x] **Per-player mute masks** (✅ 1.1.203, 2026-09-04) — on a dedicated server the current design mutes for everyone
      (`playSoundFromEntity` broadcasts). Fix: replace the broadcast in `BotDialoguePlayer.playSound`
      with a per-recipient send loop, sync each Frens client's mask via a small C2S payload, and
      consult it in `VoiceLineMuteService.isMuted(category, viewer)` — the `viewer` param is already
      threaded as the seam. Settings.json5 mask becomes the server-admin baseline.
- [x] **Fix the stubbed config sync** (✅ 1.1.203, 2026-09-04) — prerequisite: `configNetworkManager` /
      `ConfigJsonUtil.configToJson/applyConfigJson` are compile-time no-op stubs, so ALL global
      config (including the existing Voice toggle) is effectively single-player-only today.

## Separate Version Ports (Backlogged — Low Priority)

Shelved 2026-05-06. Not on roadmap unless a long-term contributor commits to maintaining a parallel branch.

- [ ] **1.21.1 backport** — A user requested 1.21.1 compatibility. Real port, not a config tweak: ~10 point releases of yarn/registry/component drift between 1.21.1 and 1.21.11. High-risk surfaces are item components (`DataComponentTypes` reworked in 1.21.2), networking payload codecs, fake-player/`ServerPlayerEntity` constructor signatures, screen handler XP-sync, and any post-1.21.1 vanilla content references (e.g. rideable Nautilus). Approach if revived: fork `backport/1.21.1` branch, freeze scope to core companion + skills (no fortify-village), accept it will lag main. Alternative is Stonecutter/preprocessor multi-version build — more upfront work, sustainable long-term. Don't start without a contributor signed up to maintain it.

## LoadGoverner Improvements (DONE 2026-08-24 — separate repo, `~/pontus/LoadGoverner`, v0.2.0)

Reworked per `~/pontus/LoadGoverner/docs/AUDIT-2026-08-23.md` (commits `b42c9cf`..`aa29f83`);
Bradley had already re-enabled the governor himself. Frens side: `SoulRuntime.activeGenerations()`
(1.1.159, commit `76d4a2d`) — stable cross-mod probe signature, do not rename.

- [x] Re-enable + loud disabled-state warning; state-change-only telemetry; real DEBUG log level (S)
- [x] Escalation hygiene: consecutive-check confirmation (default 2) + single-spike `maxMspt()`
      fast path (`spikeMspt=400` → straight to stage 3) (M)
- [x] Soul-pipeline coordination: transient stage floor (default stage 2, 100-tick hold) while a
      Frens soul generation is queued/active, via reflection probe `FrensSoulProbe` (L)

## LLM Integration (Future)

- [ ] Phase 1+: Core architecture, toggles, identity & memory, routing, performance, social awareness, integration & testing
