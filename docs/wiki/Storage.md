# Storage and chests

Frens fill their packs fast. This page explains how they put things into chests and take them out: the buttons you use, what they do on their own while they work, and when they ask before taking anything of yours.

The short versions are the Guide cards [Quick Store & Quick Fetch](Guide-Core-Actions.md#quick-store--quick-fetch) and [Storage](Guide-Utilities.md#storage).

## The ways to move items

| Way | Who starts it | Which chest | Remembered in Storage |
|---|---|---|---|
| Quick Store and Quick Fetch | You, from the fren's Actions menu | The container you point at | No |
| Storage screen: Go and Collect | You | A chest the fren placed earlier | Yes, it is the list |
| `/bot store deposit` and `withdraw` | An operator or the host | The chest you look at, or one near the fren | Only a chest it places for this |
| Offloading during a job | The fren, when its pack fills | A chest near it, or a new one | Most chests it places (see below) |
| Taking supplies for a job | The fren, when it needs materials, a tool or food | A chest it may use, after asking you | No |

You can always open the fren's inventory and move items by hand, the same way you would with a chest.

## Quick Store and Quick Fetch

Open the fren's inventory, then Actions, Core Actions, and pick **Quick Store** or **Quick Fetch**. Point at a container within 6 blocks of you and left-click. Right-click cancels.

- Any container block works: a chest, barrel or shulker box.
- The fren walks there. It has to be within about 32 blocks of the container, not busy with a job and not in a fight. If a fight starts on the way, it waits up to 30 seconds and tries again.
- **Quick Store, plain click** puts in everything except what it needs to keep working: tools, weapons and armour (anything that wears out), bundles, torches, arrows, leads, compasses, clocks, and cooked food, bread and stews.
- **Quick Store, shift-click** puts in everything, tools, armour and food too. Use it to swap a fren's whole kit.
- Both try to keep some building blocks for climbing out of holes. Once the fren holds 32 or fewer dirt, cobblestone, stone and similar blocks in total, it stops putting them in. It moves whole stacks, so it can end up with fewer than 32, or none.
- **Quick Fetch** takes everything from the container until the fren's pack is full.
- What doesn't fit stays where it was. Nothing is dropped on the ground.
- `/bot stop` stops it on the way.

The fren doesn't remember containers used this way. They don't show in Storage.

## The Storage screen

Open the fren's inventory, then Actions, Utilities, **Storage**. It lists the chests **this fren placed** when its pack filled during a job, or for `/bot store deposit`, in the dimension the fren is in now. Chests it places while fishing, building a shelter or smelting are not listed. Each row shows where the chest is, whether it is still there, and, when you hover, what was in it when it was last checked. Use the dropdown in the header to switch frens. Refresh checks the chests again.

Chests the fren only used, chests you placed, and Quick Store or Quick Fetch containers are not listed.

| Button | What it does |
|---|---|
| **Go** | The fren fast-travels to the chest. |
| **Collect** | The fren fast-travels to the chest, takes up to about 256 items, then does what you chose. |
| **Dismiss** | Removes the chest from the list. The chest stays in the world. |

When you press Collect, you choose what happens after:

- **Stay at Chest**: it stays there.
- **Return to Player**: it fast-travels back to where you are when it finishes.
- **Return to Home**: it fast-travels to a base named **home**. This is a base with that exact name, not the fren's preferred home. Without one, it goes back to where it started.

If you are offline when it finishes, Return to Player also sends it back to where it started.

Go and Collect are fast travel, so they follow the [fast travel](Fast-Travel.md) rules: the fren needs a gate and enough food, and it can't go while in a fight. If it is still resting from its last trip, it sets off when the rest ends. A lodestone compass or an Eye of Ender gives full speed. The trip back after Collect needs no gate, food or rest, and runs at full speed.

Collect only takes from the chest the fren was sent to, and only if it lands within 8 blocks of it. If the chest is gone, it takes nothing and tells you.

Only the fren's owner, an operator or the host can use a fren's Storage screen.

## While it works

When its pack fills during a gathering job (woodcutting, fishing, hunting, harvesting, digging dirt, foraging and others), a fren puts its haul in a chest so it can keep going.

- It uses a chest or barrel close by, or the last chest it placed. Some jobs also use chests your other frens placed, up to about 140 blocks away.
- What it does when there is no chest depends on the job. Most jobs can place a new chest, crafting one from wood if they need to. That includes Woodcutting, Collect Dirt, Hunt, Harvest and the item pickup that runs with most jobs. A chest placed this way shows in that fren's Storage list.
- Fishing also places a chest, but that one doesn't show in Storage.
- A few small jobs (mushrooms, honey, grass seeds, leaf litter) only use a chest that is already there.
- If nothing works, the fren may put cheap fuel in a nearby furnace, drop a stack of something cheap, or pause and tell you its pack is full.
- It keeps the things on the Quick Store keep list (tools, armour, torches, cooked food and the rest). One exception: while fishing it puts away worn-out rods, bows and leather boots it fished up, and spare arrows.
- A nearby chest or barrel counts whoever placed it. On a shared server, keep your frens' work areas away from other players' storage.

## Taking supplies from your chests

When a fren needs materials to craft, a replacement tool, seeds to replant or food, it can take them from a chest. It **asks you first**. You see a message in chat such as "Lia asks to take 8 oak planks from the chest at 120, 64, -30." with three buttons:

| Button | What happens |
|---|---|
| **Allow once** | It takes what it asked for, this once. The permission lasts 60 seconds. |
| **Always: common supplies, this chest** | All your frens may take common supplies from that chest without asking, until you take it back. |
| **No** | It takes nothing, and that fren won't ask for that item from that chest again for 5 minutes. |

If you don't answer within 30 seconds, it takes nothing. A fren asks at most once every 15 seconds.

**When it asks.** You have to be online, in the same dimension and within 32 blocks of the fren. Otherwise it doesn't ask, and takes nothing. An "Always" permission works wherever you are.

**What it may ask for.** Only common supplies:

- dirt, cobblestone, logs and planks
- sticks, torches, coal and charcoal
- bread, apples, baked potatoes and cooked meat or fish
- wheat seeds, beetroot seeds, carrots and potatoes
- wooden, stone and copper tools, and leather and copper armour

It never asks for an item that is enchanted or renamed or that carries other extra data. It never takes a chest's last few: it leaves at least 8 of a food, 16 of any other material, and one of each kind of tool or armour.

**Which chests.** Any chest that no fren placed, and chests your own frens placed. The mod can't tell who placed a chest by hand, so on a shared server this can include another player's chest, though you are still the one asked. Never a barrel, a chest placed by another player's frens, a locked chest, a chest in a protected zone it may not change, or a chest it can't open.

**Taking back "Always".** Type `/frens supply revoke <x> <y> <z>` for one chest, or `/frens supply revoke all` for every chest. Stand near the chest when you revoke one chest. Unlike `/bot`, `/frens supply` works for every player, not just operators.

What you do yourself is never asked about: Quick Fetch, Collect and `/bot store withdraw` take straight away.

## The /bot store command

Operators and the host can type:

```
/bot store deposit [amount] [item] [bot]
/bot store withdraw [amount] [item] [bot]
```

- `amount` is a number or `all`. Leave it out for all.
- `item` matches part of an item's name, so `oak` matches oak logs and oak planks. `all` or `everything` matches everything. Put a name with a space in quotes.
- To name an item, give an amount first: `/bot store deposit all oak`. Give an item with an amount too, because an amount on its own can be read as a fren's name.
- With no item, `deposit` puts in building blocks, logs, planks, ores, mob drops and other bulky items, plus anything of a kind the chest already holds. `withdraw` with no item takes the same building blocks and bulky items.

Which chest it uses:

1. the chest, trapped chest or barrel you are looking at, within 6 blocks of you
2. otherwise the last chest the fren placed, a chest or barrel near the fren, or a chest your frens placed within about 140 blocks
3. for `deposit` only, if there is none, it places a chest (crafting one if it needs to) and remembers it in Storage
4. if it can't place one either, it puts cheap fuel such as leaves and sticks into a furnace within 12 blocks, or tells you it has nowhere to put things

## Common surprises

| What you see | Why |
|---|---|
| A chest I used with Quick Store isn't in Storage | Storage lists only chests the fren placed (and not the ones it placed while fishing). |
| Return to Home sent it somewhere odd | It goes to a base named "home", not its preferred home. Without one, it goes back to where it started. |
| Collect says it isn't in the registry | Collect works only on chests in that fren's own list. Dismissed chests are gone from the list. |
| It didn't ask for the iron I have in a chest | It only asks for common supplies. Give it rarer items yourself. |
| It never asks me for anything | You were too far away, offline or in another dimension. Stay within 32 blocks, or press Always on the chest it uses. |
| Quick Store left its pickaxe and food | A plain click keeps gear. Shift-click to put everything in. |
| It put its loot in someone else's chest | Offloading uses any chest or barrel near the fren. Work away from other players' storage. |
