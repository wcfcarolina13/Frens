# Live playthrough: Frens 1.1.233 (2026-10-14)

Nirn instance (folder `1.21.11`), new world "test 1 (1)", Survival, Allow Commands OFF. Bradley plays, Claude reads `latest.log` + F2 screenshots.

## Passes

- Join with no "server is full": `Auto-spawn deferred until the first real player joins (integrated server).`
- `/bot config diagnostics on` works for the commands-off host ("Verbose diagnostics: ON").
- First-run hint teaches both meanings of `-`. After the first bot, it switches to "Now [-] sends a companion where you look. Roster: Guide (]) > Admin > Bot Controls > Spawn Bots".
- 6ac item 1 (partial): Stop and Follow toggles from the UI ran with no `[bot-access]` refusal (15:57:45-15:58:04).
- Roster Spawn Selected spawned Jake (`/bot spawn Jake admin`), fresh state, no errors.
- Follow ON from the UI. Woodcut from an empty inventory punches logs bare-handed (1.1.229 bootstrap) and crafts planks in the 2x2 grid (vanilla-correct, no table needed).

## Findings

| # | Finding | Evidence | Severity |
|---|---|---|---|
| 1 | **Axe bootstrap fails up a tree.** Jake pillars up the trunk while punching, then tries to craft the axe at y127 on his pillar. Crafted a table, couldn't place it (`placement-support-missing=31, no-stand-to-carve=15`), so "the axe craft failed". The table is real (inventory at 15:54:47: 7 planks, 1 log, 1 crafting_table), so crafting isn't superficial. The bootstrap just never retries: back on the ground he keeps punching trees bare-handed with the table in his pocket. Fix: place the carried table and craft the axe once grounded, and try the bootstrap before pillaring. | log 15:54:14 `Woodcut axe bootstrap: Jake had enough wood but the axe craft failed` | 🔴 High (first impression) |
| 1b | **Primal stage ordering (Bradley's ruling).** Without an axe the bot burns logs into plank scaffold (`Pillar stock: crafted 8 planks from logs for scaffold`, 15:54:10), then hand-breaks 7 planks (`scaffoldRemoved=7`, ~3 s each). Rule: with no axe, the first wood goes to table + sticks + wooden axe, crafted on the ground, before any pillaring. Grab a few soft, non-falling blocks early as scaffold (dirt, grass, coarse dirt, podzol, mud, clay, snow block: fast by hand) instead of planks. Never gravity blocks (sand, red sand, gravel, concrete powder). Trees reachable from the ground first. | log 15:54:09-15:54:57 | 🔴 High |
| 2 | **Chat spam on that failure:** "Crafted 1 crafting table(s).", "I need a crafting table placed nearby…", then "I couldn't place a crafting table here." x5 in the same second. | log 15:54:14 | 🟡 Medium |
| 3 | **Cross-world leak:** a brand-new world logs `Synced 1 auto-zones from registered bases`, and the roster offers Bob and Jake under "Choose Companions to Restore" (they have no state in this world). Candidate for Aiwan's "brand-new worlds also full". | log 15:51:38, screenshot 15.52.29 | 🟡 Medium (investigate) |
| 4 | No World Mode chooser seen before the first spawn (roster spawn went straight to `admin` mode). Check whether that's intended for a commands-off host. | log 15:52:36 | ❓ check |
| 5 | **Red fast-travel error after a drop sweep.** Post-task return tried to *fast-travel* Jake to 6,120,-7, about 10 blocks away, and printed in red "Jake needs a Map + Compass … or a smoke signal … to fast-travel here." A short return should just walk, and a new player shouldn't see fast-travel gear errors at all. | log 15:58:10, screenshot 15.58.12 | 🟡 Medium |
| 6 | **Drop sweep reports "collected: 0"** after picking up a sapling, 2 logs and a stick (it counts only the final pass). | log 15:58:08-10 | ⚪ Minor |
| 7 | **Scaffold teardown breaks blocks instantly (creative-like).** `ScaffoldService.teardownScaffolds` calls `bot.interactionManager.tryBreakBlock(pos)` with no mining time (the comment even says "Breaks here are instant"). Jake removed a 6-block plank pillar bare-handed in ~2 s (`surface-pillar: remove … removed=1`, 15:58:43-45); planks by hand take ~3 s each. Should go through `MiningTool` timing with the right tool. Other `tryBreakBlock`/`breakBlock` sites to audit in scoping: `BotActions` (x2), `DoorwayAccessService`, `MountedLeafClearingService`, `modCommandRegistry`. Also: `ensureAtSurface` pillared 6 up under canopy (`openSky=false`) at the start of `woodcut_cleanup`, then tore it straight down, pointless. | log 15:58:41-45, `ScaffoldService.java` teardownScaffolds | 🔴 High (fairness) |
| 8 | **Scaffold left behind, untracked.** At the second tree the pillar reported "Failed to place scaffold block" three times in one second, yet the stock dropped 9 → 6 → 5 → none ("No valid scaffold blocks available", 15:56:56). Hypothesis: the placements succeeded but were reported as failures, so they never entered the scaffold ledger and nothing removes them. Verify in scoping: compare the world blocks at 10,119..122,-22 with the ledger. | log 15:55:23-15:56:56 | 🔴 High |
| 9 | **`woodcut_cleanup` scans for 80+ s and finds nothing.** 244 `fast scan` passes with `rememberedTargets=0 overheadTargets=0`, plank scaffold visible nearby. The scan only seems to look for remembered logs/overhead targets, not placed planks, and the ledger died with the aborted woodcut. It also loops with no "nothing to do, done" exit. | log 15:58:45-16:00:07 | 🟡 Medium |
| 10 | **Developer stat dumps in player chat.** `woodcut_cleanup` timed out after 2 min and posted "Cleanup incomplete: minedLogs=0 removedScaffold=0 attemptedTargets=0 … rejectedFullTree=55 …". Drop sweep posts "Swept drops within radius 8.0 (8.0 vertical) across 1 pass(es)…". Both should be one plain sentence ("Nothing left to clean up here."), stats to the log only. | log 16:00:46, 15:58:10 | 🟡 Medium |

## Session summary

World "test 1 (1)", 15:51-16:01, one bot (Jake). No Frens exceptions or errors in the log; clean save and quit. The player saw 8 Jake lines in one second at the failed axe bootstrap (#2). 6ac item 1 only partly run (Stop, Follow); Guard, Skills/hobby toggles, come/sleep, Crafting History and the 6ad throttle are still open.

## Environment notes

- Fullscreen toggle mid-run wrecked the window (Sodium/Iris/DH). Fixed by `fullscreen:false` in `options.txt` before launch.
