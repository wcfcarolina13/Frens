# Codex wave sandbox sweep, Frens 1.1.291 (2026-10-23)

Sandbox instance `mcp-sandbox`, save "Wall Test" (commands on, level name "New World", fingerprint `New World#f2e9c6a8`), jar `frens-1.1.291-release+1.21.11.jar` (md5 502cf71f...). Muted, no Carpet. Two game launches. Peaceful, time frozen at day except for the sleep check. Claude drove the game through the hardened MCP fork. Scripts and log copies: the sweep scratch folder (`sw.py`, `item1b.py`, `item3b.py`, `item7.py`, `item10b.py`, `session1.log`, `session2.log`).

## Summary

| # | Item | Result |
|---|---|---|
| 1 | Diagonal wall-clip rescue | NOT RUN (new code path not reached, bot did get out safely) |
| 2 | Farm on flat forest ground | PASS |
| 3 | Furnace reach and dimension | PASS |
| 4 | Idle woodcut backoff | FAIL (dispatch storm). The backoff itself held |
| 5 | Wake greeting delay, busy co-sleep retry | Greeting PASS. Busy retry NOT RUN |
| 6 | Mining with tool preparation | PASS |
| 7 | Resurrection ritual | PASS (duplicate-request refusal could not be forced) |
| 8 | Hunt settings carry-over | PASS |
| 9 | Broken store file quarantine | PASS |
| 10 | Smoke: follow, woodcut, chest deposit, error scan | PASS |

## 1. Diagonal wall-clip rescue: NOT RUN

Setup: stone at (1061,120,1001) and (1061,121,1001), both axial neighbours air. Bot Clippy stopped, then `/tp Clippy 1060.85 120 1000.85`.

What happened: the older immediate IN_WALL handler (`Frens.java` damage hook, then `BotRescueService.checkAndEscapeSuffocation`, then `attemptEscapeMovement`) moved the bot out before the burial tick could use `WallClipNudgePolicy`.

```
[11:14:21] Bot Clippy taking IN_WALL damage - attempting immediate escape
[11:14:21] Bot Clippy attempting escape movement toward north
[11:14:22] Bot Clippy attempting escape movement toward north
```

The bot ended at (1060.54, 120, 998.97), on the ground in open air at full health, so the outcome was safe. No "nudged away from wall" line appeared, so the new diagonal nudge did not run. Second attempt: spawning a bot at the overlap spot (spawn-in-blocks path). `/bot spawn` snaps to (1060.5, 1002.5), so it cannot be placed at an overlap. To reach the new code, the immediate escape would need to be off or failing. Replay `frens_1_1_291_backoff_wallclip_farm_mining`.

## 2. Farm on flat forest ground: PASS

Setup: a flat grass platform with five oaks placed 2 to 5 blocks from the bot, a 2x2 pond 12 blocks east. Kit: diamond hoe, 32 seeds, water bucket, diamond axe. `/bot skill farm Farmy`.

```
[11:16:16] escapeTree attempt 1 insideTree=false skyBlocked=false nearbyTree=true blockingTreeBlocks=8
[11:16:33] Farmy: Got 5 logs from that tree.
[11:16:33] [FarmIrrigation] levelGround targetY=119 samples=96 protectedWater=4
[11:18:28] Skill 'farm' finished for bot Farmy: success=true msg='Prepared a hydrated farm and planted 32 seeds (tilled 96).'
```

No `grade-cut-too-deep`, `fill-too-deep` or "Farm site rejected" line. Note: the farm centre settled at (1132,119,1000), near the pond and a little away from the trees, and the later tree-clear pass said "Tree clearing attempt 1 failed" without stopping the farm. Replay `frens_1_1_291_backoff_wallclip_farm_mining`.

## 3. Furnace reach and dimension: PASS

The reach checks are in `SmeltingService.cookAllFoodSync`, which only the idle cook hobby and the post-hunt cook use (`/bot cook` takes another path). So the test used the idle cook hobby: bot Cooky with 12 raw beef and coal, a furnace 3 blocks away, a base set, idle hobbies on. Two earlier tries via `/bot skill hunt` did not reach cooking because my fence fill had covered the whole platform. That was my setup error, not a Frens issue.

Out of reach (teleported 40 blocks away about 6 s into the cook):

```
[11:26:39] cookAllFoodSync: Cooky cooking Raw Beef (1x stacks)
[11:26:44] furnace: 12 beef in slot 0, 11 coal in slot 1
[11:26:45] cookAllFoodSync: Cooky can no longer interact with furnace at 1243, 120, 1000
[11:26:45] Cook hobby finished for Cooky: success=false
[11:26:58] furnace: 11 beef, 11 coal, 1 cooked beef
[11:27:11] furnace: 9 beef, 11 coal, 3 cooked beef
```

Other dimension (teleported into the Nether mid-cook):

