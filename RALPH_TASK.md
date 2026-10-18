# Frens task file: current handoff on top, backlog below

The newest session handoff comes first, then "Recovered deferrals", then the backlog lineup and the prioritized backlog.
Open work is a `- [ ]` line (`grep -n '^\- \[ \]' RALPH_TASK.md`). Superseded handoffs and old session notes are in
`docs/handoffs/ARCHIVE_2026.md`. History is `changelog.md`.

## Session Handoff 2026-10-18 (1.1.269) — next session starts here

**State:** main = origin/main @ 1.1.269. Suite 2843 green. Deployed to Nirn and the mcp-sandbox instance.
**1.1.269** (changelog entry has the details): honey_collect packs, unpacks and equips inside `SupplyServerHop` (was the worker), "Got N logs" counts only the felled tree's logs (`WoodcutTreeOwnershipPolicy`, as the leftover list), emergency rescue counts only chest food the supply rules would grant (`BotMutualAidService.hasGrantableChestFood`, reserve 8, allowlist, reach, chests only). Fable review merge-ready, no fix wave.
**Field Phase 6bh done:** the three open items passed in the sandbox on 1.1.268 (save "Sweep Spawn"): stop mid-fell `removed 3/3 own scaffold block(s) (abort=true)`, leftover after a quit cleared, axeless bot cut 3 reachable logs and placed nothing. Replays `frens_1_1_268_6bh_*.zip` in the sandbox. **Phase 6bi** (one item): the starving trapped bot beside 5 chest bread gets rescued. Checklist now 278 open items.
**Next:**
- **Modrinth:** the 1.1.268 notes draft (`.superpowers/sdd/modrinth/MODRINTH_NOTES_1.1.268_DRAFT.md`, untracked) still waits for Bradley's OK. Untouched this session. If he wants 1.1.269 shipped instead, the draft needs these three fixes added.
- Deferred, new: `depositHoneyItems` passes the bot's own position as the chest, so the offload after packing is a silent `chest-missing` no-op (needs a nearest-chest lookup, behaviour change, field check); a stopped Spawn-base fell leaves the upper trunk and canopy standing (later detects reject it as a failed base); rescue check runs every tick for a starving, not-dire bot (a 20-tick memo would be cheap) and does not see refused foods, the chest hold or an absent owner; HoneyCollectSkill's private 500 ms `callOnServer` could move to `SupplyServerHop`; the rescue's other worker-thread mutations from auto-hunt.
- Still deferred from 1.1.268: ascent bridge probe bridges toward any log within 6, raised-base neighbour trunks lend logs, abort-safe scaffold cleanup outside bases, finding 2 (ranged pause).
- PlayerMemory vs relations: Bradley's decision is due (see the 1.1.268 handoff below). The 1.1.267 Next list still stands.

## Session Handoff 2026-10-18 (1.1.268) — superseded

