# Changelog & History

Historical record and reasoning. `TODO.md` is the source of truth for what’s next.

## 2025-11-19
- Hardened suffocation recovery: multiple iterations to detect head/feet blockage before damage ticks, throttle alerts, and mine with the correct tool rather than instant breaks. Spawn-in-block checks now run shortly after registration.
- Upward stairs (ascent) refinements: walk-and-jump algorithm with headroom increases, issuer-facing direction lock, button-based direction overrides, and explicit `lockDirection` parameter for consistent stair orientation. Direction state resets per command to avoid stale facings.
- Safety changes: blocked destructive helpers (`digOut`, `breakBlockAt`) to enforce tool-based mining; escape routines schedule work on tick instead of blocking server threads. Added hazard scanning during ascent and tightened drop cleanup to reuse trusted sweep logic.
- Docs: added button-orientation tip to the guide and logged ascent headroom tweaks and obstruction damage gating.

## 2025-11-20
- Follow rework: bots now chase with WASD-style input, timeboxed path steps (no tick stalls), sensible teleport catch-up, and chill when adjacent; hill walking and vertical catch-up improved.

## Unreleased
- Woodcut: tighter unreachable-log cap (4 skips), wider drop sweep sized to the job footprint, and confirmed sapling replant stays on; aborts still trigger a sweep.
- Woodcut (standalone): defaults to a small target count and stops at sunset with a clear chat message (internal woodcut calls are unaffected).
- Chest use: bot auto-deposits wood to nearby chests/barrels; documented `/bot store deposit|withdraw <amount|all> <item> [bot]`.
- Shelter: added `/bot shelter hovel <alias?>` to build a quick dirt/cobble hovel (roofed, torches, fills gaps, gathers dirt if short).
- Farming: secured irrigation basins on uneven terrain (fills edges/underblocks, cleans stray flow), repair pass now levels plots to farm Y before re-till/plant, and leaves are broken with shears/harmless items (no axe wear).
- Wool skill: peaceful shearing that crafts/equips shears if needed, detects pens vs. wild range (fence-aware search), collects drops, and auto-deposits bulk blocks to nearby chests to keep ≥5 free slots.
- Wool: `/bot stop` now interrupts movement/drop-sweeps immediately; short-range moves avoid full pathfinding to reduce hitching.
- Movement: reduced per-step INFO spam (most movement tracing is now DEBUG) to keep integrated-server stutter down during frequent short moves.
- Movement: improved “stuck under canopy” recovery by prioritizing clearing low headroom leaves (shears/harmless only), speeding up escape from dense forests.
- Fishing: added a `/bot fish` skill that can craft a rod (3 sticks + 2 string), move to the shore, cast, wait for bites, and reel in fish while tracking successes.
- Crafting: added bed crafting (selects a craftable color based on available matching wool) and enforced crafting-table requirements for 3x3 recipes; will place/craft a table, remember/seek the last known table, and can pull basic inputs from nearby chests.
- Movement: bots can open/close doors while pathing (opens when blocked, closes after passing with a small delay).
- Follow: reduced snap-teleport frequency; bots walk much farther before teleport catch-up is even considered.
- Crafting: `/bot craft` now runs asynchronously (like skills) so moving to tables/chests no longer blocks the server tick thread; crafting-table searches avoid chunk-loading scans.
- Crash fix: move bot item use/placement/hotbar changes and sheep shearing interactions onto the server thread to avoid `LegacyRandomSource` multi-thread crashes (1.21.10).
- Follow/Movement: follow movement planning now enables sprint on mid-range walks, and door opening can trigger during close-pursuit/direct-walk stalls (not just during path-segment walking).
- Come: when teleport is disabled, `/bot come` now uses follow-walk replanning instead of a one-shot direct-path attempt (reduces “could not reach you: direct: walk blocked” false failures).
- Rescue: spawn-in-block checks no longer mine doors/trapdoors; they attempt interaction and nudge out to avoid griefing player builds.
- Combat: close-range attacks prefer melee weapons over bows/crossbows when available.
- Patrol: patrol target selection now uses a bounded cooldown (less “standing still” variance when nothing else is happening).
- Commands: `/bot defend on|off` and `/bot defend nearby on|off` added as shorthands for the existing defend syntax.
- Come: `/bot come` now walks to a fixed snapshot of the commander’s position at command time (doesn’t chase a moving target) and won’t “complete early” when directly below/above the destination.
- Come: when stuck trying to reach a fixed goal (e.g., tunnel below the target), the bot will auto-attempt short `collect_dirt ascent|descent` or `stripmine` recovery steps, then resume coming to the destination.
- Come: improved “stuck” detection to use best-distance tracking (robust to jitter) and trigger vertical recovery sooner when the goal is above/below without line-of-sight.
- Commands: added `/bot regroup` as a clearer alias for `/bot come` (walk to the commander’s last location rather than live-follow).
- Come: removed the old “blocked, run `/bot resume` to dig” staging path (now superseded by automatic recovery skills).
- Refactor: removed obsolete Spartan mode; confined/no-escape handling now relies on environment checks plus the stuck/escape routines.
- Refactor: extracted follow debug-log throttling to `FollowDebugService` (no behavior change).
- Refactor: extracted follow/come state maps to `src/main/java/net/shasankp000/GameAI/services/FollowStateService.java` (behavior unchanged; BotEventHandler delegates state storage/reset).
- Refactor: extracted follow waypoint planning to `src/main/java/net/shasankp000/GameAI/services/FollowPlannerService.java` (behavior unchanged; BotEventHandler delegates async planning and waypoint application checks).
- Refactor: extracted follow movement helpers to `src/main/java/net/shasankp000/GameAI/services/FollowMovementService.java` (behavior unchanged; BotEventHandler delegates movement primitives).
- Refactor: extracted stuck tracking + enclosure snapshot to `src/main/java/net/shasankp000/GameAI/services/BotStuckService.java` (now per-bot; no change for single-bot play).
- Refactor: extracted RL action execution to `src/main/java/net/shasankp000/GameAI/services/BotRLActionService.java` (mechanical move; behavior unchanged).
- Refactor: extracted RL persistence throttling to `src/main/java/net/shasankp000/GameAI/services/BotRLPersistenceThrottleService.java` (no behavior change).
- Commands: `/bot skill` now accepts bot targets anywhere in the args (e.g., `ascend Jake 5`) and supports `ascend`/`descend` synonyms (defaulting to 5 blocks when no number is given).
- UX: `/bot inventory` summary now includes bot stats (health/food/XP) so XP/level persistence is visible.
- Follow: follow no longer runs blocking movement/path loops on the server thread; it now sprints when >2 blocks away and uses a wolf-style teleport catch-up only when far/stuck (with cooldown).
- Storage: prevent “remote” chest deposits/withdrawals through doors/walls by requiring survival-like reach + line-of-sight checks before moving items.
- Safety: rescue-from-burial/suffocation escape no longer mines doors; it will attempt to open them instead (prevents bots breaking enclosure doors).
- Movement: doors opened during movement now reliably close after the bot passes through (retry-based “close behind you” behavior).
- Crafting: crafting-table station approach now requires true interactability (reach + line-of-sight) and will open a blocking door when needed.
- Follow: follow now proactively raycasts for a blocking door within ~5 blocks and opens it (not just “stagnant” detection).
- Doors: door interact checks now probe door edges (doors are thin, center-raycasts often miss), and stuck escape will open doors instead of breaking them.
- Doors: door closing now retries longer and will close once the bot is safely away even if the inferred travel direction was wrong; close attempts emit `Door debug:` logs.
- Storage: `/bot store deposit|withdraw` now walks to the chest (opening doors en route) and only transfers once the bot has true reach + line-of-sight.
- Movement: added doorway traversal assist when a door is open but the bot is stuck on the threshold; follow and movement pursuit will “step through” instead of giving up.
- UX: bots now explicitly say they can’t open iron doors without redstone.
- Storage: `/bot store deposit|withdraw` now runs movement asynchronously (so ticks can advance) and includes a “step through doorway” assist when exiting enclosures.
- Pathfinding: wooden doors are treated as passable for planning (bot opens them on approach); iron doors remain blocked unless already open via redstone.
- Movement: `walkDirect` and `walkSegment` now attempt doorway step-through + sidestep recovery before giving up (improves exiting door enclosures for storage/stations).
- Movement: when the goal is “around the corner” and the bot is stuck (common in door enclosures), movement will pick a nearby wooden door that leads closer to the goal and treat it as a sub-goal (approach → open → step through).
- Follow: added non-blocking door sub-goal traversal (approach → open → step through) so follow doesn’t stall at doorways or freeze the server tick with blocking nudges.
- Follow: added bounded, async follow path planning (snapshot-on-thread + plan-off-thread) to navigate multi-door “around the corner” obstacle courses without oscillating back through the same door.
- Follow: path planner now falls back to “exit nearest wooden door” when no path-to-target exists in the bounded snapshot (enclosure escape even if it initially moves away from the commander); added INFO logs for plan start/success.
- Follow: path planning now triggers on stagnation even if `canSee()` is true (fences/doors can allow LoS while still blocking movement), so the bot will choose door/waypoints instead of pushing into the corner.
- Follow: stagnation now also tracks “no block-position change” (distance-to-target can jitter while pinned in a corner), so the reroute-to-door planner reliably fires from enclosure corners.
- Follow: follow now requests an initial bounded path plan immediately on `/bot follow` start (async), so it can exit enclosures even if the commander is not standing at the doorway.
- Follow: “close enough, chill” now requires an unobstructed short raycast to the commander; prevents getting stuck staring through glass/fences when the shortest route requires backing up to a door.
- Follow: enclosure-door sub-goal selection no longer requires immediate distance improvement (around-the-corner routes often start by moving away); replans after crossing doors.
- Follow: when close-but-blocked (fence/glass/wall), follow now proactively selects an exit door sub-goal (throttled) instead of pushing into the barrier until “stagnant” triggers.
- Follow: added throttled INFO logs for follow path planning early-return reasons (cooldown/inflight/same-target/world), so wiring issues show up clearly in `latest.log`.
- Doors/Follow: door “escape/sub-goal” selection no longer requires survival reach (it now checks only unobstructed line-of-sight), so bots can pick their enclosure door even when starting far away in a corner.
- Follow: “blocked route” detection now uses a conservative, throttled collision-probe (not just raycasts) so fence/glass enclosures trigger door escape routing instead of the bot pushing into the corner.
- Follow: follow planning now uses a two-phase approach: goal-inclusive snapshot first, then a bot-centered “escape nearest door” fallback when the correct first move is away from the commander (dweller/stalker-style repathing).
- Follow: improved enclosure/building door navigation by (1) scoring escape-door choices against the commander position (even when the goal is outside the bounded window), (2) increasing the bounded planning window size, (3) preventing “step-through” commits when the door did not actually open, and (4) adding a doorway recovery that uses local sidesteps/backsteps (better for fence/hinge corners), aborting door plans that can’t be unstuck, avoiding “step 1 block beyond the door” overshoots (prevents jamming between adjacent doors), plus throttled `Follow decision:` INFO logs for debugging.
- Stuck rescue: fixed a cooldown bug where “Bot is stuck!” logging updated the same timestamp used to throttle escape nudges, which could prevent movement escape from ever triggering (notably when wedged inside doors).
- Follow: reduced “door magnet” behavior by only considering door subgoals when blocked or lacking LoS, clearing stale waypoints when the commander is close/visible, and adding a short per-door cooldown when a door plan aborts or fails to open (prevents endlessly re-picking the same door).
- Follow: door traversal now prefers standing directly in front/behind doors (based on door facing), triggers “double back” recovery sooner, and no longer marks a door as “crossed” while the bot is still standing inside the door block (fixes a common stuck/oscillation loop).
- Follow: reverted a regression where door traversal could oscillate by dynamically re-picking front/back tiles each tick; door plans are stable again, and doorway recovery now prioritizes an explicit 2–3 block retreat before any micro-nudges (more reliable “double back and try again”).
- Follow/Training: paused the RL training loop while the bot is in a player-commanded mode (FOLLOW/skills/etc) to prevent training actions from canceling follow movement, and added INFO logging to show who invoked `/bot stop` when follow is unexpectedly canceled.
- Training/Performance: throttled Q-table + epsilon persistence (reduces server hitching from frequent disk writes during RL steps).
- Training/Performance: added `/bot rl on|off` hard toggle to disable the RL loop entirely (prevents background “thinking” during normal play/follow).
- Follow: cancel active door subgoals once the commander is close/visible and directly reachable, reducing “linger by the door” after the commander moves away.
- Follow: reduce long-range “door distraction” by dropping stale waypoints when the commander moves far away and suppressing door-corner subgoals when the commander is far outside the bounded planning window.
- Follow: after the bot closes a door behind itself, temporarily avoid re-opening that same door while the commander is far away (reduces “loop back to the nearest door” behavior).
- Follow: reduced post-door “door fixation” by gating adjacent/door-ray interactions to close-range/stuck situations and avoiding doors near the last-crossed doorway when the commander is far away.
- Follow: avoid door blocks as follow waypoints (expand to doorway-adjacent approach/step tiles) and prevent burial-rescue from fighting doorway traversal when the bot is inside door blocks without suffocation damage.
- Follow: when the commander is far away and the bot is not enclosed, drop stale follow waypoints/door subtasks so it doesn’t “orbit” a doorway instead of pursuing the commander.
- Follow/Debug: added throttled INFO `Follow status:` logs (distance, LoS, blocked, waypoints, door plan) to diagnose long-range “door fixation” issues from `latest.log`.
- Follow: added a long-range override that clears door plans/waypoints when the commander is visible far outside the structure so the bot heads straight for you instead of circling the doors.
- Follow: trigger sprinting toward waypoints whenever the commander is far (so sprint doesn’t stall near the doorway), and skip “door-plan” obsession when an already-open door sits between the bot and you.
- Follow: detect when the bot or commander are inside sealed rooms (closed doors are the only exits), keep/retain the door plan, and log that reason instead of dropping it when the route momentarily clears.
- Follow: fixed follow/door timer overflow caused by `Long.MIN_VALUE` timestamp sentinels (cooldowns/avoidance could become permanently “stuck”), restoring replanning and door escape behavior.
- Follow: personal-space stop now applies even when following waypoints, preventing the bot from constantly colliding with/jumping onto the commander.
- Follow: follow spacing now uses horizontal distance (prevents “push into the commander” when there’s small Y offsets like steps/slabs).
- Follow: doorway traversal plan now chooses valid standable doorway-adjacent cells and applies a small lateral nudge if stuck on the threshold after the door opens.
- Follow: reduced door-vs-target “thrash” by making blocked-route detection prefer raycast clarity over wide collision probes at close range, preventing unnecessary door escape behavior when the commander is directly reachable.
- Follow: when blocked and an adjacent door is opened, follow now commits to the existing door subtask (approach → open → step-through) instead of switching back to direct steering.
- Safety: bots refuse to mine/break protected player blocks (chests/barrels/shulkers/beds) and will nudge away instead if embedded.
- Safety: burial/suffocation/spawn escape no longer mines fences/walls/gates (treat as protected; nudge instead), preventing griefing of player-built structures.
- Safety: generic block-breaking logic refuses to break fences/walls/gates to avoid destroying player enclosures.
- Cook: `/bot cook` now runs movement asynchronously (no server tick freeze) and requires true furnace interactability (reach + line-of-sight), opening/closing doors as needed.
- Performance: removed per-step `stdout` spam from `LookController.faceBlock/faceEntity`.

