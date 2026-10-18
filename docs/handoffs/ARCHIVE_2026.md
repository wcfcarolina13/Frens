# Handoff archive 2026 (superseded)

What this is: the superseded session handoffs, old session notes, the Ralph-loop frontmatter and "Ralph Instructions"
that used to sit in `RALPH_TASK.md`, plus the Ralph loop's `.ralph/progress.md` log. Moved here 2026-10-17 (Ralph
retirement phase 3). Everything here is superseded and kept for history only: open work from these blocks was copied
into `RALPH_TASK.md` ("Recovered deferrals (2026-10-17)" and the backlog), and shipped work is in `changelog.md`.
The file is large: grep it, don't read it whole.

## RALPH_TASK.md YAML frontmatter (as it stood)

```yaml
task: "Backlog lineup 2026-09-03. DONE: 1.1.200, 1.1.201 (memory digest), 1.1.202 (torch-hold diagnostics, CreeperEvasionPolicy, creeper isFusing root-cause fix). NEXT: guided field session on 1.1.202 — ONE merged checklist: docs/testing/FIELD_SESSION_1.1.202.md (protocol: GUIDED_SESSION_PROTOCOL.md); then doorway rework discussion; then ACTION REQUESTS interview."
test_command: "./gradlew build -x test"
```

## Session Handoff 2026-10-17 (1.1.266) — superseded

**State:** main = origin/main @ 1.1.266. Suite 2748 green. 1.1.266 = the sandbox bugs A-G from field phases 6ad-6al
(`docs/testing/FIELD_6AD_6AL_SANDBOX_2026-10-17.md`): no shears on leaves (A), empty forest cleanup bounded and honest (B,
resumable scans, `CleanupScanMemo`), one line for a failed craft plus the table cooldown, pickup count and trailing bot name
(C, C2-C4), relocation walks scale their deadline and keep going while they gain ground, 90 s per attempt (D), a log broken
inside a claim completes (E), unified plans cut before a self-conflicting move (F, cause logged), tree claims cover the trunk
(G). Reviewed by Fable, one fix wave, re-review clean. Field items: Phase 6bf (checklist now 323 open items).
**Next:**
- Sandbox recheck of 6bf if not already done in this session (see the 1.1.266 changelog entry for what was rechecked).
- F route fix: make the unified search refuse a move whose support an earlier move mines (today the walk stops on the
  partial). `canReach` and the PathCompare shadow still use the raw search.
- Minors: C2 chest-pull retries every station call with no planks; a held unplaceable table still gives two lines on a
  strict craft; `MountedLeafClearingService` still shears leaves; a bot named after a material reads as the material.
- Everything in the 1.1.265 block below.

## Session Handoff 2026-10-17 (1.1.265) — superseded

**State:** main = origin/main @ 1.1.265. Suite 2695 green. 1.1.265 = `/bot go_to` plans with the bot's own finder (per-bot
override, then global), and the no-bot `calculatePath` / `canReach` overloads are gone so the gap cannot come back
(`FinderChoiceBypassGuardTest` checks the public entries take a UUID). Sandbox check passed (`tools/sandbox/gototest.py`).
Field items: Phase 6be (checklist now 316 open items).
**Ruling:** `FollowPlannerService` stays as it is. It is the local snapshot steering planner (`FollowPathService` A*,
at most 49 x 49 blocks, on a worker) under every finder, not a finder choice. The choice already applies at the sunset
segment pick. Details and cost in the 1.1.265 changelog entry.
**Next:**
- go_to plans in `server.getOverworld()` whatever the bot's dimension.
- In the 1.1.263 sandbox log, unified sunset probes returned `pathLen=0` or `7` with `next=none` inside 200 ms, so the
  return fell back to a direct follow plan to the far anchor. Worth a scope before Slice D.
- Everything in the 1.1.264 block below.

## Session Handoff 2026-10-17 (1.1.264) — superseded

**State:** main = origin/main @ 1.1.264. Suite 2694 green. 1.1.264 = stuck escape reads collision shapes, not air or
`isSolidBlock` (new pure `StuckCellPolicy`): rescue side cells accept grass, leaf litter and thin snow, the wall nudge sees
head-level blocks, the return-base quick nudge can drop one block, and fences, walls, glass and panes block the backup
path. Built as 1.1.262 and shipped as 1.1.264 because 1.1.263 landed first. Sandbox before/after for all four cases:
`tools/sandbox/stucktest.py wall|clip|pen|lane`. Field items: Phase 6bd (checklist now 315 open items).
**Next:**
- Deferred from 1.1.264: diagonal wall nudge (eye-box geometry), scaffold Phase 3 pillaring up while on a pillar, the two
  off-thread `rescueFromBurial` callers (ReturnBaseStuck async mine escape, MovementService nudge loop).
- On 1.1.263 a backup jump-sprint logged `succeeded, now at 199, 110, 202` from inside a closed stone pocket. Worth a scope.
- `ReturnBaseStuckService` counters clear only on arrival at base, so a later return inherits `backupAttempted` from an
  earlier stuck episode (seen in the sandbox). Worth a scope.
- Everything in the 1.1.263 block below.

## Session Handoff 2026-10-17 (1.1.263) — superseded

**State:** main = origin/main @ 1.1.263. Suite 2671 green. 1.1.263 = the sunset auto-return segment pick and the
`canReach` probe honour the bot's pathfinder (per-bot override, then global) through `PathFinder.probePlan`, held to
the caller's budget on the server thread. Classic probes with Baritone (no budgeted classic entry). Guard test
`FinderChoiceBypassGuardTest` stops direct finder calls outside `PathFinding/`. Sandbox check passed
(`tools/sandbox/sunsettest.py`). Field items: Phase 6bc. 1.1.262 is the parallel escape-code build.
**Next:**
- ~~Does `FollowPlannerService` honour the finder choice?~~ Ruled in 1.1.265: local steering layer, no change.
- ~~`modCommandRegistry` goto calls `PathFinder.calculatePath` without a bot~~ ✅ 1.1.265.
- Slice D, then the 6ad-6al bugs A-G, as in the 1.1.261 block below.
**Needs Bradley:** scope the CLAUDE.md pre-deploy check to the target instance (`pgrep -f "PrismLauncher/instances/1.21.11/natives"`). The any-instance pattern blocks every release while a sandbox test runs. The base CLAUDE.md is hook-blocked for sessions.

Everything in the 1.1.261 block below still applies.

## Session Handoff 2026-10-17 (1.1.261) — superseded

**State:** main = origin/main @ 1.1.261. Suite 2663 green. 1.1.261 = the unified pathfinder is the default and Baritone is
the opt-in fallback. A one-time config migration moves saved Baritone to unified and keeps classic. The command saves all
three choices. The decision rests on the swapped A/B (`docs/testing/PATHFINDER_AB_2026-10-17.md`). Field items: Phase 6ba (checklist now 333 items). Minor: after the first shared-settings save the file stores
`"pathfinder": "unified"` explicitly rather than null, so a later default change would not move that install.
**Next:**
- Field time on the new default.
- Slice D (remove the legacy boolean and bidir) once the default has held.
- Older field phases 6ad-6al: DONE in the sandbox 2026-10-17 (`docs/testing/FIELD_6AD_6AL_SANDBOX_2026-10-17.md`): 25 pass,
  6 fail (bugs A-G there: shears on leaves, slow empty cleanup in forest, two-line craft reply, relocation deadline,
  Spawn-tree toggle false refusal in MiningTool, tree claims keyed on the base block; side finding F unified ASCEND
  with no blocks). Scope them as the next build. Guest and server-op items still need a LAN guest or a dedicated server.
- Audio for "Hey, incoming!".
- Extend the punctuation guard to the JSON resources.
- Scope findings worth a look: escape checks that accept only `isAir` in BotRescueService, no drop-1 in ReturnBaseStuck
  quick nudge, `isSolidBlock` blocking tests there.

Everything in the 1.1.260 block below still applies.

## Session Handoff 2026-10-17 (1.1.260) — superseded