```
[11:27:42] cookAllFoodSync: Cooky cooking Raw Beef (1x stacks)
[11:27:48] cookAllFoodSync: Cooky can no longer interact with furnace at 1243, 120, 1000
[11:28:14] furnace: 9 beef, 3 coal, 3 cooked beef (keeps cooking by itself)
```

No item moved to or from the bot after the move, no refuel from the bot, no exception. The bot inventory held only its sword. Furnace contents: the whole load (all raw beef and all the coal the bot carried) stays in the furnace and keeps cooking on its own. The cooked food stays in the output slot. As the known deferral says, the bot does not walk back. Replay `frens_1_1_291_furnace_reach`.

## 4. Idle woodcut backoff: FAIL

Setup: bot Woody holding only a diamond axe (no pickaxe or shovel, no wood), in a bedrock pit 5 blocks deep with a bedrock floor, an oak 7 blocks east outside the pit. Base set, idle hobbies on.

The backoff itself held. After the first real failure, no woodcut started for about 10 minutes, and the unchanged axe never reset it (no "axe readiness improved" line):

```
[11:15:23] Idle hobby 'woodcut' finished for Woody: success=false msg='Found a tree, but couldn't finish cutting it after repeated reach/path failures.'
... only "Idle hobbies blocked for Woody: backoff:NNNt" and wander attempts ...
[11:25:51] Idle resource woodcut: Woody needs tools but has no wood — starting woodcut
```

Symptom (FAIL): each time the gate opens, `maybeHandleIdleWoodcutForResources` dispatches the woodcut on many consecutive ticks before the first run registers an active task:

- 11:13:53 to 11:13:54: 10 "Idle resource woodcut ... starting woodcut" lines. Nine runs ended at once with "Another skill is already running."
- 11:26:21: 9 dispatches in the same second.
- 11:27:51: 7 dispatches in the same second.

Each of the nine refused runs in the first burst went through `recordHobbyAttempt` as a failure. The backoff therefore jumped straight to the 10-minute cap and did not grow step by step from 60 s. The later bursts went through the "could not reach surface, skipping 'woodcut'" path. That path sets `NEXT_DECISION_TICK` only after the async attempt returns, so every tick in between dispatches again. My pit also made the bot read as "not at surface", which caused the second and third bursts.

Suspected file: `src/main/java/net/wcfcarolina13/GameAI/services/BotIdleHobbiesService.java`. `maybeHandleIdleWoodcutForResources` and `startAmbientSkill` set no in-flight marker and no `NEXT_DECISION_TICK` before `AMBIENT_EXECUTOR.submit`. `canStartFallbackWoodcut` relies on `TaskService.hasActiveTask`, which only becomes true once the worker thread starts. This is probably older than 1.1.291 (before the wave a held axe skipped every gate), but I did not check it on 1.1.290. Replay `frens_1_1_291_backoff_wallclip_farm_mining`.

## 5. Wake greeting and busy co-sleep retry: greeting PASS, busy retry NOT RUN

Setup: night (time 13500), a bed placed for the player, bots Puff (idle) and Busy (given a bed). The player slept through a `key.use` tap.

```
[11:42:25] Co-sleep: commander RotiWokeman went to sleep, checking 2 bot(s)...
[11:42:26] trySleep Puff at bed 1299, 120, 1001: accepted
[11:42:31] Wake-up dialogue suppressed (random silence) for bot Busy
[11:42:31] Scheduled voiced wake-up line for bot Puff in 40 ticks
[11:42:33] Wake-up line for bot Puff: fired
```

The greeting came 2 s after the wake, not in the same tick. Each bot started sleep once.

Busy retry: both tries failed because `/bot skill mining` ended at once ("No stone block detected within radius 10", then the stone pillar was out of reach), so Busy was idle when the player slept and no "has active task ... will retry" line appeared. I stopped after two setup tries. Needs a task that stays running for at least 3 s at night (for example a long go_to). Replay `frens_1_1_291_sleep_wake`.

## 6. Mining with tool preparation: PASS

With a diamond pickaxe, `/bot skill mining Minny` mined normally:

```
[11:18:01] Mining class_2338{x=1174, y=119, z=996} with minecraft:diamond_pickaxe (delta=0.1778, ticks=6, ~300ms, creative=false)
[11:18:02] Skill 'mining' finished for bot Minny: success=true msg='Collected 10 stone blocks.'
```

There were 10 "Mining ... with minecraft:diamond_pickaxe" lines, no "could not equip a safe tool" and no exception. With only a diamond sword:

```
[11:18:30] Skill 'mining' finished for bot Minny: success=false msg='I need a pickaxe to mine stone. I won't punch stone by hand.'
```

