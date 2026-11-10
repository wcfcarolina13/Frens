<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Construction

[All Guide topics](Guide.md)

## Fortify Village

Builds a stone wall with corner towers and a gate around a village.

- Finds villagers, beds and workstations within 64 blocks, draws an outline around them and builds 12 blocks outside it.
- The wall is one block thick and about three and a half blocks high, with a tower at each corner and a gate on the longest side.
- Builds only from its own inventory: stone bricks, cobblestone, stone, cobbled deepslate, andesite or dirt. Planks can't be used.
- The moat is a separate step (Fortify Moat). It digs the ditch, then lines it with a cobblestone floor, stone brick lining and a spider ledge once the whole ditch is dug.
- The finish report counts the wall, towers and gatehouse only. Below 85% it says Partial, and patch fills the gaps.
- Resume skips towers and partly built sides. Patch finishes them and fixes damage.
- dry_run: preview the layout with particles. status: per-side progress, missing blocks shown in red.
- drift / expand / merge / list / name: manage saved walls. Expand only grows a wall.
- Tip: Use the Bases screen (Wall Manager) for a wall's buttons, ownership and access.

**Command:** `/bot fortify <bot>`

**Where:** Actions &gt; Construction &gt; Stronghold &gt; Fortify Village

## Map Village

Saves the nearby settlement as a shared remembered no-go zone without building a wall.

- Open the Base Manager and use the Villages section to map the local settlement.
- The saved village reuses the fortification convex hull and stores it by name.
- Mapped villages are global shared data for the world, not personal claims.
- Bots avoid mapped villages for mining, woodcutting, dirt collection, flower/mushroom/grass gathering, and passive-mob hunting.
- Village rows appear in the same manager list as bases and walls, but they are not valid home or go-to targets.

**Command:** UI-only flow

**Where:** Actions &gt; Bases &gt; Map Village
