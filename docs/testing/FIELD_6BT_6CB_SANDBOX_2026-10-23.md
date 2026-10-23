# Field phases 6bt-6cb sandbox sweep, Frens 1.1.292 (2026-10-23)

Sandbox instance `mcp-sandbox`, save "Sweep Souls3" (commands on, fingerprint `New World#3644a587`), jar `frens-1.1.292-release+1.21.11.jar` built from main `1df93b0f` (md5 `92e9df560a31569577b9fe831d21e147`, same as the jar already deployed). Muted, no Carpet, peaceful rules (no day cycle, no mob spawning), easy difficulty. One game launch, 12:13 to 13:03. Claude drove the game through the hardened MCP fork. Replay `frens_1_1_292_6bt_6cb_sweep` (one recording for the whole launch). Repo drivers: `fix281.py craft`, `fix280.py canopy|follow|aid` (new `aid swordbow` variant added this sweep). Scratch drivers (session scratchpad, not in the repo): `h.py` helpers, `w.py two|hotbar`, `s.py strip`, `bee.py`, `bury.py`, `sun.py`, `sup.py setup|once|stop|double`, `can.py`; log copy `session1.log`. Error scan: 0 ERROR or Exception lines in the session log.

Phases 6bt-6cb hold 32 items: 9 PASS, 2 PASS with a gap, 2 FAIL, 12 NOT RUN, 7 only Bradley can do.

## Summary

| Phase | Item | Result |
|---|---|---|
| 6bt | Woodcut cleanup with a full hotbar of tools and weapons | PASS |
| 6bt | Full-pack stone tool refusal says nothing about cobbled deepslate | PASS |
| 6bt | Store chest placement miss is logged | NOT RUN (no store run happened) |
| 6bu | Fortify cavity callout spoken aloud | Bradley (needs ears) |
| 6bu | Flower gift skips a recipient with a full pack | NOT RUN |
| 6bv | Sunset return to an owner more than 48 blocks away | PASS (third try, first two were my setup) |
| 6bv | Voice mute toggles | Bradley (needs ears) |
| 6bw | Starving strip-mine bot reaches a faraway owner | PASS |
| 6bw | "Allow once" grant while the owner is away | NOT RUN (2 setup tries) |
| 6bw | Collect re-checks permission on arrival | Bradley (second player) |
| 6bw | Hand-placed chest on a destroyed record stays unlisted | NOT RUN |
| 6bx | Follow through oak leaves at a step up | **FAIL** |
| 6bx | Bow-only defender sidesteps, sword-and-bow defender melees | PASS |
| 6bx | Mounted bot under leaves | Bradley (he rides) |
| 6bx | Supply ask counts a double chest once | NOT RUN (2 setup tries) |
| 6bx | Settings file no longer pins the default pathfinder | PASS (file-save path) |
| 6by | RISKY: two bots on one felled tree leave no dirt and no hole | PASS, 1 run of the 2-3 asked (not ticked) |
| 6by | RISKY: non-owner can't rename or delete a zone | Bradley (second player) |
| 6by | RISKY: only a bot owner, op or host maps a village | Bradley (second player) |
| 6by | RISKY: ownerless wall claimable only by op or host | Bradley (second player) |
| 6by | A stopped tool search stays stopped | PASS |
| 6by | Double chest half in an unloaded chunk is skipped | NOT RUN |
| 6bz | RISKY: fortify or shelter places onto short grass, snow, carpet | NOT RUN |
| 6bz | `/bot place` onto snow in one command | **FAIL** |
| 6bz | Automatic return refused for combat says so once | NOT RUN |
| 6bz | Oak canopy counts as surface, a leaf-capped cave does not | NOT RUN (trigger not reached) |
| 6ca | RISKY: buried bot dug out cleanly | PASS (log criteria) |
| 6ca | Resume waits for the surface | NOT RUN |
| 6ca | Mining tool swaps while walking | PASS for logs and dirt, no stone met (not ticked) |
| 6cb | RISKY: cleanup at the edge of loaded terrain says it was partial | NOT RUN |
| 6cb | Logs next to a broken bee nest get cut again | PASS |
| 6cb | Mutual aid waits for a commanded job, not an idle hobby | NOT RUN |

## FAILs for a fix build

### F1. Following up a leafy step: the leaf over the bot's own head is never mined (6bx1)

`fix280.py follow` (the 1.1.278 east route at 60,230, where finding 56 came from). Run 1, 12:18:51 to 12:19:10: Fol stood at 75,73,232, 9 blocks from the player, for 19 s until the script stopped it.

