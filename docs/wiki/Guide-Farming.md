<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Farming

[All Guide topics](Guide.md)

## Build Farm

Builds and plants a small farm with irrigation.

- Chooses a site, prepares tilled rows, and handles irrigation.
- If a site is unsafe/invalid, it should relocate or fail clearly.

**Command:** `/bot skill farm <bot>`

**Where:** No keybind by default

## Plant Seeds

Plants seeds on any empty tilled soil nearby.

- Scans a 16-block radius for empty farmland and plants available seeds.
- Supports wheat, beetroot, melon, pumpkin, potato, and carrot.

**Command:** `/bot skill plant <bot>`

**Where:** No keybind by default

## Harvest Crops

Harvests fully mature crops, replants them, and keeps the field tidy.

- Scans a 16-block radius for ripe crops on farmland and carefully breaks them.
- Sneaks to avoid trampling tilled soil, replants matching crops, then runs a drop sweep.
- If inventory space gets tight, it offloads into nearby chests and can pull more seeds from nearby chests to finish replanting.

**Command:** `/bot skill harvest <bot>`

**Where:** No keybind by default
