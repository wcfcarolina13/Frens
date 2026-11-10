<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Core Actions

[All Guide topics](Guide.md)

## Quick Store &amp; Quick Fetch

Point at a nearby chest and click to deposit or take items.

- Quick Store: Actions → Core Actions → Quick Store. Bot walks to the chest and deposits.
- Quick Fetch: Actions → Core Actions → Quick Fetch. Bot walks to the chest and takes items into its inventory.
- Point at any container (chest, barrel, shulker box) and left-click to confirm.
- Quick Store, plain click: deposits loose items, keeps tools, weapons and armor, bundles, torches, arrows, leads, compasses, clocks and cooked food.
- Quick Store, shift+click: dumps everything (including armor, tools, and food). Use when swapping loadouts. Both stop putting in building blocks once the bot is down to 32, so it can climb out of holes.
- The bot physically walks to the chest before interacting. It must be within 32 blocks, not busy with a task and not in a fight.
- If the chest fills up (store) or the bot’s inventory fills up (fetch), remaining items stay put.
- The bot does NOT remember chests used this way. Only automated-task chests appear in Storage.
- Right-click to cancel targeting mode.
- The bot can be interrupted with /bot stop. Items are never dropped on the ground.

**Command:** UI-only action (Actions → Core Actions)

**Where:** Works with any container block within 6 blocks of your crosshair
