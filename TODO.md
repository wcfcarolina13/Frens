

# Gemini Report 2

## Consolidated TODO (from this report)

### P0 — Stability & Control Flow
- [ ] **Task lifecycle reset**: `/bot stop` and respawns must abort the active ticket and release the RL loop immediately via `TaskService`. *DoD:* issuing `/bot stop` during any skill halts movement within 1 tick; new command starts without “busy” spam.
- [ ] **Busy-loop prevention**: Ensure `AutoFaceEntity.isBotMoving` and related flags are always cleared in `finally` blocks; verify no background schedulers re‑latch movement. *DoD:* run a 5‑minute idle RL loop without any “Bot is busy” spam.
- [ ] **Blocking drop cleanup path**: Skill cleanup calls a synchronous drop sweep (delegating to `DropSweeper`/`MovementService`), then releases the override. *DoD:* after `collect_dirt`, all visible drops within 6 blocks are vacuumed before “job complete”.
- [ ] **Cooldowns & backoff**: Keep `dropSweepInProgress`, per‑position backoff (~15s), and repeat throttles active so RL tick doesn’t spam navigation. *DoD:* log shows at most one sweep attempt per 3s when items persist.

### P0 — Water, Terrain & Navigation
- [ ] **Water‑aware pickup**: `MovementService` handles shallow‑water detection and wade/bridge routines; `DropSweeper` delegates all movement to it. *DoD:* staged test with ankle‑deep water succeeds (bridge or wade) with no infinite loops.
- [ ] **Edge/hole pickup**: When items are one block below an edge, bot hops down safely instead of “sneak vacuum”. *DoD:* bot drops into 1‑block hole to collect item, then climbs out.

- [x] **Persistent inventory (sessions only)**: Bot retains inventory across world reloads/spawns (not across deaths). *DoD:* equip items, exit world, re-enter, respawn bot—inventory identical.
- [ ] **Bot aliases & identity**: Distinct aliases (e.g., Jake vs Bob) preserve inventory/hunger/sleep/XP separately. *DoD:* spawn two aliases; their states remain isolated across sessions.
- [ ] **Job resume prompts**: On death/leave, job is paused; on rejoin or respawn, bot asks “Should I continue/return?”. *DoD:* chat prompt appears once; user “yes” resumes or performs corpse‑run retrieval.

- [ ] **Per‑bot chat addressing**: Messages to a bot name route to that bot’s memory and response pipeline. *DoD:* “Jake, status?” yields Jake‑specific reply.
- [ ] **Broadcast commands**: Support targeting all bots (e.g., `allbots`) for skills/commands. *DoD:* issuing a group command affects all active bots once.
- [x] **Inventory summary to chat**: `/bot inventory <alias>` prints a concise header and grouped slot counts directly in chat (not just logs). *DoD:* Single chat message includes bot name and item summary; no crash on missing alias.
- [x] **Bot gives items to player**: `/bot give <alias> <item> [count]`. If the bot has the item, it drops/throws the requested count toward the requesting player within range; default count = 1. Selection priority: avoid hotbar first, then prefer most‑damaged stack (when applicable), else the smallest stack. If missing, reply in chat: `I don't have that`. *DoD:* Success/fail message in chat; items appear at player within 2s; respects priority rules.
  *Status:* Verified 2025-11-12 (plain & namespaced ids; success/unknown/missing paths).

### P0 — Combat Safety
- [ ] **Creeper evasion**: If unarmed/unarmored, always sprint‑evade creepers; otherwise prefer ranged/melee per kit. *DoD:* bot never stands still near a hissing creeper; records a single red danger alert (4s cooldown).
- [ ] **Protected build zones / no-grief halo**: Allow players to mark protected areas (e.g., by placing a special marker block/item and invoking a command while looking at it). Within a configurable cubic radius around each marker, bots must never perform destructive actions (mining, tree chopping, structure modification), but may still move, follow, and fight. *DoD:* strip-mining and collect_* skills refuse to break blocks inside protected regions while still functioning normally outside them.

