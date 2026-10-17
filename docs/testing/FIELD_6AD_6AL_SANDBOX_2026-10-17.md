# Field phases 6ad-6al in the sandbox: Frens 1.1.261, driven by Claude (2026-10-17)

Claude ran the older checklist phases 6ad-6al (builds 1.1.232-1.1.241) of `FIELD_SESSION_1.1.202.md` in the `mcp-sandbox`
Prism instance over the MCP API. JAR `frens-1.1.261-release+1.21.11.jar` (md5 29b931c8…, same as Nirn's). Nirn was never launched;
its items were read from its logs and config only.

Worlds:

| Session | Save | Commands | Used for |
|---|---|---|---|
| 1 (11:06-11:18) | `New World` as is | off | host admin gates, throttle, roster, Jake's climb-out |
| 2 (11:18-12:24) | `Field CMD`, a fresh copy of `New World` with `allowCommands=1` | on | woodcut, cleanup, craft, Spawn trees, shelters, navigation, multi-bot |
| 3 (12:25-12:36) | `Field Pocket`, a copy of `New World (1)` with `allowCommands=1` | on | 6aj-2 pocket, 6aj-3 backoff, 6ak-3 restart |

A copied save gets a new world identity (`New World#eab43694`), so Frens treats it as a brand-new world: its own Spawn base,
no companions, the Admin-mode line. That made 6af-2 and the new-world roster states testable without the title screen.

Server pauses (`frens_log_report.py`): 11:19:39 and 11:55:40 in session 2, both while a GUI screen was open for a GUI check,
none during a timed behaviour check. `[bot-access] bot-slot mismatch` 0 and `[bot-access] denied` 0 in all three logs, no ERROR lines.

How GUI items were reached: `/bot open <bot>` (bot within 8 blocks) opens the bot inventory, and its Actions header leads to the
Admin tab (Player Permissions, World Settings, Change Skin, Lock Blocks Mode). The house icon opens the Base screen with the zone panel.
`/configMan` opens Bot Controls, and "Spawn Bots..." opens the roster. The MCP cannot send key binds, right-clicks or hover,
so zone-wand corners and tooltips were not testable.

## Verdicts

41 items: 25 PASS (ticked in the checklist), 4 PARTIAL, 6 FAIL, 5 not testable in the sandbox, 1 superseded.

| Item | Verdict | Evidence (log time, session) |
|---|---|---|
| 6ad-1 Host with cheats off passes admin gates | PASS | S1, commands off. `/bot soul status` and `/bot learn status` answered (11:07). Admin tab visible. Skin set to alex (`Applied skin 'alex' to bot 'Smokey'` 11:10:27). World max base radius 120/128 (11:10:44). Spawn base radius 41/40 in the Base screen (11:15:02). Lock Blocks Mode toggled twice. New Zone gave the wand (`Zone Wand given` 11:15:17). The Guide shows the admin-only "Direct Mode" topic. "World Mode Reset" is hidden for everyone while Questing is off (1.1.235 by design). No "operator" refusal anywhere. Not tested: wand corner right-clicks. |
| 6ad-2 Ops see the Admin tab on a server | NOT TESTABLE | Needs a dedicated server op or a LAN guest. The sandbox is one client. |
| 6ad-3 Companion owner after a recruitment reset | NOT TESTABLE | Needs a guest player and a Questing world. |
| 6ad-4 Wall grantees fortify | NOT TESTABLE | Needs a guest player. |
| 6ad-5 Skins are owner-checked | NOT TESTABLE | Needs a guest player. |
| 6ad-6 Throttle notice | PASS, note | S1. 30 clicks at the MCP's ~10/s never throttle: the bucket is 20 tokens with a 100 ms refill, so a human clicking 30 times will not see the line either. A 60-click burst: 26 accepted, exactly one `Slow down, that click was ignored.` |
| 6ad-7 No bot-slot mismatch warnings | PASS | 0 in all three logs (every host button in 6ad, 6ai, roster, Base screen). |
| 6ae-1 Unowned bots refuse Set Home / Go To | NOT TESTABLE | Needs a guest player. |
| 6ae-2 Skin toggle gone | PARTIAL | S1. No "allow everyone" row in the Admin list or Player Permissions (only Open Skin Chooser and Allow Custom URL Skins). Guest half not testable. |
| 6ae-3 Leaves drop saplings, teardown uses hands | **FAIL** | S2, Shea with shears, stone pickaxe, stone axe and cobblestone. Cobblestone scaffold came down with the pickaxe (15 ticks each, 11:30:43-49): pass. But 13 leaf blocks were mined **with shears** during felling and pillar overhead clearing (11:30:20-27, 11:31:12-13), and Shea ended with 13 `oak_leaves`. Bug A. |
| 6ae-4 Crafting table line | PASS | S2, Tabby with 7 planks and 2 sticks outside the base: `Crafted and placed crafting table at 66, 80, 6`, chat only `Crafted 1 Wooden Axe.` (11:24:38), no `/bot place` hint. The "Crafted N crafting table(s)." chat line was removed on purpose in 1.1.234 (`172f7b92`, "sub-step table craft no longer chats"). |
| 6af-1 Nirn keeps its zones and locked doors | PASS (read-only) | Nirn `2026-10-16-1.log.gz` 20:00:06 (first launch after 1.1.234, on 1.1.255): `Adopting legacy protected zones … into this save (Nirn#4a9f00d9)`, `Loaded 4 protected zones`, `Adopting legacy locked blocks`, `Loaded 3 locked blocks`. `bot_zones/minecraft_overworld__41d0e9b1/claimed_by_world.txt` = `Nirn#4a9f00d9` (Oct 16 20:00). Same counts on the next launch (20:02:33). |
| 6af-2 A new world starts clean | PASS | S2 11:18:53: `Auto-created server-owned 'Spawn' base at 0, 73, 0`, no old zones or locks loaded, one auto-zone. The chooser criterion was replaced in 1.1.235 by "no chooser, Admin line" (6ag-4). |
| 6af-3 First axe on the ground | PASS | S2 Fern, empty inventory, 11:25:41-11:26:28: `punching logs bare-handed`, `not pillaring (fell-ascent)`, table `outcome=ready`, `Crafted and placed crafting table at -56, 85, 4`, `crafted an axe, back to normal woodcut`. No `Pillar stock: crafted` before the axe. Zero table chat lines. |
| 6af-4 Scaffold breaks at survival speed | PASS | S2 Dirk: `/bot stop` mid-run left a dirt pillar at 152,80-87. `woodcut_cleanup` found it and removed it (11:33:50-11:35:58, "Cleaned up 3 logs and 10 scaffold blocks."). Each block took 15 ticks by hand (75 ticks when mined mid-air). No `surface-pillar: remove` burst. Note: the cleanup rebuilds a pillar to reach each higher block, so it is slow. |
| 6af-5 No canopy pillar at cleanup start | PASS | S2 11:38:24, 11:39:41: `ensureAtSurface:current: ready … canopyOnly=true` under a leaf roof, no pillar recovery. |
| 6af-6 Cleanup finishes and says one sentence | **FAIL** | S2. Wording is right ("Nothing left to clean up here.", no stats). On a bare pad the empty verdict came in 1 s (11:41:48). In untouched forest at 400,96,-298 it took **127 s** (11:45:14 to 11:47:21): the fast scan repeated every 3 s and the async scan never merged until the deadline. Bug B. |
| 6af-7 Drop sweep, return and craft feedback | **FAIL** | S2. Sweep: "Picked up 3 items." for 3 stacks holding 6 items (11:48:40), no fast-travel error, no return trip. Craft with no wood (Ned, 11:50:35) answered with **two** lines: "I need 4 planks per craft." and "I need 3 planks and 2 sticks for a wooden axe. I have none of that. I'd also need a crafting table nearby." Bug C. |
| 6ag-1 Table placement never blocks itself | PASS | S2 11:26:26 `Utility placement summary [crafting_table]: outcome=ready`, then placed. No `bot-intersects-target` in any log. |
| 6ag-2 Bootstrap gather outside the Spawn base | PASS | S2 11:25:41 `skipped 75 protected cell(s) near 0, 73, 0`, then 11:26:14 `gathered 4 more hand scaffold block(s) at tree -55, 85, 5`. No `Cannot mine: protected claim`. |
| 6ag-3 Plain tree lines | PASS | Chat "Got 3 logs from that tree.", "Got 6 logs from that tree.", "Got 3 logs, the rest of that tree needs an axe." `Woodcut target cleared (…)` only in the log. |
| 6ag-4 Questing hidden | PARTIAL | S2: no chooser, one line "Frens is in Admin mode: open the roster with [-] to bring in a companion." (11:18:55), roster opens, `/bot recruit enable` answered "Questing mode is turned off on this server." (11:20:45). Not tested: an existing Questing world still recruits (no Questing save). |
| 6ag-5 Craft names | PASS | S2 11:23:28 "Crafted 1 Wooden Axe." |
| 6ah-1 Climb out of a shallow open-sky hole by day | PASS | S1 Jake, sheltered overnight in his dig-down. At dawn 11:12:00: `broke cap of dig-down shelter`, `linger: allow — shallow open-sky hole by day, climbing out (depth=4 scaffold=10)`, ascent mode, `Reached surface with open sky after 3 steps` (11:12:01). He climbed out by ascent, not `pillar recovery`. |
| 6ah-2 Night bunker registers and holds | PASS | S2 dusk: Dirk, Tabby, Sprout `dig-down cap placed … covers=true` (12:04:30-31). No `no_overhead_cover`. They held all night, then `breaking free from tactical shelter (daylight, tod=1003)` and `dig-down dawn exit` (12:07:43). Note: Crafty's cap was `place-rejected` twice inside the host base (12:04:30), so it climbed out and retried. That reason comes from vanilla `blockItem.place` not accepting the placement (`BotActions.java:1086`), not from the territory check. |
| 6ah-3 Roster shows this world only | PASS | S1: "All your companions are already here", "Other worlds (6)", Create inactive for "Abby" and active for "Zelda". Abby came in with `inventory: []`. S2 (new world): "No companions in this world yet." No `roster world scope not ready`. Tooltip not checkable (no hover). |
| 6ai-1 Fresh world: bot walks out of the Spawn base | **FAIL** | S2 Sprout, two trees planted inside the base: `protectedFail=10 protectReasons=spawn-base=10`, chat "The trees around spawn are protected, so I'm heading out to find others." once (11:51:59), no `Cannot mine`. But all 3 relocation walks to trees 68-114 blocks away ended `direct: deadline`, then "No valid trees nearby. Try moving closer or adjust radius." (11:52:26). It repeated on a rerun (11:53:40). Zero logs from outside the base. Bug D. |
| 6ai-2 Admin toggle lets bots cut Spawn trees | **FAIL** | S2: World Settings set "Cut trees in the Spawn base: On" ("Bots may now cut trees in the Spawn base." 11:55:41). Sprout did break the Spawn logs (7 oak_log in inventory), but every log logged `miner refused … ('⚠️ Cannot mine: protected claim.')` after a full 60-tick break (11:56:05-08 …). Woodcut counted 4/4 refused per tree (`failedBaseCount=3`) and ended "I punched enough wood for an axe but couldn't craft one…". Table placement in the base was refused (expected, like dirt). Bug E. Non-admin half not testable. |
| 6ai-3 Own base over spawn still works | PARTIAL | S2: `/bot base set HostBase` at 30,73,0 overlapping Spawn. The host's bot mined 32,74,3 inside it with no protected-zone refusal. `/bot place torch` is not supported ("I can't place torch yet."). Nirn half not testable. |
| 6aj-1 Approach never hand-mines stone | PASS | S2 Stony (stone axe, no pickaxe) against a 3-high stone ring: unified `terrain move: WALK_AROUND … breakTicks=300`, stone left alone, moved to another tree (12:00:31-38). Zero bare-hand mines of 100+ ticks in any log. The `movement obstruction mine refused … too slow by hand` line belongs to the pre-unified mover and does not fire under unified. |
| 6aj-2 Woodcut gives up on trees it can't reach from a pocket | PASS, note | S3 Hole, no axe, no blocks, at 20,62,172 (Spawn base shrunk to r=8 first): the run ended in 14 s with "I couldn't reach the surface to cut trees." (12:30:09) and underground survival took over. It left at the surface pre-check, not the 4-stands path, so "I'm stuck down here and can't reach any trees." did not show. Side finding F. |
| 6aj-3 Stuck bot with nothing to build with backs off | PASS | S3 Hole: `Stuck-escape backoff: … failed surface recovery 2 times … pausing retries for 30 s` (12:27:38), then 60 s (12:28:20). One chat line "I'm stuck in a hole with nothing to build with." Honest `Vertical ascent step: no climb (landed at Y=…, nothing placed)` lines. `/bot stop` cleared the backoff (12:28:52). The "after respawn from a death" clause was not exercised. |
| 6aj-4 Hunger said in chat, pause on time | PASS | S2 Stony, 56 blocks away: "I'm getting hungry and I have no food." at food 10 (12:01:48). "I'm starving and out of food, so I stopped cutting. Feed me, then /bot resume." 15 s after food 5 (12:02:25). |
| 6aj-5 Roster empty state and voice stubs | PASS | S2 new world: title "No companions in this world yet." vs placeholder "(Your saved companions are in other worlds.)". S1 all-here state: different lines too. `Missing sound for event` = 0 on a fresh launch. |
| 6ak-1 Bots walk around, break soft blocks or place a dirt step | PARTIAL | S2 report: `terrain move result: WALK_AROUND ok=true displaced=true` 12 of 13, 0 `not a standing cell`, 0 `A* pathfinding timed out`, unified p90 27 ms. One BREAK `ok=false` (Stony in a flooded pit, 12:03:07). No PLACE step seen. The Baritone p90 criterion is moot since 1.1.261. |
| 6ak-2 Wading through shallow water | PASS | S2 walled pad where a 1-deep, 4-wide stream is the only route: Wade crossed and said "Got 5 logs from that tree." (12:11:49), no swim or dive. Unified has no `Path wades` line (classic and Baritone only). 2-deep refusal not retested. |
| 6ak-3 Baritone default migrated once | SUPERSEDED / PARTIAL | 1.1.261 made unified the default with its own one-time migration (Phase 6ba). Nirn read-only: `settings.json5` has `baritoneDefaultMigrated: true`, `baritonePathfinderEnabled: true`, but no retained Nirn log contains the 1.1.240 line. Sandbox: `/bot config pathfinder classic` survived a restart ("Current pathfinder: classic (old)" 12:25:49, no migration line), then set back to unified. |
| 6ak-4 Escape after a fight underground | PASS | S2 Cave in a sealed pocket: `/damage` from a zombie, "Terminating all current tasks due to threat detections" (12:14:42). It held while I stood next to it (`commander nearby`). After I left: `linger: bypassed` (12:15:52), `reached operational surface via pillar recovery` (12:16:28). After a real `/bot stop` in the pocket: `holding still after a stop command until the next command`, and it stayed. No stale latch was left this time, so `cleared a stale abort latch` was not exercised. |
| 6al-1 Several bots spread across one forest | **FAIL** | S2 Ash, Birch, Cedar on six planted trees. `tree 153, 80, 200 is claimed by Ash, picking another` (12:20:08) works, and there was no single-file queue. But Cedar then selected `base=153, 81, 200`, the same tree one log up (12:20:09). Ash selected `142, 84, 200` while Birch was felling `142, 80, 200` (12:20:43-50). Birch logged `logsCut=10` on a 5-log tree. Bug G. |
| 6al-2 Creeper backoff line | PASS | S2 Ash: "A creeper! I dropped what I was doing to get clear." once (12:22:42), no `creeper-fuse`, not resumed. Side note: `[creeper-defense] Ash released (resolved, after 74854 ticks)` prints a wrong tick count. |
| 6al-3 Quiet torch-hold log | PASS | `frens_log_report.py`: torch-hold peak 37/min (12:03, ten bots seeking shelter at once), about 4/min during woodcut, not in the >60/min spam list. |

## Bug notes (fixes go to a separate numbered build)

**A. Shears used on leaves outside cleanup (6ae-3).** The 1.1.233 fix made cleanup and teardown pick tools by block tags, but felling and
`ScaffoldService.pillarUp` overhead clearing still go through the default best-tool pick, which chooses shears for leaves.
```
[11:30:20] Mining class_2338{x=152, y=86, z=0} with minecraft:shears (delta=2.5000, ticks=1, …)
[11:31:12] pillarUp step 1/3: overhead blocked at 159, 74, 1 — attempting to mine
[11:31:12] Mining class_2338{x=159, y=74, z=1} with minecraft:shears (delta=2.5000, ticks=1, …)
bot_state Shea: … {"id":"minecraft:oak_leaves","count":13} …
```

**B. Empty cleanup takes two minutes in forest (6af-6).** In live forest the async target scan (`WoodcutCleanupSkill.collectCleanupTargets`)
never completes, so the loop waits on it until `durationMs` runs out. On bare ground it finishes in a second.
```
[11:45:18] woodcut_cleanup starting: origin=400, 96, -298 radius=18 vertical=20 … sweep=true
[11:45:19] woodcut_cleanup fast scan: rememberedTargets=0 overheadTargets=0 attemptedTargets=0 minedLogs=0
… the same line every 3 s, no "async scan merged" …
[11:47:19] woodcut_cleanup scaffold scan: found 0 candidates … (scanned 58867 blocks)
[11:47:21] Skill 'woodcut_cleanup' exit: success=true … message='Nothing left to clean up here.'
```

**C. Two chat lines for one failed craft (6af-7).** The table sub-step still chats its own refusal before the main reply.
```
[11:50:35] [CHAT] I need 4 planks per craft.
[11:50:35] [CHAT] I need 3 planks and 2 sticks for a wooden axe. I have none of that. I'd also need a crafting table nearby.
```
Related: a failed 3-plank table attempt arms the 30 s table-craft cooldown. The retry with 7 planks then said "I need a crafting table
placed nearby to craft that." (11:21:20). The drop sweep counts stacks, not items ("Picked up 3 items." for 6 items). A trailing bot name
on `/bot craft <item> <bot>` is parsed as the material argument (`material 'wood' in the item name overrides preference 'Nowood'`).

**D. Relocation gives up before reaching distant trees (6ai-1).** Each relocation walk has a short deadline, so a 68-114 block trip
ends after about 30 blocks, and three hops leave the bot short of trees with nothing in detect range. Same map and symptom as
finding #2 of `PLAYTHROUGH_1.1.234_SANDBOX.md` (2026-10-14), still open.
```
[11:52:00] Woodcut relocation: broad sampled tree at -69, 74, -68 (dist=96) from 0, 73, 0
[11:52:06] Woodcut relocation: walk to -69, 74, -68 failed: direct: deadline plan=p69
[11:52:16] Woodcut relocation: walk to 10, 74, -124 failed: direct: deadline plan=p70
[11:52:26] Woodcut relocation: walk to 22, 73, -104 failed: direct: deadline plan=p71
[11:52:26] [CHAT] No valid trees nearby. Try moving closer or adjust radius.
```

**E. Spawn-tree toggle: the miner reports a refusal after the log is already broken (6ai-2).** `MiningTool` re-checks
`BotTerritoryAuthorizationService.authorizeBlockMutation(…, isTreeBlock(serverWorld.getBlockState(targetBlockPos)))` every tick
(`MiningTool.java:246`). On the tick after the break the state is air, so the check runs as a non-tree mutation in the Spawn base and
completes the future with "Cannot mine: protected claim". Woodcut then treats every log as refused.
```
[11:56:05] Mining class_2338{x=4, y=73, z=12} with bare-hands (delta=0.0167, ticks=60, ~3000ms, creative=false)
[11:56:08] Woodcut: miner refused 4, 73, 12 ('⚠️ Cannot mine: protected claim.'); not retrying it this run
[11:56:17] Woodcut target 4, 73, 12 selected but trunk mining refused (protected) for 4 of 4 log mine call(s)
bot_state Sprout: … {"id":"minecraft:oak_log","count":7} …
```

**F. Unified planner plans an ascent it cannot do (side finding, 6aj-2).** With zero blocks, the plan includes an ASCEND that live cost
verification forbids, and the replan returns the same plan.
```
[12:29:56] walkTo leg cancelled: cost verification move 3 ASCEND now forbidden [direct] bot=Hole plan=p12 planned=5.6 live=1000000.0
[12:29:56] walkTo unified [direct]: bot=Hole plan=p13 status=FULL … replans=1
[12:29:56] walkTo leg cancelled: cost verification move 3 ASCEND now forbidden [direct] bot=Hole plan=p13 planned=5.6 live=1000000.0
```

**G. Tree claims are keyed on the base block (6al-1).** After the bottom log is cut, the same tree is detected with a higher base and
can be claimed by a second bot. This is what is left of finding #19 of `PLAYTHROUGH_1.1.234_SANDBOX.md` (bots queue on one tree), which
1.1.241 fixed for the untouched base.
```
[12:20:06] (Ash)   Woodcut: selected tree target base=153, 80, 200 top=153, 85, 200 height=6
[12:20:08] (Birch) Woodcut: tree 153, 80, 200 is claimed by Ash, picking another
[12:20:09] (Cedar) Woodcut: selected tree target base=153, 81, 200 top=153, 85, 200 height=5
[12:20:43] (Birch) Woodcut: selected tree target base=142, 80, 200 top=142, 84, 200 height=5
[12:20:50] (Ash)   Woodcut: selected tree target base=142, 84, 200 top=142, 84, 200 height=1
[12:20:57] (Birch) Woodcut target cleared: base=142, 80, 200 logs=5 logsCut=10 felled=1 target=4
```

Earlier findings that now pass: #14 (bare-hand stone on approach) is 6aj-1, #17 (no-block escape loop) is 6aj-3,
#18 (`creeper-fuse` in chat) is 6al-2, #11 (roster says it twice) is 6aj-5, #10 (missing sounds) is 6aj-5.

## Test-method notes

- A save copied to a new folder gets a new world identity, so it starts with an empty roster and its own Spawn base. Use that for new-world checks.
- `/fill` silently fails over 32,768 blocks ("No blocks were filled" only in chat). Split big clears.
- Test pads built in the air let bots walk off the edge. `pad.py` now fills stone under the pad.
- The framebuffer size can change between launches (1708x960, then 854x480), so scale pixel clicks from `screen_info.fbW`.
- Removing many logs near a test area causes mass leaf decay, and the sapling drops then pull bots into drop sweeps.
