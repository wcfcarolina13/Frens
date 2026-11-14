# Multiplayer and ownership

On a shared world, every fren belongs to one player. This page explains who can do what with a fren, how ownership works, how to share bases and walls, and what changes on a dedicated server.

## Who is who

| Role | Who it is |
|---|---|
| **Owner** | The player a fren belongs to |
| **Operator** | A player in the server's operator list |
| **Host** | The player whose singleplayer or LAN world it is. A dedicated server has no host, and LAN guests aren't the host. |
| **Everyone else** | Other players |

## What each role can do

| | Owner | Operator or host | Everyone else |
|---|---|---|---|
| Buttons in the fren's menus (jobs, orders, storage, spells) | Yes, for their own frens | Yes, for every fren | No |
| Open the fren's inventory | Within 8 blocks, same dimension | From anywhere | No |
| Talk to it by name, or in [AI chat](AI-Chat.md) | Yes | Yes | No (how-to questions still get a Guide answer) |
| Typed `/bot` commands | No | Yes | No |
| Spawn new frens | No | Yes | No |
| `/frens guide` and `/frens supply` | Yes | Yes | Yes |

Players who aren't operators use the buttons. Every button does the same as a `/bot` command, but only for that player's own frens. A fren with no owner can only be used by operators and the host.

When you press a button without picking a fren, it goes to your only fren. If you have more than one, it says "You have more than one bot. Pick one first."

Common refusals:

- "You can only do that for your own bot."
- "Only an operator or the host can create bots."
- "You don't have permission to open this bot's inventory."
- "Lia is too far away to open (20 blocks)."

## How a fren gets an owner

- **Spawned:** the operator or host who types `/bot spawn` becomes the owner. A fren spawned from the server console has no owner.
- **Recruited (Questing mode):** the player who recruits it becomes the owner. Each world has one recruited companion. Someone else can't recruit a companion that already has an owner: "This companion already has an owner and cannot be recruited by you."

Ownership is kept by the fren's name. It stays when the owner is offline.

### Changing or removing ownership

Only operators and the host can do this:

| Command | What it does |
|---|---|
| `/bot config owner <bot> <player>` | Gives the fren to another player, who has to be online: "Set owner of Lia to Sam" |
| `/bot forget <bot>` | Removes the fren completely, with its inventory and its owner |

Players can't hand frens to each other themselves, and there is no way to give up a fren but keep it in the world.

## How many frens

There is no limit per player. Each fren takes a player slot on the server, so frens count towards the server's player limit. Frens that spawn automatically at startup always leave one slot free.

## How frens treat other players

- **They never attack players.** A fren holds its fire when a player is in the way of a shot.
- If you hit a fren, it complains ("Hey! Watch it!", "Friendly fire!") but doesn't hit back. If it hits you by accident, it apologises.
- They only follow and take orders from their owner, operators and the host.
- They pick up items lying near them while they work, whoever dropped them.
- They can use a chest near their work area, whoever placed it. Before taking from a chest they ask their owner. They never take from a chest another player's frens placed. See [Storage and chests](Storage.md).

## Sharing bases and walls

### Bases

You see and can change your own bases. Everyone sees server bases such as Spawn. Operators and the host see and can change all bases. Any player who owns a fren can map a village. Only operators and the host can change who owns a base. See [Bases and home](Bases-and-Home.md).

**Allies** see each other's bases and can build bases next to each other. Without an alliance, a new base can't overlap another player's base: "Overlaps Sam's base 'farm'. Move at least 12 blocks farther (or ally with Sam) and try again."

Alliances are mutual. Both players have to agree:

```
/frens ally invite <player>     invite, or accept an invitation
/frens ally revoke <player>     end an alliance, or decline
/frens ally list
```

Any player can use these; they don't need an operator. The invited player gets **[Accept]** and **[Decline]** buttons in chat. `/bot ally` does the same for operators and the host.

### Walls

A fortified wall belongs to the owner of the fren that built it. In the Bases screen, select the wall:

| Button | What it does | Who |
|---|---|---|
| **Claim Wall** | Takes a wall that has no owner | Only an operator or the host |
| **Unclaim** | Gives up the wall | Its owner, an operator or the host |
| **Permit** | Lets another player's frens work on it | Its owner, an operator or the host |
| **Revoke** | Takes that back | Its owner, an operator or the host |

A permitted player can resume, patch and dig the moat. Only the owner, an operator or the host can expand it. Walls are only managed in the Overworld. See [Fortifying a village](Fortify.md).

## When the owner is offline

- **Sunset:** a fren with Auto Home @ Sunset on normally asks its owner first. With the owner offline, it goes home without asking.
- **Supplies:** a fren only asks to take from a chest when its owner is online, in the same dimension and within 32 blocks. Otherwise it takes nothing, unless the owner pressed "Always" for that chest.
- **Fast travel:** fast travel messages are kept and shown when the owner comes back.

## Server settings

Operators and the host can set server-wide options:

- **Player Permissions** (Admin tab): defaults for every player, plus overrides for single players. They cover screens such as Bot Controls and the skin chooser, casting spells (Open Spells), gameplay tips, the Wizard's Tome, and recruitment management.
- `/bot recruit mode_access allow <player>` lets a player who isn't an operator choose the world's mode. `revoke`, `clear` and `status` manage the list.
- `/bot config globalTeleport on|off|clear` sets teleport during skills for every fren.

## Dedicated servers

- There is no host, so only operators can type `/bot`.
- Frens set to spawn automatically appear when the server starts. On a singleplayer or LAN world they wait for the first player to join.
- On an online-mode server a fren gets its skin and ID from Mojang by name, so it appears a moment after you spawn it. You can't spawn a fren with the name of a real player who is online.
- [Carpet](https://modrinth.com/mod/carpet) is optional. Frens works without it.
- For AI chat, Ollama runs on the server machine. See [AI chat](AI-Chat.md#on-a-server).

## Common surprises

| What you see | Why |
|---|---|
| My friend can't use my fren | Only the owner, operators and the host can. Ask an operator to give it to them with `/bot config owner`. |
| `/bot` says I don't have permission | Typed `/bot` commands need an operator or the host. Use the buttons in the fren's menus. |
| I can't see my friend's base | Bases are private unless you are allies. |
| The server is full with fewer people than usual | Each fren takes a player slot. |
| My fren picked up someone else's items | Frens pick up items near where they work, whoever dropped them. |