### P1 — Portals, Boats, Swimming
- [ ] **Swimming parity**: Bot swims like a player (surface and underwater) without teleport reliance. *DoD:* 20‑block swim to a target item succeeds.
- [ ] **Boats**: Enter/exit/navigate boats reliably. *DoD:* bot boards, crosses river, disembarks at target without teleport.
- [ ] **Nether/End portals**: Follow player through portals; support cross‑realm teleport command for stranded bots. *DoD:* escort through Nether portal and recall from other realm via command.

### P1 — Crafting & Utilities
- [ ] **Test kit for `/equip`**: Running `/equip` gives the bot a standard testing loadout: 2× each of a good pickaxe/shovel/hoe/axe, good fishing rod, boat, lead, crafting table, furnace, 2 chests, water bucket, shears, and a bed. This is a test harness and does not consume player inventory. *DoD:* `/equip` reliably populates the bot’s inventory with the listed items; no duplication beyond intended test run; kit can be toggled on/off via config.
- [ ] **Crafting basics**: Place and use crafting table, furnace, chest; craft bed, shears, bucket, weapons, tools, torches, sticks, planks, armor. *DoD:* scripted recipe flow succeeds from raw logs to a stone/iron tool.
- [ ] **Workspace heuristics**: Place crafting table and furnace adjacent; double‑chest placement coalesces correctly. *DoD:* chests merge; workstation blocks end up side‑by‑side.

### P1 — Building & Placement
- [ ] **Block placement primitives**: Build walls (materials/radius/thickness/height) and basic squares. *DoD:* “build wall radius=5 height=3” yields correct footprint.
- [ ] **Simple 2‑person house**: Minimal enclosed shelter with door, light, and two beds. *DoD:* house generated in flat test world matches spec.

### P1 — Farming & Husbandry
- [ ] **Farming loop**: Till soil, collect seeds, plant, harvest, and re‑plant. *DoD:* 5×5 plot cycles once without human aid.
- [ ] **Water handling**: Collect water and create an infinite source. *DoD:* working 2×2 infinite pool built nearby.
- [ ] **Animals**: Shear sheep, collect meat, lead animals, and build a fenced pen. *DoD:* two animals penned with leads removed.

### P1 — Woodcutting & Mining
- [ ] **Tree routines**: Chop trees safely, climb to reach stragglers, return to collect late drops. *DoD:* zero floating logs; all drops collected.
- [ ] **Strip mining**: Maintain 1‑block safety offset vs sand/gravel/lava; stop/report on cave/precipice/structures. *DoD:* 20‑block strip mine completes; special features reported in chat.

### P1 — Cooking & Survival
- [ ] **Furnace usage**: Smelt/cook with appropriate fuels. *DoD:* cook raw food and smelt ore in sequence.
- [ ] **Hunger persistence & eating policy**: Persist hunger across sessions; prefer lowest‑value food first (avoid rotten flesh). *DoD:* bot eats automatically at thresholds and announces hunger states.

### P1 — Sleep & Following
- [ ] **Sleep integration**: Bot uses a bed so night skip works; warns at sunset and before phantoms if overdue. *DoD:* server night skip succeeds with bot in bed.
- [ ] **Follow/defend modes**: Bots can follow each other and defend teammates (e.g., `fight_with_me`). *DoD:* two bots follow and assist in combat.

### P2 — PVP & Formations
- [ ] **Sparring**: Optional sparring with distance constraints. *DoD:* start/stop sparring leaves no lingering “busy” flags.
- [ ] **Formations**: Line and grid; later archers back, horses flank. *DoD:* bots hold a 5×3 grid while moving to a waypoint.

### P2 — Debug & Telemetry
- [ ] **Debug toggle**: Runtime toggle to quiet terminal/chat spam while preserving red danger alerts (4s cooldown). *DoD:* `debug off` reduces logs >80% by count.

