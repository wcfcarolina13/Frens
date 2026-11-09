<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Settings

[All Guide topics](Guide.md)

## Keep Bots Quiet

Turn down what bots say, in text or out loud.

- Open Bot Controls and find the dialogue toggles.
- Scripted Text and Scripted Voice cover the ready-made lines.
- Soul Chat and Soul Voice cover AI chat, if you set it up.
- Each toggle works alone, so you can keep text and turn off voice.

**Where:** Bot Controls

## Let a Friend Use Bots

Who can command a bot on a shared world.

- A bot's owner, an operator or the host can command it.
- To let a friend command your bots, make them an operator on the world.

**Where:** Bot Controls

## Follow Teleport

Wolf-style catch-up teleport during follow mode.

- When enabled, the bot teleports near you if it falls more than 15 blocks behind, gets stuck for 3 seconds, or loses vertical contact (10+ block Y gap).
- This only affects follow mode. It does not enable teleport during skills, sweeps, or other behaviors.
- The bot finds a safe spot behind you, so it won't teleport into lava or off cliffs.
- Toggle in Bot Controls &gt; Behavior &gt; Follow Teleport.
- Admins can control which players see this toggle in Player Settings.

## Fast Travel &amp; Navigation Tiers

Companion fast travel speed depends on held artifacts.

- Full speed: the bot holds a bound lodestone compass, the bot or you hold an Eye of Ender or a Wizard's Tome, the bot or you are within 6 blocks of an Enchanting Table, or the bot and you both hold Ender Pearls.
- Slower: the bot holds a Map and Compass that show the destination (underground it also needs a torch or lantern), or a smoke signal marks the destination base.
- There is no walk or fast-travel setting. Without a gate the bot walks.
- Cross-dimension travel adds 30 seconds. Delays run from 7 seconds to 5 minutes.

**Command:** UI-only

**Where:** Give the bot a Map and Compass for basic travel. For full speed, give the bot a bound lodestone compass, or carry an Eye of Ender yourself (one in the bot's inventory works too). See Fast-Travel Gates for every gate.

## Preserve Expensive Gear

Keep your bots from wearing out your best tools.

- When enabled, your bots refuse to use enchanted items and gear made of gold, diamond, netherite, or turtle shell once durability drops below 11%.
- In combat, the threshold drops to 3%, so the bot will push a risky item harder when lives are on the line.
- When a tool is locked out, the bot tries to swap to a cheaper alternative from its inventory, walks to a registered chest for a replacement, or crafts a new one at a nearby crafting table.
- If nothing is available, the bot pauses and speaks up. Check its inventory and give it a refill.
- Note on Mending: items with the Mending enchantment are also preserved. If you want a Mending item used continuously for XP self-repair, disable this toggle.
- Toggle this under the bot's Admin tab → Behavior section. The setting is per player and applies to every bot you own. Default: OFF.
