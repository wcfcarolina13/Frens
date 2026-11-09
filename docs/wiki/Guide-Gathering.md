<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Gathering

[All Guide topics](Guide.md)

## Cleanup (Drop Sweep)

Collects nearby dropped items quickly.

- Best after combat, mining, logging, or building.
- Runs a focused pickup sweep to reduce item despawns.

**Command:** `/bot skill drop_sweep <bot>`

**Where:** Actions menu: Cleanup

## Retrieve Drops

Sends the bot to where you or another bot last died to pick up the items, then it comes back to you.

- The bot fast travels to a safe spot next to the death position, even in another dimension. The travel rest time does not apply, but it still needs food for the trip.
- It goes after the items that dropped when you or the bot died, tells you how many items it got, and fast travels back to you. Other items it walks over may still be picked up. If its inventory fills up it stops and says so. It never stores items in chests there.
- It lands on a safe spot near you. If you are offline, it can't travel back, it finds no safe spot near you, or the sweep fails twice, it stays at the death spot and tells you.
- Items on the ground last about 5 minutes while the area is loaded. For a death older than 20 minutes, or one whose time is unknown, it still goes but warns that the items may be gone.
- Deaths in lava or the void are refused because the items are gone, and so is any death while the keep inventory game rule is on. A death to a mob or in fire is allowed, with a warning.
- Only the bot's owner can send it, and only for their own death or one of their own bots.

**Command:** `/bot retrieve <bot> [me|<dead bot>]`

**Where:** Actions menu: Retrieve Drops (your own death)

## Fishing

Fish until sunset or a chosen catch count.

- Set a custom count from the Actions row +/- controls.
- When count is unset, fishing runs by default behavior.

**Command:** `/bot skill fish <count> <bot>`

**Where:** Actions menu count controls

## Forget Fishing Spot

Clears the bot's saved fishing stand so it picks a fresh spot next time.

- When a bot is mid-session the sunset auto-return saves the current stand so it can resume there at sunrise.
- If the saved spot is bad (unreachable, no nearby chest, unsafe shoreline), the bot can end up fast-travelling back to it every morning and failing.
- Running this clears the saved session and the sunrise-resume record for the target bot.
- Lodestone compasses are NOT touched. The next fresh /bot fish still fast-travels to the nearest compass target.

**Command:** `/bot fish forget <bot>`

**Where:** Actions menu: Forget Spot (under Fishing)

## Woodcut

Fells nearby trees. Defaults to 'Until sunset' mode. Use +/- to set a specific tree count.

- At the default (Until sunset), the bot cuts trees until dusk then stops.
- Use +/- to set a minimum tree count instead.
- Use Cleanup after large runs to gather leftovers quickly.
- Bots won't cut trees inside your base, the Spawn area, a protected area or a mapped village. If those are the only trees nearby, the bot walks out to find others.
- An admin can let bots cut Spawn area trees in Admin, World Settings.
- No axe? The bot punches a tree for wood, then makes a crafting table, sticks and a wooden axe.

**Command:** `/bot skill woodcut [count] <bot>`

**Where:** Actions menu count controls

## Daily Work Loops (Lodestone Compass)

Send a fren to a far work spot that it returns to every morning: home at sunset, back to work at sunrise.

- Put a lodestone at the work spot (a forest, a fishing shore), bind a compass to it, and give the compass to the fren. Name your compasses on an anvil to tell them apart.
- Give the fren somewhere to sleep: a base or a bed it has slept in (see Home &amp; Bases Explained). With neither, it goes to the world spawn.
- Start the job with no count, for example /bot skill woodcut &lt;bot&gt; or /bot skill fish &lt;bot&gt;. A job with a count ends when the count is reached and is not picked up again.
- At sunset the fren stops, remembers the job and where it stopped, and goes home. It remembers the job when it holds a compass whose lodestone still stands in this dimension (or when it is at a base, or sheltered in place).
- At sunrise it fast-travels to the lodestone nearest the spot where it stopped, lands beside it and starts the same job again. That lodestone must be within 128 blocks of the spot; if none is, it starts the job again wherever it woke up. Hunting has its own loop and is not resumed this way.
- Fast travel still needs a gate (a bound lodestone compass is one) and food. See Fast-Travel Gates.
- If a lodestone is broken, the compass stops working: /bot compass list &lt;bot&gt; shows each compass and any that lost their lodestone.

**Command:** `/bot compass list <bot>`

**Where:** Inventory &gt; Bases button (white rows are lodestone compasses)

## Wool

Shears sheep and gathers wool.

- Use +/- to specify a target amount.
- Works best in open areas with nearby sheep.

**Command:** `/bot skill wool <count> <bot>`

**Where:** Actions menu count controls

## Leaf Litter

Collects leaf litter from the ground for cheap furnace fuel.

- Works best in forested biomes where leaf litter is abundant.
- Leaf litter is the cheapest fuel, always preferred over logs or coal.
- Manual command collects up to a full stack, idle hobby collects a few.

**Command:** `/bot skill leaf_litter <bot>`

**Where:** Actions menu