#### Verification Scenarios (from “Next Steps”)
- [ ] Stage dirt harvest leaving drops in ankle‑deep water and verify bridge/wade path with `MovementService` logs.
- [ ] Trigger `/bot stop` mid‑skill and force a respawn; confirm `TaskService` clears ticket, releases override, and accepts new command.
- [ ] Run a short training loop; confirm RL tick still advances and drop collector doesn’t spam movement.


## Issue: Bot gets stuck in a busy loop and doesn't collect drops.

### Analysis

The user reported that the bot gets stuck in a "busy" loop and doesn't collect drops after a skill is complete. The logs confirmed that the bot was spamming "Bot is busy" messages.

My analysis revealed these issues:

1.  The `isBotMoving` flag in `AutoFaceEntity.java` was not being reset correctly, causing the bot to think it was still moving and get stuck in a "busy" loop.
2.  The `DropSweeper.java` class was not reliably collecting drops. The `GoTo.goTo` method was returning a success message even when the bot had not reached its destination, causing the `DropSweeper` to get stuck in a loop.
3.  The `findClosestDrop` method in `DropSweeper.java` was returning drops that were already within the bot's pickup range, causing pathfinding failures.
4.  The `collectNearbyDrops` method in `BotEventHandler.java` was not a blocking call, which caused the `externalOverrideActive` flag to be set to `false` prematurely, preventing the bot from collecting drops and resuming its RL loop.
5.  The `externalOverrideActive` flag was not being reset for single function calls in `FunctionCallerV2.java`, causing the RL loop to stop.
6.  A missing import for the `GoTo` class in `BotEventHandler.java` caused a compilation error.
7.  The bot is getting into a "busy" state even without any skill being executed, which prevents the RL exploration loop from running.

### Changes

I have made the following changes to address these issues:

-   **`BotEventHandler.java`**:
    -   Added `AutoFaceEntity.isBotMoving = false;` to the `finally` blocks of the `detectAndReact` and `detectAndReactPlayMode` methods to ensure the `isBotMoving` flag is always reset.
    -   Created a new `collectNearbyDrops` method that uses the more reliable item collection logic from the "guard" mode.
    -   Modified the `collectNearbyDrops` method to be a blocking call that waits for the bot to collect drops before returning.
    -   Replaced the `moveToward` call in `collectNearbyDrops` with `GoTo.goTo` to enable proper pathfinding to drops.
    -   Added a call to `collectNearbyDrops` in the `detectAndReact` method, so the bot collects drops as part of its reinforcement learning exploration.
    -   Added the missing import for the `GoTo` class.
-   **`SkillManager.java`**:
    -   Replaced the call to `DropSweeper.sweep()` with a call to the new `BotEventHandler.collectNearbyDrops()` method.
-   **`GoTo.java`**:
    -   Modified the `goTo` method to return a failure message when path tracing fails.
-   **`FunctionCallerV2.java`**:
    -   Added `BotEventHandler.setExternalOverrideActive(false)` to the `finally` block for single function calls to ensure the external override is disabled after the function is executed.
-   **`AutoFaceEntity.java`**:
    -   Added more detailed logging to the "Bot is busy" message to show the status of each flag that contributes to the busy state.

### Codex follow-up – 2025-11-03