## 2025-11-18
- Persistency and safety: inventory save timing fixed; drop sweeps stop breaking blocks and only collect items; bots break out when spawned in walls; upward stairs start in the controller’s facing direction (partial fix).
- Task queue notes captured for stats persistence and the simplified upward stair spec.

## 2025-11-17 Checkpoint
- Mining polish: work-direction persistence across pause/resume, hazard pauses with `/bot resume`, torch placement on walls (level ≥7), and `/bot reset_direction` to clear stored facings.
- Survival & UX: hunger auto-eat thresholds with `/bot heal`, inventory full messaging, drop sweep retries, suffocation checks after tasks, and `inventory` chat summaries.
- Controls: config UI adds Bot Controls tab (auto-spawn, teleportDuringSkills, inventoryFullPause, per-bot/world LLM toggles) with owner display and scrollable rows; bots auto-spawn at last saved position.
- LLM bridge: natural-language job routing to real skills with confirmation, per-bot personas/memory, action queueing, status responses, and `/bot config llm …` toggles.

## 2025-10-31 (Gemini report recap)
- Added composite tools (`mineBlock`, `chopWood`, `shovelDirt`, `cultivateLand`) with FunctionCaller orchestration and state tracking; verified builds.
- Early RL/hold-tracking tweaks and Mineflayer/RAG exploration notes logged for future LLM integration work.

