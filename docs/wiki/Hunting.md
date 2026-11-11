# Hunting and cooking

A fren can hunt animals for food and leather, hunt zombies when it is well armed, and cook the meat afterwards. This page explains how to start a hunt, what a fren will and won't hunt, where the loot goes and how a hunt ends.

## Starting a hunt

### From the Hunting screen

Open the fren's inventory, then Actions, Utilities, **Hunting**. The screen lists every mob a fren can hunt.

- **Click rows** to tick the types it may hunt. Click again to untick. With no rows ticked it hunts anything it can.
- **Count** is how many to hunt. Leave it empty to hunt until nightfall.
- **Zone** sets how far it looks: Standard (48 blocks), Expanded (80) or Sprawling (120). It searches around itself and around its bed and bases within that range.
- **Depop** (on by default) protects animal numbers. See [Keeping some animals alive](#keeping-some-animals-alive).
- **Hunt** starts.
- **All Edible** ticks every unlocked row.
- **Target** sends the fren after one mob you click, within 32 blocks. See [Picking one animal](#picking-one-animal).

The buttons work for the fren's owner, operators and the host.

**Ticking more than one row:** pressing Hunt hunts all the ticked types, going for the nearest one each time.

The ticks are remembered for that fren, but they only steer the hunt the Hunt button starts (and that hunt's resume the next morning). Typed hunts without a mob name, [Auto Hunt](#hunting-on-its-own) and the hunt hobby hunt anything edible, so a hungry fren never waits for one kind of animal.

### Picking one animal

Press **Target**, then click a food animal. Green particles mark all food animals within 32 blocks. Right-click cancels. Only animal types you have discovered work, and zombies can't be picked.

On its first try the fren goes for that exact animal, even a baby, a named animal or one in a protected area, so use it with care. If it can't reach or kill it within a few seconds, it hunts another animal of that type instead, following the normal rules. Depop still applies.

### Discovering mobs

A food mob is locked until **you** have killed one yourself, in this world. Your first kill of a type shows "New Hunting Target!" and adds it to the list. Until then the row reads "(locked)", and naming it in a command gets "I haven't discovered Cow yet." A hunt that names no mob may still take animals you haven't discovered.

Zombies need no discovery, but you can't tick them on the Hunting screen. Name them in the command instead.

### By command

Operators and the host can type:

```
/bot skill hunt [count] [mob] [bot]
```

- `/bot skill hunt 5 cow` hunts five cows. `/bot skill hunt cow` hunts cows until nightfall.
- `/bot skill hunt` hunts anything it can, whatever is ticked on the Hunting screen. That includes zombies when it is geared for them.
- `/bot skill hunt list` lists the mobs it knows.
- With several frens and a count, the count is shared between them. Add `each` to give each fren the full count.

### What it hunts

Cows, mooshrooms, pigs, chickens, sheep, rabbits, goats, foxes, turtles, cod, salmon, tropical fish, pufferfish, squid, glow squid, and zombies.

It hunts zombies only when its health is at 16 or more (8 hearts), it wears at least one piece of armour and it has a melee weapon. If you name zombies and it isn't ready, it says "I'm not geared enough to fight zombies." A hunt that doesn't name a mob just skips zombies. Animals need no weapon.

## What it won't hunt

- Babies.
- Tamed animals, including tamed horses.
- Animals with a name tag. The Attack Named Mobs setting doesn't change this.
- Animals inside a protected area: a saved base (any owner's), a fort and the buffer around it, a mapped village, the area around a bee nest, or an admin zone. Zombies are hunted there anyway. See [Protected zones](Protected-Zones.md).

Animals on a lead, or in a pen outside a base, are not protected unless they have a name tag. Keep farm animals inside a base, or name them.

When it hunts as an idle hobby, it also leaves alone animals that look penned in (fences, walls or gates on most sides) and animals within 5 blocks of anything you built. A large or open pen may not count.

### Keeping some animals alive

With **Depop** on, a fren:

- only hunts a type when at least 3 of it are in the zone, counting only adults that aren't named, tamed or protected. If you named the type, it says "Population too low (2 Cow). Depopulation protection is active." Otherwise it skips that type.
- moves on to another type when a kill leaves fewer than 3 of this one and another type it may hunt is still plentiful ("Not many Cow left. Moving on to Pig."). If none is, or you named the type, it stops: "Population getting thin. Heading back. (4 kills)"
- doesn't count zombie kills towards the count you asked for.

Turn Depop off on the Hunting screen to hunt freely. Frens don't breed animals.

## During the hunt

- It goes up to the surface first. If it can't: "Could not reach the surface for hunting."
- It goes for the nearest suitable mob and keeps on it until it dies or gets away. After 3 failed tries to reach one, it gives up on that mob.
- When nothing is in sight, it walks out in steps around where it started, and may build a short pillar to look further.
- It fights with its best melee weapon, up close.
- It eats cooked food from its pack only once it is starving (hunger 5 or less), and raw food only in an emergency. At hunger 5 it also asks a nearby fren for food.
- A nearby threat pauses it with "Hunt paused by another task." A hunt you started by command or button picks up again once the mobs are gone, within 10 minutes, counting kills from zero. `/bot stop` ends it for good.

## Loot

- After each kill it picks up the drops nearby, and once more at the end.
- If its pack is nearly full before it starts, it places a chest first ("Inventory is nearly full. Setting up a chest first."), crafting one or cutting wood for it if it needs to.
- When its pack fills during the hunt, it first puts cheap items in a chest nearby and keeps its food. If it is still full, it places a chest and puts in everything it doesn't need to keep. As a last resort it drops boats and crafting tables.
- It keeps tools, weapons, armour, bundles, torches, arrows, leads, compasses and cooked food. Everything else may go in the chest, raw meat included.

See [Storage and chests](Storage.md).

## How a hunt ends

| What happens | What it says | Cooks and goes home? |
|---|---|---|
| It reached the count | "Hunt complete (5 kills)." | Yes |
| A hunt with no count ends normally | "Hunt complete." | Yes |
| Too few animals left (Depop) | "Population getting thin. Heading back." | Yes |
| Nightfall, on a hunt with no count | "Sun has set. Stopping hunt." | Yes |
| No kill for 60 seconds | "Can't reach any prey. Giving up." | No |
| A named mob isn't there | "No Cow found in the hunting grounds." | No |
| Nothing to hunt, with no mob named | It may go fishing instead ("Switched to fishing."), or says "No huntable mobs found nearby." | No |
| A threat or `/bot stop` | "Hunt paused by another task." | No |

The "Yes" rows need at least one kill. With no kills it says "No kills completed." and stays where it is.

When it gives up or runs out of prey, it stays where it is with the meat. It cooks it later, when it is idle and hungry.

A hunt **with a count** keeps going through the night until it reaches the count. A hunt with no count stops at nightfall, but only if it started during the day.

### Nightfall and the next morning

If **Auto Home @ Sunset** is on, a hunt with no count that is still running at nightfall says "Sun's setting. Heading home. I'll resume the hunt tomorrow. (4 kills so far)". The next morning it says "Resuming yesterday's hunt (4 kills so far)." and walks back to where it was hunting. The resumed hunt has no count and runs until nightfall.

The morning resume only happens if all of these are true:

- Auto Home @ Sunset is still on.
- The fren is awake in the Overworld early in the morning.
- It isn't busy with something else at that moment. If it is, it skips that day.
- Less than 24 hours (real time) have passed.

`/bot stop` or dying cancels it. After dying, the fren asks whether to carry on with the last job. Saying yes starts the hunt again from zero.

Auto Hunt, hobby hunts and hunts started with the `until_sunset` option work differently. With Auto Home @ Sunset on, they are cut off at dusk ("Sunset: heading home."), with no cooking and no resume the next morning.

### Going back afterwards

After a hunt that ends normally with at least one kill, the fren goes home, not to you. Home is its preferred base, or otherwise whichever is nearer of the bed it last slept in and its nearest base. With neither, it stays where it finished.

If home is more than 96 blocks away it stays where it is and says so. With teleport during skills on, it teleports home from any distance. It doesn't put the loot in base chests.

## Cooking

### After a hunt

When a hunt ends normally and it has raw meat or fish, it cooks it straight away and waits for it, up to 10 minutes. It uses, in this order:

1. a smoker or furnace your frens set up, within 64 blocks (a smoker first)
2. a furnace or smoker placed within 24 blocks
3. one from its pack
4. one it crafts

It doesn't use blast furnaces for food. It skips a furnace whose slots already hold something different.

### When it gets hungry

A fren that is idle in the Overworld with hunger at 10 or lower, raw food and fuel cooks on its own. Without fuel it may gather leaf litter first, if the leaf litter hobby is on. `/bot stop` pauses this for 2 minutes.

While raw meat is its only food, it holds on to it for the furnace instead of eating it raw. It does this while its hunger is 7 or more, its health is at least half, and fuel and a furnace are close by. At hunger 10 or lower it waits at most 3 minutes, then eats.

### The cook command

```
/bot cook [item] [fuel] [bot]
```

- `item` matches part of the name. `fish` matches all the fish.
- With no item, it loads the first thing it can cook, which may be an ore. A furnace takes one kind at a time, so name the item.
- With no fuel word, it picks the cheapest fuel it has: leaf litter first, then leaves, saplings, coal and other plain fuels, then logs, and planks, sticks and charcoal last. It never burns tools, chests or wool. `none` loads no fuel, and you can also name a fuel item. It adds no fuel if the furnace already has some.
- With no fuel and leaf litter nearby, it gathers some first: "No fuel on me. Gathering some leaf litter first."
- It walks to the furnace you look at, or one nearby, loads it and leaves. It doesn't wait for the food to finish.

## Hunting on its own

**Auto Hunt (Starving)** under Actions, Automation, or `/bot auto_hunt_starving on`, is off by default. When it is on, an idle fren in the Overworld with hunger at 10 or lower and fewer than 8 food items goes hunting. It stops once its hunger is at 16 or more and it carries 8 food items. At night it may leave a shelter to hunt zombies if it is armed and healthy.

**Hunt** is also an idle hobby. A fren with nothing to do, health at 18 or more and hunger at 16 or more may go and hunt one animal nearby when there is no thunder.

## Common surprises

| What you see | Why |
|---|---|
| It took my leashed cows | Leads don't protect animals. Keep them in a base, or name them. |
| It ignores cows in my base | Animals inside any base are protected. |
| "I haven't discovered Cow yet." | Kill one yourself first. |
| It hunted through the night | A hunt with a count keeps going until it reaches the count. |
| It stopped and didn't cook or go home | It gave up or ran out of prey. It cooks later, when it is idle and hungry. |
| Its pack is full of raw meat | Cooking needs a furnace and fuel. Give it coal, or turn on the leaf litter hobby. |