-   Reworked `BotEventHandler.collectNearbyDrops` so it only schedules a drop sweep when fresh item entities are in range, throttles repeats, and hands the sweep off to the main server thread. During training runs the RL loop remains active; in play mode we temporarily set the external override so scripted movement does not clash with sweeps.
-   Added lightweight state (`dropSweepInProgress`, cooldown timestamps) to prevent the RL tick from spamming navigation requests or latching `isBotMoving`.
-   Restored skill cleanup to call `DropSweeper.sweep` directly with the tighter parameters we already trust, keeping loot pickup synchronous with the job while the override flag is still held.
-   Drop sweeps now reuse `DropSweeper`’s verifier logic, so we no longer depend on raw `GoTo` return strings when deciding whether to retry or bail out.
-   Added per-position backoff so stubborn drops aren’t retried for ~15 s, pruned the verbose state-consistency printlns so RL tick logs stay readable, and let training-mode ticks attempt a quick manual nudge with a short GoTo fallback instead of the full drop sweep so the loop keeps moving.
-   Paused the rainbow chat formatter; normal talk now stays plain, while danger broadcasts go out in red with a 4 s cooldown to avoid flooding players.
-   Guard/patrol cleanup now funnels into the shared drop collector so escort duties scoop nearby loot just like post-skill cleanup.
-   Skills honour combat interruptions: `SkillManager` tracks the active job, sends a red “Pausing skill …” alert once, and affected skills (`collect_dirt`, `dirt_shovel`) bail out quickly when the pause flag is raised.
-   `collect_dirt` now steps off its perch to vacuum nearby drops—if loot is offset even slightly, it nudges toward the item before giving up.
-   The RL agent’s exploration floor rose to 0.25 and decay slowed, keeping idle exploration livelier instead of collapsing to near-zero action.
-   Danger warnings now trigger whenever Jake spots a hostile the player can’t see, still throttled so you get a single red alert instead of a rainbow spam.
-   **2025-11-03 22:10 CST – Refactor Plan**
    1. **Water-aware navigation:** extend `collect_dirt` and `DropSweeper` to recognise shallow water and use a controlled wade/bridge routine instead of path-finding loops that never complete.
    2. **Robust task lifecycle:** introduce an explicit Skill/Command state machine (idle → running → paused/aborted → completed) so respawns or manual `/bot stop` instantly reset the bot and unblock new commands while restoring combat/autopilot hooks.
    3. **Subsystem simplification:** audit overlapping helpers (`SkillManager`, `DropSweeper`, exploration/reposition utilities) and consolidate into fewer modules with clear interfaces (e.g., `MovementService`, `TaskService`) to remove redundant logic that cancels out other features.
    4. Document the refactor goals and perform the cleanup in the next session.

### Codex follow-up – 2025-11-04

-   Added `MovementService`, a shared navigation helper that plans direct, wading, or bridge-assisted approaches with shallow-water detection and manual nudges when GoTo cannot finish the last block.
-   `DropSweeper` now delegates every pickup attempt to `MovementService`, so loot in or beyond water is handled by building a plank first and retrying instead of looping on unreachable GoTo targets.
-   `collect_dirt` loot cleanup reuses the same planner; after a successful dig it now plots the same wade/bridge routine and only falls back to the legacy manual nudge if the drop is still offset.
-   Introduced `TaskService`, a global task lifecycle tracker that replaces `SkillManager`’s ad-hoc session flags; `/bot stop` and respawns immediately abort the active ticket and release the RL loop while preserving follow-mode resumes on clean exits.
-   `SkillManager` now consults `TaskService` for pause/abort signals, skips post-skill sweeps when an abort is requested, and defers chat messaging to the service to keep cancellation text consistent.

### Next Steps

-   [ ] Stage a dirt harvest that leaves drops in ankle-deep water and confirm the sweep builds a bridge or wades in using the new `MovementService` logs.
-   [ ] Trigger `/bot stop` mid-skill and force a respawn to verify `TaskService` clears the ticket, releases the override, and lets a new command start immediately.
-   [ ] Re-run a short training loop to ensure the RL tick still advances and the drop collector doesn’t spam movement requests.