This is a clean refusal with no exception. Note: the refusal happens in the skill before `MiningTool`, so the held-tool verification after selection was not used in the wrong-tool case. Replay `frens_1_1_291_backoff_wallclip_farm_mining`.

## 7. Resurrection ritual: PASS

Setup (second launch): `settings.json5` got `botControlsByWorld.Rez["New World#f2e9c6a8"].autoRespawnOnDeath=false` while the game was closed. In the Nether: obsidian pad, respawn anchor with 2 charges. `/bot spawn Rez`, then `/kill Rez`. The player was in survival. Sneak with the `key.sneak` hold, then the `key.use` tap on the anchor with a Ghast Tear in hand.

Failed attempt (tear and 4 gold, no blaze powder):

```
[11:45:41] The ritual demands a Ghast Tear, 4 gold ingots and 1 blaze powder.
player before and after: ghast_tear 1, gold_ingot 4. Anchor charges still 2.
```

Nothing was consumed.

Real ritual (blaze powder added, two `use` taps 50 ms apart):

```
[11:45:55] Preparing the ritual for Rez...
[11:45:55] Rez[local] logged in with entity id 163
[11:45:55] Rez joined the game
[11:45:55] Rez returns.
player after: no tear, gold or blaze powder. Anchor charges 1. Rez alive at (1,121,1), 20 HP.
```

Payment came after the bot connected, in the connection callback before "returns", and the anchor lost one charge. The second tap produced no second ritual and no "already underway" line: with an offline profile the whole ritual finishes in one tick, so the second tap found Rez alive. The duplicate refusal cannot be forced here (timing race, out of scope). Replay `frens_1_1_291_ritual`.

## 8. Hunt settings carry-over: PASS

With the game closed I wrote `config/frens/hunt_config.json` holding only the legacy key `"New World:minecraft:overworld"` → `configByBot.hunter` with `depopulationEnabled: false`, zone EXPANDED, targets [cow].

```
[11:12:26] Adopted legacy hunt config 'New World:minecraft:overworld' as 'New World#f2e9c6a8:minecraft:overworld'. Legacy original kept.
[11:12:57] [CHAT] hunter depopulation=false zone=EXPANDED targets=[minecraft:cow]
[11:12:57] [CHAT] This save adopted these records when it loaded. Legacy original retained.
```

The chat lines are from `/bot legacy-state registry preview hunt-config`. After the session the file held both the legacy bucket and `New World#f2e9c6a8:minecraft:overworld` with `depopulationEnabled: false`, and `legacyWorldClaims` mapped the legacy key to `New World#f2e9c6a8`. The second launch logged no adoption line, as expected because the save already had its own bucket. The test file was removed afterwards (there was none before).

## 9. Broken store file quarantine: PASS

The original `crafting_history.json` (6299 bytes) was backed up, then truncated to 300 bytes with the game closed.

```
[11:12:26] Failed to load crafting history: java.io.EOFException: End of input at line 13 column 10 path $.worlds..recipesByBot.[1]
[11:12:26] Could not read the crafting history file. It was moved to .../config/frens/crafting_history.json.bad-20261023-111226 and the crafting history starts empty this session.
```

The store then saved normally. `crafting_history.json` was written at 11:36:03 (287 bytes, woodcut plank crafting) and at 11:39:02 (316 bytes, after `/bot craft stick 4 Puff` "Crafted 4 Sticks."). The original file was restored afterwards and the `.bad-` file moved out to the scratch folder.

## 10. Smoke: PASS

- Follow: `/bot follow toggle Puff`, player moved 15 blocks. "Follow decision: bot=Puff botPos=1290, 120, 1009 ... dist=2.31" (11:29:44).
- Woodcut: `/bot skill woodcut Puff` felled several trees ("Got 6/7/8 logs from that tree", "Woodcut unified: mined 16 logs ..."). It went for natural trees off my platform and kept working about 9 minutes until stopped.
- Chest deposit: "Store default deposit: matching 5 item types already in chest at 1276, 94, 1047" then "Deposited 102 items." (11:39:23). It used a chest already in the world that matched, not the new empty chest I placed.
- Error scan (excluding Missing sound and UBO): session 1 only had the offline Mojang session and key fetch timeouts, the Realms flag fetch and the deliberate crafting-history EOF warning. Session 2 had zero ERROR or Exception lines. No mixin errors at either start.

Replay `frens_1_1_291_smoke_follow_woodcut_deposit`.

## State after the sweep

Lock released, game closed. Sandbox mods: Flashback, fabric-api, frens-1.1.291, minecraft-mcp-hardened. Muted (master 0). `settings.json5` and `crafting_history.json` restored from backups, test `hunt_config.json` and `.bad-` file moved to the scratch folder. The "Wall Test" save was changed: test platforms near x 1000 to 1310, z 1000, a Nether pad at 0,120,0 with a forceload at 0 0, bots despawned or shelved, and the time left at night.
