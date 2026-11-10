# Bases and home

A fren's "home" decides where it sleeps, where it goes when you send it home, and which areas it treats as yours. This page explains bases, beds, the preferred home and the home compass, and how a fren picks between them.

The short version is the Guide card [Home & Bases Explained](Guide-Basics.md#home--bases-explained).

Typed `/bot` commands work for operators and for the host of a singleplayer or LAN world. Other players use the buttons: the Base Manager and the fren's inventory screen follow the same ownership rules.

## The four things that count as home

| Thing | What it is | How you set it |
|---|---|---|
| **Base** | A named spot with a protected area around it (40 blocks each way by default). Every fren in the world can use any base as home. | Base Manager (inventory, Bases button), or `/bot base set <name>` where you stand |
| **Last bed** | The last bed this fren slept in. | Let the fren sleep in a bed (for example, type `zzz` in chat at night) |
| **Preferred home** | One base that this fren calls home. | Base Manager: select a base and press Set Home (you and the fren must be in the same dimension) |
| **Home compass** | One lodestone compass in this fren's inventory, used by `/bot compass travel` when you don't name a compass. | `/bot compass home <bot> <compass name>`. The Base Manager marks it [Home] on a white row. |

The preferred home and the home compass are separate. A fren can have both, and they don't affect each other.

## Bases

- Bases only exist in the Overworld.
- A new world gets a server-owned base called **Spawn** at the world spawn. If an operator deletes it, it stays deleted.
- A base protects a cube around its centre, 40 blocks in every direction by default. A base's owner can change that with Set Radius in the Base Manager. Operators can also use `/bot config base_radius <name> <1-128>`.
- Frens owned by other players won't break blocks in your base. Woodcutting skips trees inside bases, the Spawn area, protected zones and mapped villages, even your own.
- A player can't save a base whose area would overlap another player's base, unless the two players are allied. Operators can.
- You can rename and delete your own bases in the Base Manager. Operators can change any base, and also have `/bot base list`, `/bot base rename <old> <new>` and `/bot base remove <name>`.
- Players see their own bases, their allies' bases and server-owned ones in the Base Manager. Operators see all of them.
- Operators can set who owns a base: `/bot base owner <name> <player|server|none>`. Put names with spaces in quotes.

## Where a fren goes at sunset

At sunset, a fren heads home if it is idle (not following you) or on an until-sunset job. A guard or patrol does too when that option is on. A job with a count keeps running.

If you are online, it asks first with an on-screen prompt and leaves when you accept. Turn on **Skip Permission** to make it go without asking. If you are offline, it goes straight away.

It picks the **closest** of these three, measured from where it stands:

1. its last bed, if the bed is still there
2. its preferred home
3. the nearest base, which may be Spawn

If it has none of them, it goes to the world spawn.

This means the preferred home **doesn't always win** at sunset. If Spawn or another base is closer, the fren goes there. To make it go where you want:

- keep it working near its preferred home, or
- give it a bed near where it works, or
- delete the bases you don't want it to use, or
- have an operator turn on **prefer last bed**, so its last bed always wins: `/bot auto_return_sunset_prefer_last_bed on <bot>` (there is no button for it).

If it carries any lodestone compass and home is at least 16 blocks away, it fast-travels instead of walking (see [Fast travel](Fast-Travel.md)). At a bed, base or preferred home it sleeps once it is night.

## Where it goes the rest of the time

`/bot return <bot>`, idle hobbies and some jobs use a different rule: the **preferred home wins** if one is set. Without one, the fren goes to whichever is closer, its last bed or the nearest base. With none of these, it goes to the world spawn.

`/bot return` only works within one dimension: a fren in the Nether can't return home that way.

So if you want a fren to always come back to one place, set a preferred home and give it a bed there.

## Sunset options

Each of these is per fren. Most are buttons in the fren's inventory screen, under Actions, then Automation. Operators can also type the command with `on`, `off` or `toggle` and the fren's name.

| Button | Command | What it does |
|---|---|---|
| Auto Home @ Sunset | `/bot auto_return_sunset` | Whether the fren goes home at sunset at all |
| Sunset Return in Guard/Patrol | `/bot auto_return_sunset_guard_patrol` | Whether a guarding or patrolling fren goes home at sunset |
| Skip Permission | `/bot auto_return_skip_permission` | Go home without asking you first |
| Self-Sufficiency | `/bot auto_return_self_sufficient` | If it gets stuck on the way home, it goes somewhere else it knows instead: another base, its bed, a lodestone, you or another fren, or your chest |
| Tactical Shelter | `/bot tactical_shelter` | If it gets stuck on the way home, it may shelter where it is |
| (none) | `/bot auto_return_sunset_prefer_last_bed` | Its last bed always wins over bases |

## Going back to work in the morning

A fren that went home from an until-sunset job can go back to work at sunrise. That needs a lodestone compass. See [Work loops](Work-Loops.md).
