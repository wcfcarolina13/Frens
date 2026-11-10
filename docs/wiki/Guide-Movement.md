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

**Command:** `/bot regroup <bot> (legacy: bot come <bot>)`

**Where:** No keybind by default

## Return Home

Sends the bot back to its saved home/base anchor.

- Toggle style: pressing again can cancel and return control.
- Pair with Guard/Patrol for base security loops.

**Command:** `/bot return <bot>`

**Where:** No keybind by default

## Guard &amp; Patrol

Keeps the bot at a spot, guarding it, patrolling around it or staying put.

- Guard stands at the spot, patrol walks around it, and /bot stay waits where it is. Press the button again to stop.
- The + and - buttons set the radius, 3 to 32 blocks. Changing it resets the centre to where the bot stands.
- It notices hostile mobs it can see within about 10 blocks, whatever the radius.
- With only melee weapons it fights mobs that are after it. With a bow, crossbow or trident it also attacks other hostile mobs, and defends your pets and village villagers.
- It chases what it fights and walks back afterwards. It doesn't retreat when hurt, so give it armour, a shield and food. It does back away from creepers.
- The order isn't saved. After a restart or a death, give it again.
- It stays out through the night unless both Auto Home @ Sunset and Sunset Return in Guard/Patrol are on (Actions, Automation).

**Command:** `/bot guard <bot>`

**Where:** Actions &gt; Orders &amp; Travel &gt; Guard / Patrol