[x] Chat inventory summary: `/bot inventory <alias>` prints inventory details in chat.
[x] Bot item handoff: `/bot give <alias> <item> [count]` — bot drops/throws items to the player with the specified priority rules; missing items yield `I don't have that`. (Verified 2025-11-12)
[ ] Bot item inventory view: Can interact with bot inventories the way you would a chest.
    - [ ] Restructure BotInventoryScreen layout so bots have a clear Equipped section (armor, main hand, offhand) and a separate Backpack grid backed by BotMainInventoryView (27-slot chest-like layout).
    - [ ] Add a visible 9-slot Hotbar row for the bot at the bottom of the inventory UI so players can see and manage the bot's active bar.
    - [ ] Add slot borders and section headers (Equipped / Backpack / Hotbar) so the bot inventory feels consistent with vanilla player and chest screens.
    - [ ] Ensure shift-click, double-click, and drag interactions in BotInventoryScreen mirror vanilla chest/player inventory behavior when moving items between player and bot.
    - [ ] Add optional quick-action buttons in the bot inventory screen (Sort, Equip Best, Take All, Give All) wired to server-side handlers, keeping them disabled or hidden if permissions fail InventoryAccessPolicy checks.
    - [ ] Wire BotInventoryAccess and InventoryAccessPolicy so opening a bot inventory uses the new chest-like view while still enforcing access rules.
[...]

[x] I built and tested, though your last message was incomplete due to hitting my usage limit with Codex. Let's take another look at where you left off, in case the job was not completed. Either way, I did build and test already. I tested with the player entity and it's not possible to hoover up item entities by sneaking up against the edge of a block to pick up something a block below it (in a hole). Therefore, the bot should instead determine if it's a safe block and then hop down toward it. It should drop inside the hole, basically. It appears that one needs to be directly on the surface of the block where the item entity resides in order to pick it up.


### Protecting player builds
[ ] Add a method to define no-destruction zones around player constructs (e.g., place a specific marker block, look at it, and run a command). Within a given cubic radius, bots may not perform destructive tasks like mining, tree cutting, or block breaking, but can still navigate, follow, and fight inside that area.



To do next:

[x] **CRITICAL: Multiple task execution bug** - Not a bug; tasks run sequentially on thread pool (verified in logs)
[x] **Mining skill ignores targetCount parameter** - Fixed with parameter parsing restoration
[x] **Depth mining stops at 10 blocks instead of reaching target depth** - Fixed (line 185 sets targetCount=Integer.MAX_VALUE)
[x] **Tool selection not defaulting to best available** - Fixed; now searches entire inventory and swaps best tool to hotbar
[x] **Direction persistence across stripmine jobs** - Fixed; WorkDirectionService stores direction, `/bot direction reset` command added

[x] **Rare ore discovery - no pause/resume loop** - Bot announces ores but doesn't pause on every discovery
    - When rare ore found during mining, bot announces it in chat only ONCE per position
    - Bot remembers ores discovered during current job session
    - Bot continues mining past already-discovered ores without re-announcing
    - DISCOVERED_RARES map tracks positions seen this session, cleared only when new job starts
    - Adjacent warnings report ores without blocking the job
    
[x] **Torch placement during stripmine/stairs** - Place torches automatically when dark
    - Check light level periodically during stripmine or stair-building (every 8 blocks)
    - When light level drops below threshold (7), place torch on perpendicular wall
    - Search inventory for normal torches (minecraft:torch)
    - If no torches available: pause job, announce "Ran out of torches!" in chat
    - Player must `/bot resume` after providing torches to continue
    - Torches placed on walls to avoid blocking bot's path
    - Works for both StripMineSkill and CollectDirtSkill staircase mode
    
[ ] **Protected zones - no block breaking** - Command to create no-grief regions
    - `/bot zone protect <radius>` while looking at a block to mark it as center
    - Creates cubic protected zone around marker position
    - Bots refuse to break blocks in protected zones (mining, tree chopping, any destructive action)
    - Bots can still move, follow, fight in protected zones
    - Protected zone list persists across sessions
    - `/bot zone list` shows all protected zones
    - `/bot zone remove <x> <y> <z>` removes protection at coordinates
    - Ensure chests, barrels, spawners are NEVER broken by bots (hardcoded in exclusion list)
    - Check MiningHazardDetector already blocks chests (CHEST_BLOCKS - verified present)


