# Work loops

A work loop keeps a fren on a job day after day without you. It works at a spot, goes home at sunset, sleeps, and goes back to the same spot at sunrise. A lodestone compass is what lets it find its way back.

The short version is on the Guide card [Daily Work Loops](Guide-Gathering.md#daily-work-loops-lodestone-compass). This page walks through the setup and what to check when a loop breaks.

## What you need

- A **lodestone** at the work spot (eight chiseled stone bricks around an iron ingot).
- A **compass** bound to that lodestone (use the compass on the lodestone), in the fren's inventory.
- A **place to sleep**: a base, or a bed the fren has slept in. With neither, it goes to the world spawn. See [Bases and home](Bases-and-Home.md).
- **Food** in its inventory. Fast travel costs 1 hunger point per 20 blocks. See [Fast travel](Fast-Travel.md).

## Setting up a woodcutting loop

1. Put the lodestone at the edge of the forest you want cut. The fren starts searching for trees from beside it each morning.
2. Bind a compass to it and put the compass in the fren's inventory. If you use several compasses, rename them on an anvil ("Forest", "Shore") so you can tell them apart in `/bot compass list Jake` and in the Base Manager.
3. Give the fren an axe or two, food, and somewhere to put the logs (it stores to chests when its pack fills).
4. Start the job **without a count**: `/bot skill woodcut Jake`, or the Woodcut button left at "Until sunset".
5. That's it. At sunset it stops and heads home. Because it carries a lodestone compass, it fast-travels there when home is 16 or more blocks away. At sunrise it fast-travels to the lodestone and starts cutting again.

A fishing loop is set up the same way, with the lodestone on a shoreline and `/bot skill fish Jake`. Fishing also remembers its exact stand, so it can go back to that spot. If the spot turns out bad, use [Forget Fishing Spot](Guide-Gathering.md#forget-fishing-spot).

## How the fren decides where to go in the morning

- At sunset it notes the job and the spot where it stopped.
- At sunrise it looks at every lodestone compass it carries and picks the lodestone **nearest that spot**.
- That lodestone must be **within 128 blocks** of the spot, in the same dimension, and still standing.
- It lands beside the lodestone and restarts the job.
- If no compass qualifies, it restarts the job wherever it woke up, which is usually home.

So keep the lodestone close to where the work actually happens. A fren that wanders far from its lodestone during the day still comes back to that lodestone, as long as it stopped within 128 blocks of it.

## When the loop doesn't run

| What you see | Likely cause |
|---|---|
| It finished and never went back | The job had a count. Counted jobs end and are not resumed. Start it with no count. |
| It works next to its bed in the morning | No lodestone within 128 blocks of where it stopped, or it was in another dimension. |
| `/bot compass list Jake` says a compass lost its lodestone | The lodestone was broken. Place a new one and bind the compass again. |
| It walked instead of fast-travelling | Fast travel needs enough food, and the fren rests 3 minutes between trips. See [Fast travel](Fast-Travel.md). |
| It didn't go home at sunset | It was following you or on a job with a count. Idle frens and until-sunset jobs go home, and guards or patrols do too when that option is on. |

Hunting has its own day-to-day loop and doesn't use lodestones this way.