```
[12:18:55] Follow decision: bot=Fol botPos=74, 73, 231 msg=leaf-action: toward=east action=BYPASS_STEP target=null stagnant=10
[12:18:59] Mining complete at class_2338{x=76, y=75, z=232}
[12:19:03] Mining complete at class_2338{x=75, y=75, z=231}
[12:19:05] Mining complete at class_2338{x=75, y=75, z=230}
[12:19:08] applyMovementInput-reject bot=Fol from=(75.70, 73.00, 232.31) to=(76.01, 73.00, 232.25) reason=feet-not-passable feet=76, 73, 232=minecraft:grass_block head=76, 74, 232=minecraft:leaf_lit...
```

The `blocks` grid afterwards: the step (grass at 76,73,232) has open air above it, but `oak_leaves` stays at 75,75,232, right over the bot's own head, so it cannot jump. Every leaf-action line said `BYPASS_STEP`. No `MINING_STARTED` line came, so the 1.1.286 path never ran. Run 2 (12:20) took another route over the canopy and ended on a ledge 3 blocks up, 8 blocks from the player.

The finding 56 shape (five `leaf-cleared` lines, no mine) is gone, but the bot still does not get past the step. Suspected: the step-up leaf choice in `LeafClearService` / `MovementService` (where `BYPASS_STEP` vs `MINING_STARTED` is picked) does not treat the jump-headroom cell above the bot's own position as a leaf to mine. The step-down canopy regression passed (`fix280.py canopy wide follow`, 12:17:58: past the row at t+5.6 s, `Mining complete at 361,121,661`, 0 aborted).

### F2. `/bot place` onto a snow layer freezes the server and throws (6bz2)

Setup: `snow[layers=1]` at 1520,120,1520 on grass. The player stands at 1520.5,120,1522.5 and looks at the snow (yaw 180, pitch 37). SM stands 2 blocks off with a crafting table. Two tries (12:39:15, 12:40:00), the second with the bot next to the snow, both gave this:

```
Movement execute: mode=DIRECT dest=class_2338{x=1520, y=120, z=1520} ... player=SM
[Server thread/WARN]: nudgeTowardUntilClose on the server thread [direct-prewalk-route-1] via modCommandRegistry.attemptPlacement:5667: the tick is frozen until the nudge deadline
[Server thread/WARN]: nudgeToward failed ... / pursuit failed [direct]: finalDist=2.29
[Server thread/WARN]: Can't keep up! Is the server overloaded? Running 14474ms or 289 ticks behind
[Render thread/INFO]: [System] [CHAT] An unexpected error occurred trying to execute that command
```

Afterwards the snow is still there and the table is still in the bag. The log has no stack trace (Brigadier swallows it). Control at 12:40:40: the same spot with the snow removed gave "Placed 1 crafting_table." in 3 s. Suspected: `Commands/modCommandRegistry.java` `attemptPlacement` (about lines 5630-5670). With the snow layer as the raycast hit, the bot walks toward the snow cell on the server thread, which freezes the tick about 14 s, and then something throws uncaught. The 1.1.288 handoff deferral ("`/bot place` keeps its own instant snow clear") points at this branch. Worth checking whether the snow-clear test reads `placePos` (the cell above the hit) rather than the hit block.

## PASS

