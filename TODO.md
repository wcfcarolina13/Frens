# AI-Player TODO

Pending work only. Completed items and rationale live in `changelog.md`.

Canonical in-game verification checklist: `docs/testing/IN_GAME_AUDIT_MASTER.md`.

## Active Split
- [ ] **Reliability split in progress**: Track A (follow/come/rescue hardening) is being stabilized for cherry-pick to `main`; Track B (combat/threat/healing/ridesync tuning) is intentionally deferred on WIP.

## P0 — Critical
- [ ] **Verify launch after permission predicate fix**: Ensure bots can start without the LeveledPermissionPredicate OWNERS crash on 1.21.11.
- [ ] **Persist bot stats on respawn (verification)**: Save/load path exists; verify health/XP/hunger restore timing is visible before spawn flow completes in live runtime.

## P1 — High
- [ ] **Bot config UI refactor**: Single-bot view with alias dropdown, grouped/scrollable settings, and save/cancel affecting only the selected bot.
- [ ] **Bot identity separation (verification)**: Alias canonicalization + consistency guards are implemented (`/bot identity_check <alias>` + config/persistence normalization); complete runtime verification across restart/respawn with multi-alias scenarios.
- [ ] **Job resume prompts on death/leave (verification)**: Death prompt flow exists; verify leave/rejoin prompt and resume behavior in-game.
- [ ] **Per-bot chat addressing & broadcasts (verification)**: Named/allbots routing exists; verify no duplicate replies or cross-talk in runtime scenarios.

## P2 — Medium

### Post-Refactor Follow-Up (From Testing Notes)
- [ ] **Guard verification**: Run in-game tests for `/bot guard` (basic start/stop, radius handling, and interaction with other tasks).

### Follow / Come
- [ ] **Follow stability (verification)**: Core planner/backoff/waypoint recovery is implemented; complete runtime verification across dimensions/terrain with `follow_check` asserts.
- [ ] **Come survival movement (verification)**: `/bot come` routes through fixed-goal follow pathing; verify no snap/direct movement regressions in live terrain tests.
- [ ] **Come reroute scheduling (verification)**: Reroute attempt/cooldown state and planner backoff markers are implemented; verify cave/corner/vertical escapes reroute before recovery skills.
- [ ] **Come tool crafting (verification)**: `ToolProvisionService` is called by `/bot come` and recovery paths; verify torches/shovels/pickaxes are provisioned in-world when recipes/materials permit.
- [ ] **Deterministic follow/come assertions (verification)**: Run `docs/reliability/FOLLOW_COME_ASSERT_RUNBOOK.md` and record pass/fail outcomes for `follow_check` tokens.

### Shelter (Redo Needed)
- [ ] **ShelterSkill refactor**: Split `ShelterSkill.java` into smaller hovel/burrow builder classes and tighten shared primitives/logging.
- [ ] **ScaffoldService extraction**: Centralize pillaring/scaffolding + ladder placement so shelter/woodcut/mining can reuse the same “climb to work height” logic.
- [ ] **LeafClearService extraction**: Centralize “clear leaf blocks along path/headroom” (from follow) so other skills can reuse it when navigation fails due to leaf litter.
- [ ] **Shelter resource acquisition flow**: Auto-collect/craft required materials by default; allow `ask|wait|manual` to pause and require `/bot resume <alias>` (or `proceed`) before gathering.
- [ ] **Shelter options parameter**: Investigate what `options` currently controls for hovel/burrow; document and/or refactor.
- [ ] **Shelter chest workflow**: While building, withdraw/deposit resources and place new chests to manage inventory; place new chests inside planned interior when possible.
- [ ] **Burrow “descend-stripmine-descend”**: Restore intended method; compare with proven descent behavior from `come`.
### Mining (Verification)

