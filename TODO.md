# AI-Player TODO

Pending work only. Completed items and rationale live in `changelog.md`.

## P0 — Critical
- [ ] **Persist bot stats on respawn**: Health/XP/hunger restored with the same alias, logged on save/load, happens before spawn completes.
- [x] **Upward stairs (ascent) simplification**: Walk-and-jump staircase that keeps controller-facing direction, mines only 3 blocks above each tread, climbs exactly one block per step, aborts with a clear message if Y does not increase.
- [x] **Escape when spawned in walls**: Suffocation escape checks all four corners from head to feet, uses tool-based mining only, and throttles alerts to avoid spam.
- [x] **Task lifecycle reset & busy-loop prevention**: `/bot stop`/respawn instantly clear the active ticket, release movement overrides, and prevent “Bot is busy” spam in idle RL loops.
- [x] **Drop collection in tricky terrain**: Drop sweeps handle shallow water by wading/bridging, hop safely into 1-block holes to vacuum items, and keep cooldown/backoff logic so navigation is not spammed.
- [x] **COME/Follow precipice handling**: If walking without teleport hits a large drop/cave, plan a bridge or alternate heading, or edge-scale ascent/descent instead of stalling.

## P1 — High
- [ ] **Protected zones persistence**: JSON-backed zones (position, radius, creator, timestamp, dimension) saved on create/update and loaded on server start.
- [ ] **Bot config UI refactor**: Single-bot view with alias dropdown, grouped/scrollable settings, and save/cancel affecting only the selected bot.
- [ ] **Bot identity separation**: Per-alias inventory/hunger/sleep/XP isolation across sessions (Jake vs Bob) with validation.
- [ ] **Job resume prompts on death/leave**: Pause current job; on rejoin/respawn, ask whether to continue and resume when confirmed.
- [ ] **Per-bot chat addressing & broadcasts**: Direct questions route to the named bot; `allbots` commands fan out once without cross-talk.
- [x] **New skill: fishing**: Cast/reel loop with safe footing, inventory checks (rod, bait if needed), avoid water teleport, auto-store catch when inventory nears full.

## P2 — Medium

### Post-Refactor Follow-Up (From Testing Notes)
- [ ] **Guard verification**: Run in-game tests for `/bot guard` (basic start/stop, radius handling, and interaction with other tasks).
- [x] **Come verification**: Confirm `/bot come` reaches in one run (no “direct walk blocked” stall) and does not grief player-placed doors.
- [x] **Combat verification**: Confirm close-range combat switches from bow/crossbow to a melee weapon when available.

### Follow / Come
- [ ] **Follow stability**: Confirm follow continues after other tasks; verify advanced pathfinding stays reliable across dimensions/terrain.
- [ ] **Come survival movement**: Remove nudge/snap behavior; prefer strict survival-style walking unless explicitly configured.
- [ ] **Come better rerouting before mining tasks**: Try tighter-corner/vertical reroutes more confidently before suggesting descent/ascent/stripmine.
- [ ] **Come tool crafting**: Auto-craft torches/shovels/pickaxes from available resources when needed during movement/tasks.

### Shelter (Redo Needed)
- [ ] **Shelter hovel perimeter-walk verification**: Confirm ring-walk wall placement finishes all reachable blocks, preserves the doorway gap, and avoids stalling.
- [ ] **Hovel roof serpentine walk**: Validate pillar-to-roof + serpentine walk reliably fills roof cells and tears down support blocks safely.
- [ ] **Hovel exterior patch reach**: Ensure the bot exits the footprint to reach exterior targets and re-enters via the doorway (no wall phasing).
- [ ] **Hovel leveling sweep check**: Re-verify base-level hole fill + obstruction clearing math after the refactor (foot layer should stay clear).
- [ ] **ShelterSkill refactor**: Split `ShelterSkill.java` into smaller hovel/burrow builder classes and tighten shared primitives/logging.
- [ ] **ScaffoldService extraction**: Centralize pillaring/scaffolding + ladder placement so shelter/woodcut/mining can reuse the same “climb to work height” logic.
- [ ] **LeafClearService extraction**: Centralize “clear leaf blocks along path/headroom” (from follow) so other skills can reuse it when navigation fails due to leaf litter.
- [ ] **Hovel top-walk build**: Prefer climbing (ladder) to roof-edge, sneak-walk the perimeter, and place roof/walls by layers from above for fewer reach/path failures.
- [ ] **Shelter resource acquisition flow**: Auto-collect/craft required materials by default; allow `ask|wait|manual` to pause and require `/bot resume <alias>` (or `proceed`) before gathering.
- [ ] **Shelter options parameter**: Investigate what `options` currently controls for hovel/burrow; document and/or refactor.
- [ ] **Shelter chest workflow**: While building, withdraw/deposit resources and place new chests to manage inventory; place new chests inside planned interior when possible.
- [ ] **Burrow “descend-stripmine-descend”**: Restore intended method; compare with proven descent behavior from `come`.
### Mining (Verification)
- [x] **Descent reliability**: Ensure slow pickaxes don’t cause early pauses (no premature mining-cancel), burial-rescue doesn’t fight the stairwell, and lava hazards always announce clearly.
- [x] **Descent headroom reach**: Verify descent no longer pauses on out-of-reach stairwell headroom blocks; confirm it continues to step down safely.

