<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Mining

[All Guide topics](Guide.md)

## Collect Dirt

Collects dirt-like blocks for scaffolding and builds.

- Useful prep for construction and terrain patching.
- Command supports optional target amount.

**Command:** `/bot skill collect_dirt <count> <bot>`

**Where:** No keybind by default

## Stripmine

Mines a straight tunnel for ores and stone resources.

- Use +/- to set desired tunnel length.
- Then face the way to dig and press the Go To key (- by default) to start.
- The bot should refuse impossible or unsafe tool scenarios.

**Command:** `/bot skill stripmine <length> <bot>`

**Where:** Actions menu count controls

## Ascent

Climbs upward by a block count or until surface sky.

- Use +/- for block count.
- Then press the Go To key (- by default) to start.
- Use the ☀ toggle in Actions to turn Surface mode on/off.

**Command:** `/bot skill mining ascent <blocks|surface> <bot>`

**Where:** Actions menu: +/- and ☀ mode toggle

## Descent

Digs downward by a selected block count.

- Use +/- to set depth target.
- Then face the way to dig and press the Go To key (- by default) to start.
- Good for controlled shaft digging with oversight.

**Command:** `/bot skill mining descent <blocks> <bot>`

**Where:** Actions menu count controls
