# FAQ

## Do I need an AI model or Ollama?

No. Skills, following, building, fighting, storage and scripted dialogue all work without one. Ollama is only for the optional Soul Chat, where frens answer in their own words. See [Set up AI companions](Guide-Basics.md#set-up-ai-companions).

## Do I need Carpet?

No. Carpet is recommended alongside Frens, but it isn't required.

## Which Minecraft version?

Java Edition 1.21.11, with Fabric Loader 0.17.3 or newer and Fabric API. See [Getting started](Getting-Started.md).

## Questing or Admin mode?

Admin, unless you specifically want to recruit frens through a village questline. Questing is still in development and buggier. See [Getting started](Getting-Started.md#2-pick-a-world-mode).

## How do I make a fren stop?

Look at it and press `\`, or run `/bot stop <name>`. To keep it following you while it stops grabbing drops for a minute, use [Stand Down](Guide-Basics.md#stand-down) instead.

## How do I give a fren items?

Open its inventory with `/bot open <name>` and move items in, the same way you would with a chest.

## My fren is stuck or lost. How do I get it back?

Use Regroup (`/bot regroup <name>`) to call it back with the safer recovery behaviour. If you turn on [Follow Teleport](Guide-Settings.md#follow-teleport), a following fren teleports to you when it falls far behind or gets stuck.

## My fren didn't go home at sunset

Frens that are idle (not following you) or on an until-sunset job go home at sunset, and so do guards and patrols when that option is on. A job with a count keeps running. Where it goes depends on its beds and bases: see [Bases and home](Bases-and-Home.md).

## How do I make a fren fast-travel?

Give it food and a lodestone compass bound to a lodestone. With those it fast-travels home at sunset, back to work at sunrise, with `/bot compass travel`, and for Storage Go and Collect. Without a compass, an Eye of Ender held by you or the fren still works for Storage trips. See [Fast travel](Fast-Travel.md).

## Can it go back to work by itself in the morning?

Yes, with a lodestone compass at the work spot. See [Work loops](Work-Loops.md).

## It won't cut the trees near my house

That's deliberate. Frens don't cut trees inside your base, the spawn area, a protected zone or a mapped village. If those are the only trees nearby, the fren walks out to find others. See [Woodcut](Guide-Gathering.md#woodcut).

## How do I make frens quieter?

Bot Controls has separate switches for scripted text, scripted voice, Soul Chat and Soul Voice. See [Keep Bots Quiet](Guide-Settings.md#keep-bots-quiet).

## Can a friend use my frens?

A fren takes commands from its owner, an operator or the host. To let a friend command yours, make them an operator on the world. See [Let a Friend Use Bots](Guide-Settings.md#let-a-friend-use-bots).

## Can I change how fast a fren walks?

No, there is no speed setting.

## My fren died. What about its items?

With Auto Respawn on, it comes back right away. Its dropped items, or yours, can be fetched with [Retrieve Drops](Guide-Gathering.md#retrieve-drops) while they still exist (about 5 minutes once the area is loaded).

## Where are the settings?

In Bot Controls: open a fren's inventory and go to Admin, then Bot Controls, or run `/configMan`. See [Bot Controls Panel](Guide-Basics.md#bot-controls-panel).

## Something went wrong. Where do I report it?

Open an issue on [GitHub](https://github.com/wcfcarolina13/Frens/issues) with your Frens version and what the fren was doing. The game log (`logs/latest.log`) helps a lot.