[ ] Ensure when using chat, you can talk to individual bots by using their name and get responses based on that bot's unique memory files.
[ ] Ensure bot stops jobs if it respawns or if you leave the server, but it will return to its previous location once you spawn it back in on your next session. It will say "I died, should I return?" so ther player can send it to collect its dropped items and continue previous job. Alternatively, if you've re-entered the world, it'll say "Ah, you're back. Should I continue?" to continue its previous job.
[ ] Ensure you can use commands or assign skills to individual bots but ALSO all bots at the same time (probably just "allbots")
[ ] Ensure we haven't over-complicated persistence for individual bots (how is it handled in vanilla for invididual players?)
[ ] Bot ran toward a creeper with no weapon or protection and froze up. It should have a faster evasion maneuver for creepers specifically due to the explosion. If it has no weapon or protection it should always try to sprint away from the creeper.

### Dealing with underground/caves
[ ] If targeted items are too far away, it'll say so in chat and drop the job.

### Dealing with underwater

[ ] Bot should be able to swim like a human player

### Dealing with boats

[ ] Bot should be able to enter, exit, and navigate a boat without relying on teleportation.

### Dealing with Nether & End

[ ] Bot should be able to follow the player through portals.
[ ] A bot left in another realm can be teleported to another world with a command.

### Dealing with crafting

[ ] Bot can build a crafting table, furnace, and chest
[ ] Bot can build bed, shears, bucket, weapons, tools, torches, sticks, planks, and armor

### Dealing with placing blocks

[ ] Bot can place different blocks, build walls (with specified materials, radius, thickness, and height), and build rudimentary structures like squares
[ ] Bot will attempt to place crafting table or furnace directly next to one another (workspace)
[ ] Bot will place chests directly next to one another (to expand chests and build storage)

### Dealing with building simple structures

[ ] Bot can build a simple 2-person house

### Dealing with farming

[ ] Bot can till soil, collect seeds from grass, plant seeds, harvest crops, and replace harvested crops with newly planted seeds

[ ] Bot can build simple farm
[ ] Bot can collect water
[ ] Bot can create an infinite water source

### Dealing with animal husbandry

[ ] Bot can use shears on sheep
[ ] Bot can collect meat from wild animals
[ ] Bot can use lead to capture animals
[ ] Bot can build a fenced area

### Dealing with horseback riding

[ ] Bot can tame, capture, and mount a horse
[ ] Bot can craft a saddle

### Dealing with woodcutting
[ ] Bot can chop wood
[ ] Bot can safely climb trees to collect all wood
[ ] Bot can return to fallen tree area to collect late drops

### Dealing with mining
[ ] Bot can strip mine (keeps distance of at least 1 block to avoid sand, gravel, or lava)
[ ] Bot can mine until finding certain things, with a proceed vs report option (in case we want to handle stuff like diamonds on our own)
[ ] Bot will stop and report if it finds a cave, precipice, ancient city, or any other special structure like a mineshaft or mob spawner
[ ] Bot will stop if it encounters water and alert the player.