## Legacy Releases (pre-2025)
- 1.0.x line: 1.20.6 compatibility, server-side training mode support, Q-table format change, risk-taking mechanism, expanded triggers (lava/cliffs/sculk), and broad command set (`use-key`, `detectDangerZone`, inventory queries, armor equip/remove, etc.). See archived release notes in `archive/legacy_changelogs.md`.

## 2025-12-08
- UI: Moved Specific URLs to dedicated button + popup; ensured popup min 800x600.
- Textbox: Added placeholder guidance.
- Specific URLs flow: Added --urls support, timestamped outputs saved to 'Specific Video Lists/'.
- Cleanup: Removed empty markdown placeholders in audio_briefing project (kept API keys out of git).

## [Unreleased] - 2025-12-16
### Added
- **Fishing Skill Upgrade**:
    - Added `until_sunset` parameter to fish until nightfall.
    - Added auto-chest handling: automatically finds or crafts/places a chest when inventory is full and deposits items.
    - Added movement safety: bot now stops moving after catching to prevent running into water; relies on rod mechanics for item collection.
    - Improved default behavior to handle infinite fishing or specific counts more intuitively.
    - Added `depositAll` method to `ChestStoreService`.

### Fixed
- **Fishing Skill**:
    - Fixed issue where the bot would deposit its fishing rod into the chest, preventing it from continuing.
    - Updated default behavior: if no count is specified, the bot fishes until sunset (or stopped).
    - Improved `ChestStoreService` to support item exclusion during deposits.