- **6bt1 Full hotbar, woodcut cleanup** (`w.py hotbar`, 12:32:50 to 12:33:58). HB's hotbar 0-8: diamond sword with Sharpness 5 in slot 0, then diamond axe, pickaxe, shovel and hoe, bow, crossbow, iron sword, trident. It felled a fancy oak. `woodcut_cleanup starting: origin=1515, 120, 1515 radius=10`, then `Cleanup done: minedLogs=0 removedScaffold=3` 7 s later. No `Mining ... with` line named a sword, trident, bow or crossbow. Held-item polls every 0.4 s showed diamond_axe, dirt, empty hand, diamond_shovel (the end of the printout was cut off, and no sword appeared in the printed part). No leaf `target selected` line. No dirt or cobblestone was left above the deck and no hole in it.
- **6bt2 Full-pack refusal** (`fix281.py craft full 16 0 Smith12`, 12:16:57). "My pack's full, so I can't craft stone pickaxe right now." came once, with `craft: Smith12 has a full pack and nothing cheap to drop`. There was no `craft (quiet)` line, and no line in the whole log names cobbled deepslate or blackstone.
- **6bv1 Sunset return to a faraway owner** (`sun.py`, 12:50:29). SunB stood idle on a stone bridge at 1695,120,1560, the owner was at 1605 (90 blocks), and no base was near. Lines: `Auto-return at sunset triggered for SunB (day=29081 tod=12096)`, then `Sunset segment for SunB finder=unified pathLen=86 next=1671, 120, 1560`, then pathLen 55, then pathLen 26 with `next=1601`. x went from 1692 to 1607.5 in 25 s, toward the owner. My first two tries never triggered: the per-bot `/bot auto_return_sunset on <bot>` toggle is off by default. No `Sent sunset auto-return notification` line appeared (`auto_return_skip_permission` was on). After it arrived, SunB logged about 36 `ReturnBaseStuck TICK ... stagnant=` lines and one quick-nudge next to the owner (minor, it had arrived).
- **6bw1 Starving strip-mine bot, owner 102 blocks away** (`s.py`, 12:38:03). The player was at 170,_,276 and the bot at 68,77,276 with food 0. The client showed `[CHAT] SM: I'm starving and out of food. Stripmine paused. Feed me, then /bot resume.` The bot was already starving when the run started, so it paused at step 1, not partway through the tunnel.
- **6bx2 Defender stance** (`fix280.py aid bow`, 12:21:17, and the new `aid swordbow`, 12:22:30). Bow only: Jake moved z 800.5 → 801.4 → 802.5, off Bob's line. Arrows went 32 → 30 and the zombie was gone at t+7.9 s. `ff_received` for Bob: 0. Two `Mutual aid: Jake hands its defend_hostile approach to its own combat` lines. Bob's drop from 13 to 5 HP came from the zombie, not from Jake. Sword and bow, zombie 4 blocks from Jake: `Combat profile: bot=Jake profile=sword-sweep weapon=Iron Sword`, zombie gone at t+2.1 s, no arrows fired.
- **6bx5 Pathfinder not pinned** (file-save path). Before launch, `settings.json5` got a copy with the `"pathfinder"` line removed (`sb_cfg`). The game kept saving it (91481 → 101947 bytes, the last save after `/bot config diagnostics on` at 13:02:27 and again on quit). Neither copy had a `"pathfinder"` line, and bots planned with the default (`Sunset segment ... finder=unified`). The Frens settings screen's own open and save was not driven. The original file, which pins `"unified"`, was put back after the run.
- **6by5 Stopped tool search** (`sup.py stop`, 12:55:16). Lines: `[supply] tool scan for Sup: registered=0 nearby=1`, `[supply] request ... stone_axe ... outcome=OPENED`, withdraw `result=WAITING reason=ASKED`. Then `/bot stop` and `/bot follow` straight away ("Sup is following RotiWokeman."). For 25 s after the stop: no `[supply] request/withdraw/transfer` line, x stayed at 1702 (the chest was at 1710), and the bag stayed empty. Minor: in the same second as the stop, after "Sup has stopped.", the worker still printed `Woodcut axe bootstrap: Sup has no axe, punching logs bare-handed` and "No axe yet, so I'll punch a tree first." Nothing came after those.
- **6ca1 Buried bot** (`bury.py`, 12:43:20). Bur was on `/bot skill farm` and got buried in a 3x3x2 block of dirt. Lines: `Bot Bur is stuck! ... headState=Dirt, feetState=Dirt`, `clearing headspace by mining Dirt`, `taking IN_WALL damage - attempting immediate escape`, `emergency snap recovery [fully-encased-suffocation]: from=1561, 120, 1560 to=1560, 120, 1559`. It was out in about 1 s at 20 HP. No `Moved wrongly` or position-correction line, no doubled placement. It then staged a pillar to 1561,132,1561 (`ensureAtSurface: Bur failed at 1561, 122, 1561 openSky=false`) before the farm ended for lack of water (my pond got buried). Rubber-banding on screen was not judged.
- **6cb2 Broken bee nest** (`bee.py`, 12:41:57 to 12:42:36). An oak at 1530,120,1520 with a bee nest at 1530,122,1519. Run 1: `Registered beehive protection at 1530, 122, 1519`, `Woodcut detect reject sample 1 at 1530, 120, 1520 (... protected=true, protection=beehive)`, `protectReasons=beehive=5`, and the bot cut another tree instead. The nest was then set to air with `/setblock`, so no break event fired (the harder case). Run 2: `Woodcut detect: ... protected=0 nearest=1530, 120, 1520`, `protectReasons=none`, "Got 5 logs from that tree.", and the trunk base was gone.

## PASS with a gap (not ticked)