**State:** main = origin/main @ 1.1.260. Suite 2648 green. 1.1.260 = player text without em dashes or semicolons (guard
test `PlayerTextPunctuationGuardTest`). The 2026-10-16 follow-up batch is done: 1.1.256 spawns, 1.1.257 dig-down dawn
climb, 1.1.258 retrieval filter and reload, 1.1.259 ranged pause and woodcut stands, 1.1.260 text. Field checklist sections
1-3 passed in the sandbox. Swapped 22-minute pathfinder A/B done (`docs/testing/PATHFINDER_AB_2026-10-17.md`): unified 274 logs vs
Baritone 257, walk reach 66.7% vs 51.4%, recommend unified as default (Bradley's call). **Next:** the default switch if
Bradley agrees, older field phases 6ad-6al in the sandbox, record audio for "Hey, incoming!" (snowball_probe_incoming has no clip), extend the
guard test to the JSON resources. Everything in the 1.1.259 block below still applies.

## Session Handoff 2026-10-16 (1.1.259) — superseded

**State:** main = origin/main @ 1.1.259. Suite 2645 green. 1.1.259 = woodcut stand memory, ranged hits pause tasks,
cancelled mining INFO. First sandbox pathfinder A/B in `docs/testing/PATHFINDER_AB_2026-10-16.md` (unified ahead, small
sample). Field items: Phase 6az. **Next:** player-text sweep (em dashes, semicolons; scope in the worktree
`.superpowers/sdd/backlog2/scope-text.md`), a longer swapped A/B, older field phases 6ad-6al in the sandbox.
Everything in the 1.1.258 block below still applies.

## Session Handoff 2026-10-16 (1.1.258) — superseded

**State:** main = origin/main @ 1.1.258. Suite 2628 green. 1.1.258 = retrieval fetches only the death's drops, trips keep
their action across a reload, a bot in transit is not auto-spawned back (older bug). Field items: Phase 6ay.
**Next:** skills build (woodcut unstandable stands, ranged hit pauses a task, cancelled mining INFO; reviewed, fixed) as
1.1.259, then the player-text sweep. Everything in the 1.1.257 block below still applies.

## Session Handoff 2026-10-16 (1.1.257) — superseded

**State:** main = origin/main @ 1.1.257. Suite 2611 green. 1.1.257 = dig-down dawn climb-out, shelter exits hold the lock,
skip logs once a night, cliff seal feet first, dead external override removed. Field items: Phase 6ax.
**In flight:** retrieval follow-ups (reload mid-trip, only the death drops; reviewed merge-ready, small fix wave), skills
(woodcut unstandable stands, ranged hit pauses a task, cancelled mining INFO; fix wave for the retrieval sweep), then the
player-text sweep. Everything in the 1.1.256 block below still applies.

## Session Handoff 2026-10-16 (1.1.256) — superseded

**State:** main = origin/main @ 1.1.256. Suite 2606 green. 1.1.256 = global bot spawns retired (spawn `_legacy` purged).
Section 1-3 of the field checklist (Phases 6an-6aw) were run by Claude in the mcp-sandbox over MCP on 2026-10-16: all pass
(`tools/sandbox/section3.py`, `sheltertest.py`, `rettest.py`). Never test in Nirn (Bradley's main world).
**In flight (side builds, isolated worktrees):** polish (dig-down dawn climb-out, skip logs once, cliff head seal, dead
external override), retrieval follow-ups (reload mid-trip, only the death drops), skills (woodcut unstandable stands, ranged
shooters pause a task, cancelled mining not an ERROR), then a player-text sweep (em dashes, semicolons).
Everything in the 1.1.255 block below still applies.

## Session Handoff 2026-10-16 (1.1.255) — superseded

**State:** main = origin/main @ 1.1.255. Suite 2597 green. `docs/testing/BACKLOG_SPEC_2026-10-16.md` is fully shipped
(1.1.252 leash and base commands, 1.1.253 shelters, 1.1.254 config, 1.1.255 death retrieval), every build reviewed by Fable
and sandbox-tested (`tools/sandbox/smoke.sh`, `sheltertest.py`, `rettest.py`). Field items: Phases 6an-6aw pending.
**Follow-ups:** dig-down's dawn exit leaves a short scaffold pillar (teardown "out of reach") and waits ~60 s; a cliff seal can
drop a sand or gravel head block into its own entrance; a reload mid-retrieval strands the bot without the sweep; spawn
`_legacy` retirement; an item-thrower filter for retrieval.
**Needs Bradley:** apply `CLAUDE.md.patch` (in the suspicious-shamir worktree root) to the main checkout's CLAUDE.md.
Builds need JDK 21. In 1.21.11 game rules are snake_case (`/gamerule keep_inventory`).

## Session Handoff 2026-10-16 (1.1.254) — superseded

**State:** main = origin/main @ 1.1.254. Suite 2573 green. Backlog spec items 9-10 shipped (global bot controls retired,
remote-server Bot Controls fixed, mount `_legacy` promotion removed). Field items: Phase 6av.
**Next:** 1.1.255 death retrieval (item 11, built, in Fable review). Everything in the 1.1.253 block below still applies.

## Session Handoff 2026-10-16 (1.1.253) — superseded

**State:** main = origin/main @ 1.1.253. Suite 2561 green. Backlog spec items 6-8 shipped (seal order, shelter persistence,
dig-down never leaves a pit), sandbox-tested with `tools/sandbox/sheltertest.py`. Field items: Phase 6au.
**Next:** 1.1.254 config cleanups (items 9-10, reviewed merge-ready), 1.1.255 death retrieval (item 11, built, in review).
Everything in the 1.1.252 block below still applies.

## Session Handoff 2026-10-16 (1.1.252) — superseded

**State:** main = origin/main @ 1.1.252. Suite 2518 green. Working through `docs/testing/BACKLOG_SPEC_2026-10-16.md` (the
contract, with Bradley's 2026-10-16 decisions). 1.1.252 = items 1-5 (leash release, leash threading and records, base
commands, base rename owner fix, TorchPlacer). Field items: Phase 6at.
**Next:** 1.1.253 shelter follow-ups (items 6-8, built and in review), 1.1.254 config cleanups (items 9-10, built and in
review), 1.1.255 death retrieval (item 11). Builds need JDK 21. Everything in the 1.1.251 block below still applies.

## Session Handoff 2026-10-15 (1.1.251) — superseded

**State:** main = origin/main @ 1.1.251. Suite 2502 green. Dead-code audit phase 2 COMPLETE: every item Bradley approved on
2026-10-15 shipped in 1.1.243-1.1.251 (removals ~19,000 lines, wall-off shelter, combat callouts, /bot base owner, leash
target). 1.1.251 = legacy LLM stack and its libraries deleted, jar 33.3 MB (was 52.6). Builds need JDK 21
(`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`). Field items: Phases 6an-6as pending.
**Backlog from the audit answers:** (1) death-location retrieval mission (Q11, `companionDied*` keys kept for it), (2) per-world
migration of global bot controls, then delete the 1-arg lookups (Q12), (3) mount `_legacy` promotion removal once Nirn has run
1.1.246 or later (Q13), (4) wall-off: prefer dirt without a pickaxe, persistence across reload, (5) dig-down leaves an uncapped
bunker when the cap fails, (6) `/bot base remove|rename` have no admin or owner gate, (7) `forceDismountAndSecure` mutates
entity state on a worker thread, two leash-target removals leave the saved record, (8) TorchPlacer thread per torch.
**Needs Bradley:** Q14 force-dismount (1.1.248 ruling), CLAUDE.md edits in the main checkout (gitignored): build line (drop
`-PaiEnabled`, note JDK 21), replace the "LLM Integration" block with a short Souls block, delete the `Database/` and
`GameAI/RLAgent.java` lines, `FilingSystem/` = config handling, delete the SQLite, OpenNLP, DJL and ollama4j dependency rows,
FortifySkillTypes 15 records, FortifyEscapeHelper 13 methods without `clearImmediateOverheadForEscape`.

## Session Handoff 2026-10-15 (1.1.250) — superseded

**State:** main = origin/main @ 1.1.250. Suite 2527 green. 1.1.250 = legacy LLM chat retired, part 1 (routes, `/bot llm`,
`/bot send_message_to`, both toggles, the /configMan hub and its screens and payloads). Field items: Phase 6ar.
**Next:** part 2 as 1.1.251: delete the dead legacy classes (scope `.superpowers/sdd/run-1.1.245/scope.md` in the
suspicious-shamir worktree, keep list there), the io/github/amithkoujalgi and ai/djl stub trees, the provider/model config
fields and startup probe, then build.gradle: ollama4j, DJL, OpenNLP, sqlite-jdbc, jsoup, commons-compress, commons-math3,
`-PaiEnabled`. Keep Jackson, AiChatOffHintPolicy, LegacyChatAccessPolicy.isAuthorized, ollamaBaseUrl, SharedStateUtils.
Everything in the 1.1.249 block below still applies.

## Session Handoff 2026-10-15 (1.1.249) — superseded

**State:** main = origin/main @ 1.1.249. Suite 2531 green. 1.1.249 = the Q5 wall-off night shelter (design
`docs/testing/WALL_OFF_SHELTER_DESIGN.md`, pure `WallOffPolicy`), sandbox dusk and dawn passed. Field items: Phase 6aq.
**Next:** Q2/Q3 legacy LLM retirement build A (reviewed merge-ready, branch `llm-retire-a`) as 1.1.250, then build B (delete
the dead legacy classes, the stub trees, ollama4j, DJL, OpenNLP, sqlite-jdbc, jsoup, commons-*) as 1.1.251.
**Follow-ups:** wall-off seal order should prefer dirt without a pickaxe; dig-down leaves an uncapped bunker when the cap
fails; wall-off persistence across reload; mount `_legacy` promotion removal after Nirn runs >= 1.1.246.

## Session Handoff 2026-10-15 (1.1.248) — superseded

**State:** main = origin/main @ 1.1.248. Suite 2501 green. 1.1.248 = Q8 combat callouts wired, Q9 `/bot base owner`, Q14 leash
target forgotten after a fence tie or death. Field items: Phase 6ap.
**Next:** the Q5 wall-off night shelter (built and under review on branch `wall-off-rebuild`, 2 commits on b4b89bcb, 28 pure
tests; needs a cherry-pick, the dusk sandbox test, release as 1.1.249), then the Q2/Q3 legacy LLM retirement.
**Needs Bradley:** Q14 force-dismount (see changelog ruling), `/bot base remove|rename` ungated, CLAUDE.md count edits.

## Session Handoff 2026-10-15 (1.1.247) — superseded

**State:** main = origin/main @ 1.1.247. Suite 2472 green. Audit section 3 (all 16 SAFE-DELETE phases) is done. 1.1.247 =
Phases 13-16, Q17 entry-terrain restore, Q5 flee pillar deletions. No new field items.
**Next:** Q8/Q9/Q14 features (scoped), the Q5 wall-off rebuild (Fable design ready), the Q2/Q3 legacy LLM retirement (split
builds, ~10k lines, deps verified). Mount `_legacy` promotion removal waits until Nirn has been loaded on 1.1.246 or later.
**Needs Bradley:** CLAUDE.md (main checkout, gitignored) counts: FortifySkillTypes 15 records, FortifyEscapeHelper 13
methods without clearImmediateOverheadForEscape. The Q14 and `/bot base remove|rename` notes in the 1.1.246 block still apply.

## Session Handoff 2026-10-15 (1.1.246) — superseded

**State:** main = origin/main @ 1.1.246. Suite 2472 green. 1.1.246 = audit Phases 9-10, Q6 nav_mode (Guide topic rewritten
from the real gates), Q7 nav tier payload, Q11 (modeSelected*, botSkins mirror), Q13 (village legacy import, mount `_legacy`
write stopped), Q15, Q16. Field items: Phase 6ao.
**Next:** 1.1.247 Phases 13-16 + Q17 + Q5 deletions (scoped), plus the mount `_legacy` promotion removal (Q13, one build after
the write stopped). Then Q8/Q9/Q14 features (scoped), the Q2/Q3 legacy LLM retirement (split builds), the Q5 wall-off rebuild.
**Needs Bradley:** Q14 as answered names force-dismount and `/bot unmount`, but force-dismount deliberately keeps the leash
target (the held lead is restored on rejoin) and `/bot unmount` does not exist, so leash clearing is wired only where a lead
is really released (tied to a fence, bot death). `/bot base remove` and `rename` have no admin or owner gate.

## Session Handoff 2026-10-15 (1.1.245) — superseded

**State:** main = origin/main @ 1.1.245. Suite 2469 green. 1.1.245 = audit Phases 4-8 (construction, services, provisioning,
smelting dead bodies, ~1,790 lines, ratchet CraftingHelper 3 -> 1), no behaviour change, no new field items.
**Next removal builds:** Phases 9-10 + Q6 nav_mode, Q7 nav tier payload, Q11 (modeSelected*, botSkins mirror), Q13 (village
legacy import, stop writing mount `_legacy`), Q15, Q16 (scoped); then Phases 13-16 + Q17 + Q5 deletions; then Q2/Q3 legacy LLM
retirement (~10k lines, deps verified safe to drop, split across builds); then Q8/Q9/Q14 features and the Q5 wall-off rebuild.
Everything in the 1.1.244 block below still applies.

## Session Handoff 2026-10-15 (1.1.244) — superseded

**State:** main = origin/main @ 1.1.244. Suite 2469 green. Dead-code audit phase 2 continues (side session, build ledger in
the session notes folder; per-build scopes in `.superpowers/sdd/run-<ver>/scope.md` of that worktree). 1.1.244 = Q1 RL and
training (training kept as an admin alias), Q4 dev subcommands (testgear, test_chat_message behind `/bot debug on`), Phase 11,
and `/bot debug`, `/bot emote`, `/bot wizard_tome` registered under /bot again (they were nested under spawn since b5479ada).
Field items: Phase 6an.
**Next removal builds:** 1.1.245 Phases 4-8 (scoped: CraftingHelper ratchet 3 -> 1), then Q2/Q3 legacy LLM chat retirement
(~10k lines incl. ollama4j, DJL, OpenNLP, sqlite-jdbc, jsoup, commons-*; verified no live user; split across builds), then
Phases 9-10 + Q6, Q7, Q11, Q13, Q15, Q16; Phases 13-16 + Q17 + Q5 deletions; Q8/Q9/Q14 features; Q5 wall-off rebuild.

## Session Handoff 2026-10-15 (1.1.243) — superseded

**State:** main = origin/main @ 1.1.243. Suite 2464 green. Dead-code audit phase 2 (removals) in progress, run from a
side session; build ledger in the session notes folder and below. 1.1.243 = audit Phases 1-3 (legacy RAG/SQLite chain, misc
dead files + ExampleMixin entry, block knowledge chain), ~1,560 lines, no behaviour change.
**Planned removal builds (Bradley's answers, audit section 5a):** 1.1.244 Q1 RL/training + Q4 debug subcommands + Phase 11;
1.1.245 Q2/Q3 legacy LLM chat retirement + both toggles; 1.1.246 Phases 4-8; 1.1.247 Phases 9-10 + Q6 nav_mode, Q7
nav tier payload, Q11, Q13, Q15, Q16; 1.1.248 Phases 13-16 + Q17 + Q5 deletions; 1.1.249 Q8 combat hooks, Q9 /bot base
owner, Q14 leash clearing; 1.1.250 Q5 wall-off shelter rebuild. Do not touch PathFinding/* or MovementService unified walk
code (separate field A/B). Everything else in the 1.1.242 block below still applies.

## Session Handoff 2026-10-15 (1.1.242) — superseded

**State:** main = origin/main @ 1.1.242 (overnight run, Bradley asleep). Suite 2464 green. 1.1.242 = the unified
pathfinder (`PathFinding/unified/`, design `docs/testing/NAV_1.1.242_MERGED_FINDER_DESIGN.md`) shipped OPT-IN: default stays
Baritone because a 4-bot A/B had unified reach 46% vs the 61.5% baseline (causes fixed since, not re-measured at length).
Also: audit live-bug fixes B1-B5, B7, B8 (fishing auto-cook, fishing deposit chest, honey, wool, Fortify block-entity
guard, mount streak, detect_blocks), per-bot dig gate (only the bot's own skill/hobby/surface-recovery task can dig).
Field items now 303 (Phase 6am).
**Dead-code audit:** phase 1 done, `docs/testing/DEAD_CODE_AUDIT_2026-10.md`: ~4,100 safe-delete lines in 16 phases, 12
merges, and 17 NEEDS-BRADLEY questions (RL/Q-learning ~2,400 lines, legacy LLM path + cloud clients, dev /bot
subcommands, nav_mode, disabled flee tactics, combat line hooks, quest templates, persisted unused config keys). Phase 2
(removals) waits for Bradley's answers to section 5.
**Next:** (1) Bradley ANSWERED all 17 audit questions (2026-10-15, `docs/testing/DEAD_CODE_AUDIT_2026-10.md` section 5a): run the removal builds (≤5 files each, standing rule: confirm nothing useful is unmerged, update all docs incl. the in-game Guide in the same build) plus the small feature items listed there (wall-off shelter rebuild, combat line hooks, /bot base owner, leash clearing; later: death-location retrieval mission, bot-control migration). (2) A longer unified A/B
(daylight, axes, bots outside the Spawn base, terrain forcing break/place/swim; Phase 6am item 1); if unified wins, the
next build makes it the default and removes the old finders and the toggle (design slice D). (3) Open: woodcut should
blacklist a stand on GOAL_NOT_STANDABLE, ranged mobs don't pause a skill (Eve died to a Bogged), cancelled-mining ERROR
log noise. Session cards: task_43ec92da (merged finder, now largely done) and task_e6032cb8 (audit) can be dismissed or
re-scoped by Bradley.

## Session Handoff 2026-10-15 (1.1.241) — superseded

**State:** main = origin/main @ 1.1.241. Suite 2357 green. 1.1.241 = the small items after 1.1.240: woodcut tree claims
(`WoodcutTreeClaims`, sandbox #19), creeper backoff says a plain line instead of `creeper-fuse` (#18), torch-hold log keyed
on gate not light, `[PathCompareOutcome]` walk outcomes joined by the analyzer, woodcut bounds/envelope per bot. Field items
now 300 (Phase 6al). Log prune LaunchAgent `com.bradley.frens-log-prune` is INSTALLED (daily 11:15, Bradley approved
2026-10-15): Nirn's 239 old logs archived with digests in ~/Library/Application Support/PrismLauncher/frens-log-archive.
**Next:** the merged single pathfinder (now 1.1.242; session card proposed) and the dead-code audit (session card). First
field number for the pathfinder work: walks with an executable plan reached the goal 61.5% (8/13), so the walk executor is
the gap. Add a plan ID shared by `[PathCompare]` and `[PathCompareOutcome]` there.
Everything in the 1.1.240 block below still applies (minus the items shipped here).

## Session Handoff 2026-10-15 (1.1.240) — superseded

**State:** main = origin/main @ 1.1.240. Suite 2337 green. Not on Modrinth. 1.1.240 = multi-step navigation (Bradley's ask
with screenshots, 2026-10-14): both finders plan only executable moves (`PathMovePolicy`), Baritone is the default (one-time
migration), wading through 1-deep water, stuck-cell walk around / break soft blocks / place one dirt step
(`TerrainMovePolicy`, protection strict), failed-route memory, woodcut skips unreachable targets fast, gather radius 6, and
the 1.1.239 survival-latch regression fixed (`SurvivalLatchPolicy`). Field items now 297 (Phase 6ak). Sandbox: Carl 29 logs
from 11 trees in 6 min on the dev build, Baritone p90 109 ms, 0 A* timeouts.
**Tools:** `tools/logs/frens_log_report.py` (run it on any session log first), `frens_log_prune.py` (dry-run only; installing
its LaunchAgent and the first `--apply` on Nirn (archives 239 logs) need Bradley's OK). A/B: `/bot config pathfinder
<baritone|classic|default> <bot>`, `/bot config pathcompare on`. MCP fork: `bot_state`, `blocks`, server time in `players`.
**Next build (1.1.241): the merged pathfinder (Bradley 2026-10-15: "a single pathfinding method that's a combination of the
best of both").** Research in `docs/research/PATHFINDING_RESEARCH_2026-10.md` (Fable): one unidirectional anytime A* in tick
costs (Baritone ActionCosts), break/place/swim as costed moves inside the search, partial-path legs with splicing, per-search
caches behind a `BlockAccess` interface, synthetic voxel benchmark harness with a move simulator. Remove the finder toggle,
GUI option and per-bot override when it ships; keep PathCompare as a regression check. Run a multi-bot A/B session with a
log-analyst agent before and after (creeper-free daylight, bots outside the Spawn base).
**Also open:** sandbox #18 (creeper abort sends `creeper-fuse` to chat), #19 (per-bot tree claims), #21 (2-deep water needs
swimming), torch-hold log spam (133/min), PathCompare reached-goal flag. Everything in the 1.1.239 block below still applies.

## Session Handoff 2026-10-14 (1.1.239) — superseded

**State:** main = origin/main @ 1.1.239. Suite 2262 green. Not on Modrinth (Bradley: not until playthroughs settle). 1.1.239 is
from the 1.1.238 sandbox run (PLAYTHROUGH_1.1.234_SANDBOX.md #12-#17): movement obstruction mining refuses slow blocks
(stone by hand, `ObstructionMinePolicy`, 100-tick cap) and cancels timed-out jobs; woodcut skips trees it cannot reach from a
roofed pocket, exits trapped, caps dry reposition at 4 stands; hunger warned once in chat and the starvation pause checked at
approach loop heads (its "feed me" line was being dropped by the 32-block range gate); surface recovery with no blocks backs
off 30 s to 10 min with a truthful climb check and one owner line; a stopped bot holds still underground. Field items now 293
(Phase 6aj). Sandbox pre-release check passed for #10, #14, #17 (see changelog Field).
**MCP fork (`hardened`):** new `bot_state` (server-side food, inventory, onGround, sky), `players` uses server time,
`screenshot_to_file` restricted to `screenshots/mcp/`. Built, deployed to the sandbox.
**Next build (1.1.240): multi-step navigation (Bradley 2026-10-14, with sandbox screenshots).** From a pond-side gully at
22,63,126 a fresh bot never moved in 7 min: bidirectional A* timed out at 1536 iterations (~7 ms each) on every try, local
escape repeated one impossible 2-high step, woodcut retried adjacent stands and prepareReach after the 4-stand cap. Bradley:
"breaking blocks, scaffolding, and simply walking around are all options a human player would use to reach its destination
with little effort here." Design scope (Fable) in progress: A* speed (#13 campfire check), partial-path waypoints on timeout,
failed-route memory, costed break/place/walk moves, skip the rest of a target after a timeout from an unchanged position.
**Deferred from 1.1.239:** blind walkDirect fallback after no path, A* neighbour passability, hand-dig stair escape, bundle
food in the no-food warning, the gated hunger lines in CollectDirt/StripMine, SkillResumeService vs recovery thread, #10 audio
regeneration (needs Bradley's triage). Everything in the 1.1.236 block below still applies.

## Session Handoff 2026-10-14 (1.1.238) — superseded

**State:** main = origin/main @ 1.1.238. Suite 2204 green. Not on Modrinth. 1.1.238: Spawn-base trees protected from woodcut
by default (Bradley's ruling 2026-10-14, reversing 1.1.229), admin toggle in Admin, World Settings; spawn nudge line; refused
blocks tried and logged once; claim check asks every zone (ZoneOverlapPolicy, Spawn base yields to a granting player zone).
Field items now 288. Sandbox findings #9 fixed, #10 (5 missing voice-line sounds) and #11 (roster empty state says it twice) open. MCP fork
(`hardened` branch) gained health, pause_menu fix, screen_buttons/click_button, type_text/press_key, players, bot_view HUD
hiding, pack metadata fix; command reference in docs/testing/JAVA_MINECRAFT_AUTOMATION.md. Everything in the 1.1.236 block
below still applies (next items, deferrals).
**Unreleased on main:** `ae4cb5fb` (spawn nudge also fires at a protection-driven failure relocation, sandbox #12), tests 2208.
**Next build (1.1.239) candidates, from the 1.1.238 sandbox run (PLAYTHROUGH_1.1.234_SANDBOX.md #13-#16):** woodcut approach
punches stone bare-handed (37 s a block, no drop) and digs the bot into a covered pocket (#14, high); approach planning in
dense forest gets 0 logs in 10 min (#16, high); a fresh bot starves during a long run with no food plan (#15); slow A* in
leafy forest (#13, watch). Also open: #10 missing voice-line sounds, #11 roster empty state said twice.

## Session Handoff 2026-10-14 (1.1.237) — superseded

**State:** main = origin/main @ 1.1.237. Suite 2174 green. Not on Modrinth. 1.1.237 = one fix on top of 1.1.236: the low-roof
branch of `isRecoveryReadySurface` requires the wide near-surface check (`SurfaceReadinessPolicy.lowRoofReady`), so a capped
pit below grade is not "at the surface" and underground survival actually runs (sandbox finding #8). Everything in the
1.1.236 block below still applies (next items, deferrals). MCP fork now on branch `hardened` in ~/dev/minecraft-mod-mcp-hardened
(save_and_quit uses the pause-menu disconnect path, new `health` command with stuck_save/render_idle verdicts).

## Session Handoff 2026-10-14 (1.1.236) — superseded

**State:** main = origin/main @ 1.1.236. Suite 2171 green. Modrinth: 1.1.230 latest published; 1.1.231–1.1.236 NOT published.
Sandbox (`mcp-sandbox`) + hardened MCP fork as in the 1.1.235 block below. The sandbox "New World" holds Jake stuck at
41,62,25 (the finding #7 repro): load it on 1.1.236 by day and he should log `linger: allow — shallow open-sky hole`.

**Shipped in 1.1.236:** shallow open-sky hole climb-out by day (UndergroundLingerPolicy; night/shelter win over the #0
bypass; #8 uses the wide surface sample), dig-down bunker caps stand+2 so it registers, roster scoped to this save with
an "Other worlds (N)" toggle (RosterScopePolicy, singleplayer only). Details, rulings, deferrals in changelog.

**Field checks pending:** 6ah, 6ag, 6af (item 1: open **Nirn first**), 6ac remainder, 6ad, 6ae …  Now 285 items.

**Next (in order):**
1. ~~Aiwan's "Revenant of <name>" husk duplication~~ CLOSED 2026-10-14: Aiwan confirmed the Maze Runner mod spawns them, not a Frens interaction.
2. Deferred from 1.1.236: SHELTER_ACTIVE persistence across reload, join-check commander radius, multiplayer roster payload,
   hand-dig stair escape for a bot with no blocks.
3. MCP fork: render-thread stall when the window is minimized (needs a clear error), get_screen_buttons from mapped code,
   keyboard in-world, per-action control mode upstream PR.
4. Chat action requests. 5. Fresh-install part 2. 6. Bot livestream (Bradley considering Discord screen share).

## Session Handoff 2026-10-14 (1.1.235) — superseded

**State:** main = origin/main @ 1.1.235. Suite 2142 green. Modrinth: 1.1.230 latest published; 1.1.231–1.1.235 NOT published.
**New this session:** Claude can drive Minecraft. Prism instance `mcp-sandbox` + hardened fork of langyo/minecraft-mod-mcp at
`~/dev/minecraft-mod-mcp-hardened` (loopback only, browser/Host refusal, vanilla screenshots, click_px, send_chat,
pause_menu, save_and_quit, set_time, bot_view). How-to: docs/testing/JAVA_MINECRAFT_AUTOMATION.md (log 2026-10-14);
fork/upstream notes: docs/testing/MCP_MOD_IMPROVEMENTS.md. Sandbox findings: docs/testing/PLAYTHROUGH_1.1.234_SANDBOX.md.

**Shipped in 1.1.235:** crafting table placement never blocks itself, bootstrap gather skips protected cells and regathers at
the tree, table-failure retry from a new stand, plain tree chat lines, Questing behind `questingModeEnabled` (default off,
new worlds auto-Admin + roster), `/bot craft` display names. Details in changelog.

**Field checks pending:** 6ag, 6af (item 1: open **Nirn first**), 6ac remainder, 6ad, 6ae … Now 282 items.

**Next (in order):**
1. AiwanChu25 (Discord #general, 2026-10-14): roster lists bots from every world (filter to this save + import option);
   another mod's "Revenant of <name>" husks copy bot gear → duplication; need his mod list (reply drafted, needs Bradley's OK).
2. Sandbox: night shelter dig-down then immediate underground-survival pillar-out (two systems fighting, log 19:40:18-31).
3. MCP fork: get_screen_buttons from mapped code, keyboard in-world, per-action control mode upstream PR.
4. Chat action requests. 5. Fresh-install part 2. 6. Bot livestream (Bradley considering Discord screen share).

## Session Handoff 2026-10-14 (1.1.234) — superseded

**State:** main = origin/main @ 1.1.234. Suite 2112 green. Codex seats still paused (Opus reviewers). Modrinth: 1.1.230 is
the latest published; 1.1.231–1.1.234 NOT published (Bradley: no Modrinth until live playthroughs settle).

**Shipped in 1.1.234 (live playthrough of 1.1.233, docs/testing/PLAYTHROUGH_1.1.233.md #1–#10):** first axe crafted on the
ground before any pillaring (no log→plank scaffold without an axe, hand-gathered dirt-family scaffold, sand/gravel out),
scaffold teardown and doorway clearing at survival speed via MiningTool, forest floor under leaves counts as surface,
per-save keys for zones/locks/bases/World Mode (legacy adopted only by a played save matching >half the legacy base zones:
Nirn), woodcut cleanup stops after two empty scans, plain-sentence results, one crafting-table line, short post-task
returns walk, drop sweep counts right. Details + rulings in changelog.

**Field checks pending — order matters:** Phase 6af FIRST and open **Nirn first** after deploy (its item 1), then the rest of
6af in a new commands-off world, then 6ac item 1 remainder (Guard, Skills/hobby toggles, come/sleep, Crafting History x1/x2),
6ad, 6ae, 6ab … 6b. Now 277 items.

**Deferred:** level-name-keyed stores (chest/furnace/beehive registries, hunt + crafting history, fortifications, mapped
villages, getBotSpawn _legacy), roster lists bots from every world, RL breakBlockAhead + clutter clears + mounted leaf
clearing still instant, isAtSurface strict under canopy, woodcut cleanup async scan off-thread, long automatic return
refusals (combat/cooldown/food) still in chat.

**Next (in order):**
1. Next live playthrough on 1.1.234 (Phase 6af), more findings → 1.1.235.
2. Chat action requests (design interview first). 3. Fresh-install part 2 (AI setup). 4. Bot livestream.
5. Control research: docs/testing/MINECRAFT_CONTROL_RESEARCH_2026-10.md (minecraft-mod-mcp fork to 127.0.0.1+token, client gametests).

## Session Handoff 2026-10-13 (1.1.233) — superseded

**State:** main = origin/main @ 1.1.233. Suite 2011 green. Codex seats still paused (Opus reviewers). Modrinth: 1.1.230 is
the latest published; 1.1.231–1.1.233 NOT published (needs Bradley; copy `.superpowers/sdd/modrinth/publish-1.1.230.sh`).
This session shipped 1.1.231, 1.1.232, 1.1.233 back to back; start the next build in a FRESH session.

**Shipped in 1.1.233:** D8 (Set Home / Go To via BotAccessGate, unowned bots no longer open to anyone, isAllowedToControl
deleted), dead "allow everyone to change skins" toggle removed (config key + wire field kept), selectBestTool blank-avoid
no-op fixed, teardown uses a pickaxe only for tool-required drops, leaves never use shears (saplings still drop), woodcut
cleanup's off-thread tool swap removed, crafting table line no longer says "run /bot place". Details in changelog.

**Field checks pending — order matters:** 6ac item 1 FIRST (single-player buttons; nothing from 1.1.231–1.1.233 has run in
game), rest of 6ac, 6ad, 6ae (4 items, now 270), then 6ab … 6b.

**Deferred:** ToolSelector shears-for-leaves when not preserving, cleanup hand fallback slot 0, MovementService off-thread
tool swaps, BotActions.moveRelative same-cell teleport, D7 non-op zone edit/delete, anyone can map a village / claim an
ownerless wall, typed /bot and SPAWN for owners, resurrection owner clause.

**Next (in order):**
1. After 6ac/6ad results: bot-slot check refuse mode (6ad item 7 clean), any field fixes.
2. Chat action requests (design interview with Bradley first; memory note project_action_requests_next).
3. Fresh-install leftovers. 4. Bot livestream.

## Session Handoff 2026-10-13 (1.1.232) — superseded

**State:** main = origin/main @ 1.1.232. Suite 2007 green (1979 → 2007). Codex seats still paused (Opus reviewers).
Modrinth: 1.1.230 is the latest published. 1.1.231 and 1.1.232 NOT published (needs Bradley; copy
`.superpowers/sdd/modrinth/publish-1.1.230.sh`, JAR >10 MB).

**Shipped in 1.1.232 (host sweep, all 8 of Bradley's rulings D1-D8 as recommended):** `Frens.isServerAdmin`, and the integrated host
passes lock mode, zone wand/UI, base resize (incl. Spawn), /bot soul and /bot learn admin, soul DMs, right-click open of a guest's
bot. Wall grantees run fortify resume/patch/moat (not expand/remove/rename). Ownerless walls say "claim it first". Companion
commands for the exact owner and host (`CompanionCommanderPolicy`). Skins owner-checked (`SkinChangePolicy`, closes any-player-reskins-any-bot).
`ClientAdminCheck` replaces dead reflection (dedicated ops see the Admin tab, Guide admin topics show). Throttle notice.
WARN-only `[bot-access] bot-slot mismatch`. Scope: `.superpowers/sdd/scope-host-sweep-1.1.232.md`. Details in changelog.

**Field checks pending:** Phase 6ac FIRST (its item 1 single-player: nothing from 1.1.231 or 1.1.232 has run in game), then
6ad (7 items, now 266), then 6ab, 6aa … 6b. 6ad item 7 decides whether 1.1.233 flips the bot-slot check to refuse.

**Deferred (reasons in changelog):** D7 non-op zone edit/delete, D8 `isAllowedToControl` unowned→anyone (Set Home/Go To),
anyone can map a village / claim an ownerless wall, typed /bot and SPAWN for owners, resurrection owner clause, D5 refuse
mode, `allowEveryoneSkinChange` toggle now does nothing (relabel or remove), skin "not online" reply precedes owner check.

**Next (in order):**
1. After 6ac/6ad field results: 1.1.233 = bot-slot refuse mode (if no mismatches), D8, `allowEveryoneSkinChange` toggle cleanup.
2. Chat action requests. 3. Fresh-install leftovers. 4. Bot livestream.

## Session Handoff 2026-10-13 (1.1.231) — superseded

**State:** main = origin/main @ 1.1.231. Suite 1979 green (1937 → 1979). Codex seats still paused (Opus reviewers).
Modrinth: 1.1.230 is the latest published. 1.1.231 NOT published (needs Bradley; copy
`.superpowers/sdd/modrinth/publish-1.1.230.sh`, JAR >10 MB).

**Shipped in 1.1.231:** multiplayer non-op owners can use every Frens button and key for their own bot. Typed
`BotActionPayload` → pure `BotActionPolicy` 36-action allowlist (server-resolved bot name, closed arg shapes) →
`AuthorizedBotDispatch` per-call server-thread grant around a synchronous parse+execute. Chat fallback when the server
lacks the payload. Feed [Yes]/[No] moved to `/frens feed confirm|cancel`, hunt target and skill resume dispatch under
the grant, fortify wall edits/status/drift checked against wall ownership and visibility, `fortify list` admin only.
Details, rulings, cost-if-wrong in `changelog.md`.

**Field checks pending:** Phase 6ac (6 items, now 259). Run its FIRST item single-player before anything else: ops and
the host go through the new payload too, nothing has run in game yet. The rest need a LAN guest / second account.
Then 6ab, 6aa … 6b.

**Deferred (reasons in changelog):** companion buttons for non-op owners (`isAuthorizedCompanionCommander` recruiter
or op only), Grant Wall Access (`allowedOwnerUuids`) not honoured by `BaseAccessPolicy.canEdit(WALL)`, post-parse
bot-slot assertion, throttle message, SPAWN for owners, typed `/bot` for owners, BPIS `isAdminUser` reflective check,
legacy ownerless walls refused for non-ops.

**Next (in order):**
1. Host sweep of remaining op-only gates (now includes the companion and wall-access items above).
2. Chat action requests. 3. Fresh-install leftovers. 4. Bot livestream.

## Session Handoff 2026-10-13 (1.1.230) — superseded

**State:** main = origin/main @ 1.1.230. Suite 1937 green. **Codex seats paused** until Bradley says otherwise (plan
downgraded): reviews and scoping go to Opus subagents.

**Shipped in 1.1.230:** one-time woodcut nudge explaining why protected nearby trees are skipped before it walks off
(`WoodcutProtectionNudgePolicy`, clears on server stop). Ruling confirmed by Bradley: player bases keep protecting
their trees. 1.1.229 details in the block below.

**Modrinth/Discord:** 1.1.230 PUBLISHED (version ZwsAVkbE, covers 1.1.227–1.1.230) and announced in #announcements. Notes:
`.superpowers/sdd/modrinth/MODRINTH_CHANGELOG_1.1.230.md`, uploader `publish-1.1.230.sh` (copy + edit for the next version).

**Field checks pending:** Phase 6ab (1 item), 6aa (6), 6z … 6b; checklist now 253. Bradley runs them later.

**Next (in order):**
1. **1.1.231 = multiplayer non-op owners.** Fresh scope done (2026-10-13): `.superpowers/sdd/scope-nonop-owners-1.1.231.md`
   (untracked, gitignored). Design: one `BotActionPayload` + `BotActionPolicy` enum allowlist + a per-call, server-thread
   `AuthorizedBotDispatch` grant that reuses the `/bot` handlers; 4 implementers (A first). SECURITY-SENSITIVE: needs
   Bradley's OK on the grant design before implementing; review must check target hijack (`all`/extra bot names in args,
   global-bot fallback) and the ungated inline catch-all.
2. Host sweep of remaining op-only gates. 3. Chat action requests. 4. Fresh-install leftovers. 5. Bot livestream.

## Session Handoff 2026-10-13 (1.1.229) — superseded

**State:** main = origin/main @ 1.1.229. Suite 1927 green (1887 → 1927). On Modrinth: 1.1.226 only (1.1.227–1.1.229
not published; Bradley publishes).

**Shipped in 1.1.229** (fresh-install #4, #9, #10, #14; details, rulings, cost-if-wrong in `changelog.md`):
- Ordered woodcut with no axe punches logs bare-handed, then crafts table + sticks + wooden axe once there is enough
  wood (`WoodcutAxeBootstrapPolicy`). Hobby runs keep the old refusal.
- Follow resumes after every non-aborted skill. The "Busy with another task" line was `resumeFollowMode` running
  before `TaskService.complete` (follow was never restored after any task). Suspend/resume now silent. Post-task
  underground return skips a following bot.
- Fresh-world Spawn base (server-owned) no longer blocks woodcut. Player bases still do. A* start==goal returns at once.
- `HarmlessToolPolicy`: no durability spent on blocks a tool isn't effective on (axe on dirt/leaves), bridge retract
  picks a tool per block, "axe" no longer matches "pickaxe".
- Drop pickups: nudge needs 2-high clearance (Bob's IN_WALL), idle sweep tracks the real item, idle-only height cap,
  3 s give-up on out-of-reach items, entity-id blacklist (`DropReachPolicy`, `DropStandHelper`).

**Field checks pending:** Phase 6aa (new, 6 items; checklist now 252), 6z, 6y … 6b. Best on a brand-new world.
Fresh-install part 2 (AI setup checklist) still never run.

**Deferred (reasons in changelog):** `BotActions.moveRelative` same-cell teleport (probable IN_WALL root, shared hot
file), `ScaffoldService.teardownScaffolds` tool choice, `WoodcutCleanupSkill` pickaxe fallback + `selectBestTool(bot,
"pickaxe","")` bug, misleading `CraftingHelper:320` "run /bot place crafting_table" line, Baritone finder start==goal,
weapons+food-only inventory mining thrash (pre-existing), woodcut surface-staging cost.

**Next (in order):**
1. Multiplayer non-op owners: typed per-action payloads (`.superpowers/sdd/scope-ui-commands-1.1.227.md`).
2. Host sweep of remaining op-only gates. 3. Chat action requests (woodcut can now be its "get wood" step).
4. Fresh-install leftovers #5, #11, #12, #13, #15. 5. Bot livestream (backlog).

**Needs Bradley:** publish 1.1.227–1.1.229 to Modrinth; the 1.1.226 changelog line "Bots can make their own wooden
axe" is now true from 1.1.229 on (ordered woodcut). (Answered 2026-10-13: player bases stay protected, nudge added 1.1.230.)

## Session Handoff 2026-10-13 (1.1.228) — superseded

**State:** main = origin/main @ 1.1.228. Suite 1887 green (1792 → 1887). On Modrinth: 1.1.226 only (1.1.227 and 1.1.228
not published; Bradley publishes). Discord #announcements post for 1.1.226 is up.

**Shipped in 1.1.228:** every Frens screen and HUD hint fits at 320x240 / 342x328 / 427x240 while 427+ is unchanged
from 1.1.227 (`UiFitPolicy`, inventory scale-to-fit, Guide/roster/recruitment/popups, overhead lines wrap). Details and
rulings in `changelog.md`.

**Field checks pending:** Phase 6z (new, 3 items; checklist now 246), 6y, 6x, 6w … 6b. Fresh-install part 2 (AI setup checklist) still never run.
Test the three sizes by setting Video Settings → GUI Scale with a resized window (see Phase 6z).

**Next (in order):**
1. ✅ 1.1.229 = behaviour from the fresh-install test (#4, #9, #10, #14).
2. Multiplayer non-op owners: typed per-action payloads (`.superpowers/sdd/scope-ui-commands-1.1.227.md`).
3. Host sweep of remaining op-only gates. 4. Chat action requests. 5. Bot livestream (backlog).

**Needs Bradley:** publish 1.1.227/1.1.228 to Modrinth (mention the REI/EMI/JEI note); OK to fix the 1.1.226 changelog
line "Bots can make their own wooden axe".

## Session Handoff 2026-10-13 (1.1.227) — superseded

**State:** main = origin/main @ 1.1.227. Suite 1792 green (1745 → 1792). Built from the fresh-install test of 1.1.226
(`docs/testing/FRESH_INSTALL_1.1.226.md`, 15 findings). 1.1.226 is LIVE on Modrinth (version BkS087B5); the seven old
1.1.0/1.0.6 uploads are archived; project summary, description, Issues link updated; GitHub Issues enabled.
1.1.227 is NOT on Modrinth yet (Bradley publishes).

**Shipped in 1.1.227** (details, rulings, cost-if-wrong in `changelog.md`):
- The singleplayer host can use Frens with Allow Commands OFF (`HostPermissionPolicy` in `Frens.hasBotCommandPermission`,
  World Mode chooser, Admin tab). LAN guests no longer pass two `!isDedicated()` host tests. AI-chat-off one-time notice.
- Carpet-free `/bot stop` (`CarpetCommandPolicy`), spawn feedback reaches the player (`SpawnOutcome`), one-line stop,
  `chart_path_to_block` off the server thread, online-mode async spawn handled (PENDING + deferred init + in-flight guard).
- Roster hides present bots, `-` key hints both meanings, first-meeting greeting, hint below the tutorial toast.

**Field checks pending:** Phase 6y (new, 3 items; checklist now 243). Also 6x, 6w, 6v … 6b. **Fresh-install test part 2** (Ollama on, AI setup
checklist) was never run; the `frens-fresh` Prism instance is kept for it. Commands-off walkthrough should be re-run on 1.1.227.

**Next (in order):**
1. **1.1.228 = layouts at the default window width** (~427 scaled px): Guide detail card + search, Admin tabs, bot
   inventory screen, "Looking at X" hint bar, overhead greeting text (FRESH_INSTALL #8).
2. **1.1.229 = behaviour from the fresh-install test:** woodcut from an empty inventory (punch tree → planks → axe) and the
   wrong "Busy with another task" line (#4), scaffold cleanup tool choice (#9), bots hurting themselves in tight spaces and
   stuck on unreachable drops (#10), slow woodcut start (#14).
3. **Multiplayer non-op owners:** typed per-action C2S payloads authorized by `BotAccessGate.permitsByName` (scope:
   `.superpowers/sdd/run-1.1.227/scope-ui-commands.md`, kept), plus the server-side re-dispatches that still need op
   (`BotFoodGivingService` feedconfirm, `HuntablesNetworkManager:205`, `SkillResumeService:303`).
4. Host sweep of remaining op-only gates (zones, bases incl. server Spawn base `canEditBase`, skins, lock mode, soul DM lane).
5. Chat action requests (was 1.1.226 → now after the above). Bot livestream (backlog P2).

**Needs Bradley:** publish 1.1.227 to Modrinth; OK to fix the published 1.1.226 changelog line "Bots can make their own
wooden axe" (needs planks + sticks).

## Session Handoff 2026-10-13 (1.1.226) — superseded

**State:** main = origin/main @ 1.1.226. Suite 1745 green (1738 → 1745). Fix-only build from a Discord bug report.

**Shipped in 1.1.226** (details, rulings and cost-if-wrong are in `changelog.md`):
- **Singleplayer "server is full" lock-out** (AiwanChu25, Discord #general). Saved bots auto-spawned at SERVER_STARTED
  filled the integrated server's fixed 8 slots before the host's login was checked. Now `AutoSpawnPolicy`: integrated
  servers defer auto-spawn to the first real player's JOIN; every auto-spawn keeps one slot free; skipped bots get an
  ops/host chat summary.

**Open on this report:** the reporter says brand-new worlds failed too, which the per-world key should prevent.
Suspects: global `config/frens/` sidecars (`sleep_logoff_state.json`, `pending_travels.json`), legacy level-name-only
keys in `bot_world_state.json`. Asked the reporter for `latest.log`, `bot_world_state.json`, `settings.json5`.

**Field checks pending:** Phase 6x (new, 2 items; checklist now 240). Also 6w, 6v, 6u, 6t, 6s, 6r, 6q, 6p, 6o, 6b–6n.

**Next (Bradley's order):**
1. Modrinth publish of 1.1.226 (Bradley asked 2026-10-13), with an LLM-beta disclaimer and the Discord invite
   https://discord.gg/eQJbWW4HUJ (never expires). Changelog draft: `.superpowers/sdd/modrinth/MODRINTH_CHANGELOG.md`.
2. **1.1.227 = chat action requests.** Scope: `.superpowers/sdd/SCOPE-action-requests-1.1.224/README.md`.
3. **Bot livestream** (backlog, P2 below): scope "how do bots behave with no owner nearby" first.

## Session Handoff 2026-09-26 (1.1.225) — superseded

**State:** main = origin/main @ 1.1.225. Suite 1738 green (1662 → 1738). This is the onboarding build: Frens works
without Ollama, and turning on AI companions takes one checklist. The last public Modrinth release is still 1.1.1.
DEPLOYED to all three Prism instances (2026-09-26; 27 AiSetup* classes present in each mods-dir JAR).

**Shipped in 1.1.225** (details, rulings and cost-if-wrong are in `changelog.md`):
- **"Set up AI companions" checklist** (`AiSetupChecklistScreen`, from the Guide's AI setup button and Bot Controls'
  Setup… chip). Six steps: Ollama → model (with RAM guidance) → Soul Chat on → enable each bot → test chat → voice.
  It is server-authoritative (`AiSetupNetworkManager`): op-or-host for server-wide actions, so a cheats-off
  singleplayer host no longer needs `/bot`; bot owners can enable their own bots.
- `/bot soul enable` names what still blocks a reply. The Soul Chat tooltip says it is only the world switch.
- **Multiplayer:** the model and voice-engine screens act on the server when connected remotely. Soul Chat/Voice
  saves reload the runtime. A rejected non-op save tells the player.
- **Model-free first run:** first-run text; no false "Processing your message"; "Ollama is not reachable!" is a
  status, not a model; a missing configured model can be re-downloaded; "Largest option" label.
- **1.1.224 leftovers:** scene privacy is carried from what the prompt admitted (the race is closed). Server logs
  hold no private or DM text.

**Field checks pending:** Phase 6w (new, 9 items; checklist now 238; 2 need a second account). Also still pending:
6v, 6u, 6t, 6s, 6r, 6q, 6p, 6o, 6b–6n. Run `/bot config diagnostics on` first.

**Next (Bradley's order):**
1. **Clean-install smoke test** of both journeys: no Ollama, then AI setup via the checklist. Standard jar, fresh
   Prism instance. NEEDS BRADLEY: permission to create the instance. Then update
   `.superpowers/sdd/modrinth/MODRINTH_CHANGELOG.md` and hand Bradley the jar + changelog. He uploads it himself.
   NEEDS BRADLEY: the TTS engine and voice names for the dialogue-clip attribution line (still a placeholder).
2. **1.1.226 = chat action requests.** Scope: `.superpowers/sdd/SCOPE-action-requests-1.1.224/README.md`.

**Deferred:** as listed in the 1.1.225 changelog entry (remote model pulls and voice installs on the server host, a
voice-preset picker, a persona picker, auto-enable on spawn, dry-run generation), plus the 1.1.224 and 1.1.223
deferrals below.

## Session Handoff 2026-09-25 (1.1.224) — superseded

**State:** main = origin/main @ 1.1.224, DEPLOYED to all three Prism instances (2026-09-25; 13 new policy classes present, VectorExtensionHelper absent in each mods-dir JAR). Suite 1662 green
(1551 → 1662). This is the Modrinth publish-hardening build. The last public Modrinth release is still 1.1.1.

**Shipped in 1.1.224** (details, rulings and cost-if-wrong are in `changelog.md`):
- **Bot access control.** `BotAccessPolicy`/`BotAccessGate`: owner/op/host only, registered fake bots only.
  - Inventory screens, including `canUse` re-checked every tick, with a remote-spell exception.
  - Navigation return prompts need a server offer (`NavigationOfferPolicy`); guidance and recall are authorised.
  - Wall, village and base remove/rename resolve the entry type first (`BaseAccessPolicy`); the hunt packets are gated.
  - Zones follow base visibility, capped at 16 per viewer.
- **DM privacy.**
  - PlayerMemory is tagged PUBLIC/PRIVATE; `SoulPrivacyPolicy` handles the alone-on-server exception.
  - Privately seeded scenes stop per line if someone else could hear, mark their transcript lines `privateTo`, and
    derive no open threads, peer stances or side-channel effects.
- **No native sqlite-vec/vss.** Nothing ever needed it (Java cosine UDF). No startup NLP downloads.
- **Legacy chat.** Ownership is checked on every route, and FunctionCallerV2 confirmations re-authorise when consumed.
- **Preference packets.** Unchanged values are dropped, changes are rate-limited, and saves are batched.
- **Permission predicate.** It now resolves OWNERS via the MappingResolver. This was a live bug: bot sources probably
  ran with NONE.
- **Housekeeping.** `fabric.mod.json` issues/homepage links and `conflicts: ai-player`; `/bot config diagnostics
  on|off` (routine follow logs at DEBUG by default); config title; the startup property dump is removed.

**Rollback warning:** a jar older than 1.1.224 fails to load party transcripts that contain `privateTo` records.
Group scenes then return INTERNAL until `/bot soul reset`.

**Field checks pending:** Phase 6v (new, 6 items; checklist now 229; 3 need a second non-op account). Run `/bot config diagnostics on`
first, or the follow-log greps in 6u and earlier won't match. Also still pending: 6u, 6t, 6s, 6r, 6q, 6p, 6o, 6b–6n.

**Next builds (PROPOSED 2026-09-25; Bradley to confirm the order):**
1. **1.1.225 = onboarding** (`.superpowers/sdd/modrinth/audit-onboarding.md`).
   - Blockers:
     - The AI-companion activation journey dead-ends. The Bot Controls Soul Chat toggle alone does nothing;
       `/bot soul enable <bot>` is undiscoverable and reports success while the global switch is off.
     - Multiplayer: model and voice picks save locally with no server runtime reload; a non-op save is rejected with
       only a WARN.
   - Planned fixes: a "Set up AI companions" checklist screen (Ollama detected → model pulled → souls on → bot
     enabled → test chat → voice); a first-run "no Ollama required" message; the "Ollama is not reachable!" entry is
     removed from the model list; the false "Processing your message" is removed; a configured-but-missing model can
     be re-downloaded.
   - 1.1.224 leftovers (admin-only log exposure; banter seed/removal race) ride along.
2. **Clean-install smoke test** of both journeys in a fresh Prism instance (ask Bradley before creating it), then
   the Modrinth upload. The draft changelog is `.superpowers/sdd/modrinth/MODRINTH_CHANGELOG.md`; the clip
   attribution (engine and voices) is still needed from Bradley.
3. **1.1.226 = chat action requests.** Scope: `.superpowers/sdd/SCOPE-action-requests-1.1.224/README.md`.

**Deferred:** as listed in the 1.1.224 changelog entry, plus the 1.1.223 deferrals below.

## Session Handoff 2026-09-25 (1.1.223) — superseded

**State:** main = origin/main @ 1.1.223, DEPLOYED to all three Prism instances (2026-09-25 22:40; javap-free check: 7 new
policy classes present in each mods-dir JAR). This also ships the never-deployed 1.1.222. Suite 1551 green (1461 → 1551).

**Shipped in 1.1.223** (details, rulings and cost-if-wrong are in `changelog.md`):
- Crafting window search and sort (`CraftingListPolicy`).
- Jake stuck at the pressure-plate fence gate: follow keeps door handling when stalled beyond 6 blocks
  (`FollowDoorPriorityPolicy`, movement stagnation only). The summit climb lock only goes toward a higher commander,
  with candidates filtered before the pick (`VerticalLockAcquirePolicy`). The vertical-lock forced-door traverse no
  longer sleeps on the server thread. A WARN tripwire names remaining `nudgeTowardUntilClose` server-thread callers.
- Redstone doors: never close a powered or plate/trigger-adjacent door, and no self-cooldown on already-open doors;
  ordinary doors still get closed behind (`RedstoneDoorPolicy`). Door logs carry `open= powered= controlled=`.
- Craft accepts `stone_axe` and similar names, and says which ingredients are missing (`CraftRequestNamePolicy`,
  `ToolCraftShortfallPolicy`).
- Chat can no longer throw `NoClassDefFoundError` in the non-AI JAR (legacy NLP and `LLMOrchestrator` guarded).
- Scene smoothing step 1: `OwnerAddressPolicy` vocative cut, seed hierarchy plus support rotation, quote strip,
  quoted `##FRENS` dropped, drop and seed diagnostics.
- Codex (Astra/Sol) did the log review, batch review, re-review, redstone implementation and two fix commits.
  Codex's sandbox cannot run Gradle or write `.git`, so the controller builds and commits their edits.

**Field checks pending:** Phase 6u (new, 7 items; checklist now 223), plus 6t (now actually deployable), 6s, 6r, 6q, 6p, 6o and 6b–6n.

**Next builds (Bradley's order, 2026-09-25):**
1. **1.1.224 = Modrinth publish-hardening.** The last public release is 1.1.1 (2026-04-14, commit 64a78e73).
   Audits: `.superpowers/sdd/modrinth/audit-readiness.md` and `audit-multiplayer.md` (local, untracked).
   - Blockers:
     - `GuideInventoryNetworkManager:50` opens ANY online player's inventory (proximity-only check).
     - `SpellNavigationNetworkManager:62/:92` has no ownership or pending-offer check and accepts human targets.
     - DIRECT-conversation digests enter publicly audible group-scene prompts (`SoulMemoryDigestService:87` →
       `SoulGroupPromptAssembler:378`).
     - Startup downloads and loads sqlite-vec/vss natives without consent or a hash, and strips macOS quarantine
       (`Frens:713-716`, `VectorExtensionHelper`) — ✅ closed in 1.1.224 (native loading removed).
   - Cheap should-fixes:
     - The zone list leaks hidden bases (`ZoneNetworkManager:184`).
     - Wall delete/rename (`BaseNetworkManager:702`) and foreign hunt config (`HuntablesNetworkManager:77`) are not
       authorised; zone subscriptions have no cap.
     - A config save runs on every preference packet (`Frens:684`).
     - The legacy LLM chat route has no sender ownership check.
     - Housekeeping: `fabric.mod.json` still accepts ~1.21.10; routine follow INFO logs should move to DEBUG, and
       the 246 obsolete `ai-player:` sound IDs need handling; add an issues/contact link; replace the
       "AI-Player 1.0.5.2" config title; remove the startup system-property dump.
   - Dialogue clips: 1,523 files = 683 lines × takes. Bradley says they are synthetic TTS with redistributable
     licences, so add an attribution line; ask him for the engine and voice names.
   - Player release notes are drafted in `.superpowers/sdd/modrinth/notes-part1.md` and `notes-part2.md`; merge
     them for the Modrinth changelog. Bradley uploads it himself.
2. **1.1.225 = chat action requests** ("Jake, make an axe" → plan → offer → yes → multi-step run). The
   implement-ready scope is in `.superpowers/sdd/SCOPE-action-requests-1.1.224/README.md`.
3. Later: scene smoothing step 2 (scene boundaries in history), redstone step 2 (use plates and buttons, iron doors
   with triggers), and self-directed goals (proposer-only).

**Deferred, with reasons:**
- Return-base door/escape server-thread traverses (BEH ~3603/~3630): let the tripwire name them first.
- Defensive-aid tick freeze (`BotMutualAidService:558`): the 1.1.222 deferral 3, still open.
- Non-leather armour crafting.
- The raw "Crafted 1 stone_axe" label.
- `FoodConsumptionConfirmationService.tryHandleConfirmation` has no caller; it rides 1.1.225's yes/no router.
- The missing `snow_golem_ammo` and other voice clips.
- The permission reflection `ClassNotFoundException` at startup.
- The Pocket installer's pre-wait EOF read, which can hang.

## Session Handoff 2026-09-25 (1.1.222) — superseded

**State:** main = origin/main @ 1.1.222 (pushed 2026-09-25; NOT deployed: the game was running at release. Deploy per
CLAUDE.md "JAR Deploy Safety" once it is closed, then delete this note).
Suite 1461 green (1434 → 1461). One build: the 1.1.221 deferrals 2 and 3. Done in a worktree branch and fast-forwarded
into main.

**Shipped in 1.1.222: the server tick no longer walks to make room or to reach a crafting table.**
- `CraftingHelper.ensureCraftingStation` on the server thread uses only a table in reach, or places one from inventory
  on a ready adjacent cell. It never walks, carves (the 6 s `mineBlock().get` freeze), nudges or repositions.
  - Covers every tick root: the idle tick's four craft branches, `/bot cook` and auto-cook (`craftFurnace`), and
    RideSync's lead, saddle-stick and fence crafts.
  - Worker callers are unchanged.
  - Pure `CraftingStationPolicy`, with a rate-limited decline line:
    `craft-station tick-side: no table in reach for <bot>, not walking (reason=…)`.
- MutualAid dropped-food and aid-recipient make-room is drop-only (the 1.1.221 `ee67be61` precedent).
  - Gear checks the donor has gear before the recipient drops.
  - A flower never makes a full bot drop.
  - A failed drop backs off 10 s per (bot, kind) (`MutualAidMakeRoomPolicy`).
- Scoping: walks on the server thread could never succeed (the sleeping thread is the one that moves the bot), so each
  one was a freeze followed by a failure. The DropSweeper root in the old deferral was wrong: every sweep caller is a
  worker.
- Details, rulings and deferrals are in `changelog.md`. Scope and review notes (local):
  `.superpowers/sdd/SCOPE-tick-walks-1.1.222/`.

**Rulings made on Bradley's behalf (cost if wrong in the changelog):**
- In-reach only on the tick (Design A), rather than moving the idle crafts to a worker.
- MutualAid make-room is drop-only.
- A flower never makes room.
- Gear checks the donor first.
- The tick-side vertical scan is ±5.
- A blocked in-reach table fails instead of getting a second table beside it.

**Field checks pending:** Phase 6t (new, 7 items; `Can't keep up!` is the freeze detector), plus 6s (do its first item
first), 6r, 6q, 6p, 6o and 6b–6n. The checklist is now 216 items.

**Deferred (same class of bug), with reasons:**
1. `SmeltingService.resolveFurnaceTarget` `Thread.sleep(150)` (SS:~810/~831) on `/bot cook` and the auto-cook tick.
2. A server-thread craft with a full inventory: `distributeOutput` / `craftWithPlanks` / `ensurePlanksFromLogs` →
   `offloadCheapItemsToNearbyChest` → deposit walk or `placeChestNearBot`. Same fix shape; needs its own scope.
3. `BotMutualAidService.processDefensiveSupport` → `approachBot` / `nudgeTowardUntilClose` (~:547/:551): a 1.6–2.4 s
   tick freeze when a hostile is near an ally. Needs a one-impulse or worker design.
4. A WARN-only `isOnThread()` tripwire in `MovementService.execute` / `nudgeTowardUntilClose`, as a field instrument
   for any remaining site.
5. The MutualAid recipient's inventory is mutated from the donor's tick while its own skill worker may run
   (pre-existing).
6. `dropCheapStackForSpace` drops the LARGEST stack when nothing is cheap, and the dropped stack can be re-picked
   after 2 s. Needs a cheap-only mode or a longer pickup delay (a CraftingHelper signature change).
7. A flower keeps targeting a full preferred recipient (cosmetic).
8. Dead code: CH `tickFollowStation`, `stashInChest` with its `placeChestNearBot` overload, and the unreachable
   "already have a table" block.
9. A server-thread chest placement miss logs only at DEBUG.

**Deferred from 1.1.221 / 1.1.220:** unchanged (items 1 and 4–10 of the 1.1.221 block below; its items 2 and 3 are
closed by this build).

**Next autonomous candidates:**
1. Deferrals 1–3 above: the remaining tick-thread sleeps and walks.
2. Supplies Phase 4 (needs the WaitMode/need-bundling ruling).
3. Egress follow-ups.
4. Addressee deferrals.

**Needs Bradley:** unchanged from 1.1.221:
- the guided field session (6b–6t);
- the food reserve of 16 as the survival cutoff;
- the WaitMode-through-`ensure*` ruling;
- the doorway rework;
- the ACTION REQUESTS interview;
- Bob's TTS sample;
- `OLLAMA_NUM_PARALLEL`.

---

## Session Handoff 2026-09-25 (1.1.221) — superseded

**State:** main = origin/main @ 1.1.221 (pushed; deployed to all three Prism instances 2026-09-25, game closed). Suite 1434
green (1280 → 1434). One build, one item: supplies Phase 3. Done in a worktree branch and fast-forwarded into main.

**Shipped in 1.1.221: companion supplies Phase 3.** Every AUTOMATIC chest→bot withdrawal goes through
`SupplyWithdrawals.withdraw`: allowlist, reserve, and the owner's Allow once / Always / No, or an Always permission.
- Sites:
  - ToolProvisionService: the idle wooden fallback, saddle, lead, fence and leather, and chest tool retrieval for
    Woodcut and Durability.
  - Harvest seed restock.
  - CraftingHelper material pulls, including `/bot craft`.
  - MutualAid chest food.
- HuntSkill's container pulls are deleted (dead or off-thread no-ops).
- Owner-initiated moves stay outside: `/bot withdraw`, Quick Fetch, storage Collect.
- `SupplyBypassClosedTest` ratchets every file that obtains a world container. `SupplyEntryPointTest` (formerly
  `SupplyDormancyTest`) pins the facade as the only caller of request/transferNow.
- New: `/frens supply revoke all`. Legacy (pre-1.1.219) bot chests now prompt instead of being refused.
- javap-verified: `World.getBlockEntity` returns null off the server thread. Worker-thread chest reads before 1.1.221
  saw nothing, so prompts during skills and `/bot craft` are NEW behaviour.
- Details, rulings, deferrals: `changelog.md`. Scope notes (local): `.superpowers/sdd/SCOPE-supplies-phase3/`.

**Rulings made on Bradley's behalf (cost if wrong in the changelog):**
- Legacy chests prompt.
- Always needs no owner online.
- The policy is the single source of truth: no saddle, lead, iron and better, or raw meat is auto-taken.
- No starvation carve-out.
- `/bot craft` and `/bot harvest` pulls count as automatic.
- Only Woodcut start and Harvest wait for an answer.
- Owner-within-32 now gates autonomous tool retrieval unless the chest has an Always.

**Field checks pending:** Phase 6s (new, 10 items; do its first item first: mark your supply chest Always), 6r (1.1.220
Bot Storage screen owner-only), 6q (its legacy-chest line is superseded), 6p, 6o, plus 6b–6n. The checklist is now 209
items.

**Deferred (Phase 3 leftovers), with reasons:**
1. Worker skills can't craft from chest materials. There is one prompt per bot, so a plank ask blocks the tool ask.
   Fixing it needs WaitMode through `ensure*`/`craftGeneric` plus need-bundling, which is Phase 4 and needs a ruling.
2. `ensureCraftingStation` walks on the calling thread, including the idle tick (pre-existing).
3. BotMutualAidService `tryMakeSpaceForNearbyDroppedFood` (~:1174) and `ensureInventorySpaceForAidRecipient` (~:1232)
   make room by walking on the tick (pre-existing).
4. `grantableEstimate` works per chest half, so it under-counts double chests.
5. Three "other half of a double chest" helpers could be one.
6. CraftingHelper pull hint maps aren't cleared at stop (bounded wall-clock hints).
7. A double chest whose other half is unloaded reads as unreadable (skipped for that pass).
8. A grant-covered ask while the owner is away is still refused (only Always skips the owner-range check).
9. The `/bot stop` fix for the chest tool search (read `hasActiveTask` once before the loop) is verified by reading only;
   its test pins the pure helper, not the wiring.
10. Pre-existing latch race: after `/bot stop`, a later `beginSkill` for the same bot clears the abort latch, so a search
    still inside a hop can resume. Closing it needs the captured ticket's cancel flag (a TaskService accessor).

**Deferred from 1.1.220 (Bot Storage owner-only):** unchanged, see the 1.1.220 block below.

**Next autonomous candidates:**
1. Supplies Phase 4: the storage-room walkthrough, need-driven armour requests while following, and the
   WaitMode/need-bundling ruling above.
2. Egress follow-ups: outside→inside entry routing, and Fortify onto the shared helper.
3. Addressee deferrals.

**Needs Bradley:**
- Guided field session (6b–6s).
- Food reserve 16 as the survival cutoff (early game: a bot can't eat from a chest holding ≤16 bread).
- The WaitMode-through-`ensure*` ruling for Phase 4.
- Doorway rework decision.
- ACTION REQUESTS interview.
- Bob's TTS reference sample.
- Whether to lower `OLLAMA_NUM_PARALLEL`.

---

## Session Handoff 2026-09-25 (1.1.220) — superseded

**State:** main = origin/main @ 1.1.220. Suite 1280 green (1242 → 1280). One build, one item: a security fix. The work
was done in a worktree branch and fast-forwarded into main.

**Shipped in 1.1.220 — the Bot Storage screen is owner-only.** A read-only scoper found the hole and it was verified with
file:line. Any multiplayer player could send the screen's payloads for ANY bot. Collect / Go fast-traveled that bot to
coordinates the client chose, and on arrival it took up to 256 items from any container there. Refresh, Dismiss and
Quick Store/Fetch had no owner check either.
- Pure `ChestRegistryAccessPolicy` (authorize / checkTarget / parseMode / parseReturnTo / checkArrival / logSafe).
- All four `ChestRegistryNetworkManager` handlers run one gate first: the bot must be a companion, and the requester its
  owner, an op or the integrated-server host.
- Collect and Go need a live record in that bot's own registry.
- The arrival take is chest-only, within 8 blocks, a registry member, and only on the collect trip itself.
- Details, rulings and deferrals are in `changelog.md`.

**Rulings on Bradley's behalf (recorded with cost-if-wrong in the changelog):**
- The host is allowed, following the 1.1.203 `canEditConfig` precedent.
- The gate covers all four handlers.
- Un-owned bots are op/host only, which is stricter than `InventoryAccessPolicy`.
- Records are validated by position, not by `ownerUuid`.
- Quick Fetch keeps a player-chosen target.
- The arrival check uses distance, not a line-of-sight raycast.

**Field checks pending:** Phase 6r (new; owner flows still work, and a second player is denied), plus 6b–6q. The checklist
is now 199 items.

**Deferred from 1.1.220:**
- Ownership is not re-checked on arrival.
- A bot respawned by the End exit is a plain `ServerPlayerEntity`, so it gets "not a companion" until it is recreated.
  This fails closed, and the mod's 78 other `instanceof` checks share it.
- An offline-mode LAN guest using the host's name passes `isHost`; `configNetworkManager` has the same gap.
- `verifyChests` revives a record for any chest placed back at its position.
- A dimension-fallback collect is dropped.
- Two Collects on different chests while the bot is on cooldown fail safe but silently. The second overwrites the
  first pending action, which is keyed by alias. Retry A arrives and is dropped as WRONG_TRIP; retry B finds no action
  left. The owner sees two "arrived" lines and gets no items.

**Next autonomous candidates:** unchanged from 1.1.219 below.
1. Supplies Phase 3. Its withdrawal list includes the NavigationArtifactService withdraw branch, which is now
   owner-gated and chest-only; keep it exempt as owner-initiated.
2. Egress follow-ups.
3. Addressee deferrals.

**Needs Bradley:** guided field session (6b–6r); doorway rework decision; ACTION REQUESTS interview; Bob's TTS reference
sample; whether to lower `OLLAMA_NUM_PARALLEL`.

---

## Session Handoff 2026-09-25 (1.1.219) — superseded

**State:** main = origin/main @ 1.1.219 (pushed; deployed to all three Prism instances 2026-09-25, game closed). Suite 1242 green (1122 → 1242). One build, two
items. The work was done in a worktree branch and fast-forwarded into main.

**Shipped in 1.1.219:**
- **Companion supplies Phase 2 — DORMANT.**
  - `SupplyRequestService`: request, answer, transferNow and revoke, all on the server thread.
  - Always permissions per save in `<save>/frens/supply_always.json`.
  - `/frens supply answer|revoke`, under a new non-op root. The `/bot` root is op-only.
  - Bot-placed chest records now carry `ownerUuid`.
  - Pure `SupplyChestRules`: ownership over both halves, access order, stock, fingerprint, codec.
  - `request(` and `transferNow(` have **zero callers**, and `SupplyDormancyTest` fails the build if one appears.
- **Addressee rule fixes.**
  - Soft broadcast ("guys", "everyone", "you two"…). It counts only when no bot is named, no other human is online,
    and the party path can route.
  - A line that starts with another human's name does nothing bot-facing.
  - A trailing ", Name" wins.
  - Comma lists address both, unless a clause cue follows ("Bob, Jake said…" → Bob).
  - A 30 s DM follow-up window after a delivered soul DM reply (`SoulDmFollowUpWindow`):
    - A routingId guard stops a late reply to an older DM from taking the window.
    - It is off while another human is online.
    - It skips lines the zzz trigger, the quest ask or the skill-resume prompt already handled.
  - Eval t2 (counts only): 37/63 → 48/63 (real 17/24 → 18/24), 0 broken; with the window as an upper bound,
    54/63 (real 22/24).
- Details, rulings and deferrals are in `changelog.md`. Scope notes (local, untracked):
  `.superpowers/sdd/SCOPE-supplies-phase2.md`, `.superpowers/sdd/SCOPE-addressee.md`.

**Bradley's rulings (2026-09-25):** iron tier stays out of supplies, and nobody approves for an un-owned bot. Both were
already the defaults, so no code changed.

**Field checks pending:** Phase 6q (new; addressee behaviour + supplies groundwork), 6p, 6o, plus 6b–6n. The checklist is now 195 items.

**Next autonomous candidates:**
1. **Supplies Phase 3**: route every existing automatic withdrawal through the shared policy.
   - The plan's list: ToolProvisionService raw moves + `retrieveToolFromChests`, ChestStoreService registered-tool
     withdrawals, BotMutualAidService chest food.
   - Also the scoper's four: HarvestCropSkill seeds :493/:635, HuntSkill weapon :1256-1275 and food :1391 (its own
     scan at :1442), NavigationArtifactService :1523-1535.
   - Exempt: `/bot withdraw` and Quick Fetch.
   - Before wiring:
     - decide the policy for pre-1.1.219 null-owner chest records (today DENY_UNKNOWN until re-placed);
     - add a ledger tick sweep;
     - stop a valid empty Always file from WARNing;
     - a revoke-all command.
   - Phase 3 must land before any release that wires live withdrawals.
2. Egress follow-ups: outside→inside entry routing, and Fortify onto the shared helper.
3. Addressee deferrals: mid-sentence third-person mentions, "you and Bob" clauses, human nicknames.

**Needs Bradley:** guided field session (6b–6q); doorway rework decision; ACTION REQUESTS interview; Bob's TTS reference
sample; whether to lower `OLLAMA_NUM_PARALLEL`.

---

## Session Handoff 2026-09-25 (1.1.218) — superseded

**State:** main = origin/main @ 1.1.218 (pushed; deployed to all three Prism instances 2026-09-25, game closed). Suite 1122 green after supplies Phase 1 (1078 at the 1.1.218 release). Two builds this session: 1.1.217 (below) and 1.1.218.

**Shipped in 1.1.218 — construction interior egress** (Bradley's "stuck inside the house instead of walking out the
doorway"). Hovel: boundary-aware `HovelEgressGeometry` + aligned door waypoints; schematic/generic: `InteriorEgressPolicy`
+ `InteriorEgressService` from `BuildSchematicSkill.moveToReachBlock` and `ConstructionRecoveryService.moveTo`, doorways
derived from plan air gaps (built-ins have no door blocks), 2-failure switch per build, fall guard (ground within a 2-block
drop, contiguous). Review wave kept the 1.1.217 fallback wherever egress fails. Fortify untouched. Logs `[egress] …` and
`[hovel-egress] …`. Details, rulings, deferrals in `changelog.md`.

**Field checks pending:** Phase 6p (new, construction egress) and 6o (1.1.217), plus 6b–6n.

**Also done this session (not released, no callers):** companion supplies Phase 1 `03ef2538` — pure
`SupplyRequestPolicy` + `SupplyRequestLedger` in `GameAI/services/supply/` (tests 1122). Next for that track is Phase 2
(adapter + authorization persistence). **Next autonomous candidates:** (1) Supplies Phase 2. The Phase 1 scoper found
the plan's Phase 3 misses ≥4 withdrawal bypasses (HarvestCropSkill seeds :493,:635; HuntSkill weapon :1256-1275 and food
:1391; NavigationArtifactService :1523-1535) and that bot-placed chest records follow the bot's CURRENT owner with
unlinked double-chest halves — Phase 2 needs an explicit owner field. (2) Addressee rule fixes (listed below in the 1.1.217
block). (3) Egress follow-ups: outside→inside entry routing, Fortify onto the shared helper.

**Needs Bradley:** unchanged from the 1.1.217 block below, plus: un-owned bot supply approvals (who approves?).

**Research artifacts (local only, untracked — contain Bradley's real chat lines, repo is public):**
`.superpowers/research/2026-09-25-decision-model-eval/` (labeled sets t1–t3, runner, results, REPORT.md, JEV_FRENS_REPORT.md,
probes). Reuse them before re-proposing any model router.

---

## Session Handoff 2026-09-25 (1.1.217) — superseded

**State:** main = origin/main @ 1.1.217 (pushed; deployed to all three Prism instances 2026-09-25, game closed). Suite 1020 green (949 → 1020). The Codex branch `codex/companion-supplies-model-switch`
(voice-preserving model switch, group-chat guide topic, one-time group-chat hint) was fast-forwarded into main and
reviewed in this batch.

**Where 1.1.217 came from:** five 1.21.11 sessions on 1.1.216 (2026-09-07 21:39 → 2026-09-08 20:15) that nobody had
autopsied. Log-answered Phase 6n items (not ticked — Bradley's session still confirms): "A scene still gets through a
scripted floor" PASS (4 `vetoed:speech-floor`, each followed by `fired`), "The two banter lanes respect one quiet period"
PASS (finish→next fire 20–45 s), "The woodcut storm is gone" PASS (2 failures/session, `remaining=48s` then `108s`),
"The backoff ladder widens" PARTIAL (sessions too short past 108 s). Torch-hold DOES fire (Jake held 5 times at light 6–7)
but yielded within ≤1 s at light 8 — the P1 "not visibly firing" item is a threshold-flicker problem, now hysteresis.

**A false alarm to not repeat:** `Sending chat message (withDelay=true)` was logged BEFORE the text gate, so with the
scripted Text master off it recorded lines nobody saw. It now logs `Scripted chat suppressed (text off)` there and
`Scripted chat delivered bot=… category=… part=N` at the real send — grep `part=0` for delivered scripted lines.

**Shipped in 1.1.217:** Piper pre-warm (`[souls] tts prewarm voices=N`); torch hysteresis 7/11 + `savedSlot=` label +
pre-torch slot persisted in the snapshot (`slotToPersist`) + SERVER_STOPPING `yieldAll`; SpeechFloorService teardown;
`[souls] ollama usage … promptTokens=…` per soul call; truthful scripted-chat logs; `ScriptedDelivery` (voice-on/text-off
lines now arm the floor); `SOUL_SCENE_PENDING` reservation + hold-aware scene-line floors; review wave: GroupChatHint
disconnect hop to server thread, whenComplete release. Details, rulings and deferrals in `changelog.md`.

**Field checks pending:** Phase 6o in `docs/testing/FIELD_SESSION_1.1.202.md` (new), plus 6b–6n. Phase 6n item 1 now
greps `Scripted chat delivered … part=0`. The torch check must run at night or underground (daylight gives only
`light-above-7` rejects).

**Performance notes from the Jev / System-One investigation (2026-09-25):** hosted Jev: skip (waitlist, chat leaves the
machine, worst calibration in independent tests). The local Ollama already does single-token typed decisions with
logprobs at ~145 ms warm on the resident 8B, but a few-shot eval on REAL lines did not beat the current rules (chat
routing 28 vs 33/41, addressee 18 vs 17/24) — no-go for a model router. It did surface rule fixes for addressee
detection (below). `OLLAMA_NUM_PARALLEL=4` (Bradley's `com.bradley.ollama-env` LaunchAgent) makes Ollama reserve 4× the
KV cache for Frens' `num_ctx 8192` — ~4 GiB on llama3.1:8b, one slot used; the new `ollama usage` log lets `NUM_CTX` be
right-sized. Generation latency (4–16 s) is the real bottleneck, not decisions.

**Next autonomous candidates (in queue order):** (1) **1.1.218 construction interior egress** — scoped: schematic/generic
builds have no inside→outside doorway routing, straight-line moves run before A*, and the hovel's footprint tests are
both strict so wall-line cells skip its door exit. Fix = lift Fortify's gate pattern into a shared helper; do NOT refactor
Fortify. Scope notes: `.superpowers/sdd/SCOPE-1.1.218-egress.md`. (2) Companion supplies Phase 1 (pure policy only; plan
`docs/superpowers/plans/2026-09-08-companion-supplies-and-model-switching.md`). (3) **Addressee rule fixes** from the
eval: a 30 s reply window after a delivered DM reply (today only group scenes open one, SoulRuntime:287/293),
"guys/you two/everyone" → both, a line starting with another human's name → none, trailing ", Jake?" outranks an earlier
"Bob", "Bob, Jake said…" ≠ both. Unchanged older candidates: `threads.closed` matching, `BAD_AT`, TEASE peer rule,
`relationsEnabled()` supplier, `MIN_TRIGRAMS`, Fortify delegate inlining — all field-blocked.

**Needs Bradley:** guided field session (6b–6o); doorway rework decision (the follow/pressure-plate one, separate from
construction egress); ACTION REQUESTS interview; Bob's TTS reference sample; supplies plan iron-tier question;
whether to lower `OLLAMA_NUM_PARALLEL` (machine-wide; other local jobs share it).

---

## Session Handoff 2026-09-07 — superseded

**State:** main = origin/main @ 1.1.216 (pushed; deployed to all three Prism instances). Suite 945 green.
First build in this project driven by a real field log rather than the backlog: Bradley played four minutes on
2026-09-06 (22:29–22:33, instance 1.21.11, Jake + Bob, Roti mounted) and reported the bots talking over each
other. The log is archived at `logs/2026-09-06-session-overlap.log.gz`. Frens had been field-blind since
1.1.184, so this was the first evidence in a long while — and it was worth more than the backlog: both items
shipped here came out of it, and the scoped hypothesis for the second one was wrong on every point.

**Shipped in 1.1.216 (two root causes, four commits + one regression fix):**

1. **Cross-lane speech floor.** Scripted ambient dialogue and soul group scenes shared no "someone is talking"
   state at all — greps for `isSpeaking`, `speakingUntil`, `lastSpokeAt`, `quietPeriod` over `src/main`
   returned nothing. Scripted cooldowns are per (bot, trigger-pool) with no aggregate, so three lines from two
   bots landed inside one second at 22:30:08 and scripted lines interleaved with a playing scene at 22:32:11
   and :15. The IDLE and ACTIVE banter lanes shared only `isSceneBudgetFree`, which is occupancy-only and
   clears the instant `GroupScenePlayback.finish` runs, so a second scene fired nine seconds after the first
   ended. New pure `SpeechFloorPolicy` (source-aware: scenes preempt a scripted floor, scripted respects
   everything; 4s / 6s / 20s floors) plus `SpeechFloorService`, keyed by AUDIENCE and storing the deadline and
   its `armedBy` label as one record so they cannot disagree. Consulted by both scripted reaction services and
   by both banter lanes, armed by scene lines and scene end. Mount/animal repeats were the flat cross-bot
   dedup window expiring, so the dedup is now per-pool, equal to that pool's own pacing-scaled cooldown.
2. **Woodcut restart storm** — the repro that stood in "Needs Bradley" for two sessions is now captured and
   fixed. `Idle hobby 'woodcut' finished … success=false` fired 197 times at 3–19 per second. The scoped
   hypothesis (the picker re-picking with no backoff, `reason=''` read as "never started") was wrong on every
   point: `Starting idle hobby` never appears in the log, `SkillExecutionResult` has no reason field, and every
   restart came from `maybeHandleIdleWoodenFallback`. The real mechanism was a silent wipe — the fallback set
   a 12s cooldown and started woodcut, and on the very next tick the active-task branch called
   `clearWoodenFallbackState`, removing the cooldown it had just set for its OWN run. The 1.1.199 diagnostic
   never printed because the same wipe removed the signature its guard tests. Fixed by narrowing that clear to
   non-own tasks and adding a per-(bot, hobby) doubling failure backoff (`HobbyBackoffPolicy`, 60s to a
   10-minute cap) written only from the existing `server.execute` block.

**The regression worth remembering.** The batch review's finding 7 asked for a "world changed" reset so an axe
in a chest would not be ignored for the rest of a ten-minute backoff, and the controller ruled it should hang
off the tool-signature-change branch. That was wrong, and the scoped re-review caught it as Critical:
`ToolProvisionService.computeAccessibleIdleFallbackSignature` hashes `scanAccessibleContainers` at the bot's
CURRENT position, so it flips on movement alone. The backoff map had been the one piece of state immune to
that flip, which was exactly what made the storm fix hold — a following bot with no axe would have wiped its
own backoff by walking. The reset now hangs off real availability: the toolless early-out, or a probe
throttled to once per 100 ticks that clears the backoff only when an axe is held or craftable.

**Field checks pending:** Phase 6n in `docs/testing/FIELD_SESSION_1.1.202.md` (now 172 items), eleven items,
no new toggles — the floor and the backoff are always on. The decisive ones are "A scene still gets through a
scripted floor" (a run of only `vetoed:speech-floor` with no `fired` means scripted ambient is starving the
soul lane), "Muting scripted dialogue does not mute souls" (the lane-separation rule), and "An axe in a chest
wakes it back up" (the probe). Phases 6b–6m from 1.1.203–1.1.215 are all still unrun.

**Deferred with reasons:** Piper TTS warm-up — two engines cold-started mid-scene (`ryan` 22:32:09, `lessac`
22:32:12), real and cheap, but independent of the pile-up and it needs its own decision on which voices to
pre-spawn for bots that may not be present. The `[LoadGoverner]` debug lines are the loudest prefix in the log
(63 in a 50-second window) but belong to the separate LoadGoverner mod, not this repo, and the storm did not
drive them — `transient floor refreshed stage=2` runs at a steady 1/s from before the burst to well after it.
Unifying the two scripted services' cooldown implementations: larger refactor, no remaining field symptom.
Adding a reason enum to `SkillExecutionResult`: touches every skill call site, and consecutive-failure counting
already separates deterministic from transient failures. Unchanged from before: `FarmSkill.pillarEscape`;
`WoodcutSkill.pillarUp`, `HovelPerimeterBuilder.pillarUp`, `WoodcutSkill.clearBlockingLeaves`; doorway rework.

**Remaining autonomous candidates:** unchanged from the 2026-09-06 handoff and all still field-blocked —
`threads.closed` matching (needs a per-question `SoulMindOps.markAnswered` overload), `BAD_AT` from repeated
`TASK_FAILED`, TEASE peer rule once `SoulSpeechAct` rides on `GroupSceneTurn`,
`SoulBanterDirector.relationsEnabled()` static read → supplier, `MIN_TRIGRAMS` 3→4, inlining the four
single-caller Fortify delegates. New from this build: `SpeechFloorService.clear`/`clearAll` are unwired API
(no service in that group registers a SERVER_STOPPING teardown), and the per-hobby backoff state is written for
hunt and collect_dirt but read only by woodcut — ready if a later item gates `pickHobby` on tool availability.

**Needs Bradley:** the guided field session (Phases 6b–6n) is now the only thing blocking everything else.
Doorway rework decision; ACTION REQUESTS interview; Bob's TTS reference sample. The woodcut repro that used to
sit here is DONE — captured in the 2026-09-06 log and fixed in 1.1.216.

---

## Session Handoff 2026-09-06 (final) — superseded

**State:** main = origin/main @ 1.1.215 (pushed; deployed to all three Prism instances). Suite 903 green. 1.1.215 = one-line follow-up: leading list bullets (`-`, `•`, `>`) stripped before the `##FRENS` sentinel (the 1.1.214 re-review residual), no new field items. **Phase 3 of the soul
conversation ontology is COMPLETE** — (d) 1.1.211, (a) 1.1.212, (b) 1.1.213, (c) 1.1.214.

**Shipped in 1.1.214 (Phase 3 item c — structured LLM output, `##FRENS` side channel):** pure
`SoulSideChannelOps.parse(raw, rosterNames, ownerName)` → `SideEffects` (peers-only stance deltas ±1 per
axis per scene, ≤3 facts at confidence ≤0.6, unknown keys ignored, `threads` discarded); validator ends the
scene at a lenient `##FRENS` line (case-insensitive, `*`/`#`/`_`/backtick decoration tolerated, ≥1 `#`
required) and carries `SceneParse.sideChannelRaw`; sentinel-first → existing reject path, no effects.
Applied in `SoulGroupConversationService` after novelty, before enqueue, through a `SideChannelSink`
that `SoulRuntime` implements as worker → `server.execute` (day) → `store.updateMind`: stance deltas via
`SoulMindOps.bumpPeerStance` (per-day guard consumes `lastTrustDay`/`lastAskDay`) to every other roster
bot toward the named peer; facts via `SoulRelationOps.normalise`+`merge` at `INFERRED`, salience 6, into
every present bot — ONLY when `soulRelationsEnabled` is on. Prompt contract paragraph + 380-token cap
only when `soulStructuredOutputEnabled` (default **false**, six config sites,
`/bot soul structured on|off|status`). Logs: `[souls] side-channel correlationId=… stance=a/s facts=a/s
dropped=n`, `… unparsed correlationId=… chars=n`, `… factsSkipped=n reason=soulRelationsEnabled=false`.
Details + rulings in `changelog.md`.

**Scoping corrections:** `SoulRuntime.noteSceneDeliveredForMind` DOES exist (`SoulRuntime:939`; the
1.1.213 handoff was wrong) but is the wrong seam for (c) (server-thread playback finish, no
`SceneParse`/correlationId). `SoulMemoryDigestService` parses bullets, not JSON.

**Field checks pending:** Phase 6m in `docs/testing/FIELD_SESSION_1.1.202.md` (now 161 items) — needs
BOTH `/bot soul structured on` and `/bot soul relations on`; the decisive item is "Side channel is emitted
at all": if the 3B model never produces a usable tail over 5 scenes, schedule spec fallback (3) — a
separate clerk generation — as its own build.

**Remaining autonomous candidates:** `threads.closed` matching (needs a per-question
`SoulMindOps.markAnswered(mind, question)` overload — the existing one closes all threads and moves
`playerStance`); `BAD_AT` from repeated `TASK_FAILED`; TEASE peer rule once `SoulSpeechAct` rides on
`GroupSceneTurn`; `SoulBanterDirector.relationsEnabled()` static read → supplier; `MIN_TRIGRAMS` 3→4 if
the field session shows quoted-idiom drops; inline the four single-caller Fortify delegates. None of these
should start before the guided field session — Phase 3 is now all field-blocked.

**Deferred with reasons (unchanged):** `FarmSkill.pillarEscape`; `WoodcutSkill.pillarUp`,
`HovelPerimeterBuilder.pillarUp`, `WoodcutSkill.clearBlockingLeaves`; doorway rework.

**Needs Bradley:** guided field session (Phases 6b–6m); woodcut fallback restart loop repro; doorway
rework decision; ACTION REQUESTS interview; Bob's TTS reference sample.


---

## Session Handoff 2026-09-06 (late) — superseded

**State:** main = origin/main @ 1.1.214 (pushed; deployed to all three Prism instances). Suite 900 green. **Phase 3 of the soul
conversation ontology is COMPLETE** — (d) 1.1.211, (a) 1.1.212, (b) 1.1.213, (c) 1.1.214.

**Shipped in 1.1.214 (Phase 3 item c — structured LLM output, `##FRENS` side channel):** pure
`SoulSideChannelOps.parse(raw, rosterNames, ownerName)` → `SideEffects` (peers-only stance deltas ±1 per
axis per scene, ≤3 facts at confidence ≤0.6, unknown keys ignored, `threads` discarded); validator ends the
scene at a lenient `##FRENS` line (case-insensitive, `*`/`#`/`_`/backtick decoration tolerated, ≥1 `#`
required) and carries `SceneParse.sideChannelRaw`; sentinel-first → existing reject path, no effects.
Applied in `SoulGroupConversationService` after novelty, before enqueue, through a `SideChannelSink`
that `SoulRuntime` implements as worker → `server.execute` (day) → `store.updateMind`: stance deltas via
`SoulMindOps.bumpPeerStance` (per-day guard consumes `lastTrustDay`/`lastAskDay`) to every other roster
bot toward the named peer; facts via `SoulRelationOps.normalise`+`merge` at `INFERRED`, salience 6, into
every present bot — ONLY when `soulRelationsEnabled` is on. Prompt contract paragraph + 380-token cap
only when `soulStructuredOutputEnabled` (default **false**, six config sites,
`/bot soul structured on|off|status`). Logs: `[souls] side-channel correlationId=… stance=a/s facts=a/s
dropped=n`, `… unparsed correlationId=… chars=n`, `… factsSkipped=n reason=soulRelationsEnabled=false`.
Details + rulings in `changelog.md`.

**Scoping corrections:** `SoulRuntime.noteSceneDeliveredForMind` DOES exist (`SoulRuntime:939`; the
1.1.213 handoff was wrong) but is the wrong seam for (c) (server-thread playback finish, no
`SceneParse`/correlationId). `SoulMemoryDigestService` parses bullets, not JSON.

**Field checks pending:** Phase 6m in `docs/testing/FIELD_SESSION_1.1.202.md` (now 161 items) — needs
BOTH `/bot soul structured on` and `/bot soul relations on`; the decisive item is "Side channel is emitted
at all": if the 3B model never produces a usable tail over 5 scenes, schedule spec fallback (3) — a
separate clerk generation — as its own build.

**Remaining autonomous candidates:** `- ##FRENS` (leading bullet) not stripped by `sideChannelTail` — one char + a test; `threads.closed` matching (needs a per-question
`SoulMindOps.markAnswered(mind, question)` overload — the existing one closes all threads and moves
`playerStance`); `BAD_AT` from repeated `TASK_FAILED`; TEASE peer rule once `SoulSpeechAct` rides on
`GroupSceneTurn`; `SoulBanterDirector.relationsEnabled()` static read → supplier; `MIN_TRIGRAMS` 3→4 if
the field session shows quoted-idiom drops; inline the four single-caller Fortify delegates. None of these
should start before the guided field session — Phase 3 is now all field-blocked.

**Deferred with reasons (unchanged):** `FarmSkill.pillarEscape`; `WoodcutSkill.pillarUp`,
`HovelPerimeterBuilder.pillarUp`, `WoodcutSkill.clearBlockingLeaves`; doorway rework.

**Needs Bradley:** guided field session (Phases 6b–6m); woodcut fallback restart loop repro; doorway
rework decision; ACTION REQUESTS interview; Bob's TTS reference sample.

---

## Session Handoff 2026-09-06 (night) — superseded

**State:** main = origin/main @ 1.1.213 (pushed; deployed to all three Prism instances). Suite 874 green.

**Shipped in 1.1.213 (Phase 3 item b — typed relation facts, `SEEN` producer only):** `SoulTypes.Relation`
(closed enum of 8), `RelationSource`, `RelationFact`; `SoulMind.relations` (13th component, old
`mind.json` → empty list; malformed rows dropped in the compact ctor). Pure `SoulRelationOps`
(`normalise`/`merge`/`decay`/`noteRecalled`/`fromJournal`/`render`/`beliefLines`/`anchors`/
`anchorTopic`+`parseAnchorTopic`). Producer: `consolidate(…, botName, relationsEnabled)` folds
≥3 `TASK_COMPLETED` of one task (suffix after `:`, e.g. `woodcut`) → `(bot, GOOD_AT, task)` at 0.4, one
fact per fold (most completions), then ALWAYS decays. Render: `BELIEFS (claims the bots hold, not world
truth)` block after ABOUT, whole block ≤240 chars/≤4 lines, ABOUT wins Jaccard ≥0.6. Seeds: relation
anchors weight 4, cap 2, topic `relation:<REL>|<subject>|<object>`, recall cooldown wired in the director.
Commands: `/bot soul beliefs <Bot>` (read-only), `/bot soul relations on|off|status`. Toggle
`soulRelationsEnabled` default **false** at the six config sites. Log: `[souls] relations bot=… added=…
total=…`. Details + rulings in `changelog.md`.

**Scoping corrections:** spec's `(Bob, GOOD_AT, mining)` was unreachable — the `category` fact is
`skill` for every skill task; producer buckets on the task suffix. `SoulRuntime.noteSceneDeliveredForMind`
(the spec's (c) producer hook) does not exist — (c)'s scoper must find the real mind seam
(`noteSceneDeliveredForMind` was the 1.1.212 name at `SoulRuntime:839`? verify). (d) and the digest do
NOT share a comparator (trigram vs Jaccard).

**Field checks pending:** Phase 6l in `docs/testing/FIELD_SESSION_1.1.202.md` (now 153 items) — run
"Old minds load clean" first, then turn the toggle on; the feature is inert until then.

**Remaining autonomous candidates:** (c) structured `##FRENS` tail behind `soulStructuredOutputEnabled`
(default false), peers-only stance deltas, `SAID`/`INFERRED` relation writes through
`SoulRelationOps.normalise` with the scene roster as allowed subjects — LAST in spec order, now unblocked
because (b)'s consumer exists. Start it in a FRESH session (this one ran the full loop once; (c) touches
the generation contract and deserves a clean context). Tidy-ups: `BAD_AT` from repeated `TASK_FAILED`;
`SoulBanterDirector.relationsEnabled()` static `Frens.CONFIG` read → supplier if a seed-loop test is ever
written; TEASE peer rule once `SoulSpeechAct` rides on `GroupSceneTurn`; `MIN_TRIGRAMS` 3→4 if the field
session shows quoted-idiom drops; inline the four single-caller Fortify delegates.

**Deferred with reasons (unchanged):** `FarmSkill.pillarEscape`; `WoodcutSkill.pillarUp`,
`HovelPerimeterBuilder.pillarUp`, `WoodcutSkill.clearBlockingLeaves`; doorway rework.

**Needs Bradley:** guided field session (Phases 6b–6l); woodcut fallback restart loop repro; doorway
rework decision; ACTION REQUESTS interview; Bob's TTS reference sample.

---

## Session Handoff 2026-09-06 (evening) — superseded

**State:** main = origin/main @ 1.1.212 (pushed, deployed to all three Prism instances). Suite 846 green.

**Shipped in 1.1.212 (Phase 3 item a — bot↔bot peer stance):** `SoulTypes.PeerStance(stance,
lastTrustDay, lastAskDay)`; `SoulMind.peerStances: Map<UUID, PeerStance>` (12th component, old
`mind.json` → empty map, no `@JsonCreator` needed — Jackson binds the canonical record ctor).
`SoulMindOps.notePeerScene` (pure: asked-by-peer → curiosity; unanswered ask → exasperation; both spoke
→ trust; every axis once per Minecraft day per pair; cap 6, closest-to-baseline eviction; decay in
`consolidate` drops entries at exact baseline) + `peerStanceClause` ladder (wary of / thick as thieves
with / short with / a little tired of / curious about). Seam: `SoulRuntime.notePeerStances` above the
narrator guard in `noteSceneDeliveredForMind` (server thread builds lists → `store.updateMind` on the
store executor, log in a `thenAccept`). Render: one clause per roster-present non-baseline peer in
`SoulGroupPromptAssembler.stateBlock`, inside the 400-char cap. No toggle (inert at baseline). Log:
`[souls] peer stance bot=… peer=… trust=… exasperation=… curiosity=…`. Details + rulings in `changelog.md`.

**Scoping corrections:** the mind seam IS `SoulRuntime:839` (spec right; the 1.1.211 lesson was about
delivery only). TEASE rule deferred (`GroupSceneTurn` has no `SoulSpeechAct`); OWNER_DAMAGE-peer rule
deferred (event carries only `ownerId`). Per-day guard extended to the ask axes (review finding).

**Field checks pending:** Phase 6k in `docs/testing/FIELD_SESSION_1.1.202.md` (now 147 items) —
run "Old minds load clean" first; the rest need two bots that both speak.

**Remaining autonomous candidates (spec approved, in this order, one build each):** (b) typed
relations with only the deterministic `SEEN` producer + `BELIEFS` block + read-only
`/bot soul beliefs <Bot>` behind `soulRelationsEnabled` (default false) — next; (c) structured `##FRENS`
tail, off by default, peers-only stance deltas — last, only after (b)'s consumer exists. Not combined.
Tidy-ups: TEASE peer rule once `SoulSpeechAct` rides on `GroupSceneTurn`; `MIN_TRIGRAMS` 3→4 if the
field session shows quoted-idiom drops; inline the four single-caller Fortify delegates.

**Deferred with reasons (unchanged):** `FarmSkill.pillarEscape`; `WoodcutSkill.pillarUp`,
`HovelPerimeterBuilder.pillarUp`, `WoodcutSkill.clearBlockingLeaves`; doorway rework.

**Needs Bradley:** guided field session (Phases 6b–6k); woodcut fallback restart loop repro; doorway
rework decision; ACTION REQUESTS interview; Bob's TTS reference sample.

---

## Session Handoff 2026-09-06 — superseded

**State:** main = origin/main @ 1.1.211 (pushed, deployed to all three Prism instances). Suite 831 green.

**Phase 3 ontology spec APPROVED by Bradley (2026-09-06)** — all five open questions took the recommended
answer: (1) model stance deltas → peers only; (2) relations coexist with `PlayerMemory`, decide after the
field session; (3) structured output (c) off by default; (4) `/bot soul beliefs <Bot>` yes, read-only;
(5) novelty state not persisted. Ruling on top: (d) ships behind `soulNoveltyRejectionEnabled`, default
false (spec said no toggle; Bradley's brief asked for one).

**Shipped in 1.1.211 (Phase 3 item d):** `SoulNoveltyPolicy` (pure: ordered content words via the
widened digest normaliser, trigram overlap |a∩b|/min ≥0.6 when ≥8 content words AND ≥3 trigrams both
sides, exact-normalised match otherwise; ring of 12 per bot, only kept lines remembered; per-scene rule =
exact across speakers, full rule for the same speaker) applied in `SoulGroupConversationService` between
parse and enqueue (worker thread; `ConcurrentHashMap<UUID, Ring>`, synchronized ring). Scoping corrected
the spec: delivery is NOT in `SoulRuntime` (that is only the `sceneDelivered` callback). Toggle mirrors
`soulMemoryDigestEnabled` at all six config sites + `/bot soul novelty on|off|status`. Log line:
`[souls] novelty dropped bot=… reason=trigram|exact`. Details + rulings in `changelog.md`.

**Field checks pending:** Phase 6j in `docs/testing/FIELD_SESSION_1.1.202.md` (now 142 items) — turn the
toggle on first; the feature is inert until then.

**Remaining autonomous candidates (spec approved, in this order, one build each):** Phase 3 (a) bot↔bot
stance (`peerStances` in `mind.json`, deterministic rules in `SoulMindOps`, one prompt clause);
(b) typed relations with only the deterministic `SEEN` producer + `BELIEFS` block + read-only
`/bot soul beliefs <Bot>`; (c) structured `##FRENS` tail, off by default, peers-only stance deltas — last,
only after (b)'s consumer exists. Not combined. Tidy-ups: `MIN_TRIGRAMS` floor 3→4 if the field session
shows quoted-idiom drops; ring idle eviction only if a leak shows; inline the four single-caller Fortify
delegates.

**Deferred with reasons (unchanged):** `FarmSkill.pillarEscape`; `WoodcutSkill.pillarUp`,
`HovelPerimeterBuilder.pillarUp`, `WoodcutSkill.clearBlockingLeaves`; doorway rework.

**Needs Bradley:** guided field session (Phases 6b–6j); woodcut fallback restart loop repro; doorway
rework decision; ACTION REQUESTS interview; Bob's TTS reference sample.

---

## Session Handoff 2026-09-05 (late night) — superseded

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
(now 142 items; 6j = novelty rejection).

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


## .ralph/progress.md (Ralph loop log, 2026-02 to 2026-09)

# Progress Log

> Updated by the agent after significant work.

## Summary

- Iterations completed: 6+
- Current status: Farm tree-clearing pipeline fixed end-to-end (2026-04-08). Farm can now landscape forested sites, fell real trees via WoodcutSkill bounded-mode, and build working infinite water sources.
- Active focus: ready for in-game validation of the full `/bot skill farm manual=true` path in forested terrain.

## 2026-04-08 — Farm tree-clear + irrigation pipeline fixes (commits 6458e9d..085d2a5)

User-reported via `/bot skill farm ... manual=true` in a forest: (1) tree clearing took 3+ minutes and produced lots of floaters, (2) phantom "precipice-near-center" rejection in real flat terrain, (3) bot stood still for 42s during woodcut/cleanup, (4) irrigation built only 1 water source instead of an infinite 2×2. Traced each from log evidence in `/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10/minecraft/logs/`. Five commits pushed to origin/main:

**1. `6458e9d` — WoodcutSkill bounded-mode accepts overhanging canopies (Fix A/B/D)**

- `isTreeWorkEnvelopeWithinBounds` previously required the whole leaf envelope expanded by `WOODCUT_LOG_SCAN_EXPANSION=4` in every direction to fit inside the caller's bounds. For a 13×10×13 farm AABB that rejected every oak (16/16 `out-of-bounds-tree-envelope`).
- Relaxed to require only the trunk (`base` → `top`) inside caller bounds. Canopy may overhang.
- Added per-tree work envelope overlay (`activeTreeWorkEnvelopeMin/Max`) set just before `approachBase`/`fellTree` and cleared in a `finally` at the end of each tree iteration (plus outer `finally`). `isWithinRequestedBounds` now admits any pos inside either `activeRequestedBounds` OR the active tree's expanded envelope, so `mineBlockDetailed` can prune leaves that overhang caller bounds for the currently-felling tree only.
- Skipped the outer-finally full-region `runWoodcutCleanup` in bounded mode with `felled > 0` — per-tree maintenance already ran `runLocalTreeCleanup` for each tree, saving ~30s per pass.

**2. `f5cee16` — FarmSkill uses real woodcut for tree clearing, not brute per-block (Fix C/E)**

- Split farm's "is in the way" query buffer from woodcut's work buffer. `FARM_WOODCUT_BUFFER=2` / `FARM_WOODCUT_VERTICAL_RANGE=9` still drive `collectBlockingTreeBlocks` and `isWithinFarmWoodcutBounds`. New `FARM_WOODCUT_WORK_BUFFER=6` / `FARM_WOODCUT_WORK_VERTICAL_RANGE=20` (plus `anchorY-2`) are passed only to `runWoodcutInline` so WoodcutSkill has elbow room to approach, scaffold, and prune canopies.
- `escapeTreeAndWoodcut` and `clearBlockingTrees` previously ran `clearBlockingTreeBlocksLocally` (arbitrary-order brute clear) in parallel with the inline woodcut on every pass — source of the floaters. Both call sites now re-count blockers after the woodcut call and only fall back to the brute clear when woodcut failed to reduce the count.

**3. `4a73129` — FarmSkill no longer sees tree canopies as phantom precipices**

- `assessFarmSite` measured per-column heights via `Heightmap.Type.WORLD_SURFACE` which counts every non-air block — in a forest the "surface" was canopy tops (y=78–85), not walkable ground (y=65–68). Median landed mid-canopy; `hasSimplePrecipice` sampled a 7×7 area at y=72, found 4+ air blocks below, rejected with phantom precipice.
- Replaced with `SafePositionService.getWalkableGroundY(world, x, z)` in `assessFarmSite`'s column loop, its inline water-surface check, and `estimateFarmAreaMedianSurfaceY`. `getWalkableGroundY` scans down from `MOTION_BLOCKING_NO_LEAVES` skipping logs/leaves.
- Water detection semantics updated: `walkableY - 1` is always solid; water is detected by checking the block AT `walkableY`.
- Removed two redundant hard-reject `hasSimplePrecipice` call sites (pre-assessment guard in `execute()`, `precipice-near-center` guard in `assessFarmSite`). Per-column `grade-cut-too-deep` / `fill-too-deep` loop is the authoritative terrain gate. Kept the function itself for `assessFarmWorkingGround` and `isUsableFarmStandingGround` call sites which operate on specific upstream-chosen positions.

**4. `14f1fa0` — WoodcutSkill UOE + unconditional bounded-mode cleanup skip (Fix G + D-broader)**

- After Fix A let a tree through for the first time, `collectRemainingEnvelopeLogs` crashed with `UnsupportedOperationException`. Latent bug: `remaining = remaining.stream().filter(this::isWithinRequestedBounds).toList()` — `Stream.toList()` (Java 16+) returns unmodifiable. The next line called `.sort(...)`. Fix: `.collect(Collectors.toCollection(ArrayList::new))` + `java.util.stream.Collectors` import. Audited the other 5 `.toList()` usages in the file; all read-only.
- The UOE unwound into `execute()`'s outer `finally`, which ran `runWoodcutCleanup` for 42 seconds (bot standing still). Fix D only skipped cleanup when `felled > 0`, so on the exception path it still ran. Broadened: in bounded mode we **always** skip the full-region cleanup, since either (a) per-tree maintenance already handled each tree, or (b) the bounded region is full of intact trees that the cleanup can't touch.

**5. `085d2a5` — FarmSkill irrigation places real 2nd source instead of flowing water (Fix H1/H2)**

- `placeWaterOnServerThread` used `world.getBlockState(waterPos).isOf(Blocks.WATER)` as a fallback success check. In a 2×2 hole, after placing the NW source, flowing water spreads into SE within a few ticks. On the 2nd placement attempt at SE: `useOnBlock` returned `Pass`, bucket was NOT consumed, but the "is water" check saw pre-existing flowing water and reported success. Result: `still=1, water=4, flow=3` — one real source, three flowing cells — treated as "filled".
- Fix H1: replaced the world-state check with a **bucket consumption** check. Compare `WATER_BUCKET` → `BUCKET` before/after the interaction. This is correct even when the target cell already contains flowing water, because a valid placement still consumes the bucket. Added `consumed=true|false` to the log lines.
- Fix H2: removed the `still >= 1 && water >= 4` fallback in `isAcceptableIrrigation` — it was labeled "snow/cold-biome fallback" but only ever fired in the bug case. Confirmed by log line 3307: `finalTopOffBuckets: could not fill all buckets (left empty=1)` — a single source is not an infinite supply.

### User-confirmed fixes (in-game)

- Tree clearing via real woodcut: ✅
- Phantom precipice rejection: ✅
- Bot stillness during woodcut: ✅
- Irrigation infinite source: ✅ ("Good, that fixed it")

### Files changed

- `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java` (A/B/D/G + broader D)
- `src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java` (C/E/F/H1/H2 + walkable-ground-Y + hasSimplePrecipice removals)
- `changelog.md`

### Verification

- `./gradlew build -x test` ✅ after each commit
- JAR deployed to all three PrismLauncher instances (1.21.11, 1.21.10, 1.21.10 TEST) via CLAUDE.md pre-deploy check
- User in-game validation confirmed each fix

## 2026-04-06 — Dialogue tuning & unused voiced lines

Tuned overplayed dialogue lines (my_job, blame_terrain → WEIGHT_RARE; I am Steve roll reduced 75%; wolf cooldown 90s→6min). Added weather transition triggers (rain/snow/thunder/sunny), cooking proximity reactions (lit campfire/furnace), and voiced wake-up lines. Wired 19 mount alternate phrasings into RideSyncService via pickRandom() + DialogueTextMapper entries. Wired 4 orphaned combat lines into BotCombatCalloutService pools (Engaging!, Enemy down., All clear., Hey! Watch it!).

## 2026-03-27 — Food self-sufficiency after hunting

### Problem

Bot Jake stopped hunting at food=8 (gifted steak) with zero backup food. Auto-hunt re-trigger was suppressed by linger system's "moderately hungry" 3-min timer. The bot sat idle with no food items in inventory.

### Changes applied (commit d586b5c)

**SmeltingService — `cookAllFoodSync()` (new method, ~200 lines):**
- Synchronous multi-batch cooking on worker threads
- Resolves furnace, moves to it, then loops through each distinct raw food item type
- Per batch: evacuate output, clear input if different type, load raw items, ensure fuel, poll every 2s until input depleted
- During wait: eats cooked food from output slot if hungry, refuels when fuel runs out
- Timeouts: 3min/batch, 10min total. Furnace mutations via `server.execute()` + `CompletableFuture`

**HuntSkill — backup food check + post-hunt cook:**
- `autoStopOnHunger` now requires food bar >= 16 AND `countFoodItems(bot) >= 4`
- Post-hunt cooking replaced fire-and-forget `startBatchCook` with synchronous `cookAllFoodSync`
- Sets `BotIdleHobbiesService.setPreferCooking()` flag when raw food remains or hunt was player-commanded
- Made `hasRawFood()` and `countFoodItems()` public for cross-service use

**BotIdleHobbiesService — cooking as idle hobby:**
- Added `PREFER_COOKING_UNTIL` per-bot flag (5-min wallclock expiry)
- `pickHobby()` / `pickFallbackHobby()`: deterministic "cook" return when flag set + raw food + fuel available
- "cook" added to weighted hobby list (2x weight when bot has raw food)
- Cook hobby dispatches `SmeltingService.cookAllFoodSync` on `AMBIENT_EXECUTOR` with surface requirement
- Added "cook" to `requiresOperationalSurface()`

**BotAutoHuntService — inventory food awareness:**
- Trigger now checks inventory food at food 11-14: if zero backup items, auto-hunt still activates
- Post-hunt: sets prefer-cooking flag + `requestDecisionNow()` for immediate hobby evaluation

**BotAutoCookingService — threshold raised:**
- `COOK_HUNGER_THRESHOLD` raised from 5 to 10 (matches auto-hunt threshold)

### Key files

- `SmeltingService.java` — `cookAllFoodSync()`, timeout constants
- `HuntSkill.java` — `countFoodItems()`, `hasRawFood()` (now public), backup food auto-stop, post-hunt cook cycle
- `BotIdleHobbiesService.java` — prefer-cooking flag, cook hobby selection + dispatch
- `BotAutoHuntService.java` — inventory food trigger, post-hunt signal
- `BotAutoCookingService.java` — threshold change

### Verification

- `./gradlew build -x test` — BUILD SUCCESSFUL
- JAR deployed to PrismLauncher. Needs in-game playtesting.

### Playtesting checklist

- [ ] Spawn bot, give sword, set hunger to 3 — verify it hunts and doesn't stop until 4+ food items
- [ ] After hunt: verify bot walks to furnace and cooks all raw food types sequentially
- [ ] Give bot raw chicken + raw porkchop manually — verify idle hobby picks "cook"
- [ ] Bot at food=8 with zero inventory food — verify auto-hunt still triggers
- [ ] Bot with raw food but no fuel — verify it uses leaf litter or inventory fuel

## 2026-03-25 — Woodcut cleanup overhaul + surface detection + shelter lifecycle

### Changes applied (2 commits: e5e7fb1, 495d14b)

**Surface detection:**
- `SafePositionService.getWalkableGroundY()` — scans through tree trunks/leaves that fool MOTION_BLOCKING_NO_LEAVES heightmap (confirmed from Minecraft source: only excludes `instanceof LeavesBlock`)
- Rewired `columnSurfaceFeetY`, `wideSurfaceFeetY`, and all downstream callers
- Fixed `isRecoveryReadySurface` to check ±1 Y when standable=false but openSky+nearSurface

**Woodcut cleanup:**
- Elevated sweep: mine all reachable logs while on pillar before descending
- Async scanning concurrent with mining
- Scaffold scanner: require exposed faces for hard blocks, count dirt columns UP only, removed GRASS_BLOCK
- Extended scaffold scan Y ceiling to origin+40, scaffold removal supports pillar up to 5 blocks
- Roaming to new areas when local scan empty
- Chest offload during cleanup

**Woodcut skill:**
- Surface recovery between failed trees (inter-tree-recovery)
- Drop sweep after partial harvests
- Reposition retry before bailing on surface check

**Scaffold reserve:**
- ChestStoreService keeps minimum 32 scaffold blocks across all offload paths

**Shelter lifecycle (NEW):**
- Wired `checkDaylightBreakFree()` into IDLE tick handler (was defined but never called)
- After breakfree, auto-resume interrupted tasks via `SkillResumeService.requestAutoResume()`
- Verified working: woodcut stops at sunset → shelter → dawn breakfree → woodcut resumes

**Follow fix:**
- Clear idle sweep state when follow toggled ON — stale sweep was consuming every tick, blocking follow movement
- Reset bot pitch to 0 after skill completion (prevents leftover look-up angles)

**Drop sweep:**
- Break leaf blocks trapping drops (within 2 blocks above bot)
- Skip drops more than 4 blocks above (unreachable)

**Hole escape:**
- ReturnBaseStuck direct pillar-up when no adjacent walls
- WoodcutCleanupSkill hole recovery uses getWalkableGroundY

### Key research findings (preserved in memory + guardrails)
- MOTION_BLOCKING_NO_LEAVES heightmap counts log blocks (only excludes LeavesBlock)
- Scaffold detection: underground stone registers as 130+ false positives without exposed-face guard
- GRASS_BLOCK in scaffold detection destroys natural terrain (57 blocks shaved in one session)
- Idle sweep can block follow movement indefinitely via stale DropSweepService.isInProgressFor()

### Verification
- All changes deployed and playtested
- Shelter → breakfree → task resume cycle confirmed working in logs
- Surface detection: no more false underground detection under tree canopy
- Scaffold scanner: false positives reduced from 130 to 0

## 2026-03-21 — Survival/follow/combat/runtime stabilization batch

### Changes applied

- Hardened hungry auto-hunt, rescue, and teleport interactions:
  - interrupted auto-hunt workers now unwind cleanly instead of trying to launch `hunt` on a stale interrupted thread after rescue/surface recovery
  - external `/teleport` now aborts open-ended hunt runs and briefly suppresses immediate re-entry
  - rescue helper rejection logging now records concrete reasons such as cooldowns and missing artifact requirements
- Stabilized follow and ride-sync behavior around boats:
  - delayed join-enclosure break-free now skips bots with recent mount/dismount activity so boating no longer trips false `follow OFF`
  - commander boat destruction now queues follower boat teardown and bounded pickup attempts before resuming follow
- Tightened village protection and follow command overrides:
  - extracted shared village structure protection and reused it for generic navigation breaking and woodcut target safety
  - idle fallback woodcut now requires a safe non-protected tree candidate instead of any nearby log
  - player-issued `follow`, `follow distance`, and follow-toggle-on now hard-abort only that bot’s active task before enabling follow, with truthful command summaries
- Improved combat and equipment correctness:
  - fixed bot inventory armor slot routing so UI insertion matches actual `HEAD/CHEST/LEGS/FEET` slot order
  - armor auto-equip now repairs wrong-slot armor, preserves displaced pieces, and logs correction outcomes
  - tridents are now eligible for ranged weapon selection and no longer fail the generic fire gate
  - combat loadout staging now recognizes spear-class melee weapons
  - added low-noise combat profile logging for sword, spear, trident, and mace paths
- Confirmed one protected-village regression check from runtime logs:
  - latest Prism log still shows idle wooden fallback blocking Steve inside protected village/base area at `01:18:41` and `01:18:53`

### Verification

- `./gradlew compileJava` — **BUILD SUCCESSFUL**
- `./gradlew build` — **BUILD SUCCESSFUL**
- Built jars copied to Prism mods folder:
  - `frens-1.1.0-release+1.21.11.jar`
  - `frens-1.1.0-release+1.21.11-sources.jar`

### Notes

- This batch improves runtime stability and playtestability around survival, follow, combat, and UI/inventory behavior, but it does **not** advance the active Ralph construction-parity checklist directly.
- Construction parity work remains the active Ralph objective once these user-driven runtime fixes are out of the way.

## 2026-03-20 — Surface recovery / tome rescue hardening

### Changes applied

- Relaxed “surface enough” handling for recovery in `SafePositionService` and `BotFleeService`:
  - added `isRecoveryReadySurface(...)` so bots on standable near-top terrain with a shallow solid roof no longer keep trying to staircase upward forever just because they are not under open sky
  - `ensureAtSurface(...)` and interrupted-survival resume checks now use this broader recovery-ready test instead of the stricter open-sky-only operational surface gate
- Added explicit `BotFleeService.isSurfaceRecoveryActive(...)` and used it to suppress hungry auto-hunt churn while a single surface-recovery owner is already working
- Hardened `BotAutoHuntService` so hungry auto-hunt:
  - does not get force-requested while surface recovery is active
  - defers instead of re-launching repeated parallel hunt attempts
  - does not immediately treat an in-progress surface recovery as a fresh rescue failure
- Added Wizard’s Tome authority to autonomous instant rescue in `BotEmergencyRescueService`:
  - helper bot holding a Wizard’s Tome can now authorize the instant rescue path without requiring the doubled pearl/chorus pair
  - autonomous instant rescue still uses the harsher rescue cooldown bucket
- Aligned `NavigationArtifactService.artifactDelayMultiplier(...)` with the real Wizard’s Tome item check instead of the old written-book placeholder logic

### Why this matters

- Fixes the case where a bot is effectively already on the surface but trapped in “not surfaced enough” logic because of a few overhead blocks
- Stops the visible “doing nothing” behavior where the bot is actually stuck in `hungry -> ensureAtSurface -> duplicate recovery -> abort`
- Makes autonomous rescue match the intended design: a helper bot holding a Wizard’s Tome can rescue a same-owner bot in dire straits the way a human player can

### Verification

- `./gradlew compileJava` — **BUILD SUCCESSFUL**

## 2026-03-20 — Woodcut hardening for idle wooden fallback / protected wood

### Changes applied

- Hardened `TreeDetector` so woodcut target selection now rejects trees inside saved base protection radii, not just explicit safe zones. This uses `BotHomeService.listBases(...)` and respects per-base custom radii.
- Added village-aware wood protection in `TreeDetector` by reusing fortify/village signals instead of inventing a separate definition:
  - `VillageFortificationLayoutService.isVillageStructureBlock(...)` for nearby village structure signal blocks
  - nearby bells / beds
  - nearby villagers
- Tightened “human-built wood” detection further by treating all bed-tag blocks and bells as human/village signals during tree validation.
- Updated `WoodcutSkill` to expand its effective search radius when the bot starts woodcut from inside a protected base radius or other protected wood area. This prevents the new protection rules from causing false “no trees nearby” failures when the valid trees are simply outside the protected ring.

### Why this matters

- Idle wooden fallback now inherits the same protected-wood behavior as normal woodcut, because both flow through `TreeDetector`.
- Bots will leave the base radius before selecting trees to cut instead of stripping decorative / nearby base wood.
- Village wood is now treated as protected and excluded from tree targeting, including fallback woodcut runs.

### Verification

- `./gradlew compileJava` — **BUILD SUCCESSFUL**
- Follow-up fix: `CraftingResultSlotMixin` updated to target the current mapped two-arg overload `onCrafted(ItemStack, int)` instead of stale `onQuickCraft(...)`
- `./gradlew compileJava` now completes without the old missing-target mixin warning

## 2026-03-08 — Combat survival, flee behavior, and respawn fixes

### Changes deployed (all verified by build + in-game playtesting)

**Shield deadlock fix (`BotEventHandler.engageHostiles`):**
- When `shouldBlock` was true but bot had no shield, `raiseShield()` returned false but the block always returned `true` — bot looped forever trying to raise a phantom shield. Now falls through to melee/fist attack if shield raise fails.

**Enderman eye-contact during movement (`FollowMovementService.moveToward`):**
- `EndermanSafetyService` was wired into `FaceClosestEntity` and `LookController` but NOT into movement yaw. When chasing a zombie toward a passive enderman, the movement yaw triggered Minecraft's stare-detection. Added `deflectFromPassiveEndermen()` to offset yaw 30° when it would stare at a passive enderman.

**Respawn at world spawn (`BotPersistenceService.onBotDeath`):**
- BotSpawn config was auto-saved every 30s via `recordSpawnData()`. On death, tier 3 (BotSpawn) always resolved near the death location before failsafe world_spawn could be reached. `onBotDeath()` now calls `clearBotSpawn()` so chain falls to world spawn (or bed if set). Verified in log: `[Frens] Respawn for Jake: resolved to 0.5, 74.1, -15.5 (world spawn near 0, 74, -16)`.

**Premature combat exit fix (`BotEventHandler.updateBehavior` default case):**
- After killing a zombie, bot declared combat over while skeleton was still shooting from ~12 blocks (beyond 10-block detection range). Added 2-second "recently damaged" guard with a wider 16-block scan before returning false. Returns `true` (stay alert) even if wider scan finds nothing.

**IDLE flee behavior (`BotFleeService` — new service):**
- Idle bots flee when health ≤ 30% (any threat count), 3+ hostiles, or 2+ hostiles + no weapon. Flee direction = away from centroid of hostile positions. Sprints up to 10s or until 20+ blocks safe. 5-second cooldown after each flee. FOLLOW/GUARD/PATROL/STAY never flee. Verified in log: `Bot Jake fleeing from 4 hostiles (health=20.0/20.0)`, `Bot Steve fleeing from 1 hostiles (health=0.2/20.0)`.

### Key files changed
- `GameAI/BotEventHandler.java` — shield fix, re-engagement guard, flee integration, BotFleeService.reset on respawn
- `GameAI/BotActions.java` — added `hasMeleeWeapon()` non-mutating check
- `GameAI/services/BotFleeService.java` — **new file**
- `GameAI/services/BotCombatCalloutService.java` — hostile damage tick tracking
- `GameAI/services/BotPersistenceService.java` — `clearBotSpawn()` on death
- `GameAI/services/FollowMovementService.java` — `deflectFromPassiveEndermen()` after yaw computation

---

## 2026-03-07 — Construction parity workstream activated

Replaced the old Ralph verification checklist with a construction-parity workstream so the repo now tracks the work actually being executed.

### First implementation slice underway
- Added a shared construction tuning source for reach/scaffold thresholds (`ConstructionPlacementRules`)
- Started consolidating generic builder behavior onto the shared contract instead of per-skill constants
- Fixed the generic schematic tall-structure scaffold gate so `BuildSchematicSkill` no longer disables scaffolding solely because a target is high above the schematic origin
- Strengthened `ConstructionRecoveryService.chooseScaffoldStance(...)` with scaffold headroom validation so candidate stances more closely match the proven Fortify escalation pattern
- Added a deliberately simple `small_shelter` roof path in `BuildSchematicSkill`: roof blocks are deferred out of the normal pass and retried from two clean roof-access pillar positions instead of invoking generic target-by-target scaffold recovery throughout the shell
- Fixed the shared diagonal-corner placement failure in `BotActions.tryPlaceBlockAt(...)`: placement now tests multiple valid support blocks/faces for the same target instead of committing to a single usually-vertical support, and raycasts against denser face sample points (center, edges, corners). This directly targets the top-ring/corner `no-line-of-sight-to-support` failures seen in `small_shelter` and other shell/perimeter builds where the bot reaches a corner from the diagonally opposite side.
- Replaced the narrow roof-only fallback for `small_shelter` with a fuller phased build path in `BuildSchematicSkill`: corners first, centroid-biased non-roof fill second, and a final center-pillar completion pass from one block above the roof using `RoofAccessService`, including scaffold cleanup after descent.
- Tightened shared recovery so `CORNER` and `ROOF` targets can prefer scaffolding at `verticalDiff == 1` instead of always spending the first recovery attempt on movement-only repositioning.

### Why this slice first
- It improves the shared layer before touching shelter-specific code
- It directly targets the latest failure pattern from `small_shelter`: elevated blocks failing with `no-line-of-sight-to-support`, repeated retries, and poor scaffold engagement
- It keeps fortify as the reference implementation while moving only generic mechanics downward

## Active Work: Construction Schematic Building Reliability

### Context
The bot can build schematics (small_shelter etc.) using `BuildSchematicSkill` → `ConstructionExecutionService` → `ConstructionRecoveryService`. A protection system (`ConstructionProtectionService`) prevents the bot from mining its own structure during builds. The problem has been that the bot gets stuck wall-humping elevated targets it can't place from below, never using scaffolding.

### What's Been Fixed (all deployed, all verified by build)
1. **ConstructionProtectionService** — bot no longer destroys its own structure during builds
2. **ConstructionRepairService / ActiveBuildRepairSession** — immediate repair for movement damage
3. **isWithinReach alignment** — now uses feet-based `Entity.squaredDistanceTo` matching `BotActions.tryPlaceBlockAt`
4. **Construction scaffold overhaul (latest, 2026-03-07):**
   - `ensureReachWithScaffold` rewritten: early return threshold tightened from `verticalDiff <= 1` to `verticalDiff <= 0`; for `verticalDiff >= 2`, ALWAYS scaffold first; movement only for same-level/below targets
   - `chooseScaffoldStance` protection checks removed — scaffold stances are temporary, protection was rejecting ALL valid stances because they overlapped planned schematic blocks
   - `ensureReachByScaffolding` now falls back to `nudgeToward` when pathfinder fails through protected blocks; accepts stance within 3 blocks XZ
   - Diagnostic logging added at every decision point
   - Deploy hash: `49b11ea4d6963f3d3974c6f70cd722d26af9b6f1c85a632f0af4f3e073c7009e`

### Root Causes Found (across 4 iterations of playtest→analyze→fix)
1. Bot at Y=-59/-60 trying to reach wall/roof targets at Y=-56/-57
2. `isWithinReach` returned true from below (distance < 4.5) but placement failed with `no-line-of-sight-to-support`
3. Recovery service said "you're close enough" → placement failed → infinite retry loop
4. `isProtectedStance` in `chooseScaffoldStance` rejected ALL adjacent positions because they were planned schematic blocks → null stance → scaffold silently never triggered
5. `moveTo` for scaffold stance failed because path routed through protected blocks, with no fallback
6. Zero scaffold log entries across 3 consecutive playtests despite all threshold changes

### What Needs Playtesting Next
- Restart game, build a small_shelter schematic
- Look for `task-recovery: scaffold elevated pillar` and `task-recovery: scaffold elevated result` log entries
- Bot should pillar up adjacent to walls and place blocks at eye level
- If scaffold still fails, check logs for `task-recovery: scaffold elevated no-stance` (stance selection failing) or `task-recovery: scaffold elevated stance-unreachable` (can't walk to stance)

### Key Files
- `src/main/java/net/wcfcarolina13/GameAI/services/construction/execution/ConstructionRecoveryService.java` — reach/scaffold logic (main changes)
- `src/main/java/net/wcfcarolina13/GameAI/skills/impl/BuildSchematicSkill.java` — schematic building skill, reach handler lambda
- `src/main/java/net/wcfcarolina13/GameAI/services/construction/execution/ConstructionExecutionService.java` — pass-based execution engine
- `src/main/java/net/wcfcarolina13/GameAI/services/construction/ScaffoldService.java` — pillar mechanics
- `src/main/java/net/wcfcarolina13/GameAI/skills/impl/FortifyVillageSkill.java` — reference implementation for working scaffold patterns (L8813-8926 `ensureCanReachBlockWithEffort`, L3366-3630 `attemptScaffoldEscalation`)

### Known Remaining Issue: scaffoldCap Gate in BuildSchematicSkill
`BuildSchematicSkill.ensureCanReachBlockWithEffort` computes `heightAboveGround = target.Y - origin.Y` (schematic-relative height) and sets `scaffoldCap = heightAboveGround <= MAX_SCAFFOLD_HEIGHT(8) ? 8 : 0`. For structures taller than 8 blocks above origin, scaffoldCap would be 0, disabling scaffolding entirely. Current test structures (small_shelter) are within this limit, so this isn't blocking yet but will need fixing for taller builds.

## How This Works

Progress is tracked in THIS FILE, not in LLM context.
When context is rotated (fresh agent), the new agent reads this file.
This is how Ralph maintains continuity across iterations.

## Session History

### 2026-03-04 — Dialogue mode controls + roadmap/debug handoff

Implemented global dialogue-mode controls and centralized runtime gating so future debugging has a single set of chokepoints.

**User-facing additions:**
- `Bot Controls` now has:
	- `Global Text Dialogue`
	- `Global Voiced Dialogue`
- Voiced-dialogue tooltip now discloses current male voice set and roadmap prioritization path for alternate voices.
- `Guide` includes new `Roadmap (High-Level)` topic.

**Runtime debug chokepoints (primary):**
- `ChatUtils.sendChatMessages(...)`
	- Global text gate
	- Voice-only fallback-to-text when no mapped clip exists
- `BotDialoguePlayer.playSoundForBotDetailed(...)`
	- Global voice gate + per-bot voiced gate
- `CompanionOverheadDialogueService`
	- Overhead text gate (chat-adjacent text surfaces)

**Expected behavior matrix:**
- Text ON + Voice ON  -> normal text + normal voice.
- Text ON + Voice OFF -> text only.
- Text OFF + Voice ON -> voice only; if message has no mapped voice clip, text fallback is allowed.
- Text OFF + Voice OFF -> no bot dialogue text/voice output.

**Verification:**
- `./gradlew build -x test` passed after implementation.

**Release/deploy trail:**
- Commit: `0c5160d` on `main`.
- Pushed: `origin/main` updated from `30ca514` to `0c5160d`.
- Built artifact: `build/libs/frens-1.1.0-release+1.21.11.jar`.
- Prism deployment target:
	- `/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10/minecraft/mods/frens-1.1.0-release+1.21.11.jar`
- SHA-256 verified source==destination:
	- `08e1291b57bf9741907f5d81466f166283743cd0548e2aa4ebb767b8e788263f`

### 2026-03-05 — Crash hotfix: Admin permissions screen blur assertion

**Crash observed:**
- Client crash opening `AdminPlayerSettingsScreen`.
- Exception: `IllegalStateException: Can only blur once per frame`.
- Stack pinpointed `AdminPlayerSettingsScreen.render(...)` line with `renderBackground(...)` call path.

**Root cause:**
- `AdminPlayerSettingsScreen` used default `Screen.renderBackground(...)`, which triggers blur.
- In current UI/render pipeline this violated single-blur-per-frame guard.

**Fix applied:**
- Added `AdminPlayerSettingsScreen.renderBackground(...)` override to render a dim fill backdrop (`context.fill(...)`) instead of default blur.
- Kept existing manual panel rendering unchanged.

**Verification:**
- `./gradlew build -x test` -> BUILD SUCCESSFUL.

**Debug chokepoint for regression checks:**
- `src/main/java/net/wcfcarolina13/GraphicalUserInterface/AdminPlayerSettingsScreen.java`
- Method: `renderBackground(DrawContext, int, int, float)`.

### 2026-03-04 — Base Manager UX patch finalized (deployed + ready)

Follow-up completion pass finished:
- Replaced Prism instance jars with the latest build containing `BaseManagerScreen` panel resize + draggable scrollbar + persisted layout + reset control.
- Verified SHA-256 parity across all Prism targets (`1.21.10 TEST`, `1.21.10`, `1.21.11`).
- Changelog notes already included in `changelog.md`; branch ready for commit/push.

### 2026-03-04 — Base Manager offscreen/overflow UX fix

**Problem**: Base Manager sections could bleed below the screen on compact windows, with no robust way to access lower controls.

**Changes deployed:**
1. Reworked `BaseManagerScreen` into a movable/resizable panel window.
2. Added a clipped scrollable content region and right-side draggable scrollbar (thumb drag + track jump + wheel support).
3. Added edge/corner resize drag and header drag-to-move.
4. Added layout preference persistence at `config/frens/base_manager_ui.json`.
5. Added top-left reset layout button (`↺`) to restore defaults.

**Validation:**
- Build verified: `./gradlew build -x test` (**BUILD SUCCESSFUL**).

### 2026-03-04 — Inventory persistence failsafes (cross-world/server/jar resilience)

**Problem**: Bot persistence had three fragile edges: (1) custom inventory snapshots could overwrite newer vanilla `.dat` restore state on join, (2) world-state keys used only `levelName` and could collide across different save roots/servers, and (3) respawn BotSpawn fallback could apply a spawn record from a different level name.

**Changes deployed:**
1. `BotInventoryStorageService`
	- Added fallback snapshot resolution by recency across exact alias+uuid, alias variants, and uuid variants.
	- Added join-time stale snapshot gate (`shouldRestoreOnJoin`) that compares snapshot mtime against vanilla playerdata mtime and skips stale custom restores.
	- Expanded delete behavior to remove stale alias/uuid snapshot variants (not just one exact filename).
2. `BotPersistenceService`
	- `onBotJoin` now checks `shouldRestoreOnJoin(...)` before applying custom inventory snapshot load, logging skip reasons (`stale-or-missing-snapshot`) for diagnostics.
3. `BotWorldStateService`
	- World key now uses `levelName#<saveRootFingerprint>` instead of `levelName` alone.
	- Added backward-compatible legacy migration: if old key exists, it is promoted to the new keyed namespace and flushed.
4. `BotEventHandler.onBotRespawn`
	- BotSpawn checkpoint now rejects spawn records whose stored `levelName` doesn’t match the active server level, avoiding cross-world spawn bleed-through.

**Validation:**
- Build verified: `./gradlew build -x test` (**BUILD SUCCESSFUL**).
- Updated jar deployed to Prism instances; SHA-256 matched source build jar.

### 2026-03-04 — Skin chooser UX crowding cleanup

**Problem**: Additional skin-policy controls made the top of `Change Skin` feel cramped and allowed notice text to crowd title/content.

**Changes deployed:**
1. Increased chooser panel width/height for breathing room.
2. Added a dedicated notice band below the title.
3. Moved preview/list start below the notice band so text can’t overlap controls.
4. Wrapped policy notice to two lines max and added a compact tip when no policy warning is active.

**Validation:**
- Build verified: `./gradlew build -x test` (**BUILD SUCCESSFUL**).
- Updated Prism jar deployed; in-game screenshot confirms overlap issue resolved.

### 2026-03-04 — Bot skin policy controls + custom URL fallback hardening

**Problem**: Skin changes needed a server-authoritative policy model with two admin toggles: (1) allow everyone to change bot skins, and (2) allow custom URL skins. Existing flow was preset-only and lacked reliable fallback for non-admin custom selections when custom skins are disabled.

**Changes deployed:**
1. Added policy flags to survival recruitment world state + sync payloads (`allowEveryoneSkinChange`, `allowCustomSkins`) and client cache plumbing.
2. Extended skin payload from preset-only to typed source/value (`preset` / `custom_url`) and updated chooser UI to send typed requests.
3. Added admin actions for both toggles in admin networking/UI and wired status reporting.
4. Implemented server enforcement in skin request handler:
	- auth = admin OR `allowEveryoneSkinChange`
	- custom URL validation
	- non-admin custom fallback to safe preset (`steve`) when custom is disabled
5. Added structured skin-selection persistence (`botSkinSelections`) with legacy compatibility and metadata attribution.
6. Added reconciliation passes:
	- immediate: when custom policy toggles OFF, revert non-admin custom skins to safe preset
	- spawn-time: normalize persisted selections when bots spawn
7. Updated skin chooser UX with policy-aware controls + notice banner and custom URL field.

**Validation:**
- Fixed payload tuple-codec boolean mapping warning via explicit unboxing lambda in `RecruitmentStatePayload`.
- Build verified: `./gradlew build -x test` (**BUILD SUCCESSFUL**).

**What NOT to regress:**
- Do not rely on client UI gating for authorization; server-side policy checks must remain the trust boundary.
- Do not remove spawn-time normalization; it protects against stale persisted custom selections after policy changes.
- Keep fallback preset deterministic (`steve`) for non-admin custom disallow paths.

### 2026-02-24 — Gate-Routing v6 + Tower Stuck Timeout (iteration 6)

**Problem**: Bot stuck at wall for 50+ seconds during tower navigation. Gate routing never fired for tower approaches because `pointInConvexHull()` returns `true` for boundary points (uses `cross >= 0`). Tower vertices ARE hull vertices → always on boundary → always "inside" → gate routing skipped.

**Changes deployed:**
1. Added `pointStrictlyInsideHull()` (uses `cross > 0`) — boundary points treated as "outside" for target check
2. `tryBreakThroughObstacle` now logs diagnostic counts (allAir/canBreak/reach/noFloor) when no viable candidates found
3. Tower approach loop has 20-second safety timeout: if zero blocks placed after 20s, skip to next tower

**Expected result**: Tower approaches now trigger gate routing → bot walks through gate → approaches tower from outside → no wall collision. The 20s timeout is a safety net in case gate routing still fails.

**What NOT to try again:**
- Don't use `pointInConvexHull` (non-strict) for target check in gate routing — hull vertices are on boundary
- The 50-second stuck loops were caused by tower approach retries with hard-reset decrementing noProgressAttempts

### 2026-02-23 — Gate-Routing Navigation (4 iterations, v4 deployed)

**Problem**: During `/bot fortify patch <name> auto`, bot spawns inside walled perimeter but edge/tower approach positions are 3 blocks outside the wall. Pathfinder treats walls as impassable → bot walks into wall, gets stuck, 0 blocks repaired.

**Solution**: `navigateThroughGateIfNeeded()` in `FortifyVillageSkill.java` — detects bot inside hull via `pointInConvexHull()`, routes through the gatehouse's 3-block open gap before exterior navigation.

**Iteration history** (each deployed + tested in-game):
- **v1**: Single pathfind+walk to gate center. Failed: 78 blocks too far for one hop, exit point on hull boundary.
- **v2**: Multi-hop navigation (3 cycles), exit verification loop (6-20 blocks along normal). Fixed reach. New failure: bot at moat Y=-60, gate gap at surface Y=-58.
- **v3**: Added interior approach waypoint 3 blocks inward from gate center. Still failed: `safeSurfaceY()` returned moat-floor Y for interior (heightmap reflects post-dig terrain).
- **v4**: All waypoints (interior, gate center, exit) use `gateCenterY` from `plannedYByXZ` map. Should resolve Y-level mismatch. **Deployed but NOT yet tested in-game.**

**Code changes** (all in `FortifyVillageSkill.java`):
- `currentLayout` instance field — set/cleared via try-finally in `buildWall()` and `handlePatch()`
- `navigateThroughGateIfNeeded()` — ~100 line method with hull check, gate edge tracing, normal computation, 3-phase walk (interior→gate center→exit), multi-hop, exit verification
- Called from `navigateToEdgeApproach()` and `executeTowerVertexWithRetries()`

**Next steps for verification**:
1. Run `/bot fortify patch wall_893_-165 auto` in 1.21.10 instance with bot inside wall
2. Check logs for `[FortifyGate] Successfully exited hull` — confirms gate routing worked
3. Verify blocks actually get repaired on edges (non-zero counts)
4. If gate routing still fails, check Y-levels in log — all waypoints should be at same Y

**Known separate issue**: Tower scaffold pillar failures (`pillared=false` pattern). Most towers get 0/N blocks during patch. NOT related to gate routing — separate bug for future work.

### 2026-02-23 — Scaffold + Gate-Routing v5 (iterations 4-5)

**Changes deployed (iteration 4 — scaffold fix):**
- ScaffoldService: both `pillarUp()` and `pillarUpWithPositions()` now check if feet position is occupied (stairs/slabs) and shift target up to first air block (up to 2 above)
- Switched from `placeBlockAt` (boolean) to `tryPlaceBlockAt` (PlaceResult) for failure reason logging
- Expanded SCAFFOLD_BLOCKS: added cobblestone, stone to priority list
- Result: scaffold escalation works — edge 20 placed 2/2

**Changes deployed (iteration 5 — gate routing v5):**
- All gate waypoints use `bot.getBlockPos().getY()` instead of `gateCenterY` from `safeSurfaceY()`
- Root cause: `safeSurfaceY()` at gate center XZ hit the stone brick lintel (Y+3) and returned lintel Y, not walkable ground Y. The 2.5-block Y gap made `walkTowardBlock` (threshold distSq < 6.0) mathematically unable to ever "arrive" (Y contribution alone = 6.25 > 6.0)
- Added `gateRoutingFailures` counter: skips gate routing after 2 consecutive failures
- Result: waypoints now at correct Y=-60, gate failure caching works. But walk-through still fails on XZ alignment (separate issue — bot can't thread through the narrow 3-block gap)

**Test run results (v5, 7.5 min):**
- Edges: edge 22 complete (1 block), edge 19/18 scaffold escalation placed 0/8, edge 27 placed 0/15
- Towers: 9/30 attempted, ~4 blocks placed (tower 2: 3, tower 4: 1)
- Total: ~5 blocks repaired in 7.5 minutes

**Issues identified (NOT yet fixed):**
1. `pillarToY` treats partial success as total failure — placed 3/4 blocks → tears them all down
2. `place-rejected=class_9857[]` — Minecraft rejects placement during jump-pillar despite pre-checks passing
3. Gate XZ alignment — walkToTarget too imprecise for 3-block gap
4. Edge NO_SUPPORT — wall blocks without adjacent solid block to place against

**What NOT to try again:**
- Don't use `safeSurfaceY` or heightmap-derived Y for gate routing waypoints — the lintel skews it (v4 lesson)
- Don't use `gateCenterY` from surface profile — it's unreliable at the gap positions
- The occupied-block shift in ScaffoldService works — don't revert it

### 2026-02-22 — Admin Tab: Pathfinder Toggle + Skills Tab Cleanup
- Added `baritonePathfinderEnabled` to `ManualConfig` (persisted to JSON)
- New "Baritone Pathfinder" toggle in Admin tab — sets `PathFinder.USE_BARITONE_STYLE` and saves
- Value restored on server start from config
- `/bot config pathfinder` command also syncs to config file
- Removed 3 duplicate toggles from Skills tab (Voiced Dialogue, Teleport during Skills/Sweeps) — canonical home is Bot Controls screen
- Deleted corresponding enum values, action handlers, active-state queries, and 6 methods

### 2026-02-22 — Bot Config UI Refactor (P1, complete)
- Rewrote `BotControlScreen.java`: flat all-bots grid → single-bot view with dropdown alias selector
- 3 grouped sections (Spawning, Behavior, LLM), 8 settings including new `voicedDialogue`
- Dark bordered panel matching BotGuideScreen/BaseManagerScreen palette
- Dirty buffer preserves unsaved edits across alias switches
- Draggable scrollbar (click+drag thumb, click-on-track jump, hover highlight)
- Smooth scissor clipping (buttons slide under panel edge, don't vanish)
- Row separators, compact ON/OFF toggles without colon prefix
- Added `isOpen()` and `renderOnTop()` to `DropdownMenuWidget`
- Added "Bot Controls >" shortcut to Admin tab (top of list), reorganized admin entries by category
- Verified in-game by user — working well

## 2026-09-05 (night) — 1.1.209 shipped
FortifyVillageSkill Phase 2: FortifySharedContext/FortifyTowerContext, FortifyCleanupProcessor, FortifyTowerHelper; skill 9,560 → 7,093 lines, zero behaviour change. Commits 7e0fc6d d443d2b eeadf65 71898d5 1326524. 812 tests green. Pushed + deployed to all three Prism instances. Field check = Phase 6h.

## 2026-09-05 (late night) — 1.1.210 shipped
FortifyVillageSkill Phase 3: FortifyCarveContext + FortifyCarveHelper (carve/break-through section verbatim); skill 7,106 → 6,185 lines, zero behaviour change, reviewer-regenerated diff clean. Commits 64e4a70 e5af47e f3680ac. 812 tests green. Pushed + deployed to all three Prism instances. Field check = Phase 6i. Fortify extraction complete — no further phases.

## 2026-09-06 — 1.1.211 shipped
Phase 3 ontology spec APPROVED by Bradley (all 5 recommended answers). Item (d) novelty rejection: SoulNoveltyPolicy (trigram ≥0.6 for ≥8 content words + ≥3 trigrams both sides, exact below; ring 12 per bot; per-scene rule) in SoulGroupConversationService between parse and enqueue, behind soulNoveltyRejectionEnabled (default false) + /bot soul novelty. Commits 3017061 5fb89cf 0e66381. 812 → 831 tests green. Pushed + deployed to all three Prism instances. Field check = Phase 6j (142 items). Next autonomous: Phase 3 (a) peer stance.

## 2026-09-06 (evening) — 1.1.212 shipped + deployed
- Phase 3 (a) bot↔bot peer stance: a169568 feat, 07b7ccc fix (per-day ask guard, log hoist), 169a84a release. Suite 831 → 846. Deployed to all three Prism instances (game closed, natives pgrep clean). Phase 6k added to FIELD_SESSION_1.1.202.md (147 items). Next: (b) typed relations.

## 2026-09-06 (night) — 1.1.213 shipped + deployed
- Phase 3 (b) typed relation facts (SEEN producer only): 17cdb518 feat types/ops/consolidate, 87f0b682 feat BELIEFS/anchors/commands/toggle, 211169f5 fix wave (task-suffix bucketing, drop malformed facts, whole-block budget, recall cooldown wired, merged-count log, weighted anchors), fbbc9c4b reject '|', 33b80177 release, 0963fbb3 docs. Suite 846 → 874. Deployed to all three Prism instances (game closed, natives pgrep clean). Phase 6l added to FIELD_SESSION_1.1.202.md (153 items). Next: (c) structured output — fresh session.

## 2026-09-06 (late) — 1.1.214 shipped + deployed
- Phase 3 (c) structured LLM output: `SoulSideChannelOps`, lenient `##FRENS` sentinel in the validator, `SideChannelSink` seam in `SoulGroupConversationService`, `SoulMindOps.bumpPeerStance`, toggle `soulStructuredOutputEnabled` (off) + `/bot soul structured`. Commits 8208bbb1 (feat), 04f3d382 (fix wave), 37bfb32b (release), 2519e73b (docs). Suite 874 → 900. Phase 3 COMPLETE; Phase 6m added to the field checklist (161 items).
- 1.1.215 shipped + deployed: leading list bullets stripped before `##FRENS` (2dd32cc2 fix, 8db99c7a release). Suite 900 → 903.

## 1.1.216 — speech floor + idle-hobby backoff (2026-09-07)

First build driven by a real field log since 1.1.184. Source: `logs/2026-09-06-session-overlap.log.gz`.

- Two read-only scopers ran in parallel. The speech-floor scoper confirmed no speaking-floor state existed
  anywhere; the hobby scoper REFUTED the backlog hypothesis and found the actual two-tick wipe.
- Implementers: `075ac6b2` speech floor (903 → 912), `cd4120ca` hobby backoff (→ 926), disjoint file sets.
- One batch review (opus) → fix wave `38c91e1f` (8 findings, → 937) → ladder correction `6ae01c24`.
- Scoped re-review found a Critical the controller's own fix-wave ruling had introduced: resetting the
  backoff from the position-dependent tool signature. Fixed in `59327993` (→ 945).
- Release `a2ffd7ac`, pushed, deployed to all three Prism instances, verified with unzip.
- Two implementer agents stalled on the same task (watchdog, no progress for 600s). The second stall left
  complete, correct edits on disk; the controller read the full diff, built, tested and committed them.

Field checks: Phase 6n, 11 items, doc now 172. Everything remains field-blocked behind Phases 6b–6n.