### Commands / UX
- [x] **Open command admin mode**: Make “open” distance-independent for admins.
- [ ] **Command pruning review**: Evaluate whether `look_player` and `direction reset` are still needed; deprecate/remove if redundant.
- [ ] **Elder Scrolls-style dialogue menu** Conversation topics, commands, quests
- [ ] **Elder Scrolls-style Journal** Conversation topics, quests, important information with simple filter search

### Inventory & Items
- [ ] Bot item inventory view (chest-like interface)
- [ ] Equipped section visible (armor, main hand, offhand)
- [ ] Backpack grid (27-slot)
- [ ] Hotbar row (9-slot)
- [ ] Shift-click, double-click, drag support
- [ ] Quick-action buttons (Sort, Equip Best, Take All, Give All)

### Navigation & Movement
- [ ] Swimming parity (surface and underwater)
- [ ] Verify swimming behavior matches survival movement (no “snap”/teleport, proper buoyancy, safe ascent)
- [ ] Boat support (enter, exit, navigate)
- [ ] Test fishing from a boat; define behavior (stay seated vs dismount to shore)
- [ ] Portal following (Nether, End)
- [ ] Cross-realm teleport command
- [ ] Water-aware pickup (wade/bridge)
- [ ] Edge/hole pickup (hop down safely)
- [ ] Add shelves and any kind of container to no-break list (bot cannot mine out of it if it is stuck. Use a snap reposition instead)

### Fishing (Verification)
- [ ] Verify leaf-block clearing when fishing requires navigating far from shoreline
- [ ] Verify fishing works from higher vertical positions (cliffs/piers) without mis-casting
- [ ] Handle being asked to fish while swimming (refuse, relocate to shore, or swim-to-shore logic)

### Combat & Safety
- [ ] Creeper evasion (sprint away when unarmed)
- [ ] Enderman gaze safety: avoid looking at endermen unless already hostile (don’t aggro passive endermen)
- [ ] Protected build zones (no-grief areas)
- [ ] Follow/defend modes
- [ ] Fight with teammates
- [ ] **Water-bucket clutch on deadly falls (low priority)**: If bot has a water bucket and detects a lethal fall, attempt to place water under itself just before impact to avoid death.

### Crafting & Building
- [ ] Place and use crafting table, furnace, chest
- [ ] Craft more common items (armor, torches, etc.)
- [ ] Build walls (specified materials, dimensions)
- [ ] Simple 2-person house
- [ ] Block placement primitives

### Farming & Survival
- [ ] Till soil, plant seeds, harvest, replant
- [ ] Create infinite water source
- [ ] Animal husbandry (shear, collect meat, pen animals)
- [ ] Furnace usage with various fuels
- [ ] Hunger persistence and smart eating
- [x] Sleep integration: `/bot sleep <alias|all|default>` finds/places/crafts a bed and sleeps.
- [ ] **Farm underground recovery**: Handle cases where bot is underground and can’t pillar upward due to overhead dirt; improve escape logic and add test coverage.
- [ ] **Farm chest workflow**: Ensure the farm skill can place/store/use chests proactively for inventory/resource management.
- [ ] **Farm irrigation leak patching**: If irrigation isn’t fillable, detect leakage cause and patch the leak (enclosure improvements).

### Mining & Resource Gathering
- [ ] Tree chopping (safe climbing, late drop collection)
- [ ] Strip mining with safety offset (sand, gravel, lava)
- [ ] Cave/structure detection and reporting
- [ ] Water encounter handling

### Fishing (Enhancements)
- [ ] **Fishing reach**: Extend “near water” search/acceptance radius somewhat.
- [ ] **Water location memory**: Store/recall known water locations to guide fishing spot selection when not currently near water.

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

## Crafting Roadmap
- [ ] Crafting helper: detect required inputs in bot inventory and report missing items.
- [ ] Crafting table craft: craft when inputs exist; announce success or missing items in chat.
- [ ] Placement: place crafted table near commander safely.
- [ ] Recipe awareness: if commander lacks recipe (non-creative), bot refuses and explains. 

### Quality of Life
- [ ] Debug toggle (reduce terminal spam)
- [ ] Test kit command (`/equip` for testing loadout)
- [ ] Command queuing (multi-step instructions)

## LLM Integration (Future)

- [ ] Phase 1+: Core architecture, toggles, identity & memory, routing, performance, social awareness, integration & testing.