### Improved
- **Fishing Skill Upgrade (Part 2)**:
    - **Drop Sweeping**: Bot now performs item collection sweeps every 3 minutes and at the end of the session to catch floating items.
    - **Positioning**: Improved logic to move closer to the shoreline edge before casting to avoid hitting the ground.
    - **Bad Throw Detection**: Automatically detects if the bobber lands on dry land, retracts, and adjusts position.
    - **Cliff Casting**: Expanded vertical search range for fishing spots to better support fishing from ledges.

### Fixed
- **Fishing Skill Navigation**:
    - Replaced simple nudging with robust pathfinding for approaching fishing spots, allowing the bot to navigate around obstacles.
    - Added logic to automatically clear obstructing leaves when navigating to the water, ensuring the bot doesn't get stuck by trees.

### Fixed
- **Bot Respawn & Navigation**:
    - Fixed a bug where bots would zombie-resume 'follow' mode after respawning even if ordered to stop.
    - Bots now correctly reset to IDLE state and enable 'Assist Allies' defense mode upon respawn.
    - Improved water physics: bots now swim properly (using the swimming pose) instead of bobbing unnaturally on the surface.

### Fixed
- **Bot Respawn & Navigation**:
    - Fixed a bug where bots would zombie-resume 'follow' mode after respawning even if ordered to stop.
    - Bots now correctly reset to IDLE state and enable 'Assist Allies' defense mode upon respawn.
    - Improved water physics: bots now swim properly (using the swimming pose) instead of bobbing unnaturally on the surface.
- **Fishing Skill Navigation**:
    - Replaced simple nudging with robust pathfinding for approaching fishing spots, allowing the bot to navigate around obstacles.
    - Added logic to automatically clear obstructing leaves when navigating to the water, ensuring the bot doesn't get stuck by trees.
