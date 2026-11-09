<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Construction

[All Guide topics](Guide.md)

## Fortify Village

Builds a defensive wall with moat around a village using a convex hull of its structures.

- The bot computes a convex hull around village structures and builds along the edges.
- Full cross-section: wall + slab top + inner cliff face + spider overhang + 3-wide moat + exterior clearance.
- Moat is 3 blocks deep with cobblestone floor. Gatehouse has a walkable bridge across the moat.
- Towers at each hull vertex, walls along edges, and a gatehouse on the longest edge.
- Give the bot stone bricks, cobblestone, or slabs before starting.
- Village structures are detected and excluded. Walls route around buildings automatically.
- dry_run: preview layout with particles (orange=towers, blue=walls, gold=gate, dark blue=moat, purple=overhang, red=clear).
- status &lt;name&gt;: per-edge completion stats + particles highlighting missing blocks in red.
- resume / patch / drift / expand / merge / list / name: manage saved walls and schema updates.
- Tip: Use the Base Manager screen for wall status, ownership, and nearby village mapping.

**Command:** `/bot fortify <bot>`

**Where:** Actions &gt; Construction &gt; Fortify Village

## Map Village

Saves the nearby settlement as a shared remembered no-go zone without building a wall.

- Open the Base Manager and use the Villages section to map the local settlement.
- The saved village reuses the fortification convex hull and stores it by name.
- Mapped villages are global shared data for the world, not personal claims.
- Bots avoid mapped villages for mining, woodcutting, dirt collection, flower/mushroom/grass gathering, and passive-mob hunting.
- Village rows appear in the same manager list as bases and walls, but they are not valid home or go-to targets.

**Command:** UI-only flow

**Where:** Actions &gt; Bases &gt; Map Village