### Commands / UX
- [ ] **Command pruning review**: Evaluate whether `look_player` and `direction reset` are still needed; deprecate/remove if redundant.
- [ ] **Elder Scrolls-style dialogue menu** Conversation topics, commands, quests
- [ ] **Elder Scrolls-style Journal** Conversation topics, quests, important information with simple filter search

### Inventory & Items
- [x] Bot item inventory view (chest-like interface)
- [x] Equipped section visible (armor, main hand, offhand)
- [x] Backpack grid (27-slot)
- [x] Hotbar row (9-slot)
- [ ] Shift-click, double-click, drag support
- [ ] Quick-action buttons (Sort, Equip Best, Take All, Give All)
- [ ] Bundle packing verification: drop_sweep crafts/uses bundles when inventory is truly full.

### Navigation & Movement
- [ ] Swimming parity (surface and underwater)
- [ ] Verify swimming behavior matches survival movement (no “snap”/teleport, proper buoyancy, safe ascent)
- [ ] Boat support (enter, exit, navigate)
- [x] Boat follow paddling verification: ensure ride-sync uses paddle input (no forced boat position moves).
- [x] Boat: verify placement from inventory + boat-break retrieval after commander destroys their boat.
- [ ] Test fishing from a boat; define behavior (stay seated vs dismount to shore)
- [ ] Portal following (Nether, End)
- [ ] Cross-realm teleport command
- [ ] Water-aware pickup (wade/bridge)
- [ ] Edge/hole pickup (hop down safely)
- [ ] Add shelves and any kind of container to no-break list (bot cannot mine out of it if it is stuck. Use a snap reposition instead)

### Fishing (Verification)
- [ ] Verify leaf-block clearing when fishing requires navigating far from shoreline
- [ ] Verify fishing works from higher vertical positions (cliffs/piers) without mis-casting
- [x] Handle being asked to fish while swimming (refuse, relocate to shore, or swim-to-shore logic)
- [ ] In-game check: trigger `/bot fish` while bot is swimming/submerged and verify it relocates to dry shore before first cast (or returns a clear no-shore failure).

### Combat & Safety
- [ ] Creeper evasion (sprint away when unarmed)
- [x] Enderman gaze safety: avoid looking at endermen unless already hostile (don’t aggro passive endermen)
- [ ] In-game check: stand near passive endermen and confirm bot does not face/aggro them; then provoke one and confirm bot can still target it once hostile.
- [ ] Protected build zones (no-grief areas)
- [x] Follow/defend modes
- [ ] Fight with teammates
- [ ] Ride sync verification: commander mount/dismount mirroring across horse-like/boats/minecarts/pigs/striders with saddles/controls.
- [ ] Ride sync leashed persistence: leashed (not mounted) horse remains tethered after disconnect/rejoin.
- [x] **Water-bucket clutch on deadly falls (low priority)**: If bot has a water bucket and detects a lethal fall, attempt to place water under itself just before impact to avoid death.
- [ ] In-game check: drop bot from lethal height with/without a water bucket (Overworld), verify clutch attempts near impact and verify no attempts in ultrawarm dimensions.

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
- [x] Hunger persistence and smart eating
- [ ] **Farm underground recovery**: Handle cases where bot is underground and can’t pillar upward due to overhead dirt; improve escape logic and add test coverage.
- [ ] **Farm chest workflow**: Ensure the farm skill can place/store/use chests proactively for inventory/resource management.
- [ ] **Farm irrigation leak patching**: If irrigation isn’t fillable, detect leakage cause and patch the leak (enclosure improvements).
- [ ] Hobby verification: flower picking avoids bases/protected zones; feed-animals hobby triggers on low health; hobby hunt cooks/eats and hangs out.

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
- [ ] Command queuing (multi-step instructions)
- [ ] Voiced banter variants for follow-adventure lines (creeper joke, "run" warning).

## LLM Integration (Future)

- [ ] Phase 1+: Core architecture, toggles, identity & memory, routing, performance, social awareness, integration & testing.