- **6by1 Two bots, one tree** (`w.py two`, 12:24 to 12:31). Two fancy oaks with touching canopies on a grass deck (1500-1540 × 1500-1530, soil at Y 119). Wa and Wb each had a diamond axe and 48 dirt. Wa: "Got 25 logs". Wb: "Got 8 logs" and `woodcut_cleanup target failed: kind=OVERHEAD_SUSPENDED pos=1514, 127, 1512 reason=pillar-failed` (Wb was working on Wa's tree). Both cleanups ended with "Cleanup done". Afterwards, `/fill ... replace` counts on the deck: 0 dirt and 0 cobblestone above the soil, 0 air holes in the soil layer. Dirt used: Wa 19, Wb 6. Gap: 1 run, not the 2-3 asked. Both bots then left the deck for natural trees below (y 98-106) and kept cutting there, and those trees were not audited (one `Cleanup incomplete ... remainingLogs=5`).
- **6ca3 Tool swaps** (all woodcut runs, plus `stripmine 40` at 12:38:41). `Mining ... with` counts: diamond_axe 225 (logs), diamond_shovel 21 + 19 (dirt), and hand-held items for leaves and scaffold (torch 16 at delta 0.1667, which is hand speed on leaves). The tunnel never hit stone, so the pickaxe swap was not shown. `Tool select:` lines are DEBUG (`ToolSelector`) and do not reach the log.

## NOT RUN

- **6bt3 Store chest placement miss:** no store run that places a chest happened. The item says to check it only when one happens anyway.
- **6bu2 Flower gift:** the idle mutual-aid flower trigger was not set up (time).
- **6bw2 Allow once, owner away:** two setup tries, both my mistakes. Try 1 (12:52:31): the chest was 22 blocks away, outside the 12-block scan for unregistered chests (`[supply] tool scan for Sup: registered=0 nearby=0`). Try 2 (12:54:12): the chest held one stone axe, which the reserve keeps (`outcome=INELIGIBLE(RESERVE_EXHAUSTED)`). Recipe for next time: 2 stone axes in an unregistered chest within 12 blocks (`supplytest.py ask once stone_axe:2`), answer once, then tp the owner 100 blocks away before the walk.
- **6bw4 Hand-placed chest on a destroyed record:** needs a bot-registered chest first. Not set up.
- **6bx4 Double chest counted once:** two tries. A double chest at 1710/1711,120,1600 (left and right halves with 6 bread each, checked with `/data get`), base SupB2 12 blocks away, Sup emptied, then `/bot set hunger 6` and later `3`. "Set Sup's hunger to 3 (saturation zeroed)." appeared, but no `[supply] request` came in 75 s, so no count was ever read.
- **6by6 Half-unloaded double chest:** needs a chest on a chunk border at the exact edge of loaded terrain. The bot is a server player and probably keeps the chunks around itself loaded. Not attempted.
- **6bz1 Fortify or shelter onto snow, carpet, grass:** not set up (needs a mapped village or `sheltertest.py seal` with snow and carpet on the seal cells). Given F2, worth running in the fix build.
- **6bz3 Combat refusal said once:** the once-per-3-minutes notice (`TravelRefusalNoticePolicy`) sits on the fast-travel combat gate (`NavigationArtifactService`). It needs a fast-travel (lodestone) sunset return, which was not set up.
- **6bz4 Canopy vs cave surface** (`can.py`, 13:00 to 13:01:30): the trigger was not reached. Cano was placed in a 1x2 pocket inside persistent oak leaves, then in a stone room capped by one leaf. `/bot go_to <x> <y> <z> Cano` did not move it either time, and no surface-recovery line appeared. In the canopy pocket the stop logged `Underground survival: Cano holding still after a stop command until the next command`, so `UndergroundSurvival` saw the pocket as underground at that moment. Probably worth a look when this item is rerun.
- **6ca2 Resume waits for the surface:** not set up (time).
- **6cb1 Cleanup at the edge of loaded terrain:** needs the 37-block cleanup area to cross unloaded chunks while the bot's own chunk ticks. With a server-player bot this likely needs a lowered view distance and a chunk-edge setup. Not attempted.
- **6cb3 Aid during a job vs a hobby:** not set up (time).

## Only Bradley can do these (appended to `GUIDED_SESSION_BRADLEY.md`)

6bu1 cavity callout spoken aloud, 6bv2 voice mute toggles, 6bw3 Collect permission on arrival, 6bx3 mounted leaf clearing, 6by2 / 6by3 / 6by4 permission gates for a non-op player.

## State after the sweep

The lock is released and the game is closed. Sandbox mods: Flashback, fabric-api, frens-1.1.292, minecraft-mcp-hardened. Muted (master 0). `settings.json5` is back to the pre-run file (`sb_cfg_restore`, which pins `"pathfinder": "unified"`). The post-run copy is in the scratch folder. "Sweep Souls3" was changed: decks at x 1500-1730, z 1500-1660, Y 117-126, and a bridge at x 1600-1700, z 1556-1564. The test bots were despawned, and Jake and Bob were despawned at the aid deck (352/367,120,800). The time is noon with the day cycle frozen. Diagnostics were turned on during the session and went back to their earlier setting with the restored file.