**State:** main = origin/main @ 1.1.268. Suite 2842 green. Deployed to Nirn and the mcp-sandbox instance.
**Field sweep done:** phases 6aj-6bg in the sandbox on 1.1.267 (`docs/testing/FIELD_6AJ_6BG_SANDBOX_2026-10-17.md`): 58 items got a verdict, 49 pass, 4 superseded, 2 partial (6ak-1 no PLACE move seen, 6ap-2 Bases screen and non-op half), 1 fail (6am-2 audit fixes, fixed in 1.1.268), 2 need Bradley (6ao-1 Nirn skins and mounts, 6av-2 remote server). **No high-severity failure**, so by Bradley's 2026-10-18 rule 1.1.268 is the next Modrinth release.
**1.1.268** (changelog entry has rulings and costs):
- Spawn-base trees: with "Cut trees in the Spawn base" On a woodcut bot places and removes its own pillar scaffold there (Bradley's ruling 2026-10-17, extends 1.1.238 `7f89c502`). Owned-scaffold registry keyed to the exact block, 24 h expiry, persisted per save, abort-safe teardown inside the base. Sandbox: a rooted Spawn oak cut whole, placed 4 removed 4.
- Forest woodcut counts only the felled tree's logs (no early relocation), tree scans build each tree once (detection 0.2-1.9 s, was 2-16 s), food keeps 8 in chests (Bradley 2026-10-18), honey_collect harvests at all, fishing cooks cod and salmon, retrieval reports vanilla pickups, one line for strict crafts and leash results.
- Test tooling: MCP fork key bindings, `look`, `scroll`, key-code typing (`71efa71`, `052c4ec`), `tools/sandbox/forest.py`, `leashtest.py`, `ft.py shot/clickr/scroll`, paused-screen warning, `sb_deploy`, per-instance deploy checks, log report thread fix.
**Next:**
- **Modrinth:** draft notes for 1.1.231-1.1.268 are in `.superpowers/sdd/modrinth/MODRINTH_NOTES_1.1.268_DRAFT.md` (untracked). Bradley OKs the exact text and uploads himself. Nothing was published.
- **Field Phase 6bh:** three items ticked from today's sandbox check (Spawn tree whole, forest, honey and fish), three open: the mid-fell `/bot stop` abort teardown in the Spawn base (not caught in the sandbox, a fell takes about two seconds), leftover own scaffold cleared after a quit, an axeless bot in the Spawn base. Checklist now 280 open items.
- Deferred from 1.1.268 review: honey's `equipItem`/`extractItemFromContainer`/`packHoneyIntoContainers` mutate the inventory on the worker; `countEnvelopeLogs` ("Got N logs" count) is unfiltered; ascent bridge probe still bridges toward any log within 6; raised-base neighbour trunks still lend logs (the soil-under-base rule); `BotEmergencyRescueService.hasNearbyStorageFood` ignores reserve and allowlist; abort-safe scaffold cleanup outside bases; finding 2 (ranged pause resumed while the shooter lived, unrepeated).
- PlayerMemory vs relations: nothing in the sweep showed them disagreeing (one note: a bot on the fallback Jake profile answered "It's Jake, not Wally."). Bradley's decision is now due.
- Sandbox notes for the next session: an open screen pauses the integrated server (`ft.py` warns now), `screen_buttons` cannot see Frens' custom-drawn screens (use `ft.py shot` + `clickr`), Flashback is installed (record from the pause menu: "Start Recording", "Finish Recording", then "Save Replay"), and `.superpowers/SANDBOX.lock` is the shared lock.
- The 1.1.267 Next list (no fallback re-search ruling, SearchBuffers overlay, M2 in game, "woodcut_cleanup interrupted." for the text sweep) still stands. The Spawn-tree pillar item shipped in 1.1.268.

## Session Handoff 2026-10-17 (1.1.267) — superseded

**State:** main = origin/main @ 1.1.267. Suite 2771 green. Deployed to Nirn and the mcp-sandbox instance.
**1.1.266 sandbox recheck done** (save "Fix 266", results in the 1.1.266 changelog entry): A, C/C2/C4, D, E, G pass. B was honest but still took about 2 minutes, so it went into 1.1.267. F never fired in the field. The save now has "Cut trees in the Spawn base" On.
**1.1.267:**
- F: the unified search refuses a move an earlier move of its own path undoes, and routes around it (`MUTATED_LAYER` key bit, truncation kept as backstop). `canReach`, `probePlan` and the PathCompare shadow now see the truncated plan.
- B2: an empty forest `woodcut_cleanup` takes 16 s in the sandbox (was 126 s), with a 30 s empty-phase cap.
- M1: 15 s no-planks table window. M2: a held unplaceable table gives one line. M3: mounted leaf clearing goes bare-handed instead of using shears or a weapon. M4: a bot named after a material can take a craft.
- Reviewed by Fable, one fix wave, re-review, two small follow-ups. Field items: Phase 6bg (checklist now 330 open items).
**Next:**
- **Ruling (Bradley, 2026-10-17): no fallback re-search** after a refusing unified NO_PATH for now. Revisit only if the field shows missed routes (seed 103 is a lawful mining path still missed through dedup staleness; a retry would double failing server-thread probes).
- Per-search overlay allocation into `SearchBuffers` (needs a `MoveSimulator.Overlay` base-terrain change).
- With the Spawn-tree toggle On, a tree that needs a pillar is cut one log at a time: `pillarUp` is refused in the Spawn base (1.1.238 ruling, `7f89c502`, logs and leaves only), so the bot leaves the rest and relocates. Seen on a floating test tree. Watch whether rooted Spawn trees do the same, it may need a ruling.
- M2 not set up in game (tests only). New player string "woodcut_cleanup interrupted." for the next text sweep.
- Older open work no longer chains through earlier handoffs. The 1.1.266 Next list shipped in 1.1.267 (F route fix, M1-M4).
  Everything still open from the 1.1.265 back to 2026-03 handoffs is a `- [ ]` line in "Recovered deferrals (2026-10-17)"
  below (incl. Slice D, the go_to overworld plan, the 1.1.264 stuck-escape scopes, and the "needs Bradley" decisions
  under Docs and process). Field items live in `docs/testing/FIELD_SESSION_1.1.202.md`. The old handoffs are in
  `docs/handoffs/ARCHIVE_2026.md` (history only, grep it).

## Recovered deferrals (2026-10-17)

Deferrals that lived only in superseded handoffs (Ralph retirement audit, `.superpowers/ralph-retirement/audit.md` section 2
"ORPHAN" rows). Quoted from the source handoff, source in parentheses. `(uncertain)` = the audit could not confirm it is still
open. "Needs Bradley" items are decisions, not code. The source handoffs are in `docs/handoffs/ARCHIVE_2026.md`.

### Navigation, pathfinding and stuck escape
- [ ] "go_to plans in `server.getOverworld()` whatever the bot's dimension." (still `server.getOverworld()` in `modCommandRegistry`; 1.1.265 handoff, 2026-10-17)
- [ ] "unified sunset probes returned `pathLen=0` or `7` with `next=none` inside 200 ms, so the return fell back to a direct follow plan to the far anchor. Worth a scope before Slice D." (1.1.265 handoff, 2026-10-17)
- [ ] "Slice D (remove the legacy boolean and bidir) once the default has held." (1.1.261 handoff; also 1.1.263 and 1.1.242 handoffs, 2026-10-15/17)
- [ ] "after the first shared-settings save the file stores `"pathfinder": "unified"` explicitly rather than null, so a later default change would not move that install." (1.1.261 handoff, 2026-10-17)
- [ ] "diagonal wall nudge (eye-box geometry)" (1.1.264 handoff, 2026-10-17)
- [ ] "scaffold Phase 3 pillaring up while on a pillar" (1.1.264 handoff, 2026-10-17)
- [ ] "the two off-thread `rescueFromBurial` callers (ReturnBaseStuck async mine escape, MovementService nudge loop)." (1.1.264 handoff, 2026-10-17)
- [ ] "a backup jump-sprint logged `succeeded, now at 199, 110, 202` from inside a closed stone pocket. Worth a scope." (1.1.264 handoff, 2026-10-17)
- [ ] "`ReturnBaseStuckService` counters clear only on arrival at base, so a later return inherits `backupAttempted` from an earlier stuck episode (seen in the sandbox). Worth a scope." (1.1.264 handoff, 2026-10-17)
- [ ] "blind walkDirect fallback after no path" (1.1.239 handoff, 2026-10-14)
- [ ] "hand-dig stair escape for a bot with no blocks." (1.1.236 handoff; also 1.1.239 handoff, 2026-10-14; 1.1.240 `TerrainMovePolicy` places dirt stairs but has no no-blocks dig)
- [ ] "BotActions.moveRelative same-cell teleport" ("probable IN_WALL root, shared hot file"; 1.1.233 handoff; also 1.1.229 handoff, 2026-10-13)
- [ ] "MovementService off-thread tool swaps" (1.1.233 handoff, 2026-10-13)
- [ ] "isAtSurface strict under canopy" (1.1.234 handoff, 2026-10-14)
- [ ] "RL breakBlockAhead ... still instant" (RL is gone since 1.1.244 but `BotActions.breakBlockAhead` is still called by `BotStuckService` `escapeStairs`; 1.1.234 handoff, 2026-10-14)
- [ ] "clutter clears ... still instant" (snow/carpet clutter; 1.1.234 handoff, 2026-10-14)
- [ ] "long automatic return refusals (combat/cooldown/food) still in chat." (1.1.234 handoff, 2026-10-14)
- [ ] "Return-base door/escape server-thread traverses (BEH ~3603/~3630): let the tripwire name them first." (1.1.223 handoff, 2026-09-25)
- [ ] "redstone step 2 (use plates and buttons, iron doors with triggers)" (1.1.223 handoff, 2026-09-25)
- [ ] "`LeafClearAction`/`LeafClearResult` still on MovementService." (2026-09-05 late night handoff; also 2026-09-05 night and later handoffs)
- [ ] (uncertain) "Duplicate \"Returning to base\" messages on each sunset return." (Farm Pipeline next-session notes, 2026-04-08)
- [ ] (uncertain) "Follow mode stuck at 1-block Y differences. Bot can see commander but `directBlocked=true` when 1 block above." (Farm Pipeline next-session notes, 2026-04-08)
- [ ] (uncertain) "Bot standing at end of staircase not trying to walk around." ("can't build a multi-level path through interior stairs around a column"; Door passage series notes, 2026-04-16)
- [ ] (uncertain) "The `ensureAtSurface` / `nudgeToward failed` loop at session start ... Triggered when bot starts with `leavesTrap=true` under canopy." (Farm Pipeline next-session notes, 2026-04-08)
- [ ] "shallow pits with diagonal one-block exits, verifying fewer repeated micro-hop local-escape restarts" (validation ask, likely stale after the 1.1.240+ finder work; Recent Session Notes, 2026-03-31)

### Construction, scaffold and shelter
- [ ] Pillar and leaf migrations onto the shared services: "`FarmSkill.pillarEscape`; `WoodcutSkill.pillarUp`, `HovelPerimeterBuilder.pillarUp`, `WoodcutSkill.clearBlockingLeaves`" (reasons: "timing/placement differ from ScaffoldService — a behaviour change, judge in the field first", "need 2–3 options each, fragile skills"; deferred in 1.1.208, repeated in the 2026-09-05 handoffs and 1.1.216 handoff)
- [ ] "Egress follow-ups: outside→inside entry routing, and Fortify onto the shared helper." (hovel `enterInteriorViaDoor`; 1.1.218 ruling "don't touch Fortify" for now; 1.1.218 handoff, repeated 1.1.219-1.1.222, 2026-09-25)
- [ ] "inline four single-caller delegates (`tryBreakThroughObstacle` ×2, `attemptFinalizeCarveTransaction`, `isCarveEligibleForBreakAttempt`)" (1.1.210 Minor 3; 2026-09-05 late night handoff, repeated through 1.1.216)
- [ ] (uncertain) "Suffocation during scaffold descent — bot embeds in terrain after dropping through scaffold gaps." (Farm Pipeline next-session notes, 2026-04-08)
- [ ] (uncertain) "Shelter-breakfree misfires on indoor spawn." ("When bot legitimately respawns inside an enclosed structure ... auto-mines upward, destroying stairs"; Door passage series notes, 2026-04-16)
- [ ] (uncertain) "This change intentionally breaks the old shelter-escape \"trapped in corner, needs door as exit\" behavior ... re-address later." (Door passage series notes, 2026-04-16)

### Woodcut and mining
- [ ] "mounted leaf clearing still instant" (1.1.234 handoff; deferred again in 1.1.238. 1.1.267 M3 fixed the shears, not the speed)
- [ ] "woodcut cleanup async scan off-thread" (1.1.266 B bounded the scan, it still runs off-thread; 1.1.234 handoff, 2026-10-14)
- [ ] "cleanup hand fallback slot 0" (1.1.233 handoff, 2026-10-13)
- [ ] "woodcut surface-staging cost." (1.1.229 handoff, 2026-10-13)
- [ ] "weapons+food-only inventory mining thrash (pre-existing)" (1.1.229 handoff, 2026-10-13)
- [ ] (uncertain) "WoodcutSkill still reports `soilFail=29` out of 31 logs in dense forest" ("May need to revisit the soil/hazard heuristic for bounded-mode farm clearing"; Farm Pipeline next-session notes, 2026-04-08)
- [ ] "woodcut trunk-entry cases that previously carved `grass_block` / `dirt` support under the stand" (validation ask, likely stale; Recent Session Notes, 2026-03-31)
- [ ] "standalone and idle woodcut runs, verifying `Planted sapling at ...` or explicit replant skip summaries after per-tree cleanup" (validation ask, likely stale; Recent Session Notes, 2026-03-31)

### Survival, hunger, crafting, farming and mutual aid
- [ ] "bundle food in the no-food warning" (1.1.239 handoff, 2026-10-14)
- [ ] "the gated hunger lines in CollectDirt/StripMine" (1.1.239 handoff, 2026-10-14)
- [ ] "SkillResumeService vs recovery thread" ("not waiting on an active recovery thread"; 1.1.239 handoff, 2026-10-14)
- [ ] "`SmeltingService.resolveFurnaceTarget` `Thread.sleep(150)` (SS:~810/~831) on `/bot cook` and the auto-cook tick." (1.1.222 handoff, 2026-09-25)
- [ ] "A server-thread craft with a full inventory: `distributeOutput` / `craftWithPlanks` / `ensurePlanksFromLogs` → `offloadCheapItemsToNearbyChest` → deposit walk or `placeChestNearBot`. Same fix shape; needs its own scope." (1.1.222 handoff, 2026-09-25)
- [ ] "`FoodConsumptionConfirmationService.tryHandleConfirmation` has no caller" (was to ride 1.1.225's yes/no router, which did not ship; 1.1.223 handoff, 2026-09-25)
- [ ] "Defensive-aid tick freeze (`BotMutualAidService:558`)" ("a 1.6–2.4 s tick freeze when a hostile is near an ally. Needs a one-impulse or worker design."; 1.1.223 handoff, = 1.1.222 deferral 3, 2026-09-25)
- [ ] "The MutualAid recipient's inventory is mutated from the donor's tick while its own skill worker may run (pre-existing)." (1.1.222 handoff, 2026-09-25)
- [ ] "`dropCheapStackForSpace` drops the LARGEST stack when nothing is cheap, and the dropped stack can be re-picked after 2 s." ("Needs a cheap-only mode or a longer pickup delay"; 1.1.222 handoff, 2026-09-25)
- [ ] "A flower keeps targeting a full preferred recipient (cosmetic)." (1.1.222 handoff, 2026-09-25)
- [ ] "the per-hobby backoff state is written for hunt and collect_dirt but read only by woodcut — ready if a later item gates `pickHobby` on tool availability." (1.1.216 handoff, 2026-09-07)
- [ ] "Farm manual placement still has multiple `Heightmap.Type.WORLD_SURFACE` / `findSurfaceY` call sites outside `assessFarmSite`." ("If they misbehave in forests, apply the same walkable-ground-Y treatment"; Farm Pipeline next-session notes, 2026-04-08)
- [ ] (uncertain) "PvE only. ... Alliances feature is the planned PvP gate, not yet built." (Tamed-animal defense notes, 2026-04-11)
- [ ] Field check (from .ralph/progress.md, 2026-03-27, never moved to a field phase; re-check against current hunger/cooking code first): spawn bot, give sword, set hunger to 3, verify it hunts and doesn't stop until 4+ food items (.ralph/progress.md, now in docs/handoffs/ARCHIVE_2026.md)
- [ ] Field check (same source): after a hunt, the bot walks to a furnace and cooks all raw food types in turn (.ralph/progress.md)
- [ ] Field check (same source): give the bot raw chicken + raw porkchop, verify the idle hobby picks "cook" (.ralph/progress.md)
- [ ] Field check (same source): bot at food=8 with zero inventory food, verify auto-hunt still triggers (.ralph/progress.md)

### Multiplayer, ownership and permissions
- [ ] "join-check commander radius" (48; 1.1.236 handoff, 2026-10-14)
- [ ] "multiplayer roster payload" (1.1.236 handoff, 2026-10-14)
- [ ] "level-name-keyed stores (chest/furnace/beehive registries, hunt + crafting history, fortifications, mapped villages" (1.1.256 fixed spawns only; 1.1.234 handoff, 2026-10-14)
- [ ] "D7 non-op zone edit/delete" (1.1.233 handoff, 2026-10-13)
- [ ] "anyone can map a village" (1.1.233 handoff, 2026-10-13)
- [ ] "claim an ownerless wall" (1.1.233 handoff, 2026-10-13)
- [ ] "typed /bot ... for owners" (1.1.233 handoff; also 1.1.231 handoff, 2026-10-13)
- [ ] "SPAWN for owners" (1.1.233 handoff; also 1.1.231 handoff, 2026-10-13)
- [ ] "resurrection owner clause." (1.1.233 handoff, 2026-10-13)
- [ ] "After 6ac/6ad results: bot-slot check refuse mode" (6ad item 7 was clean; `AuthorizedBotDispatch` is still WARN-only; 1.1.233 handoff; also 1.1.232 "D5 refuse mode", 2026-10-13)
- [ ] "A bot respawned by the End exit is a plain `ServerPlayerEntity`, so it gets \"not a companion\" until it is recreated." ("fails closed, and the mod's 78 other `instanceof` checks share it"; 1.1.220 handoff, 2026-09-25)
- [ ] (uncertain) "An offline-mode LAN guest using the host's name passes `isHost`; `configNetworkManager` has the same gap." (1.1.220 handoff, 2026-09-25)
- [ ] (uncertain) "misleading \"Out of range\" text after an inventory deny (modCommandRegistry)" (1.1.224 handoff/changelog deferral, 2026-09-25)
- [ ] "the recruiter-fallback prompt edge in `resolveController`" (1.1.224 changelog deferral, 2026-09-25)
- [ ] "each rejected preference packet still queues one server task" (1.1.224 changelog deferral, 2026-09-25)
- [ ] (uncertain) "the reporter says brand-new worlds failed too, which the per-world key should prevent." ("server is full" report; suspects global `config/frens/` sidecars and legacy level-name-only keys; 1.1.226 handoff, 2026-10-13)

### Supplies and chests
- [ ] "Worker skills can't craft from chest materials. There is one prompt per bot, so a plank ask blocks the tool ask. Fixing it needs WaitMode through `ensure*`/`craftGeneric` plus need-bundling, which is Phase 4 and needs a ruling." (1.1.221 handoff; also 1.1.222 handoff, 2026-09-25)
- [ ] "Supplies Phase 4: the storage-room walkthrough, need-driven armour requests while following, and the WaitMode/need-bundling ruling above." (1.1.221 handoff; also 1.1.222 handoff, 2026-09-25)
- [ ] "`grantableEstimate` works per chest half, so it under-counts double chests." (1.1.221 handoff, 2026-09-25)
- [ ] "Three \"other half of a double chest\" helpers could be one." (1.1.221 handoff, 2026-09-25)
- [ ] "CraftingHelper pull hint maps aren't cleared at stop (bounded wall-clock hints)." (1.1.221 handoff, 2026-09-25)
- [ ] "A double chest whose other half is unloaded reads as unreadable (skipped for that pass)." (1.1.221 handoff, 2026-09-25)
- [ ] "A grant-covered ask while the owner is away is still refused (only Always skips the owner-range check)." (1.1.221 handoff, 2026-09-25)
- [ ] "The `/bot stop` fix for the chest tool search (read `hasActiveTask` once before the loop) is verified by reading only; its test pins the pure helper, not the wiring." (1.1.221 handoff, 2026-09-25)
- [ ] "Pre-existing latch race: after `/bot stop`, a later `beginSkill` for the same bot clears the abort latch, so a search still inside a hop can resume." ("needs the captured ticket's cancel flag (a TaskService accessor)"; 1.1.221 handoff, 2026-09-25)
- [ ] "Ownership is not re-checked on arrival." (Bot Storage collect; 1.1.220 handoff, 2026-09-25)
- [ ] "`verifyChests` revives a record for any chest placed back at its position." (1.1.220 handoff, 2026-09-25)
- [ ] "A dimension-fallback collect is dropped." (1.1.220 handoff, 2026-09-25)
- [ ] "Two Collects on different chests while the bot is on cooldown fail safe but silently." ("The owner sees two \"arrived\" lines and gets no items."; 1.1.220 handoff, 2026-09-25)
- [ ] "A server-thread chest placement miss logs only at DEBUG." (1.1.222 handoff, 2026-09-25)

### Souls, dialogue and audio
- [ ] "Audio for \"Hey, incoming!\"." (also listed in untracked `AUDIO_NEEDED.md`; 1.1.261 handoff, 2026-10-17)
- [ ] "Extend the punctuation guard to the JSON resources." (1.1.261 handoff, 2026-10-17)
- [ ] (uncertain) "#10 audio regeneration (needs Bradley's triage)" = "The missing `snow_golem_ammo` and other voice clips." (1.1.239 `ef4ea499` added empty-sound stubs only; 1.1.239 handoff, 2026-10-14; 1.1.223 handoff, 2026-09-25)
- [ ] "Later: scene smoothing step 2 (scene boundaries in history)" (1.1.223 handoff, 2026-09-25)
- [ ] "self-directed goals (proposer-only)." (1.1.223 handoff, 2026-09-25)
- [ ] "The Pocket installer's pre-wait EOF read, which can hang." (1.1.223 handoff, 2026-09-25)
- [ ] "HEARD records (the owner's own replies) are never marked private." (1.1.224 changelog deferral, 2026-09-25)
- [ ] "remote model pulls and voice installs on the server host" (1.1.225 handoff, 2026-09-26)
- [ ] "a voice-preset picker" (1.1.225 handoff, 2026-09-26)
- [ ] "a persona picker" (1.1.225 handoff, 2026-09-26)
- [ ] "auto-enable on spawn" (souls; 1.1.225 handoff, 2026-09-26)
- [ ] "dry-run generation" (1.1.225 handoff, 2026-09-26)
- [ ] "Addressee deferrals: mid-sentence third-person mentions, \"you and Bob\" clauses, human nicknames." (1.1.219 handoff; repeated 1.1.220-1.1.222, 2026-09-25)
- [ ] "the new `ollama usage` log lets `NUM_CTX` be right-sized." (1.1.217 handoff, 2026-09-25)
- [ ] "Unifying the two scripted services' cooldown implementations: larger refactor, no remaining field symptom." (1.1.216 handoff, 2026-09-07)
- [ ] (uncertain) "Adding a reason enum to `SkillExecutionResult`: touches every skill call site" (reads as won't-do; 1.1.216 handoff, 2026-09-07)
- [ ] "`threads.closed` matching (needs a per-question `SoulMindOps.markAnswered` overload)" (1.1.214 deferral; 1.1.216 and earlier handoffs, 2026-09-06/07)
- [ ] "`BAD_AT` from repeated `TASK_FAILED`" (1.1.213-1.1.214 deferral; 1.1.216 and earlier handoffs, 2026-09-06/07)
- [ ] "TEASE peer rule once `SoulSpeechAct` rides on `GroupSceneTurn`" (1.1.212-1.1.214 deferral; 1.1.216 and earlier handoffs, 2026-09-06/07)
- [ ] "`SoulBanterDirector.relationsEnabled()` static read → supplier" (1.1.216 and earlier handoffs, 2026-09-06/07)
- [ ] "`MIN_TRIGRAMS` 3→4" ("if the field session shows quoted-idiom drops"; 1.1.216 and earlier handoffs, 2026-09-06/07)
- [ ] "OWNER_DAMAGE-peer rule deferred (event carries only `ownerId`)." (1.1.212 handoff, 2026-09-06 evening)
- [ ] "ring idle eviction only if a leak shows" (1.1.211 handoff, 2026-09-06)
- [ ] "Deferred minors from the 1.1.203 branch: mask receiver unthrottled; wire cap 64 vs 9 ids" (voice mute masks; 2026-09-04 evening handoff)
- [ ] "Pocket voice cloning (HF-gated)" (never built, "Not in this cut" in 1.1.198; Backlog Lineup 2026-09-03 Lane 3)
- [ ] (uncertain) "ontology Phase 3 / second scripted-text personality (spec first)" (ontology half done; may mean the soul personality Silas shipped in 1.1.204; 2026-09-05 handoffs)

### MCP fork and tooling
- [ ] "keyboard in-world" (minecraft-mod-mcp fork; open in `docs/testing/MCP_MOD_IMPROVEMENTS.md`; 1.1.236 handoff, 2026-10-14)
- [ ] "per-action control mode upstream PR." ("Upstream PR plan ... nothing sent" in `MCP_MOD_IMPROVEMENTS.md`; 1.1.236 handoff, 2026-10-14)

### Docs and process
- [x] ✅ Ruled 2026-10-18: per instance (CLAUDE.md and the release skill updated). Was: Needs Bradley: "scope the CLAUDE.md pre-deploy check to the target instance (`pgrep -f "PrismLauncher/instances/1.21.11/natives"`). The any-instance pattern blocks every release while a sandbox test runs." (1.1.263 handoff, 2026-10-17)
- [x] ✅ Ruled 2026-10-18: generic credit "Voice lines are synthetic speech generated with licensed text-to-speech." for the release notes (no engine or voice names). Was: Needs Bradley: "the TTS engine and voice names for the dialogue-clip attribution line (still a placeholder)." (1.1.225 handoff; also 1.1.224 and 1.1.223 handoffs, 2026-09-25/26)
- [x] ✅ 1.1.268 Ruled 2026-10-18: lower the survival food cutoff to 8 (food keeps 8, other materials 16, `472afa60`). Was: Needs Bradley: confirm "Food reserve 16 as the survival cutoff (early game: a bot can't eat from a chest holding ≤16 bread)." (provisional ruling made on his behalf in 1.1.221; 1.1.221 and 1.1.222 handoffs, 2026-09-25)
- [x] ✅ Ruled 2026-10-18: keep 4 (com.bradley.ollama-env LaunchAgent, measured for souls). Was: Needs Bradley: "Whether to lower `OLLAMA_NUM_PARALLEL`." ("machine-wide; other local jobs share it"; 1.1.217 through 1.1.222 handoffs, 2026-09-25)
- [ ] Ruled 2026-10-18: decide after the 6aj-6bg field sweep (note any disagreement seen in play). Sweep done 2026-10-18: no disagreement seen, decision due. Was: Needs Bradley: "relations coexist with `PlayerMemory`, decide after the field session" (1.1.211 handoff, 2026-09-06)
- [ ] Ruled 2026-10-18: publish the first build after the field sweep with no high-severity failures (likely 1.1.268); notes prepared for Bradley's OK, he uploads. 1.1.268 qualifies; draft notes in `.superpowers/sdd/modrinth/MODRINTH_NOTES_1.1.268_DRAFT.md`, awaiting his OK. Was: Needs Bradley: "1.1.231–1.1.233 NOT published" on Modrinth (now everything after 1.1.230; held by the "no Modrinth until the live playthroughs settle" ruling, 2026-10-14; 1.1.233 handoff, 2026-10-13)
- [ ] (uncertain) "5. Fresh-install part 2." (no clean-instance run recorded; 1.1.236 handoff; also 1.1.234, 1.1.229, 1.1.227 and 1.1.225 handoffs)
- [ ] (uncertain) "(mention the REI/EMI/JEI note)" in the Modrinth changelog (1.1.228 handoff, 2026-10-13)
- [ ] (uncertain) "Session cards: task_43ec92da (merged finder, now largely done) and task_e6032cb8 (audit) can be dismissed or re-scoped by Bradley." (1.1.242 handoff, 2026-10-15)
- [ ] Tamed-animal defense "Manual verification checklist (15 items) is in the spec" (`docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md`), not in the field checklist (Tamed-animal defense notes, 2026-04-11)
- [ ] fabric.mod.json still declares ~1.21.10 though the 1.21.10 instances were removed 2026-10-11 (audit side finding)

## Backlog Lineup 2026-09-03

**Where things stood on 2026-09-03 (historical lineup, kept for its open lines; current state is the handoff at the top):** main at 1.1.199, deployed to all three Prism instances, 240 commits
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
- [x] **Woodcut fallback restart loop** ✅ 1.1.216 — repro captured in the 2026-09-06 field log
      (`logs/2026-09-06-session-overlap.log.gz`): 197 restarts at 3–19 per second. The 1.1.200
      diagnostic never printed, and that was the clue — the same wipe removed the signature its
      guard tests. Nothing exotic flips the signature: the active-task branch called
      `clearWoodenFallbackState` on the very next tick, removing the 12 s cooldown the fallback had
      just set for its OWN woodcut run, so the skill exited on the missing axe and restarted. Fixed
      by narrowing that clear to non-own tasks plus a per-(bot, hobby) doubling failure backoff
      (`HobbyBackoffPolicy`, 60 s to a 10-minute cap) with a throttled availability probe so an axe
      arriving in a reachable chest still wakes it within about five seconds. The product question
      underneath — should the axeless fallback punch one tree instead of refusing? — is still open
      and is now a design choice, not a bug. ✅ 1.1.229: ordered woodcut punches a tree for its first
      axe (`WoodcutAxeBootstrapPolicy`). Hobby runs still refuse (ruling, keeps HobbyBackoffPolicy).
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
      structured output, novelty rejection — (d) ✅ 1.1.211, (a) ✅ 1.1.212, (b) ✅ 1.1.213, (c) ✅ 1.1.214 — Phase 3 COMPLETE), Pocket voice cloning (HF-gated), per-player voice mute
      masks (gated on the stubbed config sync — see Multiplayer Voice Muting below).

### Housekeeping (one commit)
- [x] Decide on pushing the 240 commits. ✅ pushed 2026-09-04 (main = origin/main from 1.1.203 on).
- [x] `AGENTS.md` pgrep check — already synced to the natives pattern on 2026-09-03 (verified 2026-09-04).
- [x] `CLAUDE.md` + `AGENTS.md` "No automated tests" wording — gone as of 2026-09-03 (verified 2026-09-04); CI still `build -x test`.
- [x] `TODO.md` retired 2026-09-03; changelog header already points at RALPH_TASK.md (verified 2026-09-04).
- [x] Vault: `Frens.md` now says main 1.1.203 pushed (vault commit e8f9d83, 2026-09-04); the March backlog note was already updated 2026-09-03.

# Backlog

Future work items, organized by priority. Candidates for future builds through the frens-release-loop skill.

## P1 — High

### User-reported 2026-05-09 (top criticality)

User-flagged batch from in-game observation against deployed 1.1.93 (latest.log: `~/Library/Application Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log`). Listed in working ROI order; subsequent sessions should pick top-down.

- [x] **Stand-down hotkey + stop→drop-sweep cooldown (60s)** — ✅ shipped 1.1.95. New per-bot drop-sweep suppression layer in [DropSweepService](src/main/java/net/wcfcarolina13/GameAI/services/DropSweepService.java) (`suppressFor`/`isSuppressedFor`). `/bot stop` now sets a 60s suppression. New service [BotStandDownService](src/main/java/net/wcfcarolina13/GameAI/services/BotStandDownService.java) snapshots follow target, stops following, suppresses drop-sweep for 60s, then re-issues follow on tick expiry ("Back in formation."). Companion overlay slot 1 repurposed from duplicate "Stop" to `🪖 Stand Down (60s)`; new `bot standdown` brigadier command. Tap `\` still does plain stop. Final implementation differs from the original spec: timers live in `DropSweepService` (drop-sweep) and `BotStandDownService` (follow snapshot), not `BotHomeService` — closer to the existing per-service ownership pattern.
- [ ] **Creeper self-protection / back-away** — 🔧 1.1.202: root cause found and fixed (`BotCreeperDefenseService` filtered on `isIgnited()`, never set by proximity swelling; now `isFusing()`), decision extracted to tested `CreeperEvasionPolicy`, armed bots back away inside 4.5 blocks; **verify in the field session Phase 8**, then check off. Original report: Existing creeper evasion (sprint away when unarmed, [BotEventHandler.java:3794](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3794)) reportedly insufficient — bot still doesn't reliably back off when creepers are near. Audit fuse-distance / armed-vs-unarmed branches and tighten. Likely also needs a dedicated `BotCreeperSafetyService` or expansion of `BotHazardService`.
- [x] **Dangerous-pursuit gate** — ✅ shipped 1.1.97. New [DangerousPursuitGate](src/main/java/net/wcfcarolina13/GameAI/services/DangerousPursuitGate.java) composes 4 rules: target >4 blocks below bot → reject; combined light ≤0 at target → reject; 2+ hostiles within 5 blocks of target → reject; non-aggroed mob → require ranged weapon. Wired into [DropSweepService.collectNearbyDrops](src/main/java/net/wcfcarolina13/GameAI/services/DropSweepService.java) per-drop filter + [BotEventHandler.engageHostiles](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) hostile-filter loop. Self-defense (mob already targeting bot) always passes. Out of scope: route-based fall/light analysis (currently checks target only, not the path); charged-creeper weighting in cluster threshold.
- [ ] **BotTorchHoldService not visibly firing** — 🔧 1.1.217: diagnostics from 1.1.216 logs named the cause — holds DID fire (Jake, light 6–7) but yielded within ≤1 s at light 8 (spatial flicker); Bob carries no torches. Fixed with hysteresis (hold ≤7, keep until ≥12, `TorchHoldPolicy`); verify at night/underground in Phase 6o. 🔧 1.1.202 ships the state-change `[torch-hold]` gate diagnostics; field session Phase 8 names the rejecting gate, then fix. Original: — added in 1.1.72-1.1.91 batch but user reports torch never appears in bot's hand in dim follow situations. Service IS deployed (in 1.1.93+ JAR) and registered ([Frens.java:1131](src/main/java/net/wcfcarolina13/Frens.java#L1131)). Likely caused by: (a) overly-strict 8-block audible-hostile suppression — in caves/at night there's almost always *some* hostile in 8 blocks → torch hold rejected; (b) foreign-swap detection cycling against other systems that mutate selected slot every tick (combat loadout, AutoFaceEntity), so torch gets re-overridden between 5-tick eval intervals; (c) only logs at `LOGGER.debug` so we can't tell which gate is firing. Diagnostic-first: bump key transitions to `LOGGER.info`, ship a build, see what the gate actually rejects. Possible fix after diagnosis: drop the 8-block audible gate, keep only the 16-block visible-LOS gate. Don't touch until diagnostic confirms the root cause.
- [x] **Locked-gate enclosure respect (drop-sweep + pursuit)** — ✅ shipped 1.1.98. New ray-cast helper [DangerousPursuitGate.crossesLockedGate](src/main/java/net/wcfcarolina13/GameAI/services/DangerousPursuitGate.java) samples ~1 cell per block along the bot→target line and returns true if any sample is a tracked locked door / fence gate / trapdoor. Wired into both `isLocationSafeForPursuit` (drop-sweep) and `engageHostiles` combat-target filter. Caveat: over-rejects when a wall has BOTH a locked gate AND an unlocked gap (line crosses the locked cell while a legitimate path goes around). Under-rejection was the actual user complaint, so the trade-off is acceptable. Reuses existing LockableBlockService — no new wall semantics.
- [x] **Bed selection bugs (two related)** — ✅ shipped 1.1.100, user-verified in-game 2026-05-17 (bot now places and uses its own bed from inventory when no nearby bed is available). (a) [SleepService.findNearbyBedFeet](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java) now filters beds with `BedBlock.OCCUPIED == true` and sorts the bot's previously-claimed bed (via `BotHomeService.getLastSleep`) first. Defensive late-occupancy guard added to `tryUseBed`. (b) When all nearby beds are filtered (only-occupied case), the existing placement branch fires with an explicit "Nearby bed is taken. Setting up my own." handoff message. Out of scope: bed reservation across mid-night user step-outs; multi-bot claim contention.
- [~] **Pathfinding cache learning isn't measurably improving** — diagnostic surfacing shipped 1.1.101. [NavHazardCache](src/main/java/net/wcfcarolina13/GameAI/services/navigation/NavHazardCache.java) is the actual learning system (per-cell rejection scoring; pathfinders consult via `penaltyFor`). Wiring is correct — recording fires per `applyMovementInput-reject` (903 today's session), pathfinders read the penalty in both [PathFinder.java:152,187](src/main/java/net/wcfcarolina13/PathFinding/PathFinder.java#L152) and [BaritoneStylePathFinder.java:380](src/main/java/net/wcfcarolina13/PathFinding/BaritoneStylePathFinder.java#L380). Penalty hits now log at INFO (throttled 1/s, ≥1.0 penalty); periodic summary every 5 min lists top scoring cells. Architectural caveat: cache helps when alternative routes exist; tight bottleneck doorways (the user's actual stuck cases) have no alternates, so cache doesn't help there even when working perfectly. Out of scope: chat command for on-demand cache dump (`/bot debug nav-hazard`); promotion-event INFO log; tuning `STREAK_PROMOTION_THRESHOLD` after we have real data.
- [ ] **Doorway / pressure plate stuck (still recurring)**: 🔧 1.1.223: two follow bugs at a plate-driven fence gate fixed (door handling off beyond 6 blocks; summit climb hijack) and bots no longer close powered/plate-adjacent doors — verify in Phase 6u before closing this. User reports persistent stalls at doorways and pressure plates despite the 1.1.5 → 1.1.16 fix series. Re-read the 2026-04-16 door passage session notes (now in `docs/handoffs/ARCHIVE_2026.md`; the known-good list and test procedure are also in `docs/GUARDRAILS.md`) before touching this — their "Architectural concern" section (drop the door-plan state machine entirely; emit door tiles as regular pathfinder waypoints with an `InteractWithDoorGoal`-style observer) is the recommended next move. Discuss with user before attempting; this is a multi-day rework.

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
- [x] **ScaffoldService extraction** ✅ 1.1.208 (ladders moved. Not migrated, on purpose: `FarmSkill.pillarEscape` (its fixed 120 ms timing, placement and lack of a 12-step cap differ, so migrating is a behaviour change to judge in the field first), `WoodcutSkill.pillarUp` and `HovelPerimeterBuilder.pillarUp` (each needs 2–3 new options), `WoodcutSkill.clearBlockingLeaves` (mutates reach-session counters); changelog 1.1.208. Tracked as an open line under "Recovered deferrals", Construction, scaffold and shelter.)
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
- [ ] **Named-hostile-mob pacifism: in-game verification (reopened 2026-10-17)** — no field phase covers it and the "6-item test plan" it cites was not found in the old session notes or `docs/superpowers/specs/` (the notes are now in `docs/handoffs/ARCHIVE_2026.md`). Verify: a name-tagged hostile is not engaged, flee still fires when it deals damage, `/bot attack_named_mobs on|off|toggle` and the Admin row flip it. Original line: implemented in `aeef62a` (1.1.55). `BotCombatPolicyService.shouldBotAttack` gates engagement; named hostiles still appear in scans so flee fires on damage; per-bot `attackNamedMobs` opt-in toggle reachable via `/bot attack_named_mobs <on|off|toggle> [target]` and the Admin tab "Attack Named Mobs" row. **Manual in-game verification still pending.**
- [x] **`CompanionSpellsScreen` cleanup — partial**: Audited 2026-05-06. The legacy screen is **not** fully decommissioned — it's still reached via the dedicated `KEY_OPEN_SPELLS` keybind path in [FrensClient.java:660 → 1578](src/main/java/net/wcfcarolina13/FrensClient.java#L660), the recruit-contact key fall-through after recruitment, and the temporary `-` go-to-spells override (when holding a spell trigger item). `isEyeSpellOnCooldown` / `armEyeSpellCooldown` are also still consumed inside `FrensClient` itself, not just the legacy screen. So the original "delete the file" plan would break the keybind UX. What WAS removable: the dead `openSpellsMenu` method in `BotPlayerInventoryScreen` — defined but never called. Removed in 1.1.68. Open follow-up: decide whether to migrate the `KEY_OPEN_SPELLS` keybind path to also use the unified Spells tab (would let us actually delete the legacy screen) or accept the dual UX as intentional.
- [x] ✅ 1.1.207 (changelog "Command pruning review": the Actions-tab Regroup entry is gone, Spells keeps it; the `Return Home` vs `Home` label question below was not part of that fix) **Actions-tab Regroup duplicate**: `Regroup` lives in both the Actions tab (`Orders & Travel`) and the Spells tab (Movement). Users may hit the Actions-tab version first; it runs `/bot companion come` which the server then rejects if artifacts are missing. Decide: keep both with a visible "gated" indicator on the Actions copy, or remove Regroup from Actions and leave it in Spells only. Same question for `Return Home` (Actions) vs `Home` (Spells) — distinct actions (`RETURN_HOME` vs `COMPANION_HOME`) so this may just need clearer labels.

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

## Bot livestream — cinematic Twitch/YouTube feed (Backlogged 2026-10-13)

Bradley wants a live feed of the bots living their lives, with cinematic camera angles. Plan discussed 2026-10-13:
- [ ] **Scope first: bots with no owner nearby.** Much behaviour orbits a commander. Verify what hobbies, idle/active
  banter and SpeechFloor (keyed by a real-player audience) do with only a spectator account online. Decides whether a
  "living world" mode (self-chosen goals: build, farm, fish, explore, go home at sunset) is needed.
- [ ] **PoC director:** second MC account in spectator mode; client-side director cuts `/spectate <bot>` + third person
  to whoever is speaking/acting (server already knows scene starts and speakers); OBS → unlisted YouTube.
- [ ] **Cinematic camera:** free camera with a shot vocabulary (establishing wide, orbit, shot/reverse-shot in
  dialogue, tracking on travel, event cutaways), smooth interpolation; OBS browser-source overlay fed by a small local
  endpoint (speaker subtitles, current task, time of day).
- [ ] **24/7 hardening:** launchd watchdog/restart, memory/log growth, LLM content filter + kill switch (Twitch ToS),
  probably off-laptop hosting (server + Ollama + TTS + MC render + OBS encode overloads one M2 Pro). Twitch-chat
  interaction later only, behind prompt-injection safeguards.

## Installer portability (Backlogged 2026-09-04 — only ever tested on the M2 Pro Mac)

Bradley asked whether the installers cater to the user's machine. They detect **platform**, not specs:
- [x] **Pocket TTS installer has no Windows support** ✅ 2026-09-04 (`efd13c7`, `7944410`) — Windows uv/py-launcher/python discovery, `Scripts\` venv layout, WindowsApps stub exclusion, py-launcher false-positive fix. **Still to verify on a real Windows box**: `pocket-tts.exe` console-script name, no-shell process start, `Files.isExecutable` on `.exe`. Original: — `PocketInstaller.uvCandidates/pythonCandidates` search Homebrew, python.org framework, `/usr/local` and `PATH` for `uv`/`python3`; no `uv.exe`/`python.exe`/`py` launcher, no `Scripts/` venv layout. A Windows user with Python installed gets "No Python 3.10+ or uv found". Add Windows candidates + venv `Scripts\python.exe` path, and test.
- [ ] **Piper Windows x64 / Linux x64 paths are pinned (sha256) but untested** — zip extraction on Windows and the `.exe` name, `.so` completeness on Linux. ARM Windows/Linux are explicitly unsupported (screen says so). Needs one run each.
- [x] ✅ 1.1.225 (RAM half: the model manager reads total RAM and warns; the checklist shows the server host's RAM. Cores/GPU are still undetected.) **No RAM/cores/GPU detection anywhere** — the Ollama model manager shows a recommended-RAM guide per model but does not read the machine's RAM; Pocket pins `OMP_NUM_THREADS=1`; Piper spawns one process per voice. A cheap win: read `Runtime.maxMemory` / `OperatingSystemMXBean.getTotalMemorySize()` and warn when a model's recommended RAM exceeds it.
- [x] ✅ 1.1.203 (changelog "Dreamsleeve greyed off macOS": the engine chooser greys the row and says "macOS only") **Dreamsleeve is Mac-Metal only by construction** — fine, but the engine chooser should grey it out off-macOS instead of letting the user configure a path that can never work.
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
