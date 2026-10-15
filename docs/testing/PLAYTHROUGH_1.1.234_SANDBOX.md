# Sandbox playthrough: Frens 1.1.234, driven by Claude (2026-10-14)

Instance `mcp-sandbox` (Fabric API + Frens 1.1.234 + hardened minecraft-mod-mcp). Claude clicks, types and screenshots through the control API; Bradley watches. New Survival world "New World", commands off.

## Passes (Phase 6af)

- Item 2: a new world opened the World Mode chooser (first launch, before any companion). Admin chosen, "World mode set to Admin.", roster opened, Jake spawned. Choice persisted across a restart (no chooser on reload, Jake auto-restored).
- Item 3: no pillaring without an axe (`not pillaring (fell-ascent) … logs that need a pillar wait for the axe`), craft attempted on the ground with 3 punched logs, one chat line on failure (was 8). Table placement itself failed, see #3/#4.
- Item 5/6: `woodcut_cleanup` finished in 7 s with "Nothing left to clean up here.", no canopy pillar.
- Item 7: drop sweep said "Nothing to pick up nearby." with no return trip after it. Explicit `/bot craft wooden_axe` answered in one line and placed the carried table correctly (inventory path), so #3 is specific to the just-crafted reposition path.
- Item 3 start: `Woodcut axe bootstrap: Jake has no axe, punching logs bare-handed`, one chat line "No axe yet, so I'll punch a tree first."

## Findings

| # | Finding | Evidence | Severity |
|---|---|---|---|
| 1 | **Bootstrap dirt gather fails inside the Spawn base.** A new world's own Spawn base protects the spawn area, so the gather step at woodcut start hits "Cannot mine: protected claim" and gathers 0. Gather should happen near the target tree (outside protection), or skip protected cells before trying. | log 19:35:35 `Woodcut mining returned '⚠️ Cannot mine: protected claim.' at 0, 68, 11`, `gathered 0 hand scaffold block(s)` | 🟡 Medium |
| 2 | **Relocation walks fail on a snowy plain with a frozen lake.** No trees within 36; the 44-block walk to the nearest tree was "walk blocked after replans" twice, then it targeted a tree 114 blocks away. | log 19:35:37-19:36:17 | 🟡 Medium (watch) |
| 3 | **Crafting table placement blocks itself.** Placement picked the stump cell 43,65,24 with stand 42,65,24, then "local reposition" moved the stand onto the place cell (stand = place = 43,65,24) and failed `bot-intersects-target`. The bot stood on solid grass with plenty of room. | log 19:37:17 `Crafting table local reposition [crafting-table-crafted-place]: bot=42, 65, 24 stand=43, 65, 24 place=43, 65, 24` | 🔴 High |
| 4 | **Bootstrap retry needs a different Y, so a ground failure never retries.** Attempt 1 failed on flat ground at y=65, the rule only allows attempt 2 at another Y. With the table in hand on open ground, a second attempt (after a 1-block step) should be allowed. | log 19:37:17 `axe craft failed at y=65 (attempt 1/2)` | 🔴 High |
| 5 | **"Woodcut target cleared (1 min 4)" in chat** — developer text, and the tree was not cleared (the top logs waited for an axe). Should be plain ("Got 3 logs, the rest of that tree needs an axe."). | log 19:37:20, `WoodcutSkill.java:1077-1079` | ⚪ Minor |
| 6 | **"Crafted 1 wooden_axe."** uses the raw item id. Should be the display name ("Crafted 1 Wooden Axe."). | log 19:39:21 | ⚪ Minor |
| 7 | **Stuck in his night shelter hole after morning.** At dusk Jake dug a 3-deep emergency bunker (`dug emergency bunker … capPlaced=true`), underground-survival immediately tried to pillar him back out (19:40:27-31, walk blocked), and after a reload at morning he sits at y62 with open sky above (`ensureAtSurface … not-ready … nearSurface=false … suppressed by linger decision tree`, every 10 s). bot_view first person confirms dirt walls on all sides. Shelter and surface recovery disagree at night, and nothing climbs him out by day. | log 19:40:18-31, 19:59:47-20:00:07 (1.1.235), bot_view screenshots | 🔴 High |


- **Questing mode:** Bradley suggests disabling it until its direction is decided. Recommendation: hide it from the World Mode chooser for new worlds (Admin only, chooser becomes a one-line confirmation or disappears), keep the code and keep existing questing worlds working, behind a config flag (`questingModeEnabled`, default off).


## Rulings to discuss

- **Questing mode:** Bradley suggests disabling it until its direction is decided. Recommendation: hide it from the World Mode chooser for new worlds (Admin only), keep the code and existing questing worlds working, behind a config flag (`questingModeEnabled`, default off).
