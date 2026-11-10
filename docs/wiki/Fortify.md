# Fortifying a village

A fren can build a stone wall around a village, with a tower at each corner and a gatehouse. It saves the wall, so it can carry on later, repair it and grow it as the village grows. A ditch (the moat) is a separate step. This page explains what gets built, what it needs and how to look after a saved wall.

## Starting

Stand the fren near the village and open its inventory, then Actions, **Construction**. Under **Stronghold**:

| Section | Buttons |
|---|---|
| Build & Resume | **Fortify Village** starts a new wall. **Resume Wall** carries on with one. |
| Maintain | **Fortify Patch**, **Fortify Moat**, **Fortify Status**, **Fortify List** |
| Evolve Schema | **Drift Check**, **Expand Wall**, and **Wall Manager**, which opens the Bases screen |

These buttons use the saved wall closest to the fren. To work on a particular wall, open the Bases screen, select the wall and use its buttons there. **Fortify New** in the Bases screen starts a new wall too.

Operators and the host can type `/bot fortify` with the same subcommands (see [Commands](#commands)). Leave out the wall name and the fren uses the nearest one, saying "Auto-detected nearest wall:" and its name.

### Finding the village

The fren doesn't need a mapped village. It looks within 64 blocks for villagers, beds and village workstations, and for village buildings around them. If it finds none: "No village detected nearby." It looks for beds and workstations only from 5 blocks below to 10 above itself, so stand it at the village's level.

## What it builds

### The shape

It draws the tightest outline around everything it found and pushes it 12 blocks outwards. The wall runs straight from corner to corner of that outline. With too few points for an outline it builds a square 24 blocks out from the centre (48 across).

### The wall

- One block thick: a stone brick foundation at ground level, three blocks of stone brick above, and a slab on top. That is about three and a half blocks above the ground.
- It follows the ground column by column, so it steps up and down hills. It doesn't level the ground first.
- Water and tree trunks count as the ground, so a wall line over water starts at the water's surface.

### Towers

A small tower at each corner: a ring of stone brick 3 blocks across, one block taller than the wall, with slabs on top and an oak log post in the middle. Corners within 2 blocks of each other are built as one job.

### Gatehouse

One gate, in the middle of the longest side. The opening is about 2 blocks wide and 2 high, with a carved stone brick cap on each pillar and a lintel over the top. If the longest side is under 9 blocks, there is no gate at all.

### Gaps

The wall runs 12 blocks outside the village, so it rarely meets a building. It can still end up with gaps:

- blocks it couldn't reach in the time it gives each stretch
- a whole side skipped because more than 40% of it was taken by something other than the wall, such as a building (see [Looking after a wall](#looking-after-a-wall)). Wall blocks it placed earlier don't count.
- on a wall that was expanded or resumed, a column within 2 blocks of a village building, which it leaves empty rather than bending the wall

Run patch to fill them.

It doesn't place torches or lighting.

## Materials

The fren builds only from **its own inventory**. It doesn't take blocks from chests, craft them or dig for them. Give it the blocks before you start.

It uses the planned block when it has it and substitutes in this order when it doesn't:

| Part | Blocks it uses, in order |
|---|---|
| Wall and tower stone | stone bricks, cobblestone, stone, cobbled deepslate, andesite, dirt |
| Tower centre post | oak, spruce, birch or jungle log, cobblestone, dirt |
| Gate caps | chiseled stone bricks, stone bricks, cobblestone, dirt |
| Top slabs | stone brick slab, cobblestone slab, stone slab, cobblestone, dirt |

Oak and spruce planks and stone brick stairs count when it checks whether it has blocks, but it can't build the wall with them. If those are all it has left, it won't say it ran out. It keeps trying and ends with a partial report.

Before it starts it prints the plan, including a block count, and says how many building blocks it has. If it has fewer it warns "Will build as far as possible." The count is higher than what the wall really needs, because it includes the moat.

With no blocks at all it won't start: "No building blocks in inventory. Give me stone bricks, cobblestone, or similar." If it runs out halfway, it saves its progress and tells you to give it more blocks and resume.

### Hard spots

When it can't find a place to stand to reach a block, it builds a scaffold pillar up to 8 high and removes it afterwards. With **Force Place** on (`/bot config forceplace on`, or the toggle in the control screen) it places a block it can't otherwise reach. Force place only uses the planned block, cobblestone or stone.

When it breaks through to reach a spot, it won't break doors, beds, lecterns, fences, gates, walls, glass panes, trapdoors, chests and other blocks with contents, or blocks that look like part of a village building. Blocks it breaks to get through its own wall are put back later. Digging the moat follows the same rules with one difference: it does break chests and other containers on the moat line. It leaves doors, beds, fences, gates, walls, glass panes, trapdoors, lecterns and blocks that look like part of a village building, so the ditch can have gaps there.

## The order of work

1. **Corner towers**, one at a time. Each gets about 45 seconds of tries. A tower counts as done when 95% of it is in place.
2. **The walls**, nearest side first, in 8-block stretches, from the bottom up.
3. It takes its scaffolding down.
4. It checks the whole wall and reports.

The report counts the wall, tower and gatehouse blocks this stage places, not the moat. At 85% or more it says "Fortification complete" and marks the wall complete; below that it says "Partial completion" and suggests patch. The wall stage stops after 30 minutes and saves ("Time budget reached. Progress saved as").

The wall is saved as soon as building starts, under a name like `wall_120_-30`.

## The moat

The moat is not part of the normal build. Run **Fortify Moat** or `/bot fortify moat` once the wall is up.

It digs a ditch starting three blocks out from the wall, leaving two blocks of ground between them. The ditch is two blocks wide: 3 deep on the inner side and 2 deep on the outer side. It also clears anything up to 4 blocks above the outer side, such as trees and leaves. The moat stays dry.

Once the ditch is fully dug, it lines it: a cobblestone floor, a stone brick lining on the wall side and a ledge against spiders. It works one side at a time, nearest side first. If any of the ditch is still undug, it leaves the lining for the next run. It needs building blocks for this; if it runs out, it says how many it still needs.

The ditch isn't dug in front of the gate, so the ground there is the crossing. Move chests and anything else you want to keep off the moat line first.

## Looking after a wall

| Button / command | What it does |
|---|---|
| **Resume Wall**, `resume [name]` | Carries on building, including sides it had partly built. It skips the towers, so patch afterwards to finish those. |
| **Fortify Patch**, `patch [name]` | Checks every side and puts back missing blocks. It also removes stray scaffold. This is the reliable way to finish a wall. |
| `patch <name> auto`, or Auto Patch in the Bases screen | Patches again and again until nothing is missing or a pass repairs nothing. The wall name is needed when typing it. |
| **Fortify Status**, `status [name]` | Shows what is built by part and by side, and marks missing blocks in red. The overall percentage mixes in moat cells, so read the wall, tower and gatehouse lines. |
| **Drift Check**, `drift [name]` | Checks whether the village has grown past the wall: stable, minor or major. |
| **Expand Wall**, `expand [name]` | Adds the village's new area to the outline, keeps what is already built, and starts building. It only grows the wall, never shrinks it. |
| `merge [name]` | The same as expand. Use it when a new wall would overlap an old one. |
| **Fortify List**, `list` | Lists the saved walls and how far along each is. From the button, only operators and the host. |
| `name <old> <new>` | Renames a wall. |
| `dry_run` | Shows the planned wall without building it. |
| `report_cavities` | Shows the holes it decided to ignore this session. |

Status, dry run and expand show the layout as a burst of coloured particles: blue walls (gold on the gate side), a gold gatehouse, dark blue and purple for the moat plan, red for missing blocks and green corners. It shows once, so run the command again to see it again.

### Who can work on a wall

A new wall belongs to the fren's owner.

- **Resume, patch and moat**: the owner, an operator, the host, or a player the owner gave access to in the Bases screen.
- **Expand**: the owner, an operator or the host only.
- **Status and drift**: anyone who can see the wall in the Bases screen.

A wall with no owner has to be claimed in the Bases screen first.

### Overlapping walls

A new wall that overlaps a saved one by more than 30% is refused, with a hint to merge them instead.

## While it builds

- Stopping the fren saves the progress: "Aborted. Progress saved as" and the name. Resume carries on.
- Sunset doesn't interrupt a fortify, and it doesn't come back to you when it is done.
- It doesn't fight back specially while it builds. Give it armour if the area is dangerous.
- Run one fren on one wall at a time. Two frens fortifying at once can get in each other's way.

## Inside the wall

Once a wall is saved, the area inside it and 8 blocks around it are protected: frens won't dig, cut trees, hunt or gather there. `/bot config fort_buffer <0-64>` changes the 8 blocks. A value of 0 goes back to 8 after a restart. See [Protected zones](Protected-Zones.md).

## Commands

```
/bot fortify                    build a new wall
/bot fortify dry_run            show the plan only
/bot fortify resume [name]
/bot fortify patch [name]
/bot fortify patch <name> auto
/bot fortify status [name]
/bot fortify moat [name]
/bot fortify drift [name]
/bot fortify expand [name]
/bot fortify merge [name]
/bot fortify list
/bot fortify name <old> <new>
/bot fortify report_cavities
```

Typed commands need an operator or the host. Anything else after `fortify` starts a normal build.

## Common surprises

| What you see | Why |
|---|---|
| There is no moat | The moat is a separate step. Run Fortify Moat. |
| The wall has gaps | Some blocks couldn't be reached or a side was skipped. Run patch. |
| A tower is unfinished after resume | Resume skips towers. Run patch. |
| The moat has no stone floor or lining | It lines the moat only once the whole ditch is dug, and only with building blocks in its inventory. Run Fortify Moat again. |
| "No village detected nearby." | Move the fren into the village, at the same height as the houses. |
| It stopped and said it was out of blocks | Give it more and resume. |
| It won't use my planks | Planks can't build the wall. Give it stone, cobblestone or dirt. |
| The wall steps up and down | It follows the ground and doesn't level it. |
