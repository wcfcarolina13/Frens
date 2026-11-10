# Spells

Spells let you call, move and reach a fren from a distance: bring it to you, send it home, teleport it, or open its inventory from far away. Most spells need an item, such as an Eye of Ender, or an Enchanting Table nearby. This page lists each spell, what it needs and what it costs.

## Opening the spells

Press **[** (Frens: Open Spells) or open the fren's inventory and go to the **Spells** tab. The key picks the fren you are looking at. If you aren't looking at one, it picks your only fren, or your recruited companion in Questing mode. In Questing mode it does nothing until you have recruited a companion.

On a server, a player who isn't an operator has to be within 8 blocks of the fren, in the same dimension, for **[** to open its screen. Operators and the singleplayer host can open it from anywhere.

Only the fren's owner, operators and the host can cast its spells. In Questing mode, Regroup, Summon, Home, Remote Inventory and Soul of Ender also only work on the recruited companion, once it is a permanent companion, and only for the player it pledged to.

The status line at the top shows which spell items you and the fren carry. Spells you can't cast are greyed out.

## What unlocks the movement spells

Regroup, Summon, Home and Remote Inventory need a way to reach the fren:

| Item or place | What it allows |
|---|---|
| **Wizard's Tome**, anywhere in your inventory or the fren's | Regroup, Summon (also across dimensions), Home and Remote Inventory |
| **Enchanting Table** within 4 blocks of you (or of the fren) | The same as a Wizard's Tome. For Summon across dimensions, the table has to be near you. |
| **Eye of Ender**, carried by you or the fren | Regroup, Summon and Home in the same dimension. Not used up. |
| **Goat Horn**, carried by you | Regroup, but only with the typed command. In the Spells tab, Regroup stays greyed out with only a horn. |

The Tome and the table don't replace what the other spells use up: pearls, chorus fruit, or the Enchanting Table or anvil near you.

In Questing mode, the **Home** button works without an item within 16 blocks of your village's anchor. Regroup and Summon are also allowed within 12 blocks of it, but only by typed command.

## The spells

### Movement

| Spell | What it does | Cost |
|---|---|---|
| **Regroup** | The fren stops what it is doing and walks to you. Same dimension only. | None |
| **Summon** | The fren appears beside you, with its mount. If it isn't in the world, it is brought back. Across dimensions only with a Tome or a table near you. | None. When you cast with your own Eye of Ender and no Tome or table, you wait 60 seconds between casts. |
| **Home** | The fren goes home. In Questing mode, to the village anchor, in the same dimension. | None |

### Travel

**Remote Guidance** sends the fren on a [fast travel](Fast-Travel.md) trip: to you, to its home, or to one of your bases in the dimension you are in. You and the fren each need an Ender Pearl somewhere in your inventories, and **both are used up** when you cast. The trip takes about 1.5 seconds per chunk (30 seconds more across dimensions), between 7 seconds and 5 minutes. It can take two or three times as long if the fren only has weaker travel items.

The fren doesn't need food for this trip, but the other fast travel rules still apply:

- If it is resting from its last trip, it leaves when the rest ends.
- If it is in a fight, the trip is cancelled and the pearls are lost.
- It needs a way to find the place. The pearls count as that way, but they are used up first, so with one pearl each the trip can be cancelled unless the fren also has a Map and Compass, a lodestone compass or the like. Carry a spare pearl each.

**Chorus Recall** teleports at once, even across dimensions: either the fren to you, or you to the fren. You and the fren each need an Ender Pearl and a Chorus Fruit, and all four are used up. It has no cooldown and works during a fight. A fren riding an animal may be refused, with nothing used up. It says "Chorus Recall complete."

**Soul of Ender** frees the fren to teleport for 75 seconds, past anything that would normally block teleporting. The fren needs an Eye of Ender, which it keeps, and a Chorus Fruit, which is used up. It wears off after 75 seconds or when the server restarts.

### Remote access

| Spell | What it does | Needs |
|---|---|---|
| **Remote Inventory** | Opens the fren's inventory from anywhere | A Tome or table, on your side or the fren's |
| **Enchant** | Opens an Enchanting Table using the fren's items, levels and lapis | You within 4 blocks of an Enchanting Table, and the fren in the same dimension within 64 blocks |
| **Anvil** | Opens an anvil using the fren's items and levels | You within 4 blocks of an anvil, and the fren in the same dimension within 64 blocks |

Enchant and Anvil give no message when the fren is too far away or there is no table or anvil near you.

## The Wizard's Tome

To make one:

1. Go to **The End** with an Enchanting Table that has 15 bookshelves around it.
2. Carry 1 Eye of Ender, 1 Book, 1 Diamond, 3 Redstone and 3 Lapis Lazuli.
3. Hold the Eye of Ender, sneak and right-click the table.

It says "You craft a Wizard's Tome." Operators can also use `/bot wizard_tome [count]`. You don't open the Tome. Carry it and press **[** near the fren.

## Commands

Operators and the host can also type:

```
/bot companion come [bot]          Regroup
/bot companion summon [bot]
/bot companion home [bot]
/bot companion soulofender [bot]
/bot companion open [bot]          Remote Inventory
```

Remote Guidance and Chorus Recall have no command.

## Common surprises

| What you see | Why |
|---|---|
| Summon or Regroup is greyed out | You need a Tome, an Enchanting Table nearby or an Eye of Ender. A Goat Horn only works with the typed command. |
| **[** says the fren is too far away | On a server, players who aren't operators have to be within 8 blocks to open its screen. |
| "can't be summoned across dimensions" | Across dimensions you need a Tome, or an Enchanting Table near you. |
| "Eye of Ender spell is on cooldown" | Summon with your own Eye of Ender waits 60 seconds between casts. |
| Remote Guidance took my pearls and the fren didn't go | It was fighting, or had no other way to find the place once the pearls were gone. If it was resting, it leaves when the rest ends. |
| The message mentions an Ender Pearl for Summon or Home | That message is wrong. Pearls don't unlock those spells. |
| Enchant does nothing | You need to be within 4 blocks of an Enchanting Table, and the fren within 64 blocks. |
