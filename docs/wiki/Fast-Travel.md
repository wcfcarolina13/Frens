# Fast travel

Fast travel moves a fren across long distances without walking. The fren leaves, and after a delay it appears at the destination. How long it takes, and whether it can go at all, depends on what the fren carries, what you carry, and where it is.

The Guide cards [Fast-Travel Gates](Guide-Basics.md#fast-travel-gates) and [Fast Travel & Navigation Tiers](Guide-Settings.md#fast-travel--navigation-tiers) are the short versions. This page explains the whole system.

## When a fren fast-travels

There is no fast-travel setting. A fren fast-travels on these occasions, and walks the rest of the time:

| Occasion | What starts it |
|---|---|
| Going home at sunset | It carries any lodestone compass and home is at least 16 blocks away. See [Work loops](Work-Loops.md). |
| Going back to work at sunrise | It stopped an until-sunset job within 128 blocks of a lodestone it has a compass for. |
| Lodestone travel | You run `/bot compass travel <bot>` or `/bot compass travel <bot> <compass name>`. |
| Storage Go and Collect | You press Go or Collect on a chest in the Storage screen. |
| Retrieve Drops | You send it to fetch the items from a death. |
| Remote Guidance spell | You cast it from the Spells tab. Both pearls are used up. |

Chorus Recall is different: it is an instant teleport with no delay, paid for with pearls and chorus fruit.

## Gates: what lets it travel

A fren needs a **gate** to fast-travel. A gate is an item or a place that tells it the way. The departure message in chat names the gate it used. A few trips skip this check: Retrieve Drops, the sunset trip home (which only happens when the fren carries a lodestone compass anyway), and the recovery trip when a fren gets stuck on its way home.

### Full speed

Any one of these is enough, on the surface or underground:

- the fren holds a **lodestone compass** that is bound to a lodestone
- the fren or you hold an **Eye of Ender**
- the fren or you hold a **Wizard's Tome**
- the fren or you stand within 6 blocks of an **enchanting table**
- the fren and you **both** hold an **Ender Pearl**

None of these items are used up.

### Slower gates on the surface

Without a full-speed gate, the fren needs at least one of these:

- **Map and compass**: it carries a compass and a map that shows the destination. The destination has to be on an explored part of one of its maps.
- **Smoke signal**: the destination is a base with a lit campfire (or soul campfire) on a hay bale within 8 blocks of the base centre, and the fren is within 5 times the base's radius of it.

One of these makes the trip take 3 times as long. Both, or one plus a **spyglass** in the fren's inventory, make it 2 times as long. Both plus a spyglass is full speed.

### Slower gates underground

A fren counts as underground when it can't see the sky and stands more than 4 blocks below the surface. The rules are stricter there:

- a map and compass plus a **torch or lantern**: 2 times as long (the map doesn't need to show the destination)
- a lodestone compass whose lodestone is gone: 2 times as long
- a smoke signal at the destination base, with the fren within 2 times the base's radius: 3 times as long

With none of these, it refuses and says why.

## How long it takes

The base time is about 1.5 seconds for every 16 blocks. A trip to another dimension adds 30 seconds. The slower gates multiply that. Every trip takes at least 7 seconds and at most 5 minutes.

For example, a trip of 640 blocks takes about 60 seconds at full speed, and about 3 minutes with only a map and compass.

The sunset trip home is the exception: it always takes 3 times the base time, whatever the fren carries.

## What it costs

**Food.** A trip costs 1 hunger point for every 20 blocks, and the fren has to have at least 6 points left when it arrives. Food in its inventory counts, and it takes food out of bundles and shulker boxes in its inventory if it needs to. If it doesn't have enough, it refuses and tells you roughly how many cooked steaks it needs. A 640-block trip costs 32 points, so it needs 38 in all, counting its own hunger bar. Spells don't cost food.

**Rest.** After a trip, a fren rests for 3 minutes before it can fast-travel again. If you send it during that time, it says how long it is resting and goes by itself when the rest is over. Retrieve Drops skips the rest.

## Other limits

- A fren in combat can't fast-travel.
- A fren riding an animal takes it along when the destination has room. For a trip to another dimension, or a cramped destination, it ties the animal to a fence and goes alone. It refuses only when it can't do that. A boat or minecart goes into its inventory.
- Lodestone compasses stop working when their lodestone is broken. `/bot compass list <bot>` shows which ones still work.

## Picking which lodestone

A fren can carry several lodestone compasses. Rename them on an anvil so you can tell them apart.

- `/bot compass travel <bot> <name>` goes to the compass with that name.
- `/bot compass travel <bot>` with no name goes to the **home compass** if one is set, and otherwise to the nearest working lodestone in the same dimension.
- `/bot compass home <bot> <name>` sets the home compass. The Base Manager marks it with [Home] on a white row. See [Home & Bases Explained](Guide-Basics.md#home--bases-explained).
- The sunrise trip back to work doesn't use the home compass. It picks the lodestone nearest to where the fren stopped working.

## When it won't go

| What chat says | What to do |
|---|---|
| needs a Map + Compass (with the destination explored) or a smoke signal | Give it a lodestone compass or an Eye of Ender, or explore the destination on a map it carries. |
| can't read the map underground without a torch or lantern | Give it a torch or a lantern. |
| can't fast-travel underground without ... | Bring it to the surface, or give it a map, compass and light. |
| needs provisions for this journey | Give it food. The message says roughly how much. |
| is resting ... before traveling | Wait. It goes by itself when the rest is over. |
| cannot fast-travel while in combat | Let the fight end first. |
| compass no longer points to a valid lodestone | Place a new lodestone and bind the compass to it again. |
