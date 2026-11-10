# Guard, patrol and stay

These orders keep a fren in one place instead of following you. Guard holds a spot, patrol wanders around one, and stay waits where it is. This page explains what each order does, what a fren will and won't fight, and how the orders end.

## Giving the orders

Open the fren's inventory, then Actions, **Orders & Travel**, and press **Guard** or **Patrol**. The fren takes the spot where it stands as the centre. Pressing the button again stops it.

Next to each button, **+** and **-** set the radius, from 3 to 32 blocks (6 by default). You can also type a number. Changing the radius while the order is running sends the order again, so the fren takes the place where it stands at that moment as the new centre.

The buttons work for the fren's owner, operators and the host. Operators and the host can also type:

| Command | What it does |
|---|---|
| `/bot guard [bot] [radius]` | Guard the spot where the fren stands |
| `/bot patrol [bot] [radius]` | Patrol around the spot where the fren stands |
| `/bot stay [bot]` | Wait where it is. There is no button for this. |

In chat the fren says "Guarding this area (radius 6.0 blocks)." or "Patrolling this area (radius 6.0 blocks)." With Gameplay Tips on, look at it to see what it is doing ("is guarding", "is patrolling" or "is staying") and which key stops it.

## What each order does

| | Guard | Patrol | Stay |
|---|---|---|---|
| Where it stands | At the centre. When it is more than 1.5 blocks away, it walks back. | Walks to random points inside the radius, pausing briefly at each. Walks back if it ends up outside the radius. | Where it was told to stay. After a fight it walks back once it is more than 1.5 blocks away. After picking up items while idle, it stays where it ended up. |
| Fighting | Yes | Yes, the same way as guard | Yes, the same way as guard |
| Picking up items | Items within the radius of where it stands | The same | Anything within 20 blocks once it has been idle a while |
| Avoiding holes | Won't go 2 or more blocks below the level of its post unless what it is fighting is down there | Won't step down 2 or more blocks unless what it is fighting is down there | No extra rule. Like any fren it avoids drops of 3 or more blocks. |
| Getting unstuck | Yes, see below | Yes | No |

After any fight, all three also collect drops and arrows up to 16 blocks away, whatever the radius.

What the **radius** controls: how far patrol wanders, and how far guard and patrol look for items when there has been no fight. It does **not** limit what they fight.

## What a fren fights

All three orders fight the same way.

- It notices hostile mobs it can see within about 10 blocks, whatever the radius.
- It fights a mob that is after **it**. With a bow, crossbow or trident it can use, it also attacks hostile mobs that aren't after it yet. With only melee weapons it leaves those alone.
- It doesn't help you directly. A zombie chasing **you** is ignored until the zombie turns on the fren, unless the fren has a ranged weapon. Give a guard a bow and arrows, or keep it between you and the mobs.
- It can help other frens. With fighting alongside on, it also notices mobs within 8 blocks of another fren that is within 12 blocks of it. The same rule applies: with only melee weapons it fights those mobs only once they turn on it. Fighting alongside starts off for a new fren and after a world restart. `/bot fight_with_me on` or `off` sets it. Stopping a fren, or the fren dying, turns it on.
- With a bow, crossbow or trident it also defends, from mobs within 16 blocks:
  - your pets and horses
  - animals on your lead
  - name-tagged animals
  - animals near a hay bale at its home base
  - villagers in a mapped village it is standing in

  It only does this when it has noticed a hostile mob itself and its health is above 30%. With only melee weapons it doesn't.
- It doesn't attack players or passive animals. It leaves mobs with a name tag alone unless **Attack Named Mobs** is on.
- It picks its target by danger and distance, and closer mobs count for more. At the same distance the order is creepers, then witches and skeletons, then pillagers and ghasts, then zombies and spiders.

A guard has no leash while it fights. It chases what it is fighting and walks back to its post afterwards. Creepers can pull it further:

- It moves away from a creeper that comes within 6 blocks (12 for a charged one) and keeps going until it is out of that range.
- If the creeper starts to hiss, it backs off to 9 blocks (17 for a charged one).
- It comes back once the danger has passed.

### Gear

When a fight starts, it puts on armour it carries, moves a shield to its off hand and gets its best weapon ready.

- It uses a crossbow, bow or trident when it has no melee weapon or the mob is 5 or more blocks away, as long as it has ammunition.
- When its target is within 4.5 blocks, it raises its shield against skeletons, pillagers and creepers. It also raises it when it is fighting more than one mob, and when its health is at half or lower.
- It doesn't walk up to ghasts. It shoots them if it can, and otherwise holds its shield up.

## Food and safety

- It eats from its own inventory when it gets hungry. With mobs around, it only eats once its hunger is very low. If it is close to starving and there are no mobs, it looks for food in a chest.
- It doesn't run away from fights while guarding, patrolling or staying. Give it armour, a shield and food if it guards somewhere dangerous.
- A guard or patrol that is stuck below the surface for about 12 seconds tries to climb back up. If that fails, it waits 30 seconds before trying again.
  - It doesn't try at night or in a thunderstorm, unless its hunger is at 3 or lower.
  - It doesn't try underground inside a saved base.
  - If it climbs out higher than its old centre, the place where it ends up becomes the new centre.

## Jobs while guarding

Starting a job doesn't cancel guard or patrol. The fren keeps heading back to its post, so a job away from the post works poorly. Stop the order first.

## How the orders end

| What happens | Result |
|---|---|
| You press the button again, or use `/bot stop` | It stops and stands idle. |
| You tell it to follow, or give it another of these orders | The new order replaces the old one. Follow is refused while a job is running. |
| It dies | It comes back idle. Give the order again. |
| The world restarts | The order isn't saved. Give it again. |
| Sunset | It keeps guarding unless Sunset Return in Guard/Patrol is on, or it is doing an open-ended job (see below). |

### Sunset

By default a guard or patrol stays at its post through the night. To have it go home at sunset like an idle fren:

- turn on **Sunset Return in Guard/Patrol** in its inventory under Actions, then Automation, or type `/bot auto_return_sunset_guard_patrol on <bot>`
- **Auto Home @ Sunset** has to be on as well

It then asks you first, unless Skip Permission is on, and goes home. This only happens in the Overworld. See [Bases and home](Bases-and-Home.md).

If Auto Home @ Sunset is on and the fren is doing an open-ended job, it goes home at sunset even when Sunset Return in Guard/Patrol is off.

It doesn't go back to guarding in the morning. Give the order again when you want it.

## Common surprises

| What you see | Why |
|---|---|
| It ignored a zombie attacking me | It fights mobs that are after it. Give it a bow and arrows, or keep it between you and the mobs. |
| It ran far from its post | It was chasing a mob, getting away from a creeper or picking up drops after a fight. It walks back afterwards. |
| Its guard spot moved | You changed the radius (which resets the centre to where it stood), or it climbed out of a hole to higher ground. |
| It stopped guarding overnight | Sunset Return in Guard/Patrol is on, it was doing an open-ended job at sunset, or it died. |
| It isn't guarding after a restart | Orders aren't saved. Give it again. |