### Dealing with cooking
[ ] Bot can use a furnace with various types of fuel to cook and smelt various items
[ ] Make sure hunger works and is persistent
[ ] Ensure bots will eat when hungry, starting with worst-quality food (don't eat food with poor trade-offs like rotten flesh)
[ ] Have bots send messages in chat when hungry, ravenous, and starving (close to dying from starvation)

### Dealing with sleeping
[ ] Bots must sleep in a bed for player to skip night
[ ] Bots comment on sunset approaching if outside
[ ] Bots mention that we haven't slept in a while the night before player triggers phantom spawns

### Following

[ ] Implement bots following one another (all or many) and defending one another (fight_with_me)

### PVP
[ ] Make it possible to spar with bot or have one or many bots spar with one another (with, for example, a 5 block distance in a line)

### Army formations
[ ] Start with simple line and row/grid formations, then expand to putting archers in back and horses on flank


### Debug Toggle
[ ] To improve latency, let's make it possible to toggle off most of the terminal spam

## LLM Personality, Memory, and Command Orchestration

**Phase 0 – Recon & Mac dylib sanity**

- Map current LLM path:
  - Inspect `NLPProcessor`, `FunctionCaller`, any DJL / `*.dylib` loader, and how chat is wired into skills.
  - Identify where persistent memory was *supposed* to live (files, SQLite, qtable, etc.).
- Fix the macOS `.dylib` issue:
  - Find the code that constructs native library paths (likely double-appending `.dylib` or mangling `java.library.path`).
  - Make path building idempotent and log the final resolved path once so we can verify it.
  - Confirm a simple LLM call works again on macOS with no crashes or fallback stubs.

**Phase 1 – Core architecture & toggles**

- Add a central “LLM brain” service:
  - New class (e.g., `LLMOrchestrator`) that is the *only* way game code talks to the LLM and the persistent memory.
  - Expose async APIs like `analyzeChat(worldId, botIds, message)` and `proposeActions(...)`.
- Add configuration / toggles:
  - Global toggle: `/bot config llm on|off` (per-world), stored under the existing config mechanism.
  - Per-bot override: `/bot config llm on|off [alias]` so you can have some “dumb” bots.
  - When disabled, the chat pipeline behaves exactly as today.
- Wire the orchestrator non-invasively:
  - Wrap existing chat → NLP entry point rather than replacing it.
  - Keep all current hard-coded responses and fallbacks; the LLM layer *adds* behavior, it doesn’t own everything.

**Phase 2 – Identity & memory model (world + bot scoped)** *(in progress: personas + per-world JSON memory store live; remaining work: richer intent-classification feedback and memory summaries for command confirmations)*

- Define IDs:
  - `worldId`: derived from the save folder / dimension (e.g., `New World:overworld`).
  - `botId`: bot UUID (plus human-readable alias).
- Design storage:
  - Back memory with a lean on-disk store per world:
    - Likely SQLite (you already ship `sqlite-vec`) or simple JSON/NDJSON per world under `run/sqlite_memory/<worldId>/` or similar.
  - Tables/sections:
    - `bot_profile` (per world+bot: persona description, quirks, backstory hooks).
    - `player_profile` (per world: preferred name, playstyle notes, recurring topics).
    - `world_facts` (things found: structures, biomes, memorable events).
    - `event_log` (recent deaths, discoveries, achievements; used to drive comments).
- Enforce world isolation:
  - Orchestrator always passes `worldId`; memory queries are scoped to that world only.
  - Never open or read memory DBs from other worlds.

**Phase 3 – Personalities (TES-style companions)**

- Persona templates:
  - Define a small set of archetypes (e.g., “grizzled veteran miner”, “bookish mage”, “chaotic thief”, etc.) as prompt snippets in a data file.
  - Include tones, favorite topics, reaction patterns, and how they talk about other bots/players.
- Auto-generate bot quirks:
  - On first spawn in a world, pick an archetype + roll 2–3 quirks (e.g., “mildly afraid of water”, “obsessed with lapis”, “keeps score vs. Bob”).
  - Save these in `bot_profile` so they persist for that world.
- Prompt composition:
  - When calling the LLM, build a prompt including:
    - Archetype + quirks.
    - Compact, recent memory snippets for that world.
    - Current situation summary (location, current job, nearby bots).
  - Ensure this coexists with hard-coded lines: the LLM’s job is to generate *free-form* replies and interpretations; skills and warnings still use your existing messages unless asked otherwise.

**Phase 4 – Chat routing, command vs. conversation**

- Chat targeting:
  - Extend existing parsing so you can address:
    - Single bot: “Jake, …”, “… Jake?”.
    - All bots: “all bots …”, “everyone …”, “bots, …”.
  - Plug this into the orchestrator; it receives the resolved target set.
- Intent classification:
  - LLM gets the message + context and returns:
    - `intentType`: `command`, `small_talk`, `question`, `unknown`.
    - For `command`: a structured candidate like `skill=mining`, params, target bots, and an estimated confidence.
- Confirmation rules:
  - For high-impact skill commands (`collect_dirt`, `mining`, `stripmine`, depth runs, etc.) require confirmation:
    - Bot responds: “It sounds like you want me to stripmine 12 blocks east. Is that right?” with a short paraphrase.
    - On player “yes” / “no” / correction, the orchestrator finalizes the action.
  - For low-risk actions (follow, stop, defend toggle, give/take items) run them directly if confidence is high enough.
- Non-command chatter:
  - If `intentType` is not `command`, route to an LLM-generated reply that respects the bot’s personality and memory (or fall back to existing canned responses).

**Phase 5 – Command stringing & queuing**

- Action queue per bot:
  - Maintain a small queue structure attached to each bot (in shared state or a dedicated manager).
  - When the LLM interprets a multi-step instruction (“Jake, stripmine 10 blocks, then follow me, then guard the portal”), it:
    - Breaks it into discrete skill / config commands.
    - Asks for a single confirmation: “So: A) stripmine 10, B) follow, C) defend the portal—is that right?”
    - On “yes”, enqueues them in order.
