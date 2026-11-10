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

In chat the fren says "Guarding this area (radius 6.0 blocks)." or "Patrolling this area (radius 6.0 blocks)." Look at it to see what it is doing: "is guarding", "is patrolling" or "is staying", and which key stops it.

## What each order does

| | Guard | Patrol | Stay |
|---|---|---|---|
| Where it stands | At the centre. After a fight it walks back once it is more than 1.5 blocks away. | Walks to random points inside the radius, pausing briefly at each. Walks back if it ends up outside the radius. | Where it was told to stay. After a fight it walks back once it is more than 1.5 blocks away. |
| Fighting | Yes | Yes, the same way as guard | Yes, the same way as guard |
| Picking up items | Items near it, within the radius | Items near it, within the radius | After a fight, and anything within 20 blocks once it has been idle a while |
| Avoiding holes | Won't step into a drop of 2 or more blocks unless what it is fighting is down there | The same | No |
| Getting unstuck | Yes, see below | Yes | No |

What the **radius** controls: how far patrol wanders, and how far guard and patrol look for items to pick up. It does **not** limit what they fight.

## What a fren fights

All three orders fight the same way:

- It notices hostile mobs it can see within about 10 blocks, whatever the radius.
- It fights a mob that is after **it**. With a bow, crossbow or trident it can use, it also shoots hostile mobs that aren't after it yet. With only melee weapons it leaves those alone.
- It doesn't help you directly. A zombie chasing **you** is ignored until the zombie turns on the fren (unless the fren has a ranged weapon). Keep a guard near where mobs come at it, or give it a bow and arrows.
- It helps other frens. When fighting alongside is on, it also fights mobs within 8 blocks of another fren that is within 12 blocks of it. `/bot fight_with_me on` or `off` sets this. Stopping a fren turns it back on.
- It defends your pets, horses, leashed animals, the animals at a base and villagers in a mapped village from mobs attacking them.
- It never attacks players, passive animals or mobs with a name tag.
- It goes for the most dangerous mob first: creepers, then witches and skeletons, then pillagers and ghasts, then zombies and spiders.

A guard has no leash while it fights. It chases what it is fighting and walks back to its post afterwards. Creepers can pull it further: it may back off or run up to 12 blocks away (24 for a charged creeper) and come back once the danger has passed.

### Gear

When a fight starts it puts on armour it carries, moves a shield to its off hand and gets its best weapon ready.

- It uses a crossbow, bow or trident when it has no melee weapon or the mob is 5 or more blocks away, as long as it has ammunition.
- It raises its shield against skeletons, pillagers and witches, against creepers, against more than one mob close by, and when its health is at half or lower.
- It keeps away from ghasts and shoots them if it can.

## Food and safety

- It eats from its own inventory when it gets hungry. With mobs around it only eats once its hunger is very low. If it is close to starving and there are no mobs, it looks for food in a chest.
- It doesn't run away from fights while guarding, patrolling or staying. Give it armour, a shield and food if it guards somewhere dangerous.
- A guard or patrol that is stuck for 3 seconds tries to climb back to the surface. It doesn't do this at night or in a thunderstorm unless it is starving, or when it is underground inside one of your bases. If it climbs out higher than its old centre, the place where it ends up becomes the new centre.

## How the orders end

| What happens | Result |
|---|---|
| You press the button again, or use `/bot stop` | It stops and stands idle. |
| You tell it to follow, or give it another of these orders | The new order replaces the old one. |
| It dies | It comes back idle. Give the order again. |
| The world restarts | The order isn't saved. Give it again. |
| Sunset | It keeps guarding unless **Sunset Return in Guard/Patrol** is on (see below). |

### Sunset

By default a guard or patrol stays at its post through the night. To have it go home at sunset like an idle fren:

- turn on **Sunset Return in Guard/Patrol** in its inventory under Skills, then Automation, or type `/bot auto_return_sunset_guard_patrol on <bot>`
- **Auto Home @ Sunset** has to be on as well

It then asks you first, unless Skip Permission is on, and goes home. See [Bases and home](Bases-and-Home.md).

It doesn't go back to guarding in the morning. Give the order again when you want it.

## Common surprises

| What you see | Why |
|---|---|
| It ignored a zombie attacking me | It fights mobs that are after it. Give it a bow and arrows, or keep it between you and the mobs. |
| It ran far from its post | It was chasing a mob or getting away from a creeper. It walks back afterwards. |
| Its guard spot moved | You changed the radius (which resets the centre to where it stood), or it climbed out of a hole to higher ground. |
| It stopped guarding overnight | Sunset Return in Guard/Patrol is on, or it died. |
| It isn't guarding after a restart | Orders aren't saved. Give it again. |
