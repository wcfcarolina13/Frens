<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Farming

[All Guide topics](Guide.md)

## Build Farm

Builds a watered square farm and plants it.

- Builds a 10 by 10 square of farmland around a 2 by 2 pool, so every plot stays watered.
- Tills only as many plots as it has seeds. Give it more seeds for a bigger farm.
- Needs a hoe, seeds and a bucket in its inventory. It fills buckets from water nearby and doesn't take tools or seeds from chests.
- The Farming button shows a preview where you look. The lock key fixes it so the arrow keys can move it, and the Go To Look key starts the build.
- With the command it looks for an existing walled 2 by 2 pool within 24 blocks, or picks a flat, open spot itself.
- Clears trees and levels the ground. It adds no fences or torches, and runs once.

**Command:** `/bot skill farm <bot>`

**Where:** Actions, Skills, Farming

## Plant Seeds

Plants seeds on empty farmland nearby.

- Plants empty farmland within 16 blocks. It doesn't till new ground.
- Supports wheat, beetroot, melon, pumpkin, potato, and carrot, from its own inventory.
- There is no button for it. Harvest Crops also plants empty farmland.

**Command:** `/bot skill plant <bot>`

**Where:** No keybind by default

## Harvest Crops

Harvests ripe wheat, beetroot, carrots and potatoes, and replants them.

- Harvests fully grown wheat, beetroot, carrots and potatoes within 16 blocks. Not melons, pumpkins or other crops.
- Won't break crops inside a mapped village.
- Replants each plot, then plants any other empty farmland nearby.
- Stands beside the crops, and sneaks when it has to stand on farmland, so it doesn't trample them.
- Can ask to take seeds from a chest near it to finish replanting.
- With fewer than 3 free slots it empties its pack into a nearby chest, or places one. It keeps its seeds, carrots and potatoes for replanting.
- Runs once. Run it again when the crops are ripe.

**Command:** `/bot skill harvest <bot>`

**Where:** Actions, Skills, Harvest