- Execution integration:
  - Each queued item is translated into:
    - A `/bot skill …` invocation (through `SkillManager` / `modCommandRegistry`).
    - Or a direct config/utility call (`follow`, `stop`, defend toggles).
  - Bots pop and run the next queued item when the current task completes or aborts.

**Phase 6 – Social awareness & reactive comments**

- Event hooks:
  - Tap into existing places where you already know about:
    - Bot deaths (death handler).
    - Hazard pauses (lava, water, rare ore finds) – you already centralised these.
    - Skill completions / achievements (stripmine done, depth reached).
    - Bot spawn / despawn.
  - Each event:
    - Writes a short memory record into `event_log`.
    - Optionally enqueues a “comment opportunity” for nearby bots.
- Comment generation:
  - For each opportunity:
    - Pick 0 or 1 bots to speak (throttle: e.g., max one social comment every N seconds per bot).
    - Feed the event + persona into the LLM: “Bob just died in lava. You’re Jake; how would you react?”.
    - Post the line via the existing chat utilities.
- Throttling / “don’t overdo it”:
  - Global cooldown per bot for reactive comments.
  - Event type weights, so deaths/discoveries rank higher than minor annoyances.

**Phase 7 – Performance & safety**

- Asynchronous, off-thread calls:
  - All LLM calls run on a dedicated executor; orchestration code never blocks the main server thread.
  - Use strict timeouts (e.g., 300–800 ms) with fallbacks:
    - On timeout or error, skip the fancy behavior and fall back to minimal, existing commands or canned lines.
- Offloading model work:
  - Prefer a local sidecar or lightweight HTTP service (or your existing provider) that the mod talks to via a short RPC.
  - Keep in-game payloads small (compressed conversation state, recent memory only).
- Guardrails:
  - Keep a hard separation between:
    - “What the LLM *says*” (chat only).
    - “What the bots *do*” (only through validated, well-typed actions).
  - Never execute arbitrary LLM text—only structured, whitelisted action objects.

**Phase 8 – Integration, toggling, and non-regression**

- Gradual opt-in:
  - Start with: LLM off by default, skills unchanged; only enable new features after the toggle.
  - Add debug logging behind a config flag so we can see classification, proposed actions, and why we asked for confirmation.
- Compatibility with existing code:
  - Keep all existing skill codepaths untouched; the LLM layer only decides *when* to trigger them.
  - Ensure `/bot skill …` still works exactly as before when issued manually.
- Test passes:
  - Regression around:
    - Stripmine and depth mining hazards.
    - Resume behavior.
    - Inventory-full pause / continue.
  - New tests for:
    - Basic intent detection.
    - Per-world memory isolation (switch worlds, confirm memories don’t leak).
    - LLM toggle off: system behaves as today.
