<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Movement

[All Guide topics](Guide.md)

## Follow

Keeps the bot near you while you travel.

- Great for cave runs and travel support.
- Use follow distance controls in Actions to tighten or loosen spacing.

**Command:** `/bot follow <bot>`

**Where:** Shortcut: ` (toggle follow for looked-at bot)

## Regroup

Calls the companion back toward you using the safer regroup behavior.

- Use this when follow is struggling or you want the bot to come back to you deliberately.
- Preferred command: /bot regroup. Legacy alias: /bot come.
- Safe regroup avoids the more aggressive recovery behavior used by the older come wording.

**Command:** `/bot companion regroup (legacy: bot companion come)`

**Where:** No keybind by default

## Return Home

Sends the bot back to its saved home/base anchor.

- Toggle style: pressing again can cancel and return control.
- Pair with Guard/Patrol for base security loops.

**Command:** `/bot return <bot>`

**Where:** No keybind by default
