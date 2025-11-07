# Gemini Report 3

## High Priority Tasks from Last Session

- [x] **Realistic Mining:** Bot should use appropriate tools (shovel for dirt, pickaxe for stone) and dig from the surface instead of teleporting into caves.
- [x] **Fix "collect dirt" command:** The command "hey Jake, collect 10 dirt" is not working.
- [x] **Fix skill resumption:** Bot does not resume its job after dying and being told "yes".
- [x] **Refine `/bot skill mine`:**
    - [x] `/bot skill mine <block_family>` should collect all blocks in that family.
    - [x] `/bot skill mine <specific_block>` should only collect that block, digging through other blocks.
    - [x] Add a "fails" parameter to give up after a certain number of attempts.
- [x] **Improve Mining Awareness:**
    - [x] Bot should know the most likely Y-levels for different ores.
    - [ ] Bot should pause and ask before collecting rare resources.
    - [ ] Bot should pause and ask if it finds a cave, precipice, mob spawner, or dungeon.
- [ ] **Fix Resource Inquiry:** "Jake, what resources do you have?" should report from the bot's inventory, not perform a web search.
- [ ] **Improve Bot Personality:** Personalities feel stilted; review and alter personality prompts.

## General To-Do List

### Core Mechanics
- [x] Persistent inventory for bots (across sessions, not deaths).
- [ ] Bot aliases with unique inventories, hunger, sleep, and experience levels.
- [ ] Bots should be able to safely drop into holes to collect items even when teleportation is not enabled.
- [ ] Individual and group chat with bots.
- [ ] Bots should resume jobs after respawning or rejoining the server.
- [ ] Command individual or all bots at once.
- [ ] Simplify persistence for individual bots.
- [ ] Faster creeper evasion, especially without weapons.

### Navigation
- [ ] **Underground/Caves:**
    - [ ] If a targeted item is too far, the bot should say so and drop the job.
- [ ] **Underwater:**
    - [ ] Bot should swim like a player.
- [ ] **Boats:**
    - [ ] Bot should be able to use boats without teleporting.
- [ ] **Nether & End:**
    - [ ] Bot should follow players through portals.
    - [ ] A command to teleport bots between dimensions.

### Crafting & Building
- [ ] **Crafting:**
    - [ ] Crafting table, furnace, and chest.
    - [ ] Bed, shears, bucket, weapons, tools, torches, sticks, planks, and armor.
- [ ] **Placing Blocks:**
    - [ ] Place various blocks.
    - [ ] Build walls with specified parameters.
    - [ ] Place crafting tables/furnaces and chests next to each other.
- [ ] **Simple Structures:**
    - [ ] Build a simple 2-person house.

### Farming & Animals
- [ ] **Farming:**
    - [ ] Till soil, collect seeds, plant, harvest, and replant.
    - [ ] Build a simple farm.
    - [ ] Collect water and create an infinite water source.
- [ ] **Animal Husbandry:**
    - [ ] Shear sheep.
    - [ ] Collect meat from wild animals.
    - [ ] Use leads to capture animals.
    - [ ] Build a fenced area.
- [ ] **Horseback Riding:**
    - [ ] Tame, capture, and mount a horse.
    - [ ] Craft a saddle.

### Resource Gathering
- [ ] **Woodcutting:**
    - [ ] Chop wood.
    - [ ] Safely climb trees to collect all wood.
    - [ ] Return for late drops.
- [ ] **Mining:**
    - [ ] Strip mine safely (avoiding sand, gravel, lava).
    - [ ] Mine until finding specific items, with a proceed/report option.
    - [ ] Stop and report caves, precipices, special structures.
    - [ ] Stop and alert the player if it encounters water.

### Survival
- [ ] **Cooking:**
    - [ ] Use a furnace to cook and smelt.
    - [ ] Functional and persistent hunger.
    - [ ] Eat when hungry, starting with the worst food.
    - [ ] Chat messages for hunger levels.
- [ ] **Sleeping:**
    - [ ] Bots must sleep for the player to skip the night.
    - [ ] Comment on sunset.
    - [ ] Mention lack of sleep before phantom spawns.

### Combat & Formations
- [ ] **Following:**
    - [ ] Bots can follow and defend each other.
- [ ] **PVP:**
    - [ ] Spar with bots.
- [ ] **Army Formations:**
    - [ ] Simple line and grid formations.

### Miscellaneous
- [ ] **Debug Toggle:**
    - [ ] Toggle to reduce terminal spam.