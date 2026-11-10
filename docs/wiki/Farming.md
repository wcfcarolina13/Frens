# Farming

Frens can build a small farm, plant seeds and harvest crops. Each is a separate job, and each runs once and stops: a fren doesn't tend a farm by itself. This page explains what each job does, what it needs and where the crops go.

The short versions are the Guide cards [Build Farm](Guide-Farming.md#build-farm), [Plant Seeds](Guide-Farming.md#plant-seeds) and [Harvest Crops](Guide-Farming.md#harvest-crops).

## The jobs

| Job | Button | Command | What it does |
|---|---|---|---|
| Build Farm | Actions, Skills, **Farming** | `/bot skill farm [bot]` | Builds a watered farm and plants it |
| Plant Seeds | none | `/bot skill plant [bot]` | Plants seeds on empty farmland within 16 blocks |
| Harvest Crops | Actions, Skills, **Harvest** | `/bot skill harvest [bot]` | Harvests ripe crops within 16 blocks and replants them |

The buttons work for the fren's owner, operators and the host. Typed `/bot` commands need an operator or the host.

None of these jobs repeat or run until sunset. To keep a farm going, run Harvest again when the crops are ripe.

## Build Farm

### What it builds

A square of farmland 10 blocks across, around a 2 by 2 pool of water in the middle. Every plot is within 4 blocks of the water, so the whole farm stays wet. It tills only as many plots as it has seeds for, so a fren with 20 seeds makes a 20-plot farm.

It plants wheat, beetroot, melon or pumpkin seeds, potatoes or carrots, whichever it carries. It doesn't add fences, torches or paths.

### What it needs

- **A hoe** of any kind, in its inventory.
- **Seeds**, from the list above.
- **A bucket.** It fills buckets from water nearby, or from water it remembers within 48 blocks. Two water buckets make a pool that never runs dry. With a single bucket it says so and carries on with one. If it carries two and can fill only one, it stops with "No water available for irrigation."

It doesn't take these from chests or craft them. Give them to it first.

### Choosing the spot

- **From the button:** pressing Farming shows a preview of the farm where you are looking. Press the lock key to fix it in place and move it with the arrow keys, then press the Go To Look key to start. The stop key cancels.
- **From the command, with no spot given:** the fren looks for a 2 by 2 pool with walls around it within 24 blocks and builds around that. Otherwise it tries spots in a grid around itself, preferring ones with fewer trees in the way, then ones closer to it. A spot within 7 blocks of existing farmland is pushed about 7 blocks away from it.

It won't build where the ground has fences, gates, walls, leashed animals or existing farmland. A spot it picks itself must also be fairly flat and away from water. If no spot works, it says "No suitable reachable spot on the farm grid (avoid oceans/doors/cliffs). Try a flatter / more open spot." or "Farm site rejected:" with the reason. Move it somewhere flatter and more open, or pick the spot yourself with the button.

### What it does to the ground

It cuts down trees in the way, levels the square by digging high spots and filling low ones with dirt, digs the pool, seals its edges and pours in the water. When you pick the spot yourself it allows deeper cuts and collects dirt if it runs short. At the end it fills any holes and replants missed plots.

It won't dig or place blocks in a protected zone it isn't allowed in, or inside another player's fort. See [Protected zones](Protected-Zones.md).

When it finishes it says "Prepared a hydrated farm and planted N seeds (tilled M)."

## Plant Seeds

Plants seeds on **empty farmland** within 16 blocks, nearest first. It doesn't till, so the farmland has to be there already. It plants wheat, beetroot, melon or pumpkin seeds, potatoes or carrots from its inventory. There is no button for it.

## Harvest Crops

- It harvests **wheat, beetroot, carrots and potatoes** that are fully grown, within 16 blocks of where it starts. It doesn't harvest melons, pumpkins, sugar cane, berries, cocoa or nether wart.
- It replants each harvested plot with the same crop, then plants any other empty farmland nearby with wheat, beetroot, carrots or potatoes.
- It stands beside the crops while it works, and sneaks when it has to stand on farmland, so it doesn't trample them.
- If it harvested anything, it picks up the drops within 12 blocks at the end.
- If nothing is ripe, it still plants the empty farmland.

It works on any farmland in range, including the fields of a village you haven't mapped. Inside a [mapped village](Protected-Zones.md) it won't break crops, though it still plants empty farmland there.

### Seeds from your chests

If it runs out of seeds while replanting, it can ask to take more from a chest near it. You get one prompt for all the plots it still has to plant. See [Taking supplies from your chests](Storage.md#taking-supplies-from-your-chests). If you say no or don't answer, it stops asking for the rest of that harvest.

### Where the crops go

The harvest stays in the fren's pack. When it has fewer than 3 free slots, it empties its pack into a chest: the last chest it placed, a chest within 12 blocks, or a chest your frens placed within 48 blocks. If there is none, it places a chest, crafting one if it needs to. Wheat, beetroot and any other stackable items go in. It keeps wheat and beetroot seeds, carrots and potatoes (it replants with them), tools, torches and cooked food. See [Storage and chests](Storage.md).

It says "Harvested N crop(s) and replanted M." when it is done.

## Seeds from grass

**Grass Seeds** is an idle hobby you can turn on in the Hobbies screen. When the fren has nothing to do and there is grass within 16 blocks, it breaks a few clumps of grass and ferns for wheat seeds and puts them in a chest your frens placed, if there is one. It leaves grass close to buildings alone. It also stays out of bases, protected zones, forts, mapped villages and the area around bee nests.

## Common surprises

| What you see | Why |
|---|---|
| It built a tiny farm | It only tills as many plots as it has seeds. Give it more seeds. |
| "I need a hoe to till the soil." | Give it a hoe. It doesn't craft one or take one from a chest for farming. |
| "No water available for irrigation." | Give it a bucket and make sure there is water nearby. |
| It didn't harvest the melons | Harvest only handles wheat, beetroot, carrots and potatoes. Break melons and pumpkins yourself. |
| It harvested the village farm | Only mapped villages are protected. Map the village, or keep the fren more than 16 blocks from the fields. |
| It stopped after one pass | All farming jobs run once. Run Harvest again when the crops are ready. |
