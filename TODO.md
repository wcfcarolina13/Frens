# AI-Player TODO

Pending work only. Completed items and rationale live in `changelog.md`.

## P0 — Critical
- [ ] **Persist bot stats on respawn**: Health/XP/hunger restored with the same alias, logged on save/load, happens before spawn completes.
- [ ] **Upward stairs (ascent) simplification**: Walk-and-jump staircase that keeps controller-facing direction, mines only 3 blocks above each tread, climbs exactly one block per step, aborts with a clear message if Y does not increase.
- [ ] **Escape when spawned in walls**: Suffocation escape checks all four corners from head to feet, uses tool-based mining only, and throttles alerts to avoid spam.
- [ ] **Task lifecycle reset & busy-loop prevention**: `/bot stop`/respawn instantly clear the active ticket, release movement overrides, and prevent “Bot is busy” spam in idle RL loops.
- [ ] **Drop collection in tricky terrain**: Drop sweeps handle shallow water by wading/bridging, hop safely into 1-block holes to vacuum items, and keep cooldown/backoff logic so navigation is not spammed.
- [ ] **COME/Follow precipice handling**: If walking without teleport hits a large drop/cave, plan a bridge or alternate heading, or edge-scale ascent/descent instead of stalling.

## P1 — High
- [ ] **Protected zones persistence**: JSON-backed zones (position, radius, creator, timestamp, dimension) saved on create/update and loaded on server start.
- [ ] **Bot config UI refactor**: Single-bot view with alias dropdown, grouped/scrollable settings, and save/cancel affecting only the selected bot.
- [ ] **Bot identity separation**: Per-alias inventory/hunger/sleep/XP isolation across sessions (Jake vs Bob) with validation.
- [ ] **Job resume prompts on death/leave**: Pause current job; on rejoin/respawn, ask whether to continue and resume when confirmed.
- [ ] **Per-bot chat addressing & broadcasts**: Direct questions route to the named bot; `allbots` commands fan out once without cross-talk.

## P2 — Medium

### Inventory & Items
- [ ] Bot item inventory view (chest-like interface)
- [ ] Equipped section visible (armor, main hand, offhand)
- [ ] Backpack grid (27-slot)
- [ ] Hotbar row (9-slot)
- [ ] Shift-click, double-click, drag support
- [ ] Quick-action buttons (Sort, Equip Best, Take All, Give All)

### Navigation & Movement
- [ ] Swimming parity (surface and underwater)
- [ ] Boat support (enter, exit, navigate)
- [ ] Portal following (Nether, End)
- [ ] Cross-realm teleport command
- [ ] Water-aware pickup (wade/bridge)
- [ ] Edge/hole pickup (hop down safely)

### Combat & Safety
- [ ] Creeper evasion (sprint away when unarmed)
- [ ] Protected build zones (no-grief areas)
- [ ] Follow/defend modes
- [ ] Fight with teammates

### Crafting & Building
- [ ] Place and use crafting table, furnace, chest
- [ ] Craft common items (bed, tools, weapons, armor)
- [ ] Build walls (specified materials, dimensions)
- [ ] Simple 2-person house
- [ ] Block placement primitives

### Farming & Survival
- [ ] Till soil, plant seeds, harvest, replant
- [ ] Create infinite water source
- [ ] Animal husbandry (shear, collect meat, pen animals)
- [ ] Furnace usage with various fuels
- [ ] Hunger persistence and smart eating
- [ ] Sleep integration (bed usage, warnings)

### Mining & Resource Gathering
- [ ] Tree chopping (safe climbing, late drop collection)
- [ ] Strip mining with safety offset (sand, gravel, lava)
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
