# Protected zones and no-go areas

Frens stay out of some areas so they don't wreck your builds, your village or your neighbour's base. There are two separate rules, and they cover different jobs:

- **Protected zones** stop frens from **breaking blocks**.
- **No-go areas** stop frens from **gathering**: cutting trees, digging dirt, picking plants and hunting passive mobs.

The short version is the Guide card [Protected Zones](Guide-Utilities.md#protected-zones).

## Protected zones

A protected zone is a box. Two things make one:

- **Every base** makes a zone automatically: a cube reaching the base's radius (40 blocks by default) in every direction from its centre. See [Bases and home](Bases-and-Home.md).
- **Zones you draw.** An operator or the host gets the Zone Wand with `/bot zone wand` or New Zone in the Base Manager. Right-click one corner, right-click the opposite corner, then press `=` to name the zone. Cyan particles show the outline. Right-click again to start over. `/bot zone protect <radius> [name]` makes a box (radius 1 to 100) around the block you're looking at.

Zones are saved with the world. Anyone can see their outlines with Show in the Base Manager.

### Who a zone stops

A zone belongs to whoever made it (for a base, the base's owner). What it stops depends on the job:

| Job | Inside a zone |
|---|---|
| Digging jobs: Stripmine, Collect Dirt, Ascent, Descent, Build Farm | Stops at **every** zone except public ones, including your own. The fren says why. Press Resume to let it carry on for up to 60 seconds, or until the job ends. |
| Breaking or placing blocks to get around (paths, pillars, escaping a hole) | Allowed in your own zones and in zones whose owner permitted you. Refused in everyone else's. |
| Feeding animals | Skips animals standing in any zone except public ones. |

A zone has an access mode, which its owner, an operator or the host can change:

- `/bot zone mode <zone> owner_only` (the default): only the owner's frens, plus anyone permitted
- `/bot zone mode <zone> allowlist`: the same in practice, named for a zone built around a permit list
- `/bot zone mode <zone> public`: no fren is stopped
- `/bot zone permit <zone> <player>` and `/bot zone revoke <zone> <player>` add or remove a player whose frens are let in

`/bot zone list` lists the zones, and `/bot zone remove <zone>` deletes one. Like every typed `/bot` command, these need an operator or the world host. Operators can also rename and delete zones in the Base Manager.

## No-go areas

Gathering jobs check a wider set of places. A fren won't cut a tree, dig dirt, pick flowers, mushrooms or grass, collect leaf litter or hunt a passive mob inside:

- any **base**, including your own and the Spawn base (measured as a ball of the base's radius around its centre)
- any **protected zone** that isn't public, including your own
- a **fortified wall** and 8 blocks around it (`/bot config fort_buffer <0-64>` changes the margin)
- a **mapped village** (see [Map Village](Guide-Construction.md#map-village))
- the area around a **bee nest or beehive**, so it leaves the tree alone

If those are the only trees or blocks nearby, the fren walks out further to find others.

Woodcutting treats the **Spawn** base specially: an operator can let frens cut trees there with the setting under Admin, then World Settings.

## Common surprises

| What you see | Why |
|---|---|
| My fren won't strip-mine under my own base | Mining stops at every zone, yours too. Press Resume when it asks, or mine outside the base. |
| It won't cut the trees around my house | Bases are no-go areas for gathering, even for your own frens. |
| It walked far away to find trees | Every nearby tree was in a base, a zone, a village, near a wall or by a bee nest. |
